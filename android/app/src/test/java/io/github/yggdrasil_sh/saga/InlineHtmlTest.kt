package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.blocks.InlineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineHtmlTest {
    private fun rt(html: String) = InlineHtml.parse(html)
    private fun same(html: String) = assertEquals(html, InlineHtml.toHtml(rt(html)))

    @Test
    fun roundtripOfServerHtml() {
        same("Hallo <b>Welt</b>")
        same("a <i>b <b>c</b></i> d")
        same("""Siehe <a href="/notes#12">Seite</a> und <a href="https://example.org/?a=1&amp;b=2" target="_blank" rel="noopener noreferrer">Web</a>""")
        same("""<mark class="cdx-marker">gelb</mark> <code class="inline-code">x &lt; y</code>""")
        same("""Termin <time datetime="2026-10-10" class="hk-date">10.10.</time>""")
        same("Zeile 1<br>Zeile 2")
        same("Tom &amp; Jerry &lt;3")
        same("")
    }

    @Test
    fun unknownTagsArePreserved() {
        same("""vor <span data-x="1">mitte</span> nach""")
        val r = rt("""<span class="z">ab</span>""")
        assertEquals("ab", r.text)
        assertEquals("span", r.spans.single().tag)
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("a & b < c > d \" e ' f g é", rt("a &amp; b &lt; c &gt; d &quot; e &#39; f&nbsp;g &#xe9;").text)
        assertEquals("a\nb", rt("a<br/>b").text)
    }

    @Test
    fun typingInsideBoldExtendsIt() {
        val r = InlineHtml.applyEdit(rt("x <b>fett</b> y"), "x feXtt y")
        assertEquals("x <b>feXtt</b> y", InlineHtml.toHtml(r))
    }

    @Test
    fun typingAtEndContinuesBoldButNotLink() {
        val b = InlineHtml.applyEdit(rt("<b>fett</b>"), "fett!")
        assertEquals("<b>fett!</b>", InlineHtml.toHtml(b))
        val a = InlineHtml.applyEdit(rt("""<a href="/notes#1">Link</a>"""), "Link!")
        assertEquals("""<a href="/notes#1">Link</a>!""", InlineHtml.toHtml(a))
    }

    @Test
    fun typingBeforeSpanDoesNotStyle() {
        val r = InlineHtml.applyEdit(rt("<b>fett</b>"), ">fett")
        assertEquals("&gt;<b>fett</b>", InlineHtml.toHtml(r))
    }

    @Test
    fun deletingWholeSpanRemovesIt() {
        val r = InlineHtml.applyEdit(rt("a <b>b</b> c"), "a  c")
        assertEquals("a  c", InlineHtml.toHtml(r))
    }

    @Test
    fun editKeepsOtherSpansAndUnknownTags() {
        val r = InlineHtml.applyEdit(rt("""<span x="1">eins</span> zwei <i>drei</i>"""), """eins zwo <i>drei</i>""".replace("<i>", "").replace("</i>", ""))
        assertEquals("""<span x="1">eins</span> zwo <i>drei</i>""", InlineHtml.toHtml(r))
    }

    @Test
    fun splitAtReturn() {
        val (l, r) = InlineHtml.split(rt("ab<b>cd</b>ef"), 3)
        assertEquals("ab<b>c</b>", InlineHtml.toHtml(l))
        assertEquals("<b>d</b>ef", InlineHtml.toHtml(r))
    }

    @Test
    fun toggleBoldOnAndOff() {
        val on = InlineHtml.toggle(rt("abc def"), 4, 7, InlineStyle.BOLD)
        assertEquals("abc <b>def</b>", InlineHtml.toHtml(on))
        assertTrue(InlineHtml.isActive(on, 4, 7, InlineStyle.BOLD))
        val off = InlineHtml.toggle(on, 5, 6, InlineStyle.BOLD)
        assertEquals("abc <b>d</b>e<b>f</b>", InlineHtml.toHtml(off))
        assertFalse(InlineHtml.isActive(off, 4, 7, InlineStyle.BOLD))
        // ueberlappend fett machen -> ein zusammenhaengender Bereich
        val merged = InlineHtml.toggle(off, 4, 7, InlineStyle.BOLD)
        assertEquals("abc <b>def</b>", InlineHtml.toHtml(merged))
        // <strong> zaehlt als fett
        assertTrue(InlineHtml.isActive(rt("<strong>x</strong>"), 0, 1, InlineStyle.BOLD))
    }

    @Test
    fun overlappingSpansAreNestedCorrectly() {
        val r = InlineHtml.toggle(InlineHtml.toggle(rt("abcdef"), 0, 4, InlineStyle.BOLD), 2, 6, InlineStyle.ITALIC)
        assertEquals("<b>ab<i>cd</i></b><i>ef</i>", InlineHtml.toHtml(r))
    }

    @Test
    fun linksSetAndRead() {
        val r = InlineHtml.setLink(rt("hier klicken"), 0, 4, "https://example.org/?q=\"x\"")
        assertEquals("""<a href="https://example.org/?q=&quot;x&quot;">hier</a> klicken""", InlineHtml.toHtml(r))
        assertEquals("https://example.org/?q=\"x\"", InlineHtml.linkAt(r, 2))
        assertNull(InlineHtml.linkAt(r, 8))
        val removed = InlineHtml.setLink(r, 0, 4, "")
        assertEquals("hier klicken", InlineHtml.toHtml(removed))
    }

    @Test
    fun escapeForSaving() {
        val r = InlineHtml.applyEdit(rt(""), "a<b>&c")
        assertEquals("a&lt;b&gt;&amp;c", InlineHtml.toHtml(r))
    }
}
