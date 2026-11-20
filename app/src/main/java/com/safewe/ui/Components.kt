package com.safewe.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(44.dp).background(tint.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = tint) }
}

@Composable
fun StatusPill(text: String, ok: Boolean) {
    val c = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Surface(color = c.copy(alpha = 0.14f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(c, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = c)
        }
    }
}

/** Large, high-contrast card for the single most important message on a screen. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary
    ) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

/** Square-ish quick action. */
@Composable
fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Card(
        onClick = onClick, enabled = enabled, modifier = modifier.height(104.dp), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            IconBadge(icon, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        }
    }
}

/** Full-width row: icon, title, subtitle, optional trailing widget; tappable when [onClick] is given. */
@Composable
fun FeatureRow(
    icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null
) {
    val body: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    if (onClick != null) Card(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, elevation = elevation, content = body)
    else Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, elevation = elevation, content = body)
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
