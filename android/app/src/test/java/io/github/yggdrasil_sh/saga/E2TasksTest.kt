package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.TaskQueue
import io.github.yggdrasil_sh.saga.data.TaskSourcesLogic
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.widget.TodayLogic
import io.github.yggdrasil_sh.saga.widget.WidgetLinks
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.LocalDate

class E2TasksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = """{"open":[],"done":[],"items":[
        {"key":"t:12","kind":"task","id":12,"text":"Inbox","due":"2026-10-27","prio":2,"repeat":null,"done":false,"overdue":true},
        {"key":"r:345","kind":"row","id":345,"src":77,"text":"Zeile","due":"2026-10-28","time":"09:30","due_end":"2026-10-30","prio":1,
         "done":false,"overdue":false,"edit":false,"assignees":["~abcdefghijklmnop","~unbekanntxxxxxxx"]},
        {"key":"r:346","kind":"row","id":346,"src":77,"text":"Spaeter","due":"2026-11-30","done":false,"edit":true}],
      "sources":[{"db":77,"title":"Aufgaben","icon":"☑️","role":"owner","scope":"all","own":true,"count":14,"truncated":false,
        "map":{"done":"status","assignee":"zugewiesen"}},
        {"db":78,"title":"Alt","icon":"","role":"view","scope":"assigned","inactive":true,"map":null},
        {"db":79,"title":"Fremd","role":"edit","scope":"assigned","map":{"done":"s","assignee":null}}],
      "people":{"~abcdefghijklmnop":{"name":"Anna Berg","avatar":null,"me":true,"remote":false}},
      "limits":{"sources":20,"rows":500}}"""

    @Test fun parseKeepsServerOrderAndFields() {
        val l = TaskSourcesLogic.parse(sample)!!
        assertEquals(listOf("t:12", "r:345", "r:346"), l.items.map { it.key })
        val r = l.items[1]
        assertTrue(r.isRow)
        assertEquals(77L, r.src)
        assertEquals("09:30", r.time)
        assertEquals("2026-10-30", r.dueEnd)
        assertFalse(r.edit)
        // Zugewiesene nur ueber people - unbekannte Kennung wird nie angezeigt
        assertEquals(listOf("Anna Berg"), TaskSourcesLogic.assigneeNames(r, l.people))
        // Ziele: nur mit map, aktiv, owner/edit
        assertEquals(listOf(77L, 79L), TaskSourcesLogic.targets(l.sources).map { it.db })
        assertTrue(l.sources[0].canAssigned)
        assertFalse(l.sources[2].canAssigned)
        assertTrue(l.sources[1].inactive)
        // aeltere Server: kein items
        assertNull(TaskSourcesLogic.parse("""{"open":[],"done":[]}"""))
    }

    @Test fun widgetSnapshotKeepsOrderAndFilters() {
        val l = TaskSourcesLogic.parse(sample)!!
        val today = LocalDate.parse("2026-10-28")
        val snap = TodayLogic.forSnapshot(l, today.plusDays(1))
        assertEquals(listOf(12, 345), snap.map { it.id })            // spaetere fallen weg
        assertTrue(snap[1].row)
        assertEquals(-345, snap[1].hideKey)
        assertEquals("☑️", snap[1].srcIcon)
        val lines = TodayLogic.tasks(snap, today, ordered = true)
        assertEquals(listOf(12, 345), lines.map { it.task.id })     // Server-Reihenfolge, keine eigene Sortierung
        assertTrue(lines[0].overdue)
        assertEquals(listOf(12), TodayLogic.tasks(snap, today, hidden = setOf(-345), ordered = true).map { it.task.id })
        assertEquals("new-task:77", WidgetLinks.newTask(77))
        assertEquals(77L, WidgetLinks.newTaskTarget("new-task:77"))
        assertNull(WidgetLinks.newTaskTarget("new-task"))
    }

    private class Srv {
        val calls = ArrayList<Triple<String, String, JsonElement>>()
        var fail: Exception? = null
        var answer = """{"ok":true,"src":77,"done":true,"done_value":"x","prev":"opt-laeuft"}"""
        suspend fun call(m: String, p: String, b: JsonElement): String {
            calls.add(Triple(m, p, b))
            fail?.let { throw it }
            return answer
        }
    }

    @Test fun queueLastActionWinsAndPrevIsUsed() = runTest {
        val srv = Srv()
        val q = TaskQueue({ tmp.root }, srv::call, { "2026-10-28" })
        srv.fail = IOException("offline")
        assertTrue(q.put("r:345", true))
        assertFalse(q.put("r:345", false))                          // abhaken + wieder oeffnen offline = nichts
        assertTrue(q.pending.value.isEmpty())
        // online abhaken -> prev gemerkt; Rueckgaengig schickt prev als value
        srv.fail = null
        q.put("r:345", true)
        val r = q.flush()
        assertEquals(1, r.sent)
        assertTrue(r.reload)
        assertEquals(JsonPrimitive("opt-laeuft"), q.prevFor("r:345"))
        srv.answer = """{"ok":true}"""
        q.put("r:345", false)
        q.flush()
        val body = srv.calls.last().third as JsonObject
        assertEquals("api/tasks/src/345", srv.calls.last().second)
        assertEquals(JsonPrimitive(false), body["done"])
        assertEquals(JsonPrimitive("opt-laeuft"), body["value"])  // nie pauschal open_value
        // Inbox: PATCH api/tasks/{id} mit today
        q.put("t:12", true)
        q.flush()
        assertEquals("api/tasks/12", srv.calls.last().second)
        assertEquals(JsonPrimitive("2026-10-28"), (srv.calls.last().third as JsonObject)["today"])
    }

    @Test fun queueErrorCodes() = runTest {
        val srv = Srv()
        val q = TaskQueue({ tmp.root }, srv::call)
        for ((code, kept) in listOf(403 to false, 404 to false, 400 to false, 500 to true, 503 to true)) {
            srv.fail = ApiException(code, UiText.Raw("x"))
            q.put("r:$code", true)
            val r = q.flush()
            assertEquals("code $code", kept, q.pending.value.containsKey("r:$code"))
            if (!kept) assertTrue(r.reload)                         // verworfen -> Liste neu laden
            if (kept) { q.put("r:$code", false) }                   // aufraeumen (hebt sich auf)
        }
        // Netzfehler: behalten, ueberlebt einen Neustart (Datei)
        srv.fail = IOException("weg")
        q.put("t:1", true)
        q.flush()
        val q2 = TaskQueue({ tmp.root }, srv::call)
        srv.fail = null
        assertEquals(1, q2.flush().sent)
        assertTrue(q2.pending.value.isEmpty())
    }

    @Test fun digestAndSourceState() {
        val d = TaskSourcesLogic.parseDigest("""{"on":true,"time":"06:45","tz":""}""")
        assertTrue(d.on)
        assertEquals("06:45", d.time)
        assertEquals(true to false, TaskSourcesLogic.sourceState("""{"db":1,"map":{"done":"s"},"scope":null}"""))
        assertEquals(false to false, TaskSourcesLogic.sourceState("""{"db":1,"map":null,"scope":null}"""))
        assertEquals(true to true, TaskSourcesLogic.sourceState("""{"db":1,"map":{"done":"s"},"scope":"all"}"""))
        assertEquals(JsonNull, TaskSourcesLogic.prevOf("""{"ok":true,"prev":null}"""))
        assertNull(TaskSourcesLogic.prevOf("""{"ok":true}"""))
    }
}
