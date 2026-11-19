package io.github.yggdrasil_sh.saga.call

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Meeting-Ansicht (MEETING-SPEC.md) - reine Regeln ohne Android/WebRTC, gleiche Testvektoren wie Web und iOS
 * (meeting_vectors.json). Gilt fuer 1:1, Mesh-Gruppen und Anrufe ueber den Knoten.
 *
 * Person = `who`, Geraet = `device`, Quelle = Kamera oder Bildschirm eines Geraets. Aus den Geraeten (Roster +
 * Medienzustand) werden Kacheln: je Person EINE Personenkachel (beste Kamera, sonst Avatar), je teilendem Geraet eine
 * Bildschirmkachel, eine zweite Kamera-Kachel nur fuer ein Begleitgeraet mit eigener Kamera.
 */
object Meeting {
    /** Einstellung des Layout-Knopfs (gespeichert je Geraet, DataStore/Prefs `call_layout`) */
    enum class Pref(val key: String) {
        AUTO("auto"), GALLERY("gallery"), SPEAKER("speaker"), PRESENTATION("presentation");

        companion object {
            fun of(s: String?): Pref = entries.firstOrNull { it.key == s } ?: if (s == "grid") GALLERY else AUTO
        }
    }

    /** Tatsaechlich gezeigtes Layout; DUO = Buehne + eigenes Bild schwebend (1:1, im Web und in den Vektoren "one") */
    enum class View(val key: String) { DUO("one"), GALLERY("gallery"), SPEAKER("speaker"), PRESENTATION("presentation") }

    enum class Kind(val key: String) { PERSON("person"), CAM2("cam"), SCREEN("screen") }

    /** Geraet im Anruf mit Medienzustand */
    data class Dev(
        val who: String,
        val name: String,
        val device: String,
        val devName: String = "",
        val companion: Boolean = false,
        val me: Boolean = false,
        val avatar: String? = null,
        val mic: Boolean = false,
        val cam: Boolean = false,
        val screen: Boolean = false,
        /** Pegel 0..1 (Ton dieses Geraets) */
        val level: Double = 0.0,
        val speaking: Boolean = false,
        /** Zeitpunkt, seit dem dieses Geraet teilt (ms; groesser = spaeter gestartet) */
        val sharedAt: Long = 0,
        /** Bildschirm ueber den Knoten nicht abonnierbar (zweite Freigabe ohne `screen_dev`) */
        val screenNa: Boolean = false,
    )

    /** Kachel. device = Geraet, dessen Bild sie zeigt (Personenkachel ohne Video: Hauptgeraet) */
    data class Tile(
        val id: String,
        val kind: Kind,
        val who: String,
        val device: String,
        val name: String,
        /** Zusatz in Klammern ("Android") - nur bei zweiter Kamera bzw. Bildschirm einer Person mit mehreren Geraeten */
        val devName: String = "",
        val me: Boolean = false,
        val video: Boolean = false,
        val mic: Boolean = false,
        val speaking: Boolean = false,
        val level: Double = 0.0,
        val avatar: String? = null,
        val sharedAt: Long = 0,
        val screenNa: Boolean = false,
        /** Geraete der Person (Teilnehmerliste, Kopf "n Personen") */
        val devices: Int = 1,
    ) {
        val screen: Boolean get() = kind == Kind.SCREEN
    }

    data class Model(
        /** Personenkacheln (eigene zuletzt), zweite Kamera-Kacheln direkt hinter ihrer Person */
        val people: List<Tile>,
        /** Bildschirmkacheln anderer und anderer eigener Geraete (§4.2), zuletzt gestartete zuerst */
        val screens: List<Tile>,
        /** Namen der eigenen anderen Geraete (Chip im Kopf "iPhone, Galaxy verbunden") */
        val companions: List<String>,
        /** ich teile meinen Bildschirm (rote Leiste, nicht gespiegelt) */
        val selfSharing: Boolean,
        /** Anzahl Personen (Kopf "3 Personen") */
        val persons: Int,
    ) {
        val all: List<Tile> get() = screens + people
        val self: Tile? get() = people.firstOrNull { it.me }
        val others: List<Tile> get() = people.filter { !it.me }
    }

