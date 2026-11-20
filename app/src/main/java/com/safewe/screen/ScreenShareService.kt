package com.safewe.screen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Captures individual screenshots during a child-approved MediaProjection session. */
@AndroidEntryPoint
class ScreenShareService : Service() {
    @Inject lateinit var coordinator: CaptureCoordinator
    @Inject lateinit var transfers: FirestorePreviewTransferManager
    @Inject lateinit var pairing: PairingRepository
    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var state: ScreenShareState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: ScreenCaptureSession? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { endSession(); return START_NOT_STICKY }
        }
        if (session != null) return START_NOT_STICKY // already sharing

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null || code != android.app.Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }

        if (!noticesEnabled(this)) {
            state.set(false, "Enable SafeWe screen sharing notifications first.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+: the mediaProjection foreground service must be running BEFORE getMediaProjection().
        val started = runCatching {
            ServiceCompat.startForeground(this, ONGOING_ID, ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }.isSuccess
        if (!started) {
            state.set(false, "Screen sharing could not be started.")
            stopSelf()
            return START_NOT_STICKY
        }

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
                .filterNotNull()
                .distinctUntilChangedBy { it.requestId }
                .collect { req -> process(req) }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun process(req: CaptureRequest) {
        val s = session ?: return
        val uid = auth.uid
        if (uid == null) {
            endSession()
            return
        }
        try {
            val linked = pairing.currentPairing()
            val now = System.currentTimeMillis()
            val verdict = if (linked?.status == "ACTIVE") {
                RequestVerifier.verify(req, uid, linked.parentSigningKey, now)
            } else Verdict.NO_KEYS
            if (verdict != Verdict.OK || linked == null) {
                transfers.setRequestStatus(uid, req.requestId,
                    CaptureLabels.forVerdict(verdict) ?: TransferStatus.DENIED)
                return
            }
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.CAPTURING)
            scope.ensureActive()
            if (!noticesEnabled(this)) {
                transfers.setRequestStatus(uid, req.requestId, TransferStatus.DENIED)
                endSession()
                return
            }
            check(auth.uid == uid && req.expiresAt > System.currentTimeMillis())
            notifications.notify(ONGOING_ID, ongoing("Taking a screenshot for your paired parent."))
            val bitmap = s.capture()
            val image = try {
                transfers.setRequestStatus(uid, req.requestId, TransferStatus.COMPRESSING)
                withContext(Dispatchers.Default) { PreviewCodec.compress(bitmap) }
            } finally {
                bitmap.recycle()
            }
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.UPLOADING)
            check(pairing.currentPairing() == linked && auth.uid == uid && noticesEnabled(this))
            scope.ensureActive()
            transfers.sendPreview(uid, req, image, linked)
            notifications.notify(ONGOING_ID, ongoing("Screenshot sent. Screen sharing is still on."))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.FAILED)
            if (noticesEnabled(this)) notifications.notify(ONGOING_ID, ongoing("Screenshot could not be sent."))
        } finally {
            coordinator.resolve(req)
        }
    }

    private fun endSession() {
        scope.cancel()
        stopSelf()
    }

    private fun onProjectionStopped() {
        scope.launch {
            state.set(false, "Screen sharing ended. Start a new session to continue.")
            endSession()
        }
    }

    private fun stopIntent() = PendingIntent.getService(
        this, 2, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
    )

    private fun ongoing(message: String = "Your paired parent can request a screenshot."): Notification =
        NotificationCompat.Builder(this, SafeWeApp.SCREEN_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("SafeWe screen sharing is on")
            .setContentText(message)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(stopIntent())
            .addAction(0, "Stop sharing", stopIntent())
            .build()

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        coordinator.setScreenSessionActive(false)
        coordinator.stopListening("screen")
        if (state.active.value) state.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ONGOING_ID = 3001
        private const val ACTION_STOP = "com.safewe.SCREEN_STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun noticesEnabled(ctx: Context): Boolean {
            val manager = ctx.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(SafeWeApp.SCREEN_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun start(ctx: Context, resultCode: Int, data: Intent) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, ScreenShareService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
        )
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScreenShareService::class.java)) }
    }
}
