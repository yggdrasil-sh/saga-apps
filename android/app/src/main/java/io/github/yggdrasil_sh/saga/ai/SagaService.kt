package io.github.yggdrasil_sh.saga.ai

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.live.SseParser
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Ein Stueck aus einem SSE-Strom an den eigenen Server - oder die Fehlerantwort vor dem Strom (Code + Text) */
sealed interface SagaSse {
    data class Event(val event: String, val data: String) : SagaSse
    data class Http(val code: Int, val body: String) : SagaSse
    data class Broken(val error: UiText) : SagaSse
}

/**
 * Netz fuer den Ort "Saga" (2.6, K-I 2269 / K-B 2267): Einwilligung, Gespraech, Auftraege, Einstellungen, Meine Daten.
 * Rechte prueft immer der Server; die App blendet nur ein und zeigt die Texte des Servers.
 */
class SagaService(private val repo: SagaRepository) {
    private val json = SagaLogic.json

    // ------------------------------------------------------------------ Einwilligung

    /** POST /api/ai/consent -> neuer Zustand; danach Status neu laden (Karte, Leiste, Reiter) */
    suspend fun consent(answer: SagaLogic.IntroAnswer, intro: AiIntro): AiConsent {
        val t = repo.api.post("api/ai/consent", SagaLogic.consentBody(answer, intro))
        val c = (json.parseToJsonElement(t) as? JsonObject)?.get("consent")
        repo.ai.loadStatus()
        return c?.let { json.decodeFromJsonElement(AiConsent.serializer(), it) } ?: AiConsent()
    }

    /** DELETE /api/ai/consent (Widerruf, RT-36) -> Zahl der angehaltenen Auftraege */
    suspend fun withdraw(): Int {
        val t = repo.api.delete("api/ai/consent")
        repo.ai.loadStatus()
        return SagaLogic.str((json.parseToJsonElement(t) as? JsonObject)?.get("paused"))?.toDoubleOrNull()?.toInt() ?: 0
    }

    // ------------------------------------------------------------------ Gespraech

    suspend fun thread(): ThreadState = SagaLogic.thread(repo.api.get("api/ai/thread"))

    suspend fun clearThread() {
        repo.api.delete("api/ai/thread")
    }

    /** POST /api/ai/thread (SSE wie /api/ai/ask): sources, delta, reset, done, error; 403 consent -> Failed + Status neu */
    fun ask(q: String): Flow<AiChunk> = sse("api/ai/thread", buildJsonObject { put("q", q) }).mapNotNull { s ->
        when (s) {
            is SagaSse.Event -> AiLogic.chunk(s.event, s.data, json)
            is SagaSse.Http -> {
                if (SagaLogic.consentNeeded(s.code, s.body)) repo.ai.loadStatus()
                val msg = SagaLogic.conflict(s.body).error
                AiChunk.Failed(msg?.let { UiText.Raw(it) } ?: repo.friendly(ApiException(s.code, UiText.Raw("HTTP ${s.code}"))), false)
            }
            is SagaSse.Broken -> AiChunk.Failed(s.error, true)
        }
    }

    // ------------------------------------------------------------------ Auftraege

    /** POST /api/ai/jobs (SSE): status, dann plan | ask | refuse | error */
    fun give(text: String, choice: String? = null, note: Long? = null): Flow<JobStreamEvent> = jobStream(buildJsonObject {
        put("text", text)
        if (choice != null) put("choice", choice)
        if (note != null && note > 0) put("context", buildJsonObject { put("note", note) })
    })

    /** Rezept direkt (refuse.alternative, z. B. "Als Aufgabe anlegen") */
    fun giveRecipe(text: String, alt: RefuseAlternative): Flow<JobStreamEvent> = jobStream(SagaLogic.recipeBody(text, alt))

    private fun jobStream(body: JsonObject): Flow<JobStreamEvent> = sse("api/ai/jobs", body).mapNotNull { s ->
        when (s) {
            is SagaSse.Event -> SagaLogic.jobEvent(s.event, s.data)
            is SagaSse.Http -> {
                val need = SagaLogic.consentNeeded(s.code, s.body)
                if (need) repo.ai.loadStatus()
                JobStreamEvent.Failed(SagaLogic.conflict(s.body).error.orEmpty(), s.code, need)
            }
            is SagaSse.Broken -> JobStreamEvent.Failed("", 0)
        }
    }

