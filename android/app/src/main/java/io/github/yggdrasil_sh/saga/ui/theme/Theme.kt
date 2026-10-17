package io.github.yggdrasil_sh.saga.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Farben des Saga-Symbols: dunkles Blau-Schwarz und Gold */
val SagaNight = Color(0xFF0F141C)
val SagaGold = Color(0xFFC9A45C)

/**
 * Vollstaendige Material-3-Palette (alle Rollen gesetzt, sonst kaemen violettgraue Standardwerte durch):
 * hell = warmes Papier mit Gold, dunkel = Nachtblau (Hintergrund des App-Symbols) mit Gold. Quelle: Seed #C9A45C,
 * Nebenfarben auf das Nachtblau abgestimmt. Kontraste Text/Flaeche >= 4.5:1.
 */
internal val Light = lightColorScheme(
    primary = Color(0xFF7A5A12),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E2BC),
    onPrimaryContainer = Color(0xFF261A00),
    inversePrimary = Color(0xFFE8C26E),
    secondary = Color(0xFF675E4A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E1D6),
    onSecondaryContainer = Color(0xFF1D1B16),
    tertiary = Color(0xFF3A6A8C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCBE6FF),
    onTertiaryContainer = Color(0xFF001E30),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF9F5),
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFBF9F5),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFEDE1CF),
    onSurfaceVariant = Color(0xFF4D4639),
    surfaceTint = Color(0xFF7A5A12),
    inverseSurface = Color(0xFF33302A),
    inverseOnSurface = Color(0xFFF7F0E7),
    outline = Color(0xFF7F7667),
    outlineVariant = Color(0xFFD0C5B4),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFBF9F5),
    surfaceDim = Color(0xFFDCD9D2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F3EC),
    surfaceContainer = Color(0xFFF1EDE4),
    surfaceContainerHigh = Color(0xFFEBE7DD),
    surfaceContainerHighest = Color(0xFFE5E1D7),
)

internal val Dark = darkColorScheme(
    primary = SagaGold,
    onPrimary = Color(0xFF3F2E00),
    primaryContainer = Color(0xFF5A4410),
    onPrimaryContainer = Color(0xFFF3E2BC),
    inversePrimary = Color(0xFF7A5A12),
    secondary = Color(0xFFCCC6B9),
    onSecondary = Color(0xFF33302A),
    secondaryContainer = Color(0xFF2B313B),
    onSecondaryContainer = Color(0xFFE6E1D6),
    tertiary = Color(0xFF9CC9EC),
    onTertiary = Color(0xFF00344F),
    tertiaryContainer = Color(0xFF1E4B6A),
    onTertiaryContainer = Color(0xFFCBE6FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = SagaNight,
    onBackground = Color(0xFFE4E2DD),
    surface = SagaNight,
    onSurface = Color(0xFFE4E2DD),
    surfaceVariant = Color(0xFF2A3240),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceTint = SagaGold,
    inverseSurface = Color(0xFFE4E2DD),
    inverseOnSurface = Color(0xFF2C3038),
    outline = Color(0xFF8D919A),
    outlineVariant = Color(0xFF3F4652),
    scrim = Color.Black,
    surfaceBright = Color(0xFF353B45),
    surfaceDim = SagaNight,
    surfaceContainerLowest = Color(0xFF0A0E14),
    surfaceContainerLow = Color(0xFF141A24),
    surfaceContainer = Color(0xFF18202B),
    surfaceContainerHigh = Color(0xFF1F2733),
    surfaceContainerHighest = Color(0xFF27303D),
)

@Composable
fun SagaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme) {
        // Grund-Textfarbe passend zum Hintergrund: Bildschirme ohne Scaffold/Surface (Seiten, Datenbank-Bloecke,
        // Einbettungen) erben sonst LocalContentColor = Schwarz - im Dunkelmodus schwarz auf schwarz.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
