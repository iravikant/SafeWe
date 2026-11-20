package com.safewe.transfer

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max

class CompressedImage(val bytes: ByteArray, val mime: String)

object PreviewCodec {
    const val TARGET_MAX_BYTES = 150 * 1024

    /** WebP preferred; falls back to JPEG if the encoder is unavailable. Adaptive until <= [targetMax]. */
    fun compress(src: Bitmap, maxDim: Int = 960, targetMax: Int = TARGET_MAX_BYTES): CompressedImage {
        var last: CompressedImage? = null
        for (step in AdaptiveCompression.plan(maxDim)) {
            val scaled = scale(src, step.maxDim)
            val img = encode(scaled, step.quality)
            if (scaled !== src) scaled.recycle()
            last = img
            if (img.bytes.size <= targetMax) return img
        }
        return checkNotNull(last)
    }

    private fun encode(bmp: Bitmap, quality: Int): CompressedImage {
        val webp = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        val out = ByteArrayOutputStream()
        if (bmp.compress(webp, quality, out)) return CompressedImage(out.toByteArray(), "image/webp")
        out.reset()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return CompressedImage(out.toByteArray(), "image/jpeg")
    }

    private fun scale(src: Bitmap, maxDim: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxDim) return src
        val f = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
    }
}