    /**
     * Kacheln aus den Geraeten. myDevice = dieses Geraet: von der eigenen Person zaehlt nur es (Personenkachel "Du");
     * eigene andere Geraete erscheinen nie als Personen- oder Kamerakachel, sondern im Chip. Teilt ein anderes eigenes
     * Geraet den Bildschirm, kommt dieser als Bildschirmkachel mit Geraetename (§4.2, Bugs-DB 2160) - in beiden Richtungen
     * (Begleitgeraet -> Hauptgeraet und Hauptgeraet -> Begleitgeraet), im Mesh wie ueber den Knoten. Den Bildschirm dieses
     * Geraets nie (die eigene Freigabe zeigt die rote Leiste). Kennungen wie Web/iOS: `who`, `who|geraet:cam`, `who|geraet:scr`.
     */
    fun tiles(devs: List<Dev>, myDevice: String): Model {
        val order = LinkedHashMap<String, MutableList<Dev>>()
        for (d in devs) order.getOrPut(d.who) { ArrayList() }.add(d)
        val people = ArrayList<Tile>()
        var self: Tile? = null
        val screens = ArrayList<Tile>()
        val companions = ArrayList<String>()
        var selfSharing = false
        for ((who, list) in order) {
            val me = list.any { it.me }
            if (me) {
                val mine = list.firstOrNull { it.device == myDevice } ?: list.first()
                list.filter { it.device != mine.device }.forEach { companions.add(it.devName.ifEmpty { it.name }) }
                selfSharing = mine.screen
                self = Tile(
                    who, Kind.PERSON, who, mine.device, mine.name, me = true, video = mine.cam, mic = mine.mic,
                    speaking = mine.speaking, level = mine.level, avatar = mine.avatar, devices = list.size,
                )
                // §4.2: Bildschirm eines ANDEREN eigenen Geraets als Bildschirmkachel (immer mit Geraetename), nie eine
                // Personen- oder Kamerakachel; me = false - das Bild kommt von dort (Mesh-Verbindung bzw. Knoten-Platz)
                for (d in list) if (d.screen && d.device != mine.device) screens.add(
                    Tile("$who|${d.device}:scr", Kind.SCREEN, who, d.device, mine.name.ifEmpty { list.firstNotNullOfOrNull { it.name.ifEmpty { null } } ?: who },
                        devName = d.devName, video = !d.screenNa, sharedAt = d.sharedAt, screenNa = d.screenNa, avatar = mine.avatar, devices = list.size),
                )
                continue
            }
            val main = list.firstOrNull { !it.companion } ?: list.first()
            val camDev = if (main.cam) main else list.firstOrNull { it.cam }
            val src = camDev ?: main
            val name = main.name.ifEmpty { list.firstNotNullOfOrNull { it.name.ifEmpty { null } } ?: who }
            people.add(
                Tile(
                    who, Kind.PERSON, who, src.device, name, video = camDev != null, mic = list.any { it.mic },
                    speaking = list.any { it.speaking }, level = list.maxOf { it.level }, avatar = main.avatar ?: list.firstNotNullOfOrNull { it.avatar },
                    devices = list.size,
                ),
            )
            // zweite Kamera: Begleitgeraet mit eigener Kamera (nur dann)
            for (d in list) if (d.cam && d.device != src.device) people.add(
                Tile("$who|${d.device}:cam", Kind.CAM2, who, d.device, name, devName = d.devName, video = true, mic = d.mic,
                    speaking = d.speaking, level = d.level, avatar = main.avatar, devices = list.size),
            )
            for (d in list) if (d.screen) screens.add(
                Tile("$who|${d.device}:scr", Kind.SCREEN, who, d.device, name, devName = if (list.size > 1) d.devName else "",
                    video = !d.screenNa, sharedAt = d.sharedAt, screenNa = d.screenNa, devices = list.size),
            )
        }
        self?.let { people.add(it) }
        // zuletzt gestartete Freigabe zuerst (Buehne), gleiche Zeit: Reihenfolge der Liste
        val sortedScreens = screens.withIndex().sortedWith(compareByDescending<IndexedValue<Tile>> { it.value.sharedAt }.thenBy { it.index }).map { it.value }
        return Model(people, sortedScreens, companions, selfSharing, order.size)
    }

