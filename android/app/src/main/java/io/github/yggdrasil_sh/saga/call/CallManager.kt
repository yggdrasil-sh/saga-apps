package io.github.yggdrasil_sh.saga.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
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
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
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
    /** Ton-Diagnose aus getStats: "ok" = Audio-Pakete kommen an, "none" = seit Sekunden keine, "" = noch unbekannt */
    val audioIn: String = "",
    val reconnecting: Boolean = false,
    val message: UiText? = null,
    val error: Boolean = false,
    // 2.4 E3 Bildschirm
    /** Ich teile meinen Bildschirm */
    val sharing: Boolean = false,
    /** Das Gegenueber teilt seinen Bildschirm */
    val peerSharing: Boolean = false,
    /** Zweites (kleines) Fernbild vorhanden: Kamera neben dem Bildschirm (bzw. getauscht) */
    val remoteSmall: Boolean = false,
    /** Gross laeuft der Bildschirm des Gegenuebers */
    val bigIsScreen: Boolean = false,
    /** Meine Kamera pausiert waehrend des Teilens (Relais TCP) */
    val camPaused: Boolean = false,
)

/** VideoSink, dessen Ziel (SurfaceViewRenderer der Oberflaeche) wechseln darf */
class ProxySink : VideoSink {
    @Volatile var target: VideoSink? = null
    override fun onFrame(frame: VideoFrame) {
        target?.onFrame(frame)
    }
}

/** Merkt sich, wann zuletzt ein Bild einer Fernspur kam (eine Spur ohne Bilder wird nicht gross gezeigt) */
class FrameWatch : VideoSink {
    @Volatile var last = 0L
    override fun onFrame(frame: VideoFrame) {
        last = System.currentTimeMillis()
    }

    fun live(now: Long = System.currentTimeMillis()): Boolean = now - last < 2500
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
    /** Grosses Fernbild (Kamera, beim Teilen der Bildschirm) */
    val remoteSink = ProxySink()
    /** 2.4 E3: kleines Fernbild (Kamera neben dem geteilten Bildschirm, bzw. getauscht) */
    val remoteSink2 = ProxySink()

    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null

    private fun audioError(msg: String) {
        Log.w("SagaCall", "audio $msg")
    }
    private val crypto by lazy { CallCrypto(repo.chat.crypto) }

    private var cur: Call? = null
    private var ring: CallInfo? = null
    private var ringJob: Job? = null
    private val gone = HashSet<String>()
    private var config: CallConfig? = null
    private var tone: ToneGenerator? = null

    private class PeerDev(val enc: ByteArray, val sign: ByteArray)

    private inner class Call(var id: String?, val chatId: Long, val caller: Boolean, var media: String, var peer: CallPeer) {
        /** 2.3: saga-call/v2 (Gegenueber auf einem anderen Server, gebunden an die Handles) */
        var v2: Boolean = caller && !peer.handle.isNullOrEmpty()
        var myHandle: String? = null
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
        var remoteAudio: AudioTrack? = null
        var audioPk = 0L
        var audioIdle = 0
        val seen = HashSet<Long>()
        val early = ArrayList<CallSignalDto>()
        /** Eingegangene Kandidaten mit ihrer ufrag (ICE-Generation) */
        val remoteIce = ArrayList<Pair<IceCandidate, String?>>()
        /** ufrags frueherer Remote-SDPs (Kandidaten dieser Generationen verwerfen) */
        val oldUfrags = HashSet<String>()
        var curUfrags: Set<String> = emptySet()
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

        // 2.4 saga-call v3 (E0) und Bild-im-Bild
        /** Eigene Faehigkeiten (nach Server-features) und die des Gegenuebers (aus offer/answer) */
        var myCaps: List<String> = emptyList()
        var peerCaps: Set<String> = emptySet()
        /** Kennung der PeerConnection (`pc`); der Angerufene uebernimmt die des Angebots */
        var pcId: String = ""
        var neg: CallV3.Negotiator? = null
        var path: CallV3.Path? = null
        /** Wunsch des Gegenuebers fuer mein Video ("large" | "small" | "off") */
        var peerWant = "large"
        /** Mein Wunsch an das Gegenueber, PiP-Zustand */
        var myWant = "large"
        var pip = false
        /** Zuletzt gesetzte Sender-Grenzen (nur bei Aenderung neu setzen) */
        var applied: String? = null
        var restartJob: Job? = null
        var lastRestart = 0L
        /** ICE-Restarts seit dem letzten "connected" (hoechstens CallV3.MAX_RESTARTS) */
        var restartTries = 0
        /** Nach "failed": Frist bis zum Beenden */
        var failJob: Job? = null
        /** Vom Gegenueber gemeldeter Weg (`state.path`) */
        var peerPath: CallV3.Path? = null
        /** Antwort auf ein Neu-Angebot unterwegs: eigene Kandidaten erst danach senden */
        var answering = false
        /** Server 2.4 (features vorhanden): `caps`-Hinweis in POST /api/calls bzw. /answer mitsenden */
        var capsHint = false

        // 2.4 E3 Bildschirm (Vertrag "Bildschirm v2"). Transceiver-Objekte von org.webrtc werden bei jedem
        // getTransceivers() neu erzeugt (alte entsorgt) - darum nur die mids merken, nie die Objekte.
        var micMid: String? = null
        var camMid: String? = null
        /** Eigene Bildschirm-m-Zeile (bleibt nach dem Ende recvonly und wird beim naechsten Teilen wiederverwendet) */
        var scrMid: String? = null
        var screen: ScreenShare? = null
        var scrMode: CallScreen.Mode? = null
        var mixer: ScreenAudioMixer? = null
        /** Mikrofon an (getrennt von der Tonspur: beim Mischen bleibt die Spur an, nur das Mikrofon schweigt) */
        var micOn = true
        var camPaused = false
        /** Rollen und Stream-IDs der m-Zeilen des Gegenuebers (letzter Stand aus offer/answer/state) */
        var peerTracks: Map<String, String> = emptyMap()
        var peerMsid: Map<String, String> = emptyMap()
        var rScreen = false
        var rScreenMid: String? = null
        var rCam = false
        /** Fernspuren je mid und wann zuletzt Bilder kamen */
        val rvid = LinkedHashMap<String, VideoTrack>()
        val watch = HashMap<String, FrameWatch>()
        var smallVideo: VideoTrack? = null
        var view: CallScreen.View? = null
        var swap = false
        /** Zustand erst nach dem Angebot senden (screen_mid steht erst nach setLocalDescription fest) */
        var stateDue = false
        /** Neuverhandlung noetig: Generation der Aenderung, des laufenden und des beantworteten Angebots */
        var needGen = 0
        var offerGen = 0
        var doneGen = 0
    }

    /** Laufende Bildschirmaufnahme */
    private class ScreenShare(
        val capturer: ScreenCapturerAndroid, val source: VideoSource, val track: VideoTrack, val surface: SurfaceTextureHelper,
        var w: Int, var h: Int, var fps: Int,
    )

    /** Neuverhandlung (ICE-Restart) nur, wenn beide Geraete `reneg` melden - sonst exakt das Verhalten von 2.3 */
    private fun reneg(c: Call): Boolean = c.peerDevice != null && CallV3.canReneg(c.myCaps, c.peerCaps)

    /** Erstes Angebot (des Anrufers) traegt n=1: Anrufer n=1/remoteN=0, Angerufener n=0/remoteN=1 */
    private fun neg(c: Call): CallV3.Negotiator = c.neg ?: CallV3.Negotiator(
        CallV3.polite(repo.chat.keys()?.dev?.id.orEmpty(), c.peerDevice.orEmpty()),
        n = if (c.caller) 1 else 0, remoteN = if (c.caller) 0 else 1,
    ).also { c.neg = it }

    /** Remote-SDP wurde gesetzt: aktuelle ufrags merken, fruehere als alte Generation */
    private fun remoteSet(c: Call) {
        val now = CallV3.sdpUfrags(c.pc?.remoteDescription?.description)
        c.oldUfrags.addAll(c.curUfrags - now)
        c.curUfrags = now
    }

