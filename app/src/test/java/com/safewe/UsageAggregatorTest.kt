package com.safewe

import com.safewe.domain.usage.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageAggregatorTest {
    private val S = 1_000_000L
    private val E = S + 1_000_000L
    private fun fg(p: String, t: Long) = RawEvent(p, Kind.FOREGROUND, S + t)
    private fun bg(p: String, t: Long) = RawEvent(p, Kind.BACKGROUND, S + t)

    @Test fun simpleSession() {
        val r = UsageAggregator.foregroundMs(listOf(fg("a", 100), bg("a", 400)), S, E)
        assertEquals(300L, r["a"])
    }

    @Test fun sessionCarriedOverFromBeforeRange() {
        val r = UsageAggregator.foregroundMs(listOf(bg("a", 250)), S, E)
        assertEquals(250L, r["a"])
    }

    @Test fun sessionStillOpenAtEndIsClosedAtEnd() {
        val r = UsageAggregator.foregroundMs(listOf(fg("a", 900_000)), S, E)
        assertEquals(100_000L, r["a"])
    }

    @Test fun shutdownClosesOpenSessions() {
        val ev = listOf(fg("a", 100), RawEvent("android", Kind.SHUTDOWN, S + 300), fg("b", 500), bg("b", 600))
        val r = UsageAggregator.foregroundMs(ev, S, E)
        assertEquals(200L, r["a"]); assertEquals(100L, r["b"])
    }

    @Test fun duplicateForegroundKeepsOriginalStart() {
        val r = UsageAggregator.foregroundMs(listOf(fg("a", 100), fg("a", 200), bg("a", 300)), S, E)
        assertEquals(200L, r["a"])
    }

    @Test fun unorderedEventsAreSorted() {
        val r = UsageAggregator.foregroundMs(listOf(bg("a", 400), fg("a", 100)), S, E)
        assertEquals(300L, r["a"])
    }

    @Test fun eventsOutsideRangeIgnored() {
        val r = UsageAggregator.foregroundMs(listOf(RawEvent("a", Kind.FOREGROUND, S - 5), RawEvent("a", Kind.BACKGROUND, S - 1)), S, E)
        assertNull(r["a"])
    }

    @Test fun twoAppsIndependent() {
        val r = UsageAggregator.foregroundMs(listOf(fg("a", 0), bg("a", 100), fg("b", 100), bg("b", 350)), S, E)
        assertEquals(100L, r["a"]); assertEquals(250L, r["b"])
    }
}
