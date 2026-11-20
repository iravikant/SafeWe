package com.safewe

import com.safewe.health.*
import com.safewe.transfer.*
import org.junit.Assert.*
import org.junit.Test

class HealthPolicyTest {
    private val ok = HealthInput(true, true, true, true, true, true, false, 80, false, 10, true, true)

    @Test fun healthyPhoneHasNoIssues() = assertTrue(DeviceHealth.evaluate(ok).isEmpty())

    @Test fun revokedLocationIsReportedOnlyWhileSharing() {
        assertEquals(listOf(IssueCode.LOCATION_PERMISSION), DeviceHealth.evaluate(ok.copy(fineLocation = false)))
        assertTrue(DeviceHealth.evaluate(ok.copy(fineLocation = false, sharing = false, usageWanted = false)).isEmpty())
    }
    @Test fun backgroundLocationOnlyMattersWhenForegroundIsGranted() {
        assertEquals(listOf(IssueCode.BACKGROUND_LOCATION), DeviceHealth.evaluate(ok.copy(backgroundLocation = false)))
        assertFalse(IssueCode.BACKGROUND_LOCATION in DeviceHealth.evaluate(ok.copy(fineLocation = false, backgroundLocation = false)))
    }
    @Test fun otherIssuesAreDetected() {
        val i = DeviceHealth.evaluate(ok.copy(notifications = false, usageAccess = false, powerSave = true, batteryPct = 5, standbyBucket = 45, online = false, locationServices = false))
        assertEquals(setOf(IssueCode.NOTIFICATIONS, IssueCode.USAGE_ACCESS, IssueCode.POWER_SAVER, IssueCode.BATTERY_LOW, IssueCode.RESTRICTED_BUCKET, IssueCode.OFFLINE, IssueCode.LOCATION_SERVICES), i.toSet())
    }
    @Test fun chargingPhoneIsNotFlaggedLowBatteryAndUnknownLevelIsIgnored() {
        assertTrue(DeviceHealth.evaluate(ok.copy(batteryPct = 4, charging = true)).isEmpty())
        assertTrue(DeviceHealth.evaluate(ok.copy(batteryPct = -1)).isEmpty())
    }
    @Test fun issueCodesRoundTripAndHaveMessages() {
        IssueCode.entries.forEach { assertEquals(it, IssueCode.parse(it.name)); assertTrue(it.childMessage.isNotBlank() && it.parentMessage.isNotBlank()) }
        assertNull(IssueCode.parse("NOPE"))
    }

    // ---- presence
    @Test fun presenceRules() {
        val now = 10_000_000L
        assertEquals(Presence.UNKNOWN, PresenceRules.of(true, 0, now))
        assertEquals(Presence.ONLINE, PresenceRules.of(true, now - 10 * 60_000, now))
        assertEquals(Presence.OFFLINE, PresenceRules.of(true, now - 36 * 60_000, now))
        assertEquals(Presence.PAUSED, PresenceRules.of(false, now - 60_000, now))
    }
    @Test fun agoText() {
        val now = 100_000_000L
        assertEquals("just now", PresenceRules.ago(now - 20_000, now)); assertEquals("5 min ago", PresenceRules.ago(now - 5 * 60_000, now))
        assertEquals("3 h ago", PresenceRules.ago(now - 3 * 3_600_000, now)); assertEquals("2 d ago", PresenceRules.ago(now - 48 * 3_600_000, now))
        assertEquals("never", PresenceRules.ago(0, now))
    }

    // ---- budget
    @Test fun budgetLevelsFollowTheBusiestCounter() {
        assertEquals(BudgetPolicy.Level.OK, BudgetPolicy.level(Quota(1000, 1000, 1000)))
        assertEquals(BudgetPolicy.Level.HIGH, BudgetPolicy.level(Quota(0, 12_001, 0)))
        assertEquals(BudgetPolicy.Level.CRITICAL, BudgetPolicy.level(Quota(45_000, 0, 0)))
    }
    @Test fun heartbeatSlowsThenStops() {
        assertEquals(BudgetPolicy.HEARTBEAT_OK_MS, BudgetPolicy.heartbeatMs(Quota()))
        assertEquals(BudgetPolicy.HEARTBEAT_HIGH_MS, BudgetPolicy.heartbeatMs(Quota(0, 13_000, 0)))
        assertEquals(0L, BudgetPolicy.heartbeatMs(Quota(0, 19_000, 0)))
    }
    @Test fun egressIsPartOfThePreviewBudget() {
        val almost = Quota(bytes = (QuotaMath.EGRESS_FREE_DAILY * QuotaMath.PREVIEW_SHARE).toLong() - 1000)
        assertFalse(QuotaMath.fits(almost, QuotaMath.childCost(3)))
        assertTrue(QuotaMath.fits(Quota(), QuotaMath.childCost(3)))
    }

    // ---- backoff
    @Test fun backoffDoublesAndCaps() {
        val d = (0..10).map { Backoff.delayMs(it, rnd = 0.5) } // rnd 0.5 = no jitter
        assertEquals(listOf(500L, 1000L, 2000L, 4000L), d.take(4)); assertEquals(60_000L, d.last())
        assertEquals(d, d.sorted())
    }
    @Test fun backoffJitterStaysWithinTwentyPercent() {
        assertEquals(400L, Backoff.delayMs(0, rnd = 0.0)); assertEquals(600L, Backoff.delayMs(0, rnd = 1.0))
        assertTrue(Backoff.delayMs(500) in 48_000..72_000)
    }
}
