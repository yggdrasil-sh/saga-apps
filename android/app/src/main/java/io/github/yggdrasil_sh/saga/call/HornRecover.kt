package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wiederaufbau eines Knoten-Anrufs nach Verbindungsverlust (Saga 2.5, Horn-Fix). Reine Entscheidungslogik ohne
 * WebRTC/Netz, gleiche Regeln wie iOS App/HornRecover.swift:
 * - zuerst 30 s lang ueber einen Knoten neu verbinden; erst danach (Horn faellt aus) direkt anfragen (Vertrag horn_direct1),
 *   wenn moeglich (canDirect); node_ready -> weiter ueber Knoten, limit/fed -> kein Direkt-Weg
 * - den verlorenen Knoten nie meiden (zuletzt versuchen), andere nur, wenn noch einer uebrig bleibt
 * - waehrend ein Versuch laeuft oder geplant ist, bleibt die Beobachtung stumm (kein neues "switch lost")
 * - Backoff 1/2/4/8/15 s mit +-20 % Zufall; Rueckstellung erst, wenn die neue Verbindung >= 10 s Daten liefert;
 *   Verlust < 10 s nach dem Uebernehmen zaehlt als Fehlversuch
 * - 429 vom Server mit retry_after (bzw. Retry-After): max(retry_after, Backoff), retry_after hoechstens 60 s
 * - nach 120 s ohne Erfolg aufgeben (nur, wenn auch direkt nicht moeglich ist)
 */
class HornRecover {
    sealed class Step {
        /** naechster Versuch in ms (0 = sofort) */
        data class Wait(val ms: Long) : Step()
        /** Knoten nicht erreichbar: direkt anfragen (POST /direct) */
        object Direct : Step()
        /** aufgeben (Anruf mit Meldung beenden) */
        object GiveUp : Step()
    }

    /** Antwort auf POST /api/calls/{cid}/direct */
    sealed class DirectAnswer {
        data class Switched(val offerTo: List<String>) : DirectAnswer()
        object NodeReady : DirectAnswer()
        data class NoPath(val block: String) : DirectAnswer()
        data class Busy(val after: Int?) : DirectAnswer()
        object Ended : DirectAnswer()
        object Error : DirectAnswer()
    }

    /** Server hat direkt abgelehnt (limit/fed): in diesem Anruf nicht mehr fragen */
    var noDirect = false
        private set

    /** Wiederaufbau laeuft (bis Erfolg oder Aufgabe) */
    var active = false
        private set
    var t0 = 0L
        private set
    var fails = 0
        private set
    /** Versuch laeuft oder ist geplant */
    var busy = false
        private set
    var adoptAt = 0L
        private set

    /** Beobachtung stumm: kein neues "switch lost", kein Wechsel zu "besseren" Knoten */
    val muted: Boolean get() = busy

    /** Verbindung verloren. null = laeuft schon (ignorieren). */
    fun lost(now: Long, rnd: Double, canDirect: Boolean = false): Step? {
        if (busy) return null
        if (active && adoptAt > 0 && now - adoptAt < SETTLE_MS) {
            adoptAt = 0                                             // neue Verbindung hielt nicht: Fehlversuch
            busy = true
            return next(now, null, rnd, canDirect)
        }
        active = true; t0 = now; fails = 0; adoptAt = 0; busy = true
        return Step.Wait(0)
    }

    /** Versuch gescheitert; retryAfter = Wartezeit aus 429 (s), null = normaler Backoff */
    fun failed(now: Long, retryAfter: Int?, rnd: Double, canDirect: Boolean = false): Step {
        if (!active) { active = true; t0 = now; fails = 0; adoptAt = 0 }
        busy = true
        return next(now, retryAfter, rnd, canDirect)
    }

    private fun next(now: Long, retryAfter: Int?, rnd: Double, canDirect: Boolean): Step {
        val left = MAX_MS - (now - t0)
        if (left <= 0) { busy = false; return Step.GiveUp }         // auch direkt ging nicht
        if (canDirect && !noDirect) {
            val w = WINDOW_MS - (now - t0)
            if (w <= 0) return Step.Direct                          // Horn faellt aus: direkt fragen (busy bleibt)
            return wait(retryAfter, rnd, w)                         // letzter Knoten-Versuch am Fensterende
        }
        return wait(retryAfter, rnd, left)
    }

    private fun wait(retryAfter: Int?, rnd: Double, left: Long): Step {
        fails++
        var ms = delayMs(fails, rnd)
        if (retryAfter != null) ms = maxOf(ms, retryAfter.coerceIn(1, MAX_WAIT) * 1000L)   // 429: max(retry_after, Backoff)
        return Step.Wait(minOf(ms, left))                           // letzter Versuch genau an der Frist
    }

    /** Ergebnis der Direkt-Anfrage. null = umgeschaltet bzw. Anruf vorbei, sonst naechster Schritt (ueber Knoten) */
    fun direct(a: DirectAnswer, now: Long, rnd: Double): Step? {
        return when (a) {
            is DirectAnswer.Switched, DirectAnswer.Ended -> { reset(); null }
            is DirectAnswer.NoPath -> { noDirect = true; next(now, null, rnd, false) }
            is DirectAnswer.Busy -> nodeAgain(now, a.after, rnd)
            DirectAnswer.NodeReady, DirectAnswer.Error -> nodeAgain(now, null, rnd)
        }
    }

