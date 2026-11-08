package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.push.NotifyReply
import io.github.yggdrasil_sh.saga.push.NotifyReply.Fail
import io.github.yggdrasil_sh.saga.push.NotifyReply.Step
import io.github.yggdrasil_sh.saga.push.NotifyUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design-Runde 5 (CH-04): "Antworten" aus der Chat-Mitteilung - Aktionsauswahl, Kontopruefung, Fehler, Entwurf */
class R5NotifyReplyTest {
    @Test
    fun offersReplyOnlyForChatsWithKeysOnThisDevice() {
        assertEquals(7L, NotifyReply.offer("chat.message", "/chats#c7", true, ChatSetup.READY))
        assertEquals(7L, NotifyReply.offer("chat.mention", "/chats#c7", true, ChatSetup.READY))
        // Hintergrund: Stand noch nicht geladen -> anbieten, der Auftrag prueft beim Senden
        assertEquals(7L, NotifyReply.offer("chat.message", "/chats#c7", true, ChatSetup.LOADING))
        assertEquals(7L, NotifyReply.offer("chat.message", "/chats#c7", true, ChatSetup.ERROR))
        // keine Schluessel ("Chats auf diesem Geraet" nicht eingerichtet, Geraetegrenze)
        assertNull(NotifyReply.offer("chat.message", "/chats#c7", false, ChatSetup.READY))
        for (s in listOf(ChatSetup.NEW_DEVICE, ChatSetup.NEW_ACCOUNT, ChatSetup.OFF, ChatSetup.UNSUPPORTED)) {
            assertNull(s.name, NotifyReply.offer("chat.message", "/chats#c7", true, s))
        }
        // andere Arten, kein Chat in der url
        assertNull(NotifyReply.offer("comment.new", "/notes#5", true, ChatSetup.READY))
        assertNull(NotifyReply.offer("call.missed", "/chats#c7", true, ChatSetup.READY))
        assertNull(NotifyReply.offer("chat.message", "/", true, ChatSetup.READY))
    }

    @Test
    fun cleansText() {
        assertNull(NotifyReply.clean(null))
        assertNull(NotifyReply.clean("   \n "))
        assertEquals("Hallo", NotifyReply.clean("  Hallo \n"))
        assertEquals(NotifyReply.MAX_LEN, NotifyReply.clean("x".repeat(NotifyReply.MAX_LEN + 10))!!.length)
    }

    @Test
    fun checksAccountAndTakeOverBeforeSending() {
        assertEquals(Step.SEND, NotifyReply.before("a1", "a1", false, null, "hi"))
        assertEquals(Step.WRONG_ACCOUNT, NotifyReply.before("a1", "b2", false, null, "hi"))
        assertEquals(Step.WRONG_ACCOUNT, NotifyReply.before("", "", false, null, "hi"))
        assertEquals(Step.WRONG_ACCOUNT, NotifyReply.before(null, "a1", false, null, "hi"))
        // im Chat geparkt: steht noch da -> weiter senden; im Chat gesendet/geloescht -> uebernommen
        assertEquals(Step.SEND, NotifyReply.before("a1", "a1", true, "hi", "hi"))
        assertEquals(Step.SEND, NotifyReply.before("a1", "a1", true, "alt\nhi", "hi"))
        assertEquals(Step.TAKEN_OVER, NotifyReply.before("a1", "a1", true, null, "hi"))
        assertEquals(Step.TAKEN_OVER, NotifyReply.before("a1", "a1", true, "hi, geaendert", "hi"))
    }

    @Test
    fun classifiesFailures() {
        assertEquals(Fail.RETRY, NotifyReply.classify(null, true, 0))
        assertEquals(Fail.RETRY, NotifyReply.classify(503, false, 0))
        assertEquals(Fail.RETRY, NotifyReply.classify(429, false, 2))
        assertEquals(Fail.RETRY, NotifyReply.classify(408, false, 0))
        assertEquals(Fail.GIVE_UP, NotifyReply.classify(403, false, 0))
        assertEquals(Fail.GIVE_UP, NotifyReply.classify(404, false, 0))
        assertEquals(Fail.GIVE_UP, NotifyReply.classify(409, false, 0))
        // ohne HTTP-Code und ohne Netzfehler: Schluessel/Sicherheitsnummer/Empfaenger -> aufgeben
        assertEquals(Fail.GIVE_UP, NotifyReply.classify(null, false, 0))
        // letzter Versuch
        assertEquals(Fail.GIVE_UP, NotifyReply.classify(null, true, NotifyReply.MAX_ATTEMPTS - 1))
        assertEquals(Fail.RETRY, NotifyReply.classify(null, true, NotifyReply.MAX_ATTEMPTS - 2))
    }

    @Test
    fun parksTextInDraftAndTakesItBackAfterSending() {
        assertEquals("hi", NotifyReply.park(null, "hi"))
        assertEquals("hi", NotifyReply.park("  ", "hi"))
        assertEquals("Entwurf\nhi", NotifyReply.park("Entwurf \n", "hi"))
        assertEquals("Entwurf\nhi", NotifyReply.park("Entwurf\nhi", "hi"))         // nicht doppelt
        assertTrue(NotifyReply.stillParked("Entwurf\nhi", "hi"))
        assertFalse(NotifyReply.stillParked("Entwurf", "hi"))
        assertFalse(NotifyReply.stillParked(null, "hi"))
        assertNull(NotifyReply.unpark("hi", "hi"))
        assertNull(NotifyReply.unpark(null, "hi"))
        assertEquals("Entwurf", NotifyReply.unpark("Entwurf\nhi", "hi"))
        assertEquals("anders", NotifyReply.unpark("anders", "hi"))
    }

