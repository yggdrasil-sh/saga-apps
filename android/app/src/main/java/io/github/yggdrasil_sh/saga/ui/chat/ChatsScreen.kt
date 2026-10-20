package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatDeleteLogic
import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.relativeTime
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.launch

/**
 * Bereich Chats: Liste (neueste zuerst, Ungelesen-Zahl) und Unterhaltung. Breit: beides nebeneinander;
 * schmal: Liste bzw. Unterhaltung. Ohne Schluessel auf diesem Geraet zuerst die Einrichtung.
 */
@Composable
fun ChatsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome, chatId: Long?) {
    val svc = repo.chat
    val st by svc.state.collectAsState()
    LaunchedEffect(Unit) {
        svc.refresh()
        svc.loadChats()
    }
    LaunchedEffect(st) { if (st == ChatSetup.READY) svc.loadChats() }

    if (st != ChatSetup.READY) {
        Scaffold(topBar = { SagaTopBar(stringResource(R.string.chats), nav, chrome) }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) { ChatSetupView(svc, st) }
        }
        return
    }
    // Drehen wechselt zwischen ein- und zweispaltig: Unterhaltung samt Entwurf mitnehmen statt neu aufbauen (BUG-021)
    val conversation = remember { movableContentOf { id: Long, two: Boolean -> Conversation(repo, nav, id, twoPane = two) } }
    // Chats eingerichtet: einmal erklaeren und nach Mitteilungen fragen (Nachrichten, Anrufe, Upload-Fortschritt)
    io.github.yggdrasil_sh.saga.ui.common.NotificationPermissionPrompt()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 720.dp
        val open: (Long) -> Unit = { id -> if (chatId == null) nav.push(Dest.Chat(id)) else { nav.pop(); nav.push(Dest.Chat(id)) } }
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(340.dp).fillMaxHeight()) { ChatList(repo, nav, chrome, chatId, open, showBack = chatId == null) }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (chatId != null) conversation(chatId, true)
                    else Text(
                        stringResource(R.string.chat_pick), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
        } else if (chatId != null) {
            conversation(chatId, false)
        } else {
            ChatList(repo, nav, chrome, null, open, showBack = true)
        }
    }
}

