package io.github.yggdrasil_sh.saga.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatNoteRef
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.PendingFile
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Eine Unterhaltung: Blasen (eigene rechts), Antworten, Anhaenge, @-Seiten, "tippt …", Menue */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Conversation(repo: SagaRepository, nav: AppNavigator, chatId: Long, twoPane: Boolean) {
    val svc = repo.chat
    val s = remember(chatId) { ChatSession(chatId, svc) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var unverifiedOf by remember { mutableStateOf<String?>(null) }
    var safetyFor by remember { mutableStateOf<String?>(null) }
    var input by remember(chatId) { mutableStateOf("") }
    val files = remember(chatId) { mutableStateListOf<PendingFile>() }
    val noteIds = remember(chatId) { mutableStateListOf<ChatNoteRef>() }
    val list = rememberLazyListState()
    val images = remember(chatId) { mutableStateMapOf<String, ImageBitmap?>() }

    LaunchedEffect(s) { s.open() }
    LaunchedEffect(s) { repo.live.events.collect { s.onEvent(it) } }
    LaunchedEffect(s) {
        while (true) {
            delay(1_000)
            s.expireTyping()
        }
    }
    LaunchedEffect(s.messages.size) { if (s.messages.isNotEmpty() && list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0) }
    if (s.notFound) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch {
            for (u in uris) readFile(context, u)?.let { files.add(it) }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        TopAppBar(
            title = {
                Column {
                    Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.chat_e2e), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            },
            navigationIcon = {
                if (!twoPane && nav.canGoBack) IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
            },
            actions = {
                IconButton(onClick = { search = if (search == null) "" else null }) { Icon(Icons.Filled.Search, stringResource(R.string.chat_search)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val sum = s.summary
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_safety_number)) }, onClick = { menu = false; dialog = "safety" })
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_members)) }, onClick = { menu = false; dialog = "members" })
                        if (sum != null) {
                            DropdownMenuItem(text = { Text(stringResource(if (sum.muted) R.string.chat_unmute else R.string.chat_mute)) }, onClick = {
                                menu = false
                                repo.launchAction { s.setMuted(!sum.muted) }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_retention)) }, onClick = { menu = false; dialog = "retention" })
                            if (sum.kind == "free") {
                                DropdownMenuItem(text = { Text(stringResource(R.string.chat_rename)) }, onClick = { menu = false; dialog = "rename" })
                                DropdownMenuItem(text = { Text(stringResource(R.string.chat_leave), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; dialog = "leave" })
                            }
                        }
                    }
                }
            },
        )
        search?.let { q ->
            OutlinedTextField(
                value = q, onValueChange = { search = it }, singleLine = true, placeholder = { Text(stringResource(R.string.chat_search_hint)) },
                trailingIcon = { IconButton(onClick = { search = null }) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        // Sicherheitsnummer eines Gegenuebers hat sich geaendert (TOFU)
        s.identChanged.firstOrNull()?.let { uid ->
            val who = s.summary?.members?.firstOrNull { it.username == uid }?.name ?: uid
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        stringResource(R.string.chat_safety_changed, who), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    TextButton(onClick = { dialog = "safety" }) { Text(stringResource(R.string.chat_show), color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
        }
        s.error?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        val shown = remember(s.messages.toList(), search) {
            val q = search?.trim()?.lowercase().orEmpty()
            s.messages.toList().filter { q.isEmpty() || it.content?.text?.lowercase()?.contains(q) == true }.asReversed()
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp), state = list, reverseLayout = true) {
                items(shown, key = { it.id }) { m ->
                    Bubble(
                        m, s, group = s.summary?.kind != "dm", images = images,
                        onReply = { s.replyTo = m },
                        onCopy = { copyText(context, m.content?.text.orEmpty()) },
                        onDelete = { repo.launchAction { s.delete(m) } },
                        onOpenNote = { id -> nav.push(Dest.Page(id)) },
                        onOpenFile = { f -> scope.launch { openFile(context, s, f) { repo.notify(it) } } },
                        onUnverified = { unverifiedOf = m.sender?.username ?: "" },
                    )
                }
                if (s.more && search == null) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = { repo.launchAction { s.loadOlder() } }, modifier = Modifier.padding(8.dp)) {
                                Text(stringResource(R.string.notifications_load_older))
                            }
                        }
                    }
                }
                if (!s.loading && s.messages.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.chat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
            if (s.loading) CircularProgressIndicator(Modifier.padding(top = 48.dp))
        }
        // "{name} tippt …"
        s.typing.values.firstOrNull()?.let { (name, _) ->
            Text(
                stringResource(R.string.chat_typing, name), style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        HorizontalDivider()
        s.replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(stringResource(R.string.chat_reply_to, r.sender?.name.orEmpty()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(r.content?.text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { s.replyTo = null }) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
            }
        }
        if (files.isNotEmpty() || noteIds.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                files.forEach { f ->
                    InputChip(selected = false, onClick = { files.remove(f) }, label = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.action_delete), Modifier.size(16.dp)) })
                }
                noteIds.forEach { n ->
                    InputChip(selected = false, onClick = { noteIds.remove(n) }, label = { Text("📄 " + n.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.action_delete), Modifier.size(16.dp)) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                var attach by remember { mutableStateOf(false) }
                IconButton(onClick = { attach = true }) { Icon(Icons.Filled.Add, stringResource(R.string.chat_attach)) }
                DropdownMenu(expanded = attach, onDismissRequest = { attach = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_file)) }, onClick = { attach = false; pick.launch("*/*") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_page)) }, onClick = { attach = false; dialog = "page" })
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    if (it.isNotBlank()) scope.launch { s.typingNow() }
                },
                placeholder = { Text(stringResource(R.string.chat_message_hint)) },
                maxLines = 6, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
            val canSend = (input.isNotBlank() || files.isNotEmpty() || noteIds.isNotEmpty()) && !s.sending
            IconButton(onClick = {
                val text = input.trim()
                val fs = files.toList()
                val ns = noteIds.map { it.id }
                val refs = noteIds.toList()
                input = ""
                files.clear()
                noteIds.clear()
                scope.launch {
                    try {
                        s.send(text, fs, ns)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        input = text                                       // nichts verlieren
                        files.addAll(fs)
                        noteIds.addAll(refs)
                        repo.notify(repo.friendly(e))
                    }
                }
            }, enabled = canSend) {
                if (s.sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send), tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
        }
    }

    unverifiedOf?.let { who ->
        AlertDialog(
            onDismissRequest = { unverifiedOf = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.chat_unverified)) },
            text = { Text(stringResource(R.string.chat_unverified_explain)) },
            confirmButton = {
                TextButton(onClick = { unverifiedOf = null; safetyFor = who.ifEmpty { null }; dialog = "safety" }) {
                    Text(stringResource(R.string.chat_check_safety))
                }
            },
            dismissButton = { TextButton(onClick = { unverifiedOf = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    when (dialog) {
        "safety" -> SafetyDialog(s, safetyFor) { dialog = null; safetyFor = null }
        "members" -> MembersDialog(repo, s) { dialog = null }
        "retention" -> RetentionDialog(repo, s) { dialog = null }
        "rename" -> RenameDialog(repo, s) { dialog = null }
        "page" -> PagePickDialog(repo, onDismiss = { dialog = null }) { n -> dialog = null; if (noteIds.none { it.id == n.id }) noteIds.add(n) }
        "leave" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.chat_leave_title)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    repo.launchAction { s.leave(); nav.top(Dest.Chats) }
                }) { Text(stringResource(R.string.chat_leave), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Datei aus dem Auswahldialog lesen (Name, Typ, Inhalt) */
private suspend fun readFile(context: Context, u: Uri): PendingFile? = withContext(Dispatchers.IO) {
    try {
        val cr = context.contentResolver
        var name = "datei"
        cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
        val mime = cr.getType(u) ?: "application/octet-stream"
        val data = cr.openInputStream(u)?.use { it.readBytes() } ?: return@withContext null
        PendingFile(name.take(200), mime, data)
    } catch (_: Exception) {
        null
    }
}

/** Anhang laden, entschluesseln und mit einer anderen App oeffnen/teilen (nur im Cache, nicht dauerhaft) */
private suspend fun openFile(context: Context, s: ChatSession, f: ChatFile, onError: (io.github.yggdrasil_sh.saga.util.UiText) -> Unit) {
    try {
        val data = s.download(f)
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "chat-files").apply { mkdirs() }
            val safe = f.n.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifEmpty { "datei" }.take(100)
            val out = File(dir, safe)
            out.writeBytes(data)
            FileProvider.getUriForFile(context, context.packageName + ".files", out)
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.m.ifEmpty { "application/octet-stream" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, f.n).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        onError(io.github.yggdrasil_sh.saga.util.UiText.Res(R.string.chat_file_broken))
    }
}

/** Text mit Seitenverweisen [[note:ID]] -> "📄 Titel" (antippbar, nur mit Zugriff) */
@Composable
private fun messageText(text: String, notes: Map<Long, ChatNoteRef?>, onOpen: (Long) -> Unit): AnnotatedString {
    val noAccess = stringResource(R.string.chat_page_no_access)
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var last = 0
        for (m in ChatFormat.NOTE_REF.findAll(text)) {
            append(text.substring(last, m.range.first))
            val id = m.groupValues[1].toLongOrNull()
            val ref = id?.let { notes[it] }
            if (id != null && ref != null) {
                withLink(LinkAnnotation.Clickable("note-$id", TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) { onOpen(id) }) {
                    append("📄 " + ref.title)
                }
            } else {
                append("📄 $noAccess")
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

/** Farben der Blasen und der Markierung "Echtheit nicht bestätigt" (Kontrast im Test geprueft) */
object ChatUi {
    fun bubble(c: ColorScheme, mine: Boolean): Color = if (mine) c.primaryContainer else c.surfaceContainerHigh
    fun unverifiedText(c: ColorScheme, mine: Boolean): Color = if (mine) c.onPrimaryContainer else c.onSurfaceVariant
    fun unverifiedIcon(c: ColorScheme, mine: Boolean): Color = if (mine) c.onPrimaryContainer else c.error

    /** WCAG-Kontrastverhaeltnis */
    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: ChatMessage, s: ChatSession, group: Boolean, images: MutableMap<String, ImageBitmap?>,
    onReply: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, onOpenNote: (Long) -> Unit, onOpenFile: (ChatFile) -> Unit,
    onUnverified: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val bg = ChatUi.bubble(MaterialTheme.colorScheme, m.mine)
    val fg = if (m.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val time = remember(m.ts) {
        Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Surface(
                color = bg, contentColor = fg,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (m.mine) 18.dp else 4.dp, bottomEnd = if (m.mine) 4.dp else 18.dp),
                // voll deckend - auch bei "Echtheit nicht bestätigt" (kein Abdunkeln des Inhalts)
                modifier = Modifier.widthIn(max = 520.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.chat_message_actions)),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (group && !m.mine) {
                        Text(m.sender?.name.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (!m.verified) {
                        // Symbol in Warnfarbe, Text in Sekundaerfarbe (>= 4,5:1), antippbar -> Erklaerung + "Sicherheitsnummer prüfen"
                        val scheme = MaterialTheme.colorScheme
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onUnverified)
                                .heightIn(min = 32.dp).padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = ChatUi.unverifiedIcon(scheme, m.mine))
                            Text(
                                stringResource(R.string.chat_unverified) + " ⓘ", style = MaterialTheme.typography.labelMedium,
                                color = ChatUi.unverifiedText(scheme, m.mine), modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    m.replyTo?.let { rid ->
                        val q = s.messages.firstOrNull { it.id == rid }
                        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(q?.sender?.name.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    q?.content?.text ?: stringResource(R.string.chat_reply_missing), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    val c = m.content
                    if (c == null) {
                        Text(stringResource(R.string.chat_undecryptable), fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        c.files.forEach { f -> Attachment(f, s, images, onOpenFile) }
                        if (c.text.isNotEmpty()) Text(messageText(c.text, s.notes, onOpenNote), style = MaterialTheme.typography.bodyLarge)
                        c.notes.filter { id -> !c.text.contains("[[note:$id]]") }.forEach { id ->
                            val ref = s.notes[id]
                            if (ref != null) TextButton(onClick = { onOpenNote(id) }) { Text("📄 " + ref.title) }
                            else Text("📄 " + stringResource(R.string.chat_page_no_access), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(time, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.End))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_reply)) }, onClick = { menu = false; onReply() })
                if (!m.content?.text.isNullOrEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.text_copy)) }, onClick = { menu = false; onCopy() })
                if (m.mine) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_delete_all), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.chat_delete_all_title)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.chat_delete_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Bilder inline (entschluesselt im Speicher), sonst Datei mit Name/Groesse zum Oeffnen/Teilen */
@Composable
private fun Attachment(f: ChatFile, s: ChatSession, images: MutableMap<String, ImageBitmap?>, onOpen: (ChatFile) -> Unit) {
    if (f.m.startsWith("image/") && f.s <= 8 * 1048576) {
        LaunchedEffect(f.id) {
            if (!images.containsKey(f.id)) {
                images[f.id] = try {
                    val data = s.download(f)
                    withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }
        val img = images[f.id]
        if (img != null) {
            Image(
                img, f.n, contentScale = ContentScale.Fit,
                modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 320.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { onOpen(f) },
            )
            return
        }
        if (!images.containsKey(f.id)) {
            CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp)
            return
        }
    }
    TextButton(onClick = { onOpen(f) }) {
        Text("📎 " + f.n + " · " + android.text.format.Formatter.formatShortFileSize(LocalContext.current, f.s), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
