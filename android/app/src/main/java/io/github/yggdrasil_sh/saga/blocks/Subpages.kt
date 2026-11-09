package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.floor

/**
 * Saga 2.5 Widget-Art `subpages` ("Unterseiten", Faehigkeit subpages1; APP-API "Unterseiten als Liste oder Galerie"):
 * die direkten Unterseiten der Seite als Liste oder Kartenraster. Reine Logik ohne Compose - cfg und `data` lesen,
 * Spaltenzahl, Bildadressen. Die Werte rechnet immer der Server (Rechte der betrachtenden Person); der Block selbst
 * wird nie veraendert (Regel 0).
 */
object Subpages {
    const val KIND = "subpages"

    /** Hoechstens so viele Karten/Zeilen auf einmal (der Server liefert bis 200) - mehr auf Wunsch ("Mehr anzeigen") */
    const val PAGE = 48

    /** Abstand der Karten (dp) */
    const val GAP = 12f

    data class Cfg(val gallery: Boolean, /** cover | content | none */ val image: String)

    private fun str(o: JsonObject?, k: String): String = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    private fun int(e: JsonElement?): Int? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }?.toInt()

    /** Unbekannte Ansicht -> Liste, unbekannte Bildquelle -> cover (wie der Server beim Speichern) */
    fun cfg(cfg: JsonObject?): Cfg =
        Cfg(str(cfg, "view") == "gallery", str(cfg, "image").takeIf { it == "content" || it == "none" } ?: "cover")

    data class Item(val id: Long, /** leer = "Unbenannt" zeigen */ val title: String, val icon: String, val img: String?, val database: Boolean)

    data class Data(val items: List<Item>, val total: Int, val more: Int, val canAdd: Boolean)

    /** Nur Adressen des eigenen Servers (Datei, Titelbild-Galerie, Datei einer foederierten Seite) - nie fremde Hosts */
    fun safeImg(s: String?): String? {
        val u = s?.trim().orEmpty()
        // keine Pfad-Tricks: "..", "//", Parameter/Anker, Backslash, Prozent-Kodierung (%2e%2e), Steuer-/Leerzeichen
        if (u.isEmpty() || u.length > 300 || ".." in u || "//" in u || u.any { it in "?#\\%" || it.code <= 0x20 || it.code == 0x7f }) return null
        return u.takeIf { it.startsWith("/files/") || it.startsWith("/covers/") || it.startsWith("/api/fed/files/") }
    }

    /** Symbol: Bildpfad nur wie [safeImg] (Titelbild-Galerie nicht), sonst Emoji/Text (kurz); Unbrauchbares -> Standardsymbol */
    private fun icon(s: String): String {
        val t = s.trim()
        if (t.startsWith("/")) return safeImg(t)?.takeIf { !it.startsWith("/covers/") } ?: ""
        return if (t.length > 16) "" else t
    }

    /** `W.data`; null = keine Daten (aelterer Stand, Fehler). Eintraege ohne gueltige ID fallen weg, doppelte IDs auch */
    fun data(d: JsonObject?): Data? {
        d ?: return null
        val arr = d["items"] as? JsonArray ?: return null
        val seen = HashSet<Long>()
        val items = arr.mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val p = x["id"] as? JsonPrimitive ?: return@mapNotNull null
            val id = (p.longOrNull ?: p.content.toLongOrNull())?.takeIf { it != 0L } ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            Item(
                id, str(x, "title").replace(Regex("\\s+"), " ").trim().take(200), icon(str(x, "icon")),
                safeImg((x["img"] as? JsonPrimitive)?.takeIf { it.isString }?.content), str(x, "kind") == "database",
            )
        }
        val more = (int(d["more"]) ?: 0).coerceAtLeast(0)
        val total = (int(d["total"]) ?: 0).coerceAtLeast(items.size + more)
        return Data(items, total, more, (d["can_add"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true)
    }

    /** Mindestbreite einer Spalte (dp) wie im Web (--sp-w, 1 rem = 16 dp): je Bildquelle und Kartengroesse */
    fun minColumn(size: String, image: String): Float = when (image) {
        "content" -> when (size) { "s" -> 116f; "l" -> 200f; else -> 152f }
        "none" -> when (size) { "s" -> 136f; "l" -> 224f; else -> 176f }
        else -> when (size) { "s" -> 160f; "l" -> 288f; else -> 216f }
    }

    /**
     * Spalten im Raster: schmal (bis 520 dp - Handy hochkant, schmale Spalte) immer zwei wie im Web, darueber so viele,
     * wie mit der Mindestbreite passen (mindestens zwei). Sehr schmal (unter 200 dp, z. B. Spalten-Block) eine.
     * Grosse Schrift (ab 1,5-fach) rechnet mit breiteren Spalten, damit die Titel lesbar bleiben.
     */
    fun columns(widthDp: Float, size: String, image: String, fontScale: Float = 1f): Int {
        if (!(widthDp > 0f)) return 2
        if (widthDp < 200f) return 1
        if (widthDp <= 520f) return 2
        val min = minColumn(size, image) * (if (fontScale >= 1.5f) 1.25f else 1f)
        return floor((widthDp + GAP) / (min + GAP)).toInt().coerceIn(2, 12)
    }

    /** Seitenverhaeltnis der Bildflaeche (Breite : Hoehe): Inhalt 2:3 (Buchcover), Titelbild 16:9; none = keine Flaeche */
    fun aspect(image: String): Float? = when (image) {
        "content" -> 2f / 3f
        "none" -> null
        else -> 16f / 9f
    }

    private val COVER = Regex("""^/covers/[a-z0-9-]+\.jpg$""")

    /** Vorschaubreite wie im Web: Hochformat 400, Titelbild 640 (der Server rechnet `?w=` beim ersten Abruf) */
    fun thumbWidth(image: String): Int = if (image == "content") 400 else 640

    /** Bildadresse in Vorschaubreite; schon vorhandene Parameter bleiben */
    fun sized(img: String, w: Int): String = when {
        '?' in img -> img
        img.startsWith("/files/") || img.startsWith("/api/fed/files/") || COVER.matches(img) -> "$img?w=$w"
        else -> img
    }

    /** Verweis wie die uebrigen Seitenverweise (auch negative IDs foederierter Seiten) */
    fun href(id: Long): String = "/notes#$id"

    /** Zeigt die Karte den Platzhalter (grosses Symbol statt Bild)? Dann entfaellt das kleine Symbol neben dem Titel */
    fun placeholder(cfg: Cfg, item: Item): Boolean = cfg.gallery && cfg.image != "none" && item.img == null
}
