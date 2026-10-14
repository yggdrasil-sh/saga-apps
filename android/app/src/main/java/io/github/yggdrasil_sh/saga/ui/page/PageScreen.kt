package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import androidx.annotation.StringRes
import io.github.yggdrasil_sh.saga.util.resolve
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.yggdrasil_sh.saga.util.UiText
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
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import android.text.format.DateUtils
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
import io.github.yggdrasil_sh.saga.ui.publish.PublishModel
import io.github.yggdrasil_sh.saga.ui.publish.PublishSheet
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private val PAGE_LINK = Regex("^/notes#(\\d+)$")

/** Blocktypen fuer "Neu" und "Typ" (Name, Typ, Ebene) */
private data class BlockChoice(@param:StringRes val label: Int, val type: String, val level: Int)

private val BLOCK_TYPES = listOf(
    BlockChoice(R.string.block_text, "paragraph", 0),
    BlockChoice(R.string.block_heading, "header", 1),
    BlockChoice(R.string.block_heading, "header", 2),
    BlockChoice(R.string.block_heading, "header", 3),
    BlockChoice(R.string.block_bulleted, "unordered", 0),
    BlockChoice(R.string.block_numbered, "ordered", 0),
    BlockChoice(R.string.block_checklist, "checklist", 0),
    BlockChoice(R.string.block_quote, "quote", 0),
    BlockChoice(R.string.block_callout, "callout", 0),
    BlockChoice(R.string.block_code, "code", 0),
    BlockChoice(R.string.block_toggle, "toggle", 0),
    BlockChoice(R.string.block_divider, "delimiter", 0),
)

/** Ueberschriften mit Ebene ("Ueberschrift 2"), sonst nur der Name */
@Composable
private fun BlockChoice.title(): String = if (type == "header") stringResource(R.string.block_heading, level) else stringResource(label)

