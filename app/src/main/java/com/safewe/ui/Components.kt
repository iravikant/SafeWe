package com.safewe.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safewe.ui.theme.Brand
import com.safewe.ui.theme.LocalDarkTheme

// ============================================================ backgrounds

/** App-wide backdrop: near-black (or soft grey-white) with two purple glows. Placed once, behind the NavHost. */
@Composable
fun ScreenBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = LocalDarkTheme.current
    val base = MaterialTheme.colorScheme.background
    Box(
        modifier.fillMaxSize().background(base).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(Brand.Purple.copy(alpha = if (dark) 0.28f else 0.13f), Color.Transparent),
                    center = Offset(size.width * 0.95f, 0f), radius = size.width * 0.95f
                )
            )
            drawRect(
                Brush.radialGradient(
                    listOf(Brand.PurpleDeep.copy(alpha = if (dark) 0.32f else 0.08f), Color.Transparent),
                    center = Offset(0f, size.height), radius = size.width * 0.95f
                )
            )
        }
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
    }
}

// ============================================================ cards

/** The base card: subtle gradient fill with a purple-to-transparent edge. Optionally tappable. */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = LocalDarkTheme.current
    val shape = MaterialTheme.shapes.medium
    val fill = brush ?: if (dark) Brush.linearGradient(listOf(Color(0xFF1B1632), Color(0xFF0F0C1B)))
    else Brush.linearGradient(listOf(Color.White, Color(0xFFF1ECFC)))
    val edge = if (dark) Brush.linearGradient(listOf(Brand.Purple.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f)))
    else Brush.linearGradient(listOf(Brand.Purple.copy(alpha = 0.28f), Color(0xFFE3DFEF)))

    var m = modifier.fillMaxWidth()
    if (!dark) m = m.shadow(8.dp, shape, ambientColor = Brand.Purple.copy(alpha = 0.12f), spotColor = Brand.Purple.copy(alpha = 0.18f))
    m = m.clip(shape).background(fill).border(1.dp, edge, shape)
    if (onClick != null) m = m.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(m.padding(contentPadding), verticalArrangement = verticalArrangement, content = content)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    GradientCard(modifier, content = content)

/** Large black-to-purple card for the single most important message on a screen. Content is white. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            modifier.fillMaxWidth().clip(shape).background(Brand.hero)
                .drawBehind {
                    drawCircle(Color.White.copy(alpha = 0.07f), radius = size.minDimension * 0.62f, center = Offset(size.width * 0.96f, size.height * 0.02f))
                    drawCircle(Color.White.copy(alpha = 0.05f), radius = size.minDimension * 0.34f, center = Offset(size.width * 0.78f, size.height * 1.02f))
                }
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.04f))), shape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content
        )
    }
}

// ============================================================ badges

@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(44.dp).clip(shape)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.08f))))
            .border(1.dp, tint.copy(alpha = 0.25f), shape),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = tint) }
}

/** Solid purple-gradient badge with a white icon (quick-action tiles). */
@Composable
fun GradientBadge(icon: ImageVector, enabled: Boolean = true, size: Dp = 44.dp) {
    val bg = if (enabled) Brand.accent else Brush.linearGradient(listOf(Color(0xFF6B6880), Color(0xFF4A475C)))
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White)
    }
}

@Composable
fun StatusPill(text: String, ok: Boolean) {
    val c = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    Surface(color = c.copy(alpha = 0.14f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(c, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = c)
        }
    }
}

// ============================================================ tiles and rows

/** Square-ish quick action. */
@Composable
fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    GradientCard(
        modifier = modifier.height(108.dp).alpha(if (enabled) 1f else 0.5f),
        onClick = onClick, enabled = enabled, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        GradientBadge(icon, enabled)
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2)
    }
}

/** Full-width row: icon, title, subtitle, optional trailing widget; tappable when [onClick] is given. */
@Composable
fun FeatureRow(
    icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null
) {
    GradientCard(
        modifier = modifier.alpha(if (enabled) 1f else 0.6f),
        onClick = onClick, enabled = enabled, contentPadding = PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(84.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Brand.Purple.copy(alpha = 0.35f), Brand.Purple.copy(alpha = 0.06f))))
                .border(1.dp, Brand.Purple.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
    }
}

