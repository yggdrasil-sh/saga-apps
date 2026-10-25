package io.github.yggdrasil_sh.saga.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.ui.chat.ChatAvatar
import io.github.yggdrasil_sh.saga.ui.chat.reducedMotion
import io.github.yggdrasil_sh.saga.ui.theme.SagaGold
import io.github.yggdrasil_sh.saga.ui.theme.SagaNight

/** Design 2: Flaechen und Profilkreis des Anrufbildschirms (immer im Nachtblau von Saga, Akzent Gold) */
internal object CallDesign {
    /** Hintergrund ohne Video: Nachtblau, nach unten etwas heller, oben ein Hauch Gold */
    val backdrop: Brush = Brush.verticalGradient(listOf(Color(0xFF1A1F2A), SagaNight, Color(0xFF151C27)))

    /** Abstand der Ringe um den Profilkreis beim Klingeln/Verbinden (Anteil des Radius) */
    const val HALO_SPREAD = 0.55f
}

/**
 * Profilkreis im Anruf. waiting (klingelt, verbindet): zwei goldene Ringe laufen langsam nach aussen; mit "Animationen
 * entfernen" bzw. ohne Warten ein ruhiger Ring. Der Name steht darunter (kein eigener Text fuer Bildschirmleser).
 */
@Composable
internal fun CallHaloAvatar(name: String, size: Dp, waiting: Boolean, avatar: String? = null, icon: Int? = null) {
    val still = reducedMotion() || !waiting
    val outer = size * (1f + CallDesign.HALO_SPREAD)
    Box(Modifier.size(outer), contentAlignment = Alignment.Center) {
        if (still) {
            Canvas(Modifier.size(outer)) {
                drawCircle(SagaGold.copy(alpha = 0.35f), radius = this.size.minDimension / 2f * (1f / (1f + CallDesign.HALO_SPREAD)) + 5.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
        } else {
            val t = rememberInfiniteTransition(label = "halo")
            val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "p")
            Canvas(Modifier.size(outer)) {
                val r0 = this.size.minDimension / 2f / (1f + CallDesign.HALO_SPREAD)
                val r1 = this.size.minDimension / 2f
                for (k in 0..1) {
                    val q = (p + k * 0.5f) % 1f
                    drawCircle(SagaGold.copy(alpha = 0.45f * (1f - q)), radius = r0 + (r1 - r0) * q, style = Stroke(2.dp.toPx()))
                }
            }
        }
        ChatAvatar(name, size, avatar = avatar, icon = icon)
    }
}
