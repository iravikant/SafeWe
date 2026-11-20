package com.safewe.transfer

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.safewe.data.local.ProcessedRequestDao
import com.safewe.data.local.ProcessedRequestEntity
import com.safewe.data.remote.AuthRepository
import com.safewe.data.remote.Pairing
import com.safewe.security.B64
import com.safewe.security.DeviceIdentity
import com.safewe.security.Ecc
import com.safewe.security.PreviewChunker
import com.safewe.security.TransferCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporary, encrypted image/screenshot transfer over Firestore (no Storage, no server, no public URLs).
 *
 *  child : image -> (compress, done by caller) -> ECDH+HKDF+AES-256-GCM -> 64 KiB chunks (bytes fields)
 *          -> signed metadata -> chunks -> status READY
 *  parent: verify signature + expiry -> download chunks -> verify count + SHA-256 -> decrypt -> CONSUMED -> delete
 *  both  : sweepExpired() removes anything past its 5 minute lifetime (manual cleanup, no paid TTL).
 */
@Singleton
class FirestorePreviewTransferManager @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: AuthRepository,
    private val identity: DeviceIdentity,
    private val processed: ProcessedRequestDao,
    private val quota: QuotaTracker
) {
    private fun requests(childUid: String) = db.collection("devices").document(childUid).collection("captureRequests")
    private fun transfers(childUid: String) = db.collection("devices").document(childUid).collection("captureTransfers")

    // =============================== PARENT ===============================

    /** Creates a signed, short-lived request. The child verifies the signature before doing anything. */
    suspend fun requestCapture(childUid: String, type: String): String {
        val parentUid = requireNotNull(auth.uid)
        val now = System.currentTimeMillis()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.let(B64::enc)
        val unsigned = CaptureRequest(UUID.randomUUID().toString(), type, TransferStatus.REQUESTED, nonce, "", now, now + TRANSFER_TTL_MS)
        val sig = Ecc.sign(identity.load(parentUid).signing.private, Canon.request(childUid, unsigned))
        val req = unsigned.copy(signature = B64.enc(sig))
        requests(childUid).document(req.requestId).set(req).await()
        return req.requestId
    }

    fun observeRequest(childUid: String, requestId: String): Flow<CaptureRequest?> = callbackFlow {
        val reg = requests(childUid).document(requestId).addSnapshotListener { s, _ -> trySend(s?.toObject(CaptureRequest::class.java)) }
        awaitClose { reg.remove() }
    }

    /** Emits the transfer for [requestId] whenever its metadata changes. */
    fun observeTransfer(childUid: String, requestId: String): Flow<TransferMeta?> = callbackFlow {
        val reg = transfers(childUid).whereEqualTo("requestId", requestId).addSnapshotListener { s, _ ->
            trySend(s?.toObjects(TransferMeta::class.java)?.firstOrNull())
        }
        awaitClose { reg.remove() }
    }

    /** Download -> verify -> decrypt -> acknowledge -> delete. Plaintext only ever exists in memory here. */
    suspend fun receivePreview(childUid: String, meta: TransferMeta, pairing: Pairing): ReceivedPreview {
        val parentUid = requireNotNull(auth.uid)
        val now = System.currentTimeMillis()
        require(meta.status == TransferStatus.READY) { "Transfer not ready" }
        require(meta.expiresAt > now) { "Transfer expired" }
        require(meta.encrypted) { "Unencrypted transfers are refused" }
        require(meta.chunkCount in 1..MAX_CHUNKS) { "Bad chunk count" }

        // The child's identity: signature must verify against the key published in the pairing.
        val childSigning = Ecc.publicFrom(B64.dec(pairing.childSigningKey.also { require(it.isNotBlank()) { "Child key missing" } }))
        require(Ecc.verify(childSigning, Canon.meta(meta), B64.dec(meta.signature))) { "Bad transfer signature" }

        val cost = QuotaMath.parentCost(meta.chunkCount)
        require(quota.canAfford(cost)) { "Daily preview budget reached" }

        val ref = transfers(childUid).document(meta.transferId)
        retry { ref.update("status", TransferStatus.DOWNLOADING).await() }
        try {
            val docs = retry { ref.collection("chunks").orderBy("index", Query.Direction.ASCENDING).get().await() }.documents
            val chunks = docs.map { requireNotNull(it.getBlob("data")) { "Chunk missing data" }.toBytes() }
            val ciphertext = PreviewChunker.join(chunks, meta.chunkCount, meta.sha256) // count + SHA-256 check
            val plain = TransferCrypto.open(
                ciphertext, B64.dec(meta.ephemeralPub),
                identity.load(parentUid).agreement.private, meta.transferId,
                TransferCrypto.aad(meta.transferId, meta.requestId, meta.type)
            )
            retry { ref.update("status", TransferStatus.CONSUMED).await() }
            deleteTransfer(childUid, meta.transferId)
            quota.record(cost)
            return ReceivedPreview(plain, meta.mime, meta.requestId, meta.type)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            runCatching { ref.update("status", TransferStatus.FAILED).await() }
            throw e
        }
    }

    // ================================ CHILD ================================

    fun observeRequests(childUid: String): Flow<CaptureRequest> = callbackFlow {
        val reg = requests(childUid).whereEqualTo("status", TransferStatus.REQUESTED).addSnapshotListener { s, _ ->
            s?.documentChanges?.filter { it.type == DocumentChange.Type.ADDED }
                ?.forEach { trySend(it.document.toObject(CaptureRequest::class.java)) }
        }
        awaitClose { reg.remove() }
    }

    suspend fun setRequestStatus(childUid: String, requestId: String, status: String) {
        runCatching { requests(childUid).document(requestId).update("status", status).await() }
    }

    /**
     * Signature + expiry + type + idempotency. A request ID is accepted at most once, ever
     * (persisted in Room), which blocks replays and duplicate snapshot deliveries.
     */
    suspend fun verifyRequest(childUid: String, req: CaptureRequest, pairing: Pairing): Verdict {
        if (pairing.status != "ACTIVE") return Verdict.NO_KEYS
        val v = RequestVerifier.verify(req, childUid, pairing.parentSigningKey, System.currentTimeMillis())
        if (v != Verdict.OK) return v
        val inserted = processed.insert(ProcessedRequestEntity(req.requestId, System.currentTimeMillis()))
        processed.deleteOlderThan(System.currentTimeMillis() - 24 * 3_600_000L)
        return if (inserted == -1L) Verdict.DUPLICATE else Verdict.OK
    }

    /** Encrypts and uploads [image] for the parent named in [pairing]. Returns the transfer ID. */
    suspend fun sendPreview(childUid: String, req: CaptureRequest, image: CompressedImage, pairing: Pairing): String {
        require(pairing.parentAgreementKey.isNotBlank()) { "Parent key missing" }
        val transferId = UUID.randomUUID().toString()
        val sealed = TransferCrypto.seal(
            image.bytes, B64.dec(pairing.parentAgreementKey), transferId,
            TransferCrypto.aad(transferId, req.requestId, req.type)
        )
        val chunks = PreviewChunker.split(sealed.ciphertext, PreviewChunker.DEFAULT_CHUNK)
        val cost = QuotaMath.childCost(chunks.size)
        require(quota.canAfford(cost)) { "Daily preview budget reached" }

        val now = System.currentTimeMillis()
        val unsigned = TransferMeta(
            transferId, req.requestId, req.type, TransferStatus.UPLOADING, chunks.size, sealed.ciphertext.size,
            PreviewChunker.sha256(sealed.ciphertext), image.mime, B64.enc(sealed.ephemeralPublic), "", true, now, now + TRANSFER_TTL_MS
        )
        val meta = unsigned.copy(signature = B64.enc(Ecc.sign(identity.load(childUid).signing.private, Canon.meta(unsigned))))
        val ref = transfers(childUid).document(transferId)

        try {
            retry { ref.set(meta).await() }
            chunks.mapIndexed { i, c -> ref.collection("chunks").document("%04d".format(i)) to mapOf("index" to i, "data" to Blob.fromBytes(c)) }
                .chunked(UPLOAD_BATCH).forEach { group ->
                    retry { db.runBatch { b -> group.forEach { (r, d) -> b.set(r, d) } }.await() }
                }
            retry { ref.update("status", TransferStatus.READY).await() }
            setRequestStatus(childUid, req.requestId, TransferStatus.READY)
            quota.record(cost)
            return transferId
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            runCatching { deleteTransfer(childUid, transferId) }
            setRequestStatus(childUid, req.requestId, TransferStatus.FAILED)
            throw e
        }
    }

    // ================================ BOTH =================================

    /** Manual replacement for paid TTL: deletes transfers (and their chunks) older than their expiry. */
    suspend fun sweepExpired(childUid: String) {
        val expired = transfers(childUid).whereLessThan("expiresAt", System.currentTimeMillis()).get().await()
        expired.documents.forEach { runCatching { deleteTransfer(childUid, it.id) } }
        val oldRequests = requests(childUid).whereLessThan("expiresAt", System.currentTimeMillis()).get().await()
        oldRequests.documents.forEach { runCatching { it.reference.delete().await() } }
    }

    suspend fun deleteRequest(childUid: String, requestId: String) {
        runCatching { requests(childUid).document(requestId).delete().await() }
    }

    private suspend fun deleteTransfer(childUid: String, transferId: String) {
        val ref = transfers(childUid).document(transferId)
        val chunkDocs = ref.collection("chunks").get().await().documents
        chunkDocs.chunked(400).forEach { grp -> db.runBatch { b -> grp.forEach { b.delete(it.reference) } }.await() }
        ref.delete().await()
    }

    private suspend fun <T> retry(times: Int = 3, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(times) { attempt ->
            try { return block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { last = e; delay(com.safewe.transfer.Backoff.delayMs(attempt)) }
        }
        throw checkNotNull(last)
    }

    companion object {
        const val TRANSFER_TTL_MS = 5 * 60_000L
        private const val UPLOAD_BATCH = 8   // 8 x 64 KiB ~ 512 KiB per commit
        private const val MAX_CHUNKS = 64    // 4 MiB ceiling, far above the 150 KB target
    }
}
