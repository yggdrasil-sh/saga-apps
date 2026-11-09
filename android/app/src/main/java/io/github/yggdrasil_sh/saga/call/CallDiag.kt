package io.github.yggdrasil_sh.saga.call

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 2.5 Anruf-Diagnose (Analyse 1.11.: Videoanruf ueber den Knoten ruckelte, Qualitaetsanzeige sprang rot/gruen). Reine
 * Logik ohne WebRTC (Unit-Test CallDiagTest), gleich wie ios/App/CallDiagLogic.swift:
 * - [Gauge]: Empfangsqualitaet ueber ein gleitendes 30-s-Fenster statt der letzten 3 s (Einfrierer je Minute mindestens
 *   auf 30 s bezogen - ein Einfrieren in 3 s war sonst 20/min = sofort rot)
 * - [Debounce]: Anzeige wechselt erst nach zwei gleichen Messungen in Folge (Schwellen bleiben HornLogic.quality)
 * - [Hold]: Waerme-Hysterese - einmal heiss mindestens 60 s, zurueck erst nach 60 s am Stueck unter der Schwelle
 * - [Buffer]: Proben/Ereignisse fuer POST /api/calls/diag (Feature calldiag1): alle 30 s und beim Auflegen, hoechstens
 *   ein POST je 15 s, <= 32 KB, <= 200 Proben, <= 100 Ereignisse; Quellen nur als Kuerzel p1, p2 ... (nie Name/Adresse)
 */
object CallDiag {
    const val FEATURE = "calldiag1"
    const val PATH = "api/calls/diag"
    const val WINDOW = 30_000L
    const val EVERY = 30_000L
    const val MIN_GAP = 15_000L
    const val HOLD = 60_000L
    const val MAX_BYTES = 32 * 1024
    const val MAX_SAMPLES = 200
    const val MAX_EVENTS = 100
    const val MAX_TEXT = 120

    /** ab welcher Waermestufe gedrosselt wird: PowerManager.THERMAL_STATUS_SEVERE (3) - wie iOS .serious */
    const val HOT_FROM = 3

    fun hotRaw(thermalStatus: Int): Boolean = thermalStatus >= HOT_FROM

    data class Win(val loss: Double, val freezes: Double)

    /** kumulative Zaehler (getStats) -> Verlust und Einfrierer je Minute ueber die letzten 30 s */
    class Gauge(private val window: Long = WINDOW) {
        private data class Pt(val t: Long, val lost: Long, val recv: Long, val fz: Long)
        private val pts = ArrayList<Pt>()
        val size: Int get() = pts.size
        fun push(t: Long, lost: Long, recv: Long, fz: Long): Win {
            val l = pts.lastOrNull()
            if (l != null && (lost < l.lost || recv < l.recv || fz < l.fz || t < l.t)) pts.clear()   // Zaehler neu (anderes Bein)
            pts.add(Pt(t, lost, recv, fz))
            while (pts.size > 2 && pts[1].t <= t - window) pts.removeAt(0)
            val b = pts[0]
            val dl = lost - b.lost
            val dr = recv - b.recv
            val loss = if (dl + dr > 0) maxOf(0L, dl).toDouble() / (dl + dr) else 0.0
            val mins = maxOf(window, t - b.t) / 60_000.0
            return Win(loss, maxOf(0L, fz - b.fz) / mins)
        }
    }

    /** Anzeige erst nach [need] gleichen Messungen in Folge wechseln */
    class Debounce<T>(start: T, private val need: Int = 2) {
        var shown: T = start
            private set
        private var pend: T? = null
        private var n = 0
        fun push(v: T): T {
            if (v == shown) { pend = null; n = 0; return shown }
            if (pend == v) n++ else { pend = v; n = 1 }
            if (n >= need) { shown = v; pend = null; n = 0 }
            return shown
        }
    }

    /** Waerme-Hysterese: heiss -> mindestens [ms] heiss; zurueck erst nach [ms] am Stueck unter der Schwelle */
    class Hold(private val ms: Long = HOLD) {
        var on = false
            private set
        private var since = 0L
        private var coolSince: Long? = null
        fun update(now: Long, raw: Boolean): Boolean {
            if (raw) {
                if (!on) { on = true; since = now }
                coolSince = null
                return true
            }
            if (!on) return false
            val c = coolSince ?: now
            coolSince = c
            if (now - since >= ms && now - c >= ms) { on = false; coolSince = null }
            return on
        }
    }

    /** 2.5 (9.11.): Kamera am Knoten frueher drosseln ab THERMAL_STATUS_MODERATE (2) - der Bildschirm (5 fps) erst ab [HOT_FROM] */
    const val WARM_FROM = 2

