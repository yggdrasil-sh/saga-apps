package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatContent
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatMedia
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSys
import io.github.yggdrasil_sh.saga.chat.ChatThumb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 Medien 1: Bilderliste des Betrachters, Position, Zaehler, Zitat-Platzhalter, Zoom-/Verschiebe-Grenzen */
class ChatMediaTest {
    private val labels = ChatMedia.Labels(photo = "Foto", video = "Video", voice = "Sprachnachricht")

    private fun img(id: String, v2: Boolean = false, th: Boolean = true) =
        ChatFile(id = id, n = "$id.jpg", m = "image/jpeg", s = 100, x = if (v2) 2 else null, th = if (th) ChatThumb(id = "t$id", w = 32, h = 24) else null)

    private fun vid(id: String, th: Boolean = true) = ChatFile(id = id, n = "$id.mp4", m = "video/mp4", s = 100, th = if (th) ChatThumb(id = "t$id") else null)
    private val voice = ChatFile(id = "v", n = "voice.ogg", m = "audio/ogg", s = 10, voice = true)
    private val doc = ChatFile(id = "d", n = "bericht.pdf", m = "application/pdf", s = 10)

    private fun msg(
        id: String, seq: Long, text: String? = "", files: List<ChatFile> = emptyList(), tomb: Boolean = false,
        sys: ChatSys? = null, mine: Boolean = false,
    ) = ChatMessage(
        seq, id, ChatPerson(username = "anna", name = "Anna"), "d", seq * 1000, null, null,
        text?.let { ChatContent(text = it, files = files) }, true, mine, sys = sys, tomb = tomb,
    )

    @Test
    fun viewableImagesOnly() {
        assertTrue(ChatMedia.viewable(img("a")))
        assertTrue(ChatMedia.viewable(img("a", th = false)))                     // v1 ohne Vorschaubild: wird geladen
        assertTrue(ChatMedia.viewable(img("a", v2 = true)))                      // grosses Bild mit Vorschaubild
        assertFalse(ChatMedia.viewable(img("a", v2 = true, th = false)))         // Verlauf zeigt es als Datei
        assertFalse(ChatMedia.viewable(ChatFile(id = "n", n = "x.png", m = "image/png", x = 3)))   // neueres Format
        assertFalse(ChatMedia.viewable(vid("v")))
        assertFalse(ChatMedia.viewable(doc))
        assertFalse(ChatMedia.viewable(voice))
    }

    @Test
    fun galleryInMessageOrderWithoutHiddenAndOthers() {
        val msgs = listOf(
            msg("m1", 1, files = listOf(img("a"), doc, img("b"))),
            msg("m2", 2, text = "nur Text"),
            msg("m3", 3, files = listOf(vid("v1"))),
            msg("m4", 4, files = listOf(img("c")), tomb = true),
            msg("m5", 5, text = null),                                             // nicht entschluesselt
            msg("m6", 6, files = listOf(img("d"))),
            msg("m7", 7, files = listOf(img("e"))),
            msg("m8", 8, files = listOf(img("f")), sys = ChatSys(type = "ai")),
            msg("m9", 9, files = listOf(img("g"))),
        )
        val g = ChatMedia.gallery(msgs, hidden = setOf("m6")) { it.id == "m9" }
        assertEquals(listOf("a", "b", "e"), g.map { it.file.id })
        assertEquals(listOf("m1", "m1", "m7"), g.map { it.msgId })
        assertTrue(ChatMedia.gallery(emptyList()).isEmpty())
    }

    @Test
    fun indexOfTappedImage() {
        val g = ChatMedia.gallery(listOf(msg("m1", 1, files = listOf(img("a"), img("b"))), msg("m2", 2, files = listOf(img("c")))))
        assertEquals(0, ChatMedia.indexOf(g, "m1", "a"))
        assertEquals(1, ChatMedia.indexOf(g, "m1", "b"))
        assertEquals(2, ChatMedia.indexOf(g, "m2", "c"))
        assertEquals(2, ChatMedia.indexOf(g, "m2", "weg"))                       // Anhang fehlt: erstes Bild der Nachricht
        assertEquals(0, ChatMedia.indexOf(g, "weg", "weg"))
        assertEquals(0, ChatMedia.indexOf(emptyList(), "m1", "a"))
    }

