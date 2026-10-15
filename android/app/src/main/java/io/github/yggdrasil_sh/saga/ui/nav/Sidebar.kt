package io.github.yggdrasil_sh.saga.ui.nav

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.common.pageTitle
import io.github.yggdrasil_sh.saga.util.resolve
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.data.PageTree
import io.github.yggdrasil_sh.saga.data.SharedTree
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.heightIn
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.SectionHeader
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch

private data class TreeRow(val page: PageMeta, val depth: Int, val hasChildren: Boolean)

/** Seitenleiste wie in iOS: Bereiche, Favoriten, zuletzt besucht, Seitenbaum (aufklappbar), Einstellungen */
@Composable
fun Sidebar(repo: SagaRepository, nav: AppNavigator, onNavigate: () -> Unit) {
    val pages by repo.pages.collectAsState()
    val tasks by repo.tasks.collectAsState()
    val recentIds by repo.settings.recent.collectAsState()
    val error by repo.error.collectAsState()
    val loading by repo.loading.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(setOf<Long>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val sharedLocal by repo.sharedPages.collectAsState()
    val fedShared by repo.fedShared.collectAsState()
    val fedUnreachable by repo.fedUnreachable.collectAsState()
    // "Mit mir geteilt": Seiten dieser und anderer Saga-Instanzen (Foederation, negative IDs)
    val shared = remember(sharedLocal, fedShared) { sharedLocal + fedShared }
    val chatOn by repo.live.chatEnabled.collectAsState()
    val chatUnread by repo.live.chatUnread.collectAsState()
    val notifOn by repo.live.supported.collectAsState()
    val notifUnread by repo.live.unread.collectAsState()
    val shareOff by repo.shareOff.collectAsState()
    var sharedExpanded by rememberSaveable { mutableStateOf(setOf<Long>()) }
    val favorites = pages.filter { it.pinned }
    val recent = recentIds.mapNotNull { id -> pages.firstOrNull { it.id == id } ?: shared.firstOrNull { it.id == id } }.take(5)
    // "Mit mir geteilt": oberste geteilte Seiten (mit Eigentuemer), darunter aufklappbar ihre Unterseiten
    val sharedTree = remember(shared, sharedExpanded) { SharedTree.rows(shared, sharedExpanded) }
    val sharedUnread = shared.count { it.unread }
    val pageTree = remember(pages) { PageTree(pages) }                // einmal gruppiert statt O(n²)
    val tree = remember(pageTree, expanded) {
        val out = ArrayList<TreeRow>()
        fun walk(parent: Long?, depth: Int) {
            for (p in pageTree.children(parent)) {
                val kids = if (p.isDatabase) emptyList() else pageTree.children(p.id)
                out.add(TreeRow(p, depth, kids.isNotEmpty()))
                if (p.id in expanded && kids.isNotEmpty() && depth < 12) walk(p.id, depth + 1)
            }
        }
        walk(null, 0)
        out
    }

    fun open(id: Long) {
        nav.top(Dest.Page(id))
        onNavigate()
    }

    fun newPage(parent: Long?) {
        scope.launch {
            try {
                val id = repo.newPage(parent)
                if (parent != null) expanded = expanded + parent
                open(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(repo.friendly(e))
            }
        }
    }

    // "Zu „Mit mir geteilt“" (Seite ohne Zugriff): zum Bereich scrollen (Reihenfolge wie die Eintraege unten)
    val listState = rememberLazyListState()
    val showShared by repo.showSharedRequest.collectAsState()
    var seenShared by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(repo.showSharedRequest.value) }
    LaunchedEffect(showShared) {
        if (showShared == seenShared) return@LaunchedEffect
        seenShared = showShared
        var i = 1 + 5                                                   // Kopf + Heute/Aufgaben/Termine/Dienste/Suchen
        if (chatOn) i++                                                 // Chats
        if (notifOn) i++                                                // Benachrichtigungen
        if (favorites.isNotEmpty()) i += 1 + favorites.size
        if (recent.isNotEmpty()) i += 1 + recent.size
        i += 1 + (if (tree.isEmpty()) 1 else tree.size)                 // "Seiten" + Baum bzw. "Noch keine Seiten"
        listState.animateScrollToItem(i)
    }

    LazyColumn(Modifier.statusBarsPadding(), state = listState) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { newPage(null) }) { Icon(Icons.Filled.Add, stringResource(R.string.sidebar_new_page)) }
            }
        }
        item { NavItem(stringResource(R.string.nav_today), Icons.Filled.Home, nav.root == Dest.Today) { nav.top(Dest.Today); onNavigate() } }
        item {
            NavItem(stringResource(R.string.nav_tasks), Icons.Filled.CheckCircle, nav.root == Dest.Tasks, badge = tasks.size.takeIf { it > 0 }) {
                nav.top(Dest.Tasks); onNavigate()
            }
        }
        item { NavItem(stringResource(R.string.nav_events), Icons.Filled.DateRange, nav.root == Dest.Events) { nav.top(Dest.Events); onNavigate() } }
        item { NavItem(stringResource(R.string.nav_services), Icons.Filled.Build, nav.root == Dest.Services) { nav.top(Dest.Services); onNavigate() } }
        item { NavItem(stringResource(R.string.nav_search_pages), Icons.Filled.Search, nav.root == Dest.Search) { nav.top(Dest.Search); onNavigate() } }
        if (chatOn) {
            item {
                NavItem(
                    stringResource(R.string.chats), null, nav.root == Dest.Chats || nav.root is Dest.Chat, badge = chatUnread.takeIf { it > 0 },
                    painter = R.drawable.ic_chat,
                ) { nav.top(Dest.Chats); onNavigate() }
            }
        }
        if (notifOn) {
            item {
                NavItem(stringResource(R.string.notifications), Icons.Filled.Notifications, nav.root == Dest.Notifications, badge = notifUnread.takeIf { it > 0 }) {
                    nav.top(Dest.Notifications); onNavigate()
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.sidebar_favorites)) }
            items(favorites, key = { "fav-${it.id}" }) { p ->
                PageRowItem(p, 0, false, false, nav.root == Dest.Page(p.id), repo, { open(p.id) }, {}, { newPage(it) }, scope)
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.sidebar_recent)) }
            items(recent, key = { "rec-${it.id}" }) { p ->
                PageRowItem(p, 0, false, false, nav.root == Dest.Page(p.id), repo, { open(p.id) }, {}, { newPage(it) }, scope)
            }
        }
        item { SectionHeader(stringResource(R.string.pages)) }
        if (tree.isEmpty()) {
            item {
                Text(
                    stringResource(if (loading) R.string.loading else R.string.sidebar_no_pages),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(tree, key = { "tree-${it.page.id}" }) { r ->
            PageRowItem(
                r.page, r.depth, r.hasChildren, r.page.id in expanded, nav.root == Dest.Page(r.page.id), repo,
                { open(r.page.id) },
                { expanded = if (r.page.id in expanded) expanded - r.page.id else expanded + r.page.id },
                { newPage(it) }, scope,
            )
        }
        if (sharedTree.isNotEmpty() || shareOff || fedUnreachable.isNotEmpty()) {
            item(key = "shared-head") {
                val res = LocalResources.current
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // G9: einfarbiges Gruppen-Symbol statt 👥
                    Row(Modifier.weight(1f).padding(start = 16.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_group), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.shared_with_me), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (sharedUnread > 0) {
                        val d = res.getQuantityString(R.plurals.shared_unread_pages, sharedUnread, sharedUnread)
                        Badge(
                            Modifier.padding(top = 12.dp).semantics { contentDescription = d },
                            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) { Text("$sharedUnread") }
                    }
                }
            }
            if (fedUnreachable.isNotEmpty()) {
                item(key = "shared-unreachable") {
                    val res = LocalResources.current
                    Text(
                        fedUnreachable.joinToString("\n") { res.getString(R.string.fed_unreachable, it) }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            if (shareOff) {
                item(key = "shared-off") {
                    Text(
                        stringResource(R.string.shared_off_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            items(sharedTree, key = { "shared-${it.page.id}" }) { r ->
                PageRowItem(
                    r.page, r.depth, r.hasChildren, r.page.id in sharedExpanded, nav.root == Dest.Page(r.page.id), repo,
                    { open(r.page.id) },
                    { sharedExpanded = if (r.page.id in sharedExpanded) sharedExpanded - r.page.id else sharedExpanded + r.page.id },
                    { newPage(it) }, scope, owner = if (r.page.root) r.page.owner?.name else null,
                )
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { NavItem(stringResource(R.string.nav_settings), Icons.Filled.Settings, nav.root == Dest.Settings) { nav.top(Dest.Settings); onNavigate() } }
        error?.let { e ->
            item { Text(e.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector?, selected: Boolean, badge: Int? = null, painter: Int? = null, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { if (painter != null) Icon(painterResource(painter), null) else if (icon != null) Icon(icon, null) },
        selected = selected,
        onClick = onClick,
        badge = badge?.let { { Badge { Text("$it") } } },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageRowItem(
    p: PageMeta, depth: Int, hasChildren: Boolean, isExpanded: Boolean, selected: Boolean, repo: SagaRepository,
    onOpen: () -> Unit, onToggle: () -> Unit, onNewChild: (Long) -> Unit, scope: kotlinx.coroutines.CoroutineScope,
    /** "Mit mir geteilt": Eigentuemer klein unter der obersten geteilten Seite */
    owner: String? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val foreign = p.owner != null && !p.owner.me
    // fremde Seiten: keine Favoriten (die gehoeren dem Eigentuemer); Unterseite nur mit "Bearbeiten"
    val canFavorite = !foreign
    // Seiten anderer Saga-Instanzen: keine Unterseiten (nur Titel und Inhalt aenderbar)
    val canSub = !p.isDatabase && !p.isRemote && (!foreign || p.role == "edit")
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(
                    onClick = onOpen, onLongClick = if (canFavorite || canSub) ({ menu = true }) else null,
                    onLongClickLabel = stringResource(R.string.page_actions),
                )
                .padding(start = (16 + depth * 16).dp, end = if (hasChildren) 0.dp else 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Auf-/Zuklappen rechts: Seiten mit und ohne Unterseiten stehen buendig (kein leerer 48-dp-Platzhalter)
            PageIcon(p.icon, repo, p.isDatabase)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(pageTitle(p), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (owner != null) {
                    Text(owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (p.unread) {
                val d = stringResource(R.string.unread_dot_desc)
                Box(Modifier.padding(horizontal = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = d })
            }
            if (p.shared && !foreign) {
                val d = stringResource(R.string.shared_icon_desc)
                Icon(
                    painterResource(R.drawable.ic_group), d, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp).size(16.dp),
                )
            }
            if (hasChildren) {
                IconButton(onClick = onToggle) {
                    Icon(if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(if (isExpanded) R.string.action_collapse else R.string.action_expand))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (canFavorite) {
                DropdownMenuItem(text = { Text(stringResource(if (p.pinned) R.string.favorite_remove else R.string.favorite_add)) }, onClick = {
                    menu = false
                    repo.launchAction { repo.setPinned(p.id, !p.pinned) }
                })
            }
            if (canSub) {
                DropdownMenuItem(text = { Text(stringResource(R.string.new_subpage)) }, onClick = { menu = false; onNewChild(p.id) })
            }
        }
    }
}