    /** §3 Kacheln ohne Video ausgeblendet: Nachlauf, solange eine Person nach dem Sprechen sichtbar bleibt */
    const val NOVIDEO_TAIL = 5000L

    /**
     * §3 "Kacheln ohne Video ausblenden": bleibt diese Kachel? Mit Video, die eigene, wer spricht und wer vor weniger als
     * 5 s gesprochen hat (spokeAt = who -> letzter Sprechzeitpunkt, ms).
     */
    fun keepNoVideo(t: Tile, spokeAt: Map<String, Long>, now: Long): Boolean =
        t.video || t.me || t.speaking || (spokeAt[t.who]?.let { now - it <= NOVIDEO_TAIL } == true)

    /** Sichtbare Kacheln (Personen + zweite Kameras, ohne Bildschirme) - Grundlage der Auto-Grenzen */
    fun visible(m: Model): Int = m.people.size

    /**
     * Layout aus Einstellung und Lage (§2.1), wie meeting.js pickLayout (Bugs-DB 2161): gewaehlte Galerie/Sprecher ab zwei
     * Kacheln (Galerie im 1:1 = Galerie mit zwei Kacheln), sonst 1:1 (DUO). reducedMotion: kein Auto-Sprecher bis 8
     * Kacheln (§10). Praesentation ohne Freigabe verhaelt sich wie Sprecher.
     */
    fun pickLayout(pref: Pref, m: Model, reducedMotion: Boolean = false): View {
        val n = visible(m)
        val share = m.screens.isNotEmpty()
        val all = n + m.screens.size
        return when (pref) {
            Pref.GALLERY -> if (all <= 1) View.DUO else View.GALLERY
            Pref.SPEAKER -> if (all <= 1) View.DUO else View.SPEAKER
            Pref.PRESENTATION -> if (share) View.PRESENTATION else if (n <= 1) View.DUO else View.SPEAKER
            Pref.AUTO -> when {
                share -> View.PRESENTATION
                n <= 2 -> View.DUO
                n <= 4 -> View.GALLERY
                reducedMotion && n <= 8 -> View.GALLERY
                else -> View.SPEAKER
            }
        }
    }

    /** Layout-Knopf: im 1:1 ohne Teilen und ohne zweite Kachel ausgeblendet */
    fun layoutButton(m: Model): Boolean = m.screens.isNotEmpty() || visible(m) > 2

    /**
     * Eigenes Bild schwebend (sonst Kachel im Raster bzw. letzte im Streifen) - §7 wie meeting.js arrange: im 1:1 (DUO) und
     * im Sprecher-Layout mit hoechstens zwei Personen (Bugs-DB 2161); in der Praesentation nie ueber dem geteilten
     * Bildschirm, sondern zuletzt im Streifen.
     */
    fun selfFloats(view: View, m: Model): Boolean =
        view == View.DUO || (view == View.SPEAKER && m.self != null && m.people.count { it.kind == Kind.PERSON } <= 2)

    // ------------------------------------------------------------ Galerie

    data class Grid(val cols: Int, val perPage: Int, val videos: Int)

    /** §2.2 Spalten / Kacheln je Seite / davon mit Video nach Fensterbreite (dp) */
    fun grid(widthDp: Float, heightDp: Float, n: Int): Grid = when {
        widthDp > heightDp && heightDp < 500 -> Grid(if (n <= 2) 2 else 3, 6, 4)       // Handy quer
        widthDp < 600 -> Grid(if (n <= 2) 1 else 2, 6, 4)                              // Handy hochkant
        widthDp < 1024 -> Grid(if (n <= 4) 2 else 3, 9, 6)
        widthDp < 1920 -> Grid(if (n <= 4) 2 else if (n <= 9) 3 else 4, 16, 9)
        else -> Grid(if (n <= 4) 2 else if (n <= 9) 3 else if (n <= 16) 4 else 5, 25, 16)
    }.let { it.copy(cols = min(it.cols, max(1, n))) }                                 // wie meeting.js: nie mehr Spalten als Kacheln