    @Test
    fun jobRoundTripsWithoutLeakingIntoWorkData() {
        val j = NotifyReply.Job("id1", "a1", 7, "Hallo", "chat-7", "/chats#c7", "chat:7", 123, parked = true)
        assertEquals(j, NotifyReply.decode(NotifyReply.encode(j)))
        assertEquals(j.copy(group = null), NotifyReply.decode(NotifyReply.encode(j.copy(group = null))))
        assertNull(NotifyReply.decode("kaputt"))
        assertNull(NotifyReply.decode(null))
    }

    @Test
    fun ownReplyLineIsKeptByDeleteUpdates() {
        assertEquals(listOf(1, 2, NotifyReply.OWN_LINE), NotifyReply.countsAfterReply(listOf(1, 2), 6))
        assertEquals(listOf(2, 3, 4, 5, 6, NotifyReply.OWN_LINE), NotifyReply.countsAfterReply(listOf(1, 2, 3, 4, 5, 6), 6))
        // Nachricht geloescht (n 1 statt 2): die eigene Zeile bleibt, gekuerzt wird nur die fremde
        val lines = listOf(NotifyUpdate.Line(2, "Bruno"), NotifyUpdate.Line(NotifyReply.OWN_LINE, null))
        assertEquals(NotifyUpdate.Plan.Lines(listOf(0 to 1, 1 to NotifyReply.OWN_LINE)), NotifyUpdate.lines(lines, "chat.message", 1, "Bruno"))
        // alle fremden geloescht: nur die eigene Antwort uebrig -> Mitteilung schliessen
        assertEquals(NotifyUpdate.Plan.Cancel, NotifyUpdate.lines(lines, "chat.message", 0, "Bruno"))
        // Erwaehnungen zaehlen die eigene Zeile ebenfalls nicht
        assertEquals(NotifyUpdate.Plan.Keep, NotifyUpdate.lines(listOf(NotifyUpdate.Line(NotifyReply.OWN_LINE, null)), "chat.mention", 0, null))
        // naechste fremde Nachricht nach der Antwort: neue Zeile, eigene bleibt
        assertEquals(listOf(2, NotifyReply.OWN_LINE, 1), NotifyUpdate.nextCounts(listOf(2, NotifyReply.OWN_LINE), false, 1, 6))
    }

    @Test
    fun unclearFailuresAreCheckedBeforeRetrying() {
        // vielleicht angekommen: Abbruch/Zeitueberschreitung nach Verbindungsaufbau, 408, 5xx
        assertTrue(NotifyReply.unclear(null, true, false))
        assertTrue(NotifyReply.unclear(408, false, false))
        assertTrue(NotifyReply.unclear(502, false, false))
        // klar nicht angekommen: keine Verbindung, 429, andere Antworten, lokale Fehler
        assertFalse(NotifyReply.unclear(null, true, true))
        assertFalse(NotifyReply.unclear(429, false, false))
        assertFalse(NotifyReply.unclear(403, false, false))
        assertFalse(NotifyReply.unclear(null, false, false))
    }

    @Test
    fun detectsOwnMessageWithSameTextSinceFirstAttempt() {
        val since = 1_000_000L
        val mine = NotifyReply.Seen(true, "Bin gleich da", since + 300)
        assertTrue(NotifyReply.alreadySent(listOf(mine), "Bin gleich da", since))
        assertTrue(NotifyReply.alreadySent(listOf(mine.copy(text = " Bin gleich da\n")), "Bin gleich da", since))
        // Uhr gerundet: knapp vor dem Beginn zaehlt noch
        assertTrue(NotifyReply.alreadySent(listOf(mine.copy(ts = since - 1_000)), "Bin gleich da", since))
        // fremde Nachricht, anderer Text, aeltere eigene Nachricht, nicht entschluesselbar, leer
        assertFalse(NotifyReply.alreadySent(listOf(mine.copy(mine = false)), "Bin gleich da", since))
        assertFalse(NotifyReply.alreadySent(listOf(mine.copy(text = "Bin gleich da!")), "Bin gleich da", since))
        assertFalse(NotifyReply.alreadySent(listOf(mine.copy(ts = since - 60_000)), "Bin gleich da", since))
        assertFalse(NotifyReply.alreadySent(listOf(mine.copy(text = null)), "Bin gleich da", since))
        assertFalse(NotifyReply.alreadySent(emptyList(), "Bin gleich da", since))
    }

    @Test
    fun jobKeepsFirstAttemptAndUnclearFlag() {
        val j = NotifyReply.Job("id1", "a1", 7, "Hallo", "chat-7", "/chats#c7", since = 42, unclear = true)
        assertEquals(j, NotifyReply.decode(NotifyReply.encode(j)))
        // Auftrag aus dem Stand davor (ohne die Felder)
        val old = NotifyReply.decode("""{"id":"x","account":"a","chat":1,"text":"t","tag":"g","url":"/chats#c1"}""")!!
        assertEquals(0L, old.since)
        assertFalse(old.unclear)
    }
}
