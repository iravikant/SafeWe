package com.safewe.ui.child

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.safewe.usage.UsageCollector
import com.safewe.usage.UsageWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.data.local.LocationDao
import com.safewe.data.remote.Pairing
import com.safewe.data.remote.PairingRepository
import com.safewe.service.LocationService
import com.safewe.ui.IconBadge
import com.safewe.ui.SectionCard
import com.safewe.ui.StatusPill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChildState(
    val pairing: Pairing? = null,
    val error: String? = null,
    val localPoints: Int = 0
)

@HiltViewModel
class ChildViewModel @Inject constructor(
    private val repo: PairingRepository,
    dao: LocationDao,
    private val usage: UsageCollector,
    private val screenState: com.safewe.screen.ScreenShareState,
    private val probe: com.safewe.data.remote.HealthProbe,
    @ApplicationContext private val app: Context
) : ViewModel() {
    val screenSharing = screenState.active
    val screenMessage = screenState.message
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<ChildState> = combine(
        repo.observeMyPairing(), dao.observeLatest10(), error
    ) { p, pts, e -> ChildState(p, e, pts.size) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChildState())

    fun approve(code: String) = viewModelScope.launch {
        error.value = null
        runCatching { repo.approveAndPair(code.filter { it.isDigit() }) }
            .onFailure { error.value = it.message ?: "Pairing failed" }
    }

    fun revoke() = viewModelScope.launch {
        runCatching { repo.revoke() }
        UsageWorker.cancel(app)
        com.safewe.camera.CameraSessionService.stop(app)
        com.safewe.screen.ScreenShareService.stop(app) // monitoring ends with the pairing
        usage.clear() // paired data is removed from this device when monitoring ends
    }

    fun usageAccess() = usage.hasAccess()

    fun health(sharing: Boolean, paired: Boolean) = com.safewe.health.DeviceHealth.evaluate(probe.read(sharing, usageWanted = paired))

    fun onUsageReady() {
        UsageWorker.schedule(app)
        viewModelScope.launch(Dispatchers.IO) { runCatching { usage.collectRecent() } }
    }
}
