package io.github.yggdrasil_sh.saga.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.delay

// Gjallarhorn (Saga 2.5, P12): Oberflaeche fuer Gruppenanrufe ueber Knoten - Kacheln mit Last-N und Sprecheransicht,
// Weg-Anzeige mit Qualitaetspunkt, Runensiegel mit Erklaerung, Teilnehmerliste mit Moderation fuer Gastgeber.
// Die Bilder liefert der Aufrufer (VideoView an den ProxySinks des CallManagers; in der Vorschau Platzhalter).

/** Kachel: Geraet eines anderen (bewegtes Bild, sonst Standbild/Avatar mit Sprechring) */
data class HornTile(
    val dev: String,
    val name: String,
    val devName: String,
    val avatar: String? = null,
    /** hat gerade einen Bildplatz am Knoten (Last-N) und die Kamera an */
    val video: Boolean,
    val mic: Boolean,
    val speaking: Boolean,
    val big: Boolean = false,
)

/** Eintrag der Teilnehmerliste */
data class HornPerson(
    val dev: String,
    val name: String,
    val devName: String,
    val avatar: String? = null,
    val me: Boolean = false,
    val mic: Boolean = true,
    val companion: Boolean = false,
    val host: Boolean = false,
    val speaking: Boolean = false,
    val unverified: Boolean = false,
    /** Gastgeber darf diese Person stummschalten/entfernen */
    val moderate: Boolean = false,
)

/** Anzeige eines Knoten-Anrufs */
data class HornUi(
    val connected: Boolean = false,
    /** udp | tcp | turn */
    val path: String = "",
    val node: String = "",
    /** good | mid | bad | "" */
    val quality: String = "",
    val rtt: Int? = null,
    val lossPct: Double = 0.0,
    val changing: Boolean = false,
    val tiles: List<HornTile> = emptyList(),
    val more: Int = 0,
    /** grid | speaker */
    val view: String = "grid",
    val runes: String = "",
    val runeNames: List<String> = emptyList(),
    val unverified: List<String> = emptyList(),
    val people: List<HornPerson> = emptyList(),
    val amHost: Boolean = false,
    /** Name der Person, die gerade den Bildschirm teilt (nicht ich) */
    val screenFrom: String? = null,
    val note: UiText? = null,
    /** grosses Bild vorhanden (Sprecher bzw. Bildschirm) - Bild-im-Bild */
    val bigVideo: Boolean = false,
)

private val TILE_BG = Color(0xFF202227)
private val SPEAK = Color(0xFF8BE28B)

