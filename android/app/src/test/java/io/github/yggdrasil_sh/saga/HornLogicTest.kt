package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.HornE2E
import io.github.yggdrasil_sh.saga.call.HornLogic
import io.github.yggdrasil_sh.saga.call.HornLogic.Cand
import io.github.yggdrasil_sh.saga.call.HornLogic.Measure
import io.github.yggdrasil_sh.saga.call.HornLogic.Other
import io.github.yggdrasil_sh.saga.call.HornLogic.Switcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gjallarhorn P12 (Android): Entscheidungen wie im Web-Client - dieselben Faelle wie tests/horn_logik.js im Server-Repo
 * (Punktzahl, Umschalter mit Hysterese, UDP <-> TCP, make-before-break, Kachelansicht, Qualitaet, Weg), dazu die Formate
 * des Knotens (Kandidaten, Bein-Antwort, Ereigniskanal) und der Schluesselplan fuer FrameCryptor.
 */
class HornLogicTest {
    private val H = HornLogic

    private fun m(rtts: List<Int>, udp: Double? = null) = Measure(true, rtts.map { it.toDouble() }, udp = udp)
    private fun c(node: String = "x", udp: Boolean = false, here: Boolean = false, load: Double? = 0.0) = Cand(node, udp = udp, here = here, load = load)

    // ---- Punktzahl -------------------------------------------------------------------------------------------------

    @Test
    fun medianAndJitter() {
        assertEquals(3.0, H.median(listOf(5.0, 1.0, 3.0)), 0.0)
        assertEquals(2.5, H.median(listOf(1.0, 2.0, 3.0, 4.0)), 0.0)
        assertEquals(10.0, H.jitter(listOf(10.0, 20.0, 10.0)), 0.0)
        assertEquals(0.0, H.jitter(listOf(7.0)), 0.0)
    }

    @Test
    fun scores() {
        assertEquals("TCP-Kandidat: rtt 20 + UDP fehlt 15", 35.0, H.score(m(listOf(20, 20, 20, 20, 20)), c()), 0.0)
        assertEquals("Bonus Anruf schon hier", 10.0, H.score(m(listOf(20, 20, 20)), c(here = true)), 0.0)
        assertEquals("UDP-Kandidat mit UDP-Probe", 20.0, H.score(m(listOf(20, 20, 20), 18.0), c(udp = true)), 0.0)
        assertEquals("UDP-Kandidat ohne UDP-Probe", 35.0, H.score(m(listOf(20, 20, 20), null), c(udp = true)), 0.0)
        assertEquals("Geraet ohne UDP", 35.0, H.score(m(listOf(20, 20, 20), 18.0), c(udp = true), devUdp = false), 0.0)
        assertEquals("Jitter zaehlt doppelt", 75.0, H.score(m(listOf(20, 40, 20)), c()), 0.0)
        assertEquals(0.0, H.loadMs(0.5), 0.0)
        assertEquals(20.0, H.loadMs(0.8), 1e-9)
        assertEquals(1000.0, H.loadMs(0.95), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, H.score(Measure(false), c()), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, H.score(null, c()), 0.0)
    }

    @Test
    fun ranking() {
        val r = H.rank(listOf(c("a", here = true), c("b"), c("c")), mapOf("a" to m(listOf(60, 60, 60)), "b" to m(listOf(20, 20, 20)), "c" to Measure(false)))
        assertEquals("b (35) vor a (60+15-25=50) vor c (unerreichbar)", "b,a,c", r.joinToString(",") { it.cand.node })
        val r2 = H.rank(listOf(c("a", here = true), c("b")), mapOf("a" to m(listOf(40, 40)), "b" to m(listOf(20, 20))))
        assertEquals("Bonus haelt den Anruf auf einem Knoten", "a", r2[0].cand.node)
        val r3 = H.rank(listOf(c("a"), c("b")), mapOf("a" to m(listOf(20)), "b" to m(listOf(20))))
        assertEquals("Gleichstand: Reihenfolge des Servers", "a", r3[0].cand.node)
    }

