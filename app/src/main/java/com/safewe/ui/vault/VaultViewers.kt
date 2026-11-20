package com.safewe.ui.vault

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Plays a vault video straight from the encrypted file (proxy file descriptor), no plaintext copy on disk. */
@Composable
fun SecureVideo(pfd: ParcelFileDescriptor, onActivity: () -> Unit) {
    val holder = remember { mutableStateOf<SurfaceHolder?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(1) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var failed by remember { mutableStateOf(false) }

    AndroidView(
        factory = { c ->
            SurfaceView(c).apply {
                this.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { holder.value = h }
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) { holder.value = null }
                })
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio)
    )

    LaunchedEffect(holder.value) {
        val h = holder.value ?: return@LaunchedEffect
        player.value?.let { it.setDisplay(h); return@LaunchedEffect }
        try {
            val mp = MediaPlayer()
            player.value = mp
            mp.setDataSource(pfd.fileDescriptor)
            mp.setDisplay(h)
            mp.setOnVideoSizeChangedListener { _, w, hh -> if (w > 0 && hh > 0) ratio = w.toFloat() / hh }
            mp.setOnPreparedListener { duration = it.duration.coerceAtLeast(1); it.start(); playing = true }
            mp.setOnCompletionListener { playing = false }
            mp.setOnErrorListener { _, _, _ -> failed = true; true }
            mp.prepareAsync()
        } catch (e: Exception) { failed = true }
    }
    LaunchedEffect(playing) { while (playing) { position = player.value?.currentPosition ?: 0; onActivity(); delay(500) } }
    DisposableEffect(Unit) { onDispose { runCatching { player.value?.release() }; player.value = null } }

    if (failed) { Text("This video could not be played. Export it to open it in another app.", color = MaterialTheme.colorScheme.error); return }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            val mp = player.value ?: return@IconButton
            if (playing) { mp.pause(); playing = false } else { mp.start(); playing = true }
        }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
        Slider(
            value = position.toFloat(), onValueChange = { position = it.toInt(); player.value?.seekTo(it.toInt()); onActivity() },
            valueRange = 0f..duration.toFloat(), modifier = Modifier.weight(1f)
        )
    }
}

/** Renders PDF pages from the encrypted file on demand (PdfRenderer over a proxy file descriptor). */
@Composable
fun SecurePdf(pfd: ParcelFileDescriptor, onActivity: () -> Unit) {
    val renderer = remember(pfd) { runCatching { PdfRenderer(pfd) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) { Text("This PDF could not be opened. Export it to open it in another app.", color = MaterialTheme.colorScheme.error); return }

    val mutex = remember { Mutex() } // PdfRenderer allows only one open page at a time
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items((0 until renderer.pageCount).toList()) { index ->
                val page by produceState<ImageBitmap?>(null, index) {
                    value = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            runCatching {
                                renderer.openPage(index).use { p ->
                                    val h = (widthPx * p.height / p.width.toFloat()).toInt().coerceAtLeast(1)
                                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(AColor.WHITE)
                                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    bmp.asImageBitmap()
                                }
                            }.getOrNull()
                        }
                    }
                    onActivity()
                }
                page?.let { Image(it, "Page ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                    ?: Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}
