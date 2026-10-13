package io.github.yggdrasil_sh.saga.blocks

/**
 * Formatierter Text eines Blocks: reiner Text + Bereiche (Spans) mit dem ORIGINAL-Start-Tag.
 * So bleibt beim Bearbeiten alles erhalten, was der Server erlaubt (b/i/u/s/mark/code/a/time ...),
 * auch Tags, die die App nicht kennt - sie werden unveraendert wieder ausgegeben.
 */
data class Span(val start: Int, val end: Int, val tag: String, val open: String, val order: Int)

data class RichText(val text: String, val spans: List<Span> = emptyList())

/** Gruppen fuer die Formatierungs-Schaltflaechen */
enum class InlineStyle(val tags: Set<String>, val open: String) {
    BOLD(setOf("b", "strong"), "<b>"),
    ITALIC(setOf("i", "em"), "<i>"),
    UNDERLINE(setOf("u"), "<u>"),
    STRIKE(setOf("s", "strike", "del"), "<s>"),
    CODE(setOf("code"), "<code class=\"inline-code\">"),
    MARK(setOf("mark"), "<mark class=\"cdx-marker\">"),
}

object InlineHtml {
    private val TAG_RE = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    private val HREF_RE = Regex("href\\s*=\\s*\"([^\"]*)\"")
    private val ENTITY_RE = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

    /** Tags, die sich beim Weiterschreiben am Ende fortsetzen (wie in Text-Editoren) - Links nicht */
    private val EXTENDING = setOf("b", "strong", "i", "em", "u", "s", "strike", "del", "mark")

    private val spanOrder = compareBy<Span>({ it.start }, { -it.end }, { it.order })

    // ---------------------------------------------------------------- Lesen

    fun parse(html: String): RichText {
        class Open(val tag: String, val raw: String, val start: Int, val order: Int)

        val sb = StringBuilder()
        val spans = ArrayList<Span>()
        val stack = ArrayList<Open>()
        var order = 0
        var pos = 0
        for (m in TAG_RE.findAll(html)) {
            if (m.range.first > pos) sb.append(decodeEntities(html.substring(pos, m.range.first)))
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            if (name == "br") {
                if (!closing) sb.append('\n')
                continue
            }
            if (!closing) {
                if (m.groupValues[3].trimEnd().endsWith("/")) continue       // <x/> ohne Inhalt
                stack.add(Open(name, m.value, sb.length, order++))
            } else {
                val idx = stack.indexOfLast { it.tag == name }
                if (idx < 0) continue
                while (stack.size > idx) {
                    val o = stack.removeAt(stack.size - 1)
                    spans.add(Span(o.start, sb.length, o.tag, o.raw, o.order))
                }
            }
        }
        if (pos < html.length) sb.append(decodeEntities(html.substring(pos)))
        while (stack.isNotEmpty()) {
            val o = stack.removeAt(stack.size - 1)
            spans.add(Span(o.start, sb.length, o.tag, o.raw, o.order))
        }
        return RichText(sb.toString(), spans.filter { it.end > it.start }.sortedWith(spanOrder))
    }

    fun plain(html: String): String = parse(html).text

    // ---------------------------------------------------------------- Schreiben

