package io.github.yggdrasil_sh.saga.ui.chat

import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.yggdrasil_sh.saga.ui.common.imeDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatBlock
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatStd
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saga 2.5 Chat-Standard (Paket B, 6a): Gruppenbild, Beschreibung, Gruppenrechte, Leiste angehefteter Nachrichten,
 * Grabstein, eingeklappte Nachrichten gesperrter Personen, Sperren/Melden, Sperrliste. Texte wie im Web.
 */

/** Gruppenbild (entschluesselt) bzw. das bisherige Symbol */
@Composable
fun GroupAvatar(repo: SagaRepository, c: ChatSummary, name: String, size: Dp) {
    val extras by repo.chat.groupExtras.collectAsState()
    val f = extras[c.id]?.avatar
    var img by remember(c.id, f?.id) { mutableStateOf<ImageBitmap?>(null) }
    if (f != null) LaunchedEffect(c.id, f.id) {
        img = try {
            repo.chat.groupAvatar(c)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val bmp = img
    if (bmp != null && f != null) {
        Image(bmp, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        ChatAvatar(
            name, size, symbol = if (c.kind == "page") "📄" else null,
            icon = if (c.kind == "group" || c.kind == "free") SagaIcons.users else null, avatar = if (c.kind == "dm") c.peer?.avatar else null,
        )
    }
}

/** Kopf der Gruppeninfo: Bild mit "Gruppenbild ändern" (can_info2) */
@Composable
fun GroupHeadAvatar(repo: SagaRepository, s: ChatSession, c: ChatSummary) {
    val r = ChatStd.rights(c, repo.chat.std)
    val ctx = LocalContext.current
    val extras by repo.chat.groupExtras.collectAsState()
    val has = extras[c.id]?.avatar != null
    var menu by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        if (u != null) repo.launchAction {
            val data = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)?.use { io.github.yggdrasil_sh.saga.chat.ChatAttach.readLimited(it, 20L shl 20) } }
                ?: throw io.github.yggdrasil_sh.saga.util.UiTextException(UiText.Res(R.string.image_unsupported))
            s.setAvatar(data)
            repo.notify(UiText.Res(R.string.std_avatar_saved))
        }
    }
    if (!r.info2) {
        GroupAvatar(repo, c, s.name, 56.dp)
        return
    }
    val label = stringResource(R.string.std_avatar_change)
    Box {
        Box(Modifier.clip(CircleShape).clickable(onClickLabel = label) { if (has) menu = true else pick.launch("image/*") }) {
            GroupAvatar(repo, c, s.name, 56.dp)
            SagaIcon(
                SagaIcons.camera, null,
                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(3.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.std_avatar_pick)) }, onClick = { menu = false; pick.launch("image/*") })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.std_avatar_remove), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    repo.launchAction { s.setAvatar(null); repo.notify(UiText.Res(R.string.std_avatar_removed)) }
                },
            )
        }
    }
}

