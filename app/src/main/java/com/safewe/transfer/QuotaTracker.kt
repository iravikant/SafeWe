package com.safewe.transfer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class Quota(val reads: Long = 0, val writes: Long = 0, val deletes: Long = 0, val bytes: Long = 0) {
    operator fun plus(o: Quota) = Quota(reads + o.reads, writes + o.writes, deletes + o.deletes, bytes + o.bytes)
}

object QuotaMath {
    val FREE = Quota(50_000, 20_000, 20_000)
    /** 10 GiB/month of free outbound data, spread over a day. */
    const val EGRESS_FREE_DAILY = 10L * 1024 * 1024 * 1024 / 30
    /** Previews may use at most this share of the free daily allowance (the quota is shared by the whole project). */
    const val PREVIEW_SHARE = 0.4
    private const val CHUNK = 64L * 1024

    fun childCost(chunks: Int) = Quota(reads = 1, writes = (chunks + 5).toLong(), deletes = 0, bytes = chunks * CHUNK)
    fun parentCost(chunks: Int) = Quota(reads = (chunks + 3).toLong(), writes = 2, deletes = (chunks + 1).toLong(), bytes = chunks * CHUNK)

    fun fits(used: Quota, cost: Quota): Boolean {
        val t = used + cost
        return t.reads <= FREE.reads * PREVIEW_SHARE && t.writes <= FREE.writes * PREVIEW_SHARE &&
            t.deletes <= FREE.deletes * PREVIEW_SHARE && t.bytes <= EGRESS_FREE_DAILY * PREVIEW_SHARE
    }
}

/**
 * Local ESTIMATE of this phone's Firestore usage today. Firebase counts quota per PROJECT across all devices and
 * listener reads are not all counted here, so treat this as a conservative guard; the Firebase console is the exact meter.
 * Free quotas reset around midnight US Pacific time.
 */
@Singleton
class QuotaTracker @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = ctx.getSharedPreferences("safewe_quota", Context.MODE_PRIVATE)
    private fun day() = LocalDate.now(ZoneId.of("America/Los_Angeles")).toString()

    private val _usage = MutableStateFlow(read())
    val usage: StateFlow<Quota> = _usage

    private fun read(): Quota =
        if (prefs.getString("day", "") != day()) Quota()
        else Quota(prefs.getLong("r", 0), prefs.getLong("w", 0), prefs.getLong("d", 0), prefs.getLong("b", 0))

    @Synchronized fun today(): Quota = read()
    @Synchronized fun refresh() { _usage.value = read() }
    @Synchronized fun canAfford(cost: Quota) = QuotaMath.fits(read(), cost)

    @Synchronized fun record(cost: Quota) {
        val t = read() + cost
        prefs.edit().putString("day", day()).putLong("r", t.reads).putLong("w", t.writes).putLong("d", t.deletes).putLong("b", t.bytes).apply()
        _usage.value = t
    }

    fun add(reads: Long = 0, writes: Long = 0, deletes: Long = 0, bytes: Long = 0) = record(Quota(reads, writes, deletes, bytes))
}
