package io.github.yggdrasil_sh.saga.chat

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// Saga 2.6 (Bugs-DB 2029-7): eigener Status Verfuegbar / Beschaeftigt / Nicht stoeren nach docs/APP-API.md "Eigener Status"
// (GET/PUT /api/account/status, Live status.changed / status.digest), Anzeige bei anderen ("Anzeige bei anderen": status im
// Online-Abo, Live chat.status) und Wirkung auf Anrufe (waiting1, call.quiet, hold). Vorbild: static/userstatus.js,
// static/push.js (Erreichbarkeit), static/chat.js. Reine Regeln in UserStatusLogic (Unit-Test UserStatusTest).

/** Einstellungen des eigenen Status (prefs) - Standardwerte wie der Server */
data class StatusPrefs(
    val quietDnd: Boolean = true,
    val quietDigest: Boolean = false,
    /** knock | busy */
    val inCall: String = "knock",
    val showCall: Boolean = true,
    /** show | silent */
    val busy: String = "show",
    val repeat: Boolean = true,
    val repeatS: Int = 180,
    val mentionBreak: Boolean = false,
)

/** Ausnahme: Person (ref = Handle bzw. ~x@host, name vom Server) oder eigene Gruppe (name) */
data class StatusException(val type: String, val ref: String? = null, val name: String? = null) {
    val group: Boolean get() = type == "group"

    fun same(o: StatusException): Boolean = type == o.type && if (group) name == o.name else ref == o.ref
}

/** Grenzen (limits) */
data class StatusLimitsU(val text: Int = 80, val exceptions: Int = 100, val repeatMin: Int = 30, val repeatMax: Int = 3600)

/** Ansicht des eigenen Status (Antwort von GET/PUT /api/account/status, Live status.changed) */
data class UserStatusView(
    /** available | busy | dnd (gesetzt) */
    val state: String,
    val until: Double? = null,
    val text: String? = null,
    /** manual | focus | system */
    val source: String = "manual",
    val ver: Long = 0,
    /** was gerade wirkt: available | busy | dnd | in_call */
    val effective: String = state,
    /** manual | quiet | call | none */
    val why: String = "none",
    val quiet: Boolean = false,
    val inCall: Boolean = false,
    val prefs: StatusPrefs = StatusPrefs(),
    val exceptions: List<StatusException> = emptyList(),
    val limits: StatusLimitsU = StatusLimitsU(),
    val now: Double? = null,
    /** nur bei 409 bzw. Fokus-Ende: Text des Servers / Status blieb (kept) */
    val error: String? = null,
    val kept: Boolean = false,
)

/** Status eines Chat-Partners (status im Online-Abo bzw. Live chat.status) */
data class PeerStatus(
    /** available | busy | dnd | in_call */
    val s: String,
    val until: Double? = null,
    val text: String? = null,
)

/** Sammelmeldung nach "Nicht stoeren" - nur Zahlen, nie Namen */
data class StatusDigest(val messages: Int = 0, val calls: Int = 0, val other: Int = 0) {
    val total: Int get() = messages + calls + other
}

object UserStatusLogic {
    const val FEATURE = "userstatus1"
    val STATES = listOf("available", "busy", "dnd")
    private val PEER_STATES = setOf("available", "busy", "dnd", "in_call")

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    /** Server/Konto kann den eigenen Status (features in /api/chat/me bzw. /api/version) */
    fun has(features: Collection<String>?): Boolean = features?.contains(FEATURE) == true

    fun parse(text: String?): UserStatusView? = try {
        parse(Json.parseToJsonElement(text ?: return null) as? JsonObject)
    } catch (_: Exception) {
        null
    }

