package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatX3
import io.github.yggdrasil_sh.saga.chat.ReactionChip
import io.github.yggdrasil_sh.saga.chat.voice.VoiceLogic
import io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import kotlinx.coroutines.delay

// Saga 2.5 - Chat-Grundlagen auf Android (Konzept B1-B3): Schnellleiste (langes Druecken), Emoji-Auswahl ohne Internet
// (androidx.emoji2 EmojiPickerView, Daten in der App), Chips unter der Blase, Bottom-Sheet "Reaktionen", Leiste
// "Bearbeiten" ueber dem Eingabefeld, Sprachnachricht in der Blase (Play, 64 Balken, Dauer, Tempo). Ansichten ohne
// Sitzung (Previews/Bildschirmfotos), Anbindung an ChatSession in Conversation.kt.

/** Schnellleiste: 6 Emojis (eigenes hervorgehoben) und "+" fuer die volle Auswahl */
@Composable
fun ReactionBar(mine: String?, onPick: (String) -> Unit, onMore: () -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatX3.QUICK.forEach { e ->
            val on = e == mine
            val label = if (on) stringResource(R.string.x3_react_remove) else stringResource(R.string.x3_react_with, e)
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onPick(e) }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 24.sp) }
        }
        val more = stringResource(R.string.x3_more_emojis)
        IconButton(onClick = onMore, modifier = Modifier.semantics { contentDescription = more }) { Icon(Icons.Filled.Add, null) }
    }
}

/** Chips unter der Blase: Emoji + Zahl, eigene hervorgehoben; Tippen = gleiches Emoji setzen/zuruecknehmen, lang = wer */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReactionChips(chips: List<ReactionChip>, mineSide: Boolean, onToggle: ((String) -> Unit)?, onShow: () -> Unit) {
    if (chips.isEmpty()) return
    // Design 2: Chips als Pillen, die leicht in die Blase ragen (Rand in Hintergrundfarbe trennt sie von der Blase)
    FlowRow(
        Modifier.padding(horizontal = 10.dp).offset(y = (-6).dp).widthIn(max = 520.dp).animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, if (mineSide) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { c ->
            val sc = MaterialTheme.colorScheme
            val desc = stringResource(R.string.x3_react_chip, c.e, c.n)
            val pill = RoundedCornerShape(50)
            Surface(
                color = if (c.mine) sc.secondaryContainer else sc.surfaceContainerHighest,
                contentColor = if (c.mine) sc.onSecondaryContainer else sc.onSurface,
                shape = pill,
                modifier = Modifier.heightIn(min = 32.dp)
                    .border(2.dp, if (c.mine) sc.primary else sc.surface, pill)
                    .clip(pill)
                    .combinedClickable(onClick = { if (onToggle != null) onToggle(c.e) else onShow() }, onLongClick = onShow)
                    .semantics { contentDescription = desc },
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.e, fontSize = 16.sp)
                    if (c.n > 1) Text(" " + c.n, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Bottom-Sheet "Reaktionen": je Emoji, wer reagiert hat ("du", Mitglied, sonst ehemaliges Mitglied) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsSheet(chips: List<ReactionChip>, me: String, nameOf: (String) -> String?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.x3_reactions_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            if (chips.isEmpty()) Text(stringResource(R.string.x3_reactions_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val you = stringResource(R.string.x3_you)
            val former = stringResource(R.string.x3_former_member)
            chips.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Text(c.e, fontSize = 22.sp, modifier = Modifier.width(44.dp))
                    Text(
                        c.who.joinToString(", ") { h -> if (h == me) you else nameOf(h) ?: former },
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 4.dp),
                    )
                    Text(c.n.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, start = 8.dp))
                }
                HorizontalDivider()
            }
        }
    }
}

