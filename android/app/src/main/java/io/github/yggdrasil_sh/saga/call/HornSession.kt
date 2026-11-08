package io.github.yggdrasil_sh.saga.call

import android.util.Log
import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Gjallarhorn (Saga 2.5, P12): Gruppenanruf ueber einen Knoten - Ablauf wie static/calls.js (hornStart, hornTry,
 * hornLeg, hornMon …), Entscheidungen aus [HornLogic].
 *
 * - Kandidaten parallel messen (TLS-Aufbau + 5 WSS-Pings ueber /probe, bei udp_ok ein STUN-Binding; zusammen <= 800 ms),
 *   nach Punktzahl ordnen und der Reihe nach versuchen. Weg: UDP (eine Leitung mit BUNDLE), sonst ICE-TCP (Ton- und
 *   Bild-Leitung getrennt, H1), sonst TURN-TLS mit frischen Token.
 * - Ereigniskanal je Bein (peers, layout, state, closed, pong), CSRC -> Geraet je Verbindung.
 * - Frame-E2E saga-horn/v1 mit FrameCryptor: eigener Schluessel an allen Sendern (Geraete-ID), je Empfangsplatz ein
 *   Cryptor mit eigener Platz-Kennung, in den die App die Schluessel des Geraets setzt, das den Platz gerade belegt
 *   (newest + vier Ratschenstufen vorab; der Knoten wechselt die Quelle eines Platzes). fkey/fkey-ack nur ueber den
 *   versiegelten Signalweg ([Host.sendSignals]); "need" aus getStats.
 * - Frische Token: aelter als 30 s vor dem Verbinden neu holen, nach 401 einmal neu.
 * - Live-Umschalten make-before-break mit Hysterese, Rueckkehr auf UDP, Knoten meiden, Neuversuch bis 2 min.
 *
 * Alles laeuft auf dem Haupt-Thread ([Host.scope]); Rueckrufe von WebRTC und OkHttp springen dorthin.
 */
class HornSession(private val host: Host, val callId: String, block: HornLogic.Block?) {

    /** Was die Sitzung vom CallManager braucht */
    interface Host {
        val scope: CoroutineScope
        val factory: PeerConnectionFactory
        fun myDev(): String
        /** Handy (Last-N 4) oder Tablet (9) */
        fun mobile(): Boolean
        /** Teilnehmerliste (Endpunkte mit hp/hpn) */
        fun legs(): List<CallLeg>
        fun audioTrack(): AudioTrack?
        fun camTrack(): VideoTrack?
        fun screenTrack(): VideoTrack?
        /** Mikrofon/Kamera/Bildschirm fuer den Zustand an die anderen */
        fun myState(): Triple<Boolean, Boolean, Boolean>
        /** POST /api/calls/{id}/horn -> Kandidaten (node: dort eintragen; avoid: meiden) */
        suspend fun cands(node: String?, avoid: List<String>): List<HornLogic.Cand>
        /** Bodies (Klartext-JSON) je Zielgeraet versiegeln und in EINEM POST /signal senden */
        suspend fun sendSignals(bodies: Map<String, String>)
        /** Geraeteschluessel (sign_pk) fuer das Runensiegel; null = nicht geprueft */
        suspend fun signPk(dev: String): ByteArray?
        /** Oberflaeche neu berechnen */
        fun changed()
        fun note(text: UiText?)
        /** Kein Knoten erreichbar (2 min) bzw. Anruf beendet: Anruf beenden */
        fun lost(text: UiText)
        fun ended()
        /** Bein vom Knoten beendet mit "entfernt" (Gastgeber) */
        fun removedHint()
        fun refreshRoster()
        /** 2.5 Akku: Energiesparmodus an oder Geraet heiss -> weniger kodieren */
        fun lowPower(): Boolean = false
        /** pt2 A4: Saga-Server im lokalen Netz (private Knoten-Adressen fuer TURN erlaubt) */
        fun iceLocal(): Boolean = false
        /** Waermestufe (PowerManager.THERMAL_STATUS_*) fuer Diagnose/Protokoll; -1 = unbekannt */
        fun thermal(): Int = -1
        /** A1: Hoehe (kurze Kante) von Kamera bzw. geteiltem Bildschirm fuer die Deckel auf TCP/TLS; 0 = unbekannt */
        fun camHeight(): Int = 0
        fun screenHeight(): Int = 0
        /** 2.5 Anruf-Diagnose (calldiag1): Ereignis, Probe aus getStats, Kuerzel einer Quelle, Weg/Knoten */
        fun diagEvent(k: String, d: String) {}
        fun diagSample(type: String, m: Map<String, Any?>, src: String, key: String, rtt: Double?) {}
        fun diagAlias(dev: String?): String = ""
        fun diagWay(path: String, node: String) {}
        /** Video-Senke einer Kachel bzw. des grossen Bildes (Sprecher/Bildschirm) */
        fun tileSink(dev: String): ProxySink
        val bigSink: ProxySink
    }

    // ---------------------------------------------------------------- Zustand

    private val scope get() = host.scope
    private var blk: HornLogic.Block = block ?: HornLogic.Block()
    private val rnd = SecureRandom()
    private val http: OkHttpClient = testClient ?: OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    companion object {
        /** Nur Geraetetests (lokaler Knoten mit selbst signiertem Zertifikat); in der App immer null */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var testClient: OkHttpClient? = null

        /** Nur Geraetetests: Wartezeiten der Wege verlaengern (langsamer Emulator ohne Hardware) */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var testWaitScale: Long = 1
    }
    private val allowHttp = BuildConfig.DEBUG

    private val provider: FrameCryptorKeyProvider = HornCryptor.keyProvider()
    val own = HornLogic.OwnKeys({ ByteArray(32).also { rnd.nextBytes(it) } }, hex(8))
    private val peerKeys = HashMap<String, HornLogic.PeerKeys>()
    /** Geraete, an die der eigene Schluessel schon ging */
    private val known = LinkedHashSet<String>()
    private var started = false
    private var rekeyJob: Job? = null
    private var switchJob: Job? = null
    private val acks = HashMap<String, String>()
    private var ackJob: Job? = null
    private var stateJob: Job? = null
    /** Zustand der anderen: mic, cam, screen */
    val st = HashMap<String, Triple<Boolean, Boolean, Boolean>>()
    /** P9/P10: Kennungen anderer Geraete an ihren Knoten aus state.hp (Kaskade, Knoten anderer Server) */
    private val hpx = HashMap<String, List<String>>()
    /** zuletzt "need" je Sender */
    private val needAt = HashMap<String, Long>()
    private val unreadSince = HashMap<String, Long>()

    var cur: Conn? = null
        private set
    private var next: Conn? = null
    private val olds = ArrayList<Conn>()
    private var seq = 0
    private val bad = HashMap<String, Long>()
    private var leaving: Pair<String, Long>? = null
    private val meas = HashMap<String, HornLogic.Measure>()
    private var sw: HornLogic.Switcher? = null
    private var switching = false
    private var retryJob: Job? = null
    /** Horn-Fix: Wiederaufbau nach Knotenverlust (immer ueber Knoten, nie direkt); Regeln in HornRecover.kt */
    private val rec = HornRecover()
    /** Knoten, die der Server in dieser Sitzung angeboten hat (avoid-Regel: einzigen Knoten nicht meiden) */
    private val known = HashSet<String>()
    /** TT 67: Knoten, die in dieser Runde am Zertifikat scheiterten; anderer Fehler in dieser Runde; Runden nur Zertifikat */
    private val certBad = LinkedHashSet<String>()
    private var otherFail = false
    private var certRounds = 0
    private var monJob: Job? = null
    private var candAt = 0L
    private var udpProbeAt = 0L
    private var statAt = 0L
    var closed = false
        private set
    var since = 0L
        private set
    var note: UiText? = null
        private set

    /** Anzeige */
    var view = "grid"
    var pin: String? = null
    var speaker: String? = null
        private set
    val speaking = HashSet<String>()
    private val speakAt = HashMap<String, Long>()
    /** Meeting-Ansicht: letzte Pegel je Geraet (Sprecher-Hysterese im CallManager) und wann gemessen */
    val levelsNow = HashMap<String, Double>()
    var levelsAt = 0L
        private set
    var runes: HornE2E.RuneSeal? = null
        private set
    private var runesKey = ""
    var unverified: List<String> = emptyList()
        private set
    /** Geraet -> gebundene Spur je Senke */
    private val bound = HashMap<String, VideoTrack>()
    private var bigBound: VideoTrack? = null
    var screenFrom: String? = null
        private set
    var moving: Set<String> = emptySet()
        private set
    var bigOn = false
        private set

    private fun log(s: String) {
        if (BuildConfig.DEBUG) Log.i("SagaHorn", s)
    }

    private fun hex(n: Int): String = ByteArray(n).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ---------------------------------------------------------------- Verbindungen und Beine

    /** Empfangsplatz: Transceiver mit Cryptor unter eigener Platz-Kennung (vid), Belegung (owner) */
    inner class Recv(val tr: RtpTransceiver, val kind: String, val vid: String, val cryptor: FrameCryptor?) {
        var owner: String? = null
        var scrubJob: Job? = null
        val track: MediaStreamTrack? get() = try {
            tr.receiver.track()
        } catch (_: Exception) {
            null
        }
    }

    inner class Leg(val role: String, val pc: PeerConnection) {
        var id: String? = null
        val recv = ArrayList<Recv>()
        val aud = ArrayList<Recv>()
        val vid = ArrayList<Recv>()
        var slots: List<HornLogic.Slot> = emptyList()
        var est = 0
        var tr: RtpTransceiver? = null
        var cam: RtpTransceiver? = null
        var scr: RtpTransceiver? = null
        val senders = ArrayList<FrameCryptor>()
        var ws: WebSocket? = null
        var state: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW
        /** 2.5 (handover2, L3): durch ein neues Bein mit gleicher leg_ref ersetzt - Ereignisse/Schliessen kein Verlust */
        var replaced = false
        /** neues Ton-Bein wird gerade aufgebaut (noch nicht verbunden ist hier kein Verbindungsverlust) */
        var pend = false
    }

