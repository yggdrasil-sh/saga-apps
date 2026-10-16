package io.github.yggdrasil_sh.saga.ui.page

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.blocks.DbProp
import io.github.yggdrasil_sh.saga.blocks.DbQuery
import io.github.yggdrasil_sh.saga.blocks.DbRow
import io.github.yggdrasil_sh.saga.blocks.DbView
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** So viele Eintraege zeigt eine Ansicht zuerst (lange Datenbanken: "Alle anzeigen") */
private const val FIRST_ROWS = 50

/**
 * Datenbank wie in iOS (Database.swift): Ansichten als Reiter, Liste/Board/Galerie/Kalender.
 * embedded = Block auf einer Seite (Kopf oeffnet die Datenbank als ganze Seite).
 * localViews = eigene Ansichten eines verknuepften Blocks (in der Seite gespeichert), sonst die der Datenbank.
 */
@Composable
fun DatabaseView(
    dbId: Int,
    repo: SagaRepository,
    openPage: (Int) -> Unit,
    viewId: String? = null,
    localViews: List<DbView>? = null,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick by repo.dataTick.collectAsState()
    val pages by repo.pages.collectAsState()
    var q by remember(dbId) { mutableStateOf<DbQuery?>(null) }
    var error by remember(dbId) { mutableStateOf<String?>(null) }
    var current by rememberSaveable(dbId) { mutableStateOf(viewId) }
    var showAll by remember(dbId) { mutableStateOf(false) }

    suspend fun load() {
        try {
            q = repo.dbRows(dbId)
            error = null
        } catch (e: Exception) {
            error = repo.friendly(e)
        }
    }
    LaunchedEffect(dbId, tick) { load() }

    val query = q
    val views = localViews?.takeIf { it.isNotEmpty() } ?: query?.views ?: emptyList()
    val view = views.firstOrNull { it.id == current } ?: views.firstOrNull()

    fun add(props: Map<String, JsonElement>) {
        scope.launch {
            try {
                openPage(repo.addRow(dbId, props))
            } catch (e: Exception) {
                Toast.makeText(context, repo.friendly(e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun set(row: DbRow, pid: String, value: JsonElement) {
        val qq = q ?: return
        q = qq.copy(rows = qq.rows.map { if (it.id == row.id) it.copy(props = it.props + (pid to DbQuery.plain(value))) else it })
        scope.launch {
            try {
                repo.setRowProp(row.id, pid, value)
            } catch (e: Exception) {
                Toast.makeText(context, repo.friendly(e), Toast.LENGTH_SHORT).show()
                load()
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Kopf: Name der Datenbank (eingebettet: antippen = als ganze Seite oeffnen) + Neu
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (embedded) {
                val meta = pages.firstOrNull { it.id == dbId }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { openPage(dbId) }.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageIcon(meta?.icon, repo, isDatabase = true, size = 18.dp)
                    Text(
                        meta?.label ?: "Datenbank", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (query != null) NewButton(query, view, ::add, onTemplate = { t ->
                scope.launch {
                    try {
                        openPage(repo.addRowFromTemplate(dbId, t))
                    } catch (e: Exception) {
                        Toast.makeText(context, repo.friendly(e), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        if (views.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                views.forEach { v ->
                    FilterChip(
                        selected = v.id == view?.id, onClick = { current = v.id; showAll = false },
                        label = { Text(v.name.ifEmpty { viewTypeName(v.type) }) },
                    )
                }
            }
        }
        when {
            query == null && error == null -> CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
            query != null -> {
                val rows = query.rows(view)
                when (view?.type) {
                    "board" -> BoardView(query, view, rows, openPage, ::add)
                    "gallery" -> GalleryView(query, view, rows, openPage, repo)
                    "calendar" -> CalendarView(query, view, rows, openPage)
                    else -> RowList(query, view, if (showAll) rows else rows.take(FIRST_ROWS), openPage, repo, ::set)
                }
                if (view?.type !in setOf("board", "calendar") && rows.size > FIRST_ROWS && !showAll) {
                    TextButton(onClick = { showAll = true }) { Text("Alle ${rows.size} Einträge anzeigen") }
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private fun viewTypeName(t: String) = when (t) {
    "board" -> "Board"
    "gallery" -> "Galerie"
    "calendar" -> "Kalender"
    "list" -> "Liste"
    else -> "Tabelle"
}

/** "+ Neu" - mit Vorlagen als Menue */
@Composable
private fun NewButton(q: DbQuery, view: DbView?, add: (Map<String, JsonElement>) -> Unit, onTemplate: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { if (q.templates.isEmpty()) add(q.defaults(view)) else menu = true }) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Text("Neu", Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Leerer Eintrag") }, onClick = { menu = false; add(q.defaults(view)) })
            q.templates.forEach { t ->
                DropdownMenuItem(text = { Text(listOf(t.icon, t.title.ifEmpty { "Vorlage" }).filter { it.isNotEmpty() }.joinToString(" ")) }, onClick = {
                    menu = false
                    onTemplate(t.id)
                })
            }
        }
    }
}

// ---------------------------------------------------------------- Liste (auch fuer Tabellen auf dem Handy)

/** Haekchen vorne (erste Checkbox-Eigenschaft), Titel, darunter farbige Werte - nichts wird abgeschnitten */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowList(q: DbQuery, view: DbView?, rows: List<DbRow>, openPage: (Int) -> Unit, repo: SagaRepository, set: (DbRow, String, JsonElement) -> Unit) {
    val hidden = view?.hidden ?: emptySet()
    val check = q.props.firstOrNull { it.type == "checkbox" && it.id !in hidden }
    val chips = q.props.filter { it.type in setOf("select", "multi_select", "date", "number", "calc") && it.id !in hidden }.take(3)
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { r ->
            val color = q.colorName(r, view)?.let { optColor(it) }
            Row(
                Modifier.fillMaxWidth()
                    .then(if (color != null) Modifier.background(color.copy(alpha = 0.14f)) else Modifier)
                    .clickable { openPage(r.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (color != null) Box(Modifier.width(3.dp).height(36.dp).background(color))
                if (check != null) {
                    Checkbox(checked = r.props[check.id] == true, onCheckedChange = { set(r, check.id, JsonPrimitive(it)) })
                } else {
                    Box(Modifier.padding(horizontal = 8.dp)) { PageIcon(r.icon, repo) }
                }
                Column(Modifier.weight(1f).padding(end = 8.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (check != null && !r.icon.isNullOrEmpty()) Box(Modifier.padding(end = 6.dp)) { PageIcon(r.icon, repo, size = 18.dp) }
                        Text(
                            r.title.ifEmpty { "Unbenannt" }, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = if (r.title.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val shown = chips.filter { hasValue(q.value(it, r)) }
                    if (shown.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            shown.forEach { p -> ValueChips(p, q.value(p, r)) }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        if (rows.isEmpty()) Text("Keine Einträge", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    }
}

// ---------------------------------------------------------------- Board

@Composable
private fun BoardView(q: DbQuery, view: DbView, rows: List<DbRow>, openPage: (Int) -> Unit, add: (Map<String, JsonElement>) -> Unit) {
    val group = q.prop(view.groupBy) ?: q.props.firstOrNull { it.type == "select" }
    if (group == null) {
        Text("Board braucht eine Auswahl-Eigenschaft", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val cols = group.options.map { Triple<String?, String, String?>(it.id, it.name, it.color) } + Triple(null, "Ohne ${group.name}", null)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        cols.forEach { (id, name, color) ->
            val items = rows.filter { ((it.props[group.id] as? String)?.ifEmpty { null }) == id }
            Column(
                Modifier.width(240.dp).clip(RoundedCornerShape(10.dp)).background(optColor(color).copy(alpha = 0.08f)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(name, color)
                    Text("${items.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                }
                items.forEach { r -> Card(q, view, r, cover = false, repo = null) { openPage(r.id) } }
                Text(
                    "+ Neu", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { add(if (id != null) mapOf(group.id to JsonPrimitive(id)) else emptyMap()) }.padding(4.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Galerie

@Composable
private fun GalleryView(q: DbQuery, view: DbView, rows: List<DbRow>, openPage: (Int) -> Unit, repo: SagaRepository) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minW = when (view.card) {
            "s" -> 130.dp
            "l" -> 240.dp
            else -> 160.dp
        }
        val n = (maxWidth / minW).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.chunked(n).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    line.forEach { r -> Box(Modifier.weight(1f)) { Card(q, view, r, cover = true, repo = repo) { openPage(r.id) } } }
                    repeat(n - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (rows.isEmpty()) Text("Keine Einträge", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Card(q: DbQuery, view: DbView, r: DbRow, cover: Boolean, repo: SagaRepository?, onClick: () -> Unit) {
    val color = q.colorName(r, view)?.let { optColor(it) }
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (color != null) Modifier.background(color.copy(alpha = 0.16f)) else Modifier)
            .border(1.dp, color?.copy(alpha = 0.6f) ?: MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick),
    ) {
        if (cover && repo != null && r.cover != null) {
            val c = r.cover
            AsyncImage(
                model = repo.absoluteUrl(if (c.startsWith("/files/") && !c.contains("?")) "$c?w=640" else c), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(110.dp),
            )
        }
        Column(Modifier.padding(if (cover) 10.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!r.icon.isNullOrEmpty() && repo != null) Box(Modifier.padding(end = 6.dp)) { PageIcon(r.icon, repo, size = 18.dp) }
                else if (!r.icon.isNullOrEmpty() && !r.icon.startsWith("/")) Text(r.icon, Modifier.padding(end = 6.dp))
                Text(r.title.ifEmpty { "Unbenannt" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val ps = q.props.filter { it.id !in view.hidden && it.type in setOf("select", "multi_select", "date", "checkbox", "number", "calc") }
                .take(4).filter { hasValue(q.value(it, r)) }
            if (ps.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ps.forEach { p ->
                        if (p.type == "checkbox") Text("☑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else ValueChips(p, q.value(p, r))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Kalender (Monat) nach Datums-Eigenschaft

private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.GERMAN)

@Composable
private fun CalendarView(q: DbQuery, view: DbView, rows: List<DbRow>, openPage: (Int) -> Unit) {
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val ym = YearMonth.parse(month)
    val dp = q.prop(view.dateBy) ?: q.props.firstOrNull { it.type == "date" }
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = ym.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Voriger Monat") }
            Text(ym.atDay(1).format(monthFormat), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { month = ym.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nächster Monat") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { month = YearMonth.now().toString() }) { Text("Heute") }
        }
        if (dp == null) {
            Text("Kalender braucht eine Datums-Eigenschaft", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val lead = ym.atDay(1).dayOfWeek.value - 1                 // Montag zuerst
        val cells = lead + ym.lengthOfMonth()
        Row {
            listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        (0 until (cells + 6) / 7).forEach { w ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (0 until 7).forEach { c ->
                    val i = w * 7 + c
                    if (i < lead || i >= cells) Spacer(Modifier.weight(1f).height(72.dp))
                    else {
                        val d = ym.atDay(i - lead + 1)
                        val ds = d.toString()
                        val items = rows.filter { ((q.value(dp, it) as? String) ?: "").startsWith(ds) }
                        Column(
                            Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            items.take(2).forEach { r ->
                                Text(
                                    r.title.ifEmpty { "Unbenannt" }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                        .clickable { openPage(r.id) }.padding(horizontal = 2.dp),
                                )
                            }
                            if (items.size > 2) Text("+${items.size - 2}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Werte

/** Leerer Wert? (dann keinen Platz fuer einen Chip reservieren) */
private fun hasValue(v: Any?): Boolean = when (v) {
    null -> false
    is String -> v.isNotEmpty()
    is List<*> -> v.isNotEmpty()
    is Boolean -> v
    else -> true
}

/** Kompakte Anzeige eines Werts (Chip/Datum/Zahl); Mehrfachauswahl = mehrere Chips */
@Composable
private fun ValueChips(p: DbProp, v: Any?) {
    when (p.type) {
        "select" -> p.option(v as? String)?.let { Chip(it.name, it.color) }
        "multi_select" -> (v as? List<*>)?.forEach { id -> p.option(id as? String)?.let { Chip(it.name, it.color) } }
        "date", "created", "edited" -> (v as? String)?.takeIf { it.isNotEmpty() }?.let {
            Text(dayText(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
        }
        "number", "calc" -> (v as? Double)?.let {
            Text(numText(it, p.fmt, p.show), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
fun Chip(text: String, color: String?) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(optColor(color).copy(alpha = 0.28f)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Farbnamen der Auswahl-Optionen (wie im Web) */
fun optColor(name: String?): Color = when (name) {
    "red" -> Color(0xFFE5484D)
    "orange" -> Color(0xFFF76B15)
    "yellow" -> Color(0xFFFFC53D)
    "green" -> Color(0xFF30A46C)
    "blue" -> Color(0xFF0090FF)
    "purple" -> Color(0xFF8E4EC6)
    "pink" -> Color(0xFFD6409F)
    "brown" -> Color(0xFFAD7F58)
    else -> Color(0xFF8B8D98)
}

private fun dayText(s: String): String {
    val d = try {
        LocalDate.parse(s.take(10))
    } catch (_: Exception) {
        return s
    }
    return d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "d. MMM" else "d. MMM yyyy", Locale.GERMAN))
}

private fun numText(n: Double, fmt: String?, show: String?): String {
    val de = Locale.GERMAN
    return when (fmt) {
        "percent" -> NumberFormat.getNumberInstance(de).apply { maximumFractionDigits = if (show == "ring" || show == "bar") 0 else 2 }.format(n) + " %"
        "euro" -> NumberFormat.getCurrencyInstance(Locale.GERMANY).format(n)
        else -> NumberFormat.getNumberInstance(de).apply { maximumFractionDigits = 4 }.format(n)
    }
}
