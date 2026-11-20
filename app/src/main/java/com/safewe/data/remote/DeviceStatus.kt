package com.safewe.data.remote

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.safewe.health.ConnectivityMonitor
import com.safewe.health.DeviceHealth
import com.safewe.health.HealthInput
import com.safewe.transfer.QuotaTracker
import com.safewe.usage.UsageCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** devices/{childUid}/status/current: tiny heartbeat document (no location, no content). */
data class DeviceStatus(
    val sharing: Boolean = false,
    val issues: List<String> = emptyList(),
    val battery: Int = -1,
    val charging: Boolean = false,
    val powerSave: Boolean = false,
    val standby: Int = -1,
    val updatedAt: Long = 0
)

@Singleton
class StatusRepository @Inject constructor(private val db: FirebaseFirestore, private val quota: QuotaTracker) {
    private fun doc(uid: String) = db.collection("devices").document(uid).collection("status").document("current")

    fun observe(childUid: String): Flow<DeviceStatus?> = callbackFlow {
        val reg = doc(childUid).addSnapshotListener { s, _ -> trySend(s?.toObject(DeviceStatus::class.java)) }
        awaitClose { reg.remove() }
    }

    suspend fun clear(childUid: String) { doc(childUid).delete().await(); quota.add(deletes = 1) }
}

/** Reads the phone's current health (permissions, battery, power saver, standby bucket, network). */
@Singleton
class HealthProbe @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val connectivity: ConnectivityMonitor,
    private val usage: UsageCollector
) {
    class Battery(val pct: Int, val charging: Boolean)

    fun battery(): Battery {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return Battery(if (level >= 0 && scale > 0) level * 100 / scale else -1, st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun read(sharing: Boolean, usageWanted: Boolean): HealthInput {
        val b = battery()
        val bucket = if (Build.VERSION.SDK_INT >= 28) runCatching { ctx.getSystemService(UsageStatsManager::class.java).appStandbyBucket }.getOrDefault(-1) else -1
        return HealthInput(
            fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocation = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            locationServices = runCatching { LocationManagerCompat.isLocationEnabled(ctx.getSystemService(LocationManager::class.java)) }.getOrDefault(true),
            usageAccess = usage.hasAccess(), usageWanted = usageWanted,
            powerSave = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode,
            batteryPct = b.pct, charging = b.charging, standbyBucket = bucket,
            online = connectivity.online.value, sharing = sharing
        )
    }
}

@Singleton
class DeviceStatusReporter @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: AuthRepository,
    private val probe: HealthProbe,
    private val quota: QuotaTracker
) {
    private fun doc(uid: String) = db.collection("devices").document(uid).collection("status").document("current")

    private fun build(sharing: Boolean): DeviceStatus {
        val i = probe.read(sharing, usageWanted = sharing)
        return DeviceStatus(sharing, DeviceHealth.evaluate(i).map { it.name }, i.batteryPct, i.charging, i.powerSave, i.standbyBucket, System.currentTimeMillis())
    }

    suspend fun report(sharing: Boolean) {
        val uid = auth.uid ?: return
        doc(uid).set(build(sharing)).await()
        quota.add(writes = 1)
    }

    /** Fire-and-forget: Firestore queues the write locally even if we are being torn down. */
    fun reportAsync(sharing: Boolean) {
        val uid = auth.uid ?: return
        doc(uid).set(build(sharing))
        quota.add(writes = 1)
    }
}
