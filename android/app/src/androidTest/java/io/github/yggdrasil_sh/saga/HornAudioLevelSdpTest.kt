package io.github.yggdrasil_sh.saga

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 2263-7 (Pruefer-Notiz 2263-1): Top-3-Ton braucht `ssrc-audio-level` (RFC 6464) im Angebot der App. Die App setzt die
 * Erweiterung nicht selbst - sie kommt aus dem libwebrtc-Standard. Beleg: Angebot wie HornSession (UNIFIED_PLAN, MAXBUNDLE,
 * Ton-Bein: 1 x sendonly "saga" + 3 x recvonly) -> jede Audio-m-Zeile traegt `a=extmap:<id> urn:ietf:params:rtp-hdrext:ssrc-audio-level`;
 * nach einer Antwort (zweite PeerConnection als Knoten-Ersatz) steht die Erweiterung in den Sender-Parametern.
 * Keine Spur, kein Mikrofon, keine ICE-Server. SDP-Mitschnitt: logcat-Tag HornSdp und getExternalFilesDir()/shots/horn-sdp-*.txt
 */
@RunWith(AndroidJUnit4::class)
class HornAudioLevelSdpTest {
    companion object {
        const val URI = "urn:ietf:params:rtp-hdrext:ssrc-audio-level"
        lateinit var factory: PeerConnectionFactory

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        }
    }

    private val obs = object : PeerConnection.Observer {
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

    private fun cfg() = PeerConnection.RTCConfiguration(emptyList()).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
    }

    private fun save(name: String, text: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "horn-sdp-$name.txt").writeText(text)
        for (l in text.lines()) if (l.startsWith("m=") || l.startsWith("a=extmap:") || l.startsWith("a=sendonly") ||
            l.startsWith("a=recvonly") || l.startsWith("a=mid:")) Log.i("HornSdp", "$name $l")
    }

    /** extmap-IDs der Erweiterung je Audio-m-Zeile */
    private fun audioLevelIds(sdp: String): List<Int?> = sdp.split("\r\nm=").drop(1).filter { it.startsWith("audio") }.map { sec ->
        Regex("""a=extmap:(\d+)(/\w+)? ${Regex.escape(URI)}""").find(sec)?.groupValues?.get(1)?.toInt()
    }

    @Test
    fun tonBeinBietetAudioLevelAn() {
        val a = factory.createPeerConnection(cfg(), obs)!!
        val b = factory.createPeerConnection(cfg(), obs)!!
        try {
            val send = a.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("saga")))
            repeat(3) {
                a.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            }
            val offer = sdp { a.createOffer(it, MediaConstraints()) }!!
            save("angebot", offer.description)
            val ids = audioLevelIds(offer.description)
            assertEquals("Audio-m-Zeilen", 4, ids.size)
            assertTrue("ssrc-audio-level fehlt im Angebot: $ids", ids.all { it != null })
            assertEquals("eine ID fuer alle (BUNDLE)", 1, ids.toSet().size)
            sdp { a.setLocalDescription(it, offer) }
            // Knoten-Ersatz: nimmt an und antwortet
            sdp { b.setRemoteDescription(it, offer) }
            val answer = sdp { b.createAnswer(it, MediaConstraints()) }!!
            save("antwort", answer.description)
            sdp { b.setLocalDescription(it, answer) }
            sdp { a.setRemoteDescription(it, answer) }
            val ext = send.sender.parameters.headerExtensions.map { "${it.id} ${it.uri}" }
            Log.i("HornSdp", "sender headerExtensions $ext")
            assertTrue("nach der Antwort nicht ausgehandelt: $ext", send.sender.parameters.headerExtensions.any { it.uri == URI && it.id == ids[0] })
        } finally {
            a.dispose()
            b.dispose()
        }
    }
}
