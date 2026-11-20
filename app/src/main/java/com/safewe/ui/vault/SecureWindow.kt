package com.safewe.ui.vault

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.safewe.security.findActivity

/**
 * FLAG_SECURE blocks screenshots, screen recording, MediaProjection (SafeWe's own screenshot feature shows black)
 * and hides the app in the recent-apps thumbnail. Reference-counted so navigating between vault screens
 * never leaves a gap where the flag is cleared.
 */
object SecureWindow {
    private var count = 0
    fun acquire(a: Activity) { if (count++ == 0) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    fun release(a: Activity) { if (--count <= 0) { count = 0; a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
}

@Composable
fun SecureWindowEffect() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.let { SecureWindow.acquire(it) }
        onDispose { activity?.let { SecureWindow.release(it) } }
    }
}
