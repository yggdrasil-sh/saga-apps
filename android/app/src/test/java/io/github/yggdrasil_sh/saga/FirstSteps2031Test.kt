package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.FirstSteps
import io.github.yggdrasil_sh.saga.data.FirstStepsModel
import io.github.yggdrasil_sh.saga.ui.common.EmptyStates
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** 2.6 (Bugs-DB 2031): "Erste Schritte" (API, Drosselung, Frage) und leere Zustaende mit naechstem Schritt */
class FirstSteps2031Test {
    private val askJson = """{"enabled":true,"ask":true,"state":null,"page":null,"items":[{"key":"avatar","done":false},""" +
        """{"key":"chat","done":true},{"key":"app","done":true},{"key":"page","done":false},{"key":"task","done":false}]}"""

    @Test fun parseGet() {
        val s = FirstSteps.parse(askJson)!!
        assertTrue(s.enabled && s.ask)
        assertNull(s.state)
        assertNull(s.page)
        assertEquals(5, s.total)
        assertEquals(2, s.doneCount)
        val yes = FirstSteps.parse("""{"enabled":true,"ask":false,"state":"yes","page":42,"items":[{"key":"avatar","done":true}]}""")!!
        assertEquals("yes", yes.state)
        assertEquals(42L, yes.page)
        assertEquals(1, yes.doneCount)
        // enabled:false -> nie fragen, auch wenn ask (fehlerhaft) true waere
        val off = FirstSteps.parse("""{"enabled":false,"ask":true,"state":null,"page":null,"items":[]}""")!!
        assertFalse(off.enabled)
        assertFalse(off.ask)
        // kaputte/fremde Antworten: nichts zeigen
        assertNull(FirstSteps.parse("<html>"))
        assertNull(FirstSteps.parse("[1,2]"))
        val odd = FirstSteps.parse("""{"enabled":"true","ask":1,"state":"maybe","page":"42","items":[{"key":""},{"done":true},7]}""")!!
        assertFalse("enabled als Text zaehlt nicht", odd.enabled)
        assertNull(odd.state)
        assertNull("Seite als Text zaehlt nicht", odd.page)
        assertEquals(0, odd.total)
        assertNull(FirstSteps.parse("""{"enabled":true,"page":-3}""")!!.page)
    }

    @Test fun parsePost() {
        assertEquals(7L, FirstSteps.parsePage("""{"ok":true,"page":7}"""))
        assertNull(FirstSteps.parsePage("""{"ok":true}"""))
        assertNull(FirstSteps.parsePage("kaputt"))
        assertEquals("yes", (FirstSteps.body(true)["answer"] as JsonPrimitive).content)
        assertEquals("no", (FirstSteps.body(false)["answer"] as JsonPrimitive).content)
    }

    @Test fun throttle() {
        assertTrue(FirstSteps.shouldFetch(null, 1000))
        assertFalse(FirstSteps.shouldFetch(1000, 1000 + 59_999))
        assertTrue(FirstSteps.shouldFetch(1000, 1000 + 60_000))
        assertTrue("Einstellungen: immer", FirstSteps.shouldFetch(1000, 1001, force = true))
        assertTrue("Uhr zurueckgestellt", FirstSteps.shouldFetch(5000, 1000))
    }

    @Test fun askRules() {
        val s = FirstSteps.parse(askJson)!!
        assertTrue(FirstSteps.askNow(s, askedThisSession = false, blocked = false, dest = Dest.Today))
        assertTrue(FirstSteps.askNow(s, false, false, Dest.Page(3)))
        assertFalse("schon gefragt", FirstSteps.askNow(s, true, false, Dest.Today))
        assertFalse("anderer Dialog/Kopplung/Anmeldung", FirstSteps.askNow(s, false, true, Dest.Today))
        assertFalse("nicht in der Chat-Einrichtung", FirstSteps.askNow(s, false, false, Dest.Chats))
        assertFalse(FirstSteps.askNow(s, false, false, Dest.Chat(1)))
        assertFalse(FirstSteps.askNow(s, false, false, Dest.ChatDevices))
        assertFalse("nicht in den Einstellungen", FirstSteps.askNow(s, false, false, Dest.Settings))
        assertFalse(FirstSteps.askNow(s, false, false, Dest.SettingsInfo))
        assertFalse(FirstSteps.askNow(s.copy(ask = false), false, false, Dest.Today))
        assertFalse(FirstSteps.askNow(s.copy(enabled = false), false, false, Dest.Today))
        assertFalse(FirstSteps.askNow(null, false, false, Dest.Today))
        // Antwort: yes bleibt yes, Frage erledigt, Seite gemerkt
        val a = FirstSteps.afterAnswer(s, yes = true, page = 9)!!
        assertEquals("yes", a.state); assertFalse(a.ask); assertEquals(9L, a.page)
        assertEquals("no", FirstSteps.afterAnswer(s, false, null)!!.state)
        assertEquals("einmal yes bleibt yes", "yes", FirstSteps.afterAnswer(a, false, null)!!.state)
    }

