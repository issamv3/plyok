package com.ply.mpd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF0D1117)
val DarkSurface = Color(0xFF161B22)
val DarkSurfaceVariant = Color(0xFF21262D)
val DarkBorder = Color(0xFF30363D)

val AccentPrimary = Color(0xFF38BDF8) // Electric Sky Blue
val AccentSecondary = Color(0xFF818CF8) // Indigo
val AccentTertiary = Color(0xFFA78BFA) // Purple

val QualityHdColor = Color(0xFF10B981) // Emerald Green for HD >= 720
val QualitySdColor = Color(0xFFF59E0B) // Amber for SD < 720
val FacebookBlue = Color(0xFF1877F2)

val TextPrimary = Color(0xFFF0F6FC)
val TextSecondary = Color(0xFF8B949E)
val TextTertiary = Color(0xFF6E7681)

private val DarkColors = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.Black,
    secondary = AccentSecondary,
    onSecondary = Color.Black,
    tertiary = AccentTertiary,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder
)

@Composable
fun PlyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
