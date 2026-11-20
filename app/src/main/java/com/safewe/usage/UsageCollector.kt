package com.safewe.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.safewe.data.local.UsageDao
import com.safewe.data.local.UsageDayEntity
import com.safewe.data.remote.UsageApp
import com.safewe.data.remote.UsageDay
import com.safewe.domain.usage.Kind
import com.safewe.domain.usage.RawEvent
import com.safewe.domain.usage.UsageAggregator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageCollector @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: UsageDao
) {
    private val usm get() = ctx.getSystemService(UsageStatsManager::class.java)

    /** Re-checked on every call: the user can revoke Usage Access at any time. */
    fun hasAccess(): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Android only keeps raw events for a few days, so we persist per-day totals to Room
     * to be able to show a month. A stored day is only overwritten by a larger value,
     * which protects against partially expired event history.
     * Day boundaries use the device's CURRENT time zone, with DST-safe LocalDate maths.
     */
    suspend fun collectRecent(days: Int = 7): Boolean {
        if (!hasAccess()) return false
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)

        for (i in 0 until days) {
            val date = today.minusDays(i.toLong())
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(now, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val events = readEvents(start, end)
            if (events.isEmpty()) continue // expired or no activity: never overwrite with zeros
            val totals = UsageAggregator.foregroundMs(events, start, end)
            val existing = dao.forDay(date.toString()).associate { it.pkg to it.foregroundMs }
            val rows = totals.filter { it.value > 0 }.map { (pkg, ms) ->
                UsageDayEntity(date.toString(), pkg, maxOf(ms, existing[pkg] ?: 0L))
            }
            if (rows.isNotEmpty()) dao.upsert(rows)
        }
        dao.deleteOlderThan(today.minusDays(RETENTION_DAYS).toString())
        return true
    }

    private fun readEvents(start: Long, end: Long): List<RawEvent> {
        val out = ArrayList<RawEvent>()
        val q = usm.queryEvents(start, end) ?: return out
        val e = UsageEvents.Event()
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            val kind = when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> Kind.FOREGROUND
                UsageEvents.Event.MOVE_TO_BACKGROUND -> Kind.BACKGROUND
                DEVICE_SHUTDOWN -> Kind.SHUTDOWN
                else -> null
            } ?: continue
            if (kind != Kind.SHUTDOWN && e.packageName == ctx.packageName) continue
            out.add(RawEvent(e.packageName.orEmpty(), kind, e.timeStamp))
        }
        return out
    }

    /** Compact summary: top 10 apps per day. This is all that ever leaves the device. */
    suspend fun summary(days: Int): List<UsageDay> {
        val from = LocalDate.now(ZoneId.systemDefault()).minusDays(days - 1L).toString()
        return dao.since(from).groupBy { it.day }.map { (day, rows) ->
            UsageDay(
                day = day,
                totalMs = rows.sumOf { it.foregroundMs },
                apps = rows.sortedByDescending { it.foregroundMs }.take(10)
                    .map { UsageApp(it.pkg, label(it.pkg), it.foregroundMs) }
            )
        }.sortedBy { it.day }
    }

    suspend fun clear() = dao.clear()

    private fun label(pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        const val RETENTION_DAYS = 62L
        private const val DEVICE_SHUTDOWN = 26 // UsageEvents.Event.DEVICE_SHUTDOWN (API 28)
    }
}
