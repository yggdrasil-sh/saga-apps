package io.github.yggdrasil_sh.saga.ui.notifications

import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.live.NotifItem
import io.github.yggdrasil_sh.saga.live.NotifLogic
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.relativeTime
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import kotlinx.coroutines.launch

/** Benachrichtigungen (Glocke): neueste zuerst, ungelesene hervorgehoben, "Alle gelesen", "Ältere laden" */
@Composable
fun NotificationsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val live = repo.live
    val items by live.items.collectAsState()
    val unread by live.unread.collectAsState()
    val more by live.more.collectAsState()
    val loading by live.loading.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { live.loadNotifications() }

    Scaffold(topBar = {
        SagaTopBar(stringResource(R.string.notifications), nav, chrome) {
            // "Alle gelesen" sichtbar als Textknopf (wie im Web); NAV-22: ohne Ungelesene ausgeblendet statt grau (wirkte wie ein Fehler)
            if (unread > 0) SagaTextButton(onClick = { repo.launchAction { live.markAllRead() } }) { Text(stringResource(R.string.notifications_mark_all)) }
            IconButton(onClick = { io.github.yggdrasil_sh.saga.ui.settings.SettingsNav.open(nav, "notifs", replace = true) }) { SagaIcon(SagaIcons.settings, stringResource(R.string.notifications_settings)) }
        }
    }) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; live.loadNotifications(); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
                if (unread > 0) {
                    item {
                        Text(
                            androidx.compose.ui.res.pluralStringResource(R.plurals.notifications_unread_n, unread, unread), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = w.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (items.isEmpty() && !loading) {
                    item {
                        Text(
                            stringResource(R.string.notifications_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = w.padding(24.dp),
                        )
                    }
                }
                if (items.isEmpty() && loading) item { CircularProgressIndicator(Modifier.padding(32.dp)) }
                items(items, key = { it.id }) { n ->
                    NotificationRow(n, w) {
                        if (!n.read) repo.launchAction { live.markRead(listOf(n.id)) }
                        open(repo, nav, n)
                    }
                    HorizontalDivider(w.padding(start = 64.dp))
                }
                if (more) {
                    item {
                        SagaOutlinedButton(onClick = { repo.launchAction { live.loadMore() } }, modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.notifications_load_older))
                        }
                    }
                }
            }
        }
    }
}

/** Antippen: Seite (ggf. Unterhaltung) bzw. Chat oeffnen */
fun open(repo: SagaRepository, nav: AppNavigator, n: NotifItem) {
    if (!NotifLogic.tappable(n)) return
    val chat = n.chat
    val note = n.note
    when {
        chat != null -> nav.push(Dest.Chat(chat.id))
        // 2.3: Systemmeldungen (Backups): Einstellungen bzw. Admin-Bereich
        note == null -> when (val t = NotifLogic.target(n.url)) {
            is NotifLogic.Target.Settings -> io.github.yggdrasil_sh.saga.ui.settings.SettingsNav.open(nav, t.section, replace = false)
            else -> NotifLogic.adminLink(n)?.let { repo.openExternalRequest.value = it }
        }
        note != null -> {
            if (n.threadId != null || n.commentId != null) repo.openCommentsFor.value = note.id to n.blockId?.ifEmpty { null }
            nav.push(Dest.Page(note.id))
        }
    }
}

@Composable
private fun NotificationRow(n: NotifItem, modifier: Modifier, onClick: () -> Unit) {
    val tap = NotifLogic.tappable(n)
    // A11Y-07: "ungelesen" als Zustand der Zeile (TalkBack), der Punkt bleibt dekorativ
    val unreadState = stringResource(R.string.r1_notif_unread)
    Row(
        // NAV-22: ungelesene Zeilen deutlich getoent (dazu fetter Text), nicht nur mit dem Punkt
        modifier.then(if (n.read) Modifier else Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)))
            .then(if (tap) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { if (!n.read) stateDescription = unreadState }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(io.github.yggdrasil_sh.saga.util.PersonName.shown(n.actor?.name, n.actor?.username), icon = if (n.kind.startsWith("chat.")) SagaIcons.chats else if (NotifLogic.isSystem(n)) SagaIcons.cloudUpload else null, avatar = n.actor?.avatar)
        Column(Modifier.weight(1f)) {
            Text(
                n.text, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (n.read) FontWeight.Normal else FontWeight.SemiBold,
                color = if (tap) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A11Y-21: Chat/System/Seite als Liniensymbol statt Emoji; ein eigenes Seitensymbol (Emoji) ist Inhalt und bleibt
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pageIcon = n.note?.icon?.takeIf { it.isNotEmpty() && !it.startsWith("/") }
                val line = when {
                    n.chat != null -> SagaIcons.chats
                    NotifLogic.isSystem(n) -> SagaIcons.warning
                    pageIcon == null -> SagaIcons.file
                    else -> null
                }
                if (line != null) SagaIcon(line, null, Modifier.padding(end = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("$pageIcon ", style = MaterialTheme.typography.bodySmall)
                Text(
                    n.title.orEmpty(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            n.excerpt?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "„$it“", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
            n.created?.let {
                Text(relativeTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))   // A11Y-04: outline ist eine Randfarbe (zu wenig Kontrast fuer Text)
            }
        }
        if (!n.read) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/**
 * Anfangsbuchstabe im Kreis (oder ein einfarbiges Symbol / Emoji); avatar (2.1) = Profilbild vom eigenen Server, liegt
 * ueber den Initialen (die bleiben Platzhalter bzw. Rueckfall bei null/404).
 */
@Composable
fun Avatar(name: String, symbol: String? = null, size: Int = 36, icon: Int? = null, avatar: String? = null) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null && symbol == null && avatar != null) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                style = when { size >= 72 -> MaterialTheme.typography.headlineMedium; size >= 36 -> MaterialTheme.typography.titleSmall; else -> MaterialTheme.typography.labelSmall },
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            io.github.yggdrasil_sh.saga.ui.common.AvatarOverlay(avatar, size.dp)
            return@Box
        }
        if (icon != null) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(icon), null, Modifier.size((size * 0.55f).dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            return@Box
        }
        Text(
            symbol ?: name.trim().firstOrNull()?.uppercase() ?: "?",
            style = when { size >= 72 -> MaterialTheme.typography.headlineMedium; size >= 36 -> MaterialTheme.typography.titleSmall; else -> MaterialTheme.typography.labelSmall },
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