/** Beschreibung + Gruppenrechte (nur freie Gruppen mit groupinfo2) */
@Composable
fun GroupInfoStdSection(repo: SagaRepository, s: ChatSession, c: ChatSummary) {
    val f = repo.chat.std
    if (c.kind != "free" || !f.groupInfo2) return
    val r = ChatStd.rights(c, f)
    val extras by repo.chat.groupExtras.collectAsState()
    val desc = extras[c.id]?.desc?.ifBlank { null }
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (editing) {
            var t by remember { mutableStateOf(desc.orEmpty()) }
            OutlinedTextField(
                value = t, onValueChange = { t = it.take(ChatStd.DESC_MAX) }, minLines = 2, maxLines = 6,
                label = { Text(stringResource(R.string.std_desc_label)) }, placeholder = { Text(stringResource(R.string.std_desc_placeholder)) },
                supportingText = { Text("${t.length} / ${ChatStd.DESC_MAX}") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SagaTextButton(onClick = { repo.launchAction { s.setDesc(t.trim()); editing = false } }) { Text(stringResource(R.string.action_save)) }
                SagaTextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        } else {
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodyMedium)
            else if (r.info2) Text(stringResource(R.string.std_desc_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.info2) SagaTextButton(onClick = { editing = true }) {
                Text(stringResource(if (desc != null) R.string.std_desc_edit else R.string.std_desc_add))
            }
        }
        if (r.showPerms) {
            Text(stringResource(R.string.std_perms_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            PermRow(stringResource(R.string.std_perm_send), c.permSend == "admins", r.perms) { on -> repo.launchAction { s.setPerm("perm_send", on) } }
            PermRow(stringResource(R.string.std_perm_info), c.permInfo == "admins", r.perms) { on -> repo.launchAction { s.setPerm("perm_info", on) } }
            if (!r.perms) Text(stringResource(R.string.std_perms_admins_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (c.canEditInfo == false && c.permInfo == "admins") {
            Text(stringResource(R.string.std_info_admins_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermRow(label: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Rollenwahl beim Hinzufuegen zum Seiten-Chat (Hinweis "Die Seite wird mit dieser Person geteilt.") */
@Composable
fun PageRolePicker(role: String, onRole: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(stringResource(R.string.std_page_share_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.std_page_role), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChatStd.PAGE_ROLES.forEach { r ->
                FilterChip(selected = role == r, onClick = { onRole(r) }, label = {
                    Text(stringResource(when (r) { "comment" -> R.string.std_role_comment; "edit" -> R.string.std_role_edit; else -> R.string.std_role_view }))
                })
            }
        }
    }
}

/** Leiste angehefteter Nachrichten oben im Chat; Tipp springt hin (und zur naechsten), ✕ loest (can_pin) */
@Composable
fun PinBar(s: ChatSession, canPin: Boolean, onJump: (String) -> Unit, onUnpin: (String) -> Unit, nameOf: (ChatMessage) -> String, textOf: (ChatMessage) -> String) {
    val pins = s.summary?.pins.orEmpty()
    if (pins.isEmpty()) return
    var i by remember(pins) { mutableStateOf(0) }
    val mid = ChatStd.pinAt(pins, i) ?: return
    val m = s.messages.firstOrNull { it.id == mid }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).clickable {
                    onJump(mid)
                    if (pins.size > 1) i = (i + 1) % pins.size
                }.padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SagaIcon(SagaIcons.pin, null, Modifier.padding(end = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        if (pins.size > 1) stringResource(R.string.std_pinned_n, i + 1, pins.size) else stringResource(R.string.std_pinned),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    if (m != null) (nameOf(m).let { if (it.isNotEmpty()) "$it: " else "" } + textOf(m)) else stringResource(R.string.std_pin_older),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (canPin) IconButton(onClick = { onUnpin(mid) }) { SagaIcon(SagaIcons.x, stringResource(R.string.std_pin_remove)) }
        }
    }
}

/** Zeile statt einer Nachricht: Grabstein ("Von einem Admin gelöscht") bzw. eingeklappte Nachricht einer gesperrten Person */
@Composable
fun StdNoteRow(text: String, action: String? = null, @androidx.annotation.DrawableRes icon: Int? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(SagaRadius.md)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) SagaIcon(icon, null, Modifier.padding(end = 6.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                if (action != null) SagaTextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

/** Eingabefeld ersetzt: "Nur Admins dürfen schreiben" bzw. gesperrte Person */
@Composable
fun SendBlockedBar(t: UiText) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SagaIcon(
                if (t == UiText.Res(R.string.std_send_blocked_person)) SagaIcons.ban else SagaIcons.lock, null,
                Modifier.padding(end = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(t.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "{name} sperren?" mit Erklaerung */
@Composable
fun BlockConfirm(repo: SagaRepository, name: String, target: String, onBlock: suspend (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.std_block_title, name)) },
        text = { Text(stringResource(R.string.std_block_text, name)) },
        confirmButton = {
            SagaTextButton(onClick = {
                onDismiss()
                repo.launchAction { onBlock(target); repo.notify(UiText.Res(R.string.std_blocked_done, name)) }
            }) { Text(stringResource(R.string.std_block), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Melden …": Grund + optional der mitgeschickte Klartext (vorbelegt, aenderbar) */
@Composable
fun ReportDialog(repo: SagaRepository, s: ChatSession, msgs: List<ChatMessage>, prefill: String, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("spam") }
    var include by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf(prefill.take(ChatStd.REPORT_TEXT_MAX)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = io.github.yggdrasil_sh.saga.ui.common.ImeDialog.properties, // CH-05/A11Y-26: Tastatur verdeckt nichts
        modifier = Modifier.imeDialog(),
        title = { Text(stringResource(R.string.std_report)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.std_report_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.std_report_reason), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                ChatStd.REPORT_REASONS.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { reason = r }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(stringResource(when (r) {
                            "spam" -> R.string.std_reason_spam
                            "abuse" -> R.string.std_reason_abuse
                            "illegal" -> R.string.std_reason_illegal
                            else -> R.string.std_reason_other
                        }))
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { include = !include }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = include, onCheckedChange = { include = it })
                    Text(stringResource(R.string.std_report_include), style = MaterialTheme.typography.bodyMedium)
                }
                if (include) {
                    OutlinedTextField(value = text, onValueChange = { text = it.take(ChatStd.REPORT_TEXT_MAX) }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.std_report_edit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            SagaTextButton(onClick = {
                onDismiss()
                repo.launchAction { s.report(msgs.map { it.id }, reason, if (include) text else null); repo.notify(UiText.Res(R.string.std_report_done)) }
            }) { Text(stringResource(R.string.std_report), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Einstellungen → Chat: gesperrte Personen dieses Servers (GET /api/chat/blocks local, Live chat.blocks) */
@Composable
fun LocalBlocksDialog(repo: SagaRepository, onDismiss: () -> Unit) {
    val tick by repo.chat.blocksTick.collectAsState()
    var list by remember { mutableStateOf<List<ChatBlock>?>(null) }
    LaunchedEffect(tick) {
        list = try {
            repo.chat.fed.blocks().filter { it.local }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.std_blocked_list)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val l = list
                if (l != null && l.isEmpty()) Text(stringResource(R.string.std_blocked_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                l.orEmpty().forEach { b ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(b.label?.ifBlank { null } ?: b.name ?: "?", modifier = Modifier.weight(1f))
                        SagaTextButton(onClick = { repo.launchAction { repo.chat.unblock(b.target); repo.notify(UiText.Res(R.string.std_unblocked_done)) } }) {
                            Text(stringResource(R.string.std_unblock))
                        }
                    }
                }
            }
        },
        confirmButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Anzeigename des Absenders (nie Anmeldename) */
fun senderName(m: ChatMessage): String = m.sender?.let { ChatLogic.shown(it) }.orEmpty()
