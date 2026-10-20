package io.github.yggdrasil_sh.saga.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.MoveLogic
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.TrashItem
import io.github.yggdrasil_sh.saga.data.TrashState
import io.github.yggdrasil_sh.saga.data.movePage
import io.github.yggdrasil_sh.saga.data.trashEmpty
import io.github.yggdrasil_sh.saga.data.trashList
import io.github.yggdrasil_sh.saga.data.trashPurge
import io.github.yggdrasil_sh.saga.data.trashRestore
import io.github.yggdrasil_sh.saga.ui.common.PageIcon
import io.github.yggdrasil_sh.saga.ui.common.pageTitle
import io.github.yggdrasil_sh.saga.ui.common.relativeTime
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Saga 2.4 (Android): Seiten verschieben und Papierkorb - wie Web und iOS

/** Seite verschieben: Ziel im Baum waehlen (mit Suche). Geteilte Bereiche nur, wo die Rechte es erlauben (MoveLogic) */
@Composable
fun MovePageDialog(repo: SagaRepository, page: PageMeta, onDismiss: () -> Unit, onMoved: () -> Unit = {}) {
    val own by repo.pages.collectAsState()
    val shared by repo.sharedPages.collectAsState()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<MoveLogic.Target?>(null) }
    val untitled = stringResource(R.string.page_untitled)
    val all = remember(own, shared, page) { MoveLogic.targets(page, own, shared) }
    val shown = remember(all, q) { MoveLogic.filter(all, q, untitled) }
    val topLabel = stringResource(R.string.mv_top_level)
    val scope = rememberCoroutineScope()

    fun go(t: MoveLogic.Target) {
        busy = true
        scope.launch {
            try {
                repo.movePage(page.id, t.id)
                repo.notify(UiText.Res(R.string.mv_done, t.page?.title?.ifEmpty { untitled } ?: topLabel))
                onMoved()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(repo.friendly(e))
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.mv_title, page.title.ifEmpty { untitled })) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text(stringResource(R.string.ed24_search_pages)) }, modifier = Modifier.fillMaxWidth())
                if (page.foreign) {
                    Text(stringResource(R.string.mv_shared_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id ?: -1L }) { t ->
                        val label = t.page?.let { pageTitle(it) } ?: topLabel
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !t.current && !busy) {
                                // eigene Seite in einen geteilten Bereich: Freigaben aendern sich -> nachfragen
                                if (!page.foreign && t.page?.shared == true) confirm = t else go(t)
                            }.heightIn(min = 48.dp).padding(start = (t.depth * 14).dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (t.page != null) PageIcon(t.page.icon, repo, t.page.isDatabase) else Text("⤒")
                            Text(
                                label + if (t.current) " · " + stringResource(R.string.mv_current) else "",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (t.current) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (t.id == null) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                    if (shown.isEmpty()) item { Text(stringResource(R.string.mv_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(24.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )

    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.mv_shared_confirm_title)) },
            text = { Text(stringResource(R.string.mv_shared_confirm_text, t.page?.title?.ifEmpty { untitled } ?: "")) },
            confirmButton = { TextButton(onClick = { confirm = null; go(t) }) { Text(stringResource(R.string.mv_move)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Papierkorb: ansehen, wiederherstellen, endgueltig loeschen (mit Rueckfrage), leeren */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashDialog(repo: SagaRepository, onDismiss: () -> Unit) {
    var st by remember { mutableStateOf(TrashState()) }
    var askPurge by remember { mutableStateOf<TrashItem?>(null) }
    var askEmpty by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        st = try {
            st.loaded(repo.trashList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            st.failed(repo.friendly(e))
        }
    }
    LaunchedEffect(Unit) { load() }

    fun act(item: TrashItem, block: suspend () -> Unit, done: UiText) {
        st = st.working(item.id)
        scope.launch {
            try {
                block()
                st = st.removed(item.id)
                repo.notify(done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                st = st.idle(item.id)
                repo.notify(repo.friendly(e))
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.tr_title)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) } },
                    actions = {
                        if (st.items?.isNotEmpty() == true) TextButton(onClick = { askEmpty = true }) {
                            Text(stringResource(R.string.tr_empty), color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
                Text(stringResource(R.string.tr_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                val err = st.error
                when {
                    st.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                    err != null && st.items == null -> Column(Modifier.padding(16.dp)) {
                        Text(err.resolve(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { st = TrashState(); scope.launch { load() } }) { Text(stringResource(R.string.action_retry)) }
                    }
                    st.empty -> Text(stringResource(R.string.tr_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().widthIn(max = 820.dp)) {
                        items(st.items.orEmpty(), key = { it.id }) { t ->
                            val busy = t.id in st.busy
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PageIcon(t.icon, repo, t.kind == "database")
                                    Text(t.title.ifEmpty { stringResource(R.string.page_untitled) }, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                }
                                val parts = buildList {
                                    add(stringResource(R.string.tr_deleted, relativeTime(t.deletedAt)))
                                    if (t.subpages > 0) add(pluralStringResource(R.plurals.tr_subpages, t.subpages, t.subpages))
                                    TrashState.daysLeft(t, System.currentTimeMillis() / 1000.0)?.let { add(pluralStringResource(R.plurals.tr_days_left, it, it)) }
                                }
                                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp))
                                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = {
                                        act(t, { repo.trashRestore(t.id) }, UiText.Res(R.string.tr_restored))
                                    }, enabled = !busy) { Text(stringResource(R.string.tr_restore)) }
                                    Spacer(Modifier.size(8.dp))
                                    TextButton(onClick = { askPurge = t }, enabled = !busy) { Text(stringResource(R.string.tr_purge), color = MaterialTheme.colorScheme.error) }
                                    if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    askPurge?.let { t ->
        AlertDialog(
            onDismissRequest = { askPurge = null },
            title = { Text(stringResource(R.string.tr_purge_title, t.title.ifEmpty { stringResource(R.string.page_untitled) })) },
            text = { Text(if (t.subpages > 0) pluralStringResource(R.plurals.tr_purge_text_sub, t.subpages, t.subpages) else stringResource(R.string.tr_purge_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askPurge = null
                    act(t, { repo.trashPurge(t.id) }, UiText.Res(R.string.tr_purged))
                }) { Text(stringResource(R.string.tr_purge), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askPurge = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (askEmpty) {
        AlertDialog(
            onDismissRequest = { askEmpty = false },
            title = { Text(stringResource(R.string.tr_empty_title)) },
            text = { Text(stringResource(R.string.tr_empty_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askEmpty = false
                    scope.launch {
                        try {
                            repo.trashEmpty()
                            st = st.emptied()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            repo.notify(repo.friendly(e))
                        }
                    }
                }) { Text(stringResource(R.string.tr_empty), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askEmpty = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