    /** Ansicht lesen; ohne ver oder mit unbekanntem Zustand -> null (unbrauchbar) */
    fun parse(o: JsonObject?): UserStatusView? {
        if (o == null) return null
        val ver = (o["ver"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val state = o.s("state")?.takeIf { it in STATES } ?: return null
        val p = o["prefs"] as? JsonObject
        val d = StatusPrefs()
        val prefs = if (p == null) d else StatusPrefs(
            quietDnd = p.b("quiet_dnd") ?: d.quietDnd,
            quietDigest = p.b("quiet_digest") ?: d.quietDigest,
            inCall = p.s("in_call")?.takeIf { it == "knock" || it == "busy" } ?: d.inCall,
            showCall = p.b("show_call") ?: d.showCall,
            busy = p.s("busy")?.takeIf { it == "show" || it == "silent" } ?: d.busy,
            repeat = p.b("repeat") ?: d.repeat,
            repeatS = p.i("repeat_s") ?: d.repeatS,
            mentionBreak = p.b("mention_break") ?: d.mentionBreak,
        )
        val l = o["limits"] as? JsonObject
        val rs = (l?.get("repeat_s") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        val dl = StatusLimitsU()
        val limits = StatusLimitsU(
            text = l?.i("text") ?: dl.text,
            exceptions = l?.i("exceptions") ?: dl.exceptions,
            repeatMin = rs?.getOrNull(0) ?: dl.repeatMin,
            repeatMax = rs?.getOrNull(1) ?: dl.repeatMax,
        )
        val exc = (o["exceptions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            when (x.s("type")) {
                "user" -> x.s("ref")?.let { StatusException("user", it, x.s("name")) }
                "group" -> x.s("name")?.let { StatusException("group", null, it) }
                else -> null
            }
        }
        val eff = o.s("effective")?.takeIf { it in PEER_STATES } ?: state
        return UserStatusView(
            state = state, until = o.d("until"), text = o.s("text")?.ifBlank { null }, source = o.s("source") ?: "manual", ver = ver,
            effective = eff, why = o.s("why") ?: "none", quiet = o.b("quiet") == true, inCall = o.b("in_call") == true,
            prefs = prefs, exceptions = exc, limits = limits, now = o.d("now"), error = o.s("error"), kept = o.b("kept") == true,
        )
    }

    /** neuen Stand uebernehmen? Aeltere Antworten (kleineres ver) ueberholen den Stand nicht */
    fun take(old: UserStatusView?, new: UserStatusView): Boolean = old == null || new.ver >= old.ver

    /** Was gerade wirkt (ehrlich, wie userstatus.js shown): Zustand + Zusatz */
    data class Shown(
        val state: String,
        /** Ende anzeigen ("bis 14:30") */
        val until: Double? = null,
        /** Ruhezeit zaehlt als Nicht stoeren */
        val quiet: Boolean = false,
        /** im Anruf, darunter dieser Zustand (busy/dnd) */
        val under: String? = null,
        /** vom Geraet (Fokus/System-Nicht-stoeren) */
        val focus: Boolean = false,
    )

    fun shown(v: UserStatusView): Shown {
        val eff = v.effective
        return Shown(
            state = eff,
            until = v.until?.takeIf { eff == v.state && eff != "available" && !(eff == "dnd" && v.why == "quiet") },
            quiet = eff == "dnd" && v.why == "quiet",
            under = v.state.takeIf { eff == "in_call" && it != "available" },
            focus = v.source == "focus" && v.state != "available" && v.why != "quiet",
        )
    }

    /** Dauer im Blatt "Mein Status" */
    enum class Dur { KEEP, H1, T7, OFF }

    /** naechstes 7:00 (vor 5 Uhr: heute, sonst morgen) -> (Unix-Sekunden, heute?) */
    fun tomorrow7(now: ZonedDateTime): Pair<Long, Boolean> {
        val early = now.hour < 5
        val day = if (early) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return day.atTime(7, 0).atZone(now.zone).toEpochSecond() to early
    }

    /** "Bis {Ende} (wie bisher)" anbieten: gesetzter Status mit Ende, das noch mindestens eine Minute hin ist */
    fun keepOffered(v: UserStatusView, nowSec: Double): Boolean = v.until != null && v.state != "available" && v.until > nowSec + 60

    /** Vorauswahl der Dauer */
    fun defaultDur(v: UserStatusView, nowSec: Double): Dur = if (keepOffered(v, nowSec)) Dur.KEEP else Dur.OFF

    /** eigener Text wie gespeichert (nur selbst gesetzt) */
    fun ownText(v: UserStatusView): String = if (v.source == "manual") v.text.orEmpty() else ""

    /** Text auf die Grenze kuerzen (Zeichen = Unicode-Codepoints wie der Server) */
    fun clip(s: String, max: Int): String {
        val n = s.codePointCount(0, s.length)
        return if (n <= max) s else s.substring(0, s.offsetByCodePoints(0, max))
    }

    fun count(s: String): Int = s.codePointCount(0, s.length)

    /** PUT /api/account/status (manuell): Zustand, Dauer, Text, ver */
    fun putBody(state: String, dur: Dur, text: String, v: UserStatusView?, now: ZonedDateTime): JsonObject = buildJsonObject {
        put("state", state)
        put("source", "manual")
        val t = clip(text.trim(), v?.limits?.text ?: 80)
        if (t.isEmpty()) put("text", JsonNull) else put("text", t)
        if (state != "available") when (dur) {
            Dur.H1 -> put("dur", 3600)
            Dur.T7 -> put("until", tomorrow7(now).first)
            // ganze Sekunden (ein Double kaeme sonst als "1.7924502E9")
            Dur.KEEP -> v?.until?.let { put("until", Math.round(it)) }
            Dur.OFF -> {}
        }
        if (v != null) put("ver", v.ver)
    }

    /**
     * System-Nicht-stoeren -> Fokus-Status: NUR {state, source:"focus"} - nie Text, nie Name des Modus (RT-29), kein ver
     * (ein Fokus-Ende hebt einen selbst gesetzten Status ohnehin nicht auf - der Server antwortet dann kept: true).
     */
    fun focusBody(dnd: Boolean): JsonObject = buildJsonObject {
        put("state", if (dnd) "dnd" else "available")
        put("source", "focus")
    }

    // NotificationManager.INTERRUPTION_FILTER_* (wie RingLogic)
    /** Unterbrechungsfilter des Systems -> Nicht stoeren an? (null = unbekannt, nichts senden) */
    fun focusDnd(filter: Int): Boolean? = when (filter) {
        io.github.yggdrasil_sh.saga.call.RingLogic.FILTER_ALL -> false
        io.github.yggdrasil_sh.saga.call.RingLogic.FILTER_PRIORITY, io.github.yggdrasil_sh.saga.call.RingLogic.FILTER_NONE,
        io.github.yggdrasil_sh.saga.call.RingLogic.FILTER_ALARMS -> true
        else -> null
    }

    /** Einstellung aendern: nur genannte Felder */
    fun prefBody(key: String, value: Any): JsonObject = buildJsonObject {
        when (value) {
            is Boolean -> put(key, value)
            is Int -> put(key, value)
            else -> put(key, value.toString())
        }
    }

    /** Ausnahmen ersetzen die ganze Liste: Personen mit ref, Gruppen mit name (hoechstens limits.exceptions) */
    fun exceptionsBody(list: List<StatusException>, max: Int = 100): JsonObject = buildJsonObject {
        put("exceptions", buildJsonArray {
            list.take(max).forEach { x ->
                add(buildJsonObject {
                    put("type", x.type)
                    if (x.group) put("name", x.name.orEmpty()) else put("ref", x.ref.orEmpty())
                })
            }
        })
    }

    /** Ausnahme hinzufuegen (doppelt -> unveraendert) */
    fun addException(list: List<StatusException>, x: StatusException): List<StatusException> = if (list.any { it.same(x) }) list else list + x

    fun removeException(list: List<StatusException>, x: StatusException): List<StatusException> = list.filterNot { it.same(x) }

    /**
     * Bugs-DB 2387: Kennung einer Person fuer Ausnahmen - wie static/push.js HANDLE: '~' + 16 Zeichen (base32 klein),
     * optional '@host' (anderer Server). Gleiche Pruefung fuer die direkte Eingabe und fuer Treffer anderer Server.
     */
    val HANDLE = Regex("^~[a-z2-7]{16}(@[a-z0-9.:\\-]{1,260})?$")

    fun validHandle(s: String?): Boolean = s != null && HANDLE.matches(s)

    /**
     * Treffer eines anderen Servers (GET /api/share/targets -> remote) -> Kennung fuer Ausnahmen ('~x@host') oder null.
     * Der Server liefert Personen als type "remote" mit target '@~x@host' (bzw. '@name@host' mit oeffentlichem Namen) und
     * aus dem Adressbuch zusaetzlich handle '~x@host'; Gruppen ("remote_group") gehen nicht. Ausnahmen nehmen nur
     * pseudonyme Handles an (APP-API "Ausnahmen": '~x@host').
     */
    fun remoteRef(type: String?, target: String?, handle: String?): String? {
        if (!(type == null || type.isEmpty() || type == "remote" || type == "user")) return null
        handle?.trim()?.takeIf { validHandle(it) }?.let { return it }
        return target?.trim()?.removePrefix("@")?.takeIf { validHandle(it) }
    }

    /** Ein Treffer der Ausnahme-Suche: Eintrag und Unterzeile */
    data class ExcHit(val x: StatusException, val sub: String?)

    /**
     * Treffer der Ausnahme-Suche wie static/push.js excCard: Personen, eigene Gruppen, Personen anderer Server (nur mit
     * Handle) - ohne Treffer und bei gueltiger Kennung im Feld die direkte Eingabe (Handle bzw. Handle@host).
     */
    fun exceptionHits(r: io.github.yggdrasil_sh.saga.data.ShareTargets?, query: String, groupWord: String): List<ExcHit> {
        val out = ArrayList<ExcHit>()
        val seen = HashSet<String>()
        r?.users?.forEach { if (it.username.isNotEmpty() && seen.add("u:" + it.username)) out += ExcHit(StatusException("user", it.username, it.name), it.sub) }
        r?.groups?.forEach { if (it.name.isNotEmpty() && seen.add("g:" + it.name)) out += ExcHit(StatusException("group", null, it.name), groupWord) }
        r?.remote?.forEach { t ->
            val ref = remoteRef(t.type, t.target, t.handle) ?: return@forEach
            if (seen.add("u:$ref")) out += ExcHit(StatusException("user", ref, t.label?.ifEmpty { null } ?: t.name.ifEmpty { null }), t.host.ifEmpty { null })
        }
        val s = query.trim()
        if (out.isEmpty() && validHandle(s)) out += ExcHit(StatusException("user", s, null), s)
        return out
    }

    /** Wiederholungsanruf-Sekunden pruefen (Grenzen des Servers) -> Zahl oder null */
    fun repeatSeconds(text: String, l: StatusLimitsU): Int? = text.trim().toIntOrNull()?.takeIf { it in l.repeatMin..l.repeatMax }

    /** Status eines Partners {s, until?, text?}; unbekannter Zustand -> null */
    fun peer(e: JsonElement?): PeerStatus? {
        val o = e as? JsonObject ?: return null
        val s = o.s("s")?.takeIf { it in PEER_STATES } ?: return null
        return PeerStatus(s, o.d("until"), o.s("text")?.ifBlank { null })
    }

    /** status aus POST /api/chat/presence */
    fun peers(o: JsonObject?): Map<String, PeerStatus> =
        o.orEmpty().mapNotNull { (u, v) -> peer(v)?.let { u to it } }.toMap()

    /** anzeigen? Verfuegbar zeigt nichts Zusaetzliches */
    fun visible(p: PeerStatus?): PeerStatus? = p?.takeIf { it.s != "available" }

    /** vor dem Anruf: Nicht stoeren -> Rueckfrage, im Anruf -> Hinweis "klopft an", sonst direkt */
    enum class PreCall { CALL, ASK, HINT }

    fun preCall(p: PeerStatus?): PreCall = when (p?.s) {
        "dnd" -> PreCall.ASK
        "in_call" -> PreCall.HINT
        else -> PreCall.CALL
    }

    /** Sammelmeldung (Live status.digest bzw. digest der Mitteilung) - nichts Zaehlbares -> null */
    fun digest(o: JsonObject?): StatusDigest? {
        if (o == null) return null
        val d = StatusDigest(o.i("messages") ?: 0, o.i("calls") ?: 0, o.i("other") ?: 0)
        return d.takeIf { it.total > 0 }
    }

    /** Wann endet etwas - fuer "bis 14:30" / "bis morgen 7:00" / "bis Fr. 14:30" / "bis 24.12., 14:30" */
    enum class WhenKind { TODAY, TOMORROW, WEEK, DATE }

    fun whenKind(untilSec: Double, now: ZonedDateTime): WhenKind {
        val d = Instant.ofEpochMilli((untilSec * 1000).toLong()).atZone(now.zone).toLocalDate()
        val dd = ChronoUnit.DAYS.between(now.toLocalDate(), d)
        return when {
            dd <= 0 -> WhenKind.TODAY
            dd == 1L -> WhenKind.TOMORROW
            dd < 7 -> WhenKind.WEEK
            else -> WhenKind.DATE
        }
    }

    /** Tag eines Zeitpunkts in der Zone (Tests) */
    fun day(sec: Double, zone: ZoneId): LocalDate = Instant.ofEpochMilli((sec * 1000).toLong()).atZone(zone).toLocalDate()

    /** Nachladen planen: kurz nach Ablauf von until, sonst alle 5 Minuten (Ruhezeit/Anruf aendern effective ohne Ereignis) */
    fun reloadInMs(v: UserStatusView?, nowSec: Double): Long {
        var ms = 300_000L
        val u = v?.until
        if (u != null) ms = minOf(ms, maxOf(2_000L, ((u - nowSec) * 1000).toLong() + 2_500L))
        return ms
    }
}

/**
 * Eigener Status dieses Kontos: Stand (view), Laden/Setzen (409 -> Ansicht uebernehmen, still neu zeigen), Live
 * status.changed / status.digest. Ohne Server-Faehigkeit userstatus1 bleibt alles leer (nichts anzeigen).
 */
class UserStatusCenter(
    private val scope: CoroutineScope,
    private val get: suspend (String) -> String,
    private val put: suspend (String, JsonObject) -> String,
    /** Server kann es (features) */
    private val supported: () -> Boolean,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    val view = MutableStateFlow<UserStatusView?>(null)
    /** Sammelmeldung (Live), fuer eine ruhige Meldung in der App */
    val digests = MutableSharedFlow<StatusDigest>(extraBufferCapacity = 4)
    private var skew = 0.0
    private var plan: Job? = null

    fun now(): Double = clock() + skew

    /** Ergebnis eines PUT: neuer Stand; conflict = anderswo geaendert (409, Stand uebernommen) */
    data class Result(val view: UserStatusView?, val conflict: Boolean = false)

    fun take(v: UserStatusView?): Boolean {
        if (v == null || !UserStatusLogic.take(view.value, v)) return false
        v.now?.let { skew = it - clock() }
        view.value = v
        schedule()
        return true
    }

    private fun schedule() {
        plan?.cancel()
        plan = scope.launch {
            delay(UserStatusLogic.reloadInMs(view.value, now()))
            quiet { load() }
        }
    }

    suspend fun load(): UserStatusView? {
        if (!supported()) {
            clear()
            return null
        }
        take(UserStatusLogic.parse(get("api/account/status")))
        return view.value
    }

    private suspend fun putView(path: String, body: JsonObject): Result = try {
        take(UserStatusLogic.parse(put(path, body)))
        Result(view.value)
    } catch (e: ApiException) {
        val v = if (e.code == 409) UserStatusLogic.parse(e.body) else null
        if (v == null) throw e
        // ver veraltet: Stand eines anderen Geraets uebernehmen (auch wenn ver gleich ist)
        if (!take(v)) view.value = v
        Result(view.value, conflict = true)
    }

    /** Status setzen (manuell, mit ver) */
    suspend fun set(state: String, dur: UserStatusLogic.Dur, text: String, zone: ZoneId = ZoneId.systemDefault()): Result {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli((now() * 1000).toLong()), zone)
        return putView("api/account/status", UserStatusLogic.putBody(state, dur, text, view.value, now))
    }

    /** Fokus (System-Nicht-stoeren): nur {state, source:"focus"} */
    suspend fun focus(dnd: Boolean): Result {
        if (!supported()) return Result(null)
        return putView("api/account/status", UserStatusLogic.focusBody(dnd))
    }

    suspend fun prefs(body: JsonObject): Result = putView("api/account/status/prefs", body)

    /** Live: status.changed (eigene Geraete), status.digest, call.state ("Im Anruf" kommt ohne eigenes Ereignis) */
    fun onLive(type: String, data: JsonObject) {
        when (type) {
            "status.changed" -> take(UserStatusLogic.parse(data))
            "status.digest" -> UserStatusLogic.digest(data)?.let { digests.tryEmit(it) }
            "call.state" -> if (view.value != null) {
                plan?.cancel()
                plan = scope.launch {
                    delay(700)
                    quiet { load() }
                }
            }
        }
    }

    fun clear() {
        plan?.cancel()
        plan = null
        view.value = null
    }

    private suspend fun quiet(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

/** Texte rund um den eigenen Status (ohne Compose; Sprache ueber Resources) */
object StatusTexts {
    fun stateRes(s: String): Int = when (s) {
        "busy" -> R.string.us_busy
        "dnd" -> R.string.us_dnd
        "in_call" -> R.string.us_in_call
        else -> R.string.us_available
    }

    /** Ende als kurzer Text: heute "14:30", morgen "morgen 7:00", diese Woche "Fr. 14:30", sonst "24.12., 14:30" */
    fun whenText(res: android.content.res.Resources, locale: java.util.Locale, sec: Double, now: ZonedDateTime = ZonedDateTime.now()): String {
        val t = Instant.ofEpochMilli((sec * 1000).toLong()).atZone(now.zone)
        val time = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale).format(t)
        return when (UserStatusLogic.whenKind(sec, now)) {
            UserStatusLogic.WhenKind.TODAY -> time
            UserStatusLogic.WhenKind.TOMORROW -> res.getString(R.string.us_tomorrow, time)
            UserStatusLogic.WhenKind.WEEK -> t.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale) + " " + time
            UserStatusLogic.WhenKind.DATE -> {
                val p = android.text.format.DateFormat.getBestDateTimePattern(locale, "dM")
                java.time.format.DateTimeFormatter.ofPattern(p, locale).format(t) + ", " + time
            }
        }
    }

    /** Status eines Partners als Text ("Nicht stören bis 14:30 · Im Kundentermin"); Verfuegbar -> "" */
    fun peerText(res: android.content.res.Resources, locale: java.util.Locale, p: PeerStatus?, withText: Boolean): String {
        val v = UserStatusLogic.visible(p) ?: return ""
        var s = res.getString(stateRes(v.s))
        if (v.until != null && v.s != "in_call") s += " " + res.getString(R.string.us_until, whenText(res, locale, v.until))
        if (withText && v.text != null) s += " · " + v.text
        return s
    }

    /** Zusatz zum eigenen Stand ("bis 14:30", "Ruhezeit bis 7:00 – zählt als Nicht stören", "darunter: Beschäftigt", Fokus) */
    fun ownHint(res: android.content.res.Resources, locale: java.util.Locale, v: UserStatusView, quietTo: String?): String {
        val sh = UserStatusLogic.shown(v)
        val parts = ArrayList<String>()
        when {
            sh.quiet -> parts += if (quietTo != null) res.getString(R.string.us_quiet_until, quietTo)
            else res.getString(R.string.us_quiet_hint)
            sh.under != null -> parts += res.getString(R.string.us_under, res.getString(stateRes(sh.under)))
            sh.until != null -> parts += res.getString(R.string.us_until, whenText(res, locale, sh.until))
        }
        if (sh.focus) parts += res.getString(R.string.us_focus_device)
        return parts.joinToString(" · ")
    }

    /** Sammelmeldung als Satz - nur Zahlen, nie Namen ("Während „Nicht stören“: 4 Nachrichten, 1 verpasster Anruf") */
    fun digest(d: StatusDigest): UiText? {
        val parts = listOfNotNull(
            d.messages.takeIf { it > 0 }?.let { UiText.Plural(R.plurals.us_digest_messages, it) },
            d.calls.takeIf { it > 0 }?.let { UiText.Plural(R.plurals.us_digest_calls, it) },
            d.other.takeIf { it > 0 }?.let { UiText.Plural(R.plurals.us_digest_other, it) },
        )
        if (parts.isEmpty()) return null
        val items = parts.drop(1).fold(parts[0] as UiText) { a, b -> UiText.Res(R.string.us_join, a, b) }
        return UiText.Res(R.string.us_digest, items)
    }
}
