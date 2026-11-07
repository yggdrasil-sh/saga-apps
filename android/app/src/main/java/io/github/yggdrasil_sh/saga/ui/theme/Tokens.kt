package io.github.yggdrasil_sh.saga.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Saga-Design-System (Android, 2.5): Abstaende, Radien, Schrift, Bewegung und Akzentfarben an einer Stelle.
 * Bildschirme nehmen Werte von hier (SagaSpace.l statt 16.dp), damit Web, iOS und Android gleich ruhig wirken.
 */

/** Abstaende im 4-dp-Raster */
object SagaSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** seitlicher Rand der Inhalte (Telefon) */
    val gutter = 16.dp
    /** groesste Lesebreite (Tablet/Querformat) */
    val readingWidth = 760.dp
    /** Mindest-Trefferflaeche (Material: 48 dp) */
    val touch = 48.dp
}

/**
 * Eckenradien wie im Web (Entscheidung des Maintainers, A11Y-19; static/tokens.css VD-32/AE-22):
 * | Token | dp  | Web                         | wofuer                                                    |
 * |-------|-----|-----------------------------|-----------------------------------------------------------|
 * | xs    |  4  | (nur chat.css: Blasen-Ecke) | kleine Marken, die "nahe" Ecke von Sprechblasen           |
 * | s     |  6  | --r-sm                      | Steuerelemente: Felder, Knoepfe, Etiketten, Menue-Eintraege |
 * | md    | 10  | --r-md                      | Karten, Menues, Popups, Kacheln, Segmentlisten            |
 * | l     | 14  | 14px (Blasen, Anruf-Kacheln) | Sprechblasen, Video-Kacheln, grosse Flaechen             |
 * | xl    | 16  | 16px (mobile Blaetter)      | Blaetter, Dialoge, Sheets (oben)                          |
 * | pill  | 999 | --r-pill                    | Pillen, Suchfeld, Chips mit Vollrundung                   |
 * Avatare: CircleShape (Web 50 %). Keine weiteren Groessen - Literale ausserhalb von ui/theme ersetzt Phase B.
 * xl ist 16 statt 20: das Web rundet mobile Blaetter (.cm-panel, .gr-panel, Gruppeninfo) mit 16 px; 20 kommt im
 * Web nur beim runden Eingabefeld vor, das hier pill ist.
 */
object SagaRadius {
    val xs = 4.dp
    val s = 6.dp
    val md = 10.dp
    /** Alias fuer [md] (frueher 14 dp) */
    val m = md
    val l = 14.dp
    val xl = 16.dp
    val pill = 999.dp
}

/**
 * Material-Formen auf die Web-Radien. extraSmall = 6 statt Material 4: Material nimmt extraSmall fuer Textfelder,
 * Menues, Snackbar und Tooltips, das Web rundet Felder und Steuerelemente mit --r-sm (6); 4 gibt es im Web nicht als
 * Flaechenradius. Karten (Material: medium) = 10 wie Web-Karten, Blaetter/Dialoge (extraLarge) = 16.
 */
val SagaShapes = Shapes(
    extraSmall = RoundedCornerShape(SagaRadius.s),
    small = RoundedCornerShape(SagaRadius.s),
    medium = RoundedCornerShape(SagaRadius.md),
    large = RoundedCornerShape(SagaRadius.l),
    extraLarge = RoundedCornerShape(SagaRadius.xl),
)

/**
 * Schrift: Systemschrift (keine eingebettete Schriftdatei), aber kraeftigere Titel mit etwas engerem Zeichenabstand
 * (Expressive "emphasized"), Fliesstext ruhig mit kleinerem Zeichenabstand als der Material-Standard.
 *
 * Umbruch (ED-13): Silbentrennung (Hyphens.Auto) und Absatz-Umbruch (LineBreak.Paragraph) in allen Stilen, damit
 * lange deutsche Komposita mit Trennstrich statt mitten im Wort umbrechen ("Datenschutz-Schulun / g"). Die Sprache
 * kommt ohne eigene localeList aus Locale.getDefault(): ab Android 13 setzt der LocaleManager sie auf die App-Sprache,
 * darunter AppLanguage.wrap/applyToApp per Locale.setDefault. Ohne Trennmuster (z. B. Thai) bleibt es beim Umbruch.
 */
