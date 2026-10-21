package io.github.yggdrasil_sh.saga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.HornCryptor
import io.github.yggdrasil_sh.saga.call.HornE2E
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataChannel
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyDerivationAlgorithm
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoFrame
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gjallarhorn (Saga 2.5), docs/HORN-E2E.md §8 Punkt 3: FrameCryptor (webrtc-sdk) selbst auf Geraet/Emulator.
 * 1. HKDF-Ableitung des KeyProviders bytegenau gegen die Vektoren (ratchetKey = HKDF(K, Rahmen-Salz, 128 x 00, 32),
 *    die ersten 16 Byte = FK), Gegenprobe PBKDF2.
 * 2. Loopback zweier PeerConnections im Prozess, nur Video (VP8, Software-Kodierer), Testbild aus dem Speicher.
 *    Keine Kamera, kein Mikrofon: es gibt keine Tonspur, das Audio-Geraet nimmt nie auf; keine ICE-Server (nur Host).
 */
@RunWith(AndroidJUnit4::class)
class HornLoopbackTest {
    companion object {
        const val DEV_A = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        lateinit var factory: PeerConnectionFactory
        lateinit var v: JsonObject

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            v = Json.parseToJsonElement(
                InstrumentationRegistry.getInstrumentation().context.assets.open("horn_e2e.json").bufferedReader().readText(),
            ).jsonObject
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
                .createPeerConnectionFactory()
        }

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        fun hexs(d: ByteArray): String = d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        fun keys() = v["keys"]!!.jsonArray.map { it.jsonObject }
        fun key(n: Int): ByteArray = hex(keys().first { it["n"]!!.jsonPrimitive.int == n }["K"]!!.jsonPrimitive.content)
    }

    // ---- 1. KeyProvider-Ableitung ----------------------------------------------------------------------------------

    @Test
    fun frameCryptorHkdfMatchesVectors() {
        for (k in keys()) {
            val kk = hex(k["K"]!!.jsonPrimitive.content)
            val n = k["n"]!!.jsonPrimitive.int
            val p = HornCryptor.keyProvider()
            try {
                assertTrue(p.setKey(DEV_A, n % 16, kk))
                assertArrayEquals("setKey/exportKey n=$n", kk, p.exportKey(DEV_A, n % 16))
                val r = p.ratchetKey(DEV_A, n % 16)
                assertEquals(32, r.size)
                assertEquals("FrameCryptor-HKDF = FK n=$n", k["fk"]!!.jsonPrimitive.content, hexs(r.copyOf(16)))
                assertFalse("FrameCryptor-Ratsche darf nicht die Saga-Ratsche sein", r.contentEquals(HornE2E.ratchet(kk)))
            } finally {
                p.dispose()
            }
        }
        val pb = FrameCryptorFactory.createFrameCryptorKeyProvider(
            false, HornE2E.frameSalt, 0, ByteArray(0), -1, 16, true, FrameCryptorKeyDerivationAlgorithm.PBKDF2,
        )
        try {
            pb.setKey("x", 0, key(0))
            assertNotEquals(keys()[0]["fk"]!!.jsonPrimitive.content, hexs(pb.ratchetKey("x", 0).copyOf(16)))
        } finally {
            pb.dispose()
        }
    }

    // ---- 2. Loopback ------------------------------------------------------------------------------------------------

    data class Outcome(val decoded: Long, val received: Long, val bytesIn: Long, val states: List<FrameCryptor.FrameCryptionState>,
                       val codec: String, val unreadable: Boolean, val stats: String)

    /** Kein Trickle: Beschreibungen erst nach vollstaendigem Sammeln (enthalten dann alle Host-Kandidaten) */
    private class Obs : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {}
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun sdp(op: (SdpObserver) -> Unit): SessionDescription? {
        val l = CountDownLatch(1)
        var out: SessionDescription? = null
        op(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { out = d; l.countDown() }
            override fun onCreateFailure(e: String?) { l.countDown() }
            override fun onSetSuccess() { l.countDown() }
            override fun onSetFailure(e: String?) { l.countDown() }
        })
        assertTrue("SDP-Zeitlimit", l.await(10, TimeUnit.SECONDS))
        return out
    }

    private fun gathered(pc: PeerConnection): SessionDescription {
        repeat(200) {
            if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return pc.localDescription
            Thread.sleep(50)
        }
        throw AssertionError("ICE-Sammeln: Zeitlimit")
    }

    private fun stats(pc: PeerConnection): RTCStatsReport {
        val l = CountDownLatch(1)
        var r: RTCStatsReport? = null
        pc.getStats { r = it; l.countDown() }
        assertTrue(l.await(5, TimeUnit.SECONDS))
        return r!!
    }

    private fun run(seconds: Long = 4, sender: (FrameCryptorKeyProvider) -> Int, receiver: (FrameCryptorKeyProvider) -> Unit): Outcome {
        val f = factory
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val a = f.createPeerConnection(cfg, Obs())!!
        val b = f.createPeerConnection(cfg, Obs())!!
        val src = f.createVideoSource(false)
        val track = f.createVideoTrack("horn-test-video", src)
        val tx = a.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        // nur VP8 (der Knoten handelt nur VP8/Opus aus)
        val vp8 = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs.filter { it.name.equals("VP8", true) }
        tx.setCodecPreferences(vp8)
        // Sender-Cryptor VOR dem ersten Bild und vor der Verhandlung
        val pa = HornCryptor.keyProvider()
        val ca = HornCryptor.sender(f, tx.sender, DEV_A, pa)
        ca.setKeyIndex(sender(pa))
        ca.setEnabled(true)
        // Testbild 320x240, 15 fps, wandernder Balken (nie Kamera)
        val running = AtomicBoolean(true)
        val gen = Thread {
            src.capturerObserver.onCapturerStarted(true)
            var n = 0
            while (running.get()) {
                val w = 320
                val h = 240
                val buf = JavaI420Buffer.allocate(w, h)
                val bar = (n * 8) % w
                val y = buf.dataY
                for (r in 0 until h) for (c in 0 until w) y.put(r * buf.strideY + c, if (kotlin.math.abs(c - bar) < 16) 235.toByte() else (30 + (c + r) % 180).toByte())
                for (i in 0 until buf.strideU * h / 2) buf.dataU.put(i, 90.toByte())
                for (i in 0 until buf.strideV * h / 2) buf.dataV.put(i, 200.toByte())
                val fr = VideoFrame(buf, 0, System.nanoTime())
                src.capturerObserver.onFrameCaptured(fr)
                fr.release()
                n++
                Thread.sleep(66)
            }
            src.capturerObserver.onCapturerStopped()
        }
        gen.start()
        val seen = Collections.synchronizedList(ArrayList<FrameCryptor.FrameCryptionState>())
        var cb: FrameCryptor? = null
        try {
            val offer = sdp { a.createOffer(it, MediaConstraints()) }!!
            sdp { a.setLocalDescription(it, offer) }
            sdp { b.setRemoteDescription(it, gathered(a)) }
            val rx = b.transceivers.first().receiver
            val pb = HornCryptor.keyProvider()
            cb = HornCryptor.receiver(f, rx, DEV_A, pb)
            cb.setObserver { _, s -> seen.add(s) }
            receiver(pb)
            cb.setEnabled(true)
            val answer = sdp { b.createAnswer(it, MediaConstraints()) }!!
            sdp { b.setLocalDescription(it, answer) }
            sdp { a.setRemoteDescription(it, gathered(b)) }
            Thread.sleep(seconds * 1000)
            val rep = stats(b)
            val inb = rep.statsMap.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }
            fun num(k: String) = (inb?.members?.get(k) as? Number)?.toLong() ?: inb?.members?.get(k)?.toString()?.toBigDecimalOrNull()?.toLong() ?: 0L
            val codec = answer.description.lines().firstOrNull { it.startsWith("a=rtpmap:") }?.substringAfter(' ')?.trim().orEmpty()
            return Outcome(num("framesDecoded"), num("framesReceived"), num("bytesReceived"), seen.toList(), codec,
                HornCryptor.unreadable(rep), inb?.toString().orEmpty())
        } finally {
            running.set(false)
            gen.join(2000)
            ca.dispose()
            cb?.dispose()
            a.dispose()
            b.dispose()
            track.dispose()
            src.dispose()
        }
    }

    /** Sender und Empfaenger mit K0 auf Platz 0: Bilder kommen an, Zustand ok */
    @Test
    fun loopbackSameKey() {
        val r = run(sender = { it.setKey(DEV_A, 0, key(0)); 0 }, receiver = { it.setKey(DEV_A, 0, key(0)) })
        assertTrue("Bilder nach Entschluesselung: $r", r.decoded > 30)
        assertTrue("Zustand ok: ${r.states}", r.states.contains(FrameCryptor.FrameCryptionState.OK))
        assertEquals("VP8/90000", r.codec)
        assertFalse(r.unreadable)
    }

    /** Empfaenger ohne Schluessel: nichts dekodiert (kein Klartext-Durchreichen), "need" aus getStats */
    @Test
    fun loopbackNoKey() {
        val r = run(sender = { it.setKey(DEV_A, 0, key(0)); 0 }, receiver = { })
        assertEquals(r.toString(), 0L, r.decoded)
        assertTrue(r.toString(), r.bytesIn > 10_000)
        assertTrue("need aus getStats: $r", r.unreadable)
    }

    /** Falscher Schluessel auf dem richtigen Platz: nichts dekodiert, kein ok (failureTolerance -1: auch kein Fehler-Zustand) */
    @Test
    fun loopbackWrongKey() {
        val r = run(sender = { it.setKey(DEV_A, 0, key(0)); 0 }, receiver = { it.setKey(DEV_A, 0, key(2)) })
        assertEquals(r.toString(), 0L, r.decoded)
        assertTrue(r.toString(), r.bytesIn > 10_000)
        assertFalse("kein ok mit falschem Schluessel: ${r.states}", r.states.contains(FrameCryptor.FrameCryptionState.OK))
        assertTrue(r.unreadable)
    }

    /** Saga-Beitritt: Sender ratscht selbst (K1 auf Platz 1), Empfaenger hat nur K0 und die vorab eingesetzten Stufen */
    @Test
    fun loopbackSagaRatchetAhead() {
        val r = run(sender = {
            it.setKey(DEV_A, 0, key(0))
            it.setKey(DEV_A, 1, HornE2E.ratchet(key(0)))
            1
        }, receiver = { HornCryptor.setSlots(it, DEV_A, 0, key(0)) })
        assertTrue(r.toString(), r.decoded > 3)
        // ein ueberschriebener (abgelaufener) Platz ist wirklich unbrauchbar
        val s = run(sender = { it.setKey(DEV_A, 0, key(0)); 0 }, receiver = {
            it.setKey(DEV_A, 0, key(0))
            HornCryptor.scrub(it, DEV_A, 0)
        })
        assertEquals(s.toString(), 0L, s.decoded)
        assertTrue(s.toString(), s.bytesIn > 10_000)
    }
}
