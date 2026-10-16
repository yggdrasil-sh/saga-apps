package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.DbView
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.blocks.JsonTree
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Gemeinsames fuer alle Bloecke einer Seite */
class BlockEnv(val editor: PageEditor, val repo: SagaRepository, val onLink: (String) -> Unit) {
    /** Seite in der App oeffnen (wie ein Link auf /notes#id) */
    val openPage: (Int) -> Unit = { id -> onLink("/notes#$id") }
}

@Composable
fun headerStyle(level: Int): TextStyle {
    val t = MaterialTheme.typography
    return when {
        level <= 1 -> t.headlineMedium.copy(fontWeight = FontWeight.Bold)
        level == 2 -> t.headlineSmall.copy(fontWeight = FontWeight.Bold)
        level == 3 -> t.titleLarge.copy(fontWeight = FontWeight.Bold)
        else -> t.titleMedium.copy(fontWeight = FontWeight.Bold)
    }
}

/** Bloecke eines Arrays (Toggle-Inhalt, Spalte) untereinander */
@Composable
fun BlockColumn(arrayPath: JsonPath, env: BlockEnv) {
    val arr = JsonTree.get(env.editor.doc, arrayPath) as? JsonArray ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        arr.forEachIndexed { i, el ->
            val b = el as? JsonObject ?: return@forEachIndexed
            key(BlockDoc.id(b) ?: "i$i") { BlockView(b, arrayPath + i, env) }
        }
    }
}

@Composable
fun BlockView(block: JsonObject, path: JsonPath, env: BlockEnv) {
    val ed = env.editor
    val d = BlockDoc.data(block)
    val key = ed.blockKey(block, path)
    val body = MaterialTheme.typography.bodyLarge

    // Return in einem Textblock: neuer Absatz darunter mit dem Rest
    val splitToParagraph: (io.github.yggdrasil_sh.saga.blocks.RichText, io.github.yggdrasil_sh.saga.blocks.RichText) -> Unit = { _, right ->
        ed.insertBlockAfter(path, BlockDoc.newBlock("paragraph", InlineHtml.toHtml(right)))
    }
    // Ruecktaste am Anfang eines leeren Blocks: Block entfernen, im vorigen weiterschreiben
    val removeIfEmpty: (Boolean) -> Unit = { empty -> if (empty) ed.deleteBlock(path, focusPrevious = true) }

    when (val type = BlockDoc.type(block)) {
        "paragraph" -> RichField(
            BlockDoc.str(d, "text"), key, ed, path, body, { ed.setString(path + listOf("data", "text"), it) },
            placeholder = "Tippe etwas …", onEnter = splitToParagraph, onBackspaceAtStart = removeIfEmpty, onLink = env.onLink,
        )
        "header" -> {
            val lvl = BlockDoc.int(d, "level") ?: 2
            RichField(
                BlockDoc.str(d, "text"), key, ed, path, headerStyle(lvl), { ed.setString(path + listOf("data", "text"), it) },
                modifier = Modifier.padding(top = 6.dp), placeholder = "Überschrift $lvl",
                onEnter = splitToParagraph, onBackspaceAtStart = removeIfEmpty, onLink = env.onLink,
            )
        }
        "list" -> ListItems(block, path, d["items"] as? JsonArray ?: JsonArray(emptyList()), BlockDoc.str(d, "style").ifEmpty { "unordered" }, path + listOf("data", "items"), emptyList(), env)
        "quote" -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
            RichField(
                BlockDoc.str(d, "text"), key, ed, path, body.copy(fontStyle = FontStyle.Italic), { ed.setString(path + listOf("data", "text"), it) },
                modifier = Modifier.padding(start = 12.dp), placeholder = "Zitat",
                onEnter = splitToParagraph, onBackspaceAtStart = removeIfEmpty, onLink = env.onLink,
            )
        }
        "callout" -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
        ) {
            Text(BlockDoc.str(d, "emoji").ifEmpty { "💡" }, modifier = Modifier.padding(end = 10.dp))
            RichField(
                BlockDoc.str(d, "text"), key, ed, path, body, { ed.setString(path + listOf("data", "text"), it) },
                placeholder = "Hinweis", onEnter = splitToParagraph, onLink = env.onLink,
            )
        }
        "code" -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp),
        ) {
            val lang = BlockDoc.str(d, "language")
            RichField(
                BlockDoc.str(d, "code"), key, ed, path,
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), { ed.setString(path + listOf("data", "code"), it) },
                modifier = Modifier.padding(top = if (lang.isEmpty()) 0.dp else 14.dp), placeholder = "Code", raw = true,
            )
            if (lang.isNotEmpty()) {
                Text(lang, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        "delimiter" -> Selectable(key, path, env) { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
        "image" -> Selectable(key, path, env) { ImageBlock(d, env) }
        "toggle" -> ToggleBlock(block, path, key, env)
        "columns" -> ColumnsBlock(d, path, env)
        "database" -> {
            val db = BlockDoc.int(d, "db")
            if (db != null) Selectable(key, path, env) {
                val views = (d["views"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::DbView) }
                DatabaseView(db, env.repo, env.openPage, viewId = BlockDoc.str(d, "view").ifEmpty { null }, localViews = views, embedded = true)
            }
        }
        "tasks" -> Selectable(key, path, env) { TasksBlock(env.repo) }
        "calendar" -> Selectable(key, path, env) { CalendarBlock(env.repo) }
        "buttons" -> Selectable(key, path, env) { ButtonsBlock(d, env.repo, env.openPage) }
        "embed" -> Selectable(key, path, env) { EmbedCard(d, env.onLink) }
        "attaches" -> Selectable(key, path, env) { AttachCard(d, env.onLink) }
        "table" -> Selectable(key, path, env) { TableView(d) }
        else -> Selectable(key, path, env) { Unsupported(type) }
    }
}

/** Antippen waehlt einen Block ohne Text aus (fuer Verschieben/Loeschen in der Werkzeugleiste) */
@Composable
private fun Selectable(key: String, path: JsonPath, env: BlockEnv, content: @Composable () -> Unit) {
    val ed = env.editor
    val focus: FocusManager = LocalFocusManager.current
    val selected = ed.active?.key == key
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
            .then(if (ed.editable) Modifier.clickable {
                focus.clearFocus()
                ed.active = if (selected) null else ActiveField(key, { path })
            } else Modifier),
    ) { content() }
}

