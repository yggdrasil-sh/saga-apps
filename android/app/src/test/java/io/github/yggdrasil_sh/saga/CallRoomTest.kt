package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallConfig
import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.CallRoom.HoStep
import io.github.yggdrasil_sh.saga.call.CallState
import io.github.yggdrasil_sh.saga.call.CallV3.Path
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saga 2.4 E4/E5 (Vertrag Server docs/APP-API.md "Anrufe: Mehrgeraete und Gruppen"): Teilnehmerliste mit Stand rv,
 * Mesh-Paare, Grenzen, Begleitgeraet, Uebernahme, Bitrate im Mesh, Sprecher, Leiste "Anruf laeuft".
 */
class CallRoomTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private fun leg(dev: String, who: String = "anna", role: String = "member", name: String = "Anna", devName: String = "iPhone") =
        CallLeg(who = who, name = name, device = dev, devName = devName, role = role, caps = listOf("reneg", "want", "bye", "multi"))

    // ---------------------------------------------------------------- Faehigkeiten

    @Test
    fun multiNurMitRenegUndMulti() {
        assertEquals(listOf("want", "bye"), CallRoom.myCaps(emptySet()))
        assertFalse("multi" in CallRoom.myCaps(setOf("multi")))                  // ohne reneg kein Raum
        assertFalse("multi" in CallRoom.myCaps(setOf("reneg", "screen2")))
        val all = CallRoom.myCaps(setOf("reneg", "screen2", "multi", "group"))
        assertEquals(listOf("reneg", "want", "bye", "screen2", "multi", "askmute"), all)
        assertFalse("group" in all)                                              // group ist nur eine Server-Faehigkeit
        assertTrue(CallRoom.groupOk(setOf("reneg", "multi", "group")))
        assertFalse(CallRoom.groupOk(setOf("reneg", "multi")))
        assertFalse(CallRoom.groupOk(setOf("multi", "group")))
    }

    // ---------------------------------------------------------------- Teilnehmerliste mit rv

    @Test
    fun rosterAeltereListeUeberschreibtNichtNeuere() {
        val r = CallRoom.Roster()
        val first = r.apply(listOf(leg("a"), leg("b", "bert")), rv = 10.0)
        assertNotNull(first)
        assertTrue(first!!.joined.isEmpty() && first.left.isEmpty())             // erste Liste: keine Hinweise
        val d = r.apply(listOf(leg("a"), leg("b", "bert"), leg("c", "carl")), rv = 12.0)!!
        assertEquals(listOf("c"), d.joined.map { it.device })
        // veraltete Liste (kleineres rv) wird verworfen
        assertNull(r.apply(listOf(leg("a")), rv = 11.0))
        assertEquals(listOf("a", "b", "c"), r.devices)
        assertEquals(12.0, r.rv, 0.0)
        // gleicher Stand wird uebernommen, Weggang erkannt
        val d2 = r.apply(listOf(leg("a"), leg("c", "carl")), rv = 12.0)!!
        assertEquals(listOf("b"), d2.left.map { it.device })
        // ohne rv (lokaler Server): Reihenfolge des Eintreffens
        val d3 = r.apply(listOf(leg("a")), rv = null)!!
        assertEquals(listOf("c"), d3.left.map { it.device })
        assertEquals(12.0, r.rv, 0.0)
        assertEquals("anna", r.of("a")?.who)
        assertEquals(1, r.mine("anna").size)
    }

    @Test
    fun rosterAusLiveEreignis() {
        val o = json.parseToJsonElement(
            """{"call_id":"x","rv":1792458590.7,"legs":[{"who":"anna","name":"Anna","device":"0e32","dev_name":"iPhone","role":"member",
               "caps":["multi"],"joined":1.0,"me":false},{"who":"bert","device":"5cbb","role":"companion","extra":1},{"who":"x"}]}""",
        ) as JsonObject
        val legs = CallRoom.legsOf(o["legs"])
        assertEquals(2, legs.size)                                               // ohne device verworfen
        assertTrue(legs[1].companion)
        assertTrue(legs[0].multi)
        assertEquals(1792458590.7, CallRoom.rvOf(o)!!, 0.001)
        assertNull(CallRoom.rvOf(JsonObject(emptyMap())))
        assertEquals("iPhone", legs[0].devName)
        assertEquals("anna", legs[0].person().username)
    }

    @Test
    fun anrufObjektMitRaumfeldern() {
        val st = json.decodeFromString(
            CallState.serializer(),
            """{"call":{"id":"7927","kind":"group","media":"video","state":"active","limit":4,"joinable":false,"join_block":"full",
               "mine":["5cbb"],"legs":[{"who":"anna","device":"0e32","dev_name":"iPhone","role":"member"}],
               "old":[{"username":"carl","name":"Carl"}],"invite":"ringing"},"signals":[]}""",
        )
        assertEquals("group", st.call.kind)
        assertEquals(4, st.call.limit)
        assertEquals("full", st.call.joinBlock)
        assertEquals(listOf("5cbb"), st.call.mine)
        assertEquals("Carl", st.call.old.first().name)
        assertEquals("ringing", st.call.invite)
        // 2.3-Server ohne Raumfelder: 1:1 wie bisher
        val old = json.decodeFromString(CallState.serializer(), """{"call":{"id":"1","state":"ringing","role":"callee"}}""")
        assertEquals("dm", old.call.kind)
        assertTrue(old.call.legs.isEmpty())
        // chat.call im Chat
        val chat = json.decodeFromString(
            ChatSummary.serializer(),
            """{"id":12,"kind":"group","call":{"group":true,"group_block":null,"running":{"id":"7927","media":"audio","kind":"group","state":"active","n":3,"joinable":true}}}""",
        )
        assertEquals(true, chat.call?.group)
        assertEquals("7927", chat.call?.running?.id)
        assertNull(json.decodeFromString(ChatSummary.serializer(), """{"id":1}""").call)
    }

    // ---------------------------------------------------------------- Mesh-Paare

    @Test
    fun meshPaare() {
        assertEquals("a1:b2", CallRoom.pairId("b2", "a1"))
        assertEquals(CallRoom.pairId("x", "y"), CallRoom.pairId("y", "x"))
        assertEquals(listOf("a:b", "a:c"), CallRoom.myPairs("a", listOf("a", "b", "c", "b")))
        assertTrue(CallRoom.myPairs("a", listOf("a")).isEmpty())
        // n Geraete -> n(n-1)/2 Verbindungen, jedes Paar genau einmal
        for (n in 2..6) {
            val devs = (1..n).map { "d$it" }
            val all = CallRoom.allPairs(devs)
            assertEquals(n * (n - 1) / 2, all.size)
            // jede Seite kennt dieselbe Kennung
            for (d in devs) assertTrue(all.containsAll(CallRoom.myPairs(d, devs)))
        }
        assertEquals(2, CallRoom.endpoints(0))
        assertEquals(2, CallRoom.endpoints(1))
        assertEquals(4, CallRoom.endpoints(3))
    }

    // ---------------------------------------------------------------- Grenzen

    @Test
    fun grenzenAdminKannNurSenken() {
        assertEquals(4, CallRoom.limit("video", null))
        assertEquals(6, CallRoom.limit("audio", null))
        assertEquals(3, CallRoom.limit("video", 3))
        assertEquals(4, CallRoom.limit("video", 10))                    // hoeher als Mesh-Grenze -> gekappt
        assertEquals(6, CallRoom.limit("audio", 50))
        assertEquals(2, CallRoom.limit("audio", 1))                     // 1:1 geht immer
        assertEquals(6, CallRoom.limit("audio", 0))
        assertTrue(CallRoom.hasRoom(3, 4))
        assertFalse(CallRoom.hasRoom(4, 4))
        val cfg = json.decodeFromString(
            CallConfig.serializer(),
            """{"enabled":true,"features":["reneg","multi","group"],"limits":{"endpoints_video":3,"endpoints_audio":6,"ring_silent":8,"handover_s":15}}""",
        )
        assertEquals(3, cfg.endpoints("video"))
        assertEquals(6, cfg.endpoints("audio"))
        assertEquals(15, cfg.handoverS)
        val old = json.decodeFromString(CallConfig.serializer(), """{"enabled":true}""")
        assertEquals(4, old.endpoints("video"))
        assertEquals(15, old.handoverS)
    }

    // ---------------------------------------------------------------- Begleitgeraet

    @Test
    fun begleitgeraetStummUndTaub() {
        val c = CallRoom.start(CallRoom.ROLE_COMPANION, video = true)
        assertFalse(c.mic)
        assertFalse(c.playback)
        assertFalse(c.cam)
        assertFalse(c.askMic)                                            // nicht nach dem Mikrofon fragen
        val h = CallRoom.start(CallRoom.ROLE_HANDOVER, video = true)
        assertFalse(h.mic)
        assertFalse(h.playback)
        assertTrue(h.askMic)
        val m = CallRoom.start(CallRoom.ROLE_MEMBER, video = false)
        assertTrue(m.mic && m.playback && !m.cam)
        // Kachel: stilles Begleitgeraet bzw. eigenes Geraet ohne Kamera/Bildschirm -> keine Kachel
        assertTrue(CallRoom.quiet(own = false, companion = true, gotState = false, mic = true, cam = false, screen = false))
        assertTrue(CallRoom.quiet(own = false, companion = true, gotState = true, mic = false, cam = false, screen = false))
        assertFalse(CallRoom.quiet(own = false, companion = true, gotState = true, mic = true, cam = false, screen = false))
        assertFalse(CallRoom.quiet(own = false, companion = true, gotState = true, mic = false, cam = false, screen = true))
        assertTrue(CallRoom.quiet(own = true, companion = false, gotState = true, mic = true, cam = false, screen = false))
        // 1:1 mit stillem Begleitgeraet bleibt Einzelansicht, Gruppe immer Kacheln
        assertFalse(CallRoom.meshView("dm", 1))
        assertTrue(CallRoom.meshView("dm", 2))
        assertTrue(CallRoom.meshView("group", 0))
    }

    @Test
    fun auflegenImEinsZuEinsAmHauptgeraetEndetFuerAlle() {
        assertTrue(CallRoom.hangupEndsAll("dm", companion = false))
        assertFalse(CallRoom.hangupEndsAll("dm", companion = true))
        assertFalse(CallRoom.hangupEndsAll("group", companion = false))
    }

    // ---------------------------------------------------------------- Uebernahme

    @Test
    fun uebernahme() {
        val legs = listOf(leg("p1", "bert", name = "Bert"), leg("m1", "otto", devName = "Pixel"), leg("m2", "otto", "companion", devName = "Mac"))
        // vom Begleitgeraet m2 aus: Hauptgeraet ist m1
        assertEquals("m1", CallRoom.mainDevice(legs, "otto", "m2"))
        // dritte eigene Geraet: nimmt das Hauptgeraet, nicht das Begleitgeraet
        assertEquals("m1", CallRoom.mainDevice(legs, "otto", "m3"))
        // nur Begleitgeraet uebrig -> dieses
        assertEquals("m2", CallRoom.mainDevice(legs.filter { it.device != "m1" }, "otto", "m3"))
        assertNull(CallRoom.mainDevice(legs, "anna", "a1"))
        // fertig erst, wenn ALLE Verbindungen zu anderen Personen stehen
        assertFalse(CallRoom.handoverReady(emptyList()))
        assertFalse(CallRoom.handoverReady(listOf(true, false)))
        assertTrue(CallRoom.handoverReady(listOf(true, true)))
        assertEquals(HoStep.WAIT, CallRoom.handoverStep(listOf(false), 0, 14_999))
        assertEquals(HoStep.FAIL, CallRoom.handoverStep(listOf(false), 0, 15_000))
        assertEquals(HoStep.READY, CallRoom.handoverStep(listOf(true), 0, 20_000))
        assertEquals(HoStep.FAIL, CallRoom.handoverStep(listOf(true, false), 1_000, 11_000, limitS = 10))
    }

    // ---------------------------------------------------------------- Bitrate Mesh

    @Test
    fun bitrateMesh() {
        // 1:1 (2 Endpunkte) wie bisher
        assertEquals(1_500_000, CallRoom.meshLimits(Path.DIRECT, "large", 2).maxBps)
        // ab drei Endpunkten Mesh-Werte je Strom
        val d = CallRoom.meshLimits(Path.DIRECT, "large", 3)
        assertEquals(600_000, d.maxBps)
        assertEquals(720.0 / 540, d.scaleDown, 0.001)                   // ≤ 540p
        assertNull(d.maxFps)
        assertEquals(500_000, CallRoom.meshLimits(Path.RELAY_UDP, "large", 4).maxBps)
        val t = CallRoom.meshLimits(Path.RELAY_TCP, "large", 4)
        assertEquals(350_000, t.maxBps)
        assertEquals(2.0, t.scaleDown, 0.001)                            // ≤ 360p
        assertEquals(15, t.maxFps)
        // want: small im Mesh 200 kbit/s
        val s = CallRoom.meshLimits(Path.DIRECT, "small", 3)
        assertEquals(200_000, s.maxBps)
        assertTrue(s.scaleDown >= 2.0)
        assertFalse(CallRoom.meshLimits(Path.DIRECT, "off", 3).active)
        // Uplink 4er-Video direkt: 3 x 600 kbit/s = 1,8 Mbit/s (Konzept §5.7)
        assertEquals(1_800_000, 3 * CallRoom.meshLimits(Path.DIRECT, "large", 4).maxBps)
    }

    // ---------------------------------------------------------------- Sprecher, Klingeln, Leiste

    @Test
    fun aktiverSprecher() {
        assertNull(CallRoom.speaker(emptyMap(), null))
        assertNull(CallRoom.speaker(mapOf("a" to 0.001), null))         // unter der Schwelle
        assertEquals("a", CallRoom.speaker(mapOf("a" to 0.3, "b" to 0.1), null))
        // bisheriger bleibt, solange der andere nicht deutlich lauter ist
        assertEquals("a", CallRoom.speaker(mapOf("a" to 0.3, "b" to 0.35), "a"))
        assertEquals("b", CallRoom.speaker(mapOf("a" to 0.1, "b" to 0.5), "a"))
        // Stille: letzter Sprecher bleibt, solange seine Verbindung besteht
        assertEquals("a", CallRoom.speaker(mapOf("a" to 0.0, "b" to 0.0), "a"))
        assertNull(CallRoom.speaker(mapOf("b" to 0.0), "a"))
    }

    @Test
    fun klingelnEndetMitEigenemGeraet() {
        assertFalse(CallRoom.stopRinging(listOf(leg("a1", "anna")), "otto"))
        assertTrue(CallRoom.stopRinging(listOf(leg("a1", "anna"), leg("s2", "otto")), "otto"))
    }

    @Test
    fun leisteAnrufLaeuft() {
        val dm = CallInfo(id = "c1", chatId = 5, media = "video", state = "active", kind = "dm", joinable = true, limit = 4,
            legs = listOf(leg("p1", "bert"), leg("m1", "otto", devName = "Pixel")))
        // anderes eigenes Geraet: "Anruf laeuft auf Pixel" mit Hinzufuegen/Uebernehmen
        val b = CallRoom.runBar(listOf(dm), "otto", "m2", null)!!
        assertEquals("Pixel", b.onDevice)
        assertTrue(b.mineRunning)
        assertFalse(b.group)
        // dieses Geraet ist schon dabei bzw. telefoniert hier: keine Leiste
        assertNull(CallRoom.runBar(listOf(dm), "otto", "m1", null))
        assertNull(CallRoom.runBar(listOf(dm), "otto", "m2", "c1"))
        // fremder 1:1-Anruf ohne eigenes Geraet: keine Leiste
        assertNull(CallRoom.runBar(listOf(dm), "carl", "c9", null))
        // Gruppe ohne eigenes Geraet: Beitreten; voll -> Hinweis
        val g = dm.copy(id = "g1", kind = "group", joinable = false, joinBlock = "full")
        val gb = CallRoom.runBar(listOf(g), "carl", "c9", null)!!
        assertTrue(gb.group)
        assertFalse(gb.mineRunning)
        assertEquals(2, gb.devices)
        assertEquals("full", gb.block)
        assertNull(CallRoom.runBar(listOf(g.copy(state = "ended")), "carl", "c9", null))
    }
}
