package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.IceFilter
import io.github.yggdrasil_sh.saga.chat.ChatImages
import io.github.yggdrasil_sh.saga.util.LinkPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pentest Runde 2 (Apps): Bildgrenzen der Linkvorschau (A3), Link-Allowlist (A1), ICE-Filter (A4) */
class Pt2AppsTest {
    @Test fun `A3 Vorschaubild nie groesser als die Grenze`() {
        val max = ChatImages.PREVIEW_MAX
        // 20000 x 20000 (Angriff aus dem Pentest): 32 -> 625 px
        assertEquals(32, ChatImages.boundedSample(20000, 20000, max))
        for ((w, h) in listOf(20000 to 20000, 1025 to 10, 4000 to 3000, 2048 to 2048, 2049 to 1, 1 to 30000, 65535 to 3)) {
            val s = ChatImages.boundedSample(w, h, max)
            assertTrue("$w x $h", s >= 1)
            assertTrue("$w x $h -> $s", (maxOf(w, h) + s - 1) / s <= max)
        }
        assertEquals(1, ChatImages.boundedSample(1024, 768, max))
        assertEquals(1, ChatImages.boundedSample(64, 64, max))
        assertEquals(2, ChatImages.boundedSample(1025, 1, max))
    }

    @Test fun `A3 unbrauchbare oder absurde Masse werden nicht dekodiert`() {
        assertEquals(0, ChatImages.boundedSample(0, 10, 1024))
        assertEquals(0, ChatImages.boundedSample(-1, -1, 1024))
        assertEquals(0, ChatImages.boundedSample(100, 100, 0))
        assertEquals(0, ChatImages.boundedSample(30000, 30000, 1024))          // 900 MP
        assertEquals(0, ChatImages.boundedSample(Int.MAX_VALUE, Int.MAX_VALUE, 1024))
    }

    @Test fun `A3 Nachverkleinern, falls der Dekoder groesser liefert`() {
        assertNull(ChatImages.clampSize(1024, 500, 1024))
        assertEquals(1024 to 512, ChatImages.clampSize(2048, 1024, 1024))
        assertEquals(1 to 1024, ChatImages.clampSize(3, 5000, 1024))
    }

    @Test fun `A1 nur http(s) und mailto gehen an andere Apps`() {
        for (u in listOf("https://example.org", "http://example.org/a?b=c#d", "HTTPS://Example.org", "https://user@example.org/x",
            "mailto:anna@example.org", "https://[2001:db8::1]:8443/")) assertTrue(u, LinkPolicy.externalOk(u))
        for (u in listOf("tel:+491234", "geo:0,0?q=x", "market://details?id=x", "saga://pair?u=x&c=y", "saga://auth?code=x",
            "intent://x#Intent;scheme=http;end", "javascript:alert(1)", "file:///sdcard/x", "content://x/y", "data:text/html,x",
            "sms:123", "https:example.org", "https://", "https://@/", "mailto:", "", "  ", "https://exa mple.org",
            "https://example.org/\nx", "ftp://example.org", "about:blank")) assertFalse(u, LinkPolicy.externalOk(u))
        assertEquals("https", LinkPolicy.scheme(" HTTPS://x"))
        assertNull(LinkPolicy.scheme("/notes#1"))
        assertFalse(LinkPolicy.externalOk("/notes#1"))                     // relative Ziele loest der Aufrufer selbst auf
    }

    @Test fun `A4 ICE-Adressen - Schema und Form`() {
        for (u in listOf("stun:stun.example.org:3478", "stuns:stun.example.org", "turn:turn.example.org:3478?transport=udp",
            "turns:turn.example.org:443?transport=tcp", "TURNS:Turn.Example.org:443", "turn:[2001:db8::5]:3478", "stun:203.0.113.7:3478"))
            assertTrue(u, IceFilter.allowed(u, serverLocal = false))
        for (u in listOf("http://turn.example.org", "turn://turn.example.org", "turn:", "turn:host:0", "turn:host:70000", "turn:a b",
            "turn:host?transport=sctp", "turn:user@host", "javascript:alert(1)", "stun:host:3478/x", "", "turns:host:443?transport=tcp&x=1"))
            assertFalse(u, IceFilter.allowed(u, serverLocal = true))
    }

    @Test fun `A4 ICE-Adressen - nie Loopback, Link-Local, unspezifiziert`() {
        for (h in listOf("127.0.0.1", "127.8.9.10", "0.0.0.0", "169.254.169.254", "224.0.0.1", "255.255.255.255", "localhost", "LOCALHOST.",
            "x.localhost", "[::1]", "[::]", "[fe80::1]", "[fe80::1%25wlan0]", "[::ffff:127.0.0.1]", "[::ffff:169.254.169.254]", "[ff02::1]",
            "127.1", "2130706433", "0x7f.0.0.1", "0177.0.0.1", "[0:0:0:0:0:0:0:1]")) {
            assertFalse(h, IceFilter.allowed("turn:$h:3478", serverLocal = false))
            assertFalse(h, IceFilter.allowed("turn:$h:3478", serverLocal = true))
        }
    }

    @Test fun `A4 private Adressen nur bei Server im lokalen Netz`() {
        for (h in listOf("10.0.0.5", "172.16.0.1", "172.31.255.1", "192.168.0.10", "100.64.1.2", "[fd12:3456::1]", "[::ffff:10.1.2.3]",
            "turn.local", "box.home.arpa")) {
            assertFalse(h, IceFilter.allowed("turns:$h:443?transport=tcp", serverLocal = false))
            assertTrue(h, IceFilter.allowed("turns:$h:443?transport=tcp", serverLocal = true))
        }
        assertTrue(IceFilter.allowed("turn:172.32.0.1", serverLocal = false))               // knapp ausserhalb 172.16/12
        assertEquals(listOf("stun:stun.example.org:443"), IceFilter.filter(listOf("stun:127.0.0.1", "stun:stun.example.org:443", "turn:10.0.0.1"), false))
        assertEquals(8, IceFilter.filter(List(20) { "stun:s$it.example.org" }, false).size)
    }
}
