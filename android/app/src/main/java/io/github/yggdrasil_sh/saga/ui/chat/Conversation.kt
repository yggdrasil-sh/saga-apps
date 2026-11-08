package io.github.yggdrasil_sh.saga.ui.chat

import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import io.github.yggdrasil_sh.saga.chat.SwipeReply
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.activity.compose.BackHandler
import io.github.yggdrasil_sh.saga.chat.ChatSelect
import io.github.yggdrasil_sh.saga.chat.ChatMedia
import io.github.yggdrasil_sh.saga.chat.X3Hint
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.ui.theme.DarkAccent
import io.github.yggdrasil_sh.saga.ui.theme.LightAccent
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import io.github.yggdrasil_sh.saga.ui.theme.SagaAccent
import androidx.core.content.FileProvider
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.GroupInfo
import io.github.yggdrasil_sh.saga.ai.AiChatLogic
import io.github.yggdrasil_sh.saga.ai.AiPersona
import io.github.yggdrasil_sh.saga.ai.AiPersonaLogic
import io.github.yggdrasil_sh.saga.ai.AiReplyCheck
import io.github.yggdrasil_sh.saga.ui.ai.AiAvatar
import androidx.compose.ui.semantics.contentDescription
import io.github.yggdrasil_sh.saga.chat.ChatAttach
import androidx.core.net.toUri
import io.github.yggdrasil_sh.saga.chat.video.Videos
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
import kotlinx.coroutines.flow.collectLatest
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
import io.github.yggdrasil_sh.saga.chat.ChatStd
import io.github.yggdrasil_sh.saga.chat.ChatLogic
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
    /** 2.4: gewaehlte Videos (je Video eine eigene Nachricht, gesendet im Hintergrund) */
    val videos = remember(chatId) { mutableStateListOf<PendingVideo>() }
    val videoHost = remember(chatId) { VideoPlayerHost(context, repo, scope) }
    DisposableEffect(videoHost) { onDispose { videoHost.release() } }
    // 4.11.: dieser Chat ist sichtbar - seine Mitteilungen nicht anzeigen, solange die App vorne ist
    DisposableEffect(chatId) {
        svc.visibleChat = chatId
        onDispose { if (svc.visibleChat == chatId) svc.visibleChat = null }
    }
    val noteIds = remember(chatId) { mutableStateListOf<ChatNoteRef>() }
    // TextFieldState (statt String): nur damit gehen Bilder aus Zwischenablage/Tastatur/Ziehen (contentReceiver)
    val input = rememberSaveable(chatId, saver = TextFieldState.Saver) { TextFieldState() }   // ueberlebt Prozess-Tod (BUG-067)
    LaunchedEffect(input) {
        snapshotFlow { input.text.toString() }.drop(1).collect { if (it.isNotBlank()) scope.launch { s.typingNow() } }
    }
    // A09: Entwurf je Chat merken (auch offline geschrieben und beim Verlassen) und beim Oeffnen zurueckholen
    LaunchedEffect(input, chatId) {
        if (input.text.isEmpty()) withContext(Dispatchers.IO) { svc.store.draft(chatId) }?.let { if (input.text.isEmpty()) input.setTextAndPlaceCursorAtEnd(it) }
        snapshotFlow { input.text.toString() }.drop(1).collectLatest { t ->
            delay(400)
            if (s.editing == null) withContext(Dispatchers.IO) { svc.store.saveDraft(chatId, io.github.yggdrasil_sh.saga.chat.ChatOffline.draftToStore(t)) }
        }
    }
    DisposableEffect(input, chatId) {
        onDispose { if (s.editing == null) svc.store.saveDraft(chatId, io.github.yggdrasil_sh.saga.chat.ChatOffline.draftToStore(input.text.toString())) }
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
    /** angezeigte Nachrichten (neueste zuerst) - fuer den Sprung aus der Pin-Leiste */
    val shownRef = remember(chatId) { java.util.concurrent.atomic.AtomicReference<List<ChatMessage>>(emptyList()) }
    val unknownName = stringResource(R.string.std_unknown)

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
    // CH-24: einmal erklaeren und nach Mitteilungen fragen - erst in einem Chat mit Nachrichten (bzw. nach dem ersten Senden)
    io.github.yggdrasil_sh.saga.ui.common.NotificationPermissionPrompt(
        ready = io.github.yggdrasil_sh.saga.ui.common.NotifPrompt.moment(s.opened, s.messages.isNotEmpty()),
    )
    // CH-07: letzte Nachricht (schon entschluesselt) als Vorschau fuer die Chat-Liste merken
    val pageWord = stringResource(R.string.r5_pv_page)
    LaunchedEffect(s) {
        snapshotFlow {
            if (!s.opened) null
            else Triple(s.messages.lastOrNull { io.github.yggdrasil_sh.saga.chat.ListPreviewLogic.shows(it) }, s.lastSeq, s.summary?.kind)
        }.collectLatest { st ->
            if (st == null) return@collectLatest
            delay(300)
            val p = io.github.yggdrasil_sh.saga.chat.ListPreviewLogic.of(
                s.messages.toList(), s.me, st.second, st.third != null && st.third != "dm", { senderName(it) },
                { id -> s.notes[id]?.title }, pageWord,
            )
            quietly { svc.rememberListPreview(chatId, p) }
        }
    }
    // eine Ausnahme hier wuerde den Prozess beenden (BUG-003): jedes Ereignis einzeln abfangen
    LaunchedEffect(s) { repo.live.events.collect { ev -> quietly { s.onEvent(ev) } } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(s) {
        var tick = 0
        while (true) {
            delay(1_000)
            s.expireTyping()
            // gescheitertes Nachladen wiederholen; Zustell-Abgleich (2.3) nur bei sichtbarem Chat, nicht im Hintergrund
            if (++tick % 5 == 0) s.catchUp(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            if (s.receiptDue()) quietly { s.markRead() }         // 2.4: Gelesen-Bestaetigung nachziehen (gebuendelt, >= 2 s)
        }
    }
    // 2.4: Gelesen erst, wenn die Nachricht wirklich zu sehen war und die App im Vordergrund ist (RESUMED)
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(s, resumed) {
        s.foreground = resumed
        if (resumed) snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }.collect { ids ->
            s.seen(ids)
            if (s.receiptDue()) quietly { s.markRead() }
        }
    }
    DisposableEffect(s) { onDispose { s.foreground = false } }
    // 2.4: eigene Einstellung geaendert (hier oder auf einem anderen Geraet): Bestaetigungen neu holen bzw. verwerfen
    val privacyRev by svc.privacyRev.collectAsState()
    LaunchedEffect(s, privacyRev) { if (privacyRev > 0) quietly { s.loadReceipts() } }
    // 2.4: Online-Status des Gegenuebers (nur Direkt-Chat, nur solange sichtbar)
    val chatMe by svc.me.collectAsState()
    val presenceAll by svc.presence.state.collectAsState()
    val peerOnline = io.github.yggdrasil_sh.saga.chat.ReceiptLogic.onlineFor(chatMe, s.summary)
    PresenceWatch(svc.presence, "chat:$chatId", listOfNotNull(peerOnline))
    val presenceLine = presenceText(peerOnline?.let { presenceAll[it] }, chatMe?.statusLimits?.lastRound ?: 300)
    var readBy by remember { mutableStateOf<ChatMessage?>(null) }
    // 2.5 Chat-Standard: Melden, Sperren, Admin-Loeschen
    var reportMsgs by remember { mutableStateOf<List<ChatMessage>?>(null) }
    var blockAsk by remember { mutableStateOf<Pair<String, String>?>(null) }
    var modDel by remember { mutableStateOf<ChatMessage?>(null) }
    val std = svc.std
    // 2.5 (chatmention1): gewaehlte Erwaehnungen "@Name" -> Kennung; (unfurl1) Linkvorschau beim Tippen
    val people = remember(chatId) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var unfurl by remember(chatId) { mutableStateOf<io.github.yggdrasil_sh.saga.chat.UnfurlResult?>(null) }
    /** Adresse im Text, fuer die [unfurl] geholt wurde (der Server kann sie normalisieren) */
    var unfurlFor by remember(chatId) { mutableStateOf<String?>(null) }
    val unfurlOff = remember(chatId) { mutableSetOf<String>() }
    var showMedia by remember { mutableStateOf(false) }
    // 2.5 Medien 1: Bildbetrachter in der App (Nachricht, Anhang des angetippten Bilds)
    var viewer by remember(chatId) { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(input, chatId) {
        snapshotFlow { input.text.toString() }.collectLatest { t ->
            if (!std.unfurl || !svc.unfurlOn) { unfurl = null; return@collectLatest }
            val u = ChatStd.firstUrl(t)
            if (u == null || u in unfurlOff) { unfurl = null; unfurlFor = null; return@collectLatest }
            if (unfurlFor == u && unfurl != null) return@collectLatest
            delay(600)
            unfurlFor = u
            unfurl = try {
                svc.unfurl(u)?.let { r -> r.copy(url = r.url.ifEmpty { u }) }?.takeIf { ChatStd.firstUrl(input.text.toString()) == u }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    // 2.5: Reaktionen (volle Emoji-Auswahl, "Reaktionen von …") und Sprachnachrichten (Aufnahme, Vorschau)
    var emojiFor by remember { mutableStateOf<ChatMessage?>(null) }
    var reactionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    // 25.10.: Mehrfachauswahl (langer Druck bzw. Menue "Auswählen"; Antippen waehlt weitere, Zurueck/× beendet)
    var selecting by remember(chatId) { mutableStateOf(false) }
    var selected by remember(chatId) { mutableStateOf<Set<String>>(emptySet()) }
    var selDialog by remember(chatId) { mutableStateOf<String?>(null) }
    var forwardTo by remember(chatId) { mutableStateOf<Pair<io.github.yggdrasil_sh.saga.chat.ChatSummary, String>?>(null) }
    var forwarding by remember(chatId) { mutableStateOf(false) }
    var pageTarget by remember(chatId) { mutableStateOf<ChatNoteRef?>(null) }
    val endSelection = { selecting = false; selected = emptySet(); selDialog = null }
    BackHandler(enabled = selecting) { endSelection() }
    LaunchedEffect(s) { quietly { s.loadHiddenLocal() } }
    LaunchedEffect(s.messages.size) { if (selected.isNotEmpty()) selected = ChatSelect.prune(selected, s.messages.toList()) }
    val voiceRec = remember(chatId) { VoiceRecUi(scope) { repo.notify(it) } }
    LaunchedEffect(Unit) {
        io.github.yggdrasil_sh.saga.chat.voice.VoicePlayer.init(context)
        svc.loadRecentEmojis()
    }
    DisposableEffect(voiceRec) { onDispose { if (voiceRec.phase == VoiceRecUi.Phase.HOLD || voiceRec.phase == VoiceRecUi.Phase.LOCKED) voiceRec.cancel() } }
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

    // Grenzen fuer Anhaenge in diesem Chat (v1 ueber Servergrenzen hoechstens 10 MiB, 2.5: grosse Dateien per v2)
    fun attachLimits() = AttachLimits(svc.me.value?.limits ?: ChatLimits(), s.summary?.remote == true, s.summary?.bigFiles)
    fun addAttachment(r: ChatAttach.Read) {
        when (r) {
            is ChatAttach.Read.Ok -> files.add(r.file)
            is ChatAttach.Read.Video -> videos.add(PendingVideo(r.uri, r.name, r.mime, r.size))
            is ChatAttach.Read.Big -> videos.add(PendingVideo(r.uri, r.name, r.mime, r.size, video = false))
            is ChatAttach.Read.TooBig -> repo.notify(UiText.Res(R.string.chat_file_too_big, r.maxMb.toInt()))
            ChatAttach.Read.Failed -> repo.notify(UiText.Res(R.string.chat_file_broken))
            ChatAttach.Read.Empty -> repo.notify(UiText.Res(R.string.chat_file_empty))
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch {
            for (u in uris) addAttachment(readFile(context, u, attachLimits(), s.name))
        }
    }

    // 2.5: Kamera direkt im Chat (Foto/Video) -> wie ein Bild- bzw. Video-Anhang; Dateien im Cache (camera/), alte weg
    var camUri by remember { mutableStateOf<Uri?>(null) }
    var camVideo by remember { mutableStateOf(false) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = camUri
        if (ok && u != null) scope.launch {
            addAttachment(readFile(context, u, attachLimits(), s.name))
        }
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val u = camUri
        if (ok && u != null) {
            val f = java.io.File(java.io.File(context.cacheDir, "camera"), u.lastPathSegment ?: "")
            videos.add(PendingVideo(u.toString(), ChatAttach.niceName(f.name, "video/mp4", s.name, java.time.LocalDateTime.now()), "video/mp4", f.length().takeIf { it > 0 }))
        }
    }
    fun launchCamera(video: Boolean) {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        val f = java.io.File(dir, (if (video) "video-" else "photo-") + System.currentTimeMillis() + if (video) ".mp4" else ".jpg")
        val u = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
        camUri = u
        if (video) takeVideo.launch(u) else takePhoto.launch(u)
    }
    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera(camVideo) else repo.notify(UiText.Res(R.string.std_camera_denied))
    }
    val openCamera: (Boolean) -> Unit = { video ->
        camVideo = video
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera(video)
        else camPermission.launch(android.Manifest.permission.CAMERA)
    }

    // CH-11: Galerie - Fotos und Videos zusammen (Android-Fotoauswahl)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        scope.launch {
            for (u in uris) addAttachment(readFile(context, u, attachLimits(), s.name))
        }
    }
    // Wiedergabe anhalten, wenn die App in den Hintergrund geht
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, videoHost) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                videoHost.pause()
                // 2.5: Mikrofon nie im Hintergrund - laufende Aufnahme beenden (bleibt als Vorschau)
                if (voiceRec.phase == VoiceRecUi.Phase.HOLD || voiceRec.phase == VoiceRecUi.Phase.LOCKED) voiceRec.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Anrufe (2.1): Knoepfe im Direkt-Chat, wenn der Server Anrufe kann; vorher Mikrofon (+ Kamera) erlauben lassen
    val callsOn by repo.calls.enabled.collectAsState()
    LaunchedEffect(Unit) { if (repo.calls.enabled.value == null) repo.calls.checkEnabled() }
    var callVideo by remember { mutableStateOf<Boolean?>(null) }
    fun callNow(video: Boolean) {
        // 2.4 E5: Gruppen-, freie und Seiten-Chats - Gruppenanruf beginnen bzw. dem laufenden beitreten
        if (s.summary?.kind != "dm") {
            repo.calls.startGroup(chatId, s.name, video, s.summary?.call?.running)
            return
        }
        val p = s.summary?.peer ?: return
        repo.calls.start(chatId, io.github.yggdrasil_sh.saga.call.CallPeer(p.username, p.name, p.avatar, p.handle.takeIf { s.isFed }, p.host), video)
    }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val v = callVideo ?: return@rememberLauncherForActivityResult
        callVideo = null
        // erlaubt -> Mitteilungen auf diesem Geraet einschalten (wie nach der Erklaerung)
        if (res[android.Manifest.permission.POST_NOTIFICATIONS] == true) repo.launchAction { repo.push.autoEnable() }
        if (io.github.yggdrasil_sh.saga.call.CallPermissions.missing(context, false).contains(android.Manifest.permission.RECORD_AUDIO)) {
            repo.notify(UiText.Res(R.string.call_mic_denied))
        } else {
            callNow(v && !io.github.yggdrasil_sh.saga.call.CallPermissions.missing(context, true).contains(android.Manifest.permission.CAMERA))
        }
    }
    val callSt by repo.calls.ui.collectAsState()
    val startCall: (Boolean) -> Unit = start@{ video ->
        // laeuft hier schon ein Anruf: Anruf-Knoepfe fuehren zu ihm zurueck statt "telefonierst schon"
        if (io.github.yggdrasil_sh.saga.call.CallReturn.dial(repo.calls.ui.value, chatId) == io.github.yggdrasil_sh.saga.call.CallReturn.Dial.RETURN) {
            io.github.yggdrasil_sh.saga.call.CallActivity.open(context)
            return@start
        }
        // Pruefer R5: Mitteilungen beim ersten eigenen Anruf mit erfragen (nur einmal, wie die Erklaerung; haelt nicht auf)
        val notifPrompt = io.github.yggdrasil_sh.saga.ui.common.NotifPrompt
        val askNotif = notifPrompt.forCall(android.os.Build.VERSION.SDK_INT, notifPrompt.granted(context), notifPrompt.askedBefore(context))
        val need = io.github.yggdrasil_sh.saga.call.CallPermissions.missing(context, video, notifications = askNotif)
        if (android.Manifest.permission.POST_NOTIFICATIONS in need) notifPrompt.markAsked(context)
        if (need.isEmpty()) callNow(video) else {
            callVideo = video
            callPerms.launch(need.toTypedArray())
        }
    }
    // 2.3: aus Chats ueber Servergrenzen nur, wenn angenommen und nicht schreibgeschuetzt (Anfragen klingeln nie)
    val fedReq = s.isFed && s.summary?.request == true
    val fedRo = s.isFed && s.summary?.readonly == true && !fedReq
    val canCall = callsOn == true && s.summary?.kind == "dm" && s.summary?.peer != null && !fedReq && !fedRo
    // 2.4 E5: Gruppenanruf-Knopf nur, wenn der Server ihn fuer diesen Chat anbietet (`chat.call.group`, foederiert: alle calls3)
    val canGroupCall = callsOn == true && s.summary?.kind != "dm" && s.summary?.call?.group == true && !fedReq && !fedRo

    // 2.5 Medien 1: Bild in der Galerie speichern (Langdruck-Menue)
    val saveImage = rememberImageSaver(repo, s)

    // Surface: Inhaltsfarbe onSurface fuer alle Symbole ohne eigene Farbe ("+" im Dunkelmodus war schwarz, BUG-072)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    // TT 52: auch den Bereich der Home-Geste freihalten (sonst startet "nach oben sperren" am Mikrofon die Home-Geste)
    Column(Modifier.fillMaxSize().windowInsetsPadding(io.github.yggdrasil_sh.saga.ui.common.SafeBottom.insets)) {
        // zweizeiliger Titel: Kopfzeile waechst mit der Schriftgroesse (200 %: sonst unter der Statusleiste, BUG-070)
        val fontScale = LocalDensity.current.fontScale
        if (selecting) SelectionTopBar(
            selected.size, onClose = endSelection,
            onCopy = { selDialog = "copy" }, onForward = { selDialog = "forward" }, onToPage = { selDialog = "page" }, onDelete = { selDialog = "delete" },
        ) else TopAppBar(
            expandedHeight = maxOf(TopAppBarDefaults.TopAppBarExpandedHeight, (46 * fontScale).dp),
            title = {
                // 2.5 (chatadm1): Tipp auf den Chat-Kopf -> Gruppeninfo (Direkt-Chats: Mitglieder wie bisher)
                val infoLabel = stringResource(R.string.gi_open)
                // CH-10: Direkt-Chats (und aeltere Server) -> Chat-Info, Gruppen mit chatadm1 -> Gruppeninfo
                Row(Modifier.clickable(onClickLabel = infoLabel) {
                    dialog = if (GroupInfo.usesGroupInfo(s.summary, svc.groupInfoOn)) "groupinfo" else "chatinfo"
                }, verticalAlignment = Alignment.CenterVertically) {
                // 2.5 (groupinfo2): Gruppenbild im Kopf
                val headSum = s.summary
                if (headSum != null && headSum.kind == "free" && std.groupInfo2 && svc.groupExtras.collectAsState().value[headSum.id]?.avatar != null) {
                    Box(Modifier.padding(end = 8.dp)) { GroupAvatar(repo, headSum, s.name, 32.dp) }
                }
                Column {
                    Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Nr. 54 / CH-09 / A11Y-30: mit Online-Stand steht nur "online" bzw. "zuletzt online ..." plus Schloss; der Text
                    // "Ende-zu-Ende-verschluesselt" steckt in der Beschreibung fuer Bedienungshilfen (fedHeadLine ohne E2E ist dann leer,
                    // ausser bei Server-Herkunft/Anfrage/schreibgeschuetzt)
                    val full = (presenceLine?.let { "$it · " } ?: "") + fedHeadLine(s)
                    val shown = presenceLine?.let { pl -> fedHeadLine(s, withE2e = false).let { r -> if (r.isEmpty()) pl else "$pl · $r" } } ?: fedHeadLine(s)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = full }) {
                        SagaIcon(SagaIcons.lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        // eine Zeile: bei 200 % Schrift sonst hoeher als die Kopfzeile (ragt in die Statusleiste, BUG-070)
                        Text(
                            shown, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                }
            },
            navigationIcon = {
                if (!twoPane && nav.canGoBack) IconButton(onClick = { nav.pop() }) { SagaIcon(SagaIcons.arrowLeft, stringResource(R.string.action_back)) }
            },
            actions = {
                if (canCall) {
                    IconButton(onClick = { startCall(false) }) { SagaIcon(SagaIcons.phone, stringResource(R.string.call_audio)) }
                    IconButton(onClick = { startCall(true) }) { SagaIcon(SagaIcons.video, stringResource(R.string.call_video)) }
                } else if (canGroupCall) {
                    val running = s.summary?.call?.running?.takeIf { it.kind == "group" && it.state != "ended" } != null
                    IconButton(onClick = { startCall(false) }) {
                        SagaIcon(SagaIcons.phone, stringResource(if (running) R.string.room_join_audio else R.string.room_group_audio))
                    }
                    IconButton(onClick = { startCall(true) }) {
                        SagaIcon(SagaIcons.video, stringResource(if (running) R.string.room_join_video else R.string.room_group_video))
                    }
                }
                // 25.10.: warum (noch) keine Reaktionen/kein Bearbeiten (x3_why) - dezentes ⓘ mit Popover
                X3Hint.of(s.summary)?.let { h -> X3InfoButton(h) { nav.push(Dest.ChatDevices) } }
                IconButton(onClick = { search = if (search == null) "" else null }) { SagaIcon(SagaIcons.search, stringResource(R.string.chat_search)) }
                Box {
                    IconButton(onClick = { menu = true }) { SagaIcon(SagaIcons.moreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        // CH-10: hoechstens 6 Eintraege mit Linien-Symbolen, Trenner vor destruktiven; Seltenes in der Chat-Info
                        val sum = s.summary
                        val aiOk = aiServer && s.aiMayToggle == true && sum != null
                        val items = io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.items(
                            io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.Flags(free = sum?.kind == "free", ai = aiOk, canArchive = sum != null && s.canArchive),
                        )
                        val red = MaterialTheme.colorScheme.error
                        items.forEachIndexed { i, item ->
                            if (io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.dividerBefore(items, i)) HorizontalDivider()
                            when (item) {
                                io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.Item.INFO -> DropdownMenuItem(
                                    text = { Text(stringResource(if (GroupInfo.usesGroupInfo(sum, svc.groupInfoOn)) R.string.gi_menu else R.string.r5_info_menu)) },
                                    leadingIcon = { SagaIcon(SagaIcons.info, null) },
                                    onClick = { menu = false; dialog = if (GroupInfo.usesGroupInfo(sum, svc.groupInfoOn)) "groupinfo" else "chatinfo" },
                                )
                                io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.Item.SELECT -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cs_select)) }, leadingIcon = { SagaIcon(SagaIcons.checkbox, null) },
                                    onClick = { menu = false; selecting = true },
                                )
                                io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.Item.MUTE -> if (sum != null) {
                                    if (std.mute2) AlltagMenuItems(repo, sum, onPatched = { c -> s.applySummary(c) }, muteOnly = true) { menu = false }
                                    else DropdownMenuItem(
                                        text = { Text(stringResource(if (sum.muted) R.string.chat_unmute else R.string.chat_mute)) },
                                        leadingIcon = { SagaIcon(if (sum.muted) SagaIcons.bell else SagaIcons.bellOff, null) },
                                        onClick = { menu = false; repo.launchAction { s.setMuted(!sum.muted) } },
                                    )
                                }
                                // KI im Chat (2.1): nur fuer Berechtigte; Erlauben mit Rueckfrage (was an die KI geht)
                                io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.Item.AI -> if (sum != null) DropdownMenuItem(
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
                                // 2.1: Archiv und Loeschen (nur wenn der Server es kann)
                                io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.Item.ARCHIVE -> if (sum != null) {
                                    val arch = sum.archived == true
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (arch) R.string.chatdel_unarchive else R.string.chatdel_archive)) },
                                        leadingIcon = { Icon(painterResource(if (arch) SagaIcons.archiveOut else SagaIcons.archive), null) },
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
                                }
                                io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.Item.LEAVE -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_leave), color = red) },
                                    leadingIcon = { SagaIcon(SagaIcons.logout, null, tint = red) },
                                    onClick = { menu = false; dialog = "leave" },
                                )
                                io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.Item.DELETE -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chatdel_delete), color = red) },
                                    leadingIcon = { SagaIcon(SagaIcons.trash, null, tint = red) },
                                    onClick = { menu = false; dialog = "delete" },
                                )
                            }
                        }
                    }
                }
            },
        )
        // Laeuft der Anruf in diesem Chat: zurueck in den Anrufbildschirm (derselbe Anruf, kein neuer Aufbau)
        io.github.yggdrasil_sh.saga.call.ReturnToCallRow(callSt, chatId)
        // 2.5 (msgpin1): angeheftete Nachrichten oben; Tipp springt hin
        if (std.msgPin) {
            val pinRights = s.summary?.let { ChatStd.rights(it, std) }
            PinBar(
                s, pinRights?.pin == true,
                onJump = { mid ->
                    val idx = shownRef.get().indexOfFirst { it.id == mid }
                    if (idx >= 0) scope.launch { list.animateScrollToItem(idx + (if (s.aiPending != null) 1 else 0)) }
                    else repo.notify(UiText.Res(R.string.std_pin_not_loaded))
                },
                onUnpin = { mid -> repo.launchAction { s.pin(mid, false); repo.notify(UiText.Res(R.string.std_unpinned_done)) } },
                nameOf = { senderName(it) },
                textOf = { m -> previewText(m, s, unknownName) },
            )
        }
        if (forwarding) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        search?.let { q ->
            OutlinedTextField(
                value = q, onValueChange = { search = it }, singleLine = true, placeholder = { Text(stringResource(R.string.chat_search_hint)) },
                trailingIcon = { IconButton(onClick = { search = null }) { SagaIcon(SagaIcons.x, stringResource(R.string.action_close)) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        // Sicherheitsnummer eines Gegenuebers hat sich geaendert (TOFU)
        s.identChanged.firstOrNull()?.let { uid ->
            val who = s.summary?.members?.firstOrNull { it.username == uid }?.name ?: uid
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SagaIcon(SagaIcons.warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        stringResource(R.string.chat_safety_changed, who), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    SagaTextButton(onClick = { dialog = "safety" }) { Text(stringResource(R.string.chat_show), color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
        }
        // 2.3: Personen anderer Server - Schluessel gewechselt (Warnung, "Verstanden"), ueberprueft und gewechselt
        // (Blockade bis zum erneuten Vergleich) bzw. ungueltig. Senden und Anrufen sind dann gesperrt.
        s.fedIssues.forEach { (p, st) -> FedTrustBanner(p, st, onSafety = { safetyFor = p.username; dialog = "safety" }) { repo.launchAction { s.trustFed(p, false) } } }
        // KI erlaubt (2.1): sichtbarer Hinweis, was an die KI geht
        if (aiHere) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
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

        val shown = remember(s.messages.toList(), search, s.hiddenLocal) {
            val q = search?.trim()?.lowercase().orEmpty()
            val hid = s.hiddenLocal
            s.messages.toList().filter { it.id !in hid && (q.isEmpty() || it.content?.text?.lowercase()?.contains(q) == true) }.asReversed()
        }
        shownRef.set(shown)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
          // CH-01: Blasenbreite aus der Verlaufsbreite (hoechstens 900 dp), einmal fuer alle Zeilen
          val bubbleMax = ChatUi.bubbleMax(minOf(maxWidth, 900.dp))
          androidx.compose.runtime.CompositionLocalProvider(LocalVideoHost provides videoHost, LocalBubbleMax provides bubbleMax) {
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
                    val sys = m.sys
                    if (m.tomb) {
                        // 2.5 (moddel1): Grabstein
                        val who = senderName(m)
                        StdNoteRow(if (who.isNotEmpty()) stringResource(R.string.std_tomb_of, who) else stringResource(R.string.std_tomb), icon = SagaIcons.trash)
                    } else if (!m.mine && std.block && m.id !in s.unblocked && ChatStd.blockedInGroup(s.summary, m.sender, m.senderHandle)) {
                        // 2.5 (block2): Nachricht einer gesperrten Person eingeklappt
                        StdNoteRow(stringResource(R.string.std_blocked_msg), stringResource(R.string.std_show), icon = SagaIcons.ban) { s.unblocked = s.unblocked + m.id }
                    } else if (sys != null) {
                        SysRow(m, sys, s.summary?.myHandle)
                    } else if (call != null) {
                        CallEntryRow(m, call, s.me, canCall) { startCall(it) }
                    } else {
                        // Design 2: Folgen derselben Person enger und mit passenden Ecken (Liste neueste zuerst)
                        val runOf = { o: io.github.yggdrasil_sh.saga.chat.ChatMessage? ->
                            o != null && o.sys == null && o.call == null && o.ai == m.ai &&
                                ChatDesign.sameRun(m.sender?.username, m.mine, m.ts, o.sender?.username, o.mine, o.ts)
                        }
                        val older = shown.getOrNull(i + 1)
                        val dayStart = io.github.yggdrasil_sh.saga.chat.ChatFormat.dayStarts(m.ts, older?.ts, ZoneId.systemDefault())
                        Bubble(
                            m, s, group = s.summary?.kind != "dm", aiPersona = aiPersona,
                            first = dayStart || !runOf(older), last = !runOf(shown.getOrNull(i - 1)),
                            onReply = { s.replyTo = m },
                            onCopy = { copyText(context, m.content?.text.orEmpty()) },
                            onDelete = { repo.launchAction { s.delete(m) } },
                            onOpenNote = { id -> nav.push(Dest.Page(id)) },
                            // 2.5 Medien 1: Bilder im eigenen Betrachter, andere Dateien wie bisher mit einer anderen App
                            onOpenFile = { f ->
                                if (ChatMedia.viewable(f)) {
                                    videoHost.pause()
                                    viewer = m.id to f.id
                                } else scope.launch { openFile(context, s, f) { repo.notify(it) } }
                            },
                            onShareFile = { f -> scope.launch { openFile(context, s, f, share = true) { repo.notify(it) } } },
                            onUnverified = { unverifiedOf = m.sender?.username ?: "" },
                            onResend = { repo.launchAction { s.resend(m) } },
                            tick = s.tickOf(m),
                            readAt = if (s.summary?.kind == "dm") s.readersOf(m).firstOrNull()?.second?.ts else null,
                            onReadBy = if (m.mine && s.summary?.kind != "dm" && s.receiptsOn) ({ readBy = m }) else null,
                            onReact = { e -> s.react(scope, m, e) },
                            onMoreEmoji = { emojiFor = m },
                            onShowReactions = { reactionsFor = m },
                            onEdit = {
                                s.replyTo = null
                                s.editing = m
                                input.setTextAndPlaceCursorAtEnd(m.content?.text.orEmpty())
                            },
                            selecting = selecting, selected = m.id in selected,
                            onSelect = if (ChatSelect.selectable(m)) ({
                                if (!selecting) {
                                    selecting = true
                                    selected = setOf(m.id)
                                } else selected = ChatSelect.toggle(selected, m)
                            }) else null,
                            onMenuAction = { if (selected.size <= 1) endSelection() },
                            // 2.5 Medien 1: einzelne Nachricht weiterleiten - derselbe Weg wie die Mehrfachauswahl, nur diese gewaehlt
                            onForward = if (ChatSelect.selectable(m) && !m.tomb) ({
                                selected = setOf(m.id)
                                selDialog = "forward"
                            }) else null,
                            onSaveImage = saveImage,
                            extraMenu = { close -> StdMessageMenu(repo, s, m, close, onReport = { reportMsgs = listOf(m) }, onBlock = { n, t -> blockAsk = n to t }, onModDel = { modDel = m }) },
                        )
                    }
                  }
                }
                if (s.more && search == null) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            SagaOutlinedButton(onClick = { repo.launchAction { s.loadOlder() } }, modifier = Modifier.padding(8.dp)) {
                                Text(stringResource(R.string.notifications_load_older))
                            }
                        }
                    }
                }
                if (!s.loading && s.messages.isEmpty()) {
                    item {
                        // Design 2: Leerzustand mit Gruss statt grauer Zeile
                        // CH-21: Linien-Symbol statt Emoji
                        io.github.yggdrasil_sh.saga.ui.common.SagaLineEmptyState(stringResource(R.string.d2_chat_empty_title), SagaIcons.message, text = stringResource(R.string.chat_empty))
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
          }
            if (s.loading) CircularProgressIndicator(Modifier.padding(top = 48.dp))
        }
        if (videoHost.fullscreen) VideoFullscreen(videoHost, s)
        // "{name} tippt …"
        s.typing.values.firstOrNull()?.let { (name, _) ->
            Text(
                stringResource(R.string.chat_typing, name), style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        HorizontalDivider()
        if (fedReq || fedRo) {
            s.summary?.let { sum ->
                FedStateBar(sum, fedReq) { action, scope ->
                    repo.launchAction {
                        s.answerRequest(action, scope)
                        repo.notify(UiText.Res(when (action) {
                            "accept" -> R.string.fed_accepted
                            "block" -> R.string.fed_blocked_done
                            else -> R.string.fed_declined
                        }))
                    }
                }
            }
            return@Column
        }
        // 2.5 (groupinfo2/block2): Schreiben nicht erlaubt -> Hinweis statt Eingabefeld
        s.summary?.let { ChatStd.sendBlockedText(it) }?.let { t ->
            SendBlockedBar(t)
            return@Column
        }
        // 2.5: im Eingabefeld bearbeiten ("Bearbeiten · Abbrechen")
        s.editing?.let { e ->
            EditBar(e.content?.text.orEmpty()) {
                s.editing = null
                input.clearText()
            }
        }
        s.replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(stringResource(R.string.chat_reply_to, r.sender?.let { io.github.yggdrasil_sh.saga.chat.ChatLogic.shown(it) }.orEmpty()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    // 2.5 Medien 1: ohne Text "Foto"/"Video"/Dateiname statt leer
                    Text(
                        ChatMedia.attachmentLabel(r.content, mediaLabels()) ?: previewText(r, s, unknownName),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    )
                }
                ChatMedia.quoteThumb(r.content)?.let { f -> Box(Modifier.padding(start = 8.dp)) { QuoteThumb(f, s, 40.dp) } }
                IconButton(onClick = { s.replyTo = null }) { SagaIcon(SagaIcons.x, stringResource(R.string.action_cancel)) }
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
        // 2.4: laufende Video-Uploads (Fortschritt, Abbrechen) und gewaehlte Videos
        VideoUploadsBar(repo, chatId) { scope.launch { quietly { s.catchUp() } } }
        PendingVideoChips(videos)
        // CH-12: Bilder als Vorschaureihe, Dateien und Seiten als Chip
        if (files.isNotEmpty() || noteIds.isNotEmpty()) {
            PendingAttachments(files, onRemove = { files.remove(it) }) {
                noteIds.forEach { n ->
                    InputChip(selected = false, onClick = { noteIds.remove(n) }, label = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { SagaIcon(SagaIcons.file, null, Modifier.size(16.dp)) },
                        trailingIcon = { SagaIcon(SagaIcons.x, stringResource(R.string.action_delete), Modifier.size(16.dp)) })
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
        // 2.5 (chatmention1): "@" -> Mitglieder des Chats
        if (std.mention) {
            val at = ChatStd.atQuery(input.text.toString(), input.selection.end)
            if (at != null) MentionPicker(ChatStd.mentionCandidates(s.summary, at.second)) { p ->
                val label = ChatStd.mentionLabel(p.name)
                people[label] = ChatStd.memberRef(p)
                val end = input.selection.end.coerceIn(at.first, input.text.length)
                input.edit { replace(at.first, end, "$label ") }
            }
        }
        // 2.5 (unfurl1): Linkvorschau ueber dem Eingabefeld
        unfurl?.let { u -> ComposePreview(u) { unfurlFor?.let { unfurlOff.add(it) }; unfurl = null } }
        if (voiceRec.phase == VoiceRecUi.Phase.PREVIEW) Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            VoicePreview(voiceRec) { r ->
                scope.launch {
                    try {
                        s.sendVoice(r)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        repo.notify(repo.friendly(e))
                    }
                }
            }
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
            val recording = voiceRec.phase == VoiceRecUi.Phase.HOLD || voiceRec.phase == VoiceRecUi.Phase.LOCKED
            if (!recording) Box {
                var attach by remember { mutableStateOf(false) }
                IconButton(onClick = { attach = true }) { SagaIcon(SagaIcons.plus, stringResource(R.string.chat_attach)) }
                // CH-11: Bottom Sheet mit Kachelraster (Galerie = Fotos und Videos, Kamera, Datei, Seite, ggf. Immich)
                if (attach) AttachSheet(
                    io.github.yggdrasil_sh.saga.chat.AttachLogic.tiles(
                        camera = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY),
                        immich = immichOn, clipImage = clipHasImage,
                    ),
                    onDismiss = { attach = false },
                ) { t ->
                    when (t) {
                        io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.GALLERY -> pickMedia.launch(
                            androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                        io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.CAMERA -> openCamera(false)
                        io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.VIDEO_CAMERA -> openCamera(true)
                        io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.FILE -> pick.launch("*/*")
                        io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.PAGE -> dialog = "page"
                        io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.IMMICH -> dialog = "immich"
                        io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.PASTE -> addImages(clipboardImages(context))
                    }
                }
            }
            if (recording) Box(Modifier.weight(1f)) {
                VoiceRecordingBar(
                    voiceRec.secs, voiceRec.levels, voiceRec.phase == VoiceRecUi.Phase.LOCKED, voiceRec.warned,
                    onStop = { voiceRec.stop() }, onCancel = { voiceRec.cancel() },
                )
            } else androidx.compose.material3.TextField(
                state = input,
                placeholder = {
                    Text(stringResource(when {
                        s.editing != null -> R.string.x3_edit_hint
                        io.github.yggdrasil_sh.saga.chat.PendingLogic.caption(files, videos.size) -> R.string.r5_caption_hint
                        else -> R.string.chat_message_hint
                    }))
                },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6), shape = RoundedCornerShape(SagaRadius.xl),
                // Design 2: gefuelltes Eingabefeld als Pille, ohne Unterstrich und Rahmen
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).contentReceiver(receiver),
            )
            val canSend = (input.text.isNotBlank() || files.isNotEmpty() || noteIds.isNotEmpty() || videos.isNotEmpty()) && !s.sending && fetching == 0
            // 2.5: leeres Eingabefeld -> Mikrofon (halten zum Aufnehmen), sonst Senden
            val showMic = s.canVoiceHere && s.editing == null && (recording || (input.text.isBlank() && files.isEmpty() && noteIds.isEmpty() && videos.isEmpty()))
            if (showMic) VoiceMicButton(voiceRec, s.x3Limits, notify = { repo.notify(it) }, onDenied = { micDenied = true })
            else IconButton(onClick = {
                // 2.5: Bearbeitung senden (normale v2-Nachricht kind "edit")
                s.editing?.let { e ->
                    val text = input.text.toString()
                    scope.launch {
                        try {
                            s.sendEdit(e, text)
                            s.editing = null
                            input.clearText()
                        } catch (x: CancellationException) {
                            throw x
                        } catch (x: Exception) {
                            repo.notify(UiText.Res(R.string.x3_edit_failed, repo.friendly(x)))
                        }
                    }
                    return@IconButton
                }
                // 2.3: Warnung/Blockade bei einer Person eines anderen Servers sperrt das Senden
                s.fedIssues.firstOrNull()?.let { (p, _) ->
                    repo.notify(UiText.Res(R.string.chat_safety_send_blocked, io.github.yggdrasil_sh.saga.chat.ChatLogic.shown(p)), UiText.Res(R.string.chat_show)) {
                        safetyFor = p.username
                        dialog = "safety"
                    }
                    return@IconButton
                }
                // geaenderte Sicherheitsnummer sperrt das Senden: erklaeren und direkt "Anzeigen" anbieten (BUG-108)
                s.identChanged.firstOrNull()?.let { uid ->
                    val who = s.summary?.members?.firstOrNull { it.username == uid }?.name?.ifEmpty { null } ?: uid
                    repo.notify(UiText.Res(R.string.chat_safety_send_blocked, who), UiText.Res(R.string.chat_show)) { dialog = "safety" }
                    return@IconButton
                }
                // 2.4: Videos (2.5: und grosse Dateien) vorab pruefen (Original: Grenzen jetzt schon bekannt), dann je eine eigene Nachricht
                val al = attachLimits()
                val routeOf = { v: PendingVideo -> ChatAttach.route(v.size ?: 0, al.limits, al.remote, al.bigFiles) }
                val bad = videos.firstOrNull { v ->
                    v.size != null && (!v.video || !v.optimize) && routeOf(v).let { it != ChatAttach.Route.V1 && it != ChatAttach.Route.V2 }
                }
                if (bad != null) {
                    repo.notify(if (bad.video) videoLimitText(routeOf(bad), al) else UiText.Res(R.string.chat_file_too_big, ChatAttach.limitMb(routeOf(bad), al.limits, al.remote)))
                    return@IconButton
                }
                val vs = videos.toList()
                videos.clear()
                vs.forEach { v -> Videos.enqueue(context.applicationContext, repo, chatId, v.uri.toUri(), v.name, v.mime, v.optimize, video = v.video, size = v.size) }
                val text = input.text.toString().trim()
                if (text.isEmpty() && files.isEmpty() && noteIds.isEmpty()) return@IconButton
                val fs = files.toList()
                val ns = noteIds.map { it.id }
                val refs = noteIds.toList()
                input.clearText()
                files.clear()
                noteIds.clear()
                // @Name in einem Chat mit erlaubter KI: nach dem normalen (verschluesselten) Senden die KI fragen
                val toAi = aiHere && AiChatLogic.mentions(text, aiName)
                val peopleNow = people.toMap()
                val unfNow = unfurl?.takeIf { ChatStd.firstUrl(text) == unfurlFor }
                people.clear()
                unfurl = null
                scope.launch {
                    try {
                        val qid = s.send(text, fs, ns, people = peopleNow, unfurl = unfNow)
                        if (toAi) askAi(text, qid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        input.setTextAndPlaceCursorAtEnd(text)             // nichts verlieren
                        people.putAll(peopleNow)
                        files.addAll(fs)
                        noteIds.addAll(refs)
                        // A09: ohne Verbindung verstaendlich sagen, dass der Entwurf bleibt
                        repo.notify(if (repo.isNetworkFailure(e)) UiText.Res(R.string.chat_send_offline) else repo.friendly(e))
                    }
                }
            }, enabled = canSend, colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                // Design 2: Senden als goldener Kreis (deaktiviert: ruhige Flaeche)
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ), modifier = Modifier.padding(start = 4.dp)) {
                if (s.sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else SagaIcon(SagaIcons.send, stringResource(R.string.chat_send))
            }
        }
    }
    }

    if (showMedia) MediaDialog(repo, s) { showMedia = false }
    // 2.5 Medien 1: Bildbetrachter - alle Bilder des Chats (Verlaufsreihenfolge, ohne ausgeblendete/eingeklappte)
    viewer?.let { (mid, fid) ->
        val items = remember(mid, fid) {
            ChatMedia.gallery(s.messages.toList(), s.hiddenLocal) { m ->
                !m.mine && std.block && m.id !in s.unblocked && ChatStd.blockedInGroup(s.summary, m.sender, m.senderHandle)
            }
        }
        ImageViewer(repo, s, items, ChatMedia.indexOf(items, mid, fid)) { viewer = null }
    }
    // 2.5 Chat-Standard: Melden, Sperren, Admin-Loeschen
    reportMsgs?.let { ms ->
        ReportDialog(repo, s, ms, ChatStd.reportPrefill(ms.map { senderName(it) to previewText(it, s, unknownName) })) { reportMsgs = null }
    }
    blockAsk?.let { (n, t) -> BlockConfirm(repo, n, t, onBlock = { s.block(it) }) { blockAsk = null } }
    modDel?.let { m ->
        AlertDialog(
            onDismissRequest = { modDel = null },
            title = { Text(stringResource(R.string.std_moddel_title)) },
            text = { Text(stringResource(R.string.std_moddel_text, senderName(m).ifEmpty { "?" })) },
            confirmButton = {
                SagaTextButton(onClick = { modDel = null; repo.launchAction { s.deleteAsAdmin(m) } }) {
                    Text(stringResource(R.string.std_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { SagaTextButton(onClick = { modDel = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    // 2.4: "Gelesen von" einer eigenen Nachricht (Gruppen)
    readBy?.let { m -> ReadByDialog(s.readersOf(m)) { readBy = null } }
    // 2.5: volle Emoji-Auswahl, "Reaktionen von …", Mikrofon dauerhaft abgelehnt
    emojiFor?.let { m ->
        val recent by svc.recentEmojis.collectAsState()
        EmojiPickDialog(recent, onPick = { e -> emojiFor = null; s.react(scope, m, e) }) { emojiFor = null }
    }
    reactionsFor?.let { m -> ReactionsSheet(s.chipsOf(m), s.myHandle, { s.nameOfHandle(it) }) { reactionsFor = null } }
    if (micDenied) MicDeniedDialog { micDenied = false }
    // 25.10.: Aktionen der Mehrfachauswahl
    val selLabels = ChatSelect.Labels(
        image = stringResource(R.string.cs_l_image), voice = stringResource(R.string.cs_l_voice), video = stringResource(R.string.cs_l_video),
        file = stringResource(R.string.cs_l_file).let { f -> { n: String -> f.replace("%1\$s", n) } },
    )
    val noForwardMark = stringResource(R.string.cs_att_not_forwarded)
    val selNameOf = { m: ChatMessage ->
        if (m.ai == io.github.yggdrasil_sh.saga.ai.AiReplyCheck.OK) aiName.ifEmpty { m.content?.aiName.orEmpty() }
        else m.sender?.let { io.github.yggdrasil_sh.saga.chat.ChatLogic.shown(it) }.orEmpty()
    }
    val selLoc = io.github.yggdrasil_sh.saga.ui.common.appLocale()
    val selTimeOf = { m: ChatMessage ->
        Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(selLoc))
    }
    val noteTitle = { id: Long -> s.notes[id]?.title }
    val selMsgs = ChatSelect.ordered(s.messages.toList(), selected)
    when (selDialog) {
        "copy" -> LaunchedEffect(Unit) {
            val text = ChatSelect.copyText(selMsgs, selLabels, selNameOf, selTimeOf, noteTitle)
            copyText(context, text)
            repo.notify(UiText.Plural(R.plurals.cs_copied, selMsgs.size, listOf(selMsgs.size)))
            endSelection()
        }
        "forward" -> if (selMsgs.size > ChatSelect.FORWARD_MAX) LaunchedEffect(Unit) {
            repo.notify(UiText.Res(R.string.cs_forward_max, ChatSelect.FORWARD_MAX))
            selDialog = null
        } else ForwardTargetDialog(repo, chatId, onDismiss = { selDialog = null; if (!selecting) selected = emptySet() }) { c, name -> forwardTo = c to name; selDialog = "forward_confirm" }
        "forward_confirm" -> forwardTo?.let { (target, name) ->
            AlertDialog(
                onDismissRequest = { selDialog = null; if (!selecting) selected = emptySet() },
                title = { Text(pluralStringResource(R.plurals.cs_forward_confirm, selMsgs.size, selMsgs.size, name)) },
                confirmButton = {
                    SagaTextButton(onClick = {
                        val msgs = selMsgs
                        endSelection()
                        forwarding = true
                        repo.launchAction(onError = { forwarding = false }) {
                            val r = forwardMessages(repo, s, target.id, msgs, noForwardMark)
                            forwarding = false
                            repo.notify(UiText.Plural(R.plurals.cs_forward_done, r.first, listOf(r.first, name)))
                            if (r.second > 0) repo.notify(UiText.Plural(R.plurals.cs_forward_dropped, r.second, listOf(r.second)))
                        }
                    }) { Text(stringResource(R.string.cs_forward)) }
                },
                dismissButton = { SagaTextButton(onClick = { selDialog = null; if (!selecting) selected = emptySet() }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        "page" -> ToPageDialog(onDismiss = { selDialog = null }, onNew = { selDialog = "page_new" }, onAppend = { selDialog = "page_pick" })
        "page_pick" -> PageTargetDialog(repo, onDismiss = { selDialog = null }) { ref -> forwardTo = null; pageTarget = ref; selDialog = "page_go" }
        "page_new", "page_go" -> {
            val target = if (selDialog == "page_go") pageTarget else null
            val paras = ChatSelect.pageParagraphs(selMsgs, selLabels, selNameOf, selTimeOf, noteTitle)
            val title = ChatSelect.pageTitle(s.name, java.time.LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(io.github.yggdrasil_sh.saga.ui.common.appLocale())))
            LaunchedEffect(Unit) {
                endSelection()
                repo.launchAction {
                    val id = chatToPage(repo, target?.id, title, paras)
                    nav.push(Dest.Page(id))
                    repo.notify(UiText.Res(R.string.cs_page_done, target?.title?.ifEmpty { null } ?: title))
                }
            }
        }
        "delete" -> DeleteSelectionDialog(
            selMsgs.size, ChatSelect.canDeleteForAll(selMsgs, !fedReq && !fedRo), onDismiss = { selDialog = null },
            onForMe = {
                val ids = selMsgs.map { it.id }
                endSelection()
                repo.launchAction {
                    val server = s.hide(ids)
                    repo.notify(if (server) UiText.Plural(R.plurals.cs_hidden, ids.size, listOf(ids.size)) else UiText.Res(R.string.cs_hidden_local))
                }
            },
            onForAll = { selDialog = "delete_all" },
        )
        "delete_all" -> DeleteAllConfirm(onDismiss = { selDialog = null }) {
            val ids = selMsgs.map { it.id }
            endSelection()
            repo.launchAction {
                val r = s.deleteMany(ids)
                repo.notify(UiText.Plural(R.plurals.cs_deleted, r.deleted.size, listOf(r.deleted.size)))
                if (r.failed.isNotEmpty()) repo.notify(UiText.Plural(R.plurals.cs_delete_failed, r.failed.size, listOf(r.failed.size)))
            }
        }
    }
    unverifiedOf?.let { who ->
        AlertDialog(
            onDismissRequest = { unverifiedOf = null },
            icon = { SagaIcon(SagaIcons.warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.chat_unverified)) },
            text = { Text(stringResource(R.string.chat_unverified_explain)) },
            confirmButton = {
                SagaTextButton(onClick = { unverifiedOf = null; safetyFor = who.ifEmpty { null }; dialog = "safety" }) {
                    Text(stringResource(R.string.chat_check_safety))
                }
            },
            dismissButton = { SagaTextButton(onClick = { unverifiedOf = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    when (dialog) {
        "safety" -> SafetyDialog(s, safetyFor) { dialog = null; safetyFor = null }
        "members" -> MembersDialog(repo, s) { dialog = null }
        "chatinfo" -> ChatInfoDialog(
            repo, s, onDismiss = { dialog = null },
            onBlock = { n, t -> dialog = null; blockAsk = n to t },
            onReport = { ms -> dialog = null; reportMsgs = ms },
            onLeave = { dialog = "leave" },
        )
        "groupinfo" -> GroupInfoDialog(
            repo, s, onDismiss = { dialog = null },
            onSafety = { who -> safetyFor = who; dialog = "safety" },
            onLeave = { dialog = "leave" },
        )
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
                SagaTextButton(onClick = {
                    dialog = null
                    repo.launchAction {
                        s.setAi(true)
                        repo.notify(UiText.Res(R.string.chat_ai_allowed, aiName))
                    }
                }) { Text(stringResource(R.string.chat_ai_allow_ok)) }
            },
            dismissButton = { SagaTextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        "page" -> PagePickDialog(repo, onDismiss = { dialog = null }) { n -> dialog = null; if (noteIds.none { it.id == n.id }) noteIds.add(n) }
        "leave" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.chat_leave_title)) },
            confirmButton = {
                SagaTextButton(onClick = {
                    dialog = null
                    repo.launchAction { s.leave(); nav.top(Dest.Chats) }
                }) { Text(stringResource(R.string.chat_leave), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { SagaTextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
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

/**
 * Datei aus dem Auswahldialog lesen (Name, Typ, Inhalt). Bis zur v1-Grenze ganz einlesen; groessere Dateien (2.5) nicht
 * einlesen, sondern als [ChatAttach.Read.Big] im Hintergrund per saga-file/v2 senden (Weg: [ChatAttach.route]).
 */
private suspend fun readFile(context: Context, u: Uri, a: AttachLimits, chat: String): ChatAttach.Read = withContext(Dispatchers.IO) {
    val max = ChatAttach.v1Max(a.limits, a.remote)
    // ueber der v1-Grenze: v2 im Hintergrund oder "zu gross (hoechstens N MB)"
    fun big(name: String, mime: String, size: Long?): ChatAttach.Read {
        val r = ChatAttach.route(size ?: (max + 1), a.limits, a.remote, a.bigFiles)
        return if (r == ChatAttach.Route.V2) ChatAttach.Read.Big(u.toString(), name.take(200), mime, size)
        else ChatAttach.Read.TooBig(ChatAttach.limitMb(r, a.limits, a.remote))
    }
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
        // Foto-Auswahl liefert oft nur die Medien-ID ("1000000020.mp4"): Name aus Art, Datum und Chat (P-22)
        name = ChatAttach.niceName(name, mime, chat, java.time.LocalDateTime.now())
        // 2.4: Videos nicht einlesen - Poster, "optimiert 720p" und Upload laufen im Hintergrund (VideoUploadWorker)
        if (mime.startsWith("video/")) return@withContext ChatAttach.Read.Video(u.toString(), name.take(200), mime, size)
        if (size == 0L) return@withContext ChatAttach.Read.Empty
        // Zu grosses Bild: wie beim Einfuegen verkleinern statt ablehnen (bis zum Vierfachen der Grenze einlesen)
        if (mime.startsWith("image/") && !ChatAttach.tooBig(size, 4 * max) && (size == null || size!! > max)) {
            val raw = cr.openInputStream(u)?.use { ChatAttach.readLimited(it, 4 * max) } ?: return@withContext big(name, mime, size)
            if (raw.isEmpty()) return@withContext ChatAttach.Read.Empty
            if (raw.size <= max) return@withContext ChatAttach.Read.Ok(PendingFile(name.take(200), mime, raw))
            val (data, type) = prepareImage(raw, max) ?: return@withContext big(name, mime, raw.size.toLong())
            return@withContext ChatAttach.Read.Ok(PendingFile(ImageData.nameFor(name.take(200), type), type, data))
        }
        // Groesse vor dem Lesen pruefen, dann begrenzt lesen (BUG-005: 600 MB -> OutOfMemoryError); groesser -> v2 (Uri streamen)
        if (ChatAttach.tooBig(size, max)) return@withContext big(name, mime, size)
        val data = cr.openInputStream(u)?.use { ChatAttach.readLimited(it, max) ?: return@withContext big(name, mime, size) }
            ?: return@withContext ChatAttach.Read.Failed
        if (data.isEmpty()) return@withContext ChatAttach.Read.Empty
        ChatAttach.Read.Ok(PendingFile(name.take(200), mime, data))
    } catch (_: OutOfMemoryError) {
        ChatAttach.Read.TooBig(max / 1048576)
    } catch (_: Exception) {
        ChatAttach.Read.Failed
    }
}

/** Raeumt entschluesselte Anhaenge nach Ablauf weg - unabhaengig von der Ansicht (lebt so lange wie der Prozess) */
private val fileCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Anhang laden, entschluesseln und mit einer anderen App oeffnen (share = false) oder teilen/speichern (share = true). Klartext nur kurz im Cache
 * (eigener Zufallsordner); nach ChatFiles.KEEP_MS Freigabe zuruecknehmen und loeschen (BUG-023).
 */
internal suspend fun openFile(context: Context, s: ChatSession, f: ChatFile, share: Boolean = false, onError: (io.github.yggdrasil_sh.saga.util.UiText) -> Unit) {
    try {
        val app = context.applicationContext
        val (out, uri) = if (f.isV2) {
            // 2.4: saga-file/v2 (kein Video bzw. "Oeffnen mit"): blockweise als Strom in die kurzlebige Kopie
            val repo = (app as io.github.yggdrasil_sh.saga.SagaApplication).repo
            withContext(Dispatchers.IO) {
                ChatFiles.purgeOlderThan(app.cacheDir)
                val out = ChatFiles.write(app.cacheDir, f.n, ByteArray(0))
                try {
                    out.outputStream().use { o -> io.github.yggdrasil_sh.saga.chat.video.VideoSources(app, repo.api, repo.chat.crypto).decryptTo(f, o) {} }
                } catch (e: Exception) {
                    ChatFiles.remove(out)
                    throw e
                }
                out to FileProvider.getUriForFile(app, app.packageName + ".files", out)
            }
        } else {
            val data = s.download(f)
            withContext(Dispatchers.IO) {
                ChatFiles.purgeOlderThan(app.cacheDir)
                val out = ChatFiles.write(app.cacheDir, f.n, data)
                out to FileProvider.getUriForFile(app, app.packageName + ".files", out)
            }
        }
        fileCleanup.launch {
            delay(ChatFiles.KEEP_MS)
            try {
                app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            ChatFiles.remove(out)
        }
        // Oeffnen: schlichtes ACTION_VIEW (Android bietet "Immer" an); Teilen/Speichern per Menue bzw. als Ausweg (BUG-108)
        if (share) io.github.yggdrasil_sh.saga.util.OpenWith.share(context, uri, f.m, f.n)
        else io.github.yggdrasil_sh.saga.util.OpenWith.open(context, uri, f.m, f.n)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        onError(io.github.yggdrasil_sh.saga.util.UiText.Res(R.string.chat_file_broken))
    }
}

/** CH-21: Seitensymbol im Nachrichtentext (Linien-Symbol, so gross wie die Schrift) */
private const val PAGE_ICON = "saga-page"
private val pageIconInline = mapOf(
    PAGE_ICON to androidx.compose.foundation.text.InlineTextContent(
        androidx.compose.ui.text.Placeholder(1.1.em, 1.1.em, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
    ) { SagaIcon(SagaIcons.file, null, Modifier.fillMaxSize()) },
)

/**
 * Text mit Seitenverweisen [[note:ID]] -> Seitensymbol + Titel (antippbar, nur mit Zugriff), http(s)-Links, 2.5: Erwaehnungen
 * <@kennung> als hervorgehobenes "@Name" (eigener Name staerker) und Formatierung (chatfmt1, ChatFmt wie chat-fmt.js -
 * nur Stile, nie Markup aus dem Text).
 */
@Composable
private fun messageText(text: String, notes: Map<Long, ChatNoteRef?>, onOpen: (Long) -> Unit, s: ChatSession? = null): AnnotatedString {
    val noAccess = stringResource(R.string.chat_page_no_access)
    val pageWord = stringResource(R.string.r5_pv_page)
    val unknown = stringResource(R.string.std_unknown)
    val scheme = MaterialTheme.colorScheme
    val linkColor = scheme.primary
    val context = LocalContext.current
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    val std = s?.std
    val sum = s?.summary
    val mentionOn = std?.mention == true
    return buildAnnotatedString {
        // http(s)-Links antippbar - immer ueber openUrl (Standard-Browser bzw. Systemauswahl, kein Custom Tab)
        fun plain(t: String) {
            for (p in io.github.yggdrasil_sh.saga.util.Links.split(t)) {
                val u = p.url
                if (u == null) append(p.text)
                else withLink(LinkAnnotation.Clickable("url-$length", linkStyle) { io.github.yggdrasil_sh.saga.ui.common.openUrl(context, u) }) { append(p.text) }
            }
        }
        fun mentions(t: String) {
            if (!mentionOn) return plain(t)
            var last = 0
            for (m in ChatStd.MENTION.findAll(t)) {
                plain(t.substring(last, m.range.first))
                val ref = m.groupValues[1]
                val me = ChatStd.isMe(sum, ref)
                val name = ChatStd.mentionName(sum, ref, null) ?: unknown
                val st = length
                append("@$name")
                addStyle(
                    SpanStyle(
                        color = scheme.primary, fontWeight = if (me) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.SemiBold,
                        background = if (me) scheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Unspecified,
                    ),
                    st, length,
                )
                last = m.range.last + 1
            }
            plain(t.substring(last))
        }
        fun leaf(t: String) {
            var last = 0
            for (m in ChatFormat.NOTE_REF.findAll(t)) {
                mentions(t.substring(last, m.range.first))
                val id = m.groupValues[1].toLongOrNull()
                val ref = id?.let { notes[it] }
                if (id != null && ref != null) {
                    withLink(LinkAnnotation.Clickable("note-$id", linkStyle) { onOpen(id) }) {
                        // CH-21: Linien-Symbol (Inline) statt Emoji; Bildschirmleser hoert "Seite"
                        appendInlineContent(PAGE_ICON, pageWord)
                        append(" " + ref.title)
                    }
                } else {
                    appendInlineContent(PAGE_ICON, pageWord)
                    append(" $noAccess")
                }
                last = m.range.last + 1
            }
            mentions(t.substring(last))
        }
        if (std?.fmt != true) {
            leaf(text)
            return@buildAnnotatedString
        }
        val b = this
        io.github.yggdrasil_sh.saga.chat.ChatFmt.flatten(io.github.yggdrasil_sh.saga.chat.ChatFmt.render(text), object : io.github.yggdrasil_sh.saga.chat.ChatFmt.Sink {
            override val length: Int get() = b.length
            override fun text(s: String) { b.append(s) }
            override fun leaf(s: String) { leaf(s) }
            override fun span(start: Int, end: Int, kind: io.github.yggdrasil_sh.saga.chat.ChatFmt.Kind) {
                val mono = androidx.compose.ui.text.font.FontFamily.Monospace
                b.addStyle(
                    when (kind) {
                        io.github.yggdrasil_sh.saga.chat.ChatFmt.Kind.BOLD -> SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        io.github.yggdrasil_sh.saga.chat.ChatFmt.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                        io.github.yggdrasil_sh.saga.chat.ChatFmt.Kind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        io.github.yggdrasil_sh.saga.chat.ChatFmt.Kind.CODE, io.github.yggdrasil_sh.saga.chat.ChatFmt.Kind.PRE ->
                            SpanStyle(fontFamily = mono, background = scheme.onSurface.copy(alpha = 0.08f))
                        io.github.yggdrasil_sh.saga.chat.ChatFmt.Kind.QUOTE -> SpanStyle(color = scheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                        io.github.yggdrasil_sh.saga.chat.ChatFmt.Kind.BULLET -> SpanStyle(color = scheme.primary)
                    },
                    start, end,
                )
            }
        })
    }
}

/** CH-01: groesste Blasenbreite (vom Verlauf gesetzt; ausserhalb wie bisher 520 dp) */
internal val LocalBubbleMax = androidx.compose.runtime.staticCompositionLocalOf { 520.dp }

/** Farben der Blasen und der Markierung "Echtheit nicht bestätigt" (Kontrast im Test geprueft) */
object ChatUi {
    /** dunkles Schema? (an der Grundflaeche erkannt) */
    fun isDark(c: ColorScheme): Boolean = c.surface.luminance() < 0.5f

    /** Akzent der Blasen wie Web `--accent`: hell das kraeftige Gold (goldText), dunkel das Zier-Gold */
    fun tint(c: ColorScheme, acc: SagaAccent = if (isDark(c)) DarkAccent else LightAccent): Color = if (isDark(c)) acc.gold else acc.goldText

    /** Mischung in sRGB wie CSS `color-mix(in srgb, b t, a)` (nicht Oklab wie `lerp`), immer voll deckend */
    fun mix(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t, blue = a.blue + (b.blue - a.blue) * t, alpha = 1f,
    )

    /** CH-13/A11Y-18: wie `.ct-bubble` im Web - fremd Kartenfarbe, eigen 16 % Gold ueber der Karte */
    fun bubble(c: ColorScheme, mine: Boolean, tint: Color = tint(c)): Color =
        if (mine) mix(c.surfaceContainerLow, tint, 0.16f) else c.surfaceContainerLow

    /** feiner Rand: fremd `outlineVariant`, eigen mit 35 % Gold */
    fun bubbleBorder(c: ColorScheme, mine: Boolean, tint: Color = tint(c)): Color =
        if (mine) mix(c.outlineVariant, tint, 0.35f) else c.outlineVariant

    /** Zeit, "bearbeitet" und Haken: 70 % Textfarbe ueber der Blase (als feste Farbe, damit pruefbar >= 4,5:1) */
    fun meta(bg: Color, fg: Color): Color = mix(bg, fg, 0.7f)

    /** Hintergrund von Antwort-Zitat und Linkvorschau in der Blase: 6 % Textfarbe ueber der Blase (Web `.ct-quote`) */
    fun inset(bg: Color, fg: Color): Color = mix(bg, fg, 0.06f)

    /** CH-01: hoechstens 80 % der Verlaufsbreite und nie ueber 520 dp; freie Gasse zur Gegenseite */
    fun bubbleMax(rowWidth: Dp): Dp = minOf(rowWidth * 0.8f, 520.dp)
    val GAP: Dp = 48.dp
    /** reine Bilder mindestens 200 dp breit (Hoehe bleibt, Zuschnitt mittig) */
    const val MEDIA_MIN = 200
    fun mediaBox(dims: Pair<Int, Int>): Pair<Int, Int> = maxOf(dims.first, MEDIA_MIN) to dims.second

    fun unverifiedText(c: ColorScheme, mine: Boolean): Color = if (mine) c.onSurface else c.onSurfaceVariant
    fun unverifiedIcon(c: ColorScheme, mine: Boolean): Color = if (mine) c.onSurface else c.error

    /** WCAG-Kontrastverhaeltnis */
    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}

/** A11Y-01: Beschreibung einer Blase fuer Bedienungshilfen (rein, fuer Unit-Tests) */
internal object BubbleA11y {
    /** Anhang-Bezeichnung und Text, leere Teile entfallen */
    fun body(attachment: String?, text: String?): String = listOfNotNull(attachment?.takeIf { it.isNotBlank() }, text?.takeIf { it.isNotBlank() }).joinToString(", ")

    /** "Du/Name: Text, Zeit, Status" - ohne Absender bzw. Text entfallen ":" und das Komma */
    fun describe(who: String, body: String, time: String, status: List<String>): String {
        val head = when {
            who.isBlank() -> body
            body.isBlank() -> who
            else -> "$who: $body"
        }
        return (listOf(head, time) + status).filter { it.isNotBlank() }.joinToString(", ")
    }
}

/** internal: auch fuer die Bildschirmfotos im Geraete-Test (ChatSelShotsTest) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    m: ChatMessage, s: ChatSession, group: Boolean, aiPersona: AiPersona?,
    onReply: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, onOpenNote: (Long) -> Unit, onOpenFile: (ChatFile) -> Unit,
    onUnverified: () -> Unit, onResend: () -> Unit = {},
    /** Anhang teilen bzw. speichern (Systemauswahl) - Oeffnen selbst geht ohne Auswahl an die Standard-App */
    onShareFile: (ChatFile) -> Unit = {},
    tick: io.github.yggdrasil_sh.saga.chat.Tick = io.github.yggdrasil_sh.saga.chat.Tick(io.github.yggdrasil_sh.saga.chat.TickKind.NONE),
    readAt: Long? = null, onReadBy: (() -> Unit)? = null,
    /** Design 2: erste bzw. letzte Nachricht einer Folge derselben Person (Ecken, Abstand, Name, Profilkreis) */
    first: Boolean = true, last: Boolean = true,
    /** 2.5: Reaktion (Emoji; gleiches nochmal nimmt zurueck), volle Auswahl, "Reaktionen", Bearbeiten */
    onReact: (String) -> Unit = {}, onMoreEmoji: () -> Unit = {}, onShowReactions: () -> Unit = {}, onEdit: () -> Unit = {},
    /** 25.10.: Auswahlmodus aktiv / diese Nachricht ausgewaehlt; onSelect null = nicht auswaehlbar (nicht entschluesselt) */
    selecting: Boolean = false, selected: Boolean = false, onSelect: (() -> Unit)? = null,
    /** Eintrag im Nachrichtenmenue gewaehlt (beendet eine nur per langem Druck begonnene Auswahl) */
    onMenuAction: () -> Unit = {},
    /** 2.5 Chat-Standard: weitere Eintraege (Anheften, Löschen (Admin), Sperren, Melden); close schliesst das Menue */
    extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
    /** 2.5 Medien 1: "Weiterleiten" (null = nicht anbieten) und Bild "In Galerie speichern" (null = nicht anbieten) */
    onForward: (() -> Unit)? = null,
    onSaveImage: ((ChatFile) -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val isSel = selecting && selected
    val selDesc = stringResource(if (isSel) R.string.cs_state_selected else R.string.cs_state_not_selected)
    val selLabel = stringResource(R.string.cs_select_action)
    var confirmDelete by remember { mutableStateOf(false) }
    // 2.1: bestaetigte KI-Antwort - links mit Profilbild + Name der KI, "gefragt von …" (auch die eigene)
    val isAi = m.ai == AiReplyCheck.OK
    val mineSide = m.mine && !isAi
    // 2.5 (chatmention1): Nachrichten, die mich erwaehnen, hervorheben
    val mentionsMe = !m.mine && s.std.mention && ChatStd.mentionsMe(s.summary, m.content?.text)
    // CH-13/A11Y-18: hell mit feinem Rand wie im Web, die eigene leicht goldgetoent
    val tint = ChatUi.tint(MaterialTheme.colorScheme, LocalSagaAccent.current)
    val bg = if (isAi) MaterialTheme.colorScheme.tertiaryContainer else if (mentionsMe) MaterialTheme.colorScheme.secondaryContainer else ChatUi.bubble(MaterialTheme.colorScheme, mineSide, tint)
    val border = if (isAi || mentionsMe) null else androidx.compose.foundation.BorderStroke(1.dp, ChatUi.bubbleBorder(MaterialTheme.colorScheme, mineSide, tint))
    val fg = when {
        isAi -> MaterialTheme.colorScheme.onTertiaryContainer
        mentionsMe -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val meta = ChatUi.meta(bg, fg)
    val time = remember(m.ts) {
        Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    val reactions = s.chipsOf(m).isNotEmpty()
    // A11Y-01: eine Beschreibung je Blase "Du/Name: Text, Zeit, Status"; nur langes Druecken (kein leeres onClick, das TalkBack
    // als "Doppeltippen zum Aktivieren" ansagte), Antworten/Reagieren/Kopieren als Aktionen fuer Bedienungshilfen
    val c0 = m.content
    val whoDesc = when {
        isAi -> aiPersona?.name ?: c0?.aiName ?: stringResource(R.string.ai_title)
        m.mine -> stringResource(R.string.r1_bubble_you)
        else -> m.sender?.let { io.github.yggdrasil_sh.saga.chat.ChatLogic.shown(it) }.orEmpty()
    }
    val unknownName = stringResource(R.string.chat_reply_missing)
    val labels = mediaLabels()
    val bodyDesc = if (c0 == null) stringResource(R.string.chat_undecryptable) else BubbleA11y.body(
        ChatMedia.attachmentLabel(c0, labels) ?: c0.files.firstOrNull()?.n?.takeIf { c0.text.isBlank() },
        c0.text.takeIf { it.isNotBlank() }?.let { previewText(m, s, unknownName) },
    )
    val editedDesc = m.editedAt?.let { at ->
        val whenText = remember(at) {
            Instant.ofEpochMilli((at * 1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
        }
        stringResource(R.string.x3_edited_at, whenText)
    }
    val fedDesc = when (m.fstate) {
        "pending" -> stringResource(R.string.fed_msg_pending)
        "failed" -> stringResource(R.string.fed_msg_failed)
        else -> null
    }
    val tickDesc = tickLabel(tick, group, readAt)
    val bubbleDesc = BubbleA11y.describe(whoDesc, bodyDesc, time, listOfNotNull(editedDesc, fedDesc ?: tickDesc))
    val actReply = stringResource(R.string.chat_reply)
    val actReact = stringResource(R.string.r1_bubble_react)
    val actCopy = stringResource(R.string.text_copy)
    val actMenu = stringResource(R.string.chat_message_actions)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val openMenu = { if (!selecting) { onSelect?.invoke(); menu = true } }
    // 25.10.: im Auswahlmodus Kreis links, getoente Zeile, Antippen der ganzen Zeile waehlt (Inhalt nicht bedienbar)
    Box(
        Modifier.fillMaxWidth().background(selectedRowColor(isSel))
            .then(if (selecting && onSelect != null) Modifier.semantics(mergeDescendants = true) { this.selected = isSel; stateDescription = selDesc } else Modifier),
    ) {
    if (selecting && onSelect != null) SelectMark(isSel, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
    // CH-26: Blase nach rechts wischen = Antworten (Schwelle 64 dp, Haptik beim Erreichen, Symbol taucht auf); Langdruck
    // bleibt das Menue, TalkBack hat "Antworten" weiter als Aktion
    val swipeOn = SwipeReply.enabled(selecting, c0 != null && !m.tomb)
    val density = LocalDensity.current
    val thrPx = with(density) { SwipeReply.THRESHOLD_DP.dp.toPx() }
    val maxPx = with(density) { SwipeReply.MAX_DP.dp.toPx() }
    var swipeX by remember(m.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val swipeState = androidx.compose.foundation.gestures.rememberDraggableState { d ->
        val before = swipeX
        swipeX = SwipeReply.drag(before, d, maxPx)
        if (SwipeReply.crossed(before, swipeX, thrPx)) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    if (swipeX > 0f) SagaIcon(
        SagaIcons.reply, null,
        Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(24.dp)
            .graphicsLayer { alpha = SwipeReply.progress(swipeX, thrPx); scaleX = 0.6f + 0.4f * alpha; scaleY = scaleX },
        tint = MaterialTheme.colorScheme.primary,
    )
    Row(
        Modifier.fillMaxWidth()
            .offset { androidx.compose.ui.unit.IntOffset(swipeX.toInt(), 0) }
            .draggable(
                swipeState, androidx.compose.foundation.gestures.Orientation.Horizontal, enabled = swipeOn,
                onDragStopped = {
                    if (SwipeReply.fires(swipeX, thrPx)) onReply()
                    androidx.compose.animation.core.animate(swipeX, 0f) { v, _ -> swipeX = v }
                },
            )
            .padding(start = if (selecting) 46.dp else 12.dp, end = 12.dp, top = if (first) 6.dp else 1.dp, bottom = if (last || reactions) 2.dp else 1.dp),
        horizontalArrangement = if (mineSide) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // Profilkreis nur an der letzten Blase einer Folge, sonst gleich breiter Platz (Blasen bleiben buendig)
        if (isAi) {
            Box(Modifier.padding(end = 6.dp, bottom = 2.dp).size(28.dp)) { if (last) AiAvatar(aiPersona, 28) }
        } else if (group && !m.mine) {
            // 2.1: Profilbild des Absenders (Gruppen-Chats)
            Box(Modifier.padding(end = 6.dp, bottom = if (reactions) 26.dp else 2.dp).size(28.dp)) {
                if (last) ChatAvatar(m.sender?.let { io.github.yggdrasil_sh.saga.chat.ChatLogic.shown(it) }.orEmpty(), 28.dp, avatar = m.sender?.avatar)
            }
        }
        // CH-01: freie Gasse von 48 dp zur Gegenseite
        Column(
            Modifier.padding(start = if (mineSide) ChatUi.GAP else 0.dp, end = if (mineSide) 0.dp else ChatUi.GAP),
            horizontalAlignment = if (mineSide) Alignment.End else Alignment.Start,
        ) {
        Box {
            Surface(
                color = bg, contentColor = fg, border = border,
                shape = ChatDesign.bubbleShape(mineSide, first, last),
                // voll deckend - auch bei "Echtheit nicht bestätigt" (kein Abdunkeln des Inhalts)
                modifier = Modifier.widthIn(max = LocalBubbleMax.current)
                    // langer Druck: Auswahl beginnen (diese Nachricht) und das Nachrichtenmenue zeigen (A11Y-01: kein Tippen)
                    .pointerInput(selecting) {
                        detectTapGestures(onLongPress = {
                            if (!selecting) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                openMenu()
                            }
                        })
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = bubbleDesc
                        if (!selecting) {
                            onLongClick(label = actMenu) { openMenu(); true }
                            customActions = buildList {
                                if (c0 != null) add(CustomAccessibilityAction(actReply) { onReply(); true })
                                if (s.canReact(m)) add(CustomAccessibilityAction(actReact) { onMoreEmoji(); true })
                                if (!c0?.text.isNullOrEmpty()) add(CustomAccessibilityAction(actCopy) { onCopy(); true })
                            }
                        }
                    },
            ) {
                // Design 2: nur Bild/Video - schmaler Rand, das Bild fuellt die Blase
                val mediaOnly = m.content?.let { c -> c.text.isEmpty() && c.notes.isEmpty() && c.files.isNotEmpty() && c.files.all { (it.isImage || it.isVideo) && !it.isVoice } } == true &&
                    m.replyTo == null && m.verified && !isAi && m.ai != AiReplyCheck.BAD && !(group && !m.mine && first)
                Column(if (mediaOnly) Modifier.padding(4.dp) else Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (isAi) {
                        val name = aiPersona?.name ?: m.content?.aiName ?: stringResource(R.string.ai_title)
                        val by = if (m.mine) stringResource(R.string.chat_ai_asked_by_you)
                        else stringResource(R.string.chat_ai_asked_by, m.sender?.let { io.github.yggdrasil_sh.saga.chat.ChatLogic.shown(it) }.orEmpty())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                            Text(" · $by", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else if (group && !m.mine && first) {
                        // Design 2: Name in der Tonfarbe der Person (wie ihr Profilkreis), nur an der ersten Blase
                        val who = m.sender?.let { io.github.yggdrasil_sh.saga.chat.ChatLogic.shown(it) }.orEmpty()
                        Text(who, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = ChatDesign.nameColor(who, MaterialTheme.colorScheme),
                            modifier = Modifier.clearAndSetSemantics {})   // A11Y-01: steht in der Blasen-Beschreibung
                    }
                    if (m.ai == AiReplyCheck.BAD) {
                        // gibt sich als KI-Antwort aus, Beleg fehlt/passt nicht
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            SagaIcon(SagaIcons.warning, null, Modifier.size(16.dp), tint = ChatUi.unverifiedIcon(MaterialTheme.colorScheme, mineSide))
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
                            Modifier.clip(RoundedCornerShape(SagaRadius.s)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onUnverified)
                                .heightIn(min = 32.dp).padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SagaIcon(SagaIcons.warning, null, Modifier.size(16.dp), tint = ChatUi.unverifiedIcon(scheme, mineSide))
                            Text(
                                stringResource(R.string.chat_unverified), style = MaterialTheme.typography.labelMedium,
                                color = ChatUi.unverifiedText(scheme, mineSide), modifier = Modifier.padding(start = 4.dp),
                            )
                            SagaIcon(SagaIcons.info, null, Modifier.padding(start = 4.dp).size(14.dp), tint = ChatUi.unverifiedText(scheme, mineSide))
                        }
                    }
                    m.replyTo?.let { rid ->
                        val q = s.messages.firstOrNull { it.id == rid }
                        // 2.5 Medien 1: zitiertes Bild/Video mit kleinem Vorschaubild; ohne Text "Foto"/"Video"/Dateiname
                        val qThumb = ChatMedia.quoteThumb(q?.content)
                        val qc = q?.content
                        val qText = if (qc == null) stringResource(R.string.chat_reply_missing) else ChatMedia.attachmentLabel(qc, mediaLabels()) ?: qc.text
                        // wie Web `.ct-quote`: 6 % Textfarbe ueber der Blase, Goldstrich links, Name in Textfarbe, Text gedaempft
                        Surface(color = ChatUi.inset(bg, fg), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                Modifier.drawBehind {
                                    val w = 3.dp.toPx()
                                    val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - w else 0f
                                    drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
                                }
                                    .padding(start = 11.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f, fill = false)) {
                                    Text(
                                        if (q?.ai == AiReplyCheck.OK) aiPersona?.name ?: q.content?.aiName.orEmpty() else q?.sender?.name.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    )
                                    Text(qText, style = MaterialTheme.typography.bodySmall, color = ChatUi.meta(ChatUi.inset(bg, fg), fg), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (qThumb != null) Box(Modifier.padding(start = 8.dp)) { QuoteThumb(qThumb, s) }
                            }
                        }
                    }
                    val c = m.content
                    if (c == null) {
                        Text(stringResource(R.string.chat_undecryptable), fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        // 4.11.: langer Druck auf Bild/Video/Datei -> dasselbe Menue wie auf der Blase (Reaktionen)
                        c.files.forEach { f -> Attachment(f, s, onOpenFile, onLongPress = { if (!selecting) { onSelect?.invoke(); menu = true } }) }
                        if (c.text.isNotEmpty()) Text(messageText(c.text, s.notes, onOpenNote, s), inlineContent = pageIconInline,
                            // R1-Rest: Langdruck auf einen Link -> Menue (kurzer Tipp oeffnet den Link)
                            modifier = Modifier.longPressOverLinks {
                                if (!selecting) {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    openMenu()
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Simple))  // schnelles Scrollen langer Verlaeufe (Pruefer R1)
                        // 2.5 (unfurl1): Linkvorschau aus der Nachricht (nichts nachladen ausser dem verschluesselten Bild)
                        c.preview?.takeIf { ChatStd.showPreview(it) }?.let { pv -> PreviewCard(pv, s) }
                        c.notes.filter { id -> !c.text.contains("[[note:$id]]") }.forEach { id ->
                            val ref = s.notes[id]
                            if (ref != null) SagaTextButton(onClick = { onOpenNote(id) }) {
                                SagaIcon(SagaIcons.file, null, Modifier.padding(end = 6.dp).size(16.dp))
                                Text(ref.title)
                            } else Row(verticalAlignment = Alignment.CenterVertically) {
                                SagaIcon(SagaIcons.file, null, Modifier.padding(end = 6.dp).size(14.dp))
                                Text(stringResource(R.string.chat_page_no_access), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    // 2.3: Zustellstand in Chats ueber Servergrenzen
                    when (m.fstate) {
                        "pending" -> Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            SagaIcon(SagaIcons.clock, null, Modifier.padding(end = 4.dp).size(12.dp), tint = meta)
                            Text(stringResource(R.string.fed_msg_pending), style = MaterialTheme.typography.labelSmall, color = meta)
                        }
                        "failed" -> Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            val warnC = ChatUi.unverifiedText(MaterialTheme.colorScheme, mineSide)
                            SagaIcon(SagaIcons.warning, null, Modifier.padding(end = 4.dp).size(12.dp), tint = warnC)
                            Text(
                                stringResource(R.string.fed_msg_failed), style = MaterialTheme.typography.labelSmall,
                                color = warnC, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                        }
                    }
                    // Uhrzeit + Haken (2.4): ⏳ → ✓ → ✓✓; in Gruppen fuehrt Antippen zu "Gelesen von"
                    // A11Y-01: Zeit, "bearbeitet" und Haken stehen in der Blasen-Beschreibung; antippbar ("Gelesen von") bleibt ein eigener Knopf
                    Row(
                        Modifier.align(Alignment.End).then(if (mediaOnly) Modifier.padding(end = 8.dp, top = 2.dp, bottom = 2.dp) else Modifier)
                            .then(if (onReadBy != null) Modifier.clip(RoundedCornerShape(SagaRadius.s)).clickable(onClickLabel = stringResource(R.string.chat_read_by), onClick = onReadBy)
                                .semantics { contentDescription = time + (tickDesc?.let { ", $it" } ?: "") }
                            else Modifier.clearAndSetSemantics {}),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 2.5: "bearbeitet" klein neben der Zeit (Zeitpunkt fuer Bildschirmleser)
                        m.editedAt?.let { at ->
                            val whenText = remember(at) {
                                Instant.ofEpochMilli((at * 1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
                            }
                            val desc = stringResource(R.string.x3_edited_at, whenText)
                            Text(
                                stringResource(R.string.x3_edited) + " · ", style = MaterialTheme.typography.labelSmall, color = meta,
                                fontStyle = FontStyle.Italic, modifier = Modifier.semantics { contentDescription = desc },
                            )
                        }
                        Text(time, style = MaterialTheme.typography.labelSmall, color = meta)
                        TickMark(tick, meta, group, readAt, readColor = if (mineSide) tint else MaterialTheme.colorScheme.primary)
                    }
                }
            }
            val videoHost = LocalVideoHost.current
            val videoFiles = m.content?.files.orEmpty().filter { it.isVideo && !it.unsupported }
            val saveVideo = if (videoHost != null && videoFiles.isNotEmpty()) rememberVideoSaver(videoHost, s) else null
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // 2.5: Schnellleiste (6 Emojis + "+") ueber den Eintraegen
                if (s.canReact(m)) {
                    ReactionBar(s.myReaction(m), onPick = { menu = false; onMenuAction(); onReact(it) }, onMore = { menu = false; onMenuAction(); onMoreEmoji() })
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_reply)) }, onClick = { menu = false; onMenuAction(); onReply() })
                if (s.canEdit(m)) DropdownMenuItem(text = { Text(stringResource(R.string.x3_edit)) }, onClick = { menu = false; onMenuAction(); onEdit() })
                if (s.chipsOf(m).isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.x3_reactions_title)) }, onClick = { menu = false; onMenuAction(); onShowReactions() })
                // P-22: Videos auch aus dem Nachrichten-Menue speichern
                if (saveVideo != null) videoFiles.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(if (videoFiles.size > 1) stringResource(R.string.video_save) + " · " + f.n else stringResource(R.string.video_save)) },
                        onClick = { menu = false; onMenuAction(); saveVideo(f) },
                    )
                }
                // Anhaenge (ausser Sprache/Video) teilen bzw. "In Dateien speichern" - die Auswahl gibt es nur noch hier
                val shareFiles = m.content?.files.orEmpty().filter { !it.unsupported && !it.isVoice && !(it.isVideo && videoHost != null) }
                shareFiles.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(if (shareFiles.size > 1) stringResource(R.string.publish_share) + " · " + f.n else stringResource(R.string.publish_share)) },
                        onClick = { menu = false; onMenuAction(); onShareFile(f) },
                    )
                }
                // 2.5 Medien 1: Bilder in der Galerie speichern
                val galleryFiles = if (onSaveImage != null) m.content?.files.orEmpty().filter { ChatMedia.viewable(it) } else emptyList()
                galleryFiles.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(if (galleryFiles.size > 1) stringResource(R.string.media_save_gallery) + " · " + f.n else stringResource(R.string.media_save_gallery)) },
                        onClick = { menu = false; onMenuAction(); onSaveImage?.invoke(f) },
                    )
                }
                if (onForward != null) DropdownMenuItem(text = { Text(stringResource(R.string.cs_forward)) }, onClick = { menu = false; onMenuAction(); onForward() })
                if (!m.content?.text.isNullOrEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.text_copy)) }, onClick = { menu = false; onMenuAction(); onCopy() })
                if (m.mine && m.fstate == "failed" && m.content != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.fed_resend)) }, onClick = { menu = false; onMenuAction(); onResend() })
                }
                // 2.4: "Info" - gelesen von … (Gruppen)
                if (onReadBy != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_info)) }, onClick = { menu = false; onMenuAction(); onReadBy() })
                }
                if (m.mine) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_delete_all), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onMenuAction(); confirmDelete = true })
                }
                extraMenu?.invoke { menu = false; onMenuAction() }
            }
        }
        // 2.5: Reaktionen als Chips unter der Blase
        ReactionChips(s.chipsOf(m), mineSide, onToggle = if (s.canReact(m)) onReact else null, onShow = onShowReactions)
        }
    }
    if (selecting && onSelect != null) Box(
        Modifier.matchParentSize().clickable(onClickLabel = selLabel, role = androidx.compose.ui.semantics.Role.Checkbox) { onSelect() },
    )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.chat_delete_all_title)) },
            confirmButton = {
                SagaTextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.chat_delete_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { SagaTextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Bilder (2.1-c): im Verlauf nur das Vorschaubild, Platz sofort in richtiger Groesse (w/h, kein Springen); geladen nur,
 * solange die Nachricht sichtbar ist (Abbruch beim Wegscrollen). Antippen oeffnet das Original. Sonst Datei mit
 * Name/Groesse zum Oeffnen/Teilen.
 */
@Composable
internal fun Attachment(f: ChatFile, s: ChatSession, onOpen: (ChatFile) -> Unit, onLongPress: (() -> Unit)? = null) {
    // 2.5: Sprachnachricht - Play, Wellenform, Dauer, Tempo (gemeinsamer Player, Quelle wie Videos)
    if (f.isVoice && !f.unsupported) {
        VoiceAttachment(f, s, androidx.compose.material3.LocalContentColor.current, LocalVideoHost.current?.sources)
        return
    }
    // 2.4: neueres Format (x > 2) nicht still scheitern lassen
    if (f.unsupported) {
        SagaTextButton(onClick = {}, enabled = false) {
            SagaIcon(SagaIcons.paperclip, null, Modifier.padding(end = 6.dp).size(16.dp))
            Text(f.n + " · " + stringResource(R.string.video_newer_version), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    // 2.4: Video - Poster mit Dauer, Abspielen im gemeinsamen Player (v1 und saga-file/v2)
    if (f.isVideo && LocalVideoHost.current != null) {
        VideoAttachment(f, s, onLongPress)
        return
    }
    // 2.5: grosse Bilder per saga-file/v2 (ohne Vorschaubild) als Datei zeigen - nicht ganz laden
    if (f.isImage && !(f.isV2 && f.th == null)) {
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
            // CH-01: mindestens 200 dp breit (schmale Hochformate mittig zugeschnitten)
            val dims = ChatUi.mediaBox(io.github.yggdrasil_sh.saga.chat.ChatImages.displaySize(f.w ?: f.th?.w, f.h ?: f.th?.h)
                ?: loaded?.let { io.github.yggdrasil_sh.saga.chat.ChatImages.displaySize(it.width, it.height) }
                ?: (200 to 150))
            Box(
                Modifier.padding(vertical = 2.dp).size(dims.first.dp, dims.second.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .combinedClickable(onClick = { onOpen(f) }, onLongClick = onLongPress,
                        onLongClickLabel = if (onLongPress != null) stringResource(R.string.chat_message_actions) else null),
                contentAlignment = Alignment.Center,
            ) {
                if (loaded != null) Image(loaded, f.n, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            return
        }
    }
    // 4.11.: kein TextButton - der schluckte den langen Druck (Reaktionen gingen nur auf Text)
    Box(
        Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = { onOpen(f) }, onLongClick = onLongPress, role = androidx.compose.ui.semantics.Role.Button,
                onLongClickLabel = if (onLongPress != null) stringResource(R.string.chat_message_actions) else null)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SagaIcon(SagaIcons.paperclip, null, Modifier.padding(end = 6.dp).size(16.dp))
            Text(f.n + " · " + (if (f.s == 0L) stringResource(R.string.chat_file_empty_short) else android.text.format.Formatter.formatShortFileSize(LocalContext.current, f.s)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
            modifier = Modifier.padding(end = ChatUi.GAP).widthIn(max = LocalBubbleMax.current),
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

/** Anruf-Eintrag (2.1): mittig, Richtung, Ergebnis, Uhrzeit; verpasst -> "Zurückrufen", eigener gescheitert -> "Erneut anrufen" */
@Composable
private fun CallEntryRow(
    m: ChatMessage, e: io.github.yggdrasil_sh.saga.call.CallEntry, me: String, canCall: Boolean, onCall: (Boolean) -> Unit,
) {
    val logic = io.github.yggdrasil_sh.saga.call.CallLogic
    val missed = logic.missed(e, me)
    val failed = logic.failed(e)
    val time = remember(m.ts) {
        Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    val text = logic.entryText(e, me).resolve()
    // CH-22: verpasst und gescheitert in Fehlerfarbe
    val color = if (missed || failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    // CH-22: Richtung als Symbol (statt "↗"/"↙" im Text, das TalkBack als "Pfeil nach rechts oben" las) - mit Beschreibung
    val (icon, dirLabel) = when (logic.direction(e, me)) {
        "missed" -> SagaIcons.phoneMissed to R.string.r1_call_dir_missed
        "out_failed" -> SagaIcons.phoneOff to R.string.r1_call_dir_out
        "out" -> SagaIcons.phoneOut to R.string.r1_call_dir_out
        else -> SagaIcons.phoneIn to R.string.r1_call_dir_in
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(SagaRadius.md)) {
            Row(
                Modifier.padding(start = 12.dp, end = 4.dp).heightIn(min = 40.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(icon), stringResource(dirLabel), Modifier.size(16.dp), tint = color)
                Text(
                    "$text · $time", style = MaterialTheme.typography.labelMedium, color = color,
                    modifier = Modifier.padding(start = 6.dp, end = 8.dp),
                )
                if (missed && canCall) {
                    SagaTextButton(onClick = { onCall(e.media == "video") }) { Text(stringResource(R.string.call_back)) }
                } else if (logic.redial(e, me) && canCall) {
                    SagaTextButton(onClick = { onCall(e.media == "video") }) { Text(stringResource(R.string.r1_call_redial)) }
                }
            }
        }
    }
}

/** Datumstrenner im Chat: "Heute", "Gestern", sonst kurzes Datum mit Wochentag */
@Composable
private fun ChatDayDivider(d: java.time.LocalDate) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(SagaRadius.pill)) {
            Text(
                // "heute"/"gestern" stehen klein (fuer Faelligkeiten im Satz) - als Ueberschrift gross
                io.github.yggdrasil_sh.saga.ui.common.dueText(d.toString()).replaceFirstChar { it.titlecase(io.github.yggdrasil_sh.saga.ui.common.appLocale()) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp).semantics { heading() },
            )
        }
    }
}


/** Hinweis, warum ein Video nicht gesendet werden kann (Grenzen vom Server) */
internal fun videoLimitText(r: ChatAttach.Route, a: AttachLimits): UiText = when (r) {
    ChatAttach.Route.NO_FILE2 -> UiText.Res(R.string.video_too_big_server, ChatAttach.limitMb(r, a.limits, a.remote))
    ChatAttach.Route.PEER_OLD -> UiText.Res(R.string.video_peer_old, ChatAttach.limitMb(r, a.limits, a.remote))
    else -> UiText.Res(R.string.chat_file_too_big, ChatAttach.maxSize(a.limits, a.remote, a.bigFiles) / 1048576)
}

/** Grenzen fuer Anhaenge in einem Chat: Server-Grenzen, Chat ueber Servergrenzen, big_files */
internal class AttachLimits(val limits: ChatLimits, val remote: Boolean, val bigFiles: Boolean?)

/**
 * 25.10.: Nachrichten weiterleiten - Text entschluesselt neu senden (Ziel verschluesselt neu, normaler Sendeweg), v1-Anhaenge
 * herunterladen und wie beim normalen Senden neu hochladen. -> (gesendet, nicht weitergeleitete Anhaenge)
 */
private suspend fun forwardMessages(repo: SagaRepository, from: ChatSession, target: Long, msgs: List<ChatMessage>, droppedMark: String): Pair<Int, Int> {
    val t = ChatSession(target, repo.chat)
    t.prepareSend()
    val max = repo.chat.me.value?.limits?.fileMax ?: ChatLimits().fileMax
    var sent = 0
    var dropped = 0
    for (m in msgs.take(ChatSelect.FORWARD_MAX)) {
        val plan = ChatSelect.forwardPlan(m, max, droppedMark) ?: continue
        val files = plan.files.mapNotNull { f ->
            try {
                PendingFile(f.n, f.m, from.download(f))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val lost = plan.files.size - files.size
        val text = if (lost > 0 && plan.dropped == 0) listOf(plan.text, droppedMark).filter { it.isNotBlank() }.joinToString("\n") else plan.text
        dropped += plan.dropped + lost
        if (text.isBlank() && files.isEmpty()) continue
        try {
            t.send(text, files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw io.github.yggdrasil_sh.saga.util.UiTextException(UiText.Res(R.string.cs_forward_failed, repo.friendly(e)))
        }
        sent++
    }
    return sent to dropped
}

/** 25.10.: Auswahl als Absaetze in eine neue Seite bzw. ans Ende einer bestehenden (409 = woanders geaendert: einmal neu laden) */
private suspend fun chatToPage(repo: SagaRepository, target: Long?, title: String, paras: List<String>): Long {
    val blocks = paras.map { io.github.yggdrasil_sh.saga.blocks.BlockDoc.newBlock("paragraph", it) }
    if (target == null) {
        val doc = kotlinx.serialization.json.JsonObject(mapOf("blocks" to kotlinx.serialization.json.JsonArray(blocks)))
        return repo.createPageWith(title, io.github.yggdrasil_sh.saga.blocks.BlockDoc.serialize(doc))
    }
    var tries = 0
    while (true) {
        try {
            repo.withPageLock(target) {
                val p = repo.loadPage(target)
                val bodyEl = p["body"] as? kotlinx.serialization.json.JsonPrimitive
                val body = if (bodyEl != null && bodyEl.isString) bodyEl.content else null
                val doc = io.github.yggdrasil_sh.saga.blocks.BlockDoc.parse(body, io.github.yggdrasil_sh.saga.blocks.BlockDoc.str(p, "format").ifEmpty { "text" }).doc
                val all = io.github.yggdrasil_sh.saga.blocks.BlockDoc.blocks(doc) + blocks
                val next = kotlinx.serialization.json.JsonObject(LinkedHashMap(doc).apply { put("blocks", kotlinx.serialization.json.JsonArray(all)) })
                repo.savePage(target, io.github.yggdrasil_sh.saga.blocks.BlockDoc.str(p, "title"), io.github.yggdrasil_sh.saga.blocks.BlockDoc.serialize(next), io.github.yggdrasil_sh.saga.blocks.BlockDoc.int(p, "rev"))
            }
            return target
        } catch (e: io.github.yggdrasil_sh.saga.data.ApiException) {
            if (e.code != 409 || ++tries > 1) throw e
        }
    }
}

/** 2.5 Chat-Standard: Nachrichtenmenue - Anheften/Lösen (can_pin), Löschen (Admin) (can_moderate), Sperren, Melden */
@Composable
private fun StdMessageMenu(
    repo: SagaRepository, s: ChatSession, m: ChatMessage, close: () -> Unit,
    onReport: () -> Unit, onBlock: (String, String) -> Unit, onModDel: () -> Unit,
) {
    val std = repo.chat.std
    val c = s.summary ?: return
    if (m.tomb || m.sys != null || m.call != null) return
    val r = ChatStd.rights(c, std)
    if (r.pin && m.fstate == null && m.content != null) {
        val pinned = m.id in c.pins
        DropdownMenuItem(text = { Text(stringResource(if (pinned) R.string.std_unpin else R.string.std_pin)) }, leadingIcon = { SagaIcon(SagaIcons.pin, null) }, onClick = {
            close()
            repo.launchAction { s.pin(m.id, !pinned); repo.notify(UiText.Res(if (pinned) R.string.std_unpinned_done else R.string.std_pinned_done)) }
        })
    }
    if (!m.mine && r.moderate) {
        DropdownMenuItem(text = { Text(stringResource(R.string.std_moddel), color = MaterialTheme.colorScheme.error) }, onClick = { close(); onModDel() })
    }
    if (!m.mine && m.sender != null && c.kind != "dm") {
        val p = ChatStd.senderOf(c, m.sender, m.senderHandle)
        val tg = ChatStd.blockTarget(p)
        if (std.block && tg != null && p?.blocked != true && p?.remote != true) {
            DropdownMenuItem(text = { Text(stringResource(R.string.std_block_name, senderName(m))) }, onClick = { close(); onBlock(senderName(m), tg) })
        }
    }
    if (!m.mine && m.sender != null && std.report && m.content != null) {
        DropdownMenuItem(text = { Text(stringResource(R.string.std_report_menu)) }, onClick = { close(); onReport() })
    }
}

/** 2.5 Medien 1: uebersetzte Platzhalter fuer Zitate ohne Text */
@Composable
internal fun mediaLabels(): ChatMedia.Labels = ChatMedia.Labels(
    photo = stringResource(R.string.media_photo), video = stringResource(R.string.cs_l_video), voice = stringResource(R.string.cs_l_voice),
)

/** Kurztext einer Nachricht (Pin-Leiste, Melden): Formatierung entfernt, Erwaehnungen als @Name */
internal fun previewText(m: ChatMessage, s: ChatSession, unknown: String): String {
    val t = m.content?.text.orEmpty()
    if (t.isEmpty()) return m.content?.files?.firstOrNull()?.n.orEmpty()     // CH-21: ohne Emoji
    return ChatStd.plainMentions(io.github.yggdrasil_sh.saga.chat.ChatFmt.strip(t), s.summary, null, unknown)
        .replace(Regex("\\[\\[note:\\d+\\]\\]"), "…").replace(Regex("\\s+"), " ").trim()
}
