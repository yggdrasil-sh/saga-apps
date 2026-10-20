package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Bildschirm uebertragen (Saga 2.4, Etappe E3) - reine Regeln ohne Android/WebRTC (Unit-Tests). Vertrag: Server
 * docs/APP-API.md, Abschnitt "Anrufe: Bildschirm v2". Zwei Betriebsarten:
 *  - B: eigene Video-m-Zeile fuer den Bildschirm (Neuverhandlung), nur wenn BEIDE Seiten `reneg` und `screen2` melden
 *    und die erste Aushandlung fertig ist. Die Kamera laeuft daneben gedrosselt weiter.
 *  - A: wie 2.1/2.3 - der Bildschirm ersetzt die Kamera auf ihrer m-Zeile (replaceTrack), kein Angebot.
 * Es gibt hoechstens eine Bildschirm-m-Zeile je Verbindung; beim Ende geht sie auf recvonly und wird wiederverwendet.
 * Systemton wird in die vorhandene Tonspur gemischt (keine dritte m-Zeile).
 */
object CallScreen {
    const val CAP = "screen2"
    const val ROLE_MIC = "mic"
    const val ROLE_CAM = "cam"
    const val ROLE_SCREEN = "screen"
    private val ROLES = setOf(ROLE_MIC, ROLE_CAM, ROLE_SCREEN)
    private val MID = Regex("^[A-Za-z0-9_-]{1,16}$")
    private const val MAX_TRACKS = 8

    /** Stream-IDs der eigenen Spuren: Ton und Kamera teilen einen Stream, der Bildschirm hat einen eigenen */
    const val STREAM_CAM = "saga"
    const val STREAM_SCREEN = "saga-screen"

    /** Spur-ID der Bildschirm-Spur (erkennt den neuen Transceiver vor setLocalDescription) */
    const val TRACK_SCREEN = "saga-s"

    enum class Mode { A, B }

    fun midOk(s: String?): Boolean = s != null && MID.matches(s)

    /** Betriebsart B moeglich: beide Seiten melden `reneg` UND `screen2` */
    fun can(mine: Collection<String>, peer: Collection<String>): Boolean =
        CallV3.canReneg(mine, peer) && CAP in mine && CAP in peer

    /** Betriebsart beim Start des Teilens; established = erste Aushandlung fertig (verbunden, Geraet bekannt) */
    fun mode(mine: Collection<String>, peer: Collection<String>, established: Boolean): Mode =
        if (established && can(mine, peer)) Mode.B else Mode.A

