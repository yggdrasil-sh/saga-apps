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

/** Eckenradien: weich und deutlich runder als Material-Standard (Expressive), Karten 20 dp */
object SagaRadius {
    val xs = 6.dp
    val s = 10.dp
    val m = 14.dp
    val l = 20.dp
    val xl = 28.dp
    val pill = 999.dp
}

val SagaShapes = Shapes(
    extraSmall = RoundedCornerShape(SagaRadius.xs),
    small = RoundedCornerShape(SagaRadius.s),
    medium = RoundedCornerShape(SagaRadius.m),
    large = RoundedCornerShape(SagaRadius.l),
    extraLarge = RoundedCornerShape(SagaRadius.xl),
)

/**
 * Schrift: Systemschrift (keine eingebettete Schriftdatei), aber kraeftigere Titel mit etwas engerem Zeichenabstand
 * (Expressive "emphasized"), Fliesstext ruhig mit kleinerem Zeichenabstand als der Material-Standard.
 */
val SagaTypography: Typography = Typography().let { t ->
    fun TextStyle.w(weight: FontWeight, spacing: Float? = null) = copy(fontWeight = weight, letterSpacing = spacing?.sp ?: letterSpacing)
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
        bodyLarge = t.bodyLarge.copy(letterSpacing = 0.15.sp),
        bodyMedium = t.bodyMedium.copy(letterSpacing = 0.2.sp),
        bodySmall = t.bodySmall.copy(letterSpacing = 0.3.sp),
        labelLarge = t.labelLarge.w(FontWeight.SemiBold, 0.1f),
        labelMedium = t.labelMedium.w(FontWeight.SemiBold, 0.3f),
        labelSmall = t.labelSmall.w(FontWeight.Medium, 0.4f),
    )
}

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
