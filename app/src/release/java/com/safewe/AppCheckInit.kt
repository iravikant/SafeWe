package com.safewe

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds attest with Play Integrity (free). Turn on "Enforce" in the Firebase console once it works. */
object AppCheckInit {
    fun install() {
        runCatching {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }.onFailure { e ->
            android.util.Log.e("AppCheckInit", "Failed to install Play Integrity App Check provider", e)
        }
    }
}
