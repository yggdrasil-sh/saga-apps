package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.NoteLinks
import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.CallLogic
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.HornLogic
import io.github.yggdrasil_sh.saga.call.MicGuard
import io.github.yggdrasil_sh.saga.call.PcmSource
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.push.PushState
import io.github.yggdrasil_sh.saga.ui.page.insertedText
import io.github.yggdrasil_sh.saga.ui.push.PushUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import kotlin.math.PI
import kotlin.math.sin

/** Funde aus dem Android-Live-Test von Saga 2.5 (BERICHT.md, Nummern wie dort) */
class Tt25AndroidFixTest {
    private fun leg(dev: String, who: String = "lea", name: String = "Lea Sommer") =
        CallLeg(who = who, name = name, device = dev, devName = "Chrome", role = "member", caps = listOf("multi"))

    // ---------------------------------------------------------------- 63: Beitritt gescheitert -> Grund bleibt stehen

    @Test
    fun t63_failedJoinReasonStaysUntilClosed() {
        assertNull(CallLogic.endDismissMs(error = true, sticky = true))
        assertNull(CallLogic.endDismissMs(error = false, sticky = true))
        assertEquals(6000L, CallLogic.endDismissMs(error = true, sticky = false))
        assertEquals(3000L, CallLogic.endDismissMs(error = false, sticky = false))
    }

    // ---------------------------------------------------------------- 65: nicht mehr "Klingelt bei den anderen", wenn jemand da ist

    @Test
    fun t65_waitTextWhenOthersInRoster() {
        val w = { links: Int, others: Int, problem: Boolean -> CallRoom.waitState(true, true, group = true, starter = true, links, others, problem) }
        assertEquals(CallRoom.Wait.RINGING, w(0, 0, false))                 // allein: klingelt bei den anderen
        assertEquals(CallRoom.Wait.CONNECTING, w(0, 1, false))              // Lea ist da, Verbindung im Aufbau
        assertEquals(CallRoom.Wait.PROBLEM, w(0, 1, true))                  // Lea da, aber z. B. Sicherheitsnummer geaendert
        assertEquals(CallRoom.Wait.NONE, w(1, 1, true))                     // Verbindung steht
        assertEquals(CallRoom.Wait.RINGING, w(0, 0, true))                  // Grund ohne andere in der Liste: wieder klingeln
        assertEquals(CallRoom.Wait.WAITING, CallRoom.waitState(true, true, group = true, starter = false, links = 0, others = 0, problem = false))
        assertEquals(CallRoom.Wait.NONE, CallRoom.waitState(false, true, true, true, 0, 1, false))
        assertEquals(CallRoom.Wait.NONE, CallRoom.waitState(true, false, true, true, 0, 1, false))
    }

    // ---------------------------------------------------------------- 64: Leiste weg, wenn der Anruf als beendet bekannt ist

    @Test
    fun t64_runBarHidesEndedCalls() {
        val c = CallInfo(id = "a".repeat(32), chatId = 7, media = "audio", state = "active", role = "member", kind = CallRoom.KIND_GROUP,
            legs = listOf(leg("dev-lea")))
        assertEquals("a".repeat(32), CallRoom.runBar(listOf(c), "otto", "dev-otto", null)?.callId)
        assertNull(CallRoom.runBar(listOf(c), "otto", "dev-otto", null, ended = setOf("a".repeat(32))))
        assertNull(CallRoom.runBar(listOf(c.copy(state = "ended")), "otto", "dev-otto", null))
        assertTrue(CallLogic.RUN_POLL_MS in 10_000L..60_000L)
    }

    // ---------------------------------------------------------------- 67: Zertifikat des Knotens

    @Test
    fun t67_certErrorDetected() {
        val e = SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")
        assertTrue(HornLogic.certError(e))
        val wrapped = java.io.IOException("leg", SSLHandshakeException("x").apply { initCause(CertPathValidatorException("Trust anchor for certification path not found.")) })
        assertTrue(HornLogic.certError(wrapped))
        assertFalse(HornLogic.certError(java.net.SocketTimeoutException("timeout")))
        assertFalse(HornLogic.certError(java.io.IOException("failed")))
        assertFalse(HornLogic.certError(null))
    }

