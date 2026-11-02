package io.github.yggdrasil_sh.saga.diag

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * 2.5 Fehlerberichte und "Diagnose senden" zur Laufzeit (Feature clientlog1; Logik und Bereinigung in [ClientLog]).
 * - Absturz: Thread.setDefaultUncaughtExceptionHandler schreibt eine bereinigte Notiz (eine JSON-Zeile) in den
 *   App-Speicher - nur, wenn der Server beim letzten Mal clientlog1 meldete - und ruft danach den vorigen Handler.
 *   Beim naechsten Start gehen Notizen und ANR/native Abbrueche (ApplicationExitInfo, API 30+, nur Grund und Zeit) in
 *   EINEM Bericht hinaus.
 * - Fehler an zentralen Stellen ([error], [http], [parse]): gesammelt, gezaehlt, hoechstens ein Bericht je 30 s.
 * - Ringpuffer (200 Ereignisse, nur im Speicher): Netz je Endpunkt-Muster, Ansicht, Live-Kanal, Speichern, Lebenslauf.
 * Ohne Feature wird nichts gesendet; ist bekannt, dass der Server es nicht kann, wird auch nichts gepuffert.
 * Fehler im Logger selbst werden nie gemeldet (keine Schleife).
 */
object ClientLogger {
    private const val PREFS = "clientlog"
    private const val K_ON = "on"                  // Server meldete clientlog1 (zuletzt bekannt)
    private const val K_EXIT = "exit_checked"      // ApplicationExitInfo bis zu diesem Zeitpunkt gesehen
    private const val K_START = "start"            // Startzeit des vorigen Prozesses (fuer t der ANR-Meldung)

    private val t0 = System.currentTimeMillis()
    private val sid = ClientLog.newSid()
    private val ring = ClientLog.Ring()
    private val pending = ClientLog.Pending()
    private var app: Application? = null
    private var repo: SagaRepository? = null
    private var scope: CoroutineScope? = null
    private var head: ClientLog.Head? = null
    private var timer: Job? = null
    private var startItems: List<ClientLog.Item> = emptyList()
    @Volatile private var screen: String? = null
    @Volatile private var busy = false
    private val _on = MutableStateFlow<Boolean?>(null)
    /** null = noch unbekannt, true = Server meldet clientlog1, false = Server kann es nicht bzw. Schalter aus (404) */
    val on: StateFlow<Boolean?> = _on

    fun now(): Long = maxOf(0L, System.currentTimeMillis() - t0)

    private fun crashFile(c: Context) = File(c.filesDir, "clientlog-crash.jsonl")

