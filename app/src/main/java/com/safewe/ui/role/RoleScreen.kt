package com.safewe.ui.role

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.safewe.ui.ScreenHeader
import com.safewe.ui.SectionLabel
import com.safewe.ui.theme.Brand

@Composable
fun RoleScreen(onParent: () -> Unit, onChild: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        SectionLabel("Get started")
        Spacer(Modifier.height(4.dp))
        ScreenHeader(
            "Who is using this device?",
            "Pick a mode. Child mode always shows what is shared and lets you stop it at any time."
        )
        Spacer(Modifier.height(28.dp))
        RoleCard(Icons.Filled.SupervisorAccount, "Parent", "See your child's location and manage safe zones.", Brand.hero, onParent)
        Spacer(Modifier.height(16.dp))
        RoleCard(Icons.Filled.ChildCare, "Child", "Pair with a parent and review what is shared.", Brand.neutral, onChild)
    }
}

@Composable
private fun RoleCard(icon: ImageVector, title: String, sub: String, brush: Brush, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Row(
        Modifier.fillMaxWidth().clip(shape).background(brush)
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.07f), radius = size.minDimension * 0.9f, center = Offset(size.width * 0.98f, 0f))
            }
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.04f))), shape)
            .clickable(onClick = onClick).padding(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = Color.White)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = Color.White)
        }
    }
}
