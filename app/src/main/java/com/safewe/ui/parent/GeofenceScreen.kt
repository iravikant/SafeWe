package com.safewe.ui.parent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import com.safewe.data.remote.*
import com.safewe.ui.EmptyState
import com.safewe.ui.GradientButton
import com.safewe.ui.GradientCard
import com.safewe.ui.GradientProgress
import com.safewe.ui.IconBadge
import com.safewe.ui.fieldColors
import com.safewe.ui.theme.Brand
import com.safewe.ui.theme.LocalDarkTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt

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


// ============================================================ map helpers

/** Muted purple-black map style used in dark mode. */
private const val DARK_MAP_STYLE = """[
{"elementType":"geometry","stylers":[{"color":"#1a1730"}]},
{"elementType":"labels.text.fill","stylers":[{"color":"#a79fc4"}]},
{"elementType":"labels.text.stroke","stylers":[{"color":"#100d1c"}]},
{"featureType":"administrative","elementType":"geometry.stroke","stylers":[{"color":"#3a3556"}]},
{"featureType":"landscape","elementType":"geometry","stylers":[{"color":"#17142a"}]},
{"featureType":"poi","stylers":[{"visibility":"off"}]},
{"featureType":"transit","stylers":[{"visibility":"off"}]},
{"featureType":"road","elementType":"geometry","stylers":[{"color":"#2c2745"}]},
{"featureType":"water","elementType":"geometry","stylers":[{"color":"#0a0813"}]}
]"""

private val SafeColor = Color(0xFF8B5CF6)
private val RestrictedColor = Color(0xFFEF4444)

private fun zoomFor(radius: Double): Float = (16.5 - ln(radius / 100.0) / ln(2.0)).coerceIn(10.0, 17.0).toFloat()

private fun circleExtent(f: GeofenceConfig): List<LatLng> {
    val dLat = f.radius / 111_320.0
    val dLon = f.radius / (111_320.0 * cos(Math.toRadians(f.lat)).coerceAtLeast(0.01))
    return listOf(LatLng(f.lat + dLat, f.lon), LatLng(f.lat - dLat, f.lon), LatLng(f.lat, f.lon + dLon), LatLng(f.lat, f.lon - dLon))
}

private fun boundsUpdate(points: List<LatLng>): CameraUpdate {
    val b = LatLngBounds.Builder()
    points.forEach { b.include(it) }
    return CameraUpdateFactory.newLatLngBounds(b.build(), 140)
}

private fun fenceUpdate(f: GeofenceConfig): CameraUpdate = when {
    f.shape == "CIRCLE" -> CameraUpdateFactory.newLatLngZoom(LatLng(f.lat, f.lon), zoomFor(f.radius))
    f.points.size >= 2 -> boundsUpdate(f.points.map { LatLng(it.lat, it.lon) })
    else -> CameraUpdateFactory.newLatLngZoom(LatLng(f.lat, f.lon), 15f)
}

private fun allUpdate(fences: List<GeofenceConfig>): CameraUpdate? {
    val pts = fences.flatMap { f -> if (f.shape == "CIRCLE") circleExtent(f) else f.points.map { p -> LatLng(p.lat, p.lon) } }
    return if (pts.isEmpty()) null else boundsUpdate(pts)
}

private fun fmtRadius(m: Double): String = if (m >= 1000) "%.1f km".format(m / 1000.0) else "${m.roundToInt()} m"

private fun zoneLabel(zone: String) = if (zone == "SAFE") "Safe zone" else "Restricted"

private fun zoneSummary(f: GeofenceConfig): String =
    zoneLabel(f.zone) + " · " + if (f.shape == "CIRCLE") "${fmtRadius(f.radius)} radius" else "${f.points.size} corners"

