package io.github.yggdrasil_sh.saga.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ai.AiChunk
import io.github.yggdrasil_sh.saga.ai.AiDone
import io.github.yggdrasil_sh.saga.ai.AiHistoryItem
import io.github.yggdrasil_sh.saga.ai.AiLogic
import io.github.yggdrasil_sh.saga.ai.AiPersona
import io.github.yggdrasil_sh.saga.ai.AiPersonaLogic
import io.github.yggdrasil_sh.saga.ai.AiSource
import io.github.yggdrasil_sh.saga.ai.AiStatus
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.chat.copyText
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** Eine Frage mit Antwort (Strom) */
class AskTurn(val q: String) {
    var answer by mutableStateOf("")
    var sources by mutableStateOf<List<AiSource>?>(null)
    var error by mutableStateOf<UiText?>(null)
    var running by mutableStateOf(true)
}

/**
 * KI-Assistent als Bottom Sheet: Reiter "Frag deine Seiten" und (auf einer Seite) "Diese Seite". Fusszeile immer: Hinweis
 * des Servers (wohin Inhalte gehen) und ggf. Kontingent. Klartext, keine Links aus dem Modelltext; [n] -> Quelle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(repo: SagaRepository, editor: PageEditor?, initialTab: Int, onOpenPage: (Long) -> Unit, onDismiss: () -> Unit) {
    val ai = repo.ai
    val status by ai.status.collectAsState()
    var tab by remember { mutableStateOf(if (editor == null) 0 else initialTab) }
    LaunchedEffect(Unit) { ai.loadStatus() }                // Admin kann umschalten: beim Oeffnen neu holen
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                val st = status
                var history by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val persona = AiPersonaLogic.effective(st)
                    if (persona != null) {
                        // 2.1: Profilbild + "Frage {name}"
                        AiAvatar(persona, 32)
                        Text(
                            stringResource(R.string.ai_ask_name, persona.name), style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text("✦ " + stringResource(R.string.ai_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                    // Verlauf nur, wenn der Admin ihn erlaubt
                    if (st?.enabled == true && st.historyAllowed) TextButton(onClick = { history = true }) { Text(stringResource(R.string.ai_history)) }
                }
                if (history) HistoryDialog(repo, st) { history = false }
                if (st != null && !st.enabled) {
                    Text(stringResource(R.string.ai_off), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }
                if (editor != null) {
                    PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.ai_ask_pages)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.ai_this_page)) })
                    }
                }
                Box(Modifier.weight(1f, fill = false).heightIn(min = 200.dp)) {
                    if (tab == 0 || editor == null) AskTab(repo, st, onOpenPage) else PageTab(repo, editor, st, onOpenPage)
                }
                Footer(st)
            }
        }
    }
}

@Composable
private fun Footer(st: AiStatus?) {
    HorizontalDivider()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        st?.notice?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (st?.quota != null && st.quotaLeft != null) {
            Text(
                stringResource(R.string.ai_quota, st.quotaLeft, st.quota), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Antworttext mit antippbaren Verweisen [n] */
