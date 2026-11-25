package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallAdd
import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.CallRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2263-7 (Bugs-DB 2418): Im Knoten-Anruf (`topo horn`) kappte die App die Grenze des Anrufs ohne Horn-Unterscheidung auf
 * die Mesh-Grenze 4/6 (CallManager joinRoom/offerAll/moveIn und CallRoom.runBar) -> "Personen hinzufuegen" ab 4 voll,
 * "voll (4)" in Leiste und Beitritt. Vorher (cfd3063) lieferten dieselben Rechenwege 4 statt 12 (Beleg im Bericht).
 */
class Horn2418Test {
    private fun legs(n: Int) = List(n) { CallLeg(who = "u$it", device = "d$it", devName = "Geraet $it") }

    @Test fun knotenAnrufBehaeltGrenzeDesServers() {
        assertEquals(12, CallRoom.limit("video", 12, horn = true))
        assertEquals(25, CallRoom.limit("video", 25, horn = true))
        assertEquals(50, CallRoom.limit("audio", 50, horn = true))
        assertEquals(100, CallRoom.limit("audio", 100, horn = true))
        // Admin hat gesenkt: Wert des Servers gilt auch unter der Mesh-Grenze; nie unter 2
        assertEquals(3, CallRoom.limit("video", 3, horn = true))
        assertEquals(2, CallRoom.limit("video", 1, horn = true))
        // ohne Wert vom Server: wie bisher die Mesh-Grenze
        assertEquals(CallRoom.MAX_VIDEO, CallRoom.limit("video", null, horn = true))
        assertEquals(CallRoom.MAX_AUDIO, CallRoom.limit("audio", 0, horn = true))
    }

    @Test fun meshBleibtBeiVierUndSechs() {
        assertEquals(4, CallRoom.limit("video", 12, horn = false))
        assertEquals(6, CallRoom.limit("audio", 50, horn = false))
        assertEquals(4, CallRoom.limit("video", 12))
        assertEquals(3, CallRoom.limit("video", 3, horn = false))
    }

    /** CallManager: CallAdd.seats(legs, rs.limit).full steuert "Personen hinzufuegen" */
    @Test fun personenHinzufuegenBeiZwoelf() {
        val lim = CallRoom.limit("video", 12, horn = true)
        assertFalse(CallAdd.seats(legs(4), lim).full)
        assertFalse(CallAdd.seats(legs(5), lim).full)
        assertEquals(1, CallAdd.seats(legs(11), lim).left)
        assertFalse(CallAdd.seats(legs(11), lim).full)
        assertTrue(CallAdd.seats(legs(12), lim).full)
        assertEquals(0, CallAdd.seats(legs(12), lim).left)
        // Ton ueber den Knoten: 6 Geraete sind nicht voll
        assertFalse(CallAdd.seats(legs(6), CallRoom.limit("audio", 50, horn = true)).full)
        // Mesh unveraendert: 4 mit Video voll
        assertTrue(CallAdd.seats(legs(4), CallRoom.limit("video", 12, horn = false)).full)
    }

    @Test fun leisteKnotenAnruf() {
        val k = CallInfo(id = "g1", chatId = 5, media = "video", state = "active", kind = "group", joinable = true, limit = 12,
            topo = CallRoom.TOPO_HORN, legs = legs(5))
        assertEquals(12, CallRoom.runBar(listOf(k), "carl", "c9", null)!!.limit)
        assertEquals(50, CallRoom.runBar(listOf(k.copy(media = "audio", limit = 50)), "carl", "c9", null)!!.limit)
        // Mesh-Gruppe: weiter hoechstens 4/6
        assertEquals(4, CallRoom.runBar(listOf(k.copy(topo = "mesh")), "carl", "c9", null)!!.limit)
    }
}