    /**
     * Reihenfolge der Galerie: Pin -> Bildschirme -> Personen mit Video -> Sprecher der letzten 30 s -> Rest alphabetisch;
     * eigene Kachel zuletzt. spokeAt = who -> letzter Sprechzeitpunkt (ms).
     */
    fun galleryOrder(m: Model, pin: String?, spokeAt: Map<String, Long>, now: Long): List<Tile> {
        // Bildschirme wie meeting.js meetingTiles: aelteste Freigabe zuerst (das Modell haelt die neueste vorn, Buehne)
        val screens = m.screens.withIndex().sortedWith(compareBy<IndexedValue<Tile>> { it.value.sharedAt }.thenBy { it.index }).map { it.value }
        val all = screens + m.people.filter { !it.me }
        val pinned = all.filter { it.id == pin }
        val rest = all.filter { it.id != pin }
        val scr = rest.filter { it.screen }
        val ppl = rest.filter { !it.screen }
        val vid = ppl.filter { it.video }
        // Sprecher der letzten 30 s in Listenfolge (wie das Web), der Rest alphabetisch (gleiche Namen: Listenfolge)
        val recent = ppl.filter { !it.video && (spokeAt[it.who]?.let { now - it <= 30_000 } == true) }
        val others = (ppl - vid.toSet() - recent.toSet()).sortedBy { it.name.lowercase() }
        return pinned + scr + vid + recent + others + listOfNotNull(m.self)
    }

    /** Seiten (je perPage), mindestens eine */
    fun <T> pages(list: List<T>, perPage: Int): List<List<T>> = if (list.isEmpty()) listOf(emptyList()) else list.chunked(max(1, perPage))

    /**
     * Video-Vorrang (§2.2/§8): hoechstens max Kacheln einer Seite zeigen Video; Pin -> aktiver Sprecher -> zuletzt
     * gesprochen -> Reihenfolge. Rueckgabe: ids mit Video.
     */
    fun videoSlots(tiles: List<Tile>, max: Int, pin: String?, active: String?, spokeAt: Map<String, Long>): Set<String> {
        // das eigene Bild wird nicht dekodiert und zaehlt nicht mit
        val own = tiles.filter { it.video && it.me }.map { it.id }.toSet()
        val cand = tiles.filter { it.video && !it.me }
        if (cand.size <= max) return cand.map { it.id }.toSet() + own
        val ranked = cand.withIndex().sortedWith(
            compareBy<IndexedValue<Tile>>(
                { if (it.value.id == pin) 0 else 1 },
                { if (it.value.who == active && !it.value.screen) 0 else 1 },
                { -(spokeAt[it.value.who] ?: 0L) },
                { it.index },
            ),
        ).map { it.value.id }
        return ranked.take(max).toSet() + own
    }

    // ------------------------------------------------------------ Streifen / Buehne

    enum class Side { RIGHT, BOTTOM }

    /** Lage und Groesse des Streifens (§2.3/§2.4/§8) in dp: size = Breite (rechts) bzw. Hoehe (unten) */
    data class Strip(val side: Side, val size: Float, val videos: Int)

    /** presentation: Bildschirm auf der Buehne - der Streifen bleibt bis 1440 dp schmal (160, wie Web/iOS, Bugs-DB 2161) */
    fun strip(widthDp: Float, heightDp: Float, presentation: Boolean = false): Strip = when {
        widthDp > heightDp && heightDp < 500 -> Strip(Side.RIGHT, 120f, 3)             // Handy quer
        widthDp < 600 -> Strip(Side.BOTTOM, 84f, 3)                                     // Handy hochkant
        widthDp < 900 -> Strip(Side.BOTTOM, 96f, 6)
        widthDp < 1100 || (presentation && widthDp < 1440) -> Strip(Side.RIGHT, 160f, 6)
        widthDp < 1920 -> Strip(Side.RIGHT, 200f, 6)
        else -> Strip(Side.RIGHT, 240f, 6)
    }

    /** Buehne und Streifen in Sprecher/Praesentation */
    data class Stage(val stage: Tile?, val strip: List<Tile>)

