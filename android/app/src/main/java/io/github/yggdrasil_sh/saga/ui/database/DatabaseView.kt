package io.github.yggdrasil_sh.saga.ui.database

import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.minimumInteractiveComponentSize

import androidx.compose.ui.semantics.selected
import io.github.yggdrasil_sh.saga.ui.theme.sagaPress
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import androidx.compose.runtime.collectAsState
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbRow
import io.github.yggdrasil_sh.saga.db.DbViews
import io.github.yggdrasil_sh.saga.db.DbValues
import io.github.yggdrasil_sh.saga.db.DbView
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters

// 2.4: Datenbank als Seite - Ansichten Tabelle, Board, Liste, Galerie, Kalender wie im Web/iOS. Die Zeilen stehen
// direkt in der LazyColumn der Seite (keine verschachtelten Listen), Seiten werden beim Scrollen nachgeladen.

@Composable
fun rememberDbViewState(dbId: Long, repo: SagaRepository): DbViewState {
    val scope = rememberCoroutineScope()
    val st = remember(dbId) { DbViewState(dbId, repo, scope, repo.dbPrefs?.getString("db_view_$dbId", null)) }
    LaunchedEffect(st) { st.reload() }
    // Werte anderswo geaendert (Eintrag als Seite, andere Ansicht, offline nachgeholt)
    LaunchedEffect(st) { repo.dbStore.changes.drop(1).collect { st.onStoreChange(it) } }
    // Live: Zeile/Datenbank auf einem anderen Geraet oder von einer anderen Person geaendert
    LaunchedEffect(st) {
        repo.live.events.collect { ev ->
            if (DbLive.rowsChanged(ev.type, ev.long("id"), ev.long("db"), dbId) || ev.type == "reset") st.reload(debounce = 500)
        }
    }
    // 4.11.: Live-Kanal wieder verbunden (Vordergrund, Netzwechsel) - Verpasstes nachholen
    LaunchedEffect(st) { repo.live.connected.drop(1).collect { if (it) st.reload(debounce = 500) } }
    return st
}

/**
 * 2.5 P7 Teil B: Zustand einer eingebetteten Datenbank (Block `database`) - Block-Ansichten aus data.views, Startansicht
 * aus data.view, gemerkte Ansicht je Seite+Block, kleinere Seiten. Live/Aenderungen wie im Vollbild.
 */
@Composable
fun rememberEmbeddedDbState(dbId: Long, repo: SagaRepository, data: kotlinx.serialization.json.JsonObject, pageId: Long, blockId: String): DbViewState {
    val scope = rememberCoroutineScope()
    val local = remember(data["views"]) { DbViews.localViews(data) }
    val key = "db_view_${pageId}_$blockId"
    val st = remember(dbId, key, local) {
        val remembered = repo.dbPrefs?.getString(key, null)
        val blockView = (data["view"] as? JsonPrimitive)?.content
        // Startansicht: gemerkte, sonst data.view (Ansicht des Blocks bzw. der Datenbank) - fehlt sie, die erste
        val start = if (local.isNotEmpty()) DbViews.startView(local, remembered, blockView) else remembered ?: blockView
        DbViewState(dbId, repo, scope, start, embed = DbViewState.Embed(local, key))
    }
    LaunchedEffect(st) { st.reload() }
    LaunchedEffect(st) { repo.dbStore.changes.drop(1).collect { st.onStoreChange(it) } }
    LaunchedEffect(st) {
        repo.live.events.collect { ev ->
            if (DbLive.rowsChanged(ev.type, ev.long("id"), ev.long("db"), dbId) || ev.type == "reset") st.reload(debounce = 500)
        }
    }
    // 4.11.: Live-Kanal wieder verbunden (Vordergrund, Netzwechsel) - Verpasstes nachholen
    LaunchedEffect(st) { repo.live.connected.drop(1).collect { if (it) st.reload(debounce = 500) } }
    return st
}

@Composable
fun viewLabel(type: String): String = stringResource(
    when (type) {
        "board" -> R.string.dbv_view_board
        "list" -> R.string.dbv_view_list
        "gallery" -> R.string.dbv_view_gallery
        "calendar" -> R.string.dbv_view_calendar
        "chart" -> R.string.e1_view_chart
        "timeline" -> R.string.e1_view_timeline
        else -> R.string.dbv_view_table
    },
)

/** Liniensymbol der Ansichtsart (ED-16, wie im Web) - vor dem Namen, dekorativ (der Name steht daneben) */
@androidx.annotation.DrawableRes
fun viewIcon(type: String): Int = when (type) {
    "board" -> SagaIcons.board
    "list" -> SagaIcons.list
    "gallery" -> SagaIcons.gallery
    "calendar" -> SagaIcons.calendar
    "chart" -> SagaIcons.chart
    "timeline" -> SagaIcons.timeline
    else -> SagaIcons.table
}

