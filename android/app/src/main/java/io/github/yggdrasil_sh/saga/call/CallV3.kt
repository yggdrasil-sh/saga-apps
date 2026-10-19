package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * saga-call v3 (Saga 2.4, Etappe E0) - reine Regeln ohne Android/WebRTC (Unit-Tests). Das Signier- und
 * Versiegelungsformat bleibt v1/v2; neu sind nur Felder im Body: `caps`, `pc`, `n` (Neuverhandlung), `state.pip`,
 * `state.want.video` und der Typ `bye`. Neuverhandlung (ICE-Restart) nur, wenn BEIDE Geraete `reneg` melden - gegenueber
 * 2.3-Apps bleibt alles wie bisher. Unbekannte `t`-Typen werden ignoriert.
 */
object CallV3 {
    const val CAP_RENEG = "reneg"
    const val CAP_PIP_HINT = "pip_hint"

    /** Signaltypen; alles Unbekannte -> UNKNOWN (wird ignoriert, Vorwaertskompatibilitaet) */
    enum class Kind { OFFER, ANSWER, ICE, STATE, BYE, UNKNOWN }

    fun kind(t: String?): Kind = when (t) {
        "offer" -> Kind.OFFER
        "answer" -> Kind.ANSWER
        "ice" -> Kind.ICE
        "state" -> Kind.STATE
        "bye" -> Kind.BYE
        else -> Kind.UNKNOWN
    }

