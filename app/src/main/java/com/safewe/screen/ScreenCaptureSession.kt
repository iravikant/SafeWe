package com.safewe.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One MediaProjection, ONE VirtualDisplay (Android 14+ allows a single VirtualDisplay per consent token).
 * We keep the newest frame of the (downscaled) display and convert it to a Bitmap only when a request arrives.
 * Protected content (FLAG_SECURE, DRM) is rendered black by Android; nothing here tries to get around that.
 */
class ScreenCaptureSession(
    ctx: Context,
    private val projection: MediaProjection,
    private val onStopped: () -> Unit
) {
    private val thread = HandlerThread("safewe-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var latest: Image? = null
    private val released = AtomicBoolean(false)
    private val reader: ImageReader
    private val display: VirtualDisplay?

    init {
        val dm = ctx.resources.displayMetrics
        val (w, h) = ScreenSize.scaled(dm.widthPixels, dm.heightPixels, 960)

        // Android 14+: the callback MUST be registered before createVirtualDisplay().
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { release(); onStopped() }
        }, handler)

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
            override fun onImageAvailable(r: ImageReader) {
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
                synchronized(lock) { latest?.close(); latest = img }
            }
        }, handler)

        display = projection.createVirtualDisplay(
            "SafeWeScreen", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
        )
    }

    /** Waits briefly for a first frame if none has arrived yet. */
    suspend fun capture(timeoutMs: Long = 2500): Bitmap {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (released.get()) throw IllegalStateException("Screen sharing ended")
            synchronized(lock) { latest?.let { return toBitmap(it) } }
            if (System.currentTimeMillis() > end) throw IllegalStateException("No screen frame available")
            delay(100)
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        synchronized(lock) { latest?.close(); latest = null }
        runCatching { display?.release() }
        runCatching { reader.close() }
        runCatching { projection.stop() }
        thread.quitSafely()
    }
}
