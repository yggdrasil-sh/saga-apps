package io.github.yggdrasil_sh.saga.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder

/**
 * Saga 2.5 "Seite gestalten" (SPEC-gestalten): Spaltengewichte (G1), Breite/Ausrichtung von Bild und Player (G2),
 * Block-Farbe tunes.hkstyle (G3), Schrift/kleiner Text je Seite (G9), Toggle-Ueberschriften (G10), Rueckfall-Karte (R).
 * Reine Logik ohne Compose. Alles ist optional: fehlt ein Feld oder ist es ungueltig, gilt das bisherige Verhalten.
 * Werte werden beim Lesen begrenzt (fremde/alte Server).
 */
object Gestalten {
    /** Palette wie COLORS im Web (static/pages.js) - nie freie Farbwerte */
    val PALETTE = listOf("gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")

    /** Typen, die eine Block-Farbe haben duerfen (G3) */
    val STYLE_TYPES = setOf("paragraph", "header", "list", "quote", "toggle", "callout", "checklist")

    /** Ab dieser Inhaltsbreite (dp) gelten Spaltengewichte; darunter (Telefon) untereinander */
    const val WIDE_DP = 600

    /** G9: kleiner Text = 0,875 x Grundgroesse */
    const val SMALL_SCALE = 0.875f

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    // ---------------------------------------------------------------- G1 Spalten

    /**
     * Relative Breiten der Spalten (Summe 1) - nur, wenn JEDE Spalte ein gueltiges w > 0 hat, sonst null (= gleich breit).
     * Wie der Server: je Spalte mindestens 0,15 (danach neu normiert).
     */
    fun columnWeights(block: JsonObject): List<Float>? {
        val cols = BlockDoc.data(block)["cols"] as? JsonArray ?: return null
        if (cols.isEmpty()) return null
        val w = cols.map { c -> num((c as? JsonObject)?.get("w"))?.takeIf { it > 0 } ?: return null }
        val sum = w.sum()
        if (sum <= 0) return null
        val clamped = w.map { (it / sum).coerceAtLeast(0.15) }
        val s2 = clamped.sum()
        return clamped.map { (it / s2).toFloat() }
    }

    // ---------------------------------------------------------------- G2 Bild / Player

    /** Embed mit Player (heute: YouTube) - Link-Karten ohne Player bleiben unveraendert */
    fun isPlayerEmbed(d: JsonObject): Boolean = BlockDoc.str(d, "kind") == "youtube" || BlockOps.youtubeId(InlineHtml.decodeEntities(BlockDoc.str(d, "url"))) != null

    /** data.width (10-100, % der Inhaltsbreite) als Anteil; null = nicht gesetzt/ungueltig */
    fun widthPercent(d: JsonObject): Int? {
        val v = num(d["width"]) ?: return null
        return Math.round(v.coerceIn(10.0, 100.0)).toInt()            // erst begrenzen: toInt() schneidet riesige Werte ab
    }

    /**
     * Anteil der Inhaltsbreite fuer Bild/Player: stretched gewinnt (1), sonst width; auf Telefonen mindestens 50 %.
     * null = kein width-Feld (bisheriges Verhalten).
     */
    fun mediaFraction(d: JsonObject, phone: Boolean): Float? {
        if (BlockDoc.bool(d, "stretched")) return 1f
        val p = widthPercent(d) ?: return null
        val f = p / 100f
        return if (phone) f.coerceAtLeast(0.5f) else f
    }

    /** "left" | "center" | "right" (Standard und bei fremden Werten: center) */
    fun align(d: JsonObject): String = when (BlockDoc.str(d, "align")) {
        "left" -> "left"
        "right" -> "right"
        else -> "center"
    }

    // ---------------------------------------------------------------- G3 Block-Farbe

    data class Style(val color: String, val bg: Boolean)

    /** tunes.hkstyle eines Blocks (nur Palette, nur erlaubte Typen) */
    fun style(block: JsonObject): Style? {
        if (BlockDoc.type(block) !in STYLE_TYPES) return null
        val hs = (block["tunes"] as? JsonObject)?.get("hkstyle") as? JsonObject ?: return null
        val c = BlockDoc.str(hs, "c").takeIf { it in PALETTE } ?: return null
        val bg = (hs["bg"] as? JsonPrimitive)?.booleanOrNull == true
        return Style(c, bg)
    }

    fun canStyle(block: JsonObject?): Boolean = block != null && BlockDoc.type(block) in STYLE_TYPES

