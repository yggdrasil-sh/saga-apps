package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatBlock
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.ChatSys
import io.github.yggdrasil_sh.saga.chat.FedKeyState
import io.github.yggdrasil_sh.saga.chat.FedPerson
import io.github.yggdrasil_sh.saga.chat.TofuV2
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Saga 2.3 - Chats ueber Servergrenzen: Kopfzeile, TOFU-Hinweise, Nachrichtenanfrage/schreibgeschuetzt, Systemzeilen,
// Personen anderer Server in der Suche, Sperrliste. Lokale Chats (v1) zeigen nichts davon.

/** Zweite Zeile der Kopfzeile: "Ende-zu-Ende-verschlüsselt", bei Personen anderer Server mit Herkunft und Stand */
@Composable
fun fedHeadLine(s: ChatSession): String {
    val e2e = stringResource(R.string.chat_e2e)
    val sum = s.summary
    if (sum == null || !s.isFed) return e2e
    val p = sum.peer
    // Herkunft und Stand zuerst (die Zeile ist einzeilig und wird sonst abgeschnitten), "Ende-zu-Ende" zuletzt
    val parts = mutableListOf<String>()
    // Gruppe/Seiten-Chat (2.3, E5): Heim nennen, wenn es ein anderer Server ist (sonst die Herkunft der Mitglieder im Mitglieder-Dialog)
    val host = if (sum.kind == "dm") p?.host ?: sum.home else sum.home?.takeIf { !sum.homeSelf }
    if (!host.isNullOrEmpty()) parts.add(stringResource(R.string.fed_on_host, host))
    val st = p?.handle?.let { s.fk[it] }
    when {
        st?.verified == true -> parts.add("✓ " + stringResource(R.string.chat_verified))
        st?.trust == TofuV2.BLOCK || st?.trust == TofuV2.REJECT -> parts.add("⛔ " + stringResource(R.string.fed_blocked_chip))
    }
    if (sum.request) parts.add(stringResource(R.string.fed_request_chip))
    if (sum.readonly) parts.add("🔒 " + stringResource(R.string.fed_readonly_chip))
    parts.add(e2e)
    return parts.joinToString(" · ")
}

/** Hinweis je Person mit Warnung (Schluessel gewechselt), Blockade (war ueberprueft) bzw. ungueltigen Schluesseln */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FedTrustBanner(p: ChatPerson, st: FedKeyState, onSafety: () -> Unit, onAcknowledge: () -> Unit) {
    val name = p.name.ifEmpty { p.label ?: p.username }
    val text = when (st.trust) {
        TofuV2.WARN -> stringResource(R.string.fed_warn_changed, name)
        TofuV2.BLOCK -> stringResource(R.string.fed_warn_blocked, name)
        else -> stringResource(R.string.fed_warn_invalid, name)
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, null, Modifier.size(18.dp).padding(top = 2.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (st.trust != TofuV2.REJECT) TextButton(onClick = onSafety) { Text(stringResource(R.string.chat_safety_number), color = MaterialTheme.colorScheme.onErrorContainer) }
                if (st.trust == TofuV2.WARN) TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.fed_understood), color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        }
    }
}

/** Statt des Eingabefelds: Nachrichtenanfrage (Annehmen/Ablehnen/Sperren) bzw. "schreibgeschützt" mit Grund */
@Composable
fun FedStateBar(sum: ChatSummary, request: Boolean, onAnswer: (String, String) -> Unit) {
    val p = sum.peer
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (!request) {
                Text(
                    "🔒 " + (sum.readonlyReason?.ifBlank { null } ?: stringResource(R.string.fed_readonly)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            // Direkt-Chat: Gegenueber; Gruppe/Seiten-Chat eines anderen Servers (2.3, E5): wer eingeladen hat ("block" sperrt
            // diese Person bzw. ihren Server)
            val dm = sum.kind == "dm"
            val inv = sum.invitedBy
            val name = (if (dm) p?.name else inv?.name)?.ifEmpty { null } ?: "?"
            val host = (if (dm) p?.host else inv?.host) ?: sum.home ?: "?"
            Text(stringResource(if (dm) R.string.fed_request_text else R.string.fed_request_group, name, host), style = MaterialTheme.typography.bodyMedium)
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { menu = true }) { Text(stringResource(R.string.fed_block_menu), color = MaterialTheme.colorScheme.error) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.fed_block_person, name), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onAnswer("block", "person") },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.fed_block_instance, host), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onAnswer("block", "instance") },
                        )
                    }
                }
                OutlinedButton(onClick = { onAnswer("decline", "person") }) { Text(stringResource(R.string.fed_decline)) }
                Button(onClick = { onAnswer("accept", "person") }) { Text(stringResource(R.string.fed_accept)) }
            }
        }
    }
}

/** Systemzeile (mittig), z. B. "Anna hat die KI in diesem Chat eingeschaltet.", "Bert (b.example) hat Dora hinzugefügt." */
@Composable
fun SysRow(m: ChatMessage, sys: ChatSys, myHandle: String?) {
    val time = remember(m.ts) {
        Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    // KI ein/aus, "ab jetzt ueber Servergrenzen", Mitglieder (mit Herkunft) - FedSysText (wie das Web)
    val text = io.github.yggdrasil_sh.saga.chat.FedSysText.text(sys, myHandle).resolve()
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            Text(
                "$text · $time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Treffer einer Person auf einem anderen, verbundenen Saga-Server ("Name · host", Profilbild, Hinweis auf Anfrage) */
@Composable
fun FedPersonItem(p: FedPerson, sub: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(p.shown) },
        supportingContent = { Text(sub) },
        leadingContent = { Avatar(p.name, size = 32, avatar = p.avatar) },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Sperrliste (Personen bzw. ganze Server, von denen keine Anfragen mehr kommen) - entsperren */
@Composable
fun FedBlocksDialog(repo: SagaRepository, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<ChatBlock>?>(null) }
    var err by remember { mutableStateOf<UiText?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        try {
            list = repo.chat.fed.blocks()
            err = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = repo.friendly(e)
            if (list == null) list = emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fed_blocks_title)) },
        text = {
            Column {
                err?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val l = list
                when {
                    l == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    l.isEmpty() -> Text(stringResource(R.string.fed_blocks_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(l, key = { it.kind + ":" + it.target }) { b ->
                            ListItem(
                                headlineContent = { Text(b.label?.ifBlank { null } ?: b.name?.ifBlank { null } ?: b.host ?: b.target) },
                                supportingContent = {
                                    Text(if (b.kind == "instance") stringResource(R.string.fed_blocks_instance, b.host ?: b.target) else stringResource(R.string.fed_blocks_person))
                                },
                                trailingContent = {
                                    TextButton(onClick = { repo.launchAction { repo.chat.fed.unblock(b.target); tick++ } }) { Text(stringResource(R.string.fed_unblock)) }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
