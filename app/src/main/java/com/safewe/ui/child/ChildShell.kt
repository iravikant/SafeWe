package com.safewe.ui.child

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.safewe.data.remote.Pairing
import com.safewe.health.IssueCode
import com.safewe.screen.ScreenShareService
import com.safewe.security.SafetyCode
import com.safewe.service.LocationService
import com.safewe.service.SharingPrefs
import com.safewe.ui.*

/** Child home: Home (sharing + features), Private (vault, files), Privacy (what is shared, unpair). */
@Composable
fun ChildShell(
    onSignOut: () -> Unit, onSwitchRole: () -> Unit, onOpenCamera: () -> Unit, onOpenFiles: () -> Unit, onOpenVault: () -> Unit,
    vm: ChildViewModel = hiltViewModel()
) {
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    val paired = st.pairing?.status == "ACTIVE"
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sharing by remember { mutableStateOf(SharingPrefs.isEnabled(ctx)) }
    var issues by remember { mutableStateOf(emptyList<IssueCode>()) }
    var usageOn by remember { mutableStateOf(vm.usageAccess()) }
    val screenOn by vm.screenSharing.collectAsState()
    val screenMsg by vm.screenMessage.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        usageOn = vm.usageAccess()
        sharing = SharingPrefs.isEnabled(ctx)   // may have been stopped by Android, a revoked permission, or the parent
        issues = vm.health(sharing, paired)
    }
    LaunchedEffect(paired, usageOn) { if (paired && usageOn) vm.onUsageReady() }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            LocationService.start(ctx); sharing = true
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) // must be asked separately, after foreground
        }
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == android.app.Activity.RESULT_OK && d != null) ScreenShareService.start(ctx, r.resultCode, d)
    }

    val toggleSharing: () -> Unit = {
        if (sharing) { LocationService.stop(ctx); sharing = false }
        else {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            fgLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val toggleScreen: () -> Unit = {
        if (screenOn) ScreenShareService.stop(ctx)
        else {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            projectionLauncher.launch(ctx.getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }
    val openUsageSettings: () -> Unit = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val openAppSettings: () -> Unit = {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    val openBatterySettings: () -> Unit = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                listOf("Home" to Icons.Filled.Home, "Private" to Icons.Filled.Lock, "Privacy" to Icons.Filled.Shield).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Crossfade(tab, label = "child-tab") { t ->
                when (t) {
                    0 -> HomeTab(vm, st.pairing, st.error, paired, sharing, issues, usageOn, screenOn, screenMsg, toggleSharing, toggleScreen, openUsageSettings, openAppSettings, openBatterySettings, onOpenCamera)
                    1 -> PrivateTab(onOpenVault, onOpenFiles)
                    else -> PrivacyTab(vm, st.pairing, paired, sharing, usageOn, screenOn, onSwitchRole, onSignOut) { sharing = false }
                }
            }
        }
    }
}

@Composable
private fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

// ============================================================ Home

@Composable
private fun HomeTab(
    vm: ChildViewModel, pairing: Pairing?, error: String?, paired: Boolean, sharing: Boolean, issues: List<IssueCode>,
    usageOn: Boolean, screenOn: Boolean, screenMsg: String?, toggleSharing: () -> Unit, toggleScreen: () -> Unit,
    openUsageSettings: () -> Unit, openAppSettings: () -> Unit, openBatterySettings: () -> Unit, onOpenCamera: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var confirmPair by remember { mutableStateOf(false) }

    Page("Home") {
        if (!paired) {
            HeroCard {
                Text("Connect with your parent", style = MaterialTheme.typography.titleLarge)
                Text("Ask your parent for a code in their SafeWe app. Nothing is shared until you approve it here.", style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard {
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("Parent's 8-digit code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small
                )
                Button({ confirmPair = true }, enabled = code.length == 8, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        } else {
            HeroCard {
                Text(if (sharing) "You're sharing your location" else "Sharing is off", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (sharing) "Your parent can ask where you are, and gets safe-zone alerts. A notification is always shown. Stop whenever you like."
                    else "Your parent can't see where you are right now.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    toggleSharing, Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary)
                ) { Text(if (sharing) "Stop sharing" else "Start sharing") }
            }
        }

        if (issues.isNotEmpty()) SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Warning, MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(12.dp))
                Text("Needs attention", style = MaterialTheme.typography.titleMedium)
            }
            issues.forEach { Text("• " + it.childMessage, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(openAppSettings) { Text("App settings") }
                OutlinedButton(openBatterySettings) { Text("Battery settings") }
            }
        }

        SectionLabel("FEATURES")
        FeatureRow(
            Icons.Filled.PhotoCamera, "Camera session", "Let your parent ask for a photo while the camera screen is open. You get a countdown and can cancel.",
            enabled = paired, onClick = onOpenCamera, trailing = { Icon(Icons.Filled.ChevronRight, null) }
        )
        FeatureRow(
            Icons.Filled.Smartphone, "Screen sharing", "Let your parent ask for a screenshot. You get a notification with a Cancel button every time.",
            enabled = paired, onClick = toggleScreen, trailing = { StatusPill(if (screenOn) "On" else "Off", screenOn) }
        )
        screenMsg?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
        FeatureRow(
            Icons.Filled.BarChart, "App usage", "Share how long you use each app (not what you do inside them). Controlled in Android settings.",
            enabled = paired, onClick = openUsageSettings, trailing = { StatusPill(if (usageOn) "On" else "Off", usageOn) }
        )
    }

    if (confirmPair) AlertDialog(
        onDismissRequest = { confirmPair = false },
        title = { Text("Allow this parent to see your location?") },
        text = {
            Text(
                "If you approve, your parent can request your location while sharing is on. You will always see a notification, and you can unpair at any time. " +
                    "Android permissions are still asked separately."
            )
        },
        confirmButton = { TextButton({ confirmPair = false; vm.approve(code); code = "" }) { Text("Approve") } },
        dismissButton = { TextButton({ confirmPair = false }) { Text("Cancel") } }
    )
}

// ============================================================ Private

@Composable
private fun PrivateTab(onOpenVault: () -> Unit, onOpenFiles: () -> Unit) {
    Page("Private") {
        HeroCard {
            Text("Only you can open these", style = MaterialTheme.typography.titleLarge)
            Text("Your parent's monitoring never gives access to your private space or encrypted files. Screenshots of them are blocked.", style = MaterialTheme.typography.bodyMedium)
        }
        FeatureRow(Icons.Filled.Lock, "Private space", "Photos, videos and documents behind a PIN, password or fingerprint, with folders and secure preview.", onClick = onOpenVault, trailing = { Icon(Icons.Filled.ChevronRight, null) })
        FeatureRow(Icons.Filled.EnhancedEncryption, "Encrypt a file", "Lock or unlock any single file with AES-256 and save it wherever you like.", onClick = onOpenFiles, trailing = { Icon(Icons.Filled.ChevronRight, null) })
    }
}

// ============================================================ Privacy

@Composable
private fun PrivacyTab(
    vm: ChildViewModel, pairing: Pairing?, paired: Boolean, sharing: Boolean, usageOn: Boolean, screenOn: Boolean,
    onSwitchRole: () -> Unit, onSignOut: () -> Unit, onUnpaired: () -> Unit
) {
    val ctx = LocalContext.current
    var confirmUnpair by remember { mutableStateOf(false) }

    Page("Privacy") {
        SectionLabel("WHAT YOUR PARENT CAN SEE")
        FeatureRow(Icons.Filled.LocationOn, "Your location", "Only when you ask for it by turning sharing on, and your parent asks.", trailing = { StatusPill(if (paired && sharing) "On" else "Off", paired && sharing) })
        FeatureRow(Icons.Filled.BarChart, "App usage", "Time per app, per day. Top 10 apps.", trailing = { StatusPill(if (paired && usageOn) "On" else "Off", paired && usageOn) })
        FeatureRow(Icons.Filled.Smartphone, "Screenshots", "Only during screen sharing, with a Cancel button each time.", trailing = { StatusPill(if (paired && screenOn) "On" else "Off", paired && screenOn) })
        FeatureRow(Icons.Filled.PhotoCamera, "Photos", "Only while your camera screen is open, with a countdown.")
        FeatureRow(Icons.Filled.Lock, "Private space and encrypted files", "Never. Nobody but you can open them.", trailing = { StatusPill("Never shared", true) })

        if (paired) {
            pairing?.let { pr ->
                SafetyCode.compute(pr.parentAgreementKey, pr.parentSigningKey, pr.childAgreementKey, pr.childSigningKey)?.let {
                    SectionCard {
                        Text("Security code", style = MaterialTheme.typography.titleMedium)
                        Text(it, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        Text("It should match the code on your parent's phone. If it doesn't, unpair and pair again.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            SectionCard {
                Text("Stop all sharing", style = MaterialTheme.typography.titleMedium)
                Text("Unpairing ends monitoring straight away and removes what SafeWe stored for your parent on this phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                OutlinedButton({ confirmUnpair = true }, Modifier.fillMaxWidth()) { Text("Unpair from my parent", color = MaterialTheme.colorScheme.error) }
            }
        }

        SectionLabel("CLOUD USAGE")
        QuotaCard()

        SectionLabel("ACCOUNT")
        SectionCard {
            OutlinedButton(onSwitchRole, Modifier.fillMaxWidth()) { Text("Switch to parent mode on this phone") }
            OutlinedButton(onSignOut, Modifier.fillMaxWidth()) { Text("Sign out") }
        }
    }

    if (confirmUnpair) AlertDialog(
        onDismissRequest = { confirmUnpair = false }, title = { Text("Unpair from your parent?") },
        text = { Text("Your parent will stop seeing anything straight away. You can pair again later with a new code.") },
        confirmButton = { TextButton({ confirmUnpair = false; vm.revoke(); LocationService.stop(ctx); onUnpaired() }) { Text("Unpair") } },
        dismissButton = { TextButton({ confirmUnpair = false }) { Text("Cancel") } }
    )
}
