package io.github.yggdrasil_sh.saga.call

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// 2.4 E4/E5: Anruf als Raum - Mehrgeraete (Begleitgeraet, Uebernahme) und Gruppenanrufe als Mesh.
// Vertrag: Server docs/APP-API.md "Anrufe: Mehrgeraete und Gruppen", foederiert APP-API-FED.md §7.2.

/** Endpunkt (Geraet) im Anruf laut Server (`legs`) */
@Serializable
data class CallLeg(
    /** Benutzername (lokal) bzw. Prinzipal der Person (foederiert) */
    val who: String = "",
    val name: String = "",
    val handle: String? = null,
    val host: String? = null,
    val avatar: String? = null,
    val device: String = "",
    @SerialName("dev_name") val devName: String = "",
    /** member | companion | handover */
    val role: String = "member",
    val state: String = "active",
    val caps: List<String> = emptyList(),
    val joined: Double? = null,
    val me: Boolean = false,
    val replaces: String? = null,
    /** 2.5 Gjallarhorn: Kennung des Geraets am Knoten des Anrufs (gleich `peer` im eigenen Kandidaten) */
    val hp: String? = null,
    /** 2.5: Kennung des Geraets je Knoten, auf dem der Anruf laufen kann (Wechsel: je Verbindung die dieses Knotens) */
    val hpn: Map<String, String> = emptyMap(),
) {
    val companion: Boolean get() = role == CallRoom.ROLE_COMPANION
    val multi: Boolean get() = CallRoom.CAP_MULTI in caps

    /** Person fuer Schluessel, Signatur und Anzeige */
    fun person(): CallPeer = CallPeer(who, name, avatar, handle, host)
}

/** `chat.call` in GET /api/chats/{id}: Gruppenanruf moeglich? Laeuft einer? */
@Serializable
data class ChatCallInfo(
    val group: Boolean = false,
    @SerialName("group_block") val groupBlock: String? = null,
    val running: RunningCall? = null,
)

@Serializable
data class RunningCall(
    val id: String = "",
    val media: String = "audio",
    val kind: String = "dm",
    val state: String = "",
    val n: Int = 0,
    val joinable: Boolean = false,
    @SerialName("join_block") val joinBlock: String? = null,
    val mine: List<String> = emptyList(),
)

/** Laufender Anruf aus Sicht eines anderen eigenen Geraets: Leiste "Anruf laeuft auf …" bzw. "Gruppenanruf laeuft" */
data class RunBar(
    val callId: String,
    val chatId: Long,
    val kind: String,
    val media: String,
    /** Geraetename, auf dem der Anruf laeuft (1:1); leer bei Gruppen ohne eigenes Geraet */
    val onDevice: String,
    /** Geraete im Anruf (Gruppe) */
    val devices: Int,
    val joinable: Boolean,
    val block: String?,
    val limit: Int,
    val title: String = "",
) {
    val group: Boolean get() = kind == CallRoom.KIND_GROUP
    /** Eigenes Geraet ist schon im Anruf: Hinzufuegen + Hierher uebernehmen; sonst (Gruppe) Beitreten */
    val mineRunning: Boolean get() = onDevice.isNotEmpty()
}

/** Kachel im Mesh (je Verbindung zu einem anderen Geraet) */
data class CallTile(
    val device: String,
    val name: String,
    val devName: String,
    val video: Boolean,
    val mic: Boolean,
    val speaking: Boolean,
    val companion: Boolean,
    val own: Boolean,
    val connected: Boolean,
    val screen: Boolean = false,
)

/** Raum-Teil des Anrufbildschirms */
data class RoomUi(
    val kind: String = CallRoom.KIND_DM,
    /** Kachelansicht (Gruppe oder mehr als eine sichtbare Verbindung) */
    val mesh: Boolean = false,
    val tiles: List<CallTile> = emptyList(),
    /** Dieses Geraet ist Begleitgeraet (ohne Mikrofon und Ton) */
    val companion: Boolean = false,
    /** Wiedergabe an (Begleitgeraet: aus) */
    val playback: Boolean = true,
    /** "Hierher uebernehmen" moeglich (eigenes anderes Geraet im Anruf) */
    val canTakeOver: Boolean = false,
    /** Uebernahme laeuft */
    val takingOver: Boolean = false,
    val endpoints: Int = 2,
    val limit: Int = 0,
    /** Personen ohne Saga 2.4 (Gruppe: werden nicht eingeladen) */
    val old: List<String> = emptyList(),
    /** Name des aktiven Sprechers (Bild-im-Bild) */
    val speaker: String = "",
    /** 2.5: Anruf ueber einen Gjallarhorn-Knoten (Kacheln, Weg, Runensiegel, Teilnehmerliste) */
    val horn: HornUi? = null,
    /** 2.5: 1:1 ueber einen Knoten (kein Zaehler, Titel = Gegenueber) */
    val dm: Boolean = false,
    /** Chat-Name des Gruppenanrufs (leer: unbekannt) */
    val title: String = "",
    /** 2.5: vom Gastgeber stummgeschaltet (Name), bis man das Mikrofon selbst einschaltet */
    val mutedBy: String? = null,
)

