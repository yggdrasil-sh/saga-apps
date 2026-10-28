package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.LinearProgressIndicator
import io.github.yggdrasil_sh.saga.ui.common.IncomingImage
import io.github.yggdrasil_sh.saga.ui.database.databaseItems
import io.github.yggdrasil_sh.saga.ui.common.clipboardImages
import io.github.yggdrasil_sh.saga.ui.common.prepareImage
import io.github.yggdrasil_sh.saga.ui.common.readImage
import io.github.yggdrasil_sh.saga.ui.common.rememberClipboardHasImage
import io.github.yggdrasil_sh.saga.util.ImageData
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import androidx.annotation.StringRes
import io.github.yggdrasil_sh.saga.util.resolve
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.yggdrasil_sh.saga.util.UiText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.yggdrasil_sh.saga.data.FreshEntries
import io.github.yggdrasil_sh.saga.data.StartPage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.InlineStyle
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.ncSettings
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.publish.PublishModel
import io.github.yggdrasil_sh.saga.ui.publish.PublishSheet
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import io.github.yggdrasil_sh.saga.data.PageShareInfo
import io.github.yggdrasil_sh.saga.ui.common.relativeTime
import io.github.yggdrasil_sh.saga.ui.comments.CommentLogic
import io.github.yggdrasil_sh.saga.ui.comments.CommentsModel
import io.github.yggdrasil_sh.saga.ui.comments.CommentsSheet
import io.github.yggdrasil_sh.saga.ui.share.PageShareSheet
import io.github.yggdrasil_sh.saga.ui.share.ShareLogic
import io.github.yggdrasil_sh.saga.ui.share.ShareModel
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign

private val PAGE_LINK = Regex("^/notes#(-?\\d+)$")

/** Text des Kopier-Dialogs sichern (BUG-065); ueber COPY_DRAFT_MAX Zeichen nicht (Bundle-Grenze) */
internal const val COPY_DRAFT_MAX = 100_000
internal val CopyDraftSaver: Saver<MutableState<String?>, String> = Saver(
    save = { it.value?.takeIf { s -> s.length <= COPY_DRAFT_MAX } },
    restore = { mutableStateOf(it) },
)

/** Blocktypen fuer "Neu" und "Typ" (Name, Typ, Ebene) */
private data class BlockChoice(@param:StringRes val label: Int, val type: String, val level: Int)

private val BLOCK_TYPES = listOf(
    BlockChoice(R.string.block_text, "paragraph", 0),
    BlockChoice(R.string.block_heading, "header", 1),
    BlockChoice(R.string.block_heading, "header", 2),
    BlockChoice(R.string.block_heading, "header", 3),
    BlockChoice(R.string.block_bulleted, "unordered", 0),
    BlockChoice(R.string.block_numbered, "ordered", 0),
    BlockChoice(R.string.block_checklist, "checklist", 0),
    BlockChoice(R.string.block_quote, "quote", 0),
    BlockChoice(R.string.block_callout, "callout", 0),
    BlockChoice(R.string.block_code, "code", 0),
    BlockChoice(R.string.block_toggle, "toggle", 0),
    BlockChoice(R.string.block_divider, "delimiter", 0),
)

/** Ueberschriften mit Ebene ("Ueberschrift 2"), sonst nur der Name */
@Composable
private fun BlockChoice.title(): String = if (type == "header") stringResource(R.string.block_heading, level) else stringResource(label)

