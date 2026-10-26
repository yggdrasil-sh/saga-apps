package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
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
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.blocks.JsonTree
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Gemeinsames fuer alle Bloecke einer Seite */
class BlockEnv(val editor: PageEditor, val repo: SagaRepository, val onLink: (String) -> Unit) {
    /** 2.3: Status der Nextcloud-Bloecke dieser Seite (einmal geholt, fuer alle Karten) */
    val nc: NcPageCache by lazy { NcPageCache(editor.id, repo) }
    /** 2.5: Werte der Seiten-Widgets (GET /api/notes/{id}/widgets), einmal fuer alle Karten */
    val widgets: PageWidgetCache by lazy { PageWidgetCache(editor.id, repo) }
    /** 2.4: "/"-Menue - Auswahl auf dem Absatz (PageScreen setzt es) und erfuellte Voraussetzungen der Eintraege */
    var slash: ((JsonPath, io.github.yggdrasil_sh.saga.blocks.SlashLogic.Item) -> Unit)? = null
    var slashNeeds by mutableStateOf<Set<io.github.yggdrasil_sh.saga.blocks.SlashLogic.Need>>(emptySet())
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
            key(BlockDoc.id(b) ?: "i$i") {
                val path = remember(arrayPath, i) { arrayPath + i }
                BlockView(b, path, env)
            }
        }
    }
}

/** G3 (2.5): Block-Farbe aus tunes.hkstyle - Textfarbe ueber onSurface des Blocks, Hintergrund als Flaeche */
@Composable
fun BlockView(block: JsonObject, path: JsonPath, env: BlockEnv) {
    val st = io.github.yggdrasil_sh.saga.blocks.Gestalten.style(block)
    if (st == null) {
        BlockViewInner(block, path, env)
        return
    }
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val g = io.github.yggdrasil_sh.saga.blocks.Gestalten
    if (st.bg) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(g.bgArgb(st.color, dark))).padding(horizontal = 8.dp, vertical = 4.dp)) {
            BlockViewInner(block, path, env)
        }
    } else {
        MaterialTheme(colorScheme = cs.copy(onSurface = Color(g.textArgb(st.color, dark))), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            BlockViewInner(block, path, env)
        }
    }
}