val SagaTypography: Typography = Typography().let { t ->
    fun TextStyle.w(weight: FontWeight, spacing: Float? = null) =
        copy(fontWeight = weight, letterSpacing = spacing?.sp ?: letterSpacing).wrap()
    Typography(
        displayLarge = t.displayLarge.w(FontWeight.Bold, -0.5f),
        displayMedium = t.displayMedium.w(FontWeight.Bold, -0.5f),
        displaySmall = t.displaySmall.w(FontWeight.Bold, -0.25f),
        headlineLarge = t.headlineLarge.w(FontWeight.Bold, -0.25f),
        headlineMedium = t.headlineMedium.w(FontWeight.SemiBold, -0.15f),
        headlineSmall = t.headlineSmall.w(FontWeight.SemiBold, 0f),
        titleLarge = t.titleLarge.w(FontWeight.SemiBold, 0f),
        titleMedium = t.titleMedium.w(FontWeight.SemiBold, 0.1f),
        titleSmall = t.titleSmall.w(FontWeight.SemiBold, 0.1f),
        bodyLarge = t.bodyLarge.copy(letterSpacing = 0.15.sp).wrap(),
        bodyMedium = t.bodyMedium.copy(letterSpacing = 0.2.sp).wrap(),
        bodySmall = t.bodySmall.copy(letterSpacing = 0.3.sp).wrap(),
        labelLarge = t.labelLarge.w(FontWeight.SemiBold, 0.1f),
        labelMedium = t.labelMedium.w(FontWeight.SemiBold, 0.3f),
        labelSmall = t.labelSmall.w(FontWeight.Medium, 0.4f),
    )
}

/** Silbentrennung und Absatz-Umbruch (ED-13) */
internal fun TextStyle.wrap(): TextStyle = copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)

/**
 * Bewegung im Stil von Material 3 Expressive: raeumliche Federn mit leichtem Nachschwingen, Effekte (Alpha, Farbe)
 * ohne. Calm: bei "Animationen entfernen" - kein Nachschwingen, schnell. Eigene Umsetzung, weil MotionScheme in
 * Material3 1.4 noch nicht oeffentlich ist. Zugriff: SagaMotion.current.defaultSpatial() usw.
 */
@Immutable
class SagaMotionScheme internal constructor(private val damping: Float, private val fastDamping: Float, private val stiff: FloatArray) {
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(damping, stiff[0])
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(fastDamping, stiff[1])
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(damping, stiff[2])
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(1f, 1600f)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(1f, 3800f)
    fun <T> slowEffects(): FiniteAnimationSpec<T> = spring(1f, 800f)
}

object SagaMotion {
    val Expressive = SagaMotionScheme(damping = 0.8f, fastDamping = 0.6f, stiff = floatArrayOf(380f, 800f, 200f))
    val Calm = SagaMotionScheme(damping = 1f, fastDamping = 1f, stiff = floatArrayOf(1400f, 3800f, 800f))
    val current: SagaMotionScheme
        @Composable get() = LocalSagaMotion.current
}

val LocalSagaMotion = staticCompositionLocalOf { SagaMotion.Expressive }

/** true, wenn die Person Animationen ausgeschaltet hat (Bedienungshilfen "Animationen entfernen" / Entwickleroption) */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Bewegung reduziert? (aus SagaTheme) - fuer Zier-Effekte wie Druck-Skalierung oder Einblenden */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Saga-Akzente neben dem Material-Schema: Gold fuer Zierlinien/Markierungen (nicht fuer kleinen Text) und
 * kraeftiges Gold fuer Text auf hellem/dunklem Grund (Kontrast >= 4.5:1, siehe ThemeContrastTest).
 */
@Immutable
data class SagaAccent(
    /** Zierfarbe (Linien, Punkte, Auswahlbalken) */
    val gold: Color,
    /** Gold als Textfarbe auf surface */
    val goldText: Color,
    /** zarte Goldflaeche (Hervorhebung "heute", aktive Ansicht) */
    val goldSoft: Color,
    /** Text auf goldSoft */
    val onGoldSoft: Color,
)

