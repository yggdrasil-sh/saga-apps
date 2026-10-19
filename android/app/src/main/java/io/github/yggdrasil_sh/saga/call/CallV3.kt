package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * saga-call v3 (Saga 2.4, Etappe E0) - reine Regeln ohne Android/WebRTC (Unit-Tests). Vertrag: Server docs/APP-API.md,
 * Abschnitt "Anrufe: saga-call v3". Signatur/Versiegelung bleiben v1/v2; neu sind nur Felder im Body (`pc`, `n`, `caps`,
 * `restart`, `state.pip/want/path`, ICE `usernameFragment`) und der Typ `bye`. Neuverhandlung (ICE-Restart) nur, wenn
 * BEIDE Geraete `reneg` melden - mit 2.1/2.3-Gegenstellen laeuft alles wie bisher. Unbekannte Typen/Felder ignorieren.
 */
object CallV3 {
    const val CAP_RENEG = "reneg"
    const val CAP_WANT = "want"
    const val CAP_BYE = "bye"

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

    /** Server-Faehigkeiten aus GET /api/calls/config (`features`); fehlt das Feld (Server 2.1-2.3) -> leer */
    fun features(el: JsonElement?): Set<String> =
        (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()

    /** Eigene `caps`: `reneg` nur, wenn der EIGENE Server es in `features` meldet; `want` und `bye` immer */
    fun myCaps(serverFeatures: Set<String>): List<String> = buildList {
        if (CAP_RENEG in serverFeatures) add(CAP_RENEG)
        add(CAP_WANT)
        add(CAP_BYE)
    }

    /** `caps` aus einem Angebot/einer Antwort; fehlt es (2.1/2.3) -> leer */
    fun caps(b: JsonObject?): Set<String> =
        (b?.get("caps") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()

    /** Neuverhandlung erlaubt: beide Seiten melden `reneg` */
    fun canReneg(mine: Collection<String>, peer: Collection<String>): Boolean = CAP_RENEG in mine && CAP_RENEG in peer

    /** Perfect Negotiation: hoeflich ist die lexikographisch groessere device_id */
    fun polite(myDevice: String, peerDevice: String): Boolean = myDevice > peerDevice

    /** Kennung der PeerConnection: der Anrufer legt sie je Zielgeraet als sortiertes Paar "min:max" fest */
    fun pcId(devA: String, devB: String): String = if (devA <= devB) "$devA:$devB" else "$devB:$devA"

    /** Signal gehoert zu meiner Verbindung? Ohne `pc` (2.3) gilt die einzige Verbindung */
    fun pcMatches(signalPc: String?, mine: String): Boolean = signalPc == null || mine.isEmpty() || signalPc == mine

    /** Neues offer/answer muss dasselbe (signierte) DTLS-Zertifikat tragen wie beim Aufbau */
    fun sameFingerprints(now: List<String>, before: List<String>): Boolean =
        now.isNotEmpty() && now.map { it.lowercase() }.toSet() == before.map { it.lowercase() }.toSet()

    /** Gewuenschte Videogroesse des Gegenuebers (`state.want.video`); fehlt sie (2.3) -> "large" */
    fun peerWant(b: JsonObject?): String {
        val w = ((b?.get("want") as? JsonObject)?.get("video") as? JsonPrimitive)?.contentOrNull
        return if (w == "off" || w == "small" || w == "large") w else "large"
    }

    // ------------------------------------------------------------ ICE-Generationen (Restart)

    private val UFRAG_SDP = Regex("""^a=ice-ufrag:(\S+)""", RegexOption.MULTILINE)
    private val UFRAG_CAND = Regex("""\bufrag (\S+)""")

    /** ICE-Benutzernamen (ufrag) einer SDP */
    fun sdpUfrags(sdp: String?): Set<String> = UFRAG_SDP.findAll(sdp.orEmpty()).map { it.groupValues[1].trim() }.toSet()

    /** ufrag eines Kandidaten: Feld `usernameFragment`, sonst aus dem Kandidatentext */
    fun candidateUfrag(candidate: String, usernameFragment: String?): String? =
        usernameFragment?.takeIf { it.isNotEmpty() } ?: UFRAG_CAND.find(candidate)?.groupValues?.get(1)

    enum class CandAction { APPLY, WAIT, DROP }

    /**
     * Kandidat zur gesetzten Remote-SDP: passende bzw. fehlende ufrag -> anwenden; ufrag einer aelteren Generation ->
     * verwerfen; unbekannte ufrag (neue Generation, Angebot/Antwort noch unterwegs) -> zurueckhalten.
     */
    fun candidateAction(ufrag: String?, current: Set<String>, older: Set<String>): CandAction = when {
        ufrag == null || current.isEmpty() || ufrag in current -> CandAction.APPLY
        ufrag in older -> CandAction.DROP
        else -> CandAction.WAIT
    }

    /** Netzwechsel (Standardnetz anders als vorher) waehrend eines verbundenen Anrufs -> ICE-Restart */
    fun restartOnNetwork(prev: String?, now: String?, connected: Boolean, reneg: Boolean): Boolean =
        reneg && connected && prev != null && now != null && prev != now

    /** Hoechstens so viele ICE-Restarts je Abbruch (zurueckgesetzt bei "connected") */
    const val MAX_RESTARTS = 3

    /** Nach `failed` so lange auf die Rueckkehr warten, dann wie bisher mit reason "failed" beenden */
    const val FAILED_WAIT_MS = 20_000L

    /** `disconnected` so lange, dann ICE-Restart */
    const val DISCONNECTED_WAIT_MS = 2_500L

    // ------------------------------------------------------------ Weg und Bitrate

    /** Weg, geordnet vom besten zum schlechtesten */
    enum class Path(val wire: String) { DIRECT("direct"), RELAY_UDP("relay-udp"), RELAY_TCP("relay-tcp") }

    /**
     * Weg aus dem gewaehlten Kandidatenpaar (getStats): lokales Relais ueber tcp/tls -> RELAY_TCP, lokales Relais sonst ->
     * RELAY_UDP; lokaler Nicht-Relais-Kandidat ueber TCP (ICE-TCP) -> wie RELAY_TCP; entferntes Relais -> mindestens
     * RELAY_UDP; sonst direkt. null = unbekannt.
     */
    fun path(localType: String?, localRelayProtocol: String?, remoteType: String?, localProtocol: String? = null): Path? = when {
        localType == null && remoteType == null -> null
        localType == "relay" -> if (localRelayProtocol?.lowercase() in setOf("tcp", "tls")) Path.RELAY_TCP else Path.RELAY_UDP
        localProtocol?.lowercase() == "tcp" -> Path.RELAY_TCP
        remoteType == "relay" -> Path.RELAY_UDP
        else -> Path.DIRECT
    }

    /** `state.path` des Gegenuebers; fehlt bzw. unbekannt -> null */
    fun parsePath(s: String?): Path? = Path.entries.firstOrNull { it.wire == s }

    /** Es gilt der schlechtere Weg von eigenem und gemeldetem */
    fun worse(own: Path?, peer: Path?): Path? = when {
        own == null -> peer
        peer == null -> own
        else -> maxOf(own, peer)
    }

    /** Wert fuer die Anzeige (wie bisher "direct"/"relay") */
    fun pathLabel(p: Path?): String = when (p) {
        null -> ""
        Path.DIRECT -> "direct"
        else -> "relay"
    }

    enum class Degradation { BALANCED, MAINTAIN_RESOLUTION }

    data class VideoLimits(val maxBps: Int, val scaleDown: Double, val maxFps: Int?, val degradation: Degradation = Degradation.BALANCED, val active: Boolean = true)

    /** Verkleinern auf hoechstens target Zeilen (nur wenn die Quelle groesser ist) */
    private fun down(srcHeight: Int, target: Int): Double = if (srcHeight > target) srcHeight.toDouble() / target else 1.0

    /**
     * Grenzen fuer den eigenen Video-Sender (Tabelle "Bitrate je Weg"). srcHeight = Hoehe der Quelle (Kamera 720),
     * want = Wunsch des Gegenuebers ("large" | "small" | "off", gilt nur fuer die Kamera), peers = Endgeraete (Mesh ab 3).
     * maxFps null = unveraendert.
     */
    fun videoLimits(path: Path, want: String = "large", screen: Boolean = false, peers: Int = 2, srcHeight: Int = 720): VideoLimits {
        if (screen) return when (path) {
            Path.DIRECT -> VideoLimits(2_500_000, 1.0, 15, Degradation.MAINTAIN_RESOLUTION)
            Path.RELAY_UDP -> VideoLimits(1_800_000, 1.0, 15, Degradation.MAINTAIN_RESOLUTION)
            Path.RELAY_TCP -> VideoLimits(1_200_000, 1.0, 8, Degradation.MAINTAIN_RESOLUTION)
        }
        val mesh = peers >= 3
        val base = if (mesh) when (path) {
            Path.DIRECT -> VideoLimits(600_000, down(srcHeight, 540), null)
            Path.RELAY_UDP -> VideoLimits(500_000, down(srcHeight, 540), null)
            Path.RELAY_TCP -> VideoLimits(350_000, down(srcHeight, 360), 15)
        } else when (path) {
            Path.DIRECT -> VideoLimits(1_500_000, down(srcHeight, 720), null)
            Path.RELAY_UDP -> VideoLimits(1_200_000, down(srcHeight, 720), null)
            Path.RELAY_TCP -> VideoLimits(800_000, down(srcHeight, 540), null)
        }
        return when (want) {
            "off" -> base.copy(active = false)
            "small" -> base.copy(
                maxBps = minOf(base.maxBps, if (mesh) 200_000 else 300_000),
                scaleDown = maxOf(2.0, minOf(3.0, srcHeight / 240.0)),
            )
            else -> base
        }
    }

    /** Opus-Bitrate je Weg; In-Band-FEC ist bei libwebrtc-Opus standardmaessig an (useinbandfec=1) */
    fun audioMaxBps(path: Path): Int = if (path == Path.RELAY_TCP) 24_000 else 32_000

    // ------------------------------------------------------------ Perfect Negotiation

    /**
     * Zustandsmaschine fuer Neuverhandlung (Perfect Negotiation). `n` zaehlt je Absender: das erste Angebot des Anrufers
     * ist 1 (also Anrufer n=1, remoteN=0), das erste eigene Angebot des Angerufenen ebenfalls 1 (Angerufener n=0,
     * remoteN=1). Eine Antwort traegt das `n` des beantworteten Angebots.
     */
    class Negotiator(val polite: Boolean, n: Int = 0, remoteN: Int = 0) {
        enum class OfferAction { ACCEPT, ROLLBACK_ACCEPT, IGNORE, STALE }

        var makingOffer = false
            private set
        var ignoreOffer = false
            private set
        var n = n
            private set
        var remoteN = remoteN
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
         * Angebot des Gegenuebers waehrend des Gespraechs; stable = Signalisierungszustand STABLE. n <= zuletzt
         * verarbeitetes (bzw. ohne n) -> STALE.
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

        /** Antwort passt zu meinem letzten Angebot (gleiche Nummer) und ich habe ein offenes Angebot? */
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
