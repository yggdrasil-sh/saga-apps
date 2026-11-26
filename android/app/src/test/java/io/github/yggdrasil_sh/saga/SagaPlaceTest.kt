package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.AiIntro
import io.github.yggdrasil_sh.saga.ai.AiStatus
import io.github.yggdrasil_sh.saga.ai.JobGroup
import io.github.yggdrasil_sh.saga.ai.JobItem
import io.github.yggdrasil_sh.saga.ai.JobList
import io.github.yggdrasil_sh.saga.ai.JobPlan
import io.github.yggdrasil_sh.saga.ai.JobStreamEvent
import io.github.yggdrasil_sh.saga.ai.SagaLogic
import io.github.yggdrasil_sh.saga.live.SseParser
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.nav.NavModel
import io.github.yggdrasil_sh.saga.ui.settings.SettingsArea
import io.github.yggdrasil_sh.saga.ui.settings.SettingsLogic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.6 Ort "Saga" in der Android-App (Bugs-DB 2269 K-I Apps, 2267 K-B): Faehigkeit, Einwilligung, Gruppen, SSE,
 * Freigabe mit plan_v + plan_hash, 409-Faelle, Navigation, Texte. Formen wie Server main (docs/APP-API-KI.md 9-11).
 */
class SagaPlaceTest {
    private val json = SagaLogic.json
    private val hash = "a".repeat(64)

    private val intro = """{"version":"k19-1","host":"ollama.lan:11434","lines":["Ich bin Marin, die KI eures Servers.",""" +
        """"Ich sehe nur, was du sehen darfst. … gehen an ollama.lan:11434 (im Heimnetz).","Ich ändere nichts und schreibe niemandem ohne dein OK."],""" +
        """"details":["Nicht Ende-zu-Ende verschlüsselt: …","Was genau rausgeht: …"],"ideas_allowed":true,"question":"Soll ich dir ab und zu Ideen zeigen?",""" +
        """"bar":"Was du hier schreibst, liest die KI auf ollama.lan:11434 (im Heimnetz)"}"""

    private fun status(extra: String) = json.decodeFromString(
        AiStatus.serializer(),
        """{"enabled":true,"host":"ollama.lan:11434","notice":"…","persona":{"name":"Marin","custom_name":true,"avatar":null},$extra}""",
    )

    private val full = status(
        """"caps":["ai","ai_act","ai_ideas"],"units":{"left":1,"of":2},"style":{"label":"Marin · KI","form":"sie","lively":"warm","salutation":"du"},""" +
            """"jobs":true,"level":"plans","endpoint":{"host":"ollama.lan:11434","net":"home","label":"ollama.lan:11434 (im Heimnetz)"},""" +
            """"consent":{"state":"none"},"intro":$intro""",
    )

    // ------------------------------------------------------------------ Bereich nur mit Faehigkeit

    @Test fun areaOnlyWithCapability() {
        assertTrue(SagaLogic.available(full))
        assertEquals("Marin · KI", SagaLogic.label(full))
        assertTrue(SagaLogic.jobsOn(full))
        // aelterer Server ohne K-I-Felder: Bereich unsichtbar
        assertFalse(SagaLogic.available(status(""""caps":["ai"]""")))
        assertFalse(SagaLogic.available(status(""""caps":["ai"],"consent":{"state":"granted"}""")))
        // Assistent fuer das Konto aus ({enabled:false}) bzw. noch nicht geladen
        assertFalse(SagaLogic.available(json.decodeFromString(AiStatus.serializer(), """{"enabled":false}""")))
        assertFalse(SagaLogic.available(null))
        // caps ohne "ai" (Gruppe ausgenommen) -> unsichtbar; caps fehlt -> nach enabled
        assertFalse(SagaLogic.available(full.copy(caps = listOf("ai_act"))))
        assertTrue(SagaLogic.available(full.copy(caps = null)))
        // Auftraege nur mit jobs und Stufe != answers
        assertFalse(SagaLogic.jobsOn(full.copy(jobs = false)))
        assertFalse(SagaLogic.jobsOn(full.copy(level = "answers")))
        assertFalse(SagaLogic.jobsOn(full.copy(jobs = null)))
        // Endpunkt nicht eingerichtet: endpoint null, Bereich bleibt (Karte nennt dann keinen Host)
        val noEp = status(""""caps":["ai"],"endpoint":null,"consent":{"state":"none"},"intro":$intro""")
        assertNull(noEp.endpoint)
        assertTrue(SagaLogic.available(noEp))
        assertNull("ohne style.label baut die App selbst", SagaLogic.label(noEp))
    }

