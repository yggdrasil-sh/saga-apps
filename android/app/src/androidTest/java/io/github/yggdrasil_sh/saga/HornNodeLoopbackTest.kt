package io.github.yggdrasil_sh.saga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.HornLogic
import io.github.yggdrasil_sh.saga.call.HornSession
import io.github.yggdrasil_sh.saga.call.ProxySink
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.AudioTrack
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.SimulcastVideoEncoderFactory
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Gjallarhorn P12: zwei App-Sitzungen ([HornSession]) im selben Prozess gegen einen LOKALEN Knoten (gjallarhorn node,
 * HORN_DEV=1, selbst signiert) - nur mit Argument `hornNode` (z. B. https://10.0.2.2:18443), sonst uebersprungen.
 * Synthetisches Testbild, keine Kamera, kein Mikrofon (keine Tonspur). Geprueft: Kandidat, ICE-TCP-Beine (Ton- und
 * Bild-Leitung getrennt), Ereigniskanal, fkey ueber den (hier direkten) Signalweg, FrameCryptor: B sieht A's Bild
 * entschluesselt und umgekehrt; danach Wechsel make-before-break (Knoten "leert sich") ohne Ende des Bildes.
 */
@RunWith(AndroidJUnit4::class)
class HornNodeLoopbackTest {
    private val devA = "a".repeat(32)
    private val devB = "b".repeat(32)

    private class Count : VideoSink {
        val n = AtomicLong()
        override fun onFrame(frame: VideoFrame) {
            n.incrementAndGet()
        }
    }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun client(): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    private fun post(c: OkHttpClient, url: String, body: String): String =
        c.newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { it.body.string() }

    private fun main(f: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(f)

    @Test
    fun twoSessionsThroughLocalNode() {
        val base = InstrumentationRegistry.getArguments().getString("hornNode")
        assumeTrue("ohne lokalen Knoten (Argument hornNode) uebersprungen", base != null)
        val http = client()
        HornSession.testClient = http
        HornSession.testWaitScale = 5                        // Emulator ohne Hardware: ICE-TCP + DTLS brauchen laenger
        val room = "p12" + System.currentTimeMillis() % 100000
        post(http, "$base/api/dev/room", """{"room":"$room"}""")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_INFO)
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SimulcastVideoEncoderFactory(SoftwareVideoEncoderFactory(), SoftwareVideoEncoderFactory()))
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val legs = listOf(CallLeg(who = "anna", name = "Anna", device = devA, hp = "pa"), CallLeg(who = "bo", name = "Bo", device = devB, hp = "pb"))
        val sessions = HashMap<String, HornSession>()
        val sinks = HashMap<String, Count>()
        val ended = AtomicBoolean(false)
        val running = AtomicBoolean(true)
        val tracks = ArrayList<VideoTrack>()

        fun mkTrack(seed: Int): VideoTrack {
            val src = f.createVideoSource(false)
            val t = f.createVideoTrack("horn-test-$seed", src)
            Thread {
                src.capturerObserver.onCapturerStarted(true)
                var n = 0
                while (running.get()) {
                    val buf = JavaI420Buffer.allocate(320, 240)
                    val bar = (n * 8 + seed * 40) % 320
                    for (r in 0 until 240) for (c in 0 until 320) buf.dataY.put(r * buf.strideY + c, if (kotlin.math.abs(c - bar) < 16) 235.toByte() else (30 + (c + r) % 180).toByte())
                    for (i in 0 until buf.strideU * 120) buf.dataU.put(i, 90.toByte())
                    for (i in 0 until buf.strideV * 120) buf.dataV.put(i, 200.toByte())
                    val fr = VideoFrame(buf, 0, System.nanoTime())
                    src.capturerObserver.onFrameCaptured(fr)
                    fr.release()
                    n++
                    Thread.sleep(66)
                }
                src.capturerObserver.onCapturerStopped()
            }.start()
            tracks.add(t)
            return t
        }

        val cams = HashMap<String, VideoTrack?>()
        fun host(me: String, peer: String): HornSession.Host = object : HornSession.Host {
            override val scope: CoroutineScope get() = scope
            override val factory: PeerConnectionFactory get() = f
            override fun myDev() = me
            override fun mobile() = true
            override fun legs() = legs
            override fun audioTrack(): AudioTrack? = null                    // nie das Mikrofon
            override fun camTrack(): VideoTrack? = cams[me]
            override fun screenTrack(): VideoTrack? = null
            override fun myState() = Triple(false, true, false)
            override suspend fun cands(node: String?, avoid: List<String>): List<HornLogic.Cand> = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val toks = listOf("audio", "video").associateWith { role ->
                    Json.parseToJsonElement(post(http, "$base/api/dev/token", """{"room":"$room","peer":"$peer","role":"$role","device":"mobile"}"""))
                        .jsonObject["token"]!!.jsonPrimitive.content
                }
                listOf(HornLogic.Cand("local", base!!, udp = false, here = true, peer = peer, exp = System.currentTimeMillis() / 1000 + 60, tokens = toks))
            }
            override suspend fun sendSignals(bodies: Map<String, String>) {
                for ((to, b) in bodies) sessions[to]?.signal(me, Json.parseToJsonElement(b) as JsonObject)
            }
            override suspend fun signPk(dev: String): ByteArray = ByteArray(32) { dev[0].code.toByte() }
            override fun changed() {}
            override fun note(text: UiText?) {}
            override fun lost(text: UiText) { ended.set(true) }
            override fun ended() { ended.set(true) }
            override fun removedHint() {}
            override fun refreshRoster() {}
            private val sinkCache = HashMap<String, ProxySink>()
            override fun tileSink(dev: String): ProxySink =
                sinkCache.getOrPut(dev) { ProxySink().also { p -> p.target = sinks.getOrPut("$me<-$dev") { Count() } } }
            override val bigSink: ProxySink = ProxySink()
        }

        try {
            main {
                // A mit Kamera von Anfang an, B schaltet sie erst spaeter ein (Sender-Cryptor ueber die Platzhalter-Spur)
                cams[devA] = mkTrack(0)
                cams[devB] = null
                for ((me, peer) in listOf(devA to "pa", devB to "pb")) {
                    sessions[me] = HornSession(host(me, peer), "0123456789abcdef0123456789abcdef", null)
                }
                sessions.values.forEach { it.start() }
            }
            Thread.sleep(4000)
            main {
                cams[devB] = mkTrack(1)
                sessions[devB]!!.camChanged(cams[devB])
            }
            // Verbindung + erste entschluesselte Bilder in beide Richtungen
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 60_000) {
                if ((sinks["$devB<-$devA"]?.n?.get() ?: 0) > 20 && (sinks["$devA<-$devB"]?.n?.get() ?: 0) > 20) break
                Thread.sleep(250)
            }
            var pathA = ""
            main { pathA = sessions[devA]!!.path }
            assertEquals("Weg ohne UDP", "tcp", pathA)
            assertTrue("B sieht A (${sinks["$devB<-$devA"]?.n})", (sinks["$devB<-$devA"]?.n?.get() ?: 0) > 20)
            assertTrue("A sieht B (${sinks["$devA<-$devB"]?.n})", (sinks["$devA<-$devB"]?.n?.get() ?: 0) > 20)
            var runes = ""
            main { runes = sessions[devA]!!.runes?.runes.orEmpty() }
            assertEquals(6, runes.codePointCount(0, runes.length))

            // Wechsel make-before-break (A: Knoten leert sich -> neue Beine, alte schliessen nach 2 s)
            main { sessions[devA]!!.switch("draining") }
            Thread.sleep(6000)
            val before = sinks["$devB<-$devA"]!!.n.get()
            Thread.sleep(3000)
            val after = sinks["$devB<-$devA"]!!.n.get()
            var conns = 0
            main { conns = sessions[devA]!!.conns().size }
            assertEquals("altes Bein geschlossen", 1, conns)
            assertTrue("Bild laeuft nach dem Wechsel weiter (${after - before} in 3 s)", after - before > 15)
            assertTrue(!ended.get())
        } finally {
            running.set(false)
            main { sessions.values.forEach { it.close() } }
            scope.cancel()
            HornSession.testClient = null
            HornSession.testWaitScale = 1
            Thread.sleep(300)
            tracks.forEach { it.dispose() }
        }
    }
}
