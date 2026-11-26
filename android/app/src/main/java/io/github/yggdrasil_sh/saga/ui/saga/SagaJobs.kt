package io.github.yggdrasil_sh.saga.ui.saga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ai.AiPersonaLogic
import io.github.yggdrasil_sh.saga.ai.AiStatus
import io.github.yggdrasil_sh.saga.ai.JobBefore
import io.github.yggdrasil_sh.saga.ai.JobDetail
import io.github.yggdrasil_sh.saga.ai.JobGroup
import io.github.yggdrasil_sh.saga.ai.JobPlan
import io.github.yggdrasil_sh.saga.ai.PlanStep
import io.github.yggdrasil_sh.saga.ai.SagaLogic
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.NetworkState
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.ai.AnswerText
import io.github.yggdrasil_sh.saga.ui.ai.thinkingText
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.dateTimeText
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Auftraege der Kollegin in der App (Bugs-DB 2267 K-B "Rueckmeldung in der App", Konzept K1/K2/K3, UX R3/R4/R6):
 * Plan-Karte (einziges Freigabe-Muster "Passt, los · Aendern · Lieber nicht"), Rueckmeldung (Ergebnis zuerst +
 * Rueckgaengig je Aenderung), Liste in 4 Gruppen und Detail mit genauem Zustandswort und Protokoll. Zustandswoerter,
 * Klassen, Gruende und Saetze kommen als Systemtext/Baustein vom Server - die App uebersetzt sie nie selbst.
 */