@Composable
private fun ChatList(repo: SagaRepository, nav: AppNavigator, chrome: Chrome, selected: Long?, onOpen: (Long) -> Unit, showBack: Boolean) {
    val svc = repo.chat
    val chats by svc.chats.collectAsState()
    val titles by svc.freeTitles.collectAsState()
    val loaded by svc.chatsLoaded.collectAsState()
    var newChat by remember { mutableStateOf(false) }
    var blocks by remember { mutableStateOf(false) }
    val fedV2 by svc.fed.supported.collectAsState()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val noBack = remember { AppNavigator() }                 // breite Ansicht: Liste ohne Zurueck-Pfeil
    // 2.4: Online-Status der Direkt-Chats in der Liste (nur solange die Liste sichtbar ist, hoechstens 30 Chats)
    val chatMe by svc.me.collectAsState()
    val presence by svc.presence.state.collectAsState()
    val watch = remember(chats, chatMe) {
        chats.filter { it.archived != true }.take(30).mapNotNull { io.github.yggdrasil_sh.saga.chat.ReceiptLogic.onlineFor(chatMe, it) }.distinct()
    }
    PresenceWatch(svc.presence, "list", watch)
    val onlineOf = { c: ChatSummary -> io.github.yggdrasil_sh.saga.chat.ReceiptLogic.onlineFor(chatMe, c)?.let { presence[it]?.online } == true }
    Scaffold(
        topBar = { SagaTopBar(stringResource(R.string.chats), if (showBack) nav else noBack, chrome) },
        floatingActionButton = {
            // Bedienungshilfen: Knopf traegt seinen Namen selbst (TalkBack/uiautomator fanden ihn nicht, TT 2.4 P-12)
            io.github.yggdrasil_sh.saga.ui.common.LabeledFab(stringResource(R.string.chat_new), { newChat = true })
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing, onRefresh = { scope.launch { refreshing = true; svc.loadChats(); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.chat_e2e), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp).weight(1f),
                        )
                        // 2.3: Sperrliste (Personen/Server anderer Instanzen) - nur mit Server ab 2.3
                        if (fedV2 == true) TextButton(onClick = { blocks = true }) { Text(stringResource(R.string.fed_blocks_title), style = MaterialTheme.typography.labelMedium) }
                    }
                }
                // 2.1: archivierte Chats in einem eigenen, aufklappbaren Bereich unten (aeltere Server: alles wie bisher)
                // 2.3: Nachrichtenanfragen (Personen anderer Server, noch nicht angenommen) oben in eigenem Bereich
                val (activeAll, archived) = ChatDeleteLogic.split(chats)
                val requests = activeAll.filter { it.request }
                val active = activeAll.filter { !it.request }
                if (requests.isNotEmpty()) {
                    item(key = "requests-header") {
                        Text(
                            stringResource(R.string.fed_requests_n, requests.size), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                        )
                    }
                    items(requests, key = { "q" + it.id }) { c ->
                        ChatListEntry(repo, c, svc.displayName(c), c.id == selected, onlineOf(c), onOpen, onDeleted = { if (c.id == selected) nav.top(Dest.Chats) })
                    }
                    item(key = "requests-end") { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                }
                if (loaded && active.isEmpty() && archived.isEmpty()) {
                    item { Text(stringResource(R.string.chat_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                }
                val nameOf = { c: ChatSummary -> titles[c.id]?.takeIf { c.kind == "free" } ?: svc.displayName(c) }
                items(active, key = { it.id }) { c ->
                    ChatListEntry(repo, c, nameOf(c), c.id == selected, onlineOf(c), onOpen, onDeleted = { if (c.id == selected) nav.top(Dest.Chats) })
                }
                if (archived.isNotEmpty()) {
                    item(key = "archived-header") {
                        ArchivedHeader(archived.size, ChatDeleteLogic.archivedUnread(archived), showArchived) { showArchived = !showArchived }
                    }
                    if (showArchived) items(archived, key = { it.id }) { c ->
                        ChatListEntry(repo, c, nameOf(c), c.id == selected, onlineOf(c), onOpen, onDeleted = { if (c.id == selected) nav.top(Dest.Chats) })
                    }
                }
                item { Box(Modifier.size(88.dp)) }
            }
        }
    }
    if (newChat) NewChatDialog(repo, onDismiss = { newChat = false }) { id -> newChat = false; onOpen(id) }
    if (blocks) FedBlocksDialog(repo) { blocks = false }
}

