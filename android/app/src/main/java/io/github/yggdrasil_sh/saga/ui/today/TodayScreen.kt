package io.github.yggdrasil_sh.saga.ui.today

import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.TaskItem
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import io.github.yggdrasil_sh.saga.ui.common.SagaLineEmptyState
import io.github.yggdrasil_sh.saga.ui.theme.SagaSectionTitle
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace
import io.github.yggdrasil_sh.saga.ui.theme.sagaAppear
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yggdrasil_sh.saga.ui.common.longDate
import io.github.yggdrasil_sh.saga.ui.common.todayIso
import io.github.yggdrasil_sh.saga.ui.events.EventRow
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.tasks.QuickAddTask
import io.github.yggdrasil_sh.saga.ui.tasks.TaskEditDialog
import io.github.yggdrasil_sh.saga.ui.tasks.TaskRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.pageTitle
import io.github.yggdrasil_sh.saga.ui.events.EventDialog
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.pages.GallerySheet
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton

/** Heute wie in iOS: heutige Termine + offene Aufgaben (heute, ueberfaellig, ohne Datum) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val tasks by repo.tasks.collectAsState()
    val events by repo.todayEvents.collectAsState()
    val loading by repo.loading.collectAsState()
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        repo.loadTasks()
        repo.loadTodayEvents()
    }
    val today = todayIso()
    // Widget "Neue Aufgabe": zur Eingabe springen und fokussieren
    val quickAdd by repo.focusQuickAdd.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // NAV-13 "Weitermachen": zuletzt geoeffnete Seiten aus der lokalen Liste (settings.recent, auch Quelle von "Zuletzt" in der Suche)
    val pages by repo.pages.collectAsState()
    val sharedPages by repo.sharedPages.collectAsState()
    val recentIds by repo.settings.recent.collectAsState()
    val cont = remember(recentIds, pages, sharedPages) { TodayLogic.continuePages(recentIds, pages, sharedPages) }
    var twoCols by remember { mutableStateOf(false) }
    LaunchedEffect(quickAdd) { if (quickAdd) listState.scrollToItem(TodayLogic.quickAddIndex(cont.isNotEmpty(), events.size, twoCols)) }
    val account by repo.account.collectAsState()
    val first = TodayLogic.firstName(account)
    // NAV-13 Schnellaktionen: vorhandene Ablaeufe (Neue Seite wie Seitenleiste, Vorlagengalerie, Aufgaben-Eingabe, Termin-Dialog)
    var creating by remember { mutableStateOf(false) }
    var galleryOpen by remember { mutableStateOf(false) }
    var newEvent by remember { mutableStateOf(false) }
    val galServer by repo.server.collectAsState()
    val galUnsupported by repo.gallery.unsupported.collectAsState()
    val galleryOn = galServer.isNotEmpty() && galServer !in galUnsupported
    fun newPage() {
        if (creating) return
        creating = true
        scope.launch {
            try {
                nav.push(Dest.Page(repo.newPage(null)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(repo.friendly(e))
            } finally {
                creating = false
            }
        }
    }
    // NAV-01: eine Quelle - Kopf, Abschnittszahl und Liste zaehlen dieselbe Liste (TaskCounts)
    val todayTasks = io.github.yggdrasil_sh.saga.data.TaskCounts.todayTasks(tasks, today)
    // 2.5 E2: mit Aufgabenquellen die Server-Liste (heute, ueberfaellig, ohne Datum) inkl. Datenbank-Zeilen
    val merged by repo.taskList.collectAsState()
    val todayItems = io.github.yggdrasil_sh.saga.data.TaskCounts.todayEntries(merged, today)
    val shownCount = todayItems?.size ?: todayTasks.size

    // NAV-02: offline gibt "Aktualisieren" Rueckmeldung (Snackbar mit "Erneut versuchen") statt still nichts zu tun
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun refresh() {
        if (io.github.yggdrasil_sh.saga.ui.common.offlineNow(ctx, repo)) {
            repo.notify(
                io.github.yggdrasil_sh.saga.util.UiText.Res(R.string.r1_refresh_offline),
                io.github.yggdrasil_sh.saga.util.UiText.Res(R.string.action_retry),
            ) { refresh() }
        }
        scope.launch { repo.refresh() }
    }
    Scaffold(topBar = {
        SagaTopBar(stringResource(R.string.nav_today), nav, chrome, contentWidthFor = ::todayContentWidth) {
            IconButton(onClick = { refresh() }) { SagaIcon(SagaIcons.refresh, stringResource(R.string.action_refresh)) }
        }
    }) { pad ->
        PullToRefreshBox(isRefreshing = loading, onRefresh = { refresh() }, modifier = Modifier.padding(pad).fillMaxSize()) {
          androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val accent = LocalSagaAccent.current
            // R6 (A11Y-23): ab 840 dp verfuegbarer Breite zwei Spalten; Kopf und "Weitermachen" stehen ueber beiden
            val two = TodayLogic.twoColumns(maxWidth.value)
            val contCardW = TodayLogic.continueCardWidthDp(maxWidth.value).dp
            androidx.compose.runtime.SideEffect { twoCols = two }
            val maxW = todayContentWidth(maxWidth)
            val ml = merged
            // Aufgaben-Abschnitt (Kopf, Schnelleingabe, Zeilen bzw. leer) - einspaltig als Listeneintraege, zweispaltig rechts
            val taskHead: @Composable (Modifier) -> Unit = { m ->
                SagaSectionTitle(stringResource(R.string.nav_tasks), m, count = shownCount)
            }
            val taskAdd: @Composable (Modifier) -> Unit = { m ->
                QuickAddTask(repo, m, defaultDue = today, focus = quickAdd, onFocused = { repo.focusQuickAdd.value = false },
                    targets = merged?.let { io.github.yggdrasil_sh.saga.data.TaskSourcesLogic.targets(it.sources) }.orEmpty())
            }
            val tasksEmpty = ml == null && todayTasks.isEmpty() || ml != null && todayItems.isNullOrEmpty()
            val taskEmptyView: @Composable (Modifier) -> Unit = { m ->
                Box(m.fillMaxWidth().padding(top = SagaSpace.s).sagaSegment(0, 1)) {
                    SagaLineEmptyState(
                        stringResource(if (events.isEmpty()) R.string.today_nothing_planned else R.string.today_no_tasks),
                        SagaIcons.tasks, text = stringResource(R.string.today_tasks_empty_hint), compact = true,
                    )
                }
            }
            val sourceRow: @Composable (Int, io.github.yggdrasil_sh.saga.data.TaskEntry, Modifier) -> Unit = { i, e, m ->
                val src = ml?.sources?.firstOrNull { it.db == e.src }
                Box(m.fillMaxWidth().padding(top = if (i == 0) SagaSpace.s else 0.dp).sagaSegment(i, todayItems?.size ?: 1)) {
                    io.github.yggdrasil_sh.saga.ui.tasks.SourceTaskRow(e, src, ml?.people.orEmpty(), repo) { x ->
                        if (x.isRow) nav.push(io.github.yggdrasil_sh.saga.ui.nav.Dest.Page(x.id))
                        else editing = tasks.firstOrNull { it.id.toLong() == x.id }
                    }
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = SagaSpace.xxl),
            ) {
                // Kopf: Datum (Gold) + Gruss + kurze Zusammenfassung
                item(key = "head") {
                    Column(Modifier.widthIn(max = maxW).fillMaxWidth().padding(horizontal = SagaSpace.gutter).padding(top = SagaSpace.xs, bottom = SagaSpace.s).sagaAppear(0)) {
                        Text(longDate(), style = MaterialTheme.typography.labelLarge, color = accent.goldText)
                        Text(
                            greeting(first), style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = SagaSpace.xxs).semantics { heading() },
                        )
                        val res = LocalResources.current
                        val open = shownCount
                        // NAV-13: "Keine Termine heute" steht schon als Zeile im Abschnitt Termine, hier nicht doppelt
                        val summary = listOfNotNull(
                            if (events.isEmpty()) null else res.getQuantityString(R.plurals.today_events_count, events.size, events.size),
                            res.getQuantityString(R.plurals.today_tasks_open, open, open),
                        ).joinToString(" · ")
                        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = SagaSpace.xxs))
                        QuickActions(
                            creating = creating, galleryOn = galleryOn,
                            onNewPage = { newPage() }, onTemplate = { galleryOpen = true },
                            onTask = { repo.focusQuickAdd.value = true }, onEvent = { newEvent = true },
                        )
                    }
                }
                if (cont.isNotEmpty()) {
                    item(key = "continue") {
                        Column(Modifier.widthIn(max = maxW).fillMaxWidth().sagaAppear(1)) {
                            SagaSectionTitle(stringResource(R.string.r4t_continue), Modifier.padding(top = SagaSpace.xs))
                            ContinueRow(cont, repo, contCardW) { nav.push(Dest.Page(it)) }
                        }
                    }
                }
                if (two) {
                    // Gemeinsame Seite: beide Spalten scrollen mit dem Kopf (die Listen von "Heute" sind kurz, nicht-lazy ok)
                    item(key = "columns") {
                        Row(Modifier.widthIn(max = maxW).fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            // TalkBack: erst die ganze linke Spalte (Termine), dann die rechte (Aufgaben)
                            Column(Modifier.weight(1f).widthIn(max = TodayLogic.COLUMN_BOX_DP.dp).semantics { isTraversalGroup = true; traversalIndex = 0f }) {
                                SagaSectionTitle(stringResource(R.string.nav_events), Modifier.padding(top = SagaSpace.s), count = events.size)
                                if (events.isEmpty()) Box(Modifier.fillMaxWidth().sagaSegment(0, 1)) { NoEventsRow { newEvent = true } }
                                events.forEachIndexed { i, e ->
                                    Box(Modifier.fillMaxWidth().sagaSegment(i, events.size).padding(vertical = SagaSpace.xs)) { EventRow(e) }
                                }
                            }
                            Column(Modifier.weight(1f).widthIn(max = TodayLogic.COLUMN_BOX_DP.dp).semantics { isTraversalGroup = true; traversalIndex = 1f }) {
                                taskHead(Modifier.padding(top = SagaSpace.s))
                                taskAdd(Modifier)
                                if (ml != null && todayItems != null) todayItems.forEachIndexed { i, e -> sourceRow(i, e, Modifier) }
                                if (tasksEmpty) taskEmptyView(Modifier)
                                if (ml == null) todayTasks.forEachIndexed { i, t ->
                                    Box(Modifier.fillMaxWidth().padding(top = if (i == 0) SagaSpace.s else 0.dp).sagaSegment(i, todayTasks.size)) {
                                        TaskRow(t, repo) { editing = it }
                                    }
                                }
                            }
                        }
                    }
                    return@LazyColumn
                }
                item(key = "ev-head") { SagaSectionTitle(stringResource(R.string.nav_events), Modifier.widthIn(max = maxW).padding(top = SagaSpace.s), count = events.size) }
                if (events.isEmpty()) {
                    // NAV-13: leere Termine als eine Zeile mit "+ Termin" statt eines 200-dp-Blocks
                    item(key = "ev-empty") {
                        Box(Modifier.widthIn(max = maxW).fillMaxWidth().sagaSegment(0, 1).sagaAppear(1)) {
                            NoEventsRow { newEvent = true }
                        }
                    }
                }
                itemsIndexed(events, key = { i, _ -> "ev-$i" }) { i, e ->
                    Box(Modifier.widthIn(max = maxW).fillMaxWidth().sagaSegment(i, events.size).padding(vertical = SagaSpace.xs).sagaAppear(1 + i)) { EventRow(e) }
                }
                item(key = "t-head") { taskHead(Modifier.widthIn(max = maxW).padding(top = SagaSpace.m)) }
                item(key = "t-add") { taskAdd(Modifier.widthIn(max = maxW)) }
                if (ml != null && todayItems != null) {
                    itemsIndexed(todayItems, key = { _, e -> "e-${e.key}" }) { i, e -> sourceRow(i, e, Modifier.widthIn(max = maxW)) }
                }
                if (tasksEmpty) {
                    item(key = "t-empty") { taskEmptyView(Modifier.widthIn(max = maxW)) }
                }
                if (ml == null) itemsIndexed(todayTasks, key = { _, t -> "t-${t.id}" }) { i, t ->
                    Box(
                        Modifier.widthIn(max = maxW).fillMaxWidth().padding(top = if (i == 0) SagaSpace.s else 0.dp)
                            .sagaSegment(i, todayTasks.size).animateItem().sagaAppear(2 + i),
                    ) { TaskRow(t, repo) { editing = it } }
                }
            }
          }
        }
    }
    editing?.let { t -> TaskEditDialog(t, repo) { editing = null } }
    if (galleryOpen) {
        GallerySheet(repo, parent = null, current = null, onDismiss = { galleryOpen = false }, onCreated = { id ->
            galleryOpen = false
            nav.push(Dest.Page(id))
        })
    }
    if (newEvent) {
        EventDialog(repo, null, java.time.LocalDate.now(), onDismiss = { newEvent = false }, onChanged = { scope.launch { repo.loadTodayEvents() } })
    }
}

/**
 * R6 (A11Y-23): Gesamtbreite des Inhaltsblocks der Heute-Seite bei [available] verfuegbarer Breite (mittig, inkl.
 * seitlicher Raender von je [SagaSpace.gutter]) - zweispaltig 1024 dp, sonst [SagaSpace.readingWidth]. Eine Stelle
 * fuer alles, was am Inhalt fluchten soll (Regel in [TodayLogic.contentWidthDp]).
 */
