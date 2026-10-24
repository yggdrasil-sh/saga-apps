package io.github.yggdrasil_sh.saga.call

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import kotlin.math.roundToInt

/**
 * 2.5: eigenes Bild im Anruf - in eine Ecke ziehen (rastet an der naechsten ein) oder ausblenden (bleibt als kleiner
 * Knopf in der Ecke, Tippen zeigt es wieder). Ecke und Ausblenden merkt sich das Geraet fuer alle Anrufe.
 * top/bottom/side: freier Rand (Kopfzeile oben, Steuerknoepfe unten). content bekommt den Modifier fuer das Bild.
 */
@Composable
internal fun SelfPip(
    width: Dp, height: Dp, top: Dp, bottom: Dp, side: Dp, modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("call_ui", Context.MODE_PRIVATE) }
    var corner by remember { mutableIntStateOf(prefs.getInt("self_corner", 1)) }     // 0 o.l., 1 o.r., 2 u.l., 3 u.r.
    var hidden by remember { mutableStateOf(prefs.getBoolean("self_hidden", false)) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val d = LocalDensity.current
    val moveLabel = stringResource(R.string.call_self_move)
    BoxWithConstraints(modifier.fillMaxSize()) {
        // A05/A06: nie ueber die Knopfzone (bottom) - bei wenig Hoehe (Querformat, grosse Anzeige) kleiner bzw. nicht zeigen
        val g = SelfPipGeo.place(maxWidth.value, maxHeight.value, width.value, height.value, top.value, bottom.value, side.value, corner, hidden)
            ?: return@BoxWithConstraints
        val w = g.w.dp
        val h = g.h.dp
        val x = g.x.dp
        val y = g.y.dp
        val geo by rememberUpdatedState(listOf(x, y, w, h, maxWidth, maxHeight))
        Box(
            Modifier
                .offset { IntOffset((x.roundToPx() + drag.x).roundToInt(), (y.roundToPx() + drag.y).roundToInt()) }
                .size(w, h)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            val (gx, gy, gw, gh, mw, mh) = geo
                            val cx = with(d) { gx.toPx() + gw.toPx() / 2 } + drag.x
                            val cy = with(d) { gy.toPx() + gh.toPx() / 2 } + drag.y
                            val midY = with(d) { (top.toPx() + mh.toPx() - bottom.toPx()) / 2 }
                            corner = (if (cx < with(d) { mw.toPx() } / 2) 0 else 1) + (if (cy < midY) 0 else 2)
                            prefs.edit().putInt("self_corner", corner).apply()
                            drag = Offset.Zero
                        },
                        onDragCancel = { drag = Offset.Zero },
                    ) { ch, delta -> ch.consume(); drag += delta }
                }
                .semantics {
                    customActions = listOf(CustomAccessibilityAction(moveLabel) {
                        corner = (corner + 1) % 4; prefs.edit().putInt("self_corner", corner).apply(); true
                    })
                },
        ) {
            if (hidden) {
                IconButton(
                    onClick = { hidden = false; prefs.edit().putBoolean("self_hidden", false).apply() },
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color(0x99000000))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                ) { Icon(painterResource(R.drawable.ic_person_video), stringResource(R.string.call_self_show), tint = Color.White) }
            } else {
                content(Modifier.fillMaxSize().shadow(6.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)))
                IconButton(
                    onClick = { hidden = true; prefs.edit().putBoolean("self_hidden", true).apply() },
                    modifier = Modifier.align(Alignment.TopStart).size(40.dp),
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0x73000000)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_visibility_off), stringResource(R.string.call_self_hide), tint = Color.White,
                            modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]

/** Reine Lage des eigenen Bilds (Werte in dp, Unit-Tests) */
internal object SelfPipGeo {
    /** kleinste sinnvolle Hoehe des Bilds bzw. des Knopfs "Einblenden" */
    const val MIN = 44f
    /** Querformat: hoechstens dieser Anteil der Hoehe (Video bleibt sichtbar) */
    const val LAND_SHARE = 0.45f

    data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * Lage in der Flaeche maxW x maxH: oben `top` (Kopfzeile) und unten `bottom` (Knopfzone) frei, seitlich `side`.
     * Die Unterkante liegt nie in der Knopfzone. Reicht die Hoehe nicht, wird das Bild (Seitenverhaeltnis bleibt) kleiner und
     * der obere Rand notfalls freigegeben; ist selbst dafuer kein Platz, null (nicht zeigen).
     */
    fun place(maxW: Float, maxH: Float, w: Float, h: Float, top: Float, bottom: Float, side: Float, corner: Int, hidden: Boolean): Box? {
        val floor = maxH - bottom                                   // tiefste erlaubte Unterkante
        if (floor < MIN || w <= 0f || h <= 0f) return null
        val t = if (floor - top >= MIN) top else maxOf(0f, floor - MIN)
        val avail = floor - t
        val (bw, bh) = if (hidden) MIN to MIN else {
            val cap = if (maxW > maxH) maxOf(MIN, maxH * LAND_SHARE) else Float.MAX_VALUE
            val th = minOf(h, avail, cap)
            val k = th / h
            w * k to th
        }
        val x = if (corner % 2 == 0) side else maxW - bw - side
        val y = if (corner < 2) t else floor - bh
        return Box(x, y, bw, bh)
    }
}
