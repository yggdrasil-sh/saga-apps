package io.github.yggdrasil_sh.saga.ui.saga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yggdrasil_sh.saga.ai.AiChunk
import io.github.yggdrasil_sh.saga.ai.AiSource
import io.github.yggdrasil_sh.saga.ai.JobDetail
import io.github.yggdrasil_sh.saga.ai.JobGroup
import io.github.yggdrasil_sh.saga.ai.JobPlan
import io.github.yggdrasil_sh.saga.ai.JobStreamEvent
import io.github.yggdrasil_sh.saga.ai.RefuseAlternative
import io.github.yggdrasil_sh.saga.ai.SagaLogic
import io.github.yggdrasil_sh.saga.ai.ThreadTurn
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Ein Zug im Gespraech: eigene Frage bzw. Bitte (zug "job"), KI-Antwort, oder ein Auftrags-Zug (APP-API-KI 11.7):
 * "plan" (job_id; gezeigt wird immer der jetzige Stand aus GET /api/ai/jobs/{id}), "ask" (Rueckfrage mit Chips), "refuse"
 * (Absage mit Alternative). Lokal waehrend des Planens: "wait" (Status), bei Fehlern "job_error" (der Server legt dann keinen
 * Zug an).
 */
class TalkTurn(
    val me: Boolean, text: String, sources: List<AiSource> = emptyList(), zug: String? = null, val created: Double? = null,
    jobId: Long? = null, val choices: List<String> = emptyList(), val request: String = "", val alternative: RefuseAlternative? = null,
) {
    var text by mutableStateOf(text)
    var sources by mutableStateOf(sources)
    var zug by mutableStateOf(zug)
    var running by mutableStateOf(false)
    var error by mutableStateOf<UiText?>(null)
    var jobId by mutableStateOf(jobId)
    /** Plan direkt aus dem Strom (bevor das Detail geladen ist) */
    var plan by mutableStateOf<JobPlan?>(null)
    /** jetziger Stand des Auftrags */
    var detail by mutableStateOf<JobDetail?>(null)
    /** Auftrag gibt es nicht mehr (404) */
    var gone by mutableStateOf(false)
    var stopped by mutableStateOf(false)
    /** Zaehler der Lade-Anfragen (loadJob): nur die zuletzt gestellte gilt - eine aeltere Antwort, die spaeter ankommt, setzt den Stand nicht zurueck */
    internal var loadSeq = 0

    val jobTurn: Boolean get() = !me && zug in JOB_ZUEGE

    companion object {
        val JOB_ZUEGE = setOf("plan", "ask", "refuse", "wait", "job_error")

        fun of(x: ThreadTurn): TalkTurn = TalkTurn(
            x.me, x.text, SagaLogic.turnSources(x), x.zug, x.created, x.jobId,
            SagaLogic.turnChoices(x), SagaLogic.turnRequest(x), SagaLogic.turnAlternative(x),
        )
    }
}

/**
 * Zustand des Orts "Saga" je Konto - lebt laenger als der Bildschirm (Wechsel ins Auftrags-Detail, Drehen), damit ein
 * laufender Strom nicht abbricht und die Plan-Karte stehen bleibt. Ein anderes Konto bekommt einen frischen Zustand.
 */
