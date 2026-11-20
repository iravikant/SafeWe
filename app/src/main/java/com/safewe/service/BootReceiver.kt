package com.safewe.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth

/**
 * Resumes location sharing after a reboot or app update, but ONLY if the child had turned it on, is still signed in,
 * and Android still allows it (location + "allow all the time"; Android 12+ forbids starting most foreground services
 * from the background). Otherwise the child gets a notification to resume with one tap. The camera and screen sharing
 * are never started here: they always need the child in front of the phone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!SharingPrefs.isEnabled(ctx)) return
        if (FirebaseAuth.getInstance().currentUser == null) { SharingPrefs.set(ctx, false); return }

        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            SharingPrefs.set(ctx, false)
            ResumeNotifier.show(ctx, "SafeWe sharing is off", "Location permission was turned off. Open SafeWe to turn sharing back on.")
            return
        }
        if (!bg) {
            ResumeNotifier.show(ctx, "Tap to resume sharing", "Android needs you to open SafeWe to restart location sharing.")
            return
        }
        try { LocationService.start(ctx) }
        catch (e: Exception) { ResumeNotifier.show(ctx, "Tap to resume sharing", "Android did not allow SafeWe to restart sharing by itself.") }
    }
}
