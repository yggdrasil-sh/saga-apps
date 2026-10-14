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
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.heightIn
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.SectionHeader
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
    var expanded by rememberSaveable { mutableStateOf(setOf<Int>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val favorites = pages.filter { it.pinned }
    val recent = recentIds.mapNotNull { id -> pages.firstOrNull { it.id == id } }.take(5)
    val pageTree = remember(pages) { PageTree(pages) }                // einmal gruppiert statt O(n²)
    val tree = remember(pageTree, expanded) {
        val out = ArrayList<TreeRow>()
        fun walk(parent: Int?, depth: Int) {
            for (p in pageTree.children(parent)) {
                val kids = if (p.isDatabase) emptyList() else pageTree.children(p.id)
                out.add(TreeRow(p, depth, kids.isNotEmpty()))
                if (p.id in expanded && kids.isNotEmpty() && depth < 12) walk(p.id, depth + 1)
            }
        }
        walk(null, 0)
        out
    }

    fun open(id: Int) {
        nav.top(Dest.Page(id))
        onNavigate()
    }

    fun newPage(parent: Int?) {
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

    LazyColumn(Modifier.statusBarsPadding()) {
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
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { NavItem(stringResource(R.string.nav_settings), Icons.Filled.Settings, nav.root == Dest.Settings) { nav.top(Dest.Settings); onNavigate() } }
        error?.let { e ->
            item { Text(e.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, badge: Int? = null, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, null) },
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
    onOpen: () -> Unit, onToggle: () -> Unit, onNewChild: (Int) -> Unit, scope: kotlinx.coroutines.CoroutineScope,
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.page_actions))
                .padding(start = (16 + depth * 16).dp, end = if (hasChildren) 0.dp else 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Auf-/Zuklappen rechts: Seiten mit und ohne Unterseiten stehen buendig (kein leerer 48-dp-Platzhalter)
            PageIcon(p.icon, repo, p.isDatabase)
            Text(pageTitle(p), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
            if (hasChildren) {
                IconButton(onClick = onToggle) {
                    Icon(if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(if (isExpanded) R.string.action_collapse else R.string.action_expand))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(if (p.pinned) R.string.favorite_remove else R.string.favorite_add)) }, onClick = {
                menu = false
                repo.launchAction { repo.setPinned(p.id, !p.pinned) }
            })
            if (!p.isDatabase) {
                DropdownMenuItem(text = { Text(stringResource(R.string.new_subpage)) }, onClick = { menu = false; onNewChild(p.id) })
            }
        }
    }
}
