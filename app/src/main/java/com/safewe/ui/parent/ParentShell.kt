package com.safewe.ui.parent

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.data.remote.DeviceStatus
import com.safewe.data.remote.GeofenceAlert
import com.safewe.data.remote.GeofenceRepository
import com.safewe.data.remote.Pairing
import com.safewe.health.IssueCode
import com.safewe.health.Presence
import com.safewe.health.PresenceRules
import com.safewe.security.SafetyCode
import com.safewe.transfer.CaptureType
import com.safewe.ui.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ActivityViewModel @Inject constructor(private val repo: GeofenceRepository) : ViewModel() {
    fun alerts(childUid: String): Flow<List<GeofenceAlert>> = repo.observeAlerts(childUid)
    fun dismiss(childUid: String, id: String) { viewModelScope.launch { runCatching { repo.deleteAlert(childUid, id) } } }
}

private fun nameOf(st: ParentState, c: Pairing): String =
    st.names[c.childUid] ?: "Child ${st.children.indexOfFirst { it.childUid == c.childUid } + 1}"

private data class Tile(val icon: ImageVector, val label: String, val enabled: Boolean, val onClick: () -> Unit)

/** Parent home: Family (actions), Activity (zone alerts), Settings (devices, usage budget, account). */
@Composable
fun ParentShell(
    onOpenZones: (String) -> Unit, onOpenUsage: (String) -> Unit, onSwitchRole: () -> Unit, onSignOut: () -> Unit,
    vm: ParentViewModel = hiltViewModel()
) {
    val st by vm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val child = st.children.firstOrNull { it.childUid == st.selected } ?: st.children.firstOrNull()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                listOf("Family" to Icons.Filled.Home, "Activity" to Icons.Filled.Notifications, "Settings" to Icons.Filled.Settings).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Crossfade(tab, label = "parent-tab") { t ->
                when (t) {
                    0 -> FamilyTab(st, child, vm, onOpenZones, onOpenUsage)
                    1 -> ActivityTab(st, child)
                    else -> SettingsTab(st, vm, onSwitchRole, onSignOut)
                }
            }
        }
    }
}

// ============================================================ Family

@Composable
private fun FamilyTab(st: ParentState, child: Pairing?, vm: ParentViewModel, onOpenZones: (String) -> Unit, onOpenUsage: (String) -> Unit) {
    val capVm: CaptureViewModel = hiltViewModel()
    val cap by capVm.state.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Family", style = MaterialTheme.typography.headlineMedium)

        if (child == null) {
            EmptyState(Icons.Filled.PhoneAndroid, "Add your child's phone", "Create a one-time code, then enter it in SafeWe on their phone. They approve it before anything is shared.")
            AddDeviceCard(st, vm)
            return@Column
        }

        if (st.children.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                st.children.forEach { c ->
                    FilterChip(c.childUid == child.childUid, { vm.select(c.childUid) }, { Text(nameOf(st, c)) }, leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(18.dp)) })
                }
            }
        }

        // ---- hero: who, are they reachable, anything wrong
        HeroCard {
            Text(nameOf(st, child), style = MaterialTheme.typography.titleLarge)
            HeroStatus(st.statuses[child.childUid])
            SafetyCode.compute(child.parentAgreementKey, child.parentSigningKey, child.childAgreementKey, child.childSigningKey)?.let {
                Text("Security code  $it", style = MaterialTheme.typography.bodyMedium)
                Text("Check that it matches the code on your child's phone.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---- quick actions
        val busy = st.requesting || cap.busy
        val keysReady = child.parentAgreementKey.isNotBlank() && child.childSigningKey.isNotBlank()
        val tiles = listOf(
            Tile(Icons.Filled.MyLocation, "Locate now", !busy) { vm.getLocation(child.childUid) },
            Tile(Icons.Filled.History, "Last 10 places", !busy) { vm.getHistory(child.childUid) },
            Tile(Icons.Filled.PhotoCamera, "Request photo", !busy && keysReady) { capVm.request(child, CaptureType.CAMERA) },
            Tile(Icons.Filled.Smartphone, "Request screenshot", !busy && keysReady) { capVm.request(child, CaptureType.SCREENSHOT) },
            Tile(Icons.Filled.Fence, "Safe zones", true) { onOpenZones(child.childUid) },
            Tile(Icons.Filled.BarChart, "App usage", true) { onOpenUsage(child.childUid) }
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t -> ActionTile(t.icon, t.label, t.onClick, Modifier.weight(1f), t.enabled) }
            }
        }

        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (cap.busy) cap.status ?: "Working…" else "Waiting for your child's phone…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "Photos and screenshots require the corresponding sharing session on your child's phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
            )
        }

        // ---- results
        st.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        st.result?.let { LocationCard(it) }
        if (st.history.isNotEmpty()) HistoryCard(st.history)
        CaptureResult(cap, capVm::dismiss)
    }
}

