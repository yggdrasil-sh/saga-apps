package io.github.yggdrasil_sh.saga.call

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R

/**
 * Debug-Quelle der Meeting-Ansicht: erfundene Teilnehmende (keine echten Daten, keine Medien, keine Kamera) fuer
 * Bildschirmfotos im Geraetetest. Benutzt dieselbe [MeetingLayer] wie der echte Anruf; Bilder sind Farbflaechen, der
 * geteilte Bildschirm ein 16:10-Testbild mit Farbbalken.
 *
 * scenario: "presentation" (4 Personen, Anna teilt vom Android-Begleitgeraet), "gallery" (7 Personen), "speaker"
 * (8 Personen, Cem spricht), "duo" (1:1, eigenes Bild schwebend).
 */
object MeetDemo {
    fun devs(scenario: String): List<Meeting.Dev> {
        val me = Meeting.Dev("erik", "Erik", "me", "Pixel", me = true, mic = true, cam = true)
        val people = listOf("Anna", "Ben", "Cem", "Dora", "Emil", "Finn", "Gina")
        fun p(i: Int, cam: Boolean = true, mic: Boolean = true, speaking: Boolean = false) =
            Meeting.Dev(people[i].lowercase(), people[i], "d$i", "Laptop", mic = mic, cam = cam, speaking = speaking, level = if (speaking) 0.3 else 0.0)
        return when (scenario) {
            "presentation" -> listOf(
                Meeting.Dev("anna", "Anna", "a-mac", "MacBook", mic = true, cam = true),
                Meeting.Dev("anna", "Anna", "a-ios", "iPhone", companion = true),
                Meeting.Dev("anna", "Anna", "a-and", "Android", companion = true, screen = true, sharedAt = 1),
                p(1, speaking = true), p(2, cam = false), me,
            )
            "gallery", "novideo" -> listOf(p(0), p(1, speaking = true), p(2, cam = false), p(3, mic = false), p(4), p(5, cam = false), p(6), me)
            "speaker" -> listOf(p(0), p(1), p(2, speaking = true), p(3, cam = false), p(4), p(5, mic = false), p(6), me)
            else -> listOf(Meeting.Dev("anna", "Anna", "a-mac", "MacBook", mic = true, cam = true), me)
        }
    }

    fun speaker(scenario: String): String? = if (scenario == "speaker") "cem" else null

    fun pref(scenario: String): Meeting.Pref = when (scenario) {
        "gallery", "novideo" -> Meeting.Pref.GALLERY
        "speaker" -> Meeting.Pref.SPEAKER
        else -> Meeting.Pref.AUTO
    }
}

/** Testbild eines geteilten Bildschirms (16:10, Farbbalken, Fensterrahmen) */
@Composable
private fun DemoScreen(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.aspectRatio(16f / 10f).fillMaxSize()) {
            val bars = listOf(Color(0xFFC0C0C0), Color(0xFFC0C000), Color(0xFF00C0C0), Color(0xFF00C000), Color(0xFFC000C0), Color(0xFFC00000), Color(0xFF0000C0))
            drawRect(Color(0xFFF4F4F4))
            drawRect(Color(0xFF2F3A4A), size = Size(size.width, size.height * 0.08f))
            val bw = size.width / bars.size
            bars.forEachIndexed { i, c -> drawRect(c, Offset(i * bw, size.height * 0.16f), Size(bw, size.height * 0.5f)) }
            for (k in 0 until 6) drawRect(Color(0xFF9AA4B2), Offset(size.width * 0.06f, size.height * (0.72f + k * 0.04f)), Size(size.width * (0.88f - k * 0.1f), size.height * 0.018f))
        }
    }
}

@Composable
private fun DemoCam(seed: Int, modifier: Modifier, portrait: Boolean = false) {
    val c = listOf(Color(0xFF3A6EA5), Color(0xFF7A4E9E), Color(0xFF2F8F6B), Color(0xFFB0643C), Color(0xFF8E3B5B))
    Box(modifier.background(Brush.linearGradient(listOf(c[seed % c.size], c[(seed + 2) % c.size]))))
}

