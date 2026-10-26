package com.labserver.lablink.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand palette, used on every phone (not wallpaper colours): the planet-bot's violet, a soft lavender secondary for
// selections and the orbit's amber as tertiary. Dynamic colour produced grey, low-contrast selections on many
// wallpapers, and the bots' orb colours read better on a stable, slightly violet-tinted neutral.
private val LightColors = lightColorScheme(
    primary = Color(0xFF5546E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3DFFF),
    onPrimaryContainer = Color(0xFF170B6B),
    secondary = Color(0xFF5F5A86),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E0FF),
    onSecondaryContainer = Color(0xFF1C1840),
    tertiary = Color(0xFF8A5300),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB4),
    onTertiaryContainer = Color(0xFF2C1700),
    background = Color(0xFFFCFAFF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFCFAFF),
    onSurface = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFF5F5D6E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F3FC),
    surfaceContainer = Color(0xFFF0EDF8),
    surfaceContainerHigh = Color(0xFFEAE7F3),
    surfaceContainerHighest = Color(0xFFE4E1EE),
    outlineVariant = Color(0xFFC9C5D6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC5BFFF),
    onPrimary = Color(0xFF2A1AA6),
    primaryContainer = Color(0xFF3F31C4),
    onPrimaryContainer = Color(0xFFE3DFFF),
    secondary = Color(0xFFC8C2EE),
    onSecondary = Color(0xFF312D55),
    secondaryContainer = Color(0xFF47436D),
    onSecondaryContainer = Color(0xFFE6E0FF),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF492900),
    tertiaryContainer = Color(0xFF693D00),
    onTertiaryContainer = Color(0xFFFFDDB4),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE5E1EC),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE5E1EC),
    onSurfaceVariant = Color(0xFFC8C5D3),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F26),
    surfaceContainerHigh = Color(0xFF2A2931),
    surfaceContainerHighest = Color(0xFF35343C),
    outlineVariant = Color(0xFF47464F),
)

// Big, round corners everywhere: the "soft and cute" part of the design.
private val RoundShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun LabLinkTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, shapes = RoundShapes, typography = LabTypography, content = content)
}
