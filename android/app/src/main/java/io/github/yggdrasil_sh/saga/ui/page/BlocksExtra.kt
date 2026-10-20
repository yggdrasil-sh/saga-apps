package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.BlockOps
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.blocks.SlashLogic
import io.github.yggdrasil_sh.saga.data.CalEvent
import io.github.yggdrasil_sh.saga.ui.events.EventRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// Saga 2.4 (Android-Editor): "/"-Menue, Tabelle, Spalten, Bild mit Unterschrift/Groesse, Datei, Aufgaben, Kalender

/** Angezeigter Name eines Eintrags im "/"- und "+"-Menue */
@Composable
fun slashLabel(it: SlashLogic.Item): String = when (it.id) {
    "paragraph" -> stringResource(R.string.block_text)
    "header" -> stringResource(R.string.block_heading, it.level)
    "checklist" -> stringResource(R.string.block_checklist)
    "unordered" -> stringResource(R.string.block_bulleted)
    "ordered" -> stringResource(R.string.block_numbered)
    "quote" -> stringResource(R.string.block_quote)
    "callout" -> stringResource(R.string.block_callout)
    "toggle" -> stringResource(R.string.block_toggle)
    "code" -> stringResource(R.string.block_code)
    "delimiter" -> stringResource(R.string.block_divider)
    "image" -> stringResource(R.string.ed24_image)
    "immich_image" -> stringResource(R.string.ed24_immich)
    "table" -> stringResource(R.string.blocktype_table)
    "columns" -> stringResource(R.string.ed24_columns_n, it.level)
    "file" -> stringResource(R.string.ed24_file)
    "embed" -> stringResource(R.string.ed24_embed)
    "link" -> stringResource(R.string.ed24_link)
    "page" -> stringResource(R.string.ed24_subpage)
    "db_new" -> stringResource(R.string.ed24_db_new)
    "db_link" -> stringResource(R.string.ed24_db_link)
    "tasks" -> stringResource(R.string.blocktype_tasks)
    "calendar" -> stringResource(R.string.blocktype_calendar)
    "nextcloud" -> stringResource(R.string.nc_file_insert)
    else -> it.id
}

/** Gefilterte Eintraege (in Compose: Namen in der App-Sprache) */
@Composable
fun slashItems(query: String, needs: Set<SlashLogic.Need>): List<SlashLogic.Item> {
    val labels = SlashLogic.ITEMS.associate { it.key to slashLabel(it) }
    return remember(query, needs, labels) { SlashLogic.filter(query, needs) { labels[it.key].orEmpty() } }
}

