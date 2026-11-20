package com.safewe.screen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.safewe.SafeWeApp
import com.safewe.camera.CaptureCoordinator
import com.safewe.data.remote.AuthRepository
import com.safewe.data.remote.PairingRepository
import com.safewe.transfer.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Child-started screen-sharing session (explicit MediaProjection consent, ongoing notification).
 * While it runs, a parent's SCREENSHOT request is answered without another approval dialog, but each one
 * shows a heads-up notification with a Cancel button for [COUNTDOWN_MS] first.
 * START_NOT_STICKY: the consent token cannot survive process death, so a new session needs new consent.
 */
@AndroidEntryPoint
class ScreenShareService : Service() {
    @Inject lateinit var coordinator: CaptureCoordinator
    @Inject lateinit var transfers: FirestorePreviewTransferManager
    @Inject lateinit var pairing: PairingRepository
    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var state: ScreenShareState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: ScreenCaptureSession? = null
    private val declined = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_CANCEL -> { intent.getStringExtra(EXTRA_REQUEST_ID)?.let(::cancelRequest); return START_NOT_STICKY }
        }
        if (session != null) return START_NOT_STICKY // already sharing

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null || code != android.app.Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }

        // Android 14+: the mediaProjection foreground service must be running BEFORE getMediaProjection().
        ServiceCompat.startForeground(this, ONGOING_ID, ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val projection = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }.getOrNull()
        val created = projection?.let {
            runCatching { ScreenCaptureSession(this, it) { onProjectionStopped() } }.getOrNull()
        }
        if (created == null) { state.set(false, "Screen sharing could not be started."); stopSelf(); return START_NOT_STICKY }
        session = created

        state.set(true)
        coordinator.setScreenSessionActive(true)
        coordinator.startListening("screen", scope)
        scope.launch {
            coordinator.pending
                .map { list -> list.firstOrNull { it.type == CaptureType.SCREENSHOT } }
                .distinctUntilChangedBy { it?.requestId }
                .collectLatest { req -> if (req != null) process(req) }
        }
        return START_NOT_STICKY
    }

    private suspend fun process(req: CaptureRequest) {
        val uid = auth.uid ?: return
        val s = session ?: return
        try {
            val p = pairing.currentPairing()
            if (p == null || p.status != "ACTIVE") { coordinator.resolve(req); return }
            if (req.expiresAt < System.currentTimeMillis()) {
                transfers.setRequestStatus(uid, req.requestId, TransferStatus.EXPIRED); coordinator.resolve(req); return
            }
            // If the child cannot be told, we do not capture.
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                transfers.setRequestStatus(uid, req.requestId, TransferStatus.DENIED); coordinator.resolve(req); return
            }
            showHeadsUp(req.requestId)
            delay(COUNTDOWN_MS)
            hideHeadsUp()

            transfers.setRequestStatus(uid, req.requestId, TransferStatus.CAPTURING)
            val bmp = s.capture()
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.COMPRESSING)
            val img = withContext(Dispatchers.Default) { PreviewCodec.compress(bmp).also { bmp.recycle() } }
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.UPLOADING)
            transfers.sendPreview(uid, req, img, p)
            coordinator.resolve(req)
        } catch (e: CancellationException) {
            hideHeadsUp()
            if (req.requestId !in declined) withContext(NonCancellable) {
                transfers.setRequestStatus(uid, req.requestId, TransferStatus.WAITING_FOR_SESSION)
            }
            throw e
        } catch (e: Exception) {
            hideHeadsUp()
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.FAILED)
            coordinator.resolve(req)
        }
    }

    private fun cancelRequest(requestId: String) {
        declined.add(requestId)
        val uid = auth.uid
        coordinator.pending.value.firstOrNull { it.requestId == requestId }?.let { coordinator.resolve(it) }
        hideHeadsUp()
        if (uid != null) scope.launch { transfers.setRequestStatus(uid, requestId, TransferStatus.DENIED) }
    }

    private fun onProjectionStopped() {
        state.set(false, "Screen sharing ended. Android or the lock screen stopped it. Start a new session to continue.")
        stopSelf()
    }

    private fun stopIntent() = PendingIntent.getService(
        this, 2, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
    )

    private fun ongoing(): Notification =
        NotificationCompat.Builder(this, SafeWeApp.SCREEN_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("SafeWe screen sharing is on")
            .setContentText("Your parent can request a screenshot. You can cancel each one.")
            .setOngoing(true)
            .addAction(0, "Stop sharing", stopIntent())
            .build()

    @SuppressLint("MissingPermission")
    private fun showHeadsUp(requestId: String) {
        val cancel = PendingIntent.getService(
            this, 3, Intent(this, ScreenShareService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_REQUEST_ID, requestId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, SafeWeApp.REQUEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Sending a screenshot to your parent")
            .setContentText("It will be sent in ${COUNTDOWN_MS / 1000} seconds. Tap Cancel to stop it.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(COUNTDOWN_MS + 1000)
            .addAction(0, "Cancel", cancel)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(HEADS_UP_ID, n) }
    }

    private fun hideHeadsUp() = NotificationManagerCompat.from(this).cancel(HEADS_UP_ID)

    override fun onDestroy() {
        session?.release(); session = null
        coordinator.setScreenSessionActive(false)
        coordinator.stopListening("screen")
        if (state.active.value) state.set(false)
        hideHeadsUp()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val COUNTDOWN_MS = 5_000L
        private const val ONGOING_ID = 3001
        private const val HEADS_UP_ID = 3002
        private const val ACTION_STOP = "com.safewe.SCREEN_STOP"
        private const val ACTION_CANCEL = "com.safewe.SCREEN_CANCEL"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_REQUEST_ID = "request_id"

        fun start(ctx: Context, resultCode: Int, data: Intent) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, ScreenShareService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
        )
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScreenShareService::class.java)) }
    }
}