// ============================================================ screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceScreen(onBack: () -> Unit, vm: GeofenceViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val dark = LocalDarkTheme.current
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(20.0, 0.0), 2f) }

    // Draft zone
    var showForm by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    var shape by remember { mutableStateOf("CIRCLE") }
    var zone by remember { mutableStateOf("SAFE") }
    var name by remember { mutableStateOf("") }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var radius by remember { mutableFloatStateOf(200f) }
    var poly by remember { mutableStateOf(listOf<LatLng>()) }

    // Screen state
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var satellite by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var mapLoaded by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<GeofenceConfig?>(null) }
    var editing by remember { mutableStateOf<GeofenceConfig?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val formState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val peek by animateDpAsState(if (drawing) 0.dp else 150.dp + navBottom, label = "peek")
    val maxSheet = (LocalConfiguration.current.screenHeightDp * 0.62f).dp

    val hasDraft = center != null || poly.isNotEmpty()
    val duplicate = st.fences.any { it.name.equals(name.trim(), ignoreCase = true) }
    val canSave = name.isNotBlank() && if (shape == "CIRCLE") center != null else poly.size >= 3

    fun resetDraft() { name = ""; center = null; poly = emptyList(); radius = 200f; shape = "CIRCLE"; zone = "SAFE" }
    fun cancelDrawing() { drawing = false; resetDraft() }
    fun requestCancel() { if (hasDraft) confirmDiscard = true else cancelDrawing() }
    fun flyTo(f: GeofenceConfig) {
        selectedId = f.id
        scope.launch {
            sheetState.partialExpand()
            runCatching { camera.animate(fenceUpdate(f), 700) }
        }
    }
    fun centreOnChild() {
        if (locating) return
        locating = true
        scope.launch {
            val p = vm.childPosition()
            locating = false
            if (p != null) runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(p, 16f), 700) }
            else notice = "Couldn't reach your child's phone. Check that sharing is on."
        }
    }

    // Fit every existing zone into view once the map and the zones have loaded.
    LaunchedEffect(mapLoaded, st.fences) {
        if (mapLoaded && !fitted && st.fences.isNotEmpty()) {
            fitted = true
            allUpdate(st.fences)?.let { u -> runCatching { camera.animate(u, 800) } }
        }
    }
    LaunchedEffect(drawing) { if (drawing) sheetState.partialExpand() }
    LaunchedEffect(st.message) { st.message?.let { notice = it } }
    LaunchedEffect(notice) { if (notice != null) { delay(3500); notice = null } }

    BackHandler(enabled = drawing) { requestCancel() }
    BackHandler(enabled = !drawing && sheetState.targetValue == SheetValue.Expanded) { scope.launch { sheetState.partialExpand() } }

    val mapProps = remember(satellite, dark) {
        MapProperties(
            mapType = if (satellite) MapType.HYBRID else MapType.NORMAL,
            mapStyleOptions = if (dark && !satellite) MapStyleOptions(DARK_MAP_STYLE) else null
        )
    }
    val mapUi = remember { MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, compassEnabled = true) }
    val draftColor = if (dark || satellite) Color.White else Color(0xFF1B1530)

    Box(Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peek,
            sheetSwipeEnabled = !drawing,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetTonalElevation = 0.dp,
            sheetShadowElevation = 12.dp,
            containerColor = Color.Transparent,
            sheetContent = {
                ZonesSheet(
                    maxHeight = maxSheet, fences = st.fences, alerts = st.alerts, tab = tab, onTab = { tab = it },
                    selectedId = selectedId,
                    onAdd = { resetDraft(); showForm = true },
                    onSelect = { flyTo(it) }, onEdit = { editing = it }, onDelete = { deleting = it },
                    onDismissAlert = { vm.clearAlert(it) }
                )
            }
        ) { pad ->
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                properties = mapProps,
                uiSettings = mapUi,
                contentPadding = PaddingValues(
                    top = statusTop + 64.dp,
                    bottom = if (drawing) 260.dp + navBottom else pad.calculateBottomPadding()
                ),
                onMapLoaded = { mapLoaded = true },
                onMapClick = { p ->
                    if (drawing) { if (shape == "CIRCLE") center = p else poly = poly + p }
                    else scope.launch { sheetState.partialExpand() }
                }
            ) {
                val violet = remember { BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET) }
                st.fences.forEach { f ->
                    val c = if (f.zone == "SAFE") SafeColor else RestrictedColor
                    val w = if (f.id == selectedId) 9f else 4f
                    if (f.shape == "CIRCLE") Circle(LatLng(f.lat, f.lon), radius = f.radius, fillColor = c.copy(alpha = 0.22f), strokeColor = c, strokeWidth = w)
                    else if (f.points.size >= 3) Polygon(f.points.map { LatLng(it.lat, it.lon) }, fillColor = c.copy(alpha = 0.22f), strokeColor = c, strokeWidth = w)
                }
                if (drawing) {
                    if (shape == "CIRCLE") center?.let {
                        Circle(it, radius = radius.toDouble(), fillColor = draftColor.copy(alpha = 0.18f), strokeColor = draftColor, strokeWidth = 6f)
                        Marker(MarkerState(it), icon = violet)
                    } else {
                        if (poly.size >= 3) Polygon(poly, fillColor = draftColor.copy(alpha = 0.18f), strokeColor = draftColor, strokeWidth = 6f)
                        else if (poly.size == 2) Polyline(poly, color = draftColor, width = 6f)
                        poly.forEach { Marker(MarkerState(it), icon = violet) }
                    }
                }
            }
        }

        // ---- floating top bar
        Row(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MapButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = { if (drawing) requestCancel() else onBack() })
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (drawing) "New zone" else "Zones", style = MaterialTheme.typography.titleMedium)
                    if (!drawing && st.fences.isNotEmpty()) {
                        Text("  ·  ${st.fences.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }

        // ---- floating map controls
        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MapButton(Icons.Filled.MyLocation, "Centre map on child", onClick = { centreOnChild() }, loading = locating)
            MapButton(Icons.Filled.Layers, if (satellite) "Switch to standard map" else "Switch to satellite map", onClick = { satellite = !satellite }, active = satellite)
            MapButton(Icons.Filled.Info, "How zones work", onClick = { showHelp = true })
        }

        // ---- transient notice
        notice?.let {
            Surface(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 70.dp, start = 12.dp, end = 70.dp),
                shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp
            ) {
                Text(it, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }

        // ---- drawing panel
        AnimatedVisibility(
            visible = drawing, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()
        ) {
            DrawPanel(
                name = name.trim(), zone = zone, shape = shape,
                radius = radius, onRadius = { radius = (it / 10f).roundToInt() * 10f },
                hasCenter = center != null, corners = poly.size, canSave = canSave,
                onUndo = { if (shape == "CIRCLE") center = null else poly = poly.dropLast(1) },
                onCancel = { requestCancel() },
                onSave = {
                    vm.save(
                        GeofenceConfig(
                            name = name.trim(), shape = shape, zone = zone,
                            lat = center?.latitude ?: 0.0, lon = center?.longitude ?: 0.0, radius = radius.toDouble(),
                            points = if (shape == "POLYGON") poly.map { GeoPointDto(it.latitude, it.longitude) } else emptyList()
                        )
                    )
                    drawing = false; resetDraft(); tab = 0; notice = "Zone saved"
                },
                modifier = Modifier.navigationBarsPadding().padding(12.dp)
            )
        }
    }

    // ---- New zone: modal bottom sheet
    if (showForm) ModalBottomSheet(onDismissRequest = { showForm = false }, sheetState = formState) {
        NewZoneForm(
            name = name, onName = { name = it }, zone = zone, onZone = { zone = it }, shape = shape, onShape = { shape = it },
            duplicate = duplicate,
            onContinue = {
                scope.launch { formState.hide() }.invokeOnCompletion {
                    showForm = false; drawing = true; center = null; poly = emptyList()
                }
            }
        )
    }

    // ---- Alert dialogs
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${f.name}”?") },
            text = { Text("You will stop getting alerts for this zone. This can't be undone.") },
            confirmButton = {
                TextButton({ vm.delete(f.id); if (selectedId == f.id) selectedId = null; deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }
        )
    }
    editing?.let { f ->
        var n by remember(f.id) { mutableStateOf(f.name) }
        var z by remember(f.id) { mutableStateOf(f.zone) }
        val clash = st.fences.any { it.id != f.id && it.name.equals(n.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit zone") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        n, { n = it.take(30) }, label = { Text("Zone name") }, singleLine = true, isError = clash,
                        supportingText = { if (clash) Text("You already have a zone with this name.") },
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, colors = fieldColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(z == "SAFE", { z = "SAFE" }, { Text("Safe zone") })
                        FilterChip(z == "RESTRICTED", { z = "RESTRICTED" }, { Text("Restricted") })
                    }
                }
            },
            confirmButton = {
                TextButton({ vm.save(f.copy(name = n.trim(), zone = z)); editing = null }, enabled = n.isNotBlank() && !clash) { Text("Save") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } }
        )
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard this zone?") },
        text = { Text("What you have marked on the map won't be saved.") },
        confirmButton = { TextButton({ confirmDiscard = false; cancelDrawing() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("Keep drawing") } }
    )
    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text("How zones work") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Safe zone: a place your child is expected to be, like home or school.")
                Text("Restricted: a place your child should avoid.")
                Text(
                    "Circles are monitored by Google Play services. Polygons are checked whenever the child's phone reports a location, " +
                        "so alerts can be delayed by several minutes.",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        },
        confirmButton = { TextButton({ showHelp = false }) { Text("Got it") } }
    )
}