    suspend fun jobs(): JobList = json.decodeFromString(JobList.serializer(), repo.api.get("api/ai/jobs"))

    suspend fun job(id: Long): JobDetail = json.decodeFromString(JobDetail.serializer(), repo.api.get("api/ai/jobs/$id"))

    /** "Aendern" -> neuer Plan (plan_v+1, neuer Hash, edited) */
    suspend fun editPlan(plan: JobPlan, values: Map<Int, Map<String, String>>): JobPlan =
        json.decodeFromString(JobPlan.serializer(), repo.api.put("api/ai/jobs/${plan.job}/plan", SagaLogic.editBody(plan, values)))

    /** "Passt, los" -> {state, state_text, say}; 409 stale/state als ApiException (body lesen: SagaLogic.conflict) */
    suspend fun approve(plan: JobPlan): JsonObject =
        json.parseToJsonElement(repo.api.post("api/ai/jobs/${plan.job}/approve", SagaLogic.approveBody(plan))) as? JsonObject ?: JsonObject(emptyMap())

    suspend fun cancel(id: Long): JsonObject =
        json.parseToJsonElement(repo.api.post("api/ai/jobs/$id/cancel", JsonObject(emptyMap()))) as? JsonObject ?: JsonObject(emptyMap())

    /** Rueckgaengig je Aenderung; 409 {code: changed, version_url} -> ApiException */
    suspend fun undo(id: Long, change: Long?): JsonObject =
        json.parseToJsonElement(repo.api.post("api/ai/jobs/$id/undo", buildJsonObject { if (change != null) put("change", change) })) as? JsonObject
            ?: JsonObject(emptyMap())

    suspend fun before(job: Long, url: String): JobBefore {
        require(SagaLogic.beforePathOk(url, job))
        return json.decodeFromString(JobBefore.serializer(), repo.api.get(url.removePrefix("/")))
    }

    // ------------------------------------------------------------------ Einstellungen, Meine Daten

    suspend fun prefs(): SagaPrefs = json.decodeFromString(SagaPrefs.serializer(), repo.api.get("api/ai/prefs"))

    suspend fun putPrefs(body: JsonObject): SagaPrefs = json.decodeFromString(SagaPrefs.serializer(), repo.api.put("api/ai/prefs", body))

    /** GET /api/ai/mydata?format=json|md -> (Dateiname, Inhalt) */
    suspend fun export(format: String, day: String): Pair<String, ByteArray> {
        val r = repo.api.raw("GET", "api/ai/mydata?format=$format")
        if (r.code !in 200..299) throw ApiException(r.code, SagaLogic.conflict(r.text).error?.let { UiText.Raw(it) } ?: UiText.Raw("HTTP ${r.code}"), r.text)
        return SagaLogic.exportName(r.headers["Content-Disposition"], format, day) to r.bytes
    }

    /** Alles loeschen (mit Bestaetigung in der Oberflaeche) */
    suspend fun deleteAll() {
        repo.api.post("api/ai/mydata/delete", buildJsonObject { put("confirm", true) })
    }

    // ------------------------------------------------------------------ SSE

    /** POST mit Accept: text/event-stream; Abbrechen = Verbindung schliessen (der Server bricht dann ab) */
    private fun sse(path: String, body: JsonObject): Flow<SagaSse> = callbackFlow {
        val client = repo.api.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val req = Request.Builder().url(repo.api.url(path))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/event-stream").build()
        val call = client.newCall(req)
        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    val ct = resp.header("Content-Type").orEmpty()
                    if (resp.code !in 200..299 || !ct.startsWith("text/event-stream")) {
                        send(SagaSse.Http(if (resp.code in 200..299) 502 else resp.code, resp.body.string()))
                        return@use
                    }
                    val src = resp.body.source()
                    val parser = SseParser()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        val ev = parser.feed(line) ?: continue
                        send(SagaSse.Event(ev.event, ev.data))   // send statt trySend: bei vollem Puffer warten, nie ein Ereignis verlieren
                    }
                    parser.feed("")?.let { send(SagaSse.Event(it.event, it.data)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!call.isCanceled()) {
                    try {
                        send(SagaSse.Broken(repo.friendly(e)))
                    } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                    }
                }
            } finally {
                close()
            }
        }
        awaitClose {
            call.cancel()
            job.cancel()
        }
    }.flowOn(Dispatchers.IO)
}
