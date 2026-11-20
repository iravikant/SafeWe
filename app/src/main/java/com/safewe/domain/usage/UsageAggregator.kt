package com.safewe.domain.usage

enum class Kind { FOREGROUND, BACKGROUND, SHUTDOWN }
data class RawEvent(val pkg: String, val kind: Kind, val time: Long)

/**
 * Turns raw foreground/background events into per-app foreground milliseconds for [start, end].
 * Handles: sessions that began before [start], sessions still open at [end], device shutdown
 * (closes every open session), duplicate FOREGROUND events (keeps the original start) and
 * BACKGROUND events with no matching start (counted from [start] only if it is the app's first event).
 */
object UsageAggregator {
    fun foregroundMs(events: List<RawEvent>, start: Long, end: Long): Map<String, Long> {
        val totals = HashMap<String, Long>()
        val open = HashMap<String, Long>()
        val seen = HashSet<String>()

        fun add(pkg: String, from: Long, to: Long) {
            val d = (minOf(to, end) - maxOf(from, start))
            if (d > 0) totals[pkg] = (totals[pkg] ?: 0L) + d
        }

        for (e in events.sortedBy { it.time }) {
            if (e.time < start || e.time > end) continue
            when (e.kind) {
                Kind.FOREGROUND -> { open.putIfAbsent(e.pkg, e.time); seen.add(e.pkg) }
                Kind.BACKGROUND -> {
                    val s = open.remove(e.pkg)
                    if (s != null) add(e.pkg, s, e.time)
                    else if (seen.add(e.pkg)) add(e.pkg, start, e.time) // carried over from before the range
                }
                Kind.SHUTDOWN -> { open.forEach { (p, s) -> add(p, s, e.time) }; open.clear() }
            }
        }
        open.forEach { (p, s) -> add(p, s, end) }
        return totals
    }
}