    @Test
    fun t67_giveUpOnlyWhenAllNodesFailOnCertificate() {
        val nodes = listOf("192.168.1.12")
        assertFalse(HornLogic.giveUpCert(nodes, other = false, rounds = 1))         // erste Runde: Grund nennen, noch einmal
        assertTrue(HornLogic.giveUpCert(nodes, other = false, rounds = HornLogic.CERT_ROUNDS))
        assertFalse(HornLogic.giveUpCert(nodes, other = true, rounds = 5))          // anderer Fehler dabei: Warten lohnt
        assertFalse(HornLogic.giveUpCert(emptyList(), other = false, rounds = 5))   // kein Zertifikatsfehler: wie bisher (2 min)
    }

    // ---------------------------------------------------------------- 61: Mikrofon nach dem Teilen (synthetische Quelle)

    /** Synthetische Quelle: Sinus 440 Hz, 48 kHz mono; gestoppt -> -3 (wie AudioRecord.read nach stop()) */
    private class Sine(var recording: Boolean = true, var broken: Boolean = false) : PcmSource {
        var t = 0
        var restarts = 0
        override fun read(buf: ByteBuffer, size: Int, mode: Int?): Int {
            if (!recording || broken) return MicGuard.ERROR_INVALID_OPERATION
            val d = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            d.clear()
            for (i in 0 until size / 2) d.putShort((sin(2 * PI * 440 * (t++) / 48000.0) * 8000).toInt().toShort())
            return size
        }
        override fun stopped(): Boolean = !recording
        override fun restart() {
            restarts++
            recording = true
        }
    }

    private fun energy(buf: ByteBuffer, n: Int): Long {
        val d = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        d.clear()
        var e = 0L
        for (i in 0 until n / 2) e += kotlin.math.abs(d.getShort().toInt())
        return e
    }

    @Test
    fun t61_stoppedMicIsRestartedNotFatal() {
        val src = Sine(recording = false)                    // so stand das Mikrofon nach dem alten Konstruktor (stop() durch release())
        val buf = ByteBuffer.allocateDirect(960)
        var got = 0
        val n = MicGuard.read(src, buf, 960, null, { got = it }, pause = {})
        assertEquals(960, n)                                 // nie -3 an libwebrtc (der Aufnahme-Thread wuerde enden)
        assertEquals(1, src.restarts)
        assertEquals(960, got)                               // Ton geht wieder an den Mischer/die Spur
        assertTrue(energy(buf, n) > 0)
    }

    @Test
    fun t61_persistentErrorGivesSilenceNotFailure() {
        val src = Sine(broken = true)
        val buf = ByteBuffer.allocateDirect(960)
        buf.put(0, 55)
        var paused = 0
        var data = false
        val n = MicGuard.read(src, buf, 960, null, { data = true }, pause = { paused++ })
        assertEquals(960, n)
        assertEquals(0L, energy(buf, n))
        assertEquals(1, paused)
        assertFalse(data)
    }

    @Test
    fun t61_afterSharingTheMicKeepsDelivering() {
        // Ablauf: teilen (Huelle liest), Teilen beenden (direkt) - die Quelle bleibt aufnehmend, jeder Block hat Ton
        val src = Sine()
        val buf = ByteBuffer.allocateDirect(960)
        repeat(50) { assertEquals(960, MicGuard.read(src, buf, 960, null, {}, pause = {})) }
        assertEquals(0, src.restarts)
        assertTrue(energy(buf, 960) > 0)
    }

    // ---------------------------------------------------------------- 10: Zeichen nach Return

