package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.push.KeepAlive
import io.github.yggdrasil_sh.saga.push.PushLocal
import io.github.yggdrasil_sh.saga.push.PushLogic
import io.github.yggdrasil_sh.saga.push.StreamAfter
import io.github.yggdrasil_sh.saga.push.StreamBackoff
import io.github.yggdrasil_sh.saga.push.StreamClose
import io.github.yggdrasil_sh.saga.push.StreamCost
import io.github.yggdrasil_sh.saga.push.StreamDeath
import io.github.yggdrasil_sh.saga.push.StreamFrames
import io.github.yggdrasil_sh.saga.push.StreamMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 2.5: eigene Push-Verbindung ohne Verteiler - Rahmen, Schliess-Codes, lernender Abstand, Verbrauch im Leerlauf */
class PushStreamTest {
    @Test
    fun frames() {
        val msg = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 1, 2, 9, 8, 7)
        val m = StreamFrames.parse(msg) as StreamFrames.In.Message
        assertEquals(258L, m.id)
        assertArrayEquals(byteArrayOf(9, 8, 7), m.body)
        assertEquals(StreamFrames.In.Pong, StreamFrames.parse(byteArrayOf(4)))
        assertEquals(StreamFrames.In.Unknown, StreamFrames.parse(byteArrayOf(1, 0, 0)))          // zu kurz
        assertEquals(StreamFrames.In.Unknown, StreamFrames.parse(byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 1, 2), StreamFrames.ack(listOf(258L)))
        assertEquals(17, StreamFrames.ack(listOf(1L, 2L)).size)
        assertArrayEquals(byteArrayOf(3), StreamFrames.ping)
    }

    @Test
    fun closeCodes() {
        assertEquals(StreamAfter.STOP_AUTH, StreamClose.after(4401))
        assertEquals(StreamAfter.RESUBSCRIBE, StreamClose.after(4404))
        assertEquals(StreamAfter.STOP_REPLACED, StreamClose.after(4409))
        assertEquals(StreamAfter.RECONNECT_LATER, StreamClose.after(4429))
        assertEquals(StreamAfter.RECONNECT, StreamClose.after(1012))
        assertEquals(StreamAfter.RECONNECT, StreamClose.after(-1))
    }

    @Test
    fun keepAliveLadder() {
        val k = KeepAlive()
        assertEquals(9 * 60_000L, k.intervalMs)
        assertTrue("Wecker frueher als die Stufe (inexakte Wecker)", k.alarmDelay < k.intervalMs && k.alarmDelay * 1.75 <= k.intervalMs * 1.26)
        repeat(3) { k.onPong(0) }
        assertEquals(14 * 60_000L, k.intervalMs)
        repeat(6) { k.onPong(0) }
        assertEquals(28 * 60_000L, k.intervalMs)
        repeat(10) { k.onPong(0) }
        assertEquals("oberste Stufe bleibt", 28 * 60_000L, k.intervalMs)
        k.onSilentDeath(1000)
        assertEquals(20 * 60_000L, k.intervalMs)
        repeat(10) { k.onPong(2000) }
        assertEquals("zu lange Stufe 24 h gesperrt", 20 * 60_000L, k.intervalMs)
        repeat(3) { k.onPong(1000 + KeepAlive.CEIL_MS) }
        assertEquals("nach 24 h wieder versuchen", 28 * 60_000L, k.intervalMs)
        val d = KeepAlive.decode(k.encode())
        assertEquals(k.encode(), d.encode())
        assertEquals(KeepAlive.START, KeepAlive.decode("kaputt").level)
        assertEquals(KeepAlive.START, KeepAlive.decode(null).level)
        val low = KeepAlive(level = 0)
        low.onSilentDeath(0)
        assertEquals("unterste Stufe bleibt", 0, low.level)
        assertTrue(low.low)
    }

    @Test
    fun idleDeathOnlyAfterSilence() {
        val i = 540_000L
        assertTrue(StreamDeath.idleDeath(true, true, true, true, 600_000, 300_000, i))
        assertFalse("gerade etwas empfangen (z. B. kaputter Rahmen)", StreamDeath.idleDeath(true, true, true, true, 600_000, 1_000, i))
        assertFalse("Netzwechsel", StreamDeath.idleDeath(true, true, true, false, 600_000, 300_000, i))
        assertFalse("offline", StreamDeath.idleDeath(true, true, false, true, 600_000, 300_000, i))
        assertFalse("sauber geschlossen", StreamDeath.idleDeath(false, true, true, true, 600_000, 300_000, i))
        assertFalse("kurz nach dem Aufbau", StreamDeath.idleDeath(true, true, true, true, 10_000, 300_000, i))
    }

    @Test
    fun backoff() {
        assertEquals(0L, StreamBackoff.delay(0, 0.5))
        assertEquals(5_000L, StreamBackoff.delay(1, 0.5))
        assertEquals(10_000L, StreamBackoff.delay(2, 0.5))
        assertTrue(StreamBackoff.delay(30, 1.0) <= StreamBackoff.MAX_MS)
        assertTrue(StreamBackoff.delay(3, 0.0) in 15_000L..20_000L)
        assertTrue(StreamBackoff.restart(0.0) >= 2_000 && StreamBackoff.restart(1.0) <= 12_000)
    }

    @Test
    fun modeAndSteps() {
        assertTrue(StreamMode.run(pushOn = true, allowed = true, distributors = 0, signedIn = true))
        assertFalse(StreamMode.run(pushOn = true, allowed = true, distributors = 1, signedIn = true))
        assertFalse(StreamMode.run(pushOn = true, allowed = false, distributors = 0, signedIn = true))
        val st = PushLocal(PushLogic.STREAM, "t", "stream", 5)
        val up = PushLocal("io.heckel.ntfy", "t", "https://ntfy.example/up1", 6)
        assertEquals(PushLogic.Step.NONE, PushLogic.step(null, emptyList(), true, true))
        assertEquals(PushLogic.Step.START_STREAM, PushLogic.step(st, emptyList(), true, null))
        assertEquals(PushLogic.Step.START_STREAM, PushLogic.step(st, emptyList(), true, true))
        assertEquals("Verteiler installiert -> umschalten", PushLogic.Step.TO_DISTRIBUTOR, PushLogic.step(st, listOf("io.heckel.ntfy"), true, true))
        assertEquals("Schalter aus -> Abo weg", PushLogic.Step.DROP, PushLogic.step(st, emptyList(), false, true))
        assertEquals("Admin hat den Weg abgeschaltet", PushLogic.Step.DROP, PushLogic.step(st, emptyList(), true, false))
        assertEquals("Verteiler deinstalliert -> eigene Verbindung", PushLogic.Step.TO_STREAM, PushLogic.step(up, emptyList(), true, true))
        assertEquals(PushLogic.Step.STOP_STREAM, PushLogic.step(up, emptyList(), false, true))
        assertEquals(PushLogic.Step.STOP_STREAM, PushLogic.step(up, listOf("io.heckel.ntfy"), true, true))
        assertTrue(PushLogic.backgroundReady(st, emptyList(), true))
        assertFalse(PushLogic.backgroundReady(st, emptyList(), false))
        assertFalse(PushLogic.backgroundReady(st, listOf("io.heckel.ntfy"), true))
        assertTrue(PushLogic.backgroundReady(up, listOf("io.heckel.ntfy")))
    }

    /** Ergebnis einer Simulation: Weckungen (Lebenszeichen + Wiederaufbauten) und Bytes je Stunde im eingeschwungenen Zustand */
    private data class Sim(val wakeups: Double, val bytes: Double, val level: Long)

    /**
     * 48 h Leerlauf hinter einem NAT/Proxy, das ruhende Verbindungen nach [natMs] vergisst. Android stellt jeden
     * inexakten Wecker zufaellig zwischen alarmDelay und 1,75 x alarmDelay zu. Gezaehlt werden die letzten 24 h.
     * Ein Wiederaufbau kostet eine Weckung und ~6 KB (TCP + TLS 1.3 + WebSocket-Handshake).
     */
    private fun simulate(natMs: Long, seed: Long = 1): Sim {
        val rnd = Random(seed)
        val k = KeepAlive()
        var t = 0L
        var wakes = 0
        var bytes = 0.0
        val day = 24 * 3600_000L
        while (t < 2 * day) {
            val gap = (k.alarmDelay * (1.0 + 0.75 * rnd.nextDouble())).toLong()
            t += gap
            val count = t > day
            if (gap > natMs) {
                // Lebenszeichen ohne Antwort (Wecker + Wartezeit), Stufe zurueck, neu verbinden
                k.onSilentDeath(t)
                if (count) {
                    wakes += 2
                    bytes += StreamCost.PING_WIRE + 6_000
                }
            } else {
                k.onPong(t)
                if (count) {
                    wakes++
                    bytes += StreamCost.CYCLE
                }
            }
        }
        return Sim(wakes / 24.0, bytes / 24.0, k.intervalMs / 60_000)
    }

    @Test
    fun idleCostPerHour() {
        // typische Grenzen: Mobilfunk-CGNAT 10-30 min, Heimrouter 30 min bis Stunden, Proxy mit 1 h
        val cases = listOf(10L, 15L, 30L, 60L, 120L)
        val out = StringBuilder("NAT-Zeit | Stufe | Weckungen/h | Bytes/h\n")
        for (m in cases) {
            val s = simulate(m * 60_000)
            out.append("%3d min | %2d min | %5.1f | %6.0f\n".format(m, s.level, s.wakeups, s.bytes))
            assertTrue("NAT $m min: hoechstens 10 Netzweckungen pro Stunde (${s.wakeups})", s.wakeups <= 10.0)
        }
        println(out)
        // Ziel: ab 15 min NAT-Zeit hoechstens ~6 Weckungen/h und unter 2 KB/h
        val s15 = simulate(15 * 60_000)
        assertTrue("15 min: ${s15.wakeups}", s15.wakeups <= 9.5)
        val s60 = simulate(60 * 60_000)
        assertTrue("60 min: ${s60.wakeups}", s60.wakeups <= 3.5 && s60.bytes < 1_000)
        // Byte-Bilanz eines Lebenszeichens
        assertEquals(81, StreamCost.PING_WIRE)
        assertEquals(77, StreamCost.PONG_WIRE)
        assertEquals(262, StreamCost.CYCLE)
        assertEquals(6.0, StreamCost.wakeupsPerHour(10 * 60_000L), 0.001)
    }

    @Test
    fun shortNatStillWorks() {
        // Proxy trennt nach 60 s (nginx ohne proxy_read_timeout): unterste Stufe, Hinweis in den Einstellungen
        val s = simulate(60_000)
        assertEquals(0L, s.level)            // 45 s
        val k = KeepAlive(level = 0)
        assertTrue(k.low)
    }
}
