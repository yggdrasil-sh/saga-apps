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
        val w = if (hidden) 44.dp else width
        val h = if (hidden) 44.dp else height
        val x = if (corner % 2 == 0) side else maxWidth - w - side
        val y = if (corner < 2) top else maxOf(top, maxHeight - h - bottom)
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
                        Icon(painterResource(R.drawable.ic_videocam_off), stringResource(R.string.call_self_hide), tint = Color.White,
                            modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
