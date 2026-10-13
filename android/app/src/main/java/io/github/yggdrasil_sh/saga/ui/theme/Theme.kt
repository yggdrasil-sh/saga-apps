package io.github.yggdrasil_sh.saga.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Farben des Saga-Symbols: dunkles Blau-Schwarz und Gold */
val SagaNight = Color(0xFF0F141C)
val SagaGold = Color(0xFFC9A45C)

private val Light = lightColorScheme(
    primary = Color(0xFF7A5A12),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E2BC),
    onPrimaryContainer = Color(0xFF2A1D00),
    secondary = Color(0xFF5E5B53),
    secondaryContainer = Color(0xFFE6E1D6),
    onSecondaryContainer = Color(0xFF1D1B16),
    tertiary = Color(0xFF3A6A8C),
    background = Color(0xFFFBF9F5),
    surface = Color(0xFFFBF9F5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F3EC),
    surfaceContainer = Color(0xFFF1EDE4),
    surfaceContainerHigh = Color(0xFFEBE7DD),
    surfaceContainerHighest = Color(0xFFE5E1D7),
)

private val Dark = darkColorScheme(
    primary = SagaGold,
    onPrimary = Color(0xFF2A1D00),
    primaryContainer = Color(0xFF5A4410),
    onPrimaryContainer = Color(0xFFF3E2BC),
    secondary = Color(0xFFCCC6B9),
    secondaryContainer = Color(0xFF2B313B),
    onSecondaryContainer = Color(0xFFE6E1D6),
    tertiary = Color(0xFF9CC9EC),
    background = SagaNight,
    surface = SagaNight,
    surfaceContainerLowest = Color(0xFF0A0E14),
    surfaceContainerLow = Color(0xFF141A24),
    surfaceContainer = Color(0xFF18202B),
    surfaceContainerHigh = Color(0xFF1F2733),
    surfaceContainerHighest = Color(0xFF27303D),
)

@Composable
fun SagaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