/** "Datenbank nicht verfuegbar": Liniensymbol statt 🗃️ */
@Composable
internal fun DbMissing(modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SagaIcon(SagaIcons.database, null, Modifier.padding(end = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.db_missing), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Zeilen und Werkzeugleiste der Datenbank als Eintraege der Seiten-LazyColumn */
fun LazyListScope.databaseItems(st: DbViewState, repo: SagaRepository, onOpen: (Long) -> Unit, wide: Boolean) {
    item(key = "db-toolbar", contentType = "db-toolbar") { DbToolbar(st, onOpen, repo, wide) }
    val s = st.schema
    if (st.noAccess) {
        item(key = "db-noaccess") { DbMissing(Modifier.padding(vertical = 12.dp)) }
        return
    }
    if (s == null) {
        item(key = "db-loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        return
    }
    val v = st.view ?: return
    when (v.type) {
        "board" -> item(key = "db-board", contentType = "db-board") { DbBoard(st, repo, onOpen, Modifier.fillParentMaxHeight(0.8f)) }
        "calendar" -> item(key = "db-calendar", contentType = "db-calendar") { DbCalendar(st, repo, onOpen, wide) }
        // 2.5 chart1 / timeline1: nur lesen
        "chart" -> item(key = "db-chart", contentType = "db-chart") { DbChartView(st, repo, wide) }
        "timeline" -> item(key = "db-timeline", contentType = "db-timeline") { DbTimeline(st, repo, onOpen, wide) }
        "gallery" -> {
            val cols = if (wide) 4 else 2
            val chunks = st.rows.chunked(cols)
            items(chunks.size, key = { "g:" + chunks[it].first().id }, contentType = { "db-gallery" }) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chunks[i].forEach { r -> Box(Modifier.weight(1f)) { GalleryCard(st, repo, r, v, onOpen) } }
                    repeat(cols - chunks[i].size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        else -> {
            val list = v.type == "list"
            // ED-12: Tabelle unter 600 dp als kompakte Liste, ausser "Als Tabelle" ist fuer die Ansicht an
            val compact = !list && DbCompact.compactList(v.type, wide, st.asTable(v.id))
            if (!list && !compact) item(key = "db-th", contentType = "db-th") { TableHead(st, v, wide) }
            val entries = withGroups(st, v)
            items(entries.size, key = { i -> entries[i].let { e -> if (e is DbRow) "r:${e.id}" else "h:${(e as GroupHead).id}" } },
                contentType = { i -> if (entries[i] is DbRow) (if (list) "db-li" else if (compact) "db-ci" else "db-tr") else "db-gh" }) { i ->
                when (val e = entries[i]) {
                    is DbRow -> if (list) ListRow(st, repo, e, v, onOpen) else if (compact) CompactTableRow(st, repo, e, v, onOpen) else TableRow(st, repo, e, v, wide, onOpen)
                    is GroupHead -> GroupHeader(st, e)
                }
            }
        }
    }
    if (v.type != "board" && v.type != "calendar" && v.type != "chart" && v.type != "timeline") {
        if (st.loaded && st.rows.isEmpty()) item(key = "db-empty") {
            Text(stringResource(R.string.dbv_empty_view), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        }
        if (st.hasMore) item(key = "db-more-" + st.rows.size) {
            LaunchedEffect(Unit) { st.loadMore() }                       // Ende erreicht: naechste Seite
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.dbv_loading_more), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    item(key = "db-end") { Spacer(Modifier.height(120.dp)) }
}

internal data class GroupHead(val id: String, val option: String?, val count: Int)

/** Gruppen-Ueberschriften zwischen die geladenen Zeilen (Server liefert Zeilen in Gruppen-Reihenfolge + Anzahl) */
internal fun withGroups(st: DbViewState, v: DbView): List<Any> {
    val gs = st.groups ?: return st.rows
    val gp = st.schema?.groupProp(v) ?: return st.rows
    val out = ArrayList<Any>(st.rows.size + gs.size)
    var i = 0
    for (g in gs) {
        if (g.count == 0) continue                                   // wie im Web: leere Gruppen in Tabelle/Liste nicht
        if (i >= st.rows.size) break
        out.add(GroupHead("${gp.id}:${g.id}", g.id, g.count))
        val end = minOf(st.rows.size, i + g.count)
        while (i < end) out.add(st.rows[i++])
    }
    while (i < st.rows.size) out.add(st.rows[i++])
    return out
}

@Composable
internal fun GroupHeader(st: DbViewState, h: GroupHead) {
    val gp = st.view?.let { st.schema?.groupProp(it) }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        GroupLabel(st, gp, h.option, st.groups?.firstOrNull { it.id == h.option }?.value)
        Text("  ${h.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Beschriftung einer Gruppe/Spalte: Option, Status-Gruppe, Formel-/Personen-Wert oder "Ohne <Spalte>" */
@Composable
internal fun GroupLabel(st: DbViewState, gp: DbProp?, key: String?, value: kotlinx.serialization.json.JsonElement?) {
    val v = st.view
    when {
        gp == null -> {}
        key == null -> Text(stringResource(R.string.dbv_none_group, gp.name), style = MaterialTheme.typography.labelLarge)
        gp.type == "select" && v?.groupStatus == true && gp.status ->
            OptChip(io.github.yggdrasil_sh.saga.db.DbProp.groupSymbol(key) + " " + statusGroupName(key), gp.firstOfGroup(key)?.color ?: "gray")
        gp.type == "select" -> gp.option(key)?.let { OptChip(if (gp.status) io.github.yggdrasil_sh.saga.db.DbProp.groupSymbol(it.group) + " " + it.name else it.name, it.color) }
            ?: Text(key, style = MaterialTheme.typography.labelLarge)
        else -> {
            val fmt = rememberDbFormat({ null }, st.people)
            if (value != null && value !is kotlinx.serialization.json.JsonNull) DbValueView(gp, value, fmt)
            else Text(key, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun statusGroupName(g: String): String = stringResource(
    when (g) {
        "doing" -> R.string.e1_status_doing
        "done" -> R.string.e1_status_done
        else -> R.string.e1_status_todo
    },
)

// ---------------------------------------------------------------- Werkzeugleiste

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DbToolbar(st: DbViewState, onOpen: (Long) -> Unit, repo: SagaRepository? = null, wide: Boolean = true) {
    if (st.managing) TemplateManager(st, onOpen)
    val features by (repo?.serverFeatures ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet())).collectAsState()
    val s = st.schema
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (s != null) {
            val v = st.view ?: return@Column
            // wie iOS: eine Zeile - Ansicht als Auswahl (Menue), Suche, Filter, Sortieren, Neuer Eintrag
            var viewMenu by remember { mutableStateOf(false) }
            var q by remember(st) { mutableStateOf(st.search) }
            var searchOpen by remember(st) { mutableStateOf(st.search.isNotEmpty()) }
            val nf = v.filters.size
            val ns = v.sorts.size
            // Design 2.5: mehrere Ansichten direkt als Pillen (ein Tippen wechselt), das Menue bleibt fuer Einstellungen
            val pills = st.views.size > 1
            // ED-12: Schalter "Als Tabelle" nur auf dem Handy und nur bei Tabellen
            val tableToggle = DbCompact.offerTableToggle(v.type, wide)
            // R3-Nachbesserung (28b): Menueinhalt einmal, verankert am Knopf, der es oeffnet (⋮ neben den Pillen bzw. Ansichts-Chip)
            val viewMenuItems: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                st.views.forEach { x ->
                    DropdownMenuItem(
                        text = { Text(x.name.ifEmpty { viewLabel(x.type) }) },
                        leadingIcon = { SagaIcon(viewIcon(x.type), null, Modifier.size(18.dp)) },
                        trailingIcon = { if (x.id == v.id) SagaIcon(SagaIcons.check, null) },
                        onClick = { viewMenu = false; if (x.id != v.id) st.selectView(x.id) },
                    )
                }
                if (tableToggle) {
                    androidx.compose.material3.HorizontalDivider()
                    // R3-Nachbesserung (28b): Schalter schaltet um und laesst das Menue offen (ein Schalter ist eine Einstellung,
                    // keine Aktion; TalkBack bleibt auf ihm und sagt den neuen Zustand an) - schliessen per Tipp daneben
                    AsTableMenuItem(st.asTable(v.id)) { st.setAsTable(v.id, it) }
                }
                if (st.canEditViews) {
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.dbv_view_settings)) }, leadingIcon = { MenuIconSlot() }, onClick = { viewMenu = false; st.sheet = "view" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.dbv_new_view)) }, leadingIcon = { SagaIcon(SagaIcons.plus, null) },
                        onClick = { viewMenu = false; st.sheet = "new_view" })
                }
                if (st.canManageTemplates) {                    // 2.5: auch ohne Vorlagen erreichbar
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.tpl_manage)) }, leadingIcon = { MenuIconSlot() }, onClick = { viewMenu = false; st.managing = true })
                }
                // 2.5 E2 (tasksrc1): abonnieren nur, wenn die Zuordnung (map) schon gesetzt ist - sonst Hinweis "im Web einrichten"
                if (repo != null && st.embed == null && st.dbId > 0 && "tasksrc1" in features) {
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.e2_task_source_use)) }, leadingIcon = { MenuIconSlot() }, onClick = {
                        viewMenu = false
                        repo.launchAction {
                            val (mapped, subscribed) = repo.taskSourceState(st.dbId)
                            when {
                                subscribed -> repo.notify(io.github.yggdrasil_sh.saga.util.UiText.Res(R.string.e2_task_source_already))
                                mapped -> { repo.subscribeTaskSource(st.dbId); repo.notify(io.github.yggdrasil_sh.saga.util.UiText.Res(R.string.e2_task_source_added)) }
                                else -> repo.notify(io.github.yggdrasil_sh.saga.util.UiText.Res(R.string.e2_task_source_web))
                            }
                        }
                    })
                }
            }
            if (pills) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ViewPills(st.views, v.id) { st.selectView(it) } }
                // Menue (Ansicht bearbeiten, neue Ansicht, Vorlagen) rechts neben den Pillen
                if (tableToggle || st.canEditViews || st.canManageTemplates || (repo != null && "tasksrc1" in features)) Box {
                    IconButton(onClick = { viewMenu = true }) { SagaIcon(SagaIcons.moreVert, stringResource(R.string.dbv_views_menu)) }
                    DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }, content = viewMenuItems)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box {
                    val typeLabel = viewLabel(v.type)
                    if (!pills) FilterChip(
                        selected = true, onClick = { viewMenu = true },
                        label = { Text(v.name.ifEmpty { typeLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { SagaIcon(viewIcon(v.type), null, Modifier.size(FilterChipDefaults.IconSize)) },
                        trailingIcon = { SagaIcon(SagaIcons.caretDown, null, Modifier.size(18.dp)) },
                        modifier = Modifier.widthIn(max = 200.dp).semantics { contentDescription = v.name.ifEmpty { typeLabel } + ", " + typeLabel },
                    )
                    DropdownMenu(expanded = viewMenu && !pills, onDismissRequest = { viewMenu = false }, content = viewMenuItems)
                }
                // R3-Nachbesserung (28/30): mit Pillen (Handy) beginnen die Symbole am Inhaltsrand (Symbolkante = Kante der
                // Pillen), "Neuer Eintrag" bleibt rechts. Vorher schob ein Spacer sie nach rechts und links blieb eine Luecke.
                val leftTools = pills && !wide
                val toolShift = if (leftTools) Modifier.offset(x = (-14).dp) else Modifier
                if (!leftTools) Spacer(Modifier.weight(1f))
                IconButton(onClick = { searchOpen = !searchOpen || q.isNotEmpty() }, modifier = toolShift) { SagaIcon(SagaIcons.search, stringResource(R.string.dbv_search)) }
                IconButton(onClick = { st.sheet = "filter" }, enabled = st.canEditViews || nf > 0, modifier = toolShift) {
                    androidx.compose.material3.BadgedBox(badge = { if (nf > 0) ActiveBadge(nf) }) {
                        SagaIcon(SagaIcons.filter, if (nf > 0) stringResource(R.string.dbv_filter_count, nf) else stringResource(R.string.dbv_filter),
                            tint = if (nf > 0) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current)
                    }
                }
                IconButton(onClick = { st.sheet = "sort" }, enabled = st.canEditViews || ns > 0, modifier = toolShift) {
                    androidx.compose.material3.BadgedBox(badge = { if (ns > 0) ActiveBadge(ns) }) {
                        SagaIcon(SagaIcons.sort, if (ns > 0) stringResource(R.string.dbv_sort_count, ns) else stringResource(R.string.dbv_sort),
                            tint = if (ns > 0) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current)
                    }
                }
                if (leftTools) Spacer(Modifier.weight(1f))
                if (st.canAdd) Box {
                    // 2.5: mit Vorlagen "Neu ▾" (leer, Vorlagen, Vorlagen verwalten); ohne Vorlagen gleich ein neuer Eintrag
                    var newMenu by remember { mutableStateOf(false) }
                    // A04: mit Standardvorlage legt Tippen direkt aus ihr an; langer Druck oeffnet das Menue (leer, andere Vorlagen)
                    val dflt = st.defaultTemplate
                    val action = DbViews.newAction(st.templates, dflt)
                    val dfltName = dflt?.title?.ifEmpty { null } ?: stringResource(R.string.tpl_template)
                    // ED-18: zweigeteilter Knopf - links "+ Neuer Eintrag", rechts ▾ (weitere Moeglichkeiten), sichtbarer Spalt,
                    // beide Ziele mindestens 48 dp. Langer Druck auf den linken Teil oeffnet das Menue zusaetzlich.
                    val split = st.templates.isNotEmpty()
                    val outer = SagaRadius.s
                    val inner = SagaRadius.xs
                    val leadShape = if (split) RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner) else RoundedCornerShape(outer)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = leadShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f, fill = false).clip(leadShape).combinedClickable(
                                role = Role.Button,
                                onClickLabel = if (action == DbViews.NewAction.DEFAULT_TEMPLATE) stringResource(R.string.tpl_new_from_default, dfltName) else null,
                                onLongClickLabel = if (split) stringResource(R.string.tpl_more_choices) else null,
                                onLongClick = if (split) ({ newMenu = true }) else null,
                            ) {
                                when (action) {
                                    DbViews.NewAction.BLANK -> scope.launch { st.create()?.let(onOpen) }
                                    DbViews.NewAction.DEFAULT_TEMPLATE -> dflt?.let { t -> scope.launch { st.createFrom(t)?.let(onOpen) } }
                                    DbViews.NewAction.MENU -> newMenu = true
                                }
                            },
                        ) {
                            Row(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SagaIcon(SagaIcons.plus, null, Modifier.size(18.dp))
                                // A17: Text darf kuerzen ("Neuer Ein…"), der Pfeil bleibt als eigener Teil immer sichtbar
                                Text(stringResource(R.string.dbv_new_entry), modifier = Modifier.padding(start = 4.dp).weight(1f, fill = false), maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (split) {
                            val moreLabel = stringResource(R.string.tpl_more_choices)
                            val trailShape = RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
                            Surface(
                                shape = trailShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(48.dp).clip(trailShape)
                                    .clickable(role = Role.Button, onClickLabel = moreLabel) { newMenu = true }
                                    .semantics { contentDescription = moreLabel },
                            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SagaIcon(SagaIcons.caretDown, null, Modifier.size(20.dp)) } }
                        }
                    }
                    DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.tpl_blank_entry)) }, onClick = { newMenu = false; scope.launch { st.create()?.let(onOpen) } })
                        androidx.compose.material3.HorizontalDivider()
                        val dt = st.defaultTemplate
                        (listOfNotNull(dt) + st.templates.filter { it.id != dt?.id }).forEach { t ->
                            val name = (t.icon?.takeIf { it.length <= 4 }?.let { "$it " } ?: "📐 ") + t.title.ifEmpty { stringResource(R.string.tpl_template) }
                            DropdownMenuItem(text = { Text(if (t.id == dt?.id) stringResource(R.string.tpl_default_suffix, name) else name) },
                                onClick = { newMenu = false; scope.launch { st.createFrom(t)?.let(onOpen) } })
                        }
                        if (st.canManageTemplates) {
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.tpl_manage)) }, onClick = { newMenu = false; st.managing = true })
                        }
                    }
                }
            }
            if (searchOpen || q.isNotEmpty()) {
                val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                OutlinedTextField(
                    value = q, onValueChange = { q = it.take(500); st.updateSearch(q) }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.dbv_search)) },
                    leadingIcon = { SagaIcon(SagaIcons.search, null) },
                    trailingIcon = {
                        IconButton(onClick = { q = ""; st.updateSearch(""); searchOpen = false }) { SagaIcon(SagaIcons.x, stringResource(R.string.action_close)) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).focusRequester(fr),
                )
                LaunchedEffect(searchOpen) { if (searchOpen && q.isEmpty()) runCatching { fr.requestFocus() } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (st.loaded && v.type != "calendar" && st.matched != st.total) {        // wie iOS: Zahl nur, wenn gefiltert/gesucht
                    Text(stringResource(R.string.dbv_count, st.matched, st.total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st.readOnly) Text("  · " + stringResource(R.string.dbv_read_only), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.locked && !st.readOnly) SagaIcon(SagaIcons.lock, stringResource(R.string.dbv_locked_short), Modifier.padding(start = 8.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (st.offline) Text(stringResource(R.string.dbv_offline_cached), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            st.error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Ansichten als Pillen zum direkten Wechseln (gewaehlt: zartes Gold), seitlich wischbar */
@Composable
internal fun ViewPills(views: List<DbView>, current: String, onSelect: (String) -> Unit) {
    val accent = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current
    // ED-16: Raender per Verlauf ausblenden (zeigt, dass es weitergeht) und zur aktiven Pille scrollen
    val sc = androidx.compose.foundation.rememberScrollState()
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, IntRange>() }
    var viewport by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fade = with(density) { 24.dp.toPx() }
    val active = bounds[current]
    var first by remember { mutableStateOf(true) }
    // R6-Rest: passen die Pillen nicht in die Zeile (Tabelle neben dem Seitenblatt, Phone), erst die Symbole weglassen;
    // reicht auch das nicht, bleibt das Wischen mit Verlauf. Je Breite und Ansichtszahl einmal entschieden (kein Flackern).
    var noIcons by remember(views.size, viewport) { mutableStateOf(false) }
    LaunchedEffect(sc.maxValue, viewport, views.size) {
        if (DbCompact.pillsDropIcons(noIcons, sc.maxValue, viewport)) noIcons = true
    }
    LaunchedEffect(current, active, viewport) {
        val b = active ?: return@LaunchedEffect
        if (viewport <= 0) return@LaunchedEffect
        val target = DbCompact.pillScroll(b.first, b.last, viewport, sc.value, sc.maxValue, fade.toInt())
        if (target != sc.value) { if (first) sc.scrollTo(target) else sc.animateScrollTo(target) }
        first = false
    }
    Row(
        Modifier.fillMaxWidth()
            .onSizeChanged { viewport = it.width }
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val w = minOf(fade, size.width / 4)
                if (sc.value > 0) drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Transparent), startX = 0f, endX = w),
                    size = androidx.compose.ui.geometry.Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
                )
                if (sc.value < sc.maxValue) drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black), startX = size.width - w, endX = size.width),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
                )
            }
            .horizontalScroll(sc).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        views.forEach { x ->
            val sel = x.id == current
            val bg by androidx.compose.animation.animateColorAsState(
                if (sel) accent.goldSoft else MaterialTheme.colorScheme.surfaceContainerHigh,
                io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.defaultEffects(), label = "pill",
            )
            Row(
                Modifier.onPlaced { c -> val l = c.positionInParent().x.toInt(); val r = l..(l + c.size.width); if (bounds[x.id] != r) bounds[x.id] = r }
                    .minimumInteractiveComponentSize().clip(androidx.compose.foundation.shape.CircleShape).background(bg)
                    .then(if (sel) Modifier.border(1.dp, accent.gold.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape) else Modifier)
                    .selectable(selected = sel, role = Role.Tab) { if (!sel) onSelect(x.id) }
                    .heightIn(min = 36.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (sel) accent.onGoldSoft else MaterialTheme.colorScheme.onSurfaceVariant
                if (!noIcons) SagaIcon(viewIcon(x.type), null, Modifier.padding(end = 6.dp).size(16.dp), tint = fg)
                Text(x.name.ifEmpty { viewLabel(x.type) }, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = fg)
            }
        }
    }
}

