package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatNoteRef
import io.github.yggdrasil_sh.saga.chat.ChatSelect
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.X3Hint
import io.github.yggdrasil_sh.saga.chat.quietly
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// 25.10.: Chat-Mehrfachauswahl und Hinweis zu Reaktionen (Design 2: ruhige Flaechen, Saga-Gold als Akzent)

/** Kontextuelle Kopfzeile im Auswahlmodus: "n ausgewählt", ×, Kopieren · Weiterleiten · In Seite übernehmen · Löschen */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, onClose: () -> Unit, onCopy: () -> Unit, onForward: () -> Unit, onToPage: () -> Unit, onDelete: () -> Unit) {
    val on = count > 0
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.cs_close_selection)) } },
        title = {
            Text(
                pluralStringResource(R.plurals.cs_selected, count, count), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        actions = {
            SelAction(painterResource(R.drawable.ic_content_copy), stringResource(R.string.cs_copy), on, onCopy)
            SelAction(painterResource(R.drawable.ic_forward), stringResource(R.string.cs_forward), on, onForward)
            SelAction(painterResource(R.drawable.ic_note_add), stringResource(R.string.cs_to_page), on, onToPage)
            SelAction(
                androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Delete), stringResource(R.string.cs_delete), on, onDelete,
                tint = if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** Aktion der Auswahl-Kopfzeile: Symbol mit Beschriftung (contentDescription) und Tooltip bei langem Druck */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelAction(icon: androidx.compose.ui.graphics.painter.Painter, label: String, enabled: Boolean, onClick: () -> Unit, tint: Color? = null) {
    TooltipBox(
        TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            if (tint != null) Icon(icon, label, tint = tint) else Icon(icon, label)
        }
    }
}

/** Auswahlkreis links neben einer Nachricht (gefuellt in Primaerfarbe mit Haken bzw. Ring) */
@Composable
fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.size(22.dp).clip(CircleShape)
            .then(if (selected) Modifier else Modifier.border(2.dp, c.outline, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Surface(color = c.primary, contentColor = c.onPrimary, shape = CircleShape, modifier = Modifier.size(22.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
        }
    }
}

/** Hintergrund einer ausgewaehlten Zeile */
@Composable
fun selectedRowColor(selected: Boolean): Color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent

/** Zielchat fuer das Weiterleiten waehlen (mit Suche) */
@Composable
fun ForwardTargetDialog(repo: SagaRepository, from: Long, onDismiss: () -> Unit, onPick: (ChatSummary, String) -> Unit) {
    val svc = repo.chat
    val chats by svc.chats.collectAsState()
    val loaded by svc.chatsLoaded.collectAsState()
    LaunchedEffect(Unit) { if (!loaded) quietly { svc.loadChats() } }
    var q by remember { mutableStateOf("") }
    val shown = remember(chats, q) { ChatSelect.forwardTargets(chats, from, q) { svc.displayName(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cs_forward_title)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text(stringResource(R.string.cs_forward_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { c ->
                        val name = svc.displayName(c)
                        ListItem(
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                ChatAvatar(
                                    name, 36.dp, symbol = if (c.kind == "page") "📄" else null,
                                    icon = if (c.kind == "group" || c.kind == "free") R.drawable.ic_group else null, avatar = if (c.kind == "dm") c.peer?.avatar else null,
                                )
                            },
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(c, name) },
                        )
                    }
                    if (shown.isEmpty() && loaded) item {
                        Text(stringResource(R.string.cs_forward_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "In Seite übernehmen": neue Seite oder an bestehende anhaengen; vorher Warnung (nicht Ende-zu-Ende-verschluesselt) */
@Composable
fun ToPageDialog(onDismiss: () -> Unit, onNew: () -> Unit, onAppend: () -> Unit) {
    var append by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cs_to_page)) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.cs_page_warn), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
                listOf(false to R.string.cs_page_new, true to R.string.cs_page_append).forEach { (a, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).selectable(append == a, role = Role.RadioButton) { append = a }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = append == a, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (append) onAppend() else onNew() }) { Text(stringResource(R.string.cs_page_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Bestehende Seite waehlen (eigene und mit mir geteilte, Suche) */
@Composable
fun PageTargetDialog(repo: SagaRepository, onDismiss: () -> Unit, onPick: (ChatNoteRef) -> Unit) {
    var q by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf<List<ChatNoteRef>>(emptyList()) }
    LaunchedEffect(q) {
        delay(200)
        try {
            pages = repo.chat.pages(q)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cs_page_pick)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text(stringResource(R.string.cs_page_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(pages, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Text(p.icon.takeIf { it.isNotEmpty() && !it.startsWith("/") } ?: "📄") },
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(p) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Löschen: "Für mich" (alle eigenen Geraete) bzw. "Für alle" nur, wenn ausschliesslich eigene ausgewaehlt sind.
 * Erklaerung als Text, die Aktionen als rote Textknoepfe neben "Abbrechen" (Material-Dialog).
 */
@Composable
fun DeleteSelectionDialog(count: Int, forAll: Boolean, onDismiss: () -> Unit, onForMe: () -> Unit, onForAll: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, null) },
        title = { Text(pluralStringResource(R.plurals.cs_delete_title, count, count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cs_delete_me_hint), style = MaterialTheme.typography.bodyMedium)
                if (forAll) Text(stringResource(R.string.cs_delete_all_hint), style = MaterialTheme.typography.bodyMedium)
                else Text(stringResource(R.string.cs_delete_only_own), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        // "Für mich löschen" steht als roter Textknopf direkt neben "Abbrechen"; "Für alle löschen" (falls moeglich) darueber
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (forAll) TextButton(onClick = onForAll, modifier = Modifier.testTag("cs-delete-all")) { Text(stringResource(R.string.cs_delete_all), color = red) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = onForMe, modifier = Modifier.testTag("cs-delete-me")) { Text(stringResource(R.string.cs_delete_me), color = red) }
                }
            }
        },
    )
}

/** Rueckfrage vor "Für alle löschen" */
@Composable
fun DeleteAllConfirm(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cs_delete_all_confirm)) },
        text = { Text(stringResource(R.string.cs_delete_all_hint)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.cs_delete_all), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Text des Hinweises zu x3_why (Stichtag als Datum der App-Sprache) */
@Composable
fun x3HintText(h: X3Hint): String = when (h) {
    X3Hint.Setting -> stringResource(R.string.cs_x3_setting)
    X3Hint.Readonly -> stringResource(R.string.cs_x3_readonly)
    X3Hint.Pending -> stringResource(R.string.cs_x3_pending)
    X3Hint.Other -> stringResource(R.string.cs_x3_other)
    is X3Hint.Server -> if (h.hosts.isEmpty()) stringResource(R.string.cs_x3_server_unknown) else stringResource(R.string.cs_x3_server, h.hosts.joinToString(", "))
    is X3Hint.Devices -> {
        val parts = mutableListOf(stringResource(R.string.cs_x3_devices))
        val mine = h.mine.joinToString(", ")
        when {
            h.mine.isNotEmpty() && h.others > 0 -> parts += pluralStringResource(R.plurals.cs_x3_wait_both, h.others, mine, h.others)
            h.mine.isNotEmpty() -> parts += stringResource(R.string.cs_x3_wait_mine, mine)
            h.others > 0 -> parts += pluralStringResource(R.plurals.cs_x3_wait_others, h.others, h.others)
        }
        h.until?.let { u ->
            val loc = io.github.yggdrasil_sh.saga.ui.common.appLocale()
            val d = remember(u, loc) {
                Instant.ofEpochSecond(u).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(loc))
            }
            parts += stringResource(R.string.cs_x3_until, d)
        }
        parts.joinToString(" ")
    }
}

/** ⓘ im Chat-Kopf: kurzer, schliessbarer Hinweis (Popover), warum es noch keine Reaktionen gibt */
@Composable
fun X3InfoButton(h: X3Hint, startOpen: Boolean = false, onDevices: () -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.cs_x3_button)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.cs_x3_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(x3HintText(h), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    if (h is X3Hint.Devices && h.mine.isNotEmpty()) TextButton(onClick = { open = false; onDevices() }) { Text(stringResource(R.string.cs_x3_devices_link)) }
                    TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }
}
