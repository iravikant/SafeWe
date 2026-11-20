package com.safewe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF0F9D8A), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F4EE), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A6572), tertiary = Color(0xFFE8A33D),
    background = Color(0xFFF6F8FA), surface = Color.White,
    surfaceVariant = Color(0xFFE9EEF2), error = Color(0xFFD64545)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4FD6C0), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046), onPrimaryContainer = Color(0xFFD3F4EE),
    secondary = Color(0xFFB1CAD6), tertiary = Color(0xFFF2C277),
    background = Color(0xFF12203A), surface = Color(0xFF1A2B49),
    surfaceVariant = Color(0xFF263A5E), error = Color(0xFFFF8A80)
)

private val SafeWeTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
)

private val SafeWeShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp)
)

@Composable
fun SafeWeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = SafeWeTypography,
        shapes = SafeWeShapes,
        content = content
    )
}
