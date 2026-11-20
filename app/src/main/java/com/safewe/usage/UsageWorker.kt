package com.safewe.usage

import android.content.Context
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UsageEntryPoint { fun collector(): UsageCollector }

/** Runs a few times a day (WorkManager is deferrable, not exact) so month totals accumulate in Room. */
class UsageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val collector = EntryPointAccessors.fromApplication(applicationContext, UsageEntryPoint::class.java).collector()
        return runCatching { collector.collectRecent() }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        private const val NAME = "safewe_usage"
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UsageWorker>(6, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
        fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(NAME) }
    }
}
