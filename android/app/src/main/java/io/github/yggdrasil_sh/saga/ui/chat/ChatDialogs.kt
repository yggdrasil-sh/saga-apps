package io.github.yggdrasil_sh.saga.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatNoteRef
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.chat.PeopleGroup
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** QR-Code als Bild (zxing) */
fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** "Nie" / "1 Stunde" / "7 Tage" */
@Composable
fun retentionLabel(sec: Long): String {
    val res = LocalResources.current
    return when {
        sec <= 0 -> stringResource(R.string.chat_retention_never)
        sec % 86400 == 0L -> res.getQuantityString(R.plurals.chat_days, (sec / 86400).toInt(), (sec / 86400).toInt())
        else -> res.getQuantityString(R.plurals.chat_hours, (sec / 3600).toInt().coerceAtLeast(1), (sec / 3600).toInt().coerceAtLeast(1))
    }
}

/** Neuer Chat: Person (Direkt-Chat), eigene Gruppe (Gruppen-Chat) oder mehrere Personen (freie Gruppe) */
@Composable
fun NewChatDialog(repo: SagaRepository, onDismiss: () -> Unit, onOpen: (Long) -> Unit) {
    val svc = repo.chat
    var q by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    var groups by remember { mutableStateOf<List<PeopleGroup>>(emptyList()) }
    var multi by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    LaunchedEffect(q) {
        delay(250)
        try {
            val r = svc.people(q)
            users = r.users
            groups = r.groups
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_new)) },
        text = {
            Column {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true, label = { Text(stringResource(R.string.chat_people_search)) },
                    supportingText = { Text(stringResource(R.string.chat_people_hint)) }, modifier = Modifier.fillMaxWidth(),
                )
                FilterChip(selected = multi, onClick = { multi = !multi; picked = emptyList() }, label = { Text(stringResource(R.string.chat_kind_free)) })
                if (multi && picked.isNotEmpty()) {
                    Text(picked.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(users, key = { "u-" + it.username }) { u ->
                        val on = picked.any { it.username == u.username }
                        ListItem(
                            headlineContent = { Text(u.name.ifEmpty { u.username }) },
                            supportingContent = { Text(if (u.ready) u.username else stringResource(R.string.chat_not_ready, u.username)) },
                            leadingContent = { Avatar(u.name, size = 32) },
                            trailingContent = if (multi) ({ Checkbox(checked = on, onCheckedChange = null) }) else null,
                            modifier = Modifier.clickable {
                                if (multi) picked = if (on) picked.filter { it.username != u.username } else picked + u
                                else repo.launchAction { onOpen(svc.createDm(u.username).id) }
                            },
                        )
                    }
                    if (!multi) {
                        items(groups, key = { "g-" + it.name }) { g ->
                            ListItem(
                                headlineContent = { Text(g.name) },
                                supportingContent = { Text(stringResource(R.string.chat_kind_group)) },
                                leadingContent = { Avatar(g.name, size = 32, icon = R.drawable.ic_group) },
                                modifier = Modifier.clickable { repo.launchAction { onOpen(svc.createGroup(g.name).id) } },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multi) {
                TextButton(onClick = { repo.launchAction { onOpen(svc.createFree(picked.map { it.username }).id) } }, enabled = picked.isNotEmpty()) {
                    Text(stringResource(R.string.chat_create))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Sicherheitsnummer je Gegenueber (60 Ziffern + QR "saga-safety:1:<ziffern>"), "Als überprüft markieren" */
@Composable
fun SafetyDialog(s: ChatSession, onDismiss: () -> Unit) {
    val others = s.summary?.members.orEmpty().filter { !it.me }
    var who by remember { mutableStateOf(others.firstOrNull { it.username in s.identChanged }?.username ?: others.firstOrNull()?.username) }
    var qr by remember { mutableStateOf(false) }
    val number = who?.let { s.safetyNumber(it) }
    LaunchedEffect(who) { who?.let { if (it in s.identChanged) s.acknowledge(it, false) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_safety_number)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (others.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        others.forEach { m -> FilterChip(selected = who == m.username, onClick = { who = m.username; qr = false }, label = { Text(m.name) }) }
                    }
                }
                val name = others.firstOrNull { it.username == who }?.name.orEmpty()
                Text(stringResource(R.string.chat_safety_text, name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                if (number == null) {
                    Text(stringResource(R.string.chat_not_ready, name), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        number.split(" ").chunked(3).joinToString("\n") { it.joinToString("  ") }, fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    if (qr) {
                        val bmp = remember(number) { qrBitmap(ChatFormat.safetyQr(number)).asImageBitmap() }
                        Image(bmp, stringResource(R.string.chat_safety_qr), Modifier.padding(top = 12.dp).size(220.dp).align(Alignment.CenterHorizontally))
                    }
                    TextButton(onClick = { qr = !qr }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(if (qr) R.string.chat_safety_hide_qr else R.string.chat_safety_qr))
                    }
                    who?.let { w ->
                        if (s.isVerified(w)) Text("✓ " + stringResource(R.string.chat_verified), color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        },
        confirmButton = {
            val w = who
            if (w != null && number != null && !s.isVerified(w)) {
                TextButton(onClick = { s.acknowledge(w, true); onDismiss() }) { Text(stringResource(R.string.chat_mark_verified)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
        dismissButton = { if (who != null && number != null && who?.let { s.isVerified(it) } == false) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Mitglieder; freie Gruppe: Person hinzufuegen (Admin) */
@Composable
fun MembersDialog(repo: SagaRepository, s: ChatSession, onDismiss: () -> Unit) {
    val sum = s.summary
    var add by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    LaunchedEffect(q) {
        delay(250)
        try {
            found = repo.chat.people(q).users.filter { u -> sum?.members?.none { it.username == u.username } ?: true }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_members) + (sum?.let { " (${it.memberCount})" } ?: "")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(sum?.members.orEmpty(), key = { it.username }) { m ->
                    ListItem(
                        headlineContent = { Text(m.name.ifEmpty { m.username } + if (m.me) " (" + stringResource(R.string.chat_you) + ")" else "") },
                        supportingContent = { Text(m.username) },
                        leadingContent = { Avatar(m.name, size = 32) },
                    )
                }
                if (sum?.kind == "free" && sum.role == "admin") {
                    item { HorizontalDivider() }
                    if (!add) {
                        item { TextButton(onClick = { add = true }) { Text(stringResource(R.string.chat_add_member)) } }
                    } else {
                        item {
                            OutlinedTextField(
                                value = q, onValueChange = { q = it }, singleLine = true, label = { Text(stringResource(R.string.chat_people_search)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        items(found, key = { "f-" + it.username }) { u ->
                            ListItem(
                                headlineContent = { Text(u.name) }, supportingContent = { Text(u.username) },
                                modifier = Modifier.clickable { repo.launchAction { s.addMember(u.username); add = false; q = "" } },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Nachrichten automatisch loeschen nach (Werte vom Server; Hoechstwert des Admins beachten) */
@Composable
fun RetentionDialog(repo: SagaRepository, s: ChatSession, onDismiss: () -> Unit) {
    val limits = repo.chat.me.value?.limits
    val opts = limits?.retentions?.ifEmpty { null } ?: listOf(0L, 3600L, 86400L, 604800L, 2592000L)
    val max = limits?.retentionMax ?: 0L
    var sel by remember { mutableStateOf(s.summary?.retentionS ?: 0L) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_retention)) },
        text = {
            Column {
                opts.forEach { o ->
                    val allowed = max <= 0 || (o in 1..max)
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = sel == o, enabled = allowed, role = Role.RadioButton) { sel = o }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel == o, onClick = null, enabled = allowed)
                        Text(retentionLabel(o), modifier = Modifier.padding(start = 12.dp), color = if (allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                    }
                }
                Text(stringResource(R.string.chat_retention_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); repo.launchAction { s.setRetention(sel) } }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun RenameDialog(repo: SagaRepository, s: ChatSession, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(s.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename)) },
        text = { OutlinedTextField(value = t, onValueChange = { if (it.length <= 80) t = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); repo.launchAction { s.rename(t) } }, enabled = t.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** @-Verweis: eigene und mit mir geteilte Seiten suchen */
@Composable
fun PagePickDialog(repo: SagaRepository, onDismiss: () -> Unit, onPick: (ChatNoteRef) -> Unit) {
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
        title = { Text(stringResource(R.string.chat_attach_page)) },
        text = {
            Column {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text(stringResource(R.string.nav_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(pages, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.title) },
                            leadingContent = { Text(p.icon.takeIf { it.isNotEmpty() && !it.startsWith("/") } ?: "📄") },
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

/** Neues Geraet moechte Chats lesen: Code vergleichen, dann bestaetigen oder ablehnen (global, bei chat.link) */
@Composable
fun ChatLinkApproval(repo: SagaRepository) {
    val svc = repo.chat
    val links by svc.pendingLinks.collectAsState()
    val st by svc.state.collectAsState()
    val l = links.firstOrNull() ?: return
    if (st != ChatSetup.READY) return
    val code = remember(l.id) { svc.linkCode(l) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.chat_link_request_title)) },
        text = {
            Column {
                Text(stringResource(R.string.chat_link_request_text, l.name.ifEmpty { "?" }))
                Text(
                    linkCodeLines(code.orEmpty()), fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { repo.launchAction { svc.approve(l) } }, enabled = code != null) { Text(stringResource(R.string.chat_link_confirm)) } },
        dismissButton = { TextButton(onClick = { repo.launchAction { svc.reject(l) } }) { Text(stringResource(R.string.chat_link_reject), color = MaterialTheme.colorScheme.error) } },
    )
}

/** Kopplungs-Code in zwei Zeilen a zwei Bloecke ("01234 56789\n01234 56789") - gut vergleichbar, ohne Umbruch mitten im Block */
fun linkCodeLines(code: String): String = code.split(" ").chunked(2).joinToString("\n") { it.joinToString("  ") }
