package io.github.yggdrasil_sh.saga.push

import java.nio.ByteBuffer

/**
 * Eigene Push-Verbindung ohne Verteiler (Server 2.5, GET /api/push/stream) - reine Logik, ohne Android (Unit-Tests).
 *
 * Grundsatz: jede Netzaktivitaet weckt das Funkmodul und meist die CPU. Der Server sendet von sich aus nichts ausser
 * Mitteilungen; die App schickt nur so oft ein Lebenszeichen, wie NAT/Router/Proxy es fuer eine ruhende TCP-Verbindung
 * verlangen. Diesen Abstand lernt [KeepAlive] je Netz-Art (WLAN/Mobil/sonst).
 */
object StreamFrames {
    const val MSG: Byte = 1
    const val ACK: Byte = 2
    const val PING: Byte = 3
    const val PONG: Byte = 4

    val ping: ByteArray = byteArrayOf(PING)

    sealed class In {
        data class Message(val id: Long, val body: ByteArray) : In()
        data object Pong : In()
        data object Unknown : In()
    }

    fun parse(b: ByteArray): In = when {
        b.size == 1 && b[0] == PONG -> In.Pong
        b.size > 9 && b[0] == MSG -> In.Message(ByteBuffer.wrap(b, 1, 8).long, b.copyOfRange(9, b.size))
        else -> In.Unknown
    }

    fun ack(ids: List<Long>): ByteArray {
        val bb = ByteBuffer.allocate(1 + 8 * ids.size)
        bb.put(ACK)
        ids.forEach { bb.putLong(it) }
        return bb.array()
    }
}

/** Schliess-Codes des Servers -> was tun */
enum class StreamAfter { RECONNECT, RECONNECT_LATER, RESUBSCRIBE, STOP_AUTH, STOP_REPLACED }

object StreamClose {
    fun after(code: Int): StreamAfter = when (code) {
        4401 -> StreamAfter.STOP_AUTH            // Geraet abgemeldet/Konto gesperrt: erst nach neuer Anmeldung
        4404 -> StreamAfter.RESUBSCRIBE          // Abo fehlt oder Weg abgeschaltet: GET /api/push, ggf. neu anmelden
        4409 -> StreamAfter.STOP_REPLACED        // eine neuere Verbindung dieses Geraets hat uebernommen
        4429 -> StreamAfter.RECONNECT_LATER
        else -> StreamAfter.RECONNECT            // 1000/1001/1006/1012 ...
    }
}

/**
 * Lernender Abstand der Lebenszeichen (wie es auch FCM intern macht): Stufen 45 s, 2, 4, 7, 9, 14, 20, 28 min, Start bei 9 min; nach [UP_AFTER] beantworteten
 * Lebenszeichen eine Stufe hoeher; stirbt die RUHENDE Verbindung (keine Antwort / Abbruch ohne Netzwechsel), eine
 * Stufe zurueck und die zu lange Stufe fuer dieses Netz [CEIL_MS] lang sperren. Die unteren Stufen (45 s, 2 min) gibt
 * es nur fuer Netze/Proxys, die ruhende Verbindungen frueh trennen (z. B. nginx ohne proxy_read_timeout).
 *
 * Android stellt den Wecker (setAndAllowWhileIdle, ohne Sonderrecht fuer exakte Wecker) bis zu 75 % spaeter zu,
 * um Weckungen zu buendeln. Der Wecker wird deshalb auf [alarmDelay] = Stufe / 1,4 gestellt: im ungünstigsten Fall
 * liegt das Lebenszeichen ~25 % hinter der Stufe, meist (Geraet wach, Wecker gebuendelt) davor.
 */