    @Test
    fun t10_insertedText() {
        assertEquals("S", insertedText("\nErster", "\nErsterS"))
        assertEquals("Sc", insertedText("\n", "\nSc"))
        assertNull(insertedText("\nabc", "\nab"))            // geloescht
        assertNull(insertedText("\nabc", "\naXc"))           // ersetzt
        assertEquals("/", insertedText("", "/"))
    }

    // ---------------------------------------------------------------- 18: Unterseiten-Verweis mit aktuellem Titel

    @Test
    fun t18_noteLinksShowCurrentTitle() {
        val titles = mapOf(42L to "TT25 Unterseite", 7L to "A & B")
        val f = { id: Long -> titles[id] }
        assertEquals("📄 <a href=\"/notes#42\">TT25 Unterseite</a>", NoteLinks.refreshHtml("📄 <a href=\"/notes#42\">Unbenannt</a>", f))
        assertEquals("<a href=\"/notes#7\">A &amp; B</a>", NoteLinks.refreshHtml("<a href=\"/notes#7\">alt</a>", f))
        // unbekannte Seite / kein Titel: unveraendert
        assertEquals("<a href=\"/notes#9\">Unbenannt</a>", NoteLinks.refreshHtml("<a href=\"/notes#9\">Unbenannt</a>", f))
        assertEquals("<a href=\"https://x\">y</a>", NoteLinks.refreshHtml("<a href=\"https://x\">y</a>", f))
        val doc = Json.parseToJsonElement(
            """{"blocks":[{"id":"a","type":"paragraph","data":{"text":"📄 <a href=\"/notes#42\">Unbenannt</a>"}},""" +
                """{"id":"b","type":"list","data":{"items":[{"content":"<a href=\"/notes#7\">x</a>"}]}}]}""",
        ) as JsonObject
        val out = NoteLinks.refresh(doc, f)
        val bl = out["blocks"]!!.jsonArray
        assertTrue(bl[0].jsonObject["data"]!!.jsonObject["text"]!!.jsonPrimitive.content.contains(">TT25 Unterseite<"))
        assertTrue(bl[1].jsonObject["data"]!!.jsonObject["items"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains(">A &amp; B<"))
        assertTrue(NoteLinks.refresh(out, f) === out)        // nichts mehr zu tun -> dasselbe Objekt
    }

    // ---------------------------------------------------------------- 55: Personensuche ohne kryptische Kennung

    @Test
    fun t55_personDisplay() {
        val mark = { h: String -> "Kennung $h" }
        assertTrue(ChatLogic.opaque("~4wup3msnxxjvemea"))
        assertTrue(ChatLogic.opaque("~4wup3msnxxjvemea@192.168.1.12:8242"))
        assertFalse(ChatLogic.opaque("Lea Sommer"))
        assertFalse(ChatLogic.opaque("lea.sommer"))
        assertNull(ChatLogic.personSub("~4wup3msnxxjvemea", sameName = false, mark))
        assertEquals("Kennung ~4wup3m…", ChatLogic.personSub("~4wup3msnxxjvemea", sameName = true, mark))
        assertEquals("Lea S. (Vertrieb)", ChatLogic.personSub("Lea S. (Vertrieb)", sameName = false, mark))
        assertNull(ChatLogic.personSub(" ", sameName = true, mark))
        assertEquals("Lea Sommer", ChatLogic.shownMarked(ChatPerson(username = "~4wup3msnxxjvemea", name = "Lea Sommer"), mark))
        assertEquals("Kennung ~4wup3m…", ChatLogic.shownMarked(ChatPerson(username = "~4wup3msnxxjvemea"), mark))
    }

    // ---------------------------------------------------------------- 35: Schalter "Eigene Verbindung"

    @Test
    fun t35_streamSwitchOnlyActiveWithPush() {
        assertFalse(PushUi.streamSwitchEnabled(PushState.OFF))
        assertFalse(PushUi.streamSwitchEnabled(PushState.ERROR))
        assertTrue(PushUi.streamSwitchEnabled(PushState.ON))
        assertTrue(PushUi.streamSwitchEnabled(PushState.REGISTERING))
    }
}
