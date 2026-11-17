package io.github.yggdrasil_sh.saga.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * 2.5: Titelbild-Galerie (Server GET api/covers, docs/APP-API.md "Titelbild-Galerie"). Rein Kotlin (ohne Android),
 * per Unit-Test geprueft: Liste lesen und pruefen, nach Gruppen ordnen, Auswahl setzen (cover + empfohlene cover_pos),
 * Zufall wie im Web (nicht das jetzige), "Ueber dieses Bild". Zusaetzliche Felder werden ignoriert.
 */
data class CoverImage(
    val id: String,
    /** /covers/<id>.jpg - genau dieser Wert kommt in note.cover */
    val url: String,
    /** verkleinert (?w=384) */
    val thumb: String,
    val title: String,
    val artist: String,
    val date: String,
    /** Quelle (nur http/https), sonst leer */
    val sourceUrl: String,
    /** Nachweis, z. B. "KI-generiert für Saga" */
    val source: String,
    val group: String,
    /** empfohlene Position 0 (oben) ... 100 (unten) */
    val pos: Double,
    /** mitgeliefert (gibt es auf jedem Saga-Server gleich) */
    val builtin: Boolean,
) {
    /** "Kuenstler, Datum" wie im Web */
    val credit: String get() = listOf(artist, date).filter { it.isNotEmpty() }.joinToString(", ")

    /** TalkBack und Tooltip wie im Web: "Titel – Kuenstler, Datum" */
    val label: String get() = if (credit.isEmpty()) title else "$title – $credit"
}

data class CoverGroup(val id: String, val label: String, val covers: List<CoverImage>)

data class CoverListing(
    val covers: List<CoverImage>,
    /** (id, Name) in der Reihenfolge des Servers */
    val groups: List<Pair<String, String>>,
    /** Nachweis der mitgelieferten Kunstwerke (Hinweis unten) */
    val source: String,
    val license: String,
)

/** "Ueber dieses Bild" */
sealed interface CoverAbout {
    data class Image(val cover: CoverImage) : CoverAbout

    /** Galerie-Pfad, aber nicht (mehr) in der Auswahl (ausgeblendet/entfernt) */
    data object Gone : CoverAbout

    /** kein Galerie-Bild: Menuepunkt nicht anbieten */
    data object None : CoverAbout
}

object CoverGallery {
    const val LIST_PATH = "api/covers"
    /** Vorschaubreite, wenn der Server keine "thumb"-Adresse schickt (wie das Web) */
    const val THUMB_W = 384

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val PATH = Regex("/covers/([a-z0-9][a-z0-9-]{0,63})\\.jpg")

    /** Galerie-Pfad wie im Server (saga/covers.py PATH_RE) */
    fun idOf(url: String?): String? = url?.let { PATH.matchEntire(it)?.groupValues?.get(1) }

    fun isGalleryPath(url: String?): Boolean = idOf(url) != null

    fun validId(id: String): Boolean = ID.matches(id)

    /** Vorschauadresse des Servers, wenn sie zum Bild gehoert (gleicher Pfad mit Abfrage), sonst ?w=384 */
    fun thumb(raw: String?, url: String): String = if (raw != null && (raw == url || raw.startsWith("$url?"))) raw else "$url?w=$THUMB_W"

    /** true = Galerie da, false = aelterer Server (404) bzw. nicht erlaubt (403), null = spaeter wieder */
    fun offered(code: Int): Boolean? = when (code) {
        in 200..299 -> true
        403, 404 -> false
        else -> null
    }

    private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    fun pos(e: JsonElement?): Double {
        val p = e as? JsonPrimitive ?: return 50.0
        if (p.isString || p.booleanOrNull != null) return 50.0
        val d = p.doubleOrNull ?: return 50.0
        return if (d.isFinite()) d.coerceIn(0.0, 100.0) else 50.0
    }

