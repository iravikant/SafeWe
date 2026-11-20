package com.safewe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SafeWeApp : Application() {
    @javax.inject.Inject lateinit var connectivity: com.safewe.health.ConnectivityMonitor

    override fun onCreate() {
        super.onCreate()
        AppCheckInit.install()   // Play Integrity in release, debug provider in debug builds
        connectivity.start()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(LOCATION_CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(REQUEST_CHANNEL, "Parent requests", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(SCREEN_CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }
    companion object {
        const val LOCATION_CHANNEL = "safewe_location"
        const val REQUEST_CHANNEL = "safewe_requests"
        const val SCREEN_CHANNEL = "safewe_screen"
    }
}
