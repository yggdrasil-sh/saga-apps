package io.github.yggdrasil_sh.saga.chat

/**
 * Saga 2.5 (chatfmt1): Markdown-light NUR fuer die Darstellung - Port von static/chat-fmt.js (gleiche Regeln, gleiche
 * Testvektoren). *fett*, _kursiv_, ~durch~, `code`, ```block```, Zeilen "- " = Liste, "> " = Zitat. Marken nur am
 * Wortrand (a_b_c, 2*3*4 bleiben). Ergebnis ist ein kleiner Baum aus Text und festen Elementen (wie die DOM-Elemente im
 * Web) - nie HTML, nie Attribute aus dem Text. Die App macht daraus einen AnnotatedString (ChatFmtUi).
 */
object ChatFmt {
    /** Knoten wie im Web-DOM: Text bzw. Element mit festem Namen (strong, em, s, code, pre, ul, li, blockquote) */
    sealed class Node

    /** raw = fester Text (Inhalt von code/pre), geht nicht durch die Erkennung von Links/Erwaehnungen */
    class Text(var text: String, val raw: Boolean = false) : Node()

    class El(val tag: String, val children: MutableList<Node> = ArrayList()) : Node()

    /** Leerraum wie \s in JavaScript (auch geschuetzte/typografische Leerzeichen und BOM) */
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val MARK = mapOf("*" to "strong", "_" to "em", "~" to "s")

    // wie INL in chat-fmt.js; "$" dort = Ende der Eingabe -> \z (Java-$ passt auch vor einem letzten Zeilenumbruch)
    private val INL = Regex("(^|[$WS(\\[{\"'])([*_~])([^$WS*_~\\n](?:[^\\n]*?[^$WS\\n])?)\\2(?=\\z|[$WS)\\]}\"'.,;:!?])")
    private val CODE = Regex("`([^`\\n]+)`")
    private val BLOCK = Regex("```(?:[a-zA-Z0-9_+-]{0,20}\\n)?([\\s\\S]*?)```")
    private val BLOCK_OPEN = Regex("```(?:[a-zA-Z0-9_+-]{0,20}\\n)?")
    private val QUOTE_PFX = Regex("^> ?")
    private val LIST = Regex("^[-•] ")

    /** Textstueck zeichnen (Standard: Text; die App ersetzt Links, Seitenverweise und Erwaehnungen) */
    fun interface Leaf {
        fun leaf(box: El, text: String)
    }

    private val plainLeaf = Leaf { b, t -> b.children.add(Text(t)) }

    private fun inline(box: El, s: String, leaf: Leaf, depth: Int) {
        if (s.isEmpty()) return
        val mc = CODE.find(s)
        if (mc != null) {
            inline(box, s.substring(0, mc.range.first), leaf, depth)
            box.children.add(El("code", mutableListOf(Text(mc.groupValues[1], raw = true))))
            inline(box, s.substring(mc.range.last + 1), leaf, depth)
            return
        }
        val m = if (depth < 4) INL.find(s) else null
        if (m == null) {
            leaf.leaf(box, s)
            return
        }
        val start = m.range.first + m.groupValues[1].length
        if (start > 0) leaf.leaf(box, s.substring(0, start))
        val w = El(MARK.getValue(m.groupValues[2]))
        inline(w, m.groupValues[3], leaf, depth + 1)
        box.children.add(w)
        inline(box, s.substring(m.range.last + 1), leaf, depth)
    }

    private fun lines(box: El, text: String, leaf: Leaf) {
        val ls = text.split("\n")
        var i = 0
        var first = true
        fun nl() {
            if (!first) box.children.add(Text("\n", raw = true))
            first = false
        }
        while (i < ls.size) {
            val l = ls[i]
            if (l.startsWith(">")) {
                val q = El("blockquote")
                val qs = ArrayList<String>()
                while (i < ls.size && ls[i].startsWith(">")) {
                    qs.add(ls[i].replaceFirst(QUOTE_PFX, ""))
                    i++
                }
                lines(q, qs.joinToString("\n"), leaf)
                val lc = box.children.lastOrNull()
                if (!first && lc is Text) lc.text = lc.text.removeSuffix("\n")
                box.children.add(q)
                first = true
                continue
            }
            if (LIST.containsMatchIn(l)) {
                val ul = El("ul")
                while (i < ls.size && LIST.containsMatchIn(ls[i])) {
                    val li = El("li")
                    inline(li, ls[i].substring(2), leaf, 0)
                    ul.children.add(li)
                    i++
                }
                box.children.add(ul)
                first = true
                continue
            }
            nl()
            inline(box, l, leaf, 0)
            i++
        }
    }

    /** Text -> Baum (Wurzel ohne eigene Bedeutung, wie das Web-Element `box`) */
    fun render(text: String?, leaf: Leaf = plainLeaf): El {
        val box = El("box")
        val s = text.orEmpty()
        var last = 0
        for (m in BLOCK.findAll(s)) {
            val before = s.substring(last, m.range.first).removeSuffix("\n")
            if (before.isNotEmpty()) lines(box, before, leaf)
            box.children.add(El("pre", mutableListOf(El("code", mutableListOf(Text(m.groupValues[1].removeSuffix("\n"), raw = true))))))
            last = m.range.last + 1
            if (last < s.length && s[last] == '\n') last++
        }
        if (last < s.length) lines(box, s.substring(last), leaf)
        return box
    }

