package io.github.yggdrasil_sh.saga.call

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Saga 2.5 A3 "ICE-TCP im WebSocket-Tunnel" (Vertrag vertrag-a3.md, Konzept ws-apps): Protokoll-Stellen des Tunnels -
 * nur hier, damit eine Anpassung an den Knoten klein bleibt. Ohne Android/WebRTC (HornTunnelTest, dieselben Faelle wie
 * ios/Tests/Logic/HornTunnelTests.swift).
 */
object HornWire {
    /** Endpunkt an der Saga-Adresse (saga-media reicht ihn roh an den eingebauten Knoten) */
    const val PATH = "/api/sfu/ws"
    /** Unterprotokoll der Apps (saga-frames.1 ist fuer den Browser-Weg B2 reserviert) */
    const val PROTO = "saga-tunnel.1"
    /** Frist fuer Aufbau + Zugangs-Antwort (der Knoten schliesst selbst nach 10 s ohne erste Nachricht) */
    const val READY_MS = 10_000L
    const val K_DATA: Byte = 0
    const val K_ACK: Byte = 1
    /** Quittieren (Vertrag S3): sofort ab ACK_BYTES unquittierten Bytes, sonst spaetestens ACK_MS nach dem ersten
     * unquittierten Byte; Werte aus der ok-Antwort, fehlen sie: 16384 / 15 */
    const val ACK_BYTES = 16384L
    const val ACK_MS = 15L

    /** wss://<Host der Kandidaten-URL>/api/sfu/ws (http nur fuer Tests: ws); null = keine http(s)-Adresse */
    fun url(base: String): String? {
        val m = Regex("^(https?)://([^/?#]+)").find(base) ?: return null
        return (if (m.groupValues[1] == "http") "ws://" else "wss://") + m.groupValues[2] + PATH
    }

    /**
     * Erste Textnachricht (Vertrag S2): Zugang nur hier (nie in URL, Query oder Kopf). user/cred = POST /api/sfu/turn mit
     * dem Ton-Token (ein Satz fuer beide WS), mode "ice" (A3), role nur fuer die Diagnose am Knoten
     */
    fun hello(user: String, pass: String, role: String? = null): String = buildJsonObject {
        put("t", "auth")
        put("v", 1)
        put("mode", "ice")
        put("user", user)
        put("cred", pass)
        if (role == "audio" || role == "video") put("role", role)
    }.toString()

    /** Antwort: {"t":"ok","v":1,"ack_bytes":…,"ack_ms":…} bzw. {"t":"err","v":1,"code":"…"} */
    sealed class Reply {
        data class Ok(val ackBytes: Long = ACK_BYTES, val ackMs: Long = ACK_MS) : Reply()
        data class Denied(val code: String) : Reply()
        object Bad : Reply()
    }

