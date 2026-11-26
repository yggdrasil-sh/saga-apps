package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.JobDetail
import io.github.yggdrasil_sh.saga.ai.JobPlan
import io.github.yggdrasil_sh.saga.ai.JobReport
import io.github.yggdrasil_sh.saga.ai.PlanStep
import io.github.yggdrasil_sh.saga.ai.RefuseAlternative
import io.github.yggdrasil_sh.saga.ai.SagaLogic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.6 W2 (APP-API-KI 11.7, web-saga): Auftraege aus dem Gespraech ("Als Auftrag", thread: true) und ihre Zuege */
class SagaThreadJobsTest {
    private val json = SagaLogic.json

    @Test fun giveWithThread() {
        val b = SagaLogic.giveBody("Erinnere mich Freitag an den Pavillon", thread = true)
        assertEquals(setOf("text", "thread"), b.keys)
        assertTrue(b["thread"]!!.jsonPrimitive.boolean)
        assertFalse("ohne Umschalter: kein thread", "thread" in SagaLogic.giveBody("x"))
        val c = SagaLogic.giveBody("Frag Ben", choice = "Ben B.", note = 4, thread = true)
        assertEquals("Ben B.", c["choice"]!!.jsonPrimitive.content)
        assertEquals("4", c["context"]!!.jsonObject["note"]!!.jsonPrimitive.content)
        assertFalse("Seiten anderer Server (negativ) nie als Kontext", "context" in SagaLogic.giveBody("x", note = -2))
        val r = SagaLogic.recipeBody("Frag Ben", RefuseAlternative("aufgabe", null, "Als Aufgabe anlegen"), thread = true)
        assertTrue(r["thread"]!!.jsonPrimitive.boolean)
        assertEquals("aufgabe", r["recipe"]!!.jsonPrimitive.content)
        assertFalse("thread" in SagaLogic.recipeBody("x", RefuseAlternative("aufgabe", null, null)))
    }

    @Test fun threadTurns() {
        val t = SagaLogic.thread(
            """{"turns":[{"n":1,"role":"me","zug":"job","text":"Frag Ben wegen Grillkohle"},""" +
                """{"n":2,"role":"ai","zug":"ask","text":"Welcher Ben?","fields":{"choices":["Ben A.","Ben B."],"q":"Frag Ben wegen Grillkohle"}},""" +
                """{"n":3,"role":"me","zug":"job","text":"Ben B."},""" +
                """{"n":4,"role":"ai","zug":"plan","text":"Plan: Grillkohle","job_id":12},""" +
                """{"n":5,"role":"ai","zug":"refuse","text":"Das bekomme ich hier nicht sicher hin.","fields":{"alternative":{"recipe":"aufgabe","fields":{"text":"Ben fragen"},"label":"Als Aufgabe anlegen"},"q":"Bestell Kohle"}},""" +
                """{"n":6,"role":"ai","zug":"plan","text":"Plan: ohne Kennung"},""" +
                """{"n":7,"role":"ai","zug":"answer","text":"Antwort"}],"history":false,"keep":86400}""",
        )
        assertEquals(listOf(false, true, false, true, true, false, false), t.turns.map { SagaLogic.isJobTurn(it) })
        assertEquals(listOf("Ben A.", "Ben B."), SagaLogic.turnChoices(t.turns[1]))
        assertEquals("Frag Ben wegen Grillkohle", SagaLogic.turnRequest(t.turns[1]))
        assertEquals(12L, t.turns[3].jobId)
        val alt = SagaLogic.turnAlternative(t.turns[4])!!
        assertEquals("aufgabe", alt.recipe)
        assertEquals("Als Aufgabe anlegen", alt.label)
        assertEquals("Bestell Kohle", SagaLogic.turnRequest(t.turns[4]))
        assertNull(SagaLogic.turnAlternative(t.turns[6]))
        assertTrue(SagaLogic.turnChoices(t.turns[6]).isEmpty())
    }

    @Test fun jobViewByState() {
        val plan = JobPlan(job = 1, steps = listOf(PlanStep(text = "x")))
        assertEquals(SagaLogic.JobView.PLAN, SagaLogic.jobView(JobDetail(id = 1, state = "wartet_ok", plan = plan)))
        assertEquals("wartet_ok ohne Schritte -> Zustand", SagaLogic.JobView.STATE, SagaLogic.jobView(JobDetail(id = 1, state = "wartet_ok", plan = JobPlan())))
        assertEquals(SagaLogic.JobView.REPORT, SagaLogic.jobView(JobDetail(id = 1, state = "erledigt", report = JobReport("ok"))))
        assertEquals(SagaLogic.JobView.REPORT, SagaLogic.jobView(JobDetail(id = 1, state = "zurueckgenommen")))
        for (s in listOf("laeuft", "wartet_dich", "fehlgeschlagen", "abgebrochen", "neu")) assertEquals(s, SagaLogic.JobView.STATE, SagaLogic.jobView(JobDetail(id = 1, state = s)))
    }

    @Test fun undoAlreadyAndLabel() {
        val gone = json.parseToJsonElement("""{"ok":true,"already":true,"text":"Schon erledigt – den Termin gibt es nicht mehr."}""") as JsonObject
        assertEquals("Schon erledigt – den Termin gibt es nicht mehr.", SagaLogic.undoText(gone))
        assertNull(SagaLogic.undoText(json.parseToJsonElement("""{"ok":true}""") as JsonObject))
        assertNull(SagaLogic.undoText(json.parseToJsonElement("""{"ok":true,"already":true}""") as JsonObject))
        val d = json.decodeFromString(
            JobDetail.serializer(),
            """{"id":3,"state":"erledigt","report":{"text":"Erledigt.","lines":[{"change":5,"text":"Aufgabe „X“ angelegt","label":"Aufgabe „X“","undone":false,"can_undo":true},""" +
                """{"change":6,"text":"Termin „Y“ eingetragen","undone":true,"can_undo":false}]}}""",
        )
        assertEquals("Aufgabe „X“", d.report!!.lines[0].label)
        assertNull("aeltere Server: ohne label", d.report!!.lines[1].label)
    }

    /** Begriffe (11.7): Auftrag = en Job, es Encargo, ru Поручение - Aufgabe bleibt Task/Tarea/Задача */
    @Test fun wording() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val rx = Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""")
        fun load(dir: String) = rx.findAll(File(res, "$dir/strings_saga.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("Jobs", load("values")["saga_tab_jobs"])
        assertEquals("Job", load("values")["saga_job"])
        assertEquals("As a job", load("values")["saga_as_job"])
        assertEquals("Encargos", load("values-es")["saga_tab_jobs"])
        assertEquals("Поручение", load("values-ru")["saga_job"])
        assertEquals("Als Auftrag", load("values-de")["saga_as_job"])
        assertTrue("Aufgabe bleibt Task", load("values")["saga_as_task"]!!.contains("task"))
    }
}
