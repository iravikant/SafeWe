package com.safewe.service

import android.content.Context
import androidx.work.*
import com.safewe.data.local.PendingAlertDao
import com.safewe.data.remote.AuthRepository
import com.safewe.data.remote.GeofenceAlert
import com.safewe.data.remote.GeofenceRepository
import com.safewe.transfer.QuotaTracker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OutboxEntryPoint {
    fun auth(): AuthRepository
    fun alerts(): PendingAlertDao
    fun geofences(): GeofenceRepository
    fun quota(): QuotaTracker
}

/**
 * Uploads queued geofence alerts when the network is back. WorkManager retries with exponential backoff
 * (30 s, 60 s, 120 s ...). An alert is dropped after [MAX_ATTEMPTS] tries or after 24 h, when it is no longer news.
 */
class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, OutboxEntryPoint::class.java)
        val uid = ep.auth().uid ?: return Result.success()
        val dao = ep.alerts()
        dao.deleteOlderThan(System.currentTimeMillis() - MAX_AGE_MS)
        var failures = 0
        for (a in dao.pending(50)) {
            val ok = runCatching {
                withTimeoutOrNull(30_000) { ep.geofences().publishAlert(uid, GeofenceAlert(a.id, a.geofenceId, a.name, a.zone, a.transition, a.timestamp)); true }
            }.getOrNull() == true
            if (ok) { dao.delete(a.id); ep.quota().add(writes = 1) }
            else { failures++; if (a.attempts + 1 >= MAX_ATTEMPTS) dao.delete(a.id) else dao.bump(a.id) }
        }
        return if (failures > 0 && dao.count() > 0) Result.retry() else Result.success()
    }

    companion object {
        const val MAX_ATTEMPTS = 8
        private const val MAX_AGE_MS = 24 * 3_600_000L
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("safewe_outbox", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