// ============================================================ text

@Composable
fun SectionLabel(text: String) =
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
    )

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
    }
}

@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme.error
    val shape = MaterialTheme.shapes.small
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.copy(alpha = 0.12f)).border(1.dp, c.copy(alpha = 0.30f), shape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.ErrorOutline, null, tint = c, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c)
    }
}

// ============================================================ buttons and inputs

/** Primary call-to-action with a purple gradient. */
@Composable
fun GradientButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, loading: Boolean = false, icon: ImageVector? = null
) {
    val dark = LocalDarkTheme.current
    val shape = RoundedCornerShape(16.dp)
    val offColor = if (dark) Color.White.copy(alpha = 0.10f) else Color(0xFFD9D6E4)
    val bg = if (enabled) Brand.button else Brush.linearGradient(listOf(offColor, offColor))
    val fg = if (enabled) Color.White else if (dark) Color.White.copy(alpha = 0.40f) else Color(0xFF8A8799)
    Box(
        modifier.height(54.dp).clip(shape).background(bg)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Color.White)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(20.dp)) }
            Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun fieldColors(): TextFieldColors {
    val dark = LocalDarkTheme.current
    val cs = MaterialTheme.colorScheme
    val fill = if (dark) Color.White.copy(alpha = 0.05f) else Color(0xFFF7F5FC)
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = fill, unfocusedContainerColor = fill, disabledContainerColor = fill,
        focusedBorderColor = cs.primary, unfocusedBorderColor = cs.outline, cursorColor = cs.primary,
        focusedLabelColor = cs.primary, unfocusedLabelColor = cs.secondary,
        focusedLeadingIconColor = cs.primary, unfocusedLeadingIconColor = cs.secondary,
        focusedTrailingIconColor = cs.secondary, unfocusedTrailingIconColor = cs.secondary
    )
}

// ============================================================ progress

/** Rounded progress bar with an animated purple gradient fill. [progress] is 0..1. */
@Composable
fun GradientProgress(
    progress: Float, modifier: Modifier = Modifier, height: Dp = 8.dp,
    brush: Brush = Brand.progress, trackColor: Color? = null
) {
    val dark = LocalDarkTheme.current
    val p by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), animationSpec = tween(700), label = "progress")
    val track = trackColor ?: if (dark) Color.White.copy(alpha = 0.08f) else Color(0xFF1B1530).copy(alpha = 0.08f)
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        if (p > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(CircleShape).background(brush))
    }
}

/** Looping bar for work of unknown length. */
@Composable
fun GradientProgressIndeterminate(modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val dark = LocalDarkTheme.current
    val track = if (dark) Color.White.copy(alpha = 0.08f) else Color(0xFF1B1530).copy(alpha = 0.08f)
    val transition = rememberInfiniteTransition(label = "indeterminate")
    val x by transition.animateFloat(
        initialValue = -0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "x"
    )
    Canvas(modifier.fillMaxWidth().height(height).clip(CircleShape)) {
        drawRect(track)
        val w = size.width * 0.4f
        val left = size.width * x
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Brand.PurpleDark, Brand.Purple, Brand.Lavender), startX = left, endX = left + w),
            topLeft = Offset(left, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2)
        )
    }
}

// ============================================================ navigation

data class NavItem(val label: String, val icon: ImageVector)

@Composable
fun SafeWeNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    val dark = LocalDarkTheme.current
    val line = MaterialTheme.colorScheme.outlineVariant
    NavigationBar(
        containerColor = if (dark) Color(0xFF0C0A14) else Color.White,
        tonalElevation = 0.dp,
        modifier = Modifier.drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
    ) {
        items.forEachIndexed { i, item ->
            NavigationBarItem(
                selected = selected == i, onClick = { onSelect(i) },
                icon = { Icon(item.icon, null) }, label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White, selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = Brand.Purple,
                    unselectedIconColor = MaterialTheme.colorScheme.secondary, unselectedTextColor = MaterialTheme.colorScheme.secondary
                )
            )
        }
    }
}
