package com.safewe.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.safewe.data.remote.Pairing
import com.safewe.data.remote.PairingRepository
import com.safewe.transfer.CaptureRequest
import com.safewe.transfer.CaptureType
import com.safewe.transfer.FirestorePreviewTransferManager
import com.safewe.transfer.PreviewCodec
import com.safewe.transfer.RequestVerifier
import com.safewe.transfer.TransferStatus
import com.safewe.transfer.Verdict
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
class CameraSessionService : LifecycleService() {
    @Inject lateinit var coordinator: CaptureCoordinator
    @Inject lateinit var transfers: FirestorePreviewTransferManager
    @Inject lateinit var pairing: PairingRepository
    @Inject lateinit var auth: FirebaseAuth

    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var session: Job? = null
    private var accountListener: FirebaseAuth.AuthStateListener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) {
            session?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        val uid = auth.currentUser?.uid
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Camera sharing", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (intent?.action != START || uid == null || !canCapture()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Your paired parent can request a photo."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }

        val selector = if (intent.getBooleanExtra(FRONT, false)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        accountListener = FirebaseAuth.AuthStateListener {
            if (it.currentUser?.uid != uid) {
                session?.cancel()
                stopSelf()
            }
        }.also(auth::addAuthStateListener)
        session = lifecycleScope.launch {
            val linked = withTimeoutOrNull(15_000) {
                try {
                    pairing.currentPairing()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            if (linked == null || linked.status != "ACTIVE" || linked.childUid != uid) {
                stopSelf()
                return@launch
            }
            launch {
                pairing.observeMyPairing().collect { current ->
                    if (current != linked) {
                        session?.cancel()
                        stopSelf()
                    }
                }
            }
            coordinator.setSessionActive(true)
            coordinator.startListening(OWNER, this)
            coordinator.pending
                .map { requests -> requests.firstOrNull { it.type == CaptureType.CAMERA } }
                .filterNotNull()
                .distinctUntilChangedBy { it.requestId }
                .collect { request ->
                    if (!canCapture() || auth.currentUser?.uid != uid) {
                        stopSelf()
                        return@collect
                    }
                    captureAndSend(uid, request, selector, linked)
                }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureAndSend(
        uid: String,
        request: CaptureRequest,
        selector: CameraSelector,
        linked: Pairing
    ) {
        try {
            val now = System.currentTimeMillis()
            if (request.expiresAt <= now) {
                transfers.setRequestStatus(uid, request.requestId, TransferStatus.EXPIRED)
                return
            }
            if (RequestVerifier.verify(request, uid, linked.parentSigningKey, now) != Verdict.OK) {
                transfers.setRequestStatus(uid, request.requestId, TransferStatus.DENIED)
                return
            }
            transfers.setRequestStatus(uid, request.requestId, TransferStatus.CAPTURING)
            val bitmap = withTimeoutOrNull(15_000) {
                val provider = ProcessCameraProvider.getInstance(this@CameraSessionService).await()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                checkSharingActive(uid)
                check(request.expiresAt > System.currentTimeMillis())
                notifications.notify(NOTIFICATION_ID, notification("Taking a photo for your paired parent."))
                try {
                    provider.bindToLifecycle(this@CameraSessionService, selector, capture)
                    takePhoto(capture)
                } finally {
                    provider.unbind(capture)
                }
            } ?: error("Camera timed out")
            val image = try {
                transfers.setRequestStatus(uid, request.requestId, TransferStatus.COMPRESSING)
                withContext(Dispatchers.Default) { PreviewCodec.compress(bitmap) }
            } finally {
                bitmap.recycle()
            }
            transfers.setRequestStatus(uid, request.requestId, TransferStatus.UPLOADING)
            check(pairing.currentPairing() == linked)
            checkSharingActive(uid)
            transfers.sendPreview(uid, request, image, linked)
            notifications.notify(NOTIFICATION_ID, notification("Photo sent. Camera sharing is still on."))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            transfers.setRequestStatus(uid, request.requestId, TransferStatus.FAILED)
            if (canCapture()) {
                notifications.notify(NOTIFICATION_ID, notification("Photo failed. Camera sharing is still on."))
            }
        } finally {
            coordinator.resolve(request)
        }
    }

    private suspend fun checkSharingActive(uid: String) {
        kotlin.coroutines.coroutineContext.ensureActive()
        check(canCapture() && auth.currentUser?.uid == uid)
    }

    private suspend fun takePhoto(capture: ImageCapture): Bitmap = suspendCancellableCoroutine { continuation ->
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    if (!continuation.isActive) return
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    val photo = if (rotation == 0) raw else try {
                        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                            Matrix().apply { postRotate(rotation.toFloat()) }, true)
                    } catch (e: Exception) {
                        raw.recycle()
                        throw e
                    }
                    if (photo !== raw) raw.recycle()
                    continuation.resume(photo) { _, unused, _ -> unused.recycle() }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        })
    }

    private fun canCapture(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("SafeWe camera sharing is on")
        .setContentText(message)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setDeleteIntent(stopAction())
        .addAction(0, "Stop sharing", stopAction())
        .build()

    private fun stopAction() = PendingIntent.getService(
        this, NOTIFICATION_ID, Intent(this, CameraSessionService::class.java).setAction(STOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    override fun onDestroy() {
        session?.cancel()
        accountListener?.let(auth::removeAuthStateListener)
        if (session != null) {
            coordinator.stopListening(OWNER)
            coordinator.setSessionActive(false)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val START = "com.safewe.camera.START"
        private const val STOP = "com.safewe.camera.STOP"
        private const val FRONT = "front"
        private const val OWNER = "camera"
        internal const val CHANNEL = "safewe_camera_sharing"
        private const val NOTIFICATION_ID = 2003

        fun start(context: Context, front: Boolean) {
            ContextCompat.startForegroundService(
                context, Intent(context, CameraSessionService::class.java)
                    .setAction(START).putExtra(FRONT, front)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraSessionService::class.java))
        }
    }
}