    inner class Conn(val key: String, var cand: HornLogic.Cand, val path: String) {
        val node: String get() = cand.node
        val legs = LinkedHashMap<String, Leg>()
        var peers: Map<String, Long> = emptyMap()
        var csrc: Map<Long, String> = emptyMap()
        val created = System.currentTimeMillis()
        var up = 0L
        var rx = 0L
        var dead = false
        var closed: String? = null
        var retired = 0L
        val rtts = ArrayList<Double>()
        val pings = HashMap<Long, Long>()
        var pingN = 0L
        var pingAt = 0L
        var state = "ok"
        var mine = 0L
        var ws: WebSocket? = null
        var onUp: CompletableDeferred<Boolean>? = null
        var net: HornLogic.Net? = null
        var q = ""
        /** 1.11.: Empfangsqualitaet ueber 30 s, Anzeige erst nach zwei gleichen Messungen (CallDiag) */
        val gauge = CallDiag.Gauge()
        var shown: CallDiag.Debounce<String>? = null
        var q5: LongArray? = null
        var q5t = 0L
        var bytesIn = 0L
        /** A1: Sendestufe auf TCP/TLS aus der RTT der Bild-Leitung (UDP: aus, GCC regelt) */
        val gov = if (path == "udp") null else HornLogic.TxGov(path)
    }

    fun conns(): List<Conn> = (listOfNotNull(cur) + olds + listOfNotNull(next)).filter { !it.dead }.distinct()

    // ---------------------------------------------------------------- Start, Ende

    /** Beitritt: eigener Schluessel, Teilnehmerliste, Kandidaten messen und verbinden */
    fun start() {
        since = 0
        own.start(System.currentTimeMillis())
        host.changed()
        roster()
        scope.launch {
            try {
                newRound()
                begin(blk.candidates, "join")
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoNode) {
                retry("join")
            } catch (e: CallEnded) {
                host.ended()
            } catch (e: HornBusy) {
                retry("join", e.after)
            } catch (e: Exception) {
                log("start: ${e.message}")
                retry("join")
            }
        }
    }

    /** Anruf vorbei: alle Beine zu, Cryptoren und Schluessel frei */
    fun close() {
        if (closed) return
        closed = true
        monJob?.cancel()
        retryJob?.cancel()
        rekeyJob?.cancel()
        switchJob?.cancel()
        ackJob?.cancel()
        stateJob?.cancel()
        for (c in conns() + olds) closeConn(c)
        cur = null
        next = null
        olds.clear()
        for ((d, t) in bound) try {
            t.removeSink(host.tileSink(d))
        } catch (_: Exception) {
        }
        bound.clear()
        bigBound?.let { try { it.removeSink(host.bigSink) } catch (_: Exception) {} }
        bigBound = null
        try {
            provider.dispose()
        } catch (_: Exception) {
        }
        dummyV?.let { (src, t) -> try { t.dispose(); src.dispose() } catch (_: Exception) {} }
        dummyA?.let { (src, t) -> try { t.dispose(); src.dispose() } catch (_: Exception) {} }
        dummyV = null
        dummyA = null
    }

    private class NoNode : Exception("kein knoten")
    /** Server antwortet 429 (voll/zu viele Anfragen): after = retry_after in s (null = aelterer Server, normaler Backoff) */
    class HornBusy(val after: Int?) : Exception("429")
    private class CallEnded : Exception("beendet")
    private class HttpErr(val code: Int, msg: String) : IOException(msg)

    // ---------------------------------------------------------------- HTTP zum Knoten

