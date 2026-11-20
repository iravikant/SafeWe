package com.safewe.transfer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Spark-plan guard rails: the busier the day, the less optional traffic (heartbeats) this phone sends. */
object BudgetPolicy {
    enum class Level { OK, HIGH, CRITICAL }
    const val HEARTBEAT_OK_MS = 15 * 60_000L
    const val HEARTBEAT_HIGH_MS = 60 * 60_000L

    fun fraction(used: Quota): Double = max(
        used.reads.toDouble() / QuotaMath.FREE.reads,
        max(used.writes.toDouble() / QuotaMath.FREE.writes, used.deletes.toDouble() / QuotaMath.FREE.deletes)
    )

    fun level(used: Quota): Level = fraction(used).let { if (it >= 0.9) Level.CRITICAL else if (it >= 0.6) Level.HIGH else Level.OK }

    /** 0 = pause heartbeats entirely (safety-critical data such as geofence alerts is never throttled). */
    fun heartbeatMs(used: Quota): Long = when (level(used)) { Level.OK -> HEARTBEAT_OK_MS; Level.HIGH -> HEARTBEAT_HIGH_MS; Level.CRITICAL -> 0L }
}

object Backoff {
    /** Exponential delay with +-20% jitter: 0.5s, 1s, 2s, 4s ... capped. [rnd] is injectable for tests. */
    fun delayMs(attempt: Int, baseMs: Long = 500, capMs: Long = 60_000, jitter: Double = 0.2, rnd: Double = Random.nextDouble()): Long {
        val exp = min(capMs.toDouble(), baseMs * 2.0.pow(attempt.coerceIn(0, 20)))
        return (exp + exp * jitter * (rnd * 2 - 1)).toLong().coerceAtLeast(0)
    }
}
