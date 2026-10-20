package io.github.yggdrasil_sh.saga

import android.content.pm.ServiceInfo
import io.github.yggdrasil_sh.saga.call.CallPeer
import io.github.yggdrasil_sh.saga.call.CallPhase
import io.github.yggdrasil_sh.saga.call.CallPip
import io.github.yggdrasil_sh.saga.call.CallServiceTypes
import io.github.yggdrasil_sh.saga.call.CallUiState
import io.github.yggdrasil_sh.saga.call.CallV3
import io.github.yggdrasil_sh.saga.call.CallV3.CandAction
import io.github.yggdrasil_sh.saga.call.CallV3.Negotiator.OfferAction
import io.github.yggdrasil_sh.saga.call.CallV3.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saga 2.4: saga-call v3 (E0, Vertrag Server docs/APP-API.md) und Bild-im-Bild - Weg, Bitrate, Glare, pc/n,
 * ICE-Generationen, Kompatibilitaet mit 2.3, want small/large
 */
class CallV3Test {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    // ---------------------------------------------------------------- Weg

    @Test
    fun wegAusKandidatenpaar() {
        assertNull(CallV3.path(null, null, null))
        assertEquals(Path.DIRECT, CallV3.path("host", null, "host", "udp"))
        assertEquals(Path.DIRECT, CallV3.path("srflx", null, "prflx"))
        assertEquals(Path.RELAY_UDP, CallV3.path("relay", "udp", "host"))
        assertEquals(Path.RELAY_UDP, CallV3.path("relay", null, "srflx"))
        // turns:…:443?transport=tcp -> relayProtocol "tls" bzw. "tcp"
        assertEquals(Path.RELAY_TCP, CallV3.path("relay", "tls", "srflx"))
        assertEquals(Path.RELAY_TCP, CallV3.path("relay", "TCP", "relay"))
        // ICE-TCP ohne Relais zaehlt wie relay-tcp
        assertEquals(Path.RELAY_TCP, CallV3.path("host", null, "host", "tcp"))
        // nur das Gegenueber nutzt ein Relais -> mindestens relay-udp
        assertEquals(Path.RELAY_UDP, CallV3.path("srflx", null, "relay"))
        // Draht-Werte fuer state.path und Anzeige wie 2.3
        assertEquals(listOf("direct", "relay-udp", "relay-tcp"), Path.entries.map { it.wire })
        assertEquals("relay", CallV3.pathLabel(Path.RELAY_TCP))
        assertEquals("", CallV3.pathLabel(null))
    }

    @Test
    fun schlechtererWegZaehlt() {
        assertEquals(Path.RELAY_TCP, CallV3.parsePath("relay-tcp"))
        assertNull(CallV3.parsePath("relay"))                             // unbekannt
        assertNull(CallV3.parsePath(null))
        assertEquals(Path.RELAY_TCP, CallV3.worse(Path.DIRECT, Path.RELAY_TCP))
        assertEquals(Path.RELAY_UDP, CallV3.worse(Path.RELAY_UDP, Path.DIRECT))
        assertEquals(Path.DIRECT, CallV3.worse(Path.DIRECT, null))       // Gegenueber 2.3 meldet keinen Weg
        assertEquals(Path.RELAY_UDP, CallV3.worse(null, Path.RELAY_UDP))
        assertNull(CallV3.worse(null, null))
    }

    // ---------------------------------------------------------------- Bitrate (Tabelle "Bitrate je Weg")

    @Test
    fun bitrateKameraJeWeg() {
        val d = CallV3.videoLimits(Path.DIRECT)
        assertEquals(1_500_000, d.maxBps); assertEquals(1.0, d.scaleDown, 0.0); assertNull(d.maxFps)
        assertEquals(1_200_000, CallV3.videoLimits(Path.RELAY_UDP).maxBps)
        val t = CallV3.videoLimits(Path.RELAY_TCP)
        assertEquals(800_000, t.maxBps)
        assertEquals(540, (720 / t.scaleDown).toInt())                   // <= 540p
        assertTrue(d.active)
        // "<= 720p": Quelle 1080 -> 1,5; kleinere Quelle wird nicht vergroessert
        assertEquals(1.5, CallV3.videoLimits(Path.DIRECT, srcHeight = 1080).scaleDown, 1e-9)
        assertEquals(1.0, CallV3.videoLimits(Path.RELAY_TCP, srcHeight = 480).scaleDown, 0.0)
    }

