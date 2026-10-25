package io.github.yggdrasil_sh.saga

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import io.github.yggdrasil_sh.saga.call.CallLayout
import io.github.yggdrasil_sh.saga.ui.chat.ChatDesign
import io.github.yggdrasil_sh.saga.ui.chat.ChatUi
import io.github.yggdrasil_sh.saga.ui.theme.Dark
import io.github.yggdrasil_sh.saga.ui.theme.Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** Design 2 (Chats, Anrufe): Farben der Profilkreise und Namen, Blasenfolgen, Zeit in der Liste, Knopfreihen */
class Design2Test {
    @Test
    fun avatarTonesReadable() {
        for ((tones, label) in listOf(ChatDesign.LIGHT_TONES to "hell", ChatDesign.DARK_TONES to "dunkel")) {
            assertEquals(6, tones.size)
            tones.forEachIndexed { i, t ->
                val c = ChatUi.contrast(t.fg, t.bg)
                assertTrue("Initialen $label #$i: $c", c >= 4.5)
            }
        }
    }

    @Test
    fun groupNamesReadableOnBubbles() {
        for ((names, scheme) in listOf(ChatDesign.LIGHT_NAMES to Light, ChatDesign.DARK_NAMES to Dark)) {
            val bg = ChatUi.bubble(scheme, false)
            names.forEachIndexed { i, n ->
                val c = ChatUi.contrast(n, bg)
                assertTrue("Name #$i auf Blase: $c", c >= 4.5)
            }
        }
    }

    @Test
    fun toneStablePerName() {
        assertEquals(ChatDesign.toneIndex("Mila Berg"), ChatDesign.toneIndex("  mila berg "))
        assertEquals(0, ChatDesign.toneIndex(""))
        // verteilt sich: unter 30 Namen kommen mehrere Toene vor
        val used = (1..30).map { ChatDesign.toneIndex("Person $it") }.toSet()
        assertTrue(used.size >= 4)
    }

    @Test
    fun initials() {
        assertEquals("MB", ChatDesign.initials("Mila Berg"))
        assertEquals("D", ChatDesign.initials("dana"))
        assertEquals("AK", ChatDesign.initials("anna-katrin müller"))
        assertEquals("?", ChatDesign.initials("  "))
        assertEquals("ÖS", ChatDesign.initials("Özlem Şahin"))
    }

    @Test
    fun bubbleRunsAndCorners() {
        val t = 1_000_000L
        assertTrue(ChatDesign.sameRun("a", false, t, "a", false, t + 60_000))
        assertFalse(ChatDesign.sameRun("a", false, t, "b", false, t + 1000))                 // andere Person
        assertFalse(ChatDesign.sameRun("a", false, t, "a", false, t + 6 * 60_000))           // zu weit auseinander
        assertTrue(ChatDesign.sameRun("me", true, t, "me2", true, t + 1000))                 // eigene: Seite zaehlt
        assertFalse(ChatDesign.sameRun("a", true, t, "a", false, t))

        val d = Density(1f)
        val size = Size(200f, 100f)
        fun corners(s: RoundedCornerShape) = listOf(s.topStart, s.topEnd, s.bottomEnd, s.bottomStart).map { it.toPx(size, d) }
        // eigene Blase, einzeln: Zipfel unten rechts
        assertEquals(listOf(20f, 20f, 4f, 20f), corners(ChatDesign.bubbleShape(mine = true, first = true, last = true)))
        // eigene Blase mitten in einer Folge: rechts eng
        assertEquals(listOf(20f, 6f, 6f, 20f), corners(ChatDesign.bubbleShape(mine = true, first = false, last = false)))
        // fremde, letzte der Folge: Zipfel unten links, oben links eng
        assertEquals(listOf(6f, 20f, 20f, 4f), corners(ChatDesign.bubbleShape(mine = false, first = false, last = true)))
    }

    @Test
    fun listTimeTodayWeekdayDate() {
        val z = ZoneId.of("Europe/Berlin")
        val now = java.time.ZonedDateTime.of(2026, 10, 25, 14, 0, 0, 0, z).toInstant().toEpochMilli()
        val today = java.time.ZonedDateTime.of(2026, 10, 25, 9, 5, 0, 0, z).toInstant().toEpochMilli()
        val tue = java.time.ZonedDateTime.of(2026, 10, 20, 9, 5, 0, 0, z).toInstant().toEpochMilli()
        val old = java.time.ZonedDateTime.of(2026, 9, 1, 9, 5, 0, 0, z).toInstant().toEpochMilli()
        assertEquals("09:05", ChatDesign.listTime(today, now, z, Locale.GERMANY))
        assertEquals("Di.", ChatDesign.listTime(tue, now, z, Locale.GERMANY))
        assertTrue(ChatDesign.listTime(old, now, z, Locale.GERMANY).startsWith("01.09."))
    }

    @Test
    fun callControlRows() {
        assertEquals(1, CallLayout.controlRows(6, 1f, flat = false, widthDp = 411f))
        assertEquals(2, CallLayout.controlRows(6, 1.5f, flat = false, widthDp = 411f))      // grosse Schrift
        assertEquals(2, CallLayout.controlRows(6, 1f, flat = false, widthDp = 320f))        // schmal
        assertEquals(1, CallLayout.controlRows(6, 2f, flat = true, widthDp = 900f))         // quer: ohne Beschriftung
        assertEquals(1, CallLayout.controlRows(4, 2f, flat = false, widthDp = 320f))        // wenige Knoepfe
    }
}