/** Eine Seite: Titel + Bloecke, bearbeitbar; speichert automatisch (verzoegert und beim Verlassen) */
@Composable
fun PageScreen(id: Long, repo: SagaRepository, nav: AppNavigator, chrome: Chrome, appScope: CoroutineScope) {
    // neu angemeldet/gekoppelt (anderes Konto): Seite neu oeffnen - Rolle, Eigentuemer und Entwuerfe gehoeren zum Konto
    val signIns by repo.signInCount.collectAsState()
    val ed = remember(id, signIns) {
        PageEditor(id, repo, appScope).also { e ->
            e.titleOf = { pid -> repo.pages.value.firstOrNull { it.id == pid }?.title?.takeIf { it.isNotBlank() } }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val needsLogin by repo.needsLogin.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var styleMenu by remember { mutableStateOf(false) }
    val startId by repo.startPage.collectAsState()
    val startSupported by repo.startPageSupported.collectAsState()

    LaunchedEffect(ed) { ed.load() }
    DisposableEffect(ed) {
        onDispose {
            ed.close()                                               // speichern (laeuft im App-Scope weiter)
            // fix27 E4: aus einer Vorlage angelegt und unveraendert geschlossen -> endgueltig verwerfen
            val fresh = repo.freshEntries[id]
            if (fresh != null) {
                val open = nav.stack.any { it == io.github.yggdrasil_sh.saga.ui.nav.Dest.Page(id) }
                if (FreshEntries.shouldDiscard(fresh, ed.title, ed.lastInputAt > 0L, ed.dirty, open)) repo.discardFresh(id)
                else if (!open) repo.freshEntries.remove(id)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { ed.flush() }      // App verlassen: Entwurf auf Platte + speichern
    LaunchedEffect(needsLogin) { if (!needsLogin && ed.dirty) ed.scheduleSave(500) }   // nach erneuter Anmeldung nachholen

    val onLink: (String) -> Unit = { href ->
        val m = PAGE_LINK.find(href)
        when {
            m != null -> nav.push(Dest.Page(m.groupValues[1].toLong()))
            href.startsWith("http://") || href.startsWith("https://") -> openUrl(context, href)
            href.startsWith("mailto:") -> openUrl(context, href)
            href.startsWith("/") -> openUrl(context, repo.absoluteUrl(href))
        }
    }
    val env = remember(ed) { BlockEnv(ed, repo, onLink) }
    PageWidgetsForeground(env)                                         // 2.5: Widget-Werte beim Zurueckkehren neu
    // Bilder einfuegen (Zwischenablage, Tastatur): hochladen wie im Web-Editor, dann Bild-Block hinter dem Block mit dem Cursor
    var uploading by remember { mutableIntStateOf(0) }
    val insertImages: (List<IncomingImage>, JsonPath?) -> Unit = { list, path ->
        when {
            list.isEmpty() || !ed.editable -> list.forEach { it.release() }
            // Seite eines anderen Servers: nur, wenn dessen Server Hochladen kann (2.3, Datei liegt dort)
            !ed.can(io.github.yggdrasil_sh.saga.data.RemoteCaps.UPLOAD) -> {
                list.forEach { it.release() }
                repo.notify(UiText.Res(R.string.image_remote_page))
            }
            else -> scope.launch {
                uploading++
                try {
                    val max = repo.chat.me.value?.limits?.fileMax ?: io.github.yggdrasil_sh.saga.chat.ChatLimits().fileMax
                    var at = path
                    for (img in list) {
                        val prepared = readImage(context, img, 4 * max)?.let { prepareImage(it, max) }
                        if (prepared == null) {
                            repo.notify(UiText.Res(R.string.image_unsupported))
                            continue
                        }
                        val (data, mime) = prepared
                        // fremde Seite (Rolle Bearbeiten): die Datei gehoert dem Eigentuemer (wie Web/iOS); Seite eines anderen
                        // Servers (negative ID, 2.3): Datei liegt beim Server der Seite (X-Saga-Note negativ)
                        val url = repo.uploadImage(data, ImageData.nameFor("bild", mime), mime, if (ed.caps.foreign) ed.id else null)
                        at = ed.insertImage(at, url) ?: at
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    repo.notify(if (e is io.github.yggdrasil_sh.saga.data.ApiException) e.text else UiText.Res(R.string.image_upload_failed))
                } finally {
                    uploading--
                }
            }
        }
    }
    val imageSink: (IncomingImage, JsonPath) -> Unit = remember(ed) { { img, p -> insertImages(listOf(img), p) } }
    // 2.4: "/"- und "+"-Menue mit allen Blocktypen (Bild, Datei, Tabelle, Spalten, Einbetten, Verweis ...)
    val inserter = rememberBlockInserter(ed, repo, insertImages, onOpenPage = { pid -> nav.push(Dest.Page(pid)) })
    val slashNeeds = inserter.needs
    androidx.compose.runtime.SideEffect {
        env.slash = inserter::start
        env.slashNeeds = slashNeeds
    }
    var moveOpen by remember { mutableStateOf(false) }
    val uploadingDesc = stringResource(R.string.image_uploading)
    var confirmTrash by remember { mutableStateOf(false) }
    var conflictDialog by remember { mutableStateOf(false) }
    // Versionsverlauf/Export (2.3: auch Bearbeiter und Seiten anderer Server)
    var showVersions by remember(id) { mutableStateOf(false) }
    val serverVersion by repo.serverVersion.collectAsState()
    val server23 = io.github.yggdrasil_sh.saga.util.Versions.atLeast(serverVersion, "2.3.0")
    val ownPages by repo.pages.collectAsState()
    val sharedLocal by repo.sharedPages.collectAsState()
    val fedShared by repo.fedShared.collectAsState()
    val allPages = remember(ownPages, sharedLocal, fedShared) { ownPages + sharedLocal + fedShared }
    val export = rememberPageExport(repo, id) { ed.title }
    // Veroeffentlichen: Dialog (Bottom Sheet); aeltere Server ohne /publish -> Menuepunkt ausblenden
    var publishModel by remember(id) { mutableStateOf<PublishModel?>(null) }
    var publishHidden by remember(id) { mutableStateOf(false) }
    val openPublish: () -> Unit = {
        publishModel = PublishModel(id, repo, repo.absoluteUrl("").trimEnd('/')) { ed.applyPublish(it) }
    }
    LaunchedEffect(ed.conflict) { if (ed.conflict) conflictDialog = true }

    // Teilen + Kommentare (Server 1.3)
    val caps = ed.caps
    val comments = remember(ed) { CommentsModel(id, repo, PageShareInfo()) }
    // Live-Kanal (1.4): Anwesenheit, fremde Aenderungen, neue Kommentare
    val liveSt = rememberPageLive(id, repo, ed, comments, appScope)
    // 2.5 E2: Block-Markierungen der anderen Anwesenden; sync1 (synchronisierte Kopien bearbeiten), wenn der Server synced1 meldet
    val features25 by repo.serverFeatures.collectAsState()
    androidx.compose.runtime.SideEffect {
        env.blockPresence = PageLiveLogic.byBlock(liveSt.users)
        ed.syncSupported = "synced1" in features25 && !ed.isRemote
        ed.mentionSupported = "mention1" in features25 && !ed.isRemote
    }
    // KI-Assistent: null = zu, 0 = "Frag deine Seiten", 1 = "Diese Seite"
    val aiStatus by repo.ai.status.collectAsState()
    val aiOn = aiStatus?.enabled == true
    val aiPersona = io.github.yggdrasil_sh.saga.ai.AiPersonaLogic.effective(aiStatus)
    val chatOn = repo.live.chatEnabled.collectAsState().value && repo.chat.state.collectAsState().value == io.github.yggdrasil_sh.saga.chat.ChatSetup.READY
    var assistant by remember { mutableStateOf<Int?>(null) }
    // aus einer Benachrichtigung/Mitteilung: Kommentare (Unterhaltung am Block) gleich zeigen
    val openComments by repo.openCommentsFor.collectAsState()
    LaunchedEffect(openComments, ed.fetched) {
        val oc = openComments
        if (oc != null && oc.first == id && ed.fetched) {
            repo.openCommentsFor.value = null
            if (ed.caps.showComments) {
                try {
                    comments.load()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                val b = oc.second
                if (b != null) comments.openBlock(b) else comments.openAll()
            }
        }
    }
    LaunchedEffect(comments, ed.share) {
        comments.update(ed.share)
        if (ed.share.commentsOpen > 0 && ed.share.supported) comments.load()     // Punkte an den Bloecken (ungelesen)
    }
    var shareModel by remember(ed) { mutableStateOf<ShareModel?>(null) }
    val openShareFor by repo.openShareFor.collectAsState()
    LaunchedEffect(openShareFor, ed.fetched, caps.canShare) {
        if (openShareFor == id && ed.fetched) {
            repo.openShareFor.value = null
            if (caps.canShare) shareModel = ShareModel(id, repo)
        }
    }
    /** Lesen/Kommentieren: angetippter Block (zeigt 💬+ zum Kommentieren) */
    var selectedBlock by remember(id) { mutableStateOf<String?>(null) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    /** eigener Text nach "Neue Version laden" (Kopier-Dialog) */
    // eigener Text nach "Neue Version laden": der Entwurf auf Platte ist schon geloescht - der Dialog-Text muss den
    // Prozess-Tod ueberleben (typisch: zum Einfuegen in eine andere App gewechselt, BUG-065); sehr lange Texte nicht
    // (Grenze des Sicherungs-Bundles)
    var copyDraft by rememberSaveable(saver = CopyDraftSaver) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        // G4: "Kein Zugriff": Kopfzeile ohne Titel/Symbol der Seite
        // grosser Titel im Seitenkopf sichtbar: in der Leiste ausgeblendet, beim Wegscrollen weich eingeblendet
        val titleInBar by remember {
            androidx.compose.runtime.derivedStateOf {
                val h = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "header" }
                h == null || -h.offset > h.size * 0.55f
            }
        }
        SagaTopBar(if (ed.noAccess) "" else ed.title.ifEmpty { if (ed.fetched) stringResource(R.string.page_untitled) else "" }, nav, chrome, titleVisible = titleInBar) {
            PresenceAvatars(liveSt)
            SaveState(ed)
            // KI-Assistent (1.6): nur sichtbar, wenn der Server ihn fuer diese Person anbietet
            if (aiOn && ed.fetched && !ed.isDatabase) {
                val aiDesc = aiPersona?.let { stringResource(R.string.ai_ask_name, it.name) } ?: stringResource(R.string.ai_title)
                IconButton(onClick = { assistant = 0 }, modifier = Modifier.semantics { contentDescription = aiDesc }) {
                    // 2.1: Profilbild der KI (ohne Bild: ✦ auf Akzentfarbe); aeltere Server: bisheriges ✦
                    if (aiPersona != null) io.github.yggdrasil_sh.saga.ui.ai.AiAvatar(aiPersona, 26)
                    else Text("✦", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clearAndSetSemantics { })
                }
            }
            // Kommentare dezent: "💬 n" nur bei offenen Unterhaltungen, Punkt bei ungelesenen
            // fremde (foederierte) Seite: Kommentar-Knopf immer, wenn die Rolle Kommentieren erlaubt (der Hinweis verspricht es)
            if (ed.fetched && caps.showComments && (comments.openCount > 0 || (ed.isRemote && caps.canComment))) {
                CommentsButton(comments.openCount, comments.unreadCount) { comments.openAll() }
            }
            if (ed.fetched && caps.canFavorite) {
                IconButton(onClick = { repo.launchAction { ed.setPinned(!ed.pinned) } }) {
                    // wie iOS: kein Favorit = Umriss, Favorit = gefuellt in der Akzentfarbe
                    val favDesc = stringResource(if (ed.pinned) R.string.favorite_remove else R.string.favorite_add)
                    if (ed.pinned) Icon(Icons.Filled.Star, favDesc, tint = MaterialTheme.colorScheme.primary)
                    else Icon(painterResource(R.drawable.ic_star_border), favDesc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                PageStyleMenu(
                    styleMenu, ed.font, ed.small, onDismiss = { styleMenu = false },
                    onFont = { f -> styleMenu = false; repo.launchAction { ed.setFont(f) } },
                    onSmall = { on -> styleMenu = false; repo.launchAction { ed.setSmall(on) } },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (ed.fetched && caps.canShare && !ed.isRemote) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_menu)) }, onClick = {
                            menu = false
                            shareModel = ShareModel(id, repo)
                        })
                    }
                    if (ed.fetched && caps.showComments) {
                        val nres = comments.resolvedCount
                        DropdownMenuItem(
                            text = {
                                val res = LocalResources.current
                                Text(stringResource(R.string.comments_menu) + if (nres > 0) " · " + res.getQuantityString(R.plurals.comments_resolved_n, nres, nres) else "")
                            },
                            onClick = {
                                menu = false
                                comments.openAll()
                            },
                        )
                    }
                    // G9 (2.5): Schrift/kleiner Text - nur wenn der Server die Felder kennt und die Seite bearbeitbar ist
                    if (ed.fetched && !ed.isDatabase && ed.styleSupported && headerEditable(ed)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.gest_font_menu)) }, onClick = {
                            menu = false
                            styleMenu = true
                        })
                    }
                    if (ed.fetched && !ed.isDatabase && caps.canLock && !ed.isRemote) {
                        DropdownMenuItem(text = { Text(stringResource(if (ed.locked) R.string.page_unlock else R.string.page_lock)) }, onClick = {
                            menu = false
                            repo.launchAction { ed.setLocked(!ed.locked) }
                        })
                    }
                    // Seiten-Chat (1.5): alle mit Zugriff auf die Seite, Ende-zu-Ende-verschluesselt; Seiten anderer Server (2.3),
                    // wenn ihr Server Gruppen-Chats ueber Servergrenzen kann (Heim = Server der Seite)
                    if (chatOn && ed.fetched && ed.can(io.github.yggdrasil_sh.saga.data.RemoteCaps.CHATGROUPS)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_page_menu)) }, onClick = {
                            menu = false
                            repo.launchAction { nav.push(Dest.Chat(repo.chat.createPage(id).id)) }
                        })
                    }
                    if (aiOn && ed.fetched && !ed.isDatabase) {
                        DropdownMenuItem(text = { Text(aiPersona?.let { stringResource(R.string.ai_ask_name, it.name) } ?: stringResource(R.string.ai_title)) }, onClick = {
                            menu = false
                            assistant = 1
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.page_reload)) }, onClick = {
                        menu = false
                        scope.launch { ed.save(); ed.load() }
                    })
                    // Versionsverlauf (Bearbeiten ab Server 2.3) und Export - Seiten anderer Server je nach remote.caps
                    if (ed.fetched && PageToolsLogic.canVersions(ed.share.role, ed.isRemote, ed.remoteCaps, server23, ed.isDatabase)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.versions_title)) }, onClick = {
                            menu = false
                            ed.flush()
                            showVersions = true
                        })
                    }
                    if (ed.fetched && PageToolsLogic.canExport(ed.isRemote, ed.remoteCaps)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.export_md)) }, onClick = {
                            menu = false
                            repo.launchAction { ed.saveAndWait(); export(false) }
                        })
                        if (PageToolsLogic.hasChildren(id, allPages)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.export_zip)) }, onClick = {
                                menu = false
                                repo.launchAction { ed.saveAndWait(); export(true) }
                            })
                        }
                    }
                    if (ed.fetched && ed.publishSupported && !ed.template && !publishHidden && caps.canPublish && !ed.isRemote) {
                        // Menue einheitlich ohne Symbole (der Zustand "Öffentlich" steht im Seitenkopf)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.publish_menu)) },
                            onClick = {
                                menu = false
                                openPublish()
                            },
                        )
                    }
                    // 2.5: Lokaler Graph (Nachbarschaft der Seite) - nur eigene/freigegebene Seiten dieses Servers ab 2.5
                    if (ed.fetched && !ed.isRemote && io.github.yggdrasil_sh.saga.ui.graph.GraphSupport.available(serverVersion, repo.server.value)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.graph_local_menu)) }, onClick = {
                            menu = false
                            nav.push(io.github.yggdrasil_sh.saga.ui.nav.Dest.Graph(id))
                        })
                    }
                    // fix27 E2: eigene Startseite festlegen / zuruecksetzen (Server ab 2.5 mit start_page)
                    if (ed.fetched && !ed.noAccess && startSupported && StartPage.eligible(id)) {
                        val isStart = startId == id
                        DropdownMenuItem(text = { Text(stringResource(if (isStart) R.string.start_page_reset else R.string.start_page_set)) }, onClick = {
                            menu = false
                            scope.launch {
                                try {
                                    repo.setStartPage(if (isStart) null else id)
                                    repo.notify(UiText.Res(if (isStart) R.string.start_page_was_reset else R.string.start_page_was_set))
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    repo.notify(UiText.Res(R.string.start_page_failed, repo.friendly(e)))
                                }
                            }
                        })
                    }
                    // "App-Link kopieren" / "Im Web-Editor öffnen": Link in den Editor (nicht die oeffentliche Seite)
                    DropdownMenuItem(text = { Text(stringResource(R.string.page_copy_link)) }, onClick = {
                        menu = false
                        copy(context, repo.absoluteUrl("notes") + "#$id")
                        repo.notify(UiText.Res(R.string.page_link_copied))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.page_open_web_editor)) }, onClick = {
                        menu = false
                        ed.flush()
                        openUrl(context, repo.absoluteUrl("notes") + "#$id")
                    })
                    // 2.4: Verschieben (eigene Seiten; geteilte nur innerhalb ihres Bereichs mit Rolle Bearbeiten)
                    val meta = allPages.firstOrNull { it.id == id }
                    if (ed.fetched && !ed.isRemote && meta != null && io.github.yggdrasil_sh.saga.data.MoveLogic.canMove(meta, allPages)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.mv_menu)) }, onClick = {
                            menu = false
                            ed.flush()
                            moveOpen = true
                        })
                    }
                    if (ed.fetched && caps.canArchive && caps.canTrash && !ed.isRemote) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.page_archive)) }, onClick = {
                            menu = false
                            repo.launchAction {
                                ed.saveAndWait()
                                repo.archivePage(id, true)
                                if (!nav.pop()) nav.top(Dest.Today)
                                repo.notify(UiText.Res(R.string.page_archived), UiText.Res(R.string.action_undo)) {
                                    repo.launchAction { repo.archivePage(id, false) }
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.page_trash), color = MaterialTheme.colorScheme.error) }, onClick = {
                            menu = false
                            confirmTrash = true
                        })
                    }
                }
            }
        }
        if (ed.conflict && !conflictDialog) {
            ConflictBanner(ed.conflictBy) { conflictDialog = true }
        }
        PageLiveBanner(liveSt) {
            scope.launch {
                ed.saveAndWait()
                ed.load()
                liveSt.changedBy = null
            }
        }

        // 2.4: Datenbank-Seite - Zustand der Ansicht (bleibt beim Scrollen erhalten)
        val dbState = if (ed.isDatabase) io.github.yggdrasil_sh.saga.ui.database.rememberDbViewState(id, repo) else null
        val dbWide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val blocks = BlockDoc.blocks(ed.doc)
            val keys = remember(blocks) {
                val seen = HashSet<String>()
                // Praefix: eine Block-ID wie "header"/"footer" darf nie mit den festen Eintraegen kollidieren
                blocks.mapIndexed { i, b ->
                    val k = "b:" + (BlockDoc.id(b) ?: "i$i")
                    if (seen.add(k)) k else "dup-$i-$k"
                }
            }
            // 2.5 P6-2: Erwaehnungen zeigen den aktuellen Namen (aus den Personen-Verzeichnissen der Datenbank-Antworten)
            val people by repo.peopleNames.collectAsState()
            val mentionName = remember(people) { { ref: String -> people[ref] } }
            CompositionLocalProvider(LocalPageImageSink provides imageSink, LocalMentionName provides mentionName) {
            // G9 (2.5): Schrift und kleiner Text der Seite gelten fuer Titel, Inhalt und Eigenschaften
            MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = pageTypography(MaterialTheme.typography, ed.font, ed.small), shapes = MaterialTheme.shapes) {
            // Rand als contentPadding (nicht padding): das Titelbild darf bis an den Rand reichen, ohne abgeschnitten zu werden
            LazyColumn(Modifier.widthIn(max = if (dbState != null) 1400.dp else 820.dp + 36.dp).fillMaxSize(), state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp)) {
                item(key = "header") {
                    PageHeader(
                        ed, repo, onPublish = if (ed.template || publishHidden || !caps.canPublish || ed.isRemote) null else openPublish,
                        live = liveSt, onVersions = { showVersions = true }, onSettings = { io.github.yggdrasil_sh.saga.ui.settings.SettingsNav.open(nav, "public", replace = true) },
                    )
                }
                if (ed.fetched && !ed.isDatabase) {
                    val counts = if (caps.showComments) comments.blockCounts else emptyMap()
                    val unread = comments.unreadBlocks
                    val activeTop = ed.active?.blockPath?.invoke()?.getOrNull(1) as? Int
                    itemsIndexed(blocks, key = { i, _ -> keys[i] }) { i, b ->
                        val obj = b as? JsonObject
                        if (obj != null) {
                            // stabiles Pfad-Objekt: unveraenderte Bloecke werden beim Tippen uebersprungen (Strong Skipping)
                            val path = remember(i) { listOf<Any>("blocks", i) }
                            val bid = BlockDoc.id(obj)
                            val n = bid?.let { counts[it] } ?: 0
                            // 💬+ am ausgewaehlten Block (Bearbeiten: Block mit dem Cursor; Lesen/Kommentieren: angetippter Block)
                            val add = bid != null && n == 0 && caps.canComment && (if (ed.editable) activeTop == i else selectedBlock == bid)
                            val tap = !ed.editable && caps.canComment && bid != null
                            val focused = comments.visible && comments.block != null && comments.block == bid
                            Row(
                                Modifier.padding(vertical = 4.dp)
                                    .then(if (focused) Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
                                    .then(
                                        if (tap) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                            selectedBlock = if (selectedBlock == bid) null else bid
                                        } else Modifier,
                                    ),
                            ) {
                                Box(Modifier.weight(1f)) { BlockView(obj, path, env) }
                                if (bid != null && (n > 0 || add)) {
                                    BlockBubble(n, bid in unread) {
                                        selectedBlock = null
                                        comments.openBlock(bid)
                                        scope.launch { listState.animateScrollToItem(i + 1) }      // Block bleibt ueber dem Sheet sichtbar
                                    }
                                }
                            }
                        }
                    }
                    item(key = "backlinks") { BacklinksList(ed, repo) { pid -> nav.push(Dest.Page(pid)) } }
                    item(key = "footer") {
                        // wie Notion: in den freien Bereich unter dem letzten Block tippen -> weiterschreiben
                        Spacer(
                            Modifier.fillMaxWidth().height(180.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = ed.editable) { ed.continueWriting() },
                        )
                    }
                }
                if (ed.fetched && ed.isDatabase && dbState != null) {
                    // 2.4: Ansichten, Filter, Sortierung, neue Eintraege, Werte bearbeiten - Zeilen direkt in dieser Liste
                    databaseItems(dbState, repo, onOpen = { pid -> nav.push(Dest.Page(pid)) }, wide = dbWide)
                }
            }
            }
            }
            if (dbState != null) io.github.yggdrasil_sh.saga.ui.database.DatabaseDialogs(dbState, repo)
            if (ed.loading && !ed.fetched) CircularProgressIndicator(Modifier.padding(top = 80.dp))
            if (uploading > 0 || inserter.busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).semantics { contentDescription = uploadingDesc })
        }

        val active = ed.active
        val clipHasImage = rememberClipboardHasImage()
        // 2.3: "Nextcloud-Datei" im Einfuegen-Menue nur mit verknuepfter Nextcloud (aeltere Server: nie)
        var ncConnected by remember { mutableStateOf(false) }
        LaunchedEffect(ed.editable, ed.isRemote) {
            if (ed.editable && !ed.isRemote) ncConnected = try {
                repo.ncSettings()?.let { it.enabled && it.connected } == true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        if (active != null && ed.editable) {
            EditorToolbar(
                ed, active, onLink, onPasteImage = if (clipHasImage && ed.can(io.github.yggdrasil_sh.saga.data.RemoteCaps.UPLOAD)) { { insertImages(clipboardImages(context), active.blockPath()) } } else null,
                repo = repo, ncInsert = ncConnected && !ed.isRemote, inserter = inserter,
            )
        }
    }

    if (showVersions) VersionsDialog(ed, repo) { showVersions = false }
    BlockInserterDialogs(inserter, ed, repo)
    if (moveOpen) {
        allPages.firstOrNull { it.id == id }?.let { meta ->
            io.github.yggdrasil_sh.saga.ui.pages.MovePageDialog(repo, meta, onDismiss = { moveOpen = false })
        } ?: run { moveOpen = false }
    }

    assistant?.let { t ->
        io.github.yggdrasil_sh.saga.ui.ai.AssistantSheet(
            repo, ed, t, onOpenPage = { pid -> assistant = null; nav.push(Dest.Page(pid)) }, onDismiss = { assistant = null },
        )
    }

    publishModel?.let { m ->
        PublishSheet(
            m, scope,
            onDismiss = {
                if (m.unsupported) publishHidden = true
                publishModel = null
            },
            onOpenPage = { pid ->
                publishModel = null
                nav.push(Dest.Page(pid))
            },
        )
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text(stringResource(R.string.page_trash_confirm)) },
            text = { Text(stringResource(R.string.page_trash_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    repo.launchAction {
                        ed.saveAndWait()
                        repo.trashPage(id)
                        if (!nav.pop()) nav.top(Dest.Today)
                        repo.notify(UiText.Res(R.string.page_trashed), UiText.Res(R.string.action_undo)) {
                            repo.launchAction { repo.restorePage(id) }
                        }
                    }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    shareModel?.let { m ->
        PageShareSheet(
            m, scope, ownerName = ed.share.owner?.name.orEmpty(), link = repo.absoluteUrl("notes") + "#$id",
            onDismiss = { shareModel = null },
            onOpenParent = { pid ->
                shareModel = null
                repo.openShareFor.value = pid                // dort den Dialog wieder oeffnen (wie im Web)
                nav.push(Dest.Page(pid))
            },
            onCopied = { repo.notify(UiText.Res(R.string.share_link_copied)) },
        )
    }

    var commentMention by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val cmHook = if ("mention1" in features25 && !ed.isRemote) io.github.yggdrasil_sh.saga.ui.comments.CommentMentionHook(comments.people) { commentMention = it } else null
    commentMention?.let { cb ->
        MentionPicker(ed, repo, onDismiss = { commentMention = null }) { ref, name ->
            commentMention = null
            comments.people = comments.people + (ref to io.github.yggdrasil_sh.saga.data.MentionPerson(name))
            cb(ref)
        }
    }
    if (comments.visible) androidx.compose.runtime.CompositionLocalProvider(io.github.yggdrasil_sh.saga.ui.comments.LocalCommentMentions provides cmHook) {
        CommentsSheet(
            comments, scope,
            quote = { bid -> CommentLogic.snippet(ed.doc, bid) },
            onJump = { bid ->
                val i = CommentLogic.topLevelIndex(ed.doc, bid)
                comments.close()
                if (i >= 0) scope.launch { listState.animateScrollToItem(i + 1) }
            },
        )
    }

    // Konflikt mit einer anderen Person (Server 1.3): "Neue Version laden" (Hauptknopf) / "Meine Version behalten" (Rueckfrage)
    val by = ed.conflictBy
    if (conflictDialog && ed.conflict && by != null) {
        AlertDialog(
            onDismissRequest = { conflictDialog = false },
            title = { Text(stringResource(R.string.conflict_by_title, by)) },
            text = { Text(stringResource(R.string.conflict_by_text)) },
            confirmButton = {
                TextButton(onClick = {
                    conflictDialog = false
                    repo.launchAction { copyDraft = ed.loadServerVersion() }
                }) { Text(stringResource(R.string.conflict_load_new)) }
            },
            dismissButton = {
                TextButton(onClick = { conflictDialog = false; confirmOverwrite = true }) { Text(stringResource(R.string.conflict_keep_version)) }
            },
        )
    }
    if (confirmOverwrite && ed.conflict) {
        val who = ed.conflictBy.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text(stringResource(R.string.conflict_overwrite_title, who)) },
            text = { Text(stringResource(R.string.conflict_overwrite_text, who)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOverwrite = false
                    repo.launchAction { ed.keepMine() }
                }) { Text(stringResource(R.string.conflict_overwrite), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    copyDraft?.let { text ->
        CopyTextDialog(stringResource(R.string.draft_copy_title), stringResource(R.string.draft_copy_text), text) { copyDraft = null }
    }
    ed.lost?.let { l ->
        CopyTextDialog(
            stringResource(if (l.readOnly) R.string.lost_readonly_title else R.string.lost_none_title),
            stringResource(if (l.readOnly) R.string.lost_readonly_text else R.string.lost_none_text), l.text,
        ) { scope.launch { ed.closeLost() } }
    }

    if (conflictDialog && ed.conflict && by == null) {
        val copyTitle = stringResource(R.string.conflict_copy_title, ed.title.ifEmpty { stringResource(R.string.page_untitled) })
        AlertDialog(
            onDismissRequest = { conflictDialog = false },
            title = { Text(stringResource(R.string.conflict_title)) },
            text = { Text(stringResource(R.string.conflict_text)) },
            confirmButton = {
                TextButton(onClick = {
                    conflictDialog = false
                    repo.launchAction { ed.keepMine() }
                }) { Text(stringResource(R.string.conflict_keep_mine)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    conflictDialog = false
                    repo.launchAction {
                        val copy = ed.saveCopyAndLoadServer(copyTitle)
                        if (copy != null) repo.notify(UiText.Res(R.string.conflict_copy_saved), UiText.Res(R.string.action_open)) { nav.push(Dest.Page(copy)) }
                    }
                }) { Text(stringResource(R.string.conflict_save_copy)) }
            },
        )
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Saga", text))
}

@Composable
private fun PageHeader(ed: PageEditor, repo: SagaRepository, onPublish: (() -> Unit)?, live: PageLiveState? = null, onSettings: () -> Unit = {}, onVersions: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var iconPicker by remember { mutableStateOf(false) }
    if (iconPicker) IconPickerDialog(ed, repo) { iconPicker = false }
    val cover = ed.cover.takeIf { !ed.noAccess && ed.fetched }
    Column(Modifier.fillMaxWidth().padding(top = if (cover != null) 0.dp else 8.dp, bottom = 8.dp)) {
        // wie iOS/Web: Titelbild ueber die volle Breite, Symbol halb darueber; fehlt eins davon: dezent anbieten
        if (cover != null) PageCover(ed, repo, cover, pad = 18.dp)
        if (ed.icon.isNotEmpty() && !ed.noAccess) {
            Box(
                Modifier.then(if (cover != null) Modifier.pullUp(36.dp) else Modifier)
                    .clickable(enabled = headerEditable(ed) || ed.locked, onClickLabel = stringResource(R.string.page_icon)) {
                        if (ed.locked) repo.notify(UiText.Res(R.string.page_header_locked)) else iconPicker = true
                    },
            ) {
                // Wie iOS: Symbol direkt auf dem Titelbild, ohne Kachel dahinter - transparente Bilder bleiben transparent
                PageIcon(ed.icon, repo, ed.isDatabase, size = 72.dp)
            }
            Spacer(Modifier.height(if (cover != null) 10.dp else 8.dp))
        } else if (cover != null) Spacer(Modifier.height(12.dp))
        HeaderAddRow(ed, repo) { iconPicker = true }
        if (ed.fetched) {
            val titleStyle = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (!ed.titleEditable) {                         // gesperrt oder fremde Seite mit Lesen/Kommentieren
                Text(ed.title.ifEmpty { stringResource(R.string.page_untitled) }, style = titleStyle)
            } else {
                BasicTextField(
                    value = ed.title, textStyle = titleStyle, readOnly = ed.pendingDraft != null,
                    onValueChange = { v ->
                        // 2.4 (Test P-17): Return im Titel springt in den ersten Block, statt dort weiterzuschreiben
                        if ('\n' in v) {
                            ed.editTitle(v.replace("\n", ""))
                            val first = BlockDoc.blocks(ed.doc).firstOrNull() as? JsonObject
                            ed.requestFocus(first?.let { ed.fieldKeyFor(it, listOf("blocks", 0)) })
                        } else ed.editTitle(v)
                    },
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {
                        val first = BlockDoc.blocks(ed.doc).firstOrNull() as? JsonObject
                        ed.requestFocus(first?.let { ed.fieldKeyFor(it, listOf("blocks", 0)) })
                    }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            // fix27 E4: neuer Eintrag aus einer Vorlage - Vorlagenname als Platzhalter ("Neues Buch")
                            if (ed.title.isEmpty()) Text(repo.freshEntries[ed.id]?.placeholder?.ifBlank { null } ?: stringResource(R.string.page_untitled), style = titleStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)))
                            inner()
                        }
                    },
                )
            }
        }
        // Fremde Seite (G1): unter dem Titel "Geteilt von {Name} · Du kannst …" (kein Knopf), darunter wer zuletzt
        // geaendert/angelegt hat - beide gleich gross (Sekundaertext)
        if (ed.fetched && !ed.isDatabase && !ed.noAccess) TagsRow(ed, repo)       // wie iOS/Web: Tags unter dem Titel
        if (ed.fetched && ed.caps.foreign) SharedByLabel(ed.share)
        if (ed.fetched) MetaLine(ed.share)
        if (ed.fetched && ed.template && ed.parentId != null) {   // A02: Vorlage einer Datenbank erkennbar machen
            Text(
                stringResource(R.string.tpl_page_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (ed.fetched) RowProperties(ed, repo)                 // Datenbank-Zeile: Eigenschaften (bearbeitbar)
        // Foederation (2.0): Seite liegt auf einer anderen Saga-Instanz
        ed.remoteHost?.takeIf { ed.fetched }?.let { host ->
            Text(
                stringResource(
                    when {
                        !ed.caps.canComment -> R.string.fed_page_hint_read
                        // 2.3: Server der Seite kann Datenbanken/Unterseiten usw. - nur Loeschen und Freigaben bleiben dort
                        ed.can(io.github.yggdrasil_sh.saga.data.RemoteCaps.DB) -> R.string.fed_page_hint_full
                        else -> R.string.fed_page_hint
                    },
                    host.ifEmpty { "?" },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (ed.fetched && live != null) PresenceLine(live)
        val via = if (!ed.published) ed.publishedVia else null
        if (ed.locked || ed.published || via != null) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ed.locked) {
                    AssistChip(
                        onClick = { repo.launchAction { ed.setLocked(false) } }, enabled = ed.caps.canLock,
                        label = { Text(stringResource(R.string.page_locked_chip)) },
                        leadingIcon = { Icon(Icons.Filled.Lock, null, Modifier.size(16.dp)) },
                    )
                }
                if (ed.published || via != null) PublicChip(via?.title, onPublish)
            }
        }
        if (ed.converted) {
            Text(
                stringResource(R.string.page_converted),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
        ed.info?.let {
            Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp).clickable { ed.info = null })
        }
        ed.mergeHint?.let { h -> MergeHintLine(h, onVersions = onVersions) { ed.mergeHint = null } }
        ed.error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        if (ed.offline) {
            Text(
                stringResource(R.string.offline_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp),
            )
        }
        // G2: kein "Wiederherstellen", wenn die Seite (inzwischen) nur lesbar ist
        if (ed.share.role.canEdit && !ed.locked && ed.lost == null) {
            ed.pendingDraft?.let { d -> DraftBanner(d.stamp, onRestore = { ed.restoreDraft() }, onDiscard = { ed.discardDraft() }) }
        }
        if (ed.noAccess && !ed.loading) {
            NoAccess(onRetry = { scope.launch { ed.load() } }, onShared = { repo.showSharedRequest.value++ })
        } else if (!ed.loading && !ed.fetched) {                   // Laden fehlgeschlagen: nichts Bearbeitbares zeigen
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { ed.load() } }) { Text(stringResource(R.string.action_retry)) }
                // 403 "oeffentlicher Name fehlt" (Seite einer anderen Instanz): Weg zu den Einstellungen
                if (ed.needsPublicName) OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.nav_settings)) }
            }
        }
    }
}

