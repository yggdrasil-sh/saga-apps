package io.github.yggdrasil_sh.saga.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Wann nach einem Ende neu verbinden? (rein, fuer Tests) */
object LivePolicy {
    /** Kein Ping/Ereignis laenger als das -> Verbindung gilt als tot */
    const val STALE_MS = 70_000L

    /** bye {reason} -> Wartezeit in ms, null = nicht neu verbinden (erst beim naechsten Start der App) */
    fun afterBye(reason: String?): Long? = when (reason) {
        "refresh", "auth" -> 0L            // auth: die Erneuerung der Sitzung passiert beim naechsten Aufbau (401 -> /app/resume)
        "limit" -> 120_000L                // eine andere Verbindung hat uebernommen: nicht sofort zurueckdraengeln
        "account" -> null                  // Konto geloescht
        else -> 5_000L                     // shutdown, slow, closed
    }

    /** Fehler/Abbruch: 2, 4, 8 ... hoechstens 60 s */
    fun backoff(fails: Int): Long = (1000L shl fails.coerceIn(1, 6)).coerceAtMost(60_000L)

    /** HTTP-Status beim Aufbau -> Wartezeit (null = aufgeben, z. B. Anmeldung verloren) */
    fun afterStatus(code: Int, fails: Int): Long? = when (code) {
        401, 403 -> null
        404 -> null                        // Server ohne Live-Kanal (aelter als 1.4)
        503 -> 30_000L
        else -> backoff(fails)
    }
}

/**
 * Live-Kanal GET /api/events (Server-Sent Events) - hoechstens EINE Verbindung, nur solange die App im Vordergrund ist
 * (start/stop aus dem Lebenszyklus). Fortsetzen mit Last-Event-ID; ohne Ping > 70 s neu aufbauen; "bye" je Grund.
 * Ereignisse kommen auf dem Haupt-Thread an.
 */
class LiveClient(
    private val baseClient: () -> OkHttpClient,
    private val url: () -> String?,
    private val scope: CoroutineScope,
    private val onEvent: suspend (type: String, data: JsonObject) -> Unit,
    private val onConnected: (Boolean) -> Unit = {},
) {
    @Volatile private var call: Call? = null
    private var job: Job? = null
    /** zuletzt gesehene Ereignis-ID (<boot>-<n>) zum Fortsetzen */
    @Volatile var lastId: String? = null
        private set

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        call?.cancel()
        call = null
        onConnected(false)
    }

    /** Konto/Server gewechselt: nicht mit der alten ID fortsetzen */
    fun forget() {
        lastId = null
    }

    private suspend fun loop() {
        var fails = 0
        while (scope.isActive) {
            val wait: Long? = try {
                val r = connectOnce()
                if (r.connected) fails = 0 else fails++
                r.wait
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fails++
                LivePolicy.backoff(fails)
            } finally {
                call = null
                scope.launch(Dispatchers.Main) { onConnected(false) }
            }
            if (wait == null) return
            delay(wait)
        }
    }

    private class Result(val wait: Long?, val connected: Boolean)

    private suspend fun connectOnce(): Result {
        val u = url() ?: return Result(null, false)
        val client = baseClient().newBuilder()
            .readTimeout(LivePolicy.STALE_MS, TimeUnit.MILLISECONDS)       // kein Ping in 70 s -> SocketTimeout -> neu
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val rb = Request.Builder().url(u).header("Accept", "text/event-stream").header("Cache-Control", "no-cache")
        lastId?.let { rb.header("Last-Event-ID", it) }
        val c = client.newCall(rb.build())
        call = c
        c.execute().use { resp ->
            if (resp.code != 200) return Result(LivePolicy.afterStatus(resp.code, 1), false)
            withContext(Dispatchers.Main) { onConnected(true) }
            val src = resp.body.source()
            val parser = SseParser()
            while (true) {
                val line = src.readUtf8Line() ?: return Result(5_000L, true)        // Server hat geschlossen
                val ev = parser.feed(line) ?: continue
                ev.id?.takeIf { it.isNotEmpty() }?.let { lastId = it }
                val data = try {
                    Json.parseToJsonElement(ev.data.ifEmpty { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                if (ev.event == "bye") {
                    val reason = (data["reason"] as? JsonPrimitive)?.content
                    withContext(Dispatchers.Main) { onEvent("bye", data) }
                    return Result(LivePolicy.afterBye(reason), true)
                }
                if (ev.event == "reset") lastId = null
                withContext(Dispatchers.Main) { onEvent(ev.event, data) }
            }
        }
    }
}