    /**
     * Praesentation: Pin bzw. zuletzt gestartete Freigabe; Sprecher: Pin bzw. Sprecher (Hysterese) bzw. erste andere Person.
     * Streifen: die anderen Bildschirme vorn, dann Personen; eigene Kachel zuletzt, wenn sie nicht schwebt.
     */
    fun stage(view: View, m: Model, pin: String?, speaker: String?): Stage {
        val pinned = m.all.firstOrNull { it.id == pin && !it.me }
        val st: Tile? = when (view) {
            View.PRESENTATION -> pinned ?: m.screens.firstOrNull()
            View.SPEAKER, View.DUO -> pinned ?: m.others.firstOrNull { it.who == speaker && it.kind == Kind.PERSON } ?: m.others.firstOrNull()
                ?: m.screens.firstOrNull()
            View.GALLERY -> null
        }
        val floats = selfFloats(view, m)
        val rest = m.screens.filter { it.id != st?.id } + m.others.filter { it.id != st?.id } +
            listOfNotNull(m.self.takeIf { !floats })
        return Stage(st, rest)
    }

    // ------------------------------------------------------------ Leisten

    const val IDLE_MS = 4000L

    /** §6.1: Kopf/Knoepfe ausblenden? */
    fun hideControls(
        idleMs: Long, connected: Boolean, video: Boolean, screenReader: Boolean, menuOpen: Boolean, notice: Boolean,
        pointerOverBar: Boolean = false,
    ): Boolean = connected && video && !screenReader && !menuOpen && !notice && !pointerOverBar && idleMs >= IDLE_MS

    // ------------------------------------------------------------ Eigenes Bild

    /** Groessenstufe S/M/L (Doppeltippen schaltet weiter) */
    val SIZE_FACTORS = floatArrayOf(0.7f, 1.0f, 1.4f)

    /**
     * Rahmen des eigenen Bilds im Seitenverhaeltnis der Kamera - eine Rechnung fuer Web, iOS und Android (meeting.js
     * selfPipSize, Vektoren meeting_vectors.json, Bugs-DB 2161): Breite 22 % (Kamera quer, 120..280) bzw. 26 % (Kamera
     * hochkant, 90..160) der Buehnenbreite, mal factor (S/M/L), Verhaeltnis 0,5..2,0; passt es nicht zweimal uebereinander
     * in avail (Hoehe), kleiner bis hoechstens zur Haelfte; gerundet. aspect null/ungueltig (noch kein Bild): die Lage der
     * Buehne waehlt die Breitenregel, Verhaeltnis = fallback. Gerechnet in Double (wie JS), damit .5-Grenzen gleich runden.
     */
    fun selfPipSize(aspect: Float?, fallback: Float, width: Float, avail: Float, factor: Float = 1f): Pair<Float, Float> {
        val r = selfPipSizeD(aspect?.toDouble(), fallback.toDouble(), width.toDouble(), avail.toDouble(), factor.toDouble())
        return r.first.toFloat() to r.second.toFloat()
    }

    fun selfPipSizeD(aspect: Double?, fallback: Double, width: Double, avail: Double, factor: Double = 1.0): Pair<Double, Double> {
        var a = aspect ?: 0.0
        val known = a.isFinite() && a > 0.0
        val land = if (known) a >= 1.0 else width >= avail
        if (!known) a = if (fallback.isFinite() && fallback > 0.0) fallback else if (land) 16.0 / 9.0 else 9.0 / 16.0
        a = min(2.0, max(0.5, a))
        var w = (if (land) min(280.0, max(120.0, 0.22 * width)) else min(160.0, max(90.0, 0.26 * width))) * factor
        var h = w / a
        if (avail > 0.0 && 2 * h > avail) {
            w = min(w, max(w / 2, avail / 2 * a))
            h = w / a
        }
        return w.roundToInt().toDouble() to h.roundToInt().toDouble()
    }

    /** Ecke nach dem Loslassen (0 o.l., 1 o.r., 2 u.l., 3 u.r.) */
    fun corner(cx: Float, cy: Float, width: Float, top: Float, bottom: Float): Int =
        (if (cx < width / 2f) 0 else 1) + (if (cy < (top + bottom) / 2f) 0 else 2)