    @Test
    fun betterRule() {
        assertTrue(H.better(100.0, 70.0))
        assertFalse(H.better(100.0, 80.0))
        assertTrue(H.better(40.0, 9.0))
        assertFalse("< 30 ms", H.better(40.0, 15.0))
        assertTrue(H.better(Double.POSITIVE_INFINITY, 50.0))
        assertFalse(H.better(50.0, Double.POSITIVE_INFINITY))
    }

    // ---- Umschalter ------------------------------------------------------------------------------------------------

    @Test
    fun lostAndDraining() {
        val s = Switcher("a", "tcp", 0)
        assertEquals("lost", s.conn(1000, "failed")?.why)
        assertNull(s.conn(1000, "disconnected"))
        assertNull(s.conn(2900, "disconnected"))
        assertEquals("lost", s.conn(3100, "disconnected")?.why)
        s.conn(4000, "connected")
        assertNull("kurz disconnected, dann connected: zurueckgesetzt", s.conn(5000, "disconnected"))
        assertNull(s.conn(6500, "disconnected"))
        assertEquals("switch", s.draining().act)
    }

    private val t0 = 100_000L

    @Test
    fun betterFor20s() {
        val s = Switcher("a", "tcp", 0)
        var got: HornLogic.Decision? = null
        var at = 0L
        var t = t0
        while (t <= t0 + 25_000 && got == null) {
            got = s.observe(t, 100.0, listOf(Other("b", 60.0)))
            at = t
            t += 1000
        }
        assertEquals("b", got?.to)
        assertEquals(20_000L, at - t0)
    }

    @Test
    fun interruptionResets() {
        val s = Switcher("a", "tcp", 0)
        var flap: Long? = null
        var t = t0
        while (t <= t0 + 40_000) {
            val x = s.observe(t, 100.0, listOf(Other("b", if ((t / 1000) % 15 == 0L) 95.0 else 60.0)))
            if (x != null && flap == null) flap = t
            t += 1000
        }
        assertNull("alle 15 s kurz nicht besser: kein Wechsel", flap)
    }

    @Test
    fun onlySlightlyBetterNever() {
        val s = Switcher("a", "tcp", 0)
        assertTrue((0 until 40).all { s.observe(t0 + it * 1000L, 100.0, listOf(Other("b", 80.0))) == null })
    }

    @Test
    fun dwell60s() {
        val s = Switcher("a", "tcp", t0)
        var first: Long? = null
        var t = t0
        while (t <= t0 + 90_000 && first == null) {
            if (s.observe(t, 100.0, listOf(Other("b", 50.0))) != null) first = t
            t += 1000
        }
        assertEquals(60_000L, first!! - t0)
    }

    @Test
    fun atMostThreePer10Min() {
        val s = Switcher("a", "tcp", 0)
        val moves = ArrayList<Long>()
        var node = "a"
        var t = 0L
        while (t < 1_200_000) {
            val other = if (node == "a") "b" else "a"
            val x = s.observe(t, 100.0, listOf(Other(other, 40.0)))
            if (x != null) {
                moves.add(t)
                node = x.to!!
                s.moved(t, node, "tcp", "better")
            }
            t += 1000
        }
        val in10 = moves.count { it < 600_000 + moves[0] }
        assertTrue("in den ersten 10 min $in10", in10 <= 3)
        assertTrue(moves.withIndex().all { (i, x) -> i < 3 || x - moves[i - 3] >= 600_000 })
        assertTrue(moves.withIndex().all { (i, x) -> i == 0 || x - moves[i - 1] >= 60_000 })
        assertEquals("Abbruch zaehlt nicht gegen die Grenze", "lost", s.conn(t, "failed")?.why)
    }

    @Test
    fun udpLossToTcp() {
        var s = Switcher("a", "udp", 0)
        assertNull(s.udp(1000, 0.25))
        assertNull(s.udp(3500, 0.25))
        assertEquals("tcp", s.udp(4000, 0.25)?.path)
        s = Switcher("a", "udp", 0)
        assertNull(s.udp(0, 0.25))
        assertNull(s.udp(2000, 0.05))
        assertNull(s.udp(4000, 0.25))
        assertNull("Verlust springt: zurueckgesetzt", s.udp(6500, 0.25))
    }