@Composable
private fun ImageBlock(d: JsonObject, env: BlockEnv) {
    val file = d["file"] as? JsonObject
    val url = BlockDoc.str(file, "url").ifEmpty { BlockDoc.str(d, "url") }
    Column(Modifier.fillMaxWidth()) {
        if (url.isNotEmpty()) {
            val sized = if (url.startsWith("/files/") && !url.contains("?")) "$url?w=2000" else url
            AsyncImage(
                model = env.repo.absoluteUrl(sized), contentDescription = InlineHtml.plain(BlockDoc.str(d, "caption")).ifEmpty { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 900.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        val cap = BlockDoc.str(d, "caption")
        if (cap.isNotEmpty()) {
            RichTextView(cap, MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), env.onLink, Modifier.padding(top = 4.dp))
        }
    }
}

/** Platzhalter fuer Bloecke, die die App noch nicht kann - der Block bleibt unveraendert gespeichert */
@Composable
private fun Unsupported(type: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
    ) {
        Text(
            "${BlockDoc.typeLabel(type)} – wird hier noch nicht unterstützt",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Liste (ungeordnet, nummeriert, Checkliste) mit verschachtelten Punkten */
@Composable
private fun ListItems(block: JsonObject, blockPath: JsonPath, items: JsonArray, style: String, arrayPath: JsonPath, indices: List<Int>, env: BlockEnv) {
    val ed = env.editor
    val prefix = ed.blockKey(block, blockPath)
    Column(Modifier.padding(start = if (indices.isEmpty()) 0.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, raw ->
            val itemPath = arrayPath + i
            val obj = raw as? JsonObject
            val content = if (obj != null) BlockDoc.str(obj, "content") else (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            val contentPath = if (obj != null) itemPath + "content" else itemPath
            val checked = BlockDoc.bool(obj?.get("meta") as? JsonObject, "checked")
            val myIdx = indices + i
            val fieldKey = prefix + "/" + myIdx.joinToString("/")
            Row(verticalAlignment = Alignment.Top) {
                when (style) {
                    "checklist" -> Checkbox(
                        checked = checked, onCheckedChange = { ed.toggleChecked(itemPath) }, enabled = ed.editable && obj != null,
                        modifier = Modifier.size(32.dp).padding(end = 4.dp),
                    )
                    "ordered" -> Text("${i + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 24.dp).padding(end = 6.dp))
                    else -> Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 16.dp).padding(end = 6.dp))
                }
                Column(Modifier.weight(1f)) {
                    key(fieldKey) {
                        RichField(
                            content, fieldKey, ed, blockPath,
                            MaterialTheme.typography.bodyLarge.let {
                                if (checked) it.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) else it
                            },
                            { ed.setString(contentPath, it) },
                            itemPath = itemPath,
                            placeholder = "Listenpunkt",
                            onEnter = { left, right ->
                                if (left.text.isEmpty() && right.text.isEmpty() && obj?.get("items").let { (it as? JsonArray).isNullOrEmpty() } && indices.isEmpty()) {
                                    ed.endList(blockPath, itemPath)
                                } else {
                                    val newKey = prefix + "/" + (indices + (i + 1)).joinToString("/")
                                    ed.insertItemAfter(itemPath, InlineHtml.toHtml(right), style == "checklist", newKey)
                                }
                            },
                            onBackspaceAtStart = { empty -> if (empty) ed.removeItem(blockPath, itemPath, focusPrevious = true) },
                            onLink = env.onLink,
                        )
                    }
                    val sub = obj?.get("items") as? JsonArray
                    if (sub != null && sub.isNotEmpty()) {
                        ListItems(block, blockPath, sub, style, itemPath + "items", myIdx, env)
                    }
                }
            }
        }
    }
}

/** Toggle: Titel + aufklappbarer Inhalt (Auf/Zu wird nur lokal gemerkt, nicht gespeichert) */
@Composable
private fun ToggleBlock(block: JsonObject, path: JsonPath, key: String, env: BlockEnv) {
    val ed = env.editor
    val d = BlockDoc.data(block)
    var open by rememberSaveable(key) { mutableStateOf(BlockDoc.bool(d, "open")) }
    val inner = path + listOf("data", "blocks")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { open = !open }, modifier = Modifier.size(32.dp)) {
                Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, if (open) "Zuklappen" else "Aufklappen")
            }
            RichField(
                BlockDoc.str(d, "title"), key, ed, path, MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                { ed.setString(path + listOf("data", "title"), it) },
                placeholder = "Toggle",
                onEnter = { _, right ->
                    open = true
                    ed.insertInto(inner, 0, BlockDoc.newBlock("paragraph", InlineHtml.toHtml(right)))
                },
                onLink = env.onLink,
            )
        }
        if (open) {
            val arr = JsonTree.get(ed.doc, inner) as? JsonArray
            Box(Modifier.padding(start = 32.dp, top = 4.dp)) {
                if (arr.isNullOrEmpty()) {
                    if (ed.editable) TextButton(onClick = { ed.insertInto(inner, 0, BlockDoc.newBlock("paragraph")) }) {
                        Text("Leerer Toggle – tippen zum Schreiben")
                    } else Text("Leer", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    BlockColumn(inner, env)
                }
            }
        }
    }
}

/** Spalten: nebeneinander, wenn Platz ist, sonst untereinander (wie iOS auf dem iPhone) */
@Composable
private fun ColumnsBlock(d: JsonObject, path: JsonPath, env: BlockEnv) {
    val cols = (d["cols"] as? JsonArray)?.size ?: 0
    if (cols == 0) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (ci in 0 until cols) {
                    Box(Modifier.weight(1f)) { BlockColumn(path + listOf("data", "cols", ci, "blocks"), env) }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (ci in 0 until cols) BlockColumn(path + listOf("data", "cols", ci, "blocks"), env)
            }
        }
    }
}
