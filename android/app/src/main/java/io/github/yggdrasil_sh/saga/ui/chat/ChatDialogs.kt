package io.github.yggdrasil_sh.saga.ui.chat

import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import androidx.compose.material3.Icon
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import io.github.yggdrasil_sh.saga.util.resolve
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.yggdrasil_sh.saga.ui.common.imeDialog
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import io.github.yggdrasil_sh.saga.chat.ChatFedHint
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatNoteRef
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.chat.PeopleGroup
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.openUrl
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

/** Hinweis statt "Keine Treffer" bei name@server oder E-Mail (ChatFedHint): Chats ueber Servergrenzen gibt es noch nicht */
@Composable
fun FedChatHint(v2: Boolean = false) {
    val ctx = LocalContext.current
    val lang = LocalConfiguration.current.locales[0].language
    Column(Modifier.padding(vertical = 4.dp)) {
        // 2.3: Server kann Chats ueber Servergrenzen -> Personen ueber Anzeigename oder name@server (Adressbuch)
        Text(stringResource(if (v2) R.string.fed_chat_hint_v2 else R.string.chat_fed_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.chat_fed_hint_local), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
        SagaTextButton(onClick = { openUrl(ctx, ChatFedHint.guideUrl(lang)) }) { Text(stringResource(R.string.chat_fed_guide)) }
    }
}

/** Sicherheitsnummer je Gegenueber (60 Ziffern + QR "saga-safety:1:<ziffern>"), "Als überprüft markieren" */
@Composable
fun SafetyDialog(s: ChatSession, initial: String? = null, onDismiss: () -> Unit) {
    val others = s.summary?.members.orEmpty().filter { !it.me }
    var who by remember {
        mutableStateOf(
            initial?.takeIf { u -> others.any { it.username == u } }
                ?: others.firstOrNull { it.username in s.identChanged }?.username ?: others.firstOrNull()?.username,
        )
    }
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
                    CodeRows(ChatFormat.safetyRows(number).map { it.joinToString("  ") }, 18.sp)
                    if (qr) {
                        val bmp = remember(number) { qrBitmap(ChatFormat.safetyQr(number)).asImageBitmap() }
                        Image(bmp, stringResource(R.string.chat_safety_qr), Modifier.padding(top = 12.dp).size(220.dp).align(Alignment.CenterHorizontally))
                    }
                    SagaTextButton(onClick = { qr = !qr }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(if (qr) R.string.chat_safety_hide_qr else R.string.chat_safety_qr))
                    }
                    who?.let { w ->
                        if (s.isVerified(w)) Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                            SagaIcon(SagaIcons.check, null, Modifier.padding(end = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.chat_verified), color = MaterialTheme.colorScheme.primary)
                        }
                        // 2.5: Vertrauen aus v1 uebernommen - die Ziffern sehen anders aus, der Schluessel ist derselbe
                        if (s.isMigrated(w)) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                            SagaIcon(SagaIcons.info, null, Modifier.padding(end = 6.dp, top = 1.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.x3_safety_migrated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val w = who
            if (w != null && number != null && !s.isVerified(w)) {
                SagaTextButton(onClick = { s.acknowledge(w, true); onDismiss() }) { Text(stringResource(R.string.chat_mark_verified)) }
            } else {
                SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
        dismissButton = { if (who != null && number != null && who?.let { s.isVerified(it) } == false) SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Mitglieder; freie Gruppe: Person hinzufuegen und entfernen (Admin) */
@Composable
fun MembersDialog(repo: SagaRepository, s: ChatSession, onDismiss: () -> Unit) {
    val sum = s.summary
    var add by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    // 2.3 (E5): Personen anderer Server - nur in Gruppen ueber Servergrenzen (sonst: neue Gruppe anlegen, Server: 409)
    var foundRemote by remember { mutableStateOf<List<io.github.yggdrasil_sh.saga.chat.FedPerson>>(emptyList()) }
    val fedV2 by repo.chat.fed.supported.collectAsState()
    var fedFor by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<ChatPerson?>(null) }
    LaunchedEffect(q) {
        delay(250)
        try {
            val r = repo.chat.people(q)
            val all = r.users
            found = all.filter { u -> !ChatLogic.isMember(u.username, sum?.members.orEmpty()) }     // 2.4 (W4): Handle <-> ref
            foundRemote = if (s.isFed) r.remote.filter { u -> sum?.members?.none { it.handle == u.handle } ?: true } else emptyList()
            fedFor = if (ChatFedHint.show(q, all.size + foundRemote.size)) q else ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = io.github.yggdrasil_sh.saga.ui.common.ImeDialog.properties, // CH-05/A11Y-26: Tastatur verdeckt nichts
        modifier = Modifier.imeDialog(),
        title = { Text(stringResource(R.string.chat_members) + (sum?.let { " (${it.memberCount})" } ?: "")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                // Gruppe/Seiten-Chat ueber Servergrenzen: wo der Chat liegt (Heim fuehrt die Mitgliederliste)
                if (sum != null && sum.remote && sum.kind != "dm") item {
                    Text(
                        if (sum.homeSelf) stringResource(R.string.fed_members_home_self) else stringResource(R.string.fed_members_home_other, sum.home ?: "?"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                val admin = sum?.kind == "free" && sum.role == "admin"
                items(sum?.members.orEmpty(), key = { it.username }) { m ->
                    ListItem(
                        headlineContent = { Text(ChatLogic.shown(m) + if (m.me) " (" + stringResource(R.string.chat_you) + ")" else "") },
                        // 2.3: Person eines anderen Servers - Herkunft zeigen, nie den Handle; 2.4 (W4): nie den Anmeldenamen
                        // 2.4: sub unterscheidet Gleichnamige (eigene Zeile ohne)
                        supportingContent = if (m.remote && !m.host.isNullOrEmpty()) ({ Text(stringResource(R.string.fed_on_host, m.host)) })
                            else if (!m.me) ChatLogic.subline(m.sub)?.let { t -> { Text(t) } } else null,
                        leadingContent = { Avatar(m.name, size = 32, avatar = m.avatar) },
                        // Admins einer freien Gruppe: Person entfernen (wie Web, iOS/macOS und Linux)
                        trailingContent = if (admin && ChatLogic.canRemoveMember(m)) { {
                            SagaTextButton(onClick = { removing = m }) { Text(stringResource(R.string.chat_member_remove), color = MaterialTheme.colorScheme.error) }
                        } } else null,
                    )
                }
                if (sum?.kind == "free" && sum.role == "admin") {
                    item { HorizontalDivider() }
                    if (!add) {
                        item { SagaTextButton(onClick = { add = true }) { Text(stringResource(R.string.chat_add_member)) } }
                    } else {
                        item {
                            OutlinedTextField(
                                value = q, onValueChange = { q = it }, singleLine = true, label = { Text(stringResource(R.string.chat_people_search)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (fedFor.isNotEmpty() && fedFor == q) item { FedChatHint(fedV2 == true) }
                        items(found, key = { "f-" + it.username }) { u ->
                            val mark = stringResource(R.string.chat_handle_mark)
                            val same = found.count { ChatLogic.shown(it) == ChatLogic.shown(u) } > 1
                            ListItem(
                                headlineContent = { Text(ChatLogic.shownMarked(u) { mark.format(it) }) },   // 2.4 (W4): nur der Name, darunter sub
                                supportingContent = ChatLogic.personSub(u.sub, same) { mark.format(it) }?.let { t -> { Text(t) } },
                                modifier = Modifier.clickable { repo.launchAction { s.addMember(u.username); add = false; q = "" } },
                            )
                        }
                        items(foundRemote, key = { "r-" + it.handle }) { u ->
                            FedPersonItem(u, stringResource(R.string.fed_on_host, u.host)) {
                                repo.launchAction { s.addMemberHandle(u.handle); add = false; q = "" }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.chat_member_remove_title, ChatLogic.shown(m))) },
            text = { Text(stringResource(R.string.chat_member_remove_text)) },
            confirmButton = {
                SagaTextButton(onClick = { removing = null; repo.launchAction { s.removeMember(m.username) } }) {
                    Text(stringResource(R.string.chat_member_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { SagaTextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Nachrichten automatisch loeschen nach (Werte vom Server; Hoechstwert des Admins beachten) */
@Composable
fun RetentionDialog(repo: SagaRepository, s: ChatSession, onDismiss: () -> Unit) {
    val limits = repo.chat.me.collectAsState().value?.limits
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
                        Text(retentionLabel(o), modifier = Modifier.padding(start = 12.dp), color = if (allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.chat_retention_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { SagaTextButton(onClick = { onDismiss(); repo.launchAction { s.setRetention(sel) } }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun RenameDialog(repo: SagaRepository, s: ChatSession, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(s.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = io.github.yggdrasil_sh.saga.ui.common.ImeDialog.properties, // CH-05/A11Y-26: Tastatur verdeckt nichts
        modifier = Modifier.imeDialog(),
        title = { Text(stringResource(R.string.chat_rename)) },
        text = { OutlinedTextField(value = t, onValueChange = { if (it.length <= 80) t = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            SagaTextButton(onClick = { onDismiss(); repo.launchAction { s.rename(t) } }, enabled = t.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
        properties = io.github.yggdrasil_sh.saga.ui.common.ImeDialog.properties, // CH-05/A11Y-26: Tastatur verdeckt nichts
        modifier = Modifier.imeDialog(),
        title = { Text(stringResource(R.string.chat_attach_page)) },
        text = {
            Column {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text(stringResource(R.string.nav_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(pages, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.title) },
                            leadingContent = { PageIconOrLine(p.icon) },   // CH-21: ohne eigenes Seitensymbol das Linien-Symbol statt Emoji
                            modifier = Modifier.clickable { onPick(p) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Neues Geraet moechte Chats lesen: Code vergleichen, dann bestaetigen oder ablehnen (global, bei chat.link). 2.5: scheitert
 * die Bestaetigung an der Geraetegrenze (409 device_limit), dieselbe Meldung wie auf dem neuen Geraet mit Weg zu
 * "Chats & Geraete" ([onDevices]) statt nur einer kurzen Einblendung.
 */
@Composable
fun ChatLinkApproval(repo: SagaRepository, onDevices: () -> Unit = {}) {
    val svc = repo.chat
    val links by svc.pendingLinks.collectAsState()
    val st by svc.state.collectAsState()
    var limit by remember { mutableStateOf<io.github.yggdrasil_sh.saga.chat.DeviceLimit?>(null) }
    val scope = rememberCoroutineScope()
    limit?.let { lim ->
        AlertDialog(
            onDismissRequest = { limit = null },
            icon = { SagaIcon(SagaIcons.warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.r5_chat_device_limit_title)) },
            text = { Text(io.github.yggdrasil_sh.saga.chat.ChatDeviceLimit.text(lim).resolve()) },
            confirmButton = { SagaTextButton(onClick = { limit = null; onDevices() }) { Text(stringResource(R.string.chat_devices)) } },
            dismissButton = { SagaTextButton(onClick = { limit = null }) { Text(stringResource(R.string.action_close)) } },
        )
        return
    }
    val l = links.firstOrNull() ?: return
    if (st != ChatSetup.READY) return
    val code = remember(l.id) { svc.linkCode(l) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.chat_link_request_title)) },
        text = {
            Column {
                Text(stringResource(R.string.chat_link_request_text, l.name.ifEmpty { "?" }))
                LinkCodeView(code.orEmpty(), 22.sp, Modifier.padding(top = 16.dp), minOneLine = 16.sp)
            }
        },
        confirmButton = {
            SagaTextButton(onClick = {
                scope.launch {
                    try {
                        svc.approve(l)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: io.github.yggdrasil_sh.saga.chat.DeviceLimitException) {
                        limit = e.limit
                    } catch (e: Exception) {
                        repo.notify(repo.friendly(e))
                    }
                }
            }, enabled = code != null) { Text(stringResource(R.string.chat_link_confirm)) }
        },
        dismissButton = { SagaTextButton(onClick = { repo.launchAction { svc.reject(l) } }) { Text(stringResource(R.string.chat_link_reject), color = MaterialTheme.colorScheme.error) } },
    )
}

private fun codeStyle(fs: Float, weight: FontWeight) = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fs.sp, fontWeight = weight)

/**
 * Gruppen zeilenweise in Monospace (Sicherheitsnummer, Kopplungs-Code, Wiederherstellungsschluessel). Nie Umbruch mitten
 * in einer Zeile: die Schrift wird bis [minFont] verkleinert, bis die laengste Zeile passt.
 */
@Composable
fun CodeRows(lines: List<String>, fontSize: TextUnit, modifier: Modifier = Modifier, minFont: TextUnit = 12.sp, weight: FontWeight = FontWeight.SemiBold) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val longest = lines.maxByOrNull { it.length }.orEmpty()
        val maxPx = constraints.maxWidth
        val fs = remember(longest, maxPx, fontSize) {
            var f = fontSize.value
            while (f > minFont.value && measurer.measure(longest, codeStyle(f, weight), softWrap = false).size.width > maxPx) f -= 0.5f
            f
        }
        Text(
            lines.joinToString("\n"), style = codeStyle(fs, weight), textAlign = TextAlign.Center, softWrap = false,
            lineHeight = (fs * 1.45f).sp, color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Kopplungs-Code: 4 Gruppen in EINER Zeile; nur wenn sie selbst mit [minOneLine] nicht passt, 2 Zeilen a 2 Gruppen */
@Composable
fun LinkCodeView(code: String, fontSize: TextUnit, modifier: Modifier = Modifier, minOneLine: TextUnit = 18.sp) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val one = ChatFormat.linkCodeRows(code, true).map { it.joinToString("  ") }
        val fits = remember(code, constraints.maxWidth) {
            measurer.measure(one.firstOrNull().orEmpty(), codeStyle(minOneLine.value, FontWeight.Bold), softWrap = false).size.width <= constraints.maxWidth
        }
        val rows = if (fits) one else ChatFormat.linkCodeRows(code, false).map { it.joinToString("  ") }
        CodeRows(rows, fontSize, minFont = if (fits) minOneLine else 14.sp, weight = FontWeight.Bold)
    }
}
