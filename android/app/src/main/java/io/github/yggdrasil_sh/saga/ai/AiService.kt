package io.github.yggdrasil_sh.saga.ai

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.live.SseParser
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class AiStatus(
    val enabled: Boolean = false,
    val host: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val quota: Int? = null,
    val used: Int? = null,
    @SerialName("quota_left") val quotaLeft: Int? = null,
    @SerialName("history_allowed") val historyAllowed: Boolean = false,
    val history: Boolean = false,
    /** fertig uebersetzt, IMMER sichtbar anzeigen ("Inhalte ... gehen an {host}.") */
    val notice: String = "",
    val targets: List<String> = emptyList(),
    @SerialName("q_max") val qMax: Int = 1000,
    @SerialName("blocks_max") val blocksMax: Int = 50,
)

@Serializable
data class AiSource(val n: Int = 0, val id: Long? = null, val title: String = "", val owner: String? = null)

@Serializable
data class AiDoneBlock(val id: String = "", val type: String = "", val replaceable: Boolean = false)

/** Ende des Stroms: Kontingent, ggf. Aufgaben / Bloecke + Abschnitte (verbessern/uebersetzen) */
data class AiDone(
    val quotaLeft: Int?,
    val tasks: List<String>?,
    val blocks: List<AiDoneBlock>?,
    val segments: List<String>?,
    val truncated: Boolean,
)

sealed interface AiChunk {
    data class Sources(val sources: List<AiSource>) : AiChunk
    data class Delta(val text: String) : AiChunk
    data class Done(val done: AiDone) : AiChunk
    data class Failed(val error: UiText, val partial: Boolean) : AiChunk
}

@Serializable
data class AiHistoryItem(val id: Long = 0, val created: Double? = null, val kind: String = "ask", val q: String = "", val answer: String = "", val sources: List<AiSource> = emptyList())

@Serializable
data class AiHistory(val history: Boolean = false, val items: List<AiHistoryItem> = emptyList())

/** Text der Antwort in Abschnitte: Klartext und Verweise [n] (nur wenn es die Quelle gibt) - rein, fuer Tests */
object AiLogic {
    sealed interface Seg {
        data class Text(val t: String) : Seg
        data class Ref(val n: Int) : Seg
    }

    private val REF = Regex("\\[(\\d{1,3})]")

    fun segments(text: String, sources: List<AiSource>): List<Seg> {
        val ns = sources.map { it.n }.toSet()
        val out = ArrayList<Seg>()
        var last = 0
        for (m in REF.findAll(text)) {
            val n = m.groupValues[1].toInt()
            if (n !in ns) continue
            if (m.range.first > last) out.add(Seg.Text(text.substring(last, m.range.first)))
            out.add(Seg.Ref(n))
            last = m.range.last + 1
        }
        if (last < text.length) out.add(Seg.Text(text.substring(last)))
        return out
    }

    /** Ein Ereignis des Stroms lesen */
    fun chunk(event: String, data: String, json: Json): AiChunk? {
        val o = try {
            json.parseToJsonElement(data) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return when (event) {
            "sources" -> AiChunk.Sources((o["sources"] as? JsonArray)?.mapNotNull {
                try {
                    json.decodeFromJsonElement(AiSource.serializer(), it)
                } catch (_: Exception) {
                    null
                }
            }.orEmpty())
            "delta" -> AiChunk.Delta((o["t"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            "done" -> AiChunk.Done(
                AiDone(
                    quotaLeft = (o["quota_left"] as? JsonPrimitive)?.intOrNull,
                    tasks = (o["tasks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    blocks = (o["blocks"] as? JsonArray)?.mapNotNull {
                        try {
                            json.decodeFromJsonElement(AiDoneBlock.serializer(), it)
                        } catch (_: Exception) {
                            null
                        }
                    },
                    segments = (o["segments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    truncated = (o["truncated"] as? JsonPrimitive)?.booleanOrNull ?: false,
                ),
            )
            "error" -> AiChunk.Failed(UiText.Raw((o["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()), (o["partial"] as? JsonPrimitive)?.booleanOrNull ?: false)
            else -> null
        }
    }
}

/**
 * KI-Assistent (Server 1.6): der Server spricht mit dem Modell, die App nie direkt. Antworten als Strom (SSE ueber POST);
 * Abbrechen = Verbindung schliessen. Der Server aendert nie etwas - Uebernehmen macht die App nach einem Klick.
 */
class AiService(private val repo: SagaRepository) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    val status = MutableStateFlow<AiStatus?>(null)

    suspend fun loadStatus(): AiStatus? {
        if (repo.needsLogin.value) return null
        return try {
            json.decodeFromString(AiStatus.serializer(), repo.api.get("api/ai/status")).also { status.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404) status.value = AiStatus(enabled = false)
            status.value
        } catch (_: Exception) {
            status.value
        }
    }

    fun clear() {
        status.value = null
    }

    fun ask(q: String, tasks: Boolean, calendar: Boolean): Flow<AiChunk> = stream("api/ai/ask", buildJsonObject {
        put("q", q)
        if (tasks) put("tasks", true)
        if (calendar) put("calendar", true)
    })

    fun page(id: Long, action: String, blocks: List<String>? = null, target: String? = null): Flow<AiChunk> = stream("api/ai/page/$id", buildJsonObject {
        put("action", action)
        if (blocks != null) put("blocks", kotlinx.serialization.json.JsonArray(blocks.map { JsonPrimitive(it) }))
        if (target != null) put("target", target)
    })

    /** POST mit Accept: text/event-stream; Fehler vor dem Strom (JSON {error}) als Failed */
    private fun stream(path: String, body: JsonObject): Flow<AiChunk> = callbackFlow {
        val client = repo.api.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val req = Request.Builder().url(repo.api.url(path))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/event-stream").build()
        val call = client.newCall(req)
        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (resp.code !in 200..299) {
                        val t = resp.body.string()
                        val msg = try {
                            ((json.parseToJsonElement(t) as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
                        } catch (_: Exception) {
                            null
                        }
                        trySend(AiChunk.Failed(msg?.let { UiText.Raw(it) } ?: repo.friendly(ApiException(resp.code, UiText.Raw("HTTP ${resp.code}"))), false))
                        return@use
                    }
                    val src = resp.body.source()
                    val parser = SseParser()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        val ev = parser.feed(line) ?: continue
                        val c = AiLogic.chunk(ev.event, ev.data, json) ?: continue
                        trySend(c)
                        if (c is AiChunk.Done || c is AiChunk.Failed) break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!call.isCanceled()) trySend(AiChunk.Failed(repo.friendly(e), true))
            } finally {
                close()
            }
        }
        awaitClose {
            call.cancel()                                   // Stopp = Verbindung schliessen (Server bricht ab)
            job.cancel()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun history(): AiHistory = json.decodeFromString(AiHistory.serializer(), repo.api.get("api/ai/history"))

    suspend fun setHistory(on: Boolean) {
        repo.api.request("PUT", "api/ai/settings", buildJsonObject { put("history", on) })
        loadStatus()
    }

    suspend fun deleteHistory(id: Long) {
        repo.api.delete("api/ai/history/$id")
    }

    suspend fun deleteAllHistory() {
        repo.api.delete("api/ai/history")
    }
}
