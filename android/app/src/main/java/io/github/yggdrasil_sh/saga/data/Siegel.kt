package io.github.yggdrasil_sh.saga.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 2.5 (Bugs-DB 2156): Saga-Siegel als Seitensymbol (Server docs/APP-API.md "Saga-Siegel als Seitensymbol (2.5)").
 * Rein Kotlin (ohne Android), per Unit-Test geprueft: Pfad erkennen, Bildadresse mit `?w=` (der Server liefert dann PNG),
 * Liste GET api/siegel lesen, Name "Rabe (Weinrot)", vorgewaehlte Farbe, Auswahl nur mit `siegel1`.
 */
object Siegel {
    const val FEATURE = "siegel1"
    const val LIST_PATH = "api/siegel"
    /** Stufen des Servers (naechstgroessere wird ausgeliefert, darueber 256) */
    val SIZES = listOf(64, 128, 256)

    /**
     * Wie der Server (saga/siegel.py PATH_RE), Farbe aber offen gehalten: ein spaeterer Server mit weiterer Farbe wird
     * trotzdem als Bild gezeigt (die Datei liefert der Server; unbekannte Pfade nimmt er beim Setzen gar nicht an).
     */
    private val PATH = Regex("/siegel/([a-z]{2,20})/([a-z]{2,20})\\.svg")
    private val ID = Regex("[a-z]{2,20}")

    data class Ref(val color: String, val motif: String) {
        val path: String get() = path(color, motif)
    }

    fun path(color: String, motif: String) = "/siegel/$color/$motif.svg"

    /** Symbolwert eines Siegels (Leerzeichen am Rand zaehlen nicht, `?`/`#`/andere Endung nicht) -> Farbe/Motiv */
    fun parse(icon: String?): Ref? = icon?.trim()?.let { PATH.matchEntire(it) }?.let { Ref(it.groupValues[1], it.groupValues[2]) }

    fun isSiegel(icon: String?): Boolean = parse(icon) != null

    /** Stufe fuer eine Anzeigegroesse in Pixeln (wie der Server: naechstgroessere, darueber 256) */
    fun stage(px: Int): Int = SIZES.firstOrNull { px <= it } ?: SIZES.last()

    /** PNG-Adresse (relativ zum eigenen Server - die Dateien gibt es auf jedem Saga-Server gleich, auch fuer fremde Seiten) */
    fun imageUrl(icon: String?, px: Int): String? = parse(icon)?.let { "${it.path}?w=${stage(px)}" }

    /**
     * Auswahl anbieten? Eigener Server mit `siegel1`; Seite eines anderen Servers zusaetzlich nur, wenn deren Server
     * `siegel1` meldet (remote.caps) - sonst antwortet der Eigentuemer mit 409.
     */
    fun offered(ownFeatures: Collection<String>, remote: Boolean, remoteCaps: Collection<String>): Boolean =
        FEATURE in ownFeatures && (!remote || FEATURE in remoteCaps)

    data class Item(val id: String, val label: String)

    data class Listing(val colors: List<Item>, val motifs: List<Item>) {
        fun colorLabel(id: String): String = colors.firstOrNull { it.id == id }?.label ?: id
        fun motifLabel(id: String): String = motifs.firstOrNull { it.id == id }?.label ?: id
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    private fun items(e: JsonElement?): List<Item> {
        val seen = HashSet<String>()
        return (e as? JsonArray).orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val id = str(o["id"])
            if (!ID.matches(id) || !seen.add(id)) return@mapNotNull null
            Item(id, str(o["label"]).take(60).ifEmpty { id })
        }
    }

    /** GET api/siegel lesen; null = unlesbar bzw. ohne Farben oder Motive. Ungueltige/doppelte Eintraege fallen weg. */
    fun parseListing(body: String): Listing? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val colors = items(root["colors"])
        val motifs = items(root["motifs"])
        if (colors.isEmpty() || motifs.isEmpty()) return null
        return Listing(colors, motifs)
    }

    /** "Rabe (Weinrot)" wie im Web; ohne Liste null (dann allgemeiner Text "Seitensymbol") */
    fun label(l: Listing?, icon: String?): String? {
        val r = parse(icon) ?: return null
        l ?: return null
        return "${l.motifLabel(r.motif)} (${l.colorLabel(r.color)})"
    }

    /** Vorgewaehlte Farbe: die des jetzigen Siegels, sonst die zuletzt gewaehlte, sonst die erste der Liste */
    fun preselect(l: Listing, current: String?, last: String?): String {
        val ids = l.colors.map { it.id }
        return parse(current)?.color?.takeIf { it in ids } ?: last?.takeIf { it in ids } ?: ids.first()
    }

    /** true = Liste da, false = aelterer Server (404) bzw. nicht erlaubt (403), null = spaeter wieder */
    fun offeredCode(code: Int): Boolean? = when (code) {
        in 200..299 -> true
        403, 404 -> false
        else -> null
    }
}

/** Seitensymbol: Emoji/Text, eigenes Bild (/files/, /api/fed/files/) oder Saga-Siegel */
object IconPath {
    fun isFile(icon: String?): Boolean = icon?.trim()?.let { it.startsWith("/files/") || it.startsWith("/api/fed/files/") } == true

    /** Wird als Bild gezeigt (nicht als Text) */
    fun isImage(icon: String?): Boolean = isFile(icon) || Siegel.isSiegel(icon)

    /**
     * Bildadresse zum Laden (relativ): eigenes Bild mit `?w=160` (wie bisher, vorhandene Parameter bleiben), Siegel als PNG
     * in der passenden Stufe fuer [px]; kein Bild -> null.
     */
    fun imageUrl(icon: String?, px: Int, fileW: Int = 160): String? {
        val ic = icon?.trim().orEmpty()
        Siegel.imageUrl(ic, px)?.let { return it }
        if (!isFile(ic)) return null
        return if (ic.contains("?")) ic else "$ic?w=$fileW"
    }

    /** Als Text zeigbares Symbol (Emoji o. ae.); Pfade (Bild, Siegel, sonst etwas mit "/") -> null */
    fun text(icon: String?): String? = icon?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("/") }
}

/** Je Server/Sprache: Siegel-Liste (GET api/siegel) und Server ohne Liste; zuletzt gewaehlte Farbe (wie das Web je Browser) */
class SiegelState {
    val lists = MutableStateFlow<Map<String, Siegel.Listing>>(emptyMap())
    val unsupported = MutableStateFlow<Set<String>>(emptySet())
    @Volatile var lastColor: String? = null

    fun key(server: String, lang: String) = "$server|$lang"

    fun cached(server: String, lang: String): Siegel.Listing? = lists.value[key(server, lang)]

    @Synchronized fun record(server: String, lang: String, code: Int, listing: Siegel.Listing?) {
        when (Siegel.offeredCode(code)) {
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
