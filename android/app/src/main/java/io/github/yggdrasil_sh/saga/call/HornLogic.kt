package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.floor

/**
 * Gjallarhorn (Saga 2.5, P12): Entscheidungen fuer Gruppenanrufe ueber Knoten - ohne WebRTC und ohne Android, damit sie
 * sich auf der JVM pruefen lassen (HornLogicTest, dieselben Faelle wie tests/horn_logik.js im Server-Repo).
 * Gleiche Regeln wie static/horn.js (Konzept v2 §1.2 H6/H7, §2.6; docs/APP-API.md "Anrufe ueber Gjallarhorn-Knoten"):
 * - Punktzahl je Kandidat (kleiner = besser): rtt_p50 + 2*jitter + Lastabzug + (UDP fehlt ? 15 : 0) - (Anruf schon hier ? 25 : 0)
 * - Umschalter mit Hysterese: Verbindung weg / Knoten leert sich -> sofort; anderer Knoten besser -> nur wenn >= 25 % UND
 *   >= 30 ms besser, 20 s am Stueck, Mindestverweildauer 60 s, hoechstens 3 Wechsel je 10 min; UDP-Verlust > 20 % fuer 3 s
 *   -> TCP am selben Knoten; UDP wieder ok fuer 30 s -> zurueck, hoechstens 1x je 5 min
 * - Kachelansicht bis 25, Last-N (Handy 4, Tablet 9 bewegte Bilder inkl. Sprecherplatz), Qualitaetspunkt, Wegname
 * Dazu die Formate des Knotens (Kandidaten, Antwort auf POST /api/sfu/leg, Ereigniskanal) und der Schluesselplan fuer
 * FrameCryptor (eigener Schluessel, vorab gesetzte Ratschenstufen der anderen).
 */
object HornLogic {
    /** Anruf-UI-Regeln: Pseudonym-Handles (~abc...) in der Mitte kuerzen, sonst am Ende; max Zeichen */
    fun shortName(name: String, max: Int = 18): String {
        if (name.length <= max) return name
        if (!name.startsWith("~")) return name.take(max - 1) + "…"
        val head = (max - 1) / 2
        return name.take(head) + "…" + name.takeLast(max - 1 - head)
    }
    const val PROBE_MS = 800L
    const val PINGS = 5
    const val NO_UDP_MS = 15.0
    const val HERE_MS = 25.0
    const val LOAD_FROM = 0.7
    const val LOAD_MS = 200.0
    const val FULL = 0.9
    const val FULL_MS = 1000.0
    const val BETTER_REL = 0.25
    const val BETTER_MS = 30.0
    const val BETTER_FOR = 20_000L
    const val DWELL = 60_000L
    const val WINDOW = 600_000L
    const val MAX_SWITCH = 3
    const val LOST_MS = 2000L
    const val UDP_LOSS = 0.2
    const val UDP_LOSS_FOR = 3000L
    const val UDP_BACK_FOR = 30_000L
    const val UDP_BACK_EVERY = 300_000L
    const val OVERLAP_MIN = 2000L
    const val OVERLAP_MAX = 15_000L
    const val LASTN_DESKTOP = 9
    const val LASTN_MOBILE = 4
    const val MAX_TILES = 25

    /** Wartezeiten je Weg (calls.js): UDP 2 s (unklar 1,5 s), ICE-TCP 4 s, TURN-TLS 10 s */
    const val WAIT_UDP = 2000L
    const val WAIT_UDP_MAYBE = 1500L
    const val WAIT_TCP = 4000L
    const val WAIT_TURN = 10_000L
    /** Medien-Token gelten 60 s: aeltere als 30 s vor dem Verbinden frisch holen */
    const val FRESH_MS = 30_000L
    /** Knoten nach einem Fehler meiden */
    const val BAD_MS = 60_000L
    /** Kein Knoten erreichbar: nach 2 min aufgeben */
    const val RETRY_MAX = 120_000L
    /**
     * TT 67: scheitern ALLE Knoten einer Runde am Zertifikat (z. B. selbst signiert, "Trust anchor not found"), hilft
     * Warten nicht - nach so vielen Runden aufgeben, mit Grund statt endlos "Kein Knoten erreichbar".
     */
    const val CERT_ROUNDS = 2