/** Bildschirmfoto-Vorschau der Meeting-Ansicht (Debug); hidden = Leisten ausgeblendet */
@Composable
fun MeetDemoContent(scenario: String, hidden: Boolean = false) {
    val conf = LocalConfiguration.current
    val phone = conf.smallestScreenWidthDp < 600
    val flat = CallLayout.flat(conf.screenWidthDp, conf.screenHeightDp)
    val ms = remember(scenario) { MeetState(MeetDemo.pref(scenario), showNoVideo = scenario != "novideo") }
    val model = remember(scenario) { Meeting.tiles(MeetDemo.devs(scenario), "me") }
    androidx.compose.runtime.CompositionLocalProvider(LocalFlatCall provides flat) {
        MeetingLayer(
            model, ms, MeetDemo.speaker(scenario), hidden = hidden, reducedMotion = true, phone = phone, onActivity = {}, sideControls = flat,
            tileVideo = { t, _, m -> if (t.screen) DemoScreen(m) else DemoCam(t.device.hashCode().let { if (it < 0) -it else it }, m) },
            selfVideo = { m, onA ->
                LaunchedEffect(Unit) { onA(if (phone) 3f / 4f else 16f / 9f) }
                DemoCam(4, m)
            },
            header = {
                if (flat) {
                    Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(6.dp), contentAlignment = Alignment.Center) {
                        val title = if (scenario == "duo" || scenario == "presentation") "Anna" else "Projekt Nordlicht"
                        MeetPill(title, "12:34" + if (model.persons > 2) " · " + meetPersons(model.persons) else "", stringResource(R.string.call_e2e),
                            Modifier.padding(horizontal = 56.dp))
                    }
                    return@MeetingLayer
                }
                Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (scenario == "duo" || scenario == "presentation") "Anna" else "Projekt Nordlicht", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("12:34 · " + stringResource(R.string.call_e2e), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    if (model.persons > 2) Text(meetPersons(model.persons), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            },
            pinned = {},
            notices = {},
            controls = {
                if (flat) {
                    val tc = MaterialTheme.colorScheme.surfaceContainerHighest
                    val tf = MaterialTheme.colorScheme.onSurface
                    Box(Modifier.fillMaxHeight().safeDrawingPadding().padding(4.dp)) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f), shape = RoundedCornerShape(32.dp)) {
                            Column(Modifier.width(64.dp).fillMaxHeight().padding(vertical = 6.dp), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                                RoundButton(R.drawable.ic_mic, stringResource(R.string.call_mute), tc, tf, compact = true, col = 48.dp) {}
                                RoundButton(R.drawable.ic_videocam, stringResource(R.string.call_cam_off), tc, tf, compact = true, col = 48.dp) {}
                                RoundButton(R.drawable.ic_screen_share, stringResource(R.string.call_screen_share), tc, tf, compact = true, col = 48.dp) {}
                                RoundButton(R.drawable.ic_volume_up, stringResource(R.string.call_speaker), tc, tf, compact = true, col = 48.dp) {}
                                if (Meeting.layoutButton(model)) MeetLayoutButton(ms, model.screens.isNotEmpty(), 48.dp, tc, tf) {}
                                RoundButton(R.drawable.ic_call_end, stringResource(R.string.call_hangup), CallLabels.HANGUP_BG, Color.White, compact = true, col = 48.dp) {}
                            }
                        }
                    }
                    return@MeetingLayer
                }
                BoxWithConstraints(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    val showLayout = Meeting.layoutButton(model)
                    val n = if (showLayout) 7 else 6
                    val col = minOf(72.dp, maxWidth / n)
                    val tc = MaterialTheme.colorScheme.surfaceContainerHighest
                    val tf = MaterialTheme.colorScheme.onSurface
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f), shape = RoundedCornerShape(if (flat) 28.dp else 36.dp)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = if (flat) 4.dp else 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                            RoundButton(R.drawable.ic_mic, stringResource(R.string.call_mute), tc, tf, compact = true, col = col) {}
                            RoundButton(R.drawable.ic_videocam, stringResource(R.string.call_cam_off), tc, tf, compact = true, col = col) {}
                            RoundButton(R.drawable.ic_cameraswitch, stringResource(R.string.call_switch_camera), tc, tf, compact = true, col = col) {}
                            RoundButton(R.drawable.ic_screen_share, stringResource(R.string.call_screen_share), tc, tf, compact = true, col = col) {}
                            RoundButton(R.drawable.ic_volume_up, stringResource(R.string.call_speaker), tc, tf, compact = true, col = col) {}
                            if (showLayout) MeetLayoutButton(ms, model.screens.isNotEmpty(), col, tc, tf) {}
                            RoundButton(R.drawable.ic_call_end, stringResource(R.string.call_hangup), CallLabels.HANGUP_BG, Color.White, compact = true, col = col) {}
                        }
                    }
                }
            },
        )
    }
}