@Composable
private fun AnswerText(text: String, sources: List<AiSource>, onOpenPage: (Long) -> Unit) {
    val link = MaterialTheme.colorScheme.primary
    val s = buildAnnotatedString {
        for (seg in AiLogic.segments(text, sources)) {
            when (seg) {
                is AiLogic.Seg.Text -> append(seg.t)
                is AiLogic.Seg.Ref -> {
                    val id = sources.firstOrNull { it.n == seg.n }?.id
                    if (id != null) withLink(LinkAnnotation.Clickable("src-${seg.n}", TextLinkStyles(SpanStyle(color = link, fontWeight = FontWeight.SemiBold))) { onOpenPage(id) }) {
                        append("[${seg.n}]")
                    } else append("[${seg.n}]")
                }
            }
        }
    }
    SelectionContainer { Text(s, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun SourceChips(sources: List<AiSource>, onOpenPage: (Long) -> Unit) {
    if (sources.isEmpty()) {
        Text(stringResource(R.string.ai_no_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(stringResource(R.string.ai_sources), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    // je Quelle ein eigener Chip; Chips brechen um (kein waagrechtes Scrollen, nichts abgeschnitten)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEach { src ->
            AssistChip(
                onClick = { src.id?.let(onOpenPage) }, enabled = src.id != null,
                label = {
                    Text(
                        "[${src.n}] " + src.title + (src.owner?.let { " · $it" } ?: ""), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
    }
}

@Composable
private fun AskTab(repo: SagaRepository, st: AiStatus?, onOpenPage: (Long) -> Unit) {
    val turns = remember { mutableStateListOf<AskTurn>() }
    var q by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf(false) }
    var calendar by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val running = turns.lastOrNull()?.running == true && job?.isActive == true
    Column(Modifier.padding(horizontal = 20.dp)) {
        LazyColumn(Modifier.heightIn(max = 420.dp), state = list) {
            if (turns.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(stringResource(R.string.ai_empty_1), style = MaterialTheme.typography.bodyMedium)
                        val p = AiPersonaLogic.effective(st)
                        Text(if (p != null) stringResource(R.string.ai_empty_2_name, p.name) else stringResource(R.string.ai_empty_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            items(turns) { t ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text(t.q, modifier = Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    val persona = AiPersonaLogic.effective(st)
                    if (persona != null && (t.answer.isNotEmpty() || t.running)) Box(Modifier.padding(top = 8.dp)) { AiWho(persona, persona.name) }
                    if (t.answer.isEmpty() && t.running) {
                        Text(thinkingText(persona), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (t.answer.isNotEmpty()) Box(Modifier.padding(top = 8.dp)) { AnswerText(t.answer, t.sources.orEmpty(), onOpenPage) }
                    t.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                    if (!t.running) t.sources?.let { SourceChips(it, onOpenPage) }
                }
            }
        }
        OutlinedTextField(
            value = q, onValueChange = { if (it.length <= (st?.qMax ?: 1000)) q = it },
            placeholder = { Text(stringResource(R.string.ai_ask_hint)) }, maxLines = 4,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = tasks, onClick = { tasks = !tasks }, label = { Text(stringResource(R.string.ai_include_tasks)) })
            FilterChip(selected = calendar, onClick = { calendar = !calendar }, label = { Text(stringResource(R.string.ai_include_calendar)) })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            if (running) {
                OutlinedButton(onClick = { job?.cancel(); turns.lastOrNull()?.running = false }) { Text(stringResource(R.string.ai_stop)) }
            } else {
                Button(onClick = {
                    val t = AskTurn(q.trim())
                    turns.add(t)
                    q = ""
                    job = scope.launch {
                        try {
                            repo.ai.ask(t.q, tasks, calendar).collect { c ->
                                when (c) {
                                    is AiChunk.Sources -> t.sources = c.sources
                                    is AiChunk.Delta -> t.answer += c.text
                                    AiChunk.Reset -> t.answer = ""
                                    is AiChunk.Done -> updateQuota(repo, c.done)
                                    is AiChunk.Failed -> {
                                        t.error = c.error
                                        if (!c.partial) t.answer = ""
                                    }
                                }
                                list.animateScrollToItem(turns.size - 1)
                            }
                        } finally {
                            t.running = false
                        }
                    }
                }, enabled = q.isNotBlank()) { Text(stringResource(R.string.ai_ask)) }
            }
        }
    }
}

/** "{name} denkt nach …" mit Persona (2.1), sonst wie bisher "Denkt nach …" */
@Composable
fun thinkingText(persona: AiPersona?): String =
    if (persona != null) stringResource(R.string.ai_thinking_name, persona.name) else stringResource(R.string.ai_thinking)

private fun updateQuota(repo: SagaRepository, d: AiDone) {
    val st = repo.ai.status.value ?: return
    if (d.quotaLeft != null) repo.ai.status.value = st.copy(quotaLeft = d.quotaLeft)
}

@Composable
private fun PageTab(repo: SagaRepository, ed: PageEditor, st: AiStatus?, onOpenPage: (Long) -> Unit) {
    val context = LocalContext.current
    val blocks = remember(ed.doc) { ed.textBlocks() }
    // Vorauswahl: Block mit dem Cursor
    val activeId = remember { ed.active?.blockPath?.invoke()?.let { p -> (p.getOrNull(1) as? Int)?.let { i -> io.github.yggdrasil_sh.saga.blocks.BlockDoc.id(io.github.yggdrasil_sh.saga.blocks.BlockDoc.blocks(ed.doc).getOrNull(i)) } } }
    var selected by remember { mutableStateOf(setOfNotNull(activeId)) }
    var pickBlocks by remember { mutableStateOf(false) }
    val targets = st?.targets.orEmpty()
    var target by remember { mutableStateOf(targets.firstOrNull { it != Locale.getDefault().language } ?: targets.firstOrNull() ?: "en") }
    var targetMenu by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<UiText?>(null) }
    var done by remember { mutableStateOf<AiDone?>(null) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val taskOn = remember { mutableStateListOf<Boolean>() }
    var confirmReplace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canEdit = ed.caps.canEditContent && !ed.locked

    fun run(a: String) {
        action = a
        text = ""
        err = null
        done = null
        taskOn.clear()
        running = true
        job = scope.launch {
            try {
                if (a == "improve" || a == "translate") ed.saveAndWait()       // Server liest den gespeicherten Stand
                val flow = when (a) {
                    "improve" -> repo.ai.page(ed.id, a, selected.toList())
                    "translate" -> repo.ai.page(ed.id, a, selected.toList(), target)
                    else -> repo.ai.page(ed.id, a)
                }
                flow.collect { c ->
                    when (c) {
                        is AiChunk.Delta -> text += c.text
                        AiChunk.Reset -> text = ""
                        is AiChunk.Done -> {
                            done = c.done
                            c.done.tasks?.let { ts -> taskOn.clear(); taskOn.addAll(ts.map { true }) }
                            updateQuota(repo, c.done)
                        }
                        is AiChunk.Failed -> {
                            err = c.error
                            if (!c.partial) text = ""
                        }
                        is AiChunk.Sources -> {}
                    }
                }
            } finally {
                running = false
            }
        }
    }

    Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { run("summarize") }, enabled = !running) { Text(stringResource(R.string.ai_summarize)) }
            OutlinedButton(onClick = { run("tasks") }, enabled = !running) { Text(stringResource(R.string.ai_suggest_tasks)) }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { run("improve") }, enabled = !running && selected.isNotEmpty()) { Text(stringResource(R.string.ai_improve)) }
            OutlinedButton(onClick = { run("translate") }, enabled = !running && selected.isNotEmpty()) { Text(stringResource(R.string.ai_translate_to)) }
            Box {
                // Zielsprache (aus status.targets)
                AssistChip(
                    onClick = { targetMenu = true }, enabled = targets.isNotEmpty(),
                    label = { Text(Locale.forLanguageTag(target).getDisplayLanguage(Locale.getDefault()).ifEmpty { target } + " ▾") },
                )
                DropdownMenu(expanded = targetMenu, onDismissRequest = { targetMenu = false }) {
                    targets.forEach { t ->
                        DropdownMenuItem(text = { Text(Locale.forLanguageTag(t).getDisplayLanguage(Locale.getDefault()).ifEmpty { t } + " ($t)") }, onClick = { target = t; targetMenu = false })
                    }
                }
            }
        }
        TextButton(onClick = { pickBlocks = !pickBlocks }) { Text(stringResource(R.string.ai_selected_blocks, selected.size)) }
        if (pickBlocks) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                blocks.forEach { (bid, t) ->
                    val on = bid in selected
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Checkbox) { v ->
                            selected = if (v) (selected + bid).take(st?.blocksMax ?: 50).toSet() else selected - bid
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val persona = AiPersonaLogic.effective(st)
        if (persona != null && (running || text.isNotEmpty())) Box(Modifier.padding(bottom = 6.dp)) { AiWho(persona, persona.name) }
        if (running && text.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(thinkingText(persona), modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (running) TextButton(onClick = { job?.cancel(); running = false }) { Text(stringResource(R.string.ai_stop)) }
        val d = done
        val ts = d?.tasks
        if (action == "tasks" && ts != null) {
            ts.forEachIndexed { i, t ->
                Row(
                    Modifier.fillMaxWidth().toggleable(value = taskOn.getOrElse(i) { true }, role = Role.Checkbox) { v -> if (i < taskOn.size) taskOn[i] = v },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = taskOn.getOrElse(i) { true }, onCheckedChange = null)
                    Text(t, modifier = Modifier.padding(start = 8.dp))
                }
            }
            val chosen = ts.filterIndexed { i, _ -> taskOn.getOrElse(i) { true } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = {
                    repo.launchAction {
                        chosen.forEach { repo.addTask(it, null) }
                        repo.notify(UiText.Plural(R.plurals.ai_tasks_added, chosen.size))
                    }
                }, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.ai_add_tasks)) }
                if (canEdit) OutlinedButton(onClick = { ed.appendChecklist(chosen) }, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.ai_insert_checklist)) }
            }
        } else if (text.isNotEmpty()) {
            AnswerText(text, emptyList(), onOpenPage)
            if (d != null) {
                val segs = d.segments
                val bl = d.blocks
                val replaceable = canEdit && segs != null && bl != null && bl.size == segs.size && bl.all { it.replaceable }
                FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if ((action == "improve" || action == "translate") && replaceable) {
                        Button(onClick = { confirmReplace = true }) { Text(stringResource(R.string.ai_replace)) }
                    }
                    if ((action == "improve" || action == "translate") && canEdit) {
                        OutlinedButton(onClick = { ed.insertParagraphsAfter(bl?.lastOrNull()?.id ?: selected.lastOrNull(), text) }) { Text(stringResource(R.string.ai_insert_below)) }
                    }
                    OutlinedButton(onClick = { copyText(context, text); repo.notify(UiText.Res(R.string.text_copied)) }) { Text(stringResource(R.string.text_copy)) }
                }
            }
        }
        if (d?.truncated == true) Text(stringResource(R.string.ai_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        err?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.ai_replace_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    val d = done
                    val bl = d?.blocks.orEmpty()
                    val segs = d?.segments.orEmpty()
                    ed.replaceBlockTexts(bl.zip(segs).associate { (b, s) -> b.id to s })
                }) { Text(stringResource(R.string.ai_replace)) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Verlauf (nur wenn erlaubt): speichern an/aus (aus = loeschen, mit Rueckfrage), Eintraege entfernen */
@Composable
private fun HistoryDialog(repo: SagaRepository, st: AiStatus?, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf<List<AiHistoryItem>>(emptyList()) }
    var on by remember { mutableStateOf(st?.history == true) }
    var confirmOff by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        try {
            val h = repo.ai.history()
            items = h.items
            on = h.history
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_history)) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch) { v ->
                        if (v) repo.launchAction { repo.ai.setHistory(true); on = true } else confirmOff = true
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.ai_save_history), modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = null)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(items, key = { it.id }) { h ->
                        ListItem(
                            headlineContent = { Text(h.q, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(h.answer, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                IconButton(onClick = { repo.launchAction { repo.ai.deleteHistory(h.id); items = items.filter { it.id != h.id } } }) {
                                    Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.ai_history_off_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    repo.launchAction { repo.ai.setHistory(false); on = false; items = emptyList() }
                }) { Text(stringResource(R.string.ai_history_off), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
