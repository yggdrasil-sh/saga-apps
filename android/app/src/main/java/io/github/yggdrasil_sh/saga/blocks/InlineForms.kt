package io.github.yggdrasil_sh.saga.blocks

/**
 * 2.5 Inline-Vertrag P6-2 (APP-API "Inline-HTML"): Textfarbe `span.hk-c-<farbe>`, Hintergrund `mark.hk-bg-<farbe>`,
 * Erwaehnung `span.hk-mention[data-u]`, Inline-Mathe `code.hk-math`. Die App zeigt sie an; beim Bearbeiten bleiben
 * Tags samt Klasse erhalten (InlineHtml fuehrt den Original-Start-Tag mit). Erwaehnung und Inline-Mathe sind unteilbar -
 * Absaetze damit sperrt die App ("Im Web bearbeiten"), statt sie halb zu veraendern.
 */
object InlineForms {
    sealed interface Form {
        data class TextColor(val color: String) : Form
        data class BgColor(val color: String) : Form
        data class Mention(val ref: String?) : Form
        data object Math : Form
    }

    private val CLASS_RE = Regex("class\\s*=\\s*\"([^\"]*)\"")
    private val DATA_U_RE = Regex("data-u\\s*=\\s*\"([^\"]*)\"")

    fun classes(open: String): List<String> = CLASS_RE.find(open)?.groupValues?.get(1)?.split(' ')?.filter { it.isNotBlank() }.orEmpty()

    /** Form eines Spans (Start-Tag roh) - null = keine der neuen Formen */
    fun form(sp: Span): Form? {
        val cls = classes(sp.open)
        return when (sp.tag) {
            "span" -> when {
                "hk-mention" in cls -> Form.Mention(DATA_U_RE.find(sp.open)?.groupValues?.get(1)?.let(InlineHtml::decodeEntities)?.ifEmpty { null })
                else -> cls.firstOrNull { it.startsWith("hk-c-") }?.removePrefix("hk-c-")?.takeIf { it in Gestalten.PALETTE }?.let { Form.TextColor(it) }
            }
            "mark" -> cls.firstOrNull { it.startsWith("hk-bg-") }?.removePrefix("hk-bg-")?.takeIf { it in Gestalten.PALETTE }?.let { Form.BgColor(it) }
            "code" -> if ("hk-math" in cls) Form.Math else null
            else -> null
        }
    }

    /** Enthaelt das HTML unteilbare Formen (Erwaehnung, Inline-Mathe)? Dann Absatz nur im Web bearbeiten */
    fun hasAtomic(html: String): Boolean = html.contains("hk-mention") || html.contains("hk-math")

    fun isAtomic(sp: Span): Boolean = form(sp).let { it is Form.Mention || it == Form.Math }

    /**
     * Nur Anzeige: Text der Erwaehnungen durch den aktuellen Namen ersetzen ("@Anna"), Bereiche verschieben.
     * name(ref) = null -> gespeicherter Rueckfalltext.
     */
    fun withMentionNames(rt: RichText, name: (String) -> String?): RichText {
        val ms = rt.spans.filter { form(it) is Form.Mention }.sortedBy { it.start }
        if (ms.isEmpty()) return rt
        val sb = StringBuilder()
        // Abbildung alter Positionen auf neue: Liste (alteStelle, delta danach)
        val shifts = ArrayList<Triple<Int, Int, Int>>()                  // start, oldEnd, newLen
        var pos = 0
        var used = -1
        for (m in ms) {
            if (m.start < used) continue                                     // ueberlappend: nur die erste
            sb.append(rt.text, pos, m.start)
            val ref = (form(m) as Form.Mention).ref
            val old = rt.text.substring(m.start, m.end)
            val shown = "@" + ((ref?.let(name))?.ifBlank { null } ?: old.removePrefix("@"))
            shifts.add(Triple(m.start, m.end, shown.length))
            sb.append(shown)
            pos = m.end
            used = m.end
        }
        sb.append(rt.text, pos, rt.text.length)
        fun map(i: Int, isEnd: Boolean): Int {
            var delta = 0
            for ((s, e, n) in shifts) {
                if (i >= e) delta += n - (e - s)
                else if (i > s || (i == s && isEnd)) return s + delta + (if (isEnd) n else 0)
                else break
            }
            return i + delta
        }
        return RichText(sb.toString(), rt.spans.map { it.copy(start = map(it.start, false), end = map(it.end, true)) }.filter { it.end > it.start })
    }

    // ---------------------------------------------------------------- Palette wie Web-CSS --tc-*/--bc-*

    private val TC_DARK = mapOf(
        "gray" to 0xB0AFAB, "brown" to 0xC9A08B, "orange" to 0xF0A35E, "yellow" to 0xE6C54F, "green" to 0x4FC4A9,
        "blue" to 0x62B4E2, "purple" to 0xB893E6, "pink" to 0xE682BB, "red" to 0xF28585,
    )
    private val TC_LIGHT = mapOf(
        "gray" to 0x686763, "brown" to 0x8A5A44, "orange" to 0xA65300, "yellow" to 0x7F6200, "green" to 0x0B6E60,
        "blue" to 0x0B6893, "purple" to 0x6940A5, "pink" to 0xA8186E, "red" to 0xC22A2A,
    )

    /** Textfarbe (ARGB) wie --tc-<farbe> */
    fun textArgb(color: String, dark: Boolean): Long = 0xFF000000L or ((if (dark) TC_DARK else TC_LIGHT)[color] ?: (if (dark) TC_DARK else TC_LIGHT).getValue("gray")).toLong()

    /** Hintergrund (ARGB) wie --bc-<farbe>: Grundfarbe zu 30 % (dunkel) bzw. 20 % (hell) */
    fun bgArgb(color: String, dark: Boolean): Long {
        val a = if (dark) 0x4D else 0x33
        return (a.toLong() shl 24) or Gestalten.baseRgb(color).toLong()
    }

    /** Faehigkeiten fuer X-Saga-Caps beim Speichern: Inline-Formen bleiben erhalten (inline2), bis 6 Spalten (cols6) */
    const val CAPS = "inline2,cols6"
}
