package io.github.yggdrasil_sh.saga.ui.saga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ai.AiPersonaLogic
import io.github.yggdrasil_sh.saga.ai.AiStatus
import io.github.yggdrasil_sh.saga.ai.SagaLogic
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.ai.AiAvatar
import io.github.yggdrasil_sh.saga.ui.ai.AnswerText
import io.github.yggdrasil_sh.saga.ui.ai.thinkingText
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.resolve

/** Zustand des Orts fuer das angemeldete Konto */
@Composable
fun rememberSagaSession(repo: SagaRepository): SagaSession {
    val server by repo.server.collectAsState()
    val account by repo.account.collectAsState()
    return remember(server, account) { SagaSessions.of(repo, "$server|$account") }
}

/** Uhrzeit eines Zugs (Sekunden seit 1970) in der Form des Geraets */
@Composable
fun clockText(sec: Double?): String? {
    sec ?: return null
    val ctx = LocalContext.current
    return android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date((sec * 1000).toLong()))
}

/** Kopfzeile des Bereichs: Persona-Bild + "{Persona} · KI" (K11); Zurueck bzw. Menue wie die anderen Bildschirme */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SagaAreaBar(st: AiStatus?, nav: AppNavigator, chrome: Chrome, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiAvatar(AiPersonaLogic.effective(st), 32)
                Text(sagaLabel(st), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
            }
        },
        navigationIcon = {
            when {
                nav.canGoBack -> IconButton(onClick = { nav.pop() }) { SagaIcon(SagaIcons.arrowLeft, stringResource(R.string.action_back)) }
                chrome.showMenu -> IconButton(onClick = chrome.onMenu) { SagaIcon(SagaIcons.menu, stringResource(R.string.action_sidebar)) }
            }
        },
        actions = {
            actions()
            if (!nav.canGoBack) chrome.headActions?.invoke(this)
        },
    )
}

/**
 * Bereich "Saga" (K10, M1): eigenes Navigationsziel, nicht in der Chat-Liste. Oben die feste Leiste B6, vor der
 * Einwilligung nur die Kennenlernen-Karte (nichts geht ans Modell), danach Reiter Gespraech / Auftraege (Auftraege nur,
 * wenn der Server sie anbietet). Ab 840 dp Breite stehen beide nebeneinander (Gespraech links, Auftraege rechts).
 */
