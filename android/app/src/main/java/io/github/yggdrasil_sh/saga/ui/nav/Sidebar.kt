package io.github.yggdrasil_sh.saga.ui.nav

import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import io.github.yggdrasil_sh.saga.data.avatarState
import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.common.pageTitle
import io.github.yggdrasil_sh.saga.util.resolve
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import io.github.yggdrasil_sh.saga.data.PageTree
import io.github.yggdrasil_sh.saga.data.SharedTree
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.heightIn
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch

private data class TreeRow(val page: PageMeta, val depth: Int, val hasChildren: Boolean)

/**
 * Seitenleiste wie im Web (Design-Runde 4, NAV-07/15/24/27): oben Kontomenue und Suchfeld, Hauptpunkte nur, was nicht
 * in der unteren Leiste liegt, dann Favoriten und Seitenbaum mit "Neue Seite", unten eine feste Fusszeile
 * (Dienste & Links, Graph, Papierkorb, Einstellungen). "Zuletzt" steht nur noch auf Heute ("Weitermachen").
 * bottomBar = die Ziele der unteren Leiste (Heute, Seiten, Suchen, Chats) sind sichtbar (Phone-Leiste oder Rail).
 * openOver = Seiten ueber dem Leistenziel oeffnen (Zurueck fuehrt dorthin); Design-Runde 6: quer in den Chats nicht.
 */
