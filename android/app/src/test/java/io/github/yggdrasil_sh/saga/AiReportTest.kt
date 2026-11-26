package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.AiChunk
import io.github.yggdrasil_sh.saga.ai.AiLogic
import io.github.yggdrasil_sh.saga.ai.AiReplyCheck
import io.github.yggdrasil_sh.saga.ai.AiReportLogic
import io.github.yggdrasil_sh.saga.ai.JobPlan
import io.github.yggdrasil_sh.saga.ai.PlanStep
import io.github.yggdrasil_sh.saga.chat.ChatStd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.6 (Bugs-DB 2507): KI-Ausgaben melden - POST /api/ai/report {where, reason, text?, page?} wie das Web (ai.js reportBtn) */
class AiReportTest {
    @Test fun bodyAsk() {
        val b = AiReportLogic.body(AiReportLogic.ask("Antwort [1]"), "abuse", "  Antwort [1]  ")
        assertEquals(setOf("where", "reason", "text"), b.keys)
        assertEquals("ask", b["where"]!!.jsonPrimitive.content)
        assertEquals("abuse", b["reason"]!!.jsonPrimitive.content)
        assertEquals("Antwort [1]", b["text"]!!.jsonPrimitive.content)
        // ohne mitgeschickten Text (Schalter aus) bzw. leer: kein Feld text
        assertFalse("text" in AiReportLogic.body(AiReportLogic.ask("x"), "spam", null))
        assertFalse("text" in AiReportLogic.body(AiReportLogic.ask("x"), "spam", "   "))
        // unbekannter Grund -> other; Text auf 2000 Zeichen gekuerzt
        val long = AiReportLogic.body(AiReportLogic.ask("x"), "boese", "y".repeat(5000))
        assertEquals("other", long["reason"]!!.jsonPrimitive.content)
        assertEquals(AiReportLogic.TEXT_MAX, long["text"]!!.jsonPrimitive.content.length)
        assertEquals(listOf("spam", "abuse", "illegal", "other"), AiReportLogic.REASONS)
        assertEquals(ChatStd.REPORT_REASONS, AiReportLogic.REASONS)
        assertEquals(ChatStd.REPORT_TEXT_MAX, AiReportLogic.TEXT_MAX)
    }

    @Test fun bodyPageAndJob() {
        val p = AiReportLogic.body(AiReportLogic.page(42, "Zusammenfassung"), "illegal", "Zusammenfassung")
        assertEquals("page", p["where"]!!.jsonPrimitive.content)
        assertEquals(42L, p["page"]!!.jsonPrimitive.long)
        val j = AiReportLogic.body(AiReportLogic.job(7, "Plan"), "spam", null)
        assertEquals("job", j["where"]!!.jsonPrimitive.content)
        assertEquals(7, j["page"]!!.jsonPrimitive.int)
        assertFalse("ask ohne page", "page" in AiReportLogic.body(AiReportLogic.ask("x"), "spam", null))
        // meldbar nur mit gueltiger ID - Seiten anderer Server (negative IDs) bzw. leerer Plan nicht
        assertTrue(AiReportLogic.ok(AiReportLogic.page(1, "x")))
        assertFalse(AiReportLogic.ok(AiReportLogic.page(-3, "x")))
        assertFalse(AiReportLogic.ok(AiReportLogic.job(0, "x")))
        assertTrue(AiReportLogic.ok(AiReportLogic.ask("x")))
        assertEquals("abc", AiReportLogic.prefill("  abc \n"))
    }

    @Test fun planText() {
        val p = JobPlan(job = 3, steps = listOf(PlanStep(text = "Aufgabe anlegen"), PlanStep(text = "Eintragen", wording = "**Grillkohle**")))
        assertEquals("Aufgabe anlegen\nEintragen\n**Grillkohle**", AiReportLogic.planText(p))
    }

    @Test fun chatOwnAiAnswerOnly() {
        assertTrue(ChatStd.aiReportable(true, AiReplyCheck.OK, true, true))
        assertFalse("fremde Nachrichten: normales Melden", ChatStd.aiReportable(false, AiReplyCheck.OK, true, true))
        assertFalse("Beleg passt nicht", ChatStd.aiReportable(true, AiReplyCheck.BAD, true, true))
        assertFalse("keine KI-Antwort", ChatStd.aiReportable(true, AiReplyCheck.NONE, true, true))
        assertFalse("Server ohne report1", ChatStd.aiReportable(true, AiReplyCheck.OK, false, true))
        assertFalse("ohne Inhalt", ChatStd.aiReportable(true, AiReplyCheck.OK, true, false))
    }

    @Test fun noMatchHasNoReport() {
        val json = Json { ignoreUnknownKeys = true }
        val d = AiLogic.chunk("done", """{"quota_left":3,"no_match":true,"read":[]}""", json) as AiChunk.Done
        assertTrue(d.done.noMatch)
        assertFalse((AiLogic.chunk("done", """{"quota_left":3}""", json) as AiChunk.Done).done.noMatch)
        assertNull(AiLogic.chunk("status", """{"text":"x"}""", json))
    }

    @Test fun stringsAllLanguages() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val rx = Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""")
        fun load(dir: String) = rx.findAll(File(res, "$dir/strings_aireport.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        val base = load("values")
        assertEquals(6, base.size)
        for (d in listOf("values-de", "values-es", "values-ru", "values-th")) {
            val m = load(d)
            assertEquals(d, base.keys, m.keys)
            m.forEach { (k, v) -> assertTrue("$d/$k leer", v.isNotBlank()) }
        }
        assertEquals("Melden …", load("values-de")["ai_report_btn"])
    }
}