    private fun web(e: JsonElement?): String {
        val s = str(e)
        return if (Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).matches(s)) s else ""
    }

    /** GET api/covers lesen; null = keine lesbare Antwort. Eintraege ohne gueltigen Pfad fallen weg, doppelte einmal. */
    fun parse(body: String): CoverListing? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val arr = root["covers"] as? JsonArray ?: return null
        val seen = HashSet<String>()
        val out = ArrayList<CoverImage>()
        for (x in arr) {
            val c = x as? JsonObject ?: continue
            val url = str(c["url"])
            val id = idOf(url) ?: continue
            if (!seen.add(id)) continue
            out += CoverImage(
                id = id, url = url, thumb = thumb(str(c["thumb"]).ifEmpty { null }, url), title = str(c["title"]).ifEmpty { id },
                artist = str(c["artist"]), date = str(c["date"]), sourceUrl = web(c["source_url"]), source = str(c["source"]),
                group = str(c["group"]), pos = pos(c["pos"]),
                builtin = (c["builtin"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false,
            )
        }
        val groups = ArrayList<Pair<String, String>>()
        for (x in (root["groups"] as? JsonArray).orEmpty()) {
            val g = x as? JsonObject ?: continue
            val id = str(g["id"])
            if (groups.none { it.first == id }) groups += id to str(g["label"])
        }
        return CoverListing(out, groups, str(root["source"]), str(root["license"]))
    }

    /** Fremde Instanz (remote): nur mitgelieferte Bilder (Uploads des Admins gibt es dort nicht) */
    fun choosable(l: CoverListing, remote: Boolean): List<CoverImage> = if (remote) l.covers.filter { it.builtin } else l.covers

    /**
     * Nach Gruppen in der Reihenfolge des Servers; Gruppen, die der Server nicht nennt, dahinter (Name = ID), Bilder
     * ohne Gruppe zuletzt unter [other] ("Weitere"). Leere Gruppen fallen weg.
     */
    fun grouped(l: CoverListing, remote: Boolean, other: String): List<CoverGroup> {
        val list = choosable(l, remote)
        val order = l.groups.toMutableList()
        for (c in list) if (order.none { it.first == c.group }) order += c.group to c.group
        order.indexOfFirst { it.first.isEmpty() }.takeIf { it >= 0 }?.let { order += order.removeAt(it) }
        return order.mapNotNull { (id, label) ->
            val items = list.filter { it.group == id }
            if (items.isEmpty()) null else CoverGroup(id, label.ifEmpty { id.ifEmpty { other } }, items)
        }
    }

    /** Felder fuer PATCH api/notes/{id}: Pfad und empfohlene Position. null = schon das Titelbild (nicht speichern). */
    fun fields(c: CoverImage, current: String?): JsonObject? =
        if (c.url == current) null else buildJsonObject { put("cover", c.url); put("cover_pos", c.pos) }

    /** "Zufaellig" wie im Web: ein anderes als das jetzige; [roll] waehlt einen Index 0 until n (Tests: fest) */
    fun random(l: CoverListing, remote: Boolean, current: String?, roll: (Int) -> Int = { kotlin.random.Random.nextInt(it) }): CoverImage? {
        val list = choosable(l, remote).filter { it.url != current }
        if (list.isEmpty()) return null
        return list[roll(list.size).coerceIn(0, list.size - 1)]
    }

    fun about(l: CoverListing?, current: String?): CoverAbout {
        if (!isGalleryPath(current)) return CoverAbout.None
        return l?.covers?.firstOrNull { it.url == current }?.let { CoverAbout.Image(it) } ?: CoverAbout.Gone
    }
}

/** Je Server: Galerie angeboten? (404 -> Eintraege ausblenden) und zuletzt geladene Liste (Sprache: Accept-Language) */
class CoverGalleryState {
    /** "server|lang" -> Liste */
    val lists = MutableStateFlow<Map<String, CoverListing>>(emptyMap())
    /** Server ohne Galerie (aelter, 404) */
    val unsupported = MutableStateFlow<Set<String>>(emptySet())

    fun key(server: String, lang: String) = "$server|$lang"

    fun cached(server: String, lang: String): CoverListing? = lists.value[key(server, lang)]

    /** Antwort uebernehmen: code wie [CoverGallery.offered]; unlesbare 2xx-Antwort aendert nichts */
    @Synchronized fun record(server: String, lang: String, code: Int, listing: CoverListing?) {
        when (CoverGallery.offered(code)) {
            true -> if (listing != null) {
                lists.value = lists.value + (key(server, lang) to listing)
                if (server in unsupported.value) unsupported.value = unsupported.value - server
            }
            false -> {
                lists.value = lists.value.filterKeys { !it.startsWith("$server|") }
                unsupported.value = unsupported.value + server
            }
            null -> {}
        }
    }

    @Synchronized fun clear() {
        lists.value = emptyMap()
        unsupported.value = emptySet()
    }
}
