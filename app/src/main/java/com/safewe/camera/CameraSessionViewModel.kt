package com.safewe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.data.remote.AuthRepository
import com.safewe.data.remote.PairingRepository
import com.safewe.transfer.CaptureRequest
import com.safewe.transfer.FirestorePreviewTransferManager
import com.safewe.transfer.PreviewCodec
import com.safewe.transfer.TransferStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException

data class CameraUi(
    val active: Boolean = false,
    val current: CaptureRequest? = null,
    val countdown: Int = 0,
    val message: String = "Waiting for a request from your parent.",
    val lastResult: String? = null
)

@HiltViewModel
class CameraSessionViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val coordinator: CaptureCoordinator,
    private val transfers: FirestorePreviewTransferManager,
    private val pairing: PairingRepository,
    private val auth: AuthRepository
) : ViewModel() {

    private val _s = MutableStateFlow(CameraUi())
    val state: StateFlow<CameraUi> = _s

    private val active = MutableStateFlow(false)
    private val ready = MutableStateFlow(false)
    private var imageCapture: ImageCapture? = null
    private val declined = HashSet<String>()

    init {
        viewModelScope.launch {
            combine(
                coordinator.pending.map { list -> list.firstOrNull { it.type == com.safewe.transfer.CaptureType.CAMERA } }.distinctUntilChangedBy { it?.requestId },
                active, ready
            ) { head, a, r -> if (head != null && a && r) head else null }
                .distinctUntilChanged()
                .collectLatest { req ->
                    if (req != null) process(req)
                    else _s.update { it.copy(current = null, countdown = 0, message = IDLE) }
                }
        }
    }

    fun setCapture(c: ImageCapture?) { imageCapture = c; ready.value = c != null }

    /** Active == the camera screen is visible AND resumed. Locking the phone ends the session automatically. */
    fun setActive(a: Boolean) {
        active.value = a
        _s.update { it.copy(active = a) }
        coordinator.setSessionActive(a)
        if (a) coordinator.startListening("camera", viewModelScope) else coordinator.stopListening("camera")
    }

    fun decline() {
        val req = _s.value.current ?: return
        if (_s.value.countdown <= 0) return // too late once the shutter is firing
        declined.add(req.requestId)
        coordinator.resolve(req)
        viewModelScope.launch {
            auth.uid?.let { transfers.setRequestStatus(it, req.requestId, TransferStatus.DENIED) }
            _s.update { it.copy(lastResult = "You declined the request.") }
        }
    }

    private suspend fun process(req: CaptureRequest) {
        val uid = auth.uid ?: return
        try {
            val p = pairing.currentPairing()
            if (p == null || p.status != "ACTIVE") { coordinator.resolve(req); return }
            if (req.expiresAt < System.currentTimeMillis()) {
                transfers.setRequestStatus(uid, req.requestId, TransferStatus.EXPIRED); coordinator.resolve(req); return
            }
            for (i in COUNTDOWN downTo 1) {
                _s.update { it.copy(current = req, countdown = i, message = "Your parent asked for a photo. Taking it in $i…", lastResult = null) }
                delay(1000)
            }
            _s.update { it.copy(countdown = 0, message = "Taking the photo…") }
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.CAPTURING)
            val bmp = capture()
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.COMPRESSING)
            val img = withContext(Dispatchers.Default) { PreviewCodec.compress(bmp).also { bmp.recycle() } }
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.UPLOADING)
            transfers.sendPreview(uid, req, img, p)
            _s.update { it.copy(lastResult = "Photo sent to your parent.") }
            coordinator.resolve(req)
        } catch (e: CancellationException) {
            // Session ended or head changed. Unless the child declined, keep it waiting.
            if (req.requestId !in declined) withContext(NonCancellable) {
                transfers.setRequestStatus(uid, req.requestId, TransferStatus.WAITING_FOR_SESSION)
            }
            throw e
        } catch (e: Exception) {
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.FAILED)
            _s.update { it.copy(lastResult = "The photo could not be sent.") }
            coordinator.resolve(req)
        }
    }

    private suspend fun capture(): Bitmap = suspendCancellableCoroutine { cont ->
        val cap = imageCapture
        if (cap == null) { cont.resumeWithException(IllegalStateException("Camera not ready")); return@suspendCancellableCoroutine }
        cap.takePicture(ContextCompat.getMainExecutor(app), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rot = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    val out = if (rot == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    if (out !== raw) raw.recycle()
                    cont.resume(out)
                } catch (e: Exception) { cont.resumeWithException(e) } finally { image.close() }
            }
            override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
        })
    }

    override fun onCleared() { coordinator.setSessionActive(false); coordinator.stopListening("camera") }

    private companion object {
        const val COUNTDOWN = 3
        const val IDLE = "Waiting for a request from your parent."
    }
}