// ============================================================ floating map button

@Composable
private fun MapButton(icon: ImageVector, desc: String, onClick: () -> Unit, active: Boolean = false, loading: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, modifier = Modifier.size(46.dp), shape = CircleShape,
        color = cs.surface.copy(alpha = 0.94f), shadowElevation = 6.dp,
        border = BorderStroke(1.dp, if (active) cs.primary else cs.outlineVariant)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(icon, desc, tint = if (active) cs.primary else cs.onSurface)
        }
    }
}

// ============================================================ persistent sheet: zones + alerts

@Composable
private fun ZonesSheet(
    maxHeight: Dp, fences: List<GeofenceConfig>, alerts: List<GeofenceAlert>, tab: Int, onTab: (Int) -> Unit,
    selectedId: String?, onAdd: () -> Unit, onSelect: (GeofenceConfig) -> Unit, onEdit: (GeofenceConfig) -> Unit,
    onDelete: (GeofenceConfig) -> Unit, onDismissAlert: (String) -> Unit
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Zones", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (fences.isEmpty()) "Nothing set up yet" else "${fences.size} active · tap one to view it",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
                )
            }
            GradientButton("Add zone", onAdd, Modifier.width(138.dp), icon = Icons.Filled.Add, height = 46.dp)
        }
        Spacer(Modifier.height(12.dp))
        PillToggle(listOf("Zones", if (alerts.isEmpty()) "Alerts" else "Alerts (${alerts.size})"), tab, onTab)
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = 24.dp + navBottom),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (tab == 0) {
                if (fences.isEmpty()) item {
                    EmptyState(Icons.Filled.Place, "No zones yet", "Add a safe zone like home or school to get arrival and departure alerts.")
                }
                items(fences) { f -> ZoneRow(f, f.id == selectedId, { onSelect(f) }, { onEdit(f) }, { onDelete(f) }) }
            } else {
                if (alerts.isEmpty()) item {
                    EmptyState(Icons.Filled.Notifications, "No alerts", "Arrivals and departures will show up here.")
                }
                items(alerts.take(30)) { a -> AlertRow(a) { onDismissAlert(a.id) } }
            }
        }
    }
}

