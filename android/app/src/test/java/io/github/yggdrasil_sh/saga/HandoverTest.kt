package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallDiag
import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.CallWrap
import io.github.yggdrasil_sh.saga.call.Handover
import io.github.yggdrasil_sh.saga.ui.settings.notifKindLabel
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saga 2.5 Geraete-Uebergabe hin und her (Server-Feature `handover2`, APP-API "Geraete-Uebergabe hin und her"):
 * bedingtes /leave (by/gen, stale), Rueckfall-Zeiten, Fuehrung erkennen, Wiedereintritt/Leiste, handover_busy, takeover,
 * call.mod why handover, promote-Ruecknahme; dazu calldiag-CPU-Felder und die Beschriftung der neuen Mitteilungsarten.
 */
class HandoverTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private fun leg(dev: String, who: String = "anna", role: String = "member", gen: Long? = null, devName: String = "iPhone") =
        CallLeg(who = who, name = who, device = dev, devName = devName, role = role, gen = gen, caps = listOf("reneg", "multi"))

    @Test
    fun feature() {
        assertTrue(Handover.on(setOf("reneg", "multi", "handover2")))
        assertFalse(Handover.on(setOf("reneg", "multi")))
    }

    // ---------------------------------------------------------------- bedingtes /leave

    @Test
    fun leaveBodyWithAndWithoutHandover2() {
        // Rueckfall des neuen Geraets: device = altes, by = eigenes, gen = des alten
        val b = Handover.leaveBody("old", Handover.REASON, by = "new", gen = 1793634001234, v2 = true)
        assertEquals("old", (b["device"] as JsonPrimitive).content)
        assertEquals("handover", (b["reason"] as JsonPrimitive).content)
        assertEquals("new", (b["by"] as JsonPrimitive).content)
        assertEquals(1793634001234, (b["gen"] as JsonPrimitive).long)
        // altes Geraet (handoverOut): eigene gen, kein by
        val o = Handover.leaveBody("old", Handover.REASON, gen = 77, v2 = true)
        assertNull(o["by"])
        assertEquals(77L, (o["gen"] as JsonPrimitive).long)
        // ohne handover2: unbedingt wie bisher
        val legacy = Handover.leaveBody("old", Handover.REASON, by = "new", gen = 77, v2 = false)
        assertEquals(setOf("device", "reason"), legacy.keys)
        // by = das Geraet selbst bzw. gen fehlt / 0: weglassen (der Server wuerde sonst immer stale melden)
        val self = Handover.leaveBody("me", null, by = "me", gen = 0, v2 = true)
        assertEquals(setOf("device"), self.keys)
    }

    @Test
    fun genFromRoster() {
        val legs = listOf(leg("a", gen = 10), leg("b", gen = 0), leg("c"))
        assertEquals(10L, Handover.genOf(legs, "a"))
        assertNull(Handover.genOf(legs, "b"))
        assertNull(Handover.genOf(legs, "c"))
        assertNull(Handover.genOf(legs, "x"))
        // aus der Teilnehmerliste gelesen (Server 2.5: legs[].gen)
        val k = json.decodeFromString(CallInfo.serializer(), """{"id":"c1","legs":[{"device":"a","who":"anna","gen":1793634016726}],"takeover":true}""")
        assertEquals(1793634016726, k.legs[0].gen)
        assertTrue(k.takeover)
        val w = json.decodeFromString(CallWrap.serializer(), """{"ok":true,"gen":1793634016726,"call":{"id":"c1"}}""")
        assertEquals(1793634016726, w.gen)
        val p = json.decodeFromString(CallWrap.serializer(), """{"ok":true,"already":true}""")
        assertTrue(p.already)
        // aelterer Server: keine gen
        assertNull(json.decodeFromString(CallInfo.serializer(), """{"legs":[{"device":"a"}]}""").legs[0].gen)
    }

    @Test
    fun leaveResult() {
        assertEquals(Handover.Left.STALE, Handover.leaveResult("""{"ok":true,"stale":true}"""))
        assertEquals(Handover.Left.ALREADY, Handover.leaveResult("""{"ok":true,"already":true}"""))
        assertEquals(Handover.Left.OK, Handover.leaveResult("""{"ok":true}"""))
        assertEquals(Handover.Left.OK, Handover.leaveResult("kaputt"))
        assertEquals(Handover.Left.OK, Handover.leaveResult(null))
    }

    @Test
    fun fallbackTimes() {
        assertEquals(3000L, Handover.fallbackMs(horn = true, v2 = true, handoverS = 15))
        assertEquals(3000L, Handover.fallbackMs(horn = true, v2 = false, handoverS = 15))     // wie bisher
        assertEquals(7500L, Handover.fallbackMs(horn = false, v2 = true, handoverS = 15))     // Mesh: handover_s/2
        assertEquals(10_000L, Handover.fallbackMs(horn = false, v2 = true, handoverS = 20))
        assertNull(Handover.fallbackMs(horn = false, v2 = false, handoverS = 15))              // Mesh ohne handover2: keiner
    }

    // ---------------------------------------------------------------- Fuehrung (Teilnehmerliste)

    @Test
    fun leaderDetectsTakeOverIncludingFormerHandover() {
        val me = "m1"
        // neues Geraet hat uebernommen (vorher nicht in der Liste)
        assertEquals("m2", Handover.leader(listOf(leg(me), leg("x", "bert")), listOf(leg("m2"), leg("x", "bert")), "anna", me)?.device)
        // vorher Begleitgeraet
        assertEquals("m2", Handover.leader(listOf(leg(me), leg("m2", role = "companion")), listOf(leg("m2")), "anna", me)?.device)
        // 2.5: vorher Uebernahme (state.ready kam nicht an, Rueckfall per /leave)
        assertEquals("m2", Handover.leader(listOf(leg(me), leg("m2", role = "handover")), listOf(leg("m2")), "anna", me)?.device)
        // Begleitgeraet bleibt Begleitgeraet: nur kurz aus der Liste gefallen
        assertNull(Handover.leader(listOf(leg(me), leg("m2", role = "companion")), listOf(leg("m2", role = "companion")), "anna", me))
        // anderes Hauptgeraet war schon Hauptgeraet
        assertNull(Handover.leader(listOf(leg(me, role = "companion"), leg("m2")), listOf(leg("m2")), "anna", me))
        // Geraet einer anderen Person zaehlt nie
        assertNull(Handover.leader(listOf(leg(me)), listOf(leg("x", "bert")), "anna", me))
    }

    // ---------------------------------------------------------------- Beitritt, Leiste, Wiedereintritt

    @Test
    fun joinAllowedWithTakeover() {
        assertTrue(Handover.joinAllowed(true, false, CallRoom.ROLE_COMPANION))
        assertTrue(Handover.joinAllowed(false, true, CallRoom.ROLE_HANDOVER))       // voller Anruf, Uebernahme geht
        assertFalse(Handover.joinAllowed(false, true, CallRoom.ROLE_COMPANION))     // Hinzufuegen bleibt gesperrt
        assertFalse(Handover.joinAllowed(false, false, CallRoom.ROLE_HANDOVER))
    }

    @Test
    fun busyText() {
        val t = CallRoom.blockText(Handover.BUSY, 4) as UiText.Res
        assertEquals(R.string.ho_busy, t.id)
        assertNotEquals(R.string.room_block_other, t.id)
    }

    @Test
    fun runBarReentryAndFull() {
        val main = leg("m1", devName = "Pixel")
        val peer = leg("p1", "bert")
        // dieses Geraet (m2) steht noch in der Liste (sein /leave ist unterwegs), telefoniert hier aber nicht
        val c = CallInfo(id = "c1", chatId = 3, kind = "dm", state = "active", joinable = true,
            legs = listOf(main, peer, leg("m2", devName = "Tablet")))
        assertNull(CallRoom.runBar(listOf(c), "anna", "m2", null))                       // ohne handover2: wie bisher keine Leiste
        val b = CallRoom.runBar(listOf(c), "anna", "m2", null, reenter = true)!!
        assertEquals("Pixel", b.onDevice)
        assertTrue(b.mineRunning)
        // telefoniert hier noch (busy) -> nie
        assertNull(CallRoom.runBar(listOf(c), "anna", "m2", "c1", reenter = true))
        // nur das eigene, liegengebliebene Bein: keine Leiste
        val alone = c.copy(legs = listOf(peer, leg("m2")))
        assertNull(CallRoom.runBar(listOf(alone), "anna", "m2", null, reenter = true))
        // voller Anruf mit takeover: Leiste mit Hinweis + Hierher uebernehmen
        val full = CallInfo(id = "c2", chatId = 3, kind = "group", state = "active", joinable = false, joinBlock = "full", takeover = true,
            legs = listOf(main, peer))
        val fb = CallRoom.runBar(listOf(full), "anna", "m2", null, reenter = true)!!
        assertFalse(fb.joinable)
        assertTrue(fb.takeover)
        // ohne eigenes Geraet im Anruf gibt es kein takeover
        val other = full.copy(takeover = true, legs = listOf(peer, leg("p2", "carl")))
        assertFalse(CallRoom.runBar(listOf(other), "anna", "m2", null)?.takeover ?: false)
    }

    @Test
    fun companionMute() {
        assertTrue(Handover.companionMute("mute", "m1", "handover", "m1"))
        assertFalse(Handover.companionMute("mute", "m1", null, "m1"))                // normales Stummschalten (Gastgeber)
        assertFalse(Handover.companionMute("mute", "m2", "handover", "m1"))
        assertFalse(Handover.companionMute("remove", "m1", "handover", "m1"))
    }

    // ---------------------------------------------------------------- promote (Begleitgeraet ueber Knoten)

    @Test
    fun promoteUndo() {
        assertTrue(Handover.usePromote(horn = true, v2 = true))
        assertFalse(Handover.usePromote(horn = false, v2 = true))       // Mesh: nicht noetig
        assertFalse(Handover.usePromote(horn = true, v2 = false))       // ohne handover2 wie bisher
        // Erfolg: befoerdert, Ton-Bein steht, ready -> nichts zuruecknehmen
        val ok = Handover.Promote("old")
        assertTrue(ok.busy)
        ok.promotedNow(already = false)
        ok.ready()
        assertFalse(ok.busy)
        ok.finish()
        assertFalse(ok.undo())
        // Ton-Bein scheitert nach der Befoerderung: genau einmal zuruecknehmen
        val bad = Handover.Promote("old")
        bad.promotedNow(already = false)
        assertTrue(bad.undo())
        assertFalse(bad.undo())
        assertFalse(bad.busy)
        // schon Hauptgeraet (already): nichts zuruecknehmen
        val al = Handover.Promote("old")
        al.promotedNow(already = true)
        assertFalse(al.undo())
        // promote selbst scheitert (keine Antwort): nichts zuruecknehmen
        assertFalse(Handover.Promote("old").undo())
    }

    @Test
    fun audioRole() {
        assertEquals("both", Handover.audioRole(listOf("both")))
        assertEquals("audio", Handover.audioRole(listOf("audio", "video")))
        assertNull(Handover.audioRole(listOf("video")))
    }

    /**
     * Mehrfaches Hin und Her (A -> B -> A -> B) als Folge der Server-Regeln: jedes bedingte /leave traegt die gen des
     * Beins zum Zeitpunkt von ready. Kehrt A zurueck (neue gen), ist der spaete Rueckfall von B fuer A stale.
     */
    @Test
    fun pingPongStaleRule() {
        // Nachbildung von leave_stale (saga/calls.py) fuer die Bodies dieser App
        data class Leg(val gen: Long, var active: Boolean = true)
        val legs = HashMap<String, Leg>()
        fun join(dev: String, at: Long) { legs[dev] = Leg(at) }
        fun leave(body: kotlinx.serialization.json.JsonObject): String {
            val dev = (body["device"] as JsonPrimitive).content
            val l = legs[dev] ?: return "already"
            if (!l.active) return "already"
            val gen = (body["gen"] as? JsonPrimitive)?.long
            val by = (body["by"] as? JsonPrimitive)?.content
            if (gen != null && gen != l.gen) return "stale"
            if (by != null && legs[by]?.active != true) return "stale"
            l.active = false
            return "ok"
        }
        join("A", 100)
        join("B", 200)                                           // B uebernimmt (handover)
        val bFallback = Handover.leaveBody("A", "handover", "B", legs["A"]!!.gen, true)   // B merkt gen von A bei ready
        val aOut = Handover.leaveBody("A", "handover", null, legs["A"]!!.gen, true)       // A gibt ab
        join("A", 300)                                           // A kehrt schnell zurueck (Wiedereintritt, neue gen)
        assertEquals("stale", leave(aOut))                       // A's eigenes Verlassen kam zu spaet
        assertEquals("stale", leave(bFallback))                  // B's Rueckfall wirft das zurueckgekehrte A nicht hinaus
        assertTrue(legs["A"]!!.active)
        val aFallback = Handover.leaveBody("B", "handover", "A", legs["B"]!!.gen, true)
        val bOut = Handover.leaveBody("B", "handover", null, legs["B"]!!.gen, true)
        assertEquals("ok", leave(bOut))
        assertEquals("already", leave(aFallback))
        join("B", 400)                                           // und wieder zu B
        val aOut2 = Handover.leaveBody("A", "handover", null, legs["A"]!!.gen, true)
        assertEquals("ok", leave(aOut2))
        assertTrue(legs["B"]!!.active)
        assertFalse(legs["A"]!!.active)
    }

    // ---------------------------------------------------------------- calldiag: Geraete-CPU

    @Test
    fun diagCpuFields() {
        val b = CallDiag.Buffer("c".repeat(32), "android", "2.5.0", 0)
        val out0 = mapOf(
            "kind" to "video", "rid" to "h", "bytesSent" to 0L, "framesEncoded" to 100L, "totalEncodeTime" to 0.5,
            "qualityLimitationDurations" to mapOf("cpu" to 1.0, "bandwidth" to 0.0, "none" to 10.0),
        )
        val src0 = CallDiag.withSource(out0, mapOf("frames" to 110L))
        val s0 = b.sample("outbound-rtp", src0, "me", "o.h", 1000, null)!!
        assertEquals(0, s0.qcpu)                                  // erste Messung: nur Ausgangswerte
        assertFalse(s0.json().containsKey("qcpu"))
        val out1 = out0 + mapOf("framesEncoded" to 160L, "totalEncodeTime" to 0.8,
            "qualityLimitationDurations" to mapOf("cpu" to 3.5, "bandwidth" to 0.25))
        val s1 = b.sample("outbound-rtp", CallDiag.withSource(out1, mapOf("frames" to 180L)), "me", "o.h", 4000, null)!!
        assertEquals(2500, s1.qcpu)
        assertEquals(250, s1.qbw)
        assertEquals(5.0, s1.encms, 0.001)                        // 300 ms / 60 Rahmen
        assertEquals(10, s1.fdrop)                                // 70 Kamerarahmen, 60 kodiert
        val j = s1.json()
        assertEquals(2500, (j["qcpu"] as JsonPrimitive).content.toInt())
        assertTrue(j.containsKey("encms"))
        assertFalse(j.containsKey("decms"))
        assertFalse(j.containsKey("fzms"))
        // Schicht l (bewusst weniger Bilder): kein fdrop
        val l0 = mapOf("kind" to "video", "rid" to "l", "framesEncoded" to 10L)
        b.sample("outbound-rtp", CallDiag.withSource(l0, mapOf("frames" to 10L)), "me", "o.l", 1000, null)
        val l1 = b.sample("outbound-rtp", CallDiag.withSource(l0 + ("framesEncoded" to 20L), mapOf("frames" to 40L)), "me", "o.l", 4000, null)!!
        assertEquals(0, l1.fdrop)
        // Empfang
        val in0 = mapOf("kind" to "video", "framesDecoded" to 50L, "totalDecodeTime" to 0.2, "framesDropped" to 1L, "totalFreezesDuration" to 0.0)
        b.sample("inbound-rtp", in0, "p1", "i", 1000, null)
        val in1 = b.sample("inbound-rtp", in0 + mapOf("framesDecoded" to 90L, "totalDecodeTime" to 0.6, "framesDropped" to 4L, "totalFreezesDuration" to 0.75), "p1", "i", 4000, null)!!
        assertEquals(10.0, in1.decms, 0.001)
        assertEquals(3, in1.fdrop)
        assertEquals(750, in1.fzms)
        assertEquals(0, in1.qcpu)
        assertEquals(0.0, in1.encms, 0.0)
        // ohne Quelle (aeltere WebRTC-Stats): kein fdrop, kein Fehler
        assertNotNull(b.sample("outbound-rtp", mapOf("kind" to "video", "rid" to "m"), "me", "o.m", 1000, null))
    }

    // ---------------------------------------------------------------- Mitteilungsarten (Labor #1)

    @Test
    fun notifKindLabels() {
        assertEquals(R.string.notify_kind_db_assigned, notifKindLabel("db.assigned"))
        assertEquals(R.string.notify_kind_page_mention, notifKindLabel("page.mention"))
        assertEquals(R.string.notify_kind_comment_mention, notifKindLabel("comment.mention"))
        assertEquals(R.string.notify_kind_chat_mention, notifKindLabel("chat.mention"))
        assertEquals(R.string.notify_kind_chat_message, notifKindLabel("chat.message"))
        // alle Arten des Servers (saga/notify.py KINDS) haben eine eigene Beschriftung
        for (k in listOf("share.added", "share.role", "comment.new", "comment.reply", "publish.withdrawn", "chat.message",
            "call.missed", "db.assigned", "page.mention", "comment.mention", "chat.mention")) {
            assertNotEquals(k, R.string.notifications, notifKindLabel(k))
        }
        assertEquals(R.string.notifications, notifKindLabel("unbekannt"))
    }
}