    /** Attrappe der API: zaehlt Aufrufe, Antwort bzw. Fehler je Methode */
    private class FakeApi {
        var getCalls = 0
        var posts = mutableListOf<JsonElement>()
        var getAnswer: () -> String = { "{}" }
        var postAnswer: (JsonElement) -> String = { """{"ok":true}""" }
        var now = 1_000_000L
        val model = FirstStepsModel(
            get = { p -> assertEquals("api/first-steps", p); getCalls++; getAnswer() },
            post = { p, b -> assertEquals("api/first-steps", p); posts += b; postAnswer(b) },
            clock = { now },
        )
    }

    @Test fun modelThrottlesAndAsks() = runBlocking {
        val f = FakeApi().apply { getAnswer = { askJson } }
        f.model.resetFor("https://saga.example.org|alex")
        assertTrue(f.model.check()!!.ask)
        assertEquals(1, f.getCalls)
        f.now += 30_000
        f.model.check()
        assertEquals("innerhalb 60 s kein neuer Abruf", 1, f.getCalls)
        f.now += 30_000
        f.model.check()
        assertEquals("Vordergrund nach 60 s: neu abrufen (hakt ab)", 2, f.getCalls)
        f.model.check(force = true)
        assertEquals(3, f.getCalls)
        // anderes Konto: Drosselung und Zustand neu
        f.model.resetFor("https://saga.example.org|kim")
        assertNull(f.model.status.value)
        f.model.check()
        assertEquals(4, f.getCalls)
        f.model.resetFor("https://saga.example.org|kim")       // gleicher Schluessel: nichts vergessen
        f.model.check()
        assertEquals(4, f.getCalls)
    }

    @Test fun modelOldServer404() = runBlocking {
        val f = FakeApi().apply { getAnswer = { throw ApiException(404, UiText.Raw("not found")) } }
        f.model.resetFor("a")
        assertNull(f.model.check())
        assertTrue(f.model.unsupported)
        f.now += 120_000
        assertNull(f.model.check(force = true))
        assertEquals("404: still, kein weiterer Abruf", 1, f.getCalls)
        f.model.resetFor("b")                                   // anderer Server: wieder versuchen
        f.getAnswer = { askJson }
        assertNotNull(f.model.check())
        assertFalse(f.model.unsupported)
    }

    @Test fun modelDisabledAndErrors() = runBlocking {
        val f = FakeApi().apply { getAnswer = { """{"enabled":false,"ask":false,"state":null,"page":null,"items":[]}""" } }
        f.model.resetFor("a")
        val st = f.model.check()!!
        assertFalse(st.enabled)
        assertFalse(FirstSteps.askNow(st, false, false, Dest.Today))
        // offline: letzter Stand bleibt, nichts geworfen
        f.getAnswer = { throw IOException("offline") }
        f.now += 61_000
        assertFalse(f.model.check()!!.enabled)
        // POST bei enabled:false -> 403: Fehler geht an die Oberflaeche (normale Fehlermeldung)
        f.postAnswer = { throw ApiException(403, UiText.Raw("Die Einführung ist auf diesem Server abgeschaltet.")) }
        try {
            f.model.answer(true)
            fail("403 muss gemeldet werden")
        } catch (e: ApiException) {
            assertEquals(403, e.code)
        }
    }

