package com.safewe.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pairing flow:
 *  1. Parent creates a one-time 8-digit code (valid 10 min)  -> pairingCodes/{code}
 *  2. Child enters the code and EXPLICITLY approves on the child device.
 *  3. Child writes pairings/{childUid}; Firestore rules verify the code.
 *  4. Child can revoke at any time.
 */
@Singleton
class PairingRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: AuthRepository,
    private val identity: com.safewe.security.DeviceIdentity
) {
    suspend fun createCode(): String {
        val parentUid = requireNotNull(auth.uid)
        val code = (SecureRandom().nextInt(90_000_000) + 10_000_000).toString()
        val now = System.currentTimeMillis()
        db.collection("pairingCodes").document(code).set(
            mapOf("parentUid" to parentUid, "createdAt" to now, "expiresAt" to now + CODE_TTL_MS)
        ).await()
        return code
    }

    /** Call ONLY after the child user has tapped "Approve". */
    suspend fun approveAndPair(code: String) {
        val childUid = requireNotNull(auth.uid)
        val snap = db.collection("pairingCodes").document(code).get().await()
        require(snap.exists()) { "Invalid code" }
        require((snap.getLong("expiresAt") ?: 0) > System.currentTimeMillis()) { "Code expired" }
        val parentUid = snap.getString("parentUid") ?: error("Invalid code")
        val keys = identity.load(childUid) // child public keys are published with the pairing
        db.collection("pairings").document(childUid).set(
            Pairing(childUid, parentUid, "ACTIVE", code, System.currentTimeMillis(),
                childAgreementKey = keys.agreementPublicB64, childSigningKey = keys.signingPublicB64)
        ).await()
        db.collection("pairingCodes").document(code).delete().await() // one-time use
    }

    /** Parent publishes ITS public keys once a child has paired. Rules allow only these two fields. */
    suspend fun publishParentKeys(childUid: String) {
        val keys = identity.load(requireNotNull(auth.uid))
        db.collection("pairings").document(childUid).update(
            mapOf("parentAgreementKey" to keys.agreementPublicB64, "parentSigningKey" to keys.signingPublicB64)
        ).await()
    }

    suspend fun currentPairing(): Pairing? {
        val uid = auth.uid ?: return null
        return db.collection("pairings").document(uid).get().await().toObject(Pairing::class.java)
    }

    /** Parent ends monitoring. Rules stop honouring the parent immediately; the child's phone also cleans up. */
    suspend fun unpair(childUid: String) {
        db.collection("pairings").document(childUid).delete().await()
    }

    suspend fun revoke() {
        val uid = requireNotNull(auth.uid)
        db.collection("pairings").document(uid).delete().await()
        identity.wipe(uid) // destroy this device's keys; re-pairing generates new ones
    }

    /** Child side: who is monitoring me? */
    fun observeMyPairing(): Flow<Pairing?> = callbackFlow {
        val uid = auth.uid
        if (uid == null) { trySend(null); close(); return@callbackFlow }
        val reg = db.collection("pairings").document(uid).addSnapshotListener { s, _ ->
            trySend(s?.toObject(Pairing::class.java))
        }
        awaitClose { reg.remove() }
    }

    /** Parent side: children paired with me. */
    fun observeChildren(): Flow<List<Pairing>> = callbackFlow {
        val uid = auth.uid
        if (uid == null) { trySend(emptyList()); close(); return@callbackFlow }
        val reg = db.collection("pairings").whereEqualTo("parentUid", uid)
            .addSnapshotListener { s, _ -> trySend(s?.toObjects(Pairing::class.java).orEmpty()) }
        awaitClose { reg.remove() }
    }

    companion object { const val CODE_TTL_MS = 10 * 60 * 1000L }
}