    /** Fehler beim Aufbau der TLS-Verbindung zum Knoten wegen eines nicht vertrauenswuerdigen Zertifikats? */
    fun certError(t: Throwable?): Boolean {
        var e = t
        var n = 0
        while (e != null && n++ < 12) {
            val cls = e.javaClass.name
            if (cls == "javax.net.ssl.SSLPeerUnverifiedException" || cls == "java.security.cert.CertPathValidatorException" ||
                cls == "java.security.cert.CertificateException" || cls == "java.security.cert.CertificateExpiredException" ||
                cls == "java.security.cert.CertificateNotYetValidException" || cls == "sun.security.validator.ValidatorException"
            ) return true
            val m = e.message.orEmpty()
            if (cls == "javax.net.ssl.SSLHandshakeException" &&
                (m.contains("Trust anchor", true) || m.contains("certificate", true) || m.contains("CertPath", true))
            ) return true
            if (m.contains("Trust anchor for certification path not found", true)) return true
            e = e.cause
        }
        return false
    }

    /**
     * Nach einer Runde ohne Knoten: weiter versuchen oder mit Zertifikats-Grund aufgeben? certNodes: Knoten, die am
     * Zertifikat scheiterten; other: mindestens ein anderer Fehler (Netz, Zeitlimit) - dann lohnt Warten.
     */
    fun giveUpCert(certNodes: Collection<String>, other: Boolean, rounds: Int): Boolean =
        certNodes.isNotEmpty() && !other && rounds >= CERT_ROUNDS
    const val CANDS_EVERY = 30_000L
    const val UDP_PROBE_EVERY = 10_000L
    const val PING_EVERY = 2000L
    /** Empfangene Bilder auf TCP/TLS: Jitter-Puffer-Ziel (Web: receiver.jitterBufferTarget) */
    const val JITTER_TARGET_MS = 150

    // ---- Punktzahl -----------------------------------------------------------------------------------------------

