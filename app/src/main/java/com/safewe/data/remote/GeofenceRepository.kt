package com.safewe.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeofenceRepository @Inject constructor(private val db: FirebaseFirestore, private val quota: com.safewe.transfer.QuotaTracker) {
    private fun fences(childUid: String) = db.collection("devices").document(childUid).collection("geofences")
    private fun alerts(childUid: String) = db.collection("devices").document(childUid).collection("alerts")

    // Parent
    suspend fun save(childUid: String, cfg: GeofenceConfig) {
        quota.add(writes = 1)
        val id = cfg.id.ifBlank { UUID.randomUUID().toString() }
        fences(childUid).document(id).set(cfg.copy(id = id, createdAt = System.currentTimeMillis())).await()
    }
    suspend fun delete(childUid: String, id: String) { quota.add(deletes = 1); fences(childUid).document(id).delete().await() }
    suspend fun deleteAlert(childUid: String, id: String) { alerts(childUid).document(id).delete().await() }

    fun observeAlerts(childUid: String): Flow<List<GeofenceAlert>> = callbackFlow {
        val reg = alerts(childUid).addSnapshotListener { s, _ ->
            trySend(s?.toObjects(GeofenceAlert::class.java).orEmpty().sortedByDescending { it.timestamp })
        }
        awaitClose { reg.remove() }
    }

    // Both
    fun observe(childUid: String): Flow<List<GeofenceConfig>> = callbackFlow {
        val reg = fences(childUid).addSnapshotListener { s, _ ->
            trySend(s?.toObjects(GeofenceConfig::class.java).orEmpty())
        }
        awaitClose { reg.remove() }
    }

    // Child
    suspend fun fetch(childUid: String, id: String): GeofenceConfig? =
        fences(childUid).document(id).get().await().toObject(GeofenceConfig::class.java)

    suspend fun publishAlert(childUid: String, alert: GeofenceAlert) {
        alerts(childUid).document(alert.id).set(alert).await()
    }

    /** Child removes alerts older than [maxAgeMs] so the cloud copy never piles up. */
    suspend fun pruneAlerts(childUid: String, maxAgeMs: Long = 7 * 24 * 3_600_000L) {
        val old = alerts(childUid).whereLessThan("timestamp", System.currentTimeMillis() - maxAgeMs).get().await().documents
        old.forEach { it.reference.delete().await() }
        quota.add(reads = old.size.toLong(), deletes = old.size.toLong())
    }

    /** Child wipes what the parent configured when the pairing ends. */
    suspend fun clearAll(childUid: String) {
        (fences(childUid).get().await().documents + alerts(childUid).get().await().documents).forEach { it.reference.delete().await() }
    }
}