    /**
     * 2.5 (9.11., echter Anruf: Pixel nach 12-25 min bei ~30 kbit/s und 1-4 fps): Kamera ohne h und mit hoechstens 15 fps,
     * wenn das Geraet warm ist (>= MODERATE) oder der Kodierer anhaltend (> [cpuMs]) durch die CPU begrenzt ist - bevor
     * libwebrtc (BALANCED) selbst bis an die Untergrenze herunterfaehrt. Zurueck erst nach [calmMs] am Stueck ohne beides;
     * springt es kurz nach dem Hochschalten wieder an, verdoppelt sich die Wartezeit (hoechstens [maxCalmMs]), sonst pendelte
     * es (gedrosselt ist die CPU-Grenze weg, ungedrosselt kommt sie wieder). cpu = null: keine Messung (Kamera aus).
     */
    class Throttle(private val cpuMs: Long = 20_000, private val calmMs: Long = 60_000, private val maxCalmMs: Long = 300_000) {
        var on = false
            private set
        var why = ""
            private set
        private var cpuSince: Long? = null
        private var calmSince: Long? = null
        private var offAt = 0L
        private var need = calmMs
        val calmNeed: Long get() = need
        fun update(now: Long, thermal: Int, cpu: Boolean?): Boolean {
            if (cpu == true) { if (cpuSince == null) cpuSince = now } else if (cpu == false) cpuSince = null
            val warm = thermal >= WARM_FROM
            val cpuLong = cpuSince?.let { now - it >= cpuMs } == true
            if (!on) {
                if (warm || cpuLong) {
                    on = true
                    why = if (warm) "waerme $thermal" else "cpu ${(now - (cpuSince ?: now)) / 1000} s"
                    need = if (offAt > 0 && now - offAt < 2 * maxCalmMs) minOf(maxCalmMs, need * 2) else calmMs
                    calmSince = null
                }
                return on
            }
            if (warm || cpu == true) { calmSince = null; return true }
            val c = calmSince ?: now
            calmSince = c
            if (now - c >= need) { on = false; why = ""; offAt = now; calmSince = null; cpuSince = null }
            return on
        }
    }

    /**
     * 2.5 (9.11.): ueberwiegend CPU-begrenzt seit der letzten Messung? cpuS = qualityLimitationDurations.cpu (Sekunden,
     * kumulativ, hoechster Wert der Kamera-Schichten), prev = (cpuS, t) davor. null = keine Aussage (erste Messung, Zaehler neu)
     */
    fun cpuLimited(prevS: Double?, prevT: Long?, cpuS: Double?, now: Long, share: Double = 0.5): Boolean? {
        if (cpuS == null || prevS == null || prevT == null || now <= prevT || cpuS < prevS) return null
        return (cpuS - prevS) * 1000 / (now - prevT) >= share
    }

