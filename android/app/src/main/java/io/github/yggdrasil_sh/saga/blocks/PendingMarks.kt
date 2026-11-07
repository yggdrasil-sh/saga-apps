package io.github.yggdrasil_sh.saga.blocks

/**
 * Nachbesserung R3 Editor (B/I/U/S ohne Auswahl): Formate, die an der Schreibmarke fuer das naechste Tippen vorgemerkt
 * sind (wie in Docs/Notion). Ohne Auswahl schaltet ein Format-Knopf den Wunschzustand an der Schreibmarke um; der
 * naechste an genau dieser Stelle eingefuegte Text bekommt ihn. Reine Logik (Unit-Test), der Zustand lebt im Feld.
 */
object PendingMarks {
    /** Format an der Schreibmarke at (Klartext) ohne Vormerkung: wie das Zeichen davor */
    fun natural(rt: RichText, at: Int, style: InlineStyle): Boolean = InlineHtml.isActive(rt, at, at, style)

    /** Anzeigezustand eines Formats an der Schreibmarke: vorgemerkt, sonst wie das Zeichen davor */
    fun isOn(rt: RichText, at: Int, style: InlineStyle, marks: Map<InlineStyle, Boolean>): Boolean =
        marks[style] ?: natural(rt, at, style)

    /** Knopf ohne Auswahl: Vormerkung umschalten; entspricht sie wieder dem natuerlichen Zustand, faellt sie weg */
    fun toggle(rt: RichText, at: Int, style: InlineStyle, marks: Map<InlineStyle, Boolean>): Map<InlineStyle, Boolean> {
        val want = !isOn(rt, at, style, marks)
        return if (want == natural(rt, at, style)) marks - style else marks + (style to want)
    }

    /**
     * Laenge des Texts, der in old genau an der Stelle at eingefuegt wurde (sonst nichts geaendert) -> new; null, wenn
     * es keine reine Einfuegung an at ist (Loeschen, Ersetzen, Autokorrektur an anderer Stelle).
     */
    fun insertionAt(old: String, new: String, at: Int): Int? {
        if (at < 0 || at > old.length || new.length <= old.length) return null
        val len = new.length - old.length
        return if (new.regionMatches(0, old, 0, at) && new.regionMatches(at + len, old, at, old.length - at)) len else null
    }

    /** Vorgemerkte Formate auf [start, end) anwenden (nur, wo der Zustand abweicht) */
    fun apply(rt: RichText, start: Int, end: Int, marks: Map<InlineStyle, Boolean>): RichText {
        if (start >= end) return rt
        var r = rt
        for ((s, want) in marks) if (InlineHtml.isActive(r, start, end, s) != want) r = InlineHtml.toggle(r, start, end, s)
        return r
    }
}