/**
 * Hinweis "Oeffentlich" im Seitenkopf (wie "🌐 Öffentlich" im Web): Globus + Text, antippbar -> Dialog.
 * viaTitle != null: oeffentlich als Unterseite dieser Seite (Titel gekuerzt wie im Web).
 */
@Composable
private fun PublicChip(viaTitle: String?, onClick: (() -> Unit)?) {
    val untitled = stringResource(R.string.page_untitled)
    val full = viaTitle?.ifEmpty { untitled }
    val short = full?.let { if (it.length > 28) it.take(27) + "…" else it }
    val label = short?.let { stringResource(R.string.publish_badge_via, it) } ?: stringResource(R.string.publish_badge)
    val hint = full?.let { stringResource(R.string.publish_badge_via_hint, it) } ?: stringResource(R.string.publish_badge_hint)
    AssistChip(
        onClick = { onClick?.invoke() }, enabled = onClick != null,
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_public), null, Modifier.size(AssistChipDefaults.IconSize), tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.semantics { contentDescription = hint },
    )
}

/** Entwurf vom letzten Mal (weicht vom Server-Stand ab): Wiederherstellen oder Verwerfen - wie iOS/Linux */
@Composable
private fun DraftBanner(stamp: Long, onRestore: () -> Unit, onDiscard: () -> Unit) {
    val time = io.github.yggdrasil_sh.saga.ui.common.dateTimeText(stamp)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Text(
                stringResource(R.string.draft_banner, time), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.draft_discard)) }
                TextButton(onClick = onRestore) { Text(stringResource(R.string.draft_restore)) }
            }
        }
    }
}

