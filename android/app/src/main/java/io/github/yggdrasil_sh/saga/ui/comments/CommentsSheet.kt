package io.github.yggdrasil_sh.saga.ui.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.Comment
import io.github.yggdrasil_sh.saga.data.CommentThread
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.ui.common.relativeTime
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Kommentare als Sheet von unten (dezent: die Seite bleibt oben sichtbar). Alle Unterhaltungen oder nur die eines
 * Blocks; antworten, eigene bearbeiten/loeschen (⋯), "✓ Erledigt"/"Wieder öffnen", "Erledigte zeigen" am Listenende,
 * ungelesen = Punkt neben dem Autor. Rolle Lesen: nur lesen (Fusszeile erklaert es).
 * quote(blockId) = Zitat des Blocks fuer "Alle Unterhaltungen"; onJump springt zum Block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(model: CommentsModel, scope: CoroutineScope, quote: (String) -> BlockQuote?, onJump: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snack = remember { SnackbarHostState() }
    val res = LocalResources.current
    var confirm by remember { mutableStateOf<Triple<CommentThread, Comment, CommentAction>?>(null) }

    LaunchedEffect(model) { model.load() }
    val shown = model.shownThreads
    LaunchedEffect(shown, model.visible) { model.markSeen() }
    LaunchedEffect(model.toast) {
        val t = model.toast ?: return@LaunchedEffect
        val r = snack.showSnackbar(t.text.asString(res), t.action?.asString(res), withDismissAction = t.action == null)
        if (r == SnackbarResult.ActionPerformed) t.onAction?.invoke()
        if (model.toast === t) model.toast = null
    }

    ModalBottomSheet(onDismissRequest = { model.close() }, sheetState = sheet) {
        Box {
            Column(Modifier.fillMaxWidth().imePadding()) {
                // Kopf: "Kommentare (n)" + Schliessen
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.comments_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (model.openCount > 0) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(start = 10.dp)) {
                            Text("${model.openCount}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { model.close() }) { Icon(Icons.Filled.Close, stringResource(R.string.comments_close)) }
                }
                if (model.block != null) {
                    TextButton(onClick = { model.showAll() }, modifier = Modifier.padding(start = 12.dp)) { Text(stringResource(R.string.comments_all_threads)) }
                }
                HorizontalDivider()
                if (model.loading && !model.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 80.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    model.loadError?.let { e -> item { Text(e.resolve(), color = MaterialTheme.colorScheme.error) } }
                    items(shown, key = { it.id }) { t ->
                        ThreadCard(t, model, scope, if (model.block == null) quote else null, onJump) { c, a -> confirm = Triple(t, c, a) }
                    }
                    if (shown.isEmpty() && model.loaded) {
                        item {
                            Text(
                                model.emptyText.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (model.showResolvedSwitch) {
                        item {
                            // Handy: Schalter "Erledigte zeigen" am Listenende (wie im Web)
                            val label = stringResource(R.string.comments_show_resolved)
                            val n = res.getQuantityString(R.plurals.comments_resolved_n, model.resolvedCount, model.resolvedCount)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { stateDescription = n },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                                Switch(checked = model.showResolved, onCheckedChange = { on -> scope.launch { model.setShowResolved(on) } })
                            }
                        }
                    }
                }
                HorizontalDivider()
                Footer(model, scope)
            }
            SnackbarHost(snack, Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
        }
    }

    confirm?.let { (t, c, a) ->
        val first = t.comments.firstOrNull()?.id == c.id
        val (title, text) = CommentLogic.deletePrompt(t, first, a)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { model.delete(c.id, a == CommentAction.DELETE_THREAD) }
                }) { Text(stringResource(R.string.comments_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Fusszeile: neuer Kommentar (Block oder ganze Seite) bzw. Hinweis fuer Rolle Lesen */
@Composable
private fun Footer(model: CommentsModel, scope: CoroutineScope) {
    if (!model.canComment) {
        Text(
            stringResource(R.string.comments_read_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        return
    }
    var text by rememberSaveable(model.block) { mutableStateOf("") }
    Editor(
        value = text, onValue = { text = it },
        placeholder = stringResource(if (model.block != null) R.string.comments_ph_block else R.string.comments_ph_page),
        ok = stringResource(R.string.comments_add), busy = model.busy,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        onOk = { scope.launch { if (model.send(text)) text = "" } },
    )
}

/** Eingabe mit Zaehler (ab 1800 Zeichen) und Knopf; onCancel = Abbrechen (Antworten/Bearbeiten) */
@Composable
private fun Editor(
    value: String, onValue: (String) -> Unit, placeholder: String, ok: String, busy: Boolean,
    modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null, onOk: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = { onValue(it.take(CommentLogic.MAX)) },
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            minLines = 1, maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val n = value.length
            Text(
                if (n > CommentLogic.MAX - 200) stringResource(R.string.comments_counter, n, CommentLogic.MAX) else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onOk, enabled = value.isNotBlank() && !busy, modifier = Modifier.padding(start = 8.dp)) { Text(ok) }
        }
    }
}

@Composable
private fun ThreadCard(
    t: CommentThread, model: CommentsModel, scope: CoroutineScope, quote: ((String) -> BlockQuote?)?, onJump: (String) -> Unit,
    onConfirm: (Comment, CommentAction) -> Unit,
) {
    val border = if (t.unread && !t.resolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, border, RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (quote != null) {
            // in "Alle Unterhaltungen": woran die Unterhaltung haengt (Block, ganze Seite, Block entfernt)
            val q = if (t.blockId.isEmpty()) null else if (!t.blockExists) null else quote(t.blockId)
            val gone = t.blockId.isNotEmpty() && q == null
            val label = when {
                t.blockId.isEmpty() -> stringResource(R.string.comments_whole_page)
                gone -> stringResource(R.string.comments_block_removed)
                q is BlockQuote.Text -> q.text
                q is BlockQuote.Image -> stringResource(R.string.comments_image)
                else -> stringResource(R.string.comments_block)
            }
            val jump = !gone && t.blockId.isNotEmpty()
            Text(
                label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontStyle = if (gone) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().then(
                    if (jump) Modifier.clickable(onClickLabel = stringResource(R.string.comments_go_to_block)) { onJump(t.blockId) } else Modifier,
                ).heightIn(min = 32.dp).padding(vertical = 4.dp),
            )
            HorizontalDivider(Modifier.padding(bottom = 6.dp))
        }
        val by = CommentLogic.resolvedLabel(t).resolve()
        if (t.resolved && t.id !in model.expanded) {
            // erledigt: zugeklappt
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { model.expand(t.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (t.resolvedBy != null) "✓ $by" else by, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    LocalResources.current.getQuantityString(R.plurals.comments_count, t.comments.size, t.comments.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        if (t.resolved) {
            Text(if (t.resolvedBy != null) "✓ $by" else by, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        val dot = CommentLogic.unreadIndex(t)
        t.comments.forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            CommentView(t, c, i == 0, i == dot, model, scope, onConfirm)
        }
        if (model.canComment) {
            if (model.replyTo == t.id) {
                var text by rememberSaveable(t.id) { mutableStateOf("") }
                Editor(
                    value = text, onValue = { text = it }, placeholder = stringResource(R.string.comments_reply), ok = stringResource(R.string.comments_reply),
                    busy = model.busy, modifier = Modifier.padding(top = 8.dp), onCancel = { model.replyTo = null },
                    onOk = { scope.launch { if (model.reply(t.id, text)) text = "" } },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (model.replyTo != t.id) {
                    TextButton(onClick = { model.replyTo = t.id; model.editing = null }) { Text(stringResource(R.string.comments_reply)) }
                }
                Spacer(Modifier.weight(1f))
                val desc = stringResource(if (t.resolved) R.string.comments_reopen_desc else R.string.comments_resolve_desc)
                TextButton(
                    onClick = {
                        scope.launch {
                            model.resolve(t, !t.resolved) { scope.launch { model.resolve(t, false) } }
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = desc },
                ) { Text(stringResource(if (t.resolved) R.string.comments_reopen else R.string.comments_resolve)) }
            }
        }
    }
}

@Composable
private fun CommentView(
    t: CommentThread, c: Comment, first: Boolean, dot: Boolean, model: CommentsModel, scope: CoroutineScope,
    onConfirm: (Comment, CommentAction) -> Unit,
) {
    val context = LocalContext.current
    val actions = CommentLogic.actions(t, c, first, model.role)
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c.author.me) stringResource(R.string.share_you) else c.author.name,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (dot) {
                val d = stringResource(R.string.unread_dot_desc)
                Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = d })
            }
            Text(relativeTime(c.created), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            if (c.edited != null && !c.deleted) {
                Text(stringResource(R.string.comments_edited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (actions.isNotEmpty()) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.comments_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    actions.forEach { a ->
                        val label = when (a) {
                            CommentAction.EDIT -> R.string.comments_edit
                            CommentAction.DELETE -> R.string.comments_delete
                            CommentAction.DELETE_THREAD -> R.string.comments_delete_thread
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(label), color = if (a == CommentAction.EDIT) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                if (a == CommentAction.EDIT) {
                                    model.editing = c.id
                                    model.replyTo = null
                                } else onConfirm(c, a)
                            },
                        )
                    }
                }
            }
        }
    }
    if (model.editing == c.id) {
        var text by rememberSaveable(c.id) { mutableStateOf(c.body) }
        Editor(
            value = text, onValue = { text = it }, placeholder = stringResource(R.string.comments_edit_placeholder), ok = stringResource(R.string.action_save),
            busy = model.busy, modifier = Modifier.padding(top = 4.dp), onCancel = { model.editing = null },
            onOk = { scope.launch { model.edit(c.id, text) } },
        )
        return
    }
    if (c.deleted) {
        Text(stringResource(R.string.comments_deleted), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(c.body, linkColor) {
        buildAnnotatedString {
            for (s in CommentLogic.linkify(c.body)) {
                if (s.url == null) append(s.text)
                else withLink(LinkAnnotation.Clickable(s.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) { openUrl(context, s.url) }) {
                    append(s.text)
                }
            }
        }
    }
    BasicText(text, style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.padding(top = 2.dp))
}
