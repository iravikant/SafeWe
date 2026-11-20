package com.safewe.camera

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.safewe.MainActivity
import com.safewe.SafeWeApp
import com.safewe.data.remote.AuthRepository
import com.safewe.data.remote.PairingRepository
import com.safewe.transfer.CaptureLabels
import com.safewe.transfer.CaptureRequest
import com.safewe.transfer.CaptureType
import com.safewe.transfer.FirestorePreviewTransferManager
import com.safewe.transfer.TransferStatus
import com.safewe.transfer.Verdict
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receives parent capture requests, verifies them (signature, expiry, replay) and queues the valid ones.
 * Camera requests are NEVER executed here: they wait until the child has a visible camera session open.
 * If none is open, the child gets a notification and must tap it. Nothing is launched in the background.
 */
@Singleton
class CaptureCoordinator @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val transfers: FirestorePreviewTransferManager,
    private val pairing: PairingRepository,
    private val auth: AuthRepository
) {
    private val _pending = MutableStateFlow<List<CaptureRequest>>(emptyList())
    val pending: StateFlow<List<CaptureRequest>> = _pending

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive

    private val _screenActive = MutableStateFlow(false)
    val screenSessionActive: StateFlow<Boolean> = _screenActive

    private val jobs = ConcurrentHashMap<String, Job>()

    fun startListening(owner: String, scope: CoroutineScope) {
        val uid = auth.uid ?: return
        jobs.remove(owner)?.cancel()
        jobs[owner] = scope.launch { transfers.observeRequests(uid).collect { runCatching { handle(uid, it) } } }
    }

    fun stopListening(owner: String) { jobs.remove(owner)?.cancel() }

    private suspend fun handle(uid: String, req: CaptureRequest) {
        if (req.type != CaptureType.CAMERA && req.type != CaptureType.SCREENSHOT) return
        val p = pairing.currentPairing() ?: return
        val verdict = transfers.verifyRequest(uid, req, p)
        CaptureLabels.forVerdict(verdict)?.let { transfers.setRequestStatus(uid, req.requestId, it); return }
        if (verdict != Verdict.OK) return // DUPLICATE: already handled
        _pending.update { it + req }
        val active = if (req.type == CaptureType.SCREENSHOT) _screenActive.value else _sessionActive.value
        if (!active) {
            transfers.setRequestStatus(uid, req.requestId, TransferStatus.WAITING_FOR_SESSION)
            notifyChild(req.type)
        }
    }

    /** Camera session (visible camera screen) opened / closed. */
    fun setSessionActive(active: Boolean) {
        _sessionActive.value = active
        if (active) cancelNotification() else notifyIfPending()
    }

    /** MediaProjection session started / ended. */
    fun setScreenSessionActive(active: Boolean) {
        _screenActive.value = active
        if (active) NotificationManagerCompat.from(ctx).cancel(SCREEN_NOTIF_ID)
    }

    /** Removes a request from the queue once it was answered, declined or expired. */
    fun resolve(req: CaptureRequest) { _pending.update { list -> list.filterNot { it.requestId == req.requestId } } }

    fun clear() { _pending.value = emptyList(); cancelNotification() }

    fun notifyIfPending() {
        val now = System.currentTimeMillis()
        _pending.update { list -> list.filter { it.expiresAt > now } }
        if (_pending.value.any { it.type == CaptureType.CAMERA }) notifyChild(CaptureType.CAMERA)
    }

    fun cancelNotification() = NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)

    @SuppressLint("MissingPermission")
    private fun notifyChild(type: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (type == CaptureType.SCREENSHOT) {
            val open = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, SafeWeApp.REQUEST_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Your parent asked for a screenshot")
                .setContentText("Start screen sharing in SafeWe to answer, or ignore this.")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open).build()
            runCatching { nm.notify(SCREEN_NOTIF_ID, n) }
            return
        }
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CAMERA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, SafeWeApp.REQUEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Your parent asked for a photo")
            .setContentText("Tap to open the camera screen. You can decline there.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    private companion object { const val NOTIF_ID = 2001; const val SCREEN_NOTIF_ID = 2002 }
}