/** Volle Emoji-Auswahl (androidx.emoji2, ohne Internet), oben die zuletzt benutzten */
@Composable
fun EmojiPickDialog(recent: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.x3_more_emojis)) },
        text = {
            Column {
                if (recent.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                    recent.forEach { e ->
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        androidx.emoji2.emojipicker.EmojiPickerView(ctx).apply {
                            emojiGridColumns = 8
                            setOnEmojiPickedListener { item -> if (ChatX3.emojiOk(item.emoji)) onPick(item.emoji) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Leiste ueber dem Eingabefeld: "Bearbeiten" mit dem bisherigen Text, Abbrechen */
@Composable
fun EditBar(original: String, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
            Text(stringResource(R.string.x3_edit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(original, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
    }
}

/** 64 Balken; gehoerter Teil eingefaerbt; Tippen springt dorthin */
@Composable
fun WaveBars(bars: IntArray, played: Int, color: Color, rest: Color, modifier: Modifier = Modifier, onSeek: ((Double) -> Unit)? = null) {
    Row(
        modifier.height(28.dp)
            .then(if (onSeek != null) Modifier.pointerInput(Unit) { detectTapGestures { o -> onSeek((o.x / size.width.coerceAtLeast(1)).toDouble()) } } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until VoiceLogic.WAVE_N) {
            val h = (bars.getOrElse(i) { 0 } / 255f).coerceIn(0.1f, 1f)
            Box(Modifier.weight(1f).fillMaxHeight(h).clip(RoundedCornerShape(1.dp)).background(if (i < played) color else rest))
        }
    }
}

/** Sprachnachricht in der Blase (ohne Sitzung): Play/Pause, Wellenform, Zeit, Tempo */
@Composable
fun VoiceBubbleView(
    bars: IntArray, dur: Double, pos: Double, playing: Boolean, loading: Boolean, failed: Boolean, speed: Float, fg: Color,
    onPlay: () -> Unit, onSeek: (Double) -> Unit, onSpeed: () -> Unit,
) {
    Row(Modifier.widthIn(min = 220.dp, max = 300.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val label = stringResource(if (playing) R.string.voice_pause else R.string.voice_play)
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(fg.copy(alpha = 0.12f)).clickable(onClick = onPlay).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fg)
                failed -> Text("⚠", color = fg)
                playing -> Text("❚❚", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                else -> Icon(Icons.Filled.PlayArrow, null, tint = fg)
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            WaveBars(bars, VoiceLogic.played(pos, dur), fg, fg.copy(alpha = 0.35f), Modifier.fillMaxWidth(), onSeek)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(VoiceLogic.fmt(if (playing || pos > 0) pos else dur), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
                Spacer(Modifier.weight(1f))
                val sp = stringResource(R.string.voice_speed)
                Text(
                    VoiceLogic.speedLabel(speed, decimalSep()), style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSpeed).padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { contentDescription = sp },
                )
            }
        }
    }
}

@Composable
private fun decimalSep(): Char = java.text.DecimalFormatSymbols.getInstance(
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0],
).decimalSeparator

/** Sprachnachricht einer Sitzung (Quelle wie Videos, gemeinsamer Player, danach die naechste) */
@Composable
fun VoiceAttachment(f: ChatFile, s: ChatSession, fg: Color, sources: io.github.yggdrasil_sh.saga.chat.video.VideoSources?) {
    val context = LocalContext.current
    val dur = (f.dur ?: 0.0).coerceAtLeast(0.0)
    val bars = remember(f.wf) { VoiceLogic.bars(f.wf) }
    val active = VoicePlayer.current == f.id
    var pos by remember(f.id) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(active, VoicePlayer.playing) {
        while (active) {
            pos = VoicePlayer.position(f.id)
            if (!VoicePlayer.playing) break
            delay(100)
        }
        if (!active) pos = 0.0
    }
    fun item(x: ChatFile): VoicePlayer.Item =
        if (x.isV2 && sources != null) VoicePlayer.Item(x.id, x, factory = sources.factory(x)) else VoicePlayer.Item(x.id, x, bytes = { s.download(x) })
    VoiceBubbleView(
        bars, dur, if (active) pos else 0.0, active && VoicePlayer.playing, active && !VoicePlayer.playing && VoicePlayer.failed == null && VoicePlayer.loading,
        VoicePlayer.failed == f.id, VoicePlayer.speed, fg,
        onPlay = {
            if (active && VoicePlayer.playing) VoicePlayer.pause() else {
                s.watchVoiceCalls()
                VoicePlayer.start(context, item(f)) { id -> s.nextVoice(id)?.let { item(it) } }
            }
        },
        onSeek = { x ->
            if (!active) VoicePlayer.start(context, item(f)) { id -> s.nextVoice(id)?.let { item(it) } }
            VoicePlayer.seek(f.id, VoiceLogic.seek(x, dur))
        },
        onSpeed = { VoicePlayer.cycleSpeed(context) },
    )
}

// ---------------------------------------------------------------- Previews (hell/dunkel; Bildschirmfotos)

private val PREVIEW_BARS = IntArray(64) { ((kotlin.math.abs(kotlin.math.sin(it * 0.37)) * (it % 7) / 7.0 * 255).toInt() + 30).coerceAtMost(255) }

@Composable
internal fun X3PreviewContent() {
    val sc = MaterialTheme.colorScheme
    Surface(color = sc.background) {
        Column(Modifier.padding(12.dp).width(360.dp)) {
            Surface(color = sc.surfaceContainer, shape = RoundedCornerShape(24.dp), shadowElevation = 3.dp) {
                ReactionBar(mine = "❤️", onPick = {}, onMore = {})
            }
            Spacer(Modifier.height(10.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Surface(color = ChatUi.bubble(sc, false), shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("Treffen wir uns um 9?", style = MaterialTheme.typography.bodyLarge)
                        Row(Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.x3_edited) + " · 09:12", style = MaterialTheme.typography.labelSmall, color = sc.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
                ReactionChips(
                    listOf(ReactionChip("👍", 2, true, listOf("~a", "~b")), ReactionChip("❤️", 1, false, listOf("~c"))),
                    mineSide = false, onToggle = {}, onShow = {},
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(color = ChatUi.bubble(sc, true), contentColor = sc.onPrimaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        VoiceBubbleView(PREVIEW_BARS, 12.4, 4.1, true, false, false, 1.5f, sc.onPrimaryContainer, {}, {}, {})
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(color = ChatUi.bubble(sc, false), shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    VoiceBubbleView(PREVIEW_BARS.reversedArray(), 63.0, 0.0, false, false, false, 1f, sc.onSurface, {}, {}, {})
                }
            }
            Spacer(Modifier.height(10.dp))
            EditBar("Treffen wir uns um 8?", onCancel = {})
            Spacer(Modifier.height(10.dp))
            VoiceRecordingBar(secs = 13.0, levels = PREVIEW_BARS.map { it / 255f }, locked = false, warn = false, onStop = {}, onCancel = {})
            Spacer(Modifier.height(6.dp))
            VoicePreviewBar(PREVIEW_BARS, 13.0, 0.0, false, 1f, onPlay = {}, onDiscard = {}, onSend = {}, onSpeed = {})
        }
    }
}

@Preview(name = "Chat 2.5 hell", widthDp = 384)
@Composable
private fun X3PreviewLight() = SagaTheme(dark = false) { X3PreviewContent() }

@Preview(name = "Chat 2.5 dunkel", widthDp = 384)
@Composable
private fun X3PreviewDark() = SagaTheme(dark = true) { X3PreviewContent() }

/** Leiste waehrend der Aufnahme: rote Marke, Zeit, Live-Wellenform, Hinweise (sperren/verwerfen) bzw. Stopp (gesperrt) */
@Composable
fun VoiceRecordingBar(secs: Double, levels: List<Float>, locked: Boolean, warn: Boolean, onStop: () -> Unit, onCancel: () -> Unit) {
    val sc = MaterialTheme.colorScheme
    Surface(color = sc.surfaceContainerHigh, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(sc.error))
                Text(
                    VoiceLogic.fmt(secs), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp, end = 10.dp),
                    color = if (warn) sc.error else sc.onSurface,
                )
                val last = levels.takeLast(VoiceLogic.WAVE_N)
                val mx = (last.maxOrNull() ?: 0f).coerceAtLeast(0.02f)
                val bars = IntArray(VoiceLogic.WAVE_N) { i ->
                    val k = i - (VoiceLogic.WAVE_N - last.size)
                    if (k < 0) 0 else (kotlin.math.sqrt(last[k] / mx) * 255).toInt()
                }
                WaveBars(bars, VoiceLogic.WAVE_N, sc.primary, sc.primary, Modifier.weight(1f))
                if (locked) {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Delete, stringResource(R.string.voice_discard), tint = sc.error) }
                    IconButton(onClick = onStop) { Text("■", color = sc.primary, fontSize = 20.sp) }
                }
            }
            if (!locked) Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("‹ " + stringResource(R.string.voice_slide_cancel), style = MaterialTheme.typography.labelSmall, color = sc.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = sc.onSurfaceVariant)
                    Text(" " + stringResource(R.string.voice_slide_lock), style = MaterialTheme.typography.labelSmall, color = sc.onSurfaceVariant)
                }
            }
            if (warn) Text(stringResource(R.string.voice_warn_minute), style = MaterialTheme.typography.labelSmall, color = sc.error)
        }
    }
}

/** Vorschau vor dem Senden: Abspielen, Verwerfen, Senden */
@Composable
fun VoicePreviewBar(
    bars: IntArray, dur: Double, pos: Double, playing: Boolean, speed: Float, onPlay: () -> Unit, onDiscard: () -> Unit, onSend: () -> Unit,
    onSpeed: () -> Unit,
) {
    val sc = MaterialTheme.colorScheme
    Surface(color = sc.surfaceContainerHigh, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDiscard) { Icon(Icons.Filled.Delete, stringResource(R.string.voice_discard), tint = sc.error) }
            Box(Modifier.weight(1f)) {
                VoiceBubbleView(bars, dur, pos, playing, false, false, speed, sc.onSurface, onPlay, {}, onSpeed)
            }
            IconButton(onClick = onSend) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send), tint = sc.primary)
            }
        }
    }
}

/** Meldung, wenn das Mikrofon dauerhaft abgelehnt wurde ("nie wieder"): Link zu den App-Einstellungen */
@Composable
fun MicDeniedDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_mic_title)) },
        text = { Text(stringResource(R.string.voice_mic_denied)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                try {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Exception) {
                }
            }) { Text(stringResource(R.string.voice_open_settings)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
