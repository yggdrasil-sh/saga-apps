package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.Presence
import io.github.yggdrasil_sh.saga.chat.PresenceHub
import io.github.yggdrasil_sh.saga.chat.PresenceLogic
import io.github.yggdrasil_sh.saga.chat.ReadMark
import io.github.yggdrasil_sh.saga.chat.Tick
import io.github.yggdrasil_sh.saga.chat.TickKind
import io.github.yggdrasil_sh.saga.ui.common.relativeTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Saga 2.4: Gelesen-Haken, "Gelesen von", Online-Status (wie WhatsApp)

/** Gruen des Online-Punkts (wie ueblich), mit Rand in Hintergrundfarbe */
private val OnlineGreen = Color(0xFF2EB85C)

/**
 * Online-Abo dieser Ansicht, solange sie sichtbar ist (Lebenszyklus STARTED): im Hintergrund bzw. beim Verlassen abmelden
 * (Akku, kein unnoetiges Abo).
 */
@Composable
fun PresenceWatch(hub: PresenceHub, owner: String, users: List<String>) {
    LifecycleStartEffect(hub, owner, users) {
        hub.show(owner, users)
        onStopOrDispose { hub.hide(owner) }
    }
}

/** "online" bzw. "zuletzt online vor …" (null = nichts anzeigen) */
@Composable
fun presenceText(p: Presence?, round: Int): String? {
    if (p == null) return null
    if (p.online) return stringResource(R.string.chat_online)
    val last = p.last ?: return null
    return stringResource(R.string.chat_last_seen, relativeTime(PresenceLogic.shownLast(System.currentTimeMillis() / 1000.0, last, round)))
}

/** Gruener Punkt unten rechts am Profilbild (Chatliste) */
@Composable
fun OnlineDot(modifier: Modifier = Modifier) {
    val d = stringResource(R.string.chat_online)
    Box(
        modifier.size(12.dp).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).padding(1.dp)
            .background(OnlineGreen, CircleShape).semantics { contentDescription = d },
    )
}

/** Profilbild mit Online-Punkt */
@Composable
fun WithOnlineDot(online: Boolean, content: @Composable () -> Unit) {
    Box {
        content()
        if (online) OnlineDot(Modifier.align(Alignment.BottomEnd))
    }
}

/** Haken an der eigenen Blase: ⏳ → ✓ → ✓✓ (gelesen: farbig; Gruppe teilweise: mit Zaehler) */
@Composable
fun TickMark(t: Tick, fg: Color, group: Boolean, readAt: Long?) {
    if (t.kind == TickKind.NONE) return
    val read = t.kind == TickKind.READ
    val full = read && (!group || t.all)
    val label = when (t.kind) {
        TickKind.PENDING -> stringResource(R.string.fed_msg_pending)
        TickKind.SENT -> stringResource(R.string.chat_tick_sent)
        else -> when {
            !group -> stringResource(R.string.chat_tick_read, readAt?.let { fmtTime(it) }.orEmpty()).trim()
            t.all -> stringResource(R.string.chat_tick_read_all)
            else -> androidx.compose.ui.res.pluralStringResource(R.plurals.chat_tick_read_n, t.readers, t.readers)
        }
    }
    val sym = when (t.kind) {
        TickKind.PENDING -> "⏳"
        TickKind.SENT -> "✓"
        else -> if (group && !t.all) "✓✓ ${t.readers}" else "✓✓"
    }
    Text(
        sym, style = MaterialTheme.typography.labelSmall,
        color = if (full) MaterialTheme.colorScheme.primary else fg.copy(alpha = 0.7f),
        fontWeight = if (full) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.padding(start = 4.dp).semantics { contentDescription = label },
    )
}

private fun fmtTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))

/** "Gelesen von" (Gruppe): Name + Zeit; Hinweis, dass Abgeschaltete fehlen */
@Composable
fun ReadByDialog(readers: List<Pair<ChatPerson, ReadMark>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_read_by)) },
        text = {
            Column {
                if (readers.isEmpty()) {
                    Text(stringResource(R.string.chat_read_by_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(readers.sortedByDescending { it.second.ts }, key = { it.first.username }) { (p, r) ->
                            ListItem(
                                headlineContent = { Text(p.name.ifEmpty { p.label ?: p.username }) },
                                supportingContent = if (r.ts > 0) ({ Text(fmtTime(r.ts)) }) else null,
                                leadingContent = { io.github.yggdrasil_sh.saga.ui.notifications.Avatar(p.name.ifEmpty { p.username }, size = 32, avatar = p.avatar) },
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.chat_read_by_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