    @Test
    fun bitrateGegenueberImPip() {
        for (p in Path.entries) {
            val s = CallV3.videoLimits(p, want = "small")
            assertEquals("$p", 300_000, s.maxBps)
            assertEquals("$p", 3.0, s.scaleDown, 0.0)                     // max(2, min(3, 720/240))
        }
        assertEquals(2.0, CallV3.videoLimits(Path.DIRECT, "small", srcHeight = 360).scaleDown, 0.0)
        assertEquals(3.0, CallV3.videoLimits(Path.DIRECT, "small", srcHeight = 1080).scaleDown, 0.0)
        // "large" bzw. unbekannt -> unveraendert, "off" -> Kamera-Encoding aus
        assertEquals(CallV3.videoLimits(Path.DIRECT), CallV3.videoLimits(Path.DIRECT, "large"))
        assertFalse(CallV3.videoLimits(Path.DIRECT, "off").active)
    }

    @Test
    fun bitrateBildschirmUndMesh() {
        val sd = CallV3.videoLimits(Path.DIRECT, screen = true)
        assertEquals(2_500_000, sd.maxBps); assertEquals(15, sd.maxFps)
        assertEquals(CallV3.Degradation.MAINTAIN_RESOLUTION, sd.degradation)   // Bildschirm immer
        assertEquals(1_800_000, CallV3.videoLimits(Path.RELAY_UDP, screen = true).maxBps)
        val ts = CallV3.videoLimits(Path.RELAY_TCP, screen = true)
        assertEquals(1_200_000, ts.maxBps); assertEquals(8, ts.maxFps)
        // Bildschirm bleibt bei small/off unveraendert
        assertEquals(ts, CallV3.videoLimits(Path.RELAY_TCP, "small", screen = true))
        assertEquals(ts, CallV3.videoLimits(Path.RELAY_TCP, "off", screen = true))
        assertEquals(600_000, CallV3.videoLimits(Path.DIRECT, peers = 3).maxBps)
        assertEquals(540, (720 / CallV3.videoLimits(Path.DIRECT, peers = 3).scaleDown).toInt())
        assertEquals(500_000, CallV3.videoLimits(Path.RELAY_UDP, peers = 4).maxBps)
        val tm = CallV3.videoLimits(Path.RELAY_TCP, peers = 3)
        assertEquals(350_000, tm.maxBps); assertEquals(15, tm.maxFps); assertEquals(360, (720 / tm.scaleDown).toInt())
        assertEquals(200_000, CallV3.videoLimits(Path.DIRECT, "small", peers = 3).maxBps)
    }

    @Test
    fun audioBitrate() {
        assertEquals(32_000, CallV3.audioMaxBps(Path.DIRECT))
        assertEquals(32_000, CallV3.audioMaxBps(Path.RELAY_UDP))
        assertEquals(24_000, CallV3.audioMaxBps(Path.RELAY_TCP))
    }

    // ---------------------------------------------------------------- pc, n, Perfect Negotiation / Glare

    @Test
    fun pcIstSortiertesGeraetepaar() {
        assertEquals("2f67:65fb", CallV3.pcId("65fb", "2f67"))
        assertEquals("2f67:65fb", CallV3.pcId("2f67", "65fb"))
        assertTrue(CallV3.pcMatches(null, "2f67:65fb"))                  // 2.3 ohne pc: die einzige Verbindung
        assertTrue(CallV3.pcMatches("2f67:65fb", "2f67:65fb"))
        assertFalse(CallV3.pcMatches("2f67:aaaa", "2f67:65fb"))          // fremde PeerConnection
    }

    @Test
    fun hoeflichIstGroessereGeraeteId() {
        assertTrue(CallV3.polite("b", "a"))
        assertFalse(CallV3.polite("a", "b"))
        val x = "3f1c"; val y = "a0b2"
        assertTrue(CallV3.polite(x, y) != CallV3.polite(y, x))
    }

    @Test
    fun nummernAbErstemAngebot() {
        // Anrufer: erstes Angebot n=1; Angerufener hat es verarbeitet (remoteN=1), sein erstes eigenes Angebot ist 1
        val caller = CallV3.Negotiator(polite = false, n = 1, remoteN = 0)
        val callee = CallV3.Negotiator(polite = true, n = 0, remoteN = 1)
        assertEquals(OfferAction.STALE, callee.onOffer(1, stable = true))      // erstes Angebot nicht noch einmal
        val k = callee.startOffer()
        assertEquals(1, k)
        assertEquals(OfferAction.ACCEPT, caller.onOffer(k, stable = true))
        assertEquals(1, caller.remoteN)                                     // Antwort traegt n=1
        assertTrue(callee.onAnswer(caller.remoteN, haveLocalOffer = true))
        callee.offerDone()
        val r = caller.startOffer()
        assertEquals(2, r)                                                  // ICE-Restart des Anrufers
        assertEquals(OfferAction.ACCEPT, callee.onOffer(r, stable = true))
        assertTrue(caller.onAnswer(callee.remoteN, haveLocalOffer = true))
        assertFalse(caller.onAnswer(1, haveLocalOffer = true))              // altes n
    }

