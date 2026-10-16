package io.github.yggdrasil_sh.saga.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatFormat.unb64
import io.github.yggdrasil_sh.saga.chat.LocalChatKeys
import io.github.yggdrasil_sh.saga.chat.TofuResult
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.live.LiveEvent
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CallPhase { INCOMING, PREPARING, RINGING, CONNECTING, CONNECTED, ENDED }

/** Zustand fuer die Anruf-Oberflaeche (CallActivity) */
data class CallUiState(
    val phase: CallPhase,
    val id: String?,
    val chatId: Long,
    val caller: Boolean,
    val video: Boolean,
    val peer: CallPeer,
    val mic: Boolean = true,
    val cam: Boolean = false,
    val speaker: Boolean = false,
    val remoteVideo: Boolean = false,
    val remoteMic: Boolean = true,
    /** DTLS-Zertifikat des Gegenuebers stimmt mit dem signierten Fingerabdruck ueberein */
    val verified: Boolean = false,
    val path: String = "",
    val quality: String = "",
    val connectedAt: Long = 0,
    val bytesIn: Long = 0,
    val reconnecting: Boolean = false,
    val message: UiText? = null,
    val error: Boolean = false,
)

/** VideoSink, dessen Ziel (SurfaceViewRenderer der Oberflaeche) wechseln darf */
class ProxySink : VideoSink {
    @Volatile var target: VideoSink? = null
    override fun onFrame(frame: VideoFrame) {
        target?.onFrame(frame)
    }
}

/**
 * 1:1-Anrufe (Server 2.1, saga-call/v1) mit WebRTC - Ablauf wie static/calls.js: Signale (SDP, ICE, Zustand) mit dem
 * Chat-Geraeteschluessel signiert und fuer das Zielgeraet versiegelt, ueber /api/calls und den Live-Kanal; Pruefungen
 * beim Empfang (Entsiegeln, Geraetezertifikat + TOFU, Signatur mit MEINEM Geraet als Ziel, fp == SDP, DTLS-Zertifikat
 * nach dem Verbinden). Immer zwei Transceiver (Audio + Video, sendrecv), Kamera an/aus per replaceTrack.
 * Alles laeuft auf dem Haupt-Thread (scope); WebRTC-Rueckrufe springen dorthin.
 */
