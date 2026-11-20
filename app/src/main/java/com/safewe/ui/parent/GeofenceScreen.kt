package com.safewe.ui.parent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.safewe.data.remote.*
import com.safewe.ui.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class ZonesState(
    val fences: List<GeofenceConfig> = emptyList(),
    val alerts: List<GeofenceAlert> = emptyList(),
    val message: String? = null
)

@HiltViewModel
class GeofenceViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: GeofenceRepository,
    private val commands: CommandRepository
) : ViewModel() {
    val childUid: String = checkNotNull(saved["childUid"])
    private val msg = MutableStateFlow<String?>(null)

    val state: StateFlow<ZonesState> = combine(repo.observe(childUid), repo.observeAlerts(childUid), msg) { f, a, m ->
        ZonesState(f, a, m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ZonesState())

    fun save(cfg: GeofenceConfig) = viewModelScope.launch {
        runCatching { repo.save(childUid, cfg) }.onFailure { msg.value = it.message }
    }
    fun delete(id: String) = viewModelScope.launch { runCatching { repo.delete(childUid, id) } }
    fun clearAlert(id: String) = viewModelScope.launch { runCatching { repo.deleteAlert(childUid, id) } }

    /** Optional helper to centre the map on the child. */
    suspend fun childPosition(): LatLng? = runCatching {
        val id = commands.requestLocation(childUid)
        val r = withTimeoutOrNull(45_000) { commands.observeResult(childUid, id).filterNotNull().first() }
        runCatching { commands.cleanup(childUid, id) }
        r?.let { LatLng(it.latitude, it.longitude) }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceScreen(onBack: () -> Unit, vm: GeofenceViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(20.0, 0.0), 2f) }

    var shape by remember { mutableStateOf("CIRCLE") }
    var zone by remember { mutableStateOf("SAFE") }
    var name by remember { mutableStateOf("") }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var radius by remember { mutableFloatStateOf(200f) }
    var poly by remember { mutableStateOf(listOf<LatLng>()) }

    val canSave = name.isNotBlank() &&
        if (shape == "CIRCLE") center != null else poly.size >= 3

    fun reset() { name = ""; center = null; poly = emptyList() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Zones") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard {
                GoogleMap(
                    Modifier.fillMaxWidth().height(300.dp), cameraPositionState = camera,
                    uiSettings = MapUiSettings(zoomControlsEnabled = false),
                    onMapClick = { p -> if (shape == "CIRCLE") center = p else poly = poly + p }
                ) {
                    st.fences.forEach { f ->
                        val c = if (f.zone == "SAFE") Color(0xFF0F9D8A) else Color(0xFFD64545)
                        if (f.shape == "CIRCLE") Circle(LatLng(f.lat, f.lon), radius = f.radius, fillColor = c.copy(alpha = 0.2f), strokeColor = c)
                        else if (f.points.size >= 3) Polygon(f.points.map { LatLng(it.lat, it.lon) }, fillColor = c.copy(alpha = 0.2f), strokeColor = c)
                    }
                    val draft = Color(0xFF3F51B5)
                    if (shape == "CIRCLE") center?.let { Circle(it, radius = radius.toDouble(), fillColor = draft.copy(alpha = 0.2f), strokeColor = draft) }
                    else if (poly.size >= 3) Polygon(poly, fillColor = draft.copy(alpha = 0.2f), strokeColor = draft)
                    else if (poly.size == 2) Polyline(poly, color = draft)
                    poly.forEach { Marker(MarkerState(it)) }
                }
                OutlinedButton(onClick = { scope.launch { vm.childPosition()?.let { camera.position = CameraPosition.fromLatLngZoom(it, 15f) } } }) {
                    Text("Centre map on child")
                }
            }

            SectionCard {
                Text("New zone", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("CIRCLE" to "Circle", "POLYGON" to "Polygon").forEachIndexed { i, (k, label) ->
                        SegmentedButton(shape == k, { shape = k }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("SAFE" to "Safe zone", "RESTRICTED" to "Restricted").forEachIndexed { i, (k, label) ->
                        SegmentedButton(zone == k, { zone = k }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. School)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                if (shape == "CIRCLE") {
                    Text("Tap the map to place the centre. Radius: ${radius.toInt()} m", style = MaterialTheme.typography.bodyMedium)
                    Slider(radius, { radius = it }, valueRange = 100f..2000f)
                } else {
                    Text("Tap the map to add corners (${poly.size} so far, 3 needed).", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { poly = poly.dropLast(1) }, enabled = poly.isNotEmpty()) { Text("Undo last corner") }
                }
                Button(
                    enabled = canSave, modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = {
                        vm.save(
                            GeofenceConfig(
                                name = name.trim(), shape = shape, zone = zone,
                                lat = center?.latitude ?: 0.0, lon = center?.longitude ?: 0.0, radius = radius.toDouble(),
                                points = if (shape == "POLYGON") poly.map { GeoPointDto(it.latitude, it.longitude) } else emptyList()
                            )
                        ); reset()
                    }
                ) { Text("Save zone") }
                Text(
                    "Circles are monitored by Google Play services. Polygons are checked whenever the child's phone reports a location, " +
                        "so alerts can be delayed by several minutes.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
                )
            }

            SectionCard {
                Text("Your zones", style = MaterialTheme.typography.titleMedium)
                if (st.fences.isEmpty()) Text("None yet.", color = MaterialTheme.colorScheme.secondary)
                st.fences.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${f.shape.lowercase()} · ${f.zone.lowercase()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = { vm.delete(f.id) }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }

            SectionCard {
                Text("Recent alerts", style = MaterialTheme.typography.titleMedium)
                if (st.alerts.isEmpty()) Text("No alerts.", color = MaterialTheme.colorScheme.secondary)
                st.alerts.take(20).forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${a.name}: ${a.transition.lowercase()}", style = MaterialTheme.typography.bodyLarge)
                            Text(DateFormat.getDateTimeInstance().format(Date(a.timestamp)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                        TextButton(onClick = { vm.clearAlert(a.id) }) { Text("Dismiss") }
                    }
                }
            }
            st.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