fun todayContentWidth(available: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    TodayLogic.contentWidthDp(available.value).dp

/** NAV-13: Schnellaktionen wie im Web (hz-acts): Neue Seite, Aus Vorlage, Aufgabe, Termin - umrandete Saga-Knoepfe, 48 dp */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(creating: Boolean, galleryOn: Boolean, onNewPage: () -> Unit, onTemplate: () -> Unit, onTask: () -> Unit, onEvent: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = SagaSpace.m).semantics { isTraversalGroup = true },
        horizontalArrangement = Arrangement.spacedBy(SagaSpace.s),
        verticalArrangement = Arrangement.spacedBy(SagaSpace.xs),
    ) {
        QuickAction(stringResource(R.string.gal_new_page), SagaIcons.filePlus, enabled = !creating, onClick = onNewPage)
        if (galleryOn) QuickAction(stringResource(R.string.gal_from_template), SagaIcons.template, onClick = onTemplate)
        QuickAction(stringResource(R.string.r4t_action_task), SagaIcons.checkbox, onClick = onTask)
        QuickAction(stringResource(R.string.r4t_action_event), SagaIcons.calendar, onClick = onEvent)
    }
}

@Composable
private fun QuickAction(label: String, icon: Int, enabled: Boolean = true, onClick: () -> Unit) {
    SagaOutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = SagaSpace.touch),
        contentPadding = PaddingValues(horizontal = SagaSpace.m, vertical = SagaSpace.xs),
    ) {
        SagaIcon(icon, null, Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = SagaSpace.s), maxLines = 1)
    }
}

