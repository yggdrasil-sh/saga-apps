package io.github.yggdrasil_sh.saga.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/** Farben des Saga-Symbols: dunkles Blau-Schwarz und Gold */
val SagaNight = Color(0xFF0F141C)
val SagaGold = Color(0xFFC9A45C)

/**
 * Vollstaendige Material-3-Palette (alle Rollen gesetzt, sonst kaemen violettgraue Standardwerte durch).
 * Grundton wie im Web (Entscheidung des Maintainers, A11Y-17): kuehles Schiefergrau fuer Text, Flaechen aus
 * static/tokens.css, Gold (primary, SagaAccent) unveraendert. Hell = Papier #FDFCFA mit weissen Karten, dunkel =
 * Nachtblau (Hintergrund des App-Symbols). Pruefung: ThemeContrastTest (Text >= 4,5:1, outline >= 3:1).
 *
 * Zuordnung Web-Token -> Material-Rolle (hell / dunkel):
 * | Web-Token     | Rolle(n)                                    | hell     | dunkel   |
 * |---------------|---------------------------------------------|----------|----------|
 * | --bg          | background, surface, surfaceBright(hell)    | #FDFCFA  | #0F141C  |
 * | --fg          | onBackground, onSurface                     | #1D2430  | #E6EBF2  |
 * | --muted       | onSurfaceVariant, secondary                 | #5D6878  | #8B97A8  |
 * | --card        | surfaceContainerLowest(hell), ...Low        | #FFFFFF  | #18202C  |
 * | --card-h      | surfaceContainer                            | #F6F3EC  | #202B3A  |
 * | --side-row    | surfaceContainerHigh, surfaceVariant,       | #EBE7DE  | #222C3A  |
 * |               | surfaceDim(hell)                            |          |          |
 * | --gold-soft   | secondaryContainer (= SagaAccent.goldSoft)  | #F3E2BC  | #3A3020  |
 * |               | onSecondaryContainer (= onGoldSoft)         | #3D2C00  | #F3E2BC  |
 * | --code        | surfaceContainerHighest (hell)              | #EBE6DA  |    -     |
 * | --border      | outlineVariant (Karten, Trennlinien)        | #E7E2D7  | #263242  |
 * | --field       | outline (Feldraender, >= 3:1)               | #8F8670  | *#717E93 |
 * | --overdue     | error (Text-sichere Rotstufe)               | #B3121F  | #FF7B72  |
 * | --critbg      | errorContainer                              | #FBE0DE  | #3D1718  |
 * | --accent      | primary (hell), SagaAccent.goldText         | #7A5A12  | (Gold)   |
 * Ohne Web-Gegenstueck (Android-Stufen, gleiche Familie): dunkel surfaceContainerLowest #0A0E14,
 * surfaceContainerHighest/surfaceBright #252F3D (dunkelste Stufe, auf der --muted noch 4,5:1 erreicht).
 * * dunkel outline: Web --field #5E6B80 erreicht 3:1 nur auf --bg/--card; Android legt Felder auch auf
 *   surfaceContainer (Eingabeleisten), daher eine Stufe heller (>= 3:1 auf allen Containern).
 * error: Web --down (#CF222E / #F85149) erreicht 4,5:1 nur auf --bg/--card; error steht in der App oft als Text auf
 *   Containern, daher --overdue. --down selbst gibt es 1:1 in SagaColors.
 * Text immer onSurface/onSurfaceVariant, nie outline (A11Y-04): outline ist nur fuer Raender gedacht.
 * secondaryContainer (Nachbesserung R3, Entscheidung Koordinator): Gold-Soft wie die aktive Ansichts-Pille und die
 *   gewaehlte Zeile der Seitenleiste. Vorher = surfaceContainerHigh -> aktive B/I/U/S-Knoepfe auf der Werkzeugleiste,
 *   getoente Knoepfe ("Uebernehmen") und gewaehlte Segmente waren unsichtbar. Material nutzt die Rolle fuer aktive/
 *   gewaehlte Zustaende (FilterChip, SegmentedButton, NavigationRail-Indikator, FilledTonalButton). Neutrale Flaechen
 *   (Hinweisleisten, Avatare, Blasen) nehmen ausdruecklich surfaceContainerHigh. Kontrast: Text 10,5:1 / 10,1:1;
 *   als Flaeche gegen surfaceContainerHigh nur ~1,1:1 (Farbton statt Helligkeit) - wo der Zustand allein an der
 *   Flaeche haengt (Format-Knoepfe), zusaetzlich ein Rand in SagaAccent.goldText (>= 3:1, ThemeContrastTest).
 */