    /** nach abgelehnter Direkt-Anfrage: erst wieder ueber den Knoten (kein sofortiges erneutes Direct) */
    private fun nodeAgain(now: Long, retryAfter: Int?, rnd: Double): Step {
        val left = MAX_MS - (now - t0)
        if (left <= 0) { busy = false; return Step.GiveUp }
        return wait(retryAfter, rnd, left)
    }

    private fun reset() {
        active = false; t0 = 0; fails = 0; adoptAt = 0; busy = false; noDirect = false
    }

    /** Neue Verbindung uebernommen (der Versuch ist fertig; noch nicht zurueckgestellt) */
    fun adopted(now: Long) {
        if (!active) return
        busy = false
        adoptAt = now
    }

    /** Laufende Beobachtung: rx = die aktuelle Verbindung liefert Daten. true = Wiederaufbau erfolgreich beendet. */
    fun tick(now: Long, rx: Boolean): Boolean {
        if (!active || busy || adoptAt == 0L || !rx || now - adoptAt < SETTLE_MS) return false
        val nd = noDirect
        reset()
        noDirect = nd                                               // limit/fed gilt fuer den ganzen Anruf
        return true
    }

    fun expired(now: Long): Boolean = active && now - t0 >= MAX_MS

    companion object {
        val STEPS = longArrayOf(1000, 2000, 4000, 8000, 15000)
        const val JITTER = 0.2
        const val MAX_MS = 120_000L
        /** so lange nur ueber Knoten; danach direkt (wenn moeglich) */
        const val WINDOW_MS = 30_000L
        const val SETTLE_MS = 10_000L
        const val MAX_WAIT = 60

        /** Backoff fuer den n-ten Fehlversuch (1 ...): 1, 2, 4, 8, 15, 15 ... s, rnd 0..<1 -> +-20 % */
        fun delayMs(fails: Int, rnd: Double): Long {
            val b = STEPS[fails.coerceIn(1, STEPS.size) - 1]
            return Math.round(b * (1 - JITTER + 2 * JITTER * rnd.coerceIn(0.0, 1.0)))
        }

        /** Zu meidende Knoten: der verlorene nie (zuletzt versuchen), andere nur, wenn danach noch ein bekannter uebrig bleibt */
        fun avoid(bad: Collection<String>, known: Collection<String>, lost: String? = null): List<String> {
            val a = bad.toSortedSet().also { if (lost != null) it.remove(lost) }
            val k = known.toSet()
            if (k.size <= 1 || (k - a).isEmpty()) return emptyList()
            return a.toList()
        }

        /**
         * 429-Vertrag: Status 429, Body {"error": ..., "retry_after": <s>} bzw. Header Retry-After -> Sekunden (1 ... 60).
         * null = kein 429 oder aelterer Server ohne Angabe (dann normaler Backoff).
         */
        fun retryAfter(status: Int, body: JsonObject?, header: String? = null): Int? {
            if (status != 429) return null
            val p = body?.get("retry_after") as? JsonPrimitive
            var s: Long? = null
            if (p != null && p.content != "true" && p.content != "false") s = p.longOrNull ?: p.content.trim().toLongOrNull()
            if (s == null) s = header?.trim()?.toLongOrNull()
            if (s == null || s < 0) return null
            return s.coerceIn(1, MAX_WAIT.toLong()).toInt()
        }

        /** Antwort von POST /direct auswerten (status null = Netzfehler) */
        fun directAnswer(status: Int?, body: JsonObject?): DirectAnswer {
            val st = status ?: return DirectAnswer.Error
            if (st in 200..299) {
                val topo = ((body?.get("call") as? JsonObject)?.get("topo") as? JsonPrimitive)?.content
                if (topo != null && topo != "mesh") return DirectAnswer.Error
                val to = (body?.get("offer_to") as? kotlinx.serialization.json.JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                return DirectAnswer.Switched(to)
            }
            if (st == 404) return DirectAnswer.Ended
            if (st == 429) return DirectAnswer.Busy(retryAfter(st, body))
            if (st == 409) {
                val b = (body?.get("horn_block") as? JsonPrimitive)?.content.orEmpty()
                return if (b == "node_ready") DirectAnswer.NodeReady else DirectAnswer.NoPath(b.ifEmpty { "?" })
            }
            return DirectAnswer.Error
        }

        /** Mesh nach dem Wechsel: Angebote nur an Endpunkte mit groesserer Geraete-ID (kein Glare) */
        fun offerTargets(devices: Collection<String>, me: String): List<String> = devices.filter { it > me }.toSortedSet().toList()

        /** Knoten, die in den letzten 30 s nicht erreichbar waren (bad: Knoten -> gemieden bis, ms) */
        fun unreached(bad: Map<String, Long>, now: Long, badMs: Long): List<String> =
            bad.filter { it.value - badMs > now - WINDOW_MS }.keys.sorted()

        /** Hinweis beim Anrufstart (409 fallback direct): "node" -> Knoten weg, "old"/"peer" -> Gegenstelle veraltet */
        fun startNote(block: String?): String? = when (block) {
            "node" -> "node"
            "old", "peer" -> "old"
            else -> null
        }
    }
}
