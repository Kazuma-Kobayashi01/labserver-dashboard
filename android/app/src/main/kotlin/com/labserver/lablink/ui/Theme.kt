package com.labserver.lablink.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Fallback palette (phones before Android 12, or when system colours are off): soft violet, bubblegum pink, sunny yellow.
private val LightColors = lightColorScheme(
    primary = Color(0xFF5B52E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E0FF),
    onPrimaryContainer = Color(0xFF16106B),
    secondary = Color(0xFFD6508A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E6),
    onSecondaryContainer = Color(0xFF3E001E),
    tertiary = Color(0xFFF2B63D),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFFFFE9B0),
    onTertiaryContainer = Color(0xFF251A00),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF1ECF6),
    surfaceContainerHigh = Color(0xFFEBE6F0),
    surfaceContainerHighest = Color(0xFFE5E0EA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC4BFFF),
    onPrimary = Color(0xFF2A1FB0),
    primaryContainer = Color(0xFF4238CF),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFFFFB0CD),
    onSecondary = Color(0xFF610035),
    secondaryContainer = Color(0xFF8C1C5A),
    onSecondaryContainer = Color(0xFFFFD9E6),
    tertiary = Color(0xFFFFD66E),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFE9B0),
    background = Color(0xFF1B1B1F),
    surface = Color(0xFF1B1B1F),
    surfaceContainerLow = Color(0xFF1F1F24),
    surfaceContainer = Color(0xFF252529),
    surfaceContainerHigh = Color(0xFF2F2F34),
    surfaceContainerHighest = Color(0xFF3A3A3F),
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
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, shapes = RoundShapes, content = content)
}