class CallManager(private val repo: SagaRepository, private val app: Context, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val api get() = repo.api

    private val _ui = MutableStateFlow<CallUiState?>(null)
    val ui: StateFlow<CallUiState?> = _ui

    /** Anruf-Funktion des Servers (GET /api/calls/config): null = unbekannt */
    val enabled = MutableStateFlow<Boolean?>(null)

    val localSink = ProxySink()
    val remoteSink = ProxySink()

    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private val crypto by lazy { CallCrypto(repo.chat.crypto) }

    private var cur: Call? = null
    private var ring: CallInfo? = null
    private var ringJob: Job? = null
    private val gone = HashSet<String>()
    private var config: CallConfig? = null
    private var tone: ToneGenerator? = null

    private class PeerDev(val enc: ByteArray, val sign: ByteArray)

    private inner class Call(var id: String?, val chatId: Long, val caller: Boolean, var media: String, var peer: CallPeer) {
        var peerDevs: Map<String, PeerDev> = emptyMap()
        var peerDevice: String? = null
        var callerDevice: String? = null
        var pc: PeerConnection? = null
        var audioSource: AudioSource? = null
        var audioTrack: AudioTrack? = null
        var videoSource: VideoSource? = null
        var videoTrack: VideoTrack? = null
        var capturer: CameraVideoCapturer? = null
        var surface: SurfaceTextureHelper? = null
        var remoteVideo: VideoTrack? = null
        val seen = HashSet<Long>()
        val early = ArrayList<CallSignalDto>()
        val remoteIce = ArrayList<IceCandidate>()
        val outIce = ArrayList<JsonObject>()
        var outJob: Job? = null
        var ready = false
        var connected = false
        var fps: List<String> = emptyList()
        var t0 = 0L
        var jobs = ArrayList<Job>()
        var connJob: Job? = null
        var done = false
        var ending = false
        var gotState = false
        var lastLost = 0L
        var lastRecv = 0L
    }

    init {
        scope.launch { repo.live.events.collect { onEvent(it) } }
        // Live-Kanal (wieder) verbunden: klingelt gerade etwas fuer mich? (App aus dem Hintergrund, nach Neustart)
        scope.launch { repo.live.connected.collect { if (it) pending() } }
    }

    private fun log(msg: String) {
        if (BuildConfig.DEBUG) Log.i("SagaCall", msg)
    }

    // ---------------------------------------------------------------- Konfiguration, Schluessel

    private suspend fun loadConfig(): CallConfig {
        val c = json.decodeFromString(CallConfig.serializer(), api.get("api/calls/config"))
        config = c
        enabled.value = c.enabled
        return c
    }

    /** Anrufknoepfe im Direkt-Chat: Server kann Anrufe (still, ohne Fehler) */
    suspend fun checkEnabled() {
        try {
            loadConfig()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404 || e.code == 403) enabled.value = false
        } catch (_: Exception) {
        }
    }

    private fun keys(): LocalChatKeys = repo.chat.keys() ?: throw UiTextException(UiText.Res(R.string.call_chats_not_ready))

    /** Aktive Geraete des Gegenuebers: Zertifikat gueltig, Identitaet wie gemerkt (TOFU - geaendert => nicht anrufen) */
    private suspend fun peerKeys(c: Call) {
        val peer = c.peer.username
        val o = Json.parseToJsonElement(api.get("api/chat/keys?users=" + java.net.URLEncoder.encode(peer, "UTF-8"))) as? JsonObject
        val u = (o?.get("users") as? JsonObject)?.get(peer) as? JsonObject
            ?: throw UiTextException(UiText.Res(R.string.call_peer_no_keys, c.peer.name.ifEmpty { peer }))
        val ip = (u["ident_pub"] as? JsonPrimitive)?.contentOrNull ?: throw UiTextException(UiText.Res(R.string.call_peer_no_keys, c.peer.name.ifEmpty { peer }))
        val store = repo.chat.store
        val changed = withContext(Dispatchers.IO) { store.observe(peer, ip) == TofuResult.CHANGED || store.known()[peer]?.changed == true }
        if (changed) throw UiTextException(UiText.Res(R.string.call_safety_changed, c.peer.name.ifEmpty { peer }))
        val ipk = unb64(ip, 32)
        val cc = repo.chat.crypto
        val out = LinkedHashMap<String, PeerDev>()
        withContext(Dispatchers.Default) {
            for (d in (u["devices"] as? JsonArray).orEmpty()) {
                val x = d as? JsonObject ?: continue
                if ((x["revoked"] as? JsonPrimitive)?.booleanOrNull == true) continue
                val id = (x["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val enc = (x["enc_pub"] as? JsonPrimitive)?.contentOrNull
                val sign = (x["sign_pub"] as? JsonPrimitive)?.contentOrNull
                if (!cc.verifyDevice(peer, ipk, id, enc, sign, (x["sig"] as? JsonPrimitive)?.contentOrNull)) continue
                out[id] = PeerDev(unb64(enc, 32) ?: continue, unb64(sign, 32) ?: continue)
            }
        }
        c.peerDevs = out
    }

    private fun seal(c: Call, toDev: String, body: String): String {
        val k = keys()
        val pd = c.peerDevs[toDev] ?: throw UiTextException(UiText.Res(R.string.call_devices_changed))
        return crypto.sealSignal(c.id!!, k.uid, k.dev, c.peer.username, toDev, pd.enc, body)
    }

    private fun sealAll(c: Call, devs: Collection<String>, body: String): JsonObject = buildJsonObject {
        for (d in devs) put(d, seal(c, d, body))
    }

    /** Signal oeffnen und pruefen -> (Absendergeraet, Inhalt) oder null (Sicherheitspruefung fehlgeschlagen) */
    private suspend fun openSig(c: Call, s: CallSignalDto): JsonObject? {
        val k = keys()
        if (c.peerDevs[s.fromDevice] == null) {
            try {
                peerKeys(c)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        }
        val pd = c.peerDevs[s.fromDevice] ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val o = crypto.openSignal(s.sealed, k.dev.encPk, k.dev.encSk)
                if (!crypto.verifySignal(c.id!!, c.peer.username, s.fromDevice, k.uid, k.dev.id, o.body, o.sig, pd.sign)) return@withContext null
                val b = Json.parseToJsonElement(o.body) as? JsonObject ?: return@withContext null
                JsonObject(b + ("_from" to JsonPrimitive(s.fromDevice)))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.fp(): List<String> = (this["fp"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    // ---------------------------------------------------------------- WebRTC

    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(Build.VERSION.SDK_INT >= 29)
            .setUseHardwareNoiseSuppressor(Build.VERSION.SDK_INT >= 29)
            .createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    private fun startCamera(c: Call): Boolean {
        val f = factory()
        val en = Camera2Enumerator(app)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return false
        val cap = en.createCapturer(name, null) ?: return false
        val src = c.videoSource ?: f.createVideoSource(false).also { c.videoSource = it }
        val sth = c.surface ?: SurfaceTextureHelper.create("saga-cam", egl.eglBaseContext).also { c.surface = it }
        cap.initialize(sth, app, src.capturerObserver)
        cap.startCapture(1280, 720, 30)
        c.capturer = cap
        val t = c.videoTrack ?: f.createVideoTrack("saga-v", src).also { c.videoTrack = it }
        t.setEnabled(true)
        t.addSink(localSink)
        return true
    }

    private fun stopCamera(c: Call) {
        try {
            c.capturer?.stopCapture()
        } catch (_: Exception) {
        }
        c.capturer?.dispose()
        c.capturer = null
        c.videoTrack?.removeSink(localSink)
        c.videoTrack?.dispose()
        c.videoTrack = null
    }

    private fun localMedia(c: Call, video: Boolean) {
        val f = factory()
        val src = f.createAudioSource(MediaConstraints())
        c.audioSource = src
        c.audioTrack = f.createAudioTrack("saga-a", src)
        if (video && !startCamera(c)) repo.notify(UiText.Res(R.string.call_no_camera))
        audioOn(c.videoTrack != null)
    }

    private fun makePc(c: Call, cfg: CallConfig): PeerConnection {
        val ice = cfg.iceServers.filter { it.urlList.isNotEmpty() }.map {
            PeerConnection.IceServer.builder(it.urlList).setUsername(it.username.orEmpty()).setPassword(it.credential.orEmpty()).createIceServer()
        }
        val rtc = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceTransportsType = if (cfg.relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val obs = object : PeerConnection.Observer {
            override fun onIceCandidate(cand: IceCandidate) {
                scope.launch {
                    if (cur !== c) return@launch
                    c.outIce.add(buildJsonObject {
                        put("candidate", cand.sdp)
                        put("sdpMid", cand.sdpMid)
                        put("sdpMLineIndex", cand.sdpMLineIndex)
                    })
                    if (c.outJob == null) c.outJob = scope.launch {
                        delay(120)
                        c.outJob = null
                        flushIce(c)
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val t = transceiver.receiver.track()
                scope.launch { if (cur === c && t is VideoTrack) attachRemote(c, t) }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val t = receiver.track()
                scope.launch { if (cur === c && t is VideoTrack) attachRemote(c, t) }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch { onConn(c, newState) }
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(cands: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }
        return factory().createPeerConnection(rtc, obs) ?: throw UiTextException(UiText.Res(R.string.call_failed))
    }

    private fun attachRemote(c: Call, t: VideoTrack) {
        if (c.remoteVideo === t) return
        c.remoteVideo?.removeSink(remoteSink)
        c.remoteVideo = t
        t.addSink(remoteSink)
        update(c)
    }

    private fun videoSender(c: Call): RtpSender? =
        c.pc?.transceivers?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.sender

    private fun addLocal(c: Call, pc: PeerConnection, fromOffer: Boolean) {
        val streams = listOf("saga")
        if (!fromOffer) {
            val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, streams)
            pc.addTransceiver(c.audioTrack, init)
            // immer: Kamera spaeter ohne neues Angebot (replaceTrack)
            if (c.videoTrack != null) pc.addTransceiver(c.videoTrack, init)
            else pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
            return
        }
        for (tr in pc.transceivers) {
            try {
                tr.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            } catch (_: Exception) {
            }
            when (tr.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> tr.sender.setTrack(c.audioTrack, false)
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> if (c.videoTrack != null) tr.sender.setTrack(c.videoTrack, false)
                else -> {}
            }
            try {
                tr.sender.setStreams(streams)
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun createSdp(pc: PeerConnection, offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {
                if (cont.isActive) cont.resume(d)
            }

            override fun onCreateFailure(e: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(e ?: "sdp"))
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }
        if (offer) pc.createOffer(obs, MediaConstraints()) else pc.createAnswer(obs, MediaConstraints())
    }

    private suspend fun setSdp(pc: PeerConnection, local: Boolean, d: SessionDescription) = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onSetFailure(e: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(e ?: "sdp"))
            }
        }
        if (local) pc.setLocalDescription(obs, d) else pc.setRemoteDescription(obs, d)
    }

    private fun applyRemoteIce(c: Call) {
        val pc = c.pc ?: return
        if (pc.remoteDescription == null) return
        val list = ArrayList(c.remoteIce)
        c.remoteIce.clear()
        for (x in list) pc.addIceCandidate(x)
    }

    private fun queueIce(c: Call, b: JsonObject) {
        for (e in (b["c"] as? JsonArray).orEmpty().take(60)) {
            val x = e as? JsonObject ?: continue
            val cand = x.s("candidate")?.takeIf { it.isNotEmpty() } ?: continue
            c.remoteIce.add(IceCandidate(x.s("sdpMid") ?: "0", (x["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0, cand))
        }
    }

    // ---------------------------------------------------------------- Signale

    private suspend fun sendSignal(c: Call, body: JsonObject) {
        val id = c.id ?: return
        if (c.ending) return
        val devs = CallLogic.targets(c.peerDevice, c.peerDevs.keys)
        if (devs.isEmpty()) return
        try {
            val sig = withContext(Dispatchers.Default) { sealAll(c, devs, body.toString()) }
            api.post("api/calls/$id/signal", buildJsonObject {
                put("device", keys().dev.id)
                put("signals", sig)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 409) remoteEnded(c, "gone")
        } catch (_: Exception) {
        }
    }

    private suspend fun flushIce(c: Call) {
        if (c.id == null || !c.ready || c.outIce.isEmpty()) return
        val all = ArrayList(c.outIce)
        c.outIce.clear()
        for (batch in CallLogic.iceBatches(all)) {
            sendSignal(c, buildJsonObject {
                put("t", "ice")
                put("c", JsonArray(batch))
            })
        }
    }

    private suspend fun stateOut(c: Call) {
        if (!c.ready) return
        sendSignal(c, buildJsonObject {
            put("t", "state")
            put("mic", c.audioTrack?.enabled() ?: false)
            put("cam", c.videoTrack != null)
            put("screen", false)
        })
    }

    private suspend fun handle(c: Call, b: JsonObject?) {
        if (b == null) {
            securityFail(c)
            return
        }
        when (b.s("t")) {
            "ice" -> {
                queueIce(c, b)
                applyRemoteIce(c)
            }
            "answer" -> {
                val pc = c.pc ?: return
                if (!c.caller || pc.remoteDescription != null) return
                val from = b.s("_from")
                if (c.peerDevice != null && from != c.peerDevice) return
                val sdp = b.s("sdp")
                if (!CallCrypto.fpMatches(sdp, b.fp())) {
                    securityFail(c)
                    return
                }
                c.peerDevice = from                         // ab jetzt Signale nur an das annehmende Geraet
                c.fps = CallCrypto.sdpFingerprints(sdp)
                try {
                    setSdp(pc, false, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    applyRemoteIce(c)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(c, UiText.Res(R.string.call_failed))
                }
            }
            "state" -> {
                c.gotState = true
                val st = _ui.value ?: return
                _ui.value = st.copy(
                    remoteMic = (b["mic"] as? JsonPrimitive)?.booleanOrNull != false,
                    remoteVideo = c.remoteVideo != null && ((b["cam"] as? JsonPrimitive)?.booleanOrNull == true || (b["screen"] as? JsonPrimitive)?.booleanOrNull == true),
                )
            }
        }
    }

    private fun signalIn(callId: String, s: CallSignalDto) {
        val c = cur ?: return
        if (callId != c.id || s.toDevice != repo.chat.keys()?.dev?.id || !c.seen.add(s.seq)) return
        if (!c.ready) {
            c.early.add(s)
            return
        }
        scope.launch {
            val b = openSig(c, s)
            if (cur === c) handle(c, b)
        }
    }

    // ---------------------------------------------------------------- Anrufen

    /** Aus dem Direkt-Chat anrufen (Berechtigungen hat die Oberflaeche schon geholt) */
    fun start(chatId: Long, peer: CallPeer, video: Boolean) {
        if (cur != null || ring != null) {
            repo.notify(UiText.Res(R.string.call_already))
            return
        }
        val c = Call(null, chatId, true, if (video) "video" else "audio", peer)
        cur = c
        _ui.value = CallUiState(CallPhase.PREPARING, null, chatId, true, video, peer, cam = video, speaker = video, message = UiText.Res(R.string.call_media))
        CallActivity.open(app)
        scope.launch {
            try {
                keys()
                val cfg = loadConfig()
                if (!cfg.enabled) throw UiTextException(UiText.Res(R.string.call_disabled))
                peerKeys(c)
                if (c.peerDevs.isEmpty()) throw UiTextException(UiText.Res(R.string.call_peer_no_keys, peer.name.ifEmpty { peer.username }))
                if (cur !== c) return@launch
                localMedia(c, video)
                c.media = if (c.videoTrack != null) "video" else "audio"
                CallService.start(app, c.videoTrack != null)
                val pc = makePc(c, cfg)
                c.pc = pc
                addLocal(c, pc, false)
                val offer = createSdp(pc, true)
                setSdp(pc, true, offer)
                update(c)
                dial(c, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cur === c) fail(c, if (e is UiTextException) e.text else repo.friendly(e))
            }
        }
    }

    private suspend fun dial(c: Call, again: Boolean) {
        c.id = c.id ?: ChatFormat.hex(repo.chat.crypto.random(16))
        val sdp = c.pc?.localDescription?.description ?: throw UiTextException(UiText.Res(R.string.call_failed))
        val body = buildJsonObject {
            put("t", "offer")
            put("sdp", sdp)
            put("media", c.media)
            put("fp", buildJsonArray { CallCrypto.sdpFingerprints(sdp).forEach { add(JsonPrimitive(it)) } })
        }.toString()
        update(c, UiText.Res(R.string.call_connecting))
        try {
            val sig = withContext(Dispatchers.Default) { sealAll(c, c.peerDevs.keys, body) }
            api.post("api/calls", buildJsonObject {
                put("id", c.id)
                put("chat_id", c.chatId)
                put("media", c.media)
                put("device", keys().dev.id)
                put("signals", sig)
            })
        } catch (e: ApiException) {
            val o = try {
                Json.parseToJsonElement(e.body) as? JsonObject
            } catch (_: Exception) {
                null
            }
            when {
                e.code == 409 && o?.containsKey("devices") == true && !again -> {
                    peerKeys(c)
                    dial(c, true)
                    return
                }
                e.code == 409 && (o?.get("busy") as? JsonPrimitive)?.booleanOrNull == true -> {
                    c.id = null
                    finish(c, UiText.Res(R.string.call_busy_peer, c.peer.name), false)
                    return
                }
                e.code == 429 -> {
                    c.id = null
                    throw UiTextException(UiText.Res(R.string.call_rate))
                }
                else -> {
                    c.id = null
                    throw e
                }
            }
        }
        if (cur !== c) {
            quietPost("api/calls/${c.id}/end", buildJsonObject { put("reason", "hangup") })
            return
        }
        c.ready = true
        update(c)
        tone(ToneGenerator.TONE_SUP_RINGTONE)
        flushIce(c)
        startLoops(c)
    }

    // ---------------------------------------------------------------- Klingeln, Annehmen

    private fun pending() {
        if (repo.needsLogin.value || repo.chat.keys() == null) return
        scope.launch {
            try {
                val r = json.decodeFromString(CallsActive.serializer(), api.get("api/calls/active"))
                for (x in r.calls) if (x.role == "callee" && x.state == "ringing") checkRing(x.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Mitteilung (UnifiedPush, Art "call") bzw. Live-Ereignis: erst den Zustand pruefen, dann klingeln */
    fun onRing(callId: String?): Job? {
        if (!CallLogic.validId(callId)) return null
        return scope.launch { checkRing(callId!!) }
    }

    private suspend fun checkRing(callId: String) {
        if (callId in gone || cur?.id == callId || ring?.id == callId) return
        val info = try {
            json.decodeFromString(CallState.serializer(), api.get("api/calls/$callId")).call
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (info.state != "ringing" || info.role != "callee" || callId in gone || ring?.id == callId) return
        if (cur != null || ring != null) {
            quietPost("api/calls/$callId/decline", buildJsonObject { put("reason", "busy") })       // telefoniere schon
            return
        }
        ring = info
        _ui.value = CallUiState(CallPhase.INCOMING, info.id, info.chatId, false, info.media == "video", info.peer)
        CallNotify.showIncoming(app, info)
        if (androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            CallActivity.open(app)
        }
        val left = ((info.expiresIn ?: (info.ringS ?: 45).toDouble()).coerceAtLeast(5.0) * 1000).toLong() + 3000
        ringJob?.cancel()
        ringJob = scope.launch {
            delay(left)
            unring(callId, UiText.Res(R.string.call_missed))
        }
    }

    private fun unring(id: String, msg: UiText? = null) {
        gone.add(id)
        if (ring?.id != id) return
        ring = null
        ringJob?.cancel()
        ringJob = null
        CallNotify.cancelIncoming(app)
        val st = _ui.value
        if (st != null && st.phase == CallPhase.INCOMING && st.id == id) {
            _ui.value = if (msg != null) st.copy(phase = CallPhase.ENDED, message = msg) else null
            if (msg != null) scope.launch {
                delay(2500)
                if (_ui.value?.phase == CallPhase.ENDED && _ui.value?.id == id) _ui.value = null
            }
        }
    }

    fun decline() {
        val r = ring ?: return
        unring(r.id)
        _ui.value = null
        quietPost("api/calls/${r.id}/decline", buildJsonObject { put("reason", "declined") })
    }

    /** Annehmen (Berechtigungen hat die Oberflaeche schon geholt); video = mit Kamera */
    fun accept(video: Boolean) {
        val r = ring ?: return
        unring(r.id)
        if (cur != null) {
            repo.notify(UiText.Res(R.string.call_already))
            return
        }
        val c = Call(r.id, r.chatId, false, r.media, r.peer)
        cur = c
        _ui.value = CallUiState(CallPhase.CONNECTING, r.id, r.chatId, false, r.media == "video", r.peer, cam = video, speaker = video,
            message = UiText.Res(R.string.call_connecting))
        scope.launch {
            try {
                val k = keys()
                val cfg = loadConfig()
                val st = json.decodeFromString(CallState.serializer(), api.get("api/calls/${r.id}?device=${k.dev.id}"))
                val info = st.call
                c.peer = info.peer
                c.media = info.media
                c.callerDevice = info.callerDevice
                if (info.role != "callee" || info.state != "ringing") {
                    throw UiTextException(UiText.Res(if (info.state == "active") R.string.call_answered_elsewhere else R.string.call_already_ended))
                }
                st.signals.forEach { c.seen.add(it.seq) }
                peerKeys(c)
                var offer: JsonObject? = null
                for (s in st.signals) {
                    val b = openSig(c, s)
                    if (b == null) {
                        securityFail(c)
                        return@launch
                    }
                    if (b.s("_from") != info.callerDevice) continue
                    when (b.s("t")) {
                        "offer" -> if (offer == null) offer = b
                        "ice" -> queueIce(c, b)
                    }
                }
                val sdp = offer?.s("sdp")
                if (offer == null || !CallCrypto.fpMatches(sdp, offer.fp())) {
                    securityFail(c)
                    return@launch
                }
                c.peerDevice = info.callerDevice
                c.fps = CallCrypto.sdpFingerprints(sdp)
                if (cur !== c) return@launch
                localMedia(c, video)
                CallService.start(app, c.videoTrack != null)
                val pc = makePc(c, cfg)
                c.pc = pc
                setSdp(pc, false, SessionDescription(SessionDescription.Type.OFFER, sdp))
                addLocal(c, pc, true)
                val ans = createSdp(pc, false)
                setSdp(pc, true, ans)
                val asdp = pc.localDescription?.description ?: ans.description
                val body = buildJsonObject {
                    put("t", "answer")
                    put("sdp", asdp)
                    put("fp", buildJsonArray { CallCrypto.sdpFingerprints(asdp).forEach { add(JsonPrimitive(it)) } })
                }.toString()
                val sig = withContext(Dispatchers.Default) { buildJsonObject { put(c.peerDevice!!, seal(c, c.peerDevice!!, body)) } }
                api.post("api/calls/${r.id}/answer", buildJsonObject {
                    put("device", k.dev.id)
                    put("signals", sig)
                })
                if (cur !== c) return@launch
                c.ready = true
                update(c, UiText.Res(R.string.call_connecting))
                applyRemoteIce(c)
                flushIce(c)
                startLoops(c)
                stateOut(c)
                val early = ArrayList(c.early)
                c.early.clear()
                for (s in early) {
                    val b = openSig(c, s)
                    if (cur === c) handle(c, b)
                }
                c.connJob = scope.launch {
                    delay(30_000)
                    if (cur === c && !c.connected) iceFailed(c)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (cur === c) fail(c, if (e.code == 409) UiText.Res(R.string.call_gone_or_elsewhere) else repo.friendly(e))
            } catch (e: Exception) {
                if (cur === c) fail(c, if (e is UiTextException) e.text else repo.friendly(e))
            }
        }
    }

    // ---------------------------------------------------------------- Verbindung, Pruefung, Qualitaet

    private fun onConn(c: Call, st: PeerConnection.PeerConnectionState) {
        if (cur !== c || c.pc == null) return
        log("connection $st")
        when (st) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                if (!c.connected) {
                    c.connected = true
                    c.t0 = System.currentTimeMillis()
                    stopTone()
                    c.connJob?.cancel()
                    scope.launch { stateOut(c) }
                    checkStats(c)
                }
                update(c, null, reconnecting = false)
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> update(c, UiText.Res(R.string.call_reconnecting), reconnecting = true)
            PeerConnection.PeerConnectionState.FAILED -> iceFailed(c)
            else -> {}
        }
    }

    private fun iceFailed(c: Call) {
        fail(c, UiText.Res(if (config?.hasTurn == true) R.string.call_no_turn else R.string.call_no_direct), failed = true)
    }

    private fun Any?.num(): Long = (this as? Number)?.toLong() ?: (this as? String)?.toLongOrNull() ?: 0L

    /** Stats: DTLS-Fingerabdruck des Gegenuebers gegen die SIGNIERTE SDP (Pflicht 5), Weg, Qualitaet, empfangene Bytes */
    private fun checkStats(c: Call) {
        val pc = c.pc ?: return
        pc.getStats { rep: RTCStatsReport ->
            scope.launch { if (cur === c && !c.done) onStats(c, rep) }
        }
    }

    private fun onStats(c: Call, rep: RTCStatsReport) {
        val byId = rep.statsMap
        val tr = byId.values.firstOrNull { it.type == "transport" && it.members["remoteCertificateId"] != null }
            ?: byId.values.firstOrNull { it.type == "transport" }
        val cert = (tr?.members?.get("remoteCertificateId") as? String)?.let { byId[it] }
        val ok = CallCrypto.certMatches(cert?.members?.get("fingerprintAlgorithm") as? String, cert?.members?.get("fingerprint") as? String, c.fps)
        if (ok == false) {
            securityFail(c)
            return
        }
        var pair = (tr?.members?.get("selectedCandidatePairId") as? String)?.let { byId[it] }
        if (pair == null) pair = byId.values.firstOrNull {
            it.type == "candidate-pair" && it.members["state"] == "succeeded" && (it.members["nominated"] == true || it.members["selected"] == true)
        }
        var path = _ui.value?.path.orEmpty()
        var rtt: Double? = null
        if (pair != null) {
            val l = byId[pair.members["localCandidateId"] as? String]
            val r = byId[pair.members["remoteCandidateId"] as? String]
            path = if (l?.members?.get("candidateType") == "relay" || r?.members?.get("candidateType") == "relay") "relay" else "direct"
            rtt = (pair.members["currentRoundTripTime"] as? Number)?.toDouble()
        }
        var lost = 0L
        var recv = 0L
        var bytes = 0L
        for (s in byId.values) if (s.type == "inbound-rtp") {
            lost += s.members["packetsLost"].num()
            recv += s.members["packetsReceived"].num()
            bytes += s.members["bytesReceived"].num()
        }
        val dl = lost - c.lastLost
        val dr = recv - c.lastRecv
        c.lastLost = lost
        c.lastRecv = recv
        val loss = if (dr + dl > 0) dl.toDouble() / (dr + dl) else 0.0
        val st = _ui.value ?: return
        _ui.value = st.copy(
            verified = st.verified || ok == true, path = path, quality = CallLogic.quality(loss, rtt, dr), bytesIn = bytes,
            remoteVideo = c.remoteVideo != null && (if (c.gotState) st.remoteVideo else c.media == "video"),
        )
        log("stats verified=${st.verified || ok == true} path=$path bytesIn=$bytes")
    }

    private fun startLoops(c: Call) {
        val alive = (config?.aliveS ?: 15).coerceIn(5, 60) * 1000L
        c.jobs += scope.launch {
            while (cur === c && !c.done) {
                delay(alive)
                val id = c.id ?: break
                try {
                    val r = json.decodeFromString(CallAlive.serializer(), api.post("api/calls/$id/alive"))
                    if (r.state == "ended") remoteEnded(c, r.reason)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        // Abfrage als Rueckfall (Live-Kanal ruht im Hintergrund bzw. Proxy puffert): Signale + Zustand alle 3 s
        c.jobs += scope.launch {
            while (cur === c && !c.done) {
                delay(3000)
                val id = c.id ?: break
                val dev = repo.chat.keys()?.dev?.id ?: break
                try {
                    val after = c.seen.maxOrNull() ?: 0
                    val st = json.decodeFromString(CallState.serializer(), api.get("api/calls/$id?device=$dev&after=$after"))
                    for (s in st.signals) signalIn(id, s)
                    when {
                        st.call.state == "ended" -> remoteEnded(c, st.call.reason)
                        st.call.state == "active" && c.caller && c.peerDevice == null -> activeAsCaller(c, st.call.calleeDevice)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        c.jobs += scope.launch {
            while (cur === c && !c.done) {
                delay(2000)
                if (c.connected) checkStats(c)
                update(c)
            }
        }
    }

    private fun activeAsCaller(c: Call, device: String?) {
        if (c.peerDevice == null) c.peerDevice = device
        stopTone()
        update(c, UiText.Res(R.string.call_connecting))
        c.connJob?.cancel()
        c.connJob = scope.launch {
            delay(30_000)
            if (cur === c && !c.connected) iceFailed(c)
        }
    }

    // ---------------------------------------------------------------- Bedienung

    fun toggleMic() {
        val c = cur ?: return
        val t = c.audioTrack ?: return
        t.setEnabled(!t.enabled())
        update(c)
        scope.launch { stateOut(c) }
    }

    fun toggleCam() {
        val c = cur ?: return
        if (c.pc == null) return
        if (c.videoTrack != null) {
            videoSender(c)?.setTrack(null, false)
            stopCamera(c)
        } else {
            if (!startCamera(c)) {
                repo.notify(UiText.Res(R.string.call_no_camera))
                return
            }
            videoSender(c)?.setTrack(c.videoTrack, false)
            CallService.start(app, true)
        }
        update(c)
        scope.launch { stateOut(c) }
    }

    fun switchCamera() {
        cur?.capturer?.switchCamera(null)
    }

    fun toggleSpeaker() {
        val st = _ui.value ?: return
        setSpeaker(!st.speaker)
        _ui.value = st.copy(speaker = !st.speaker)
    }

    fun hangup() {
        val c = cur
        if (c == null) {
            if (ring != null) decline() else _ui.value = null
            return
        }
        if (c.id != null && !c.ending) quietPost("api/calls/${c.id}/end", buildJsonObject {
            put("reason", "hangup")
            put("connected", c.connected)
        })
        finish(c, if (c.connected) UiText.Res(R.string.call_ended_dur, CallLogic.duration((System.currentTimeMillis() - c.t0) / 1000)) else UiText.Res(R.string.call_ended), false)
    }

    /** Oberflaeche geschlossen, Anruf vorbei */
    fun dismiss() {
        if (cur == null && ring == null) _ui.value = null
    }

    private fun securityFail(c: Call) = fail(c, UiText.Res(R.string.call_security_failed), failed = true)

    private fun fail(c: Call, msg: UiText, failed: Boolean = false) {
        if (cur !== c) return
        if (c.id != null && !c.ending) quietPost("api/calls/${c.id}/end", buildJsonObject {
            put("reason", if (failed) "failed" else "hangup")
            put("connected", c.connected)
        })
        finish(c, msg, true)
    }

    private fun remoteEnded(c: Call, reason: String?) {
        if (cur !== c) return
        c.ending = true
        val name = c.peer.name.ifEmpty { c.peer.username }
        finish(c, CallLogic.endText(reason, c.caller, name, if (c.connected) (System.currentTimeMillis() - c.t0) / 1000 else null), false)
    }

    private fun finish(c: Call, msg: UiText, error: Boolean) {
        if (c.done) return
        c.done = true
        c.ending = true
        c.jobs.forEach { it.cancel() }
        c.connJob?.cancel()
        c.outJob?.cancel()
        stopTone()
        if (!error && c.ready) tone(ToneGenerator.TONE_PROP_PROMPT, 400)
        c.remoteVideo?.removeSink(remoteSink)
        stopCamera(c)
        try {
            c.pc?.close()
            c.pc?.dispose()
        } catch (_: Exception) {
        }
        c.pc = null
        c.audioTrack?.dispose()
        c.audioSource?.dispose()
        c.videoSource?.dispose()
        c.surface?.dispose()
        c.audioTrack = null
        if (cur === c) cur = null
        audioOff()
        CallService.stop(app)
        val st = _ui.value
        _ui.value = (st ?: CallUiState(CallPhase.ENDED, c.id, c.chatId, c.caller, c.media == "video", c.peer))
            .copy(phase = CallPhase.ENDED, message = msg, error = error, remoteVideo = false, cam = false)
        val id = c.id
        if (id != null) gone.add(id)
        scope.launch {
            delay(if (error) 6000 else 3000)
            val now = _ui.value
            if (now != null && now.phase == CallPhase.ENDED && cur == null && ring == null) _ui.value = null
        }
        repo.chat.chatsLoaded.value.let { if (it) scope.launch { io.github.yggdrasil_sh.saga.chat.quietly { repo.chat.loadChats() } } }
    }

    private fun update(c: Call, msg: UiText? = _ui.value?.message, reconnecting: Boolean? = null) {
        if (cur !== c) return
        val st = _ui.value ?: return
        val phase = when {
            c.connected -> CallPhase.CONNECTED
            c.caller && c.ready && c.peerDevice == null -> CallPhase.RINGING
            c.caller && !c.ready -> CallPhase.PREPARING
            else -> CallPhase.CONNECTING
        }
        _ui.value = st.copy(
            phase = phase, id = c.id, peer = c.peer, video = c.media == "video",
            mic = c.audioTrack?.enabled() ?: true, cam = c.videoTrack != null,
            message = if (phase == CallPhase.CONNECTED && reconnecting != true && !st.reconnecting) null
            else if (phase == CallPhase.RINGING) UiText.Res(R.string.call_ringing) else msg,
            connectedAt = if (c.connected) c.t0 else 0,
            reconnecting = reconnecting ?: st.reconnecting,
            remoteVideo = c.remoteVideo != null && (if (c.gotState) st.remoteVideo else c.media == "video"),
        )
    }

    // ---------------------------------------------------------------- Live

    private fun onEvent(ev: LiveEvent) {
        when (ev.type) {
            "call.ring" -> { onRing(ev.str("call_id")) }
            "call.signal" -> {
                val id = ev.str("call_id") ?: return
                val s = CallSignalDto(ev.long("seq") ?: return, ev.str("from_device").orEmpty(), ev.str("to_device").orEmpty(), ev.str("sealed").orEmpty())
                signalIn(id, s)
            }
            "call.state" -> {
                val id = ev.str("call_id") ?: return
                val state = ev.str("state")
                if (state == "active" || state == "ended") unring(id, if (state == "ended" && ev.str("reason") == "cancelled") UiText.Res(R.string.call_missed) else null)
                val c = cur ?: return
                if (c.id != id) return
                when {
                    state == "ended" -> remoteEnded(c, ev.str("reason"))
                    state == "active" && c.caller -> activeAsCaller(c, ev.str("device"))
                    state == "active" && !c.caller && !c.ready && ev.str("device") != repo.chat.keys()?.dev?.id ->
                        finish(c, UiText.Res(R.string.call_answered_elsewhere), false)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Audio, Toene

    private val am: AudioManager? get() = app.getSystemService(AudioManager::class.java)

    private fun audioOn(speaker: Boolean) {
        try {
            am?.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {
        }
        setSpeaker(speaker)
        _ui.value = _ui.value?.copy(speaker = speaker)
    }

    private fun audioOff() {
        val a = am ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice() else @Suppress("DEPRECATION") run { a.isSpeakerphoneOn = false }
            a.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    private fun setSpeaker(on: Boolean) {
        val a = am ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val want = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val d = a.availableCommunicationDevices.firstOrNull { it.type == want }
                if (d != null) a.setCommunicationDevice(d) else if (!on) a.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                a.isSpeakerphoneOn = on
            }
        } catch (_: Exception) {
        }
    }

    private fun tone(kind: Int, ms: Int = -1) {
        stopTone()
        try {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).also { it.startTone(kind, ms) }
        } catch (_: Exception) {
            tone = null
        }
    }

    private fun stopTone() {
        try {
            tone?.stopTone()
            tone?.release()
        } catch (_: Exception) {
        }
        tone = null
    }

    private fun quietPost(path: String, body: JsonObject) {
        scope.launch {
            try {
                api.post(path, body)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Abmelden/Kontowechsel: laufenden Anruf beenden */
    fun reset() {
        cur?.let { hangup() }
        ring?.let { unring(it.id) }
        enabled.value = null
        gone.clear()
    }

    /** Fuer Tests/Pruefung (nur Debug-Ausgabe): laufender Anruf verbunden + empfangene Bytes */
    val debugState: String
        get() = _ui.value?.let { "phase=${it.phase} verified=${it.verified} path=${it.path} bytesIn=${it.bytesIn}" } ?: "idle"
}
