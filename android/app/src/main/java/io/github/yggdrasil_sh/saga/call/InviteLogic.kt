package io.github.yggdrasil_sh.saga.call

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 2.5 (L7) "Erneut klingeln": Abschnitt "Nicht dabei" der Teilnehmerliste eines Gruppenanrufs (Mesh und Knoten).
 * Vertrag: Server docs/APP-API.md "Anrufe: Erneut klingeln und »Nicht dabei«". Reine Regeln ohne Android (Unit-Tests:
 * InviteLogicTest) - Zustand je Person, Knopf, Reihenfolge, Stand `rv`, Ablauf des Klingelns, eigenes erneutes Klingeln.
 */
object Invites {
    /** Hoechstzahl je /invite ("Alle erneut klingeln") und je Liste */
    const val MAX = 50
    /** Vorlaeufiges "klingelt …" nach dem Klick, bis die Antwort kommt (danach gilt die Liste des Servers) */
    const val PEND_MS = 15_000L
    /** Hinweis unter dem Abschnitt (Fehler, "wird angerufen …") so lange zeigen */
    const val NOTE_MS = 6_000L
    /** Ohne Angabe des Servers */
    const val RING_S = 45

    /** Zustaende in der Reihenfolge der Liste */
    enum class St(val key: String) { RINGING("ringing"), DECLINED("declined"), MISSED("missed"), LEFT("left"), NONE("none"), REMOVED("removed"), OLD("old") }

    /** Knopf je Zeile; FULL = Klingeln bzw. Erneut klingeln gesperrt, weil der Anruf voll ist */
    enum class Btn { NONE, RING, RING_AGAIN, OLD, FULL }

    /** Unbekannte Zustaende wie `none` */
    fun st(s: String?): St = St.entries.firstOrNull { it.key == s } ?: St.NONE

    /**
     * Stand des Abschnitts "Nicht dabei" fuer die Oberflaeche. skewMs = Server-Uhr minus Geraete-Uhr (ms); pend = vorlaeufig
     * klingelnde Personen (who -> bis, Geraete-Uhr ms); host = ich habe den Anruf begonnen (darf Entfernte klingeln lassen).
     */
    data class Snapshot(
        val list: List<CallInvite>,
        val more: Int = 0,
        val skewMs: Long = 0,
        val ringS: Int = RING_S,
        val pend: Map<String, Long> = emptyMap(),
        val host: Boolean = false,
        val note: UiText? = null,
        /** 2.5 (Bugs-DB 2154): alle Plaetze belegt - Klingeln hilft niemandem (Beitritt ginge nicht), Knoepfe gesperrt wie im Web */
        val full: Boolean = false,
    )

    data class Row(val x: CallInvite, val st: St, val btn: Btn)

    /** Zustand einer Person zur Zeit nowMs (Geraete-Uhr): vorlaeufiges Klingeln, "klingelt" nur bis since + ring_s */
    fun state(x: CallInvite, s: Snapshot, nowMs: Long): St {
        val p = s.pend[x.who]
        if (p != null && p > nowMs) return St.RINGING
        val st = st(x.state)
        if (st == St.RINGING) {
            val since = x.since ?: return st
            if ((since + s.ringS) * 1000.0 <= nowMs + s.skewMs) return St.MISSED
        }
        return st
    }

    /** Knopf: klingelt schon -> keiner; entfernt -> nur beim Gastgeber; App zu alt -> deaktiviert; nie geklingelt -> "Klingeln" */
    fun button(st: St, host: Boolean, full: Boolean = false): Btn = when (st) {
        St.RINGING -> Btn.NONE
        St.REMOVED -> if (!host) Btn.NONE else if (full) Btn.FULL else Btn.RING_AGAIN
        St.OLD -> Btn.OLD
        else -> if (full) Btn.FULL else if (st == St.NONE) Btn.RING else Btn.RING_AGAIN
    }

    /** Zeilen in der Reihenfolge der Zustaende, darin nach Name (wie der Server) */
    fun rows(s: Snapshot, nowMs: Long): List<Row> = s.list.filter { it.who.isNotEmpty() }.take(MAX)
        .map { x -> state(x, s, nowMs).let { st -> Row(x, st, button(st, s.host, s.full)) } }
        .sortedWith(compareBy<Row>({ it.st.ordinal }, { it.x.name.lowercase() }, { it.x.who }))

    /** Abschnitt zeigen? (aelterer Server: keine Liste; alle dabei: leer) */
    fun visible(s: Snapshot?): Boolean = s != null && (s.list.isNotEmpty() || s.more > 0)

    /** Ueberschrift "Nicht dabei (n)" */
    fun count(s: Snapshot): Int = s.list.size + s.more

    /** "Alle erneut klingeln": nur, wenn mehr als eine Person missed/declined/none hat - genau diese (hoechstens 50) */
    fun ringAll(rows: List<Row>): List<String> {
        if (rows.any { it.btn == Btn.FULL }) return emptyList()        // voller Anruf: kein "Alle erneut klingeln" (wie Web)
        val w = rows.filter { it.st == St.MISSED || it.st == St.DECLINED || it.st == St.NONE }.map { it.x.who }
        return if (w.size > 1) w.take(MAX) else emptyList()
    }