    // ------------------------------------------------------------ Zoom

    /** Zoom des geteilten Bildschirms 1x..4x; Verschiebung so begrenzen, dass kein Rand ins Bild kommt */
    fun zoom(scale: Float): Float = scale.coerceIn(1f, 4f)

    fun pan(off: Float, scale: Float, size: Float): Float {
        val lim = (size * (scale - 1f)) / 2f
        return off.coerceIn(-lim, lim)
    }
}

/**
 * Sprechererkennung mit Hysterese (§2.3), eine Logik fuer alle Plattformen: Pegel je Person ca. alle 330 ms.
 *  - "spricht" = Pegel > 0,03 seit >= 300 ms, mit 600 ms Nachlauf (Sprechring).
 *  - Buehnenwechsel nur, wenn die neue Person >= 1,5 s am Stueck lauter ist als die aktuelle UND seit dem letzten Wechsel
 *    >= 3 s vergangen sind. Niemand spricht -> Buehne bleibt. Eigene Stimme bewegt nie die Buehne.
 *  - Noch keine Buehne: die erste Person, die spricht.
 */
class SpeakerPick(private val self: String? = null) {
    companion object {
        const val THRESHOLD = 0.03
        const val ON_MS = 300L
        const val HOLD_MS = 600L
        const val LOUDER_MS = 1500L
        const val GAP_MS = 3000L
    }

    var stage: String? = null
        private set
    private var lastSwitch = Long.MIN_VALUE / 2
    private val above = HashMap<String, Long>()          // seit wann ueber der Schwelle
    private val lastAbove = HashMap<String, Long>()      // zuletzt ueber der Schwelle
    private val louder = HashMap<String, Long>()         // seit wann lauter als die Buehne
    /** letzter Sprechzeitpunkt je Person (Galerie-Reihenfolge) */
    val spokeAt = HashMap<String, Long>()

    /** Spricht diese Person gerade (Sprechring)? */
    fun speaking(who: String, now: Long): Boolean {
        val a = above[who]
        if (a != null && now - a >= ON_MS) return true
        val l = lastAbove[who] ?: return false
        return a == null && now - l <= HOLD_MS && wasOn.contains(who)
    }

    private val wasOn = HashSet<String>()

    /** Pegel eintragen; Rueckgabe: Person auf der Buehne */
    fun feed(levels: Map<String, Double>, now: Long): String? {
        for ((w, lv) in levels) {
            if (lv > THRESHOLD) {
                if (above[w] == null) above[w] = now
                lastAbove[w] = now
                if (now - above[w]!! >= ON_MS) {
                    wasOn.add(w)
                    spokeAt[w] = now
                }
            } else {
                above.remove(w)
                if (lastAbove[w]?.let { now - it > HOLD_MS } != false) wasOn.remove(w)
            }
        }
        for (w in above.keys.toList()) if (w !in levels) above.remove(w)
        val cur = stage
        val curLv = cur?.let { levels[it] } ?: 0.0
        val cands = levels.filter { (w, lv) -> w != self && w != cur && lv > THRESHOLD && lv > curLv }
        for (w in louder.keys.toList()) if (w !in cands) louder.remove(w)
        for (w in cands.keys) if (louder[w] == null) louder[w] = now
        if (cur == null) {
            val first = levels.filter { (w, _) -> w != self && above[w]?.let { now - it >= ON_MS } == true }.maxByOrNull { it.value }
            if (first != null) {
                stage = first.key
                lastSwitch = now
                louder.clear()
            }
            return stage
        }
        if (now - lastSwitch < GAP_MS) return stage
        val best = louder.filter { now - it.value >= LOUDER_MS }.keys.maxByOrNull { levels[it] ?: 0.0 }
        if (best != null) {
            stage = best
            lastSwitch = now
            louder.clear()
        }
        return stage
    }

    /** Person verlaesst den Anruf */
    fun drop(who: String) {
        above.remove(who); lastAbove.remove(who); louder.remove(who); wasOn.remove(who); spokeAt.remove(who)
        if (stage == who) stage = null
    }
}