    @Test
    fun glareHoeflichGibtNach() {
        val polite = CallV3.Negotiator(polite = true, n = 0, remoteN = 1)
        val impolite = CallV3.Negotiator(polite = false, n = 1, remoteN = 0)
        val np = polite.startOffer()
        val ni = impolite.startOffer()
        assertEquals(1, np); assertEquals(2, ni)
        assertEquals(OfferAction.IGNORE, impolite.onOffer(np, stable = false))
        assertTrue(impolite.makingOffer)
        assertEquals(OfferAction.ROLLBACK_ACCEPT, polite.onOffer(ni, stable = false))
        assertFalse(polite.makingOffer)
        assertTrue(impolite.onAnswer(polite.remoteN, haveLocalOffer = true))
        impolite.offerDone()
        assertFalse(impolite.makingOffer)
    }

    @Test
    fun angebotOhneKollision() {
        val n = CallV3.Negotiator(polite = false)
        assertEquals(OfferAction.ACCEPT, n.onOffer(1, stable = true))
        assertEquals(OfferAction.STALE, n.onOffer(1, stable = true))       // doppelt (Abfrage + Live-Kanal)
        assertEquals(OfferAction.STALE, n.onOffer(0, stable = true))
        assertEquals(OfferAction.STALE, n.onOffer(null, stable = true))    // ohne n: keine Neuverhandlung
        assertEquals(OfferAction.ACCEPT, n.onOffer(2, stable = true))
    }

    @Test
    fun antwortNurZumOffenenAngebot() {
        val n = CallV3.Negotiator(polite = true)
        assertFalse(n.onAnswer(0, haveLocalOffer = true))
        val k = n.startOffer()
        assertFalse(n.onAnswer(k, haveLocalOffer = false))
        assertFalse(n.onAnswer(k + 1, haveLocalOffer = true))
        assertFalse(n.onAnswer(null, haveLocalOffer = true))
        assertTrue(n.onAnswer(k, haveLocalOffer = true))
    }

    @Test
    fun neuverhandlungBehaeltZertifikat() {
        assertTrue(CallV3.sameFingerprints(listOf("sha-256 AB:CD"), listOf("sha-256 ab:cd")))
        assertFalse(CallV3.sameFingerprints(listOf("sha-256 AB:CE"), listOf("sha-256 ab:cd")))
        assertFalse(CallV3.sameFingerprints(emptyList(), emptyList()))
    }

    // ---------------------------------------------------------------- ICE-Generationen

    @Test
    fun kandidatenNachGeneration() {
        val sdp = "v=0\r\na=ice-ufrag:alt1\r\nm=audio 9 UDP\r\na=ice-ufrag:alt1\r\n"
        assertEquals(setOf("alt1"), CallV3.sdpUfrags(sdp))
        // usernameFragment hat Vorrang, sonst aus dem Kandidatentext
        val text = "candidate:1 1 udp 2122260223 10.0.0.5 50000 typ host generation 0 ufrag neu2 network-id 2"
        assertEquals("oNrl", CallV3.candidateUfrag(text, "oNrl"))
        assertEquals("neu2", CallV3.candidateUfrag(text, null))
        assertNull(CallV3.candidateUfrag("candidate:1 1 udp 1 10.0.0.5 5 typ host", ""))
        val cur = setOf("neu2")
        val old = setOf("alt1")
        assertEquals(CandAction.APPLY, CallV3.candidateAction("neu2", cur, old))
        assertEquals(CandAction.DROP, CallV3.candidateAction("alt1", cur, old))     // aeltere Generation
        assertEquals(CandAction.WAIT, CallV3.candidateAction("drei", cur, old))     // SDP noch unterwegs
        assertEquals(CandAction.APPLY, CallV3.candidateAction(null, cur, old))      // 2.3 ohne ufrag
        assertEquals(CandAction.APPLY, CallV3.candidateAction("x", emptySet(), old))
    }

    // ---------------------------------------------------------------- Kompatibilitaet mit 2.3

