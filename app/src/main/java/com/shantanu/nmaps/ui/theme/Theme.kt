package com.shantanu.nmaps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shantanu.nmaps.R

val NothingRed = Color(0xFFD71921)

@Immutable
data class Palette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val content: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val red: Color = NothingRed,
)

val DarkPalette = Palette(
    isDark = true,
    background = Color(0xFF000000),
    surface = Color(0xFF1B1B1B),
    surfaceRaised = Color(0xFF2A2A2A),
    content = Color(0xFFFFFFFF),
    muted = Color(0xFF8C8C8C),
    faint = Color(0xFF3C3C3C),
    line = Color(0xFF2C2C2C),
)

val LightPalette = Palette(
    isDark = false,
    background = Color(0xFFF2F2F2),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEAEAEA),
    content = Color(0xFF000000),
    muted = Color(0xFF6C6C6C),
    faint = Color(0xFFC8C8C8),
    line = Color(0xFFE0E0E0),
)

/** Dot-matrix display face (Doto, round dots) — stands in for Nothing's NDot. */
val Doto = FontFamily(
    Font(R.font.doto_600, FontWeight.SemiBold),
    Font(R.font.doto_900, FontWeight.Black),
)

/** Body grotesque — stands in for Nothing's NType. */
val Grotesk = FontFamily(
    Font(R.font.space_grotesk_400, FontWeight.Normal),
    Font(R.font.space_grotesk_500, FontWeight.Medium),
    Font(R.font.space_grotesk_700, FontWeight.Bold),
)

object NType {
    val display = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Black, fontSize = 60.sp, lineHeight = 60.sp)
    val dotLarge = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 42.sp)
    val dotMedium = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Black, fontSize = 26.sp, lineHeight = 28.sp)
    val dotLabel = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Black, fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 1.sp)
    val title = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp)
    val body = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
    val bodyStrong = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val caption = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val unit = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 16.sp)
    val button = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 18.sp)
}

val LocalPalette = staticCompositionLocalOf { DarkPalette }

@Composable
fun NTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette, content = content)
}
