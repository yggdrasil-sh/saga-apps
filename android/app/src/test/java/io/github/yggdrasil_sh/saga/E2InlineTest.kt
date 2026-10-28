package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.InlineForms
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.data.CommentMentions
import io.github.yggdrasil_sh.saga.data.MentionPerson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 E2 (P6-8): Inline-Formen setzen, Erwaehnung/Mathe unteilbar - gegen tests/vectors/editor_inline.json */
class E2InlineTest {
    private val vectors = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("vectors/editor_inline.json")!!.bufferedReader().readText()).jsonObject

    private fun rt(h: String) = InlineHtml.parse(h)
    private fun html(h: String) = InlineHtml.toHtml(rt(h))

    @Test fun roundTripAllVectorOutputs() {
        // Inline-HTML -> Editor -> HTML: jedes gespeicherte "out" bleibt byte-gleich (sanitize, publish-Eingaben, markdown-Eingaben)
        var n = 0
        for (sec in listOf("sanitize", "publish", "markdown")) {
            for (v in vectors[sec]!!.jsonArray) {
                val a = v.jsonArray
                val stored = if (sec == "sanitize") a[2].jsonPrimitive.content else a[1].jsonPrimitive.content
                assertEquals("$sec: ${a[0].jsonPrimitive.content}", stored, html(stored))
                // auch nach einer Eingabe am Ende und deren Loeschen
                val r = rt(stored)
                val typed = InlineForms.applyEditAtomic(r, r.text + "x").rich
                val back = InlineForms.applyEditAtomic(typed, r.text).rich
                assertEquals("$sec edit: ${a[0].jsonPrimitive.content}", stored, InlineHtml.toHtml(back))
                n++
            }
        }
        assertTrue(n >= 30)
    }

    @Test fun setAndRemoveColors() {
        val r = rt("Hallo Welt")
        val red = InlineForms.setColor(r, 0, 5, "red", false)
        assertEquals("""<span class="hk-c-red">Hallo</span> Welt""", InlineHtml.toHtml(red))
        val both = InlineForms.setColor(red, 0, 5, "blue", true)
        assertEquals("""<span class="hk-c-red"><mark class="hk-bg-blue">Hallo</mark></span> Welt""", InlineHtml.toHtml(both))   // span aussen
        // Hintergrund zuerst, dann Textfarbe: Reihenfolge bleibt span aussen
        val bgFirst = InlineForms.setColor(InlineForms.setColor(r, 0, 5, "blue", true), 0, 5, "red", false)
        assertEquals("""<span class="hk-c-red"><mark class="hk-bg-blue">Hallo</mark></span> Welt""", InlineHtml.toHtml(bgFirst))
        // andere Farbe ersetzt (genau eine Klasse), Teilbereich schneidet
        val green = InlineForms.setColor(both, 2, 5, "green", false)
        assertEquals("""<span class="hk-c-red"><mark class="hk-bg-blue">Ha</mark></span><span class="hk-c-green"><mark class="hk-bg-blue">llo</mark></span> Welt""", InlineHtml.toHtml(green))
        assertEquals("blue", InlineForms.colorAt(green, 0, 5, true))
        assertNull(InlineForms.colorAt(green, 0, 5, false))                // gemischt
        val none = InlineForms.setColor(InlineForms.setColor(both, 0, 5, null, false), 0, 5, null, true)
        assertEquals("Hallo Welt", InlineHtml.toHtml(none))
        assertEquals("Hallo Welt", InlineHtml.toHtml(InlineForms.setColor(r, 0, 5, "lila", false)))   // nur Palette
        // vorhandenes HTML mit mark aussen bleibt byte-gleich, solange nichts geaendert wird
        val legacy = """<mark class="hk-bg-blue"><span class="hk-c-red">x</span></mark>"""
        assertEquals("""<span class="hk-c-red"><mark class="hk-bg-blue">x</mark></span>""", html(legacy))
    }

    private val mention = """Hi <span class="hk-mention" data-u="~abcdefghijklmnop">Anna</span> und <code class="hk-math">a&lt;b</code>!"""

    @Test fun atomicMentionAndMath() {
        val r = rt(mention)
        assertEquals("Hi Anna und a<b!", r.text)
        // Ruecktaste im Namen -> ganze Erwaehnung weg
        val bs = InlineForms.applyEditAtomic(r, "Hi Ann und a<b!")
        assertEquals("""Hi  und <code class="hk-math">a&lt;b</code>!""", InlineHtml.toHtml(bs.rich))
        assertEquals(3, bs.cursor)
        // Tippen mitten im Namen -> dahinter
        val ty = InlineForms.applyEditAtomic(r, "Hi AnXna und a<b!")
        assertEquals("""Hi <span class="hk-mention" data-u="~abcdefghijklmnop">Anna</span>X und <code class="hk-math">a&lt;b</code>!""", InlineHtml.toHtml(ty.rich))
        assertEquals(8, ty.cursor)
        // Weiterschreiben direkt hinter der Formel: Formel waechst nicht
        val after = InlineForms.applyEditAtomic(r, "Hi Anna und a<bc!")
        assertTrue(InlineHtml.toHtml(after.rich).contains("""<code class="hk-math">a&lt;b</code>c!"""))
        // normales Tippen anderswo: unveraendert erhalten
        val ok = InlineForms.applyEditAtomic(r, "Hallo Anna und a<b!")
        assertNull(ok.cursor)
        assertEquals(mention.replace("Hi ", "Hallo "), InlineHtml.toHtml(ok.rich))
        // Farbe ueber eine Erwaehnung hinweg erweitert auf die ganze Einheit
        val col = InlineForms.setColor(r, 5, 9, "red", false)
        assertEquals("""Hi <span class="hk-c-red"><span class="hk-mention" data-u="~abcdefghijklmnop">Anna</span> u</span>nd <code class="hk-math">a&lt;b</code>!""", InlineHtml.toHtml(col))
    }

    @Test fun insertMentionAndMath() {
        val (m, cur) = InlineForms.insertMention(rt("<b>Hallo</b> "), 6, "~abcdefghijklmnop", "Anna")
        assertEquals("""<b>Hallo</b> <span class="hk-mention" data-u="~abcdefghijklmnop">Anna</span> """, InlineHtml.toHtml(m))
        assertEquals(11, cur)
        // Prinzipal mit Sonderzeichen wird als Attribut maskiert
        val (p, _) = InlineForms.insertMention(rt(""), 0, "@bob@b.example", "Bob")
        assertEquals("""<span class="hk-mention" data-u="@bob@b.example">Bob</span> """, InlineHtml.toHtml(p))
        val (f, fc) = InlineForms.insertMath(rt("x = y"), 4, 5, "\\frac{a}{b}")
        assertEquals("""x = <code class="hk-math">\frac{a}{b}</code>""", InlineHtml.toHtml(f))
        assertEquals(15, fc)
        // Formel bearbeiten: ganze Einheit ersetzen, Entities korrekt
        val (g, _) = InlineForms.insertMath(rt(mention), 12, 15, "a>b")
        assertEquals("""Hi <span class="hk-mention" data-u="~abcdefghijklmnop">Anna</span> und <code class="hk-math">a&gt;b</code>!""", InlineHtml.toHtml(g))
        assertEquals("a<b" to (12 until 15), InlineForms.mathAt(rt(mention), 13)!!.let { it.second to it.first })
        // Mathe enthaelt nur Text: Formatierung darf sie umschliessen, aber nie in ihr beginnen/enden
        val (h, _) = InlineForms.insertMath(rt("a<i>bc</i>d"), 0, 2, "k")
        assertEquals("""<i><code class="hk-math">k</code>c</i>d""", InlineHtml.toHtml(h))
        for ((src, a, b) in listOf(Triple("<b>abc</b>d", 1, 4), Triple("x<i>yz</i>", 0, 2), Triple("<u>a<s>bc</s></u>", 1, 2))) {
            val out = InlineHtml.toHtml(InlineForms.insertMath(rt(src), a, b, "k").first)
            assertTrue(out, out.contains("""<code class="hk-math">k</code>"""))     // nichts in der Formel verschachtelt
        }
    }

    @Test fun commentTokens() {
        val ppl = mapOf("~abcdefghijklmnop" to MentionPerson("Anna Berg", me = true))
        val parts = CommentMentions.split("Schau <@~abcdefghijklmnop> und <@@bob@b.example>!")
        assertEquals(listOf(null, "~abcdefghijklmnop", null, "@bob@b.example", null), parts.map { it.ref })
        assertEquals("@Anna Berg", CommentMentions.label("~abcdefghijklmnop", ppl))
        assertEquals("@…", CommentMentions.label("@bob@b.example", ppl))      // nie die Kennung zeigen
        assertEquals("<@~x>", CommentMentions.token("~x"))
        val d = CommentMentions.display("A <@~abcdefghijklmnop> B", ppl)
        assertEquals("A @Anna Berg B", d.text)
        assertEquals(listOf(2 until 12), d.chips)
        assertEquals(2, d.toDisplay(2))
        assertEquals(12, d.toDisplay(10))                                // im Token -> Rand
        assertEquals(2, d.toOriginal(2))
        assertEquals(22, d.toOriginal(12))
        assertEquals(24, d.toOriginal(14))
    }
}