    @Test
    fun counterText() {
        assertEquals("1 / 3", ChatMedia.counter(0, 3))
        assertEquals("3 / 3", ChatMedia.counter(2, 3))
        assertEquals("3 / 3", ChatMedia.counter(7, 3))
        assertEquals("", ChatMedia.counter(0, 0))
    }

    @Test
    fun replyLabelForAttachmentsWithoutText() {
        assertEquals("Foto", ChatMedia.attachmentLabel(ChatContent(files = listOf(img("a"))), labels))
        assertEquals("Video", ChatMedia.attachmentLabel(ChatContent(files = listOf(vid("v"))), labels))
        assertEquals("Sprachnachricht", ChatMedia.attachmentLabel(ChatContent(files = listOf(voice)), labels))
        assertEquals("bericht.pdf", ChatMedia.attachmentLabel(ChatContent(files = listOf(doc, img("a"))), labels))
        // mit Text gilt der Text; ohne Anhang bzw. Inhalt nichts
        assertNull(ChatMedia.attachmentLabel(ChatContent(text = "Schau mal", files = listOf(img("a"))), labels))
        assertNull(ChatMedia.attachmentLabel(ChatContent(text = ""), labels))
        assertNull(ChatMedia.attachmentLabel(null, labels))
        // nur Leerzeichen zaehlt als kein Text
        assertEquals("Foto", ChatMedia.attachmentLabel(ChatContent(text = "  ", files = listOf(img("a"))), labels))
    }

    @Test
    fun quoteThumbPicksImageOrVideoWithThumb() {
        assertEquals("a", ChatMedia.quoteThumb(ChatContent(files = listOf(doc, img("a"))))?.id)
        assertEquals("v", ChatMedia.quoteThumb(ChatContent(files = listOf(vid("v"))))?.id)
        assertNull(ChatMedia.quoteThumb(ChatContent(files = listOf(vid("v", th = false)))))
        assertNull(ChatMedia.quoteThumb(ChatContent(files = listOf(img("b", v2 = true, th = false)))))
        assertNull(ChatMedia.quoteThumb(ChatContent(files = listOf(doc, voice))))
        assertNull(ChatMedia.quoteThumb(null))
    }

    @Test
    fun zoomAndPanBounds() {
        // 4000x3000 in 1000x2000 eingepasst: 1000x750
        assertEquals(1000f to 750f, ChatMedia.fitted(4000, 3000, 1000f, 2000f))
        assertEquals(1000f to 2000f, ChatMedia.fitted(0, 0, 1000f, 2000f))        // Masse unbekannt: ganzes Feld
        // nicht gezoomt: nicht verschiebbar; 2x: halbe Ueberstaende
        assertEquals(0f, ChatMedia.maxPan(1000f, 1000f, 1f), 0.001f)
        assertEquals(500f, ChatMedia.maxPan(1000f, 1000f, 2f), 0.001f)
        assertEquals(0f, ChatMedia.maxPan(750f, 2000f, 2f), 0.001f)              // 1500 < 2000: senkrecht nicht
        assertEquals(500f, ChatMedia.clampPan(900f, 1000f, 1000f, 2f), 0.001f)
        assertEquals(-500f, ChatMedia.clampPan(-900f, 1000f, 1000f, 2f), 0.001f)
        assertEquals(120f, ChatMedia.clampPan(120f, 1000f, 1000f, 2f), 0.001f)
        assertEquals(1f, ChatMedia.clampZoom(0.3f), 0.001f)
        assertEquals(ChatMedia.ZOOM_MAX, ChatMedia.clampZoom(40f), 0.001f)
        assertEquals(1f, ChatMedia.clampZoom(Float.NaN), 0.001f)
        // Punkt unter den Fingern bleibt stehen: Fokus 100 rechts der Mitte, 1x -> 2x => Verschiebung -100
        assertEquals(-100f, ChatMedia.zoomAround(0f, 100f, 1f, 2f), 0.001f)
        assertEquals(0f, ChatMedia.zoomAround(0f, 0f, 1f, 3f), 0.001f)
        assertFalse(ChatMedia.zoomed(1f))
        assertTrue(ChatMedia.zoomed(1.5f))
    }
}
