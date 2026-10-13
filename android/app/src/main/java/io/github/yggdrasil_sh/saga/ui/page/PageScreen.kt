package io.github.yggdrasil_sh.saga.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.InlineStyle
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private val PAGE_LINK = Regex("^/notes#(\\d+)$")

/** Blocktypen fuer "Neu" und "Typ" (Name, Typ, Ebene) */
private val BLOCK_TYPES = listOf(
    Triple("Text", "paragraph", 0),
    Triple("Überschrift 1", "header", 1),
    Triple("Überschrift 2", "header", 2),
    Triple("Überschrift 3", "header", 3),
    Triple("Aufzählung", "unordered", 0),
    Triple("Nummerierte Liste", "ordered", 0),
    Triple("Checkliste", "checklist", 0),
    Triple("Zitat", "quote", 0),
    Triple("Hinweis", "callout", 0),
    Triple("Code", "code", 0),
    Triple("Toggle", "toggle", 0),
    Triple("Trenner", "delimiter", 0),
)

/** Eine Seite: Titel + Bloecke, bearbeitbar; speichert automatisch (verzoegert und beim Verlassen) */
@Composable
fun PageScreen(id: Int, repo: SagaRepository, nav: AppNavigator, chrome: Chrome, appScope: CoroutineScope) {
    val ed = remember(id) { PageEditor(id, repo, appScope) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val needsLogin by repo.needsLogin.collectAsState()
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(id) { ed.load() }
    DisposableEffect(id) { onDispose { ed.flush() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { ed.flush() }
    LaunchedEffect(needsLogin) { if (!needsLogin && ed.dirty) ed.scheduleSave(500) }   // nach erneuter Anmeldung nachholen

    val onLink: (String) -> Unit = { href ->
        val m = PAGE_LINK.find(href)
        when {
            m != null -> nav.push(Dest.Page(m.groupValues[1].toInt()))
            href.startsWith("http://") || href.startsWith("https://") -> openUrl(context, href)
            href.startsWith("mailto:") -> openUrl(context, href)
            href.startsWith("/") -> openUrl(context, repo.absoluteUrl(href))
        }
    }
    val env = remember(ed) { BlockEnv(ed, repo, onLink) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        SagaTopBar(ed.title.ifEmpty { if (ed.fetched) "Unbenannt" else "" }, nav, chrome) {
            if (ed.saving) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            if (ed.fetched) {
                IconButton(onClick = {
                    scope.launch {
                        try {
                            ed.setPinned(!ed.pinned)
                        } catch (e: Exception) {
                            Toast.makeText(context, repo.friendly(e), Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Icon(
                        Icons.Filled.Star, if (ed.pinned) "Aus Favoriten entfernen" else "Zu Favoriten",
                        tint = if (ed.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (ed.fetched && !ed.isDatabase) {
                        DropdownMenuItem(text = { Text(if (ed.locked) "Seite entsperren" else "Seite sperren") }, onClick = {
                            menu = false
                            scope.launch {
                                try {
                                    ed.setLocked(!ed.locked)
                                } catch (e: Exception) {
                                    Toast.makeText(context, repo.friendly(e), Toast.LENGTH_SHORT).show()
                                }
                            }
                        })
                    }
                    DropdownMenuItem(text = { Text("Neu laden") }, onClick = {
                        menu = false
                        scope.launch { ed.save(); ed.load() }
                    })
                    DropdownMenuItem(text = { Text("Link kopieren") }, onClick = {
                        menu = false
                        copy(context, repo.absoluteUrl("notes") + "#$id")
                    })
                    DropdownMenuItem(text = { Text("Im Browser öffnen") }, onClick = {
                        menu = false
                        scope.launch { ed.save() }
                        openUrl(context, repo.absoluteUrl("notes") + "#$id")
                    })
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val blocks = BlockDoc.blocks(ed.doc)
            val keys = remember(blocks) {
                val seen = HashSet<String>()
                blocks.mapIndexed { i, b ->
                    val k = BlockDoc.id(b) ?: "i$i"
                    if (seen.add(k)) k else "dup-$i-$k"
                }
            }
            LazyColumn(Modifier.widthIn(max = 820.dp).fillMaxSize().padding(horizontal = 18.dp)) {
                item(key = "header") { PageHeader(ed, repo) }
                if (ed.fetched && !ed.isDatabase) {
                    itemsIndexed(blocks, key = { i, _ -> keys[i] }) { i, b ->
                        val obj = b as? JsonObject
                        if (obj != null) {
                            Box(Modifier.padding(vertical = 4.dp)) { BlockView(obj, listOf("blocks", i), env) }
                        }
                    }
                    item(key = "footer") {
                        // wie Notion: in den freien Bereich unter dem letzten Block tippen -> weiterschreiben
                        Spacer(
                            Modifier.fillMaxWidth().height(180.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = ed.editable) { ed.continueWriting() },
                        )
                    }
                }
                if (ed.fetched && ed.isDatabase) {
                    item(key = "db") {
                        Column(Modifier.padding(vertical = 16.dp)) {
                            Text(
                                "Datenbanken mit Ansichten (Tabelle, Board, Kalender …) kommen in einer der nächsten Versionen.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { openUrl(context, repo.absoluteUrl("notes") + "#$id") }) { Text("Im Browser öffnen") }
                        }
                    }
                }
            }
            if (ed.loading && !ed.fetched) CircularProgressIndicator(Modifier.padding(top = 80.dp))
        }

        val active = ed.active
        if (active != null && ed.editable) EditorToolbar(ed, active, onLink)
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Saga", text))
    Toast.makeText(context, "Link kopiert", Toast.LENGTH_SHORT).show()
}

@Composable
private fun PageHeader(ed: PageEditor, repo: SagaRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        if (ed.icon.isNotEmpty()) {
            PageIcon(ed.icon, repo, ed.isDatabase, size = 52.dp)
            Spacer(Modifier.height(6.dp))
        }
        if (ed.fetched) {
            val titleStyle = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (ed.locked) {
                Text(ed.title.ifEmpty { "Unbenannt" }, style = titleStyle)
            } else {
                BasicTextField(
                    value = ed.title, onValueChange = { ed.editTitle(it) }, textStyle = titleStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {
                        val first = BlockDoc.blocks(ed.doc).firstOrNull() as? JsonObject
                        ed.requestFocus(first?.let { ed.fieldKeyFor(it, listOf("blocks", 0)) })
                    }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (ed.title.isEmpty()) Text("Unbenannt", style = titleStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)))
                            inner()
                        }
                    },
                )
            }
        }
        if (ed.locked) {
            AssistChip(
                onClick = {
                    scope.launch {
                        try {
                            ed.setLocked(false)
                        } catch (e: Exception) {
                            Toast.makeText(context, repo.friendly(e), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                label = { Text("Gesperrt – tippen zum Entsperren") },
                leadingIcon = { Icon(Icons.Filled.Lock, null, Modifier.size(16.dp)) },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (ed.converted) {
            Text(
                "Alte Textnotiz – wird beim Bearbeiten als Blöcke gespeichert.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
        ed.info?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp).clickable { ed.info = null })
        }
        ed.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        if (!ed.loading && !ed.fetched) {                        // Laden fehlgeschlagen: nichts Bearbeitbares zeigen
            Button(onClick = { scope.launch { ed.load() } }, modifier = Modifier.padding(top = 12.dp)) { Text("Erneut versuchen") }
        }
    }
}

/** Werkzeugleiste ueber der Tastatur: Block einfuegen/umwandeln, Formatierung, Link, verschieben, loeschen */
@Composable
private fun EditorToolbar(ed: PageEditor, active: ActiveField, onLink: (String) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var addMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    val blockPath: JsonPath = active.blockPath()
    val isText = active.toggle != null

    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, "Block einfügen") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        BLOCK_TYPES.forEach { (label, type, level) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                addMenu = false
                                ed.insertBlockAfter(blockPath, BlockDoc.newBlock(type, level = if (level == 0) 2 else level))
                            })
                        }
                    }
                }
                if (active.itemPath() == null && ed.block(blockPath)?.let { BlockDoc.mainText(it) != null } == true) {
                    Box {
                        IconButton(onClick = { typeMenu = true }) { Icon(Icons.Filled.Edit, "Blocktyp ändern") }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            BLOCK_TYPES.filter { it.second != "delimiter" }.forEach { (label, type, level) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    typeMenu = false
                                    ed.convertBlock(blockPath, type, if (level == 0) 2 else level)
                                })
                            }
                        }
                    }
                } else if (active.itemPath() != null) {
                    Box {
                        IconButton(onClick = { typeMenu = true }) { Icon(Icons.Filled.Edit, "Listenart ändern") }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            listOf("Aufzählung" to "unordered", "Nummerierte Liste" to "ordered", "Checkliste" to "checklist", "Text" to "paragraph").forEach { (label, t) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    typeMenu = false
                                    ed.convertBlock(blockPath, t)
                                })
                            }
                        }
                    }
                }
                if (isText) {
                    FormatButton("B", active.styleOn(InlineStyle.BOLD), active.hasSelection(), fontWeight = FontWeight.Bold) { active.toggle.invoke(InlineStyle.BOLD) }
                    FormatButton("I", active.styleOn(InlineStyle.ITALIC), active.hasSelection(), fontStyle = FontStyle.Italic) { active.toggle.invoke(InlineStyle.ITALIC) }
                    FormatButton("U", active.styleOn(InlineStyle.UNDERLINE), active.hasSelection(), deco = TextDecoration.Underline) { active.toggle.invoke(InlineStyle.UNDERLINE) }
                    FormatButton("S", active.styleOn(InlineStyle.STRIKE), active.hasSelection(), deco = TextDecoration.LineThrough) { active.toggle.invoke(InlineStyle.STRIKE) }
                    FormatButton("Link", false, active.hasSelection()) { linkDialog = true }
                    active.linkAtCursor?.invoke()?.let { href ->
                        TextButton(onClick = { onLink(href) }) { Text("Link öffnen") }
                    }
                }
                IconButton(onClick = { ed.moveBlock(blockPath, -1) }) { Icon(Icons.Filled.KeyboardArrowUp, "Block nach oben") }
                IconButton(onClick = { ed.moveBlock(blockPath, 1) }) { Icon(Icons.Filled.KeyboardArrowDown, "Block nach unten") }
                IconButton(onClick = {
                    val ip = active.itemPath()
                    if (ip != null) ed.removeItem(blockPath, ip) else ed.deleteBlock(blockPath)
                }) { Icon(Icons.Filled.Delete, "Löschen") }
                IconButton(onClick = { focus.clearFocus(); ed.active = null }) { Icon(Icons.Filled.Check, "Fertig") }
            }
        }
    }

    if (linkDialog) {
        var href by remember { mutableStateOf(active.linkAtCursor?.invoke() ?: "https://") }
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text("Link") },
            text = {
                OutlinedTextField(value = href, onValueChange = { href = it }, singleLine = true, label = { Text("Adresse (https://… oder /notes#123)") })
            },
            confirmButton = {
                TextButton(onClick = {
                    linkDialog = false
                    val h = href.trim()
                    if (h.isEmpty() || h == "https://") active.setLink?.invoke("")
                    else if (h.startsWith("https://") || h.startsWith("http://") || h.startsWith("mailto:") || PAGE_LINK.matches(h)) active.setLink?.invoke(h)
                    else Toast.makeText(context, "Nur http(s)-, mailto- oder Seiten-Links (/notes#123)", Toast.LENGTH_LONG).show()
                }) { Text("Übernehmen") }
            },
            dismissButton = {
                TextButton(onClick = { linkDialog = false; active.setLink?.invoke("") }) { Text("Link entfernen") }
            },
        )
    }
}

@Composable
private fun FormatButton(
    label: String, on: Boolean, enabled: Boolean,
    fontWeight: FontWeight? = null, fontStyle: FontStyle? = null, deco: TextDecoration? = null, onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(horizontal = 0.dp)) {
        Text(
            label, fontWeight = fontWeight, fontStyle = fontStyle, textDecoration = deco, fontSize = 16.sp,
            modifier = if (on) Modifier.background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 4.dp) else Modifier.padding(horizontal = 4.dp),
        )
    }
}