/** Eine Seite: Titel + Bloecke, bearbeitbar; speichert automatisch (verzoegert und beim Verlassen) */
@Composable
fun PageScreen(id: Int, repo: SagaRepository, nav: AppNavigator, chrome: Chrome, appScope: CoroutineScope) {
    val ed = remember(id) { PageEditor(id, repo, appScope) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val needsLogin by repo.needsLogin.collectAsState()
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(id) { ed.load() }
    DisposableEffect(id) { onDispose { ed.close() } }                // speichern (laeuft im App-Scope weiter)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { ed.flush() }      // App verlassen: Entwurf auf Platte + speichern
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
    var confirmTrash by remember { mutableStateOf(false) }
    var conflictDialog by remember { mutableStateOf(false) }
    // Veroeffentlichen: Dialog (Bottom Sheet); aeltere Server ohne /publish -> Menuepunkt ausblenden
    var publishModel by remember(id) { mutableStateOf<PublishModel?>(null) }
    var publishHidden by remember(id) { mutableStateOf(false) }
    val openPublish: () -> Unit = {
        publishModel = PublishModel(id, repo, repo.absoluteUrl("").trimEnd('/')) { ed.applyPublish(it) }
    }
    LaunchedEffect(ed.conflict) { if (ed.conflict) conflictDialog = true }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        SagaTopBar(ed.title.ifEmpty { if (ed.fetched) stringResource(R.string.page_untitled) else "" }, nav, chrome) {
            SaveState(ed)
            if (ed.fetched) {
                IconButton(onClick = { repo.launchAction { ed.setPinned(!ed.pinned) } }) {
                    Icon(
                        Icons.Filled.Star, stringResource(if (ed.pinned) R.string.favorite_remove else R.string.favorite_add),
                        tint = if (ed.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (ed.fetched && !ed.isDatabase) {
                        DropdownMenuItem(text = { Text(stringResource(if (ed.locked) R.string.page_unlock else R.string.page_lock)) }, onClick = {
                            menu = false
                            repo.launchAction { ed.setLocked(!ed.locked) }
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.page_reload)) }, onClick = {
                        menu = false
                        scope.launch { ed.save(); ed.load() }
                    })
                    if (ed.fetched && ed.publishSupported && !ed.template && !publishHidden) {
                        // Menue einheitlich ohne Symbole (der Zustand "Öffentlich" steht im Seitenkopf)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.publish_menu)) },
                            onClick = {
                                menu = false
                                openPublish()
                            },
                        )
                    }
                    // "App-Link kopieren" / "Im Web-Editor öffnen": Link in den Editor (nicht die oeffentliche Seite)
                    DropdownMenuItem(text = { Text(stringResource(R.string.page_copy_link)) }, onClick = {
                        menu = false
                        copy(context, repo.absoluteUrl("notes") + "#$id")
                        repo.notify(UiText.Res(R.string.page_link_copied))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.page_open_web_editor)) }, onClick = {
                        menu = false
                        ed.flush()
                        openUrl(context, repo.absoluteUrl("notes") + "#$id")
                    })
                    if (ed.fetched) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.page_archive)) }, onClick = {
                            menu = false
                            repo.launchAction {
                                ed.saveAndWait()
                                repo.archivePage(id, true)
                                if (!nav.pop()) nav.top(Dest.Today)
                                repo.notify(UiText.Res(R.string.page_archived), UiText.Res(R.string.action_undo)) {
                                    repo.launchAction { repo.archivePage(id, false) }
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.page_trash), color = MaterialTheme.colorScheme.error) }, onClick = {
                            menu = false
                            confirmTrash = true
                        })
                    }
                }
            }
        }
        if (ed.conflict && !conflictDialog) {
            Text(
                stringResource(R.string.conflict_banner), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).clickable { conflictDialog = true }.padding(12.dp),
            )
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
                item(key = "header") { PageHeader(ed, repo, onPublish = if (ed.template || publishHidden) null else openPublish) }
                if (ed.fetched && !ed.isDatabase) {
                    itemsIndexed(blocks, key = { i, _ -> keys[i] }) { i, b ->
                        val obj = b as? JsonObject
                        if (obj != null) {
                            // stabiles Pfad-Objekt: unveraenderte Bloecke werden beim Tippen uebersprungen (Strong Skipping)
                            val path = remember(i) { listOf<Any>("blocks", i) }
                            Box(Modifier.padding(vertical = 4.dp)) { BlockView(obj, path, env) }
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
                                stringResource(R.string.page_database_soon),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { openUrl(context, repo.absoluteUrl("notes") + "#$id") }) { Text(stringResource(R.string.action_open_in_browser)) }
                        }
                    }
                }
            }
            if (ed.loading && !ed.fetched) CircularProgressIndicator(Modifier.padding(top = 80.dp))
        }

        val active = ed.active
        if (active != null && ed.editable) EditorToolbar(ed, active, onLink)
    }

    publishModel?.let { m ->
        PublishSheet(
            m, scope,
            onDismiss = {
                if (m.unsupported) publishHidden = true
                publishModel = null
            },
            onOpenPage = { pid ->
                publishModel = null
                nav.push(Dest.Page(pid))
            },
        )
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text(stringResource(R.string.page_trash_confirm)) },
            text = { Text(stringResource(R.string.page_trash_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    repo.launchAction {
                        ed.saveAndWait()
                        repo.trashPage(id)
                        if (!nav.pop()) nav.top(Dest.Today)
                        repo.notify(UiText.Res(R.string.page_trashed), UiText.Res(R.string.action_undo)) {
                            repo.launchAction { repo.restorePage(id) }
                        }
                    }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (conflictDialog && ed.conflict) {
        val copyTitle = stringResource(R.string.conflict_copy_title, ed.title.ifEmpty { stringResource(R.string.page_untitled) })
        AlertDialog(
            onDismissRequest = { conflictDialog = false },
            title = { Text(stringResource(R.string.conflict_title)) },
            text = { Text(stringResource(R.string.conflict_text)) },
            confirmButton = {
                TextButton(onClick = {
                    conflictDialog = false
                    repo.launchAction { ed.keepMine() }
                }) { Text(stringResource(R.string.conflict_keep_mine)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    conflictDialog = false
                    repo.launchAction {
                        val copy = ed.saveCopyAndLoadServer(copyTitle)
                        if (copy != null) repo.notify(UiText.Res(R.string.conflict_copy_saved), UiText.Res(R.string.action_open)) { nav.push(Dest.Page(copy)) }
                    }
                }) { Text(stringResource(R.string.conflict_save_copy)) }
            },
        )
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Saga", text))
}

@Composable
private fun PageHeader(ed: PageEditor, repo: SagaRepository, onPublish: (() -> Unit)?) {
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
                Text(ed.title.ifEmpty { stringResource(R.string.page_untitled) }, style = titleStyle)
            } else {
                BasicTextField(
                    value = ed.title, onValueChange = { ed.editTitle(it) }, textStyle = titleStyle, readOnly = ed.pendingDraft != null,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {
                        val first = BlockDoc.blocks(ed.doc).firstOrNull() as? JsonObject
                        ed.requestFocus(first?.let { ed.fieldKeyFor(it, listOf("blocks", 0)) })
                    }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (ed.title.isEmpty()) Text(stringResource(R.string.page_untitled), style = titleStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)))
                            inner()
                        }
                    },
                )
            }
        }
        val via = if (!ed.published) ed.publishedVia else null
        if (ed.locked || ed.published || via != null) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ed.locked) {
                    AssistChip(
                        onClick = { repo.launchAction { ed.setLocked(false) } },
                        label = { Text(stringResource(R.string.page_locked_chip)) },
                        leadingIcon = { Icon(Icons.Filled.Lock, null, Modifier.size(16.dp)) },
                    )
                }
                if (ed.published || via != null) PublicChip(via?.title, onPublish)
            }
        }
        if (ed.converted) {
            Text(
                stringResource(R.string.page_converted),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
        ed.info?.let {
            Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp).clickable { ed.info = null })
        }
        ed.error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        if (ed.offline) {
            Text(
                stringResource(R.string.offline_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp),
            )
        }
        ed.pendingDraft?.let { d -> DraftBanner(d.stamp, onRestore = { ed.restoreDraft() }, onDiscard = { ed.discardDraft() }) }
        if (!ed.loading && !ed.fetched) {                        // Laden fehlgeschlagen: nichts Bearbeitbares zeigen
            Button(onClick = { scope.launch { ed.load() } }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/**
 * Hinweis "Oeffentlich" im Seitenkopf (wie "🌐 Öffentlich" im Web): Globus + Text, antippbar -> Dialog.
 * viaTitle != null: oeffentlich als Unterseite dieser Seite (Titel gekuerzt wie im Web).
 */
@Composable
private fun PublicChip(viaTitle: String?, onClick: (() -> Unit)?) {
    val untitled = stringResource(R.string.page_untitled)
    val full = viaTitle?.ifEmpty { untitled }
    val short = full?.let { if (it.length > 28) it.take(27) + "…" else it }
    val label = short?.let { stringResource(R.string.publish_badge_via, it) } ?: stringResource(R.string.publish_badge)
    val hint = full?.let { stringResource(R.string.publish_badge_via_hint, it) } ?: stringResource(R.string.publish_badge_hint)
    AssistChip(
        onClick = { onClick?.invoke() }, enabled = onClick != null,
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_public), null, Modifier.size(AssistChipDefaults.IconSize), tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.semantics { contentDescription = hint },
    )
}

/** Entwurf vom letzten Mal (weicht vom Server-Stand ab): Wiederherstellen oder Verwerfen - wie iOS/Linux */
@Composable
private fun DraftBanner(stamp: Long, onRestore: () -> Unit, onDiscard: () -> Unit) {
    val context = LocalContext.current
    val time = remember(stamp) {
        DateUtils.formatDateTime(context, stamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Text(
                stringResource(R.string.draft_banner, time), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.draft_discard)) }
                TextButton(onClick = onRestore) { Text(stringResource(R.string.draft_restore)) }
            }
        }
    }
}

