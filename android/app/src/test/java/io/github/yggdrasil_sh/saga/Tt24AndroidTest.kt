package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.auth.LoginFlow
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.auth.PairTarget
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ReadMark
import io.github.yggdrasil_sh.saga.chat.ReceiptHistory
import io.github.yggdrasil_sh.saga.chat.ReceiptLogic
import io.github.yggdrasil_sh.saga.chat.TickKind
import io.github.yggdrasil_sh.saga.data.ShareTargets
import io.github.yggdrasil_sh.saga.data.TargetRemote
import io.github.yggdrasil_sh.saga.ui.common.NotifPrompt
import io.github.yggdrasil_sh.saga.ui.login.QrCamera
import io.github.yggdrasil_sh.saga.ui.share.ShareLogic
import io.github.yggdrasil_sh.saga.util.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Funde aus dem Android-Test 2.4 (TT 2.4 P-05 bis P-15) */
class Tt24AndroidTest {
    private val anna = ChatPerson("anna", "Anna", me = true)
    private val bert = ChatPerson("bert", "Bert")

    private fun msg(seq: Long, mine: Boolean, fseq: Long? = null) =
        ChatMessage(seq, "m$seq", if (mine) anna else bert, "d", seq * 1000, null, null, null, true, mine, fseq = fseq)

    @Test
    fun readTimeDoesNotJump() {
        // P-06: dieselbe Position erneut bestaetigt (erneut geoeffnet) -> fruehere Zeit bleibt
        assertFalse(ReceiptLogic.take(ReadMark(4, "a", 1000), ReadMark(4, "a", 2000)))
        assertTrue(ReceiptLogic.take(ReadMark(4, "a", 2000), ReadMark(4, "a", 1000)))
        assertTrue(ReceiptLogic.take(ReadMark(4, "a", 2000), ReadMark(5, "b", 3000)))
        // Historie: Lesezeit einer Nachricht = erste Bestaetigung, die sie abdeckt (nicht die neueste)
        var h = emptyList<ReadMark>()
        h = ReceiptHistory.merge(h, ReadMark(3, "c", 7_35))
        h = ReceiptHistory.merge(h, ReadMark(5, "e", 7_37))
        h = ReceiptHistory.merge(h, ReadMark(5, "e", 8_10))         // erneut geoeffnet: bleibt 7:37
        h = ReceiptHistory.merge(h, ReadMark(8, "h", 7_43))
        assertEquals(735L, ReceiptHistory.readAt(h, 2))
        assertEquals(735L, ReceiptHistory.readAt(h, 3))
        assertEquals(737L, ReceiptHistory.readAt(h, 4))
        assertEquals(743L, ReceiptHistory.readAt(h, 8))
        assertNull(ReceiptHistory.readAt(h, 9))
        assertNull(ReceiptHistory.readAt(h, 0))
        // gespeichert und wieder gelesen: gleich
        assertEquals(mapOf("bert" to h), ReceiptHistory.decode(ReceiptHistory.encode(mapOf("bert" to h))))
        assertEquals(emptyMap<String, List<ReadMark>>(), ReceiptHistory.decode("kaputt"))
        // begrenzt auf die neuesten MAX
        var big = emptyList<ReadMark>()
        for (i in 1..(ReceiptHistory.MAX + 10)) big = ReceiptHistory.merge(big, ReadMark(i.toLong(), "x$i", i.toLong()))
        assertEquals(ReceiptHistory.MAX, big.size)
        assertEquals(11L, big.first().n)
    }

    @Test
    fun ownMessageWithoutPositionCountsByOrder() {
        // P-06: eigenes Bild im foederierten Chat ohne fseq bleibt sonst fuer immer bei ✓
        val members = listOf(anna, bert)
        val img = msg(5, true, fseq = null)
        val later = msg(6, false, fseq = 9)
        val order = listOf(msg(4, false, fseq = 7), img, later)
        val reads = mapOf("bert" to ReadMark(9, later.id, 1))
        assertEquals(TickKind.SENT, ReceiptLogic.tick(img, true, true, true, members, reads).kind)
        assertEquals(TickKind.READ, ReceiptLogic.tick(img, true, true, true, members, reads, order).kind)
        // Bestaetigung fuer eine aeltere Nachricht deckt das Bild nicht ab
        assertEquals(TickKind.SENT, ReceiptLogic.tick(img, true, true, true, members, mapOf("bert" to ReadMark(7, "m4", 1)), order).kind)
    }