/** Reine Regeln fuer Raum, Mesh und Mehrgeraete (Unit-Tests) */
object CallRoom {
    const val CAP_MULTI = "multi"
    /** 2.5: Gruppenanrufe ueber Gjallarhorn-Knoten (Frame-E2E saga-horn/v1 mit FrameCryptor) */
    const val CAP_HORN = "horn"
    const val TOPO_HORN = "horn"
    const val FEATURE_GROUP = "group"
    const val ROLE_MEMBER = "member"
    const val ROLE_COMPANION = "companion"
    const val ROLE_HANDOVER = "handover"
    const val KIND_DM = "dm"
    const val KIND_GROUP = "group"

    /** Mesh-Grenzen nach Konzept §5.2: 4 Geraete mit Video, 6 nur Ton */
    const val MAX_VIDEO = 4
    const val MAX_AUDIO = 6
    /** Uebernahme: so lange darf das neue Geraet brauchen (`handover_s`) */
    const val HANDOVER_S = 15
    /** Gruppen mit mehr Mitgliedern klingeln nicht (`ring_silent`) */
    const val RING_SILENT = 8

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    /**
     * Eigene `caps` inkl. `multi`: nur, wenn der EIGENE Server `reneg` UND `multi` meldet (und der Client den Raum kann).
     * `group` ist nur eine Server-Faehigkeit (Gruppenanruf-Knopf), keine Geraete-Faehigkeit.
     */
    fun myCaps(serverFeatures: Set<String>, screen2: Boolean = true, multi: Boolean = true, horn: Boolean = true): List<String> {
        val m = multi && CallV3.CAP_RENEG in serverFeatures && CAP_MULTI in serverFeatures
        return CallV3.myCaps(serverFeatures, screen2) + listOfNotNull(CAP_MULTI.takeIf { m }, CAP_HORN.takeIf { m && horn && CAP_HORN in serverFeatures })
    }

    fun multi(caps: Collection<String>): Boolean = CAP_MULTI in caps

    /** Gruppenanrufe: Server kann `group` und dieses Geraet `multi` */
    fun groupOk(serverFeatures: Set<String>): Boolean = FEATURE_GROUP in serverFeatures && CAP_MULTI in myCaps(serverFeatures)

    /**
     * Grenze je Anruf: Server-Wert (`limits.endpoints_*` bzw. `limit` des Anrufs), hoechstens die Mesh-Grenze - der Admin kann
     * nur senken. Fehlt der Wert: Mesh-Grenze. Nie unter 2 (1:1 geht immer).
     */
    fun limit(media: String, server: Int?): Int {
        val max = if (media == "video") MAX_VIDEO else MAX_AUDIO
        return (server?.takeIf { it > 0 } ?: max).coerceIn(2, max)
    }

    /**
     * 2.5 P10: Hinweis `horn_mesh` (Teilnehmerliste, Antwort auf join): Gruppenanruf ueber Servergrenzen bleibt im Mesh,
     * weil ein Server keinen Knoten nutzen kann. Text vom Server (Sprache der Anfrage); leer = kein Hinweis.
     */
    fun meshNote(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }?.take(300)

    /** Kann noch ein Geraet dazukommen? (eigene Zusatzgeraete zaehlen mit) */
    fun hasRoom(endpoints: Int, limit: Int): Boolean = endpoints < limit

    /** Kennung der PeerConnection je Geraetepaar (sortiert) */
    fun pairId(a: String, b: String): String = CallV3.pcId(a, b)

    /** Verbindungen eines Geraets im Mesh: je anderem Endpunkt eine (der Hinzukommende bietet an) */
    fun myPairs(me: String, devices: Collection<String>): List<String> = devices.filter { it != me }.distinct().map { pairId(me, it) }

    /** Alle Verbindungen im Mesh: n·(n−1)/2 */
    fun allPairs(devices: Collection<String>): Set<String> {
        val d = devices.distinct().sorted()
        val out = LinkedHashSet<String>()
        for (i in d.indices) for (j in i + 1 until d.size) out.add(pairId(d[i], d[j]))
        return out
    }

    /** Endpunkte fuer die Bitrate: eigene Verbindungen + ich (mindestens 2) */
    fun endpoints(links: Int): Int = maxOf(2, links + 1)

