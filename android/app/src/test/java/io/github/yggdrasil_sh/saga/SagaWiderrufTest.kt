package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.AiConsent
import io.github.yggdrasil_sh.saga.ai.AiIntro
import io.github.yggdrasil_sh.saga.ai.AiStatus
import io.github.yggdrasil_sh.saga.ai.ReportLine
import io.github.yggdrasil_sh.saga.ai.SagaLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bugs-DB 2518: Auftraege nach Widerruf erreichbar (A1), Rueckmeldung mit einer Aenderung nur mit Knopf (N2) */
class SagaWiderrufTest {
    private fun st(state: String, jobs: Boolean = true) =
        AiStatus(enabled = true, caps = listOf("ai"), jobs = jobs, level = "plans", consent = AiConsent(state), intro = AiIntro(version = "k19-1"))

    @Test fun pausedJobsReachableWithoutConsent() {
        for (s in listOf("withdrawn", "stale", "none", "later")) {
            assertTrue(s, SagaLogic.pausedJobsShown(st(s), 2))
            assertFalse("$s ohne Auftraege: nur die Karte", SagaLogic.pausedJobsShown(st(s), 0))
        }
        assertFalse("mit Einwilligung: normaler Bereich", SagaLogic.pausedJobsShown(st("granted"), 3))
        assertFalse("Server ohne Auftraege", SagaLogic.pausedJobsShown(st("withdrawn", jobs = false), 3))
        assertFalse(SagaLogic.pausedJobsShown(null, 3))
        assertEquals(SagaLogic.PausedNote.WITHDRAWN, SagaLogic.pausedNote(st("withdrawn")))
        assertEquals(SagaLogic.PausedNote.STALE, SagaLogic.pausedNote(st("stale")))
        assertEquals(SagaLogic.PausedNote.NOT_MET, SagaLogic.pausedNote(st("later")))
        assertEquals(SagaLogic.PausedNote.NOT_MET, SagaLogic.pausedNote(st("none")))
    }

    @Test fun reportLineOnlyButtonForSingleChange() {
        val a = ReportLine(change = 1, text = "Aufgabe „X“ angelegt", label = "Aufgabe „X“", canUndo = true)
        val b = ReportLine(change = 2, text = "Termin „Y“ eingetragen", canUndo = true)
        assertNull("eine Aenderung: nur der Knopf", SagaLogic.reportLineText(1, a))
        assertEquals("Aufgabe „X“", SagaLogic.reportLineText(2, a))
        assertEquals("ohne label der Satz der Zeile", "Termin „Y“ eingetragen", SagaLogic.reportLineText(2, b))
        assertNull(SagaLogic.reportLineText(2, ReportLine(change = 3, text = "", label = " ")))
    }

    @Test fun pausedTextsAllLanguages() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        for (d in listOf("values", "values-de", "values-es", "values-ru", "values-th")) {
            val x = File(res, "$d/strings_saga.xml").readText()
            for (k in listOf("saga_paused_withdrawn", "saga_paused_stale", "saga_paused_not_met")) {
                assertTrue("$d/$k", Regex("""<string name="$k">[^<]*%1\${'$'}s[^<]*</string>""").containsMatchIn(x))
            }
        }
    }
}