/** Bereich "Archiviert (n)": aufklappbar, Zaehler der ungelesenen Nachrichten darin */
@Composable
private fun ArchivedHeader(count: Int, unread: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = stringResource(if (open) R.string.action_close else R.string.action_open), onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_archive), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.chatdel_archived_n, count), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        if (unread > 0) {
            val d = androidx.compose.ui.res.pluralStringResource(R.plurals.notifications_unread_n, unread, unread)
            Badge(Modifier.semantics { contentDescription = d }, containerColor = MaterialTheme.colorScheme.outline, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(if (unread > 99) "99+" else "$unread")
            }
        }
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
            Modifier.padding(start = 8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Eintrag der Chatliste mit Aktionen (2.1): Wischen nach links archiviert bzw. holt aus dem Archiv, langes Druecken
 * oeffnet das Menue (Archivieren, Chat löschen …). Aeltere Server: nur Antippen.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatListEntry(repo: SagaRepository, c: ChatSummary, name: String, selected: Boolean, online: Boolean, onOpen: (Long) -> Unit, onDeleted: () -> Unit) {
    val svc = repo.chat
    val supported = ChatDeleteLogic.supported(c)
    var menu by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toggleArchive: () -> Unit = {
        val to = c.archived != true
        repo.launchAction {
            svc.archive(c.id, to)
            repo.notify(
                UiText.Res(if (to) R.string.chatdel_archived_done else R.string.chatdel_unarchived_done),
                UiText.Res(R.string.chatdel_undo),
            ) { repo.launchAction { svc.archive(c.id, !to) } }
        }
    }
    val row = @Composable {
        Box {
            ChatRow(c, name, selected, online, onClick = { onOpen(c.id) }, onLongClick = if (supported) ({ menu = true }) else null)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (c.archived == true) R.string.chatdel_unarchive else R.string.chatdel_archive)) },
                    leadingIcon = { Icon(painterResource(if (c.archived == true) R.drawable.ic_unarchive else R.drawable.ic_archive), null) },
                    onClick = { menu = false; toggleArchive() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chatdel_delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; delete = true },
                )
            }
        }
    }
    if (!supported) {
        row()
    } else {
        // je Archiv-Zustand ein eigener Wisch-Zustand: der Eintrag wechselt danach den Bereich (anderer Platz in der
        // Liste); ein gespeicherter "weggewischt"-Zustand darf beim Wiederauftauchen nicht erneut ausloesen
        val state = key(c.archived == true) { rememberSwipeToDismissBoxState() }
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = false,
            onDismiss = {
                // sofort zurueckschnappen (vor der Antwort des Servers, die den Eintrag verschiebt), dann ausfuehren
                scope.launch { state.snapTo(SwipeToDismissBoxValue.Settled) }
                toggleArchive()
            },
            backgroundContent = {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        stringResource(if (c.archived == true) R.string.chatdel_unarchive else R.string.chatdel_archive),
                        color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge,
                    )
                    Icon(
                        painterResource(if (c.archived == true) R.drawable.ic_unarchive else R.drawable.ic_archive), null,
                        Modifier.padding(start = 8.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            },
        ) { Box(Modifier.background(MaterialTheme.colorScheme.surface)) { row() } }
    }
    if (delete) {
        DeleteChatDialog(repo, c, name, onDismiss = { delete = false }, onDone = { delete = false; onDeleted() }) { all, leave ->
            svc.deleteChat(c.id, all, leave)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(c: ChatSummary, name: String, selected: Boolean, online: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val kindLabel = when (c.kind) {
        "group" -> stringResource(R.string.chat_kind_group)
        "page" -> stringResource(R.string.chat_kind_page)
        "free" -> stringResource(R.string.chat_kind_free)
        else -> stringResource(R.string.chat_kind_dm)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(
                onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) stringResource(R.string.chatdel_actions) else null,
            ).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WithOnlineDot(online) {
            Avatar(name, symbol = if (c.kind == "page") "📄" else null, size = 40, icon = if (c.kind == "group" || c.kind == "free") R.drawable.ic_group else null, avatar = if (c.kind == "dm") c.peer?.avatar else null)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name.ifEmpty { kindLabel }, style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (c.unread > 0 && !c.muted) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (c.muted) {
                    val d = stringResource(R.string.chat_muted)
                    Icon(
                        painterResource(R.drawable.ic_notifications_off), d, Modifier.padding(start = 4.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 2.3: Person eines anderen Servers - Anfrage/Direkt, Herkunft, schreibgeschuetzt
            // Gruppe/Seiten-Chat ueber Servergrenzen (E5): Art, Mitgliederzahl, Heim (wenn anderer Server)
            val sub = if (c.remote) {
                (if (c.request) stringResource(R.string.fed_request_chip) else kindLabel) +
                    (if (c.kind == "dm") (c.peer?.host ?: c.home)?.let { " · $it" }.orEmpty()
                    else " · " + androidx.compose.ui.res.pluralStringResource(R.plurals.fed_people_n, c.memberCount, c.memberCount) +
                        (c.home?.takeIf { !c.homeSelf }?.let { " · $it" }.orEmpty())) +
                    (if (c.readonly) " · " + stringResource(R.string.fed_readonly_chip) else "")
            } else kindLabel
            Text(
                sub + (c.lastAt?.let { " · " + relativeTime(it) } ?: ""), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (c.unread > 0) {
            val d = androidx.compose.ui.res.pluralStringResource(R.plurals.notifications_unread_n, c.unread, c.unread)
            Badge(
                Modifier.semantics { contentDescription = d },
                containerColor = if (c.muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text(if (c.unread > 99) "99+" else "${c.unread}") }
        }
    }
}