    data class Sample(
        val t: Long, val dir: String, val src: String, val rid: String,
        val fps: Int = 0, val h: Int = 0, val kbps: Int = 0, val lost: Int = 0, val nack: Int = 0, val pli: Int = 0,
        val freeze: Int = 0, val kf: Int = 0, val rtt: Int = 0, val qlr: String = "", val enc: String = "",
        /** 2.5 Geraete-CPU (Labor 2.11.): ms CPU- bzw. Bandbreiten-begrenzt (out), Kodier-/Dekodierzeit je Rahmen (ms),
         *  nicht kodierte Kamerarahmen (out) bzw. verworfene Rahmen (in), Dauer der Standbilder (in, ms) - Zuwaechse */
        val qcpu: Int = 0, val qbw: Int = 0, val encms: Double = 0.0, val fdrop: Int = 0, val decms: Double = 0.0, val fzms: Int = 0,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("t", t); put("dir", dir); put("src", src); put("rid", rid); put("fps", fps); put("h", h); put("kbps", kbps)
            put("lost", lost); put("nack", nack); put("pli", pli); put("freeze", freeze); put("kf", kf); put("rtt", rtt)
            put("qlr", qlr); put("enc", enc)
            // Werte 0 weglassen (aeltere Server verwerfen die Felder still)
            if (qcpu > 0) put("qcpu", qcpu)
            if (qbw > 0) put("qbw", qbw)
            if (encms > 0) put("encms", encms)
            if (fdrop > 0) put("fdrop", fdrop)
            if (decms > 0) put("decms", decms)
            if (fzms > 0) put("fzms", fzms)
        }
    }

    /** Eintrag `mediaSourceId` -> Rahmen der Kamera (fuer fdrop beim Senden); Aufrufer legen ihn unter diesem Schluessel ab */
    const val SRC_FRAMES = "_srcFrames"

    /** members eines outbound-rtp um die Rahmen seiner Quelle (media-source.frames) ergaenzen */
    fun withSource(m: Map<String, Any?>, source: Map<String, Any?>?): Map<String, Any?> {
        val f = source?.get("frames") ?: return m
        return m + (SRC_FRAMES to f)
    }

    data class Event(val t: Long, val k: String, val d: String) {
        fun json(): JsonObject = buildJsonObject { put("t", t); put("k", k); put("d", d) }
    }

    val KINDS = setOf("layers", "thermal", "switch", "quality", "path")
    private val QLRS = setOf("none", "cpu", "bandwidth", "other")
    private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    private val IPV6 = Regex("""\b[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){3,7}\b""")

    /** Kurztext: eine Zeile, Adressen entfernt, hoechstens 120 Zeichen */
    fun clean(s: String): String = IPV6.replace(IPV4.replace(s.replace('\n', ' '), "*"), "*").take(MAX_TEXT)

    fun transportHorn(path: String?): String = when (path) {
        "udp" -> "udp"
        "tcp" -> "tcp"
        "turn" -> "tls"
        else -> "?"
    }

    fun transportP2p(p: CallV3.Path?): String = when (p) {
        CallV3.Path.DIRECT, CallV3.Path.RELAY_UDP -> "udp"
        CallV3.Path.RELAY_TCP -> "tcp"
        null -> "?"
    }

    private val SRC_BAD = Regex("""[^A-Za-z0-9_\-:.]""")
    private val ENC_BAD = Regex("""[^A-Za-z0-9 _()\[\].,:+/\-]""")
    private val RIDS = setOf("l", "m", "h")

    /** Zeichensatz des Servers (saga/calldiag.py _SRC/_ENC) */
    fun src(s: String): String = SRC_BAD.replace(s, "").take(16)
    fun enc(s: String): String = ENC_BAD.replace(s, "").take(60)

    /** Zahl fuer den Server: endlich, >= 0, ganzzahlig */
    fun int(v: Double?): Int = if (v == null || !v.isFinite() || v <= 0) 0 else if (v >= 1e9) 1_000_000_000 else Math.round(v).toInt()

    private fun n(v: Any?): Double? = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull()

    class Buffer(val call: String, val plat: String, val app: String, val t0: Long) {
        var path = "p2p"
        var transport = "?"
        var node = ""
        val samples = ArrayList<Sample>()
        val events = ArrayList<Event>()
        var lastPost: Long? = null
            private set
        private val aliases = HashMap<String, String>()
        private val prev = HashMap<String, Double>()
        private val rate = HashMap<String, Pair<Double, Long>>()
        private val sampled = HashMap<String, Long>()

        fun ms(now: Long): Long = maxOf(0L, now - t0)

        /** Geraete-ID -> p1, p2 ... (je Anruf fest); unbekannt -> "" (Server nimmt fuer src nur [A-Za-z0-9_-:.] an - "?" verwarf die Meldung) */
        fun alias(dev: String?): String {
            if (dev.isNullOrEmpty()) return ""
            return aliases.getOrPut(dev) { "p${aliases.size + 1}" }
        }

        private fun delta(key: String, v: Double): Int {
            val p = prev[key]
            prev[key] = v
            return if (p == null || v < p) 0 else int(v - p)
        }

        /** Zuwachs als Zahl; null = erste Messung, Zaehler neu bzw. Wert fehlt */
        private fun dd(key: String, v: Double?): Double? {
            if (v == null || !v.isFinite()) return null
            val p = prev[key]
            prev[key] = v
            return if (p == null || v < p) null else v - p
        }

        private fun per(ms: Double?, frames: Double?): Double =
            if (ms == null || frames == null || frames <= 0 || ms <= 0) 0.0 else Math.round(ms / frames * 10) / 10.0

        private fun kbps(key: String, bytes: Double, t: Long): Int {
            val p = rate[key]
            rate[key] = bytes to t
            return if (p == null || t <= p.second || bytes < p.first) 0 else int((bytes - p.first) * 8 / (t - p.second))
        }

        /** hoechstens eine Probe je Spur und [every] ms */
        fun sampleDue(key: String, now: Long, every: Long = 2500): Boolean {
            val s = sampled[key]
            if (s != null && now - s < every) return false
            sampled[key] = now
            return true
        }

        /** Probe aus einem getStats-Eintrag (nur Video, inbound-rtp/outbound-rtp); members = RTCStats.members */
        fun sample(type: String, m: Map<String, Any?>, src: String, key: String, now: Long, rtt: Double?): Sample? {
            if (type != "inbound-rtp" && type != "outbound-rtp") return null
            val kind = (m["kind"] ?: m["mediaType"])?.toString()
            if (kind != "video") return null
            val out = type == "outbound-rtp"
            val q = m["qualityLimitationReason"]?.toString().orEmpty()
            val rid = if (out) m["rid"]?.toString().orEmpty().takeIf { it in RIDS }.orEmpty() else ""
            // 2.5 Geraete-CPU (wie static/calls.js diagRep)
            val qd = m["qualityLimitationDurations"] as? Map<*, *>
            val dFr = dd("$key.fr", n(m[if (out) "framesEncoded" else "framesDecoded"]))
            val dTt = dd("$key.tt", n(m[if (out) "totalEncodeTime" else "totalDecodeTime"]))?.times(1000)
            val qcpu = if (out) dd("$key.qcpu", n(qd?.get("cpu"))?.times(1000)) else null
            val qbw = if (out) dd("$key.qbw", n(qd?.get("bandwidth"))?.times(1000)) else null
            val fdrop = if (out) {
                // Kamerarahmen, die der Kodierer nicht kodiert hat - nur Schichten mit voller Bildrate (l bewusst weniger)
                val dSrc = dd("$key.src", n(m[SRC_FRAMES]))
                if (rid != "l" && dSrc != null && dFr != null) maxOf(0.0, dSrc - dFr) else null
            } else dd("$key.drop", n(m["framesDropped"]))
            val fzms = if (out) null else dd("$key.fzd", n(m["totalFreezesDuration"])?.times(1000))
            val perFrame = per(dTt, dFr)
            return Sample(
                t = ms(now), dir = if (out) "out" else "in", src = CallDiag.src(src),
                rid = rid,
                fps = int(n(m["framesPerSecond"])),
                h = int(n(m["frameHeight"])),
                kbps = kbps(key, n(m[if (out) "bytesSent" else "bytesReceived"]) ?: 0.0, now),
                lost = if (out) 0 else delta("$key.lost", n(m["packetsLost"]) ?: 0.0),
                nack = delta("$key.nack", n(m["nackCount"]) ?: 0.0),
                pli = delta("$key.pli", n(m["pliCount"]) ?: 0.0),
                freeze = if (out) 0 else delta("$key.fz", n(m["freezeCount"]) ?: 0.0),
                kf = delta("$key.kf", n(m[if (out) "keyFramesEncoded" else "keyFramesDecoded"]) ?: 0.0),
                rtt = int(rtt),
                qlr = if (!out || q.isEmpty()) "" else if (q in QLRS) q else "other",
                enc = if (out) CallDiag.enc(m["encoderImplementation"]?.toString().orEmpty()) else "",
                qcpu = int(qcpu), qbw = int(qbw), fdrop = int(fdrop), fzms = int(fzms),
                encms = if (out) perFrame else 0.0, decms = if (out) 0.0 else perFrame,
            )
        }

        fun add(s: Sample) {
            samples.add(s)
            if (samples.size > MAX_SAMPLES * 2) samples.subList(0, samples.size - MAX_SAMPLES).clear()
        }

        fun event(k: String, d: String, now: Long) {
            if (k !in KINDS) return
            events.add(Event(ms(now), k, clean(d)))
            if (events.size > MAX_EVENTS * 2) events.subList(0, events.size - MAX_EVENTS).clear()
        }

        val isEmpty: Boolean get() = samples.isEmpty() && events.isEmpty()
        /** regelmaessiger POST faellig (alle 30 s ab Anrufbeginn) */
        fun due(now: Long): Boolean = now - (lastPost ?: t0) >= EVERY
        /** Wartezeit bis ein POST erlaubt ist (hoechstens einer je 15 s) */
        fun wait(now: Long): Long = lastPost?.let { maxOf(0L, MIN_GAP - (now - it)) } ?: 0L

        /** Inhalt fuer den POST (<= 32 KB, neueste behalten) und Puffer leeren; null = nichts zu senden */
        fun take(now: Long): JsonObject? {
            if (isEmpty || wait(now) > 0) return null
            var s = samples.takeLast(MAX_SAMPLES)
            var e = events.takeLast(MAX_EVENTS)
            fun body() = buildJsonObject {
                put("v", 1); put("call", call); put("plat", plat); put("app", app); put("path", path); put("transport", transport)
                put("node", node)
                put("samples", JsonArray(s.map { it.json() }))
                put("events", JsonArray(e.map { it.json() }))
            }
            var b = body()
            while (b.toString().toByteArray().size > MAX_BYTES && (s.isNotEmpty() || e.isNotEmpty())) {
                if (s.isNotEmpty()) s = s.drop(maxOf(1, s.size / 10)) else e = e.drop(maxOf(1, e.size / 10))
                b = body()
            }
            samples.clear()
            events.clear()
            lastPost = now
            return b
        }
    }
}
