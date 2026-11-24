package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.ui.nav.Dest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 2.6 (Bugs-DB 2031) "Erste Schritte": beim ersten Anmelden einmal fragen, ob man eine kurze Einfuehrung moechte
 * (Ja -> Server legt die Seite "Erste Schritte" mit einer Haekchenliste an, Nein -> nichts). Abgehakt wird auf dem
 * Server: jeder GET hakt neu Erledigtes ab - die App ruft ihn beim Start und beim Wechsel in den Vordergrund auf,
 * hoechstens alle [THROTTLE_MS]. Spaeter unter Einstellungen > Info (Hilfe) wieder abrufbar. Ganz ohne KI.
 *
 *   GET  api/first-steps                  -> {enabled, ask, state: null|"yes"|"no", page: id|null, items: [{key, done}]}
 *   POST api/first-steps {answer: "yes"}  -> {ok, page}   (legt die Seite an bzw. neu an)
 *   POST api/first-steps {answer: "no"}   -> {ok}
 *   enabled:false -> nichts zeigen (POST 403); aeltere Server -> 404 -> still nichts tun.
 */
object FirstSteps {
    const val PATH = "api/first-steps"
    const val THROTTLE_MS = 60_000L

    data class Item(val key: String, val done: Boolean)

    data class Status(
        val enabled: Boolean,
        val ask: Boolean,
        val state: String?,
        val page: Long?,
        val items: List<Item>,
    ) {
        val doneCount: Int get() = items.count { it.done }
        val total: Int get() = items.size
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Antwort von GET; null, wenn sie kein Objekt ist. Fehlende/kaputte Felder: vorsichtig (nichts zeigen, nicht fragen). */
    fun parse(text: String): Status? {
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val enabled = o.bool("enabled") ?: false
        val state = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it == "yes" || it == "no" }
        val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val io = e as? JsonObject ?: return@mapNotNull null
            val k = (io["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Item(k, io.bool("done") ?: false)
        }
        return Status(enabled = enabled, ask = enabled && (o.bool("ask") ?: false), state = state, page = pageId(o["page"]), items = items)
    }

    /** Seiten-ID aus der POST-Antwort ({ok, page}); null ohne Seite */
    fun parsePage(text: String): Long? = try {
        pageId((json.parseToJsonElement(text) as? JsonObject)?.get("page"))
    } catch (_: Exception) {
        null
    }

    private fun pageId(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.longOrNull?.takeIf { it > 0 }
    }

    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    fun body(yes: Boolean): JsonObject = buildJsonObject { put("answer", if (yes) "yes" else "no") }

    /** Neu abrufen? Hoechstens alle [THROTTLE_MS]; force (Einstellungen) immer; Uhr zurueckgestellt -> abrufen. */
    fun shouldFetch(lastAt: Long?, now: Long, force: Boolean = false): Boolean =
        force || lastAt == null || now < lastAt || now - lastAt >= THROTTLE_MS

    /** Zustand nach einer Antwort (wie das Web: einmal "yes" bleibt "yes", die Frage ist erledigt) */
    fun afterAnswer(cur: Status?, yes: Boolean, page: Long?): Status? = cur?.copy(
        ask = false,
        state = if (cur.state == "yes" || yes) "yes" else "no",
        page = page ?: cur.page,
    )

    /**
     * Darf die Frage hier erscheinen? Nicht in den Chats (Einrichtung, Schluessel), nicht in den Einstellungen/Kopplung,
     * nicht im Anruf; nur auf Inhaltszielen (Heute, Aufgaben, Termine, Suche, Seiten, Dienste, Graph).
     */
    fun askPlace(dest: Dest): Boolean = when (dest) {
        Dest.Today, Dest.Tasks, Dest.Events, Dest.Search, Dest.Services -> true
        is Dest.Page, is Dest.Graph -> true
        else -> false
    }

    /** Jetzt fragen? Server sagt ask, diese Sitzung noch nicht gefragt, kein anderer Dialog/Ablauf und ein passender Ort */
    fun askNow(st: Status?, askedThisSession: Boolean, blocked: Boolean, dest: Dest): Boolean =
        st != null && st.enabled && st.ask && !askedThisSession && !blocked && askPlace(dest)
}

/**
 * Zustand "Erste Schritte" je Konto (Repository). [get]/[post] sind die API-Aufrufe (Tests: Attrappen), [clock] die Uhr.
 * Fehler beim Abrufen bleiben still; 404 (aelterer Server) merkt sich die App bis zum naechsten Konto-/Serverwechsel.
 */
class FirstStepsModel(
    private val get: suspend (String) -> String,
    private val post: suspend (String, JsonElement) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<FirstSteps.Status?>(null)
    val status: StateFlow<FirstSteps.Status?> = _status

    /** Server kennt "Erste Schritte" nicht (404) */
    @Volatile var unsupported = false
        private set

    /** In dieser Sitzung schon gefragt (bzw. Frage ohne Antwort geschlossen) */
    val asked = MutableStateFlow(false)

    /** POST laeuft (Knoepfe der Frage und des Info-Abschnitts gesperrt) */
    val busy = MutableStateFlow(false)

    @Volatile private var lastAt: Long? = null
    @Volatile private var key: String? = null
    /** Zaehlt jeden Konto-/Serverwechsel: Antworten einer Anfrage von vorher werden verworfen */
    @Volatile private var gen = 0
    private val lock = Mutex()

    /** Anderes Konto/anderer Server: alles vergessen */
    fun resetFor(k: String) {
        if (k == key) return
        key = k
        gen++
        lastAt = null
        unsupported = false
        asked.value = false
        busy.value = false
        _status.value = null
    }

    /** GET (gedrosselt); liefert den letzten bekannten Stand. Wirft nie (ausser Abbruch). */
    suspend fun check(force: Boolean = false): FirstSteps.Status? = lock.withLock {
        if (unsupported) return@withLock null
        val now = clock()
        if (!FirstSteps.shouldFetch(lastAt, now, force)) return@withLock _status.value
        lastAt = now
        val g = gen
        try {
            val st = FirstSteps.parse(get(FirstSteps.PATH))
            if (g != gen) return@withLock null                 // inzwischen anderes Konto: Antwort gehoert zum alten
            st?.let { _status.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (g != gen) return@withLock null
            if (e.code == 404) {
                unsupported = true
                _status.value = null
            }
        } catch (_: Exception) {
            // offline/Zeitueberschreitung: beim naechsten Mal wieder
        }
        _status.value
    }

    /** POST yes/no -> Seiten-ID (bei yes). Fehler (403, offline) werden geworfen - die Oberflaeche zeigt sie. */
    suspend fun answer(yes: Boolean): Long? {
        val g = gen
        val r = post(FirstSteps.PATH, FirstSteps.body(yes))
        if (g != gen) return null                              // Kontowechsel waehrend des POST: nichts uebernehmen/oeffnen
        val page = FirstSteps.parsePage(r)
        asked.value = true
        _status.value = FirstSteps.afterAnswer(_status.value, yes, page)
        lastAt = null                                       // naechster Abruf (Vordergrund) holt die Haekchen frisch
        return page
    }

    fun markAsked() {
        asked.value = true
    }
}