class KeepAlive(var level: Int = START, var ok: Int = 0, var ceil: Int = LEVELS_MS.size, var ceilUntil: Long = 0) {
    companion object {
        val LEVELS_MS = longArrayOf(45_000, 120_000, 240_000, 420_000, 540_000, 840_000, 1_200_000, 1_680_000)
        const val START = 4                       // 9 min
        const val UP_AFTER = 3
        const val CEIL_MS = 24 * 3600_000L
        const val SLACK = 1.4                     // Wecker frueher stellen (Android verschiebt inexakte Wecker)
        const val LOW = 2                         // unter 4 min: Hinweis in den Einstellungen

        fun decode(s: String?): KeepAlive {
            val p = s?.split('|')?.mapNotNull { it.toLongOrNull() } ?: return KeepAlive()
            if (p.size != 4) return KeepAlive()
            return KeepAlive(p[0].toInt().coerceIn(0, LEVELS_MS.size - 1), p[1].toInt().coerceAtLeast(0),
                p[2].toInt().coerceIn(1, LEVELS_MS.size), p[3])
        }
    }

    fun encode(): String = "$level|$ok|$ceil|$ceilUntil"

    val intervalMs: Long get() = LEVELS_MS[level]

    /** Wecker so viel spaeter stellen (siehe oben) */
    val alarmDelay: Long get() = (intervalMs / SLACK).toLong()

    private fun maxLevel(now: Long): Int = if (now < ceilUntil) ceil - 1 else LEVELS_MS.size - 1

    /** Lebenszeichen beantwortet */
    fun onPong(now: Long) {
        ok++
        if (ok >= UP_AFTER && level < maxLevel(now)) {
            level++
            ok = 0
        }
    }

    /** Ruhende Verbindung gestorben (keine Antwort bzw. Abbruch ohne Netzwechsel) */
    fun onSilentDeath(now: Long) {
        if (level > 0) {
            ceil = level
            ceilUntil = now + CEIL_MS
            level--
        }
        ok = 0
    }

    val low: Boolean get() = level < LOW
}

/** Wartezeit vor dem naechsten Aufbau nach Fehlschlaegen (mit Zufall, damit nach einem Neustart nicht alle zugleich) */
object StreamBackoff {
    const val MAX_MS = 30 * 60_000L

    fun delay(fails: Int, rnd: Double): Long {
        if (fails <= 0) return 0
        val base = (5_000L shl (fails - 1).coerceAtMost(10)).coerceAtMost(MAX_MS)
        return (base * (0.75 + 0.5 * rnd)).toLong().coerceAtMost(MAX_MS)
    }

    /** Server startet neu (1012): kurze, zufaellige Wartezeit 2-12 s */
    fun restart(rnd: Double): Long = 2_000L + (10_000 * rnd).toLong()
}

/** Soll die eigene Verbindung laufen? (rein) */
object StreamMode {
    /**
     * @param pushOn Mitteilungen auf diesem Geraet eingeschaltet und Abo vom Typ "stream" vorhanden
     * @param allowed Schalter "Eigene Verbindung" (Standard an)
     * @param distributors installierte UnifiedPush-Verteiler
     */
    fun run(pushOn: Boolean, allowed: Boolean, distributors: Int, signedIn: Boolean): Boolean =
        pushOn && allowed && distributors == 0 && signedIn
}

/**
 * Was die Verbindung im Leerlauf kostet (fuer die Anzeige und den Unit-Test). Bytes auf der Leitung je Lebenszeichen:
 * App -> Server 0x03 als WebSocket-Rahmen (2 Kopf + 4 Maske + 1) in einem TLS-1.3-Datensatz (5 + 1 + 16), Server ->
 * App 0x04 (2 + 1 + 22), je mit TCP/IP-Koepfen (IPv4 20 + TCP 32 mit Zeitstempeln) und zwei reinen ACKs.
 */
object StreamCost {
    const val TCPIP = 52
    const val PING_WIRE = 7 + 22 + TCPIP           // 81
    const val PONG_WIRE = 3 + 22 + TCPIP           // 77
    const val ACKS_WIRE = 2 * TCPIP                // 104
    const val CYCLE = PING_WIRE + PONG_WIRE + ACKS_WIRE

    /** Weckungen je Stunde bei Abstand [intervalMs] (eine je Lebenszeichen; die Antwort kommt im selben Wachfenster) */
    fun wakeupsPerHour(intervalMs: Long): Double = 3_600_000.0 / intervalMs

    fun bytesPerHour(intervalMs: Long): Double = wakeupsPerHour(intervalMs) * CYCLE
}