@Composable
private fun ZoneRow(f: GeofenceConfig, selected: Boolean, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val safe = f.zone == "SAFE"
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.10f) else cs.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, if (selected) cs.primary.copy(alpha = 0.6f) else cs.outlineVariant, shape)
            .clickable(onClick = onClick).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(if (safe) Icons.Filled.Place else Icons.Filled.Warning, if (safe) cs.primary else cs.error, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(zoneSummary(f), style = MaterialTheme.typography.bodySmall, color = cs.secondary)
        }
        IconButton(onEdit) { Icon(Icons.Filled.Edit, "Edit ${f.name}", tint = cs.secondary) }
        IconButton(onDelete) { Icon(Icons.Filled.Delete, "Delete ${f.name}", tint = cs.error) }
    }
}

@Composable
private fun AlertRow(a: GeofenceAlert, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val what = when (a.transition) { "ENTER" -> "Arrived at"; "EXIT" -> "Left"; else -> a.transition.lowercase() }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(cs.surfaceVariant.copy(alpha = 0.45f)).border(1.dp, cs.outlineVariant, shape)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(Icons.Filled.Notifications, if (a.zone == "SAFE") cs.primary else cs.error, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("$what ${a.name}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.timestamp)),
                style = MaterialTheme.typography.bodySmall, color = cs.secondary
            )
        }
        IconButton(onDismiss) { Icon(Icons.Filled.Close, "Dismiss alert", tint = cs.secondary) }
    }
}