    @Test fun modelAnswer() = runBlocking {
        val f = FakeApi().apply { getAnswer = { askJson }; postAnswer = { b -> if ((b as JsonObject)["answer"].toString().contains("yes")) """{"ok":true,"page":77}""" else """{"ok":true}""" } }
        f.model.resetFor("a")
        f.model.check()
        assertEquals(77L, f.model.answer(true))
        assertEquals("""{"answer":"yes"}""", f.posts.last().toString())
        assertTrue(f.model.asked.value)
        assertFalse(f.model.status.value!!.ask)
        assertEquals(77L, f.model.status.value!!.page)
        // nach der Antwort holt der naechste Abruf frisch (Haekchen), ohne 60 s zu warten
        val before = f.getCalls
        f.model.check()
        assertEquals(before + 1, f.getCalls)
        assertNull(f.model.answer(false))
        assertEquals("""{"answer":"no"}""", f.posts.last().toString())
    }

    /** Kontowechsel waehrend eines laufenden GET/POST: die Antwort gehoert zum alten Konto und wird verworfen */
    @Test fun modelAccountSwitchRace() = runBlocking {
        lateinit var f: FakeApi
        f = FakeApi().apply { getAnswer = { f.model.resetFor("kim"); askJson } }
        f.model.resetFor("alex")
        assertNull(f.model.check())
        assertNull("Stand von alex nicht bei kim", f.model.status.value)
        // 404 des alten Servers sperrt den neuen nicht
        f.getAnswer = { f.model.resetFor("neu"); throw ApiException(404, UiText.Raw("not found")) }
        f.model.check(force = true)
        assertFalse(f.model.unsupported)
        // danach normal: neues Konto fragt
        f.getAnswer = { askJson }
        assertTrue(f.model.check(force = true)!!.ask)
        // POST waehrend Kontowechsel: keine Seite oeffnen, nichts uebernehmen
        f.postAnswer = { f.model.resetFor("anders"); """{"ok":true,"page":5}""" }
        assertNull(f.model.answer(true))
        assertNull(f.model.status.value)
        assertFalse(f.model.asked.value)
    }

    @Test fun emptyStates() {
        assertEquals(EmptyStates.Tasks.NONE_EVER, EmptyStates.tasks(0, 0))
        assertEquals(EmptyStates.Tasks.ALL_DONE, EmptyStates.tasks(0, 3))
        assertEquals(EmptyStates.Tasks.NOT_EMPTY, EmptyStates.tasks(2, 0))
        assertEquals(EmptyStates.Db.NONE, EmptyStates.db(0, false, ""))
        assertEquals(EmptyStates.Db.NONE, EmptyStates.db(0, false, "  "))
        assertEquals("Eintraege da, Ansicht leer", EmptyStates.Db.FILTERED, EmptyStates.db(5, false, ""))
        assertEquals(EmptyStates.Db.FILTERED, EmptyStates.db(0, true, ""))
        assertEquals(EmptyStates.Db.FILTERED, EmptyStates.db(0, false, "rechnung"))
        assertEquals(EmptyStates.Events.NO_CALENDAR, EmptyStates.events(false))
        assertEquals(EmptyStates.Events.EMPTY, EmptyStates.events(true))
        assertEquals("unbekannt (offline): wie bisher", EmptyStates.Events.EMPTY, EmptyStates.events(null))
        assertEquals("auf /notes (\"/\" leitet bei eigener Startseite um)", "/notes?settings=calendar", EmptyStates.CALENDAR_SETTINGS)
    }

    /** Alle Sprachen haben dieselben Schluessel, Platzhalter unveraendert */
    @Test fun stringsAllLanguages() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val rx = Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""")
        fun load(dir: String) = rx.findAll(File(res, "$dir/strings_2031.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        val base = load("values")
        assertEquals(19, base.size)
        for (d in listOf("values-de", "values-es", "values-ru", "values-th")) {
            val m = load(d)
            assertEquals(d, base.keys, m.keys)
            m.forEach { (k, v) ->
                assertTrue("$d/$k leer", v.isNotBlank())
                assertEquals("$d/$k Platzhalter", Regex("%\\d\\$[sd]").findAll(base[k]!!).map { it.value }.toList(), Regex("%\\d\\$[sd]").findAll(v).map { it.value }.toList())
            }
        }
    }
}
