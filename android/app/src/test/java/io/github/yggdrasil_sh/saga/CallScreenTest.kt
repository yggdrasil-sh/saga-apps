package io.github.yggdrasil_sh.saga

import android.content.pm.ServiceInfo
import io.github.yggdrasil_sh.saga.call.CallScreen
import io.github.yggdrasil_sh.saga.call.CallScreen.Mode
import io.github.yggdrasil_sh.saga.call.CallServiceTypes
import io.github.yggdrasil_sh.saga.call.CallV3
import io.github.yggdrasil_sh.saga.call.CallV3.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saga 2.4 E3 "Bildschirm v2" (Vertrag Server docs/APP-API.md): Betriebsart A/B, Zuordnung ueber mid/msid,
 * Wiederverwendung der Bildschirm-m-Zeile, Bitrate, Systemton-Mischung, Vordergrund-Dienst mediaProjection
 */
class CallScreenTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    // ---------------------------------------------------------------- Betriebsart

    @Test
    fun screen2NurMitRenegUndServerFaehigkeit() {
        assertEquals(listOf("reneg", "want", "bye", "screen2"), CallV3.myCaps(setOf("reneg", "pip_hint", "screen2")))
        // ohne reneg kein screen2 (B braucht die Neuverhandlung)
        assertEquals(listOf("want", "bye"), CallV3.myCaps(setOf("screen2")))
        // Server ohne screen2 (E0) bzw. 2.3 ohne features
        assertEquals(listOf("reneg", "want", "bye"), CallV3.myCaps(setOf("reneg", "pip_hint")))
        assertEquals(listOf("want", "bye"), CallV3.myCaps(emptySet()))
        // Client, der B nicht kann, meldet screen2 nicht
        assertEquals(listOf("reneg", "want", "bye"), CallV3.myCaps(setOf("reneg", "screen2"), screen2 = false))
    }

    @Test
    fun betriebsartBNurWennBeideRenegUndScreen2() {
        val mine = listOf("reneg", "want", "bye", "screen2")
        assertEquals(Mode.B, CallScreen.mode(mine, setOf("reneg", "want", "bye", "screen2"), established = true))
        // noch nicht verbunden -> A
        assertEquals(Mode.A, CallScreen.mode(mine, setOf("reneg", "screen2"), established = false))
        // E0-Gegenstelle (reneg ohne screen2) -> A
        assertEquals(Mode.A, CallScreen.mode(mine, setOf("reneg", "want", "bye"), established = true))
        // 2.1/2.3 ohne caps -> A
        assertEquals(Mode.A, CallScreen.mode(mine, emptySet(), established = true))
        // Gegenueber meldet screen2, aber kein reneg -> A
        assertEquals(Mode.A, CallScreen.mode(mine, setOf("screen2"), established = true))
        // ich ohne screen2 (eigener Server ohne Faehigkeit) -> A
        assertEquals(Mode.A, CallScreen.mode(listOf("reneg", "want", "bye"), setOf("reneg", "screen2"), established = true))
        assertTrue(CallScreen.can(mine, setOf("reneg", "screen2")))
        assertFalse(CallScreen.can(mine, setOf("reneg")))
    }

    // ---------------------------------------------------------------- tracks / msid

    @Test
    fun tracksUndMsidAusDemBody() {
        val b = obj(
            """{"t":"offer","tracks":{"0":"mic","1":"cam","2":"screen","x y":"cam","3":"video","4":5},
               "msid":{"0":"9b3c97c5","1":"9b3c97c5","2":"ec27dfd5","bad mid":"z"}}""",
        )
        assertEquals(mapOf("0" to "mic", "1" to "cam", "2" to "screen"), CallScreen.tracksOf(b["tracks"]))
        assertEquals(mapOf("0" to "9b3c97c5", "1" to "9b3c97c5", "2" to "ec27dfd5"), CallScreen.msidOf(b["msid"]))
        // fehlt bzw. falscher Typ -> leer (aeltere Gegenstelle)
        assertEquals(emptyMap<String, String>(), CallScreen.tracksOf(null))
        assertEquals(emptyMap<String, String>(), CallScreen.tracksOf(obj("""{"a":[1]}""")["a"]))
        // hoechstens 8 Eintraege
        val many = obj("{" + (0 until 12).joinToString(",") { "\"$it\":\"cam\"" } + "}")
        assertEquals(8, CallScreen.tracksOf(many).size)
    }

    @Test
    fun screenMidNurBeiScreenUndGueltig() {
        val b = obj("""{"screen":true,"screen_mid":"2","bad":"a b","long":"12345678901234567"}""")
        assertEquals("2", CallScreen.screenMid(true, b["screen_mid"]))
        assertNull(CallScreen.screenMid(false, b["screen_mid"]))
        assertNull(CallScreen.screenMid(true, b["bad"]))
        assertNull(CallScreen.screenMid(true, b["long"]))
        assertNull(CallScreen.screenMid(true, null))
    }

    @Test
    fun msidAusDerSdp() {
        val sdp = "v=0\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=sendrecv\r\na=msid:saga 7f1a\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:1\r\na=sendrecv\r\na=msid:saga 9c2b\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:2\r\na=sendrecv\r\na=msid:saga-screen saga-s\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:3\r\na=recvonly\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:4\r\na=sendrecv\r\na=msid:- 1234\r\n"
        assertEquals(mapOf("0" to "saga", "1" to "saga", "2" to "saga-screen"), CallScreen.sdpMsids(sdp))
        assertEquals(emptyMap<String, String>(), CallScreen.sdpMsids(null))
    }

    @Test
    fun eigeneRollen() {
        assertEquals(mapOf("0" to "mic", "1" to "cam", "2" to "screen"), CallScreen.myTracks("0", "1", "2"))
        assertEquals(mapOf("0" to "mic", "1" to "cam"), CallScreen.myTracks("0", "1", null))
        // vor setLocalDescription noch keine mid
        assertEquals(emptyMap<String, String>(), CallScreen.myTracks(null, null, null))
        // Bildschirm nie auf der Kamera-mid
        assertEquals(mapOf("1" to "cam"), CallScreen.myTracks(null, "1", "1"))
    }

    // ---------------------------------------------------------------- Empfaenger: Zuordnung ueber die mid

    private val all = listOf("1", "2")
    private val roles = mapOf("0" to "mic", "1" to "cam", "2" to "screen")

    @Test
    fun betriebsartBBildschirmGrossKameraKlein() {
        val v = CallScreen.view(roles, all, setOf("1", "2"), screen = true, screenMid = "2", cam = true, swap = false, pip = false)
        assertEquals("2", v.big)
        assertEquals("1", v.small)
        assertTrue(v.bigScreen)
        assertEquals("2", v.screenMid)
        assertEquals("1", v.camMid)
        // Tauschen
        val s = CallScreen.view(roles, all, setOf("1", "2"), screen = true, screenMid = "2", cam = true, swap = true, pip = false)
        assertEquals("1", s.big)
        assertEquals("2", s.small)
        assertFalse(s.bigScreen)
        // Bild-im-Bild zeigt immer den Bildschirm
        val p = CallScreen.view(roles, all, setOf("1", "2"), screen = true, screenMid = "2", cam = true, swap = true, pip = true)
        assertEquals("2", p.big)
        assertTrue(p.bigScreen)
    }

    @Test
    fun zuordnungUeberMidNichtReihenfolge() {
        // Gegenueber hat die Bildschirm-m-Zeile als erste Video-m-Zeile (z. B. wiederverwendet, Kamera auf mid 3)
        val r = mapOf("0" to "mic", "3" to "cam", "1" to "screen")
        val v = CallScreen.view(r, listOf("1", "3"), setOf("1", "3"), screen = true, screenMid = "1", cam = true, swap = false, pip = false)
        assertEquals("1", v.big)
        assertEquals("3", v.small)
        // ohne Teilen: die "cam"-mid gross, nicht die erste
        val n = CallScreen.view(r, listOf("1", "3"), setOf("3"), screen = false, screenMid = null, cam = true, swap = false, pip = false)
        assertEquals("3", n.big)
        assertNull(n.small)
    }

    @Test
    fun kameraAusNurBildschirm() {
        val v = CallScreen.view(roles, all, setOf("1", "2"), screen = true, screenMid = "2", cam = false, swap = false, pip = false)
        assertEquals("2", v.big)
        assertNull(v.small)
    }

    @Test
    fun spurOhneBilderNichtGross() {
        // kurz nach dem Angebot: Bildschirm-Spur ohne Bilder -> Kamera bleibt gross
        val v = CallScreen.view(roles, all, setOf("1"), screen = true, screenMid = "2", cam = true, swap = false, pip = false)
        assertEquals("1", v.big)
        assertNull(v.small)
        assertFalse(v.bigScreen)
        // sobald Bilder kommen: Bildschirm gross
        val w = CallScreen.view(roles, all, setOf("1", "2"), screen = true, screenMid = "2", cam = true, swap = false, pip = false)
        assertEquals("2", w.big)
    }

    @Test
    fun betriebsartAMitScreenMidDerKamera() {
        // 2.4-Gegenstelle in A: screen_mid = Kamera-mid -> die einzige Videospur ist der Bildschirm
        val v = CallScreen.view(mapOf("0" to "mic", "1" to "cam"), listOf("1"), setOf("1"), screen = true, screenMid = "1", cam = false, swap = false, pip = false)
        assertEquals("1", v.big)
        assertNull(v.small)
        assertTrue(v.bigScreen)
        assertNull(v.camMid)
    }

    @Test
    fun aeltereGegenstelleOhneTracksUndScreenMid() {
        // 2.1/2.3/E0: screen ohne screen_mid -> die einzige Videospur (erste Video-m-Zeile) ist der Bildschirm
        val v = CallScreen.view(emptyMap(), listOf("1"), setOf("1"), screen = true, screenMid = null, cam = false, swap = false, pip = false)
        assertEquals("1", v.big)
        assertTrue(v.bigScreen)
        // ohne Teilen: erste Video-m-Zeile = Kamera
        val c = CallScreen.view(emptyMap(), listOf("1", "5"), emptySet(), screen = false, screenMid = null, cam = true, swap = false, pip = false)
        assertEquals("1", c.big)
        assertFalse(c.bigScreen)
    }

    // ---------------------------------------------------------------- Wiederverwendung (keine m-Zeilen-Flut)

    @Test
    fun bildschirmMZeileWiederverwenden() {
        // eigene aus einem frueheren Teilen (jetzt recvonly/inactive)
        assertEquals("2", CallScreen.reuseMid("2", "1", listOf("1", "2"), emptyMap()))
        // vom Gegenueber angelegte (seine tracks nennen sie "screen")
        assertEquals("2", CallScreen.reuseMid(null, "1", listOf("1", "2"), mapOf("1" to "cam", "2" to "screen")))
        // keine vorhanden -> neuen Transceiver anlegen
        assertNull(CallScreen.reuseMid(null, "1", listOf("1"), mapOf("1" to "cam")))
        // nie die Kamera-m-Zeile
        assertNull(CallScreen.reuseMid("1", "1", listOf("1"), mapOf("1" to "screen")))
        // eigene mid nicht mehr vorhanden -> die des Gegenuebers
        assertEquals("3", CallScreen.reuseMid("9", "1", listOf("1", "3"), mapOf("3" to "screen")))
    }

    // ---------------------------------------------------------------- Bitrate

    @Test
    fun bildschirmBitrateJeWeg() {
        val d = CallV3.videoLimits(Path.DIRECT, screen = true)
        assertEquals(2_500_000, d.maxBps)
        assertEquals(15, d.maxFps)
        assertEquals(1.0, d.scaleDown, 0.0)
        assertEquals(CallV3.Degradation.MAINTAIN_RESOLUTION, d.degradation)
        assertEquals(1_800_000, CallV3.videoLimits(Path.RELAY_UDP, screen = true).maxBps)
        val t = CallV3.videoLimits(Path.RELAY_TCP, screen = true)
        assertEquals(1_200_000, t.maxBps)
        assertEquals(8, t.maxFps)
        assertEquals(CallV3.Degradation.MAINTAIN_RESOLUTION, t.degradation)
        // want: small aendert den Bildschirm nicht
        assertEquals(d, CallV3.videoLimits(Path.DIRECT, want = "small", screen = true))
    }

    @Test
    fun kameraNebenDemBildschirmGedrosselt() {
        val d = CallV3.videoLimits(Path.DIRECT, shareCam = true)
        assertEquals(300_000, d.maxBps)
        assertEquals(2.0, d.scaleDown, 0.001)        // 720p -> 360p
        assertTrue(d.active)
        val u = CallV3.videoLimits(Path.RELAY_UDP, shareCam = true)
        assertEquals(300_000, u.maxBps)
        assertTrue(u.active)
        // Relais TCP: Kamera aus (Summe je Richtung <= 1,6 Mbit/s)
        val t = CallV3.videoLimits(Path.RELAY_TCP, shareCam = true)
        assertFalse(t.active)
        assertTrue(t.maxBps + CallV3.videoLimits(Path.RELAY_TCP, screen = true).maxBps <= 1_600_000)
        // want small bleibt hoechstens 300 kbit/s, Verkleinerung mindestens wie bei small
        val s = CallV3.videoLimits(Path.DIRECT, want = "small", shareCam = true)
        assertEquals(300_000, s.maxBps)
        assertEquals(3.0, s.scaleDown, 0.001)
        // ohne Teilen: normale Kamera-Regeln
        assertEquals(1_500_000, CallV3.videoLimits(Path.DIRECT).maxBps)
        assertTrue(CallScreen.camPaused(Mode.B, Path.RELAY_TCP, cam = true))
        assertFalse(CallScreen.camPaused(Mode.A, Path.RELAY_TCP, cam = true))
        assertFalse(CallScreen.camPaused(Mode.B, Path.RELAY_UDP, cam = true))
        assertFalse(CallScreen.camPaused(Mode.B, Path.RELAY_TCP, cam = false))
        assertFalse(CallScreen.camPaused(null, Path.RELAY_TCP, cam = true))
    }

    @Test
    fun aufnahmeGroesseUndBildrate() {
        assertEquals(Pair(1080, 1920), CallScreen.captureSize(1440, 2560))
        assertEquals(Pair(1920, 886), CallScreen.captureSize(3120, 1440))
        assertEquals(Pair(720, 1280), CallScreen.captureSize(720, 1280))
        assertEquals(Pair(864, 1920), CallScreen.captureSize(1080, 2400))
        assertEquals(Pair(1280, 720), CallScreen.captureSize(0, 0))
        val (w, h) = CallScreen.captureSize(1081, 2341)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        assertEquals(15, CallScreen.captureFps(Path.DIRECT))
        assertEquals(15, CallScreen.captureFps(Path.RELAY_UDP))
        assertEquals(15, CallScreen.captureFps(null))
        assertEquals(8, CallScreen.captureFps(Path.RELAY_TCP))
        assertEquals(5, CallScreen.captureFps(Path.DIRECT, hot = true))
    }

    // ---------------------------------------------------------------- Systemton

    @Test
    fun systemtonInsMikrofonGemischt() {
        val mic = shortArrayOf(100, -200, 30000, -30000, 5)
        CallScreen.mixPcm16(mic, 5, shortArrayOf(1, 2, 10000, -10000), 4, micMuted = false)
        assertArrayEquals(shortArrayOf(101, -198, Short.MAX_VALUE, Short.MIN_VALUE, 5), mic)
        // stumm: nur das Mikrofon schweigt, der Systemton laeuft weiter
        val m2 = shortArrayOf(100, 100, 100)
        CallScreen.mixPcm16(m2, 3, shortArrayOf(7, 8), 2, micMuted = true)
        assertArrayEquals(shortArrayOf(7, 8, 0), m2)
        // kein Systemton verfuegbar
        val m3 = shortArrayOf(1, 2)
        CallScreen.mixPcm16(m3, 2, ShortArray(2), 0, micMuted = false)
        assertArrayEquals(shortArrayOf(1, 2), m3)
    }

    // ---------------------------------------------------------------- Vordergrund-Dienst

    @Test
    fun dienstTypMediaProjection() {
        val mp = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val t = CallServiceTypes.tries(34, video = true, mic = true, cam = true, screen = true)
        assertTrue(t.isNotEmpty())
        assertTrue(t.all { (it and mp) != 0 })
        assertEquals(mp, t.last())
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or mp,
            t.first(),
        )
        // ohne Teilen unveraendert (kein mediaProjection vor der Einwilligung)
        assertTrue(CallServiceTypes.tries(34, video = true, mic = true, cam = true).none { (it and mp) != 0 })
        assertEquals(listOf(mp), CallServiceTypes.tries(29, video = false, mic = true, cam = false, screen = true))
        assertEquals(listOf(0), CallServiceTypes.tries(28, video = false, mic = true, cam = false, screen = true))
        assertTrue(CallServiceTypes.screenOk(34, mp or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE))
        assertFalse(CallServiceTypes.screenOk(34, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE))
        assertFalse(CallServiceTypes.screenOk(34, null))
        assertTrue(CallServiceTypes.screenOk(28, 0))
    }
}
