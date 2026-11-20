package com.safewe

import com.safewe.screen.ScreenSize
import com.safewe.transfer.CaptureLabels
import com.safewe.transfer.CaptureType
import com.safewe.transfer.TransferStatus
import org.junit.Assert.*
import org.junit.Test

class ScreenSizeTest {
    @Test fun portraitPhoneIsScaledToMax960() = assertEquals(432 to 960, ScreenSize.scaled(1080, 2400))
    @Test fun landscapeKeepsAspect() = assertEquals(960 to 432, ScreenSize.scaled(2400, 1080))
    @Test fun smallScreensAreNotUpscaled() = assertEquals(480 to 800, ScreenSize.scaled(480, 800))
    @Test fun dimensionsAreEvenAndPositive() {
        for ((w, h) in listOf(1081 to 2401, 721 to 1281, 1 to 1, 3000 to 7)) {
            val (a, b) = ScreenSize.scaled(w, h)
            assertTrue(a >= 2 && b >= 2); assertEquals(0, a % 2); assertEquals(0, b % 2)
        }
    }
    @Test fun screenshotLabelsMentionScreenSharing() {
        assertTrue(CaptureLabels.parent(TransferStatus.WAITING_FOR_SESSION, CaptureType.SCREENSHOT).contains("screen sharing"))
        assertTrue(CaptureLabels.parent(TransferStatus.WAITING_FOR_SESSION, CaptureType.CAMERA).contains("camera"))
    }
}