    private fun capsJson(c: Call) = JsonArray(c.myCaps.map { JsonPrimitive(it) })

    // ---------------------------------------------------------------- 2.4 E3: Rollen der m-Zeilen

    private fun screen2(c: Call) = CallScreen.CAP in c.myCaps

    /** mids der eigenen Ton-/Kamera-m-Zeile merken (nach setLocalDescription bzw. setRemoteDescription des Angebots) */
    private fun noteMids(c: Call) {
        val trs = c.pc?.transceivers ?: return
        if (c.micMid == null) c.micMid = trs.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }?.mid
        if (c.camMid == null) c.camMid = trs.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.mid
        // neue Bildschirm-m-Zeile: mid steht erst nach setLocalDescription fest (Spur-ID saga-s)
        if (c.screen != null && c.scrMode == CallScreen.Mode.B && c.scrMid == null) {
            c.scrMid = trs.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && it.sender.track()?.id() == CallScreen.TRACK_SCREEN }?.mid
        }
    }

    /** Bildschirm-mid fuer `tracks`: die eigene, sonst eine vom Gegenueber als "screen" angelegte (gemeinsame m-Zeile) */
    private fun screenRoleMid(c: Call, videoMids: List<String>): String? =
        CallScreen.reuseMid(c.scrMid, c.camMid, videoMids, c.peerTracks)

    /** `tracks` und `msid` (aus der lokalen SDP) - nur wer `screen2` meldet */
    private fun putTracks(c: Call, o: kotlinx.serialization.json.JsonObjectBuilder) {
        if (!screen2(c)) return
        val pc = c.pc ?: return
        val vids = pc.transceivers.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }.mapNotNull { it.mid }
        val t = CallScreen.myTracks(c.micMid, c.camMid, screenRoleMid(c, vids))
        val m = CallScreen.sdpMsids(pc.localDescription?.description)
        o.put("tracks", JsonObject(t.mapValues { JsonPrimitive(it.value) }))
        o.put("msid", JsonObject(m.filterKeys { it in t }.mapValues { JsonPrimitive(it.value) }))
    }

    /** `tracks`/`msid` des Gegenuebers aus offer/answer/state merken (letzter Stand gilt) */
    private fun peerTracksIn(c: Call, b: JsonObject) {
        val t = CallScreen.tracksOf(b["tracks"])
        if (t.isNotEmpty()) c.peerTracks = t
        val m = CallScreen.msidOf(b["msid"])
        if (b.containsKey("msid")) c.peerMsid = m
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
        if (c.v2) return peerKeysV2(c)
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

    /**
     * 2.3: Gegenueber auf einem anderen Server - Schluessel ueber den eigenen Server, selbst geprueft (Zertifikate v2,
     * signierte Geraete-Liste) + TOFU wie beim Senden; Warnung/Blockade -> nicht anrufen.
     */
    private suspend fun peerKeysV2(c: Call) {
        val h = c.peer.handle ?: throw UiTextException(UiText.Res(R.string.call_peer_no_keys, c.peer.name))
        val fed = repo.chat.fed
        if (fed.myHandle == null) fed.certs()
        c.myHandle = fed.myHandle ?: throw UiTextException(UiText.Res(R.string.fed_keys_not_ready))
        val st = fed.keys(listOf(h), fresh = c.peerDevs.isNotEmpty())[h]
        val k = st?.keys ?: throw UiTextException(UiText.Res(R.string.call_peer_no_keys, c.peer.name.ifEmpty { h }))
        if (st.trust != io.github.yggdrasil_sh.saga.chat.TofuV2.TRUST) throw UiTextException(UiText.Res(R.string.call_safety_changed, c.peer.name.ifEmpty { h }))
        val out = LinkedHashMap<String, PeerDev>()
        for (d in k.active) out[d.id] = PeerDev(unb64(d.encPub, 32) ?: continue, unb64(d.signPub, 32) ?: continue)
        c.peerDevs = out
    }

    private fun seal(c: Call, toDev: String, body: String): String {
        val k = keys()
        val pd = c.peerDevs[toDev] ?: throw UiTextException(UiText.Res(R.string.call_devices_changed))
        if (c.v2) return repo.chat.fed.crypto.sealSignal(c.id!!, c.myHandle!!, k.dev, c.peer.handle!!, toDev, pd.enc, body)
        return crypto.sealSignal(c.id!!, k.uid, k.dev, c.peer.username, toDev, pd.enc, body)
    }

    /** `pc` fuer ein Zielgeraet: die ausgehandelte Kennung, sonst (Anrufer vor der Annahme) das sortierte Geraetepaar */
    private fun pcFor(c: Call, dev: String): String =
        if (dev == c.peerDevice && c.pcId.isNotEmpty()) c.pcId else CallV3.pcId(keys().dev.id, dev)

    /** Body je Zielgeraet mit dessen `pc` versiegeln */
    private fun sealPerDevice(c: Call, devs: Collection<String>, body: JsonObject): JsonObject = buildJsonObject {
        for (d in devs) put(d, seal(c, d, JsonObject(body + ("pc" to JsonPrimitive(pcFor(c, d)))).toString()))
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
                val o = if (c.v2) repo.chat.fed.crypto.openSignal(s.sealed, k.dev.encPk, k.dev.encSk) else crypto.openSignal(s.sealed, k.dev.encPk, k.dev.encSk)
                val ok = if (c.v2) repo.chat.fed.crypto.verifySignal(c.id!!, c.peer.handle.orEmpty(), s.fromDevice, c.myHandle.orEmpty(), k.dev.id, o.body, o.sig, pd.sign)
                else crypto.verifySignal(c.id!!, c.peer.username, s.fromDevice, k.uid, k.dev.id, o.body, o.sig, pd.sign)
                if (!ok) return@withContext null
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
        // Ton-Ein-/Ausgabe: Sprach-Attribute (Hoerer/Lautsprecher/Headset folgen setCommunicationDevice), Fehler der
        // Aufnahme/Wiedergabe protokollieren statt still zu schlucken (Diagnose "kein Ton")
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(Build.VERSION.SDK_INT >= 29)
            .setUseHardwareNoiseSuppressor(Build.VERSION.SDK_INT >= 29)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(msg: String?) = audioError("record init: $msg")
                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode?, msg: String?) = audioError("record start $code: $msg")
                override fun onWebRtcAudioRecordError(msg: String?) = audioError("record: $msg")
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(msg: String?) = audioError("track init: $msg")
                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode?, msg: String?) = audioError("track start $code: $msg")
                override fun onWebRtcAudioTrackError(msg: String?) = audioError("track: $msg")
            })
            .createAudioDeviceModule()
        this.adm = adm
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
        c.audioTrack = f.createAudioTrack("saga-a", src).also { it.setEnabled(true) }
        // ADM lebt ueber Anrufe hinweg: Stumm-Zustaende eines frueheren Anrufs duerfen nicht haengen bleiben
        adm?.setMicrophoneMute(false)
        adm?.setSpeakerMute(false)
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
                        // 2.4: ICE-Generation (ufrag) fuer den Empfaenger (Restart)
                        val uf = CallV3.candidateUfrag(cand.sdp, null)
                            ?: CallV3.sdpUfrags(c.pc?.localDescription?.description).firstOrNull()
                        if (uf != null) put("usernameFragment", uf)
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
                // 2.4 E3: mid der m-Zeile (auf beiden Seiten gleich) - ordnet Kamera und Bildschirm zu
                val mid = try {
                    transceiver.mid
                } catch (_: Exception) {
                    null
                }
                scope.launch { if (cur === c) attachTrack(c, t, mid) }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                // Unified Plan meldet jede Spur auch ueber onTrack (mit mid) - hier nur noch Ton
                val t = receiver.track()
                if (t is AudioTrack) scope.launch { if (cur === c) attachTrack(c, t, null) }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch { onConn(c, newState) }
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                scope.launch { onIce(c, s) }
            }
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

    private fun attachTrack(c: Call, t: MediaStreamTrack?, mid: String?) {
        when (t) {
            is VideoTrack -> attachRemote(c, t, mid)
            // Ton des Gegenuebers: spielt das ADM ab, solange die Spur aktiv ist - ausdruecklich einschalten
            is AudioTrack -> if (c.remoteAudio !== t) {
                c.remoteAudio = t
                try {
                    t.setEnabled(true)
                    t.setVolume(1.0)
                } catch (_: Exception) {
                }
                log("remote audio track ${t.id()}")
            }
            else -> {}
        }
    }

    /** Fernspur je mid merken (mit Bild-Waechter); welche gross/klein laeuft, entscheidet bindRemote */
    private fun attachRemote(c: Call, t: VideoTrack, mid0: String?) {
        val mid = mid0 ?: c.camMid ?: "?"
        val old = c.rvid[mid]
        if (old === t) return
        if (old != null) {
            c.watch[mid]?.let { old.removeSink(it) }
            if (c.remoteVideo === old) {
                old.removeSink(remoteSink)
                c.remoteVideo = null
            }
            if (c.smallVideo === old) {
                old.removeSink(remoteSink2)
                c.smallVideo = null
            }
        }
        c.rvid[mid] = t
        val w = c.watch.getOrPut(mid) { FrameWatch() }
        t.addSink(w)
        bindRemote(c)
    }

    /**
     * Fernspuren auf die Anzeige verteilen (Vertrag "Empfaenger: Kamera und Bildschirm unterscheiden"): Bildschirm gross,
     * Kamera klein, getauscht auf Wunsch; im Bild-im-Bild immer der Bildschirm.
     */
    private fun bindRemote(c: Call) {
        if (cur !== c) return
        val now = System.currentTimeMillis()
        val mids = c.rvid.keys.toList()
        val live = c.watch.filter { it.value.live(now) }.keys
        val v = CallScreen.view(c.peerTracks, mids, live, c.rScreen, c.rScreenMid, c.rCam, c.swap, c.pip)
        c.view = v
        val big = v.big?.let { c.rvid[it] }
        val small = v.small?.let { c.rvid[it] }
        if (c.remoteVideo !== big) {
            c.remoteVideo?.removeSink(remoteSink)
            c.remoteVideo = big
            big?.addSink(remoteSink)
        }
        if (c.smallVideo !== small) {
            c.smallVideo?.removeSink(remoteSink2)
            c.smallVideo = small
            small?.addSink(remoteSink2)
        }
        update(c)
    }

    /** Oberflaeche: Ansicht tauschen (Bildschirm <-> Kamera des Gegenuebers) */
    fun swapView() {
        val c = cur ?: return
        if (!c.rScreen) return
        c.swap = !c.swap
        bindRemote(c)
    }

    /** Sender der Kamera-m-Zeile (in Betriebsart A laeuft dort der Bildschirm) */
    private fun videoSender(c: Call): RtpSender? {
        val vids = c.pc?.transceivers?.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO } ?: return null
        return (vids.firstOrNull { c.camMid != null && it.mid == c.camMid } ?: vids.firstOrNull())?.sender
    }

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
        remoteSet(c)
        val list = ArrayList(c.remoteIce)
        c.remoteIce.clear()
        // 2.4: Kandidaten einer neuen ICE-Generation (Restart), deren SDP noch nicht gesetzt ist, zurueckhalten;
        // Kandidaten aelterer Generationen verwerfen
        val keep = ArrayList<Pair<IceCandidate, String?>>()
        for (x in list) when (CallV3.candidateAction(x.second, c.curUfrags, c.oldUfrags)) {
            CallV3.CandAction.APPLY -> pc.addIceCandidate(x.first)
            CallV3.CandAction.WAIT -> keep.add(x)
            CallV3.CandAction.DROP -> {}
        }
        c.remoteIce.addAll(keep.takeLast(60))
    }

    private fun queueIce(c: Call, b: JsonObject) {
        for (e in (b["c"] as? JsonArray).orEmpty().take(60)) {
            val x = e as? JsonObject ?: continue
            val cand = x.s("candidate")?.takeIf { it.isNotEmpty() } ?: continue
            val ice = IceCandidate(x.s("sdpMid") ?: "0", (x["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0, cand)
            c.remoteIce.add(ice to CallV3.candidateUfrag(cand, x.s("usernameFragment")))
        }
    }

    // ---------------------------------------------------------------- Signale

    private suspend fun sendSignal(c: Call, body: JsonObject) {
        val id = c.id ?: return
        if (c.ending) return
        val devs = CallLogic.targets(c.peerDevice, c.peerDevs.keys)
        if (devs.isEmpty()) return
        try {
            val sig = withContext(Dispatchers.Default) { sealPerDevice(c, devs, body) }
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
        // Neues Angebot (ICE-Restart) unterwegs: Kandidaten erst danach senden (sonst kaemen sie vor dem Angebot an)
        if (c.id == null || !c.ready || c.outIce.isEmpty() || c.neg?.makingOffer == true || c.answering) return
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
        val sharing = c.screen != null
        if (sharing && c.scrMode == CallScreen.Mode.B && c.scrMid == null) {
            c.stateDue = true                               // mid steht erst nach dem Angebot fest - dann senden
            return
        }
        sendSignal(c, buildJsonObject {
            put("t", "state")
            put("mic", c.audioTrack != null && c.micOn)
            // Betriebsart A: auf der Kamera-m-Zeile laeuft der Bildschirm; Kamera ueber Relais TCP pausiert (B)
            put("cam", c.videoTrack != null && !(sharing && c.scrMode == CallScreen.Mode.A) && !c.camPaused)
            put("screen", sharing)
            // 2.4: Bild-im-Bild und gewuenschte Videogroesse (aeltere Gegenstellen ignorieren die Felder)
            put("pip", c.pip)
            putJsonObject("want") { put("video", c.myWant) }
            // eigener Weg (immer, sobald bekannt); das Gegenueber rechnet mit dem schlechteren
            c.path?.let { put("path", it.wire) }
            // 2.4 E3: Rollen der m-Zeilen; screen_mid = mid, auf der JETZT der Bildschirm laeuft (B: eigene, A: Kamera)
            if (screen2(c)) {
                putTracks(c, this)
                if (sharing) {
                    val sm = if (c.scrMode == CallScreen.Mode.B) c.scrMid else c.camMid
                    if (sm != null) put("screen_mid", sm)
                    put("screen_audio", c.mixer?.active == true)
                }
            }
        })
    }

    private suspend fun handle(c: Call, b: JsonObject?) {
        if (b == null) {
            securityFail(c)
            return
        }
        // Signale einer fremden PeerConnection ignorieren (ohne `pc`: 2.3, die einzige Verbindung)
        val from = b.s("_from").orEmpty()
        val expectPc = if (c.peerDevice == null || from == c.peerDevice) pcFor(c, from) else ""
        if (!CallV3.pcMatches(b.s("pc"), expectPc)) {
            log("signal for other pc ignored")
            return
        }
        when (CallV3.kind(b.s("t"))) {
            CallV3.Kind.ICE -> {
                queueIce(c, b)
                applyRemoteIce(c)
            }
            CallV3.Kind.OFFER -> renegOffer(c, b)
            CallV3.Kind.ANSWER -> {
                val pc = c.pc ?: return
                if (pc.remoteDescription != null) {
                    renegAnswer(c, b)
                    return
                }
                if (!c.caller) return
                if (c.peerDevice != null && from != c.peerDevice) return
                val sdp = b.s("sdp")
                if (!CallCrypto.fpMatches(sdp, b.fp())) {
                    securityFail(c)
                    return
                }
                c.peerDevice = from                         // ab jetzt Signale nur an das annehmende Geraet
                c.pcId = CallV3.pcId(keys().dev.id, from.orEmpty())
                c.peerCaps = CallV3.caps(b)
                peerTracksIn(c, b)
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
            CallV3.Kind.STATE -> {
                c.gotState = true
                // 2.4: Gegenueber im Bild-im-Bild -> mein Video kleiner (Tabelle Konzept §1.3); fehlt das Feld: "large"
                val want = CallV3.peerWant(b)
                // 2.4: gemeldeter Weg des Gegenuebers - es gilt der schlechtere
                val pp = CallV3.parsePath(b.s("path"))
                if (want != c.peerWant || pp != c.peerPath) {
                    c.peerWant = want
                    c.peerPath = pp
                    applyLimits(c)
                }
                // 2.4 E3: Bildschirm des Gegenuebers - welche mid (screen_mid), Rollen (tracks); neues Teilen: gross
                val scr = (b["screen"] as? JsonPrimitive)?.booleanOrNull == true
                if (scr && !c.rScreen) c.swap = false
                c.rScreen = scr
                c.rScreenMid = CallScreen.screenMid(scr, b["screen_mid"])
                c.rCam = (b["cam"] as? JsonPrimitive)?.booleanOrNull == true
                peerTracksIn(c, b)
                val st = _ui.value ?: return
                _ui.value = st.copy(remoteMic = (b["mic"] as? JsonPrimitive)?.booleanOrNull != false)
                bindRemote(c)
            }
            // Gegenueber legt auf (1:1): sofort beenden; /end sendet das Gegenueber selbst
            CallV3.Kind.BYE -> if (from == c.peerDevice) remoteEnded(c, "hangup")
            CallV3.Kind.UNKNOWN -> {}                       // unbekannte Typen neuerer Versionen ignorieren
        }
    }

    // ---------------------------------------------------------------- Neuverhandlung (saga-call v3, Perfect Negotiation)

    /** Neu ausgehandelte SDP: signierter Fingerabdruck passt zur SDP UND ist derselbe wie beim Aufbau */
    private fun renegSdpOk(c: Call, sdp: String?, b: JsonObject): Boolean =
        CallCrypto.fpMatches(sdp, b.fp()) && CallV3.sameFingerprints(CallCrypto.sdpFingerprints(sdp), c.fps)

    private suspend fun renegOffer(c: Call, b: JsonObject) {
        val pc = c.pc ?: return
        // Ohne beidseitiges `reneg` (2.3-Gegenstelle) bleibt ein Angebot im Gespraech wie bisher unbeachtet
        if (!reneg(c) || b.s("_from") != c.peerDevice) return
        val sdp = b.s("sdp") ?: return
        val stable = pc.signalingState() == PeerConnection.SignalingState.STABLE
        val act = neg(c).onOffer((b["n"] as? JsonPrimitive)?.intOrNull, stable)
        log("reneg offer n=${b["n"]} -> $act")
        if (act == CallV3.Negotiator.OfferAction.IGNORE || act == CallV3.Negotiator.OfferAction.STALE) return
        if (!renegSdpOk(c, sdp, b)) {
            securityFail(c)
            return
        }
        peerTracksIn(c, b)
        c.answering = true
        try {
            if (act == CallV3.Negotiator.OfferAction.ROLLBACK_ACCEPT && !stable) {
                setSdp(pc, true, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
            }
            setSdp(pc, false, SessionDescription(SessionDescription.Type.OFFER, sdp))
            applyRemoteIce(c)
            val ans = createSdp(pc, false)
            setSdp(pc, true, ans)
            noteMids(c)
            val asdp = pc.localDescription?.description ?: ans.description
            sendSignal(c, buildJsonObject {
                put("t", "answer")
                put("sdp", asdp)
                put("fp", buildJsonArray { CallCrypto.sdpFingerprints(asdp).forEach { add(JsonPrimitive(it)) } })
                put("media", c.media)
                put("caps", capsJson(c))
                put("n", neg(c).remoteN)                    // n des beantworteten Angebots
                putTracks(c, this)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("reneg answer failed: ${e.message}")
        } finally {
            c.answering = false
            if (cur === c) {
                flushIce(c)
                bindRemote(c)
                // eigene Aenderung (Teilen an/aus) noch nicht ausgehandelt (Kollision, Rollback)? Jetzt anbieten
                if (c.needGen > c.doneGen) offerNow(c)
            }
        }
    }

    private suspend fun renegAnswer(c: Call, b: JsonObject) {
        val pc = c.pc ?: return
        if (!reneg(c) || b.s("_from") != c.peerDevice) return
        val haveOffer = pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER
        if (!neg(c).onAnswer((b["n"] as? JsonPrimitive)?.intOrNull, haveOffer)) return
        val sdp = b.s("sdp")
        if (!renegSdpOk(c, sdp, b)) {
            securityFail(c)
            return
        }
        peerTracksIn(c, b)
        try {
            setSdp(pc, false, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            applyRemoteIce(c)
            c.doneGen = maxOf(c.doneGen, c.offerGen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("reneg setRemote(answer) failed: ${e.message}")
        }
        bindRemote(c)
        if (c.needGen > c.doneGen) offerNow(c)
    }

    /**
     * 2.4 E3: Neuverhandlung nach einer eigenen Aenderung (Bildschirm-m-Zeile an/aus) - Angebot mit `n` + 1, `tracks` und
     * `msid`; danach `state` (screen_mid steht erst jetzt fest). Laeuft schon eine Aushandlung, folgt das Angebot danach.
     */
    private fun offerNow(c: Call) {
        if (cur !== c || c.done || !reneg(c)) return
        val pc = c.pc ?: return
        val ng = neg(c)
        if (ng.makingOffer || c.answering || pc.signalingState() != PeerConnection.SignalingState.STABLE) return
        val gen = c.needGen
        scope.launch {
            val n = ng.startOffer()
            try {
                val offer = createSdp(pc, true)
                if (cur !== c || pc.signalingState() != PeerConnection.SignalingState.STABLE || !ng.makingOffer) return@launch
                setSdp(pc, true, offer)
                c.offerGen = gen
                noteMids(c)
                val sdp = pc.localDescription?.description ?: offer.description
                sendSignal(c, buildJsonObject {
                    put("t", "offer")
                    put("sdp", sdp)
                    put("media", c.media)
                    put("fp", buildJsonArray { CallCrypto.sdpFingerprints(sdp).forEach { add(JsonPrimitive(it)) } })
                    put("caps", capsJson(c))
                    put("n", n)
                    putTracks(c, this)
                })
                if (c.stateDue) {
                    c.stateDue = false
                    stateOut(c)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("reneg offer failed: ${e.message}")
            } finally {
                ng.offerDone()
                if (cur === c) flushIce(c)
            }
        }
    }

    /**
     * ICE-Restart (Netzwechsel WLAN<->LTE, Verbindung getrennt/gescheitert): neues Angebot mit `n`. Nur mit beidseitigem
     * `reneg`; Ergebnis false = nicht moeglich (2.3-Gegenstelle) -> bisheriges Verhalten.
     */
    private fun restartIce(c: Call, why: String): Boolean {
        if (cur !== c || c.done || !reneg(c)) return false
        val pc = c.pc ?: return false
        val ng = neg(c)
        if (ng.makingOffer) return true
        // hoechstens 3 Versuche je Abbruch (zurueckgesetzt bei "connected")
        if (c.restartTries >= CallV3.MAX_RESTARTS) return true
        c.restartTries++
        c.lastRestart = System.currentTimeMillis()
        log("ice restart ($why) #${c.restartTries}")
        scope.launch {
            val n = ng.startOffer()
            try {
                pc.restartIce()
                val offer = createSdp(pc, true)
                if (cur !== c || pc.signalingState() != PeerConnection.SignalingState.STABLE || !ng.makingOffer) return@launch
                setSdp(pc, true, offer)
                c.offerGen = c.needGen                      // enthaelt auch offene Aenderungen (Bildschirm)
                noteMids(c)
                val sdp = pc.localDescription?.description ?: offer.description
                sendSignal(c, buildJsonObject {
                    put("t", "offer")
                    put("sdp", sdp)
                    put("media", c.media)
                    put("fp", buildJsonArray { CallCrypto.sdpFingerprints(sdp).forEach { add(JsonPrimitive(it)) } })
                    put("caps", capsJson(c))
                    put("n", n)
                    put("restart", true)                    // nur Hinweis
                    putTracks(c, this)
                })
                if (c.stateDue) {
                    c.stateDue = false
                    stateOut(c)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("ice restart failed: ${e.message}")
            } finally {
                ng.offerDone()
                if (cur === c) flushIce(c)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- Bitrate je Pfad (Konzept §1.3)

    /** Grenzen fuer Video- und Audio-Sender nach Pfad und Wunsch des Gegenuebers setzen (nur bei Aenderung) */
    private fun applyLimits(c: Call) {
        val pc = c.pc ?: return
        // es gilt der schlechtere Weg von eigenem und vom Gegenueber gemeldetem
        val p = CallV3.worse(c.path, c.peerPath) ?: return
        // 2.4 E3: beim Teilen - B: Bildschirm-m-Zeile mit Bildschirm-Regeln, Kamera daneben <= 300 kbit/s (Relais TCP aus);
        // A: die Kamera-m-Zeile traegt den Bildschirm und bekommt solange die Bildschirm-Regeln
        val sharing = c.screen != null
        val modeB = sharing && c.scrMode == CallScreen.Mode.B
        val cam = if (sharing && !modeB) CallV3.videoLimits(p, screen = true)
        else CallV3.videoLimits(p, c.peerWant, shareCam = modeB)
        val scr = CallV3.videoLimits(p, screen = true)
        val a = CallV3.audioMaxBps(p)
        val paused = CallScreen.camPaused(c.scrMode.takeIf { sharing }, p, c.videoTrack != null)
        if (paused != c.camPaused) {
            c.camPaused = paused
            update(c)
            scope.launch { if (cur === c) stateOut(c) }
        }
        // Aufnahme: 15 fps, ueber Relais TCP 8 fps (nur bei Aenderung). Ueber die Quelle (verwirft Bilder vor dem
        // Encoder) - NICHT changeCaptureFormat: das legt das virtuelle Display neu an, was Android 14+ verbietet
        c.screen?.let { s ->
            val fps = CallScreen.captureFps(p, hot())
            if (fps != s.fps) {
                s.fps = fps
                s.source.adaptOutputFormat(s.w, s.h, fps)
            }
        }
        val key = "$cam|$scr|$a|${c.scrMode.takeIf { sharing }}|${c.scrMid}|${c.camMid}"
        if (key == c.applied) return
        var ok = true
        for (tr in pc.transceivers) {
            val s = tr.sender
            try {
                val mid = tr.mid
                val isScr = modeB && mid != null && mid == c.scrMid
                val isCam = !isScr && (c.camMid == null || mid == c.camMid)
                if (tr.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !isScr && !isCam) continue
                val v = if (isScr) scr else cam
                val prm = s.parameters
                val enc = prm.encodings.firstOrNull()
                if (enc == null) {
                    if (tr.mediaType != MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO || s.track() != null) ok = false
                    continue
                }
                when (tr.mediaType) {
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                        enc.maxBitrateBps = v.maxBps
                        enc.scaleResolutionDownBy = v.scaleDown
                        enc.maxFramerate = v.maxFps                // null = ohne Grenze (nach dem Teilen)
                        enc.active = v.active
                        prm.degradationPreference = if (v.degradation == CallV3.Degradation.MAINTAIN_RESOLUTION)
                            RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION else RtpParameters.DegradationPreference.BALANCED
                    }
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> enc.maxBitrateBps = a
                    else -> continue
                }
                if (!s.setParameters(prm)) ok = false
            } catch (e: Exception) {
                ok = false
                log("setParameters: ${e.message}")
            }
        }
        if (ok) c.applied = key
        log("limits path=$p want=${c.peerWant} -> cam=$cam screen=$scr audio=$a ok=$ok")
    }

    /** Geraet heiss (THERMAL_STATUS_MODERATE oder mehr): Bildschirm nur mit 5 fps aufnehmen (Konzept §4.5) */
    private fun hot(): Boolean = Build.VERSION.SDK_INT >= 29 && try {
        (app.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0) >= android.os.PowerManager.THERMAL_STATUS_MODERATE
    } catch (_: Exception) {
        false
    }

    /** Oberflaeche: Bild-im-Bild an/aus, Anrufbildschirm sichtbar? -> `want` an das Gegenueber (nur bei Aenderung) */
    fun setView(pip: Boolean, visible: Boolean) {
        val c = cur ?: return
        val want = CallPip.want(pip, visible)
        if (c.pip == pip && c.myWant == want) return
        val pipChanged = c.pip != pip
        c.pip = pip
        c.myWant = want
        if (pipChanged) bindRemote(c)                       // im Bild-im-Bild immer der Bildschirm
        scope.launch { if (cur === c) stateOut(c) }
    }

    // ---------------------------------------------------------------- Netzwechsel

    private var netCb: ConnectivityManager.NetworkCallback? = null

    private fun watchNetwork(c: Call) {
        if (netCb != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null
            override fun onAvailable(network: Network) {
                val prev = last
                last = network
                scope.launch {
                    if (cur !== c || c.done) return@launch
                    if (CallV3.restartOnNetwork(prev?.toString(), network.toString(), c.connected, reneg(c))) {
                        update(c, UiText.Res(R.string.call_net_restart), reconnecting = true)
                        restartIce(c, "network")
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) {
            log("network callback: ${e.message}")
        }
    }

    private fun unwatchNetwork() {
        val cb = netCb ?: return
        netCb = null
        try {
            app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
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

    /** Erster Anruf ohne UnifiedPush: nach dem Anruf einmal darauf hinweisen, dass Saga nur bei offener App klingelt */
    private fun pushHint() {
        scope.launch(Dispatchers.IO) {
            try {
                repo.push.maybeCallHint()
            } catch (_: Exception) {
            }
        }
    }

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
        pushHint()
        scope.launch {
            try {
                keys()
                val cfg = loadConfig()
                if (!cfg.enabled) throw UiTextException(UiText.Res(R.string.call_disabled))
                c.myCaps = CallV3.myCaps(cfg.featureSet)
                c.capsHint = cfg.featureSet.isNotEmpty()
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
                noteMids(c)
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
            // 2.4: Faehigkeiten und erstes Angebot n=1; `pc` je Zielgeraet (sealPerDevice). Aeltere Apps ignorieren das
            put("caps", capsJson(c))
            put("n", 1)
            putTracks(c, this)                              // E3: Rollen der m-Zeilen (nur mit screen2)
        }
        update(c, UiText.Res(R.string.call_connecting))
        try {
            val sig = withContext(Dispatchers.Default) { sealPerDevice(c, c.peerDevs.keys, body) }
            api.post("api/calls", buildJsonObject {
                put("id", c.id)
                put("chat_id", c.chatId)
                put("media", c.media)
                put("device", keys().dev.id)
                put("signals", sig)
                // optionaler Hinweis fuer Server/Oberflaeche (nur an 2.4-Server; massgeblich sind die signierten caps)
                if (c.capsHint) put("caps", capsJson(c))
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
        pushHint()
        scope.launch {
            try {
                val k = keys()
                val cfg = loadConfig()
                val st = json.decodeFromString(CallState.serializer(), api.get("api/calls/${r.id}?device=${k.dev.id}"))
                val info = st.call
                c.peer = info.peer
                c.v2 = info.v == 2 && !info.peer.handle.isNullOrEmpty()      // 2.3: Anruf ueber Servergrenzen
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
                c.myCaps = CallV3.myCaps(cfg.featureSet)
                c.capsHint = cfg.featureSet.isNotEmpty()
                c.peerCaps = CallV3.caps(offer)
                // `pc` legt der Anrufer fest; der Angerufene uebernimmt sie (2.3 ohne pc: das Geraetepaar)
                c.pcId = offer.s("pc")?.take(200) ?: CallV3.pcId(k.dev.id, info.callerDevice.orEmpty())
                if (cur !== c) return@launch
                localMedia(c, video)
                CallService.start(app, c.videoTrack != null)
                val pc = makePc(c, cfg)
                c.pc = pc
                setSdp(pc, false, SessionDescription(SessionDescription.Type.OFFER, sdp))
                addLocal(c, pc, true)
                val ans = createSdp(pc, false)
                setSdp(pc, true, ans)
                noteMids(c)
                peerTracksIn(c, offer)
                val asdp = pc.localDescription?.description ?: ans.description
                val body = buildJsonObject {
                    put("t", "answer")
                    put("sdp", asdp)
                    put("fp", buildJsonArray { CallCrypto.sdpFingerprints(asdp).forEach { add(JsonPrimitive(it)) } })
                    put("media", c.media)
                    put("caps", capsJson(c))
                    put("pc", c.pcId)
                    // Antwort traegt das n des beantworteten Angebots (2.3-Anrufer ohne n: 1)
                    put("n", (offer["n"] as? JsonPrimitive)?.intOrNull ?: 1)
                    putTracks(c, this)                      // E3: Rollen der m-Zeilen (nur mit screen2)
                }.toString()
                val sig = withContext(Dispatchers.Default) { buildJsonObject { put(c.peerDevice!!, seal(c, c.peerDevice!!, body)) } }
                api.post("api/calls/${r.id}/answer", buildJsonObject {
                    put("device", k.dev.id)
                    put("signals", sig)
                    if (c.capsHint) put("caps", capsJson(c))
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
                    watchNetwork(c)
                }
                update(c, null, reconnecting = false)
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> update(c, UiText.Res(R.string.call_reconnecting), reconnecting = true)
            PeerConnection.PeerConnectionState.FAILED -> {
                // 2.4: laufendes Gespraech mit beidseitigem `reneg` -> ICE-Restart (onIce), Frist 20 s; sonst wie bisher
                if (c.connected && reneg(c)) {
                    update(c, UiText.Res(R.string.call_reconnecting), reconnecting = true)
                    failSoon(c)
                } else iceFailed(c)
            }
            else -> {}
        }
    }

    /** Kommt die Verbindung nach `failed` nicht binnen 20 s wieder, wie bisher mit reason "failed" beenden */
    private fun failSoon(c: Call) {
        if (c.failJob?.isActive == true) return
        c.failJob = scope.launch {
            delay(CallV3.FAILED_WAIT_MS)
            if (cur === c && c.pc?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) iceFailed(c)
        }
    }

    /**
     * 2.4 ICE-Restart nach Vertrag (nur mit beidseitigem `reneg`): `disconnected` laenger als 2,5 s, sofort bei `failed`;
     * hoechstens 3 Versuche je Abbruch, zurueckgesetzt bei `connected`.
     */
    private fun onIce(c: Call, s: PeerConnection.IceConnectionState) {
        if (cur !== c || c.pc == null) return
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                c.restartTries = 0
                c.restartJob?.cancel()
                c.restartJob = null
                c.failJob?.cancel()
                c.failJob = null
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                if (c.connected && reneg(c) && c.restartJob?.isActive != true) c.restartJob = scope.launch {
                    delay(CallV3.DISCONNECTED_WAIT_MS)
                    if (cur === c && c.pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) restartIce(c, "disconnected")
                }
            }
            PeerConnection.IceConnectionState.FAILED -> {
                if (c.connected && reneg(c)) {
                    c.restartJob?.cancel()
                    restartIce(c, "failed")
                    failSoon(c)
                }
            }
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
            // 2.4: Relais ueber TCP/TLS (turns:…:443) gesondert - eigene Bitrate-Regeln (Konzept §1.3)
            val p = CallV3.path(l?.members?.get("candidateType") as? String, l?.members?.get("relayProtocol") as? String,
                r?.members?.get("candidateType") as? String, l?.members?.get("protocol") as? String)
            if (p != null) {
                path = CallV3.pathLabel(p)
                val changed = p != c.path
                c.path = p
                applyLimits(c)
                if (changed) {
                    log("path $p")
                    scope.launch { if (cur === c) stateOut(c) }   // `state.path` an das Gegenueber
                }
            }
            rtt = (pair.members["currentRoundTripTime"] as? Number)?.toDouble()
        }
        var lost = 0L
        var recv = 0L
        var bytes = 0L
        var aPk = 0L
        var aSamples = 0L
        var aLevel: Double? = null
        var aOut = 0L
        for (s in byId.values) {
            val kind = (s.members["kind"] ?: s.members["mediaType"]) as? String
            if (s.type == "inbound-rtp") {
                lost += s.members["packetsLost"].num()
                recv += s.members["packetsReceived"].num()
                bytes += s.members["bytesReceived"].num()
                if (kind == "audio") {
                    aPk = s.members["packetsReceived"].num()
                    aSamples = s.members["totalSamplesReceived"].num()
                    aLevel = (s.members["audioLevel"] as? Number)?.toDouble()
                }
            } else if (s.type == "outbound-rtp" && kind == "audio") aOut = s.members["bytesSent"].num()
        }
        // Ton-Diagnose: kommen Audio-Pakete an? (2 Abfragen = 4 s ohne neue Pakete -> "kein Ton")
        c.audioIdle = if (aPk > c.audioPk) 0 else c.audioIdle + 1
        val audioIn = when {
            aPk > c.audioPk -> "ok"
            c.audioIdle >= 2 -> "none"
            else -> _ui.value?.audioIn.orEmpty()
        }
        c.audioPk = aPk
        log("audio in pk=$aPk samples=$aSamples level=$aLevel out bytes=$aOut")
        val dl = lost - c.lastLost
        val dr = recv - c.lastRecv
        c.lastLost = lost
        c.lastRecv = recv
        val loss = if (dr + dl > 0) dl.toDouble() / (dr + dl) else 0.0
        val st = _ui.value ?: return
        _ui.value = st.copy(
            verified = st.verified || ok == true, path = path, quality = CallLogic.quality(loss, rtt, dr), bytesIn = bytes, audioIn = audioIn,
            remoteVideo = c.remoteVideo != null && (if (c.gotState) c.rCam || c.rScreen else c.media == "video"),
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
                bindRemote(c)                               // E3: Spuren ohne Bilder nicht gross zeigen (ruft update)
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
        if (c.audioTrack == null) return
        c.micOn = !c.micOn
        applyMic(c)
        update(c)
        scope.launch { stateOut(c) }
    }

    /** Stumm: ohne Systemton die Tonspur aus; mit gemischtem Systemton nur das Mikrofon (die Spur bleibt an) */
    private fun applyMic(c: Call) {
        val m = c.mixer?.takeIf { it.active }
        m?.micMuted = !c.micOn
        c.audioTrack?.setEnabled(c.micOn || m != null)
    }

    fun toggleCam() {
        val c = cur ?: return
        if (c.pc == null) return
        // Betriebsart A: auf der Kamera-m-Zeile laeuft gerade der Bildschirm - Kamera nur lokal schalten
        val camSends = !(c.screen != null && c.scrMode == CallScreen.Mode.A)
        if (c.videoTrack != null) {
            if (camSends) videoSender(c)?.setTrack(null, false)
            stopCamera(c)
            CallService.setVideo(app, false)
        } else {
            // Erst den Vordergrund-Dienst auf Typ camera heben, DANN die Kamera starten: sonst stoppt Android die
            // Kamera, sobald die App in den Hintergrund bzw. ins Bild-im-Bild geht (offener Punkt aus 2.3.1)
            CallService.setVideo(app, true)
            if (!startCamera(c)) {
                CallService.setVideo(app, false)
                repo.notify(UiText.Res(R.string.call_no_camera))
                return
            }
            if (camSends) videoSender(c)?.setTrack(c.videoTrack, false)
        }
        c.applied = null
        applyLimits(c)
        update(c)
        scope.launch { stateOut(c) }
    }

    fun switchCamera() {
        cur?.capturer?.switchCamera(null)
    }

    // ---------------------------------------------------------------- 2.4 E3: Bildschirm teilen

    /** Teilen moeglich: Anruf verbunden (Betriebsart A geht mit jeder Gegenstelle) */
    fun canShare(): Boolean = cur?.let { it.connected && !it.done && it.pc != null } == true

    /** Aufnahmegroesse aus der Bildschirmgroesse: lange Kante <= 1920 */
    private fun screenSize(): Pair<Int, Int> {
        val dm = app.resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        if (Build.VERSION.SDK_INT >= 30) try {
            app.getSystemService(android.view.WindowManager::class.java)?.maximumWindowMetrics?.bounds?.let {
                w = it.width()
                h = it.height()
            }
        } catch (_: Exception) {
        }
        return CallScreen.captureSize(w, h)
    }

    /**
     * Bildschirm teilen, nachdem die Oberflaeche die Einwilligung (MediaProjection) erhalten hat. Reihenfolge fuer
     * Android 14+: Einwilligung -> Vordergrund-Dienst mit Typ mediaProjection -> Aufnahme (getMediaProjection).
     * Betriebsart B (beide `reneg` + `screen2`): eigene m-Zeile (vorhandene wiederverwenden) und Neuverhandlung;
     * sonst A: der Bildschirm ersetzt die Kamera auf ihrer m-Zeile. Ergebnis false = nicht moeglich.
     */
    fun startScreen(data: android.content.Intent): Boolean {
        val c = cur ?: return false
        val pc = c.pc ?: return false
        if (c.screen != null || !c.connected || c.done) return false
        if (!CallService.setScreen(true)) {
            repo.notify(UiText.Res(R.string.call_screen_failed))
            return false
        }
        val f = factory()
        val (w, h) = screenSize()
        val fps = CallScreen.captureFps(CallV3.worse(c.path, c.peerPath), hot())
        var share: ScreenShare? = null
        val cb = object : MediaProjection.Callback() {
            // "Teilen beenden" in der Statusleiste bzw. Projektion vom System beendet
            override fun onStop() {
                scope.launch { if (cur === c && share != null && c.screen === share) stopScreen(c) }
            }

            // Android 14+: einzelne App geteilt - Groesse folgt dem Fenster der App
            override fun onCapturedContentResize(width: Int, height: Int) {
                scope.launch {
                    val s = share ?: return@launch
                    if (c.screen !== s) return@launch
                    val (nw, nh) = CallScreen.captureSize(width, height)
                    if (nw == s.w && nh == s.h) return@launch
                    if (resizeDisplay(s, nw, nh)) {
                        s.w = nw
                        s.h = nh
                        s.source.adaptOutputFormat(nw, nh, s.fps)
                    }
                }
            }
        }
        var cap: ScreenCapturerAndroid? = null
        var src: VideoSource? = null
        var sth: SurfaceTextureHelper? = null
        return try {
            cap = ScreenCapturerAndroid(data, cb)
            src = f.createVideoSource(true)                // Bildschirm: Detail/Text statt Bewegung (screencast)
            sth = SurfaceTextureHelper.create("saga-screen", egl.eglBaseContext)
            cap.initialize(sth, app, src.capturerObserver)
            cap.startCapture(w, h, fps)
            src.adaptOutputFormat(w, h, fps)
            val track = f.createVideoTrack(CallScreen.TRACK_SCREEN, src)
            track.setEnabled(true)
            val s = ScreenShare(cap, src, track, sth, w, h, fps)
            share = s
            c.screen = s
            c.scrMode = CallScreen.mode(c.myCaps, c.peerCaps, c.connected && c.peerDevice != null && pc.remoteDescription != null)
            log("screen on mode=${c.scrMode} ${w}x$h@$fps")
            mixOn(c, cap)
            if (c.scrMode == CallScreen.Mode.B) {
                // hoechstens eine Bildschirm-m-Zeile je Verbindung: eigene oder vom Gegenueber angelegte wiederverwenden
                val vids = pc.transceivers.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                val reuse = CallScreen.reuseMid(c.scrMid, c.camMid, vids.mapNotNull { it.mid }, c.peerTracks)
                val tr = reuse?.let { m -> vids.firstOrNull { it.mid == m } }
                if (tr != null) {
                    c.scrMid = reuse
                    tr.sender.setTrack(track, false)
                    tr.sender.setStreams(listOf(CallScreen.STREAM_SCREEN))
                    tr.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                } else {
                    c.scrMid = null
                    pc.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(CallScreen.STREAM_SCREEN)))
                }
                c.needGen++
                c.stateDue = true                           // state erst nach dem Angebot (screen_mid)
                offerNow(c)
                stateLater(c)
            } else {
                videoSender(c)?.setTrack(track, false)
                scope.launch { if (cur === c) stateOut(c) }
            }
            c.applied = null
            applyLimits(c)
            update(c)
            true
        } catch (e: Exception) {
            log("screen failed: ${e.message}")
            if (c.screen != null && c.screen === share) {
                stopScreen(c)
            } else {
                try {
                    cap?.stopCapture()
                } catch (_: Exception) {
                }
                cap?.dispose()
                src?.dispose()
                sth?.dispose()
                CallService.setScreen(false)
            }
            repo.notify(UiText.Res(R.string.call_screen_failed))
            false
        }
    }

    /**
     * Android 14+ (einzelne App geteilt, Fenster aendert die Groesse): das virtuelle Display der Aufnahme anpassen.
     * ScreenCapturerAndroid kann das nur durch Neuanlegen (changeCaptureFormat) - das verbietet Android 14+ (eine
     * Projektion, ein Display). Daher resize() auf dem vorhandenen Display; scheitert das, bleibt die alte Groesse.
     */
    private fun resizeDisplay(s: ScreenShare, w: Int, h: Int): Boolean = try {
        val f = ScreenCapturerAndroid::class.java.getDeclaredField("virtualDisplay").apply { isAccessible = true }
        val vd = f.get(s.capturer) as? android.hardware.display.VirtualDisplay
        if (vd == null) false else {
            org.webrtc.ThreadUtils.invokeAtFrontUninterruptibly(s.surface.handler) {
                s.surface.setTextureSize(w, h)
                vd.resize(w, h, app.resources.displayMetrics.densityDpi)
            }
            true
        }
    } catch (e: Exception) {
        log("screen resize: ${e.message}")
        false
    }

    /** Oberflaeche: "Teilen beenden" */
    fun stopScreen() {
        cur?.let { stopScreen(it) }
    }

    /**
     * Teilen beenden. B: Spur weg, Richtung recvonly (nicht stoppen, nicht entfernen - die m-Zeile wird beim naechsten
     * Teilen wiederverwendet) -> Neuverhandlung, danach `state` mit screen: false. A: Kamera zurueck auf ihre m-Zeile.
     */
    private fun stopScreen(c: Call) {
        val s = c.screen ?: return
        val mode = c.scrMode
        c.screen = null
        c.scrMode = null
        c.camPaused = false
        mixOff(c)
        val pc = c.pc
        if (pc != null && !c.done) try {
            if (mode == CallScreen.Mode.B) {
                val vids = pc.transceivers.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                val tr = vids.firstOrNull { c.scrMid != null && it.mid == c.scrMid }
                    ?: vids.firstOrNull { it.sender.track()?.id() == CallScreen.TRACK_SCREEN }
                if (tr != null) {
                    if (c.scrMid == null) c.scrMid = tr.mid
                    tr.sender.setTrack(null, false)
                    tr.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                }
                c.needGen++
                c.stateDue = true
                offerNow(c)
                stateLater(c)
            } else {
                videoSender(c)?.setTrack(c.videoTrack, false)
                scope.launch { if (cur === c) stateOut(c) }
            }
        } catch (e: Exception) {
            log("screen stop: ${e.message}")
        }
        try {
            s.capturer.stopCapture()
        } catch (_: Exception) {
        }
        s.capturer.dispose()
        s.track.dispose()
        s.source.dispose()
        s.surface.dispose()
        CallService.setScreen(false)
        log("screen off")
        c.applied = null
        applyLimits(c)
        update(c)
    }

    /** Zustand spaetestens nach 3 s senden, falls das Angebot ausbleibt (Aushandlung laeuft gerade) */
    private fun stateLater(c: Call) {
        scope.launch {
            delay(3000)
            if (cur === c && c.stateDue) {
                c.stateDue = false
                stateOut(c)
            }
        }
    }

    /** Systemton (Android 10+, AudioPlaybackCapture) in die Tonspur mischen - wo erlaubt; sonst ohne Ton */
    private fun mixOn(c: Call, cap: ScreenCapturerAndroid) {
        if (Build.VERSION.SDK_INT < 29) return
        val a = adm ?: return
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val mp = cap.mediaProjection ?: return
        val m = ScreenAudioMixer()
        if (m.start(a, mp)) {
            c.mixer = m
            applyMic(c)
        }
        log("screen audio ${m.active}")
    }

    private fun mixOff(c: Call) {
        val m = c.mixer ?: return
        c.mixer = null
        m.stop()
        applyMic(c)
    }

    fun toggleSpeaker() {
        val st = _ui.value ?: return
        // Tatsaechliche Ausgabe anzeigen: ohne Hoerer (Tablet) bleibt der Ton auf dem Lautsprecher
        _ui.value = st.copy(speaker = setSpeaker(!st.speaker))
    }

    fun hangup() {
        val c = cur
        if (c == null) {
            if (ring != null) decline() else _ui.value = null
            return
        }
        val id = c.id
        if (id != null && !c.ending) {
            val end = buildJsonObject {
                put("reason", "hangup")
                put("connected", c.connected)
            }
            // 2.4: an eine Gegenstelle mit `bye` zuerst {"t":"bye","reason":"hangup"}, dann wie bisher /end
            val bye = if (c.ready && c.peerDevice != null && CallV3.CAP_BYE in c.peerCaps) try {
                sealPerDevice(c, listOf(c.peerDevice!!), buildJsonObject {
                    put("t", "bye")
                    put("reason", "hangup")
                })
            } catch (_: Exception) {
                null
            } else null
            if (bye == null) quietPost("api/calls/$id/end", end) else scope.launch {
                try {
                    api.post("api/calls/$id/signal", buildJsonObject {
                        put("device", keys().dev.id)
                        put("signals", bye)
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                quietPost("api/calls/$id/end", end)
            }
        }
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
        c.restartJob?.cancel()
        c.failJob?.cancel()
        unwatchNetwork()
        stopTone()
        if (!error && c.ready) tone(ToneGenerator.TONE_PROP_PROMPT, 400)
        // E3: Teilen endet mit dem Anruf (Auflegen, Uebergabe, Verlust der Verbindung)
        stopScreen(c)
        c.remoteVideo?.removeSink(remoteSink)
        c.smallVideo?.removeSink(remoteSink2)
        for ((m, t) in c.rvid) c.watch[m]?.let { t.removeSink(it) }
        c.rvid.clear()
        c.remoteVideo = null
        c.smallVideo = null
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
        val v = c.view
        _ui.value = st.copy(
            phase = phase, id = c.id, peer = c.peer, video = c.media == "video",
            mic = c.micOn, cam = c.videoTrack != null,
            sharing = c.screen != null, peerSharing = c.rScreen && c.connected, camPaused = c.camPaused,
            remoteSmall = c.smallVideo != null, bigIsScreen = c.rScreen && v?.bigScreen == true,
            message = if (phase == CallPhase.CONNECTED && reconnecting != true && !st.reconnecting) null
            else if (phase == CallPhase.RINGING) UiText.Res(R.string.call_ringing) else msg,
            connectedAt = if (c.connected) c.t0 else 0,
            reconnecting = reconnecting ?: st.reconnecting,
            remoteVideo = c.remoteVideo != null && (if (c.gotState) c.rCam || c.rScreen else c.media == "video"),
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

    private var focus: AudioFocusRequest? = null
    private var audioActive = false
    private var savedMicMute = false
    private var scoOn = false

    /** Kopfhoerer/Headset (kabelgebunden, USB, Bluetooth, Hoergeraet): hat Vorrang vor Hoerer und Lautsprecher */
    private val headsetTypes = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= 28) add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun headset(): AudioDeviceInfo? {
        val a = am ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 31) a.availableCommunicationDevices.firstOrNull { it.type in headsetTypes }
            else a.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in headsetTypes }
        } catch (_: Exception) {
            null
        }
    }

    /** Neues/entferntes Headset waehrend des Anrufs: Ton dorthin bzw. zurueck auf Lautsprecher (Video) / Hoerer */
    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = reroute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = reroute()
    }

    private fun reroute() {
        scope.launch {
            if (!audioActive) return@launch
            val st = _ui.value ?: return@launch
            val sp = setSpeaker(headset() == null && (st.cam || st.video))
            _ui.value = _ui.value?.copy(speaker = sp)
        }
    }

    /**
     * Anruf-Ton an: Audio-Fokus (andere Wiedergabe pausiert, Lautstaerketasten regeln den Anruf), MODE_IN_COMMUNICATION,
     * Mikrofon nicht stumm, Ausgabe: Headset > Lautsprecher (Video) > Hoerer (Audio).
     */
    private fun audioOn(speaker: Boolean) {
        val a = am
        if (a != null) {
            try {
                if (!audioActive) {
                    focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                        )
                        .setOnAudioFocusChangeListener { }
                        .build()
                        .also { a.requestAudioFocus(it) }
                    savedMicMute = a.isMicrophoneMute
                    a.registerAudioDeviceCallback(deviceCb, null)
                }
                a.mode = AudioManager.MODE_IN_COMMUNICATION
                if (a.isMicrophoneMute) a.isMicrophoneMute = false
            } catch (e: Exception) {
                audioError("audioOn: ${e.message}")
            }
        }
        audioActive = true
        val sp = setSpeaker(speaker && headset() == null)
        _ui.value = _ui.value?.copy(speaker = sp)
    }

    private fun audioOff() {
        val a = am ?: return
        if (!audioActive) return
        audioActive = false
        try {
            a.unregisterAudioDeviceCallback(deviceCb)
            if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice() else @Suppress("DEPRECATION") run {
                a.isSpeakerphoneOn = false
                if (scoOn) {
                    a.stopBluetoothSco()
                    a.isBluetoothScoOn = false
                }
            }
            scoOn = false
            a.isMicrophoneMute = savedMicMute
            a.mode = AudioManager.MODE_NORMAL
            focus?.let { a.abandonAudioFocusRequest(it) }
            focus = null
        } catch (_: Exception) {
        }
    }

    /** Ausgabe waehlen; Ergebnis: laeuft der Ton tatsaechlich ueber den Lautsprecher? */
    private fun setSpeaker(on: Boolean): Boolean {
        val a = am ?: return on
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val devs = a.availableCommunicationDevices
                val d = if (on) devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else devs.firstOrNull { it.type in headsetTypes }
                    ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }     // Tablet ohne Hoerer
                if (d == null || !a.setCommunicationDevice(d)) {
                    a.clearCommunicationDevice()
                    audioError("setCommunicationDevice(${d?.type}) failed")
                }
                log("audio route ${a.communicationDevice?.type} (speaker=$on)")
                return CallLogic.speakerActive(on, a.communicationDevice?.type)
            } else {
                @Suppress("DEPRECATION")
                run {
                    val bt = !on && a.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    if (bt && !scoOn) {
                        a.startBluetoothSco()
                        a.isBluetoothScoOn = true
                        scoOn = true
                    } else if (!bt && scoOn) {
                        a.stopBluetoothSco()
                        a.isBluetoothScoOn = false
                        scoOn = false
                    }
                    a.isSpeakerphoneOn = on
                }
            }
        } catch (e: Exception) {
            audioError("setSpeaker: ${e.message}")
        }
        return on
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
        get() = _ui.value?.let { "phase=${it.phase} verified=${it.verified} path=${it.path} bytesIn=${it.bytesIn} audioIn=${it.audioIn}" } ?: "idle"
}
