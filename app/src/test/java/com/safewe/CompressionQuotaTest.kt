package com.safewe

import com.safewe.transfer.*
import org.junit.Assert.*
import org.junit.Test

class CompressionQuotaTest {
    @Test fun planStartsAtFullSizeAndOnlyShrinks() {
        val p = AdaptiveCompression.plan(960)
        assertEquals(AdaptiveCompression.Step(960, 80), p.first())
        assertTrue(p.all { it.maxDim <= 960 && it.maxDim >= 320 && it.quality in 40..80 })
        assertEquals(p.map { it.maxDim }, p.map { it.maxDim }.sortedDescending())
    }

    @Test fun smallBudgetStillProducesAPlan() = assertTrue(AdaptiveCompression.plan(300).isNotEmpty())

    @Test fun costsScaleWithChunks() {
        val c = QuotaMath.childCost(3); val p = QuotaMath.parentCost(3)
        assertEquals(8L, c.writes); assertEquals(6L, p.reads); assertEquals(4L, p.deletes)
    }

    @Test fun budgetIsAFractionOfTheFreeTier() {
        assertTrue(QuotaMath.fits(Quota(), QuotaMath.childCost(3)))
        val nearLimit = Quota(0, (QuotaMath.FREE.writes * QuotaMath.PREVIEW_SHARE).toLong() - 2, 0)
        assertFalse(QuotaMath.fits(nearLimit, QuotaMath.childCost(3)))
    }
}
