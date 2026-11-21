package com.safewe

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Debug builds use the debug provider: register the token printed in logcat in the Firebase console. */
object AppCheckInit {
    fun install() {
        runCatching {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        }.onFailure { e ->
            android.util.Log.e("AppCheckInit", "Failed to install debug App Check provider", e)
        }
    }
}