    fun median(a: List<Double>): Double {
        if (a.isEmpty()) return Double.NaN
        val s = a.sorted()
        val n = s.size
        return if (n % 2 == 1) s[(n - 1) / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** mittlere Abweichung aufeinanderfolgender RTTs (wie RFC 3550 ohne Glaettung) */
    fun jitter(a: List<Double>): Double {
        if (a.size < 2) return 0.0
        var s = 0.0
        for (i in 1 until a.size) s += abs(a[i] - a[i - 1])
        return s / (a.size - 1)
    }

    fun loadMs(load: Double?): Double {
        val l = load ?: 0.0
        if (l >= FULL) return FULL_MS
        return if (l > LOAD_FROM) (l - LOAD_FROM) * LOAD_MS else 0.0
    }

    /** Messung eines Kandidaten: ok = WSS-Pings kamen an; udp = Antwort auf das STUN-Binding (ms) bzw. null */
    data class Measure(
        val ok: Boolean,
        val rtts: List<Double> = emptyList(),
        val tls: Double? = null,
        val udp: Double? = null,
        /** UDP-Ergebnis unklar (keine Antwort, aber auch kein Fehler): kurz probieren */
        val udpMaybe: Boolean = false,
    )

    /** Punktzahl (kleiner = besser); devUdp = false: Geraet hat gar kein UDP */
    fun score(m: Measure?, cand: Cand?, devUdp: Boolean? = null): Double {
        if (m == null || !m.ok || m.rtts.isEmpty()) return Double.POSITIVE_INFINITY
        var s = median(m.rtts) + 2 * jitter(m.rtts) + loadMs(cand?.load)
        val udp = cand != null && cand.udp && devUdp != false && m.udp != null
        if (!udp) s += NO_UDP_MS
        if (cand?.here == true) s -= HERE_MS
        return floor(s * 10 + 0.5) / 10
    }

    data class Ranked(val cand: Cand, val m: Measure?, val score: Double, val i: Int)

    /** Kandidaten nach Punktzahl (unerreichbare zuletzt, bei Gleichstand die Reihenfolge des Servers) */
    fun rank(cands: List<Cand>, meas: Map<String, Measure?>, devUdp: Boolean? = null): List<Ranked> =
        cands.mapIndexed { i, c -> Ranked(c, meas[c.node], score(meas[c.node], c, devUdp), i) }
            .sortedWith(compareBy<Ranked> { it.score }.thenBy { it.i })

    /** Anderer Knoten deutlich besser? (>= 30 ms UND >= 25 %) */
    fun better(cur: Double, other: Double): Boolean {
        if (!other.isFinite()) return false
        if (!cur.isFinite()) return true
        return cur - other >= BETTER_MS && other <= cur * (1 - BETTER_REL)
    }

    // ---- Umschalter ----------------------------------------------------------------------------------------------

    /** Entscheidung: act = "switch" (anderer Knoten) | "path" (anderer Weg am selben Knoten) */
    data class Decision(val act: String, val why: String, val to: String? = null, val path: String? = null)

    data class Other(val node: String, val score: Double)

    /**
     * Umschalter mit Hysterese. now in ms. Zustand nur hier; der Aufrufer fuehrt den Wechsel aus und meldet ihn mit
     * [moved]. Gleiche Regeln wie SagaHorn.Switcher (static/horn.js).
     */
    class Switcher(node: String?, path: String, now: Long = 0) {
        var node: String? = node
            private set
        var path: String = path
            private set
        private var since = now
        private val moves = ArrayList<Long>()
        private var cand: String? = null
        private var candSince = 0L
        private var lossSince = 0L
        private var udpOkSince = 0L
        private var udpBackAt = Long.MIN_VALUE / 2
        private var discSince = 0L

        fun moved(now: Long, node: String?, path: String?, reason: String) {
            if (reason == "better") moves.add(now)
            if (node != this.node) since = now
            this.node = node
            if (!path.isNullOrEmpty()) this.path = path
            cand = null
            candSince = 0
            lossSince = 0
            discSince = 0
            if (reason == "udp") udpBackAt = now
            if (path == "udp") udpOkSince = 0
        }

        fun budget(now: Long): Int {
            val w = now - WINDOW
            moves.removeAll { it <= w }
            return MAX_SWITCH - moves.size
        }

        /** state = schlechtester Zustand der Beine: connected | disconnected | failed | closed | ... */
        fun conn(now: Long, state: String): Decision? {
            if (state == "failed" || state == "closed") return Decision("switch", "lost")
            if (state == "disconnected") {
                if (discSince == 0L) discSince = now
                if (now - discSince > LOST_MS) return Decision("switch", "lost")
            } else discSince = 0
            return null
        }

        fun draining(): Decision = Decision("switch", "draining")

        /** Laufende Beobachtung: cur = Punktzahl des aktuellen Knotens */
        fun observe(now: Long, cur: Double, others: List<Other>): Decision? {
            var best: Other? = null
            for (x in others) if (x.node != node && better(cur, x.score) && (best == null || x.score < best.score)) best = x
            if (best == null) {
                cand = null
                candSince = 0
                return null
            }
            if (cand == null || cand != best.node) {
                cand = best.node
                candSince = now
                return null
            }
            if (now - candSince < BETTER_FOR) return null
            if (now - since < DWELL) return null
            if (budget(now) <= 0) return null
            return Decision("switch", "better", to = best.node)
        }

        /** UDP-Weg: Verlust (0..1) aus getStats; ok = UDP-Probe gelingt (fuer die Rueckkehr) */
        fun udp(now: Long, loss: Double, ok: Boolean = false): Decision? {
            if (path == "udp") {
                if (loss > UDP_LOSS) {
                    if (lossSince == 0L) lossSince = now
                    if (now - lossSince >= UDP_LOSS_FOR) return Decision("path", "udp-loss", path = "tcp")
                } else lossSince = 0
                return null
            }
            if (!ok) {
                udpOkSince = 0
                return null
            }
            if (udpOkSince == 0L) udpOkSince = now
            if (now - udpOkSince >= UDP_BACK_FOR && now - udpBackAt >= UDP_BACK_EVERY) return Decision("path", "udp", path = "udp")
            return null
        }
    }

    /**
     * Altes Bein beim Wechsel schliessen? up = Zeitpunkt, an dem das neue Bein Rahmen bekam (0 = noch nicht);
     * waiting = andere, die nur ueber den alten Knoten zu erreichen sind
     */
    fun closeOld(now: Long, up: Long, waiting: Int): Boolean {
        if (up == 0L) return false
        val d = now - up
        return d >= OVERLAP_MAX || (d >= OVERLAP_MIN && waiting == 0)
    }

    // ---- Kachelansicht -------------------------------------------------------------------------------------------

    /** Spalten fuer n Kacheln bei Breite w (dp bzw. px wie im Web): schmal (<= 640) 2 Spalten */
    fun cols(n: Int, w: Int): Int {
        if (w in 1..640) return if (n <= 1) 1 else 2
        return when {
            n <= 1 -> 1
            n <= 4 -> 2
            n <= 9 -> 3
            n <= 16 -> 4
            else -> 5
        }
    }

    fun lastN(mobile: Boolean): Int = if (mobile) LASTN_MOBILE else LASTN_DESKTOP

    /** Kachelplaetze am Knoten: Last-N zaehlt den Sprecherplatz mit */
    fun tileSlots(mobile: Boolean): Int = lastN(mobile) - 1

    data class TilePos(val dev: String, val moving: Boolean, val big: Boolean)
    data class Layout(val list: List<TilePos>, val more: Int)

    /**
     * Kacheln: devs = andere Geraete (Reihenfolge der Teilnehmerliste), moving = Geraete mit Bildplatz am Knoten,
     * speaker = Geraet des Sprecherplatzes. Sprecheransicht: der Sprecher zuerst und gross. Hoechstens 25 (Rest: more).
     */
    fun tiles(devs: List<String>, moving: Set<String>, speaker: String?, view: String): Layout {
        val list = devs.take(MAX_TILES).map { TilePos(it, it in moving, false) }.toMutableList()
        if (view == "speaker" && list.isNotEmpty()) {
            var i = list.indexOfFirst { it.dev == speaker }
            if (i < 0) i = 0
            val s = list.removeAt(i)
            list.add(0, s.copy(big = true))
        }
        return Layout(list, maxOf(0, devs.size - MAX_TILES))
    }

    // ---- Qualitaet, Weg ------------------------------------------------------------------------------------------

    data class Net(val rtt: Double?, val loss: Double = 0.0, val freezes: Double = 0.0)

    /** Qualitaetspunkt: "good" | "mid" | "bad" | "" (unbekannt). freezes je Minute */
    fun quality(q: Net?): String {
        if (q == null) return ""
        val rtt = q.rtt ?: 0.0
        if (rtt > 400 || q.loss > 0.08 || q.freezes > 3) return "bad"
        if (rtt > 200 || q.loss > 0.02 || q.freezes > 1) return "mid"
        return "good"
    }

    fun pathName(p: String?): String = when (p) {
        "udp" -> "UDP"
        "turn" -> "TLS"
        "tcp" -> "TCP"
        else -> ""
    }

    // ---- Simulcast (NODE.md "Erwartungen an Clients") ------------------------------------------------------------

    data class Enc(val rid: String, val scaleDown: Double, val maxKbit: Int, val maxFps: Int, val mode: String)

    /** TCP/TLS: l = 1/4, 20 fps L1T2 (Kacheln, der Knoten nimmt 10 fps), m = voll 30 fps L1T3; UDP zusaetzlich h */
    fun camEncodings(path: String): List<Enc> = if (path == "udp") listOf(
        Enc("l", 4.0, 150, 20, "L1T2"),
        Enc("m", 2.0, 500, 30, "L1T3"),
        Enc("h", 1.0, 1500, 30, "L1T3"),
    ) else listOf(
        Enc("l", 4.0, 150, 20, "L1T2"),
        Enc("m", 1.0, 700, 30, "L1T3"),
    )

    /** Bildschirm wie 2.4 relay-udp bzw. relay-tcp (kbit/s, fps) */
    fun screenRate(path: String): Pair<Int, Int> = if (path == "udp") 1800 to 15 else 1200 to 10

    /** Rollen der Beine: UDP eine Leitung mit BUNDLE ("both"), TCP/TLS getrennt Ton und Bild (H1) */
    fun roles(path: String): List<String> = if (path == "udp") listOf("both") else listOf("audio", "video")

    // ---- Formate ---------------------------------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val REF = Regex("^[A-Za-z0-9_-]{1,64}$")

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** Kandidat aus `horn.candidates` (Knoten mit je eigenen Medien-Token) */
    data class Cand(
        val node: String,
        val url: String = "",
        val udp: Boolean = false,
        val here: Boolean = false,
        val load: Double? = null,
        val peer: String = "",
        val exp: Long = 0,
        val tokens: Map<String, String> = emptyMap(),
    ) {
        val host: String get() = url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBeforeLast(':').trim('[', ']')
        val port: Int get() = url.removePrefix("https://").removePrefix("http://").substringBefore('/').let { h ->
            if (h.startsWith("[")) h.substringAfter("]").removePrefix(":").toIntOrNull() else if (h.count { it == ':' } == 1) h.substringAfter(':').toIntOrNull() else null
        } ?: if (url.startsWith("http://")) 80 else 443
        val wsBase: String get() = if (url.startsWith("http://")) "ws://" + url.removePrefix("http://") else "wss://" + url.removePrefix("https://")
    }

    data class Block(val e2e: String = "", val ttl: Int = 60, val candidates: List<Cand> = emptyList())

    /** `horn` im Anruf-Objekt bzw. Antwort von POST /api/calls/{id}/horn; nur https-Knoten (http nur fuer Tests: dev) */
    fun parseBlock(el: JsonElement?, allowHttp: Boolean = false): Block? {
        val o = el as? JsonObject ?: return null
        val cands = (o["candidates"] as? JsonArray).orEmpty().mapNotNull { x ->
            val c = x as? JsonObject ?: return@mapNotNull null
            val node = c.str("node")?.takeIf { it.isNotEmpty() && it.length <= 253 } ?: return@mapNotNull null
            val url = c.str("url")?.trimEnd('/') ?: return@mapNotNull null
            if (!url.startsWith("https://") && !(allowHttp && url.startsWith("http://"))) return@mapNotNull null
            val tk = (c["tokens"] as? JsonObject)?.mapNotNull { (k, v) ->
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (k in setOf("audio", "video", "both") && s != null && s.length <= 4096) k to s else null
            }?.toMap().orEmpty()
            Cand(node, url, c.bool("udp"), c.bool("here"), c.num("load"), c.str("peer").orEmpty(), c.num("exp")?.toLong() ?: 0, tk)
        }.take(3)
        return Block(o.str("e2e").orEmpty(), (o.num("ttl") ?: 60.0).toInt(), cands)
    }

    /** Platz am Knoten: i = Index unter den Empfangs-Transceivern des Beins, class audio | speaker | screen | tile */
    data class Slot(val i: Int, val cls: String, val peer: String = "", val csrc: Long = 0, val layer: String = "", val paused: Boolean = false)

    fun parseSlots(el: JsonElement?): List<Slot> = (el as? JsonArray).orEmpty().take(64).mapNotNull { x ->
        val o = x as? JsonObject ?: return@mapNotNull null
        val i = o.num("i")?.toInt()?.takeIf { it in 0..63 } ?: return@mapNotNull null
        val cls = o.str("class")?.takeIf { it in setOf("audio", "speaker", "screen", "tile") } ?: return@mapNotNull null
        val peer = o.str("peer")?.takeIf { REF.matches(it) }.orEmpty()
        val csrc = (o["csrc"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..0xFFFF_FFFFL } ?: 0
        Slot(i, cls, peer, csrc, o.str("layer").orEmpty(), o.bool("paused"))
    }

    /** peers {peer_ref: csrc} (alle Teilnehmenden des Raums) */
    fun parsePeers(el: JsonElement?): Map<String, Long> = (el as? JsonObject)?.entries?.take(256)?.mapNotNull { (k, v) ->
        val c = (v as? JsonPrimitive)?.longOrNull
        if (REF.matches(k) && c != null && c in 0..0xFFFF_FFFFL) k to c else null
    }?.toMap().orEmpty()

    /** Antwort auf POST /api/sfu/leg */
    data class LegRes(val id: String, val answer: String, val csrc: Long, val slots: List<Slot>, val peers: Map<String, Long>, val state: String)

    fun parseLeg(text: String): LegRes? = try {
        val o = json.parseToJsonElement(text) as? JsonObject
        val id = o?.str("id")?.takeIf { REF.matches(it) }
        val ans = o?.str("answer")
        if (o == null || id == null || ans.isNullOrEmpty()) null
        else LegRes(id, ans, (o["csrc"] as? JsonPrimitive)?.longOrNull ?: 0, parseSlots(o["slots"]), parsePeers(o["peers"]), o.str("state") ?: "ok")
    } catch (_: Exception) {
        null
    }

    /** Ereigniskanal je Bein (GET /api/sfu/leg/{id}/events) */
    sealed class Event {
        data class Hello(val leg: String, val peers: Map<String, Long>, val slots: List<Slot>, val estKbit: Int, val state: String) : Event()
        data class Peers(val peers: Map<String, Long>) : Event()
        data class Layout(val slots: List<Slot>, val estKbit: Int) : Event()
        data class State(val state: String) : Event()
        data class Closed(val why: String) : Event()
        data class Pong(val n: Long) : Event()
    }

    /** Eine Nachricht des Ereigniskanals; unbekannt bzw. kaputt -> null (ignorieren) */
    fun parseEvent(text: String): Event? {
        if (text.length > 256 * 1024) return null
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val est = o.num("est_kbit")?.toInt()?.coerceAtLeast(0) ?: 0
        return when (o.str("t")) {
            "hello" -> Event.Hello(o.str("leg").orEmpty(), parsePeers(o["peers"]), parseSlots(o["slots"]), est, state(o.str("state")))
            "peers" -> Event.Peers(parsePeers(o["peers"]))
            "layout" -> if (o["slots"] is JsonArray) Event.Layout(parseSlots(o["slots"]), est) else null
            "state" -> o.str("state")?.let { Event.State(state(it)) }
            "closed" -> Event.Closed(o.str("why")?.take(64) ?: "zu")
            "pong" -> (o["n"] as? JsonPrimitive)?.longOrNull?.let { Event.Pong(it) }
            else -> null
        }
    }

    private fun state(s: String?): String = if (s == "draining") "draining" else "ok"

    /** CSRC -> Geraet je Verbindung: aus peers (Ereigniskanal, POST /leg) und der Belegung; nur Geraete der Teilnehmerliste */
    fun csrcMap(peers: Map<String, Long>, slots: List<Slot>, devOf: (String) -> String?, me: String): Map<Long, String> {
        val cp = HashMap<Long, String>()
        for ((ref, c) in peers) cp[c] = ref
        for (s in slots) if (s.peer.isNotEmpty() && s.csrc != 0L) cp[s.csrc] = s.peer
        val out = HashMap<Long, String>()
        for ((c, ref) in cp) {
            val d = devOf(ref) ?: continue
            if (d != me) out[c] = d
        }
        return out
    }

    /**
     * P9/P10 state.hp: Kennungen des Absenders an seinen Knoten (Kaskade, Knoten anderer Server). null = Feld fehlt
     * (aeltere App); nur Texte bis 64 Zeichen, hoechstens 4.
     */
    fun parseHp(b: JsonObject): List<String>? {
        if ((b["t"] as? JsonPrimitive)?.contentOrNull != "state") return null
        val a = b["hp"] as? JsonArray ?: return null
        return a.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it.isNotEmpty() && it.length <= 64 }.take(4)
    }

    /** Eigene Kennungen fuer state.hp: ohne leere und doppelte, Reihenfolge bleibt, hoechstens 4 */
    fun myRefs(refs: List<String>): List<String> = refs.filter { it.isNotEmpty() }.distinct().take(4)

    /** Wer belegt Platz i? Geraet ueber die CSRC des Platzes (bzw. die Kennung) */
    fun slotOwner(s: Slot?, csrc: Map<Long, String>, devOf: (String) -> String?): String? {
        if (s == null || s.paused) return null
        if (s.csrc != 0L) csrc[s.csrc]?.let { return it }
        return if (s.peer.isNotEmpty()) devOf(s.peer) else null
    }

    // ---- Schluessel (docs/HORN-E2E.md §4, §6) ---------------------------------------------------------------------

    /** Ein Schluessel: Generation n, Material K (32 Byte) */
    class Gen(val n: Int, val key: ByteArray) {
        val kid: Int get() = n % HornE2E.RING
    }

    /**
     * Eigener Schluessel als Sender: Start (zufaellig, n = 0), Beitritt (Ratsche, sofort aktiv, nur an den Neuen),
     * Abgang (neuer Zufallsschluessel an alle Verbliebenen, Umschalten nach 1,5 s bzw. sobald alle bestaetigt haben),
     * Beitritt waehrend ein Wechsel wartet (der wartende wird geratscht), Wechsel nach 6 h. [rnd] liefert 32 Zufallsbytes.
     */
    class OwnKeys(private val rnd: () -> ByteArray, val session: String) {
        var active: Gen = Gen(0, rnd())
            private set
        var pending: Gen? = null
            private set
        var switchAt = 0L
            private set
        var since = 0L
            private set
        private val acks = HashSet<String>()
        private var ackWant: Set<String> = emptySet()

        /** neuester Schluessel (wartend oder aktiv) - der wird verteilt bzw. nachgefordert */
        val newest: Gen get() = pending ?: active

        fun start(now: Long): Gen {
            active = Gen(0, rnd())
            pending = null
            since = now
            return active
        }

        /** Beitritt: neuester Schluessel geratscht; ohne wartenden Wechsel sofort aktiv */
        fun join(): Gen {
            val p = pending
            return if (p != null) {
                Gen(p.n + 1, HornE2E.ratchet(p.key)).also { pending = it }
            } else {
                Gen(active.n + 1, HornE2E.ratchet(active.key)).also { active = it }
            }
        }

        /** Abgang (bzw. 6 h): neuer Zufallsschluessel, Umschalten nach 1,5 s; want = Geraete, deren Bestaetigung zaehlt */
        fun leave(now: Long, want: Set<String>): Gen {
            val g = Gen(newest.n + 1, rnd())
            pending = g
            switchAt = now + HornE2E.SWITCH_MS.toLong()
            acks.clear()
            ackWant = want
            return g
        }

        /** Bestaetigung (fkey-ack ok) eines Empfaengers; true = alle haben bestaetigt -> jetzt umschalten */
        fun ack(dev: String, n: Int): Boolean {
            val p = pending ?: return false
            if (n != p.n) return false
            acks.add(dev)
            return ackWant.isNotEmpty() && acks.containsAll(ackWant)
        }

        /** Faelliger Wechsel (Zeit erreicht bzw. force): neuer aktiver Schluessel, sonst null */
        fun due(now: Long, force: Boolean = false): Gen? {
            val p = pending ?: return null
            if (!force && now < switchAt) return null
            active = p
            pending = null
            since = now
            return p
        }

        /** Schluessel 6 h in Gebrauch -> wie Abgang */
        fun tooOld(now: Long): Boolean = pending == null && since > 0 && now - since >= 6 * 3600_000L
    }

    /**
     * Schluessel eines anderen Senders aus Sicht des Empfaengers (FrameCryptor kann die Saga-Ratsche beim ersten Rahmen
     * nicht): Basis aus dem letzten fkey, jeder Beitritt danach = newest + 1 (der Sender ratscht selbst). Gesetzt werden
     * newest und die vier folgenden Ratschenstufen ([plan]); aeltere Generationen laufen 3 s nach der Erhoehung ab.
     */
    class PeerKeys {
        var session: String? = null
            private set
        private var baseN = -1
        private var baseK: ByteArray? = null
        private var joins = 0
        /** Erhoehung von newest: (alte Generation, Zeitpunkt) - deren Platz wird 3 s spaeter ueberschrieben */
        private val expire = ArrayList<Pair<Int, Long>>()

        val newest: Int get() = if (baseN < 0) -1 else baseN + joins
        val known: Boolean get() = baseK != null

        /** fkey des Senders; Ergebnis true = neuer Stand (Plan neu setzen). Neue Sitzung: alles Alte verwerfen */
        fun fkey(n: Int, key: ByteArray, s: String, now: Long): Boolean {
            if (s != session) {
                session = s
                baseN = n
                baseK = key
                joins = 0
                expire.clear()
                return true
            }
            if (n < newest - 1) return false                // HORN-E2E §3: n < newest - 1 (gleiche Sitzung) ignorieren
            if (n == baseN && baseK?.contentEquals(key) == true) return false
            if (n == newest && joins > 0 && baseK != null && HornE2E.ratchet(baseK!!, joins).contentEquals(key)) {
                baseN = n                                     // genau die vorhergesagte Stufe: nur die Basis nachziehen
                baseK = key
                joins = 0
                return false
            }
            val old = newest
            baseN = n
            baseK = key
            joins = 0
            if (old in 0 until n) expire.add(old to now)
            return true
        }

        /** Beitritt eines anderen Geraets: der Sender ratscht (n + 1) */
        fun join(now: Long) {
            if (baseK == null) return
            val old = newest
            joins++
            expire.add(old to now)
        }

        /** Plaetze fuer FrameCryptor: newest und die vier folgenden Stufen */
        fun plan(): List<HornE2E.Slot> {
            val k = baseK ?: return emptyList()
            return HornE2E.cryptorSlots(newest, HornE2E.ratchet(k, joins))
        }

        /** Faellige Ablaeufe (aeltere Generation 3 s nach der Erhoehung): kids zum Ueberschreiben (nicht im Plan) */
        fun expired(now: Long): List<Int> {
            val due = expire.filter { now - it.second >= HornE2E.GRACE_MS.toLong() }
            if (due.isEmpty()) return emptyList()
            expire.removeAll(due.toSet())
            val keep = plan().map { it.kid }.toSet()
            return due.map { it.first % HornE2E.RING }.filter { it !in keep }.distinct()
        }
    }
}