private fun s(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

@Composable
private fun rememberOnline(): Boolean {
    val ctx = LocalContext.current.applicationContext
    val on by remember(ctx) { NetworkState.flow(ctx) }.collectAsState(initial = true)
    return on
}

/** Reiter "Auftraege": Eingabe, Buehne (Plan-Karte, Rueckfrage, Absage, Rueckmeldung) und Liste in 4 Gruppen */
@Composable
fun SagaJobsPane(repo: SagaRepository, nav: AppNavigator, st: AiStatus, session: SagaSession) {
    LaunchedEffect(session) { session.refreshJobs() }
    val name = sagaName(st)
    val thinking = thinkingText(AiPersonaLogic.effective(st))
    LazyColumn(
        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
        item(key = "give") {
            Column(w, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val hint = stringResource(R.string.saga_jobs_hint, name)
                OutlinedTextField(
                    value = session.jobDraft, onValueChange = { if (it.length <= 1000) session.jobDraft = it },
                    placeholder = { Text(hint) }, maxLines = 4, enabled = !session.giving,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (session.giving) {
                        SagaOutlinedButton(onClick = { session.stopGive() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_stop)) }
                    } else {
                        SagaButton(
                            onClick = { session.give(session.jobDraft, thinking = thinking) }, enabled = session.jobDraft.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.saga_give)) }
                    }
                }
                // TalkBack: Statuswechsel hoeflich (role=status)
                if (session.live.isNotBlank()) {
                    Text(
                        session.live, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        session.stage?.let { stage ->
            item(key = "stage") { Box(w) { JobStageView(repo, nav, st, session, stage) } }
        }
        val groups = session.groups
        session.jobsError?.let { e -> item(key = "jerr") { Text(e.resolve(), color = MaterialTheme.colorScheme.error, modifier = w) } }
        if (groups != null) {
            if (SagaLogic.total(groups) == 0) {
                item(key = "jempty") {
                    Column(w, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.saga_jobs_empty), style = MaterialTheme.typography.bodyLarge)
                        val ex = stringResource(R.string.saga_jobs_ex)
                        AssistChip(onClick = { session.jobDraft = ex; session.give(ex, thinking = thinking) }, label = { Text(ex) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            } else {
                groups.filter { it.items.isNotEmpty() }.forEach { g ->
                    item(key = "g-" + g.key) { GroupHead(g, w) }
                    items(g.items, key = { "j-" + it.id }) { it ->
                        JobRow(g.key, it.title.ifBlank { stringResource(R.string.saga_job) }, SagaLogic.lineText(it), w) { nav.push(Dest.SagaJob(it.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun groupLabel(g: JobGroup): String = g.label.ifBlank {
    stringResource(
        when (g.key) {
            "laeuft" -> R.string.saga_g_run
            "fertig" -> R.string.saga_g_done
            "nicht_geklappt" -> R.string.saga_g_fail
            else -> R.string.saga_g_wait
        },
    )
}

@Composable
private fun GroupHead(g: JobGroup, modifier: Modifier) {
    Row(modifier.padding(top = 8.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Text(groupLabel(g), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("  " + g.items.size, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** B5 Auftrags-Zeile: Symbol · Titel · Nebenzeile (genaues Wort) · › ; 48 dp, Rolle Knopf */
@Composable
private fun JobRow(group: String, title: String, line: String, modifier: Modifier, onClick: () -> Unit) {
    val fail = group == "nicht_geklappt"
    val done = group == "fertig"
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                SagaLogic.groupSymbol(group), modifier = Modifier.width(28.dp).clearAndSetSemantics { },
                color = when {
                    fail -> MaterialTheme.colorScheme.error
                    group == "wartet_auf_dich" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(line, style = MaterialTheme.typography.bodyMedium, color = if (fail) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SagaIcon(SagaIcons.chevronRight, null)
        }
    }
}

/** Buehne nach "Auftrag geben" */
@Composable
private fun JobStageView(repo: SagaRepository, nav: AppNavigator, st: AiStatus, session: SagaSession, stage: JobStage) {
    val thinking = thinkingText(AiPersonaLogic.effective(st))
    when (stage) {
        is JobStage.Thinking -> SagaCard {
            SagaHead(st)
            Text(stage.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        is JobStage.Plan -> {
            val ready = stringResource(R.string.saga_plan_ready)
            LaunchedEffect(stage.plan.job, stage.plan.planV) { session.live = ready }
            PlanCard(
                repo, st, stage.plan,
                onPlan = { p -> session.stage = JobStage.Plan(p); session.refreshJobs() },
                onDone = { job, stateText, text, approved ->
                    session.stage = JobStage.Small(job, stateText, text)
                    session.live = text.ifBlank { stateText }
                    if (approved) session.watch(job)
                    session.refreshJobs()
                },
            )
        }
        is JobStage.Ask -> SagaCard {
            SagaHead(st)
            Text(stage.question, style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stage.choices.forEach { c ->
                    AssistChip(onClick = { session.give(stage.text, choice = c, thinking = thinking) }, label = { Text(c) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        is JobStage.Refuse -> SagaCard {
            SagaHead(st)
            Text(stage.text, style = MaterialTheme.typography.bodyLarge)
            stage.alternative?.let { alt ->
                SagaButton(
                    onClick = { session.give(stage.request, recipe = alt, thinking = thinking) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(alt.label ?: stringResource(R.string.saga_as_task)) }
            }
            SagaTextButton(onClick = { session.stage = null; session.jobDraft = stage.request }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.saga_rephrase))
            }
        }
        is JobStage.Failed -> SagaCard {
            SagaHead(st)
            Text(
                stage.error.ifBlank { stringResource(R.string.saga_failed) }, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        is JobStage.Small -> SagaCard(Modifier.clickable(role = Role.Button) { nav.push(Dest.SagaJob(stage.job)) }) {
            SagaHead(st, extra = stage.stateText)
            if (stage.text.isNotBlank()) Text(stage.text, style = MaterialTheme.typography.bodyLarge)
        }
        is JobStage.Feedback -> ReportCard(repo, st, stage.detail, onOpen = { nav.push(Dest.SagaJob(stage.detail.id)) }) {
            session.scope.launch {
                try {
                    session.stage = JobStage.Feedback(repo.saga.job(stage.detail.id))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                session.refreshJobs()
            }
        }
    }
}

/** Ein Schritt (B3): Art-Symbol + Text + Klasse als Wort; TalkBack "Schritt 1 von 3, …, rueckgaengig: …" */
@Composable
private fun StepRow(i: Int, n: Int, step: PlanStep, showWording: Boolean) {
    val desc = stringResource(R.string.saga_step_a11y, i + 1, n, step.kindText, step.classText, step.text)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = desc }, verticalAlignment = Alignment.Top) {
            Text(SagaLogic.kindSymbol(step.klass), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(step.text, style = MaterialTheme.typography.bodyLarge)
                if (step.classText.isNotBlank()) {
                    Text(step.classText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val wd = step.wording
        if (showWording && !wd.isNullOrBlank()) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 6.dp)) {
                Box(Modifier.padding(10.dp)) { AnswerText(wd, emptyList()) {} }
            }
        }
    }
}

@Composable
private fun fieldLabel(k: String): String = stringResource(
    when (k) {
        "text" -> R.string.saga_f_text
        "due" -> R.string.saga_f_due
        "markdown" -> R.string.saga_f_markdown
        "summary", "title" -> R.string.saga_f_title
        "start" -> R.string.saga_f_start
        "end" -> R.string.saga_f_end
        "location" -> R.string.saga_f_location
        else -> R.string.saga_f_allday
    },
)

/**
 * B3 Plan-Karte: "Wartet auf dein OK" · Satz · Schritte · Entwurf · "Ich lese dafuer: …" · Passt, los (volle Breite) ·
 * Aendern · Lieber nicht (Textknoepfe, Abstand). Freigabe nur mit plan_v + plan_hash; 409 stale -> neuer Plan.
 */
@Composable
fun PlanCard(
    repo: SagaRepository, st: AiStatus, plan: JobPlan,
    onPlan: (JobPlan) -> Unit, onDone: (job: Long, stateText: String, text: String, approved: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val online = rememberOnline()
    var editing by remember(plan.job, plan.planV) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(plan.job, plan.planV) { mutableStateOf<UiText?>(null) }
    val values = remember(plan.job, plan.planV) { mutableStateMapOf<Int, Map<String, String>>() }
    fun fail(e: Exception) {
        error = repo.friendly(e)
    }
    SagaCard {
        SagaHead(st, extra = stringResource(R.string.saga_wait_ok))
        if (plan.edited) Text(stringResource(R.string.saga_edited), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(plan.say?.takeIf { it.isNotBlank() } ?: stringResource(R.string.saga_plan_lead), style = MaterialTheme.typography.bodyLarge)
        plan.steps.forEachIndexed { i, step ->
            StepRow(i, plan.steps.size, step, showWording = !(editing && step.edit.isNotEmpty()))
            if (editing && step.edit.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    step.edit.forEach { k ->
                        val cur = values[i]?.get(k) ?: SagaLogic.fieldValue(step, k)
                        fun set(v: String) {
                            values[i] = (values[i] ?: emptyMap()) + (k to v)
                        }
                        val label = fieldLabel(k)
                        if (k == "allday") {
                            val on = cur == "true"
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Switch) { set((!on).toString()) }
                                    .semantics(mergeDescendants = true) { toggleableState = ToggleableState(on) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, modifier = Modifier.weight(1f))
                                Switch(checked = on, onCheckedChange = null)
                            }
                        } else {
                            OutlinedTextField(
                                value = cur, onValueChange = { set(it) }, label = { Text(label) },
                                singleLine = k != "markdown" && k != "text", maxLines = if (k == "markdown") 6 else 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        if (plan.readText.isNotBlank()) Text(plan.readText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SagaButton(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val p = repo.saga.editPlan(plan, values.toMap())
                            editing = false
                            onPlan(p)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            fail(e)
                            if (e is ApiException && e.code == 409) reload(repo, plan.job, onPlan)
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy && values.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_save)) }
                SagaTextButton(onClick = { editing = false; values.clear() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_cancel)) }
            }
        } else {
            if (!online) Text(stringResource(R.string.saga_offline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SagaButton(
                onClick = {
                    if (!SagaLogic.approvable(plan)) {
                        error = UiText.Res(R.string.saga_failed)
                        return@SagaButton
                    }
                    busy = true
                    scope.launch {
                        try {
                            val r = repo.saga.approve(plan)
                            onDone(plan.job, s(r, "state_text"), s(r, "say"), true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            fail(e)
                            if (e is ApiException && e.code == 409) reload(repo, plan.job, onPlan)   // stale: neuer Plan, neue Freigabe
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && online, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.saga_go_ahead)) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (SagaLogic.canEdit(plan)) {
                    SagaTextButton(onClick = { editing = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_edit)) }
                }
                Spacer(Modifier.weight(1f))
                SagaTextButton(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val r = repo.saga.cancel(plan.job)
                            onDone(plan.job, s(r, "state_text"), s(r, "text"), false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            fail(e)
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_no)) }
            }
        }
        // 2.6 (Bugs-DB 2507): Plan der KI melden (where=job, page = Auftrag)
        io.github.yggdrasil_sh.saga.ui.ai.AiReportButton(repo, io.github.yggdrasil_sh.saga.ai.AiReportLogic.job(plan.job, io.github.yggdrasil_sh.saga.ai.AiReportLogic.planText(plan)))
    }
}

/** 409: Plan neu laden - wartet er noch auf das OK, steht der neue Plan (neuer Hash) da */
private suspend fun reload(repo: SagaRepository, job: Long, onPlan: (JobPlan) -> Unit) {
    try {
        val d = repo.saga.job(job)
        val p = d.plan
        if (d.state == "wartet_ok" && p != null && p.steps.isNotEmpty()) onPlan(p)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

/**
 * B4 Rueckmeldung: Ergebnis zuerst, je Aenderung eine Zeile mit Knopf "Rueckgaengig" (48 dp). "nur loeschbar" fragt vorher;
 * 409 changed -> "Das wurde seitdem geaendert." + "Alte Fassung ansehen" (nie still ueberschreiben).
 */
@Composable
fun ReportCard(repo: SagaRepository, st: AiStatus, d: JobDetail, onOpen: (() -> Unit)? = null, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rep = d.report
    var confirm by remember { mutableStateOf<Long?>(null) }
    val conflicts = remember(d.id) { mutableStateMapOf<Long, String?>() }
    var busy by remember { mutableStateOf<Long?>(null) }
    var old by remember { mutableStateOf<JobBefore?>(null) }
    val undoneText = stringResource(R.string.saga_undone)
    fun undo(change: Long) {
        busy = change
        scope.launch {
            try {
                repo.saga.undo(d.id, change)
                repo.notify(UiText.Raw(undoneText))
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val c = (e as? ApiException)?.takeIf { it.code == 409 }?.let { SagaLogic.conflict(it.body) }
                if (c?.code == "changed") conflicts[change] = c.versionUrl else repo.notify(repo.friendly(e))
            } finally {
                busy = null
            }
        }
    }
    fun showOld(url: String) {
        scope.launch {
            try {
                old = repo.saga.before(d.id, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(repo.friendly(e))
            }
        }
    }
    SagaCard {
        SagaHead(st, extra = d.stateText)
        AnswerText(rep?.text?.ifBlank { null } ?: d.reasonText?.ifBlank { null } ?: d.stateText, emptyList()) {}
        rep?.lines?.forEach { ln ->
            val ch = d.changes.firstOrNull { it.id == ln.change }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ln.text, style = MaterialTheme.typography.bodyLarge)
                val conflictUrl = conflicts[ln.change]
                when {
                    ln.undone -> Text(undoneText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ln.change in conflicts || ch?.why == "changed" -> ChangedNote(d.id, conflictUrl ?: ch?.versionUrl, ::showOld)
                    ln.canUndo -> SagaOutlinedButton(
                        onClick = { if (ch?.undoClass == "delete_only") confirm = ln.change else undo(ln.change) },
                        enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.saga_undo)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onOpen != null) SagaTextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_view)) }
            rep?.text?.takeIf { it.isNotBlank() }?.let { tx ->
                io.github.yggdrasil_sh.saga.ui.ai.AiReportButton(repo, io.github.yggdrasil_sh.saga.ai.AiReportLogic.job(d.id, tx))
            }
        }
    }
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.saga_delete_again_q)) },
            text = { Text(stringResource(R.string.saga_delete_again_text)) },
            confirmButton = {
                SagaTextButton(onClick = { confirm = null; undo(c) }) { Text(stringResource(R.string.saga_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { SagaTextButton(onClick = { confirm = null }) { Text(stringResource(R.string.saga_keep)) } },
        )
    }
    old?.let { o ->
        InfoSheet(stringResource(R.string.saga_old), listOfNotNull(o.title.ifBlank { null }, o.text)) { old = null }
    }
}

@Composable
private fun ChangedNote(job: Long, url: String?, onOld: (String) -> Unit) {
    Column {
        Text(stringResource(R.string.saga_changed_since), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null && SagaLogic.beforePathOk(url, job)) {
            SagaTextButton(onClick = { onOld(url) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_old_view)) }
        }
    }
}

/** Auftrags-Detail: genaues Zustandswort + Grund, Plan (bzw. Plan-Karte), Rueckmeldung, Gelesenes, Protokoll, Abbrechen */
@Composable
fun SagaJobScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome, id: Long) {
    val st by repo.ai.status.collectAsState()
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<JobDetail?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    val session = rememberSagaSession(repo)
    fun load() {
        scope.launch {
            try {
                d = repo.saga.job(id)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = repo.friendly(e)
            }
        }
    }
    LaunchedEffect(id) { load() }
    LaunchedEffect(id) {
        repo.live.events.collect { ev -> if (ev.type == "ai.job.changed" && ev.long("id") == id) load() }
    }
    val title = d?.title?.ifBlank { null } ?: stringResource(R.string.saga_job)
    Scaffold(topBar = { io.github.yggdrasil_sh.saga.ui.common.SagaTopBar(title, nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) }) { pad ->
        val s = st
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                val x = d ?: return@Column
                Text(
                    x.stateText + (x.reasonText?.takeIf { it.isNotBlank() && it != x.stateText }?.let { " – $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                val plan = x.plan
                if (x.state == "wartet_ok" && plan != null && plan.steps.isNotEmpty() && s != null) {
                    PlanCard(repo, s, plan, onPlan = { p -> d = x.copy(plan = p) }, onDone = { job, _, _, approved ->
                        if (approved) session.watch(job)
                        session.refreshJobs()
                        load()
                    })
                } else {
                    if ((x.state == "erledigt" || x.state == "zurueckgenommen") && s != null) ReportCard(repo, s, x) { load(); session.refreshJobs() }
                    if (plan != null && plan.steps.isNotEmpty()) {
                        Section(stringResource(R.string.saga_plan))
                        plan.steps.forEachIndexed { i, step -> StepRow(i, plan.steps.size, step, showWording = true) }
                        x.approved?.let { a ->
                            Text(
                                stringResource(R.string.saga_approved, dateTimeText((a.at * 1000).toLong(), true)) + (a.device?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (plan != null && plan.read.isNotEmpty()) {
                    Section(stringResource(R.string.saga_read))
                    Text(plan.read.joinToString(", ") { it.label }, style = MaterialTheme.typography.bodyLarge)
                }
                if (x.events.isNotEmpty()) {
                    Section(stringResource(R.string.saga_log))
                    x.events.forEach { e ->
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                            Text(dateTimeText((e.at * 1000).toLong()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
                            SelectionContainer(Modifier.weight(1f)) { Text(e.text, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                if ("cancel" in x.actions && x.state != "wartet_ok") {
                    SagaOutlinedButton(onClick = {
                        scope.launch {
                            try {
                                val r = repo.saga.cancel(id)
                                session.live = s(r, "text").ifBlank { s(r, "state_text") }
                                load()
                                session.refreshJobs()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                repo.notify(repo.friendly(e))
                            }
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saga_cancel_job)) }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}