    @Test
    fun udpBack() {
        val s = Switcher("a", "udp", 0)
        s.moved(10_000, "a", "tcp", "udp-loss")
        var back: Long? = null
        var x = 10_000L
        while (x < 60_000 && back == null) {
            if (s.udp(x, 0.0, true) != null) back = x
            x += 1000
        }
        assertEquals("UDP wieder ok fuer 30 s", 30_000L, back!! - 10_000)
        s.moved(back, "a", "udp", "udp")
        s.moved(back + 5000, "a", "tcp", "udp-loss")
        var back2: Long? = null
        x = back + 5000
        while (x < back + 600_000 && back2 == null) {
            if (s.udp(x, 0.0, true) != null) back2 = x
            x += 1000
        }
        assertTrue("hoechstens 1x je 5 min (${back2!! - back})", back2 - back >= 300_000)
        val s2 = Switcher("a", "tcp")
        var any = false
        x = 0
        while (x < 400_000) {
            if (s2.udp(x, 0.0, false) != null) any = true
            x += 1000
        }
        assertFalse("UDP-Probe scheitert: keine Rueckkehr", any)
    }

    @Test
    fun makeBeforeBreak() {
        assertFalse("nicht vor den ersten Rahmen", H.closeOld(5000, 0, 0))
        assertFalse(H.closeOld(1900, 1, 0))
        assertTrue("nach 2 s ohne Wartende", H.closeOld(2001, 1, 0))
        assertFalse(H.closeOld(14_000, 1, 2))
        assertTrue("spaetestens nach 15 s", H.closeOld(15_001, 1, 2))
    }

    // ---- Kachelansicht ---------------------------------------------------------------------------------------------

    @Test
    fun tilesAndColumns() {
        assertEquals("1,2,3,4,5,3,4,5", listOf(1, 4, 9, 16, 25, 5, 10, 17).joinToString(",") { H.cols(it, 1280).toString() })
        assertEquals(1, H.cols(1, 390))
        assertEquals(2, H.cols(12, 390))
        assertEquals(9, H.lastN(false))
        assertEquals(4, H.lastN(true))
        assertEquals("Handy: Sprecher + 3 Kacheln", 3, H.tileSlots(true))
        assertEquals("Tablet: Sprecher + 8 Kacheln", 8, H.tileSlots(false))
        val devs = (0 until 30).map { "d$it" }
        val mv = devs.take(9).toSet()
        val tl = H.tiles(devs, mv, "d12", "grid")
        assertEquals(25, tl.list.size)
        assertEquals(5, tl.more)
        assertEquals(9, tl.list.count { it.moving })
        assertEquals(16, tl.list.count { !it.moving })
        assertEquals("d0", tl.list[0].dev)
        val sp = H.tiles(devs.take(6), mv, "d4", "speaker")
        assertEquals("d4", sp.list[0].dev)
        assertTrue(sp.list[0].big)
        assertTrue(sp.list.drop(1).none { it.big })
        assertEquals(6, sp.list.size)
        assertEquals("ohne bekannten Sprecher: der erste", "x", H.tiles(listOf("x", "y"), emptySet(), null, "speaker").list[0].dev)
    }

    @Test
    fun qualityAndPath() {
        assertEquals("good", H.quality(HornLogic.Net(40.0, 0.0)))
        assertEquals("mid", H.quality(HornLogic.Net(250.0)))
        assertEquals("mid", H.quality(HornLogic.Net(40.0, 0.03)))
        assertEquals("bad", H.quality(HornLogic.Net(40.0, freezes = 4.0)))
        assertEquals("bad", H.quality(HornLogic.Net(500.0)))
        assertEquals("", H.quality(null))
        assertEquals("UDP", H.pathName("udp"))
        assertEquals("TCP", H.pathName("tcp"))
        assertEquals("TLS", H.pathName("turn"))
        assertEquals(listOf("both"), H.roles("udp"))
        assertEquals(listOf("audio", "video"), H.roles("tcp"))
        assertEquals(listOf("audio", "video"), H.roles("turn"))
        assertEquals("l,m", H.camEncodings("tcp").joinToString(",") { it.rid })
        assertEquals("l,m,h", H.camEncodings("udp").joinToString(",") { it.rid })
        assertEquals(3, H.CAM_TEMPORAL)
    }