/** "/"-Menue unter dem Absatz: tippen waehlt, Return nimmt den ersten Eintrag. Nimmt der Tastatur nie den Fokus */
@Composable
fun SlashMenuCard(query: String, needs: Set<SlashLogic.Need>, onPick: (SlashLogic.Item) -> Unit) {
    val items = slashItems(query, needs)
    Surface(
        tonalElevation = 4.dp, shadowElevation = 4.dp, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(top = 4.dp).widthIn(max = 320.dp),
    ) {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            if (items.isEmpty()) {
                Text(stringResource(R.string.ed24_slash_none, query), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            }
            items.forEachIndexed { i, it ->
                Text(
                    slashLabel(it), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal),
                    modifier = Modifier.fillMaxWidth().clickable { onPick(it) }.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp)
                        .semantics { role = Role.Button },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Tabelle

/** Zelle aus dem Schluessel des aktiven Feldes ("b:id/t2.1" -> 2 to 1) */
internal fun tableCellOf(activeKey: String?, blockKey: String): Pair<Int, Int>? {
    val k = activeKey ?: return null
    if (!k.startsWith("$blockKey/t")) return null
    val rc = k.removePrefix("$blockKey/t").split('.')
    return rc.getOrNull(0)?.toIntOrNull()?.let { r -> rc.getOrNull(1)?.toIntOrNull()?.let { c -> r to c } }
}

@Composable
fun TableBlock(block: JsonObject, path: JsonPath, key: String, env: BlockEnv) {
    val ed = env.editor
    val rows = BlockOps.cells(block)
    val ncols = BlockOps.columnCount(block).coerceAtLeast(1)
    val heads = BlockOps.headings(block)
    val cur = tableCellOf(ed.active?.key, key)
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(Modifier.border(1.dp, line, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))) {
                rows.forEachIndexed { r, row ->
                    Row {
                        for (c in 0 until ncols) {
                            val html = row.getOrNull(c) ?: ""
                            val head = heads && r == 0
                            val style = MaterialTheme.typography.bodyMedium.let { if (head) it.copy(fontWeight = FontWeight.Bold) else it }
                            Box(
                                Modifier.width(150.dp).border(0.5.dp, line)
                                    .then(if (head) Modifier.background(MaterialTheme.colorScheme.surfaceContainer) else Modifier)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                RichField(
                                    html, "$key/t$r.$c", ed, path, style,
                                    { h -> ed.updateBlock(path) { BlockOps.setCell(it, r, c, h) } },
                                    onLink = env.onLink,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (ed.editable) {
            var more by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { ed.updateBlock(path) { BlockOps.insertRow(it, (cur?.first ?: (rows.size - 1)) + 1) } }) { Text(stringResource(R.string.ed24_row_add)) }
                TextButton(onClick = { ed.updateBlock(path) { BlockOps.insertColumn(it, (cur?.second ?: (ncols - 1)) + 1) } }) { Text(stringResource(R.string.ed24_col_add)) }
                Box {
                    TextButton(onClick = { more = true }) { Text("⋯") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(if (heads) R.string.ed24_headings_off else R.string.ed24_headings_on)) }, onClick = {
                            more = false
                            ed.updateBlock(path) { BlockOps.setHeadings(it, !heads) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ed24_row_above)) }, onClick = {
                            more = false
                            ed.updateBlock(path) { BlockOps.insertRow(it, cur?.first ?: 0) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ed24_col_left)) }, onClick = {
                            more = false
                            ed.updateBlock(path) { BlockOps.insertColumn(it, cur?.second ?: 0) }
                        })
                        if (rows.size > 1) DropdownMenuItem(text = { Text(stringResource(R.string.ed24_row_remove, (cur?.first ?: (rows.size - 1)) + 1)) }, onClick = {
                            more = false
                            ed.updateBlock(path) { BlockOps.removeRow(it, cur?.first ?: (rows.size - 1)) }
                        })
                        if (ncols > 1) DropdownMenuItem(text = { Text(stringResource(R.string.ed24_col_remove, (cur?.second ?: (ncols - 1)) + 1)) }, onClick = {
                            more = false
                            ed.updateBlock(path) { BlockOps.removeColumn(it, cur?.second ?: (ncols - 1)) }
                        })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Spalten

/** Spalten: nebeneinander, wenn Platz ist, sonst untereinander; bearbeiten: Anzahl 2/3 und "+ Block" je Spalte */
@Composable
fun ColumnsBlockEdit(block: JsonObject, path: JsonPath, env: BlockEnv) {
    val ed = env.editor
    val cols = BlockOps.columnBlocksCount(block)
    if (cols == 0) return
    val edit = ed.editable
    @Composable
    fun col(ci: Int) {
        val inner = path + listOf("data", "cols", ci, "blocks")
        Column {
            if (edit) Text(stringResource(R.string.ed24_column_n, ci + 1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BlockColumn(inner, env)
            if (edit) {
                TextButton(onClick = {
                    val n = ((BlockDoc.data(ed.block(path))["cols"] as? JsonArray)?.getOrNull(ci) as? JsonObject)?.get("blocks").let { (it as? JsonArray)?.size ?: 0 }
                    ed.insertInto(inner, n, BlockDoc.newBlock("paragraph"))
                }) { Text(stringResource(R.string.ed24_add_block)) }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (edit) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.blocktype_columns), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (if (cols > 3) listOf(2, 3, 4) else listOf(2, 3)).forEach { n ->
                    FilterChip(selected = cols == n, onClick = { ed.updateBlock(path) { BlockOps.setColumnCount(it, n) } }, label = { Text("$n") })
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 600.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for (ci in 0 until cols) Box(Modifier.weight(1f)) { col(ci) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (ci in 0 until cols) col(ci)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Bild

@Composable
fun ImageBlockEdit(block: JsonObject, path: JsonPath, key: String, env: BlockEnv) {
    val ed = env.editor
    val d = BlockDoc.data(block)
    val file = d["file"] as? JsonObject
    val url = BlockDoc.str(file, "url").ifEmpty { BlockDoc.str(d, "url") }
    val selected = ed.active?.key == key || ed.active?.key == "$key/cap"
    val bg = BlockDoc.bool(d, "withBackground")
    val border = BlockDoc.bool(d, "withBorder")
    val wide = BlockDoc.bool(d, "stretched")
    Column(Modifier.fillMaxWidth()) {
        if (url.isNotEmpty()) {
            val sized = if ((url.startsWith("/files/") || url.startsWith("/api/fed/files/")) && !url.contains("?")) "$url?w=2000" else url
            Box(
                Modifier.fillMaxWidth().then(if (bg) Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = env.repo.absoluteUrl(sized), contentDescription = InlineHtml.plain(BlockDoc.str(d, "caption")).ifEmpty { null },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(if (bg && !wide) 0.6f else 1f).heightIn(min = 60.dp, max = 900.dp).clip(RoundedCornerShape(8.dp))
                        .then(if (border) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)) else Modifier),
                )
            }
        }
        val cap = BlockDoc.str(d, "caption")
        if (ed.editable && (selected || cap.isNotEmpty())) {
            RichField(
                cap, "$key/cap", ed, path, MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                { h -> ed.setString(path + listOf("data", "caption"), h) },
                modifier = Modifier.padding(top = 4.dp), placeholder = stringResource(R.string.ed24_caption), onLink = env.onLink,
            )
        } else if (cap.isNotEmpty()) {
            RichTextView(cap, MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), env.onLink, Modifier.padding(top = 4.dp))
        }
        if (ed.editable && selected) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(wide, { ed.updateBlock(path) { BlockOps.setImageFlag(it, "stretched", !wide) } }, { Text(stringResource(R.string.ed24_img_wide)) })
                FilterChip(bg, { ed.updateBlock(path) { BlockOps.setImageFlag(it, "withBackground", !bg) } }, { Text(stringResource(R.string.ed24_img_small)) })
                FilterChip(border, { ed.updateBlock(path) { BlockOps.setImageFlag(it, "withBorder", !border) } }, { Text(stringResource(R.string.ed24_img_border)) })
            }
        }
    }
}

// ---------------------------------------------------------------- Datei, Aufgaben, Kalender

@Composable
fun AttachesBlock(d: JsonObject, env: BlockEnv) {
    val f = d["file"] as? JsonObject
    val name = BlockDoc.str(d, "title").ifEmpty { BlockDoc.str(f, "name") }.ifEmpty { stringResource(R.string.blocktype_attachment) }
    val u = BlockDoc.str(f, "url")
    val size = BlockDoc.int(f, "size")
    // Bearbeiten: Antippen waehlt den Block (Verschieben/Loeschen), Oeffnen ueber den Knopf
    val edit = env.editor.editable
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(enabled = u.isNotEmpty() && !edit) { env.onLink(u) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📎", modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(InlineHtml.decodeEntities(name), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (size != null && size > 0) Text(humanSize(size.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (edit && u.isNotEmpty()) TextButton(onClick = { env.onLink(u) }) { Text(stringResource(R.string.action_open)) }
    }
}

internal fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${(b + 512) / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0)
}

/** Aufgaben-Block (eigene Seite): offene Aufgaben, abhaken wie in der Aufgabenliste */
@Composable
fun TasksBlock(env: BlockEnv) {
    val tasks by env.repo.tasks.collectAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(8.dp),
    ) {
        Text("✓ " + stringResource(R.string.blocktype_tasks), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
        if (tasks.isEmpty()) Text(stringResource(R.string.ed24_tasks_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        tasks.take(30).forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = false, onCheckedChange = { env.repo.toggleTask(t, true) })
                Text(t.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Kalender-Block (eigene Seite): Termine der naechsten Tage (data.days, Standard 14) als Liste */
@Composable
fun CalendarBlock(d: JsonObject, env: BlockEnv) {
    val days = (BlockDoc.int(d, "days") ?: 14).coerceIn(1, 62)
    var events by remember(days) { mutableStateOf<List<CalEvent>?>(null) }
    LaunchedEffect(days) { events = env.repo.events(days) ?: emptyList() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(vertical = 8.dp),
    ) {
        Text("🗓 " + stringResource(R.string.blocktype_calendar), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        val l = events
        when {
            l == null -> Text("…", modifier = Modifier.padding(12.dp))
            l.isEmpty() -> Text(stringResource(R.string.events_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            else -> l.take(20).forEach { EventRow(it) }
        }
    }
}