    /** Zeit (ms) bis zum naechsten Ablauf eines Klingelns bzw. vorlaeufigen Klingelns; null = keiner */
    fun nextChangeMs(s: Snapshot, nowMs: Long): Long? {
        var next = Long.MAX_VALUE
        for (x in s.list) {
            s.pend[x.who]?.let { if (it > nowMs) next = minOf(next, it - nowMs) }
            val since = x.since
            if (st(x.state) == St.RINGING && since != null) {
                val t = ((since + s.ringS) * 1000.0 - s.skewMs - nowMs).toLong()
                if (t > 0) next = minOf(next, t)
            }
        }
        return next.takeIf { it != Long.MAX_VALUE }
    }

    /** Abstand Server-Uhr minus Geraete-Uhr (ms) aus `now` */
    fun skewMs(serverNow: Double?, deviceMs: Long): Long? = serverNow?.let { (it * 1000.0).toLong() - deviceMs }

    /**
     * Stand der Liste mit eigenem Zaehler `rv` (wie call.roster, aber getrennt - call.roster traegt keine invites): eine
     * Liste nur uebernehmen, wenn rv nicht kleiner ist als das der zuletzt uebernommenen. Ohne rv zaehlt das Eintreffen.
     */
    class Book {
        var list: List<CallInvite>? = null
            private set
        var more = 0
            private set
        var rv = Double.NEGATIVE_INFINITY
            private set
        var skewMs = 0L
            private set
        var ringS = RING_S
            private set
        /** vorlaeufig klingelnd: who -> bis (Geraete-Uhr ms) */
        val pend = LinkedHashMap<String, Long>()
        var note: UiText? = null
        var noteUntil = 0L

        /** Liste uebernehmen (null = Feld fehlt, nichts zu tun); false = veraltet bzw. ohne Liste */
        fun apply(next: List<CallInvite>?, more: Int, rv: Double?, serverNow: Double?, ringS: Int?, deviceMs: Long): Boolean {
            if (next == null) return false
            if (rv != null && rv < this.rv) return false
            if (rv != null) this.rv = rv
            skewMs(serverNow, deviceMs)?.let { skewMs = it }
            ringS?.takeIf { it > 0 }?.let { this.ringS = it }
            list = next
            this.more = maxOf(0, more)
            return true
        }

        /** Klick: diese Personen vorlaeufig "klingelt …" */
        fun pending(who: Collection<String>, deviceMs: Long): Long {
            val until = deviceMs + PEND_MS
            for (w in who) pend[w] = until
            return until
        }

        /** Antwort bzw. Fehler: vorlaeufiges Klingeln dieses Klicks zuruecknehmen (ein neuerer Klick bleibt) */
        fun settle(who: Collection<String>, until: Long) {
            for (w in who) if (pend[w] == until) pend.remove(w)
        }

        fun snapshot(host: Boolean, deviceMs: Long, full: Boolean = false): Snapshot? {
            val l = list ?: return null
            pend.entries.removeAll { it.value <= deviceMs }
            return Snapshot(l, more, skewMs, ringS, HashMap(pend), host, note?.takeIf { noteUntil > deviceMs }, full)
        }
    }

    /** Hinweis zu einem Fehler von /invite: 429 fester Text, 403 (und andere) Text des Servers, sonst allgemein */
    fun errorText(code: Int?, server: UiText?): UiText = when {
        code == 429 -> UiText.Res(R.string.inv_busy)
        server != null && code != null && code in 400..499 -> server
        else -> UiText.Res(R.string.inv_failed)
    }

    /** Hinweis nach dem Klingeln ("Cora Beispiel wird angerufen …" bzw. "3 Personen werden angerufen …") */
    fun doneText(names: List<String>): UiText =
        if (names.size == 1) UiText.Res(R.string.inv_calling_one, names[0]) else UiText.Res(R.string.inv_calling_n, names.size)

    // ------------------------------------------------------------ eigenes (erneutes) Klingeln

    /**
     * Klingelt meine Einladung (GET /api/calls/{id})? Gruppe: `invite == "ringing"`, kein eigenes Geraet in `mine`,
     * Anruf nicht beendet. Das Klingeln war hier schon vorbei/abgelehnt (goneAtMs, Geraete-Uhr): nur, wenn der Server
     * einen spaeteren Beginn `invite_since` meldet (erneut geklingelt) - aeltere Server ohne `invite_since`: nicht.
     */
    fun ringsAgain(info: CallInfo, goneAtMs: Long?, deviceMs: Long): Boolean {
        if (info.kind != CallRoom.KIND_GROUP || info.state == "ended" || info.invite != "ringing" || info.mine.isNotEmpty()) return false
        if (goneAtMs == null) return true
        val since = info.inviteSince ?: return false
        val skew = skewMs(info.now, deviceMs) ?: 0L
        return since * 1000.0 - skew > goneAtMs
    }

    /**
     * Restdauer des eigenen Klingelns (Sekunden) ab `invite_since` statt ab Anrufbeginn: invite_since + ring_s - now.
     * null = aelterer Server (bisherige Regel ueber expires_in bzw. ring_s).
     */
    fun ringLeftS(info: CallInfo): Double? {
        val since = info.inviteSince ?: return null
        val now = info.now ?: return null
        return since + (info.ringS ?: RING_S) - now
    }

    // ------------------------------------------------------------ Ereignis call.invites

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    /** Liste aus JSON (`invites`); null = Feld fehlt */
    fun parse(el: JsonElement?): List<CallInvite>? = (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
        try {
            json.decodeFromJsonElement(CallInvite.serializer(), it).takeIf { x -> x.who.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun num(o: JsonObject?, k: String): Double? = (o?.get(k) as? JsonPrimitive)?.doubleOrNull
    fun int(o: JsonObject?, k: String): Int? = (o?.get(k) as? JsonPrimitive)?.intOrNull
}
