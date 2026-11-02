package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.diag.ClientLog
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 Fehlerberichte und Diagnose (clientlog1): Bereinigung, Muster, Drossel, Puffer, JSON gegen docs/APP-API.md */
class ClientLogTest {
    private val head = ClientLog.Head("2.5.0-rc.3", ClientLog.os("15"), "phone", "k3J9xQ2mP7aZ4tWb")

    // ---------------------------------------------------------------------------------------------------- Bereinigung

    @Test fun scrubRemovesContent() {
        val s = ClientLog.scrub(
            "Seite \"Urlaub Plan\" fuer anna@example.org nicht geladen von https://saga.example.org/notes/12?x=1 " +
                "(id 4711, 123e4567-e89b-12d3-a456-426614174000, deadbeefcafe, hkd_abcdef123456, @bert, ~xy9)",
        )
        listOf("Urlaub", "anna", "example", "saga.example", "4711", "123e4567", "deadbeef", "hkd_", "bert", "xy9", "Seite", "fuer", "geladen")
            .forEach { assertFalse("'$it' darf nicht bleiben: $s", s.contains(it)) }
        listOf("\"<str>\"", "<email>", "<url>", "<n>", "<uuid>", "<hex>", "<secret>", "<id>").forEach {
            assertTrue("'$it' fehlt: $s", s.contains(it))
        }
    }

    @Test fun scrubKeepsProgramWords() {
        assertEquals("IllegalStateException: <w> not <w>", ClientLog.scrub("IllegalStateException: Fragment not attached"))
        val s = ClientLog.scrub("NullPointerException: Cannot read property 'title' of null in PageEditor.doSave")
        assertTrue(s, s.startsWith("NullPointerException: Cannot read property 'title' of null in PageEditor.doSave"))
        // Seitentitel ohne Anfuehrungszeichen -> <w>
        assertEquals("Error: <w> <w> failed", ClientLog.scrub("Error: Wochenplan Garten failed"))
        // Pfade und Hostnamen ohne Schema
        val h = ClientLog.scrub("Unable to resolve host saga.example.net: No address associated; file /data/user/0/x/notes.db")
        assertFalse(h, h.contains("example"))
        assertFalse(h, h.contains("/data"))
        assertTrue(h, h.contains("<host>") && h.contains("<path>"))
        // Paketnamen und Dateien bleiben
        assertTrue(ClientLog.scrub("at java.lang.Thread.run(Thread.java)").contains("java.lang.Thread.run"))
        assertTrue(ClientLog.scrub("ClientLog.kt failed").contains("ClientLog.kt"))
        // Steuer- und Richtungszeichen, Laenge
        assertFalse(ClientLog.scrub("a‮b\u0000c").contains("‮"))
        assertTrue(ClientLog.scrub("Error " + "x ".repeat(500)).length <= ClientLog.MSG_MAX)
        // IPs werden Zahlen
        assertFalse(ClientLog.scrub("connect to 10.1.2.3 failed").contains("10.1"))
        assertFalse(ClientLog.scrub("connect to fe80::1:2:3 failed").contains("fe80"))
    }

    @Test fun messageOfExceptions() {
        assertEquals("ApiException: http_conflict", ClientLog.message(ApiException(409, UiText.Raw("Seite Geheim geaendert"))))
        assertEquals("IllegalStateException", ClientLog.message(IllegalStateException()))
        val m = ClientLog.message(java.io.IOException("Unable to resolve host \"my.server.example\""))
        assertTrue(m, m.startsWith("IOException: "))
        assertFalse(m, m.contains("server"))
        // eigene Ausnahmen (UiTextException u. a.) koennen Oberflaechen-Texte tragen: nur die Klasse
        assertEquals("UiTextException", ClientLog.message(io.github.yggdrasil_sh.saga.util.UiTextException(UiText.Raw("Anna ruft an"))))
    }

