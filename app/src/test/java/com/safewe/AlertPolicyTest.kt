package com.safewe

import com.safewe.domain.geo.AlertPolicy
import org.junit.Assert.*
import org.junit.Test

class AlertPolicyTest {
    @Test fun safeZoneAlertsOnArriveAndLeave() {
        assertTrue(AlertPolicy.shouldAlert("SAFE", "ENTER"))
        assertTrue(AlertPolicy.shouldAlert("SAFE", "EXIT"))
        assertFalse(AlertPolicy.shouldAlert("SAFE", "DWELL"))
    }
    @Test fun restrictedZoneAlertsOnEnterAndDwell() {
        assertTrue(AlertPolicy.shouldAlert("RESTRICTED", "ENTER"))
        assertTrue(AlertPolicy.shouldAlert("RESTRICTED", "DWELL"))
        assertFalse(AlertPolicy.shouldAlert("RESTRICTED", "EXIT"))
    }
    @Test fun unknownZoneNeverAlerts() = assertFalse(AlertPolicy.shouldAlert("OTHER", "ENTER"))
}