@Composable
fun hornQualityColor(q: String): Color = when (q) {
    "good" -> Color(0xFF2E9E4F)
    "mid" -> Color(0xFFE0A100)
    "bad" -> Color(0xFFD93025)
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun pathText(path: String): String = when (path) {
    "udp" -> stringResource(R.string.horn_path_udp)
    "turn" -> stringResource(R.string.horn_path_tls)
    else -> stringResource(R.string.horn_path_tcp)
}

@Composable
private fun qualityText(q: String): String = when (q) {
    "good" -> stringResource(R.string.horn_q_good)
    "mid" -> stringResource(R.string.horn_q_mid)
    "bad" -> stringResource(R.string.horn_q_bad)
    else -> ""
}

/**
 * Zeile unter der Status-Zeile: Weg (UDP/TCP/TLS) mit Qualitaetspunkt, "wechselt den Knoten …", Runensiegel,
 * Teilnehmende, Ansicht (Kacheln / Sprecher).
 */
@Composable
fun HornBar(ui: HornUi, onDark: Boolean, onRunes: () -> Unit, onPeople: () -> Unit, onView: () -> Unit) {
    val fg = if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val chipBg = if (onDark) Color(0x66101114) else MaterialTheme.colorScheme.surfaceContainerHigh
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.path.isNotEmpty()) {
                val desc = stringResource(R.string.horn_path_desc, ui.node, pathText(ui.path), qualityText(ui.quality)) +
                    (ui.rtt?.let { " · RTT $it ms" } ?: "") + (if (ui.lossPct >= 0.1) " · " + stringResource(R.string.horn_loss, ui.lossPct) else "")
                Surface(color = chipBg, shape = RoundedCornerShape(50), modifier = Modifier.semantics { contentDescription = desc }) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(hornQualityColor(ui.quality)))
                        Text(HornLogic.pathName(ui.path), color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (ui.runes.isNotEmpty()) {
                val cd = stringResource(R.string.horn_runes_cd, ui.runeNames.joinToString(" "))
                Surface(
                    color = chipBg, shape = RoundedCornerShape(50),
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = stringResource(R.string.horn_runes), role = Role.Button) { onRunes() }
                        .semantics { contentDescription = cd },
                ) {
                    Text(ui.runes, color = fg, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                }
            }
            val pl = stringResource(R.string.horn_people, ui.people.size)
            Surface(
                color = chipBg, shape = RoundedCornerShape(50),
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onPeople() }.semantics { contentDescription = pl },
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_group), null, Modifier.size(16.dp), tint = fg)
                    Text(ui.people.size.toString(), color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                }
            }
            val vl = stringResource(if (ui.view == "speaker") R.string.horn_view_grid else R.string.horn_view_speaker)
            Surface(
                color = chipBg, shape = RoundedCornerShape(50),
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onView() }.semantics { contentDescription = vl },
            ) {
                Icon(painterResource(R.drawable.ic_fullscreen), null, Modifier.padding(horizontal = 10.dp, vertical = 4.dp).size(16.dp), tint = fg)
            }
        }
        if (ui.changing) Text(
            stringResource(R.string.horn_switching), color = fg, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Kachelansicht (bis 25, Last-N bewegt, die anderen mit Avatar und Sprechring) bzw. Sprecheransicht (der Sprecher gross,
 * die anderen als Streifen). Bildschirm geteilt: oben gross. [video] zeichnet das Bild einer Kachel, [big] das grosse.
 */
@Composable
fun HornStage(
    ui: HornUi,
    modifier: Modifier,
    self: HornTile?,
    video: @Composable (HornTile, Modifier) -> Unit,
    big: @Composable (Modifier) -> Unit,
    selfVideo: @Composable (Modifier) -> Unit,
    onPin: (String) -> Unit,
) {
    val tiles = ui.tiles
    val strip = ui.screenFrom != null || (ui.view == "speaker" && tiles.size > 1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (strip) {
            val bigTile = tiles.firstOrNull { it.big }
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                if (ui.screenFrom != null || bigTile?.video == true) big(Modifier.fillMaxSize())
                else if (bigTile != null) Avatar(bigTile.name, size = 96, avatar = bigTile.avatar)
                val label = ui.screenFrom?.let { stringResource(R.string.call_screen_peer, it) } ?: bigTile?.name
                if (label != null) Text(
                    label, color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0x99101114), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            val rest = tiles.filter { ui.screenFrom != null || !it.big } + listOfNotNull(self)
            if (rest.isNotEmpty()) Row(Modifier.fillMaxWidth().height(108.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rest.take(5).forEach { t ->
                    androidx.compose.runtime.key(t.dev) {
                        Tile(t, Modifier.weight(1f).fillMaxSize(), t === self, video, selfVideo, onPin)
                    }
                }
                if (rest.size > 5 || ui.more > 0) MoreTile(rest.size - 5 + ui.more, Modifier.weight(1f).fillMaxSize())
            }
        } else {
            val all = tiles + listOfNotNull(self)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val n = all.size + if (ui.more > 0) 1 else 0
                val cols = HornLogic.cols(n, maxWidth.value.toInt())
                val rows = (n + cols - 1) / cols
                val scroll = rows > (if (maxWidth.value <= 640) 4 else 5)
                val h = if (scroll || rows == 0) 160.dp else (maxHeight - 8.dp * (rows - 1)) / rows
                LazyVerticalGrid(
                    GridCells.Fixed(cols), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(all, key = { it.dev }) { t ->
                        Tile(t, Modifier.fillMaxWidth().height(h), t === self, video, selfVideo, onPin)
                    }
                    if (ui.more > 0) item(key = "more") { MoreTile(ui.more, Modifier.fillMaxWidth().height(h)) }
                }
            }
        }
    }
}

@Composable
private fun MoreTile(n: Int, modifier: Modifier) {
    val cd = stringResource(R.string.horn_more_cd, n)
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(TILE_BG).semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
        Text("+$n", color = Color.White, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Tile(
    t: HornTile, modifier: Modifier, self: Boolean, video: @Composable (HornTile, Modifier) -> Unit, selfVideo: @Composable (Modifier) -> Unit,
    onPin: (String) -> Unit,
) {
    val label = buildString {
        append(t.name)
        if (t.devName.isNotEmpty()) append(" (").append(t.devName).append(")")
    }
    val speaking = stringResource(R.string.room_speaking)
    val muted = stringResource(R.string.room_muted)
    val desc = listOfNotNull(label, speaking.takeIf { t.speaking }, muted.takeIf { !t.mic }).joinToString(", ")
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(TILE_BG)
            .then(if (t.speaking) Modifier.border(3.dp, SPEAK, RoundedCornerShape(14.dp)) else Modifier)
            .then(if (!self) Modifier.clickable(onClickLabel = stringResource(R.string.horn_view_speaker)) { onPin(t.dev) } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        if (t.video) {
            if (self) selfVideo(Modifier.fillMaxSize()) else video(t, Modifier.fillMaxSize())
        } else Avatar(t.name, size = 56, avatar = t.avatar)
        Row(
            Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0x99101114), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!t.mic) Icon(painterResource(R.drawable.ic_mic_off), null, Modifier.size(14.dp), tint = Color.White)
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp))
        }
    }
}

