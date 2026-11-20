package com.safewe.transfer

object CaptureLabels {
    /** Parent-facing text for a request status. */
    fun parent(status: String, type: String = CaptureType.CAMERA): String = when (status) {
        TransferStatus.REQUESTED -> "Waiting for your child's device…"
        TransferStatus.WAITING_FOR_SESSION ->
            if (type == CaptureType.SCREENSHOT) "Waiting for your child to start screen sharing…"
            else "Waiting for your child to start camera sharing…"
        TransferStatus.CAPTURING -> if (type == CaptureType.SCREENSHOT) "Capturing the screen…" else "Taking the photo…"
        TransferStatus.COMPRESSING -> "Preparing the image…"
        TransferStatus.UPLOADING, TransferStatus.READY -> "Receiving the image…"
        TransferStatus.DENIED -> "Your child declined this request (or notifications are off on their device)."
        TransferStatus.EXPIRED -> "The request expired before it could be completed."
        TransferStatus.FAILED -> "Something went wrong. Please try again."
        else -> status
    }

    val TERMINAL = setOf(TransferStatus.DENIED, TransferStatus.EXPIRED, TransferStatus.FAILED)

    /** Status the child writes when a request fails verification. null = accepted. */
    fun forVerdict(v: Verdict): String? = when (v) {
        Verdict.OK, Verdict.DUPLICATE -> null
        Verdict.EXPIRED -> TransferStatus.EXPIRED
        else -> TransferStatus.DENIED
    }
}
