package com.safewe.transfer

import com.safewe.security.B64
import com.safewe.security.Ecc

object TransferStatus {
    const val REQUESTED = "REQUESTED"
    const val WAITING_FOR_SESSION = "WAITING_FOR_SESSION"
    const val CAPTURING = "CAPTURING"
    const val COMPRESSING = "COMPRESSING"
    const val UPLOADING = "UPLOADING"
    const val READY = "READY"
    const val DOWNLOADING = "DOWNLOADING"
    const val COMPLETED = "COMPLETED"
    const val CONSUMED = "CONSUMED"
    const val DENIED = "DENIED"
    const val EXPIRED = "EXPIRED"
    const val FAILED = "FAILED"
}

object CaptureType { const val CAMERA = "CAMERA"; const val SCREENSHOT = "SCREENSHOT" }

data class CaptureRequest(
    val requestId: String = "",
    val type: String = "",
    val status: String = TransferStatus.REQUESTED,
    val nonce: String = "",
    val signature: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0
)

data class TransferMeta(
    val transferId: String = "",
    val requestId: String = "",
    val type: String = "",
    val status: String = TransferStatus.UPLOADING,
    val chunkCount: Int = 0,
    val totalBytes: Int = 0,
    val sha256: String = "",
    val mime: String = "",
    val ephemeralPub: String = "",
    val signature: String = "",
    val encrypted: Boolean = true,
    val createdAt: Long = 0,
    val expiresAt: Long = 0
)

class ReceivedPreview(val bytes: ByteArray, val mime: String, val requestId: String, val type: String)

/** Canonical strings that get signed. Field order is fixed; '|' never appears in the values. */
object Canon {
    fun request(childUid: String, r: CaptureRequest) =
        "${r.requestId}|${r.type}|${r.createdAt}|${r.expiresAt}|${r.nonce}|$childUid".toByteArray()

    fun meta(m: TransferMeta) =
        "${m.transferId}|${m.requestId}|${m.type}|${m.chunkCount}|${m.totalBytes}|${m.sha256}|${m.mime}|${m.ephemeralPub}|${m.expiresAt}".toByteArray()
}

enum class Verdict { OK, EXPIRED, BAD_SIGNATURE, UNKNOWN_TYPE, NO_KEYS, DUPLICATE }

object RequestVerifier {
    const val MAX_TTL_MS = 10 * 60_000L
    private const val CLOCK_SKEW_MS = 60_000L
    private val TYPES = setOf(CaptureType.CAMERA, CaptureType.SCREENSHOT)

    /** Pure check; duplicate detection (idempotency) is done separately against Room. */
    fun verify(req: CaptureRequest, childUid: String, parentSigningKeyB64: String, now: Long): Verdict {
        if (parentSigningKeyB64.isBlank()) return Verdict.NO_KEYS
        if (req.type !in TYPES) return Verdict.UNKNOWN_TYPE
        if (req.expiresAt < now || req.createdAt > now + CLOCK_SKEW_MS || req.expiresAt - req.createdAt > MAX_TTL_MS) return Verdict.EXPIRED
        val pub = runCatching { Ecc.publicFrom(B64.dec(parentSigningKeyB64)) }.getOrNull() ?: return Verdict.NO_KEYS
        val sig = runCatching { B64.dec(req.signature) }.getOrNull() ?: return Verdict.BAD_SIGNATURE
        return if (Ecc.verify(pub, Canon.request(childUid, req), sig)) Verdict.OK else Verdict.BAD_SIGNATURE
    }
}