    /**
     * Farbe setzen (color null = Standard: hkstyle entfernen). Andere Schluessel in tunes bleiben (Regel 0);
     * wird tunes dadurch leer, faellt es weg.
     */
    fun setStyle(block: JsonObject, color: String?, bg: Boolean): JsonObject {
        val tunes = LinkedHashMap(block["tunes"] as? JsonObject ?: JsonObject(emptyMap()))
        if (color == null || color !in PALETTE) tunes.remove("hkstyle")
        else tunes["hkstyle"] = JsonObject(linkedMapOf("c" to JsonPrimitive(color), "bg" to JsonPrimitive(bg)))
        val m = LinkedHashMap(block)
        if (tunes.isEmpty()) m.remove("tunes") else m["tunes"] = JsonObject(tunes)
        return JsonObject(m)
    }

    /**
     * Farbwerte (ARGB) je Palette-Farbe wie .opt-<farbe> im Web. Text: hell abgedunkelt, dunkel aufgehellt (lesbar,
     * WCAG AA auf der Seitenflaeche); Hintergrund wie die Auswahl-Chips der App (optBg).
     */
    private val BASE = mapOf(
        "gray" to 0x9B9A97, "brown" to 0x9F6B53, "orange" to 0xD9730D, "yellow" to 0xDFAB01, "green" to 0x0F7B6C,
        "blue" to 0x0B6E99, "purple" to 0x6940A5, "pink" to 0xAD1A72, "red" to 0xE03E3E,
    )

    fun baseRgb(name: String): Int = BASE[name] ?: BASE.getValue("gray")

    private fun mix(rgb: Int, to: Int, t: Float): Int {
        fun ch(v: Int, s: Int) = (v shr s) and 0xFF
        fun m(s: Int) = Math.round(ch(rgb, s) + (ch(to, s) - ch(rgb, s)) * t)
        return (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** Textfarbe (ARGB, deckend) */
    fun textArgb(name: String, dark: Boolean): Long {
        val base = baseRgb(name)
        val rgb = if (dark) mix(base, 0xFFFFFF, 0.40f) else mix(base, 0x000000, 0.40f)
        return 0xFF000000L or rgb.toLong()
    }

    /** Hintergrund (ARGB mit Deckkraft) - hell 35 %, dunkel aufgehellt und 55 % (wie optBg) */
    fun bgArgb(name: String, dark: Boolean): Long {
        val base = baseRgb(name)
        return if (dark) (0x8CL shl 24) or mix(base, 0xFFFFFF, 0.18f).toLong()
        else (0x59L shl 24) or base.toLong()
    }

    // ---------------------------------------------------------------- G9 Schrift / kleiner Text

    /** note.font: "serif" | "mono", sonst null (Standard) */
    fun pageFont(p: JsonObject): String? = when ((p["font"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "serif" -> "serif"
        "mono" -> "mono"
        else -> null
    }

    fun pageSmall(p: JsonObject): Boolean {
        val v = p["small"] as? JsonPrimitive ?: return false
        return v.booleanOrNull ?: ((num(v) ?: 0.0) != 0.0)
    }

    /** Server kennt die Felder (GET liefert font/small) - nur dann das Seitenmenue anbieten */
    fun pageStyleSupported(p: JsonObject): Boolean = "font" in p || "small" in p

    /** PATCH-Felder: font null -> "default" (Server setzt NULL) */
    fun patchFont(font: String?): JsonObject = JsonObject(mapOf("font" to JsonPrimitive(if (font == "serif" || font == "mono") font else "default")))
    fun patchSmall(on: Boolean): JsonObject = JsonObject(mapOf("small" to JsonPrimitive(on)))

    // ---------------------------------------------------------------- G10 Toggle-Ueberschrift

    /** toggle.data.level: 0 = normaler Toggle, 1-3 = Ueberschrift */
    fun toggleLevel(d: JsonObject): Int = Math.round((num(d["level"]) ?: 0.0).coerceIn(0.0, 3.0)).toInt()

    // ---------------------------------------------------------------- R Rueckfall-Karte

    /** Seite (mit Block-Anker, wenn eine ID da ist) im Web-Editor: <notes>#<id>[:<block>] wie pageLink im Web */
    fun webUrl(notesUrl: String, pageId: Long, blockId: String?): String {
        val anchor = blockId?.takeIf { it.isNotBlank() }?.let { ":" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""
        return "$notesUrl#$pageId$anchor"
    }
}