@Composable
private fun PillToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(38.dp).clip(CircleShape)
                    .then(if (on) Modifier.background(Brand.button) else Modifier)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) Color.White else MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

// ============================================================ new zone form (modal bottom sheet)

@Composable
private fun NewZoneForm(
    name: String, onName: (String) -> Unit, zone: String, onZone: (String) -> Unit,
    shape: String, onShape: (String) -> Unit, duplicate: Boolean, onContinue: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("New zone", style = MaterialTheme.typography.titleLarge)
            Text("Choose what it is, then mark it on the map.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionCard(zone == "SAFE", Icons.Filled.Place, "Safe zone", "Home, school, a friend's house", { onZone("SAFE") }, Modifier.weight(1f))
            OptionCard(zone == "RESTRICTED", Icons.Filled.Warning, "Restricted", "Somewhere to avoid", { onZone("RESTRICTED") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionCard(shape == "CIRCLE", Icons.Filled.RadioButtonUnchecked, "Circle", "A centre and a radius", { onShape("CIRCLE") }, Modifier.weight(1f))
            OptionCard(shape == "POLYGON", Icons.Filled.Timeline, "Polygon", "Trace the boundary", { onShape("POLYGON") }, Modifier.weight(1f))
        }
        OutlinedTextField(
            name, { onName(it.take(30)) }, label = { Text("Zone name") }, singleLine = true, isError = duplicate,
            supportingText = { Text(if (duplicate) "You already have a zone with this name." else "${name.length}/30") },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, colors = fieldColors()
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Home", "School", "Tuition", "Relative's home", "Park").forEach { preset ->
                AssistChip({ onName(preset) }, { Text(preset) })
            }
        }
        GradientButton("Continue to map", onContinue, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && !duplicate)
    }
}

@Composable
private fun OptionCard(selected: Boolean, icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Column(
        modifier.clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outline, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (selected) cs.primary else cs.secondary)
            Spacer(Modifier.weight(1f))
            if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = cs.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.secondary)
    }
}

// ============================================================ drawing panel

@Composable
private fun DrawPanel(
    name: String, zone: String, shape: String, radius: Float, onRadius: (Float) -> Unit,
    hasCenter: Boolean, corners: Int, canSave: Boolean,
    onUndo: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val safe = zone == "SAFE"
    GradientCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (safe) Icons.Filled.Place else Icons.Filled.Warning, if (safe) cs.primary else cs.error, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(zoneLabel(zone) + " · " + if (shape == "CIRCLE") "Circle" else "Polygon", style = MaterialTheme.typography.bodySmall, color = cs.secondary)
            }
            IconButton(onCancel) { Icon(Icons.Filled.Close, "Cancel", tint = cs.secondary) }
        }
        if (shape == "CIRCLE") {
            if (!hasCenter) {
                Text("Tap the map to place the centre of the zone.", style = MaterialTheme.typography.bodyMedium, color = cs.secondary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Radius", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.secondary)
                    Text(fmtRadius(radius.toDouble()), style = MaterialTheme.typography.titleMedium, color = cs.primary)
                }
                Slider(radius, onRadius, valueRange = 100f..2000f)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100f, 250f, 500f, 1000f).forEach { v ->
                        FilterChip(radius.roundToInt() == v.roundToInt(), { onRadius(v) }, { Text(fmtRadius(v.toDouble())) })
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Corners", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.secondary)
                Text("$corners of 3 minimum", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
            GradientProgress((corners / 3f).coerceAtMost(1f), height = 8.dp)
            Text(
                if (corners < 3) "Tap the map to add each corner of the boundary." else "Keep tapping to refine the boundary, or save it.",
                style = MaterialTheme.typography.bodySmall, color = cs.secondary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onUndo, Modifier.height(54.dp), enabled = hasCenter || corners > 0, shape = RoundedCornerShape(16.dp)
            ) { Text(if (shape == "CIRCLE") "Clear" else "Undo") }
            GradientButton("Save zone", onSave, Modifier.weight(1f), enabled = canSave)
        }
    }
}
