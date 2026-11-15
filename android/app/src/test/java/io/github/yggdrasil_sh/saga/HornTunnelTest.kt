package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallDiag
import io.github.yggdrasil_sh.saga.call.HornLogic
import io.github.yggdrasil_sh.saga.call.HornLogic.Cand
import io.github.yggdrasil_sh.saga.call.HornLogic.Ways
import io.github.yggdrasil_sh.saga.call.HornWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saga 2.5 A3 "ICE-TCP im WebSocket-Tunnel": Wegwahl, SDP-Biegen und Tunnel-Protokoll ([HornWire]) - dieselben Faelle
 * wie ios/Tests/Logic/HornTunnelTests.swift.
 */
class HornTunnelTest {
    private val H = HornLogic

    private fun cand(ws: Boolean = false, tcp: Boolean = true, tls: Boolean = true) = Cand("n", "https://x.example", ws = ws, tcp = tcp, tls = tls)

    @Test
    fun candidateFields() {
        val b = H.parseBlock(Json.parseToJsonElement(
            """{"candidates":[{"node":"n1","url":"https://saga.example.org/","udp":true,"ws":true,"tcp":false,"tls":false},
               {"node":"n2","url":"https://saga.example.org"},{"node":"n3","url":"https://saga.example.org","ws":"ja","tcp":"nein"}]}""",
        ))!!
        val (a, o, x) = b.candidates
        assertTrue("ws/tcp/tls gelesen", a.ws && !a.tcp && !a.tls && a.udp)
        assertEquals("https://saga.example.org", a.url)
        assertTrue("aelterer Server ohne Felder: kein ws, tcp/tls wie bisher", !o.ws && o.tcp && o.tls)
        assertTrue("nur Wahrheitswerte", !x.ws && x.tcp)
    }

    @Test
    fun ways() {
        assertEquals("ohne ws: UDP -> TCP -> TLS", Ways(listOf("udp"), listOf("tcp", "turn")), H.ways(cand(), true))
        assertEquals(Ways(emptyList(), listOf("tcp", "turn")), H.ways(cand(), false))
        assertEquals("Tunnel-Betrieb mit UDP: parallel, kein TCP/TLS", Ways(listOf("udp", "ws"), emptyList()), H.ways(cand(ws = true, tcp = false, tls = false), true))
        assertEquals("Tunnel-Betrieb ohne UDP: nur Tunnel", Ways(listOf("ws"), emptyList()), H.ways(cand(ws = true, tcp = false, tls = false), false))
        assertEquals("ws neben TCP/TLS: Tunnel zuletzt", Ways(listOf("udp"), listOf("tcp", "turn", "ws")), H.ways(cand(ws = true), true))
        assertEquals("ws neben TLS", Ways(emptyList(), listOf("turn", "ws")), H.ways(cand(ws = true, tcp = false), false))
        assertEquals("ws neben TCP", Ways(emptyList(), listOf("tcp", "ws")), H.ways(cand(ws = true, tls = false), false))
        assertEquals("tcp:false: gleich TLS", Ways(emptyList(), listOf("turn")), H.ways(cand(tcp = false), false))
        assertEquals("nichts angeboten: TLS als letzter Versuch", Ways(emptyList(), listOf("turn")), H.ways(cand(tcp = false, tls = false), false))
        val w = H.ways(cand(), true)
        assertFalse("Tunnel nur, wenn angeboten", "ws" in w.race || "ws" in w.then)
        assertTrue(H.WS_HEAD_UDP > H.WS_HEAD_UDP_MAYBE && H.WS_HEAD_UDP < H.WAIT_UDP)
        assertEquals("ws", H.streamPath(cand(ws = true, tcp = false, tls = false)))
        assertEquals("tcp", H.streamPath(cand(ws = true)))
        assertEquals("tcp", H.streamPath(cand()))
    }

    @Test
    fun switcherUdpLossToTunnel() {
        var sw = HornLogic.Switcher("n", "udp", 0)
        sw.udp(1000, 0.5, stream = "ws")
        assertEquals("ws", sw.udp(1000 + H.UDP_LOSS_FOR, 0.5, stream = "ws")?.path)
        sw = HornLogic.Switcher("n", "udp", 0)
        sw.udp(1000, 0.5)
        assertEquals("ohne Tunnel wie bisher tcp", "tcp", sw.udp(1000 + H.UDP_LOSS_FOR, 0.5)?.path)
        sw = HornLogic.Switcher("n", "ws", 0)
        sw.udp(1000, 0.0, ok = true, stream = "ws")
        assertEquals("vom Tunnel zurueck auf UDP", "udp", sw.udp(1000 + H.UDP_BACK_FOR, 0.0, ok = true, stream = "ws")?.path)
    }

    @Test
    fun ratesAndNames() {
        assertEquals("ws: Deckel wie TCP (Vertrag 11)", H.TX["tcp"], H.TX["ws"])
        assertEquals(listOf(150, 600), H.camEncodings("ws").map { it.maxKbit })
        assertEquals(900 to 8, H.screenRate("ws"))
        assertEquals("WS", H.pathName("ws"))
        assertEquals("ws", CallDiag.transportHorn("ws"))
        assertEquals("tls", CallDiag.transportHorn("turn"))
        assertEquals("ws", HornWire.legPath("ws"))
        assertEquals("udp", HornWire.legPath("udp"))
        assertEquals("turn", HornWire.legPath("turn"))
    }

