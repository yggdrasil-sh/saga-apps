package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatImages
import io.github.yggdrasil_sh.saga.util.LinkPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pentest Runde 2 (Apps): Bildgrenzen der Linkvorschau (A3), Link-Allowlist (A1) */
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
}
