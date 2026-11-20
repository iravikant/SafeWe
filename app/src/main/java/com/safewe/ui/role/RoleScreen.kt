package com.safewe.ui.role

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.safewe.ui.IconBadge

@Composable
fun RoleScreen(onParent: () -> Unit, onChild: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Who is using this device?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a mode. Child mode always shows what is shared and lets you stop it at any time.",
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(28.dp))
        RoleCard(Icons.Filled.SupervisorAccount, "Parent", "See your child's location and manage safe zones.", onParent)
        Spacer(Modifier.height(16.dp))
        RoleCard(Icons.Filled.ChildCare, "Child", "Pair with a parent and review what is shared.", onChild)
    }
}

@Composable
private fun RoleCard(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