@Composable
private fun BlockViewInner(block: JsonObject, path: JsonPath, env: BlockEnv) {
    val ed = env.editor
    val d = BlockDoc.data(block)
    val key = ed.blockKey(block, path)
    val body = MaterialTheme.typography.bodyLarge

    // Return in einem Textblock: neuer Absatz darunter mit dem Rest
    val splitToParagraph: (io.github.yggdrasil_sh.saga.blocks.RichText, io.github.yggdrasil_sh.saga.blocks.RichText) -> Unit = { _, right ->
        ed.insertBlockAfter(path, BlockDoc.newBlock("paragraph", InlineHtml.toHtml(right)))
    }
    // Ruecktaste am Blockanfang: leer -> Block entfernen, im vorigen weiterschreiben; sonst mit dem vorigen zusammenfuehren
    val removeIfEmpty: (Boolean) -> Unit = { empty -> if (empty) ed.deleteBlock(path, focusPrevious = true) else ed.mergeWithPrevious(path) }

    when (val type = BlockDoc.type(block)) {
        "paragraph" -> {
            // 2.4: "/" am Absatzanfang oeffnet das Block-Menue (wie Web/iOS); Return nimmt den ersten Treffer
            val text = BlockDoc.str(d, "text")
            val pick = env.slash
            val slashQ = if (pick != null && ed.editable && text.startsWith("/")) io.github.yggdrasil_sh.saga.blocks.SlashLogic.query(InlineHtml.plain(text)) else null
            val slashLabels = if (slashQ != null) slashItems(slashQ, env.slashNeeds) else emptyList()
            Column {
                RichField(
                    text, key, ed, path, body, { ed.setString(path + listOf("data", "text"), it) },
                    placeholder = stringResource(R.string.placeholder_paragraph),
                    onEnter = { left, right ->
                        val first = slashLabels.firstOrNull()
                        if (pick != null && first != null && io.github.yggdrasil_sh.saga.blocks.SlashLogic.query(left.text) != null && right.text.isEmpty()) pick(path, first)
                        else splitToParagraph(left, right)
                    },
                    onBackspaceAtStart = removeIfEmpty, onLink = env.onLink,
                )
                if (slashQ != null && pick != null && ed.active?.key == key) {
                    SlashMenuCard(slashQ, env.slashNeeds) { pick(path, it) }
                }
            }
        }
        "header" -> {
            val lvl = BlockDoc.int(d, "level") ?: 2
            RichField(
                BlockDoc.str(d, "text"), key, ed, path, headerStyle(lvl), { ed.setString(path + listOf("data", "text"), it) },
                modifier = Modifier.padding(top = 6.dp), placeholder = stringResource(R.string.block_heading, lvl),
                onEnter = splitToParagraph, onBackspaceAtStart = removeIfEmpty, onLink = env.onLink,
            )
        }
        "list" -> ListItems(block, path, d["items"] as? JsonArray ?: JsonArray(emptyList()), BlockDoc.str(d, "style").ifEmpty { "unordered" }, path + listOf("data", "items"), emptyList(), env)
        "quote" -> Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
            // Saga-Gold als Zitatbalken (Zierfarbe, kein Text)
            Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.gold))
            Column(Modifier.padding(start = 12.dp)) {
                RichField(
                    BlockDoc.str(d, "text"), key, ed, path, body.copy(fontStyle = FontStyle.Italic), { ed.setString(path + listOf("data", "text"), it) },
                    placeholder = stringResource(R.string.block_quote),
                    onEnter = splitToParagraph, onBackspaceAtStart = removeIfEmpty, onLink = env.onLink,
                )
                // Unterschrift (Autor/Quelle) wie im Web: "— Autor"
                val caption = InlineHtml.plain(BlockDoc.str(d, "caption")).trim()
                if (caption.isNotEmpty()) {
                    Text("— $caption", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        "callout" -> Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
        ) {
            Text(BlockDoc.str(d, "emoji").ifEmpty { "💡" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 12.dp))
            RichField(
                BlockDoc.str(d, "text"), key, ed, path, body, { ed.setString(path + listOf("data", "text"), it) },
                placeholder = stringResource(R.string.block_callout), onEnter = splitToParagraph, onLink = env.onLink,
            )
        }
        "code" -> Box(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(14.dp),
        ) {
            val lang = BlockDoc.str(d, "language")
            RichField(
                BlockDoc.str(d, "code"), key, ed, path,
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), { ed.setString(path + listOf("data", "code"), it) },
                modifier = Modifier.padding(top = if (lang.isEmpty()) 0.dp else 14.dp), placeholder = stringResource(R.string.block_code), raw = true,
            )
            if (lang.isNotEmpty()) {
                Text(lang, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        "delimiter" -> Selectable(key, path, env) { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
        "image" -> Selectable(key, path, env) { ImageBlockEdit(block, path, key, env) }
        "toggle" -> ToggleBlock(block, path, key, env)
        "columns" -> ColumnsBlockEdit(block, path, env)
        // 2.4: Tabelle bearbeitbar, Datei als Karte; Aufgaben/Kalender auf eigenen Seiten mit Inhalt
        "table" -> Selectable(key, path, env) { TableBlock(block, path, key, env) }
        "attaches" -> Selectable(key, path, env) { AttachesBlock(d, env) }
        "tasks" -> Selectable(key, path, env) { if (ed.caps.ownerOnlyBlock(type)) OwnerOnly(type) else TasksBlock(env) }
        "calendar" -> Selectable(key, path, env) { if (ed.caps.ownerOnlyBlock(type)) OwnerOnly(type) else CalendarBlock(d, env) }
        // Notion-Import: eingebettete Datenbank, Schaltflaechen, Einbettungen - lesbar statt Platzhalter
        "database" -> Selectable(key, path, env) { DatabaseBlock(d, env) }
        "buttons" -> Selectable(key, path, env) { ButtonsBlock(d, env) }
        "embed" -> Selectable(key, path, env) { EmbedBlock(d, env) }
        // 2.3: Datei/Ordner aus einer Nextcloud - nur anzeigen, die Daten bleiben beim Speichern unveraendert
        "nextcloud" -> Selectable(key, path, env) { NextcloudCard(d, env) }
        // 2.5: Widgets in Seiten - nur anzeigen (Einstellungen im Web), Block bleibt beim Speichern unveraendert
        "widget" -> Selectable(key, path, env) { PageWidgetBlock(block, env) }
        // Aufgaben, Kalender, Immich gehoeren dem Eigentuemer: auf fremden Seiten nur ein neutraler Platzhalter
        // (der Block bleibt beim Speichern unveraendert)
        else -> if (ed.caps.ownerOnlyBlock(type)) Selectable(key, path, env) { OwnerOnly(type) }
        else Selectable(key, path, env) { Unsupported(BlockDoc.id(block), env) }
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

/** "✓ Aufgaben · Nur für den Eigentümer sichtbar" (wie im Web) - nie Daten des Eigentuemers */
@Composable
private fun OwnerOnly(type: String) {
    val what = when (type) {
        "tasks" -> "✓ " + stringResource(R.string.owner_only_tasks)
        "calendar" -> "🗓 " + stringResource(R.string.owner_only_calendar)
        else -> "📷 Immich"
    }
    Text(
        what + " · " + stringResource(R.string.owner_only),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(12.dp),
    )
}

/**
 * R (2.5): einheitliche Rueckfall-Karte fuer Bloecke, die die App noch nicht kann - Symbol, Hinweis, "Im Web oeffnen"
 * (Seite im Browser, mit Block-Anker). Der Block bleibt beim Speichern unveraendert (Regel 0).
 */
@Composable
private fun Unsupported(blockId: String?, env: BlockEnv) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ed = env.editor
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(androidx.compose.material.icons.Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp).size(20.dp))
        Text(
            stringResource(R.string.gest_fallback_text), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        // Seiten anderer Server haben keine Adresse im eigenen Web-Editor
        if (!ed.isRemote) {
            TextButton(onClick = {
                ed.flush()
                io.github.yggdrasil_sh.saga.ui.common.openUrl(context, io.github.yggdrasil_sh.saga.blocks.Gestalten.webUrl(env.repo.absoluteUrl("notes"), ed.id, blockId))
            }) { Text(stringResource(R.string.gest_open_web)) }
        }
    }
}

/** Liste (ungeordnet, nummeriert, Checkliste) mit verschachtelten Punkten */
@Composable
private fun ListItems(block: JsonObject, blockPath: JsonPath, items: JsonArray, style: String, arrayPath: JsonPath, indices: List<Int>, env: BlockEnv) {
    val ed = env.editor
    val prefix = ed.blockKey(block, blockPath)
    // Editor.js-List v2: nummerierte Liste kann bei meta.start beginnen (oberste Ebene)
    val start = if (indices.isEmpty()) (BlockDoc.int(BlockDoc.data(block)["meta"] as? JsonObject, "start") ?: 1) else 1
    Column(Modifier.padding(start = if (indices.isEmpty()) 0.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, raw ->
            val itemPath = arrayPath + i
            val obj = raw as? JsonObject
            val content = if (obj != null) BlockDoc.str(obj, "content") else (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            val contentPath = if (obj != null) itemPath + "content" else itemPath
            val checked = BlockDoc.bool(obj?.get("meta") as? JsonObject, "checked")
            val myIdx = indices + i
            val fieldKey = prefix + "/" + myIdx.joinToString("/")
            // Kaestchen an der ersten Zeile ausrichten (nicht mittig ueber Text + Unterpunkte): Text um die halbe
            // Differenz zwischen 48-dp-Kaestchen und Zeilenhoehe nach unten
            val checkPad = if (style == "checklist") {
                with(androidx.compose.ui.platform.LocalDensity.current) { ((48.dp - MaterialTheme.typography.bodyLarge.lineHeight.toDp()) / 2).coerceAtLeast(0.dp) }
            } else 0.dp
            Row(verticalAlignment = Alignment.Top) {
                when (style) {
                    "checklist" -> {
                        val label = InlineHtml.plain(content).ifBlank { stringResource(R.string.placeholder_list_item) }
                        // G8: Lesen/Kommentieren -> sichtbar gesperrt (onSurface ~45 %, auch dunkel gut erkennbar)
                        val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        Checkbox(                                    // 48 dp Trefferflaeche; TalkBack liest den Punkt mit
                            checked = checked, onCheckedChange = { ed.toggleChecked(itemPath) }, enabled = ed.editable && obj != null,
                            colors = CheckboxDefaults.colors(disabledCheckedColor = dim, disabledUncheckedColor = dim),
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                    "ordered" -> Text("${i + start}.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 24.dp).padding(end = 6.dp))
                    else -> Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 16.dp).padding(end = 6.dp))
                }
                // Nr. 11: "/" im letzten (obersten) Listenpunkt oeffnet das Block-Menue wie im Absatz; die Wahl beendet die
                // Liste und setzt den Block dahinter (mitten in einer Liste waere das ein Aufteilen - dort bleibt "/" Text)
                val pick = env.slash
                val last = indices.isEmpty() && i == items.size - 1 && (obj?.get("items") as? JsonArray).isNullOrEmpty()
                val slashQ = if (pick != null && ed.editable && last && content.startsWith("/")) io.github.yggdrasil_sh.saga.blocks.SlashLogic.query(InlineHtml.plain(content)) else null
                val slashLabels = if (slashQ != null) slashItems(slashQ, env.slashNeeds) else emptyList()
                val slashPick: (io.github.yggdrasil_sh.saga.blocks.SlashLogic.Item) -> Unit = { item ->
                    ed.endList(blockPath, itemPath)?.let { p -> pick?.invoke(p, item) }
                }
                Column(Modifier.weight(1f)) {
                    key(fieldKey) {
                        RichField(
                            content, fieldKey, ed, blockPath,
                            MaterialTheme.typography.bodyLarge.let {
                                if (checked) it.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) else it
                            },
                            { ed.setString(contentPath, it) },
                            modifier = Modifier.padding(top = checkPad),
                            itemPath = itemPath,
                            placeholder = stringResource(R.string.placeholder_list_item),
                            onEnter = { left, right ->
                                val first = slashLabels.firstOrNull()
                                if (first != null && io.github.yggdrasil_sh.saga.blocks.SlashLogic.query(left.text) != null && right.text.isEmpty()) {
                                    slashPick(first)
                                } else if (left.text.isEmpty() && right.text.isEmpty() && obj?.get("items").let { (it as? JsonArray).isNullOrEmpty() } && indices.isEmpty()) {
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
                    if (slashQ != null && ed.active?.key == fieldKey) SlashMenuCard(slashQ, env.slashNeeds) { slashPick(it) }
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
            IconButton(onClick = { open = !open }) {
                // Pfeil dreht sich mit (Feder), statt das Symbol zu tauschen
                val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
                val turn by androidx.compose.animation.core.animateFloatAsState(
                    if (open) 90f else 0f, io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.fastSpatial(), label = "toggle",
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(if (open) R.string.action_collapse else R.string.action_expand),
                    Modifier.graphicsLayer { rotationZ = if (rtl) -turn else turn },
                )
            }
            RichField(
                // G10 (2.5): Toggle-Ueberschrift 1-3 in Ueberschriftengroesse
                BlockDoc.str(d, "title"), key, ed, path,
                io.github.yggdrasil_sh.saga.blocks.Gestalten.toggleLevel(d).let { lvl -> if (lvl > 0) headerStyle(lvl) else MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold) },
                { ed.setString(path + listOf("data", "title"), it) },
                placeholder = stringResource(R.string.block_toggle),
                onEnter = { _, right ->
                    open = true
                    ed.insertInto(inner, 0, BlockDoc.newBlock("paragraph", InlineHtml.toHtml(right)))
                },
                onLink = env.onLink,
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            open,
            enter = androidx.compose.animation.expandVertically(io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.defaultSpatial()) +
                androidx.compose.animation.fadeIn(io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.defaultEffects()),
            exit = androidx.compose.animation.shrinkVertically(io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.fastSpatial()) +
                androidx.compose.animation.fadeOut(io.github.yggdrasil_sh.saga.ui.theme.SagaMotion.current.fastEffects()),
        ) {
            val arr = JsonTree.get(ed.doc, inner) as? JsonArray
            Box(Modifier.padding(start = 32.dp, top = 4.dp)) {
                if (arr.isNullOrEmpty()) {
                    if (ed.editable) TextButton(onClick = { ed.insertInto(inner, 0, BlockDoc.newBlock("paragraph")) }) {
                        Text(stringResource(R.string.toggle_empty_tap))
                    } else Text(stringResource(R.string.toggle_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    BlockColumn(inner, env)
                }
            }
        }
    }
}