    @Test fun introStatesAndAnswers() {
        assertTrue(SagaLogic.introOnToday(full))
        for (s in listOf("none", "stale")) assertTrue(s, SagaLogic.introOnToday(full.copy(consent = full.consent!!.copy(state = s))))
        for (s in listOf("later", "granted", "withdrawn")) assertFalse(s, SagaLogic.introOnToday(full.copy(consent = full.consent!!.copy(state = s))))
        assertFalse(SagaLogic.consentOk(full))
        assertTrue(SagaLogic.consentOk(full.copy(consent = full.consent!!.copy(state = "granted"))))
        assertFalse("stale gilt nicht", SagaLogic.consentOk(full.copy(consent = full.consent!!.copy(state = "stale"))))
        val i = full.intro!!
        assertEquals(listOf(SagaLogic.IntroAnswer.IDEAS, SagaLogic.IntroAnswer.NO_IDEAS, SagaLogic.IntroAnswer.LATER), SagaLogic.introAnswers(i))
        val noIdeas = i.copy(ideasAllowed = false, question = null)
        assertEquals(listOf(SagaLogic.IntroAnswer.AGREE, SagaLogic.IntroAnswer.LATER), SagaLogic.introAnswers(noIdeas))
        assertEquals("no_ideas", SagaLogic.IntroAnswer.AGREE.wire)
        // Einwilligung mit genau Fassung und Host der gezeigten Karte (409 sonst)
        val b = SagaLogic.consentBody(SagaLogic.IntroAnswer.IDEAS, i)
        assertEquals("ideas", b["answer"]!!.jsonPrimitive.content)
        assertEquals("k19-1", b["version"]!!.jsonPrimitive.content)
        assertEquals("ollama.lan:11434", b["host"]!!.jsonPrimitive.content)
        assertEquals("Heimnetz-Text kommt nur vom Server", "Was du hier schreibst, liest die KI auf ollama.lan:11434 (im Heimnetz)", i.bar)
        // Anrede-Auswahl nicht im Englischen
        assertFalse(SagaLogic.salutationShown("en"))
        assertTrue(SagaLogic.salutationShown("de"))
        assertTrue(SagaLogic.salutationShown("th"))
    }

    // ------------------------------------------------------------------ Zustaende und Gruppen (K2)

    @Test fun statesToGroups() {
        val m = mapOf(
            "wartet_ok" to "wartet_auf_dich", "wartet_dich" to "wartet_auf_dich", "laeuft" to "laeuft", "erledigt" to "fertig",
            "abgebrochen" to "fertig", "zurueckgenommen" to "fertig", "fehlgeschlagen" to "nicht_geklappt",
        )
        m.forEach { (s, g) -> assertEquals(s, g, SagaLogic.groupOf(s)) }
        assertNull(SagaLogic.groupOf("neu_in_3_0"))
        assertEquals(listOf("wartet_auf_dich", "laeuft", "fertig", "nicht_geklappt"), SagaLogic.GROUPS)
        assertEquals("!", SagaLogic.groupSymbol("nicht_geklappt"))
        assertEquals("◷", SagaLogic.groupSymbol("laeuft"))
        assertEquals("✓", SagaLogic.groupSymbol("fertig"))
        assertEquals("●", SagaLogic.groupSymbol("wartet_auf_dich"))
        assertEquals("◎", SagaLogic.kindSymbol("read"))
        assertEquals("✎", SagaLogic.kindSymbol("own"))
        assertEquals("↗", SagaLogic.kindSymbol("out"))
    }

