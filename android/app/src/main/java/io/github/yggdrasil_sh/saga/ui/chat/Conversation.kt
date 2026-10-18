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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
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
import io.github.yggdrasil_sh.saga.ai.AiChatLogic
import io.github.yggdrasil_sh.saga.ai.AiPersona
import io.github.yggdrasil_sh.saga.ai.AiPersonaLogic
import io.github.yggdrasil_sh.saga.ai.AiReplyCheck
import io.github.yggdrasil_sh.saga.ui.ai.AiAvatar
import androidx.compose.ui.semantics.contentDescription
import io.github.yggdrasil_sh.saga.chat.ChatAttach
import io.github.yggdrasil_sh.saga.ui.common.ImmichPicker
import io.github.yggdrasil_sh.saga.ui.common.IncomingImage
import io.github.yggdrasil_sh.saga.ui.common.clipboardImages
import io.github.yggdrasil_sh.saga.ui.common.imagesIn
import io.github.yggdrasil_sh.saga.ui.common.prepareImage
import io.github.yggdrasil_sh.saga.ui.common.readImage
import io.github.yggdrasil_sh.saga.ui.common.rememberClipboardHasImage
import io.github.yggdrasil_sh.saga.util.ImageData
import android.content.ClipData
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import io.github.yggdrasil_sh.saga.chat.ChatLimits
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatNoteRef
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.PendingFile
import io.github.yggdrasil_sh.saga.chat.quietly
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val files = remember(chatId) { mutableStateListOf<PendingFile>() }
    val noteIds = remember(chatId) { mutableStateListOf<ChatNoteRef>() }
    // TextFieldState (statt String): nur damit gehen Bilder aus Zwischenablage/Tastatur/Ziehen (contentReceiver)
    val input = rememberSaveable(chatId, saver = TextFieldState.Saver) { TextFieldState() }   // ueberlebt Prozess-Tod (BUG-067)
    LaunchedEffect(input) {
        snapshotFlow { input.text.toString() }.drop(1).collect { if (it.isNotBlank()) scope.launch { s.typingNow() } }
    }
    /** Immich fuer diese Person eingerichtet? -> "Aus Immich" beim Anhaengen */
    var immichOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        immichOn = try {
            repo.immich.connected()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    /** laufende Uebernahmen (Immich, eingefuegte Bilder) - Hinweis ueber dem Eingabefeld */
    var fetching by remember { mutableIntStateOf(0) }
    var immichBusy by remember { mutableIntStateOf(0) }
    val clipHasImage = rememberClipboardHasImage()
    val addImages: (List<IncomingImage>) -> Unit = { list ->
        if (list.isNotEmpty()) scope.launch {
            val max = svc.me.value?.limits?.fileMax ?: ChatLimits().fileMax
            fetching++
            try {
                for (img in list) {
                    val f = incomingImage(context, img, max)
                    if (f != null) files.add(f) else repo.notify(UiText.Res(R.string.chat_file_broken))
                }
            } finally {
                fetching--
            }
        }
    }
    val receiver = remember(chatId) {
        ReceiveContentListener { tc ->
            if (!tc.hasMediaType(MediaType.Image)) return@ReceiveContentListener tc
            val found = ArrayList<IncomingImage>()
            val rest = tc.consume { item ->
                val u = item.uri ?: return@consume false
                val img = imagesIn(context, ClipData(tc.clipEntry.clipData.description, item)).firstOrNull()
                    ?: IncomingImage(u, "image/*").takeIf { tc.source == TransferableContent.Source.Keyboard }
                    ?: return@consume false
                found.add(img)
                true
            }
            addImages(found)
            rest
        }
    }
    val list = rememberLazyListState()

    // KI im Chat (2.1, nur auf Zuruf): Server-Schalter + Persona aus /api/ai/status, Chat-Schalter aus summary.ai
    val aiSt by repo.ai.status.collectAsState()
    val aiPersona = AiPersonaLogic.effective(aiSt)
    val aiServer = aiPersona != null && aiSt?.chat == true
    val aiHere = aiServer && s.summary?.ai == true
    val aiName = aiPersona?.name.orEmpty()
    val aiHost = aiSt?.host?.ifBlank { null } ?: "?"
    // Stand des Servers (KI an, "KI in Chats", Persona) beim Oeffnen und sobald der Chat geladen ist neu holen
    LaunchedEffect(s.summary?.id) { quietly { repo.ai.loadStatus() } }
    LaunchedEffect(s.summary?.id, aiServer) { if (aiServer && s.summary != null && s.aiMayToggle == null) quietly { s.checkAiToggle() } }
    /** Frage an die KI stellen (die Nachricht ist schon gesendet); Fehler als Hinweis */
    val askAi: (String, String) -> Unit = { text, qid ->
        val name = aiName
        scope.launch {
            try {
                s.askAi(text, qid, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(UiText.Res(R.string.chat_ai_failed, name, repo.friendly(e)))
            }
        }
    }

    LaunchedEffect(s) { s.open() }
    // eine Ausnahme hier wuerde den Prozess beenden (BUG-003): jedes Ereignis einzeln abfangen
    LaunchedEffect(s) { repo.live.events.collect { ev -> quietly { s.onEvent(ev) } } }
    LaunchedEffect(s) {
        var tick = 0
        while (true) {
            delay(1_000)
            s.expireTyping()
            if (++tick % 5 == 0) s.catchUp()                     // gescheitertes Nachladen wiederholen
        }
    }
    LaunchedEffect(s.messages.size) { if (s.messages.isNotEmpty() && list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0) }
    // laufende KI-Antwort erscheint ganz unten: dorthin rollen (die Liste haelt sonst die bisherige Position)
    LaunchedEffect(s.aiPending) { if (s.aiPending != null && list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0) }
    // 2.1: Entwurf verwerfen, wenn der Chat geloescht/geleert wurde (Eingabe, Anhaenge, Seitenverweise)
    LaunchedEffect(s.draftReset) {
        if (s.draftReset > 0) {
            input.clearText()
            files.clear()
            noteIds.clear()
        }
    }
    if (s.notFound) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    // Chat fuer alle geloescht bzw. Direkt-Chat auf einem anderen eigenen Geraet geleert: schliessen
    if (s.closed) {
        LaunchedEffect(Unit) { nav.top(Dest.Chats) }
        return
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch {
            val max = svc.me.value?.limits?.fileMax ?: ChatLimits().fileMax
            for (u in uris) when (val r = readFile(context, u, max)) {
                is ChatAttach.Read.Ok -> files.add(r.file)
                ChatAttach.Read.TooBig -> repo.notify(UiText.Res(R.string.chat_file_too_big, (max / 1048576).toInt()))
                ChatAttach.Read.Failed -> repo.notify(UiText.Res(R.string.chat_file_broken))
            }
        }
    }

    // Anrufe (2.1): Knoepfe im Direkt-Chat, wenn der Server Anrufe kann; vorher Mikrofon (+ Kamera) erlauben lassen
    val callsOn by repo.calls.enabled.collectAsState()
    LaunchedEffect(Unit) { if (repo.calls.enabled.value == null) repo.calls.checkEnabled() }
    var callVideo by remember { mutableStateOf<Boolean?>(null) }
    fun callNow(video: Boolean) {
        val p = s.summary?.peer ?: return
        repo.calls.start(chatId, io.github.yggdrasil_sh.saga.call.CallPeer(p.username, p.name, p.avatar), video)
    }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val v = callVideo ?: return@rememberLauncherForActivityResult
        callVideo = null
        if (io.github.yggdrasil_sh.saga.call.CallPermissions.missing(context, false).contains(android.Manifest.permission.RECORD_AUDIO)) {
            repo.notify(UiText.Res(R.string.call_mic_denied))
        } else {
            callNow(v && !io.github.yggdrasil_sh.saga.call.CallPermissions.missing(context, true).contains(android.Manifest.permission.CAMERA))
        }
    }
    val startCall: (Boolean) -> Unit = { video ->
        val need = io.github.yggdrasil_sh.saga.call.CallPermissions.missing(context, video)
        if (need.isEmpty()) callNow(video) else {
            callVideo = video
            callPerms.launch(need.toTypedArray())
        }
    }
    val canCall = callsOn == true && s.summary?.kind == "dm" && s.summary?.peer != null

    // Surface: Inhaltsfarbe onSurface fuer alle Symbole ohne eigene Farbe ("+" im Dunkelmodus war schwarz, BUG-072)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        // zweizeiliger Titel: Kopfzeile waechst mit der Schriftgroesse (200 %: sonst unter der Statusleiste, BUG-070)
        val fontScale = LocalDensity.current.fontScale
        TopAppBar(
            expandedHeight = maxOf(TopAppBarDefaults.TopAppBarExpandedHeight, (46 * fontScale).dp),
            title = {
                Column {
                    Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        // eine Zeile: bei 200 % Schrift sonst hoeher als die Kopfzeile (ragt in die Statusleiste, BUG-070)
                        Text(
                            stringResource(R.string.chat_e2e), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                if (!twoPane && nav.canGoBack) IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
            },
            actions = {
                if (canCall) {
                    IconButton(onClick = { startCall(false) }) { Icon(painterResource(R.drawable.ic_call), stringResource(R.string.call_audio)) }
                    IconButton(onClick = { startCall(true) }) { Icon(painterResource(R.drawable.ic_videocam), stringResource(R.string.call_video)) }
                }
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
                            // KI im Chat (2.1): nur fuer Berechtigte; Erlauben mit Rueckfrage (was an die KI geht)
                            if (aiServer && s.aiMayToggle == true) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (sum.ai) R.string.chat_ai_disallow else R.string.chat_ai_allow, aiName)) },
                                    leadingIcon = { AiAvatar(aiPersona, 22) },
                                    onClick = {
                                        menu = false
                                        if (sum.ai) repo.launchAction {
                                            s.setAi(false)
                                            repo.notify(UiText.Res(R.string.chat_ai_disallowed, aiName))
                                        } else dialog = "ai_allow"
                                    },
                                )
                            }
                            if (sum.kind == "free") {
                                DropdownMenuItem(text = { Text(stringResource(R.string.chat_rename)) }, onClick = { menu = false; dialog = "rename" })
                                DropdownMenuItem(text = { Text(stringResource(R.string.chat_leave), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; dialog = "leave" })
                            }
                            // 2.1: Archiv und Loeschen (nur wenn der Server es kann)
                            if (s.canArchive) {
                                val arch = sum.archived == true
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (arch) R.string.chatdel_unarchive else R.string.chatdel_archive)) },
                                    leadingIcon = { Icon(painterResource(if (arch) R.drawable.ic_unarchive else R.drawable.ic_archive), null) },
                                    onClick = {
                                        menu = false
                                        repo.launchAction {
                                            s.setArchived(!arch)
                                            repo.notify(
                                                UiText.Res(if (arch) R.string.chatdel_unarchived_done else R.string.chatdel_archived_done),
                                                UiText.Res(R.string.chatdel_undo),
                                            ) { repo.launchAction { s.setArchived(arch) } }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chatdel_delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; dialog = "delete" },
                                )
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
        // KI erlaubt (2.1): sichtbarer Hinweis, was an die KI geht
        if (aiHere) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AiAvatar(aiPersona, 20)
                    Text(
                        stringResource(R.string.chat_ai_notice, aiName, AiChatLogic.CTX_MAX, aiHost), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
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
                // laufende Frage an die KI: "{name} denkt nach …" bzw. mitlaufender Text (unten)
                s.aiPending?.let { p ->
                    item(key = "ai-pending") { AiPendingBubble(aiPersona, aiName, p.text) }
                }
                itemsIndexed(shown, key = { _, it -> it.id }) { i, m ->
                  Column {
                    // Datumstrenner ueber der ersten Nachricht eines Tages (Liste neueste zuerst, umgekehrt angezeigt)
                    if (io.github.yggdrasil_sh.saga.chat.ChatFormat.dayStarts(m.ts, shown.getOrNull(i + 1)?.ts, ZoneId.systemDefault())) {
                        ChatDayDivider(io.github.yggdrasil_sh.saga.chat.ChatFormat.dayOf(m.ts, ZoneId.systemDefault()))
                    }
                    val call = m.call
                    if (call != null) {
                        CallEntryRow(m, call, s.me, canCall) { startCall(it) }
                    } else {
                        Bubble(
                            m, s, group = s.summary?.kind != "dm", aiPersona = aiPersona,
                            onReply = { s.replyTo = m },
                            onCopy = { copyText(context, m.content?.text.orEmpty()) },
                            onDelete = { repo.launchAction { s.delete(m) } },
                            onOpenNote = { id -> nav.push(Dest.Page(id)) },
                            onOpenFile = { f -> scope.launch { openFile(context, s, f) { repo.notify(it) } } },
                            onUnverified = { unverifiedOf = m.sender?.username ?: "" },
                        )
                    }
                  }
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
                // 2.1: "Der bisherige Verlauf wurde gelöscht." am Anfang des Verlaufs (umgekehrte Liste: zuletzt = oben)
                if ((s.summary?.clearedSeq ?: 0) > 0 && !s.more && search == null) {
                    item(key = "history-cleared") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.chatdel_history_cleared), style = MaterialTheme.typography.labelMedium,
                                fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
        if (immichBusy > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.immich_loading_photo), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
                )
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
        if (aiHere) {
            val txt = input.text.toString()
            // "@" + Anfang des Namens: KI als erster Vorschlag
            val at = AiChatLogic.atQuery(txt, input.selection.end)
            if (at != null && AiChatLogic.suggestAi(at.second, aiName) && !AiChatLogic.mentions(txt, aiName)) {
                val desc = stringResource(R.string.chat_ai_mention, aiName)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    androidx.compose.material3.AssistChip(
                        onClick = {
                            val end = input.selection.end.coerceIn(at.first, input.text.length)
                            input.edit { replace(at.first, end, "@$aiName ") }
                        },
                        label = { Text(aiName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { AiAvatar(aiPersona, 20) },
                        modifier = Modifier.semantics { contentDescription = desc },
                    )
                }
            }
            // Nachricht mit @Name: kurz anzeigen, dass sie (mit Verlauf) an die KI geht
            if (AiChatLogic.mentions(txt, aiName)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    AiAvatar(aiPersona, 16)
                    Text(
                        stringResource(R.string.chat_ai_send_hint, aiName, AiChatLogic.CTX_MAX, aiHost), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                var attach by remember { mutableStateOf(false) }
                IconButton(onClick = { attach = true }) { Icon(Icons.Filled.Add, stringResource(R.string.chat_attach)) }
                DropdownMenu(expanded = attach, onDismissRequest = { attach = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_file)) }, onClick = { attach = false; pick.launch("*/*") })
                    // gleiche Pruefung wie beim Bild-Einfuegen auf Seiten: nur wenn Immich fuer mich eingerichtet ist
                    if (immichOn) DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_immich)) }, onClick = { attach = false; dialog = "immich" })
                    if (clipHasImage) DropdownMenuItem(text = { Text(stringResource(R.string.paste_image)) }, onClick = { attach = false; addImages(clipboardImages(context)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_page)) }, onClick = { attach = false; dialog = "page" })
                }
            }
            OutlinedTextField(
                state = input,
                placeholder = { Text(stringResource(R.string.chat_message_hint)) },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6), shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).contentReceiver(receiver),
            )
            val canSend = (input.text.isNotBlank() || files.isNotEmpty() || noteIds.isNotEmpty()) && !s.sending && fetching == 0
            IconButton(onClick = {
                // geaenderte Sicherheitsnummer sperrt das Senden: erklaeren und direkt "Anzeigen" anbieten (BUG-108)
                s.identChanged.firstOrNull()?.let { uid ->
                    val who = s.summary?.members?.firstOrNull { it.username == uid }?.name?.ifEmpty { null } ?: uid
                    repo.notify(UiText.Res(R.string.chat_safety_send_blocked, who), UiText.Res(R.string.chat_show)) { dialog = "safety" }
                    return@IconButton
                }
                val text = input.text.toString().trim()
                val fs = files.toList()
                val ns = noteIds.map { it.id }
                val refs = noteIds.toList()
                input.clearText()
                files.clear()
                noteIds.clear()
                // @Name in einem Chat mit erlaubter KI: nach dem normalen (verschluesselten) Senden die KI fragen
                val toAi = aiHere && AiChatLogic.mentions(text, aiName)
                scope.launch {
                    try {
                        val qid = s.send(text, fs, ns)
                        if (toAi) askAi(text, qid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        input.setTextAndPlaceCursorAtEnd(text)             // nichts verlieren
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
        "delete" -> s.summary?.let { sum ->
            DeleteChatDialog(repo, sum, s.name, onDismiss = { dialog = null }, onDone = { dialog = null; nav.top(Dest.Chats) }) { all, leave ->
                s.deleteChat(all, leave)
            }
        }
        "immich" -> ImmichPicker(repo, onDismiss = { dialog = null }) { a ->
            dialog = null
            scope.launch {
                val max = svc.me.value?.limits?.fileMax ?: ChatLimits().fileMax
                fetching++
                immichBusy++
                try {
                    // ueber den Immich-Proxy des Servers (nur durchgereicht), dann wie jeder Anhang E2E-verschluesselt
                    val raw = repo.immich.chatImage(a, max)
                    val prepared = prepareImage(raw, max)
                    if (prepared == null) repo.notify(UiText.Res(R.string.image_unsupported))
                    else files.add(PendingFile(ImageData.nameFor(a.name.ifEmpty { "immich" }, prepared.second), prepared.second, prepared.first))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    repo.notify(UiText.Res(R.string.immich_photo_failed))
                } finally {
                    fetching--
                    immichBusy--
                }
            }
        }
        "ai_allow" -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { AiAvatar(aiPersona, 40) },
            title = { Text(stringResource(R.string.chat_ai_allow_title, aiName)) },
            text = { Text(stringResource(R.string.chat_ai_allow_text, aiName, AiChatLogic.CTX_MAX, aiHost)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    repo.launchAction {
                        s.setAi(true)
                        repo.notify(UiText.Res(R.string.chat_ai_allowed, aiName))
                    }
                }) { Text(stringResource(R.string.chat_ai_allow_ok)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
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

/** Eingefuegtes/von der Tastatur gesendetes Bild -> Anhang (Name aus dem Anbieter, sonst "bild.<endung>") */
private suspend fun incomingImage(context: Context, img: IncomingImage, max: Long): PendingFile? {
    val name = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(img.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
    // etwas mehr lesen als erlaubt: zu grosse Bilder werden danach verkleinert (statt abgelehnt)
    val raw = readImage(context, img, maxOf(max, 4 * max)) ?: return null
    val (data, mime) = prepareImage(raw, max) ?: return null
    return PendingFile(ImageData.nameFor(name.orEmpty(), mime), mime, data)
}

/** Datei aus dem Auswahldialog lesen (Name, Typ, Inhalt) */
private suspend fun readFile(context: Context, u: Uri, max: Long): ChatAttach.Read = withContext(Dispatchers.IO) {
    try {
        val cr = context.contentResolver
        var name = "datei"
        var size: Long? = null
        cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = cr.getType(u) ?: "application/octet-stream"
        // Zu grosses Bild: wie beim Einfuegen verkleinern statt ablehnen (bis zum Vierfachen der Grenze einlesen)
        if (mime.startsWith("image/") && !ChatAttach.tooBig(size, 4 * max) && (size == null || size!! > max)) {
            val raw = cr.openInputStream(u)?.use { ChatAttach.readLimited(it, 4 * max) } ?: return@withContext ChatAttach.Read.TooBig
            if (raw.size <= max) return@withContext ChatAttach.Read.Ok(PendingFile(name.take(200), mime, raw))
            val (data, type) = prepareImage(raw, max) ?: return@withContext ChatAttach.Read.TooBig
            return@withContext ChatAttach.Read.Ok(PendingFile(ImageData.nameFor(name.take(200), type), type, data))
        }
        // Groesse vor dem Lesen pruefen, dann begrenzt lesen (BUG-005: 600 MB -> OutOfMemoryError)
        if (ChatAttach.tooBig(size, max)) return@withContext ChatAttach.Read.TooBig
        val data = cr.openInputStream(u)?.use { ChatAttach.readLimited(it, max) ?: return@withContext ChatAttach.Read.TooBig }
            ?: return@withContext ChatAttach.Read.Failed
        ChatAttach.Read.Ok(PendingFile(name.take(200), mime, data))
    } catch (_: OutOfMemoryError) {
        ChatAttach.Read.TooBig
    } catch (_: Exception) {
        ChatAttach.Read.Failed
    }
}

/** Raeumt entschluesselte Anhaenge nach Ablauf weg - unabhaengig von der Ansicht (lebt so lange wie der Prozess) */
private val fileCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Anhang laden, entschluesseln und mit einer anderen App oeffnen oder teilen/speichern. Klartext nur kurz im Cache
 * (eigener Zufallsordner); nach ChatFiles.KEEP_MS Freigabe zuruecknehmen und loeschen (BUG-023).
 */
private suspend fun openFile(context: Context, s: ChatSession, f: ChatFile, onError: (io.github.yggdrasil_sh.saga.util.UiText) -> Unit) {
    try {
        val data = s.download(f)
        val app = context.applicationContext
        val (out, uri) = withContext(Dispatchers.IO) {
            ChatFiles.purgeOlderThan(app.cacheDir)
            val out = ChatFiles.write(app.cacheDir, f.n, data)
            out to FileProvider.getUriForFile(app, app.packageName + ".files", out)
        }
        fileCleanup.launch {
            delay(ChatFiles.KEEP_MS)
            try {
                app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            ChatFiles.remove(out)
        }
        val type = f.m.ifEmpty { "application/octet-stream" }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // zusaetzlich Teilen (z. B. "In Dateien speichern") - auch fuer Typen ohne Betrachter-App (BUG-108)
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, f.n).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(send))
        context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    m: ChatMessage, s: ChatSession, group: Boolean, aiPersona: AiPersona?,
    onReply: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, onOpenNote: (Long) -> Unit, onOpenFile: (ChatFile) -> Unit,
    onUnverified: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 2.1: bestaetigte KI-Antwort - links mit Profilbild + Name der KI, "gefragt von …" (auch die eigene)
    val isAi = m.ai == AiReplyCheck.OK
    val mineSide = m.mine && !isAi
    val bg = if (isAi) MaterialTheme.colorScheme.tertiaryContainer else ChatUi.bubble(MaterialTheme.colorScheme, mineSide)
    val fg = when {
        isAi -> MaterialTheme.colorScheme.onTertiaryContainer
        mineSide -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val time = remember(m.ts) {
        Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (mineSide) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isAi) {
            Box(Modifier.padding(end = 6.dp, bottom = 2.dp)) { AiAvatar(aiPersona, 28) }
        } else if (group && !m.mine) {
            // 2.1: Profilbild des Absenders (Gruppen-Chats)
            Box(Modifier.padding(end = 6.dp, bottom = 2.dp)) {
                io.github.yggdrasil_sh.saga.ui.notifications.Avatar(m.sender?.name.orEmpty(), size = 28, avatar = m.sender?.avatar)
            }
        }
        Box {
            Surface(
                color = bg, contentColor = fg,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mineSide) 18.dp else 4.dp, bottomEnd = if (mineSide) 4.dp else 18.dp),
                // voll deckend - auch bei "Echtheit nicht bestätigt" (kein Abdunkeln des Inhalts)
                modifier = Modifier.widthIn(max = 520.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.chat_message_actions)),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (isAi) {
                        val name = aiPersona?.name ?: m.content?.aiName ?: stringResource(R.string.ai_title)
                        val by = if (m.mine) stringResource(R.string.chat_ai_asked_by_you)
                        else stringResource(R.string.chat_ai_asked_by, m.sender?.name?.ifBlank { null } ?: m.sender?.username.orEmpty())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                            Text(" · $by", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else if (group && !m.mine) {
                        Text(m.sender?.name.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (m.ai == AiReplyCheck.BAD) {
                        // gibt sich als KI-Antwort aus, Beleg fehlt/passt nicht
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = ChatUi.unverifiedIcon(MaterialTheme.colorScheme, mineSide))
                            Text(
                                stringResource(R.string.chat_ai_unconfirmed), style = MaterialTheme.typography.labelMedium,
                                color = ChatUi.unverifiedText(MaterialTheme.colorScheme, mineSide), modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    if (!m.verified) {
                        // Symbol in Warnfarbe, Text in Sekundaerfarbe (>= 4,5:1), antippbar -> Erklaerung + "Sicherheitsnummer prüfen"
                        val scheme = MaterialTheme.colorScheme
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onUnverified)
                                .heightIn(min = 32.dp).padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = ChatUi.unverifiedIcon(scheme, mineSide))
                            Text(
                                stringResource(R.string.chat_unverified) + " ⓘ", style = MaterialTheme.typography.labelMedium,
                                color = ChatUi.unverifiedText(scheme, mineSide), modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    m.replyTo?.let { rid ->
                        val q = s.messages.firstOrNull { it.id == rid }
                        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(
                                    if (q?.ai == AiReplyCheck.OK) aiPersona?.name ?: q.content?.aiName.orEmpty() else q?.sender?.name.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                )
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
                        c.files.forEach { f -> Attachment(f, s, onOpenFile) }
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

/**
 * Bilder (2.1-c): im Verlauf nur das Vorschaubild, Platz sofort in richtiger Groesse (w/h, kein Springen); geladen nur,
 * solange die Nachricht sichtbar ist (Abbruch beim Wegscrollen). Antippen oeffnet das Original. Sonst Datei mit
 * Name/Groesse zum Oeffnen/Teilen.
 */
@Composable
private fun Attachment(f: ChatFile, s: ChatSession, onOpen: (ChatFile) -> Unit) {
    if (f.isImage) {
        var img by remember(f.id) { mutableStateOf(s.cachedThumb(f)) }
        var failed by remember(f.id) { mutableStateOf(false) }
        if (img == null && !failed) {
            LaunchedEffect(f.id) {
                img = try {
                    s.thumb(f)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                failed = img == null
            }
        }
        val loaded = img
        if (!failed) {
            // Groesse: aus w/h der Nachricht (bzw. des Vorschaubilds), sonst aus dem geladenen Bild, sonst Platzhalter
            val dims = io.github.yggdrasil_sh.saga.chat.ChatImages.displaySize(f.w ?: f.th?.w, f.h ?: f.th?.h)
                ?: loaded?.let { io.github.yggdrasil_sh.saga.chat.ChatImages.displaySize(it.width, it.height) }
                ?: (200 to 150)
            Box(
                Modifier.padding(vertical = 4.dp).size(dims.first.dp, dims.second.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { onOpen(f) },
                contentAlignment = Alignment.Center,
            ) {
                if (loaded != null) Image(loaded, f.n, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            return
        }
    }
    TextButton(onClick = { onOpen(f) }) {
        Text("📎 " + f.n + " · " + android.text.format.Formatter.formatShortFileSize(LocalContext.current, f.s), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** Laufende Antwort der KI (nur auf dem Geraet des Fragenden): "{name} denkt nach …" bzw. mitlaufender Text */
@Composable
private fun AiPendingBubble(persona: AiPersona?, name: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.padding(end = 6.dp, bottom = 2.dp)) { AiAvatar(persona, 28) }
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                if (text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            io.github.yggdrasil_sh.saga.ui.ai.thinkingText(persona), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                } else {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Anruf-Eintrag (2.1): mittig, Richtung, Ergebnis, Uhrzeit; verpasst -> "Zurückrufen" */
@Composable
private fun CallEntryRow(
    m: ChatMessage, e: io.github.yggdrasil_sh.saga.call.CallEntry, me: String, canCall: Boolean, onCall: (Boolean) -> Unit,
) {
    val missed = io.github.yggdrasil_sh.saga.call.CallLogic.missed(e, me)
    val time = remember(m.ts) {
        Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    val text = io.github.yggdrasil_sh.saga.call.CallLogic.entryText(e, me).resolve()
    val color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            Row(
                Modifier.padding(start = 12.dp, end = 4.dp).heightIn(min = 40.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (e.media == "video") R.drawable.ic_videocam else R.drawable.ic_call), null,
                    Modifier.size(16.dp), tint = color,
                )
                Text(
                    (if (e.from == me) "↗ " else "↙ ") + text + " · " + time, style = MaterialTheme.typography.labelMedium, color = color,
                    modifier = Modifier.padding(start = 6.dp, end = 8.dp),
                )
                if (missed && canCall) {
                    TextButton(onClick = { onCall(e.media == "video") }) { Text(stringResource(R.string.call_back)) }
                }
            }
        }
    }
}

/** Datumstrenner im Chat: "Heute", "Gestern", sonst kurzes Datum mit Wochentag */
@Composable
private fun ChatDayDivider(d: java.time.LocalDate) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
            Text(
                // "heute"/"gestern" stehen klein (fuer Faelligkeiten im Satz) - als Ueberschrift gross
                io.github.yggdrasil_sh.saga.ui.common.dueText(d.toString()).replaceFirstChar { it.titlecase(io.github.yggdrasil_sh.saga.ui.common.appLocale()) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp).semantics { heading() },
            )
        }
    }
}
