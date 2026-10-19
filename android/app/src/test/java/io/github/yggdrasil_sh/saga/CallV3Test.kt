package io.github.yggdrasil_sh.saga

import android.content.pm.ServiceInfo
import io.github.yggdrasil_sh.saga.call.CallPeer
import io.github.yggdrasil_sh.saga.call.CallPhase
import io.github.yggdrasil_sh.saga.call.CallPip
import io.github.yggdrasil_sh.saga.call.CallServiceTypes
import io.github.yggdrasil_sh.saga.call.CallUiState
import io.github.yggdrasil_sh.saga.call.CallV3
import io.github.yggdrasil_sh.saga.call.CallV3.Negotiator.OfferAction
import io.github.yggdrasil_sh.saga.call.CallV3.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saga 2.4: saga-call v3 (E0) und Bild-im-Bild - Pfad, Bitrate, Glare, Kompatibilitaet mit 2.3, want small/large */
class CallV3Test {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    // ---------------------------------------------------------------- Pfaderkennung

    @Test
    fun pfadAusKandidatenpaar() {
        assertEquals(null, CallV3.path(null, null, null))
        assertEquals(Path.DIRECT, CallV3.path("host", null, "host"))
        assertEquals(Path.DIRECT, CallV3.path("srflx", null, "prflx"))
        assertEquals(Path.RELAY_UDP, CallV3.path("relay", "udp", "host"))
        assertEquals(Path.RELAY_UDP, CallV3.path("relay", null, "srflx"))
        // turns:…:443?transport=tcp -> relayProtocol "tls" bzw. "tcp"
        assertEquals(Path.RELAY_TCP, CallV3.path("relay", "tls", "srflx"))
        assertEquals(Path.RELAY_TCP, CallV3.path("relay", "TCP", "relay"))
        // nur das Gegenueber nutzt ein Relais
        assertEquals(Path.RELAY_UDP, CallV3.path("srflx", null, "relay"))
        // Anzeige bleibt "direct"/"relay" wie in 2.3
        assertEquals("direct", CallV3.pathLabel(Path.DIRECT))
        assertEquals("relay", CallV3.pathLabel(Path.RELAY_TCP))
        assertEquals("", CallV3.pathLabel(null))
    }

    // ---------------------------------------------------------------- Bitrate nach Tabelle §1.3

    @Test
    fun bitrateKameraJePfad() {
        val d = CallV3.videoLimits(Path.DIRECT)
        assertEquals(1_500_000, d.maxBps); assertEquals(1.0, d.scaleDown, 0.0); assertEquals(30, d.maxFps)
        assertEquals(1_200_000, CallV3.videoLimits(Path.RELAY_UDP).maxBps)
        val t = CallV3.videoLimits(Path.RELAY_TCP)
        assertEquals(800_000, t.maxBps)
        assertEquals(540, (720 / t.scaleDown).toInt())                   // 540p
        assertTrue(d.active)
    }

    @Test
    fun bitrateGegenueberImPip() {
        for (p in Path.entries) {
            val s = CallV3.videoLimits(p, want = "small")
            assertTrue("$p", s.maxBps <= 300_000)
            assertTrue("$p", s.scaleDown in 2.0..3.0)
        }
        // groesser wird es durch "small" nie
        assertEquals(300_000, CallV3.videoLimits(Path.DIRECT, "small").maxBps)
        assertEquals(3.0, CallV3.videoLimits(Path.RELAY_TCP, "small").scaleDown, 0.0)
        // "large" bzw. unbekannt -> unveraendert, "off" -> Sender aus
        assertEquals(CallV3.videoLimits(Path.DIRECT), CallV3.videoLimits(Path.DIRECT, "large"))
        assertFalse(CallV3.videoLimits(Path.DIRECT, "off").active)
    }

