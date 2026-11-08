package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wiederaufbau eines Knoten-Anrufs nach Verbindungsverlust (Saga 2.5, Horn-Fix). Reine Entscheidungslogik ohne
 * WebRTC/Netz, gleiche Regeln wie iOS App/HornRecover.swift:
 * - immer ueber einen Knoten neu verbinden, nie direkt (p2p): es gibt keinen solchen Schritt
 * - den einzigen bekannten Knoten nicht meiden (avoid leer), andere nur, wenn noch einer uebrig bleibt
 * - waehrend ein Versuch laeuft oder geplant ist, bleibt die Beobachtung stumm (kein neues "switch lost")
 * - Backoff 1/2/4/8/15 s mit +-20 % Zufall; Rueckstellung erst, wenn die neue Verbindung >= 10 s Daten liefert;
 *   Verlust < 10 s nach dem Uebernehmen zaehlt als Fehlversuch
 * - 429 vom Server mit retry_after (bzw. Retry-After): so lange warten (hoechstens 60 s), kein "kein Knoten"
 * - nach 120 s ohne Erfolg aufgeben
 */
class HornRecover {
    sealed class Step {
        /** naechster Versuch in ms (0 = sofort) */
        data class Wait(val ms: Long) : Step()
        /** aufgeben (Anruf mit Meldung beenden) */
        object GiveUp : Step()
    }

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
    fun lost(now: Long, rnd: Double): Step? {
        if (busy) return null
        if (active && adoptAt > 0 && now - adoptAt < SETTLE_MS) {
            adoptAt = 0                                             // neue Verbindung hielt nicht: Fehlversuch
            busy = true
            return next(now, null, rnd)
        }
        active = true; t0 = now; fails = 0; adoptAt = 0; busy = true
        return Step.Wait(0)
    }

    /** Versuch gescheitert; retryAfter = Wartezeit aus 429 (s), null = normaler Backoff */
    fun failed(now: Long, retryAfter: Int?, rnd: Double): Step {
        if (!active) { active = true; t0 = now; fails = 0; adoptAt = 0 }
        busy = true
        return next(now, retryAfter, rnd)
    }

    private fun next(now: Long, retryAfter: Int?, rnd: Double): Step {
        val left = MAX_MS - (now - t0)
        if (left <= 0) { busy = false; return Step.GiveUp }
        val ms = if (retryAfter != null) retryAfter.coerceIn(1, MAX_WAIT) * 1000L     // 429: kein Fehlversuch
        else { fails++; delayMs(fails, rnd) }
        return Step.Wait(minOf(ms, left))                           // letzter Versuch genau an der Frist
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
        active = false; t0 = 0; fails = 0; adoptAt = 0
        return true
    }

    fun expired(now: Long): Boolean = active && now - t0 >= MAX_MS

    companion object {
        val STEPS = longArrayOf(1000, 2000, 4000, 8000, 15000)
        const val JITTER = 0.2
        const val MAX_MS = 120_000L
        const val SETTLE_MS = 10_000L
        const val MAX_WAIT = 60

        /** Backoff fuer den n-ten Fehlversuch (1 ...): 1, 2, 4, 8, 15, 15 ... s, rnd 0..<1 -> +-20 % */
        fun delayMs(fails: Int, rnd: Double): Long {
            val b = STEPS[fails.coerceIn(1, STEPS.size) - 1]
            return Math.round(b * (1 - JITTER + 2 * JITTER * rnd.coerceIn(0.0, 1.0)))
        }

        /** Zu meidende Knoten: bad/verlorene nur, wenn danach noch ein bekannter Knoten uebrig bleibt */
        fun avoid(bad: Collection<String>, known: Collection<String>): List<String> {
            val a = bad.toSortedSet()
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
    }
}
