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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.defaultMinSize
import io.github.yggdrasil_sh.saga.ui.theme.SagaPill
import io.github.yggdrasil_sh.saga.ui.theme.SagaSectionTitle
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

/**
 * Benachrichtigungen ("Alle anzeigen" aus dem Glocken-Blatt): Design-Runde 4 (NAV-03/22) gebuendelt wie im Web - eine
 * Zeile je Gespraechseinheit, Abschnitte "Neu" (ungelesen) und "Früher"; "Alle gelesen" nur, wenn es Ungelesenes gibt.
 */
@Composable
fun NotificationsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val live = repo.live
    val items by live.items.collectAsState()
    val unreadRows by NotificationCounts.unreadGroups.collectAsState()
    val more by live.more.collectAsState()
    val loading by live.loading.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { live.loadNotifications() }
    val (fresh, earlier) = remember(items) { NotifGroups.sections(items) }

    Scaffold(topBar = {
        SagaTopBar(stringResource(R.string.notifications), nav, chrome) {
            // NAV-22: ohne Ungelesene ausgeblendet statt grau (wirkte wie ein Fehler)
            if (fresh.isNotEmpty() || unreadRows > 0) SagaTextButton(onClick = { repo.launchAction { live.markAllRead() } }) { Text(stringResource(R.string.notifications_mark_all)) }
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
                if (items.isEmpty() && !loading) {
                    item { NotifEmpty(w) }
                }
                if (items.isEmpty() && loading) item { CircularProgressIndicator(Modifier.padding(32.dp)) }
                notifSections(fresh, earlier, w) { n ->
                    repo.launchAction { live.markRead(n) }
                    open(repo, nav, n)
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

/** Leerer Zustand (Glocke und Bildschirm) */
@Composable
internal fun NotifEmpty(modifier: Modifier, compact: Boolean = false) {
    io.github.yggdrasil_sh.saga.ui.common.SagaLineEmptyState(
        stringResource(R.string.notifications_empty), SagaIcons.bell, modifier,
        text = stringResource(R.string.r4n_empty_hint), compact = compact,
    )
}

/** Abschnitte "Neu" / "Früher" (Anzahl als Pille; Ueberschriften fuer TalkBack) */
internal fun androidx.compose.foundation.lazy.LazyListScope.notifSections(
    fresh: List<NotifItem>,
    earlier: List<NotifItem>,
    w: Modifier,
    onTap: (NotifItem) -> Unit,
) {
    if (fresh.isNotEmpty()) {
        item(key = "h-new") { SagaSectionTitle(stringResource(R.string.r4n_section_new), w.padding(top = 4.dp), count = fresh.size) }
        items(fresh, key = { "u" + NotifGroups.head(it) }) { n ->
            NotifGroupRow(n, w) { onTap(n) }
            HorizontalDivider(w.padding(start = 64.dp))
        }
    }
    if (earlier.isNotEmpty()) {
        item(key = "h-old") { SagaSectionTitle(stringResource(R.string.r4n_section_earlier), w.padding(top = 8.dp)) }
        items(earlier, key = { "r" + NotifGroups.head(it) }) { n ->
            NotifGroupRow(n, w) { if (NotifLogic.tappable(n)) onTap(n) }
            HorizontalDivider(w.padding(start = 64.dp))
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

/** Satz einer Zeile: gebuendelt mit Zahl (Plural je Sprache), sonst der fertige Satz des Servers */
@Composable
internal fun notifSentence(n: NotifItem): String {
    val names = NotifGroups.namesShort(NotifGroups.who(n))
    return when (NotifGroups.sentence(n)) {
        NotifGroups.Sentence.MESSAGES -> pluralStringResource(R.plurals.r4n_g_messages, n.count, n.count)
        NotifGroups.Sentence.MESSAGES_FROM -> pluralStringResource(R.plurals.r4n_g_messages_from, n.count, n.count, names)
        NotifGroups.Sentence.CALLS -> pluralStringResource(R.plurals.r4n_g_calls, n.count, n.count)
        NotifGroups.Sentence.VIDEO_CALLS -> pluralStringResource(R.plurals.r4n_g_video_calls, n.count, n.count)
        NotifGroups.Sentence.COMMENTS -> pluralStringResource(R.plurals.r4n_g_comments, n.count, n.count)
        NotifGroups.Sentence.COMMENTS_MENTION -> pluralStringResource(R.plurals.r4n_g_comments_mention, n.count, n.count)
        null -> n.text
    }
}

/**
 * Eine (gebuendelte) Zeile: Avatar/Symbol, Satz (fett wenn ungelesen), Ort (Chat/Seite), Personen, Zeit; rechts Anzahl
 * und Ungelesen-Punkt. Ungelesen: getoenter Hintergrund + fett + Punkt + Zustand fuer TalkBack (NAV-22, A11Y-07).
 */
@Composable
internal fun NotifGroupRow(n: NotifItem, modifier: Modifier, onClick: () -> Unit) {
    val tap = NotifLogic.tappable(n)
    val unreadState = stringResource(R.string.r1_notif_unread)
    val sentence = notifSentence(n)
    val names = NotifGroups.who(n)
    Row(
        modifier.then(if (n.read) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)))
            .then(if (tap) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { if (!n.read) stateDescription = unreadState }
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = when {
            NotifLogic.isSystem(n) -> SagaIcons.cloudUpload
            n.kind == "call.missed" -> SagaIcons.phoneMissed
            n.kind.startsWith("chat.") && (NotifGroups.isGroup(n) && names.size > 1 || n.chat?.kind.let { it != null && it != "dm" }) -> SagaIcons.chats
            n.actor == null && n.kind.startsWith("chat.") -> SagaIcons.chats
            else -> null
        }
        Avatar(io.github.yggdrasil_sh.saga.util.PersonName.shown(n.actor?.name, n.actor?.username), icon = icon, avatar = n.actor?.avatar)
        Column(Modifier.weight(1f)) {
            Text(
                sentence, style = MaterialTheme.typography.bodyMedium,
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
                    n.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Personen einer Gruppe (sofern nicht schon im Satz "(von A, B)")
            if (NotifGroups.isGroup(n) && names.size > 1 && NotifGroups.sentence(n) != NotifGroups.Sentence.MESSAGES_FROM) {
                Text(
                    NotifGroups.namesShort(names), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
            n.excerpt?.takeIf { it.isNotBlank() && !NotifGroups.isGroup(n) }?.let {
                Text(
                    "„$it“", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
            n.created?.let {
                Text(relativeTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))   // A11Y-04
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Anzahl steht schon im Satz (TalkBack liest sie dort) - die Pille ist nur fuers Auge
            if (n.count > 1) SagaPill(n.count.toString(), Modifier.clearAndSetSemantics { })
            if (!n.read) Box(Modifier.padding(top = 2.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
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
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null && symbol == null && avatar != null) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                style = when { size >= 72 -> MaterialTheme.typography.headlineMedium; size >= 36 -> MaterialTheme.typography.titleSmall; else -> MaterialTheme.typography.labelSmall },
                color = MaterialTheme.colorScheme.onSurface,
            )
            io.github.yggdrasil_sh.saga.ui.common.AvatarOverlay(avatar, size.dp)
            return@Box
        }
        if (icon != null) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(icon), null, Modifier.size((size * 0.55f).dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            return@Box
        }
        Text(
            symbol ?: name.trim().firstOrNull()?.uppercase() ?: "?",
            style = when { size >= 72 -> MaterialTheme.typography.headlineMedium; size >= 36 -> MaterialTheme.typography.titleSmall; else -> MaterialTheme.typography.labelSmall },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