/** NAV-13: "Keine Termine heute" als eine Zeile, rechts "+ Termin" (oeffnet den Termin-Dialog) */
@Composable
private fun NoEventsRow(onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = SagaSpace.m, end = SagaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SagaIcon(SagaIcons.calendar, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.today_no_events), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SagaSpace.m).weight(1f),
        )
        val desc = stringResource(R.string.ev_add)
        SagaTextButton(onClick = onAdd, modifier = Modifier.heightIn(min = SagaSpace.touch).semantics { contentDescription = desc }) {
            SagaIcon(SagaIcons.plus, null, Modifier.size(18.dp))
            Text(stringResource(R.string.r4t_action_event), modifier = Modifier.padding(start = SagaSpace.xs))
        }
    }
}

/** NAV-13: "Weitermachen" - Karten 140 x 88 dp (Tablet 184 dp breit) mit Symbol und Titel der zuletzt geoeffneten Seiten, waagerecht scrollbar */
@Composable
private fun ContinueRow(list: List<PageMeta>, repo: SagaRepository, cardWidth: androidx.compose.ui.unit.Dp, onOpen: (Long) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = SagaSpace.xs),
        contentPadding = PaddingValues(horizontal = SagaSpace.gutter),
        horizontalArrangement = Arrangement.spacedBy(SagaSpace.s),
    ) {
        items(list, key = { "c-${it.id}" }) { p ->
            val title = pageTitle(p)
            val openLabel = stringResource(R.string.r4t_open_page)
            Column(
                Modifier.size(width = cardWidth, height = 88.dp).clip(RoundedCornerShape(SagaRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable(onClickLabel = openLabel, role = Role.Button) { onOpen(p.id) }
                    .padding(SagaSpace.m),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                PageIcon(p.icon, repo, p.isDatabase)
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** NAV-13: Gruss nach Tageszeit, mit Vorname wie im Web ("Guten Tag, Anna"); ohne Namen wie bisher */
@Composable
private fun greeting(first: String?): String =
    if (first == null) stringResource(greetingRes()) else stringResource(greetingNameRes(), first)

private fun greetingNameRes(): Int = when (java.time.LocalTime.now().hour) {
    in 5..10 -> R.string.r4t_greeting_morning
    in 11..17 -> R.string.r4t_greeting_day
    in 18..22 -> R.string.r4t_greeting_evening
    else -> R.string.r4t_greeting_night
}

/** Gruss nach Tageszeit */
private fun greetingRes(): Int = when (java.time.LocalTime.now().hour) {
    in 5..10 -> R.string.greeting_morning
    in 11..17 -> R.string.greeting_day
    in 18..22 -> R.string.greeting_evening
    else -> R.string.greeting_night
}
