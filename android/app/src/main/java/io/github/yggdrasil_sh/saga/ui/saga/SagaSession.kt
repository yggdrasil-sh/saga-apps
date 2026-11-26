package io.github.yggdrasil_sh.saga.ui.saga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Ein Zug im Gespraech (eigene Frage oder KI-Antwort); die laufende Antwort waechst ueber delta */
class TalkTurn(val me: Boolean, text: String, sources: List<AiSource> = emptyList(), zug: String? = null, val created: Double? = null) {
    var text by mutableStateOf(text)
    var sources by mutableStateOf(sources)
    var zug by mutableStateOf(zug)
    var running by mutableStateOf(false)
    var error by mutableStateOf<UiText?>(null)
}

/** Buehne ueber der Auftrags-Liste: was nach "Auftrag geben" gerade zu sehen ist */
sealed interface JobStage {
    data class Thinking(val text: String) : JobStage
    data class Plan(val plan: JobPlan) : JobStage
    data class Ask(val question: String, val choices: List<String>, val text: String) : JobStage
    data class Refuse(val text: String, val alternative: RefuseAlternative?, val request: String) : JobStage
    data class Failed(val error: String) : JobStage
    /** Karte nach "Passt, los" / "Lieber nicht": Zustandswort + Satz (klein) */
    data class Small(val job: Long, val stateText: String, val text: String) : JobStage
    /** Rueckmeldung an Ort und Stelle (Ergebnis zuerst), sobald ein freigegebener Auftrag fertig ist */
    data class Feedback(val detail: JobDetail) : JobStage
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
    private var talkJob: Job? = null

    // ---- Auftraege
    var jobDraft by mutableStateOf("")
    var stage by mutableStateOf<JobStage?>(null)
    var giving by mutableStateOf(false)
    private var giveJob: Job? = null
    var groups by mutableStateOf<List<JobGroup>?>(null)
    var jobsError by mutableStateOf<UiText?>(null)
    /** Ansage fuer TalkBack (role=status, hoeflich) */
    var live by mutableStateOf("")
    private val watch = HashSet<Long>()

    init {
        scope.launch {
            repo.live.events.collect { ev ->
                when (ev.type) {
                    "ai.job.changed" -> {
                        val id = ev.long("id") ?: return@collect
                        refreshJobs()
                        val st = ev.str("state")
                        if (id in watch && st != null && st != "laeuft") {
                            watch.remove(id)
                            try {
                                val d = repo.saga.job(id)
                                stage = JobStage.Feedback(d)
                                live = d.report?.text ?: d.stateText
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                    }
                    "reset" -> if (groups != null) refreshJobs()
                }
            }
        }
    }

    fun close() = scope.cancel()

    // ------------------------------------------------------------------ Gespraech

    fun loadThread() {
        scope.launch {
            try {
                val t = repo.saga.thread()
                if (!talking) {
                    turns.clear()
                    t.turns.forEach { x -> turns.add(TalkTurn(x.me, x.text, SagaLogic.turnSources(x), x.zug, x.created)) }
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
                            live = ai.text.take(200)
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

    // ------------------------------------------------------------------ Auftraege

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

    fun give(text: String, choice: String? = null, recipe: RefuseAlternative? = null, thinking: String) {
        val t = text.trim()
        if ((t.isEmpty() && recipe == null) || giving) return
        giving = true
        stage = JobStage.Thinking(thinking)
        giveJob = scope.launch {
            try {
                val flow = if (recipe != null) repo.saga.giveRecipe(t, recipe) else repo.saga.give(t, choice)
                flow.collect { ev ->
                    when (ev) {
                        is JobStreamEvent.Status -> if (ev.text.isNotBlank()) stage = JobStage.Thinking(ev.text)
                        is JobStreamEvent.Plan -> {
                            stage = JobStage.Plan(ev.plan)
                            jobDraft = ""
                            refreshJobs()
                        }
                        is JobStreamEvent.Ask -> stage = JobStage.Ask(ev.question, ev.choices, t)
                        is JobStreamEvent.Refuse -> stage = JobStage.Refuse(ev.text, ev.alternative, t)
                        is JobStreamEvent.Failed -> stage = if (ev.consentNeeded) null else JobStage.Failed(ev.error)
                    }
                }
            } finally {
                giving = false
                if (stage is JobStage.Thinking) stage = null            // gestoppt oder Strom ohne Ergebnis
            }
        }
    }

    fun stopGive() {
        giveJob?.cancel()
    }

    /** Nach "Passt, los": Rueckmeldung an Ort und Stelle, sobald der Auftrag nicht mehr laeuft */
    fun watch(job: Long) {
        watch.add(job)
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