    @Test
    fun ohneRenegKeineNeuverhandlung() {
        assertEquals(emptySet<String>(), CallV3.features(null))
        val mine23 = CallV3.myCaps(emptySet())
        assertEquals(listOf("want", "bye"), mine23)                       // reneg nur mit eigenem 2.4-Server
        val mine24 = CallV3.myCaps(CallV3.features(Json.parseToJsonElement("""["reneg","pip_hint"]""")))
        assertEquals(listOf("reneg", "want", "bye"), mine24)
        val old = obj("""{"t":"answer","sdp":"v=0","fp":["sha-256 AA"]}""")
        assertEquals(emptySet<String>(), CallV3.caps(old))
        assertFalse(CallV3.canReneg(mine24, CallV3.caps(old)))
        assertFalse(CallV3.canReneg(mine23, setOf("reneg")))
        assertTrue(CallV3.canReneg(mine24, CallV3.caps(obj("""{"t":"offer","n":1,"caps":["reneg","want","bye"]}"""))))
        assertFalse(CallV3.restartOnNetwork("100", "101", connected = true, reneg = false))
        assertTrue(CallV3.restartOnNetwork("100", "101", connected = true, reneg = true))
        assertFalse(CallV3.restartOnNetwork(null, "101", connected = true, reneg = true))
        assertFalse(CallV3.restartOnNetwork("100", "100", connected = true, reneg = true))
        assertFalse(CallV3.restartOnNetwork("100", "101", connected = false, reneg = true))
        // Fristen und Versuche laut Vertrag
        assertEquals(3, CallV3.MAX_RESTARTS)
        assertEquals(2_500L, CallV3.DISCONNECTED_WAIT_MS)
        assertEquals(20_000L, CallV3.FAILED_WAIT_MS)
    }

    @Test
    fun unbekannteTypenWerdenIgnoriert() {
        assertEquals(CallV3.Kind.OFFER, CallV3.kind("offer"))
        assertEquals(CallV3.Kind.BYE, CallV3.kind("bye"))
        assertEquals(CallV3.Kind.UNKNOWN, CallV3.kind("fkey"))
        assertEquals(CallV3.Kind.UNKNOWN, CallV3.kind(null))
        assertEquals(CallV3.Kind.UNKNOWN, CallV3.kind("join"))
    }

    // ---------------------------------------------------------------- want small/large, Bild-im-Bild

    @Test
    fun wantSmallLarge() {
        assertEquals("small", CallPip.want(pip = true, visible = true))
        assertEquals("small", CallPip.want(pip = false, visible = false))
        assertEquals("large", CallPip.want(pip = false, visible = true))
        assertEquals("large", CallV3.peerWant(obj("""{"t":"state","mic":true,"cam":true,"screen":false}""")))
        assertEquals("small", CallV3.peerWant(obj("""{"t":"state","pip":true,"want":{"video":"small"},"path":"relay-tcp"}""")))
        assertEquals("off", CallV3.peerWant(obj("""{"want":{"video":"off"}}""")))
        assertEquals("large", CallV3.peerWant(obj("""{"want":{"video":"huge"}}""")))
        assertEquals("large", CallV3.peerWant(obj("""{"want":"small"}""")))
    }

    @Test
    fun pipNurMitVideo() {
        val base = CallUiState(CallPhase.CONNECTED, "a".repeat(32), 7, true, true, CallPeer("marin", "Marin"))
        assertFalse(CallPip.eligible(null))
        assertFalse(CallPip.eligible(base))
        assertTrue(CallPip.eligible(base.copy(cam = true)))
        assertTrue(CallPip.eligible(base.copy(remoteVideo = true)))
        assertFalse(CallPip.eligible(base.copy(remoteVideo = true, phase = CallPhase.INCOMING)))
        assertFalse(CallPip.eligible(base.copy(remoteVideo = true, phase = CallPhase.ENDED)))
    }

    @Test
    fun pipSeitenverhaeltnisBegrenzt() {
        assertEquals(Pair(563, 1000), CallPip.ratio(null))
        assertEquals(Pair(1778, 1000), CallPip.ratio(16f / 9f))
        assertEquals(Pair(2390, 1000), CallPip.ratio(5f))
        assertEquals(Pair(420, 1000), CallPip.ratio(0.1f))
    }

    // ---------------------------------------------------------------- Vordergrund-Dienst

    @Test
    fun dienstTypMitKameraBeiVideo() {
        val v = CallServiceTypes.tries(34, video = true, mic = true, cam = true)
        assertTrue(v.first() and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0)
        assertTrue(v.first() and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA, v[1])
        val a = CallServiceTypes.tries(34, video = false, mic = true, cam = true)
        assertTrue(a.none { it and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0 })
        assertEquals(listOf(0), CallServiceTypes.tries(29, video = true, mic = true, cam = true))
    }
}