internal val LightAccent = SagaAccent(gold = SagaGold, goldText = Color(0xFF7A5A12), goldSoft = Color(0xFFF3E2BC), onGoldSoft = Color(0xFF3D2C00))
internal val DarkAccent = SagaAccent(gold = SagaGold, goldText = Color(0xFFE2C07E), goldSoft = Color(0xFF3A3020), onGoldSoft = Color(0xFFF3E2BC))

val LocalSagaAccent = compositionLocalOf { LightAccent }

/**
 * Signalfarben (A11Y-22), Werte 1:1 aus static/tokens.css, hell und dunkel. Zugriff: SagaColors.current.ok usw.
 * Textfarben (ok, warn, down, overdue, info) erreichen wie im Web >= 4,5:1 auf background und Karte
 * (surfaceContainerLow); auf hoeheren Containern fuer Text overdue statt down nehmen (siehe ThemeContrastTest).
 * up/unk nur fuer Punkte und Balken. call* = Anruf-Buehne (--cl-*), bewusst in beiden Schemata gleich und dunkel.
 */
@Immutable
data class SagaSignals(
    /** --ok: Gruen fuer Text, Live-Lampe */
    val ok: Color,
    /** --up: Gruen nur fuer Punkte/Balken */
    val up: Color,
    /** --warn */
    val warn: Color,
    /** --down: Fehler/ausgefallen */
    val down: Color,
    /** --overdue: ueberfaellig, Text-sicheres Rot */
    val overdue: Color,
    /** --info */
    val info: Color,
    /** --unk: unbekannt (Punkte) */
    val unk: Color,
    /** --warnbg: Flaeche fuer Warnungen */
    val warnBg: Color,
    /** --critbg: Flaeche fuer Fehler */
    val critBg: Color,
    /** --cl-green: Anruf annehmen */
    val callGreen: Color = Color(0xFF2EA043),
    /** --cl-red: Anruf auflegen/ablehnen */
    val callRed: Color = Color(0xFFDA3633),
    /** --cl-ok/--cl-warn/--cl-err: Verbindungsguete auf der Anruf-Buehne */
    val callOk: Color = Color(0xFF56D364),
    val callWarn: Color = Color(0xFFE3B341),
    val callErr: Color = Color(0xFFF85149),
    /** --cl-bg/--cl-panel/--cl-pop/--cl-btn/--cl-line/--cl-edge/--cl-fg: Flaechen der Anruf-Buehne */
    val callBg: Color = Color(0xFF0B0F15),
    val callPanel: Color = Color(0xFF151C27),
    val callPop: Color = Color(0xFF1A2230),
    val callBtn: Color = Color(0xFF2A3444),
    val callLine: Color = Color(0xFF3A475B),
    val callEdge: Color = Color(0xFF263242),
    val callFg: Color = Color(0xFFE6EBF2),
)

internal val LightSignals = SagaSignals(
    ok = Color(0xFF1F7A33), up = Color(0xFF3FB950), warn = Color(0xFF8A5A00), down = Color(0xFFCF222E),
    overdue = Color(0xFFB3121F), info = Color(0xFF0B5CAD), unk = Color(0xFFB3BAC5),
    warnBg = Color(0xFFFBF0D4), critBg = Color(0xFFFBE0DE),
)
internal val DarkSignals = SagaSignals(
    ok = Color(0xFF3FB950), up = Color(0xFF3FB950), warn = Color(0xFFD29922), down = Color(0xFFF85149),
    overdue = Color(0xFFFF7B72), info = Color(0xFF58A6FF), unk = Color(0xFF5A6577),
    warnBg = Color(0xFF3A2F12), critBg = Color(0xFF3D1718),
)

val LocalSagaColors = compositionLocalOf { LightSignals }

/** Signalfarben des aktuellen Schemas (aus SagaTheme) */
object SagaColors {
    val current: SagaSignals
        @Composable get() = LocalSagaColors.current
}