    /** Kamera-Grenzen im Mesh (ab drei Endpunkten 600/500/350 kbit/s, ≤ 540p bzw. 360p15; `want: small` 200 kbit/s) */
    fun meshLimits(path: CallV3.Path, want: String, endpoints: Int, srcHeight: Int = 720): CallV3.VideoLimits =
        CallV3.videoLimits(path, want, screen = false, peers = endpoints, srcHeight = srcHeight)

    /** Medien zu Beginn: Begleitgeraet und Uebernahme ohne Mikrofon, ohne Ton und ohne Kamera (Echo-Schutz) */
    data class Start(val mic: Boolean, val playback: Boolean, val cam: Boolean, val askMic: Boolean)

    fun start(role: String, video: Boolean): Start = when (role) {
        ROLE_COMPANION -> Start(mic = false, playback = false, cam = false, askMic = false)
        ROLE_HANDOVER -> Start(mic = false, playback = false, cam = video, askMic = true)
        else -> Start(mic = true, playback = true, cam = video, askMic = true)
    }

    /** Eigenes Hauptgeraet im Anruf (fuer `replaces`): eigenes anderes Geraet mit Rolle != companion, sonst irgendein eigenes */
    fun mainDevice(legs: List<CallLeg>, me: String, myDevice: String): String? {
        val mine = legs.filter { it.who == me && it.device != myDevice }
        return (mine.firstOrNull { !it.companion } ?: mine.firstOrNull())?.device
    }

    /** Uebernahme fertig, sobald alle Verbindungen zu ANDEREN Personen verbunden sind (mindestens eine) */
    fun handoverReady(others: List<Boolean>): Boolean = others.isNotEmpty() && others.all { it }

    /** Zustand der Uebernahme ("Hierher uebernehmen") */
    enum class HoStep { WAIT, READY, FAIL }

    fun handoverStep(others: List<Boolean>, startedMs: Long, nowMs: Long, limitS: Int = HANDOVER_S): HoStep = when {
        handoverReady(others) -> HoStep.READY
        nowMs - startedMs >= limitS * 1000L -> HoStep.FAIL
        else -> HoStep.WAIT
    }

    /**
     * Auflegen: im 1:1 am Hauptgeraet endet der Anruf fuer alle (POST /end), als Begleitgeraet bzw. in Gruppen verlaesst nur
     * dieses Geraet den Anruf (POST /end mit device - der Server macht daraus ein Verlassen).
     */
    fun hangupEndsAll(kind: String, companion: Boolean): Boolean = kind != KIND_GROUP && !companion

    /** "Stille" Verbindung (eigenes Geraet bzw. Begleitgeraet ohne Ton, Kamera und Bildschirm): keine eigene Kachel */
    fun quiet(own: Boolean, companion: Boolean, gotState: Boolean, mic: Boolean, cam: Boolean, screen: Boolean): Boolean {
        if (cam || screen) return false
        return own || (companion && (!gotState || !mic))
    }

    /** Kachelansicht: Gruppenanruf oder mehr als eine sichtbare Verbindung (1:1 mit stillem Begleitgeraet bleibt Einzelansicht) */
    fun meshView(kind: String, shown: Int): Boolean = kind == KIND_GROUP || shown >= 2

    /**
     * Aktiver Sprecher aus den Pegeln der empfangenen Tonspuren (0..1): lautester ueber der Schwelle; der bisherige bleibt,
     * solange ein anderer nicht deutlich lauter ist (kein Flackern).
     */
    fun speaker(levels: Map<String, Double>, current: String?, threshold: Double = 0.02, margin: Double = 1.5): String? {
        val top = levels.filter { it.value >= threshold }.maxByOrNull { it.value } ?: return current?.takeIf { it in levels }
        val cur = current?.let { levels[it] }
        if (cur != null && current != top.key && top.value < cur * margin) return current
        return top.key
    }

    /** Hoert ein eigenes Geraet auf zu klingeln? Ja, sobald die Teilnehmerliste ein EIGENES Geraet zeigt */
    fun stopRinging(legs: List<CallLeg>, me: String): Boolean = legs.any { it.who == me }

    /** Statuszeile im Raum, solange keine Verbindung steht (TT 65) */
    enum class Wait { NONE, RINGING, WAITING, CONNECTING, PROBLEM }

    /**
     * connecting: Phase CONNECTING; links: bestehende Verbindungen; others: andere Geraete laut Teilnehmerliste;
     * problem: zu einem davon kam aus einem benannten Grund keine Verbindung zustande (z. B. Sicherheitsnummer).
     * Sind andere schon da, heisst es nicht mehr "Klingelt bei den anderen", sondern "Verbinde …" bzw. der Grund.
     */
    fun waitState(connecting: Boolean, ready: Boolean, group: Boolean, starter: Boolean, links: Int, others: Int, problem: Boolean): Wait = when {
        !connecting || !ready || links > 0 -> Wait.NONE
        others > 0 && problem -> Wait.PROBLEM
        others > 0 -> Wait.CONNECTING
        group && starter -> Wait.RINGING
        else -> Wait.WAITING
    }

