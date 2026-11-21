package com.safewe.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.safewe.data.AppRole
import com.safewe.data.RoleStore
import com.safewe.data.remote.AuthRepository
import com.safewe.ui.*
import com.safewe.ui.theme.Brand
import com.safewe.vault.VaultIndex
import com.safewe.vault.VaultRepository
import com.safewe.vault.VaultState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Read-only view of the signed-in account and the private-space status. Shared by parent and child. */
@HiltViewModel
class ProfileViewModel @Inject constructor(auth: AuthRepository, roles: RoleStore, private val vault: VaultRepository) : ViewModel() {
    val email: String? = auth.email
    val role: AppRole? = roles.get()
    val vaultState: StateFlow<VaultState> = vault.state
    val index: StateFlow<VaultIndex> = vault.index
    fun biometricEnabled(): Boolean = vault.biometricEnabled()
}

/** Greeting with the account name; tapping the avatar opens the Profile tab. */
@Composable
fun AccountGreeting(subtitle: String, onAvatarClick: (() -> Unit)?, vm: ProfileViewModel = hiltViewModel()) {
    GreetingHeader(displayNameOf(vm.email), subtitle, onAvatarClick)
}

/** Profile tab. Same layout for both roles; only the wording changes. */
@Composable
fun ProfileTab(
    role: AppRole, onSwitchRole: () -> Unit, onSignOut: () -> Unit,
    onOpenVault: () -> Unit, onOpenFiles: () -> Unit,
    vm: ProfileViewModel = hiltViewModel()
) {
    val ctx = LocalContext.current
    val vaultState by vm.vaultState.collectAsState()
    val index by vm.index.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }

    val name = displayNameOf(vm.email)
    val vaultReady = vaultState !is VaultState.NotSetUp
    val biometricOn = remember(vaultState) { vaultReady && vm.biometricEnabled() }
    val done = listOf(vaultReady, biometricOn).count { it }
    val vaultLabel = when (vaultState) {
        VaultState.NotSetUp -> "Not set up"
        is VaultState.Locked -> "Locked"
        VaultState.Unlocked -> "Unlocked"
    }
    val isParent = role == AppRole.PARENT
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenHeader("Profile")

        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(name, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    vm.email?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Surface(color = Color.White.copy(alpha = 0.16f), shape = CircleShape, contentColor = Color.White) {
                Text(
                    if (isParent) "Parent account" else "Child account",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Security checklist", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("$done of 2", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            }
            GradientProgress(done / 2f, brush = Brand.onHeroProgress, trackColor = Color.White.copy(alpha = 0.18f), height = 10.dp)
            CheckLine(vaultReady, "Private space created")
            CheckLine(biometricOn, "Fingerprint or face unlock turned on")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Filled.Lock, vaultLabel, "Private space", Modifier.weight(1f))
            StatTile(Icons.Filled.Folder, if (vaultState is VaultState.Unlocked) "${index.items.size}" else "—", "Files stored", Modifier.weight(1f))
            StatTile(Icons.Filled.EnhancedEncryption, "AES-256", "Encryption", Modifier.weight(1f))
        }

        SectionLabel("Private and encrypted")
        SettingsGroup {
            SettingsRow(
                Icons.Filled.Lock, "Private space",
                when (vaultState) {
                    VaultState.NotSetUp -> "Set up a PIN or password to start"
                    is VaultState.Locked -> "Locked. Unlock to view your files"
                    VaultState.Unlocked -> "${index.items.size} file(s) inside"
                },
                onClick = onOpenVault
            )
            SettingsDivider()
            SettingsRow(Icons.Filled.EnhancedEncryption, "Encrypt and decrypt files", "Lock or unlock any single file and save it anywhere", onClick = onOpenFiles)
        }

        SectionLabel("Account")
        SettingsGroup {
            SettingsRow(
                Icons.Filled.SwapHoriz,
                if (isParent) "Switch to child mode" else "Switch to parent mode",
                "Use this phone as a ${if (isParent) "child" else "parent"} device", onClick = onSwitchRole
            )
            SettingsDivider()
            SettingsRow(Icons.AutoMirrored.Filled.Logout, "Sign out", onClick = { confirmSignOut = true }, danger = true)
        }

        Text(
            "SafeWe${if (version.isNotBlank()) " · version $version" else ""}",
            Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center
        )
    }

    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false }, title = { Text("Sign out?") },
        text = { Text("Your private space will lock. You can sign back in at any time.") },
        confirmButton = { TextButton({ confirmSignOut = false; onSignOut() }) { Text("Sign out", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmSignOut = false }) { Text("Cancel") } }
    )
}

@Composable
private fun CheckLine(done: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, Modifier.size(18.dp),
            tint = if (done) Color.White else Color.White.copy(alpha = 0.45f)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = if (done) 1f else 0.7f))
    }
}