    @Test
    fun camActiveAkku() {
        fun on(p: String, n: Int, low: Boolean) = H.camActive(p, n, low).first.sorted().joinToString(",")
        assertEquals("", on("udp", 0, false))                    // allein: nichts kodieren
        assertEquals("h,m", on("udp", 1, false))                 // zu zweit: ohne Kachel-Schicht
        assertEquals("h,l,m", on("udp", 3, false))               // Gruppe: alle
        assertEquals("l,m", on("udp", 3, true))                  // Energiesparen: ohne h
        assertEquals("m", on("udp", 1, true))
        assertEquals("m", on("tcp", 1, false))
        assertEquals("m", on("tcp", 1, true))                    // nie ganz leer, solange jemand da ist
        assertEquals(15, H.camActive("udp", 2, true).second)
        assertEquals(null, H.camActive("udp", 2, false).second)
    }

    // ---- Formate des Knotens ---------------------------------------------------------------------------------------

    @Test
    fun candidates() {
        val el = Json.parseToJsonElement(
            """{"e2e":"saga-horn/v1","ttl":60,"candidates":[
              {"node":"horn-0.example.org","url":"https://horn-0.example.org","udp":false,"here":true,"load":0.12,"peer":"p1","exp":1760000000,
               "tokens":{"audio":"h1.a","video":"h1.v","x":"nein"}},
              {"node":"horn-1.example.org","url":"https://horn-1.example.org:8443/","udp":true,"tokens":{"both":"h1.b"}},
              {"node":"boese","url":"http://192.0.2.1","tokens":{}},
              {"url":"https://ohne-name"}]}""",
        )
        val b = H.parseBlock(el)!!
        assertEquals("saga-horn/v1", b.e2e)
        assertEquals("nur https, mit Namen", 2, b.candidates.size)
        val a = b.candidates[0]
        assertTrue(a.here)
        assertEquals(0.12, a.load!!, 0.0)
        assertEquals(setOf("audio", "video"), a.tokens.keys)
        assertEquals(1_760_000_000L, a.exp)
        assertEquals("horn-0.example.org", a.host)
        assertEquals(443, a.port)
        val u = b.candidates[1]
        assertEquals("https://horn-1.example.org:8443", u.url)
        assertEquals(8443, u.port)
        assertEquals("wss://horn-1.example.org:8443", u.wsBase)
        assertEquals(1, H.parseBlock(el, allowHttp = true)!!.candidates.count { it.node == "boese" })
        assertNull(H.parseBlock(null))
    }

    @Test
    fun legResponse() {
        val r = H.parseLeg("""{"id":"ab12cd34","answer":"v=0\r\n","csrc":17,"state":"ok","peers":{"p1":17,"p2":4294967295,"bad ref":3},
            "slots":[{"i":0,"class":"audio","peer":"p2","csrc":4294967295,"tid":0},{"i":3,"class":"tile","paused":true},{"i":1,"class":"weird"}]}""")!!
        assertEquals("ab12cd34", r.id)
        assertEquals(17L, r.csrc)
        assertEquals(mapOf("p1" to 17L, "p2" to 4294967295L), r.peers)
        assertEquals(2, r.slots.size)
        assertTrue(r.slots[1].paused)
        assertNull(H.parseLeg("""{"id":"x y","answer":"v=0"}"""))
        assertNull(H.parseLeg("kaputt"))
    }

