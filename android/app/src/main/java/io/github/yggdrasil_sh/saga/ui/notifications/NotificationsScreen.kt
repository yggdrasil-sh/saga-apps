package io.github.yggdrasil_sh.saga.ui.notifications

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
            // "Alle gelesen" sichtbar als Textknopf (wie im Web), ohne Ungelesene gesperrt
            TextButton(onClick = { repo.launchAction { live.markAllRead() } }, enabled = unread > 0) { Text(stringResource(R.string.notifications_mark_all)) }
            IconButton(onClick = { io.github.yggdrasil_sh.saga.ui.settings.SettingsNav.open(nav, "notifs", replace = true) }) { Icon(Icons.Filled.Settings, stringResource(R.string.notifications_settings)) }
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
                        OutlinedButton(onClick = { repo.launchAction { live.loadMore() } }, modifier = Modifier.padding(16.dp)) {
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
    val unreadDesc = stringResource(R.string.unread_dot_desc)
    Row(
        modifier.then(if (n.read) Modifier else Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)))
            .then(if (tap) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(io.github.yggdrasil_sh.saga.util.PersonName.shown(n.actor?.name, n.actor?.username), icon = if (n.kind.startsWith("chat.")) R.drawable.ic_chat else if (NotifLogic.isSystem(n)) R.drawable.ic_cloud_upload else null, avatar = n.actor?.avatar)
        Column(Modifier.weight(1f)) {
            Text(
                n.text, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (n.read) FontWeight.Normal else FontWeight.SemiBold,
                color = if (tap) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(if (n.chat != null) "💬" else if (NotifLogic.isSystem(n)) "⚠️" else n.note?.icon?.takeIf { it.isNotEmpty() && !it.startsWith("/") } ?: "📄", n.title).joinToString(" "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            n.excerpt?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "„$it“", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
            n.created?.let {
                Text(relativeTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (!n.read) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = unreadDesc })
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
