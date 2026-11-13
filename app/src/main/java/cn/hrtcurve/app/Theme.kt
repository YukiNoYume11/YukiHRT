package cn.hrtcurve.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val FlagPink = Color(0xFFF4A7C3)
val FlagBlue = Color(0xFF8BCDE9)
val AccentPink = Color(0xFFD96F9D)
val AccentBlue = Color(0xFF4C9CC8)
val Ink = Color(0xFF342C37)
val MutedInk = Color(0xFF766B78)
val AppBackground = Color(0xFFFFF7FA)
val SoftPink = Color(0xFFFFF0F5)
val SoftBlue = Color(0xFFEEF8FD)
val SoftLavender = Color(0xFFF4F0FA)
val Danger = Color(0xFFB3465D)

private val AppColors = lightColorScheme(
    primary = AccentPink,
    onPrimary = Color.White,
    primaryContainer = SoftPink,
    onPrimaryContainer = Ink,
    secondary = AccentBlue,
    onSecondary = Color.White,
    secondaryContainer = SoftBlue,
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF8B78B5),
    background = AppBackground,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SoftLavender,
    onSurfaceVariant = MutedInk,
    outline = Color(0xFFE7D9E1),
    error = Danger
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        color = Ink
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        color = Ink
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        color = Ink
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = Ink
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = Ink
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = Ink
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

@Composable
fun HRTCurveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        shapes = MaterialTheme.shapes.copy(
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp)
        ),
        content = content
    )
}
