package com.safewe.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safewe.data.AppRole
import com.safewe.ui.*
import com.safewe.vault.VaultState

/**
 * "Private" tab, identical for parent and child: the personal vault and single-file encryption / decryption.
 * Only the intro wording depends on [role].
 */
@Composable
fun PrivateHub(role: AppRole, onOpenVault: () -> Unit, onOpenFiles: () -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val vaultState by vm.vaultState.collectAsState()
    val label = when (vaultState) {
        VaultState.NotSetUp -> "Not set up yet"
        is VaultState.Locked -> "Locked"
        VaultState.Unlocked -> "Unlocked"
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenHeader("Private", "Your encrypted space on this phone")

        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Only you can open these", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (vaultState is VaultState.Unlocked) Color.White else Color.White.copy(alpha = 0.45f), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
            Text(
                if (role == AppRole.CHILD)
                    "Your parent's monitoring never gives access to your private space or encrypted files. Screenshots of them are blocked."
                else
                    "Photos, videos, documents and single files are encrypted on this phone. They open only with your PIN, password or fingerprint.",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(Icons.Filled.Lock, "Private space", onOpenVault, Modifier.weight(1f))
            ActionTile(Icons.Filled.EnhancedEncryption, "Encrypt or decrypt", onOpenFiles, Modifier.weight(1f))
        }

        SectionLabel("How it works")
        SettingsGroup {
            SettingsRow(Icons.Filled.Folder, "Private space", "Photos, videos and documents with folders and a secure preview.")
            SettingsDivider()
            SettingsRow(Icons.Filled.Fingerprint, "Locked behind you", "A PIN, a password or your fingerprint opens it. It locks when you leave.")
            SettingsDivider()
            SettingsRow(Icons.Filled.EnhancedEncryption, "Single-file encryption", "Lock or unlock any one file with AES-256 and save it wherever you like.")
        }
    }
}