class SagaSession(private val repo: SagaRepository) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Gespraech
    val turns = mutableStateListOf<TalkTurn>()
    var talkLoaded by mutableStateOf(false)
    var talkError by mutableStateOf<UiText?>(null)
    var history by mutableStateOf(false)
    var draft by mutableStateOf("")
    var talking by mutableStateOf(false)
    /** Umschalter "Als Auftrag" (11.7): aus = Frage (POST /api/ai/thread), an = Auftrag (POST /api/ai/jobs, thread: true) */
    var asJob by mutableStateOf(false)
    private var talkJob: Job? = null

    // ---- Auftraege (Liste)
    var groups by mutableStateOf<List<JobGroup>?>(null)
    var jobsError by mutableStateOf<UiText?>(null)
    /** Ansage fuer TalkBack (role=status, hoeflich) */
    var live by mutableStateOf("")
    /** 2515: Rueckgaengig mit 409 changed je Aenderung -> version_url (null = ohne alte Fassung); lebt laenger als die Karte */
    val undoConflicts = mutableStateMapOf<Long, String?>()

    init {
        scope.launch {
            repo.live.events.collect { ev ->
                when (ev.type) {
                    "ai.job.changed" -> {
                        val id = ev.long("id") ?: return@collect
                        refreshJobs()
                        turns.filter { it.jobId == id && it.zug == "plan" }.forEach { loadJob(it) }
                    }
                    "reset" -> {
                        if (groups != null) refreshJobs()
                        turns.filter { it.zug == "plan" && it.jobId != null }.forEach { loadJob(it) }
                    }
                }
            }
        }
    }

    init {
        // 2515: ai.job.changed geht beim Wiederverbinden ggf. verloren (bzw. kam, bevor der Zug den Auftrag kannte) -
        // beim (Wieder-)Verbinden, auch nach dem Zurueckkehren in den Vordergrund (Live startet dann neu), einmal neu holen
        scope.launch {
            var was = repo.live.connected.value
            repo.live.connected.collect { on ->
                if (on && !was) resync()
                was = on
            }
        }
    }

    /** Stand der gezeigten Auftraege einmal neu holen (nicht bei Endzustaenden) */
    fun resync() {
        if (groups != null) refreshJobs()
        turns.filter { it.zug == "plan" && it.jobId != null && SagaLogic.resyncDue(it.detail?.state) }.forEach { loadJob(it) }
    }

    fun close() = scope.cancel()

    // ------------------------------------------------------------------ Gespraech

    fun loadThread() {
        scope.launch {
            try {
                val t = repo.saga.thread()
                if (!talking) {
                    turns.clear()
                    t.turns.forEach { x -> turns.add(TalkTurn.of(x)) }
                    turns.filter { it.zug == "plan" && it.jobId != null }.forEach { loadJob(it) }
                }
                history = t.history
                talkError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                talkError = repo.friendly(e)
            } finally {
                talkLoaded = true
            }
        }
    }

    /**
     * jetzigen Stand eines Auftrags-Zugs holen (Plan-Karte, Rueckmeldung oder Zustand). Wechselt der Zustand gegenueber dem
     * gezeigten, sagt die Statuszeile das Ergebnis an - verglichen erst beim Eintreffen (Pruefer pmelden: sonst verpasste
     * die Ansage einen schnell erledigten Auftrag, wenn ein anderes Laden den neuen Stand schon eingetragen hatte).
     */
    fun loadJob(t: TalkTurn, then: (JobDetail) -> Unit = {}) {
        val id = t.jobId ?: return
        val my = ++t.loadSeq
        scope.launch {
            try {
                val d = repo.saga.job(id)
                if (my != t.loadSeq) return@launch                     // inzwischen neuer angefragt
                val before = t.detail?.state
                t.detail = d
                if (before != null && before != d.state) live = d.report?.text ?: d.stateText
                t.gone = false
                then(d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == 404 && my == t.loadSeq) t.gone = true
            } catch (_: Exception) {
            }
        }
    }

    fun ask(q: String) {
        val text = q.trim()
        if (text.isEmpty() || talking) return
        val me = TalkTurn(true, text)
        val ai = TalkTurn(false, "", created = System.currentTimeMillis() / 1000.0).apply { running = true }
        turns.add(me)
        turns.add(ai)
        draft = ""
        talking = true
        talkJob = scope.launch {
            try {
                repo.saga.ask(text).collect { c ->
                    when (c) {
                        is AiChunk.Sources -> ai.sources = c.sources
                        is AiChunk.Delta -> ai.text += c.text
                        AiChunk.Reset -> ai.text = ""
                        is AiChunk.Done -> {
                            c.done.text?.let { ai.text = it }
                            ai.zug = if (c.done.noMatch) "no_match" else "answer"
                        }
                        is AiChunk.Failed -> {
                            ai.error = c.error
                            if (!c.partial) ai.text = ""
                        }
                    }
                }
            } finally {
                ai.running = false
                talking = false
                if (ai.text.isEmpty() && ai.error == null) {           // gestoppt, bevor etwas kam: leere Karte weg
                    turns.remove(ai)
                }
            }
        }
    }

    /**
     * Auftrag aus dem Gespraech (11.7): eigener Zug "Auftrag" (said = was die Person getippt bzw. gewaehlt hat), dann der
     * Strom von POST /api/ai/jobs mit thread: true - plan (Plan-Karte), ask (Rueckfrage), refuse (Absage) oder Fehler.
     */
    fun giveInThread(text: String, choice: String? = null, recipe: RefuseAlternative? = null, said: String? = null, thinking: String, ready: String) {
        val t = text.trim()
        if ((t.isEmpty() && recipe == null) || talking) return
        turns.add(TalkTurn(true, said ?: t, zug = "job"))
        val ai = TalkTurn(false, thinking, zug = "wait", created = System.currentTimeMillis() / 1000.0).apply { running = true }
        turns.add(ai)
        if (choice == null && recipe == null) draft = ""
        talking = true
        talkJob = scope.launch {
            try {
                val flow = if (recipe != null) repo.saga.giveRecipe(t, recipe, thread = true) else repo.saga.give(t, choice, thread = true)
                flow.collect { ev ->
                    when (ev) {
                        is JobStreamEvent.Status -> if (ev.text.isNotBlank()) ai.text = ev.text
                        is JobStreamEvent.Plan -> {
                            ai.jobId = ev.plan.job
                            ai.plan = ev.plan
                            ai.text = ev.plan.title
                            ai.zug = "plan"
                            live = ready
                            refreshJobs()
                        }
                        is JobStreamEvent.Ask -> replace(ai, TalkTurn(false, ev.question, zug = "ask", created = ai.created, choices = ev.choices, request = t))
                        is JobStreamEvent.Refuse -> replace(ai, TalkTurn(false, ev.text, zug = "refuse", created = ai.created, request = t, alternative = ev.alternative))
                        is JobStreamEvent.Failed -> {
                            ai.zug = "job_error"
                            ai.text = ""
                            ai.error = if (ev.error.isNotBlank()) UiText.Raw(ev.error) else null
                        }
                    }
                }
            } finally {
                ai.running = false
                talking = false
                if (ai.zug == "wait") ai.stopped = true                   // gestoppt oder Strom ohne Ergebnis
            }
        }
    }

    private fun replace(old: TalkTurn, new: TalkTurn) {
        val i = turns.indexOf(old)
        if (i >= 0) turns[i] = new else turns.add(new)
    }

    fun stopTalk() {
        talkJob?.cancel()
    }

    fun clearThread(onError: (Throwable) -> Unit) {
        scope.launch {
            try {
                repo.saga.clearThread()
                turns.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    // ------------------------------------------------------------------ Auftraege (Liste)

    fun refreshJobs() {
        scope.launch {
            try {
                groups = SagaLogic.groups(repo.saga.jobs())
                jobsError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                jobsError = repo.friendly(e)
            }
        }
    }
}

/** Ein Zustand je Server und Konto (Prozess) */
object SagaSessions {
    private var key: String? = null
    private var session: SagaSession? = null

    fun of(repo: SagaRepository, key: String): SagaSession {
        val s = session
        if (s != null && this.key == key) return s
        s?.close()
        return SagaSession(repo).also {
            session = it
            this.key = key
        }
    }
}