    /** Hinweis, warum (noch) kein Beitritt geht */
    fun blockText(block: String?, limit: Int): UiText = when (block) {
        "old" -> UiText.Res(R.string.room_block_old)
        "full" -> UiText.Res(R.string.room_block_full, limit)
        "ended" -> UiText.Res(R.string.call_already_ended)
        "none" -> UiText.Res(R.string.room_block_none)
        "node" -> UiText.Res(R.string.horn_block_node)
        "removed" -> UiText.Res(R.string.horn_block_removed)
        else -> UiText.Res(R.string.room_block_other)
    }

    /**
     * Leiste fuer dieses Geraet aus GET /api/calls/active: eigenes anderes Geraet im Anruf -> "Anruf laeuft auf <Geraet>";
     * Gruppenanruf ohne eigenes Geraet -> "Gruppenanruf laeuft · n Geraete"; dieses Geraet schon dabei -> keine Leiste.
     */
    fun runBar(calls: List<CallInfo>, me: String, myDevice: String, busy: String?, ended: Set<String> = emptySet()): RunBar? {
        for (c in calls) {
            // TT 64: als beendet bekannt (Live-Ereignis, Beitritt "schon beendet") -> keine Leiste, auch wenn die Liste
            // des Servers noch nachhinkt
            if (c.state == "ended" || c.id == busy || c.legs.isEmpty() || c.id in ended) continue
            if (c.legs.any { it.device == myDevice }) continue
            val mine = c.legs.filter { it.who == me }
            val on = (mine.firstOrNull { !it.companion } ?: mine.firstOrNull())?.devName?.ifEmpty { "?" }.orEmpty()
            if (on.isEmpty() && c.kind != KIND_GROUP) continue
            if (on.isEmpty() && c.state != "active" && c.state != "ringing") continue
            return RunBar(c.id, c.chatId, c.kind, c.media, on, c.legs.size, c.joinable, c.joinBlock, limit(c.media, c.limit))
        }
        return null
    }

    // ------------------------------------------------------------ Teilnehmerliste (call.roster) mit Versionsstempel

    /** Unterschied zwischen zwei Teilnehmerlisten (fuer "ist hinzugekommen" / "hat verlassen") */
    data class Diff(val joined: List<CallLeg>, val left: List<CallLeg>)

    /**
     * Teilnehmerliste mit Stand `rv`: eine ältere Liste (kleineres rv) ueberschreibt keine neuere. Ohne rv (lokale Server
     * melden keinen) zaehlt die Reihenfolge des Eintreffens.
     */
    class Roster {
        var legs: List<CallLeg> = emptyList()
            private set
        var rv: Double = Double.NEGATIVE_INFINITY
            private set
        var known = false
            private set

        /** Neue Liste uebernehmen; null = veraltet (verworfen), sonst der Unterschied */
        fun apply(next: List<CallLeg>, rv: Double? = null): Diff? {
            if (rv != null && rv < this.rv) return null
            if (rv != null) this.rv = rv
            val old = legs.associateBy { it.device }
            val now = next.associateBy { it.device }
            val diff = Diff(next.filter { it.device !in old }, legs.filter { it.device !in now })
            val first = !known
            legs = next
            known = true
            return if (first) Diff(emptyList(), emptyList()) else diff
        }

        fun of(device: String): CallLeg? = legs.firstOrNull { it.device == device }
        fun mine(me: String): List<CallLeg> = legs.filter { it.who == me }
        val devices: List<String> get() = legs.map { it.device }
    }

    // ------------------------------------------------------------ JSON

    fun legsOf(el: JsonElement?): List<CallLeg> = (el as? JsonArray)?.mapNotNull {
        try {
            json.decodeFromJsonElement(CallLeg.serializer(), it).takeIf { l -> l.device.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }.orEmpty()

    /** Versionsstempel einer Teilnehmerliste (`rv`), falls vorhanden */
    fun rvOf(o: JsonObject?): Double? = (o?.get("rv") as? JsonPrimitive)?.doubleOrNull

    fun str(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonPrimitive)?.contentOrNull

    /**
     * 2.4 (Testrunde W9): Geraet fehlt in den (zwischengespeicherten) Schluesseln der Person - z. B. waehrend des Anrufs
     * gekoppelt (Hinzufuegen/Hierher uebernehmen): die signierte Liste einmal frisch holen, erst danach ablehnen.
     */
    fun needsFreshKeys(device: String, signed: Set<String>): Boolean = device.isNotEmpty() && device !in signed
}
