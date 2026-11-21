package com.safewe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** True when the dark variant of the theme is active. Gradients and borders read this. */
val LocalDarkTheme = staticCompositionLocalOf { true }

/**
 * SafeWe brand palette: Black, Purple, Grey, White.
 * Everything gradient-based in the app is built from these.
 */
object Brand {
    val Black = Color(0xFF07060B)
    val Ink = Color(0xFF0E0C16)
    val Plum = Color(0xFF1B1530)
    val PurpleDeep = Color(0xFF3B1A8C)
    val PurpleDark = Color(0xFF5B21B6)
    val Purple = Color(0xFF7C3AED)
    val PurpleBright = Color(0xFF9F7AFF)
    val Lavender = Color(0xFFC9B8FF)
    val Grey = Color(0xFF9A98AB)
    val GreyDark = Color(0xFF2B2840)
    val GreySoft = Color(0xFFE6E4EE)

    /** Black to deep purple to purple. Hero cards, logo, primary role card. */
    val hero: Brush = Brush.linearGradient(listOf(Color(0xFF0A0710), Color(0xFF2A0F66), Color(0xFF6D28D9)))

    /** Purple accent for badges. */
    val accent: Brush = Brush.linearGradient(listOf(PurpleDark, Purple, PurpleBright))

    /** Horizontal purple accent for buttons. */
    val button: Brush = Brush.horizontalGradient(listOf(PurpleDark, Purple, Color(0xFF9061F9)))

    /** Progress bar fill. */
    val progress: Brush = Brush.horizontalGradient(listOf(PurpleDark, Purple, Lavender))

    /** Progress bar fill when something is nearly used up. */
    val progressCritical: Brush = Brush.horizontalGradient(listOf(Color(0xFF7F1D1D), Color(0xFFEF4444)))

    /** Progress bar fill on top of a hero card (light on dark). */
    val onHeroProgress: Brush = Brush.horizontalGradient(listOf(Lavender, Color.White))

    /** Black to grey. Secondary role card. */
    val neutral: Brush = Brush.linearGradient(listOf(Color(0xFF0B0B10), GreyDark, Color(0xFF55516E)))
}

private val Light = lightColorScheme(
    primary = Color(0xFF6D28D9), onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE4FF), onPrimaryContainer = Color(0xFF24095C),
    secondary = Color(0xFF65627A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E2FA), onSecondaryContainer = Color(0xFF24095C),
    tertiary = Color(0xFFB45309), onTertiary = Color.White,
    background = Color(0xFFF4F3F8), onBackground = Color(0xFF0E0C16),
    surface = Color.White, onSurface = Color(0xFF0E0C16),
    surfaceVariant = Color(0xFFE8E6F0), onSurfaceVariant = Color(0xFF4B4860),
    outline = Color(0xFFC4C0D4), outlineVariant = Color(0xFFE2DFEC),
    error = Color(0xFFD64545), onError = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F7FC),
    surfaceContainer = Color(0xFFF3F1F9), surfaceContainerHigh = Color(0xFFEEEBF6),
    surfaceContainerHighest = Color(0xFFE8E5F1)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9061F9), onPrimary = Color.White,
    primaryContainer = Color(0xFF2E1A5E), onPrimaryContainer = Color(0xFFEADFFF),
    secondary = Color(0xFFA09DB5), onSecondary = Color(0xFF0E0C16),
    secondaryContainer = Color(0xFF2E1A5E), onSecondaryContainer = Color(0xFFEADFFF),
    tertiary = Color(0xFFF5B84B), onTertiary = Color(0xFF2A1A00),
    background = Color(0xFF08070D), onBackground = Color(0xFFF4F2FA),
    surface = Color(0xFF13101F), onSurface = Color(0xFFF4F2FA),
    surfaceVariant = Color(0xFF211C36), onSurfaceVariant = Color(0xFFB9B6CC),
    outline = Color(0xFF3A3556), outlineVariant = Color(0xFF2A2640),
    error = Color(0xFFFF6B7A), onError = Color(0xFF2A0008),
    surfaceContainerLowest = Color(0xFF07060B), surfaceContainerLow = Color(0xFF0E0C16),
    surfaceContainer = Color(0xFF13101F), surfaceContainerHigh = Color(0xFF1A1630),
    surfaceContainerHighest = Color(0xFF241F3C)
)

private val SafeWeTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.0.sp)
)

private val SafeWeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp)
)

@Composable
fun SafeWeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = SafeWeTypography, shapes = SafeWeShapes) {
        CompositionLocalProvider(
            LocalDarkTheme provides dark,
            LocalContentColor provides scheme.onBackground,
            content = content
        )
    }
}