@Composable
fun SagaScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    val st by repo.ai.status.collectAsState()
    val session = rememberSagaSession(repo)
    var loaded by remember { mutableStateOf(st != null) }
    LaunchedEffect(Unit) {
        repo.ai.loadStatus()
        loaded = true
    }
    // ohne Faehigkeit (Admin hat umgestellt, aelterer Server): den Bereich gibt es nicht - zurueck nach "Heute"
    LaunchedEffect(loaded, SagaLogic.available(st)) { if (loaded && st != null && !SagaLogic.available(st)) nav.top(Dest.Today) }
    val consent = SagaLogic.consentOk(st)
    var menu by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        SagaAreaBar(st, nav, chrome) {
            if (consent) {
                Box {
                    IconButton(onClick = { menu = true }) { SagaIcon(SagaIcons.moreVert, stringResource(R.string.saga_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.saga_clear)) }, onClick = { menu = false; askClear = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.saga_settings_link)) }, onClick = { menu = false; nav.push(Dest.SettingsSaga) })
                    }
                }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            val s = st
            if (s == null || !SagaLogic.available(s)) {
                Box(Modifier.fillMaxSize())
                return@Column
            }
            SagaBar(s)
            var tab by rememberSaveable { mutableIntStateOf(0) }
            // 2.6 (2271): „Wartet auf dich“ aus der Tageszeile -> Reiter „Aufträge“
            val wantJobs by repo.todayLine.wantJobs.collectAsState()
            LaunchedEffect(wantJobs) { if (wantJobs) { tab = 1; repo.todayLine.wantJobs.value = false } }
            if (!consent) {
                // 2518 A1: ohne Einwilligung nur die Karte - hat die Person Auftraege, bleibt der Reiter "Auftraege"
                // erreichbar (Liste, Detail, Abbrechen, Rueckgaengig); Gespraech und "Als Auftrag" bleiben gesperrt
                LaunchedEffect(s.consent?.state) { if (SagaLogic.jobsOn(s)) session.refreshJobs() }
                val paused = SagaLogic.pausedJobsShown(s, session.groups?.let { SagaLogic.total(it) } ?: 0)
                val intro: @Composable () -> Unit = {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        IntroCard(
                            repo, s, Modifier.widthIn(max = 640.dp),
                            onLater = { nav.top(Dest.Today) },
                            onMore = { nav.push(Dest.SettingsSaga) },
                        )
                    }
                }
                if (!paused) {
                    intro()
                    return@Column
                }
                val name = sagaName(s)
                val note = when (SagaLogic.pausedNote(s)) {
                    SagaLogic.PausedNote.WITHDRAWN -> stringResource(R.string.saga_paused_withdrawn, name)
                    SagaLogic.PausedNote.STALE -> stringResource(R.string.saga_paused_stale, name)
                    SagaLogic.PausedNote.NOT_MET -> stringResource(R.string.saga_paused_not_met, name)
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth >= 840.dp) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { intro() }
                            VerticalDivider()
                            Box(Modifier.width(420.dp).fillMaxHeight()) { SagaJobsPane(repo, nav, s, session, onGive = {}, note = note) }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            PrimaryTabRow(selectedTabIndex = tab) {
                                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.saga_tab_talk)) }, modifier = Modifier.heightIn(min = 48.dp))
                                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.saga_tab_jobs)) }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                            Box(Modifier.weight(1f)) {
                                if (tab == 1) SagaJobsPane(repo, nav, s, session, onGive = {}, note = note) else intro()
                            }
                        }
                    }
                }
                return@Column
            }
            val jobsOn = SagaLogic.jobsOn(s)
            if (!jobsOn && session.asJob) session.asJob = false            // Server bietet keine Auftraege (mehr)
            val thinking = thinkingText(AiPersonaLogic.effective(s))
            val ready = stringResource(R.string.saga_plan_ready)
            // Beispiel aus der leeren Auftrags-Liste: im Gespraech als Auftrag geben (11.7: ein Ort fuer die Eingabe)
            val giveFromList: (String) -> Unit = { text -> tab = 0; session.asJob = true; session.giveInThread(text, thinking = thinking, ready = ready) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                if (wide && jobsOn) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { SagaTalk(repo, nav, s, session) }
                        VerticalDivider()
                        Box(Modifier.width(420.dp).fillMaxHeight()) { SagaJobsPane(repo, nav, s, session, giveFromList) }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (jobsOn) {
                            PrimaryTabRow(selectedTabIndex = tab) {
                                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.saga_tab_talk)) }, modifier = Modifier.heightIn(min = 48.dp))
                                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.saga_tab_jobs)) }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            if (tab == 1 && jobsOn) SagaJobsPane(repo, nav, s, session, giveFromList) else SagaTalk(repo, nav, s, session)
                        }
                    }
                }
            }
        }
    }
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text(stringResource(R.string.saga_clear_q)) },
            text = { Text(stringResource(R.string.saga_clear_text)) },
            confirmButton = {
                SagaTextButton(onClick = {
                    askClear = false
                    session.clearThread { repo.notify(repo.friendly(it)) }
                }) { Text(stringResource(R.string.saga_clear_ok), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { SagaTextButton(onClick = { askClear = false }) { Text(stringResource(R.string.saga_cancel)) } },
        )
    }
}

