package io.github.yggdrasil_sh.saga.ui.database

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
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
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbRow
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
            if ((ev.type == "note.changed" && ev.long("id") == dbId) || ev.type == "reset") st.reload(debounce = 500)
        }
    }
    return st
}

@Composable
fun viewLabel(type: String): String = stringResource(
    when (type) {
        "board" -> R.string.dbv_view_board
        "list" -> R.string.dbv_view_list
        "gallery" -> R.string.dbv_view_gallery
        "calendar" -> R.string.dbv_view_calendar
        else -> R.string.dbv_view_table
    },
)

fun viewIcon(type: String): String = when (type) {
    "board" -> "▥"
    "list" -> "☰"
    "gallery" -> "▣"
    "calendar" -> "🗓"
    else -> "▦"
}

/** Zeilen und Werkzeugleiste der Datenbank als Eintraege der Seiten-LazyColumn */
fun LazyListScope.databaseItems(st: DbViewState, repo: SagaRepository, onOpen: (Long) -> Unit, wide: Boolean) {
    item(key = "db-toolbar", contentType = "db-toolbar") { DbToolbar(st, onOpen) }
    val s = st.schema
    if (st.noAccess) {
        item(key = "db-noaccess") { Text("🗃️ " + stringResource(R.string.db_missing), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        return
    }
    if (s == null) {
        item(key = "db-loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        return
    }
    val v = s.view(st.viewId)
    when (v.type) {
        "board" -> item(key = "db-board", contentType = "db-board") { DbBoard(st, repo, onOpen, Modifier.fillParentMaxHeight(0.8f)) }
        "calendar" -> item(key = "db-calendar", contentType = "db-calendar") { DbCalendar(st, repo, onOpen) }
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
            if (!list) item(key = "db-th", contentType = "db-th") { TableHead(st, v, wide) }
            val entries = withGroups(st, v)
            items(entries.size, key = { i -> entries[i].let { e -> if (e is DbRow) "r:${e.id}" else "h:${(e as GroupHead).id}" } },
                contentType = { i -> if (entries[i] is DbRow) (if (list) "db-li" else "db-tr") else "db-gh" }) { i ->
                when (val e = entries[i]) {
                    is DbRow -> if (list) ListRow(st, repo, e, v, onOpen) else TableRow(st, repo, e, v, wide, onOpen)
                    is GroupHead -> GroupHeader(st, e)
                }
            }
        }
    }
    if (v.type != "board" && v.type != "calendar") {
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

private data class GroupHead(val id: String, val option: String?, val count: Int)

/** Gruppen-Ueberschriften zwischen die geladenen Zeilen (Server liefert Zeilen in Gruppen-Reihenfolge + Anzahl) */
private fun withGroups(st: DbViewState, v: DbView): List<Any> {
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
private fun GroupHeader(st: DbViewState, h: GroupHead) {
    val gp = st.view?.let { st.schema?.groupProp(it) }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        val o = gp?.option(h.option)
        if (o != null) OptChip(o.name, o.color) else Text(stringResource(R.string.dbv_none_group, gp?.name.orEmpty()), style = MaterialTheme.typography.labelLarge)
        Text("  ${h.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Werkzeugleiste

@Composable
private fun DbToolbar(st: DbViewState, onOpen: (Long) -> Unit) {
    val s = st.schema
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (s != null) {
            val v = s.view(st.viewId)
            // Ansichten als Reiter; die aktuelle noch einmal antippen = bearbeiten
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.views.forEach { x ->
                    val on = x.id == v.id
                    val typeLabel = viewLabel(x.type)
                    FilterChip(
                        selected = on,
                        onClick = { if (on) { if (st.canEditViews) st.sheet = "view" } else st.selectView(x.id) },
                        label = { Text(viewIcon(x.type) + " " + x.name.ifEmpty { viewLabel(x.type) }, maxLines = 1) },
                        modifier = Modifier.semantics { contentDescription = x.name.ifEmpty { typeLabel } + ", " + typeLabel },
                    )
                }
                if (st.canEditViews) {
                    IconButton(onClick = { st.sheet = "new_view" }) { Icon(Icons.Filled.Add, stringResource(R.string.dbv_new_view)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val nf = v.filters.size
                val ns = v.sorts.size
                AssistChip(
                    onClick = { st.sheet = "filter" }, enabled = st.canEditViews || nf > 0,
                    label = { Text(if (nf > 0) stringResource(R.string.dbv_filter_count, nf) else stringResource(R.string.dbv_filter)) },
                )
                AssistChip(
                    onClick = { st.sheet = "sort" }, enabled = st.canEditViews || ns > 0,
                    label = { Text(if (ns > 0) stringResource(R.string.dbv_sort_count, ns) else stringResource(R.string.dbv_sort)) },
                )
                Spacer(Modifier.weight(1f))
                if (st.canAdd) {
                    FilledTonalButton(onClick = { scope.launch { st.create()?.let(onOpen) } }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.dbv_new_entry), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            var q by remember(st) { mutableStateOf(st.search) }
            OutlinedTextField(
                value = q, onValueChange = { q = it.take(500); st.updateSearch(q) }, singleLine = true,
                placeholder = { Text(stringResource(R.string.dbv_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (st.loaded && v.type != "calendar") {
                    Text(stringResource(R.string.dbv_count, st.matched, st.total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st.readOnly) Text("  · " + stringResource(R.string.dbv_read_only), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.locked && !st.readOnly) Text("  🔒", style = MaterialTheme.typography.labelMedium)
            }
            if (st.offline) Text(stringResource(R.string.dbv_offline_cached), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            st.error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ---------------------------------------------------------------- Tabelle

private fun colWidth(p: DbProp?, wide: Boolean): Dp = when (p?.type) {
    null -> if (wide) 280.dp else 200.dp
    "checkbox" -> 72.dp
    "number", "calc" -> 110.dp
    "date", "created", "edited" -> 130.dp
    "multi_select", "relation" -> 190.dp
    "text" -> 200.dp
    else -> 150.dp
}

@Composable
private fun TableHead(st: DbViewState, v: DbView, wide: Boolean) {
    val s = st.schema ?: return
    Column {
        Row(Modifier.horizontalScroll(st.hScroll).padding(vertical = 6.dp)) {
            Text(s.titleName ?: stringResource(R.string.dbv_name_col), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(colWidth(null, wide)).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.visibleProps(v).forEach { p ->
                Text(p.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(colWidth(p, wide)).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun rowColor(st: DbViewState, r: DbRow, v: DbView): androidx.compose.ui.graphics.Color? {
    if (v.colors.isEmpty()) return null
    val s = st.schema ?: return null
    return DbQuery(s).color(r, v)?.let { optColor(it).copy(alpha = 0.14f) }
}

@Composable
private fun TableRow(st: DbViewState, repo: SagaRepository, r: DbRow, v: DbView, wide: Boolean, onOpen: (Long) -> Unit) {
    val s = st.schema ?: return
    val fmt = rememberDbFormat { id -> repo.page(id)?.title }
    val tint = rowColor(st, r, v)
    Column(Modifier.then(if (tint != null) Modifier.background(tint) else Modifier)) {
        Row(Modifier.horizontalScroll(st.hScroll).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.width(colWidth(null, wide)).clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PageIcon(r.icon, repo, size = 18.dp)
                Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium, color = if (r.title.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
            s.visibleProps(v).forEach { p ->
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
                ) { DbValueView(p, value, fmt, maxLines = 2) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

// ---------------------------------------------------------------- Liste

@Composable
private fun ListRow(st: DbViewState, repo: SagaRepository, r: DbRow, v: DbView, onOpen: (Long) -> Unit) {
    val s = st.schema ?: return
    val fmt = rememberDbFormat { id -> repo.page(id)?.title }
    val shown = s.visibleProps(v)
    val check = shown.firstOrNull { it.type == "checkbox" }
    val chips = shown.filter { it.type in setOf("select", "multi_select", "date", "number", "calc") }.take(3)
    val tint = rowColor(st, r, v)
    Column(Modifier.then(if (tint != null) Modifier.background(tint) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (check != null) {
                val on = DbQuery.truthy(r.props[check.id])
                val cd = valueDescription(check, r.props[check.id], fmt)
                androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { st.setProp(r, check, DbValues.checkbox(it)) }, enabled = !st.readOnly,
                    modifier = Modifier.semantics { contentDescription = cd })
            } else Box(Modifier.padding(horizontal = 8.dp)) { PageIcon(r.icon, repo, size = 20.dp) }
            Column(Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }.padding(vertical = 8.dp)) {
                Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = if (r.title.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                val withValue = chips.filter { !DbQuery.isEmpty(DbQuery(s).value(it, r)) }
                if (withValue.isNotEmpty()) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        withValue.forEach { p -> DbValueView(p, DbQuery(s).value(p, r), fmt) }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

// ---------------------------------------------------------------- Galerie

@Composable
private fun GalleryCard(st: DbViewState, repo: SagaRepository, r: DbRow, v: DbView, onOpen: (Long) -> Unit) {
    val s = st.schema ?: return
    val fmt = rememberDbFormat { id -> repo.page(id)?.title }
    Surface(
        shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) },
    ) {
        Column {
            val pv = r.preview
            if (pv != null) {
                val sized = if (pv.startsWith("/files/") && !pv.contains("?")) "$pv?w=480" else pv
                AsyncImage(model = repo.absoluteUrl(sized), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    PageIcon(r.icon, repo, size = 36.dp)
                }
            }
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                s.visibleProps(v).filter { p -> !DbQuery.isEmpty(DbQuery(s).value(p, r)) && p.type != "text" }.take(3).forEach { p ->
                    DbValueView(p, DbQuery(s).value(p, r), fmt)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Board

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DbBoard(st: DbViewState, repo: SagaRepository, onOpen: (Long) -> Unit, modifier: Modifier) {
    val s = st.schema ?: return
    val v = s.view(st.viewId)
    val gp = s.groupProp(v)
    if (gp == null) {
        Text(stringResource(R.string.dbv_view_group_by) + ": –", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    val scope = rememberCoroutineScope()
    val keys: List<String?> = listOf<String?>(null) + gp.options.map { it.id }
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(keys.size, key = { "c:" + (keys[it] ?: "-") }) { i ->
            val k = keys[i]
            val col = st.columns[k]
            if (k == null && (col?.matched ?: 0) == 0) return@items       // "Ohne" leer: wie im Web nicht zeigen
            val o = gp.option(k)
            Column(
                Modifier.width(280.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp),
            ) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
                    if (o != null) OptChip(o.name, o.color) else Text(stringResource(R.string.dbv_none_group, gp.name), style = MaterialTheme.typography.labelLarge)
                    Text("  ${col?.matched ?: 0}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (st.canAdd) {
                        IconButton(onClick = {
                            scope.launch { st.create(if (k != null) mapOf(gp.id to JsonPrimitive(k)) else emptyMap())?.let(onOpen) }
                        }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Add, stringResource(R.string.dbv_new_entry)) }
                    }
                }
                val rows = col?.rows.orEmpty()
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(rows, key = { it.id }) { r -> BoardCard(st, repo, r, v, gp, onOpen) }
                    if (col != null && rows.size < col.matched) item(key = "more-" + rows.size) {
                        LaunchedEffect(Unit) { st.loadMoreColumn(k) }
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardCard(st: DbViewState, repo: SagaRepository, r: DbRow, v: DbView, gp: DbProp, onOpen: (Long) -> Unit) {
    val s = st.schema ?: return
    val fmt = rememberDbFormat { id -> repo.page(id)?.title }
    var menu by remember { mutableStateOf(false) }
    val title = r.title.ifEmpty { stringResource(R.string.page_untitled) }
    val moveLabel = stringResource(R.string.dbv_move_to)
    // Bedienungshilfen: "Verschieben nach <Spalte>" als eigene Aktionen
    val targets = (listOf<String?>(null) + gp.options.map { it.id }).filter { it != r.props[gp.id]?.let { e -> (e as? JsonPrimitive)?.content } }
    val noneName = stringResource(R.string.dbv_none_group, gp.name)
    val actions = if (st.readOnly) emptyList() else targets.map { t ->
        CustomAccessibilityAction("$moveLabel: " + (gp.option(t)?.name ?: noneName)) { st.setProp(r, gp, t?.let(::JsonPrimitive)); true }
    }
    Box {
        Surface(
            shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClickLabel = stringResource(R.string.dbv_open_entry), onLongClickLabel = moveLabel,
                    onLongClick = { if (!st.readOnly) menu = true }) { onOpen(r.id) }
                .semantics { customActions = actions },
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (!st.readOnly) IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.dbv_card_actions, title))
                    }
                }
                s.visibleProps(v).filter { p -> p.id != gp.id && p.type != "text" && !DbQuery.isEmpty(DbQuery(s).value(p, r)) }.take(3).forEach { p ->
                    DbValueView(p, DbQuery(s).value(p, r), fmt)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.dbv_open_entry)) }, onClick = { menu = false; onOpen(r.id) })
            HorizontalDivider()
            targets.forEach { t ->
                DropdownMenuItem(
                    text = { Text("$moveLabel: " + (gp.option(t)?.name ?: noneName)) },
                    onClick = { menu = false; st.setProp(r, gp, t?.let(::JsonPrimitive)) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Kalender

@Composable
private fun DbCalendar(st: DbViewState, repo: SagaRepository, onOpen: (Long) -> Unit) {
    val s = st.schema ?: return
    val v = s.view(st.viewId)
    val dp = s.dateProp(v)
    if (dp == null) {
        Text(stringResource(R.string.dbv_no_date_prop), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    val locale = appLocale()
    val scope = rememberCoroutineScope()
    val q = remember(s) { DbQuery(s) }
    val byDay = remember(st.monthRows, s) {
        st.monthRows.groupBy { r ->
            val x = q.value(dp, r)
            if (dp.isAuto) (x as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { q.isoDay(it) } else DbValues.parseDate(x)?.toString()
        }
    }
    var selected by remember(st.month) { mutableStateOf<LocalDate?>(null) }
    val month = st.month
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { st.showMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.dbv_prev_month)) }
            Text(month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + month.year, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = { st.showMonth(LocalDate.now()) }) { Text(stringResource(R.string.dbv_this_month)) }
            IconButton(onClick = { st.showMonth(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.dbv_next_month)) }
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
        val today = LocalDate.now()
        val dayFmt = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0 until 7) {
                    val day = start.plusDays((w * 7 + d).toLong())
                    val inMonth = day.month == month.month
                    val items = byDay[day.toString()].orEmpty()
                    val desc = day.format(dayFmt) + if (items.isNotEmpty()) ", " + items.size else ""
                    Column(
                        Modifier.weight(1f).heightIn(min = 64.dp).padding(1.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (day == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (inMonth) 1f else 0.4f))
                            .clickable { selected = day }.semantics(mergeDescendants = true) { contentDescription = desc }.padding(3.dp),
                    ) {
                        Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                            color = if (day == today) MaterialTheme.colorScheme.primary else if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                        items.take(2).forEach { r ->
                            Text(r.title.ifEmpty { "–" }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).padding(horizontal = 2.dp))
                        }
                        if (items.size > 2) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.dbv_more_entries, items.size - 2, items.size - 2), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val sel = selected
        if (sel != null) {
            Text(sel.format(dayFmt), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
            byDay[sel.toString()].orEmpty().forEach { r ->
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.dbv_open_entry)) { onOpen(r.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageIcon(r.icon, repo, size = 18.dp)
                    Text(r.title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (st.canAdd && dp.type == "date") {
                TextButton(onClick = { scope.launch { st.create(mapOf(dp.id to DbValues.date(sel)))?.let(onOpen) } }) {
                    Icon(Icons.Filled.Add, null)
                    Text(stringResource(R.string.dbv_new_on_day, sel.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))))
                }
            }
        }
    }
}