@Composable
fun Sidebar(repo: SagaRepository, nav: AppNavigator, onNavigate: () -> Unit, bottomBar: Boolean = false, openOver: Boolean = bottomBar) {
    val pages by repo.pages.collectAsState()
    val tasks by repo.tasks.collectAsState()
    // NAV-01: wie das Web-Badge "faellig heute + ueberfaellig" (mit Aufgabenquellen aus der zusammengefuehrten Liste)
    val mergedTasks by repo.taskList.collectAsState()
    val todayIso = io.github.yggdrasil_sh.saga.ui.common.todayIso()
    val taskBadge = io.github.yggdrasil_sh.saga.data.TaskCounts.badge(mergedTasks, tasks, todayIso)
    val taskOverdue = NavModel.overdueCount(mergedTasks, tasks, todayIso)
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
    val chatUnread = rememberUnreadChats(repo)       // 46b: Gespraeche, nicht Nachrichten
    val shareOff by repo.shareOff.collectAsState()
    var sharedExpanded by rememberSaveable { mutableStateOf(setOf<Long>()) }
    // 2.4: Verschieben (Zeilenmenue "⋮" bzw. Langdruck auf eine Seite); der Papierkorb ist ein eigenes Ziel (NAV-17)
    var movePage by remember { mutableStateOf<PageMeta?>(null) }
    val allMeta = remember(pages, sharedLocal) { pages + sharedLocal }
    val onMove: (PageMeta) -> Unit = { movePage = it }
    val favorites = pages.filter { it.pinned }
    // "Mit mir geteilt": oberste geteilte Seiten (mit Eigentuemer), darunter aufklappbar ihre Unterseiten
    val sharedTree = remember(shared, sharedExpanded) { SharedTree.rows(shared, sharedExpanded) }
    val sharedUnread = shared.count { it.unread }
    // einmal gruppiert statt O(n²), im Hintergrund (grosse Listen blockieren sonst den Haupt-Thread); bis dahin
    // bleibt der bisherige Baum stehen
    val pageTree by androidx.compose.runtime.produceState(remember { PageTree(pages) }, pages) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { PageTree(pages) }
    }
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
        // R4-Nachbesserung 22: mit unterer Leiste ueber dem Leistenziel oeffnen (Zurueck -> dorthin), sonst wie bisher
        if (openOver) nav.openOver(Dest.Page(id)) else nav.top(Dest.Page(id))
        onNavigate()
    }

    // Neue Seite: waehrend des Anlegens gesperrt (kein Doppel-Anlegen durch mehrfaches Tippen), "+" zeigt sofort Fortschritt
    var creating by remember { mutableStateOf(false) }
    fun newPage(parent: Long?) {
        if (creating) return
        creating = true
        scope.launch {
            try {
                val id = repo.newPage(parent)
                if (parent != null) expanded = expanded + parent
                open(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(repo.friendly(e))
            } finally {
                creating = false
            }
        }
    }

    // 2.5 Vorlagengalerie: "+" wird zum Menue "Neue Seite" / "Aus Vorlage ...", wenn der Server die Galerie anbietet
    // (einmal je Server/Sprache im Hintergrund geprueft; 404 = aelterer Server -> "+" legt wie bisher direkt an)
    val galServer by repo.server.collectAsState()
    val galUnsupported by repo.gallery.unsupported.collectAsState()
    val galLists by repo.gallery.lists.collectAsState()
    val galOn = galServer.isNotEmpty() && galServer !in galUnsupported && galLists.containsKey(repo.gallery.key(galServer, repo.galleryLang()))
    LaunchedEffect(galServer, loading) {
        if (galServer.isEmpty() || loading || galServer in repo.gallery.unsupported.value) return@LaunchedEffect
        if (repo.gallery.cached(galServer, repo.galleryLang()) == null) repo.galleryList()
    }
    var newMenu by remember { mutableStateOf(false) }
    var galleryOpen by remember { mutableStateOf(false) }
    /** Elternseite aus dem Langdruck-Menue (null = Kopf-Menue "Neu": oberste Ebene oder offene Seite) */
    var galleryParent by remember { mutableStateOf<PageMeta?>(null) }
    fun openGallery(parent: PageMeta?) {
        galleryParent = parent
        galleryOpen = true
    }
    val galTemplate: ((PageMeta) -> Unit)? = if (galOn) ({ p -> openGallery(p) }) else null

    val mainDests = NavModel.sidebarMain(bottomBar, chatOn)
    val serverVersion by repo.serverVersion.collectAsState()
    val graphOn = io.github.yggdrasil_sh.saga.ui.graph.GraphSupport.available(serverVersion, galServer)
    // "Zu „Mit mir geteilt“" (Seite ohne Zugriff): zum Bereich scrollen (Reihenfolge wie die Eintraege unten)
    val listState = rememberLazyListState()
    val showShared by repo.showSharedRequest.collectAsState()
    var seenShared by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(repo.showSharedRequest.value) }
    LaunchedEffect(showShared) {
        if (showShared == seenShared) return@LaunchedEffect
        seenShared = showShared
        listState.animateScrollToItem(NavModel.sharedIndex(mainDests.size, favorites.size, tree.size))
    }

    Column(Modifier.statusBarsPadding()) {
    // R6-Rest: geht der Baum unter der festen Fusszeile weiter, blendet ein Verlauf die letzte Zeile aus, statt sie
    // halb abgeschnitten stehen zu lassen (zeigt: weiter scrollen)
    val fadePx = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
    LazyColumn(
        Modifier.weight(1f)
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (listState.canScrollForward) {
                    val h = minOf(fadePx, size.height / 4)
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black),
                            startY = size.height - h, endY = size.height,
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                        size = androidx.compose.ui.geometry.Size(size.width, h),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
                    )
                }
            },
        state = listState,
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // NAV-11: Avatar oeffnet das Kontomenue (Sprache, Darstellung, Einstellungen, Admin, Abmelden)
                AccountAvatarMenu(repo, nav, onNavigate, Modifier.padding(end = 6.dp))
                Text(
                    stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                // Neue Seite: deutlich als Hauptaktion (getoente Goldflaeche), 48 dp
                val accent = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current
                Box {
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { if (galOn) newMenu = true else newPage(null) }, enabled = !creating,
                    colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(containerColor = accent.goldSoft, contentColor = accent.onGoldSoft),
                ) {
                    if (creating) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        SagaIcon(SagaIcons.plus, stringResource(R.string.sidebar_new_page))
                    }
                }
                DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.gal_new_page)) }, onClick = { newMenu = false; newPage(null) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.gal_from_template)) }, onClick = { newMenu = false; openGallery(null) })
                }
                }
            }
        }
        // NAV-15: Suchfeld oben (oeffnet die Suche), ein Begriff "Suchen"
        item(key = "search") { SearchField { nav.top(Dest.Search); onNavigate() } }
        items(mainDests, key = { "main-" + Dest.encode(it) }) { d ->
            val (label, icon) = destLabel(d)
            // R4-Nachbesserung 17: wie Heute (goldene Zahl); Rot nur fuer "N ueberfaellig" mit Wort (Regel Runde 1)
            val taskUi: @Composable () -> Unit = { TaskBadge(taskBadge, taskOverdue) }
            val chatUi: @Composable () -> Unit = { ChatBadge(chatUnread) }
            val badge: (@Composable () -> Unit)? = when {
                d == Dest.Tasks && taskBadge > 0 -> taskUi
                d == Dest.Chats && chatUnread != 0 -> chatUi
                else -> null
            }
            NavItem(stringResource(label), null, NavModel.same(nav.root, d), badge = badge, painter = icon) {
                nav.top(d); onNavigate()
            }
        }

        if (favorites.isNotEmpty()) {
            item { SidebarSection(stringResource(R.string.sidebar_favorites)) }
            items(favorites, key = { "fav-${it.id}" }) { p ->
                PageRowItem(p, 0, false, false, nav.root == Dest.Page(p.id), repo, { open(p.id) }, {}, { newPage(it) }, scope, allMeta = allMeta, onMove = onMove, onTemplate = galTemplate)
            }
        }
        item { SidebarSection(stringResource(R.string.pages)) }
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
                { newPage(it) }, scope, allMeta = allMeta, onMove = onMove, onTemplate = galTemplate,
            )
        }
        // NAV-18: "Neue Seite" auch unter dem Baum (wie Web note-new); mit Vorlagen als kleines Menue
        item(key = "new-page") {
            var rowMenu by remember { mutableStateOf(false) }
            Box {
                NewPageRow(enabled = !creating) { if (galOn) rowMenu = true else newPage(null) }
                DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.gal_new_page)) }, onClick = { rowMenu = false; newPage(null) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.gal_from_template)) }, onClick = { rowMenu = false; openGallery(null) })
                }
            }
        }
        if (sharedTree.isNotEmpty() || shareOff || fedUnreachable.isNotEmpty()) {
            item(key = "shared-head") {
                val res = LocalResources.current
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // G9: einfarbiges Gruppen-Symbol statt 👥
                    Row(Modifier.weight(1f).padding(start = 28.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SagaIcon(SagaIcons.users, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.shared_with_me), style = MaterialTheme.typography.labelLarge, color = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.goldText,
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
                    { newPage(it) }, scope, owner = if (r.page.root) r.page.owner?.name else null, allMeta = allMeta, onMove = onMove, onTemplate = galTemplate,
                )
            }
        }
        error?.let { e ->
            item { Text(e.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    // NAV-07/24: feste Fusszeile wie Web (Symbolzeile), immer sichtbar unter dem Baum
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    // R4-Nachbesserung 68: Papierkorb ist ein Unterziel (Zurueck-Pfeil, Zurueck -> vorheriges Ziel), die anderen
    // Fusszeilen-Ziele bleiben Wurzeln wie die Hauptpunkte
    SidebarFooter(NavModel.sidebarFooter(graphOn), nav.root, nav.current) { d ->
        if (NavModel.footerPush(d)) nav.push(d) else nav.top(d)
        onNavigate()
    }
    }
    movePage?.let { p -> io.github.yggdrasil_sh.saga.ui.pages.MovePageDialog(repo, p, onDismiss = { movePage = null }) }
    if (galleryOpen) {
        // Kopf-Menue: offene Seite als Elternseite anbieten (nur wenn dort Unterseiten erlaubt sind)
        val current = (nav.root as? Dest.Page)?.id?.let { id -> allMeta.firstOrNull { it.id == id } }
            ?.takeIf { galleryParent == null && io.github.yggdrasil_sh.saga.data.Gallery.canBeParent(it) }
        io.github.yggdrasil_sh.saga.ui.pages.GallerySheet(
            repo, parent = galleryParent, current = current,
            onDismiss = { galleryOpen = false },
            onCreated = { id ->
                galleryOpen = false
                galleryParent?.let { expanded = expanded + it.id }
                open(id)
            },
        )
    }
}

/** Beschriftung und Symbol eines Ziels in Seitenleiste und Fusszeile */
internal fun destLabel(d: Dest): Pair<Int, Int> = when (d) {
    Dest.Today -> R.string.nav_today to SagaIcons.today
    Dest.Tasks -> R.string.nav_tasks to SagaIcons.tasks
    Dest.Events -> R.string.nav_events to SagaIcons.calendar
    Dest.Chats -> R.string.chats to SagaIcons.chats
    Dest.Search -> R.string.r4nav_search to SagaIcons.search
    Dest.Services -> R.string.r4nav_services_links to SagaIcons.server
    is Dest.Graph -> R.string.graph_title to SagaIcons.graph
    Dest.Trash -> R.string.tr_nav to SagaIcons.trash
    else -> R.string.nav_settings to SagaIcons.settings
}

/** NAV-15: Suchfeld oben in der Leiste - sieht aus wie ein Feld, oeffnet die Suche (Schaltflaeche, 48 dp) */
@Composable
private fun SearchField(onClick: () -> Unit) {
    val label = stringResource(R.string.r4nav_search)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = androidx.compose.ui.semantics.Role.Button
                onClick { onClick(); true }
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SagaIcon(SagaIcons.search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    }
}

/** NAV-18: Zeile "+ Neue Seite" unter dem Baum (eingerueckt wie die Seiten) */
@Composable
private fun NewPageRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SagaIcon(SagaIcons.plus, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.sidebar_new_page), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * NAV-07/24: Fusszeile mit Symbol und Beschriftung (je mindestens 48 dp), gewaehltes Ziel in Gold-Soft.
 * R4-Nachbesserung 39/79b: vier Spalten waren zu schmal ("Einstellun-gen", "Papier-korb" bei Schrift 1,3 und auf dem
 * Tablet). Jetzt zwei Reihen mit je zwei Zielen (halbe Breite, Symbol links), Beschriftung ohne Silbentrennung,
 * Umbruch nur an Leerzeichen (LineBreak.Simple), hoechstens zwei Zeilen, sonst Ellipse - liest sich auch in de/ru/th.
 */
@Composable
private fun SidebarFooter(dests: List<Dest>, root: Dest, current: Dest, onPick: (Dest) -> Unit) {
    val accent = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        hyphens = androidx.compose.ui.text.style.Hyphens.None,
        lineBreak = androidx.compose.ui.text.style.LineBreak.Simple,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        NavModel.footerRows(dests).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { d ->
                    val (label, icon) = destLabel(d)
                    val selected = NavModel.same(root, d) || current == d
                    Row(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                            .background(if (selected) accent.goldSoft else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab) { onPick(d) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (selected) accent.onGoldSoft else MaterialTheme.colorScheme.onSurfaceVariant
                        SagaIcon(icon, null, Modifier.size(20.dp), tint = tint)
                        Text(
                            stringResource(label), style = labelStyle, color = tint, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                // ungerade Anzahl (z. B. ohne Graph): letzte Zelle bleibt halb breit
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Abschnitt der Seitenleiste (Favoriten, Zuletzt, Seiten): Gold, eingerueckt wie die Eintraege */
@Composable
private fun SidebarSection(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.goldText,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

/** Gewaehlter Eintrag: zartes Gold (wie die Seitenzeilen), sonst transparent */
@Composable
private fun sidebarItemColors() = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.let { a ->
    androidx.compose.material3.NavigationDrawerItemDefaults.colors(
        selectedContainerColor = a.goldSoft, selectedTextColor = a.onGoldSoft, selectedIconColor = a.onGoldSoft,
        selectedBadgeColor = a.onGoldSoft,
    )
}

@Composable
private fun NavItem(label: String, icon: ImageVector?, selected: Boolean, badge: (@Composable () -> Unit)? = null, painter: Int? = null, onClick: () -> Unit) {
    NavigationDrawerItem(
        colors = sidebarItemColors(),
        label = { NavLabel(label) },
        icon = { if (painter != null) Icon(painterResource(painter), null) else if (icon != null) Icon(icon, null) },
        selected = selected,
        onClick = onClick,
        badge = badge,
        // R2c: gewaehlte Zeile 6 dp wie Web-Steuerelemente (--r-sm) statt Material-Pille
        shape = MaterialTheme.shapes.small,
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
    /** 2.4: Verschieben im Langdruck-Menue (Regeln: MoveLogic) */
    allMeta: List<PageMeta> = emptyList(),
    onMove: ((PageMeta) -> Unit)? = null,
    /** 2.5: "Aus Vorlage ..." als Unterseite (nur wenn der Server die Galerie anbietet) */
    onTemplate: ((PageMeta) -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val foreign = p.owner != null && !p.owner.me
    // fremde Seiten: keine Favoriten (die gehoeren dem Eigentuemer); Unterseite nur mit "Bearbeiten"
    val canFavorite = !foreign
    // Seiten anderer Saga-Instanzen: Unterseiten nur, wenn ihr Server es kann (2.3, Rolle Bearbeiten), sonst nicht
    val canSub = !p.isDatabase && p.can(io.github.yggdrasil_sh.saga.data.RemoteCaps.SUBPAGES) && (!(foreign || p.isRemote) || p.role == "edit")
    val canMove = onMove != null && io.github.yggdrasil_sh.saga.data.MoveLogic.canMove(p, allMeta)
    val canTemplate = onTemplate != null && canSub && io.github.yggdrasil_sh.saga.data.Gallery.canBeParent(p)
    val hasActions = canFavorite || canSub || canMove || canTemplate
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(MaterialTheme.shapes.small)
                .background(if (selected) io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.goldSoft else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(
                    onClick = onOpen, onLongClick = if (hasActions) ({ menu = true }) else null,
                    onLongClickLabel = stringResource(R.string.page_actions),
                )
                .padding(start = (16 + depth * 16).dp, end = if (hasChildren || p.shared || p.unread || hasActions) 0.dp else 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Auf-/Zuklappen rechts: Seiten mit und ohne Unterseiten stehen buendig (kein leerer 48-dp-Platzhalter)
            PageIcon(p.icon, repo, p.isDatabase)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    pageTitle(p), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected) io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.onGoldSoft else androidx.compose.ui.graphics.Color.Unspecified,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                )
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
                SagaIcon(
                    SagaIcons.users, d, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp).size(16.dp),
                )
            }
            if (hasChildren) {
                IconButton(onClick = onToggle) {
                    SagaIcon(if (isExpanded) SagaIcons.chevronDown else SagaIcons.chevronRight, stringResource(if (isExpanded) R.string.action_collapse else R.string.action_expand))
                }
            } else if (p.shared || p.unread) {
                Spacer(Modifier.width(48.dp))      // Nr. 22: Geteilt-Symbol/Punkt stehen buendig mit denen neben dem Pfeil
            }
            // NAV-18: Zeilenmenue "⋮" (48 dp) ganz rechts, immer sichtbar (gedaempft); Langdruck bleibt als Abkuerzung.
            // R4-Nachbesserung 18b: das Menue haengt im selben Box wie der Knopf, damit es dort aufgeht (nicht am
            // linken Rand der Schublade); Eintraege mit Saga-Symbolen.
            if (hasActions) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        SagaIcon(
                            SagaIcons.moreVert, stringResource(R.string.r4nav_row_actions, pageTitle(p)),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (canFavorite) {
                            RowMenuItem(if (p.pinned) R.string.favorite_remove else R.string.favorite_add, SagaIcons.star) {
                                menu = false
                                repo.launchAction { repo.setPinned(p.id, !p.pinned) }
                            }
                        }
                        if (canSub) {
                            RowMenuItem(R.string.new_subpage, SagaIcons.filePlus) { menu = false; onNewChild(p.id) }
                        }
                        if (canTemplate) {
                            RowMenuItem(R.string.gal_from_template, SagaIcons.template) { menu = false; onTemplate?.invoke(p) }
                        }
                        if (canMove) {
                            RowMenuItem(R.string.mv_menu, SagaIcons.move) { menu = false; onMove?.invoke(p) }
                        }
                    }
                }
            }
        }
    }
}

/** Aufgaben-Badge in der Seitenleiste: Zahl in Gold wie auf Heute, bei Ueberfaelligem davor rot "N ueberfaellig" */
@Composable
private fun TaskBadge(due: Int, overdue: Int) {
    val res = LocalResources.current
    val desc = res.getQuantityString(R.plurals.r4nav_tasks_due, due, due) +
        (if (overdue > 0) ", " + res.getQuantityString(R.plurals.r4nav_tasks_overdue, overdue, overdue) else "")
    Row(Modifier.clearAndSetSemantics { contentDescription = desc }, verticalAlignment = Alignment.CenterVertically) {
        if (overdue > 0) {
            Text(
                res.getQuantityString(R.plurals.r4nav_tasks_overdue, overdue, overdue), style = MaterialTheme.typography.labelMedium,
                color = io.github.yggdrasil_sh.saga.ui.theme.SagaColors.current.overdue, maxLines = 1,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        io.github.yggdrasil_sh.saga.ui.theme.SagaPill(NavModel.badgeText(due) ?: "")
    }
}

/** Chats-Badge in der Seitenleiste (ohne untere Leiste): ungelesene Gespraeche, Punkt solange unbekannt */
@Composable
private fun ChatBadge(count: Int) {
    val desc = unreadChatsText(count)
    val m = if (desc != null) Modifier.clearAndSetSemantics { contentDescription = desc } else Modifier
    if (count < 0) Badge(m) else Badge(m) { Text(NavModel.badgeText(count) ?: "") }
}

/** Eintrag im Zeilenmenue: Saga-Symbol (gleiche Groesse/Einrueckung fuer alle) und Beschriftung */
@Composable
private fun RowMenuItem(label: Int, icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { SagaIcon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

/**
 * Beschriftung in Seitenleiste/Navigationsleiste: einzeilig, bei Platzmangel (Querformat, Badge, grosse Schrift)
 * stufenweise kleiner statt mitten im Wort umzubrechen ("Benachrichtigu-ngen", TT 2.4 P-11); notfalls mit "…".
 */
@Composable
internal fun NavLabel(label: String) {
    val style = androidx.compose.material3.LocalTextStyle.current
    val color = androidx.compose.material3.LocalContentColor.current
    val max = if (style.fontSize.isSp) style.fontSize else 16.sp
    androidx.compose.foundation.text.BasicText(
        label,
        style = style.copy(color = color),
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = max, stepSize = 0.5.sp),
    )
}
