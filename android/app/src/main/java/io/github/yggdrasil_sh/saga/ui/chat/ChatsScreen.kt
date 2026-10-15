package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Badge
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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.relativeTime
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
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
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val noBack = remember { AppNavigator() }                 // breite Ansicht: Liste ohne Zurueck-Pfeil
    Scaffold(
        topBar = { SagaTopBar(stringResource(R.string.chats), if (showBack) nav else noBack, chrome) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newChat = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.chat_new)) },
            )
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                if (loaded && chats.isEmpty()) {
                    item { Text(stringResource(R.string.chat_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                }
                items(chats, key = { it.id }) { c ->
                    ChatRow(c, titles[c.id]?.takeIf { c.kind == "free" } ?: svc.displayName(c), c.id == selected) { onOpen(c.id) }
                }
                item { Box(Modifier.size(88.dp)) }
            }
        }
    }
    if (newChat) NewChatDialog(repo, onDismiss = { newChat = false }) { id -> newChat = false; onOpen(id) }
}

@Composable
private fun ChatRow(c: ChatSummary, name: String, selected: Boolean, onClick: () -> Unit) {
    val kindLabel = when (c.kind) {
        "group" -> stringResource(R.string.chat_kind_group)
        "page" -> stringResource(R.string.chat_kind_page)
        "free" -> stringResource(R.string.chat_kind_free)
        else -> stringResource(R.string.chat_kind_dm)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, symbol = if (c.kind == "page") "📄" else null, size = 40, icon = if (c.kind == "group" || c.kind == "free") R.drawable.ic_group else null)
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
            Text(
                kindLabel + (c.lastAt?.let { " · " + relativeTime(it) } ?: ""), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (c.unread > 0) {
            val d = stringResource(R.string.notifications_unread_n, c.unread)
            Badge(
                Modifier.semantics { contentDescription = d },
                containerColor = if (c.muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text(if (c.unread > 99) "99+" else "${c.unread}") }
        }
    }
}
