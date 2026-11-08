package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BookOrbit
import io.github.yggdrasil_sh.saga.blocks.PageWidgets
import io.github.yggdrasil_sh.saga.ui.settings.SettingsArea
import io.github.yggdrasil_sh.saga.ui.settings.SettingsLogic
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saga 2.5 BookOrbit (bookorbit1): Widget "Lesen" - alle Zustaende und Anzeige-Arten, Cover, Heute, Einstellungen */
class BookOrbitTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun featureAndKinds() {
        assertEquals("bookorbit1", PageWidgets.featureFor("reading"))
        assertEquals("weather1", PageWidgets.featureFor("weather"))
        assertTrue(PageWidgets.supported("reading", setOf("bookorbit1")))
        assertFalse(PageWidgets.supported("reading", setOf("reading1", "weather1")))
        val spec = PageWidgets.spec(obj("""{"id": "r", "type": "widget", "data": {"kind": "reading", "cfg": {"show": "goal"}}}"""))
        assertTrue(spec.known && spec.serverValue)
    }

    @Test
    fun current() {
        val r = BookOrbit.reading(obj("""{"state": "ok", "show": "current", "more": 2, "books": [
            {"id": 11, "title": " Der Nachtzug ", "authors": ["Ida Feld", "", "Bo Lind", "Cy Dorn"], "progress": 42.6, "cover": "/api/bookorbit/cover/11", "format": "epub"},
            {"id": 12, "title": "", "authors": [], "progress": 140, "cover": null},
            {"title": "ohne id"},
            {"id": 13, "title": "Fremd", "progress": -3, "cover": "https://evil.example/x.png"}]}"""))!!
        assertEquals(BookOrbit.State.OK, r.state)
        assertEquals(BookOrbit.Show.CURRENT, r.show)
        assertEquals(3, r.books.size)
        val b = r.books[0]
        assertEquals("Der Nachtzug", b.title)
        assertEquals("Ida Feld, Bo Lind", b.authorLine)
        assertEquals(43, b.percent)
        assertEquals("/api/bookorbit/cover/11", b.cover)
        assertEquals(100.0, r.books[1].progress, 0.0)
        assertNull(r.books[2].cover)
        assertEquals(0.0, r.books[2].progress, 0.0)
        assertEquals(BookOrbit.Content.Books(r.books, 2), BookOrbit.content(r))
        // Heute: hoechstens 3 (hier 2) - der Rest zaehlt zu "weitere"
        val c = BookOrbit.content(r, limit = 2) as BookOrbit.Content.Books
        assertEquals(2, c.books.size)
        assertEquals(3, c.more)
        assertEquals(BookOrbit.Content.NoBooks, BookOrbit.content(BookOrbit.reading(obj("""{"state": "ok", "show": "current", "books": []}"""))!!))
    }

    @Test
    fun goal() {
        val g = BookOrbit.reading(obj("""{"state": "ok", "show": "goal", "goal": {"goal": 12, "done": 3, "year": 2026}}"""))!!
        assertEquals(BookOrbit.Show.GOAL, g.show)
        assertEquals(BookOrbit.Goal(12, 3, 2026), g.goal)
        assertEquals(0.25f, g.goal!!.fraction!!, 0.0001f)
        assertEquals(BookOrbit.Content.GoalC(BookOrbit.Goal(12, 3, 2026)), BookOrbit.content(g))
        val none = BookOrbit.reading(obj("""{"state": "ok", "show": "goal", "goal": {"goal": null, "done": 5, "year": 2026}}"""))!!.goal!!
        assertNull(none.goal)
        assertNull(none.fraction)
        assertEquals(1f, BookOrbit.reading(obj("""{"state": "ok", "show": "goal", "goal": {"goal": 2, "done": 7}}"""))!!.goal!!.fraction!!, 0f)
        assertEquals(BookOrbit.Content.NoGoal, BookOrbit.content(BookOrbit.reading(obj("""{"state": "ok", "show": "goal", "goal": null}"""))!!))
    }

    @Test
    fun streak() {
        val s = BookOrbit.reading(obj("""{"state": "ok", "show": "streak", "streak": {"current": 4, "longest": 9, "last7": [true, false, true, true, true, true, true, true]}}"""))!!
        assertEquals(BookOrbit.Streak(4, 9, listOf(true, false, true, true, true, true, true)), s.streak)
        assertEquals(6, s.streak!!.daysRead)
        assertEquals(BookOrbit.Content.NoStreak, BookOrbit.content(BookOrbit.reading(obj("""{"state": "ok", "show": "streak"}"""))!!))
    }

    @Test
    fun states() {
        for ((txt, st) in listOf("none" to BookOrbit.State.NONE, "expired" to BookOrbit.State.EXPIRED, "unavailable" to BookOrbit.State.UNAVAILABLE)) {
            val r = BookOrbit.reading(obj("""{"state": "$txt", "show": "current"}"""))!!
            assertEquals(st, r.state)
            assertEquals(BookOrbit.Content.Hint(st), BookOrbit.content(r))
        }
        assertTrue(BookOrbit.offersSettings(BookOrbit.State.NONE) && BookOrbit.offersSettings(BookOrbit.State.EXPIRED))
        assertFalse(BookOrbit.offersSettings(BookOrbit.State.UNAVAILABLE) || BookOrbit.offersSettings(BookOrbit.State.OK))
        assertEquals(BookOrbit.State.UNAVAILABLE, BookOrbit.reading(obj("""{"state": "kaputt"}"""))!!.state)
        assertEquals(BookOrbit.Show.CURRENT, BookOrbit.reading(obj("""{"state": "ok", "show": "seltsam"}"""))!!.show)
        assertTrue(BookOrbit.reading(obj("""{"state": "ok", "stale": true}"""))!!.stale)
        assertNull(BookOrbit.reading(null))
        assertNull(BookOrbit.parseReading("kein json"))
    }

    @Test
    fun widgetResponse() {
        val v = PageWidgets.parseResponse("""{"widgets": {
            "r1": {"kind": "reading", "value": 1, "error": null, "data": {"state": "ok", "show": "current", "books": [{"id": 5, "title": "A", "authors": ["B"], "progress": 10, "cover": "/api/bookorbit/cover/5"}], "more": 0}},
            "r2": {"kind": "reading", "value": null, "error": null, "data": {"state": "expired", "show": "streak"}},
            "r3": {"kind": "reading", "error": "no_access", "data": null}}}""") as PageWidgets.Values.Ok
        assertEquals("/api/bookorbit/cover/5", BookOrbit.reading(v.map["r1"]!!.data)!!.books.single().cover)
        assertEquals(BookOrbit.State.EXPIRED, BookOrbit.reading(v.map["r2"]!!.data)!!.state)
        assertNull(BookOrbit.reading(v.map["r3"]!!.data))
        assertEquals(PageWidgets.Err.NO_ACCESS, v.map["r3"]!!.error)
    }

    @Test
    fun coverPaths() {
        assertEquals("/api/bookorbit/cover/123", BookOrbit.coverPath("/api/bookorbit/cover/123"))
        for (bad in listOf("/api/bookorbit/cover/", "/api/bookorbit/cover/1/../x", "/api/bookorbit/cover/1?x=1", "/files/1.png",
            "//evil/api/bookorbit/cover/1", "https://evil.example/api/bookorbit/cover/1", null)) assertNull(bad, BookOrbit.coverPath(bad))
    }

    @Test
    fun today() {
        assertFalse(BookOrbit.todayVisible(null))
        assertFalse(BookOrbit.todayVisible(BookOrbit.Reading(BookOrbit.State.NONE)))
        assertTrue(BookOrbit.todayVisible(BookOrbit.Reading(BookOrbit.State.EXPIRED)))
        assertTrue(BookOrbit.todayVisible(BookOrbit.Reading(BookOrbit.State.OK)))
    }

    @Test
    fun settings() {
        val s = BookOrbit.status("""{"available": true, "connected": true, "state": "ok", "name": "Anna", "url": "https://books.example.org", "since": 1762600000, "suggest": null}""")!!
        assertTrue(s.connected)
        assertEquals("Anna", s.name)
        assertEquals("books.example.org", s.host)
        assertFalse(s.needsLogin)
        assertTrue(s.copy(state = BookOrbit.State.EXPIRED).needsLogin)
        val off = BookOrbit.status("""{"available": true, "connected": false, "state": "none", "suggest": "https://books.example.org"}""")!!
        assertTrue(off.needsLogin)
        assertEquals("https://books.example.org", off.suggest)
        assertFalse(BookOrbit.status("""{"available": false}""")!!.needsLogin)
        assertNull(BookOrbit.status("<html>"))
        val body = BookOrbit.connectBody(" https://books.example.org/ ", " anna ", " geheim ")
        assertEquals(JsonPrimitive("https://books.example.org/"), body["url"])
        assertEquals(JsonPrimitive("anna"), body["username"])
        assertEquals(JsonPrimitive(" geheim "), body["password"])
        assertFalse(BookOrbit.canConnect("x", " ", "p"))
        assertTrue(BookOrbit.canConnect("x", "a", "p"))
    }

    @Test
    fun navigation() {
        assertEquals(SettingsArea.Sub.BOOKORBIT, SettingsArea.sub("bookorbit"))
        assertEquals(Dest.BookOrbit, Dest.decode(Dest.encode(Dest.BookOrbit)))
        assertEquals(
            listOf(SettingsLogic.Category.NOTIFICATIONS, SettingsLogic.Category.BOOKORBIT, SettingsLogic.Category.ACCOUNT, SettingsLogic.Category.INFO),
            SettingsLogic.categories(chats = false, backup = false, nextcloud = false, bookorbit = true),
        )
    }
}