    /** Bereiche wieder als sauber verschachteltes HTML ausgeben (Ueberlappungen werden aufgeteilt) */
    fun toHtml(rt: RichText): String {
        val text = rt.text
        val spans = rt.spans.filter { it.start < it.end && it.start >= 0 && it.end <= text.length }
        val cuts = sortedSetOf(0, text.length)
        spans.forEach { cuts.add(it.start); cuts.add(it.end) }
        val pts = cuts.toList()
        val out = StringBuilder()
        val stack = ArrayList<Span>()
        for (k in 0 until pts.size - 1) {
            val a = pts[k]
            val b = pts[k + 1]
            val want = spans.filter { it.start <= a && it.end >= b }.sortedWith(spanOrder)
            var common = 0
            while (common < stack.size && common < want.size && stack[common] === want[common]) common++
            for (i in stack.size - 1 downTo common) out.append("</").append(stack[i].tag).append('>')
            while (stack.size > common) stack.removeAt(stack.size - 1)
            for (i in common until want.size) {
                out.append(want[i].open)
                stack.add(want[i])
            }
            out.append(escape(text.substring(a, b)))
        }
        for (i in stack.size - 1 downTo 0) out.append("</").append(stack[i].tag).append('>')
        return out.toString()
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\n' -> sb.append("<br>")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun escapeAttr(s: String): String = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY_RE.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf { it <= 0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value
                else -> when (e) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> "\u00A0"
                    else -> m.value
                }
            }
        }
    }

    // ---------------------------------------------------------------- Bearbeiten

    /**
     * Neuer Text aus dem Eingabefeld -> Bereiche mitfuehren. Es wird der kleinste geaenderte Abschnitt
     * bestimmt (gemeinsamer Anfang/Ende); Einfuegen innerhalb eines Bereichs erweitert ihn, Einfuegen direkt
     * am Ende setzt Formatierung (fett, kursiv ...) fort, Links nicht.
     */
    fun applyEdit(old: RichText, newText: String): RichText {
        val o = old.text
        if (o == newText) return old
        var p = 0
        val maxP = minOf(o.length, newText.length)
        while (p < maxP && o[p] == newText[p]) p++
        var s = 0
        while (s < o.length - p && s < newText.length - p && o[o.length - 1 - s] == newText[newText.length - 1 - s]) s++
        val oldEnd = o.length - s
        val ins = newText.length - s - p
        val delta = newText.length - o.length
        val spans = old.spans.mapNotNull { sp ->
            val ns = when {
                sp.start < p -> sp.start
                sp.start < oldEnd -> p                                   // im ersetzten Bereich
                else -> sp.start + delta                                 // dahinter (auch Einfuegen genau am Anfang)
            }
            val ne = when {
                sp.end < p -> sp.end
                sp.end == p -> if (sp.tag in EXTENDING) p + ins else p
                sp.end <= oldEnd -> p + ins
                else -> sp.end + delta
            }
            if (ne > ns) sp.copy(start = ns, end = ne) else null
        }
        return RichText(newText, spans.sortedWith(spanOrder))
    }

    /** Text an einer Stelle teilen (Return): links bis at, rechts ab at */
    fun split(rt: RichText, at: Int): kotlin.Pair<RichText, RichText> {
        val a = at.coerceIn(0, rt.text.length)
        val left = rt.spans.filter { it.start < a }.map { it.copy(end = minOf(it.end, a)) }.filter { it.end > it.start }
        val right = rt.spans.filter { it.end > a }.map { it.copy(start = maxOf(it.start, a) - a, end = it.end - a) }.filter { it.end > it.start }
        return RichText(rt.text.substring(0, a), left) to RichText(rt.text.substring(a), right)
    }

    /** Ist [start, end) vollstaendig mit dieser Formatierung versehen? (leere Auswahl: Zeichen davor) */
    fun isActive(rt: RichText, start: Int, end: Int, style: InlineStyle): Boolean {
        val group = rt.spans.filter { it.tag in style.tags }
        if (start >= end) {
            val pos = start - 1
            return pos >= 0 && group.any { it.start <= pos && it.end > pos }
        }
        return (start until end).all { i -> group.any { it.start <= i && it.end > i } }
    }

    /** Formatierung fuer [start, end) ein- bzw. ausschalten */
    fun toggle(rt: RichText, start: Int, end: Int, style: InlineStyle): RichText {
        if (start >= end) return rt
        val nextOrder = (rt.spans.maxOfOrNull { it.order } ?: -1) + 1
        return if (isActive(rt, start, end, style)) {
            RichText(rt.text, cut(rt.spans, start, end) { it.tag in style.tags }.sortedWith(spanOrder))
        } else {
            val same = rt.spans.filter { it.tag in style.tags && it.start <= end && it.end >= start }
            val rest = rt.spans - same.toSet()
            val s = minOf(start, same.minOfOrNull { it.start } ?: start)
            val e = maxOf(end, same.maxOfOrNull { it.end } ?: end)
            val first = same.minByOrNull { it.order }
            val span = Span(s, e, first?.tag ?: style.open.substring(1).substringBefore('>').substringBefore(' '),
                first?.open ?: style.open, first?.order ?: nextOrder)
            RichText(rt.text, (rest + span).sortedWith(spanOrder))
        }
    }

    /** Link fuer [start, end) setzen (href leer = Link entfernen) */
    fun setLink(rt: RichText, start: Int, end: Int, href: String): RichText {
        if (start >= end) return rt
        val kept = cut(rt.spans, start, end) { it.tag == "a" }
        if (href.isBlank()) return RichText(rt.text, kept.sortedWith(spanOrder))
        val order = (rt.spans.maxOfOrNull { it.order } ?: -1) + 1
        val span = Span(start, end, "a", "<a href=\"${escapeAttr(href.trim())}\">", order)
        return RichText(rt.text, (kept + span).sortedWith(spanOrder))
    }

    /** Link an einer Textstelle (Cursor) */
    fun linkAt(rt: RichText, pos: Int): String? =
        rt.spans.firstOrNull { it.tag == "a" && it.start <= pos && pos <= it.end && it.end > it.start }?.let { hrefOf(it.open) }

    fun hrefOf(open: String): String? = HREF_RE.find(open)?.groupValues?.get(1)?.let { decodeEntities(it) }

    /** Bereiche der passenden Spans aus [start, end) herausschneiden */
    private fun cut(spans: List<Span>, start: Int, end: Int, match: (Span) -> Boolean): List<Span> {
        val out = ArrayList<Span>()
        for (sp in spans) {
            if (!match(sp) || sp.end <= start || sp.start >= end) {
                out.add(sp)
                continue
            }
            if (sp.start < start) out.add(sp.copy(end = start))
            if (sp.end > end) out.add(sp.copy(start = end))
        }
        return out
    }
}
