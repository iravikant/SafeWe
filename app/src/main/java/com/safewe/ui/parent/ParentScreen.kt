package com.safewe.ui.parent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.safewe.data.remote.*
import com.safewe.ui.SectionCard
import com.safewe.ui.StatusPill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class ParentState(
    val children: List<Pairing> = emptyList(),
    val code: String? = null,
    val requesting: Boolean = false,
    val result: LocationResult? = null,
    val history: List<HistoryPoint> = emptyList(),
    val statuses: Map<String, DeviceStatus> = emptyMap(),
    val selected: String? = null,
    val names: Map<String, String> = emptyMap(),
    val message: String? = null
)

@HiltViewModel
class ParentViewModel @Inject constructor(
    private val pairing: PairingRepository,
    private val commands: CommandRepository,
    private val statusRepo: StatusRepository,
    private val deviceNames: com.safewe.data.DeviceNames
) : ViewModel() {
    private val statusJobs = HashMap<String, kotlinx.coroutines.Job>()
    private val _s = MutableStateFlow(ParentState(names = deviceNames.all()))
    val state: StateFlow<ParentState> = _s

    init {
        viewModelScope.launch {
            pairing.observeChildren().collect { c ->
                _s.update { it.copy(children = c) }
                statusJobs.keys.filter { id -> c.none { it.childUid == id } }.forEach { statusJobs.remove(it)?.cancel() }
                c.forEach { ch ->
                    if (statusJobs[ch.childUid] == null) statusJobs[ch.childUid] = viewModelScope.launch {
                        statusRepo.observe(ch.childUid).collect { st -> st?.let { v -> _s.update { it.copy(statuses = it.statuses + (ch.childUid to v)) } } }
                    }
                }
                c.filter { it.parentSigningKey.isBlank() }.forEach { runCatching { pairing.publishParentKeys(it.childUid) } }
            }
        }
    }

    fun select(childUid: String) = _s.update { it.copy(selected = childUid, result = null, history = emptyList(), message = null) }

    fun rename(childUid: String, name: String) {
        if (name.isBlank()) return
        deviceNames.set(childUid, name)
        _s.update { it.copy(names = deviceNames.all()) }
    }

    fun unpair(childUid: String) = viewModelScope.launch {
        runCatching { pairing.unpair(childUid) }.onFailure { e -> _s.update { it.copy(message = e.message) } }
    }

    fun newCode() = viewModelScope.launch {
        runCatching { pairing.createCode() }
            .onSuccess { c -> _s.update { it.copy(code = c, message = null) } }
            .onFailure { e -> _s.update { it.copy(message = e.message) } }
    }

    private val noReply = "No response. The child device may be offline or sharing is stopped."

    fun getLocation(childUid: String) = viewModelScope.launch {
        _s.update { it.copy(requesting = true, message = null, result = null, history = emptyList()) }
        runCatching {
            val id = commands.requestLocation(childUid)
            val res = withTimeoutOrNull(RESULT_TIMEOUT_MS) { commands.observeResult(childUid, id).filterNotNull().first() }
            if (res != null) runCatching { commands.cleanup(childUid, id) }
            res
        }.onSuccess { r -> _s.update { it.copy(requesting = false, result = r, message = if (r == null) noReply else null) } }
            .onFailure { e -> _s.update { it.copy(requesting = false, message = e.message) } }
    }

    fun getHistory(childUid: String) = viewModelScope.launch {
        _s.update { it.copy(requesting = true, message = null, result = null, history = emptyList()) }
        runCatching {
            val id = commands.requestHistory(childUid)
            val res = withTimeoutOrNull(RESULT_TIMEOUT_MS) { commands.observeHistory(childUid, id).filterNotNull().first() }
            if (res != null) runCatching { commands.cleanup(childUid, id) } // remove temporary cloud copy
            res
        }.onSuccess { r ->
            _s.update { it.copy(requesting = false, history = r?.points.orEmpty(),
                message = when { r == null -> noReply; r.points.isEmpty() -> "The device has no saved locations yet."; else -> null }) }
        }.onFailure { e -> _s.update { it.copy(requesting = false, message = e.message) } }
    }

    companion object { const val RESULT_TIMEOUT_MS = 60_000L }
}

@Composable
internal fun StatusRow(s: DeviceStatus) {
    val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
    val presence = com.safewe.health.PresenceRules.of(s.sharing, s.updatedAt, now)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                when (presence) { com.safewe.health.Presence.ONLINE -> "Online"; com.safewe.health.Presence.PAUSED -> "Sharing paused"; com.safewe.health.Presence.OFFLINE -> "Offline"; else -> "Unknown" },
                presence == com.safewe.health.Presence.ONLINE
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Seen " + com.safewe.health.PresenceRules.ago(s.updatedAt, now) + if (s.battery >= 0) " · Battery ${s.battery}%" + (if (s.charging) " (charging)" else "") else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
            )
        }
        s.issues.mapNotNull { com.safewe.health.IssueCode.parse(it) }.forEach {
            Text("• " + it.parentMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
internal fun LocationCard(r: LocationResult) {
    val pos = LatLng(r.latitude, r.longitude)
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(pos, 15f) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Last known location", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusPill(if (r.fresh) "Fresh" else "Cached", r.fresh)
        }
        GoogleMap(Modifier.fillMaxWidth().height(220.dp), cameraPositionState = camera, uiSettings = MapUiSettings(zoomControlsEnabled = false)) {
            Marker(state = MarkerState(pos), title = "Child")
        }
        Text("%.5f, %.5f".format(r.latitude, r.longitude), style = MaterialTheme.typography.bodyLarge)
        Text("Accuracy ±${r.accuracy.toInt()} m · " + DateFormat.getDateTimeInstance().format(Date(r.timestamp)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
internal fun HistoryCard(points: List<HistoryPoint>) {
    val latLngs = points.map { LatLng(it.latitude, it.longitude) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(latLngs.first(), 14f) }
    LaunchedEffect(latLngs) {
        if (latLngs.size > 1) {
            val b = LatLngBounds.builder().also { bb -> latLngs.forEach { bb.include(it) } }.build()
            runCatching { camera.move(com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(b, 80)) }
        }
    }
    SectionCard {
        Text("Last ${points.size} locations", style = MaterialTheme.typography.titleMedium)
        GoogleMap(Modifier.fillMaxWidth().height(240.dp), cameraPositionState = camera, uiSettings = MapUiSettings(zoomControlsEnabled = false)) {
            Polyline(points = latLngs.reversed(), width = 6f)
            latLngs.forEachIndexed { i, p -> Marker(state = MarkerState(p), title = if (i == 0) "Newest" else "#${i + 1}") }
        }
        points.forEachIndexed { i, p ->
            Text("${i + 1}. " + DateFormat.getDateTimeInstance().format(Date(p.timestamp)) + " · ±${p.accuracy.toInt()} m",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
