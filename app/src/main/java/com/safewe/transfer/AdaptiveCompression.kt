package com.safewe.transfer

/** Ordered (dimension, quality) attempts: shrink quality first, then dimension, until under target size. */
object AdaptiveCompression {
    data class Step(val maxDim: Int, val quality: Int)

    fun plan(maxDim: Int = 960): List<Step> {
        val dims = listOf(1.0, 0.85, 0.7, 0.55).map { (maxDim * it).toInt() }.filter { it >= 320 }.ifEmpty { listOf(maxDim) }
        return dims.flatMap { d -> listOf(80, 65, 50, 40).map { q -> Step(d, q) } }
    }
}