    /** Fuer Vorschauen (Liste, Zitat, Mitteilung): Marken entfernen - wie strip() in chat-fmt.js */
    fun strip(text: String?): String {
        var s = text.orEmpty().replace(BLOCK_OPEN, "")
        s = CODE.replace(s) { it.groupValues[1] }
        s = INL.replace(s) { it.groupValues[1] + it.groupValues[3] }
        // /^(> ?|[-•] )/gm - Zeilenanfang wie in JavaScript (nach \n, \r,  ,  )
        val out = StringBuilder(s.length)
        var i = 0
        var lineStart = true
        while (i < s.length) {
            if (lineStart) {
                lineStart = false
                if (s.startsWith("- ", i) || s.startsWith("• ", i)) { i += 2; continue }
                if (s[i] == '>') { i += if (i + 1 < s.length && s[i + 1] == ' ') 2 else 1; continue }
            }
            val c = s[i]
            out.append(c)
            if (c == '\n' || c == '\r' || c == ' ' || c == ' ') lineStart = true
            i++
        }
        return out.toString()
    }

    /** Baum als Text wie innerHTML im Web (fuer die gemeinsamen Testvektoren): Text mit &amp; &lt; &gt;, Elemente ohne Klassen */
    fun serialize(n: Node): String = when (n) {
        is Text -> n.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        is El -> if (n.tag == "box") n.children.joinToString("") { serialize(it) }
        else "<" + n.tag + ">" + n.children.joinToString("") { serialize(it) } + "</" + n.tag + ">"
    }

    // ------------------------------------------------------------------ flache Darstellung (Text + Bereiche)

    /** Art eines Bereichs im fertigen Text (die App setzt Stile; Links/Seiten/Erwaehnungen kommen aus [Sink.leaf]) */
    enum class Kind { BOLD, ITALIC, STRIKE, CODE, PRE, QUOTE, BULLET }

    /** Ziel der flachen Darstellung: App = AnnotatedString.Builder, Tests = Text + Bereiche */
    interface Sink {
        val length: Int
        /** fester Text (Code, Zeilenumbrueche, Listenpunkt) */
        fun text(s: String)
        /** Textstueck aus dem Nachrichtentext (Links, Seitenverweise, Erwaehnungen erkennt die App) */
        fun leaf(s: String)
        fun span(start: Int, end: Int, kind: Kind)
    }

    /**
     * Baum -> ein Text mit Bereichen: Bloecke (pre, ul/li, blockquote) stehen auf eigenen Zeilen, Listenpunkte mit "• ".
     * Kein Zeilenumbruch am Ende. Text aus code/pre geht nie durch [Sink.leaf] (wie textContent im Web).
     */
    fun flatten(root: El, out: Sink) {
        var last = '\n'                     // letztes ausgegebenes Zeichen ('\n' = Zeilenanfang)
        var pendingNl = false                // nach einem Block: naechster Inhalt auf neuer Zeile
        fun emit(s: String, leaf: Boolean) {
            if (s.isEmpty()) return
            if (pendingNl) {
                pendingNl = false
                if (s[0] != '\n' && last != '\n') out.text("\n")
            }
            if (leaf) out.leaf(s) else out.text(s)
            last = s.last()
        }
        fun walk(n: Node, raw: Boolean) {
            when (n) {
                is Text -> emit(n.text, !raw && !n.raw)
                is El -> {
                    val block = n.tag == "pre" || n.tag == "ul" || n.tag == "li" || n.tag == "blockquote"
                    if (block && out.length > 0 && last != '\n') {
                        out.text("\n")
                        last = '\n'
                    }
                    if (block) pendingNl = false
                    if (n.tag == "li") {
                        val b = out.length
                        emit("• ", false)
                        out.span(b, out.length, Kind.BULLET)
                    }
                    val st = out.length
                    n.children.forEach { walk(it, raw || n.tag == "code") }
                    val kind = when (n.tag) {
                        "strong" -> Kind.BOLD
                        "em" -> Kind.ITALIC
                        "s" -> Kind.STRIKE
                        "code" -> Kind.CODE
                        "pre" -> Kind.PRE
                        "blockquote" -> Kind.QUOTE
                        else -> null
                    }
                    if (kind != null && out.length > st) out.span(st, out.length, kind)
                    if (block) pendingNl = true
                }
            }
        }
        root.children.forEach { walk(it, false) }
    }

    /** Fuer Tests und einfache Anzeigen: Text + Bereiche */
    class Plain : Sink {
        val sb = StringBuilder()
        val spans = ArrayList<Triple<Int, Int, Kind>>()
        override val length: Int get() = sb.length
        override fun text(s: String) { sb.append(s) }
        override fun leaf(s: String) { sb.append(s) }
        override fun span(start: Int, end: Int, kind: Kind) { spans.add(Triple(start, end, kind)) }
        override fun toString(): String = sb.toString()
    }
}
