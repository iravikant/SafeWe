package com.safewe.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.safewe.SafeWeApp
import com.safewe.data.local.LocationDao
import com.safewe.data.local.LocationEntity
import com.safewe.data.remote.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import javax.inject.Inject

/**
 * User-started foreground location service (visible, ongoing notification).
 * Target interval is 10 min; Android may batch or defer updates.
 * Commands and geofence configs are received only while this process is alive.
 */
@AndroidEntryPoint
class LocationService : Service() {

    @Inject lateinit var fused: FusedLocationProviderClient
    @Inject lateinit var dao: LocationDao
    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var commands: CommandRepository
    @Inject lateinit var geofences: GeofenceManager
    @Inject lateinit var usage: com.safewe.usage.UsageCollector
    @Inject lateinit var transfers: com.safewe.transfer.FirestorePreviewTransferManager
    @Inject lateinit var captures: com.safewe.camera.CaptureCoordinator
    @Inject lateinit var reporter: DeviceStatusReporter
    @Inject lateinit var connectivity: com.safewe.health.ConnectivityMonitor
    @Inject lateinit var pairingRepo: PairingRepository
    @Inject lateinit var budget: com.safewe.transfer.QuotaTracker
    @Inject lateinit var geofenceRepo: GeofenceRepository
    @Inject lateinit var statusRepo: StatusRepository
    @Inject lateinit var identity: com.safewe.security.DeviceIdentity

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandJob: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
            result.lastLocation?.let { loc ->
                scope.launch { save(loc) }
                geofences.onLocation(loc.latitude, loc.longitude, loc.accuracy)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { SharingPrefs.set(this, false); stopSelf(); return START_NOT_STICKY }
        if (!hasFineLocation()) {
            // Android requires startForeground() after startForegroundService(); it may refuse without the permission.
            runCatching { startForeground(NOTIF_ID, notification()) }
            permissionLost(); return START_NOT_STICKY
        }

        ServiceCompat.startForeground(this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startUpdates()
        auth.uid?.let { uid ->
            geofences.start(scope, uid)
            scope.launch { runCatching { transfers.sweepExpired(uid) } } // manual cleanup of expired previews
            scope.launch { runCatching { commands.sweepStale(uid) }; runCatching { geofenceRepo.pruneAlerts(uid) } }
            captures.startListening("service", scope)
        }
        SharingPrefs.set(this, true)
        listenForCommands()
        startMonitors()
        return START_STICKY
    }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun startUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
            .build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private suspend fun save(loc: android.location.Location) {
        dao.insertAndTrim(
            LocationEntity(
                latitude = loc.latitude, longitude = loc.longitude, accuracy = loc.accuracy,
                timestamp = loc.time, provider = loc.provider ?: "fused", deviceId = auth.uid.orEmpty()
            )
        )
    }

    private fun listenForCommands() {
        val uid = auth.uid ?: return
        commandJob?.cancel()
        commandJob = scope.launch {
            commands.observeIncoming(uid).collect { cmd ->
                if (cmd.expiresAt < System.currentTimeMillis()) {
                    runCatching { commands.markStatus(uid, cmd.id, "EXPIRED") }
                    return@collect
                }
                runCatching {
                    when (cmd.type) {
                        CommandRepository.TYPE_GET_LOCATION -> answerLocation(uid, cmd.id)
                        CommandRepository.TYPE_GET_HISTORY -> answerHistory(uid, cmd.id)
                        CommandRepository.TYPE_GET_USAGE -> answerUsage(uid, cmd.id, cmd.param)
                        else -> commands.markStatus(uid, cmd.id, "DENIED") // unknown type: refuse
                    }
                }.onFailure { runCatching { commands.markStatus(uid, cmd.id, "FAILED") } }
            }
        }
    }

    @Suppress("MissingPermission")
    private suspend fun answerLocation(uid: String, commandId: String) {
        val fresh = runCatching {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
        }.getOrNull()

        val result: com.safewe.data.remote.LocationResult = if (fresh != null) {
            save(fresh)
            com.safewe.data.remote.LocationResult(commandId, fresh.latitude, fresh.longitude, fresh.accuracy, fresh.time, true)
        } else {
            val c = dao.latest() ?: error("No location available")
            com.safewe.data.remote.LocationResult(commandId, c.latitude, c.longitude, c.accuracy, c.timestamp, false)
        }
        commands.publishResult(uid, result)
    }

    private suspend fun answerHistory(uid: String, commandId: String) {
        val pts = dao.latest10().map { HistoryPoint(it.latitude, it.longitude, it.accuracy, it.timestamp) }
        commands.publishHistory(uid, HistoryResult(commandId, pts, System.currentTimeMillis()))
    }

    private suspend fun answerUsage(uid: String, commandId: String, days: Int) {
        val now = System.currentTimeMillis()
        val result = if (!usage.hasAccess()) UsageResult(commandId, emptyList(), "NO_PERMISSION", now)
        else {
            usage.collectRecent()
            UsageResult(commandId, usage.summary(days.coerceIn(1, 31)), "", now)
        }
        commands.publishUsage(uid, result)
    }

    /** Heartbeat, connectivity, pairing and permission watchers. All of them run only while sharing is on. */
    private fun startMonitors() {
        // Heartbeat: tiny status document. The interval stretches (or stops) as this phone's daily Firestore budget fills up.
        scope.launch {
            while (isActive) {
                if (!hasFineLocation()) { permissionLost(); return@launch }
                val interval = com.safewe.transfer.BudgetPolicy.heartbeatMs(budget.today())
                if (interval > 0) runCatching { reporter.report(true) }
                delay(if (interval > 0) interval else com.safewe.transfer.BudgetPolicy.HEARTBEAT_HIGH_MS)
            }
        }
        // Back online: say so, and flush queued alerts.
        scope.launch {
            connectivity.online.drop(1).filter { it }.collect {
                runCatching { reporter.report(true) }
                OutboxWorker.enqueue(this@LocationService)
            }
        }
        // The parent can end the pairing at any time: stop everything and clean up.
        scope.launch {
            var seenActive = false
            pairingRepo.observeMyPairing().collect { p ->
                if (p?.status == "ACTIVE") seenActive = true
                else if (seenActive) { pairingRemoved(); return@collect }
            }
        }
    }

    private fun permissionLost() {
        SharingPrefs.set(this, false)
        reporter.reportAsync(false)
        ResumeNotifier.show(this, "SafeWe stopped sharing", "Location permission was turned off. Open SafeWe to turn sharing back on.")
        stopSelf()
    }

    private fun pairingRemoved() {
        val uid = auth.uid
        SharingPrefs.set(this, false)
        com.safewe.usage.UsageWorker.cancel(this)
        com.safewe.camera.CameraSessionService.stop(this)
        com.safewe.screen.ScreenShareService.stop(this)
        captures.clear()
        scope.launch(NonCancellable) {
            runCatching { usage.clear() }
            uid?.let { u ->
                runCatching { geofenceRepo.clearAll(u) }
                runCatching { statusRepo.clear(u) }
                identity.wipe(u)
            }
        }
        ResumeNotifier.show(this, "Pairing ended", "Your parent removed the pairing. SafeWe stopped sharing and cleared what it stored for them.")
        stopSelf()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, LocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SafeWeApp.LOCATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("SafeWe is sharing your location")
            .setContentText("Your paired parent can request your location and safe-zone alerts.")
            .setOngoing(true)
            .addAction(0, "Stop sharing", stop)
            .build()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        reporter.reportAsync(false)
        geofences.stop()
        captures.stopListening("service")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val INTERVAL_MS = 600_000L
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.safewe.STOP"
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java))
        fun stop(ctx: Context) { SharingPrefs.set(ctx, false); ctx.stopService(Intent(ctx, LocationService::class.java)) }
    }
}