    @Test
    fun eventChannel() {
        val hello = H.parseEvent("""{"t":"hello","leg":"L1","peers":{"p1":5},"slots":[{"i":0,"class":"speaker","peer":"p1","csrc":5}],"est_kbit":900,"state":"draining"}""")
        assertTrue(hello is HornLogic.Event.Hello)
        hello as HornLogic.Event.Hello
        assertEquals("draining", hello.state)
        assertEquals(900, hello.estKbit)
        assertEquals("speaker", hello.slots[0].cls)
        assertEquals(mapOf("p2" to 7L), (H.parseEvent("""{"t":"peers","peers":{"p2":7}}""") as HornLogic.Event.Peers).peers)
        val lay = H.parseEvent("""{"t":"layout","slots":[{"i":2,"class":"tile","peer":"p2","csrc":7,"layer":"l"}],"est_kbit":300}""") as HornLogic.Event.Layout
        assertEquals("l", lay.slots[0].layer)
        assertEquals(300, lay.estKbit)
        assertEquals("ok", (H.parseEvent("""{"t":"state","state":"irgendwas"}""") as HornLogic.Event.State).state)
        assertEquals("entfernt", (H.parseEvent("""{"t":"closed","why":"entfernt"}""") as HornLogic.Event.Closed).why)
        assertEquals(42L, (H.parseEvent("""{"t":"pong","n":42}""") as HornLogic.Event.Pong).n)
        assertNull(H.parseEvent("""{"t":"layout"}"""))
        assertNull(H.parseEvent("""{"t":"unbekannt"}"""))
        assertNull(H.parseEvent("""[1,2]"""))
        assertNull(H.parseEvent("nicht json"))
        assertNull(H.parseEvent("""{"t":"pong"}"""))
    }

    @Test
    fun csrcMapping() {
        val devOf = mapOf("p1" to "devA", "p2" to "devB", "pMe" to "me")::get
        val map = H.csrcMap(mapOf("p1" to 5L, "pMe" to 9L, "pX" to 11L), listOf(HornLogic.Slot(0, "tile", "p2", 7)), devOf, "me")
        assertEquals(mapOf(5L to "devA", 7L to "devB"), map)
        assertEquals("devB", H.slotOwner(HornLogic.Slot(0, "tile", "p2", 7), map, devOf))
        assertEquals("ueber die Kennung", "devA", H.slotOwner(HornLogic.Slot(0, "tile", "p1", 0), map, devOf))
        assertNull("pausiert", H.slotOwner(HornLogic.Slot(0, "tile", "p2", 7, paused = true), map, devOf))
    }

    // ---- Schluesselplan --------------------------------------------------------------------------------------------

    private var seed = 1
    private fun rnd(): ByteArray = ByteArray(32) { (seed * 31 + it).toByte() }.also { seed++ }

    @Test
    fun ownKeys() {
        val k = HornLogic.OwnKeys(::rnd, "0123456789abcdef")
        val g0 = k.start(1000)
        assertEquals(0, g0.n)
        val g1 = k.join()
        assertEquals("Beitritt: Ratsche, sofort aktiv", 1, k.active.n)
        assertArrayEquals(HornE2E.ratchet(g0.key), g1.key)
        val g2 = k.leave(2000, setOf("x", "y"))
        assertEquals(2, g2.n)
        assertEquals("Abgang: alter Schluessel bleibt bis zum Umschalten", 1, k.active.n)
        val g3 = k.join()
        assertEquals("Beitritt waehrend ein Wechsel wartet: der wartende wird geratscht", 3, g3.n)
        assertArrayEquals(HornE2E.ratchet(g2.key), g3.key)
        assertEquals(1, k.active.n)
        assertNull(k.due(3000))
        assertFalse(k.ack("x", 3))
        assertFalse("falsche Generation zaehlt nicht", k.ack("y", 2))
        assertTrue("alle bestaetigt", k.ack("y", 3))
        assertEquals(3, k.due(3100, force = true)!!.n)
        assertEquals(3, k.active.n)
        k.leave(4000, emptySet())
        assertEquals("nach 1,5 s", 4, k.due(5500)!!.n)
        assertFalse(k.tooOld(5500 + 3600_000))
        assertTrue(k.tooOld(5500 + 6 * 3600_000L))
    }