/** Kartenflaeche (Board, Galerie): hell fast weiss, dunkel etwas heller als die Spalte */
@Composable
internal fun cardColor(): androidx.compose.ui.graphics.Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest

// ---------------------------------------------------------------- Tabelle

internal fun colWidth(p: DbProp?, wide: Boolean): Dp = when (p?.type) {
    null -> if (wide) 280.dp else 200.dp
    "checkbox" -> 72.dp
    "number", "calc" -> 110.dp
    // ED-14: Datum im Kurzformat ("02.11.", "02.11.25") und Auswahl (Chip + 16 dp) schmaler - mehr Spalten auf dem Handy
    "date" -> 96.dp
    "select" -> 96.dp
    "created", "edited" -> 130.dp
    "multi_select", "relation" -> 190.dp
    "text" -> 200.dp
    else -> 150.dp
}

@Composable
internal fun TableHead(st: DbViewState, v: DbView, wide: Boolean) {
    val s = st.schema ?: return
    // 2.5 tableopts1: fixierte Spalten (freeze, 1 = Name) stehen beim waagrechten Scrollen
    val (frozen, moving) = DbViews.frozenSplit(s.visibleProps(v), v.freeze)
    val movingW = moving.map { colWidth(it, wide) }
    val snap = rememberColumnSnap(st.hScroll, movingW)
    var viewportPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column {
        // ED-15 / R2c: mindestens so hoch wie der Pfeil, damit die Kopfzeile am Scroll-Ende nicht springt.
        // Der Pfeil "Weitere Spalten" hat einen eigenen Platz rechts NEBEN dem Scrollbereich (MORE_COLUMNS_SLOT). Frueher
        // lag er ueber dem Scrollbereich und der Platz war nur am Inhaltsende reserviert; solange es weitere Spalten gab
        // (nur dann ist er sichtbar), verdeckte er deshalb den Kopf der Spalte am rechten Rand ("Start").
        Row(Modifier.heightIn(min = MORE_COLUMNS_SLOT).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.titleName ?: stringResource(R.string.dbv_name_col), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(colWidth(null, wide)).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            frozen.forEach { p ->
                Text(p.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(colWidth(p, wide)).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // ED-14: einrasten an Spaltenkanten, Linie + Schatten an der fixierten Spalte, sobald gescrollt
            Row(Modifier.weight(1f, fill = false).onSizeChanged { viewportPx = it.width }.frozenEdge(st.hScroll).wholeColumns(st.hScroll, movingW).horizontalScroll(st.hScroll, flingBehavior = snap)) {
                moving.forEach { p ->
                    Text(p.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(colWidth(p, wide)).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ColumnEndPad(st.hScroll, movingW)
            }
            if (moving.isNotEmpty()) Box(Modifier.size(MORE_COLUMNS_SLOT), contentAlignment = Alignment.Center) {
                // Nr. 23: es gibt weitere Spalten rechts - Pfeil (antippen = ein Stueck weiter), ED-15: Zielflaeche 48 x 48 dp
                if (st.hScroll.canScrollForward) {
                    val scope = rememberCoroutineScope()
                    val more = stringResource(R.string.dbv_more_columns)
                    Box(
                        Modifier.matchParentSize().clickable(onClickLabel = more) {
                            // R3-Nachbesserung: genau bis zur naechsten Spaltenkante - die bisher angeschnittene Spalte steht danach ganz links
                            val px = with(density) { movingW.map { it.toPx() } }
                            val target = DbCompact.nextColumnScroll(px, st.hScroll.value.toFloat(), viewportPx.toFloat(), st.hScroll.maxValue.toFloat())
                            scope.launch { st.hScroll.animateScrollTo(target.toInt()) }
                        },
                        contentAlignment = Alignment.Center,
                    ) { SagaIcon(SagaIcons.chevronRight, more, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** R3-Nachbesserung (28b): leerer Platz in Symbolgroesse, damit Menueeintraege ohne Symbol gleich eingerueckt sind */
@Composable
internal fun MenuIconSlot() {
    Spacer(Modifier.size(18.dp))
}

/** ED-17: Zahl an Filter/Sortierung meldet "aktiv", kein Problem - Saga-Gold (primary) statt Fehler-Rot */
@Composable
private fun ActiveBadge(n: Int) {
    androidx.compose.material3.Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Text("$n") }
}

/** ED-15/R2c: Breite/Hoehe des Pfeils "Weitere Spalten"; eigene Spalte rechts neben dem Scrollbereich (Kopf und Zeilen) */
private val MORE_COLUMNS_SLOT = 48.dp

@Composable
internal fun rowColor(st: DbViewState, r: DbRow, v: DbView): androidx.compose.ui.graphics.Color? {
    if (v.colors.isEmpty()) return null
    val s = st.schema ?: return null
    return DbQuery(s).color(r, v)?.let { optColor(it).copy(alpha = 0.14f) }
}

/** 2.5 tableopts1: Kartenfarbe (Flaeche + Streifen oben) aus view.colors - Board/Galerie */
@Composable
internal fun cardTint(st: DbViewState, r: DbRow, v: DbView): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>? {
    if (v.colors.isEmpty()) return null
    val s = st.schema ?: return null
    val c = DbQuery(s).color(r, v) ?: return null
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return androidx.compose.ui.graphics.Color(io.github.yggdrasil_sh.saga.blocks.InlineForms.bgArgb(c, dark)).compositeOver(cardColor()) to optColor(c)
}

/** "blockiert" (subtasks1): Liniensymbol "ban" in Fehlerfarbe statt ⛔ */
@Composable
internal fun BlockedMark() {
    SagaIcon(SagaIcons.ban, stringResource(R.string.e1_blocked), Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.error)
}

@Composable
internal fun TableRow(st: DbViewState, repo: SagaRepository, r: DbRow, v: DbView, wide: Boolean, onOpen: (Long) -> Unit) {
    val s = st.schema ?: return
    val fmt = rememberDbFormat({ id -> repo.page(id)?.title }, st.people)
    val tint = rowColor(st, r, v)
    val (frozen, moving) = DbViews.frozenSplit(s.visibleProps(v), v.freeze)
    val movingW = moving.map { colWidth(it, wide) }
    val snap = rememberColumnSnap(st.hScroll, movingW)
    @Composable
    fun cell(p: DbProp) {
        val value = DbQuery(s).value(p, r)
        val desc = valueDescription(p, value, fmt)
        val canEdit = !st.readOnly && p.editable
        Box(
            Modifier.width(colWidth(p, wide)).heightIn(min = 44.dp)
                .then(if (canEdit) Modifier.clickable(onClickLabel = stringResource(R.string.dbv_edit_prop, p.name), role = if (p.type == "checkbox") Role.Checkbox else Role.Button) {
                    if (p.type == "checkbox") st.setProp(r, p, DbValues.checkbox(!DbQuery.truthy(value))) else st.editing = r to p
                } else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = desc }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) { CompactValue(p, value, fmt, err = r.fxErr[p.id], labeled = false, maxLines = DbCompact.cellLines(p.effType, v.wraps(p.id))) }
    }
    Column(Modifier.then(if (tint != null) Modifier.background(tint) else Modifier).dbRowSelected(r.id)) {
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.width(colWidth(null, wide)).clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PageIcon(r.icon, repo, size = 18.dp)
                Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = if (v.wraps("title")) 6 else 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium, color = if (r.title.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (r.blocked) BlockedMark()
            }
            frozen.forEach { cell(it) }
            // gleiche Inhaltsbreite wie die Kopfzeile (ED-15), sonst rechnen Kopf und Zeilen mit verschiedenem Scroll-Ende
            Row(Modifier.weight(1f, fill = false).frozenEdge(st.hScroll).wholeColumns(st.hScroll, movingW).horizontalScroll(st.hScroll, flingBehavior = snap), verticalAlignment = Alignment.CenterVertically) {
                moving.forEach { cell(it) }
                ColumnEndPad(st.hScroll, movingW)
            }
            // R2c: gleiche Spalte wie der Pfeil im Kopf, damit Kopf und Zeilen denselben Scrollbereich haben
            if (moving.isNotEmpty()) Spacer(Modifier.width(MORE_COLUMNS_SLOT))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

// ---------------------------------------------------------------- Liste

@Composable
internal fun ListRow(st: DbViewState, repo: SagaRepository, r: DbRow, v: DbView, onOpen: (Long) -> Unit) {
    val s = st.schema ?: return
    val fmt = rememberDbFormat({ id -> repo.page(id)?.title }, st.people)
    val shown = s.visibleProps(v)
    val check = shown.firstOrNull { it.type == "checkbox" }
    // 2.5 status1: ohne Haekchen-Spalte hakt eine Status-Spalte ab (erste Option der Gruppe "Erledigt" bzw. "Zu erledigen")
    val status = if (check == null) s.props.firstOrNull { it.status && it.type == "select" && it.firstOfGroup("done") != null } else null
    val chips = shown.filter { it.effType in setOf("select", "multi_select", "date", "number", "calc", "person") && it.id != status?.id }.take(3)
    val tint = rowColor(st, r, v)
    Column(Modifier.then(if (tint != null) Modifier.background(tint) else Modifier).dbRowSelected(r.id).padding(start = (r.depth * 20).dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (check != null) {
                val on = DbQuery.truthy(r.props[check.id])
                val cd = valueDescription(check, r.props[check.id], fmt)
                androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { st.setProp(r, check, DbValues.checkbox(it)) }, enabled = !st.readOnly,
                    modifier = Modifier.semantics { contentDescription = cd })
            } else if (status != null) {
                val cur = (r.props[status.id] as? JsonPrimitive)?.content
                val on = status.groupOf(cur) == "done"
                val cd = valueDescription(status, r.props[status.id], fmt)
                androidx.compose.material3.Checkbox(checked = on, enabled = !st.readOnly, modifier = Modifier.semantics { contentDescription = cd },
                    onCheckedChange = { done -> DbViews.statusToggle(status, cur, done)?.let { st.setProp(r, status, it) } })
            } else Box(Modifier.padding(horizontal = 8.dp)) { PageIcon(r.icon, repo, size = 20.dp) }
            Column(Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        color = if (r.title.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (r.blocked) BlockedMark()
                }
                val withValue = chips.filter { !DbQuery.isEmpty(DbQuery(s).value(it, r)) || r.fxErr[it.id] != null }
                if (withValue.isNotEmpty()) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        withValue.forEach { p -> DbValueView(p, DbQuery(s).value(p, r), fmt, err = r.fxErr[p.id]) }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

// ---------------------------------------------------------------- Galerie

@Composable
internal fun GalleryCard(st: DbViewState, repo: SagaRepository, r: DbRow, v: DbView, onOpen: (Long) -> Unit) {
    val s = st.schema ?: return
    val fmt = rememberDbFormat({ id -> repo.page(id)?.title }, st.people)
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val ccol = cardTint(st, r, v)
    Surface(
        shape = MaterialTheme.shapes.medium, color = ccol?.first ?: cardColor(),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().sagaPress(src).clip(MaterialTheme.shapes.medium).dbCardSelected(r.id, MaterialTheme.shapes.medium)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) },
    ) {
        Column {
            ccol?.let { Box(Modifier.fillMaxWidth().height(4.dp).background(it.second)) }
            val pv = r.preview
            // wie iOS/Web: Titelbild (bzw. erstes Bild) oben, ohne Bild keine leere Flaeche; Symbol vor dem Namen
            if (pv != null) {
                val sized = if ((pv.startsWith("/files/") || pv.startsWith("/api/fed/files/")) && !pv.contains("?")) "$pv?w=640" else pv
                AsyncImage(model = repo.absoluteUrl(sized), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!r.icon.isNullOrEmpty()) PageIcon(r.icon, repo, size = 20.dp)
                    Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                }
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.visibleProps(v).filter { p -> (!DbQuery.isEmpty(DbQuery(s).value(p, r)) || r.fxErr[p.id] != null) && p.type != "text" }.take(3).forEach { p ->
                        // ED-19: Datum mit Kurzlabel bzw. als Zeitraum mit Kalender-Symbol
                        CompactValue(p, DbQuery(s).value(p, r), fmt, err = r.fxErr[p.id])
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Board

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DbBoard(st: DbViewState, repo: SagaRepository, onOpen: (Long) -> Unit, modifier: Modifier) {
    val s = st.schema ?: return
    val v = s.view(st.viewId)
    val gp = s.groupProp(v)
    if (gp == null) {
        Text(stringResource(R.string.dbv_view_group_by) + ": –", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    val scope = rememberCoroutineScope()
    val keys: List<String?> = st.boardKeys.ifEmpty { listOf<String?>(null) + gp.options.map { it.id } }
    // 2.5: nach Formel/Rollup/Person gruppiert - nur lesend (kein Ziehen, "+ Neu" ohne Wert)
    val readOnlyGroups = gp.type != "select"
    // ED-20: Spalten rasten ein; auf dem Handy Spaltenbreite = Bildschirm − 48 dp (die naechste Spalte schaut hervor)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    val colW = DbCompact.boardColumnWidth(maxWidth.value).dp
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val snap = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(rowState, androidx.compose.foundation.gestures.snapping.SnapPosition.Start)
    LazyRow(Modifier.fillMaxSize(), state = rowState, flingBehavior = snap, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(keys.size, key = { "c:" + (keys[it] ?: "-") }) { i ->
            val k = keys[i]
            val col = st.columns[k]
            if (k == null && (col?.matched ?: 0) == 0) return@items       // "Ohne" leer: wie im Web nicht zeigen
            Column(
                Modifier.width(colW).fillMaxHeight().clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // R3-Nachbesserung (30b): Name + Zahl links in einer eigenen Zeile mit dem ganzen Rest, "+" ganz rechts.
                    // Vorher teilten sich Name (weight, fill=false) und Spacer (weight) den Platz - "+" landete in der Mitte.
                    Row(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f, fill = false)) { GroupLabel(st, gp, k, st.boardValues[k]) }
                        Spacer(Modifier.width(8.dp))
                        io.github.yggdrasil_sh.saga.ui.theme.SagaPill(
                            "${col?.matched ?: 0}", container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (st.canAdd) {
                        IconButton(onClick = {
                            val cv = if (readOnlyGroups) null else st.columnValue(gp, k)
                            scope.launch { st.create(if (cv != null) mapOf(gp.id to cv) else emptyMap())?.let(onOpen) }
                        }) { SagaIcon(SagaIcons.plus, stringResource(R.string.dbv_new_entry)) }
                    }
                }
                val rows = col?.rows.orEmpty()
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.id }) { r -> Box(Modifier.animateItem()) { BoardCard(st, repo, r, v, gp, onOpen, keys, readOnlyGroups) } }
                    if (col != null && rows.size < col.matched) item(key = "more-" + rows.size) {
                        LaunchedEffect(Unit) { st.loadMoreColumn(k) }
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoardCard(st: DbViewState, repo: SagaRepository, r: DbRow, v: DbView, gp: DbProp, onOpen: (Long) -> Unit, keys: List<String?>, readOnlyGroups: Boolean = false) {
    val s = st.schema ?: return
    val fmt = rememberDbFormat({ id -> repo.page(id)?.title }, st.people)
    var menu by remember { mutableStateOf(false) }
    val title = r.title.ifEmpty { stringResource(R.string.page_untitled) }
    val moveLabel = stringResource(R.string.dbv_move_to)
    // Bedienungshilfen: "Verschieben nach <Spalte>" als eigene Aktionen
    val curKey = DbViews.cardKey(gp, v, (r.props[gp.id] as? JsonPrimitive)?.content)
    val targets = if (readOnlyGroups) emptyList() else keys.filter { it != curKey }
    val noneName = stringResource(R.string.dbv_none_group, gp.name)
    val names = targets.associateWith { t -> if (t == null) noneName else if (v.groupStatus && gp.status) statusGroupName(t) else gp.option(t)?.name ?: t }
    val actions = if (st.readOnly) emptyList() else targets.map { t ->
        CustomAccessibilityAction("$moveLabel: " + names[t]) { st.moveTo(r, gp, t); true }
    }
    val ccol = cardTint(st, r, v)
    Box {
        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Surface(
            shape = MaterialTheme.shapes.medium, color = cardColor(), shadowElevation = 1.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().sagaPress(src).clip(MaterialTheme.shapes.medium).dbCardSelected(r.id, MaterialTheme.shapes.medium)
                .combinedClickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current,onClickLabel = stringResource(R.string.dbv_open_entry), onLongClickLabel = moveLabel,
                    onLongClick = { if (!st.readOnly && targets.isNotEmpty()) menu = true }) { onOpen(r.id) }
                .semantics { customActions = actions },
        ) {
            Column {
            ccol?.let { Box(Modifier.fillMaxWidth().height(4.dp).background(it.second)) }
            Column(Modifier.then(if (ccol != null) Modifier.background(ccol.first) else Modifier).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (r.blocked) BlockedMark()
                    if (!st.readOnly && targets.isNotEmpty()) IconButton(onClick = { menu = true }) {
                        SagaIcon(SagaIcons.moreVert, stringResource(R.string.dbv_card_actions, title))
                    }
                }
                s.visibleProps(v).filter { p -> p.id != gp.id && p.type != "text" && (!DbQuery.isEmpty(DbQuery(s).value(p, r)) || r.fxErr[p.id] != null) }.take(3).forEach { p ->
                    // ED-19: Datum mit Kurzlabel ("Start 16.11.") bzw. als Zeitraum "16.11. → 18.12.2026"
                    CompactValue(p, DbQuery(s).value(p, r), fmt, err = r.fxErr[p.id])
                }
            }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.dbv_open_entry)) }, onClick = { menu = false; onOpen(r.id) })
            HorizontalDivider()
            targets.forEach { t ->
                DropdownMenuItem(
                    text = { Text("$moveLabel: " + names[t]) },
                    onClick = { menu = false; st.moveTo(r, gp, t) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Kalender

@Composable
internal fun DbCalendar(st: DbViewState, repo: SagaRepository, onOpen: (Long) -> Unit, wide: Boolean = false) {
    val s = st.schema ?: return
    val v = st.view ?: return
    val dp = s.dateProp(v)
    if (dp == null) {
        Text(stringResource(R.string.dbv_no_date_prop), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    val locale = appLocale()
    val scope = rememberCoroutineScope()
    val q = remember(s) { DbQuery(s) }
    val zone = remember { java.time.ZoneId.systemDefault() }
    // 2.5 datetime1: Zeitraum auf jedem Tag, Uhrzeit vor dem Namen (Zone des Betrachters)
    val byDay = remember(st.monthRows, s) { DbViews.calendarDays(st.monthRows, dp, q, zone) }
    val timeFmt = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    fun label(r: DbRow): String {
        val t = if (dp.type == "date") io.github.yggdrasil_sh.saga.db.DbDate.parse(DbQuery.text(r.props[dp.id]).ifEmpty { null }, zone)?.start?.localTime(zone) else null
        return (t?.let { it.format(timeFmt) + " " } ?: "") + r.title.ifEmpty { "–" }
    }
    var selected by remember(st.month) { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    val dayFmt = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    val accent = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current
    if (v.week) {
        // 2.5 tableopts1: Woche Mo-So - Tablet als 7 Spalten, Telefon als Tagesliste
        val week = st.week
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { st.showWeek(week.minusWeeks(1)) }) { SagaIcon(SagaIcons.chevronLeft, stringResource(R.string.e1_prev_week)) }
                Text(week.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) + " – " +
                    week.plusDays(6).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                SagaTextButton(onClick = { st.showWeek(LocalDate.now()) }) { Text(stringResource(R.string.e1_today)) }
                IconButton(onClick = { st.showWeek(week.plusWeeks(1)) }) { SagaIcon(SagaIcons.chevronRight, stringResource(R.string.e1_next_week)) }
            }
            val days = (0 until 7).map { week.plusDays(it.toLong()) }
            if (wide) Row(Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    Column(
                        Modifier.weight(1f).heightIn(min = 160.dp).padding(2.dp).clip(RoundedCornerShape(SagaRadius.md))
                            .background(if (day == today) accent.goldSoft else MaterialTheme.colorScheme.surfaceContainerLow).padding(4.dp),
                    ) {
                        Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + day.dayOfMonth, style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.semantics { heading() })
                        byDay[day.toString()].orEmpty().forEach { r ->
                            Text(label(r), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp).fillMaxWidth().clip(RoundedCornerShape(SagaRadius.xs)).background(MaterialTheme.colorScheme.surface).dbRowSelected(r.id)
                                    .clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }.padding(3.dp))
                        }
                        if (st.canAdd && dp.type == "date") SagaTextButton(onClick = { scope.launch { st.create(mapOf(dp.id to DbValues.date(day)))?.let(onOpen) } }) {
                            SagaIcon(SagaIcons.plus, stringResource(R.string.dbv_new_on_day, day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))), Modifier.size(16.dp))
                        }
                    }
                }
            } else days.forEach { day ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(day.format(dayFmt), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() },
                        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    if (st.canAdd && dp.type == "date") IconButton(onClick = { scope.launch { st.create(mapOf(dp.id to DbValues.date(day)))?.let(onOpen) } }) {
                        SagaIcon(SagaIcons.plus, stringResource(R.string.dbv_new_on_day, day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))))
                    }
                }
                val items = byDay[day.toString()].orEmpty()
                if (items.isEmpty()) Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                items.forEach { r ->
                    Row(Modifier.fillMaxWidth().dbRowSelected(r.id).clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageIcon(r.icon, repo, size = 18.dp)
                        Text(label(r), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        return
    }
    val month = st.month
    // ED-21: Handy (< 600 dp) - Zellen mit Punkten, darunter immer die Tagesagenda (vorausgewaehlt heute)
    val compact = !wide
    val shown: LocalDate? = selected ?: if (compact) DbCompact.agendaDay(month, today) else null
    val agenda = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    fun dotColor(r: DbRow): androidx.compose.ui.graphics.Color = q.color(r, v)?.let { optColor(it) } ?: accent.gold
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { st.showMonth(month.minusMonths(1)) }) { SagaIcon(SagaIcons.chevronLeft, stringResource(R.string.dbv_prev_month)) }
            Text(month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + month.year, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() })
            SagaTextButton(onClick = { st.showMonth(LocalDate.now()) }) { Text(stringResource(R.string.e1_today)) }
            IconButton(onClick = { st.showMonth(month.plusMonths(1)) }) { SagaIcon(SagaIcons.chevronRight, stringResource(R.string.dbv_next_month)) }
        }
        // Wochentage ab Montag (wie im Web)
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { i ->
                Text(DayOfWeek.MONDAY.plus(i.toLong()).getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(2.dp))
            }
        }
        val start = month.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(start, month.plusMonths(1)) + 6) / 7).toInt()
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0 until 7) {
                    val day = start.plusDays((w * 7 + d).toLong())
                    val inMonth = day.month == month.month
                    val items = byDay[day.toString()].orEmpty()
                    // R3-Nachbesserung (31b): Anzahl mit Einheit ("1 Eintrag", "3 Einträge") statt nackter Zahl
                    val desc = day.format(dayFmt) + if (items.isNotEmpty()) ", " + pluralStringResource(R.plurals.r3d_cal_entries, items.size, items.size) else ""
                    val isSel = day == shown
                    Column(
                        Modifier.weight(1f).heightIn(min = if (compact) 48.dp else 64.dp).padding(1.5.dp).clip(RoundedCornerShape(SagaRadius.md))
                            .background(if (isSel) accent.goldSoft else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (inMonth) 1f else 0.4f))
                            .then(if (isSel) Modifier.border(1.5.dp, accent.gold, RoundedCornerShape(SagaRadius.md)) else Modifier)
                            .clickable {
                                selected = day
                                if (compact) scope.launch { agenda.bringIntoView() }      // Agenda bleibt sichtbar
                            }.semantics(mergeDescendants = true) { contentDescription = desc; this.selected = isSel }.padding(3.dp),
                        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
                    ) {
                        // heute: Tageszahl in einem Goldkreis
                        Box(
                            Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (day == today) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (day == today) MaterialTheme.colorScheme.onPrimary else if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (compact) {
                            // bis zu 3 Punkte in der Eintragsfarbe (Farbe der Ansicht, sonst Gold)
                            if (items.isNotEmpty()) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                items.take(DbCompact.dots(items.size)).forEach { r ->
                                    Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dotColor(r)))
                                }
                            }
                        } else items.take(2).forEach { r ->
                            Text(label(r), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = accent.onGoldSoft,
                                modifier = Modifier.padding(top = 2.dp).fillMaxWidth().clip(RoundedCornerShape(SagaRadius.xs))
                                    .background(if (isSel) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) else accent.goldSoft).padding(horizontal = 3.dp))
                        }
                        if (!compact && items.size > 2) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.dbv_more_entries, items.size - 2, items.size - 2), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val sel = shown
        if (sel != null) Column(Modifier.fillMaxWidth().bringIntoViewRequester(agenda)) {
            Text(sel.format(dayFmt), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
            val dayItems = byDay[sel.toString()].orEmpty()
            if (dayItems.isEmpty()) Text(stringResource(R.string.dbv_agenda_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            dayItems.forEach { r ->
                Row(Modifier.fillMaxWidth().dbRowSelected(r.id).clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (compact) Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dotColor(r)))
                    PageIcon(r.icon, repo, size = 18.dp)
                    Text(label(r), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (st.canAdd && dp.type == "date") {
                SagaTextButton(onClick = { scope.launch { st.create(mapOf(dp.id to DbValues.date(sel)))?.let(onOpen) } }) {
                    SagaIcon(SagaIcons.plus, null)
                    Text(stringResource(R.string.dbv_new_on_day, sel.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))))
                }
            }
        }
    }
}

/** 4.11.: Eintraege der Datenbank anderswo geaendert? db.rows {db} (Server ab 2.5, an alle mit Zugriff) bzw.
 *  note.changed {id = Datenbank} (wer die Datenbank offen hat) */
object DbLive {
    fun rowsChanged(type: String, id: Long?, db: Long?, dbId: Long): Boolean = when (type) {
        "db.rows" -> db == dbId
        "note.changed" -> id == dbId
        else -> false
    }
}