    /** Server-Fahigkeiten aus GET /api/calls/config (`features`); fehlt das Feld (Server 2.3) -> leer */
    fun features(el: kotlinx.serialization.json.JsonElement?): Set<String> =
        (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()

    /** Eigene Faehigkeiten fuer den Body: `reneg` nur, wenn auch der eigene Server es kann (alle Ebenen, Konzept §1.1) */
    fun myCaps(serverFeatures: Set<String>): List<String> = buildList {
        if (CAP_RENEG in serverFeatures) add(CAP_RENEG)
        add(CAP_PIP_HINT)
    }

    /** `caps` aus einem Angebot/einer Antwort; aeltere Apps senden keine -> leer */
    fun caps(b: JsonObject?): Set<String> =
        (b?.get("caps") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()

    /** Neuverhandlung erlaubt: beide Seiten melden `reneg` */
    fun canReneg(mine: Collection<String>, peer: Collection<String>): Boolean = CAP_RENEG in mine && CAP_RENEG in peer

    /** Perfect Negotiation: hoeflich ist die lexikographisch groessere device_id */
    fun polite(myDevice: String, peerDevice: String): Boolean = myDevice > peerDevice

    /** Neuverhandelte SDP muss dasselbe (signierte) DTLS-Zertifikat tragen wie beim Aufbau */
    fun sameFingerprints(now: List<String>, before: List<String>): Boolean =
        now.isNotEmpty() && now.map { it.lowercase() }.toSet() == before.map { it.lowercase() }.toSet()

    /** Gewuenschte Videogroesse des Gegenuebers (`state.want.video`); fehlt sie (2.3) -> "large" */
    fun peerWant(b: JsonObject?): String {
        val w = ((b?.get("want") as? JsonObject)?.get("video") as? JsonPrimitive)?.contentOrNull
        return if (w == "off" || w == "small" || w == "large") w else "large"
    }

    private val UFRAG_SDP = Regex("""^a=ice-ufrag:(\S+)""", RegexOption.MULTILINE)
    private val UFRAG_CAND = Regex("""\bufrag (\S+)""")

    /** ICE-Benutzernamen (ufrag) einer SDP */
    fun sdpUfrags(sdp: String?): Set<String> = UFRAG_SDP.findAll(sdp.orEmpty()).map { it.groupValues[1].trim() }.toSet()

    /**
     * Kandidat gehoert zu einer ICE-Generation, deren SDP noch nicht gesetzt ist (ICE-Restart, Signale ueberholen sich)
     * -> zurueckhalten. Ohne ufrag-Angabe (bzw. SDP ohne ufrag) wie bisher sofort anwenden.
     */
    fun candidateWaits(candidate: String, remoteUfrags: Set<String>): Boolean {
        val u = UFRAG_CAND.find(candidate)?.groupValues?.get(1) ?: return false
        return remoteUfrags.isNotEmpty() && u !in remoteUfrags
    }

    /** Netzwechsel (Standardnetz anders als vorher) waehrend eines verbundenen Anrufs -> ICE-Restart */
    fun restartOnNetwork(prev: String?, now: String?, connected: Boolean, reneg: Boolean): Boolean =
        reneg && connected && prev != null && now != null && prev != now

    // ------------------------------------------------------------ Pfad und Bitrate (Konzept §1.3)

    enum class Path { DIRECT, RELAY_UDP, RELAY_TCP }

    /**
     * Pfad aus dem gewaehlten Kandidatenpaar (getStats): eigenes Relais ueber TCP/TLS (turns:…:443) -> RELAY_TCP,
     * sonst Relais auf einer Seite -> RELAY_UDP, sonst direkt. null = noch unbekannt.
     */
    fun path(localType: String?, localRelayProtocol: String?, remoteType: String?): Path? = when {
        localType == null && remoteType == null -> null
        localType == "relay" -> if (localRelayProtocol?.lowercase() in setOf("tcp", "tls")) Path.RELAY_TCP else Path.RELAY_UDP
        remoteType == "relay" -> Path.RELAY_UDP
        else -> Path.DIRECT
    }

    /** Wert fuer die Anzeige (wie bisher "direct"/"relay") */
    fun pathLabel(p: Path?): String = when (p) {
        null -> ""
        Path.DIRECT -> "direct"
        else -> "relay"
    }

    enum class Degradation { BALANCED, MAINTAIN_RESOLUTION }

    data class VideoLimits(val maxBps: Int, val scaleDown: Double, val maxFps: Int, val degradation: Degradation = Degradation.BALANCED, val active: Boolean = true)

    /**
     * Grenzen fuer den eigenen Video-Sender. Kamera nimmt 720p30 auf; scaleDown verkleinert davon ausgehend
     * (720/540 = 4/3, 720/360 = 2). want = Wunsch des Gegenuebers ("large" | "small" | "off"), peers = Endgeraete im
     * Anruf (Mesh ab 3).
     */
    fun videoLimits(path: Path, want: String = "large", screen: Boolean = false, peers: Int = 2): VideoLimits {
        val mesh = peers >= 3
        val base = when {
            screen -> when (path) {
                Path.DIRECT -> VideoLimits(2_500_000, 1.0, 15)
                Path.RELAY_UDP -> VideoLimits(1_800_000, 1.0, 15)
                Path.RELAY_TCP -> VideoLimits(1_200_000, 1.0, 10, Degradation.MAINTAIN_RESOLUTION)
            }
            mesh -> when (path) {
                Path.DIRECT -> VideoLimits(600_000, 4.0 / 3, 30)
                Path.RELAY_UDP -> VideoLimits(500_000, 4.0 / 3, 30)
                Path.RELAY_TCP -> VideoLimits(350_000, 2.0, 15)
            }
            else -> when (path) {
                Path.DIRECT -> VideoLimits(1_500_000, 1.0, 30)
                Path.RELAY_UDP -> VideoLimits(1_200_000, 1.0, 30)
                Path.RELAY_TCP -> VideoLimits(800_000, 4.0 / 3, 30)
            }
        }
        return when {
            want == "off" -> base.copy(active = false)
            // Bildschirm bleibt unveraendert (Text lesbar)
            want == "small" && !screen -> base.copy(
                maxBps = minOf(base.maxBps, if (mesh) 200_000 else 300_000),
                scaleDown = maxOf(base.scaleDown, if (path == Path.RELAY_TCP) 3.0 else 2.0),
            )
            else -> base
        }
    }

    /** Opus-Bitrate je Pfad; In-Band-FEC ist bei libwebrtc-Opus standardmaessig an (useinbandfec=1) */
    fun audioMaxBps(path: Path): Int = if (path == Path.RELAY_TCP) 24_000 else 32_000

    // ------------------------------------------------------------ Perfect Negotiation

    /**
     * Zustandsmaschine fuer Neuverhandlung waehrend des Gespraechs (Perfect Negotiation, W3C-Muster). `n` zaehlt die
     * eigenen Angebote (das erste Angebot beim Aufbau ist 0), remoteN das zuletzt angenommene des Gegenuebers.
     */
    class Negotiator(val polite: Boolean) {
        enum class OfferAction { ACCEPT, ROLLBACK_ACCEPT, IGNORE, STALE }

        var makingOffer = false
            private set
        var ignoreOffer = false
            private set
        var n = 0
            private set
        var remoteN = 0
            private set

        /** Eigenes Angebot beginnt; Ergebnis = laufende Nummer fuer `n` */
        fun startOffer(): Int {
            makingOffer = true
            return ++n
        }

        fun offerDone() {
            makingOffer = false
        }

        /**
         * Angebot des Gegenuebers waehrend des Gespraechs; stable = Signalisierungszustand STABLE. Ohne `n` (bzw. alt,
         * doppelt) ist es kein Neuverhandlungs-Angebot -> STALE (wie bisher ignoriert).
         */
        fun onOffer(rn: Int?, stable: Boolean): OfferAction {
            if (rn == null || rn <= remoteN) return OfferAction.STALE
            val collision = makingOffer || !stable
            ignoreOffer = !polite && collision
            if (ignoreOffer) return OfferAction.IGNORE
            remoteN = rn
            if (collision) makingOffer = false            // eigenes Angebot verworfen (Rollback)
            return if (collision) OfferAction.ROLLBACK_ACCEPT else OfferAction.ACCEPT
        }

        /** Antwort passt zum offenen eigenen Angebot (gleiche Nummer)? */
        fun onAnswer(rn: Int?, haveLocalOffer: Boolean): Boolean = haveLocalOffer && rn != null && rn == n && n > 0
    }
}

/** Bild-im-Bild (Konzept §3): reine Regeln */
object CallPip {
    /** Video aktiv (eigene Kamera oder Bild des Gegenuebers) und Anruf laeuft bzw. wird aufgebaut */
    fun eligible(st: CallUiState?): Boolean = st != null &&
        st.phase in setOf(CallPhase.PREPARING, CallPhase.RINGING, CallPhase.CONNECTING, CallPhase.CONNECTED) &&
        (st.cam || st.remoteVideo)

    /** Wunsch an das Gegenueber: klein im PiP bzw. wenn der Anruf nicht sichtbar ist, sonst gross */
    fun want(pip: Boolean, visible: Boolean): String = if (pip || !visible) "small" else "large"

    /** Seitenverhaeltnis fuer PictureInPictureParams (Android erlaubt 1:2,39 bis 2,39:1) als Zaehler/Nenner */
    fun ratio(aspect: Float?): Pair<Int, Int> {
        val a = (aspect ?: (9f / 16f)).coerceIn(0.42f, 2.39f)
        return Pair(Math.round(a * 1000), 1000)
    }
}
