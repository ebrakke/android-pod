package dev.reclaimed.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val TouchInk = Color(0xFF182431)
internal val TouchBlue = Color(0xFF176B87)
internal val TouchBlueSoft = Color(0xFFD8EEF4)
internal val TouchCanvas = Color(0xFFF3F0E8)
internal val TouchCard = Color(0xFFFFFCF5)
internal val TouchMuted = Color(0xFF62717B)
internal val TouchOrange = Color(0xFFE77B3D)

private val TouchColors = lightColorScheme(
    primary = TouchBlue,
    onPrimary = Color.White,
    primaryContainer = TouchBlueSoft,
    onPrimaryContainer = TouchInk,
    secondary = TouchOrange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1CF),
    onSecondaryContainer = Color(0xFF562108),
    background = TouchCanvas,
    onBackground = TouchInk,
    surface = TouchCard,
    onSurface = TouchInk,
    surfaceVariant = Color(0xFFE7E8E3),
    onSurfaceVariant = TouchMuted,
    outline = Color(0xFFB5BDBD),
    outlineVariant = Color(0xFFD8DDD9),
    error = Color(0xFFB3261E),
)

private val TouchTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
internal fun ReclaimedTouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TouchColors,
        typography = TouchTypography,
        content = content,
    )
}
