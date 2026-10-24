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
    // 2.4 E4/E5
    /** Raum (Begleitgeraet, Kacheln, Grenzen); null = 1:1 wie bisher */
    val room: RoomUi? = null,
    /** Klingeln: Gruppenanruf (Name = Chat) */
    val group: Boolean = false,
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
    /** TT 64: Anrufe, von denen dieses Geraet weiss, dass sie beendet sind (Leiste "Gruppenanruf laeuft" weg) */
    private val endedIds = LinkedHashSet<String>()

    private fun markEnded(id: String?) {
        if (id == null) return
        endedIds.add(id)
        while (endedIds.size > 200) endedIds.remove(endedIds.first())
        if (run.value?.callId == id) run.value = null
    }
    private var runPoll: Job? = null
    private var config: CallConfig? = null
    private var tone: ToneGenerator? = null

    private class PeerDev(val enc: ByteArray, val sign: ByteArray)

    /**
     * 2.4 E4/E5: Zustand des Raums, den alle Verbindungen eines Anrufs teilen (lokale Medien, Kennung, Teilnehmerliste …).
     * Der Raum selbst ist zugleich die erste Verbindung (1:1 laeuft genau wie bisher); weitere Geraete bekommen je eine
     * eigene Verbindung (`Call` mit `room`), die diese Felder ueber `rs` mitbenutzt - wie static/calls.js (SHARED).
     */
    private inner class RoomShared(var id: String?, var media: String) {
        var v2 = false
        var myHandle: String? = null
        /** 2.5: 1:1 ueber einen Knoten (Raum-Anruf mit dm) - Anzeige wie 1:1 */
        var dm = false
        /** 2.5: vom Gastgeber stummgeschaltet (Name) - Hinweis bis zum eigenen Einschalten */
        var mutedBy: String? = null
        var audioSource: AudioSource? = null
        var audioTrack: AudioTrack? = null
        var videoSource: VideoSource? = null
        var videoTrack: VideoTrack? = null
        var capturer: CameraVideoCapturer? = null
        var surface: SurfaceTextureHelper? = null
        val seen = HashSet<Long>()
        val early = ArrayList<CallSignalDto>()
        var t0 = 0L
        var jobs = ArrayList<Job>()
        var done = false
        var ending = false
        var ready = false
        var myCaps: List<String> = emptyList()
        var capsHint = false
        var pip = false
        var myWant = "large"
        var screen: ScreenShare? = null
        var mixer: ScreenAudioMixer? = null
        var micOn = true
        // Raum
        var kind = CallRoom.KIND_DM
        /** Dieses Geraet ist Begleitgeraet (ohne Mikrofon und Ton, bis "Hierher uebernehmen") */
        var companion = false
        /** Wiedergabe an (Begleitgeraet/Uebernahme: zunaechst aus, Echo-Schutz) */
        var playback = true
        val roster = CallRoom.Roster()
        /** Weitere Verbindungen: Geraet -> Verbindung */
        val links = LinkedHashMap<String, Call>()
        /** Uebernahme laeuft: bisheriges eigenes Geraet und Beginn */
        var hoRep: String? = null
        var hoStart = 0L
        var hoJob: Job? = null
        /** Dieses Geraet ist (wieder) Hauptgeraet nach einer Uebernahme */
        var took = false
        /** 2.5 P10: Hinweis horn_mesh schon gezeigt */
        var meshNoted = false
        /** Ueber join bzw. als Gruppe begonnen (Raum ohne eigene erste Verbindung) */
        var inRoom = false
        /** Gruppenanruf selbst begonnen (klingelt bei den anderen) */
        var starter = false
        /** Uebernahme per join (role handover) - scheitert sie, verlaesst dieses Geraet den Anruf */
        var hoJoin = false
        /** Signale unbekannter Geraete, bis ihre Verbindung steht */
        val pend = HashMap<String, ArrayList<CallSignalDto>>()
        val incoming = HashSet<String>()
        val bad = HashSet<String>()
        /** 2.5 (TT 65): warum zu einem Geraet der Liste keine Verbindung zustande kam (z. B. Sicherheitsnummer) */
        var problem: UiText? = null
        /** TT 63: Ende mit Grund bleibt stehen, bis es geschlossen wird (Beitritt gescheitert) */
        var stickyEnd = false
        var limit = 0
        var old: List<String> = emptyList()
        /** Pegel der empfangenen Tonspuren je Geraet und aktiver Sprecher */
        val levels = HashMap<String, Double>()
        var speaker: String? = null
        var title = ""
        // 2.5 Gjallarhorn: Gruppenanruf ueber einen Knoten
        var horn: HornSession? = null
        /** dieses Konto hat den Gruppenanruf begonnen (Moderation) */
        var hornHost = false
        /** Konto des Gastgebers (Anzeige "Gastgeber") */
        var hornHostWho = ""
        /** Schluessel-Halter je Geraet (versiegeln/oeffnen der fkey-Signale), ohne PeerConnection */
        val hornKeys = HashMap<String, Call>()
    }

    private inner class Call(id: String?, val chatId: Long, val caller: Boolean, media: String, var peer: CallPeer, val room: Call? = null) {
        val rs: RoomShared = room?.rs ?: RoomShared(id, media)
        var id: String? by rs::id
        var media: String by rs::media
        /** 2.3: saga-call/v2 (Gegenueber auf einem anderen Server, gebunden an die Handles) */
        var v2: Boolean by rs::v2
        var myHandle: String? by rs::myHandle
        var peerDevs: Map<String, PeerDev> = emptyMap()
        var peerDevice: String? = null
        var callerDevice: String? = null
        var pc: PeerConnection? = null
        var audioSource: AudioSource? by rs::audioSource
        var audioTrack: AudioTrack? by rs::audioTrack
        var videoSource: VideoSource? by rs::videoSource
        var videoTrack: VideoTrack? by rs::videoTrack
        var capturer: CameraVideoCapturer? by rs::capturer
        var surface: SurfaceTextureHelper? by rs::surface
        var remoteVideo: VideoTrack? = null
        var remoteAudio: AudioTrack? = null
        var audioPk = 0L
        var audioIdle = 0
        val seen: HashSet<Long> get() = rs.seen
        val early: ArrayList<CallSignalDto> get() = rs.early
        /** Eingegangene Kandidaten mit ihrer ufrag (ICE-Generation) */
        val remoteIce = ArrayList<Pair<IceCandidate, String?>>()
        /** ufrags frueherer Remote-SDPs (Kandidaten dieser Generationen verwerfen) */
        val oldUfrags = HashSet<String>()
        var curUfrags: Set<String> = emptySet()
        val outIce = ArrayList<JsonObject>()
        var outJob: Job? = null
        var ready: Boolean by rs::ready
        var connected = false
        var fps: List<String> = emptyList()
        var t0: Long by rs::t0
        val jobs: ArrayList<Job> get() = rs.jobs
        var connJob: Job? = null
        var done: Boolean by rs::done
        var ending: Boolean by rs::ending
        var gotState = false
        var lastLost = 0L
        var lastRecv = 0L
        /** 2.4 E4/E5: diese Verbindung ist getrennt (Geraet hat den Anruf verlassen) */
        var gone = false
        /** Angebot/Antwort ausgetauscht (Verbindung ausgehandelt) */
        var established = false
        /** Gegenueber dieser Verbindung meldet Mikrofon an */
        var rMic = true
        /** Vorgemerkte Signale werden gerade abgearbeitet (neue so lange anhaengen - Reihenfolge) */
        var draining = false
        /** DTLS-Zertifikat dieser Verbindung geprueft */
        var verified = false

        // 2.4 saga-call v3 (E0) und Bild-im-Bild
        /** Eigene Faehigkeiten (nach Server-features) und die des Gegenuebers (aus offer/answer) */
        var myCaps: List<String> by rs::myCaps
        var peerCaps: Set<String> = emptySet()
        /** Kennung der PeerConnection (`pc`); der Angerufene uebernimmt die des Angebots */
        var pcId: String = ""
        var neg: CallV3.Negotiator? = null
        var path: CallV3.Path? = null
        /** Wunsch des Gegenuebers fuer mein Video ("large" | "small" | "off") */
        var peerWant = "large"
        /** Mein Wunsch an das Gegenueber, PiP-Zustand */
        var myWant: String by rs::myWant
        var pip: Boolean by rs::pip
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
        var capsHint: Boolean by rs::capsHint

        // 2.4 E3 Bildschirm (Vertrag "Bildschirm v2"). Transceiver-Objekte von org.webrtc werden bei jedem
        // getTransceivers() neu erzeugt (alte entsorgt) - darum nur die mids merken, nie die Objekte.
        var micMid: String? = null
        var camMid: String? = null
        /** Eigene Bildschirm-m-Zeile (bleibt nach dem Ende recvonly und wird beim naechsten Teilen wiederverwendet) */
        var scrMid: String? = null
        /** Laufende Aufnahme (gilt fuer alle Verbindungen des Raums) */
        var screen: ScreenShare? by rs::screen
        /** Betriebsart des Teilens auf DIESER Verbindung (null = teilt hier nicht) */
        var scrMode: CallScreen.Mode? = null
        var mixer: ScreenAudioMixer? by rs::mixer
        /** Mikrofon an (getrennt von der Tonspur: beim Mischen bleibt die Spur an, nur das Mikrofon schweigt) */
        var micOn: Boolean by rs::micOn
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
        /** 2.4 E5: Kachel-Bild dieser Verbindung (Hauptspur des Gegenuebers) */
        val tile = ProxySink()
        var tileVideo: VideoTrack? = null
    }

    /** Laufende Bildschirmaufnahme */
    private class ScreenShare(
        val capturer: ScreenCapturerAndroid, val source: VideoSource, val track: VideoTrack, val surface: SurfaceTextureHelper,
        var w: Int, var h: Int, var fps: Int,
    )

    // ---------------------------------------------------------------- 2.4 E4/E5: Raum und Verbindungen

    /** Raum einer Verbindung (der Raum ist zugleich die erste Verbindung) */
    private fun roomOf(c: Call): Call = c.room ?: c

    /** Gehoert c (Raum oder Verbindung) zum laufenden Anruf? */
    private fun live(c: Call): Boolean {
        val r = cur ?: return false
        return (c === r || c.room === r) && !c.gone
    }

    /** Verbindungen des Raums: der Raum selbst (wenn er eine hat) und alle weiteren */
    private fun links(c: Call): List<Call> {
        val r = roomOf(c)
        val out = ArrayList<Call>()
        if (r.pc != null && !r.gone) out.add(r)
        r.rs.links.values.filterTo(out) { !it.gone }
        return out
    }

    private fun linkOf(c: Call, dev: String): Call? {
        val r = roomOf(c)
        return if (r.peerDevice == dev && !r.gone && r.pc != null) r else r.rs.links[dev]?.takeIf { !it.gone }
    }

    private fun myDevId(): String = repo.chat.keys()?.dev?.id.orEmpty()
    private fun myUser(): String = repo.chat.keys()?.uid.orEmpty()

    /** Verbindung zu einem eigenen Geraet (dessen Ton nie abspielen) */
    private fun own(c: Call): Boolean = c.peer.username == myUser() && c.peer.username.isNotEmpty()

    /** Endpunkte fuer die Bitrate (Mesh ab drei) */
    private fun endpoints(c: Call): Int = CallRoom.endpoints(links(c).size)

    /** Raum-Faehigkeit: eigener Server und dieses Geraet koennen `multi` */
    private fun multi(c: Call): Boolean = CallRoom.multi(c.myCaps)

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
            reportCaps(loadConfig())                        // 2.4: Faehigkeiten melden (Einladung zu Gruppenanrufen)
            refreshRun()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 404 || e.code == 403) enabled.value = false
        } catch (_: Exception) {
        }
    }

    private fun keys(): LocalChatKeys = repo.chat.keys() ?: throw UiTextException(UiText.Res(R.string.call_chats_not_ready))

    /** Aktive Geraete des Gegenuebers: Zertifikat gueltig, Identitaet wie gemerkt (TOFU - geaendert => nicht anrufen) */
    private suspend fun peerKeys(c: Call, fresh: Boolean = false) {
        // 2.4: weitere Verbindungen im foederierten Raum - Personen anderer Server ueber die geprueften v2-Schluessel,
        // eigene und lokale Personen wie im Chat (der Body bleibt saga-call/v2, dafuer braucht es den eigenen Handle)
        if (c.v2 && (c.room == null || remotePerson(c.peer))) return peerKeysV2(c, fresh)
        if (c.v2 && c.myHandle == null) {
            val fed = repo.chat.fed
            if (fed.myHandle == null) fed.certs()
            c.myHandle = fed.myHandle ?: throw UiTextException(UiText.Res(R.string.fed_keys_not_ready))
        }
        val peer = c.peer.username
        val o = Json.parseToJsonElement(api.get("api/chat/keys?users=" + java.net.URLEncoder.encode(peer, "UTF-8"))) as? JsonObject
        val u = io.github.yggdrasil_sh.saga.chat.ChatLogic.keyEntry(o, peer)      // 2.4: unter der angefragten Kennung, nur Chat-Partner
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
    /** Person eines anderen Servers (Handle und Server bekannt, nicht ich) */
    private fun remotePerson(p: CallPeer): Boolean = !p.handle.isNullOrEmpty() && !p.host.isNullOrEmpty() && p.username != myUser()

    private suspend fun peerKeysV2(c: Call, fresh: Boolean = false) {
        val h = c.peer.handle ?: throw UiTextException(UiText.Res(R.string.call_peer_no_keys, c.peer.name))
        val fed = repo.chat.fed
        if (fed.myHandle == null) fed.certs()
        c.myHandle = fed.myHandle ?: throw UiTextException(UiText.Res(R.string.fed_keys_not_ready))
        val st = fed.keys(listOf(h), fresh = fresh || c.peerDevs.isNotEmpty())[h]
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
            // 2.5: Simulcast (Gjallarhorn: l/m bzw. l/m/h) auch mit Hardware-Kodierern; eine Schicht (1:1, Mesh) unveraendert
            .setVideoEncoderFactory(org.webrtc.SimulcastVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true), org.webrtc.SoftwareVideoEncoderFactory()))
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
                    if (!live(c)) return@launch
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
                scope.launch { if (live(c)) attachTrack(c, t, mid) }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                // Unified Plan meldet jede Spur auch ueber onTrack (mit mid) - hier nur noch Ton
                val t = receiver.track()
                if (t is AudioTrack) scope.launch { if (live(c)) attachTrack(c, t, null) }
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
            // Ton des Gegenuebers: spielt das ADM ab, solange die Spur aktiv ist - ausdruecklich einschalten.
            // 2.4: Ton eigener Geraete (Begleitgeraet, Uebernahme) nie abspielen
            is AudioTrack -> if (c.remoteAudio !== t) {
                c.remoteAudio = t
                try {
                    t.setEnabled(!own(c))
                    t.setVolume(if (own(c)) 0.0 else 1.0)
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
            if (c.tileVideo === old) {
                old.removeSink(c.tile)
                c.tileVideo = null
            }
            if (c.remoteVideo === old) c.remoteVideo = null
            if (c.smallVideo === old) c.smallVideo = null
            unbindMain(old)
        }
        c.rvid[mid] = t
        val w = c.watch.getOrPut(mid) { FrameWatch() }
        t.addSink(w)
        bindRemote(c)
    }

    /**
     * Fernspuren auf die Anzeige verteilen (Vertrag "Empfaenger: Kamera und Bildschirm unterscheiden"): Bildschirm gross,
     * Kamera klein, getauscht auf Wunsch; im Bild-im-Bild immer der Bildschirm. 2.4 E5: je Verbindung eine Kachel; das
     * grosse Bild (und Bild-im-Bild) zeigt die Hauptverbindung - im Mesh den aktiven Sprecher.
     */
    private fun bindRemote(c: Call) {
        if (!live(c)) return
        val now = System.currentTimeMillis()
        val mids = c.rvid.keys.toList()
        val live = c.watch.filter { it.value.live(now) }.keys
        val v = CallScreen.view(c.peerTracks, mids, live, c.rScreen, c.rScreenMid, c.rCam, c.swap, c.pip)
        c.view = v
        val big = v.big?.let { c.rvid[it] }
        c.remoteVideo = big
        c.smallVideo = v.small?.let { c.rvid[it] }
        // Kachel: Bildschirm bzw. Kamera der Verbindung (ohne Tausch)
        if (c.tileVideo !== big) {
            c.tileVideo?.removeSink(c.tile)
            c.tileVideo = big
            big?.addSink(c.tile)
        }
        bindMain(roomOf(c))
        update(c)
    }

    /** Spuren, die gerade das grosse bzw. kleine Fernbild speisen */
    private var mainBig: VideoTrack? = null
    private var mainSmall: VideoTrack? = null

    private fun unbindMain(t: VideoTrack) {
        if (mainBig === t) {
            t.removeSink(remoteSink)
            mainBig = null
        }
        if (mainSmall === t) {
            t.removeSink(remoteSink2)
            mainSmall = null
        }
    }

    /** Grosses/kleines Fernbild an die Hauptverbindung haengen (nur bei Aenderung) */
    private fun bindMain(r: Call) {
        if (!live(r)) return
        val p = primary(r)
        val big = p?.remoteVideo
        val small = if (p != null && !meshView(r)) p.smallVideo else null
        if (mainBig !== big) {
            mainBig?.removeSink(remoteSink)
            mainBig = big
            big?.addSink(remoteSink)
        }
        if (mainSmall !== small) {
            mainSmall?.removeSink(remoteSink2)
            mainSmall = small
            small?.addSink(remoteSink2)
        }
    }

    private fun unbindAll() {
        mainBig?.removeSink(remoteSink)
        mainSmall?.removeSink(remoteSink2)
        mainBig = null
        mainSmall = null
    }

    /** Endpunkt einer Verbindung laut Teilnehmerliste */
    private fun legOf(c: Call): CallLeg? = c.peerDevice?.let { roomOf(c).rs.roster.of(it) }

    /** Stille Verbindung (eigenes Geraet bzw. Begleitgeraet ohne Ton, Kamera und Bildschirm): keine eigene Kachel */
    private fun quiet(c: Call): Boolean =
        CallRoom.quiet(own(c), legOf(c)?.companion == true, c.gotState, c.rMic, c.rCam, c.rScreen)

    private fun shown(r: Call): List<Call> = links(r).filter { !quiet(it) }

    private fun meshView(r: Call): Boolean = CallRoom.meshView(roomOf(r).rs.kind, shown(roomOf(r)).size)

    /** Hauptverbindung: im Mesh der aktive Sprecher, sonst die erste sichtbare Verbindung (1:1 wie bisher) */
    private fun primary(r0: Call): Call? {
        val r = roomOf(r0)
        val sh = shown(r)
        if (meshView(r)) r.rs.speaker?.let { sp -> sh.firstOrNull { it.peerDevice == sp }?.let { return it } }
        return sh.firstOrNull() ?: links(r).firstOrNull() ?: r.takeIf { it.pc != null || r.rs.links.isEmpty() }
    }

    /** Oberflaeche: Ansicht tauschen (Bildschirm <-> Kamera des Gegenuebers) */
    fun swapView() {
        val c = cur?.let { primary(it) } ?: return
        if (!c.rScreen) return
        c.swap = !c.swap
        bindRemote(c)
    }

    /** 2.4 E5: Bild einer Kachel (Verbindung zu diesem Geraet) */
    fun tileSink(device: String): ProxySink? = cur?.let { r ->
        if (r.rs.horn != null) hornSinks.getOrPut(device) { ProxySink() } else linkOf(r, device)?.tile
    }

    /** 2.5: Kachel-Senken im Knoten-Anruf (je Geraet; die Sitzung haengt die Spur des Platzes an) */
    private val hornSinks = HashMap<String, ProxySink>()

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

    /** Zustand an alle Verbindungen des Raums (Mikrofon, Kamera, Bild-im-Bild …) */
    private fun stateAll(c: Call) {
        for (l in links(c)) scope.launch { if (live(l)) stateOut(l) }
    }

    /** replaces = Uebernahme fertig: `ready: true` + bisheriges Geraet (nur an dieses) */
    private suspend fun stateOut(c: Call, replaces: String? = null) {
        if (!c.ready || c.pc == null) return
        val sharing = c.screen != null && c.scrMode != null      // 2.4: teilt auf DIESER Verbindung
        if (sharing && c.scrMode == CallScreen.Mode.B && c.scrMid == null) {
            c.stateDue = true                               // mid steht erst nach dem Angebot fest - dann senden
            return
        }
        sendSignal(c, buildJsonObject {
            put("t", "state")
            put("mic", c.audioTrack != null && c.micOn)
            if (replaces != null) {
                put("ready", true)
                put("replaces", replaces)
            }
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
            // 2.4: erstes Angebot eines hinzukommenden Geraets (eigene Verbindung, noch ohne PeerConnection)
            CallV3.Kind.OFFER -> if (c.room != null && c.pc == null) acceptOffer(c, b) else renegOffer(c, b)
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
                    c.established = true
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
                c.rMic = (b["mic"] as? JsonPrimitive)?.booleanOrNull != false
                // 2.4 E4: ein anderes eigenes Geraet hat uebernommen ("Hierher uebernehmen") -> hier still und raus
                if ((b["ready"] as? JsonPrimitive)?.booleanOrNull == true && b.s("replaces") == myDevId() && own(c)) {
                    handoverOut(roomOf(c), from)
                    return
                }
                bindRemote(c)
            }
            // Gegenueber legt auf: 1:1 (Hauptgeraet) sofort beenden - /end sendet das Gegenueber selbst. 2.4: verlaesst
            // nur ein Geraet den Raum (Gruppe, Begleitgeraet, Uebernahme), nur diese Verbindung trennen
            CallV3.Kind.BYE -> if (from == c.peerDevice) {
                val r = roomOf(c)
                if (r.rs.kind == CallRoom.KIND_GROUP || b.s("reason") == "handover" || own(c) || legOf(c)?.companion == true) {
                    dropLink(c, null)
                } else remoteEnded(c, "hangup")
            }
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
            if (live(c)) {
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
        if (!live(c) || c.done || !reneg(c)) return
        val pc = c.pc ?: return
        val ng = neg(c)
        if (ng.makingOffer || c.answering || pc.signalingState() != PeerConnection.SignalingState.STABLE) return
        val gen = c.needGen
        scope.launch {
            val n = ng.startOffer()
            try {
                val offer = createSdp(pc, true)
                if (!live(c) || pc.signalingState() != PeerConnection.SignalingState.STABLE || !ng.makingOffer) return@launch
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
                if (live(c)) flushIce(c)
            }
        }
    }

    /**
     * ICE-Restart (Netzwechsel WLAN<->LTE, Verbindung getrennt/gescheitert): neues Angebot mit `n`. Nur mit beidseitigem
     * `reneg`; Ergebnis false = nicht moeglich (2.3-Gegenstelle) -> bisheriges Verhalten.
     */
    private fun restartIce(c: Call, why: String): Boolean {
        if (!live(c) || c.done || !reneg(c)) return false
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
                if (!live(c) || pc.signalingState() != PeerConnection.SignalingState.STABLE || !ng.makingOffer) return@launch
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
                if (live(c)) flushIce(c)
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
        val sharing = c.screen != null && c.scrMode != null      // 2.4: teilt auf DIESER Verbindung
        val modeB = sharing && c.scrMode == CallScreen.Mode.B
        val cam = if (sharing && !modeB) CallV3.videoLimits(p, screen = true)
        // 2.4 E5: ab drei Endpunkten Mesh-Werte je Strom (600/500/350 kbit/s, ≤ 540p bzw. 360p15)
        else CallV3.videoLimits(p, c.peerWant, peers = endpoints(c), shareCam = modeB)
        val scr = CallV3.videoLimits(p, screen = true)
        val a = CallV3.audioMaxBps(p)
        val paused = CallScreen.camPaused(c.scrMode.takeIf { sharing }, p, c.videoTrack != null)
        if (paused != c.camPaused) {
            c.camPaused = paused
            update(c)
            scope.launch { if (live(c)) stateOut(c) }
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
        val key = "$cam|$scr|$a|${c.scrMode.takeIf { sharing }}|${c.scrMid}|${c.camMid}|${endpoints(c)}"
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
        if (pipChanged) links(c).forEach { bindRemote(it) }  // im Bild-im-Bild immer der Bildschirm (Mesh: Sprecher)
        stateAll(c)
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
                    if (!live(c) || c.done) return@launch
                    // 2.4: jede Verbindung des Raums neu aufbauen
                    for (l in links(c)) if (CallV3.restartOnNetwork(prev?.toString(), network.toString(), l.connected, reneg(l))) {
                        update(l, UiText.Res(R.string.call_net_restart), reconnecting = true)
                        restartIce(l, "network")
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
        route(c, s)
    }

    /**
     * 2.4: Signal an die passende Verbindung. 1:1, solange es klingelt: Antworten/Kandidaten aller Geraete des Gegenuebers
     * gehen an den Raum (wie bisher). Unbekanntes Geraet im Raum: merken und pruefen; sein Angebot legt eine Verbindung an.
     */
    private fun route(r: Call, s: CallSignalDto) {
        if (r.rs.horn != null) {                            // 2.5: Knoten-Anruf - nur fkey/fkey-ack/state
            hornRoute(r, s)
            return
        }
        var l = linkOf(r, s.fromDevice)
        if (l == null && r.rs.kind == CallRoom.KIND_DM && r.caller && r.pc != null && r.peerDevice == null && !r.established) l = r
        if (l == null && !multi(r)) l = r                  // 2.3: die einzige Verbindung (wie bisher)
        if (l == null && s.fromDevice in r.rs.bad) return
        if (l != null && !l.draining) {
            val x = l
            scope.launch {
                val b = openSig(x, s)
                if (live(x)) handle(x, b)
            }
            return
        }
        val q = r.rs.pend.getOrPut(s.fromDevice) { ArrayList() }
        q.add(s)
        if (q.size > 80) q.removeAt(0)
        if (l == null) incoming(r, s.fromDevice)
    }

    /** Neue Verbindung im Raum zu einem Geraet (Person laut Teilnehmerliste) */
    private fun newLink(r: Call, dev: String, x: CallLeg, caller: Boolean): Call =
        Call(null, r.chatId, caller, r.media, x.person(), room = r).also {
            it.peerDevice = dev
            it.pcId = CallRoom.pairId(myDevId(), dev)
        }

    /**
     * Signal eines Geraets ohne Verbindung: gehoert es zum Anruf (Liste des Servers) und zur Person (signierte
     * Geraeteliste, TOFU)? Dann Verbindung ohne Klingeln anlegen; sonst ablehnen ("Unbekanntes Geraet … abgelehnt").
     */
    private fun incoming(r: Call, dev: String) {
        val rs = r.rs
        if (dev in rs.incoming || dev in rs.bad || !r.ready || r.done) return
        rs.incoming.add(dev)
        scope.launch {
            try {
                if (rs.roster.of(dev) == null) fetchRoster(r)
                val x = rs.roster.of(dev)
                if (x == null || !live(r) || linkOf(r, dev) != null) {
                    if (x == null) rs.pend.remove(dev)
                    return@launch
                }
                val l = newLink(r, dev, x, caller = false)
                l.draining = true
                rs.links[dev] = l
                var why: UiText? = null
                try {
                    peerKeys(l)
                    // 2.4 (Testrunde W9): ein Geraet, das die Person WAEHREND des Anrufs gekoppelt hat (Hinzufuegen/Hierher
                    // uebernehmen), fehlt in zwischengespeicherten Schluesseln - die signierte Liste einmal frisch holen
                    if (CallRoom.needsFreshKeys(dev, l.peerDevs.keys)) peerKeys(l, fresh = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UiTextException) {
                    why = e.text                                // z. B. Sicherheitsnummer geaendert (TT 65): sagen, nicht "unbekannt"
                } catch (e: Exception) {
                    log("incoming keys $dev: ${e.message}")
                }
                if (why != null) {
                    rs.bad.add(dev)
                    rs.pend.remove(dev)
                    rs.problem = why
                    log("incoming $dev abgelehnt: $why")
                    dropLink(l, why)
                    return@launch
                }
                if (l.peerDevs[dev] == null) {                 // Geister-Geraet: nicht in der signierten Liste der Person
                    rs.bad.add(dev)
                    rs.pend.remove(dev)
                    dropLink(l, UiText.Res(R.string.room_unknown_device, x.name.ifEmpty { x.who }))
                    return@launch
                }
                while (live(l)) {
                    val list = rs.pend.remove(dev) ?: break
                    for (s in list) {
                        val b = openSig(l, s)
                        if (live(l)) handle(l, b)
                    }
                }
                l.draining = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("incoming $dev: ${e.message}")
            } finally {
                rs.incoming.remove(dev)
            }
        }
    }

    /** Angebot eines hinzukommenden Geraets beantworten (ohne Klingeln: die Person ist schon im Anruf bzw. tritt bei) */
    private suspend fun acceptOffer(c: Call, b: JsonObject) {
        val sdp = b.s("sdp")
        if (!CallCrypto.fpMatches(sdp, b.fp())) {
            securityFail(c)
            return
        }
        c.fps = CallCrypto.sdpFingerprints(sdp)
        c.peerCaps = CallV3.caps(b)
        peerTracksIn(c, b)
        b.s("pc")?.takeIf { it.length <= 200 }?.let { c.pcId = it }
        val n = (b["n"] as? JsonPrimitive)?.intOrNull ?: 1
        c.neg = CallV3.Negotiator(CallV3.polite(myDevId(), c.peerDevice.orEmpty()), n = 0, remoteN = n)
        c.answering = true                                  // eigene Kandidaten erst nach der Antwort
        try {
            val cfg = config ?: loadConfig()
            val pc = makePc(c, cfg)
            c.pc = pc
            setSdp(pc, false, SessionDescription(SessionDescription.Type.OFFER, sdp))
            addLocal(c, pc, true)
            val ans = createSdp(pc, false)
            setSdp(pc, true, ans)
            noteMids(c)
            if (!live(c)) return
            val asdp = pc.localDescription?.description ?: ans.description
            sendSignal(c, buildJsonObject {
                put("t", "answer")
                put("sdp", asdp)
                put("fp", buildJsonArray { CallCrypto.sdpFingerprints(asdp).forEach { add(JsonPrimitive(it)) } })
                put("media", c.media)
                put("caps", capsJson(c))
                put("n", n)
                putTracks(c, this)
            })
            c.established = true
            applyRemoteIce(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (live(c)) dropLink(c, UiText.Res(R.string.room_link_failed, c.peer.name.ifEmpty { c.peer.username }))
            return
        } finally {
            c.answering = false
        }
        flushIce(c)
        stateOut(c)
        // laufendes Teilen gilt auch fuer spaeter hinzukommende Verbindungen
        if (c.screen != null && c.scrMode == null) shareOn(c)
        c.connJob = scope.launch {
            delay(30_000)
            if (live(c) && !c.connected) dropLink(c, UiText.Res(R.string.room_link_failed, c.peer.name.ifEmpty { c.peer.username }))
        }
        update(c)
    }

    /** Verbindung zu einem Geraet trennen (hat den Anruf verlassen bzw. Fehler nur auf dieser Verbindung) */
    private fun dropLink(c: Call, msg: UiText?) {
        val r = roomOf(c)
        c.connJob?.cancel()
        c.outJob?.cancel()
        c.restartJob?.cancel()
        c.failJob?.cancel()
        c.tileVideo?.removeSink(c.tile)
        c.tileVideo = null
        for ((m, t) in c.rvid) {
            c.watch[m]?.let { t.removeSink(it) }
            unbindMain(t)
        }
        c.rvid.clear()
        c.remoteVideo = null
        c.smallVideo = null
        c.remoteAudio = null
        try {
            c.pc?.close()
            c.pc?.dispose()
        } catch (_: Exception) {
        }
        c.pc = null
        c.peerDevice?.let { r.rs.levels.remove(it) }
        if (c === r) {                                      // erste Verbindung (1:1): der Raum selbst bleibt bestehen
            c.established = false
            c.connected = false
            c.gotState = false
            c.peerDevice = null
            c.scrMode = null
            c.scrMid = null
            c.applied = null
        } else {
            c.gone = true
            c.peerDevice?.let { if (r.rs.links[it] === c) r.rs.links.remove(it) }
        }
        if (r.rs.speaker == c.peerDevice) r.rs.speaker = null
        msg?.let { repo.notify(it) }
        if (!live(r)) return
        links(r).forEach { it.applied = null; applyLimits(it) }   // Endpunktzahl geaendert -> Bitrate neu
        bindMain(r)
        update(r)
    }

    /** Teilnehmerliste neu holen (GET /api/calls/{id}) */
    private suspend fun fetchRoster(r: Call) {
        val id = r.id ?: return
        try {
            val st = json.decodeFromString(CallState.serializer(), api.get("api/calls/$id?device=${myDevId()}"))
            if (st.call.legs.isNotEmpty()) rosterIn(r, st.call.legs, null, st.call.limit, poll = true, mesh = st.call.hornMesh)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Teilnehmerliste uebernehmen (call.roster, GET): Begleitgeraet ja/nein, Hinweise "ist hinzugekommen" / "hat
     * verlassen", Verbindungen zu Geraeten trennen, die nicht mehr dabei sind. Eine aeltere Liste (rv) ueberschreibt keine
     * neuere; eine abgefragte (poll) trennt nie - das tun nur call.roster, bye und alive.
     */
    private fun rosterIn(r: Call, legs: List<CallLeg>, rv: Double?, limit: Int?, poll: Boolean = false, mesh: String? = null) {
        if (r.done) return
        val rs = r.rs
        val before = rs.roster.legs
        val diff = rs.roster.apply(legs, rv) ?: return
        // 2.5 P10: Gruppenanruf ueber Servergrenzen ohne Knoten (ein Server kann keinen nutzen) - einmal sagen
        CallRoom.meshNote(mesh)?.let { if (!rs.meshNoted) { rs.meshNoted = true; repo.notify(UiText.Raw(it)) } }
        // 2.5: im Knoten-Anruf gilt die Grenze des Anrufs (12/25 Video, 50/100 Ton), nicht die Mesh-Grenze
        if (limit != null && limit > 0) rs.limit = if (rs.horn != null) limit else CallRoom.limit(r.media, limit)
        val me = myDevId()
        if (r.ready && rs.hoRep == null && diff.left.any { it.device == me }) {
            // nur wenn das andere Geraet gerade die Fuehrung uebernommen hat (neu bzw. vorher Begleitgeraet) - ein Begleitgeraet,
            // das Begleitgeraet bleibt, heisst nur: dieses Geraet ist kurz aus der Liste gefallen (Bughunt R09)
            val other = legs.firstOrNull { x ->
                x.who == myUser() && x.device != me && x.role != "companion" &&
                    before.firstOrNull { it.device == x.device }.let { it == null || it.role == "companion" }
            }
            if (other != null) {                               // anderes eigenes Geraet hat uebernommen und dieses abgemeldet
                r.micOn = false
                applyMic(r)
                setPlayback(r, false)
                r.ending = true
                finish(r, UiText.Res(R.string.room_taken_over, other.devName.ifEmpty { app.getString(R.string.room_other_device) }), false)
                return
            }
        }
        rs.roster.of(me)?.let { mine ->
            if (rs.hoRep == null && !rs.took) rs.companion = mine.companion
            if (!mine.companion) rs.took = false
        }
        if (multi(r) && r.ready) {
            for (x in diff.joined) if (x.device != me) repo.notify(UiText.Res(R.string.room_joined, x.name.ifEmpty { x.who }, x.devName.ifEmpty { "?" }))
            if (!poll) for (x in diff.left) if (x.device != me) repo.notify(UiText.Res(R.string.room_left, x.name.ifEmpty { x.who }, x.devName.ifEmpty { "?" }))
        }
        rs.horn?.let { h ->
            h.roster()
            hornFlush(r)
            update(r)
            return
        }
        if (!poll) {
            val now = legs.map { it.device }.toSet()
            for (l in links(r)) if (l.peerDevice != null && l.peerDevice !in now && l.established) dropLink(l, null)
        }
        hoCheck(r)
        bindMain(r)
        update(r)
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
        c.v2 = !peer.handle.isNullOrEmpty()                 // 2.3: Gegenueber auf einem anderen Server
        cur = c
        _ui.value = CallUiState(CallPhase.PREPARING, null, chatId, true, video, peer, cam = video, speaker = video, message = UiText.Res(R.string.call_media))
        CallActivity.open(app)
        pushHint()
        scope.launch {
            try {
                keys()
                val cfg = loadConfig()
                if (!cfg.enabled) throw UiTextException(UiText.Res(R.string.call_disabled))
                c.myCaps = CallRoom.myCaps(cfg.featureSet)       // 2.4: inkl. `multi` (Raum), wenn der Server es kann
                c.capsHint = cfg.featureSet.isNotEmpty()
                peerKeys(c)
                if (c.peerDevs.isEmpty()) throw UiTextException(UiText.Res(R.string.call_peer_no_keys, peer.name.ifEmpty { peer.username }))
                if (!live(c)) return@launch
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
                if (live(c)) fail(c, if (e is UiTextException) e.text else repo.friendly(e))
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
        if (!live(c)) {
            quietPost("api/calls/${c.id}/end", buildJsonObject { put("reason", "hangup") })
            return
        }
        c.ready = true
        update(c)
        tone(ToneGenerator.TONE_SUP_RINGTONE)
        flushIce(c)
        startLoops(c)
    }

    // ---------------------------------------------------------------- 2.4 E4/E5: Beitreten, Begleitgeraet, Uebernahme, Gruppen

    /** Laufender Anruf auf einem anderen eigenen Geraet bzw. Gruppenanruf ohne mich: Leiste mit Hinzufuegen/Uebernehmen/Beitreten */
    val run = MutableStateFlow<RunBar?>(null)
    private var runJob: Job? = null

    /** Leiste neu bestimmen (GET /api/calls/active) - nach call.roster/call.state, Verbindungsaufbau des Live-Kanals */
    fun refreshRun() {
        if (repo.needsLogin.value || repo.chat.keys() == null) return
        runJob?.cancel()
        runJob = scope.launch {
            delay(400)                                      // mehrere Ereignisse kurz hintereinander zusammenfassen
            try {
                val cfg = config ?: loadConfig()
                if (!CallRoom.multi(CallRoom.myCaps(cfg.featureSet))) {
                    run.value = null
                    return@launch
                }
                val r = json.decodeFromString(CallsActive.serializer(), api.get("api/calls/active"))
                for (x in r.calls) if (x.state == "ended") markEnded(x.id)
                run.value = CallRoom.runBar(r.calls, myUser(), myDevId(), cur?.id, endedIds)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            // TT 64: solange die Leiste steht, gelegentlich nachsehen - das Ende eines Gruppenanrufs, dem dieses Geraet
            // nie beigetreten ist, kommt nicht immer als Live-Ereignis an
            runPoll?.cancel()
            runPoll = if (run.value != null) scope.launch {
                delay(CallLogic.RUN_POLL_MS)
                if (run.value != null) refreshRun()
            } else null
        }
    }

    private var capsSent: String? = null

    /** Faehigkeiten dieses Geraets melden (PUT /api/calls/caps) - zu Gruppenanrufen wird nur eingeladen, wer `multi` hat */
    private suspend fun reportCaps(cfg: CallConfig) {
        val caps = CallRoom.myCaps(cfg.featureSet)
        if (!CallRoom.multi(caps)) return
        val dev = repo.chat.keys()?.dev?.id ?: return
        val key = dev + caps.joinToString(",")
        if (capsSent == key) return
        try {
            api.put("api/calls/caps", buildJsonObject {
                put("device", dev)
                put("caps", JsonArray(caps.map { JsonPrimitive(it) }))
            })
            capsSent = key
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Mit diesem Geraet einem laufenden Anruf beitreten. role: member (Gruppe), companion (Begleitgeraet: ohne Mikrofon und
     * Ton), handover ("Hierher uebernehmen": ersetzt das eigene Hauptgeraet, erst still bis alle Verbindungen stehen).
     */
    fun join(callId: String, chatId: Long, role: String, video: Boolean, title0: String = "") {
        if (!CallLogic.validId(callId)) return
        val title = title0.ifEmpty { chatTitle(chatId) }
        val cur0 = cur
        if (cur0 != null) {
            if (cur0.id != callId) repo.notify(UiText.Res(R.string.call_already)) else CallActivity.open(app)
            return
        }
        // TT 63: Klingeln beenden, ohne die Oberflaeche zwischendurch zu leeren (sonst schliesst sich CallActivity)
        if (ring?.id == callId) unring(callId, keepUi = true)
        val st0 = CallRoom.start(role, video)
        val c = Call(callId, chatId, true, if (video) "video" else "audio", CallPeer(name = title))
        c.rs.companion = role == CallRoom.ROLE_COMPANION
        c.rs.inRoom = true
        c.rs.title = title
        c.micOn = st0.mic
        c.rs.playback = st0.playback
        cur = c
        run.value = null
        _ui.value = CallUiState(CallPhase.CONNECTING, callId, chatId, true, video, c.peer, mic = st0.mic, cam = st0.cam, speaker = st0.cam,
            message = UiText.Res(R.string.call_connecting))
        CallActivity.open(app)
        scope.launch {
            try {
                keys()
                val cfg = loadConfig()
                c.myCaps = CallRoom.myCaps(cfg.featureSet)
                c.capsHint = true
                if (!multi(c)) throw UiTextException(UiText.Res(R.string.room_server_old))
                val st = json.decodeFromString(CallState.serializer(), api.get("api/calls/$callId?device=${myDevId()}"))
                val k = st.call
                c.media = k.media
                c.rs.kind = k.kind
                c.rs.dm = k.dm
                if (k.dm && k.peer.name.isNotEmpty()) c.peer = k.peer          // 2.5: 1:1 ueber Knoten - Gegenueber als Titel
                c.v2 = k.v == 2
                c.rs.limit = CallRoom.limit(k.media, k.limit ?: cfg.endpoints(k.media))
                c.rs.old = k.old.map { it.name.ifEmpty { it.username } }
                st.signals.forEach { c.seen.add(it.seq) }
                if (k.state == "ended") {
                    markEnded(callId)
                    throw UiTextException(UiText.Res(R.string.call_already_ended))
                }
                if (!k.joinable) {
                    if (k.joinBlock == "ended") markEnded(callId)
                    throw UiTextException(CallRoom.blockText(k.joinBlock, c.rs.limit))
                }
                c.rs.roster.apply(k.legs)
                var rl = role
                var rep: String? = null
                if (role == CallRoom.ROLE_HANDOVER) {
                    rep = CallRoom.mainDevice(k.legs, myUser(), myDevId())
                    if (rep == null) rl = CallRoom.ROLE_MEMBER else {
                        c.rs.hoRep = rep
                        c.rs.hoJoin = true
                    }
                }
                // Anzeige: im 1:1 die andere Person, in Gruppen der Chat
                if (k.kind != CallRoom.KIND_GROUP) k.legs.firstOrNull { it.who != myUser() }?.let { c.peer = it.person() }
                else if (title.isEmpty()) c.peer = CallPeer(name = k.legs.firstOrNull()?.name.orEmpty())
                if (!live(c)) return@launch
                localMedia(c, st0.cam && k.media == "video")
                applyMic(c)
                setPlayback(c, c.rs.playback)
                CallService.start(app, c.videoTrack != null)
                if (k.topo == CallRoom.TOPO_HORN) hornJoin(c, rl, cfg) else offerAll(c, rl, rep, cfg, again = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!live(c)) return@launch
                log("join ${callId.take(8)} gescheitert: ${e.message}")
                // nie beigetreten: kein /end (das wuerde einen fremden bzw. laufenden Anruf beenden)
                if (c.ready) quietPost("api/calls/$callId/leave", buildJsonObject { put("device", myDevId()) })
                c.ending = true
                if (e is ApiException && e.code == 409) markEnded(callId)
                // TT 63: der Grund (z. B. Sicherheitsnummer geaendert) bleibt stehen, bis er geschlossen wird - vorher
                // verschwand er nach 6 s und man stand ohne Erklaerung im Chat mit "Beitreten"
                c.rs.stickyEnd = true
                fail(c, if (e is UiTextException) e.text else if (e is ApiException && e.code == 409) UiText.Res(R.string.call_already_ended) else repo.friendly(e))
            }
        }
    }

    /** Je anderem Endpunkt eine PeerConnection mit Angebot, alles in einem POST /join. 409 mit neuer Liste: einmal neu. */
    private suspend fun offerAll(c: Call, role: String, rep: String?, cfg: CallConfig, again: Boolean) {
        for (l in c.rs.links.values) {
            try {
                l.pc?.close()
                l.pc?.dispose()
            } catch (_: Exception) {
            }
            l.pc = null
            l.gone = true
        }
        c.rs.links.clear()
        val me = myDevId()
        val others = c.rs.roster.legs.filter { it.device != me }
        val sig = LinkedHashMap<String, JsonPrimitive>()
        for (x in others) {
            val l = newLink(c, x.device, x, caller = true)
            c.rs.links[x.device] = l
            peerKeys(l)
            if (CallRoom.needsFreshKeys(x.device, l.peerDevs.keys)) peerKeys(l, fresh = true)   // 2.4 (W9): einmal frisch
            if (l.peerDevs[x.device] == null) throw UiTextException(UiText.Res(R.string.room_unknown_abort, x.name.ifEmpty { x.who }))
            val pc = makePc(l, cfg)
            l.pc = pc
            addLocal(l, pc, false)
            val o = createSdp(pc, true)
            setSdp(pc, true, o)
            noteMids(l)
            val sdp = pc.localDescription?.description ?: o.description
            val body = buildJsonObject {
                put("t", "offer")
                put("pc", CallRoom.pairId(me, x.device))
                put("n", 1)
                put("caps", capsJson(l))
                put("media", c.media)
                put("sdp", sdp)
                put("fp", buildJsonArray { CallCrypto.sdpFingerprints(sdp).forEach { add(JsonPrimitive(it)) } })
                putTracks(l, this)
            }
            sig[x.device] = JsonPrimitive(withContext(Dispatchers.Default) { seal(l, x.device, body.toString()) })
        }
        val r = try {
            json.decodeFromString(CallWrap.serializer(), api.post("api/calls/${c.id}/join", buildJsonObject {
                put("device", me)
                put("role", role)
                put("caps", capsJson(c))
                put("signals", JsonObject(sig))
                if (rep != null && role == CallRoom.ROLE_HANDOVER) put("replaces", rep)
            }))
        } catch (e: ApiException) {
            val o = try {
                Json.parseToJsonElement(e.body) as? JsonObject
            } catch (_: Exception) {
                null
            }
            val block = CallRoom.str(o, "join_block")
            if (e.code == 409 && o?.get("legs") is JsonArray && block == null && !again) {
                c.rs.roster.apply(CallRoom.legsOf(o["legs"]))       // Liste veraltet: einmal neu anbieten
                return offerAll(c, role, rep, cfg, again = true)
            }
            if (e.code == 409 && block != null) {
                val lim = (o?.get("limit") as? JsonPrimitive)?.intOrNull ?: c.rs.limit
                throw UiTextException(CallRoom.blockText(block, lim))
            }
            if (e.code == 429) throw UiTextException(UiText.Res(R.string.call_rate))
            throw e
        }
        if (!live(c)) {
            quietPost("api/calls/${c.id}/leave", buildJsonObject { put("device", me) })
            return
        }
        c.ready = true
        r.call?.let { k ->
            k.limit?.let { c.rs.limit = CallRoom.limit(c.media, it) }
            rosterIn(c, k.legs, null, k.limit, poll = true, mesh = k.hornMesh)
        }
        startLoops(c)
        for (l in links(c)) {
            flushIce(l)
            l.connJob = scope.launch {
                delay(30_000)
                if (live(l) && !l.connected) dropLink(l, UiText.Res(R.string.room_link_failed, l.peer.name.ifEmpty { l.peer.username }))
            }
        }
        if (c.rs.hoRep != null) {
            c.rs.hoStart = System.currentTimeMillis()
            c.rs.hoJob = scope.launch {
                delay((config?.handoverS ?: CallRoom.HANDOVER_S) * 1000L)
                hoFail(c)
            }
        }
        val early = ArrayList(c.early)
        c.early.clear()
        for (s in early) if (live(c)) route(c, s)
        update(c, UiText.Res(R.string.call_connecting))
    }

    /**
     * Uebernahme: sobald alle Verbindungen zu ANDEREN Personen stehen, uebernimmt dieses Geraet Ton und Mikrofon und sagt
     * dem bisherigen Geraet Bescheid (`state.ready` + `replaces`) - das schaltet im selben Moment ab und verlaesst den Anruf.
     */
    private fun hoCheck(r: Call) {
        val rs = r.rs
        val rep = rs.hoRep ?: return
        if (!r.ready || r.done || !live(r)) return
        val others = rs.horn?.let { listOf(it.connected) } ?: links(r).filter { !own(it) }.map { it.connected }   // 2.5: Knoten verbunden
        if (CallRoom.handoverStep(others, rs.hoStart, System.currentTimeMillis(), Int.MAX_VALUE) != CallRoom.HoStep.READY) return
        rs.hoRep = null
        rs.hoJob?.cancel()
        rs.companion = false
        rs.took = true
        r.micOn = true
        applyMic(r)
        setPlayback(r, true)
        CallService.start(app, r.videoTrack != null)       // jetzt mit Mikrofon im Vordergrund-Dienst
        val a = linkOf(r, rep)
        if (rs.horn != null) {
            // 2.5: ueber einen Knoten keine Direktverbindung zum alten Geraet - Bescheid ueber den Signalweg (state.ready +
            // replaces) und zur Sicherheit (aeltere App) dessen Platz kurz danach beim Server abmelden
            val body = buildJsonObject {
                put("t", "state")
                put("mic", true)
                put("cam", r.videoTrack != null)
                put("screen", false)
                put("ready", true)
                put("replaces", rep)
            }.toString()
            scope.launch {
                hornSend(r, mapOf(rep to body))
                delay(3000)
                quietPost("api/calls/${r.id}/leave", buildJsonObject {
                    put("device", rep)
                    put("reason", "handover")
                })
            }
        } else if (a != null && a.established) scope.launch { if (live(a)) stateOut(a, replaces = rep) }
        else quietPost("api/calls/${r.id}/leave", buildJsonObject {        // eigenes Geraet ohne Verbindung
            put("device", rep)
            put("reason", "handover")
        })
        update(r)
        stateAll(r)
    }

    /** Uebernahme hat nicht rechtzeitig geklappt: per join -> dieses Geraet verlaesst den Anruf; als Begleitgeraet -> bleibt still */
    private fun hoFail(r: Call) {
        val rs = r.rs
        if (rs.hoRep == null || !live(r)) return
        rs.hoRep = null
        if (rs.hoJoin) {
            quietPost("api/calls/${r.id}/leave", buildJsonObject { put("device", myDevId()) })
            r.ending = true
            finish(r, UiText.Res(R.string.room_takeover_failed), true)
        } else {
            repo.notify(UiText.Res(R.string.room_takeover_failed))
            update(r)
        }
    }

    /** Begleitgeraet -> Hauptgeraet ("Hierher uebernehmen" im Anrufbildschirm, ohne neuen Beitritt) */
    fun takeOver() {
        val r = cur ?: return
        if (r.done || r.rs.hoRep != null) return
        val main = CallRoom.mainDevice(r.rs.roster.legs, myUser(), myDevId())
        if (main == null) {
            r.rs.companion = false
            r.rs.took = true
            r.micOn = true
            applyMic(r)
            setPlayback(r, true)
            update(r)
            stateAll(r)
            return
        }
        r.rs.hoRep = main
        r.rs.hoJoin = false
        r.rs.hoStart = System.currentTimeMillis()
        r.rs.hoJob = scope.launch {
            delay((config?.handoverS ?: CallRoom.HANDOVER_S) * 1000L)
            hoFail(r)
        }
        update(r)
        hoCheck(r)
    }

    /** Ein anderes eigenes Geraet hat uebernommen: sofort still, `bye` an die anderen, verlassen, Hinweis */
    private fun handoverOut(r: Call, from: String) {
        if (!live(r) || r.done) return
        r.micOn = false
        applyMic(r)
        setPlayback(r, false)
        val id = r.id
        val x = r.rs.roster.of(from)
        val bye = byeSeal(r, "handover", except = from)
        r.ending = true
        scope.launch {
            // erst "bye" an die anderen (danach nimmt der Server keine Signale dieses Geraets mehr an), dann verlassen
            if (id != null) kotlinx.coroutines.withTimeoutOrNull(1500) { byePost(id, bye) }
            if (id != null) quietPost("api/calls/$id/leave", buildJsonObject {
                put("device", myDevId())
                put("reason", "handover")
            })
        }
        finish(r, UiText.Res(R.string.room_taken_over, x?.devName?.ifEmpty { null } ?: app.getString(R.string.room_other_device)), false)
    }

    /** Name des Chats aus der Chatliste (freie Gruppen: Ende-zu-Ende-verschluesselter Titel - der Server kennt ihn nicht) */
    private fun chatTitle(chatId: Long): String {
        if (chatId == 0L) return ""
        val svc = repo.chat
        val c = svc.chats.value.firstOrNull { it.id == chatId } ?: return ""
        return io.github.yggdrasil_sh.saga.chat.ChatLogic.displayName(c, svc.freeTitles.value[chatId])
    }

    /** Gruppenanruf beginnen (Gruppen-, freie und Seiten-Chats): erst allein, alle anderen klingeln (call.invite) */
    fun startGroup(chatId: Long, title: String, video: Boolean, running: RunningCall? = null) {
        if (running != null && running.kind == CallRoom.KIND_GROUP && running.state != "ended" && running.id.isNotEmpty()) {
            join(running.id, chatId, CallRoom.ROLE_MEMBER, video, title)
            return
        }
        if (cur != null || ring != null) {
            repo.notify(UiText.Res(R.string.call_already))
            return
        }
        val c = Call(null, chatId, true, if (video) "video" else "audio", CallPeer(name = title))
        c.rs.kind = CallRoom.KIND_GROUP
        c.rs.inRoom = true
        c.rs.starter = true
        c.rs.title = title
        cur = c
        _ui.value = CallUiState(CallPhase.PREPARING, null, chatId, true, video, c.peer, cam = video, speaker = video, message = UiText.Res(R.string.call_media))
        CallActivity.open(app)
        scope.launch {
            try {
                keys()
                val cfg = loadConfig()
                if (!cfg.enabled) throw UiTextException(UiText.Res(R.string.call_disabled))
                c.myCaps = CallRoom.myCaps(cfg.featureSet)
                c.capsHint = true
                if (!CallRoom.groupOk(cfg.featureSet)) throw UiTextException(UiText.Res(R.string.room_server_old))
                c.rs.limit = cfg.endpoints(c.media)
                if (!live(c)) return@launch
                localMedia(c, video)
                c.media = if (c.videoTrack != null) "video" else "audio"
                CallService.start(app, c.videoTrack != null)
                c.id = ChatFormat.hex(repo.chat.crypto.random(16))
                val r = try {
                    json.decodeFromString(CallWrap.serializer(), api.post("api/calls", buildJsonObject {
                        put("id", c.id)
                        put("chat_id", chatId)
                        put("media", c.media)
                        put("device", myDevId())
                        put("caps", capsJson(c))
                    }))
                } catch (e: ApiException) {
                    val o = try {
                        Json.parseToJsonElement(e.body) as? JsonObject
                    } catch (_: Exception) {
                        null
                    }
                    val runId = CallRoom.str(o, "running") ?: CallRoom.str(o?.get("running") as? JsonObject, "id")
                    c.id = null
                    if (e.code == 409 && runId != null) {           // laeuft schon -> beitreten
                        c.ending = true
                        finish(c, UiText.Res(R.string.call_connecting), false)
                        _ui.value = null
                        join(runId, chatId, CallRoom.ROLE_MEMBER, video, title)
                        return@launch
                    }
                    if (e.code == 429) throw UiTextException(UiText.Res(R.string.call_rate))
                    throw e
                }
                if (!live(c)) {
                    quietPost("api/calls/${c.id}/end", buildJsonObject {
                        put("reason", "hangup")
                        put("device", myDevId())
                    })
                    return@launch
                }
                c.ready = true
                r.call?.let { k ->
                    c.rs.old = k.old.map { it.name.ifEmpty { it.username } }
                    rosterIn(c, k.legs, null, k.limit, poll = true, mesh = k.hornMesh)
                    if (k.topo == CallRoom.TOPO_HORN) hornStart(c, k)       // 2.5: grosse Gruppe ueber einen Knoten
                }
                tone(ToneGenerator.TONE_SUP_RINGTONE)
                startLoops(c)
                val early = ArrayList(c.early)
                c.early.clear()
                for (s in early) if (live(c)) route(c, s)
                update(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (live(c)) fail(c, if (e is UiTextException) e.text else repo.friendly(e))
            }
        }
    }

    /** call.invite: Gruppenanruf klingelt (still bei grossen Gruppen: nur die Leiste) */
    private fun onInvite(ev: LiveEvent) {
        val id = ev.str("call_id") ?: return
        if (!CallLogic.validId(id) || id in gone || cur?.id == id || ring?.id == id) return
        if (ev.bool("silent")) {
            refreshRun()
            return
        }
        val from = ev.obj("from")
        val peer = CallPeer(
            CallRoom.str(from, "username").orEmpty(), CallRoom.str(from, "name").orEmpty(), CallRoom.str(from, "avatar"),
            CallRoom.str(from, "handle"),
        )
        if (!ev.bool("dm")) (CallRoom.str(from, "chat")?.takeIf { it.isNotEmpty() } ?: chatTitle(ev.long("chat_id") ?: 0).takeIf { it.isNotEmpty() })
            ?.let { titles[id] = it }   // 2.5: 1:1 ueber Knoten ohne Chat-Titel; freie Gruppe: Name aus der Chatliste
        val info = CallInfo(id = id, chatId = ev.long("chat_id") ?: 0, media = ev.str("media") ?: "audio", state = "ringing", role = "callee",
            peer = peer, ringS = ev.int("ring_s"), kind = CallRoom.KIND_GROUP, dm = ev.bool("dm"))
        ringGroup(info)
    }

    /** Chat-Titel klingelnder Gruppenanrufe */
    private val titles = HashMap<String, String>()

    private fun ringGroup(info: CallInfo) {
        if (cur != null || ring != null) {
            refreshRun()                                     // telefoniere schon: nicht ablehnen, nur Leiste
            return
        }
        ring = info
        _ui.value = CallUiState(CallPhase.INCOMING, info.id, info.chatId, false, info.media == "video", info.peer, group = !info.dm)   // 2.5: dm = Anzeige wie 1:1
        CallNotify.showIncoming(app, info)
        if (androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            CallActivity.open(app)
        }
        val left = ((info.expiresIn ?: (info.ringS ?: 45).toDouble()).coerceAtLeast(5.0) * 1000).toLong() + 3000
        ringJob?.cancel()
        ringJob = scope.launch {
            delay(left)
            waitRingHold()
            unring(info.id, UiText.Res(R.string.call_missed))
        }
    }

    // ---------------------------------------------------------------- Klingeln, Annehmen

    private fun pending() {
        if (repo.needsLogin.value || repo.chat.keys() == null) return
        scope.launch {
            try {
                val r = json.decodeFromString(CallsActive.serializer(), api.get("api/calls/active"))
                for (x in r.calls) if (x.role == "callee" && x.state == "ringing" || x.kind == CallRoom.KIND_GROUP && x.invite == "ringing") checkRing(x.id)
                config?.let { reportCaps(it) }
                refreshRun()
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
        // 2.4 E5: Gruppenanruf (Mitteilung "call.group"): eigene Einladung klingelt und noch kein eigenes Geraet dabei
        if (info.kind == CallRoom.KIND_GROUP) {
            if (info.invite == "ringing" && !CallRoom.stopRinging(info.legs, myUser()) && callId !in gone && ring?.id != callId) {
                val from = info.legs.firstOrNull()?.person() ?: info.peer
                ringGroup(info.copy(peer = from, state = "ringing", role = "callee"))
            }
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
            waitRingHold()
            unring(callId, UiText.Res(R.string.call_missed))
        }
    }

    /** N-02: Beim Annehmen fragt die Oberflaeche nach dem Mikrofon - so lange wartet das lokale Klingel-Zeitlimit (max. 60 s) */
    @Volatile private var ringHeldUntil = 0L

    fun holdRing(on: Boolean) {
        ringHeldUntil = if (on) System.currentTimeMillis() + CallLogic.RING_HOLD_MS else 0L
    }

    val isRinging: Boolean get() = ring != null

    private suspend fun waitRingHold() {
        while (System.currentTimeMillis() < ringHeldUntil) delay(500)
    }

    private fun unring(id: String, msg: UiText? = null, keepUi: Boolean = false) {
        gone.add(id)
        if (ring?.id != id) return
        ring = null
        ringJob?.cancel()
        ringJob = null
        CallNotify.cancelIncoming(app)
        val st = _ui.value
        if (!keepUi && st != null && st.phase == CallPhase.INCOMING && st.id == id) {
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
        // 2.4 E5: Gruppenanruf annehmen = beitreten (Mitglied); join beendet das Klingeln selbst und zeigt sofort
        // "Verbinde …" (TT 63: kein Zwischenzustand ohne Anruf, in dem sich der Anrufbildschirm schliesst)
        if (r.kind == CallRoom.KIND_GROUP) {
            if (cur != null || !CallLogic.validId(r.id)) unring(r.id)
            join(r.id, r.chatId, CallRoom.ROLE_MEMBER, video, titles[r.id].orEmpty())
            return
        }
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
                c.myCaps = CallRoom.myCaps(cfg.featureSet)       // 2.4: inkl. `multi` (Raum), wenn der Server es kann
                c.capsHint = cfg.featureSet.isNotEmpty()
                c.peerCaps = CallV3.caps(offer)
                // `pc` legt der Anrufer fest; der Angerufene uebernimmt sie (2.3 ohne pc: das Geraetepaar)
                c.pcId = offer.s("pc")?.take(200) ?: CallV3.pcId(k.dev.id, info.callerDevice.orEmpty())
                if (!live(c)) return@launch
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
                if (!live(c)) return@launch
                c.ready = true
                c.established = true
                update(c, UiText.Res(R.string.call_connecting))
                applyRemoteIce(c)
                flushIce(c)
                startLoops(c)
                stateOut(c)
                val early = ArrayList(c.early)
                c.early.clear()
                for (s in early) if (live(c)) route(c, s)
                c.connJob = scope.launch {
                    delay(30_000)
                    if (live(c) && !c.connected) iceFailed(c)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (live(c)) fail(c, if (e.code == 409) UiText.Res(R.string.call_gone_or_elsewhere) else repo.friendly(e))
            } catch (e: Exception) {
                if (live(c)) fail(c, if (e is UiTextException) e.text else repo.friendly(e))
            }
        }
    }

    // ---------------------------------------------------------------- Verbindung, Pruefung, Qualitaet

    private fun onConn(c: Call, st: PeerConnection.PeerConnectionState) {
        if (!live(c) || c.pc == null) return
        log("connection $st")
        when (st) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                if (!c.connected) {
                    c.connected = true
                    if (c.t0 == 0L) c.t0 = System.currentTimeMillis()      // Raum: Dauer ab der ersten Verbindung
                    stopTone()
                    c.connJob?.cancel()
                    scope.launch { stateOut(c) }
                    checkStats(c)
                    watchNetwork(roomOf(c))
                    // 2.4: weitere Verbindung -> Mesh-Bitrate fuer alle neu; Uebernahme evtl. fertig
                    links(c).forEach { if (it !== c) { it.applied = null; applyLimits(it) } }
                    hoCheck(roomOf(c))
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
            if (live(c) && c.pc?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) iceFailed(c)
        }
    }

    /**
     * 2.4 ICE-Restart nach Vertrag (nur mit beidseitigem `reneg`): `disconnected` laenger als 2,5 s, sofort bei `failed`;
     * hoechstens 3 Versuche je Abbruch, zurueckgesetzt bei `connected`.
     */
    private fun onIce(c: Call, s: PeerConnection.IceConnectionState) {
        if (!live(c) || c.pc == null) return
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
                    if (live(c) && c.pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) restartIce(c, "disconnected")
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
            scope.launch { if (live(c) && !c.done) onStats(c, rep) }
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
        if (ok == true) c.verified = true
        val r = roomOf(c)
        val main = primary(r) === c
        var path = if (main) _ui.value?.path.orEmpty() else ""
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
                    scope.launch { if (live(c)) stateOut(c) }   // `state.path` an das Gegenueber
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
        // 2.4 E5: Pegel der empfangenen Tonspur -> aktiver Sprecher (Kachel hervorheben, Bild-im-Bild)
        c.peerDevice?.let { d ->
            if (!own(c) && c.connected) r.rs.levels[d] = if (c.rMic) aLevel ?: 0.0 else 0.0
            val sp = CallRoom.speaker(r.rs.levels.filterKeys { k -> linkOf(r, k) != null }, r.rs.speaker)
            if (sp != r.rs.speaker) {
                r.rs.speaker = sp
                bindMain(r)
                update(r)
            }
        }
        val dl = lost - c.lastLost
        val dr = recv - c.lastRecv
        c.lastLost = lost
        c.lastRecv = recv
        val loss = if (dr + dl > 0) dl.toDouble() / (dr + dl) else 0.0
        val st = _ui.value ?: return
        // "Ende-zu-Ende ✓" erst, wenn JEDE Verbindung des Raums geprueft ist; Weg/Qualitaet der Hauptverbindung
        val ls = links(r)
        val verified = ls.isNotEmpty() && ls.all { it.verified || !it.connected } && ls.any { it.verified }
        _ui.value = if (main) st.copy(
            verified = verified, path = path, quality = CallLogic.quality(loss, rtt, dr), bytesIn = bytes, audioIn = audioIn,
            remoteVideo = c.remoteVideo != null && (if (c.gotState) c.rCam || c.rScreen else c.media == "video"),
        ) else st.copy(verified = verified)
        log("stats verified=$verified path=$path bytesIn=$bytes")
    }

    private fun startLoops(c: Call) {
        val alive = (config?.aliveS ?: 15).coerceIn(5, 60) * 1000L
        c.jobs += scope.launch {
            while (live(c) && !c.done) {
                delay(alive)
                val id = c.id ?: break
                try {
                    // 2.4: mit Geraet - `leg: "left"` = dieses Geraet ist nicht mehr im Anruf (z. B. uebernommen)
                    val r = json.decodeFromString(CallAlive.serializer(), api.post("api/calls/$id/alive", buildJsonObject { put("device", myDevId()) }))
                    if (r.state == "ended") remoteEnded(c, r.reason)
                    else if (r.leg == "left" && c.rs.hoRep == null) {
                        c.ending = true
                        finish(c, UiText.Res(R.string.room_left_here), false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        // Abfrage als Rueckfall (Live-Kanal ruht im Hintergrund bzw. Proxy puffert): Signale + Zustand alle 3 s
        c.jobs += scope.launch {
            while (live(c) && !c.done) {
                delay(3000)
                val id = c.id ?: break
                val dev = repo.chat.keys()?.dev?.id ?: break
                try {
                    val after = c.seen.maxOrNull() ?: 0
                    val st = json.decodeFromString(CallState.serializer(), api.get("api/calls/$id?device=$dev&after=$after"))
                    for (s in st.signals) signalIn(id, s)
                    if (st.call.legs.isNotEmpty()) rosterIn(c, st.call.legs, null, st.call.limit, poll = true, mesh = st.call.hornMesh)
                    when {
                        st.call.state == "ended" -> remoteEnded(c, st.call.reason)
                        st.call.state == "active" && c.caller && c.peerDevice == null && c.pc != null && c.rs.kind == CallRoom.KIND_DM ->
                            activeAsCaller(c, st.call.calleeDevice)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        c.jobs += scope.launch {
            while (live(c) && !c.done) {
                delay(2000)
                for (l in links(c)) {
                    if (l.connected) checkStats(l)
                    bindRemote(l)                           // E3: Spuren ohne Bilder nicht gross zeigen (ruft update)
                }
                if (links(c).isEmpty()) update(c)
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
            if (live(c) && !c.connected) iceFailed(c)
        }
    }

    // ---------------------------------------------------------------- Bedienung

    fun toggleMic() {
        val c = cur ?: return
        if (c.audioTrack == null) return
        c.micOn = !c.micOn
        if (c.micOn) c.rs.mutedBy = null
        applyMic(c)
        // Begleitgeraet: Mikrofon erst jetzt erlaubt -> Vordergrund-Dienst mit Typ microphone neu melden
        if (c.micOn && (c.rs.companion || c.rs.took)) CallService.start(app, c.videoTrack != null)
        c.rs.horn?.micChanged()
        update(c)
        stateAll(c)
    }

    /** Stumm: ohne Systemton die Tonspur aus; mit gemischtem Systemton nur das Mikrofon (die Spur bleibt an) */
    private fun applyMic(c: Call) {
        val m = c.mixer?.takeIf { it.active }
        m?.micMuted = !c.micOn
        c.audioTrack?.setEnabled(c.micOn || m != null)
        // 2.4 E4: Begleitgeraet bzw. Uebernahme vor "bereit" - Mikrofon auch im ADM stumm (kein Ton verlaesst das Geraet)
        adm?.setMicrophoneMute(!c.micOn && m == null)
    }

    /** 2.4 E4: Wiedergabe an/aus (Begleitgeraet: standardmaessig aus, Echo-Schutz) */
    fun togglePlayback() {
        val c = cur ?: return
        setPlayback(c, !c.rs.playback)
        update(c)
    }

    private fun setPlayback(c: Call, on: Boolean) {
        c.rs.playback = on
        adm?.setSpeakerMute(!on)
    }

    /** Nr. 62: Kamera liess sich nicht starten (zaehlt hoch) - die Anrufansicht zeigt dann ihren Hinweis */
    val camFailed = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun toggleCam() {
        val c = cur ?: return
        if (c.done) return
        // Betriebsart A: auf der Kamera-m-Zeile laeuft gerade der Bildschirm - Kamera dort nur lokal schalten
        val ls = links(c)
        if (c.videoTrack != null) {
            for (l in ls) if (!(c.screen != null && l.scrMode == CallScreen.Mode.A)) videoSender(l)?.setTrack(null, false)
            c.rs.horn?.camChanged(null)                     // vor dem Freigeben der Spur
            stopCamera(c)
            CallService.setVideo(app, false)
        } else {
            // Erst den Vordergrund-Dienst auf Typ camera heben, DANN die Kamera starten: sonst stoppt Android die
            // Kamera, sobald die App in den Hintergrund bzw. ins Bild-im-Bild geht (offener Punkt aus 2.3.1)
            CallService.setVideo(app, true)
            if (!startCamera(c)) {
                CallService.setVideo(app, false)
                repo.notify(UiText.Res(R.string.call_no_camera))
                camFailed.value++
                return
            }
            for (l in ls) if (!(c.screen != null && l.scrMode == CallScreen.Mode.A)) videoSender(l)?.setTrack(c.videoTrack, false)
            c.rs.horn?.camChanged(c.videoTrack)
        }
        for (l in ls) {
            l.applied = null
            applyLimits(l)
        }
        update(c)
        stateAll(c)
    }

    fun switchCamera() {
        cur?.capturer?.switchCamera(null)
    }

    // ---------------------------------------------------------------- 2.4 E3: Bildschirm teilen

    /** Teilen moeglich: eine Verbindung steht (Betriebsart A geht mit jeder Gegenstelle) */
    fun canShare(): Boolean = cur?.let { r -> !r.done && (r.rs.horn?.connected == true || links(r).any { it.connected && it.pc != null }) } == true

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
     * 2.4 E5: eine Aufnahme fuer alle Verbindungen; je Verbindung Betriebsart B (beide `reneg` + `screen2`: eigene
     * m-Zeile, Neuverhandlung) bzw. A (der Bildschirm ersetzt die Kamera auf ihrer m-Zeile). Ergebnis false = nicht moeglich.
     */
    fun startScreen(data: android.content.Intent): Boolean {
        val c = cur ?: return false
        if (c.screen != null || !canShare() || c.done) return false
        if (!CallService.setScreen(true)) {
            repo.notify(UiText.Res(R.string.call_screen_failed))
            return false
        }
        val f = factory()
        val (w, h) = screenSize()
        val fps = CallScreen.captureFps(primary(c)?.let { CallV3.worse(it.path, it.peerPath) }, hot())
        var share: ScreenShare? = null
        val cb = object : MediaProjection.Callback() {
            // "Teilen beenden" in der Statusleiste bzw. Projektion vom System beendet
            override fun onStop() {
                scope.launch { if (live(c) && share != null && c.screen === share) stopScreen(c) }
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
            log("screen on ${w}x$h@$fps")
            mixOn(c, cap)
            for (l in links(c)) shareOn(l)
            c.rs.horn?.screenChanged()                      // 2.5: eigener Bildschirm-Sender je Bild-Leitung
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

    /** Laufenden Bildschirm auf eine Verbindung legen (Betriebsart je Verbindung nach deren `caps`) */
    private fun shareOn(c: Call) {
        val s = c.screen ?: return
        val pc = c.pc ?: return
        if (c.scrMode != null) return
        c.scrMode = CallScreen.mode(c.myCaps, c.peerCaps, c.connected && c.peerDevice != null && pc.remoteDescription != null)
        log("screen link ${c.peerDevice?.take(8)} mode=${c.scrMode}")
        if (c.scrMode == CallScreen.Mode.B) {
            // hoechstens eine Bildschirm-m-Zeile je Verbindung: eigene oder vom Gegenueber angelegte wiederverwenden
            val vids = pc.transceivers.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            val reuse = CallScreen.reuseMid(c.scrMid, c.camMid, vids.mapNotNull { it.mid }, c.peerTracks)
            val tr = reuse?.let { m -> vids.firstOrNull { it.mid == m } }
            if (tr != null) {
                c.scrMid = reuse
                tr.sender.setTrack(s.track, false)
                tr.sender.setStreams(listOf(CallScreen.STREAM_SCREEN))
                tr.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            } else {
                c.scrMid = null
                pc.addTransceiver(s.track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(CallScreen.STREAM_SCREEN)))
            }
            c.needGen++
            c.stateDue = true                               // state erst nach dem Angebot (screen_mid)
            offerNow(c)
            stateLater(c)
        } else {
            videoSender(c)?.setTrack(s.track, false)
            scope.launch { if (live(c)) stateOut(c) }
        }
        c.applied = null
        applyLimits(c)
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

    /** Teilen beenden: auf allen Verbindungen zuruecknehmen, dann die Aufnahme freigeben */
    private fun stopScreen(c0: Call) {
        val c = roomOf(c0)
        val s = c.screen ?: return
        c.screen = null
        mixOff(c)
        c.rs.horn?.screenChanged()                          // Spur abnehmen, bevor sie freigegeben wird
        for (l in listOf(c) + c.rs.links.values) shareOff(l)
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
        update(c)
    }

    /**
     * Teilen auf einer Verbindung beenden. B: Spur weg, Richtung recvonly (nicht stoppen, nicht entfernen - die m-Zeile
     * wird beim naechsten Teilen wiederverwendet) -> Neuverhandlung, danach `state` mit screen: false. A: Kamera zurueck.
     */
    private fun shareOff(c: Call) {
        val mode = c.scrMode ?: return
        c.scrMode = null
        c.camPaused = false
        val pc = c.pc
        if (pc != null && !c.done && !c.gone) try {
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
                scope.launch { if (live(c)) stateOut(c) }
            }
        } catch (e: Exception) {
            log("screen stop: ${e.message}")
        }
        c.applied = null
        applyLimits(c)
    }

    /** Zustand spaetestens nach 3 s senden, falls das Angebot ausbleibt (Aushandlung laeuft gerade) */
    private fun stateLater(c: Call) {
        scope.launch {
            delay(3000)
            if (live(c) && c.stateDue) {
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

    /**
     * Signale `bye` fuer alle Verbindungen mit `bye` (ausser except), versiegelt je Ziel - SOFORT (vor finish, das die
     * Verbindungen abbaut); gesendet wird danach mit byePost. null = niemand versteht `bye`.
     */
    private fun byeSeal(r: Call, reason: String, except: String? = null): JsonObject? {
        val sig = HashMap<String, JsonPrimitive>()
        for (l in links(r)) {
            val d = l.peerDevice ?: continue
            if (d == except || !l.established || CallV3.CAP_BYE !in l.peerCaps) continue
            try {
                val one = sealPerDevice(l, listOf(d), buildJsonObject {
                    put("t", "bye")
                    put("reason", reason)
                })
                (one[d] as? JsonPrimitive)?.let { sig[d] = it }
            } catch (_: Exception) {
            }
        }
        return if (sig.isEmpty()) null else JsonObject(sig)
    }

    private suspend fun byePost(id: String, sig: JsonObject?) {
        if (sig == null) return
        try {
            api.post("api/calls/$id/signal", buildJsonObject {
                put("device", myDevId())
                put("signals", sig)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Auflegen. 1:1 am Hauptgeraet: Ende fuer alle (wie bisher). 2.4: als Begleitgeraet bzw. in Gruppen verlaesst nur dieses
     * Geraet den Anruf - `/end` mit `device` (der Server entscheidet), vorher `bye` an alle Verbindungen.
     */
    fun hangup() {
        val c = cur
        if (c == null) {
            if (ring != null) decline() else _ui.value = null
            return
        }
        val id = c.id
        val all = CallRoom.hangupEndsAll(c.rs.kind, c.rs.companion)
        if (id != null && !c.ending) {
            val end = buildJsonObject {
                put("reason", "hangup")
                put("connected", links(c).any { it.connected } || c.rs.horn?.connected == true)
                put("device", myDevId())
            }
            // 2.4: an Gegenstellen mit `bye` zuerst {"t":"bye","reason":"hangup"}, dann wie bisher /end
            val bye = if (c.ready) byeSeal(c, "hangup") else null
            if (bye == null) quietPost("api/calls/$id/end", end) else scope.launch {
                byePost(id, bye)
                quietPost("api/calls/$id/end", end)
            }
        }
        val dur = if (c.t0 > 0) (System.currentTimeMillis() - c.t0) / 1000 else -1
        val msg = when {
            !all -> UiText.Res(R.string.room_you_left)
            dur >= 0 -> UiText.Res(R.string.call_ended_dur, CallLogic.duration(dur))
            else -> UiText.Res(R.string.call_ended)
        }
        finish(c, msg, false)
    }

    fun notifyMicDenied() = repo.notify(UiText.Res(R.string.call_mic_denied))

    /** Oberflaeche geschlossen, Anruf vorbei */
    fun dismiss() {
        if (cur == null && ring == null) _ui.value = null
    }

    private fun securityFail(c: Call) = fail(c, UiText.Res(R.string.call_security_failed), failed = true)

    private fun fail(c: Call, msg: UiText, failed: Boolean = false) {
        if (!live(c)) return
        // 2.4: Fehler auf einer weiteren Verbindung (bzw. im Raum mit mehreren) trennt nur diese
        if (c.room != null || c.established && !c.ending && (c.rs.kind == CallRoom.KIND_GROUP || links(c).size > 1)) {
            dropLink(c, msg)
            return
        }
        if (c.id != null && !c.ending) quietPost("api/calls/${c.id}/end", buildJsonObject {
            put("reason", if (failed) "failed" else "hangup")
            put("connected", c.connected)
            put("device", myDevId())
        })
        finish(c, msg, true)
    }

    private fun remoteEnded(c0: Call, reason: String?) {
        if (!live(c0)) return
        if (c0.room != null && reason == "gone") {          // Signal an ein Geraet, das nicht mehr dabei ist
            dropLink(c0, null)
            return
        }
        val c = roomOf(c0)
        c.ending = true
        val name = c.peer.name.ifEmpty { c.peer.username }
        finish(c, CallLogic.endText(reason, c.caller && (c.rs.kind == CallRoom.KIND_DM && !c.rs.inRoom || c.rs.starter), name,
            if (c.t0 > 0) (System.currentTimeMillis() - c.t0) / 1000 else null), false)
    }

    private fun finish(c0: Call, msg: UiText, error: Boolean) {
        if (c0.room != null) {
            dropLink(c0, msg)
            return
        }
        val c = c0
        if (c.done) return
        c.done = true
        c.ending = true
        c.jobs.forEach { it.cancel() }
        c.rs.horn?.let { h ->                               // 2.5: Beine zum Knoten zu, bevor die eigenen Spuren freigegeben werden
            h.close()
            c.rs.horn = null
            CallTelecom.end()
        }
        hornSinks.clear()
        c.rs.hoJob?.cancel()
        unwatchNetwork()
        stopTone()
        if (!error && c.ready) tone(ToneGenerator.TONE_PROP_PROMPT, 400)
        // E3: Teilen endet mit dem Anruf (Auflegen, Uebergabe, Verlust der Verbindung)
        stopScreen(c)
        unbindAll()
        for (l in listOf(c) + c.rs.links.values) {
            l.connJob?.cancel()
            l.outJob?.cancel()
            l.restartJob?.cancel()
            l.failJob?.cancel()
            l.tileVideo?.removeSink(l.tile)
            l.tileVideo = null
            for ((m, t) in l.rvid) l.watch[m]?.let { t.removeSink(it) }
            l.rvid.clear()
            l.remoteVideo = null
            l.smallVideo = null
            try {
                l.pc?.close()
                l.pc?.dispose()
            } catch (_: Exception) {
            }
            l.pc = null
        }
        c.rs.links.clear()
        stopCamera(c)
        c.audioTrack?.dispose()
        c.audioSource?.dispose()
        c.videoSource?.dispose()
        c.surface?.dispose()
        c.audioTrack = null
        // ADM lebt ueber Anrufe hinweg: Begleitgeraet-Stumm nicht haengen lassen
        adm?.setSpeakerMute(false)
        adm?.setMicrophoneMute(false)
        if (cur === c) cur = null
        audioOff()
        CallService.stop(app)
        val st = _ui.value
        _ui.value = (st ?: CallUiState(CallPhase.ENDED, c.id, c.chatId, c.caller, c.media == "video", c.peer))
            .copy(phase = CallPhase.ENDED, message = msg, error = error, remoteVideo = false, cam = false, room = null)
        val id = c.id
        if (id != null) gone.add(id)
        CallLogic.endDismissMs(error, c.rs.stickyEnd)?.let { ms -> scope.launch {
            delay(ms)
            val now = _ui.value
            if (now != null && now.phase == CallPhase.ENDED && cur == null && ring == null) _ui.value = null
        } }
        repo.chat.chatsLoaded.value.let { if (it) scope.launch { io.github.yggdrasil_sh.saga.chat.quietly { repo.chat.loadChats() } } }
        refreshRun()
    }

    private fun update(c0: Call, msg: UiText? = _ui.value?.message, reconnecting: Boolean? = null) {
        if (!live(c0)) return
        val c = roomOf(c0)
        val st = _ui.value ?: return
        val ls = links(c)
        val p = primary(c) ?: c
        val horn = c.rs.horn
        val anyConn = ls.any { it.connected } || horn?.connected == true
        if (horn?.connected == true && c.t0 == 0L) {
            c.t0 = System.currentTimeMillis()
            stopTone()
            watchNetwork(c)
            CallTelecom.active()
        }
        val group = c.rs.kind == CallRoom.KIND_GROUP
        val phase = when {
            anyConn -> CallPhase.CONNECTED
            c.rs.inRoom || group -> CallPhase.CONNECTING
            c.caller && c.ready && c.peerDevice == null -> CallPhase.RINGING
            c.caller && !c.ready -> CallPhase.PREPARING
            else -> CallPhase.CONNECTING
        }
        val v = p.view
        val mesh = meshView(c)
        // TT 65: sind andere laut Liste schon da, aber ohne Verbindung, nicht weiter "Klingelt bei den anderen"
        val others = c.rs.roster.legs.count { it.device != myDevId() }
        val waiting = when (CallRoom.waitState(phase == CallPhase.CONNECTING, c.ready, group, c.rs.starter, ls.size, others,
            c.rs.problem != null)) {
            CallRoom.Wait.PROBLEM -> c.rs.problem
            CallRoom.Wait.CONNECTING -> UiText.Res(R.string.room_connecting_others)
            CallRoom.Wait.RINGING -> UiText.Res(R.string.room_ringing_others)
            CallRoom.Wait.WAITING -> UiText.Res(R.string.room_waiting)
            CallRoom.Wait.NONE -> null
        }
        _ui.value = st.copy(
            phase = phase, id = c.id, peer = if (group) c.peer else p.peer, video = c.media == "video",
            mic = c.micOn, cam = c.videoTrack != null,
            sharing = c.screen != null, peerSharing = p.rScreen && p.connected, camPaused = ls.any { it.camPaused },
            remoteSmall = p.smallVideo != null && !mesh, bigIsScreen = p.rScreen && v?.bigScreen == true,
            message = if (phase == CallPhase.CONNECTED && reconnecting != true && !st.reconnecting) null
            else if (phase == CallPhase.RINGING) UiText.Res(R.string.call_ringing) else waiting ?: msg,
            connectedAt = if (anyConn) c.t0 else 0,
            reconnecting = reconnecting ?: st.reconnecting,
            remoteVideo = p.remoteVideo != null && (if (p.gotState) p.rCam || p.rScreen else c.media == "video"),
            remoteMic = p.rMic,
            room = roomUi(c, mesh),
        ).let { u ->
            if (horn == null) u else u.copy(
                remoteVideo = horn.bigOn, path = "", quality = "", bytesIn = horn.bytesIn,
                verified = horn.connected && horn.unverified.isEmpty() && horn.runes != null,
                message = if (anyConn && horn.note == null && reconnecting != true) null else horn.note ?: u.message,
            )
        }
    }

    /** Raum-Teil der Oberflaeche: Kacheln, Begleitgeraet, Grenzen; null = einfacher 1:1-Anruf wie bisher */
    private fun roomUi(c: Call, mesh: Boolean): RoomUi? {
        val rs = c.rs
        val ls = links(c)
        if (!multi(c) && rs.kind == CallRoom.KIND_DM) return null
        val me = myUser()
        val tiles = shown(c).map { l ->
            val x = legOf(l)
            CallTile(
                device = l.peerDevice.orEmpty(), name = (x?.name ?: l.peer.name).ifEmpty { l.peer.username }, devName = x?.devName.orEmpty(),
                video = l.remoteVideo != null && (l.rCam || l.rScreen), mic = l.rMic, speaking = mesh && rs.speaker == l.peerDevice,
                companion = x?.companion == true, own = own(l), connected = l.connected, screen = l.rScreen,
            )
        }
        val ownOther = rs.roster.legs.any { it.who == me && it.device != myDevId() }
        val limit = if (rs.limit > 0) rs.limit else config?.endpoints(c.media) ?: CallRoom.limit(c.media, null)
        return RoomUi(
            kind = rs.kind, mesh = mesh, tiles = tiles, companion = rs.companion, playback = rs.playback,
            canTakeOver = rs.companion && ownOther && rs.hoRep == null, takingOver = rs.hoRep != null,
            endpoints = maxOf(rs.roster.legs.size, ls.size + 1), limit = limit, old = rs.old,
            speaker = rs.horn?.let { h -> (h.pin ?: h.speaker)?.let { rs.roster.of(it)?.name } }
                ?: rs.speaker?.let { sp -> ls.firstOrNull { it.peerDevice == sp } }?.let { legOf(it)?.name ?: it.peer.name }.orEmpty(),
            horn = rs.horn?.let { hornUi(c, it) },
            dm = rs.dm, title = rs.title, mutedBy = rs.mutedBy,
        )
    }

    // ---------------------------------------------------------------- 2.5 Gjallarhorn: Gruppenanruf ueber Knoten

    /**
     * Beitritt zu einem Knoten-Anruf: POST /join OHNE Angebote (Fehler 409 mit join_block wie im Mesh), die Antwort
     * traegt die Kandidaten. Danach misst und verbindet die [HornSession].
     */
    private suspend fun hornJoin(c: Call, role: String, cfg: CallConfig) {
        if (CallRoom.CAP_HORN !in c.myCaps) throw UiTextException(UiText.Res(R.string.room_server_old))
        // "Hierher uebernehmen" auch ueber einen Knoten (wie Web/iOS): join mit replaces; sobald der Knoten verbunden ist,
        // bekommt das alte Geraet state.ready + replaces ueber den Signalweg (hoCheck)
        val rep = c.rs.hoRep.takeIf { role == CallRoom.ROLE_HANDOVER }
        if (rep == null) {
            c.rs.hoRep = null
            c.rs.hoJoin = false
        }
        val me = myDevId()
        val r = try {
            json.decodeFromString(CallWrap.serializer(), api.post("api/calls/${c.id}/join", buildJsonObject {
                put("device", me)
                put("role", if (rep != null) CallRoom.ROLE_HANDOVER else if (role == CallRoom.ROLE_HANDOVER) CallRoom.ROLE_MEMBER else role)
                if (rep != null) put("replaces", rep)
                put("caps", capsJson(c))
                put("dev_class", hornClass())
            }))
        } catch (e: ApiException) {
            val o = try {
                Json.parseToJsonElement(e.body) as? JsonObject
            } catch (_: Exception) {
                null
            }
            val block = CallRoom.str(o, "join_block")
            if (e.code == 409 && block != null) {
                val lim = (o?.get("limit") as? JsonPrimitive)?.intOrNull ?: c.rs.limit
                throw UiTextException(CallRoom.blockText(block, lim))
            }
            if (e.code == 429) throw UiTextException(UiText.Res(R.string.call_rate))
            throw e
        }
        if (!live(c)) {
            quietPost("api/calls/${c.id}/leave", buildJsonObject { put("device", me) })
            return
        }
        c.ready = true
        val k = r.call ?: throw UiTextException(UiText.Res(R.string.call_failed))
        k.limit?.let { c.rs.limit = it }
        rosterIn(c, k.legs, null, null, poll = true, mesh = k.hornMesh)
        startLoops(c)
        hornStart(c, k)
        if (c.rs.hoRep != null) {
            c.rs.hoStart = System.currentTimeMillis()
            c.rs.hoJob = scope.launch {
                // bis der Knoten verbunden ist (hornChanged ruft hoCheck), hoechstens handover_s
                val end = System.currentTimeMillis() + (config?.handoverS ?: CallRoom.HANDOVER_S) * 1000L
                while (live(c) && c.rs.hoRep != null && System.currentTimeMillis() < end) {
                    hoCheck(c)
                    delay(300)
                }
                if (c.rs.hoRep != null) hoFail(c)
            }
        }
        val early = ArrayList(c.early)
        c.early.clear()
        for (s in early) if (live(c)) route(c, s)
        update(c, UiText.Res(R.string.horn_connecting))
    }

    /** Last-N nach Geraeteklasse: Handy 4, Tablet (ab 600 dp) 9 */
    private fun hornMobile(): Boolean = app.resources.configuration.smallestScreenWidthDp < 600
    private fun hornClass(): String = if (hornMobile()) "mobile" else "desktop"

    private fun hornStart(c: Call, k: CallInfo) {
        if (c.rs.horn != null || c.id == null) return
        c.rs.hornHost = k.host
        c.rs.hornHostWho = if (k.host) myUser() else k.peer.username
        k.limit?.let { c.rs.limit = it }                    // Knoten-Anruf: Grenze des Anrufs (12/25 bzw. 50/100)
        val s = HornSession(hornHostOf(c), c.id!!, HornLogic.parseBlock(k.horn, allowHttp = BuildConfig.DEBUG))
        c.rs.horn = s
        log("horn start ${s.callId.take(8)} cands=${HornLogic.parseBlock(k.horn, BuildConfig.DEBUG)?.candidates?.size}")
        CallTelecom.start(app, c.rs.title.ifEmpty { c.peer.name }, c.id!!, c.media == "video")
        s.start()
        update(c)
    }

    /** Verbindung der Sitzung zum CallManager (Medien, Signale, Kandidaten, Oberflaeche) */
    private fun hornHostOf(r: Call): HornSession.Host = object : HornSession.Host {
        override val scope: kotlinx.coroutines.CoroutineScope get() = this@CallManager.scope
        override val factory: PeerConnectionFactory get() = this@CallManager.factory()
        override fun myDev(): String = myDevId()
        override fun mobile(): Boolean = hornMobile()
        override fun lowPower(): Boolean = hot() || (app.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true)
        override fun legs(): List<CallLeg> = r.rs.roster.legs
        override fun audioTrack(): AudioTrack? = r.audioTrack
        override fun camTrack(): VideoTrack? = r.videoTrack
        override fun screenTrack(): VideoTrack? = r.screen?.track
        override fun myState(): Triple<Boolean, Boolean, Boolean> = Triple(r.micOn && !r.rs.companion, r.videoTrack != null, r.screen != null)

        override suspend fun cands(node: String?, avoid: List<String>): List<HornLogic.Cand> {
            val t = api.post("api/calls/${r.id}/horn", buildJsonObject {
                put("device", myDevId())
                put("dev_class", hornClass())
                if (node != null) put("node", node)
                put("avoid", JsonArray(avoid.map { JsonPrimitive(it) }))
            })
            val o = Json.parseToJsonElement(t) as? JsonObject
            return HornLogic.parseBlock(o?.get("horn"), BuildConfig.DEBUG)?.candidates.orEmpty()
        }

        override suspend fun sendSignals(bodies: Map<String, String>) = hornSend(r, bodies)

        override suspend fun signPk(dev: String): ByteArray? {
            if (dev == myDevId()) return keys().dev.signPk
            val h = hornHolder(r, dev) ?: return null
            return try {
                if (h.peerDevs[dev] == null) peerKeys(h)
                if (h.peerDevs[dev] == null) peerKeys(h, fresh = true)
                h.peerDevs[dev]?.sign
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        override fun changed() = hornChanged(r)
        override fun note(text: UiText?) = update(r)
        override fun lost(text: UiText) {
            r.rs.stickyEnd = true                           // TT 67: Grund (z. B. Zertifikat) bleibt stehen
            fail(r, text, failed = true)
        }
        override fun ended() = remoteEnded(r, null)
        override fun removedHint() {
            scope.launch {
                try {
                    val st = json.decodeFromString(CallState.serializer(), api.get("api/calls/${r.id}?device=${myDevId()}"))
                    if (!live(r)) return@launch
                    when {
                        st.call.state == "ended" -> remoteEnded(r, st.call.reason)
                        myDevId() !in st.call.mine -> hornRemoved(r, null)
                        else -> r.rs.horn?.switch("lost")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    r.rs.horn?.switch("lost")
                }
            }
        }

        override fun refreshRoster() {
            scope.launch { fetchRoster(r) }
        }

        override fun tileSink(dev: String): ProxySink = hornSinks.getOrPut(dev) { ProxySink() }
        override val bigSink: ProxySink get() = remoteSink
    }

    /** Oberflaeche gebuendelt neu (die Sitzung meldet viele kleine Aenderungen) */
    private var hornUiJob: Job? = null

    private fun hornChanged(r: Call) {
        if (hornUiJob != null) return
        hornUiJob = scope.launch {
            delay(80)
            hornUiJob = null
            if (live(r)) update(r)
        }
    }

    /** Schluessel-Halter eines Geraets (Person laut Teilnehmerliste) fuer Versiegeln/Oeffnen - keine Verbindung */
    private fun hornHolder(r: Call, dev: String): Call? {
        val x = r.rs.roster.of(dev) ?: return null
        return r.rs.hornKeys.getOrPut(dev) { newLink(r, dev, x, caller = false) }
    }

    /** Bodies je Zielgeraet versiegeln (nur Geraete mit gueltigem Geraete-Zertifikat), EIN POST /signal */
    private suspend fun hornSend(r: Call, bodies: Map<String, String>) {
        val id = r.id ?: return
        if (bodies.isEmpty() || r.ending || !live(r)) return
        val sig = LinkedHashMap<String, JsonPrimitive>()
        for ((d, b) in bodies) {
            val h = hornHolder(r, d) ?: continue
            try {
                if (h.peerDevs[d] == null) peerKeys(h)
                if (h.peerDevs[d] == null) peerKeys(h, fresh = true)
                if (h.peerDevs[d] == null) continue           // Geister-Geraet: bekommt keinen Schluessel
                sig[d] = JsonPrimitive(withContext(Dispatchers.Default) { seal(h, d, b) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("horn seal ${d.take(8)}: ${e.message}")
            }
        }
        if (sig.isEmpty() || !live(r)) return
        try {
            api.post("api/calls/$id/signal", buildJsonObject {
                put("device", myDevId())
                put("signals", JsonObject(sig))
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("horn signal: ${e.message}")
        }
    }

    /** Signal im Knoten-Anruf oeffnen (Absender laut Teilnehmerliste, signiert) und an die Sitzung */
    private fun hornRoute(r: Call, s: CallSignalDto) {
        val h = hornHolder(r, s.fromDevice)
        if (h == null) {
            val q = r.rs.pend.getOrPut(s.fromDevice) { ArrayList() }
            q.add(s)
            if (q.size > 80) q.removeAt(0)
            scope.launch {
                fetchRoster(r)
                hornFlush(r)
            }
            return
        }
        scope.launch {
            val b = openSig(h, s) ?: return@launch
            if (!live(r)) return@launch
            // "Hierher uebernehmen" im Knoten-Anruf: das neue eigene Geraet meldet ready + replaces = dieses Geraet
            if ((b["t"] as? JsonPrimitive)?.contentOrNull == "state" && (b["ready"] as? JsonPrimitive)?.booleanOrNull == true &&
                (b["replaces"] as? JsonPrimitive)?.contentOrNull == myDevId() && r.rs.roster.of(s.fromDevice)?.who == myUser()) {
                handoverOut(r, s.fromDevice)
                return@launch
            }
            r.rs.horn?.signal(s.fromDevice, b)
        }
    }

    /** Wartende Signale von Geraeten, die jetzt in der Teilnehmerliste stehen */
    private fun hornFlush(r: Call) {
        if (r.rs.horn == null) return
        for (d in r.rs.pend.keys.toList()) {
            if (r.rs.roster.of(d) == null) continue
            val list = r.rs.pend.remove(d) ?: continue
            for (s in list) hornRoute(r, s)
        }
    }

    private fun hornUi(c: Call, s: HornSession): HornUi {
        val me = myDevId()
        val legs = c.rs.roster.legs
        val byDev = legs.associateBy { it.device }
        val others = legs.filter { it.device != me }.map { it.device }
        val pin = s.pin?.takeIf { it in byDev }
        val lay = HornLogic.tiles(others, s.moving, pin ?: s.speaker, s.view)
        fun nm(x: CallLeg) = x.name.ifEmpty { x.who }
        val tiles = lay.list.mapNotNull { t ->
            val x = byDev[t.dev] ?: return@mapNotNull null
            HornTile(t.dev, nm(x), x.devName, x.avatar, video = t.moving, mic = s.st[t.dev]?.first != false, speaking = t.dev in s.speaking, big = t.big)
        }
        val people = legs.map { x ->
            val mine = x.device == me
            HornPerson(
                x.device, nm(x), x.devName, x.avatar, me = mine, mic = if (mine) c.micOn && !c.rs.companion else s.st[x.device]?.first != false,
                companion = x.companion, host = x.who.isNotEmpty() && x.who == c.rs.hornHostWho, speaking = x.device in s.speaking,
                unverified = x.device in s.unverified, moderate = c.rs.hornHost && !mine && x.who != myUser(),
            )
        }
        val net = s.net
        return HornUi(
            connected = s.connected, path = s.path, node = s.node, quality = s.q, rtt = net?.rtt?.toInt(), lossPct = (net?.loss ?: 0.0) * 100,
            changing = s.changing, tiles = tiles, more = lay.more, view = s.view,
            runes = s.runes?.runes.orEmpty(), runeNames = s.runes?.idx?.map { HornE2E.runeNames[it] }.orEmpty(),
            unverified = s.unverified.mapNotNull { byDev[it]?.let(::nm) }, people = people, amHost = c.rs.hornHost,
            screenFrom = s.screenFrom?.let { d -> byDev[d]?.let(::nm) }, note = s.note, bigVideo = s.bigOn,
        )
    }

    /** Oberflaeche: Kachel- bzw. Sprecheransicht umschalten */
    fun hornToggleView() {
        val c = cur ?: return
        val h = c.rs.horn ?: return
        h.view = if (h.view == "speaker") "grid" else "speaker"
        if (h.view == "grid") h.pin = null
        h.mapAll()
    }

    /** Oberflaeche: Kachel antippen -> diese Person gross (Sprecheransicht); nochmal -> zurueck */
    fun hornPin(dev: String) {
        val c = cur ?: return
        val h = c.rs.horn ?: return
        h.pin = if (h.pin == dev) null else dev
        h.view = if (h.pin != null) "speaker" else h.view
        h.mapAll()
    }

    /** Gastgeber: Geraet stummschalten bzw. Person entfernen (POST /moderate) */
    fun moderate(target: String, action: String) {
        val c = cur ?: return
        val id = c.id ?: return
        val x = c.rs.roster.of(target) ?: return
        val name = x.name.ifEmpty { x.who }
        scope.launch {
            try {
                api.post("api/calls/$id/moderate", buildJsonObject {
                    put("device", myDevId())
                    put("target", target)
                    put("action", action)
                })
                repo.notify(UiText.Res(if (action == "mute") R.string.horn_muted_done else R.string.horn_removed_done, name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.notify(repo.friendly(e))
            }
        }
    }

    /** call.mod: Gastgeber hat dieses Geraet stummgeschaltet bzw. diese Person entfernt */
    private fun hornModIn(ev: LiveEvent) {
        val c = cur ?: return
        if (c.id != ev.str("call_id") || c.done) return
        val by = ev.str("by")
        val me = myDevId()
        when (ev.str("action")) {
            "mute" -> if (ev.str("device") == me) {
                if (c.micOn) {
                    c.micOn = false
                    applyMic(c)
                    c.rs.horn?.micChanged()
                    update(c)
                }
                c.rs.mutedBy = by ?: "?"
                update(c)
                repo.notify(UiText.Res(R.string.horn_muted_by, by ?: "?"))
            }
            "remove" -> scope.launch {
                try {
                    val st = json.decodeFromString(CallState.serializer(), api.get("api/calls/${c.id}?device=$me"))
                    if (live(c) && me !in st.call.mine) hornRemoved(c, by)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun hornRemoved(c: Call, by: String?) {
        if (!live(c)) return
        c.ending = true
        finish(c, if (by != null) UiText.Res(R.string.horn_removed_by, by) else UiText.Res(R.string.horn_removed), false)
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
                val group = ev.str("kind") == CallRoom.KIND_GROUP
                // Gruppe: "active" heisst nur "zwei sind dabei" - weiter klingeln; "declined" = auf anderem Geraet abgelehnt
                if (state == "ended" || state == "declined" || state == "active" && !group) {
                    unring(id, if (state == "ended" && ev.str("reason") == "cancelled") UiText.Res(R.string.call_missed) else null)
                }
                if (state == "ended") markEnded(id)
                refreshRun()
                val c = cur ?: return
                if (c.id != id) return
                val room = c.rs.inRoom || c.rs.kind == CallRoom.KIND_GROUP
                when {
                    state == "ended" -> remoteEnded(c, ev.str("reason"))
                    // Uebernahme im 1:1 (`moved`): das neue Geraet bietet selbst an - nichts zu tun
                    ev.str("moved") != null -> {}
                    state == "active" && c.caller && !room -> activeAsCaller(c, ev.str("device"))
                    state == "active" && !c.caller && !c.ready && ev.str("device") != repo.chat.keys()?.dev?.id ->
                        finish(c, UiText.Res(R.string.call_answered_elsewhere), false)
                }
            }
            // 2.4 E4/E5: Teilnehmerliste (laufender Anruf, Leiste auf anderen Geraeten, Klingeln beenden)
            "call.roster" -> {
                val id = ev.str("call_id") ?: return
                val legs = CallRoom.legsOf(ev.data["legs"])
                if (CallRoom.stopRinging(legs, myUser())) unring(id)          // ein eigenes Geraet ist beigetreten
                val c = cur
                if (c != null && c.id == id && c.ready) {
                    rosterIn(c, legs, CallRoom.rvOf(ev.data), ev.int("limit"), mesh = ev.str("horn_mesh"))
                }
                if (ev.str("state") == "ended") {
                    unring(id)
                    markEnded(id)
                }
                refreshRun()
            }
            "call.invite" -> onInvite(ev)
            // 2.5 Gjallarhorn: Gastgeber hat stummgeschaltet/entfernt; Knoten wird geleert bzw. entfernt (Umzug)
            "call.mod" -> hornModIn(ev)
            "call.horn_move" -> {
                val c = cur ?: return
                if (c.id == ev.str("call_id")) c.rs.horn?.moveAway()
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
