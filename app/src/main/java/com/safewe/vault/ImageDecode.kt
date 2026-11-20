package com.safewe.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

object ImageDecode {
    /** Decodes with down-sampling and applies the EXIF rotation, so phone photos are not shown sideways. */
    fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val deg = degrees(bytes)
        val longest = max(raw.width, raw.height)
        if (deg == 0 && longest <= maxDim) return raw
        val m = Matrix()
        if (longest > maxDim) m.postScale(maxDim.toFloat() / longest, maxDim.toFloat() / longest)
        if (deg != 0) m.postRotate(deg.toFloat())
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }

    private fun degrees(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}

/** Small JPEG previews for the grid. Best effort: failures just mean "no thumbnail". */
object Thumbnailer {
    private const val SIZE = 256
    private const val MAX_IMAGE_BYTES = 48L * 1024 * 1024

    fun make(ctx: Context, uri: Uri, mime: String, size: Long): ByteArray? = runCatching {
        when {
            mime.startsWith("image/") && size in 0..MAX_IMAGE_BYTES -> ctx.contentResolver.openInputStream(uri)?.use { s ->
                ImageDecode.decode(s.readBytes(), SIZE)?.let(::jpeg)
            }
            mime.startsWith("video/") -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { f ->
                        val s = SIZE.toFloat() / max(f.width, f.height)
                        jpeg(if (s < 1f) Bitmap.createScaledBitmap(f, (f.width * s).toInt().coerceAtLeast(1), (f.height * s).toInt().coerceAtLeast(1), true) else f)
                    }
                } finally { r.release() }
            }
            else -> null
        }
    }.getOrNull()

    private fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
}