    /** `tracks` des Gegenuebers: {mid: "mic"|"cam"|"screen"}, hoechstens 8, Unbekanntes ignorieren */
    fun tracksOf(el: JsonElement?): Map<String, String> {
        val o = el as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in o) {
            if (out.size >= MAX_TRACKS) break
            val r = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
            if (midOk(k) && r in ROLES) out[k] = r
        }
        return out
    }

    /** `msid` des Gegenuebers: {mid: Stream-ID} (nur zur Kontrolle/Anzeige; massgeblich ist die mid) */
    fun msidOf(el: JsonElement?): Map<String, String> {
        val o = el as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in o) {
            if (out.size >= MAX_TRACKS) break
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
            if (midOk(k) && s.isNotEmpty() && s.length <= 64) out[k] = s
        }
        return out
    }

    /** `screen_mid` aus `state` (nur gueltig bei screen == true) */
    fun screenMid(screen: Boolean, el: JsonElement?): String? {
        if (!screen) return null
        val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return s?.takeIf { midOk(it) }
    }

    /**
     * mid -> Stream-ID aus einer SDP (`a=mid:` und `a=msid:<Stream-ID> <Spur-ID>` je m-Abschnitt). m-Abschnitte ohne
     * a=msid (nur empfangen) fehlen. `a=msid:-` (keine Streams) zaehlt nicht.
     */
    fun sdpMsids(sdp: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var mid: String? = null
        var msid: String? = null
        fun flush() {
            val m = mid
            val s = msid
            if (m != null && s != null && midOk(m)) out[m] = s
            mid = null
            msid = null
        }
        for (raw in sdp.orEmpty().split('\n')) {
            val l = raw.trimEnd('\r')
            when {
                l.startsWith("m=") -> flush()
                l.startsWith("a=mid:") -> mid = l.substring(6).trim()
                l.startsWith("a=msid:") && msid == null -> {
                    val id = l.substring(7).trim().substringBefore(' ')
                    if (id.isNotEmpty() && id != "-") msid = id
                }
            }
        }
        flush()
        return out
    }

    /** Eigene Rollen je m-Zeile: Ton, Kamera, Bildschirm (null = (noch) keine mid) */
    fun myTracks(micMid: String?, camMid: String?, screenMid: String?): Map<String, String> = buildMap {
        if (micMid != null && midOk(micMid)) put(micMid, ROLE_MIC)
        if (camMid != null && midOk(camMid)) put(camMid, ROLE_CAM)
        if (screenMid != null && midOk(screenMid) && screenMid != camMid) put(screenMid, ROLE_SCREEN)
    }

    /**
     * Bildschirm-m-Zeile zum Wiederverwenden: die eigene aus einem frueheren Teilen, sonst eine Video-m-Zeile (nicht die
     * Kamera), die das Gegenueber in seinen `tracks` als "screen" fuehrt. null = neuen Transceiver anlegen.
     * videoMids = mids der Video-Transceiver in Reihenfolge.
     */
    fun reuseMid(own: String?, camMid: String?, videoMids: List<String>, peerTracks: Map<String, String>): String? {
        if (own != null && own in videoMids && own != camMid) return own
        return videoMids.firstOrNull { it != camMid && peerTracks[it] == ROLE_SCREEN }
    }

    /** Kamera-mid des Gegenuebers: Rolle "cam" aus seinen `tracks`, sonst (aeltere Gegenstelle) die erste Video-m-Zeile */
    fun peerCamMid(peerTracks: Map<String, String>, videoMids: List<String>): String? =
        peerTracks.entries.firstOrNull { it.value == ROLE_CAM }?.key ?: videoMids.firstOrNull()

    /**
     * Anzeige beim Empfaenger: welche Fernspur gross, welche klein. big/small = mid. bigScreen = gross laeuft der Bildschirm.
     * screenMid = Spur des Bildschirms, camMid = Spur der Kamera (falls daneben).
     */
    data class View(val big: String?, val small: String?, val bigScreen: Boolean, val screenMid: String?, val camMid: String?)

    /**
     * Zuordnung nach Vertrag: state.screen + screen_mid -> diese mid ist der Bildschirm, die "cam"-mid (falls cam) die
     * Kamera; screen ohne screen_mid (2.1/2.3/E0, Betriebsart A) -> die einzige Videospur ist der Bildschirm; ohne
     * `tracks` ist die erste Video-m-Zeile die Kamera. Eine Spur ohne Bilder (live fehlt) nicht gross zeigen.
     * Bildschirm gross, Kamera klein; swap tauscht (nicht im Bild-im-Bild - dort immer der Bildschirm).
     */
    fun view(
        peerTracks: Map<String, String>, videoMids: List<String>, live: Set<String>, screen: Boolean, screenMid: String?,
        cam: Boolean, swap: Boolean, pip: Boolean,
    ): View {
        val camMid = peerCamMid(peerTracks, videoMids)
        if (!screen) return View(camMid, null, false, null, camMid)
        var scr: String?
        var camM: String? = camMid
        if (screenMid == null) {
            scr = camMid                                    // Betriebsart A bzw. aeltere Gegenstelle
            camM = null
        } else {
            scr = screenMid.takeIf { it in videoMids }
            if (scr == camM) camM = null                    // Betriebsart A mit screen_mid = Kamera-mid
        }
        val scrOn = scr?.takeIf { it in live }
        val camOn = camM?.takeIf { cam && it in live }
        return when {
            scrOn != null && camOn != null ->
                if (swap && !pip) View(camOn, scrOn, false, scr, camM) else View(scrOn, camOn, true, scr, camM)
            scrOn != null -> View(scrOn, null, true, scr, camM)
            camOn != null -> View(camOn, null, false, scr, camM)    // Bildschirm liefert noch keine Bilder
            else -> View(scr ?: camM, null, scr != null, scr, camM)
        }
    }

    // ------------------------------------------------------------ Aufnahme und Bitrate

    /** Aufnahmegroesse: lange Kante hoechstens maxLong (1080p), Seitenverhaeltnis bleibt, gerade Werte */
    fun captureSize(w: Int, h: Int, maxLong: Int = 1920): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return Pair(1280, 720)
        val long = maxOf(w, h)
        val f = if (long > maxLong) maxLong.toDouble() / long else 1.0
        fun even(x: Double) = maxOf(2, (Math.round(x).toInt()) and 1.inv())
        return Pair(even(w * f), even(h * f))
    }

    /** Bildrate der Aufnahme: 15 fps, ueber Relais TCP 8 fps; heiss (THERMAL_STATUS_MODERATE+) 5 fps */
    fun captureFps(path: CallV3.Path?, hot: Boolean = false): Int = when {
        hot -> 5
        path == CallV3.Path.RELAY_TCP -> 8
        else -> 15
    }

    /** Kamera daneben (nur B) ueber Relais TCP aus - Hinweis "Kamera pausiert (langsame Verbindung)" beim Teilenden */
    fun camPaused(mode: Mode?, path: CallV3.Path?, cam: Boolean): Boolean =
        mode == Mode.B && cam && path == CallV3.Path.RELAY_TCP

    /** Hoechstrate der Kamera neben dem Bildschirm (Betriebsart B), bit/s */
    const val SHARE_CAM_BPS = 300_000

    /**
     * Systemton in die Mikrofon-Samples mischen (PCM 16 bit, gleiche Rate/Kanaele): mic[i] = mic[i] + sys[i] mit
     * Saettigung. micMuted = nur das Mikrofon stumm (der Systemton laeuft weiter). sysLen < n: Rest ohne Systemton.
     */
    fun mixPcm16(mic: ShortArray, n: Int, sys: ShortArray, sysLen: Int, micMuted: Boolean) {
        for (i in 0 until n) {
            val m = if (micMuted) 0 else mic[i].toInt()
            val s = if (i < sysLen) sys[i].toInt() else 0
            mic[i] = (m + s).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