    @Test fun groupsAlwaysFourInOrder() {
        val body = """{"groups":[{"key":"fertig","label":"Fertig","items":[{"id":3,"title":"Pavillon","state":"erledigt","state_text":"Erledigt","line":""}]},""" +
            """{"key":"wartet_auf_dich","label":"Wartet auf dich","items":[{"id":1,"title":"A","state":"wartet_ok","state_text":"Wartet auf dein OK","line":"Wartet auf dein OK","plan_v":1}]},""" +
            """{"key":"x","label":"?","items":[{"id":9,"title":"Z","state":"fehlgeschlagen","state_text":"Hat nicht geklappt"},{"id":10,"state":"unbekannt"}]}],""" +
            """"counts":{"wartet_auf_dich":1,"laeuft":0,"fertig":1,"nicht_geklappt":0}}"""
        val g = SagaLogic.groups(json.decodeFromString(JobList.serializer(), body))
        assertEquals(SagaLogic.GROUPS, g.map { it.key })
        assertEquals(listOf(1L), g[0].items.map { it.id })
        assertEquals("Wartet auf dich", g[0].label)
        assertTrue("fehlt -> leer", g[1].items.isEmpty())
        assertEquals("", g[1].label)
        assertEquals(listOf(3L), g[2].items.map { it.id })
        assertEquals("unbekannte Gruppe: Zustand entscheidet", listOf(9L), g[3].items.map { it.id })
        assertEquals(3, SagaLogic.total(g))
        assertEquals(0, SagaLogic.total(SagaLogic.groups(JobList())))
        assertEquals(4, SagaLogic.groups(JobList()).size)
    }

    @Test fun lineWithoutDoubleWord() {
        fun it(st: String, line: String?) = JobItem(id = 1, state = "x", stateText = st, line = line)
        assertEquals("Läuft", SagaLogic.lineText(it("Läuft", "")))
        assertEquals("Läuft", SagaLogic.lineText(it("Läuft", null)))
        assertEquals("Wartet auf dein OK", SagaLogic.lineText(it("Wartet auf dein OK", "Wartet auf dein OK")))
        assertEquals("Läuft · zurückgestellt bis morgen", SagaLogic.lineText(it("Läuft", "zurückgestellt bis morgen")))
        assertEquals("Läuft – x", SagaLogic.lineText(it("Läuft", "Läuft – x")))
    }

    // ------------------------------------------------------------------ SSE (POST /api/ai/jobs)

    private fun events(raw: String): List<JobStreamEvent> {
        val p = SseParser()
        return (raw.split("\n") + "").mapNotNull { p.feed(it) }.mapNotNull { SagaLogic.jobEvent(it.event, it.data) }
    }

    private val planJson = """{"job":7,"plan_v":2,"plan_hash":"$hash","title":"Pavillon","state":"wartet_ok",""" +
        """"steps":[{"n":0,"tool":"create_task","klass":"own","kind_text":"Eigenes ändern","undo_class":"undo","class_text":"rückgängig",""" +
        """"text":"Aufgabe „Pavillon“ anlegen","wording":null,"edit":["text","due"],"fields":{"text":"Pavillon","due":"2026-07-10"},"state":"offen"},""" +
        """{"n":1,"tool":"read_page","klass":"read","kind_text":"Lesen","undo_class":null,"class_text":"","text":"Seite lesen","edit":[]}],""" +
        """"read":[{"ref":"note:4","label":"Sommerfest"}],"read_text":"Ich lese dafür: Sommerfest","wording":[null,null],"classes":["undo",null],"say":"So würde ich es machen:","recipe":null}"""