    /** in Application.onCreate (vor allem anderen): Absturz-Handler; nach dem Repository [start] */
    fun install(a: Application) {
        app = a
        val sw = try { a.resources.configuration.smallestScreenWidthDp } catch (_: Exception) { 0 }
        head = ClientLog.Head(BuildConfig.VERSION_NAME, ClientLog.os(Build.VERSION.RELEASE), ClientLog.dev(sw), sid)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                if (a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ON, false)) writeCrash(a, e)
            } catch (_: Throwable) {
            }
            if (prev != null) prev.uncaughtException(t, e) else android.os.Process.killProcess(android.os.Process.myPid())
        }
        ev(ClientLog.life(now(), "start"))
    }

    private fun writeCrash(c: Context, e: Throwable) {
        val f = crashFile(c)
        val old = if (f.isFile) f.readLines().takeLast(ClientLog.CRASH_KEEP - 1) else emptyList()
        f.writeText((old + ClientLog.crashLine(e, now(), screen)).joinToString("\n") + "\n")
    }

    /** nach dem Repository: Absturz-Notizen/ApplicationExitInfo lesen, Feature verfolgen */
    fun start(r: SagaRepository, s: CoroutineScope) {
        val a = app ?: return
        repo = r
        scope = s
        s.launch(Dispatchers.IO) {
            val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val items = ArrayList<ClientLog.Item>()
            try {
                val f = crashFile(a)
                if (f.isFile) {
                    items += ClientLog.parseCrash(f.readText())
                    f.delete()
                }
            } catch (_: Exception) {
            }
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val exits = am.getHistoricalProcessExitReasons(a.packageName, 0, 10).map { ClientLog.Exit(it.reason, it.timestamp) }
                    val after = prefs.getLong(K_EXIT, -1L)
                    // erster Start mit dieser Version: alte Eintraege nicht nachmelden
                    if (after >= 0 && prefs.getBoolean(K_ON, false)) {
                        items += ClientLog.exitItems(exits, after, prefs.getLong(K_START, 0L).takeIf { it > 0 })
                    }
                }
            } catch (_: Exception) {
            }
            prefs.edit().putLong(K_EXIT, System.currentTimeMillis()).putLong(K_START, t0).apply()
            synchronized(this@ClientLogger) { startItems = ClientLog.dedupe(items) }
            flushStart()
        }
        s.launch {
            combine(r.serverFeatures, r.serverVersion) { f, v -> if (ClientLog.FEATURE in f) true else if (v != null) false else null }
                .collect { st -> setOn(st) }
        }
        s.launch { r.needsLogin.collect { if (!it) { flushStart(); schedule() } } }
    }

    private fun setOn(st: Boolean?) {
        if (_on.value == false && st == null) return
        _on.value = st
        val a = app ?: return
        if (st == null) return
        try {
            a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ON, st).apply()
        } catch (_: Exception) {
        }
        if (st) {
            flushStart()
            schedule()
        } else {
            pending.clear()
            ring.clear()
            synchronized(this) { startItems = emptyList() }
            try { crashFile(a).delete() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------------------------------------- Ereignisse

    /** Ereignis in den Ringpuffer (nicht, wenn bekannt ist, dass der Server clientlog1 nicht kann) */
    fun ev(e: JsonObject?) {
        if (e == null || _on.value == false || busy) return
        ring.add(e)
    }

    fun view(dest: String) {
        val s = ClientLog.screenOf(dest)
        if (s == screen) return
        screen = s
        ev(ClientLog.view(now(), s))
    }

    fun life(st: String) = ev(ClientLog.life(now(), st))
    fun conn(ch: String, st: String) = ev(ClientLog.conn(now(), ch, st))
    fun save(st: String, status: Int? = null) = ev(ClientLog.save(now(), st, status))

    /** API-Client: Status je Endpunkt-Muster (0 = keine Antwort); 5xx zusaetzlich als Fehler */
    fun net(method: String, path: String, status: Int, ms: Long) {
        if (path.startsWith("api/client/") || path.startsWith("/api/client/")) return
        ev(ClientLog.net(now(), method, path, status, ms))
        if (status in 500..599) add(ClientLog.Item(k = "error", msg = ClientLog.httpMessage(method, path, status), screen = screen, t = now()))
    }

    /** abgefangener Fehler an einer zentralen Stelle (Editor-Speichern, Anruf-Aufbau, ...) */
    fun error(e: Throwable, where: String? = null) {
        if (e is CancellationException) return
        try {
            add(ClientLog.item("error", e, now(), where ?: screen))
        } catch (_: Throwable) {
        }
    }

    /** Antwort nicht lesbar (JSON): nur Klasse und Stack, nie der Text (er kann Inhalte zitieren) */
    fun parse(e: Throwable) {
        try {
            add(ClientLog.item("error", e, now(), screen, msg = e.javaClass.simpleName + ": json parse failed"))
        } catch (_: Throwable) {
        }
    }

    private fun add(it: ClientLog.Item) {
        if (_on.value == false || busy) return
        busy = true
        try {
            ring.add(it.event())
            if (pending.add(it)) schedule()
        } finally {
            busy = false
        }
    }

    // ---------------------------------------------------------------------------------------------------- Versand

    private fun ready(): Boolean = _on.value == true && repo?.needsLogin?.value == false

    @Synchronized
    private fun schedule() {
        val s = scope ?: return
        if (timer?.isActive == true || pending.size == 0 || !ready()) return
        val wait = maxOf(1500L, pending.wait(System.currentTimeMillis()))      // kurz sammeln (Fehler + Folgefehler)
        timer = s.launch(Dispatchers.IO) {
            delay(wait)
            synchronized(this@ClientLogger) { timer = null }
            if (!ready()) return@launch
            val items = pending.take(System.currentTimeMillis()) ?: run { schedule(); return@launch }
            post(items)
            schedule()
        }
    }

    /** Absturz-Notizen des vorigen Starts: ein Bericht, sobald Feature und Anmeldung bekannt sind */
    private fun flushStart() {
        val s = scope ?: return
        if (!ready()) return
        val items = synchronized(this) { startItems.also { startItems = emptyList() } }
        if (items.isEmpty()) return
        s.launch(Dispatchers.IO) {
            pending.markSent(System.currentTimeMillis())
            post(items)
        }
    }

    private suspend fun post(items: List<ClientLog.Item>) {
        val h = head ?: return
        val r = repo ?: return
        val body = ClientLog.logBody(h, items) ?: return
        try {
            val resp = r.api.raw("POST", ClientLog.PATH_LOG, body.toString().toRequestBody(JSON))
            pending.result(System.currentTimeMillis(), resp.code, resp.text)
            if (resp.code == 404) _on.value = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Netz weg: verwerfen (der Ringpuffer hat den Fehler noch fuer "Diagnose senden")
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Ergebnis von "Diagnose senden": Kennung D-XXXXX bzw. HTTP-Status (0 = Netz) */
    sealed interface DiagResult {
        data class Ok(val id: String) : DiagResult
        data class Failed(val code: Int) : DiagResult
    }

    /** "Diagnose senden" (nur auf Knopfdruck): Ringpuffer + optionaler Freitext */
    suspend fun diag(note: String): DiagResult {
        val h = head ?: return DiagResult.Failed(0)
        val r = repo ?: return DiagResult.Failed(0)
        if (_on.value != true) return DiagResult.Failed(404)
        val body = ClientLog.diagBody(h, ring.snapshot(), note)
        return try {
            val resp = r.api.raw("POST", ClientLog.PATH_DIAG, body.toString().toRequestBody(JSON))
            if (resp.code == 404) _on.value = false
            val id = if (resp.code in 200..299) ClientLog.diagId(resp.text) else null
            if (id != null) DiagResult.Ok(id) else DiagResult.Failed(if (resp.code in 200..299) 500 else resp.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            DiagResult.Failed(e.code)
        } catch (_: Exception) {
            DiagResult.Failed(0)
        }
    }
}