/** Runensiegel mit Erklaerung */
@Composable
fun HornRunesDialog(ui: HornUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.horn_runes), modifier = Modifier.semantics { heading() }) },
        text = { HornRunesBody(ui) },
    )
}

@Composable
fun HornRunesBody(ui: HornUi) {
    Column {
        Text(
            ui.runes, fontSize = 40.sp, letterSpacing = 6.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = ui.runeNames.joinToString(" ") },
        )
        Text(ui.runeNames.joinToString(" · "), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
        Text(stringResource(R.string.horn_runes_info), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.horn_runes_change), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
        if (ui.unverified.isNotEmpty()) Text(
            stringResource(R.string.horn_runes_unverified, ui.unverified.joinToString(", ")), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
        )
        Text(stringResource(R.string.horn_e2e_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** Teilnehmerliste; Gastgeber: Stummschalten, Entfernen (zwei Schritte: "Wirklich entfernen?", 4 s) */
@Composable
fun HornPeopleDialog(ui: HornUi, onMute: (String) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.horn_people, ui.people.size), modifier = Modifier.semantics { heading() }) },
        text = { HornPeopleList(ui, onMute, onRemove) },
    )
}

@Composable
fun HornPeopleList(ui: HornUi, onMute: (String) -> Unit, onRemove: (String) -> Unit) {
    var sure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sure) {
        if (sure != null) {
            delay(4000)
            sure = null
        }
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(ui.people, key = { it.dev }) { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(if (p.speaking) Modifier.border(2.dp, SPEAK, CircleShape) else Modifier) { Avatar(p.name, size = 36, avatar = p.avatar) }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        p.name + if (p.me) " (" + stringResource(R.string.horn_you) + ")" else "",
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(
                        p.devName.ifEmpty { null },
                        stringResource(R.string.horn_companion).takeIf { p.companion },
                        stringResource(R.string.horn_host).takeIf { p.host },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.unverified) Text(stringResource(R.string.horn_unverified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (!p.mic) Icon(painterResource(R.drawable.ic_mic_off), stringResource(R.string.room_muted), Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.moderate) {
                    val muteCd = stringResource(R.string.horn_mute_cd, p.name)
                    val rmCd = stringResource(R.string.horn_remove_cd, p.name)
                    if (p.mic) TextButton(onClick = { onMute(p.dev) }, modifier = Modifier.semantics { contentDescription = muteCd }) {
                        Text(stringResource(R.string.horn_mute))
                    }
                    val isSure = sure == p.dev
                    OutlinedButton(modifier = Modifier.semantics { contentDescription = if (isSure) rmCd + "?" else rmCd }, onClick = {
                        if (isSure) {
                            sure = null
                            onRemove(p.dev)
                        } else sure = p.dev
                    }) {
                        Text(
                            stringResource(if (isSure) R.string.horn_remove_sure else R.string.horn_remove),
                            color = if (isSure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------- Vorschau (Bildschirmfotos, nur Tests)

/** Beispielanruf mit 7 Geraeten (fuer Bildschirmfotos im Geraetetest, keine echten Daten) */
fun hornPreviewUi(view: String = "grid", screen: Boolean = false): HornUi {
    val names = listOf("Astrid", "Bjorn", "Freya", "Gunnar", "Ingrid", "Leif", "Sigrun")
    val tiles = names.mapIndexed { i, n ->
        HornTile("d$i", n, if (i % 3 == 0) "Pixel" else if (i % 3 == 1) "iPhone" else "Laptop", video = i < 3, mic = i != 4, speaking = i == 1)
    }
    val lay = HornLogic.tiles(tiles.map { it.dev }, tiles.filter { it.video }.map { it.dev }.toSet(), "d1", view)
    val byDev = tiles.associateBy { it.dev }
    val seal = HornE2E.runeSeal("0123456789abcdef0123456789abcdef", (0 until 8).map { ByteArray(32) { b -> (it * 7 + b).toByte() } })
    return HornUi(
        connected = true, path = "tcp", node = "horn-0.example.org", quality = "good", rtt = 38, tiles = lay.list.map { byDev[it.dev]!!.copy(big = it.big) },
        view = view, runes = seal.runes, runeNames = seal.idx.map { HornE2E.runeNames[it] },
        people = listOf(HornPerson("me", "Erik", "Pixel 9", me = true, host = true)) + tiles.map {
            HornPerson(it.dev, it.name, it.devName, mic = it.mic, speaking = it.speaking, moderate = true, unverified = it.dev == "d6")
        },
        amHost = true, screenFrom = if (screen) "Freya" else null, bigVideo = true, unverified = listOf("Sigrun"),
    )
}

/** Platzhalter fuer ein bewegtes Bild in der Vorschau */
@Composable
private fun PreviewVideo(seed: Int, modifier: Modifier) {
    val c = listOf(Color(0xFF3A6EA5), Color(0xFF7A4E9E), Color(0xFF2F8F6B), Color(0xFFB0643C))
    Box(modifier.background(Brush.linearGradient(listOf(c[seed % 4], c[(seed + 1) % 4]))))
}

/**
 * Bildschirmfoto-Vorschau eines Knoten-Anrufs (Kopf, Weg-Leiste, Kacheln) - panel: "" | "people" | "runes".
 * Nur fuer Geraetetests (Debug-ComponentActivity), keine Medien.
 */
@Composable
internal fun HornPreviewContent(view: String = "grid", panel: String = "", screen: Boolean = false) {
    val ui = hornPreviewUi(view, screen)
    val sc = MaterialTheme.colorScheme
    Surface(color = sc.surface) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.call_e2e), style = MaterialTheme.typography.labelMedium, color = sc.onSurfaceVariant)
            Text("12:34 · " + stringResource(R.string.room_devices_of, ui.people.size, 12), style = MaterialTheme.typography.labelMedium, color = sc.onSurfaceVariant)
            HornBar(ui, onDark = false, onRunes = {}, onPeople = {}, onView = {})
            when (panel) {
                "people" -> Surface(shape = RoundedCornerShape(20.dp), color = sc.surfaceContainerHigh, modifier = Modifier.padding(top = 12.dp).weight(1f)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.horn_people, ui.people.size), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        HornPeopleList(ui, {}, {})
                    }
                }
                "runes" -> Surface(shape = RoundedCornerShape(20.dp), color = sc.surfaceContainerHigh, modifier = Modifier.padding(top = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.horn_runes), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        HornRunesBody(ui)
                    }
                }
                else -> HornStage(
                    ui, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                    self = HornTile("self", stringResource(R.string.room_you), "", video = true, mic = true, speaking = false),
                    video = { t, m -> PreviewVideo(t.dev.drop(1).toIntOrNull() ?: 0, m) },
                    big = { m -> PreviewVideo(2, m.aspectRatio(16 / 9f)) },
                    selfVideo = { m -> PreviewVideo(3, m) },
                    onPin = {},
                )
            }
        }
    }
}
