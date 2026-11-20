package com.safewe

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.safewe.vault.VaultRepository
import javax.inject.Inject
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safewe.ui.nav.AppNav
import com.safewe.ui.theme.SafeWeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var vault: VaultRepository
    private var openCamera by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Auto-lock: the vault key is wiped when the app has been in the background long enough.
        lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> vault.onBackgrounded()
                Lifecycle.Event.ON_START -> vault.onForegrounded()
                else -> Unit
            }
        })
        enableEdgeToEdge()
        openCamera = intent.getBooleanExtra(EXTRA_OPEN_CAMERA, false)
        setContent { SafeWeTheme { AppNav(openCamera = openCamera, onHandled = { openCamera = false }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CAMERA, false)) openCamera = true
    }

    companion object { const val EXTRA_OPEN_CAMERA = "open_camera" }
}