    @Test fun stackOnlyClassesMethodsLines() {
        val e = IllegalStateException("boom \"Titel\"", RuntimeException("inner secret text"))
        e.stackTrace = arrayOf(
            StackTraceElement("io.github.yggdrasil_sh.saga.ui.page.PageEditor", "doSave", "PageEditor.kt", 88),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 106),
            StackTraceElement("x.Y\$1", "invoke", null, -1),
        )
        val st = ClientLog.stack(e)
        val lines = st.split('\n')
        assertEquals("at io.github.yggdrasil_sh.saga.ui.page.PageEditor.doSave(PageEditor.kt:88)", lines[0])
        assertEquals("at android.os.Handler.dispatchMessage(Handler.java:106)", lines[1])
        assertEquals("at x.Y\$1.invoke(Unknown Source)", lines[2])
        assertTrue(st.contains("Caused by: RuntimeException"))
        assertFalse(st.contains("secret"))
        assertFalse(st.contains("Titel"))
        // Grenzen 20 Zeilen / 2000 Zeichen
        val big = RuntimeException()
        big.stackTrace = Array(100) { StackTraceElement("a.b.C$it", "m", "C.kt", it) }
        val bs = ClientLog.stack(big)
        assertTrue(bs.split('\n').size <= ClientLog.STACK_LINES)
        assertTrue(bs.length <= ClientLog.STACK_MAX)
        val it = ClientLog.item("crash", e, 183_000, "pages")
        assertEquals("PageEditor.kt", it.src)
        assertEquals(88, it.line)
        assertTrue(it.msg, it.msg.startsWith("IllegalStateException: "))
        assertFalse(it.msg.contains("Titel"))
    }

    // ------------------------------------------------------------------------------------------------- Endpunkt-Muster

    @Test fun pathPatterns() {
        assertEquals("/api/notes/:id/comments", ClientLog.pathPattern("api/notes/12/comments?since=3#x"))
        assertEquals("/api/notes/:id", ClientLog.pathPattern("/api/notes/-5"))
        assertEquals("/api/chats/:id/files/:x", ClientLog.pathPattern("api/chats/7/files/Q1w2E3r4T5y6"))
        assertEquals("/api/:x", ClientLog.pathPattern("https://saga.example.org/api/Urlaub"))
        assertEquals("/api/calls/:x/end", ClientLog.pathPattern("api/calls/abcDEF123/end"))
        assertEquals("/fed/:x/:x", ClientLog.pathPattern("fed/@anna/geheim"))
        assertEquals("api_notes_id_comments", ClientLog.pathIdent("api/notes/12/comments"))
        assertEquals("ApiException: http_bad_gateway method_patch api_notes_id", ClientLog.httpMessage("PATCH", "api/notes/3", 502))
        // die Meldung uebersteht die Bereinigung unveraendert
        val m = ClientLog.httpMessage("GET", "api/chat/me", 503)
        assertEquals(m, ClientLog.scrub(m))
    }

    @Test fun screens() {
        assertEquals("pages", ClientLog.screenOf("page:42"))
        assertEquals("chat", ClientLog.screenOf("chat:7"))
        assertEquals("settings", ClientLog.screenOf("settings-info"))
        assertEquals("dashboard", ClientLog.screenOf("today"))
        assertEquals("other", ClientLog.screenOf("irgendwas"))
        listOf("today", "page:1", "chat:2", "graph:3", "push", "nextcloud").forEach { assertTrue(ClientLog.SCREEN.matches(ClientLog.screenOf(it))) }
    }

    // ----------------------------------------------------------------------------------------------- Drossel, Dedupe

    private fun item(msg: String, t: Long = 1) = ClientLog.Item(k = "error", msg = msg, src = "A.kt", line = 3, t = t)

    @Test fun pendingDedupeAndRate() {
        val p = ClientLog.Pending()
        assertTrue(p.add(item("x")))
        assertTrue(p.add(item("x")))
        assertTrue(p.add(item("y")))
        assertEquals(2, p.size)
        val a = p.take(1_000)!!
        assertEquals(2, a.first { it.msg == "x" }.n)
        assertEquals(1, a.first { it.msg == "y" }.n)
        p.add(item("z"))
        assertNull("hoechstens ein Bericht je 30 s", p.take(20_000))
        assertEquals(11_000, p.wait(20_000))
        assertNotNull(p.take(31_000))
        repeat(30) { p.add(item("m$it")) }
        assertEquals("hoechstens 20 verschiedene", ClientLog.ITEMS, p.size)
    }

    @Test fun pending429And404() {
        val p = ClientLog.Pending()
        p.add(item("x"))
        p.take(0)
        p.result(0, 429, """{"error":"too many","retry_s":120}""")
        p.add(item("y"))
        assertNull(p.take(60_000))
        assertEquals(60_000, p.wait(60_000))
        assertNotNull(p.take(120_000))
        p.result(120_000, 429, "kaputt")
        assertEquals("ohne retry_s 60 s", 60_000, p.wait(120_000))
        p.result(200_000, 404)
        assertTrue(p.off)
        assertFalse(p.add(item("z")))
        assertNull(p.take(10_000_000))
    }

    @Test fun ring() {
        val r = ClientLog.Ring()
        repeat(250) { r.add(ClientLog.life(it.toLong(), "visible")) }
        r.add(null)
        r.add(ClientLog.life(1, "falsch"))
        val s = r.snapshot()
        assertEquals(ClientLog.RING, s.size)
        assertEquals("aeltere zuerst, die letzten 200", 50L, s.first()["t"]!!.jsonPrimitive.long)
        assertEquals(249L, s.last()["t"]!!.jsonPrimitive.long)
    }

    // ------------------------------------------------------------------------------------------- JSON gegen Vertrag

    private fun keys(o: JsonObject) = o.keys

    @Test fun logBodyContract() {
        val b = ClientLog.logBody(head, listOf(item("x").copy(n = 3, screen = "pages"), item("y").copy(screen = "Seite Titel")))!!
        assertEquals(setOf("v", "app", "ver", "os", "dev", "sid", "items"), keys(b))
        assertEquals(1, b["v"]!!.jsonPrimitive.int)
        assertEquals("android", b["app"]!!.jsonPrimitive.content)
        assertEquals("Android 15", b["os"]!!.jsonPrimitive.content)
        assertTrue(Regex("[A-Za-z0-9_-]{8,40}").matches(b["sid"]!!.jsonPrimitive.content))
        val it = b["items"]!!.jsonArray
        assertEquals(2, it.size)
        val a = it[0].jsonObject
        assertEquals(setOf("k", "msg", "src", "line", "screen", "n", "t"), keys(a))
        assertFalse("ungueltiger Ort wird weggelassen", "screen" in it[1].jsonObject)
        assertFalse("n = 1 wird weggelassen", "n" in it[1].jsonObject)
        assertNull(ClientLog.logBody(head, emptyList()))
        // Groesse <= 16 KB, <= 20 Eintraege
        val many = (0 until 40).map { ClientLog.Item(k = "error", msg = "E$it" + "x".repeat(280), stack = "at a.B.c(B.kt:1)\n".repeat(110), t = 1) }
        val big = ClientLog.logBody(head, many)!!
        assertTrue(big.toString().toByteArray().size <= ClientLog.LOG_MAX)
        assertTrue(big["items"]!!.jsonArray.size in 1..ClientLog.ITEMS)
    }

    @Test fun headValues() {
        assertEquals("Android", ClientLog.os(null))
        assertEquals("Android 14", ClientLog.os("14.0.1"))
        assertEquals("tablet", ClientLog.dev(720))
        assertEquals("phone", ClientLog.dev(390))
        assertEquals("2.5.0-rc.3", ClientLog.ver("2.5.0-rc.3"))
        assertEquals("2.5.0", ClientLog.ver("2.5.0 (debug)").take(5))
        val a = ClientLog.newSid()
        assertEquals(16, a.length)
        assertTrue(Regex("[A-Za-z0-9_-]{8,40}").matches(a))
        assertTrue(a != ClientLog.newSid())
    }

    @Test fun diagBodyContract() {
        val evs = listOfNotNull(
            ClientLog.life(10, "start"), ClientLog.view(2100, "pages"),
            ClientLog.net(2300, "get", "api/notes/12", 200, 84), ClientLog.net(9100, "PATCH", "api/notes/12", 409, 120),
            ClientLog.save(9120, "conflict", 409), ClientLog.conn(15000, "sse", "retry"),
            item("URLError: lost", 16000).copy(screen = "pages").event(),
        )
        assertNull(ClientLog.net(1, "TRACE", "api/x", 200, 1))
        assertNull(ClientLog.conn(1, "sse", "kaputt"))
        assertNull(ClientLog.view(1, "Seite"))
        assertNull(ClientLog.save(1, "nope"))
        val b = ClientLog.diagBody(head, evs, "  Beim Speichern hing es kurz.  ")
        assertEquals(setOf("v", "app", "ver", "os", "dev", "sid", "ev", "note"), keys(b))
        assertEquals("Beim Speichern hing es kurz.", b["note"]!!.jsonPrimitive.content)
        val ev = b["ev"]!!.jsonArray.map { it.jsonObject }
        assertEquals(7, ev.size)
        assertEquals(setOf("t", "k", "m", "p", "s", "ms"), keys(ev[2]))
        assertEquals("/api/notes/:id", ev[2]["p"]!!.jsonPrimitive.content)
        assertEquals("GET", ev[2]["m"]!!.jsonPrimitive.content)
        assertEquals(setOf("t", "k", "st", "s"), keys(ev[4]))
        val err = ev[6]
        assertEquals("err", err["k"]!!.jsonPrimitive.content)
        assertEquals("error", err["kind"]!!.jsonPrimitive.content)
        assertTrue("msg" in err)
        ev.forEach { e -> assertTrue(e["k"]!!.jsonPrimitive.content in ClientLog.EV_KINDS) }
        // ohne Notiz kein Feld; Notiz <= 1000; Groesse <= 64 KB
        assertFalse("note" in ClientLog.diagBody(head, evs, "  "))
        assertEquals(ClientLog.NOTE_MAX, ClientLog.diagBody(head, evs, "x".repeat(5000))["note"]!!.jsonPrimitive.content.length)
        val fat = (0 until 400).map { item("E".repeat(300), it.toLong()).event() }
        val fb = ClientLog.diagBody(head, fat, null)
        assertTrue(fb.toString().toByteArray().size <= ClientLog.DIAG_MAX)
        assertTrue(fb["ev"]!!.jsonArray.size <= ClientLog.RING)
    }

    @Test fun responses() {
        assertEquals("D-7K3FM", ClientLog.diagId("""{"ok": true, "id": "D-7K3FM"}"""))
        assertNull(ClientLog.diagId("""{"ok": true, "id": "D-7K3FO"}"""))       // O nicht im Alphabet
        assertNull(ClientLog.diagId("kaputt"))
        assertEquals(30, ClientLog.retryS("""{"error":"x","retry_s":30}"""))
        assertNull(ClientLog.retryS("{}"))
    }

    // ------------------------------------------------------------------------------------------------ Absturz-Notiz

    @Test fun crashNoteRoundTrip() {
        val e = IllegalArgumentException("Seite \"Geheim\" kaputt")
        e.stackTrace = arrayOf(StackTraceElement("io.github.yggdrasil_sh.saga.MainActivity", "onCreate", "MainActivity.kt", 12))
        val ln = ClientLog.crashLine(e, 5000, "pages")
        assertFalse(ln, ln.contains("Geheim"))
        assertFalse(ln.contains('\n'))
        val text = ln + "\n" + ln + "\nkaputt\n{\"k\":\"error\",\"msg\":\"x\",\"t\":1}\n{\"k\":\"crash\",\"t\":1}\n"
        val items = ClientLog.parseCrash(text)
        assertEquals("gleiche zusammengefasst, kaputte/falsche Zeilen weg", 1, items.size)
        assertEquals(2, items[0].n)
        assertEquals("crash", items[0].k)
        assertEquals("MainActivity.kt", items[0].src)
        assertEquals(12, items[0].line)
        val b = ClientLog.logBody(head, items)!!
        val j = b["items"]!!.jsonArray[0].jsonObject
        assertEquals("crash", j["k"]!!.jsonPrimitive.content)
        assertEquals(2, j["n"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive("pages"), j["screen"])
    }

    @Test fun exitReasons() {
        val ex = listOf(
            ClientLog.Exit(ClientLog.EXIT_ANR, 2_000), ClientLog.Exit(ClientLog.EXIT_ANR, 3_000),
            ClientLog.Exit(ClientLog.EXIT_CRASH, 4_000),            // Java-Absturz: kommt ueber die eigene Notiz
            ClientLog.Exit(ClientLog.EXIT_CRASH_NATIVE, 5_000),
            ClientLog.Exit(ClientLog.EXIT_ANR, 500),                 // vor der letzten Pruefung: schon gemeldet
            ClientLog.Exit(1, 6_000),                                // normal beendet
        )
        val it = ClientLog.exitItems(ex, after = 1_000, prevStart = 1_500)
        assertEquals(2, it.size)
        assertEquals("anr", it[0].k)
        assertEquals(2, it[0].n)
        assertEquals(500L, it[0].t)
        assertEquals("crash", it[1].k)
        assertNull(it[1].stack)
        it.forEach { x -> assertEquals(x.msg, ClientLog.scrub(x.msg)) }
    }
}
