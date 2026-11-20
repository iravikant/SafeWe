package com.safewe

import com.safewe.transfer.*
import org.junit.Assert.*
import org.junit.Test

class CaptureLabelsTest {
    @Test fun expiredMapsToExpiredAndBadRequestsToDenied() {
        assertEquals(TransferStatus.EXPIRED, CaptureLabels.forVerdict(Verdict.EXPIRED))
        assertEquals(TransferStatus.DENIED, CaptureLabels.forVerdict(Verdict.BAD_SIGNATURE))
        assertEquals(TransferStatus.DENIED, CaptureLabels.forVerdict(Verdict.UNKNOWN_TYPE))
        assertEquals(TransferStatus.DENIED, CaptureLabels.forVerdict(Verdict.NO_KEYS))
    }
    @Test fun acceptedAndDuplicateWriteNothing() {
        assertNull(CaptureLabels.forVerdict(Verdict.OK))
        assertNull(CaptureLabels.forVerdict(Verdict.DUPLICATE))
    }
    @Test fun terminalStatesStopTheParentWait() {
        assertTrue(TransferStatus.DENIED in CaptureLabels.TERMINAL)
        assertFalse(TransferStatus.UPLOADING in CaptureLabels.TERMINAL)
    }
    @Test fun everyActiveStatusHasAFriendlyLabel() {
        listOf(TransferStatus.REQUESTED, TransferStatus.WAITING_FOR_SESSION, TransferStatus.CAPTURING,
            TransferStatus.COMPRESSING, TransferStatus.UPLOADING, TransferStatus.DENIED, TransferStatus.FAILED)
            .forEach { assertNotEquals(it, CaptureLabels.parent(it)) }
    }
}