    @Test fun sseJobStream() {
        val raw = ":" + " ".repeat(2048) + "\n\n" +
            "event: status\ndata: {\"phase\":\"planning\",\"text\":\"Marin denkt nach …\"}\n\n" +
            ": ping\n\n" +
            "event: plan\r\ndata: $planJson\r\n\r\n"
        val ev = events(raw)
        assertEquals(2, ev.size)
        assertEquals("Marin denkt nach …", (ev[0] as JobStreamEvent.Status).text)
        val p = (ev[1] as JobStreamEvent.Plan).plan
        assertEquals(7L, p.job)
        assertEquals(2, p.planV)
        assertEquals(hash, p.planHash)
        assertEquals(2, p.steps.size)
        assertEquals("rückgängig", p.steps[0].classText)
        assertEquals(listOf("text", "due"), p.steps[0].edit)
        assertEquals("Ich lese dafür: Sommerfest", p.readText)
        assertEquals("Sommerfest", p.read[0].label)
        assertTrue(SagaLogic.canEdit(p))
        assertTrue(SagaLogic.approvable(p))
        // Rueckfrage, Absage mit Rezept, Fehler, Unbekanntes, Kaputtes
        val more = events(
            "event: ask\ndata: {\"question\":\"Welcher Ben?\",\"choices\":[\"Ben (saga.example)\",\"Ben B.\"]}\n\n" +
                "event: refuse\ndata: {\"zug\":\"cant\",\"case\":\"x\",\"text\":\"Das bekomme ich hier nicht sicher hin.\",\"alternative\":{\"recipe\":\"aufgabe\",\"fields\":{\"text\":\"Ben fragen\"},\"label\":\"Als Aufgabe anlegen\"}}\n\n" +
                "event: error\ndata: {\"status\":500,\"error\":\"Das hat nicht geklappt – nichts wurde geändert.\"}\n\n" +
                "event: neu\ndata: {}\n\n" +
                "event: plan\ndata: {kaputt\n\n",
        )
        assertEquals(4, more.size)
        val ask = more[0] as JobStreamEvent.Ask
        assertEquals(listOf("Ben (saga.example)", "Ben B."), ask.choices)
        val ref = more[1] as JobStreamEvent.Refuse
        assertEquals("aufgabe", ref.alternative!!.recipe)
        assertEquals("Ben fragen", ref.alternative!!.fields!!["text"]!!.jsonPrimitive.content)
        val body = SagaLogic.recipeBody("Frag Ben", ref.alternative!!)
        assertTrue("nur die Kennung, nie ein Rezept-Objekt", body["recipe"] is JsonPrimitive)
        val err = more[2] as JobStreamEvent.Failed
        assertEquals(500, err.status)
        assertTrue(more[3] is JobStreamEvent.Failed)
        // mehrzeilige data-Zeilen werden mit \n verbunden
        val multi = events("event: status\ndata: {\"text\":\ndata: \"x\"}\n\n")
        assertEquals("x", (multi[0] as JobStreamEvent.Status).text)
    }

    // ------------------------------------------------------------------ Plan-Hash, Aendern, 409

