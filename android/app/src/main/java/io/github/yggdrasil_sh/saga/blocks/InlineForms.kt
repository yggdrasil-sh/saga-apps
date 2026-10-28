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

    /** Enthaelt das HTML unteilbare Formen (Erwaehnung, Inline-Mathe)? (E1: Absatz nur im Web; seit E2 bearbeitbar) */
    fun hasAtomic(html: String): Boolean = html.contains("hk-mention") || html.contains("hk-math")

    // ---------------------------------------------------------------- 2.5 E2: Inline-Formen setzen (P6-8)

    private fun isTextColor(sp: Span) = form(sp) is Form.TextColor
    private fun isMark(sp: Span) = sp.tag == "mark"

    /**
     * Reihenfolge fest (Inline-Vertrag): Textfarbe `span` aussen, `mark` innen. In einem Abschnitt, in dem beide gelten,
     * wird die Textfarbe vor das erste mark gezogen; sonst bleibt die Reihenfolge (bestehendes HTML byte-gleich).
     */
    fun colorOuter(want: List<Span>): List<Span> {
        val mi = want.indexOfFirst(::isMark)
        if (mi < 0) return want
        val late = want.withIndex().filter { (i, sp) -> i > mi && isTextColor(sp) }.map { it.value }
        if (late.isEmpty()) return want
        val rest = want.filter { it !in late }
        val at = rest.indexOfFirst(::isMark)
        return rest.subList(0, at) + late + rest.subList(at, rest.size)
    }

    /** Aktuelle Text- bzw. Hintergrundfarbe der Auswahl (nur wenn ueberall gleich) */
    fun colorAt(rt: RichText, start: Int, end: Int, bg: Boolean): String? {
        if (start >= end) return null
        val cs = (start until end).map { i ->
            rt.spans.filter { it.start <= i && it.end > i }.firstNotNullOfOrNull { sp ->
                when (val f = form(sp)) {
                    is Form.TextColor -> if (!bg) f.color else null
                    is Form.BgColor -> if (bg) f.color else null
                    else -> null
                }
            }
        }.toSet()
        return cs.singleOrNull()
    }

    /**
     * Text- (bg false) bzw. Hintergrundfarbe (bg true) fuer [start, end) setzen; color null = entfernen. Bisherige
     * Farbe derselben Art wird im Bereich herausgeschnitten (genau eine Klasse je Element). Erwaehnung/Mathe bleiben ganz:
     * der Bereich wird auf ganze Einheiten erweitert.
     */
    fun setColor(rt: RichText, start: Int, end: Int, color: String?, bg: Boolean): RichText {
        if (start >= end) return rt
        val (s0, e0) = widenAtomic(rt, start, end)
        val match: (Span) -> Boolean = if (bg) { sp -> form(sp) is Form.BgColor } else { sp -> form(sp) is Form.TextColor }
        val kept = cutSpans(rt.spans, s0, e0, match)
        if (color == null || color !in Gestalten.PALETTE) return RichText(rt.text, sortSpans(kept))
        val order = (rt.spans.maxOfOrNull { it.order } ?: -1) + 1
        val sp = if (bg) Span(s0, e0, "mark", "<mark class=\"hk-bg-$color\">", order)
        else Span(s0, e0, "span", "<span class=\"hk-c-$color\">", order)
        return RichText(rt.text, sortSpans(kept + sp))
    }

    /** Bereich auf ganze Erwaehnungen/Inline-Mathe erweitern */
    fun widenAtomic(rt: RichText, start: Int, end: Int): Pair<Int, Int> {
        var s0 = start
        var e0 = end
        for (a in rt.spans.filter(::isAtomic)) {
            if (a.start < e0 && a.end > s0) {
                s0 = minOf(s0, a.start)
                e0 = maxOf(e0, a.end)
            }
        }
        return s0 to e0
    }

    private fun cutSpans(spans: List<Span>, start: Int, end: Int, match: (Span) -> Boolean): List<Span> {
        val out = ArrayList<Span>()
        for (sp in spans) {
            if (!match(sp) || sp.end <= start || sp.start >= end) { out.add(sp); continue }
            if (sp.start < start) out.add(sp.copy(end = start))
            if (sp.end > end) out.add(sp.copy(start = end))
        }
        return out
    }

    private fun sortSpans(l: List<Span>) = l.sortedWith(compareBy<Span>({ it.start }, { -it.end }, { it.order }))

    /** Ergebnis einer Eingabe mit unteilbaren Formen: neuer Text + Cursor (Klartext-Position) */
    data class Edit(val rich: RichText, val cursor: Int?)

    /**
     * Eingabe uebernehmen, Erwaehnung/Inline-Mathe bleiben unteilbar (wie im Web):
     * - Loeschen, das eine Einheit anschneidet, loescht sie ganz (Ruecktaste auf dem Chip)
     * - Tippen mitten in einer Einheit landet direkt dahinter
     * - wird eine Einheit ganz ersetzt, faellt sie weg (nie fremder Text als Erwaehnung)
     * cursor != null: die Eingabe wurde verschoben - Schreibmarke dorthin setzen.
     */
    fun applyEditAtomic(old: RichText, newText: String): Edit {
        val atoms = old.spans.filter(::isAtomic)
        if (atoms.isEmpty()) return Edit(InlineHtml.applyEdit(old, newText), null)
        val o = old.text
        var p = 0
        val maxP = minOf(o.length, newText.length)
        while (p < maxP && o[p] == newText[p]) p++
        var s = 0
        while (s < o.length - p && s < newText.length - p && o[o.length - 1 - s] == newText[newText.length - 1 - s]) s++
        var delStart = p
        var delEnd = o.length - s
        val ins = newText.substring(p, newText.length - s)
        var cursor: Int? = null
        var insAt = p
        if (delStart == delEnd) {
            // reine Einfuegung mitten in einer Einheit -> dahinter
            atoms.firstOrNull { it.start < p && p < it.end }?.let { a ->
                insAt = a.end
                delStart = a.end; delEnd = a.end
                cursor = a.end + ins.length
            }
        } else {
            var changed = true
            while (changed) {
                changed = false
                for (a in atoms) {
                    if (a.start < delEnd && a.end > delStart && (a.start < delStart || a.end > delEnd)) {
                        delStart = minOf(delStart, a.start)
                        delEnd = maxOf(delEnd, a.end)
                        changed = true
                    }
                }
            }
            insAt = delStart
            if (delStart != p || delEnd != o.length - s) cursor = delStart + ins.length
        }
        val text = o.substring(0, delStart) + ins + o.substring(delEnd)
        // ganz geloeschte bzw. ersetzte Einheiten fallen weg; dann die uebrigen Bereiche mitfuehren
        val gone = atoms.filter { it.start >= delStart && it.end <= delEnd && delEnd > delStart }.toSet()
        val base = RichText(o, old.spans.filter { it !in gone })
        val r = InlineHtml.applyEdit(base, text)
        // Einheiten duerfen nie durch Weiterschreiben am Rand wachsen
        val fixed = r.spans.map { sp ->
            if (isAtomic(sp)) {
                val orig = old.spans.firstOrNull { it.order == sp.order && it.open == sp.open }
                if (orig != null && sp.end - sp.start != orig.end - orig.start) sp.copy(end = sp.start + (orig.end - orig.start)) else sp
            } else sp
        }.filter { it.end > it.start && it.end <= text.length }
        return Edit(RichText(text, sortSpans(fixed)), if (insAt != p) cursor ?: (insAt + ins.length) else cursor)
    }

    /** Erwaehnung an der Stelle at einfuegen (Text = Name, data-u = Kennung), danach ein Leerzeichen -> (Text, Cursor) */
    fun insertMention(rt: RichText, at: Int, ref: String, name: String): Pair<RichText, Int> {
        val a = widenAtomic(rt, at, at).let { (s0, e0) -> if (s0 < at && at < e0) e0 else at }.coerceIn(0, rt.text.length)
        val label = name.ifBlank { ref }
        val ins = label + " "
        val text = rt.text.substring(0, a) + ins + rt.text.substring(a)
        val n = ins.length
        // Bereiche, die die Stelle umschliessen, wachsen; an der Stelle endende NICHT (die Erwaehnung erbt keine Formatierung)
        val moved = rt.spans.map { sp ->
            when {
                sp.end <= a -> sp
                sp.start >= a -> sp.copy(start = sp.start + n, end = sp.end + n)
                else -> sp.copy(end = sp.end + n)
            }
        }
        val order = (rt.spans.maxOfOrNull { it.order } ?: -1) + 1
        val m = Span(a, a + label.length, "span", "<span class=\"hk-mention\" data-u=\"${InlineHtml.escapeAttr(ref)}\">", order)
        return RichText(text, sortSpans(moved + m)) to (a + n)
    }

    /** Inline-Mathe: Auswahl zur Formel machen bzw. an der Stelle neue Formel einfuegen */
    fun insertMath(rt: RichText, start: Int, end: Int, tex: String): Pair<RichText, Int> {
        val t = tex.replace("\n", " ").trim()
        if (t.isEmpty()) return rt to end
        val s0 = start.coerceIn(0, rt.text.length)
        val e0 = end.coerceIn(s0, rt.text.length)
        val (ws, we) = widenAtomic(rt, s0, e0)
        val base = if (we > ws) RichText(rt.text, rt.spans.filter { !(isAtomic(it) && it.start >= ws && it.end <= we) }) else rt
        val text = rt.text.substring(0, ws) + t + rt.text.substring(we)
        val r = InlineHtml.applyEdit(base, text)
        // Formel enthaelt nur Text: andere Bereiche nicht hinein verschachteln (an den Raendern schneiden)
        val inner = cutSpans(r.spans, ws, ws + t.length) { sp -> !(sp.start <= ws && sp.end >= ws + t.length) }
        val order = (rt.spans.maxOfOrNull { it.order } ?: -1) + 1
        return RichText(text, sortSpans(inner + Span(ws, ws + t.length, "code", "<code class=\"hk-math\">", order))) to (ws + t.length)
    }

    /** Inline-Mathe an der Stelle (Cursor innerhalb oder direkt dahinter) -> (Bereich, TeX) */
    fun mathAt(rt: RichText, pos: Int): Pair<IntRange, String>? =
        rt.spans.firstOrNull { form(it) == Form.Math && it.start <= pos && pos <= it.end }?.let { (it.start until it.end) to rt.text.substring(it.start, it.end) }

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

    /**
     * Faehigkeiten fuer X-Saga-Caps beim Speichern: Inline-Formen bleiben erhalten (inline2), bis 6 Spalten (cols6),
     * blockweises Zusammenfuehren (merge1, 2.5 E2)
     */
    const val CAPS = "inline2,cols6,merge1,sync1"
}