@Composable
private fun HeroStatus(s: DeviceStatus?) {
    val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
    if (s == null) { Text("No status yet. It appears once your child turns sharing on.", style = MaterialTheme.typography.bodyMedium); return }
    val presence = PresenceRules.of(s.sharing, s.updatedAt, now)
    val label = when (presence) { Presence.ONLINE -> "Online"; Presence.PAUSED -> "Sharing paused"; Presence.OFFLINE -> "Offline"; Presence.UNKNOWN -> "Unknown" }
    Text(
        "$label · seen ${PresenceRules.ago(s.updatedAt, now)}" + if (s.battery >= 0) " · battery ${s.battery}%" + (if (s.charging) " (charging)" else "") else "",
        style = MaterialTheme.typography.bodyLarge
    )
    s.issues.mapNotNull { IssueCode.parse(it) }.forEach { Text("• " + it.parentMessage, style = MaterialTheme.typography.bodyMedium) }
}

// ============================================================ Activity

@Composable
private fun ActivityTab(st: ParentState, child: Pairing?) {
    val vm: ActivityViewModel = hiltViewModel()
    val alerts by remember(child?.childUid) { child?.let { vm.alerts(it.childUid) } ?: emptyFlow<List<GeofenceAlert>>() }.collectAsState(emptyList())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Activity", style = MaterialTheme.typography.headlineMedium)
        if (child == null) { EmptyState(Icons.Filled.Notifications, "Nothing yet", "Safe-zone alerts will show up here once a device is paired."); return@Column }
        Text("Safe-zone alerts for ${nameOf(st, child)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        if (alerts.isEmpty()) EmptyState(Icons.Filled.NotificationsNone, "No alerts", "You'll see when your child arrives at or leaves a safe zone, or enters a restricted one.")
        alerts.take(50).forEach { a ->
            val verb = when (a.transition) { "ENTER" -> "arrived at"; "EXIT" -> "left"; else -> "stayed at" }
            FeatureRow(
                icon = if (a.zone == "RESTRICTED") Icons.Filled.Warning else Icons.Filled.Place,
                title = "${nameOf(st, child)} $verb ${a.name}",
                subtitle = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.timestamp)),
                trailing = { TextButton({ vm.dismiss(child.childUid, a.id) }) { Text("Dismiss") } }
            )
        }
    }
}

// ============================================================ Settings

@Composable
private fun SettingsTab(st: ParentState, vm: ParentViewModel, onSwitchRole: () -> Unit, onSignOut: () -> Unit) {
    var renaming by remember { mutableStateOf<Pairing?>(null) }
    var unpairing by remember { mutableStateOf<Pairing?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionLabel("DEVICES")
        AddDeviceCard(st, vm)
        st.children.forEach { c ->
            SectionCard {
                Text(nameOf(st, c), style = MaterialTheme.typography.titleMedium)
                st.statuses[c.childUid]?.let { StatusRow(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ renaming = c }) { Text("Rename") }
                    OutlinedButton({ unpairing = c }) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        SectionLabel("CLOUD USAGE")
        QuotaCard()

        SectionLabel("ACCOUNT")
        SectionCard {
            OutlinedButton(onSwitchRole, Modifier.fillMaxWidth()) { Text("Switch to child mode on this phone") }
            OutlinedButton(onSignOut, Modifier.fillMaxWidth()) { Text("Sign out") }
        }
        SectionCard {
            Text("How SafeWe treats your family", style = MaterialTheme.typography.titleMedium)
            Text(
                "Everything is shared with consent. Your child approves the pairing, sees what is on, gets a notice for every photo or screenshot request and can unpair at any time. " +
                    "Photos and screenshots are encrypted end to end and deleted from the cloud right after you receive them. Your child's private space is never visible to you.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
            )
        }
    }

    renaming?.let { c -> RenameDialog(nameOf(st, c), { renaming = null }) { vm.rename(c.childUid, it); renaming = null } }
    unpairing?.let { c ->
        AlertDialog(
            onDismissRequest = { unpairing = null }, title = { Text("Unpair ${nameOf(st, c)}?") },
            text = { Text("You will stop seeing this child's location, usage and requests straight away. Their phone stops sharing and clears what it stored for you.") },
            confirmButton = { TextButton({ vm.unpair(c.childUid); unpairing = null }) { Text("Unpair") } },
            dismissButton = { TextButton({ unpairing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AddDeviceCard(st: ParentState, vm: ParentViewModel) {
    SectionCard {
        Text("Add a child's phone", style = MaterialTheme.typography.titleMedium)
        Text("Make a one-time code, then enter it in SafeWe on their phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        st.code?.let {
            Text(it.chunked(4).joinToString(" "), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text("Valid for 10 minutes. Works once.", style = MaterialTheme.typography.bodyMedium)
        }
        FilledTonalButton(onClick = vm::newCode) { Icon(Icons.Filled.QrCode2, null); Spacer(Modifier.width(8.dp)); Text(if (st.code == null) "Create code" else "New code") }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Rename device") },
        text = { OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ onOk(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