    private suspend fun nodeHttp(url: String, body: JsonObject?, method: String = if (body != null) "POST" else "GET"): String =
        withContext(Dispatchers.IO) {
            val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).method(method, if (method == "POST") rb else null).build()
            suspendCancellableCoroutine { cont ->
                val call = http.newCall(req)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        val r = try {
                            response.use { it.code to it.body.string() }
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWith(Result.failure(e))
                            return
                        }
                        if (!cont.isActive) return
                        if (r.first !in 200..299) cont.resumeWith(Result.failure(HttpErr(r.first, r.second.take(200))))
                        else cont.resume(r.second)
                    }
                })
            }
        }

    // ---------------------------------------------------------------- Messung (§2.6)

    /** TLS-Aufbau + 5 WSS-Pings ueber /probe, dazu STUN-Binding bei udp_ok; alles innerhalb von ms */
    suspend fun probe(c: HornLogic.Cand, ms: Long = HornLogic.PROBE_MS): HornLogic.Measure = kotlinx.coroutines.coroutineScope {
        val udp = async(Dispatchers.IO) { if (c.udp) stun(c.host, c.port, ms) else null }
        val wss = async { wssProbe(c, ms) }
        val (tls, rtts) = wss.await()
        val u = udp.await()
        HornLogic.Measure(rtts.isNotEmpty(), rtts, tls, u?.first, u?.second == true)
    }

    private suspend fun wssProbe(c: HornLogic.Cand, ms: Long): Pair<Double?, List<Double>> {
        val t0 = System.nanoTime()
        val rtts = ArrayList<Double>()
        var tls: Double? = null
        val done = CompletableDeferred<Unit>()
        val sent = HashMap<Int, Long>()
        var n = 0
        val ws = try {
            http.newWebSocket(Request.Builder().url(c.wsBase + "/probe").build(), object : WebSocketListener() {
                private fun ping(w: WebSocket) {
                    n++
                    synchronized(sent) { sent[n] = System.nanoTime() }
                    w.send("p$n")
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    tls = (System.nanoTime() - t0) / 1e6
                    ping(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val k = text.drop(1).toIntOrNull() ?: return
                    val s = synchronized(sent) { sent[k] } ?: return
                    synchronized(rtts) { rtts.add(Math.round((System.nanoTime() - s) / 1e5) / 10.0) }
                    if (synchronized(rtts) { rtts.size } >= HornLogic.PINGS) done.complete(Unit) else ping(webSocket)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.complete(Unit)
                }
            })
        } catch (_: Exception) {
            return null to emptyList()
        }
        withTimeoutOrNull(ms) { done.await() }
        ws.cancel()
        return tls to synchronized(rtts) { ArrayList(rtts) }
    }

    /**
     * STUN-Binding (RFC 5389, ohne USERNAME - die Weiche beantwortet es selbst) an den Knoten auf UDP. Ergebnis: (ms,
     * unklar) - Antwort = Zeit; ohne Antwort und ohne Fehler bei erreichbarer Adresse = unklar (kurz probieren).
     */
    private fun stun(hostName: String, port: Int, ms: Long): Pair<Double?, Boolean> {
        val deadline = System.nanoTime() + ms * 1_000_000
        return try {
            val addr = InetAddress.getByName(hostName)
            DatagramSocket().use { s ->
                val tx = ByteArray(12).also { rnd.nextBytes(it) }
                val req = ByteArray(20)
                req[1] = 0x01                                   // Binding Request, Laenge 0
                req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4.toByte(); req[7] = 0x42
                System.arraycopy(tx, 0, req, 8, 12)
                val t0 = System.nanoTime()
                s.send(DatagramPacket(req, req.size, addr, port))
                val buf = ByteArray(512)
                var res: Pair<Double?, Boolean> = null to true      // keine Antwort, aber kein Fehler: unklar
                while (true) {
                    val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                    if (left <= 0) break
                    s.soTimeout = left
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                    if (p.length >= 20 && buf[0].toInt() == 0x01 && buf[1].toInt() == 0x01 && (8 until 20).all { buf[it] == tx[it - 8] }) {
                        res = Math.round((System.nanoTime() - t0) / 1e5) / 10.0 to false
                        break
                    }
                }
                res
            }
        } catch (_: java.net.PortUnreachableException) {
            null to false
        } catch (_: Exception) {
            null to false
        }
    }

    private suspend fun measure(cands: List<HornLogic.Cand>): Map<String, HornLogic.Measure> = kotlinx.coroutines.coroutineScope {
        cands.map { c -> async { c.node to probe(c) } }.awaitAll().toMap().also { meas.putAll(it) }
    }

    private fun isBad(node: String): Boolean = (bad[node] ?: 0) > System.currentTimeMillis()

    private fun avoid(extra: List<String> = emptyList()): List<String> {
        val now = System.currentTimeMillis()
        val a = bad.filter { it.value > now }.keys.toMutableList()
        leaving?.let { (n, until) -> if (until > now && n !in a) a.add(n) }
        for (n in extra) if (n !in a) a.add(n)
        return HornRecover.avoid(a, known + blk.candidates.map { it.node })   // einzigen Knoten nie meiden
    }

    /** Kandidaten vom Server; merkt sich die angebotenen Knoten */
    private suspend fun cands(node: String?, avoid: List<String>): List<HornLogic.Cand> =
        host.cands(node, avoid).also { l -> known.addAll(l.map { it.node }) }

    private suspend fun fresh(c: HornLogic.Cand): HornLogic.Cand =
        cands(c.node, avoid()).firstOrNull { it.node == c.node } ?: throw NoNode()

    // ---------------------------------------------------------------- Kandidaten -> Verbindung

    /** Kandidaten messen, ordnen, der Reihe nach versuchen. why: join | lost | draining | better | retry */
    private suspend fun begin(cands0: List<HornLogic.Cand>, why: String): Conn {
        val cands = cands0.filter { !isBad(it.node) || cands0.size == 1 }.ifEmpty { cands0 }   // alle gescheitert: trotzdem
        if (cands.isEmpty()) throw NoNode()
        val one = cands.size == 1
        val list = if (one && !cands[0].udp) {
            scope.launch { try { measure(cands) } catch (_: Exception) {} }       // nur fuer die Anzeige
            cands
        } else HornLogic.rank(cands, measure(cands)).map { it.cand }
        return tryList(list, why, 0)
    }

    private suspend fun tryList(list: List<HornLogic.Cand>, why: String, i: Int): Conn {
        val c = list.getOrNull(i) ?: throw NoNode()
        if (closed) throw CancellationException()
        val first = blk.candidates.firstOrNull()?.node
        val young = c.exp > 0 && c.exp * 1000 - System.currentTimeMillis() > HornLogic.FRESH_MS
        return try {
            val c2 = if (why == "join" && i == 0 && c.node == first && !usedJoin && young) c.also { usedJoin = true } else fresh(c)
            val conn = open(c2)
            adopt(conn, why)
            conn
        } catch (e: CancellationException) {
            throw e
        } catch (e: CallEnded) {
            throw e
        } catch (e: HornBusy) {
            throw e                                                 // Server 429: warten statt naechster Knoten
        } catch (e: Exception) {
            log("knoten ${c.node}: ${e.message}")
            if (e is HttpErr && e.code == 404) throw CallEnded()
            if (HornLogic.certError(e)) certBad.add(c.node) else otherFail = true
            bad[c.node] = System.currentTimeMillis() + HornLogic.BAD_MS
            list.getOrNull(i + 1)?.let { nx -> setNote(UiText.Res(R.string.horn_node_try_next, c.node, nx.node)) }
            tryList(list, why, i + 1)
        }
    }

    private var usedJoin = false

    /** Weg waehlen: UDP (wenn udp_ok und die UDP-Probe klappt), sonst ICE-TCP, dann TURN-TLS mit frischen Token */
    private suspend fun open(cand: HornLogic.Cand): Conn {
        val m = meas[cand.node]
        val udp = cand.udp && cand.tokens["both"] != null && m != null && (m.udp != null || m.udpMaybe)
        try {
            if (udp) {
                try {
                    return connect(cand, "udp", if (m.udp != null) HornLogic.WAIT_UDP else HornLogic.WAIT_UDP_MAYBE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpErr) {
                    if (e.code == 404 || e.code == 429) throw e
                } catch (_: Exception) {
                }
                return connect(fresh(cand), "tcp")
            }
            return connect(cand, "tcp")
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpErr) {
            if (e.code == 404 || e.code == 429) throw e
            return connect(fresh(cand), "turn")
        } catch (e: CallEnded) {
            throw e
        } catch (_: Exception) {
            return connect(fresh(cand), "turn")
        }
    }

    private suspend fun connect(cand: HornLogic.Cand, path: String, waitMs: Long = 0): Conn {
        val conn = Conn("c" + (++seq), cand, path)
        next = conn
        try {
            var turn: Pair<String, String>? = null
            if (path == "turn") {
                val r = nodeHttp(cand.url + "/api/sfu/turn", buildJsonObject { put("token", cand.tokens["audio"].orEmpty()) })
                val o = kotlinx.serialization.json.Json.parseToJsonElement(r) as? JsonObject
                turn = ((o?.get("username") as? JsonPrimitive)?.contentOrNull ?: "") to ((o?.get("credential") as? JsonPrimitive)?.contentOrNull ?: "")
            }
            for (role in HornLogic.roles(path)) leg(conn, role, path, turn)
            val d = CompletableDeferred<Boolean>()
            conn.onUp = d
            connState(conn)
            val wait = if (waitMs > 0) waitMs else when (path) {
                "udp" -> HornLogic.WAIT_UDP
                "tcp" -> HornLogic.WAIT_TCP
                else -> HornLogic.WAIT_TURN
            }
            val ok = withTimeoutOrNull(wait * testWaitScale) { d.await() }
            conn.onUp = null
            if (ok != true) throw IOException(if (ok == null) "timeout" else "failed")
            if (closed) {
                closeConn(conn)
                throw CancellationException()
            }
            return conn
        } catch (e: Throwable) {
            closeConn(conn)
            if (next === conn) next = null
            throw e
        }
    }

    private fun rtcConfig(cand: HornLogic.Cand, turn: Pair<String, String>?): PeerConnection.RTCConfiguration {
        // pt2 A4: TURN-Adresse aus dem Knoten-Kandidaten pruefen (Loopback/Link-Local nie, privat nur im Heimnetz-Betrieb)
        val tu = "turns:${if (cand.host.contains(':')) "[${cand.host}]" else cand.host}:${cand.port}?transport=tcp"
        val ice = if (turn != null && IceFilter.allowed(tu, host.iceLocal())) listOf(
            PeerConnection.IceServer.builder(tu).setUsername(turn.first).setPassword(turn.second).createIceServer(),
        ) else emptyList()
        return PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            if (turn != null) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
    }

    private fun observer(conn: Conn, legRef: () -> Leg?): PeerConnection.Observer = object : PeerConnection.Observer {
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
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            scope.launch {
                legRef()?.state = s
                if (!conn.dead) connState(conn)
            }
        }
    }

    /**
     * Ein Bein (Ton-Leitung, Bild-Leitung bzw. UDP-Weg): eigene Sender zuerst (Cryptor VOR der Verhandlung), dann die
     * Empfangsplaetze in fester Reihenfolge (Ton 3; Sprecher, Bildschirm, Kacheln). POST /api/sfu/leg, 401 -> einmal frisch.
     */
    private suspend fun leg(conn: Conn, role: String, path: String, turn: Pair<String, String>?, pend: Boolean = false) {
        // Aufgelegt bzw. Verbindung verworfen, waehrend davor gewartet wurde (TURN-Zugang, voriges Bein): kein neues Bein -
        // closeConn liefe fuer die tote Verbindung nicht mehr, PeerConnection und Platzhalter blieben liegen, und der
        // schon freigegebene KeyProvider wirft
        if (closed || conn.dead) throw CancellationException()
        var legObj: Leg? = null
        val pc = host.factory.createPeerConnection(rtcConfig(conn.cand, turn), observer(conn) { legObj })
            ?: throw IOException("peerconnection")
        val L = Leg(role, pc)
        L.pend = pend
        legObj = L
        conn.legs[role] = L
        val me = host.myDev()
        val tiles = HornLogic.tileSlots(host.mobile())
        fun recv(kind: String): Recv {
            val mt = if (kind == "audio") MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO else MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            val tr = pc.addTransceiver(mt, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            val vid = "${conn.key}.$role.${L.recv.size}"
            val cr = try {
                HornCryptor.receiver(host.factory, tr.receiver, vid, provider).also { it.setEnabled(true) }
            } catch (e: Exception) {
                log("cryptor recv: ${e.message}")
                null
            }
            val r = Recv(tr, kind, vid, cr)
            L.recv.add(r)
            (if (kind == "audio") L.aud else L.vid).add(r)
            return r
        }
        fun sender(tr: RtpTransceiver, dummy: Boolean) {
            val c = HornCryptor.sender(host.factory, tr.sender, me, provider)
            c.setKeyIndex(own.active.kid)
            c.setEnabled(true)                               // nie setEnabled(false): dann ginge Klartext raus
            L.senders.add(c)
            // Platzhalter-Spur nur fuer das Anlegen des Cryptors (webrtc-sdk braucht dafuer eine Spur); vor der
            // Verhandlung wieder ab - es geht nie ein Rahmen ohne Cryptor raus, der Cryptor bleibt am Sender
            if (dummy) tr.sender.setTrack(null, false)
        }
        // eigener Schluessel liegt vor dem ersten Rahmen im Provider
        provider.setKey(me, own.active.kid, own.active.key)
        own.pending?.let { provider.setKey(me, it.kid, it.key) }
        if (role != "video") {
            val a = host.audioTrack()
            val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("saga"))
            L.tr = pc.addTransceiver(a ?: dummyAudio(), init)
            sender(L.tr!!, a == null)
            repeat(3) { recv("audio") }
        }
        if (role != "audio") {
            val enc = HornLogic.camEncodings(path).map { e ->
                // alle Schichten gleich: T3 ueber numTemporalLayers, keine Bildrate je Schicht (HornLogic.Enc, Fehler 26.10.)
                RtpParameters.Encoding(e.rid, true, e.scaleDown).apply {
                    maxBitrateBps = e.maxKbit * 1000
                    numTemporalLayers = HornLogic.CAM_TEMPORAL
                }
            }
            val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("saga"), enc)
            val cam = host.camTrack()
            L.cam = pc.addTransceiver(cam ?: dummyVideo(), init)
            sender(L.cam!!, cam == null)
            val (sk, sf) = HornLogic.screenRate(path)
            val sinit = RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("screen"),
                listOf(RtpParameters.Encoding(null, true, 1.0).apply { maxBitrateBps = sk * 1000; maxFramerate = sf }),
            )
            val scr = host.screenTrack()
            L.scr = pc.addTransceiver(scr ?: dummyVideo(), sinit)
            sender(L.scr!!, scr == null)
            recv("video")                                    // Sprecher
            recv("video")                                    // Bildschirm
            repeat(tiles) { recv("video") }                  // Kacheln (Last-N)
        }
        // nur VP8/Opus (der Knoten handelt nichts anderes aus)
        try {
            val vp8 = host.factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs.filter { it.name.equals("VP8", true) }
            // nicht pc.transceivers: das verwirft die Java-Huellen der gemerkten Transceiver (RtpTransceiver disposed)
            if (vp8.isNotEmpty()) for (t in listOfNotNull(L.cam, L.scr) + L.vid.map { it.tr }) t.setCodecPreferences(vp8)
        } catch (_: Exception) {
        }
        val offer = sdp { pc.createOffer(it, MediaConstraints()) } ?: throw IOException("offer")
        sdpSet { pc.setLocalDescription(it, offer) }
        val local = pc.localDescription?.description ?: offer.description
        suspend fun post(c: HornLogic.Cand): String = nodeHttp(c.url + "/api/sfu/leg", buildJsonObject {
            put("token", c.tokens[role].orEmpty())
            put("path", path)
            put("offer", local)
            put("audio", if (role != "video") 3 else 0)
            put("speaker", role != "audio")
            put("screen", role != "audio")
            put("tiles", if (role != "audio") tiles else 0)
            put("device", if (host.mobile()) "mobile" else "desktop")
            put("screen_mid", L.scr?.mid.orEmpty())
        })
        val text = try {
            post(conn.cand)
        } catch (e: HttpErr) {
            if (e.code != 401 || closed) throw e
            conn.cand = fresh(conn.cand)
            post(conn.cand)
        }
        val res = HornLogic.parseLeg(text) ?: throw IOException("antwort")
        if (closed || conn.dead || conn.legs[role] !== L) throw CancellationException()
        log("bein $role ${conn.node} $path: " + res.answer.lines().filter { it.startsWith("a=candidate") || it.startsWith("a=setup") }.distinct().joinToString(" | "))
        L.id = res.id
        conn.mine = res.csrc
        L.slots = res.slots
        conn.state = res.state
        peersIn(conn, res.peers)
        sdpSet { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, res.answer)) }
        events(conn, L, false)
        mapAll()
    }

    /** Platzhalter-Spuren (liefern nie Rahmen; Ton: Quelle ohne Sender oeffnet kein Mikrofon) */
    private var dummyV: Pair<org.webrtc.VideoSource, VideoTrack>? = null
    private var dummyA: Pair<org.webrtc.AudioSource, AudioTrack>? = null

    private fun dummyVideo(): VideoTrack = (dummyV ?: host.factory.createVideoSource(false).let { src ->
        src to host.factory.createVideoTrack("horn-none-v", src)
    }.also { dummyV = it }).second

    private fun dummyAudio(): AudioTrack = (dummyA ?: host.factory.createAudioSource(MediaConstraints()).let { src ->
        src to host.factory.createAudioTrack("horn-none-a", src).also { it.setEnabled(false) }
    }.also { dummyA = it }).second

    private suspend fun sdp(op: (SdpObserver) -> Unit): SessionDescription? = suspendCancellableCoroutine { cont ->
        op(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { if (cont.isActive) cont.resume(d) }
            override fun onCreateFailure(e: String?) { if (cont.isActive) cont.resume(null) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        })
    }

    private suspend fun sdpSet(op: (SdpObserver) -> Unit) {
        val err = suspendCancellableCoroutine<String?> { cont ->
            op(object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) {}
                override fun onCreateFailure(e: String?) {}
                override fun onSetSuccess() { if (cont.isActive) cont.resume(null) }
                override fun onSetFailure(e: String?) { if (cont.isActive) cont.resume(e ?: "sdp") }
            })
        }
        if (err != null) throw IOException(err)
    }

    // ---------------------------------------------------------------- Ereigniskanal

    private fun events(conn: Conn, L: Leg, again: Boolean) {
        val id = L.id ?: return
        if (conn.dead) return
        var opened = false
        val ws = try {
            http.newWebSocket(Request.Builder().url(conn.cand.wsBase + "/api/sfu/leg/" + id + "/events").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch { opened = true }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val ev = HornLogic.parseEvent(text) ?: return
                    scope.launch { if (!closed && !conn.dead && !L.replaced) event(conn, L, ev) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scope.launch { wsGone(conn, L, webSocket, again, opened) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scope.launch { wsGone(conn, L, webSocket, again, opened) }
                }
            })
        } catch (_: Exception) {
            return
        }
        L.ws = ws
        if (conn.ws == null) conn.ws = ws                   // Ping (RTT) ueber den ersten Kanal
    }

    private fun wsGone(conn: Conn, L: Leg, ws: WebSocket, again: Boolean, opened: Boolean) {
        if (L.ws === ws) L.ws = null
        if (conn.ws === ws) conn.ws = null
        if (closed || conn.dead || conn.closed != null || L.replaced) return
        // Neuverbindung scheitert sofort: Knoten weg - nicht erst auf ICE warten
        if (again && !opened) {
            if (cur === conn) switch("lost")
            return
        }
        scope.launch {
            delay(300)
            if (!closed && !conn.dead && L.ws == null && !L.replaced && conn.legs.values.any { it === L }) events(conn, L, true)
        }
    }

    private fun event(conn: Conn, L: Leg, ev: HornLogic.Event) {
        when (ev) {
            is HornLogic.Event.Hello -> {
                peersIn(conn, ev.peers)
                L.slots = ev.slots
                L.est = ev.estKbit
                stateIn(conn, ev.state)
                mapAll()
            }
            is HornLogic.Event.Peers -> peersIn(conn, ev.peers)
            is HornLogic.Event.Layout -> {
                L.slots = ev.slots
                L.est = ev.estKbit
                mapAll()
            }
            is HornLogic.Event.State -> stateIn(conn, ev.state)
            is HornLogic.Event.Closed -> {
                conn.closed = ev.why
                if (cur === conn) {
                    if (ev.why == "entfernt") host.removedHint() else switch("lost")
                }
            }
            is HornLogic.Event.Pong -> {
                val t = conn.pings.remove(ev.n) ?: return
                conn.rtts.add((System.nanoTime() - t) / 1e6)
                if (conn.rtts.size > 10) conn.rtts.removeAt(0)
            }
        }
    }

    private fun stateIn(conn: Conn, s: String) {
        conn.state = s
        if (s == "draining" && cur === conn) switch("draining")
    }

    private fun peersIn(conn: Conn, peers: Map<String, Long>) {
        conn.peers = peers
        mapAll()
        tune(conn)
        // unbekannte Kennung (Geraet gerade beigetreten): Teilnehmerliste einmal neu holen
        val mine = myRef(conn)
        if (peers.keys.any { devOf(conn, it) == null && it != mine } && System.currentTimeMillis() - rosterAt > 2000) {
            rosterAt = System.currentTimeMillis()
            host.refreshRoster()
        }
    }

    private var rosterAt = 0L
    private var tuneAt = 0L
    private var tuneLine = ""

    /**
     * 2.5 Akku: Kamera-Schichten nach Zahl der anderen und Energiesparen; A1 (Labor 2.11.): auf TCP/TLS bleibt l auch zu
     * zweit an (Ausweichschicht), m und der Bildschirm folgen den Deckeln und der Stufe aus der RTT ([HornLogic.txPlan],
     * [HornLogic.TxGov]); beim Bildschirmteilen auf TCP/TLS Kamera nur l. Nur bei Aenderung neu setzen.
     */
    private fun tune(conn: Conn) {
        tuneAt = System.currentTimeMillis()
        val mine = myRefs().toSet() + myRef(conn)                    // auch eigene Kennungen anderer Verbindungen (Wechsel)
        val others = conn.peers.keys.count { it !in mine }
        val lv = conn.gov?.level ?: 0
        val share = host.screenTrack() != null
        val plan = HornLogic.txPlan(conn.path, lv, others, share, host.camHeight(), host.screenHeight(), lowPower = host.lowPower())
        val fps = plan.maxFps
        val f = HornLogic.camMaxFps(fps)                              // gemeinsam fuer alle Schichten, nie je Schicht
        val on = plan.cam.filterValues { it.active }.keys
        val line = "schichten ${on.sorted()} fps ${fps ?: "-"} stufe $lv (andere $others) waerme ${host.thermal()}"
        if (line != tuneLine) { tuneLine = line; host.diagEvent("layers", line) }
        for (L in conn.legs.values) {
            L.cam?.sender?.let { s ->
                try {
                    val p = s.parameters
                    var diff = false
                    for (e in p.encodings) {
                        val w = plan.cam[e.rid] ?: continue
                        if (e.active != w.active) { e.active = w.active; diff = true }
                        if (e.maxFramerate != f) { e.maxFramerate = f; diff = true }
                        w.maxBps?.let { if (e.maxBitrateBps != it) { e.maxBitrateBps = it; diff = true } }
                        w.scaleDown?.let { if (e.scaleResolutionDownBy != it) { e.scaleResolutionDownBy = it; diff = true } }
                    }
                    // Kamera: Bildrate und Groesse ausgleichen (CPU/Waerme); Bildschirm unten: Schaerfe vor Bildrate
                    if (p.degradationPreference != RtpParameters.DegradationPreference.BALANCED) {
                        p.degradationPreference = RtpParameters.DegradationPreference.BALANCED; diff = true
                    }
                    if (diff) { s.parameters = p; log(line) }
                } catch (e: Exception) {
                    log("schichten: ${e.message}")
                }
            }
            L.scr?.sender?.let { s ->
                try {
                    val p = s.parameters
                    val e = p.encodings.firstOrNull() ?: return@let
                    var diff = false
                    plan.screen?.let { w ->
                        if (e.maxBitrateBps != w.maxBps) { e.maxBitrateBps = w.maxBps; diff = true }
                        if (e.maxFramerate != w.maxFps) { e.maxFramerate = w.maxFps; diff = true }
                        if (e.scaleResolutionDownBy != w.scaleDown) { e.scaleResolutionDownBy = w.scaleDown; diff = true }
                    }
                    if (p.degradationPreference != RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION) {
                        p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION; diff = true
                    }
                    if (diff) s.parameters = p
                } catch (e: Exception) {
                    log("bildschirm: ${e.message}")
                }
            }
        }
    }

    private fun refOf(x: CallLeg, conn: Conn): String? = x.hpn[conn.node] ?: x.hp
    private fun myRef(conn: Conn): String = host.legs().firstOrNull { it.device == host.myDev() }?.let { refOf(it, conn) } ?: conn.cand.peer
    /**
     * Kennung am Knoten -> Geraet: Teilnehmerliste (hp bzw. hpn dieses Knotens), dann hpn anderer Knoten dieses Servers
     * (Kaskade: Teilnehmende behalten die Kennung ihres Knotens), dann state.hp (Knoten anderer Server)
     */
    fun devOf(conn: Conn, ref: String): String? {
        if (ref.isEmpty()) return null
        val legs = host.legs()
        legs.firstOrNull { refOf(it, conn) == ref }?.let { return it.device }
        legs.firstOrNull { ref in it.hpn.values }?.let { return it.device }
        return hpx.entries.firstOrNull { (d, refs) -> ref in refs && legs.any { it.device == d } }?.key
    }

    /** Eigene Kennungen an allen Knoten, mit denen das Geraet gerade verbunden ist (state.hp, hoechstens 4) */
    private fun myRefs(): List<String> = HornLogic.myRefs(conns().map { myRef(it) })

    // ---------------------------------------------------------------- Belegung -> Cryptoren, Bilder, Ton

    /** CSRC-Zuordnung je Verbindung, Eigentuemer der Empfangsplaetze (Schluessel), Bilder und Ton neu binden */
    fun mapAll() {
        if (closed) return
        val me = host.myDev()
        for (conn in conns()) {
            val slots = conn.legs.values.flatMap { it.slots }
            conn.csrc = HornLogic.csrcMap(conn.peers, slots, { devOf(conn, it) }, me)
            for (L in conn.legs.values) for ((i, r) in L.recv.withIndex()) {
                val s = L.slots.firstOrNull { it.i == i }
                val o = HornLogic.slotOwner(s, conn.csrc, { devOf(conn, it) })
                if (o != null && o != r.owner) setOwner(r, o)
            }
        }
        bind()
    }

    /** Platz gehoert jetzt Geraet d: dessen Schluessel (newest + 4 Stufen) setzen; Reste des Vorgaengers nach 2 s weg */
    private fun setOwner(r: Recv, d: String) {
        r.owner = d
        val plan = peerKeys[d]?.plan().orEmpty()
        for (s in plan) provider.setKey(r.vid, s.kid, s.key)
        r.scrubJob?.cancel()
        r.scrubJob = scope.launch {
            delay(2000)
            if (r.owner != d || closed) return@launch
            // Plan JETZT bestimmen: kam der fkey von d erst nach dem Belegen (neues Geraet - der Knoten belegt den Platz
            // oft vor dem versiegelten fkey), haette ein beim Belegen gemerkter (leerer) Plan dessen Schluessel geloescht;
            // ein erneut gesendeter gleicher fkey setzt nichts neu (PeerKeys.fkey -> false) - der Platz bliebe stumm/schwarz
            val keep = peerKeys[d]?.plan().orEmpty().map { it.kid }.toSet()
            for (k in 0 until HornE2E.RING) if (k !in keep) provider.setKey(r.vid, k, HornE2E.scrubKey())
        }
    }

    /** Neuer Schluesselstand eines Senders: an allen Plaetzen, die er belegt, neu setzen */
    private fun applyPlan(d: String) {
        val plan = peerKeys[d]?.plan().orEmpty()
        if (plan.isEmpty()) return
        for (c in conns()) for (L in c.legs.values) for (r in L.recv) if (r.owner == d) for (s in plan) provider.setKey(r.vid, s.kid, s.key)
    }

    /** Bilder: je Geraet der Platz der aktuellen Verbindung (sonst einer alten), gross Bildschirm bzw. Sprecher */
    private fun bind() {
        val me = host.myDev()
        val vid = LinkedHashMap<String, VideoTrack>()
        var scr: Pair<String, VideoTrack>? = null
        var spk: String? = null
        val order = conns().sortedBy { if (it === cur) 0 else 1 }
        for (c in order) for (L in c.legs.values) for (s in L.slots) {
            if (s.cls == "audio" || s.paused) continue
            val r = L.recv.getOrNull(s.i) ?: continue
            val d = HornLogic.slotOwner(s, c.csrc, { devOf(c, it) }) ?: continue
            if (d == me) continue
            val t = r.track as? VideoTrack ?: continue
            when (s.cls) {
                "screen" -> if (scr == null && st[d]?.third != false) scr = d to t
                "speaker" -> {
                    if (spk == null || c === cur) spk = d
                    if (d !in vid) vid[d] = t
                }
                else -> if (d !in vid) vid[d] = t
            }
        }
        speaker = spk ?: speaker?.takeIf { s -> host.legs().any { it.device == s } }
        moving = vid.keys.filter { st[it]?.second != false }.toSet()
        for ((d, t) in vid) if (bound[d] !== t) {
            bound[d]?.let { try { it.removeSink(host.tileSink(d)) } catch (_: Exception) {} }
            t.addSink(host.tileSink(d))
            bound[d] = t
        }
        for (d in bound.keys.toList()) if (d !in vid) {
            try { bound[d]?.removeSink(host.tileSink(d)) } catch (_: Exception) {}
            bound.remove(d)
        }
        screenFrom = scr?.first
        val big = scr?.second ?: (pin ?: speaker)?.let { vid[it] } ?: vid.values.firstOrNull()
        if (big !== bigBound) {
            bigBound?.let { try { it.removeSink(host.bigSink) } catch (_: Exception) {} }
            big?.addSink(host.bigSink)
            bigBound = big
        }
        bigOn = big != null
        audioRoute()
        host.changed()
    }

    /** Ton waehrend eines Wechsels: je Absender nur EINE Verbindung (die neue, sobald sie liefert), sonst alle */
    private fun audioRoute() {
        val c = cur
        val onCur = HashSet<String>()
        if (c != null && c.rx > 0) for (L in c.legs.values) for (r in L.aud) r.owner?.let { onCur.add(it) }
        for (x in conns()) for (L in x.legs.values) for (r in L.aud) {
            val dup = x !== c && r.owner != null && r.owner in onCur
            try {
                (r.track as? AudioTrack)?.setEnabled(!dup)
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- Uebernehmen, Wechseln

    private fun connState(conn: Conn) {
        if (closed) return
        val ls = conn.legs.values.filter { !it.pend }
        val up = ls.isNotEmpty() && ls.all { it.state == PeerConnection.PeerConnectionState.CONNECTED }
        val failed = ls.any { it.state == PeerConnection.PeerConnectionState.FAILED || it.state == PeerConnection.PeerConnectionState.CLOSED }
        if (up && conn.up == 0L) conn.up = System.currentTimeMillis()
        if (up || failed) conn.onUp?.complete(up)
        host.changed()
    }

    /** Neue Verbindung uebernehmen (make-before-break): die bisherige laeuft weiter, bis die neue liefert */
    private fun adopt(conn: Conn, why: String) {
        val prev = cur
        val now = System.currentTimeMillis()
        if (next === conn) next = null
        cur = conn
        if (prev != null && prev !== conn) {
            prev.retired = now
            olds.add(prev)
            // Bild nur noch ueber die neue Verbindung senden; Ton ueber beide, bis die alte schliesst
            for (L in prev.legs.values) for (t in listOfNotNull(L.cam, L.scr)) try {
                t.sender.setTrack(null, false)
            } catch (_: Exception) {
            }
        }
        syncTracks(conn)
        if (prev?.path != conn.path) host.diagEvent("path", "${prev?.path ?: "-"} -> ${conn.path}")
        host.diagWay(conn.path, conn.node)
        val s = sw
        if (s == null) sw = HornLogic.Switcher(conn.node, conn.path, now) else s.moved(now, conn.node, conn.path, why)
        rec.adopted(now)                                            // Wiederaufbau: erst nach 10 s Daten zurueckgestellt
        certRounds = 0
        newRound()
        if (since == 0L) since = now
        setNote(null)
        mapAll()
        stateOut()                                                  // P9/P10: neue Kennung (state.hp) an alle
        runesSoon()
        if (monJob == null) monJob = scope.launch {
            while (isActive && !closed) {
                delay(500)
                try {
                    mon()
                    cur?.let { if (System.currentTimeMillis() - tuneAt > 5000) tune(it) }   // Energiesparen an/aus
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("mon: ${e.message}")
                }
            }
        }
        host.changed()
    }

    /**
     * Eigene Spuren an die uebernommene Verbindung: die Beine bekamen beim Anlegen die damaligen Spuren. Kamera an/aus
     * bzw. Teilen starten/beenden WAEHREND des Verbindens traf nur [cur] (camChanged setzt an anderen Verbindungen null,
     * screenChanged nur an cur) - nach dem Wechsel ging sonst kein Bild bzw. die alte, schon freigegebene Spur.
     */
    private fun syncTracks(conn: Conn) {
        val a = host.audioTrack()
        val v = host.camTrack()
        val sc = host.screenTrack()
        for (L in conn.legs.values) try {
            L.tr?.sender?.let { if (it.track() !== a) it.setTrack(a, false) }
            L.cam?.sender?.let { if (it.track() !== v) it.setTrack(v, false) }
            L.scr?.sender?.let { if (it.track() !== sc) it.setTrack(sc, false) }
        } catch (e: Exception) {
            log("spuren: ${e.message}")
        }
    }

    /** Wechseln. why: lost | draining | better (to) | udp-loss / udp (path, selber Knoten) */
    fun switch(why: String, to: String? = null, path: String? = null) {
        if (closed || rec.muted) return                             // Wiederaufbau laeuft: stumm
        if (why == "lost") { recover(why, cur?.node); return }      // Horn-Fix: Wiederaufbau ueber Knoten
        if (switching) return
        val c = cur
        val hard = why == "lost" || why == "draining"
        switching = true
        host.diagEvent("switch", why + (path?.let { " -> $it" } ?: ""))
        log("switch $why ${path ?: ""}")
        val now = System.currentTimeMillis()
        if (hard && c != null) leaving = c.node to now + HornLogic.BAD_MS
        if (why == "draining") setNote(UiText.Res(R.string.horn_draining_switch, c?.node ?: "?"))
        scope.launch {
            try {
                if (path != null && c != null) {
                    val conn = connect(fresh(c.cand), path)
                    adopt(conn, why)
                } else {
                    var list = cands(null, avoid())
                    if (to != null) list = list.filter { it.node == to }
                    else if (hard && c != null) {
                        val other = list.filter { it.node != c.node }
                        list = other.ifEmpty { list }
                    }
                    if (list.isEmpty() && hard && c != null) list = listOf(c.cand)
                    begin(list, why)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CallEnded) {
                host.ended()
            } catch (e: HttpErr) {
                if (e.code == 404) host.ended() else if (hard) retry(why)
            } catch (e: HornBusy) {
                if (hard) retry(why, e.after)
            } catch (e: Exception) {
                log("switch $why: ${e.message}")
                if (hard) retry(why)
            } finally {
                switching = false
            }
        }
    }

    /** Wechsel/Beitritt gescheitert: Wiederaufbau mit Backoff (zaehlt als Fehlversuch) */
    private fun retry(why: String, after: Int? = null) {
        if (closed || rec.muted) return
        step(why, rec.failed(System.currentTimeMillis(), after, rnd.nextDouble()), failure = true)
    }

    /**
     * Horn-Fix: Verbindung zum Knoten verloren -> neu ueber einen Knoten verbinden (nie direkt/p2p). Der einzige Knoten
     * wird dabei nicht gemieden; die Beobachtung bleibt stumm, solange ein Versuch laeuft oder geplant ist.
     */
    private fun recover(why: String, node: String?) {
        if (closed) return
        val now = System.currentTimeMillis()
        val st = rec.lost(now, rnd.nextDouble()) ?: return          // laeuft schon
        log("wiederaufbau ${node ?: "-"} $why")
        host.diagEvent("switch", "lost (wiederaufbau)")
        if (node != null) bad[node] = now + HornLogic.BAD_MS        // nur bei mehreren Knoten gemieden
        step(why, st, failure = st is HornRecover.Step.Wait && st.ms > 0)
    }

    private fun step(why: String, st: HornRecover.Step, failure: Boolean) {
        if (closed) return
        if (st !is HornRecover.Step.Wait) {
            log("wiederaufbau aufgegeben")
            host.lost(UiText.Res(R.string.horn_recover_failed))
            return
        }
        // TT 67: alle Knoten lehnt TLS wegen des Zertifikats ab -> Grund nennen; nach CERT_ROUNDS Runden aufgeben statt
        // endlos zu wiederholen (selbst signierte Knoten werden bewusst NICHT akzeptiert)
        val cert = certBad.toList()
        if (failure) {
            if (cert.isNotEmpty() && !otherFail) certRounds++ else certRounds = 0
            if (HornLogic.giveUpCert(cert, otherFail, certRounds)) {
                host.lost(UiText.Res(R.string.horn_cert_untrusted, cert.joinToString(", ")))
                return
            }
        }
        val s = maxOf(1, Math.round(st.ms / 1000.0).toInt())
        setNote(when {
            failure && cert.isNotEmpty() && !otherFail -> UiText.Res(R.string.horn_cert_retry, cert.joinToString(", "), s)
            since > 0 -> UiText.Res(R.string.horn_reconnecting)
            else -> UiText.Res(R.string.horn_retry, s)
        })
        retryJob?.cancel()                                          // nur der eigene, ueberholte Plan
        retryJob = scope.launch {
            if (st.ms > 0) delay(st.ms)
            if (closed) return@launch
            var after: Int? = null
            try {
                newRound()
                begin(cands(null, avoid()), if (since > 0) "lost" else if (why == "join") "join" else "retry")
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: CallEnded) {
                host.ended()
                return@launch
            } catch (e: HttpErr) {
                if (e.code == 404) { host.ended(); return@launch }
            } catch (e: HornBusy) {
                after = e.after
            } catch (e: Exception) {
                log("wiederaufbau: ${e.message}")
            }
            if (closed) return@launch
            log("wiederaufbau fehlgeschlagen${after?.let { " 429 $it s" } ?: ""}")
            step(why, rec.failed(System.currentTimeMillis(), after, rnd.nextDouble()), failure = true)
        }
    }

    private fun newRound() {
        certBad.clear()
        otherFail = false
    }

    private fun setNote(t: UiText?) {
        note = t
        host.note(t)
    }

    private fun closeConn(c: Conn) {
        if (c.dead) return
        c.dead = true
        for (L in c.legs.values) {
            try {
                L.ws?.cancel()
            } catch (_: Exception) {
            }
            L.ws = null
            L.id?.let { id -> scope.launch { try { nodeHttp(c.cand.url + "/api/sfu/leg/" + id, null, "DELETE") } catch (_: Exception) {} } }
            for (r in L.recv) {
                r.scrubJob?.cancel()
                try { r.cryptor?.dispose() } catch (_: Exception) {}
            }
            for (x in L.senders) try { x.dispose() } catch (_: Exception) {}
            try {
                L.pc.close()
                L.pc.dispose()
            } catch (_: Exception) {
            }
        }
        // gebundene Spuren dieser Verbindung loesen (sie sind jetzt ungueltig)
        val gone = c.legs.values.flatMap { l -> l.vid.mapNotNull { it.track } }.toSet()
        for ((d, t) in bound.entries.toList()) if (gone.any { it === t }) bound.remove(d)
        if (gone.any { it === bigBound }) bigBound = null
        olds.remove(c)
        if (cur === c) cur = null
        if (next === c) next = null
        if (!closed) stateOut()                                     // state.hp ohne die geschlossene Verbindung
    }

    // ---------------------------------------------------------------- Beobachtung (alle 0,5 s)

    private suspend fun stats(pc: PeerConnection): RTCStatsReport? = withTimeoutOrNull(2000) {
        suspendCancellableCoroutine { cont -> pc.getStats { r -> if (cont.isActive) cont.resume(r) } }
    }

    private suspend fun mon() {
        if (closed) return
        val now = System.currentTimeMillis()
        val c = cur
        if (c != null && rec.tick(now, c.rx > 0)) log("wiederaufbau ok ${c.node}")
        if (c != null && !switching && !rec.muted) {
            var worst = "connected"
            for (L in c.legs.values) {
                if (L.pend) continue                                // L3: Ton-Bein wird gerade ersetzt
                val s = L.state
                if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.CLOSED) worst = s.name.lowercase()
                else if (s != PeerConnection.PeerConnectionState.CONNECTED && worst == "connected") worst = "disconnected"
            }
            sw?.conn(now, worst)?.let {
                switch(it.why)
                return
            }
        }
        // Neues Bein liefert Rahmen? (Pakete in getStats, ersatzweise 1,5 s nach dem Verbinden)
        for (x in conns()) {
            if (x.rx > 0 || x.up == 0L) continue
            var got = false
            for (L in x.legs.values) {
                val r = stats(L.pc) ?: continue
                if (r.statsMap.values.any { it.type == "inbound-rtp" && num(it.members["packetsReceived"]) > 0 }) got = true
            }
            if (got || now - x.up > 1500) {
                x.rx = now
                bind()
            }
        }
        // Alte Verbindungen schliessen: 2 s nachdem die neue liefert, solange dort noch andere nur ueber sie hoerbar sind 15 s
        if (c != null && c.rx > 0) for (o in olds.toList()) {
            val onCur = c.legs.values.flatMap { l -> l.aud.mapNotNull { it.owner } }.toSet()
            val waiting = o.legs.values.flatMap { l -> l.aud.mapNotNull { it.owner } }.count { it !in onCur }
            val dead = o.legs.values.any { it.state == PeerConnection.PeerConnectionState.FAILED || it.state == PeerConnection.PeerConnectionState.CLOSED }
            if (dead || o.closed != null || HornLogic.closeOld(now, maxOf(c.rx, o.retired), waiting)) {
                closeConn(o)
                bind()
            }
        }
        // Ping ueber den Ereigniskanal (RTT)
        if (c != null && now - c.pingAt >= HornLogic.PING_EVERY) {
            c.ws?.let { ws ->
                c.pingAt = now
                c.pingN++
                c.pings[c.pingN] = System.nanoTime()
                c.pings.remove(c.pingN - 10)
                ws.send("{\"t\":\"ping\",\"n\":${c.pingN}}")
            }
        }
        // Schluessel: faelliger Wechsel, Ablaeufe, 6 h
        own.due(now)?.let { switchOwn(it) }
        if (own.tooOld(now)) rekey()
        for ((d, p) in peerKeys) for (kid in p.expired(now)) {
            for (x in conns()) for (L in x.legs.values) for (r in L.recv) if (r.owner == d) provider.setKey(r.vid, kid, HornE2E.scrubKey())
        }
        if (c != null && now - statAt >= 3000) {
            statAt = now
            quality(c)
        }
        // Auf TCP/TLS zu einem Knoten mit UDP: alle 10 s die UDP-Probe
        if (c != null && c.path != "udp" && c.cand.udp && c.cand.tokens["both"] != null && now - udpProbeAt >= HornLogic.UDP_PROBE_EVERY) {
            udpProbeAt = now
            scope.launch { try { meas[c.node] = probe(c.cand) } catch (_: Exception) {} }
        }
        // Andere Kandidaten: alle 30 s neu messen, Hysterese entscheidet
        if (c != null && !switching && !rec.muted && now - (if (candAt > 0) candAt else c.created) >= HornLogic.CANDS_EVERY) {
            candAt = now
            scope.launch {
                try {
                    val list = cands(null, emptyList())
                    val others = list.filter { it.node != c.node }
                    if (others.isEmpty() || closed) return@launch
                    val m = measure(list)
                    val load = list.firstOrNull { it.node == c.node }?.load
                    val curScore = HornLogic.score(
                        HornLogic.Measure(true, c.rtts.ifEmpty { m[c.node]?.rtts.orEmpty() }, udp = if (c.path == "udp") 1.0 else null),
                        HornLogic.Cand(c.node, here = true, udp = c.path == "udp", load = load),
                    )
                    sw?.observe(System.currentTimeMillis(), curScore, others.map { HornLogic.Other(it.node, HornLogic.score(m[it.node], it)) })
                        ?.let { switch("better", to = it.to) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: v?.toString()?.toBigDecimalOrNull()?.toLong() ?: 0L
    private fun dbl(v: Any?): Double? = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull()

    /** Qualitaet (RTT, Verlust, Einfrieren), UDP-Regeln, Sprechpegel, "need" aus getStats */
    private suspend fun quality(c: Conn) {
        var rtt: Double? = null
        var vrtt: Double? = null                                       // A1: RTT der Bild-Leitung (dort staut sich das Bild)
        var lost = 0L
        var recv = 0L
        var fz = 0L
        var bytes = 0L
        val levels = HashMap<String, Double>()
        val now = System.currentTimeMillis()
        for (L in c.legs.values) {
            val r = stats(L.pc) ?: continue
            val byTrack = L.recv.associateBy { it.track?.id() }
            for (s in r.statsMap.values) {
                val m = s.members
                if (s.type == "inbound-rtp" || s.type == "outbound-rtp") {
                    val src = if (s.type == "outbound-rtp") "me" else host.diagAlias(byTrack[m["trackIdentifier"]?.toString()]?.owner)
                    val mm = if (s.type == "outbound-rtp") CallDiag.withSource(m, (m["mediaSourceId"] as? String)?.let { r.statsMap[it]?.members }) else m
                    host.diagSample(s.type, mm, src, L.role + "." + s.id, c.net?.rtt)
                }
                if (s.type == "candidate-pair" && m["nominated"] == true && m["state"] == "succeeded") {
                    dbl(m["currentRoundTripTime"])?.let {
                        rtt = maxOf(rtt ?: 0.0, it * 1000)
                        if (L.role != "audio") vrtt = maxOf(vrtt ?: 0.0, it * 1000)
                    }
                }
                if (s.type == "inbound-rtp") {
                    lost += num(m["packetsLost"])
                    recv += num(m["packetsReceived"])
                    bytes += num(m["bytesReceived"])
                    val rr = byTrack[m["trackIdentifier"]?.toString()]
                    if (m["kind"] == "video") {
                        fz += num(m["freezeCount"])
                        // need: Bytes kommen, aber kein Bild geht auf (FrameCryptor meldet mit failureTolerance -1 nichts)
                        val o = rr?.owner
                        if (o != null) {
                            val unread = num(m["bytesReceived"]) > 2000 && num(m["framesDecoded"]) == 0L
                            if (unread) {
                                val t0 = unreadSince.getOrPut(o) { now }
                                if (now - t0 >= 2000 && now - (needAt[o] ?: 0) >= 5000) {
                                    needAt[o] = now
                                    ack(o, peerKeys[o]?.newest ?: -1, peerKeys[o]?.session, false)
                                }
                            } else unreadSince.remove(o)
                        }
                    } else if (m["kind"] == "audio") {
                        val o = rr?.owner
                        val lv = dbl(m["audioLevel"])
                        if (o != null && lv != null) levels[o] = maxOf(levels[o] ?: 0.0, lv)
                    }
                }
            }
        }
        val p = c.q5 ?: longArrayOf(lost, recv, fz)
        val dl = lost - p[0]
        val dr = recv - p[1]
        val loss = if (dl + dr > 0) maxOf(0L, dl).toDouble() / (dl + dr) else 0.0      // 3 s: nur fuer die UDP-Regel
        c.q5 = longArrayOf(lost, recv, fz)
        c.q5t = now
        if (rtt == null && c.rtts.isNotEmpty()) rtt = HornLogic.median(c.rtts)
        // Anzeige: gleitendes 30-s-Fenster, Wechsel erst nach zwei gleichen Messungen (Analyse 1.11.)
        val w = c.gauge.push(now, lost, recv, fz)
        c.net = HornLogic.Net(rtt?.let { Math.round(it).toDouble() }, Math.round(w.loss * 1000) / 1000.0, Math.round(w.freezes * 10) / 10.0)
        val before = c.q
        val raw = HornLogic.quality(c.net)
        c.q = c.shown?.push(raw) ?: raw.also { c.shown = CallDiag.Debounce(it) }   // erste Messung sofort
        if (cur === c && c.q != before) {
            val n = c.net
            val d = "${before.ifEmpty { "-" }} -> ${c.q} rtt ${n?.rtt?.toInt() ?: 0} verlust ${n?.loss ?: 0.0} fz/min ${n?.freezes ?: 0.0}"
            host.diagEvent("quality", d)
            log("qualitaet $d")
        }
        c.bytesIn = bytes
        // A1: Senderate auf TCP/TLS nach der RTT der Bild-Leitung (Ton laeuft getrennt)
        c.gov?.add(now, vrtt ?: rtt)?.let { tx ->
            val d = tx.line(c.path)
            if (cur === c) host.diagEvent("tx", d)
            log("senderate $d")
            tune(c)
        }
        // Sprechring (Pegel der Tonplaetze), 600 ms Nachlauf; stumme Geraete nie
        levelsNow.clear()
        for ((d, lv) in levels) if (st[d]?.first != false) levelsNow[d] = lv
        levelsAt = now
        for ((d, lv) in levels) if (lv > 0.03 && st[d]?.first != false) speakAt[d] = now
        speaking.clear()
        for ((d, t) in speakAt) if (now - t < 3600) speaking.add(d)
        if (cur === c && !switching) {
            val udpOk = c.path != "udp" && c.cand.udp && meas[c.node]?.udp != null
            sw?.udp(now, loss, udpOk)?.let { x ->
                if (x.path != c.path && (x.path != "udp" || c.cand.tokens.isNotEmpty())) switch(x.why, path = x.path)
            }
        }
        host.changed()
    }

    // ---------------------------------------------------------------- Teilnehmerliste, Schluessel

    /** Teilnehmerliste geaendert: Schluesselplan (Beitritt: Ratsche; Abgang: neuer Schluessel), Zuordnung, Siegel */
    fun roster() {
        if (closed) return
        val me = host.myDev()
        val now = System.currentTimeMillis()
        val devs = host.legs().map { it.device }.filter { it != me }.toSet()
        val add = devs.filter { it !in known }
        val gone = known.filter { it !in devs }
        for (d in gone) {
            known.remove(d)
            st.remove(d)
            peerKeys.remove(d)
            speaking.remove(d)
        }
        if (gone.isNotEmpty()) {
            rekeyJob?.cancel()
            rekeyJob = scope.launch {
                delay(500)                                   // Abgaenge binnen 0,5 s: ein Wechsel
                rekey()
            }
        }
        if (add.isNotEmpty()) {
            // jeder Beitritt: die anderen Sender ratschen (Plan vorruecken)
            if (started) for ((d, p) in peerKeys) if (d !in add) {
                p.join(now)
                applyPlan(d)
            }
            known.addAll(add)
            val g = if (started) own.join() else own.newest
            if (started) {
                provider.setKey(me, g.kid, g.key)
                if (own.pending == null) setSenderIndex(g.kid)
            }
            started = true
            val body = fkeyBody(g)
            scope.launch {
                host.sendSignals(add.associateWith { body })
                stateOut(add)
            }
        }
        mapAll()
        runesSoon()
    }

    /** Abgang bzw. 6 h: neuer Zufallsschluessel an alle Verbliebenen, Umschalten nach 1,5 s (frueher, wenn alle bestaetigt) */
    private fun rekey() {
        if (closed) return
        val g = own.leave(System.currentTimeMillis(), known.toSet())
        provider.setKey(host.myDev(), g.kid, g.key)
        val body = fkeyBody(g)
        val to = known.toList()
        scope.launch { if (to.isNotEmpty()) host.sendSignals(to.associateWith { body }) }
        switchJob?.cancel()
        switchJob = scope.launch {
            delay(HornE2E.SWITCH_MS.toLong())
            own.due(System.currentTimeMillis())?.let { switchOwn(it) }
        }
    }

    private fun switchOwn(g: HornLogic.Gen) {
        setSenderIndex(g.kid)
    }

    private fun setSenderIndex(kid: Int) {
        for (c in conns()) for (L in c.legs.values) for (s in L.senders) try {
            s.setKeyIndex(kid)
        } catch (_: Exception) {
        }
    }

    private fun fkeyBody(g: HornLogic.Gen): String = buildJsonObject {
        put("t", "fkey")
        put("n", g.n)
        put("kid", g.kid)
        put("key", Base64.getEncoder().encodeToString(g.key))
        put("s", own.session)
    }.toString()

    /** Signal im Knoten-Anruf (versiegelt, geprueft): fkey, fkey-ack, state */
    fun signal(from: String, b: JsonObject) {
        if (closed) return
        val now = System.currentTimeMillis()
        when ((b["t"] as? JsonPrimitive)?.contentOrNull) {
            "fkey" -> {
                val k = HornE2E.parseFKey(b) ?: return
                val p = peerKeys.getOrPut(from) { HornLogic.PeerKeys() }
                if (p.fkey(k.n, k.key, k.s, now)) applyPlan(from)
                ack(from, k.n, k.s, true)
                mapAll()
            }
            "fkey-ack" -> {
                val n = (b["n"] as? JsonPrimitive)?.intOrNull ?: return
                val s = (b["s"] as? JsonPrimitive)?.contentOrNull
                val ok = (b["ok"] as? JsonPrimitive)?.booleanOrNull == true
                if (ok) {
                    if (s != own.session) return
                    if (own.ack(from, n)) own.due(now, force = true)?.let { switchOwn(it) }
                } else if (from in known) {
                    // Nachforderung: nur den neuesten Schluessel (nie einen aelteren)
                    val body = fkeyBody(own.newest)
                    scope.launch { host.sendSignals(mapOf(from to body)) }
                }
            }
            "state" -> {
                st[from] = Triple(
                    (b["mic"] as? JsonPrimitive)?.booleanOrNull != false,
                    (b["cam"] as? JsonPrimitive)?.booleanOrNull == true,
                    (b["screen"] as? JsonPrimitive)?.booleanOrNull == true,
                )
                HornLogic.parseHp(b)?.let { hp -> if (hpx[from] != hp) { hpx[from] = hp; mapAll() } }   // P9/P10
                bind()
            }
        }
    }

    /** fkey-ack gebuendelt (<= 100 ms) */
    private fun ack(to: String, n: Int, s: String?, ok: Boolean) {
        acks[to] = buildJsonObject {
            put("t", "fkey-ack")
            put("n", n)
            put("s", s.orEmpty())
            put("ok", ok)
        }.toString()
        if (ackJob == null) ackJob = scope.launch {
            delay(100)
            ackJob = null
            val b = HashMap(acks)
            acks.clear()
            host.sendSignals(b)
        }
    }

    /** Eigener Zustand an alle (gebuendelt, 300 ms) bzw. nur an neue Geraete */
    fun stateOut(only: Collection<String>? = null) {
        if (closed) return
        val (mic, cam, screen) = host.myState()
        val body = buildJsonObject {
            put("t", "state")
            put("mic", mic)
            put("cam", cam)
            put("screen", screen)
            val hp = myRefs()                                       // P9/P10: eigene Kennungen an den Knoten
            if (hp.isNotEmpty()) put("hp", JsonArray(hp.map { JsonPrimitive(it) }))
        }.toString()
        if (only != null) {
            scope.launch { host.sendSignals(only.filter { it in known }.associateWith { body }) }
            return
        }
        stateJob?.cancel()
        stateJob = scope.launch {
            delay(300)
            host.sendSignals(known.associateWith { body })
        }
    }

    /** Runensiegel neu (Teilnehmerliste geaendert) */
    private fun runesSoon() {
        scope.launch {
            val legs = host.legs()
            val key = legs.map { it.device }.sorted().joinToString(",")
            if (key == runesKey && runes != null) return@launch
            val pks = legs.map { it.device to host.signPk(it.device) }
            if (closed) return@launch
            unverified = pks.filter { it.second == null }.map { it.first }
            runesKey = key
            runes = HornE2E.runeSeal(callId, pks.mapNotNull { it.second })
            host.changed()
        }
    }

    // ---------------------------------------------------------------- 2.5 Uebergabe: Ton-Bein neu (promote)

    /**
     * 2.5 (handover2, L3): Ton-Bein der aktuellen Verbindung mit frischen Token neu aufbauen (nach POST /promote: mit
     * Ton-Recht) - am selben Knoten und Weg, Rolle `audio` bzw. bei UDP `both`. Gleiche leg_ref -> der Knoten ersetzt das
     * alte Bein (dessen `closed {why: "ersetzt"}` ist kein Verbindungsverlust). Das alte Bein schliesst hier erst, wenn das
     * neue verbunden ist (<= 8 s); klappt das nicht, bleibt das alte. cands = Kandidaten der promote-Antwort (sonst frisch).
     * -> true = neues Bein steht (bzw. nichts zu tun), false = Fehler (altes Bein behalten).
     */
    suspend fun reAudio(cands: List<HornLogic.Cand>): Boolean {
        val conn = cur ?: return false
        if (closed || conn.dead) return false
        val role = Handover.audioRole(conn.legs.keys) ?: return false
        val old = conn.legs[role] ?: return false
        var L: Leg? = null
        try {
            val cand = cands.firstOrNull { it.node == conn.node && it.tokens[role] != null } ?: fresh(conn.cand)
            conn.cand = cand
            var turn: Pair<String, String>? = null
            if (conn.path == "turn") {
                val r = nodeHttp(cand.url + "/api/sfu/turn", buildJsonObject { put("token", cand.tokens["audio"].orEmpty()) })
                val o = kotlinx.serialization.json.Json.parseToJsonElement(r) as? JsonObject
                turn = ((o?.get("username") as? JsonPrimitive)?.contentOrNull ?: "") to ((o?.get("credential") as? JsonPrimitive)?.contentOrNull ?: "")
            }
            if (closed || conn.dead) return false
            old.replaced = true
            try {
                leg(conn, role, conn.path, turn, pend = true)   // setzt conn.legs[role] = neues Bein (pend: kein Verlust)
            } finally {
                L = conn.legs[role]?.takeIf { it !== old }
            }
            val nl = L ?: throw IOException("bein")
            val t0 = System.currentTimeMillis()
            while (!closed && !conn.dead && System.currentTimeMillis() - t0 < Handover.REAUDIO_MS * testWaitScale) {
                val st = nl.state
                if (st == PeerConnection.PeerConnectionState.CONNECTED || st == PeerConnection.PeerConnectionState.FAILED ||
                    st == PeerConnection.PeerConnectionState.CLOSED) break
                delay(100)
            }
            nl.pend = false
            if (nl.state != PeerConnection.PeerConnectionState.CONNECTED || closed || conn.dead) throw IOException("audio ${nl.state}")
            dropLeg(conn, old)
            if (conn.ws == null) conn.ws = nl.ws
            syncTracks(conn)
            mapAll()
            bind()
            stateOut()
            log("ton-bein ersetzt (${conn.node} $role)")
            host.changed()
            return true
        } catch (e: Exception) {
            // auch CancellationException aus leg() (Verbindung verworfen): altes Bein behalten, Aufrufer nimmt zurueck
            log("ton-bein neu: ${e.message}")
            if (!closed) restoreLeg(conn, role, old, L)
            return false
        }
    }

    /** neues Bein ging nicht: altes behalten (der Knoten hat es nur ersetzt, wenn das neue angenommen wurde) */
    private fun restoreLeg(conn: Conn, role: String, old: Leg, nl: Leg?) {
        if (nl != null && nl !== old) closeLeg(conn, nl)
        if (!conn.dead) conn.legs[role] = old
        old.replaced = false
        if (!conn.dead && old.ws == null && old.id != null) events(conn, old, true)
        connState(conn)
    }

    /** Ersetztes Bein schliessen (ohne DELETE am Knoten - die leg_ref gehoert jetzt dem neuen Bein) */
    private fun dropLeg(conn: Conn, L: Leg) {
        L.replaced = true
        closeLeg(conn, L, delete = false)
    }

    private fun closeLeg(conn: Conn, L: Leg, delete: Boolean = true) {
        try {
            L.ws?.cancel()
        } catch (_: Exception) {
        }
        if (conn.ws === L.ws) conn.ws = null
        L.ws = null
        if (delete) L.id?.let { id -> scope.launch { try { nodeHttp(conn.cand.url + "/api/sfu/leg/" + id, null, "DELETE") } catch (_: Exception) {} } }
        for (r in L.recv) {
            r.scrubJob?.cancel()
            try { r.cryptor?.dispose() } catch (_: Exception) {}
        }
        for (x in L.senders) try { x.dispose() } catch (_: Exception) {}
        val gone = L.vid.mapNotNull { it.track }.toSet()
        for ((d, t) in bound.entries.toList()) if (gone.any { it === t }) bound.remove(d)
        if (gone.any { it === bigBound }) bigBound = null
        try {
            L.pc.close()
            L.pc.dispose()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- Eigene Medien

    /** Mikrofon an/aus bzw. Systemton: Spur an allen Ton-Sendern */
    fun micChanged() {
        val a = host.audioTrack()
        for (c in conns()) for (L in c.legs.values) L.tr?.let { if (it.sender.track() !== a) it.sender.setTrack(a, false) }
        stateOut()
    }

    /** Kamera an/aus: v = neue Spur (null: aus - VOR dem Freigeben der alten Spur aufrufen) */
    fun camChanged(v: VideoTrack?) {
        for (c in conns()) for (L in c.legs.values) L.cam?.sender?.setTrack(if (c === cur) v else null, false)
        cur?.let { tune(it) }
        stateOut()
        host.changed()
    }

    /** A1: Sendeparameter neu abstimmen (Kamera gewechselt, Format geaendert) */
    fun retune() {
        if (!closed) cur?.let { tune(it) }
    }

    /** Bildschirm ueber den Knoten: eigener Sender je Bild-Leitung */
    fun screenChanged() {
        val s = host.screenTrack()
        cur?.legs?.values?.forEach { L -> L.scr?.sender?.setTrack(s, false) }
        cur?.let { tune(it) }                                       // A1: beim Teilen auf TCP/TLS Kamera nur l
        micChanged()
        host.changed()
    }

    /** Knoten wird geleert/entfernt (call.horn_move): neue Kandidaten, make-before-break */
    fun moveAway() = switch("draining")

    /** Daten fuer die Anzeige */
    val path: String get() = cur?.path.orEmpty()
    val node: String get() = cur?.node.orEmpty()
    val net: HornLogic.Net? get() = cur?.net
    val q: String get() = cur?.q.orEmpty()
    val changing: Boolean get() = next != null || olds.isNotEmpty()
    val connected: Boolean get() = cur != null
    val bytesIn: Long get() = cur?.bytesIn ?: 0
}