internal val Light = lightColorScheme(
    primary = Color(0xFF7A5A12),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E2BC),
    onPrimaryContainer = Color(0xFF261A00),
    inversePrimary = Color(0xFFE8C26E),
    secondary = Color(0xFF5D6878),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E2BC),
    onSecondaryContainer = Color(0xFF3D2C00),
    tertiary = Color(0xFF3A6A8C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCBE6FF),
    onTertiaryContainer = Color(0xFF001E30),
    error = Color(0xFFB3121F),
    onError = Color.White,
    errorContainer = Color(0xFFFBE0DE),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDFCFA),
    onBackground = Color(0xFF1D2430),
    surface = Color(0xFFFDFCFA),
    onSurface = Color(0xFF1D2430),
    surfaceVariant = Color(0xFFEBE7DE),
    onSurfaceVariant = Color(0xFF5D6878),
    surfaceTint = Color(0xFF7A5A12),
    inverseSurface = Color(0xFF1D2430),
    inverseOnSurface = Color(0xFFE6EBF2),
    outline = Color(0xFF8F8670),
    outlineVariant = Color(0xFFE7E2D7),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFDFCFA),
    surfaceDim = Color(0xFFEBE7DE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF6F3EC),
    surfaceContainerHigh = Color(0xFFEBE7DE),
    surfaceContainerHighest = Color(0xFFEBE6DA),
)

internal val Dark = darkColorScheme(
    primary = SagaGold,
    onPrimary = Color(0xFF3F2E00),
    primaryContainer = Color(0xFF5A4410),
    onPrimaryContainer = Color(0xFFF3E2BC),
    inversePrimary = Color(0xFF7A5A12),
    secondary = Color(0xFF8B97A8),
    onSecondary = SagaNight,
    secondaryContainer = Color(0xFF3A3020),
    onSecondaryContainer = Color(0xFFF3E2BC),
    tertiary = Color(0xFF9CC9EC),
    onTertiary = Color(0xFF00344F),
    tertiaryContainer = Color(0xFF1E4B6A),
    onTertiaryContainer = Color(0xFFCBE6FF),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF3D1718),
    onErrorContainer = Color(0xFFFFDAD6),
    background = SagaNight,
    onBackground = Color(0xFFE6EBF2),
    surface = SagaNight,
    onSurface = Color(0xFFE6EBF2),
    surfaceVariant = Color(0xFF222C3A),
    onSurfaceVariant = Color(0xFF8B97A8),
    surfaceTint = SagaGold,
    inverseSurface = Color(0xFFE6EBF2),
    inverseOnSurface = Color(0xFF1D2430),
    outline = Color(0xFF717E93),
    outlineVariant = Color(0xFF263242),
    scrim = Color.Black,
    surfaceBright = Color(0xFF252F3D),
    surfaceDim = SagaNight,
    surfaceContainerLowest = Color(0xFF0A0E14),
    surfaceContainerLow = Color(0xFF18202C),
    surfaceContainer = Color(0xFF202B3A),
    surfaceContainerHigh = Color(0xFF222C3A),
    surfaceContainerHighest = Color(0xFF252F3D),
)

@Composable
fun SagaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) Dark else Light
    // Bewegung: Material 3 Expressive (Federn mit leichtem Nachschwingen). "Animationen entfernen" bzw.
    // ANIMATOR_DURATION_SCALE = 0 -> ruhige Federn ohne Ueberschwingen (Compose kuerzt die Dauer zusaetzlich selbst).
    val reduced = rememberReducedMotion()
    val motion = remember(reduced) { if (reduced) SagaMotion.Calm else SagaMotion.Expressive }
    SystemBarIcons(dark)
    MaterialTheme(colorScheme = scheme, shapes = SagaShapes, typography = SagaTypography) {
        // Grund-Textfarbe passend zum Hintergrund: Bildschirme ohne Scaffold/Surface (Seiten, Datenbank-Bloecke,
        // Einbettungen) erben sonst LocalContentColor = Schwarz - im Dunkelmodus schwarz auf schwarz.
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            LocalReducedMotion provides reduced,
            LocalSagaMotion provides motion,
            LocalSagaAccent provides if (dark) DarkAccent else LightAccent,
            LocalSagaColors provides if (dark) DarkSignals else LightSignals,
            content = content,
        )
    }
}

/**
 * Symbole der Status- und Navigationsleiste nach Thema: dunkel -> helle Symbole. enableEdgeToEdge() entscheidet nur
 * beim Start; MainActivity behandelt uiMode selbst (configChanges) und wird beim Themenwechsel nicht neu erzeugt.
 */
@Composable
private fun SystemBarIcons(dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (view.isInEditMode) return
    androidx.compose.runtime.SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