    @Test
    fun debugHttpOnlyPrivateNetworks() {
        // P-07: Debug-Build erlaubt http im privaten Netz, Release nie
        for (h in listOf("192.168.0.20", "10.0.2.2", "10.1.2.3", "172.16.0.1", "172.31.255.1", "127.0.0.1", "localhost", "nas.local", "fd12::1", "[fe80::1]")) {
            assertTrue(h, ServerUrl.isPrivateHost(h))
        }
        for (h in listOf("8.8.8.8", "172.32.0.1", "192.169.1.1", "evil.example.org", "2001:db8::1", "", "999.1.1.1")) {
            assertFalse(h, ServerUrl.isPrivateHost(h))
        }
        assertEquals("http://192.168.0.20:8241", ServerUrl.normalize("http://192.168.0.20:8241/", allowLocalHttp = true))
        assertNull(ServerUrl.normalize("http://192.168.0.20:8241", allowLocalHttp = false))
        assertNull(ServerUrl.normalize("http://saga.example.org", allowLocalHttp = true))
        val code = "A".repeat(32)
        assertEquals("http://192.168.0.20:8241", PairLink.check(PairTarget("http://192.168.0.20:8241", code), allowLocalHttp = true)?.server)
        assertNull(PairLink.check(PairTarget("http://192.168.0.20:8241", code), allowLocalHttp = false))
        // abgewiesen: Grund statt stillem Ignorieren
        assertEquals(R.string.pair_rejected_http, PairLink.rejectReason(PairTarget("http://192.168.0.20:8241", code), false))
        assertEquals(R.string.pair_rejected_http_debug, PairLink.rejectReason(PairTarget("http://saga.example.org", code), true))
        assertEquals(R.string.login_invalid_pair_link, PairLink.rejectReason(PairTarget("https://saga.example.org", "kurz"), false))
    }

    @Test
    fun loginWindowInAppLanguage() {
        // P-15: Sprache der App an das Anmeldefenster
        assertEquals("https://s.example/app/login?challenge=abc&scheme=saga&lang=de", LoginFlow.loginUrl("https://s.example", "abc", false, "de"))
        assertEquals("https://s.example/app/login?challenge=abc&scheme=saga&prompt=login&lang=th", LoginFlow.loginUrl("https://s.example", "abc", true, "th-TH"))
        assertEquals("https://s.example/app/login?challenge=abc&scheme=saga", LoginFlow.loginUrl("https://s.example", "abc", false, "fr"))
    }

    @Test
    fun federatedShareFallsBackToAddressBook() {
        // P-13: name@server -> zusaetzlich Adressbuch nach "name", nur Treffer dieses Servers
        assertEquals("nora" to "192.168.0.20:8242", ShareLogic.addressParts("nora@192.168.0.20:8242"))
        assertEquals("nora" to "b.example", ShareLogic.addressParts(" @nora@B.example "))
        assertNull(ShareLogic.addressParts("nora"))
        assertNull(ShareLogic.addressParts("nora@team"))
        val r = ShareTargets(remote = listOf(
            TargetRemote(target = "@~x@192.168.0.20:8242", name = "Nora", host = "192.168.0.20:8242"),
            TargetRemote(target = "@~y@c.example", name = "Nora C", host = "c.example"),
        ))
        assertEquals(listOf("@~x@192.168.0.20:8242"), ShareLogic.sameHost(r, "192.168.0.20:8242").map { it.target })
    }

    @Test
    fun notificationPromptOnce() {
        // P-05: ab Android 13, ohne Berechtigung, nur einmal
        assertTrue(NotifPrompt.shouldAsk(33, granted = false, askedBefore = false))
        assertFalse(NotifPrompt.shouldAsk(32, granted = false, askedBefore = false))
        assertFalse(NotifPrompt.shouldAsk(35, granted = true, askedBefore = false))
        assertFalse(NotifPrompt.shouldAsk(35, granted = false, askedBefore = true))
    }

    @Test
    fun emptyVersionWarns() {
        // P-10: leerer Anfangsstand -> Rueckfrage warnt ausdruecklich
        assertTrue(io.github.yggdrasil_sh.saga.ui.page.PageToolsLogic.versionEmpty("Unbenannt", ""))
        assertTrue(io.github.yggdrasil_sh.saga.ui.page.PageToolsLogic.versionEmpty(null, null))
        assertFalse(io.github.yggdrasil_sh.saga.ui.page.PageToolsLogic.versionEmpty("Notiz", "Text"))
    }

    @Test
    fun qrWithoutCameraIsReadable() {
        // P-08: keine rohe Ausnahme, verstaendlicher Text mit Ausweg
        val e = RuntimeException("androidx.camera.core.InitializationException: Expected camera missing from device. Available cameras: 0")
        assertEquals(R.string.qr_no_camera_paste, QrCamera.errorText(e))
        assertEquals(R.string.qr_camera_unavailable_paste, QrCamera.errorText(IllegalStateException("busy")))
        assertFalse(QrCamera.available(false))
    }
}
