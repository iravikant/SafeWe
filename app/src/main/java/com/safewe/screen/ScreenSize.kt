package com.safewe.screen

import kotlin.math.max
import kotlin.math.roundToInt

object ScreenSize {
    /** Scales (w,h) so the longer side is at most [maxDim]; both sides even and >= 2. */
    fun scaled(w: Int, h: Int, maxDim: Int = 960): Pair<Int, Int> {
        val longest = max(w, h).coerceAtLeast(1)
        if (longest <= maxDim) return even(w) to even(h)
        val f = maxDim.toFloat() / longest
        return even((w * f).roundToInt()) to even((h * f).roundToInt())
    }
    private fun even(v: Int) = max(2, v and 1.inv())
}