/** Werkzeugleiste ueber der Tastatur: Block einfuegen/umwandeln, Formatierung, Link, verschieben, loeschen */
@Composable
private fun EditorToolbar(ed: PageEditor, active: ActiveField, onLink: (String) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
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
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, stringResource(R.string.block_insert)) }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        BLOCK_TYPES.forEach { choice ->
                            val (_, type, level) = choice
                            DropdownMenuItem(text = { Text(choice.title()) }, onClick = {
                                addMenu = false
                                ed.insertBlockAfter(blockPath, BlockDoc.newBlock(type, level = if (level == 0) 2 else level))
                            })
                        }
                    }
                }
                if (active.itemPath() == null && ed.block(blockPath)?.let { BlockDoc.mainText(it) != null } == true) {
                    Box {
                        IconButton(onClick = { typeMenu = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.block_change_type)) }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            BLOCK_TYPES.filter { it.type != "delimiter" }.forEach { choice ->
                                val (_, type, level) = choice
                                DropdownMenuItem(text = { Text(choice.title()) }, onClick = {
                                    typeMenu = false
                                    ed.convertBlock(blockPath, type, if (level == 0) 2 else level)
                                })
                            }
                        }
                    }
                } else if (active.itemPath() != null) {
                    Box {
                        IconButton(onClick = { typeMenu = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.list_change_type)) }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            listOf(R.string.block_bulleted to "unordered", R.string.block_numbered to "ordered", R.string.block_checklist to "checklist", R.string.block_text to "paragraph").forEach { (label, t) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                    typeMenu = false
                                    ed.convertBlock(blockPath, t)
                                })
                            }
                        }
                    }
                }
                if (isText) {
                    FormatButton(stringResource(R.string.format_bold_short), stringResource(R.string.format_bold), active.styleOn(InlineStyle.BOLD), active.hasSelection(), fontWeight = FontWeight.Bold) { active.toggle.invoke(InlineStyle.BOLD) }
                    FormatButton(stringResource(R.string.format_italic_short), stringResource(R.string.format_italic), active.styleOn(InlineStyle.ITALIC), active.hasSelection(), fontStyle = FontStyle.Italic) { active.toggle.invoke(InlineStyle.ITALIC) }
                    FormatButton(stringResource(R.string.format_underline_short), stringResource(R.string.format_underline), active.styleOn(InlineStyle.UNDERLINE), active.hasSelection(), deco = TextDecoration.Underline) { active.toggle.invoke(InlineStyle.UNDERLINE) }
                    FormatButton(stringResource(R.string.format_strike_short), stringResource(R.string.format_strike), active.styleOn(InlineStyle.STRIKE), active.hasSelection(), deco = TextDecoration.LineThrough) { active.toggle.invoke(InlineStyle.STRIKE) }
                    TextButton(onClick = { linkDialog = true }, enabled = active.hasSelection(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.format_link)) }
                    active.linkAtCursor?.invoke()?.let { href ->
                        TextButton(onClick = { onLink(href) }) { Text(stringResource(R.string.link_open)) }
                    }
                }
                IconButton(onClick = { ed.moveBlock(blockPath, -1) }) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.block_move_up)) }
                IconButton(onClick = { ed.moveBlock(blockPath, 1) }) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.block_move_down)) }
                IconButton(onClick = {
                    val ip = active.itemPath()
                    if (ip != null) ed.removeItem(blockPath, ip) else ed.deleteBlock(blockPath)
                }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                IconButton(onClick = { focus.clearFocus(); ed.active = null }) { Icon(Icons.Filled.Check, stringResource(R.string.action_done)) }
            }
        }
    }

    if (linkDialog) {
        var href by remember { mutableStateOf(active.linkAtCursor?.invoke() ?: "https://") }
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text(stringResource(R.string.format_link)) },
            text = {
                OutlinedTextField(value = href, onValueChange = { href = it }, singleLine = true, label = { Text(stringResource(R.string.link_address_label)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    linkDialog = false
                    val h = href.trim()
                    if (h.isEmpty() || h == "https://") active.setLink?.invoke("")
                    else if (h.startsWith("https://") || h.startsWith("http://") || h.startsWith("mailto:") || PAGE_LINK.matches(h)) active.setLink?.invoke(h)
                    else ed.info = UiText.Res(R.string.link_invalid)
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { linkDialog = false; active.setLink?.invoke("") }) { Text(stringResource(R.string.link_remove)) }
            },
        )
    }
}

/** Format-Knopf als Umschalter: 48 dp, TalkBack liest Namen und Zustand (an/aus) */
@Composable
private fun FormatButton(
    label: String, description: String, on: Boolean, enabled: Boolean,
    fontWeight: FontWeight? = null, fontStyle: FontStyle? = null, deco: TextDecoration? = null, onClick: () -> Unit,
) {
    IconToggleButton(
        checked = on, onCheckedChange = { onClick() }, enabled = enabled,
        colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(label, fontWeight = fontWeight, fontStyle = fontStyle, textDecoration = deco, fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** Titelleiste: speichert gerade / gespeichert / nicht gespeichert (wie "Gespeichert ✓" im Web) */
@Composable
private fun SaveState(ed: PageEditor) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        ed.saving -> CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        ed.conflict || ed.error != null -> Icon(Icons.Filled.Warning, stringResource(R.string.save_state_unsaved), tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 4.dp))
        ed.dirty -> Text(stringResource(R.string.save_state_pending), style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(end = 4.dp))
        ed.savedAt != null -> Icon(Icons.Filled.Done, stringResource(R.string.save_state_saved), tint = color, modifier = Modifier.padding(end = 4.dp).size(20.dp))
    }
}