/** Werkzeugleiste ueber der Tastatur: Block einfuegen/umwandeln, Formatierung, Link, verschieben, loeschen */
@Composable
private fun EditorToolbar(
    ed: PageEditor, active: ActiveField, onLink: (String) -> Unit, onPasteImage: (() -> Unit)? = null,
    repo: io.github.yggdrasil_sh.saga.data.SagaRepository? = null, ncInsert: Boolean = false,
    /** 2.4: "+" oeffnet die Auswahl aller Blocktypen (mit Suche) */
    inserter: BlockInserter? = null,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val focus = LocalFocusManager.current
    var addMenu by remember { mutableStateOf(false) }
    var ncPick by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var inlineColorMenu by remember { mutableStateOf(false) }
    var mathDialog by remember { mutableStateOf<String?>(null) }
    var mentionPick by remember { mutableStateOf(false) }
    var movePick by remember { mutableStateOf(false) }
    val trig = ed.mentionTrigger
    LaunchedEffect(trig) { if (trig != null && trig.fieldKey == active.key) mentionPick = true }
    val blockPath: JsonPath = active.blockPath()
    val isText = active.toggle != null

    // Design 2.5: schwebende Werkzeugleiste (Pille ueber der Tastatur, wie die Expressive-Toolbar)
    val toolBg = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = toolBg, shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column {
            // Nr. 16: Hoch/Runter/Loeschen/Fertig fest rechts, nur Einfuegen und Formate scrollen (vorher lagen die Knoepfe
            // am Ende der scrollenden Leiste ausserhalb des Bildschirms)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // A16: scrollende Formatleiste mit Hinweis am rechten Rand (Verlauf + Pfeil), solange es weiter geht -
            // sonst wirkt der angeschnittene Knopf (U) kaputt und Durchgestrichen/Link findet man nicht
            val toolScroll = rememberScrollState()
            val toolScope = rememberCoroutineScope()
            Box(Modifier.weight(1f)) {
            Row(
                Modifier.horizontalScroll(toolScroll).padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { if (inserter != null) inserter.pickerAfter = blockPath else addMenu = true }) { Icon(Icons.Filled.Add, stringResource(R.string.block_insert)) }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        BLOCK_TYPES.forEach { choice ->
                            val (_, type, level) = choice
                            DropdownMenuItem(text = { Text(choice.title()) }, onClick = {
                                addMenu = false
                                ed.insertBlockAfter(blockPath, BlockDoc.newBlock(type, level = if (level == 0) 2 else level))
                            })
                        }
                        // 2.3: Datei aus der eigenen Nextcloud (nur eigene Seiten dieses Servers mit verknuepfter Nextcloud)
                        if (ncInsert) DropdownMenuItem(text = { Text(stringResource(R.string.nc_file_insert)) }, onClick = { addMenu = false; ncPick = true })
                    }
                }
                // Bild aus der Zwischenablage (nur sichtbar, wenn dort eins liegt) - das Feld selbst nimmt nur Text an
                if (onPasteImage != null) {
                    IconButton(onClick = onPasteImage) { Icon(painterResource(R.drawable.ic_paste_image), stringResource(R.string.paste_image)) }
                }
                if (active.itemPath() == null && ed.block(blockPath)?.let { BlockDoc.mainText(it) != null } == true) {
                    Box {
                        IconButton(onClick = { typeMenu = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.block_change_type)) }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            BLOCK_TYPES.filter { it.type != "delimiter" }.forEach { choice ->
                                val (_, type, level) = choice
                                DropdownMenuItem(text = { Text(choice.title()) }, onClick = {
                                    typeMenu = false
                                    ed.convertBlock(blockPath, type, if (level == 0) 2 else level)
                                })
                            }
                        }
                    }
                } else if (active.itemPath() != null) {
                    Box {
                        IconButton(onClick = { typeMenu = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.list_change_type)) }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            listOf(R.string.block_bulleted to "unordered", R.string.block_numbered to "ordered", R.string.block_checklist to "checklist", R.string.block_text to "paragraph").forEach { (label, t) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                    typeMenu = false
                                    ed.convertBlock(blockPath, t)
                                })
                            }
                        }
                    }
                }
                // G3 (2.5): Block-Farbe (Text oder Hintergrund) aus der Palette
                val styleBlock = ed.block(blockPath)
                if (io.github.yggdrasil_sh.saga.blocks.Gestalten.canStyle(styleBlock)) {
                    Box {
                        val cur = styleBlock?.let { io.github.yggdrasil_sh.saga.blocks.Gestalten.style(it) }
                        TextButton(onClick = { colorMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gest_color)) }
                        BlockColorMenu(colorMenu, cur, onDismiss = { colorMenu = false }) { c, bg ->
                            colorMenu = false
                            ed.setBlockStyle(blockPath, c, bg)
                        }
                    }
                }
                if (isText) {
                    FormatButton(stringResource(R.string.format_bold_short), stringResource(R.string.format_bold), active.styleOn(InlineStyle.BOLD), active.hasSelection(), fontWeight = FontWeight.Bold) { active.toggle.invoke(InlineStyle.BOLD) }
                    FormatButton(stringResource(R.string.format_italic_short), stringResource(R.string.format_italic), active.styleOn(InlineStyle.ITALIC), active.hasSelection(), fontStyle = FontStyle.Italic) { active.toggle.invoke(InlineStyle.ITALIC) }
                    FormatButton(stringResource(R.string.format_underline_short), stringResource(R.string.format_underline), active.styleOn(InlineStyle.UNDERLINE), active.hasSelection(), deco = TextDecoration.Underline) { active.toggle.invoke(InlineStyle.UNDERLINE) }
                    FormatButton(stringResource(R.string.format_strike_short), stringResource(R.string.format_strike), active.styleOn(InlineStyle.STRIKE), active.hasSelection(), deco = TextDecoration.LineThrough) { active.toggle.invoke(InlineStyle.STRIKE) }
                    TextButton(onClick = { linkDialog = true }, enabled = active.hasSelection(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.format_link)) }
                    // 2.5 E2 (P6-8): Farbe der Auswahl, Inline-Mathe, Erwaehnung
                    if (active.setColor != null) Box {
                        TextButton(onClick = { inlineColorMenu = true }, enabled = active.hasSelection(), modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.e2_inline_color))
                        }
                        InlineColorMenu(inlineColorMenu, active.colorAt?.invoke(false), active.colorAt?.invoke(true), onDismiss = { inlineColorMenu = false }) { c, bg ->
                            inlineColorMenu = false
                            active.setColor.invoke(c, bg)
                        }
                    }
                    if (active.setMath != null) {
                        TextButton(onClick = { mathDialog = active.mathAtCursor?.invoke() ?: "" }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.e2_inline_math))
                        }
                    }
                    if (active.insertMention != null && ed.mentionSupported) {
                        TextButton(onClick = { mentionPick = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("@") }
                    }
                    active.linkAtCursor?.invoke()?.let { href ->
                        TextButton(onClick = { onLink(href) }) { Text(stringResource(R.string.link_open)) }
                    }
                }
                Spacer(Modifier.width(if (toolScroll.maxValue > 0) 36.dp else 0.dp))   // letzter Knopf nicht unter dem Hinweis
            }
            if (toolScroll.canScrollForward) {
                val bg = toolBg
                val px = with(androidx.compose.ui.platform.LocalDensity.current) { 144.dp.toPx() }
                Box(
                    Modifier.align(Alignment.CenterEnd).width(40.dp).height(48.dp)
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(bg.copy(alpha = 0f), bg, bg))),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    IconButton(onClick = { toolScope.launch { toolScroll.animateScrollBy(px) } }, modifier = Modifier.width(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.toolbar_more))
                    }
                }
            }
            }
            // 2.5: Widget - Einstellungen nur im Web (Etappe 1): Seite mit Block-Anker im Browser
            val wBlock = ed.block(blockPath)?.takeIf { BlockDoc.type(it) == "widget" && active.itemPath() == null }
            if (wBlock != null && !ed.isRemote && repo != null) {
                TextButton(onClick = {
                    ed.flush()
                    openUrl(context, io.github.yggdrasil_sh.saga.blocks.Gestalten.webUrl(repo.absoluteUrl("notes"), ed.id, BlockDoc.id(wBlock)))
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pw_edit_web)) }
            }
            // 2.5 E2 (move1): Block auf eine andere Seite verschieben (nur Seiten dieses Servers)
            val moveFeatures = repo?.serverFeatures?.collectAsState()?.value.orEmpty()
            if (repo != null && active.itemPath() == null && ed.editable && io.github.yggdrasil_sh.saga.blocks.MoveLogic.available(ed.id, moveFeatures) &&
                ed.block(blockPath)?.let { BlockDoc.id(it) } != null) {
                TextButton(onClick = { movePick = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.e2_move)) }
            }
            val dbBlock = ed.block(blockPath)?.takeIf { BlockDoc.type(it) == "database" && active.itemPath() == null }
            if (dbBlock != null) {                                  // 2.5: Datenbanktitel des Blocks ein-/ausblenden (wie Web/iOS)
                val hidden = (BlockDoc.data(dbBlock)["hide_title"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                TextButton(onClick = {
                    ed.updateBlock(blockPath) { b ->
                        val d = LinkedHashMap(BlockDoc.data(b)).apply { if (hidden) remove("hide_title") else put("hide_title", kotlinx.serialization.json.JsonPrimitive(true)) }
                        kotlinx.serialization.json.JsonObject(LinkedHashMap(b).apply { put("data", kotlinx.serialization.json.JsonObject(d)) })
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (hidden) R.string.db_title_show else R.string.db_title_hide)) }
            }
            androidx.compose.material3.VerticalDivider(Modifier.height(28.dp))
            Row(Modifier.padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ed.moveBlock(blockPath, -1) }) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.block_move_up)) }
                IconButton(onClick = { ed.moveBlock(blockPath, 1) }) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.block_move_down)) }
                IconButton(onClick = {
                    val ip = active.itemPath()
                    if (ip != null) ed.removeItem(blockPath, ip) else {
                        // 2.4: Block loeschen mit "Rueckgaengig" (holt ihn unveraendert an dieselbe Stelle zurueck)
                        val undo = ed.deleteBlockUndoable(blockPath)
                        if (undo != null) repo?.notify(UiText.Res(R.string.ed24_block_deleted), UiText.Res(R.string.action_undo)) { undo() }
                    }
                }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                IconButton(onClick = { focus.clearFocus(); ed.active = null }) { Icon(Icons.Filled.Check, stringResource(R.string.action_done)) }
            }
            }
        }
    }

    if (ncPick && repo != null) {
        NextcloudPickerDialog(repo, onDismiss = { ncPick = false }) { data ->
            ncPick = false
            ed.insertBlockAfter(blockPath, kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive(BlockDoc.newId()))
                put("type", kotlinx.serialization.json.JsonPrimitive("nextcloud"))
                put("data", data)
            }, focus = false)
        }
    }

    mathDialog?.let { cur ->
        var tex by remember { mutableStateOf(cur) }
        AlertDialog(
            onDismissRequest = { mathDialog = null },
            title = { Text(stringResource(R.string.e2_inline_math)) },
            text = {
                OutlinedTextField(value = tex, onValueChange = { tex = it.take(io.github.yggdrasil_sh.saga.blocks.RenderPreview.TEX_MAX) }, singleLine = true,
                    label = { Text(stringResource(R.string.e2_inline_math_tex)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            },
            confirmButton = {
                TextButton(onClick = { mathDialog = null; active.setMath?.invoke(tex) }, enabled = tex.isNotBlank()) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = { TextButton(onClick = { mathDialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (movePick && repo != null) {
        val own by repo.pages.collectAsState()
        val shared by repo.sharedPages.collectAsState()
        val pool = remember(own, shared) { io.github.yggdrasil_sh.saga.blocks.MoveLogic.targets(own + shared, ed.id) }
        val bid = ed.block(blockPath)?.let { BlockDoc.id(it) }
        PagePickerDialog(stringResource(R.string.e2_move_title), pool, repo, onDismiss = { movePick = false }) { target ->
            movePick = false
            if (bid != null) moveBlocksUi(ed, repo, listOf(bid), target)
        }
    }
    if (mentionPick && repo != null) {
        MentionPicker(ed, repo, onDismiss = { mentionPick = false; ed.mentionTrigger = null }) { ref, name ->
            mentionPick = false
            active.insertMention?.invoke(ref, name)
        }
    }

    if (linkDialog) {
        var href by remember { mutableStateOf(active.linkAtCursor?.invoke() ?: "https://") }
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text(stringResource(R.string.format_link)) },
            text = {
                OutlinedTextField(value = href, onValueChange = { href = it }, singleLine = true, label = { Text(stringResource(R.string.link_address_label)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    linkDialog = false
                    val h = href.trim()
                    if (h.isEmpty() || h == "https://") active.setLink?.invoke("")
                    else if (h.startsWith("https://") || h.startsWith("http://") || h.startsWith("mailto:") || PAGE_LINK.matches(h)) active.setLink?.invoke(h)
                    else ed.info = UiText.Res(R.string.link_invalid)
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { linkDialog = false; active.setLink?.invoke("") }) { Text(stringResource(R.string.link_remove)) }
            },
        )
    }
}

/** Format-Knopf als Umschalter: 48 dp, TalkBack liest Namen und Zustand (an/aus) */
@Composable
private fun FormatButton(
    label: String, description: String, on: Boolean, enabled: Boolean,
    fontWeight: FontWeight? = null, fontStyle: FontStyle? = null, deco: TextDecoration? = null, onClick: () -> Unit,
) {
    IconToggleButton(
        checked = on, onCheckedChange = { onClick() }, enabled = enabled,
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.goldSoft,
            checkedContentColor = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.onGoldSoft,
        ),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(label, fontWeight = fontWeight, fontStyle = fontStyle, textDecoration = deco, fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** Titelleiste: speichert gerade / gespeichert / nicht gespeichert (wie "Gespeichert ✓" im Web) */
@Composable
private fun SaveState(ed: PageEditor) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        ed.saving -> CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        ed.conflict -> {}                                   // das Konflikt-Banner zeigt das (kein zweites Warnsymbol)
        ed.error != null -> Icon(Icons.Filled.Warning, stringResource(R.string.save_state_unsaved), tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 4.dp))
        // als Symbol statt "Speichert…" neben dem Titel (der Titel bleibt lesbar)
        ed.dirty -> Icon(painterResource(R.drawable.ic_cloud_upload), stringResource(R.string.save_state_pending), tint = color, modifier = Modifier.padding(end = 4.dp).size(20.dp))
        ed.savedAt != null -> Icon(Icons.Filled.Done, stringResource(R.string.save_state_saved), tint = color, modifier = Modifier.padding(end = 4.dp).size(20.dp))
    }
}

// ---------------------------------------------------------------- Teilen und Kommentare (Server 1.3)

/**
 * G1: "Geteilt von {Name} · Du kannst lesen" unter dem Titel fremder Seiten (kein Knopf) - einfarbiges
 * Gruppen-Symbol (G9) und dieselbe Groesse/Farbe wie die Zeile "Zuletzt geändert von …" darunter
 */
@Composable
private fun SharedByLabel(info: PageShareInfo) {
    val text = stringResource(R.string.shared_by, info.owner?.name.orEmpty()) + " · " + stringResource(ShareLogic.roleYou(info.role))
    Row(Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        // Symbol an der ersten Zeile (das Etikett kann umbrechen)
        Icon(painterResource(R.drawable.ic_group), null, Modifier.padding(top = 1.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** "Angelegt von {name}" / "Zuletzt geändert von {name} · vor …" - nur, wenn es nicht man selbst war */
@Composable
private fun MetaLine(info: PageShareInfo) {
    val parts = ArrayList<String>()
    info.createdBy?.takeIf { !it.me && it.name.isNotEmpty() }?.let { parts.add(stringResource(R.string.meta_created_by, it.name)) }
    val up = info.updatedBy
    val at = info.updated
    if (up != null && !up.me && up.name.isNotEmpty() && at != null) parts.add(stringResource(R.string.meta_updated_by, up.name, relativeTime(at)))
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** G4: Seite geloescht oder nicht (mehr) geteilt - alles mittig, "Erneut versuchen" und "Zu „Mit mir geteilt“" */
@Composable
private fun NoAccess(onRetry: () -> Unit, onShared: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, start = 8.dp, end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.no_access_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp),     // dunkel sonst schwarz
        )
        Text(
            stringResource(R.string.no_access_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
        Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShared) { Text(stringResource(R.string.no_access_to_shared)) }
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/**
 * Konflikt (Nur Android): M3-Banner unter der Kopfzeile - ein Warnsymbol, Text ohne ⚠ und TextButton
 * "Version wählen" (oeffnet den Konflikt-Dialog). Die Kopfzeile zeigt dann kein zweites Warnsymbol.
 */
@Composable
private fun ConflictBanner(by: String?, onChoose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    by?.let { stringResource(R.string.conflict_by_banner, it) } ?: stringResource(R.string.conflict_banner),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp).weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onChoose) { Text(stringResource(R.string.conflict_choose), color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        }
    }
    HorizontalDivider()
}

/** Kopfzeile: Sprechblase + Zahl offener Unterhaltungen, Punkt bei ungelesenen (48 dp) */
@Composable
private fun CommentsButton(open: Int, unread: Int, onClick: () -> Unit) {
    val res = LocalResources.current
    val desc = (if (open == 0) res.getString(R.string.comments_add) else res.getQuantityString(R.plurals.comments_open_threads, open, open)) +
        if (unread > 0) " · " + res.getQuantityString(R.plurals.comments_unread, unread, unread) else ""
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_comment), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (open > 0) Text("$open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        if (unread > 0) Box(Modifier.padding(start = 4.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

/** Am Block: kleine Zahl (offene Unterhaltungen) bzw. "💬+" am ausgewaehlten Block - dezent, 48 dp Trefferflaeche */
@Composable
private fun BlockBubble(count: Int, unread: Boolean, onClick: () -> Unit) {
    val res = LocalResources.current
    val desc = if (count > 0) res.getQuantityString(R.plurals.comments_block_threads, count, count) else stringResource(R.string.comments_add)
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_comment), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (count > 0) "$count" else "+", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp),
                )
                if (unread) Box(Modifier.padding(start = 3.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

/**
 * G3: Zustandsdialoge (Konflikt-Entwurf, Kein Zugriff mehr, Nur noch lesen) einheitlich: Titel, kurzer Text,
 * Textfeld nur so hoch wie noetig (hoechstens ~40 % der Fensterhoehe), Hauptknopf "Kopieren", zweiter "Schließen"
 */
@Composable
private fun CopyTextDialog(title: String, text: String, draft: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val desc = stringResource(R.string.your_text)
    val copied = stringResource(R.string.text_copied)
    val maxField = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.4f).toDp() }.coerceAtLeast(120.dp)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                Text(text)
                Surface(
                    color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(max = maxField),
                ) {
                    SelectionContainer {
                        Text(
                            draft, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(10.dp).semantics { contentDescription = desc },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                copy(context, draft)
                android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.text_copy)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}

/** 2.5 P3: dezenter Hinweis nach dem Zusammenfuehren; bei lost/title_lost mit Weg zu den Versionen */
@Composable
private fun MergeHintLine(h: MergeHint, onVersions: (() -> Unit)?, onDismiss: () -> Unit) {
    Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(when {
                h.versions -> R.string.e2_merged_lost
                h.conflicts > 0 -> R.string.e2_merged_conflicts
                else -> R.string.e2_merged_restored
            }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).clickable { onDismiss() },
        )
        if (h.versions && onVersions != null) {
            TextButton(onClick = { onDismiss(); onVersions() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.e2_merged_versions)) }
        }
    }
}

/** 2.5 E2 (move1): verschieben mit Rueckgaengig und verstaendlichen Fehlern (403/423/507, foederiert) */
internal fun moveBlocksUi(ed: PageEditor, repo: io.github.yggdrasil_sh.saga.data.SagaRepository, ids: List<String>, target: io.github.yggdrasil_sh.saga.data.PageMeta) {
    repo.launchAction(onError = {}) {
        try {
            val (req, res) = ed.moveBlocksTo(ids, target.id)
            val prev = ed.lastMovePrev
            val name = target.title.ifBlank { "…" }
            repo.notify(UiText.Res(R.string.e2_move_done, name), UiText.Res(R.string.action_undo)) {
                repo.launchAction { ed.undoMove(req, res, prev) }
            }
            if (res.unsigned > 0) repo.notify(UiText.Res(R.string.e2_move_unsigned))
        } catch (e: io.github.yggdrasil_sh.saga.data.ApiException) {
            val msg = when (io.github.yggdrasil_sh.saga.blocks.MoveLogic.error(e.code, e.body)) {
                io.github.yggdrasil_sh.saga.blocks.MoveLogic.Error.FORBIDDEN -> R.string.e2_move_forbidden
                io.github.yggdrasil_sh.saga.blocks.MoveLogic.Error.LOCKED -> R.string.e2_move_locked
                io.github.yggdrasil_sh.saga.blocks.MoveLogic.Error.QUOTA -> R.string.e2_move_quota
                io.github.yggdrasil_sh.saga.blocks.MoveLogic.Error.CONFLICT -> R.string.e2_move_conflict
                io.github.yggdrasil_sh.saga.blocks.MoveLogic.Error.FEDERATED -> R.string.e2_move_federated
                else -> null
            }
            if (msg != null) repo.notify(UiText.Res(msg)) else repo.notify(e.text)
        }
    }
}