    @Test
    fun bitrateBildschirmUndMesh() {
        assertEquals(2_500_000, CallV3.videoLimits(Path.DIRECT, screen = true).maxBps)
        assertEquals(15, CallV3.videoLimits(Path.DIRECT, screen = true).maxFps)
        assertEquals(1_800_000, CallV3.videoLimits(Path.RELAY_UDP, screen = true).maxBps)
        val ts = CallV3.videoLimits(Path.RELAY_TCP, screen = true)
        assertEquals(1_200_000, ts.maxBps); assertEquals(10, ts.maxFps)
        assertEquals(CallV3.Degradation.MAINTAIN_RESOLUTION, ts.degradation)
        // Bildschirm im PiP des Gegenuebers unveraendert (Text lesbar)
        assertEquals(ts, CallV3.videoLimits(Path.RELAY_TCP, "small", screen = true))
        assertEquals(600_000, CallV3.videoLimits(Path.DIRECT, peers = 3).maxBps)
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

    // ---------------------------------------------------------------- Perfect Negotiation / Glare

    @Test
    fun hoeflichIstGroessereGeraeteId() {
        assertTrue(CallV3.polite("b", "a"))
        assertFalse(CallV3.polite("a", "b"))
        // genau eine Seite ist hoeflich
        val x = "3f1c"; val y = "a0b2"
        assertTrue(CallV3.polite(x, y) != CallV3.polite(y, x))
    }

    @Test
    fun glareHoeflichGibtNach() {
        val polite = CallV3.Negotiator(polite = true)
        val impolite = CallV3.Negotiator(polite = false)
        // beide starten gleichzeitig einen ICE-Restart (Netzwechsel auf beiden Seiten)
        val np = polite.startOffer()
        val ni = impolite.startOffer()
        assertEquals(1, np); assertEquals(1, ni)
        // unhoefliche Seite ignoriert das kollidierende Angebot
        assertEquals(OfferAction.IGNORE, impolite.onOffer(np, stable = false))
        assertTrue(impolite.makingOffer)
        // hoefliche Seite rollt zurueck und nimmt an
        assertEquals(OfferAction.ROLLBACK_ACCEPT, polite.onOffer(ni, stable = false))
        assertFalse(polite.makingOffer)
        // Antwort der hoeflichen Seite traegt n des angenommenen Angebots -> passt zum offenen Angebot der unhoeflichen
        assertTrue(impolite.onAnswer(polite.remoteN, haveLocalOffer = true))
        impolite.offerDone()
        assertFalse(impolite.makingOffer)
    }

    @Test
    fun angebotOhneKollision() {
        val n = CallV3.Negotiator(polite = false)
        assertEquals(OfferAction.ACCEPT, n.onOffer(1, stable = true))
        // dasselbe/aeltere Angebot noch einmal (Abfrage + Live-Kanal) -> STALE
        assertEquals(OfferAction.STALE, n.onOffer(1, stable = true))
        assertEquals(OfferAction.STALE, n.onOffer(0, stable = true))
        // Angebot ohne n (erstes Angebot aus 2.3/Aufbau) ist keine Neuverhandlung
        assertEquals(OfferAction.STALE, n.onOffer(null, stable = true))
        assertEquals(OfferAction.ACCEPT, n.onOffer(2, stable = true))
    }

    @Test
    fun antwortNurZumOffenenAngebot() {
        val n = CallV3.Negotiator(polite = true)
        assertFalse(n.onAnswer(0, haveLocalOffer = true))              // kein eigenes Neu-Angebot
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

    @Test
    fun kandidatenNeuerGenerationWarten() {
        val sdp = "v=0\r\na=ice-ufrag:alt1\r\nm=audio 9 UDP\r\na=ice-ufrag:alt1\r\n"
        val u = CallV3.sdpUfrags(sdp)
        assertEquals(setOf("alt1"), u)
        val alt = "candidate:1 1 udp 2122260223 192.168.1.5 50000 typ host generation 0 ufrag alt1 network-id 1"
        val neu = "candidate:1 1 udp 2122260223 10.0.0.5 50000 typ host generation 0 ufrag neu2 network-id 2"
        assertFalse(CallV3.candidateWaits(alt, u))
        assertTrue(CallV3.candidateWaits(neu, u))                         // Antwort/Angebot des Restarts fehlt noch
        // ohne ufrag (aeltere Gegenstellen, Web) wie bisher sofort
        assertFalse(CallV3.candidateWaits("candidate:1 1 udp 1 10.0.0.5 5 typ host", u))
        assertFalse(CallV3.candidateWaits(neu, emptySet()))
    }

    // ---------------------------------------------------------------- Kompatibilitaet mit 2.3

    @Test
    fun ohneRenegKeineNeuverhandlung() {
        // Server 2.3: keine features -> kein reneg in den eigenen caps
        assertEquals(emptySet<String>(), CallV3.features(null))
        val mine23 = CallV3.myCaps(emptySet())
        assertFalse(CallV3.CAP_RENEG in mine23)
        val mine24 = CallV3.myCaps(CallV3.features(Json.parseToJsonElement("""["reneg","multi","pip_hint"]""")))
        assertTrue(CallV3.CAP_RENEG in mine24)
        // 2.3-App sendet keine caps
        val old = obj("""{"t":"answer","sdp":"v=0","fp":["sha-256 AA"]}""")
        assertEquals(emptySet<String>(), CallV3.caps(old))
        assertFalse(CallV3.canReneg(mine24, CallV3.caps(old)))
        assertFalse(CallV3.canReneg(mine23, setOf("reneg")))
        assertTrue(CallV3.canReneg(mine24, CallV3.caps(obj("""{"t":"offer","caps":["reneg","pip_hint"]}"""))))
        // Netzwechsel ohne reneg -> kein Restart (Anruf verhaelt sich wie 2.3)
        assertFalse(CallV3.restartOnNetwork("100", "101", connected = true, reneg = false))
        assertTrue(CallV3.restartOnNetwork("100", "101", connected = true, reneg = true))
        assertFalse(CallV3.restartOnNetwork(null, "101", connected = true, reneg = true))  // erste Meldung
        assertFalse(CallV3.restartOnNetwork("100", "100", connected = true, reneg = true))
        assertFalse(CallV3.restartOnNetwork("100", "101", connected = false, reneg = true))
    }

    @Test
    fun unbekannteTypenWerdenIgnoriert() {
        assertEquals(CallV3.Kind.OFFER, CallV3.kind("offer"))
        assertEquals(CallV3.Kind.BYE, CallV3.kind("bye"))
        assertEquals(CallV3.Kind.UNKNOWN, CallV3.kind("fkey"))           // erst 2.5
        assertEquals(CallV3.Kind.UNKNOWN, CallV3.kind(null))
        assertEquals(CallV3.Kind.UNKNOWN, CallV3.kind("join"))
    }

    // ---------------------------------------------------------------- want small/large, Bild-im-Bild

    @Test
    fun wantSmallLarge() {
        assertEquals("small", CallPip.want(pip = true, visible = true))
        assertEquals("small", CallPip.want(pip = false, visible = false))
        assertEquals("large", CallPip.want(pip = false, visible = true))
        // Gegenueber 2.3 sendet kein want -> large; Unsinn -> large
        assertEquals("large", CallV3.peerWant(obj("""{"t":"state","mic":true,"cam":true,"screen":false}""")))
        assertEquals("small", CallV3.peerWant(obj("""{"t":"state","pip":true,"want":{"video":"small"}}""")))
        assertEquals("off", CallV3.peerWant(obj("""{"want":{"video":"off"}}""")))
        assertEquals("large", CallV3.peerWant(obj("""{"want":{"video":"huge"}}""")))
        assertEquals("large", CallV3.peerWant(obj("""{"want":"small"}""")))
    }

    @Test
    fun pipNurMitVideo() {
        val base = CallUiState(CallPhase.CONNECTED, "a".repeat(32), 7, true, true, CallPeer("marin", "Marin"))
        assertFalse(CallPip.eligible(null))
        assertFalse(CallPip.eligible(base))                               // Audio: Zurueck minimiert wie in 2.3.1
        assertTrue(CallPip.eligible(base.copy(cam = true)))
        assertTrue(CallPip.eligible(base.copy(remoteVideo = true)))
        assertFalse(CallPip.eligible(base.copy(remoteVideo = true, phase = CallPhase.INCOMING)))
        assertFalse(CallPip.eligible(base.copy(remoteVideo = true, phase = CallPhase.ENDED)))
    }

    @Test
    fun pipSeitenverhaeltnisBegrenzt() {
        assertEquals(Pair(563, 1000), CallPip.ratio(null))               // unbekannt: Hochformat 9:16
        assertEquals(Pair(1778, 1000), CallPip.ratio(16f / 9f))
        assertEquals(Pair(2390, 1000), CallPip.ratio(5f))                 // Android-Grenze 2,39:1
        assertEquals(Pair(420, 1000), CallPip.ratio(0.1f))
    }

    // ---------------------------------------------------------------- Vordergrund-Dienst

    @Test
    fun dienstTypMitKameraBeiVideo() {
        val v = CallServiceTypes.tries(34, video = true, mic = true, cam = true)
        assertTrue(v.first() and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0)
        assertTrue(v.first() and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
        // ohne phoneCall-Recht: Mikrofon+Kamera als zweiter Versuch
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA, v[1])
        val a = CallServiceTypes.tries(34, video = false, mic = true, cam = true)
        assertTrue(a.none { it and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0 })
        assertEquals(listOf(0), CallServiceTypes.tries(29, video = true, mic = true, cam = true))
    }
}