    fun reply(text: String): Reply = try {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        fun str(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun num(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()
        when (str("t")) {
            "ok" -> Reply.Ok(num("ack_bytes")?.takeIf { it in 1..(1L shl 20) } ?: ACK_BYTES, num("ack_ms")?.takeIf { it in 1..1000 } ?: ACK_MS)
            "err" -> Reply.Denied(str("code")?.take(40)?.ifEmpty { null } ?: "err")
            else -> Reply.Bad
        }
    } catch (_: Exception) {
        Reply.Bad
    }

    fun data(b: ByteArray, n: Int = b.size): ByteArray = ByteArray(n + 1).also { it[0] = K_DATA; System.arraycopy(b, 0, it, 1, n) }

    /** Quittung = Summe der empfangenen Nutzbytes (8 Byte big endian) */
    fun ack(total: Long): ByteArray = ByteArray(9).also { m -> m[0] = K_ACK; for (i in 0 until 8) m[1 + i] = (total shr (56 - 8 * i)).toByte() }

    sealed class Frame {
        class Data(val b: ByteArray) : Frame()
        data class Ack(val total: Long) : Frame()
        object Unknown : Frame()
    }

    fun frame(m: ByteArray): Frame = when {
        m.isEmpty() -> Frame.Unknown
        m[0] == K_DATA && m.size > 1 -> Frame.Data(m.copyOfRange(1, m.size))
        m[0] == K_ACK && m.size >= 9 -> Frame.Ack((1..8).fold(0L) { a, i -> (a shl 8) or (m[i].toLong() and 0xff) })
        else -> Frame.Unknown
    }

    /** Weg im Body von POST /api/sfu/leg (Vertrag S4): "ws"; aeltere Knoten antworten 400 (naechster Weg) */
    fun legPath(p: String): String = p

    /** Fehler in Worten (calldiag/clientlog/Protokoll); 426 = Proxy ohne "Websockets Support" */
    fun failText(status: Int, code: String? = null): String = when {
        status == 426 -> "ws 426 Proxy reicht WebSocket nicht durch"
        code != null -> "ws abgelehnt $code"
        status > 0 -> "ws HTTP $status"
        else -> "ws nicht erreichbar"
    }

    /**
     * A3: passiven ICE-TCP-Kandidaten (host) des Knotens im Antwort-SDP auf 127.0.0.1:<port> biegen; nur der erste bleibt
     * (weitere, z. B. IPv6, fallen weg: sonst mehrere lokale Verbindungen je Leitung). Andere Zeilen unveraendert.
     */
    fun bend(sdp: String, port: Int): String {
        var done = false
        val out = ArrayList<String>()
        for (l in sdp.split("\r\n")) {
            val f = l.split(" ").toMutableList()
            val i = f.indexOf("tcptype")
            val hit = l.startsWith("a=candidate:") && f.size >= 8 && f[2].equals("tcp", true) && f[6] == "typ" && f[7] == "host" &&
                i > 0 && i + 1 < f.size && f[i + 1] == "passive"
            if (!hit) { out.add(l); continue }
            if (done) continue
            f[4] = "127.0.0.1"; f[5] = port.toString()
            out.add(f.joinToString(" ")); done = true
        }
        return out.joinToString("\r\n")
    }

    fun bendable(sdp: String): Boolean = bend(sdp, 9).contains(" 127.0.0.1 9 typ host")
}

/**
 * Tunnel einer Leitung (Ton bzw. Bild): [connect] baut den WebSocket `wss://<Saga>/api/sfu/ws` (saga-tunnel.1) VOR dem
 * Setzen der Antwort auf und sendet den Zugang in der ersten Textnachricht; [listen] oeffnet erst unmittelbar vor
 * setRemoteDescription einen Lauscher auf 127.0.0.1 (Port vom System), nimmt genau eine Verbindung an (libwebrtc,
 * ICE-TCP aktiv auf den gebogenen Kandidaten) und schliesst ihn dann. Die App quittiert, was sie empfaengt (der Knoten
 * fuehrt damit sein Fenster fuer den Downlink), sendet selbst ohne Fenster - nur die OkHttp-Warteschlange bleibt
 * begrenzt (256 KiB). Der vorhandene Foreground-Service (phoneCall/microphone/camera) haelt den Prozess.
 * Die Tunnel-Adresse erzeugt die App selbst: Sie geht NICHT durch [IceFilter].
 */
class HornTunnel(base: String, user: String, pass: String, private val http: OkHttpClient, role: String? = null) : WebSocketListener() {
    /** Aufbau gescheitert; status 426 = Proxy reicht WebSocket nicht durch */
    class Fail(val status: Int, val code: String? = null) : IOException(HornWire.failText(status, code))

    private val url = HornWire.url(base) ?: throw Fail(0, "url")
    private val hello = HornWire.hello(user, pass, role)
    @Volatile private var ackBytes = HornWire.ACK_BYTES
    @Volatile private var ackMs = HornWire.ACK_MS
    private val lock = ReentrantLock()
    private val room = lock.newCondition()
    @Volatile private var dead = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var sock: Socket? = null
    private var server: ServerSocket? = null
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var readyAt = 0L
    private var t0 = 0L
    private var sent = 0L
    private var rcvd = 0L
    private var ackedOut = 0L
    @Volatile private var ackDue = 0L
    private val early = ArrayList<ByteArray>()
    val accepted = AtomicInteger()
    val refused = AtomicInteger()
    private var acksOut = 0
    private var acksIn = 0
    private var held = 0
    @Volatile var closedWhy = ""
        private set
    /** nach dem Aufbau zu (WS weg oder lokale Verbindung zu); auf einem Hintergrund-Thread */
    @Volatile var onClose: ((String) -> Unit)? = null
    var port = 0
        private set

    /** WS aufbauen und Zugang pruefen lassen; wirft [Fail] (426 = Proxy ohne WebSocket) */
    suspend fun connect() {
        t0 = System.nanoTime()
        val r = Request.Builder().url(url).header("Sec-WebSocket-Protocol", HornWire.PROTO).build()
        ws = http.newWebSocket(r, this)
        val ok = withTimeoutOrNull(HornWire.READY_MS) { ready.await(); true }
        if (ok == null) {
            fail("frist")
            throw Fail(0, "frist")
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(hello)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (ready.isCompleted) return                               // nach dem Zugang keine Textnachrichten vorgesehen
        when (val x = HornWire.reply(text)) {
            is HornWire.Reply.Ok -> { ackBytes = x.ackBytes; ackMs = x.ackMs; readyAt = System.nanoTime(); ready.complete(Unit) }
            is HornWire.Reply.Denied -> { ready.completeExceptionally(Fail(0, x.code)); fail("abgelehnt") }
            else -> { ready.completeExceptionally(Fail(0, "antwort")); fail("antwort") }
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val s = response?.code ?: 0
        if (!ready.isCompleted) ready.completeExceptionally(Fail(if (s == 101) 0 else s))
        fail("ws")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (!ready.isCompleted) ready.completeExceptionally(Fail(0, "zu"))
        fail("ws zu")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    /** Lauscher auf 127.0.0.1 (genau eine Verbindung, danach zu); Port fuer [HornWire.bend] */
    fun listen(): Int {
        if (dead || ws == null) throw Fail(0, "zu")
        val s = ServerSocket()
        s.receiveBufferSize = 64 shl 10                                     // gilt fuer angenommene Verbindungen
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
        server = s
        port = s.localPort
        thread(name = "horn-tunnel-accept", isDaemon = true) {
            val c = try { s.accept() } catch (_: IOException) { null }
            runCatching { s.close() }                                       // keine weitere lokale Verbindung
            if (c == null) return@thread
            if (dead || sock != null) { refused.incrementAndGet(); runCatching { c.close() }; return@thread }
            accepted.incrementAndGet()
            c.tcpNoDelay = true
            c.sendBufferSize = 64 shl 10
            lock.withLock {
                sock = c
                for (b in early) c.getOutputStream().write(b)
                early.clear()
            }
            thread(name = "horn-tunnel-up", isDaemon = true) { pumpUp(c) }
            thread(name = "horn-tunnel-ack", isDaemon = true) {
                while (!dead) {
                    Thread.sleep(5)
                    if (ackDue != 0L && System.nanoTime() - ackDue >= ackMs * 1_000_000) ack()
                }
            }
        }
        return port
    }

    /** libwebrtc -> Knoten, ohne Fenster (nur die OkHttp-Warteschlange begrenzen) */
    private fun pumpUp(c: Socket) {
        val buf = ByteArray(16 shl 10)
        try {
            val inp = c.getInputStream()
            while (!dead) {
                val n = inp.read(buf)
                if (n < 0) break
                lock.withLock {
                    var w = false
                    while (!dead && (ws?.queueSize() ?: 0) > (256L shl 10)) { w = true; room.awaitNanos(5_000_000) }
                    if (w) held++
                    sent += n
                    if (ws?.send(HornWire.data(buf, n).toByteString()) != true) dead = true
                }
            }
        } catch (_: Exception) {
        }
        fail("lokal", 1000)                                         // libwebrtc schloss die lokale Verbindung
    }

    /** Knoten -> libwebrtc (blockiert = Rueckstau), Quittungen zurueck */
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        when (val f = HornWire.frame(bytes.toByteArray())) {
            is HornWire.Frame.Data -> {
                try {
                    val c = sock
                    if (c == null) lock.withLock { if (sock == null) early.add(f.b) else sock!!.getOutputStream().write(f.b) }
                    else c.getOutputStream().write(f.b)
                } catch (_: IOException) {
                    fail("lokal"); return
                }
                synchronized(this) { rcvd += f.b.size }
                if (rcvd - ackedOut >= ackBytes) ack() else if (ackDue == 0L) ackDue = System.nanoTime()
                lock.withLock { room.signalAll() }
            }
            is HornWire.Frame.Ack -> synchronized(this) { acksIn++ }
            else -> {}
        }
    }

    @Synchronized private fun ack() {
        ackDue = 0L
        val v = rcvd
        if (v == ackedOut || dead) return
        ackedOut = v
        acksOut++
        ws?.send(HornWire.ack(v).toByteString())
    }

    private fun fail(why: String, code: Int = 0) {
        if (dead && closedWhy.isNotEmpty()) return
        dead = true
        if (closedWhy.isEmpty()) closedWhy = why
        lock.withLock { room.signalAll() }
        runCatching { server?.close() }
        runCatching { if (code == 1000) ws?.close(1000, null) else ws?.cancel() }
        runCatching { sock?.close() }
        val k = onClose
        onClose = null
        if (readyAt > 0) k?.invoke(why)                                  // nur nach erfolgreichem Aufbau
    }

    fun close() {
        onClose = null
        fail("zu")
        if (!ready.isCompleted) ready.completeExceptionally(Fail(0, "zu"))
    }

    fun stats(): String = "ws ready=${if (readyAt > 0) 1 else 0} ${if (readyAt > 0) (readyAt - t0) / 1_000_000 else 0}ms acc=${accepted.get()} ref=${refused.get()} " +
        "tx=$sent rx=$rcvd ack=$acksOut/$acksIn held=$held" + (if (closedWhy.isNotEmpty()) " zu=$closedWhy" else "")
}