    @Test fun approveCarriesPlanVersionAndHash() {
        val p = json.decodeFromString(JobPlan.serializer(), planJson)
        val b = SagaLogic.approveBody(p)
        assertEquals(setOf("plan_v", "plan_hash"), b.keys)
        assertEquals(2, b["plan_v"]!!.jsonPrimitive.int)
        assertEquals(hash, b["plan_hash"]!!.jsonPrimitive.content)
        assertFalse("ohne vollen Hash nie freigeben", SagaLogic.approvable(p.copy(planHash = "abc")))
        assertFalse(SagaLogic.approvable(p.copy(steps = emptyList())))
        assertFalse("leerer Plan ({} vom Server)", SagaLogic.approvable(json.decodeFromString(JobPlan.serializer(), "{}")))
        // Aendern: nur Felder aus steps[].edit, n = Stelle, plan_v mit
        val e = SagaLogic.editBody(p, mapOf(0 to mapOf("text" to " Pavillon holen ", "tool" to "delete_page"), 1 to mapOf("text" to "x")))
        assertEquals(2, e["plan_v"]!!.jsonPrimitive.int)
        val edits = e["edits"]!!.jsonArray
        assertEquals(1, edits.size)
        assertEquals(0, edits[0].jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(mapOf("text" to JsonPrimitive("Pavillon holen")), edits[0].jsonObject["args"]!!.jsonObject.toMap())
        assertEquals("2026-07-10", SagaLogic.fieldValue(p.steps[0], "due"))
        assertEquals("", SagaLogic.fieldValue(p.steps[1], "text"))
        val ev = p.copy(steps = listOf(p.steps[0].copy(tool = "create_event", edit = listOf("allday"))))
        assertTrue(SagaLogic.editBody(ev, mapOf(0 to mapOf("allday" to "true")))["edits"]!!.jsonArray[0].jsonObject["args"]!!.jsonObject["allday"]!!.jsonPrimitive.boolean)
    }

    @Test fun conflicts409() {
        val stale = SagaLogic.conflict("""{"error":"Der Plan hat sich inzwischen geändert – bitte noch einmal ansehen.","code":"stale","plan_v":3}""")
        assertEquals("stale", stale.code)
        assertEquals(3, stale.planV)
        val st = SagaLogic.conflict("""{"error":"Dieser Auftrag wartet nicht mehr auf dein OK.","code":"state","state":"laeuft","state_text":"Läuft"}""")
        assertEquals("state", st.code)
        assertEquals("laeuft", st.state)
        val ch = SagaLogic.conflict("""{"error":"Das wurde seitdem geändert.","code":"changed","target":"note:4","why":"changed","version_url":"/api/ai/jobs/7/changes/12/before"}""")
        assertEquals("changed", ch.code)
        assertEquals("/api/ai/jobs/7/changes/12/before", ch.versionUrl)
        assertTrue(SagaLogic.beforePathOk(ch.versionUrl, 7))
        assertNull(SagaLogic.conflict("""{"code":"changed","version_url":null}""").versionUrl)
        assertNull(SagaLogic.conflict("<html>").code)
        // "Alte Fassung" nur fuer diesen Auftrag auf dem eigenen Server
        assertFalse(SagaLogic.beforePathOk("/api/ai/jobs/8/changes/12/before", 7))
        assertFalse(SagaLogic.beforePathOk("https://evil.example/api/ai/jobs/7/changes/1/before", 7))
        assertFalse(SagaLogic.beforePathOk("/api/ai/jobs/7/changes/../../x/before", 7))
        assertFalse(SagaLogic.beforePathOk(null, 7))
        // Einwilligung fehlt: 403 {consent: needed}
        assertTrue(SagaLogic.consentNeeded(403, """{"error":"Lerne Marin zuerst kennen – vorher geht nichts an die KI.","consent":"needed"}"""))
        assertFalse(SagaLogic.consentNeeded(403, """{"error":"Der KI-Assistent ist für dich nicht eingeschaltet."}"""))
        assertFalse(SagaLogic.consentNeeded(409, """{"consent":"needed"}"""))
    }

    /** 2515: edited vom Server (Detail und plan_view, true|false) - ohne das Feld (aeltere Server) wie iOS ab plan_v 2 */
    @Test fun planChangedWithAndWithoutEdited() {
        fun plan(v: Int, edited: String?) = json.decodeFromString(
            JobPlan.serializer(),
            """{"job":7,"plan_v":$v,"plan_hash":"$hash","title":"T","state":"wartet_ok","steps":[{"n":1,"tool":"create_task","klass":"own","text":"x"}]""" +
                (edited?.let { ""","edited":$it""" } ?: "") + "}",
        )
        // mit dem Feld (neuer Server): es gilt, was der Server sagt - auch bei plan_v > 1
        assertTrue(SagaLogic.planChanged(plan(2, "true")))
        assertFalse("plan_v 3, aber nicht geaendert (Server weiss es)", SagaLogic.planChanged(plan(3, "false")))
        assertTrue("edited auch bei plan_v 1", SagaLogic.planChanged(plan(1, "true")))
        assertFalse(SagaLogic.planChanged(plan(1, "false")))
        // ohne das Feld (Server main): Rueckfall plan_v > 1 - nach 409 stale laedt das Detail ohne edited
        assertNull(plan(2, null).edited)
        assertTrue(SagaLogic.planChanged(plan(2, null)))
        assertFalse(SagaLogic.planChanged(plan(1, null)))
        assertNull("edited: null wie fehlend", plan(2, "null").edited)
        // nach 409 stale steht der Hinweis immer (nie stiller Wechsel zum neuen Plan)
        assertTrue(SagaLogic.planChanged(plan(1, null), afterStale = true))
        assertTrue(SagaLogic.planChanged(plan(3, "false"), afterStale = true))
        // Detail mit Plan (GET /api/ai/jobs/<id>), mit und ohne edited
        val d = json.decodeFromString(io.github.yggdrasil_sh.saga.ai.JobDetail.serializer(),
            """{"id":7,"state":"wartet_ok","plan":{"job":7,"plan_v":2,"plan_hash":"$hash","steps":[{"n":1,"text":"x"}],"edited":false}}""")
        assertFalse(SagaLogic.planChanged(d.plan!!))
        val d0 = json.decodeFromString(io.github.yggdrasil_sh.saga.ai.JobDetail.serializer(),
            """{"id":7,"state":"wartet_ok","plan":{"job":7,"plan_v":2,"plan_hash":"$hash","steps":[{"n":1,"text":"x"}]}}""")
        assertTrue(SagaLogic.planChanged(d0.plan!!))
    }

    /** 2515: 409 auf "Passt, los" - stale: neuer Plan mit Hinweis; state (anderswo freigegeben) und sonst: neu laden, Knopf aus */
    @Test fun approveConflictFollowUp() {
        val stale = SagaLogic.conflict("""{"error":"Der Plan hat sich inzwischen geändert.","code":"stale","plan_v":3}""")
        assertEquals(SagaLogic.ApproveConflict.STALE, SagaLogic.approveConflict(stale.code))
        val st = SagaLogic.conflict("""{"error":"Dieser Auftrag wartet nicht mehr auf dein OK.","code":"state","state":"laeuft"}""")
        assertEquals(SagaLogic.ApproveConflict.SETTLED, SagaLogic.approveConflict(st.code))
        assertEquals(SagaLogic.ApproveConflict.SETTLED, SagaLogic.approveConflict(SagaLogic.conflict("<html>").code))
    }

    /** 2515: beim (Wieder-)Verbinden einmal neu holen - nicht bei Endzustaenden */
    @Test fun resyncOnReconnect() {
        for (s in listOf(null, "wartet_ok", "laeuft", "wartet_dich", "erledigt", "neu_unbekannt")) assertTrue("$s", SagaLogic.resyncDue(s))
        for (s in listOf("abgebrochen", "zurueckgenommen", "fehlgeschlagen")) assertFalse(s, SagaLogic.resyncDue(s))
    }

    /** 2515: 409 changed ueberlebt Drehen/Prozess-Neustart; "Alte Fassung ansehen" nur mit version_url (Aufgaben ohne) */
    @Test fun undoConflictKeptAndOldVersionOnlyWithUrl() {
        val m = mapOf(12L to "/api/ai/jobs/7/changes/12/before", 13L to null)
        val saved = SagaLogic.conflictsSave(m)
        assertTrue(saved.all { it is String })
        assertEquals(m, SagaLogic.conflictsRestore(saved))
        assertEquals(emptyMap<Long, String?>(), SagaLogic.conflictsRestore(null))
        assertEquals(mapOf(5L to null), SagaLogic.conflictsRestore(listOf("5\t", "kaputt", "\t/x")))
        val task = io.github.yggdrasil_sh.saga.ai.JobChange(id = 13, target = "task:3", why = "changed", versionUrl = null)
        val note = io.github.yggdrasil_sh.saga.ai.JobChange(id = 12, target = "note:4", why = "changed", versionUrl = "/api/ai/jobs/7/changes/12/before")
        assertNull("Aufgabe: keine alte Fassung", SagaLogic.oldVersionUrl(null, task, 7))
        assertNull(SagaLogic.oldVersionUrl("", task, 7))
        assertNull(SagaLogic.oldVersionUrl(null, null, 7))
        assertEquals("/api/ai/jobs/7/changes/12/before", SagaLogic.oldVersionUrl(null, note, 7))
        assertNull("Detail kennt den Konflikt (Aufgabe ohne alte Fassung): Adresse aus dem 409 gilt nicht (aeltere Server)",
            SagaLogic.oldVersionUrl("/api/ai/jobs/7/changes/12/before", task, 7))
        assertEquals("vor dem Neuladen: Adresse aus dem 409", "/api/ai/jobs/7/changes/12/before",
            SagaLogic.oldVersionUrl("/api/ai/jobs/7/changes/12/before", task.copy(why = null), 7))
        assertEquals("409 leer, Detail hat sie", "/api/ai/jobs/7/changes/12/before", SagaLogic.oldVersionUrl("", note, 7))
        assertNull("fremder Auftrag", SagaLogic.oldVersionUrl("/api/ai/jobs/8/changes/12/before", null, 7))
        // why_text vom Server (neuere Server), auch why "done" (schon wie vorher); ohne why_text eigene Texte
        val d = json.decodeFromString(io.github.yggdrasil_sh.saga.ai.JobDetail.serializer(),
            """{"id":7,"state":"erledigt","changes":[{"id":1,"target":"task:3","why":"done","why_text":"Schon wie vorher – die Aufgabe gibt es nicht mehr.","version_url":null,"can_undo":false},""" +
                """{"id":2,"target":"note:4","why":"changed","why_text":"Die Seite wurde seitdem geändert.","version_url":"/api/ai/jobs/7/changes/2/before"},""" +
                """{"id":3,"target":"task:5","why":"changed","version_url":null},{"id":4,"target":"task:6","why":null,"why_text":"x","can_undo":true}]}""")
        assertEquals("Schon wie vorher – die Aufgabe gibt es nicht mehr.", SagaLogic.whyNote(d.changes[0]))
        assertEquals("Die Seite wurde seitdem geändert.", SagaLogic.whyNote(d.changes[1]))
        assertNull("ohne why_text: eigener Text", SagaLogic.whyNote(d.changes[2]))
        assertNull("ohne why kein Satz", SagaLogic.whyNote(d.changes[3]))
        assertNull(SagaLogic.oldVersionUrl(null, d.changes[0], 7))
        assertEquals("/api/ai/jobs/7/changes/2/before", SagaLogic.oldVersionUrl(null, d.changes[1], 7))
    }

    // ------------------------------------------------------------------ Gespraech, Einstellungen, Export

    @Test fun threadAndPrefs() {
        val t = SagaLogic.thread(
            """{"turns":[{"n":1,"role":"me","zug":null,"text":"Was steht an?","fields":null,"read":[],"job_id":null,"created":1.7e9,"expires":1.7e9},""" +
                """{"n":2,"role":"ai","zug":"answer","text":"Elternabend [1]","fields":{"sources":[{"n":1,"id":4,"title":"Kalender"}]},"read":["note:4"]}],""" +
                """"history":false,"keep":86400,"consent":{"state":"granted"}}""",
        )
        assertEquals(2, t.turns.size)
        assertTrue(t.turns[0].me)
        assertFalse(t.turns[1].me)
        assertEquals(86400L, t.keep)
        assertFalse(t.history)
        val src = SagaLogic.turnSources(t.turns[1])
        assertEquals(4L, src[0].id)
        assertTrue(SagaLogic.turnSources(t.turns[0]).isEmpty())
        assertTrue(SagaLogic.thread("""{"turns":[],"history":true,"keep":null}""").history)
        // null = Standard des Servers
        assertEquals(JsonNull, SagaLogic.prefBody("salutation", null)["salutation"])
        assertEquals("sie", SagaLogic.prefBody("salutation", "sie")["salutation"]!!.jsonPrimitive.content)
        assertTrue(SagaLogic.prefBody("history", true)["history"]!!.jsonPrimitive.boolean)
        val p = json.decodeFromString(
            io.github.yggdrasil_sh.saga.ai.SagaPrefs.serializer(),
            """{"salutation":null,"lively":"sachlich","effective":{"salutation":"du","lively":"sachlich"},"default":{"salutation":"du","lively":"warm"},""" +
                """"choices":{"salutation":["du","sie"],"lively":["sachlich","warm"]},"ideas":"off","sources":{"pages":true},"history":false,""" +
                """"history_allowed":true,"notify_content":"unlocked","dictation":"server","ideas_allowed":true,"consent":{"state":"granted","at":1.7e9}}""",
        )
        assertNull(p.salutation)
        assertEquals(listOf("sachlich", "warm"), p.choices!!.lively)
        assertEquals("warm", p.default!!.lively)
        assertTrue(p.historyAllowed && p.ideasAllowed)
        assertEquals("granted", p.consent!!.state)
        assertEquals(false, json.decodeFromString(io.github.yggdrasil_sh.saga.ai.SagaPrefs.serializer(), """{"enabled":false}""").enabled)
    }

    @Test fun exportName() {
        assertEquals("saga-ki-2026-11-26.json", SagaLogic.exportName("attachment; filename=\"saga-ki-2026-11-26.json\"", "json", "2026-11-26"))
        assertEquals("saga-ki-2026-11-26.md", SagaLogic.exportName(null, "md", "2026-11-26"))
        assertEquals("_.._x.json", SagaLogic.exportName("attachment; filename=\"/../x.json\"", "json", "d"))
        assertEquals("saga-ki-d.json", SagaLogic.exportName("attachment; filename=\"...\"", "json", "d"))
        assertEquals("text/markdown", SagaLogic.mimeOf("md"))
        assertEquals("application/json", SagaLogic.mimeOf("json"))
    }

    // ------------------------------------------------------------------ Navigation und Einstellungen

    @Test fun navigation() {
        assertEquals(listOf(NavModel.Tab.TODAY, NavModel.Tab.PAGES, NavModel.Tab.SEARCH, NavModel.Tab.CHATS), NavModel.tabs(true))
        assertEquals(NavModel.Tab.SAGA, NavModel.tabs(true, sagaOn = true).last())
        assertEquals(listOf(NavModel.Tab.TODAY, NavModel.Tab.PAGES, NavModel.Tab.SEARCH, NavModel.Tab.SAGA), NavModel.tabs(false, sagaOn = true))
        assertEquals(NavModel.Tab.SAGA, NavModel.selectedTab(Dest.Saga, false))
        assertEquals(NavModel.Tab.SAGA, NavModel.selectedTab(Dest.SagaJob(3), false))
        assertEquals(NavModel.Tab.PAGES, NavModel.selectedTab(Dest.Saga, true))
        // feste Seitenleiste: "Saga" unter "Heute" (K10), mit Leiste/Rail steht es dort
        assertEquals(listOf(Dest.Today, Dest.Saga, Dest.Chats, Dest.Tasks, Dest.Events), NavModel.sidebarMain(false, true, sagaOn = true))
        assertEquals(listOf(Dest.Today, Dest.Chats, Dest.Tasks, Dest.Events), NavModel.sidebarMain(false, true))
        assertEquals(listOf(Dest.Tasks, Dest.Events), NavModel.sidebarMain(true, true, sagaOn = true))
        assertTrue(NavModel.same(Dest.SagaJob(1), Dest.Saga))
        assertTrue(NavModel.same(Dest.SettingsSaga, Dest.Settings))
        for (d in listOf(Dest.Saga, Dest.SagaJob(42), Dest.SettingsSaga)) assertEquals(d, Dest.decode(Dest.encode(d)))
        assertNull(Dest.decode("saga-job:x"))
        assertEquals(
            listOf(SettingsLogic.Category.NOTIFICATIONS, SettingsLogic.Category.CHATS, SettingsLogic.Category.SAGA, SettingsLogic.Category.ACCOUNT, SettingsLogic.Category.INFO),
            SettingsLogic.categories(chats = true, backup = false, nextcloud = false, saga = true),
        )
        assertFalse(SettingsLogic.Category.SAGA in SettingsLogic.categories(chats = true, backup = true, nextcloud = true))
        assertEquals(SettingsArea.Sub.SAGA, SettingsArea.sub("saga"))
    }

    /** Alle Sprachen der App haben dieselben Schluessel, Platzhalter unveraendert (de/en/es/ru/th) */
    @Test fun stringsAllLanguages() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val rx = Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""")
        fun load(dir: String) = rx.findAll(File(res, "$dir/strings_saga.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        val base = load("values")
        assertTrue(base.size >= 100)
        val ph = Regex("%\\d\\$[sd]")
        for (d in listOf("values-de", "values-es", "values-ru", "values-th")) {
            val m = load(d)
            assertEquals(d, base.keys, m.keys)
            m.forEach { (k, v) ->
                assertTrue("$d/$k leer", v.isNotBlank())
                assertEquals("$d/$k Platzhalter", ph.findAll(base[k]!!).map { it.value }.toSet(), ph.findAll(v).map { it.value }.toSet())
            }
        }
        assertEquals("%1\$s · KI", load("values-de")["saga_ki_label"])
        assertEquals("Saga", base["saga_nav"])
    }

    /**
     * Androids ICU-Regex kennt das Flag (?U) (UNICODE_CHARACTER_CLASS) nicht - Muster damit werfen erst auf dem Geraet
     * (PatternSyntaxException beim Laden der Klasse, Absturz bei jeder KI-Antwort), die JVM-Tests merken nichts.
     */
    @Test fun noUnicodeClassFlagInRegexes() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val bad = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { l -> !l.trimStart().startsWith("//") && (l.contains("(?U)") || l.contains("UNICODE_CHARACTER_CLASS")) } }
            .map { it.name }.toList()
        assertTrue("(?U) in: $bad", bad.isEmpty())
        // Leerraum wie Python \s: geschuetztes Leerzeichen vor dem Listenpunkt, Ideographic Space nach der Nummer
        val l = io.github.yggdrasil_sh.saga.ai.AiLogic.lines("\u00A0- eins\n2.\u3000zwei\n#\u2003Kopf", emptySet())
        assertEquals(listOf("ul", "ol", "h"), l.map { it.kind })
        assertEquals(2, l[1].n)
    }

    @Test fun groupLabelsFromServerWin() {
        val g = SagaLogic.groups(JobList(listOf(JobGroup("laeuft", "In progress", listOf(JobItem(id = 2, state = "laeuft"))))))
        assertEquals("In progress", g[1].label)
        assertEquals(JsonArray(emptyList()), JsonArray(emptyList()))
        assertTrue(JsonObject(emptyMap()).isEmpty())
    }
}