    @Test
    fun peerKeys() {
        val k0 = rnd()
        val p = HornLogic.PeerKeys()
        assertTrue(p.plan().isEmpty())
        assertTrue(p.fkey(0, k0, "0123456789abcdef", 0))
        val plan0 = p.plan()
        assertEquals(listOf(0, 1, 2, 3, 4), plan0.map { it.n })
        assertArrayEquals(HornE2E.ratchet(k0, 2), plan0[2].key)
        p.join(1000)
        assertEquals(1, p.newest)
        assertEquals("Plan rueckt mit: newest und vier Stufen", listOf(1, 2, 3, 4, 5), p.plan().map { it.n })
        assertArrayEquals(plan0[1].key, p.plan()[0].key)
        assertTrue("vor 3 s laeuft nichts ab", p.expired(3000).isEmpty())
        assertEquals("alte Generation 0 nach 3 s", listOf(0), p.expired(4000))
        // fkey genau der vorhergesagten Stufe (der Sender schickt sie einem Neuen nicht an mich - aber falls doch): kein neuer Plan
        assertFalse(p.fkey(1, HornE2E.ratchet(k0), "0123456789abcdef", 5000))
        // Abgang: neuer Zufallsschluessel n = 2
        val k2 = rnd()
        assertTrue(p.fkey(2, k2, "0123456789abcdef", 6000))
        assertArrayEquals(k2, p.plan()[0].key)
        assertEquals(listOf(1), p.expired(9000))
        assertFalse("alt (n < newest - 1)", p.fkey(0, rnd(), "0123456789abcdef", 9000))
        // neue Sitzung (Neustart des Senders): alles Alte verworfen
        val k9 = rnd()
        assertTrue(p.fkey(0, k9, "fedcba9876543210", 10_000))
        assertEquals(0, p.newest)
        assertArrayEquals(k9, p.plan()[0].key)
        assertEquals("Ratsche = kid + 1", (0..4).toList(), p.plan().map { it.kid })
    }

    @Test
    fun hornCapOnlyWithServerFeature() {
        val base = setOf("reneg", "multi", "screen2")
        assertFalse("horn" in io.github.yggdrasil_sh.saga.call.CallRoom.myCaps(base))
        assertTrue("horn" in io.github.yggdrasil_sh.saga.call.CallRoom.myCaps(base + "horn"))
        assertFalse("ohne multi kein horn", "horn" in io.github.yggdrasil_sh.saga.call.CallRoom.myCaps(setOf("reneg", "horn")))
        assertTrue("multi" in io.github.yggdrasil_sh.saga.call.CallRoom.myCaps(base + "horn"))
    }

    @Test
    fun stateHp() {
        val o = Json.parseToJsonElement("""{"t":"state","mic":true,"hp":["p1",3,"","p2","p3","p4","p5"]}""").jsonObject
        assertEquals(listOf("p1", "p2", "p3", "p4"), HornLogic.parseHp(o))
        assertNull("ohne hp", HornLogic.parseHp(Json.parseToJsonElement("""{"t":"state","mic":true}""").jsonObject))
        assertNull("nur state", HornLogic.parseHp(Json.parseToJsonElement("""{"t":"fkey","hp":["a"]}""").jsonObject))
        val long = "x".repeat(65)
        assertEquals(listOf("a"), HornLogic.parseHp(Json.parseToJsonElement("""{"t":"state","hp":["$long","a"]}""").jsonObject))
        assertEquals(listOf("a", "b", "c", "d"), HornLogic.myRefs(listOf("a", "b", "a", "", "c", "d", "e")))
    }

    @Test
    fun meshNote() {
        assertEquals("Ohne Knoten", io.github.yggdrasil_sh.saga.call.CallRoom.meshNote(" Ohne Knoten "))
        assertNull(io.github.yggdrasil_sh.saga.call.CallRoom.meshNote("  "))
        assertNull(io.github.yggdrasil_sh.saga.call.CallRoom.meshNote(null))
    }

    @Test
    fun previewModelIsConsistent() {
        val ui = io.github.yggdrasil_sh.saga.call.hornPreviewUi("speaker")
        assertEquals("Sprecher zuerst", "d1", ui.tiles[0].dev)
        assertTrue(ui.tiles[0].big)
        assertEquals(6, ui.runes.codePointCount(0, ui.runes.length))
        assertEquals(6, ui.runeNames.size)
    }
}