/** Gespraech (K10): Verlauf aus KI-Karten, Eingabe unten; Antworten als Strom in der KI-Markdown-Darstellung (2248) */
@Composable
fun SagaTalk(repo: SagaRepository, nav: AppNavigator, st: AiStatus, session: SagaSession) {
    LaunchedEffect(session) { if (!session.talkLoaded) session.loadThread() }
    val list = rememberLazyListState()
    val name = sagaName(st)
    val n = session.turns.size
    LaunchedEffect(n, session.turns.lastOrNull()?.text?.length) { if (n > 0) list.animateScrollToItem(n - 1) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list, horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session.talkLoaded && session.turns.isEmpty()) {
                item(key = "empty") { TalkEmpty(st) { session.ask(it) } }
            }
            itemsIndexed(session.turns, key = { i, _ -> i }) { _, t ->
                Box(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                    when {
                        t.me -> MeBubble(t.text, job = t.zug == "job")
                        t.jobTurn -> JobTurnView(repo, nav, st, session, t)
                        else -> AiTurnCard(repo, st, t) { id -> nav.push(Dest.Page(id)) }
                    }
                }
            }
            session.talkError?.let { e ->
                item(key = "err") { Text(e.resolve(), color = MaterialTheme.colorScheme.error) }
            }
            item(key = "keep") {
                Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (session.history) R.string.saga_keep_on else R.string.saga_keep_off),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false),
                    )
                    SagaTextButton(onClick = { nav.push(Dest.SettingsSaga) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_change)) }
                }
            }
        }
        val jobsOn = SagaLogic.jobsOn(st)
        val job = jobsOn && session.asJob
        val thinking = thinkingText(AiPersonaLogic.effective(st))
        val ready = stringResource(R.string.saga_plan_ready)
        val submit = {
            if (job) session.giveInThread(session.draft, thinking = thinking, ready = ready) else session.ask(session.draft)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // 11.7: Umschalter "Als Auftrag" - kein Raten, ob ein Satz ein Auftrag ist
                if (jobsOn) {
                    FilterChip(
                        selected = job, onClick = { session.asJob = !session.asJob }, enabled = !session.talking,
                        label = { Text(stringResource(R.string.saga_as_job)) },
                        leadingIcon = if (job) ({ SagaIcon(SagaIcons.check, null) }) else null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = stringResource(if (job) R.string.saga_job_label else R.string.saga_write_label, name)
                    val max = if (job) 1000 else (st.qMax.takeIf { q -> q > 0 } ?: 1000)
                    OutlinedTextField(
                        value = session.draft, onValueChange = { if (it.length <= max) session.draft = it },
                        placeholder = { Text(stringResource(if (job) R.string.saga_jobs_hint else R.string.saga_write_hint, name)) },
                        maxLines = 5, enabled = !session.talking,
                        modifier = Modifier.weight(1f).semantics { contentDescription = label },
                    )
                    if (session.talking) {
                        SagaOutlinedButton(onClick = { session.stopTalk() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_stop)) }
                    } else {
                        SagaButton(onClick = submit, enabled = session.draft.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (job) R.string.saga_give else R.string.saga_send))
                        }
                    }
                }
                if (session.live.isNotBlank()) {
                    Text(
                        session.live, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

@Composable
private fun TalkEmpty(st: AiStatus, onAsk: (String) -> Unit) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AiAvatar(AiPersonaLogic.effective(st), 56)
        Text(stringResource(R.string.saga_empty_lead), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        val ex = listOf(stringResource(R.string.saga_ex1), stringResource(R.string.saga_ex2), stringResource(R.string.saga_ex3))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            ex.forEach { q -> AssistChip(onClick = { onAsk(q) }, label = { Text(q) }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
    }
}

@Composable
private fun MeBubble(text: String, job: Boolean = false) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Bitte als Auftrag: kleine Marke "Auftrag" (Systemtext)
                if (job) Text(stringResource(R.string.saga_job), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** KI-Zug als Karte (B1 + Antwort + "aus:" Quellen); laufend: "{name} denkt nach …" */
@Composable
private fun AiTurnCard(repo: SagaRepository, st: AiStatus, t: TalkTurn, onOpenPage: (Long) -> Unit) {
    SagaCard {
        SagaHead(st, 24, clockText(t.created))
        when {
            t.text.isEmpty() && t.running -> Text(
                thinkingText(AiPersonaLogic.effective(st)), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            t.running -> Text(t.text, style = MaterialTheme.typography.bodyLarge)        // waehrend des Stroms als Klartext
            t.text.isNotEmpty() -> AnswerText(t.text, t.sources, onOpenPage)
        }
        t.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        val src = t.sources.filter { it.id != null }
        if (!t.running && src.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    stringResource(R.string.saga_from), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically),
                )
                src.forEach { s ->
                    AssistChip(
                        onClick = { s.id?.let(onOpenPage) },
                        label = { Text(s.title.ifBlank { "[${s.n}]" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 320.dp),
                    )
                }
            }
        }
        // 2.6 (Bugs-DB 2507): fertige Antwort melden (nicht "nichts gefunden" - die kommt ohne Modell)
        if (!t.running && t.text.isNotEmpty() && t.zug != "no_match" && t.error == null) {
            io.github.yggdrasil_sh.saga.ui.ai.AiReportButton(repo, io.github.yggdrasil_sh.saga.ai.AiReportLogic.ask(t.text))
        }
    }
}