    @Test
    fun bend() {
        val ans = listOf(
            "v=0", "a=candidate:1 1 udp 2130706431 203.0.113.7 443 typ host",
            "a=candidate:2 1 tcp 1671430143 203.0.113.7 443 typ host tcptype passive",
            "a=candidate:3 1 tcp 1671430142 2001:db8::7 443 typ host tcptype passive",
            "a=candidate:4 1 tcp 1671430141 203.0.113.7 9 typ host tcptype active",
            "a=end-of-candidates", "",
        ).joinToString("\r\n")
        val b = HornWire.bend(ans, 50123)
        val lines = b.split("\r\n")
        assertTrue(lines.contains("a=candidate:2 1 tcp 1671430143 127.0.0.1 50123 typ host tcptype passive"))
        assertFalse("weitere passive TCP-Kandidaten fallen weg", b.contains("2001:db8::7"))
        assertTrue(lines.contains("a=candidate:1 1 udp 2130706431 203.0.113.7 443 typ host"))
        assertTrue(lines.contains("a=candidate:4 1 tcp 1671430141 203.0.113.7 9 typ host tcptype active"))
        assertTrue(lines.first() == "v=0" && lines.last() == "" && lines.contains("a=end-of-candidates"))
        assertTrue(HornWire.bendable(ans))
        assertFalse(HornWire.bendable("v=0\r\na=candidate:1 1 udp 1 203.0.113.7 443 typ host\r\n"))
        val srflx = "a=candidate:2 1 TCP 1 203.0.113.7 443 typ srflx tcptype passive"
        assertEquals("nur host-Kandidaten", srflx, HornWire.bend(srflx, 1))
    }

    @Test
    fun wire() {
        assertEquals("wss://saga.example.org:8443/api/sfu/ws", HornWire.url("https://saga.example.org:8443/x?y=1"))
        assertEquals("ws://127.0.0.1:8080/api/sfu/ws", HornWire.url("http://127.0.0.1:8080"))
        assertNull(HornWire.url("ftp://x"))
        assertNull(HornWire.url(""))
        assertEquals("saga-tunnel.1", HornWire.PROTO)
        val hs = HornWire.hello("1700000000:abc", "p/w+=", "video")
        val h = Json.parseToJsonElement(hs).jsonObject
        assertEquals("auth", (h["t"] as JsonPrimitive).content)
        assertEquals("1", (h["v"] as JsonPrimitive).content)
        assertEquals("ice", (h["mode"] as JsonPrimitive).content)
        assertEquals("1700000000:abc", (h["user"] as JsonPrimitive).content)
        assertEquals("p/w+=", (h["cred"] as JsonPrimitive).content)
        assertEquals("video", (h["role"] as JsonPrimitive).content)
        assertTrue(hs.toByteArray().size <= 4096)
        assertNull("role nur audio/video", Json.parseToJsonElement(HornWire.hello("u", "p", "x")).jsonObject["role"])
        assertFalse("Zugang nie in der Adresse", HornWire.url("https://saga.example.org")!!.contains("abc"))
        assertEquals(HornWire.Reply.Ok(8192, 10), HornWire.reply("""{"t":"ok","v":1,"ack_bytes":8192,"ack_ms":10}"""))
        assertEquals(HornWire.Reply.Ok(16384, 15), HornWire.reply("""{"t":"ok","v":1}"""))
        assertEquals(HornWire.Reply.Ok(), HornWire.reply("""{"t":"ok","ack_bytes":0,"ack_ms":-1}"""))
        assertEquals(HornWire.Reply.Denied("auth"), HornWire.reply("""{"t":"err","v":1,"code":"auth"}"""))
        assertEquals(HornWire.Reply.Denied("err"), HornWire.reply("""{"t":"err","v":1}"""))
        assertEquals(HornWire.Reply.Bad, HornWire.reply("kaputt"))
        assertEquals(HornWire.Reply.Bad, HornWire.reply("{}"))
        assertEquals(HornWire.Reply.Bad, HornWire.reply("""{"ok":true}"""))
        val d = byteArrayOf(1, 2, 3)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), HornWire.data(d))
        assertArrayEquals(byteArrayOf(0, 1, 2), HornWire.data(d, 2))
        assertArrayEquals(d, (HornWire.frame(HornWire.data(d)) as HornWire.Frame.Data).b)
        assertArrayEquals(byteArrayOf(1, 1, 2, 3, 4, 5, 6, 7, 8), HornWire.ack(0x0102030405060708))
        assertEquals(HornWire.Frame.Ack(70_000), HornWire.frame(HornWire.ack(70_000)))
        for (m in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(1, 0), byteArrayOf(9, 1))) assertTrue(HornWire.frame(m) is HornWire.Frame.Unknown)
        assertTrue(HornWire.ACK_MS in 10..20)
        assertTrue(HornWire.failText(426).contains("Proxy reicht WebSocket nicht durch"))
        assertEquals("ws abgelehnt auth", HornWire.failText(0, "auth"))
        assertEquals("ws HTTP 502", HornWire.failText(502))
    }
}
