package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatContent
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSelect
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.ChatSys
import io.github.yggdrasil_sh.saga.chat.X3Hint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat-Mehrfachauswahl (25.10.): Auswahl-Regeln, Kopiertext, Weiterleiten, Seite, Server-Antworten, x3_why */
class ChatSelectTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val labels = ChatSelect.Labels(image = "Bild", voice = "Sprachnachricht", video = "Video", file = { "Datei: $it" })

    private fun msg(
        id: String, seq: Long, text: String? = "t$id", mine: Boolean = false, files: List<ChatFile> = emptyList(),
        sys: ChatSys? = null, call: io.github.yggdrasil_sh.saga.call.CallEntry? = null, who: String = "anna",
    ) = ChatMessage(
        seq, id, ChatPerson(username = who, name = who.replaceFirstChar { it.uppercase() }), "d", seq * 1000, null, null,
        text?.let { ChatContent(text = it, files = files) }, true, mine, call = call, sys = sys,
    )

    private val nameOf = { m: ChatMessage -> m.sender?.name.orEmpty() }
    private val timeOf = { m: ChatMessage -> "10:0${m.seq}" }

    @Test
    fun selectionRules() {
        val a = msg("a", 1)
        val undecrypted = msg("b", 2, text = null)
        val sys = msg("c", 3, sys = ChatSys(type = "ai"))
        assertTrue(ChatSelect.selectable(a))
        assertFalse(ChatSelect.selectable(undecrypted))
        assertFalse(ChatSelect.selectable(sys))
        var sel = ChatSelect.toggle(emptySet(), a)
        assertEquals(setOf("a"), sel)
        assertEquals(sel, ChatSelect.toggle(sel, undecrypted))                     // nicht auswaehlbar: bleibt
        assertEquals(sel, ChatSelect.toggle(sel, sys))
        sel = ChatSelect.toggle(sel, a)
        assertTrue(sel.isEmpty())
        // Reihenfolge des Verlaufs, unabhaengig von der Reihenfolge des Antippens; verschwundene fallen heraus
        val all = listOf(msg("x", 5), msg("y", 2), msg("z", 9))
        assertEquals(listOf("y", "x", "z"), ChatSelect.ordered(all, setOf("z", "x", "y", "gone")).map { it.id })
        assertEquals(setOf("x"), ChatSelect.prune(setOf("x", "gone"), all))
    }

    @Test
    fun deleteForAllOnlyOwn() {
        val own = listOf(msg("a", 1, mine = true), msg("b", 2, mine = true))
        assertTrue(ChatSelect.canDeleteForAll(own, allowed = true))
        assertFalse(ChatSelect.canDeleteForAll(own, allowed = false))              // z. B. schreibgeschuetzt
        assertFalse(ChatSelect.canDeleteForAll(own + msg("c", 3), allowed = true))
        assertFalse(ChatSelect.canDeleteForAll(emptyList(), allowed = true))
    }

    @Test
    fun copyText() {
        val img = ChatFile(id = "f1", n = "foto.jpg", m = "image/jpeg", s = 10)
        val doc = ChatFile(id = "f2", n = "plan.pdf", m = "application/pdf", s = 10)
        val voice = ChatFile(id = "f3", n = "v.ogg", m = "audio/ogg", s = 10, voice = true)
        // eine Nachricht: nur der Text
        assertEquals("Hallo", ChatSelect.copyText(listOf(msg("a", 1, "Hallo")), labels, nameOf, timeOf))
        val sel = listOf(
            msg("a", 1, "Hallo"),
            msg("b", 2, "", files = listOf(img), who = "ben"),
            msg("c", 3, "siehe", files = listOf(doc)),
            msg("d", 4, "", files = listOf(voice), who = "ben"),
        )
        assertEquals(
            "Anna, 10:01: Hallo\nBen, 10:02: [Bild]\nAnna, 10:03: [Datei: plan.pdf] siehe\nBen, 10:04: [Sprachnachricht]",
            ChatSelect.copyText(sel, labels, nameOf, timeOf),
        )
        // Seitenverweise als Titel statt [[note:ID]]
        assertEquals("📄 Plan und 📄 …", ChatSelect.copyText(listOf(msg("e", 1, "[[note:7]] und [[note:8]]")), labels, nameOf, timeOf) { if (it == 7L) "Plan" else null })
    }

    @Test
    fun forwardPlan() {
        val small = ChatFile(id = "f1", n = "a.png", m = "image/png", s = 100)
        val big = ChatFile(id = "f2", n = "b.bin", m = "application/octet-stream", s = 5_000)
        val v2 = ChatFile(id = "f3", n = "film.mp4", m = "video/mp4", s = 100, x = 2)
        val voice = ChatFile(id = "f4", n = "v.ogg", m = "audio/ogg", s = 100, voice = true)
        val p = ChatSelect.forwardPlan(msg("a", 1, "Text", files = listOf(small, big, v2, voice)), 1_000, "[X]")!!
        assertEquals(listOf("f1"), p.files.map { it.id })
        assertEquals(3, p.dropped)
        assertEquals("Text\n[X]", p.text)
        // nur Anhaenge, keiner geht: Hinweis als Text
        assertEquals("[X]", ChatSelect.forwardPlan(msg("b", 2, "", files = listOf(voice)), 1_000, "[X]")!!.text)
        // ohne Inhalt bzw. nicht entschluesselt: nichts zu senden
        assertNull(ChatSelect.forwardPlan(msg("c", 3, ""), 1_000, "[X]"))
        assertNull(ChatSelect.forwardPlan(msg("d", 4, null), 1_000, "[X]"))
        assertEquals(50, ChatSelect.FORWARD_MAX)
        // Ziele: nicht der Ausgangs-Chat, keine Anfragen/schreibgeschuetzten/archivierten, Suche im Namen
        val chats = listOf(
            ChatSummary(1, name = "Hier"), ChatSummary(2, name = "Team Nord"), ChatSummary(3, name = "Anfrage", request = true),
            ChatSummary(4, name = "Alt", readonly = true), ChatSummary(5, name = "Team Sued"), ChatSummary(6, name = "Team Archiv", archived = true),
        )
        assertEquals(listOf(2L, 5L), ChatSelect.forwardTargets(chats, 1, "", { it.name.orEmpty() }).map { it.id })
        assertEquals(listOf(5L), ChatSelect.forwardTargets(chats, 1, " sUED ", { it.name.orEmpty() }).map { it.id })
    }

    @Test
    fun pageContent() {
        assertEquals("Chat Team – 25.10.2026", ChatSelect.pageTitle("Team", "25.10.2026"))
        val img = ChatFile(id = "f1", n = "x.jpg", m = "image/jpeg", s = 1)
        val p = ChatSelect.pageParagraphs(
            listOf(msg("a", 1, "a < b\nzweite"), msg("b", 2, "", files = listOf(img), who = "b&c")), labels, nameOf, timeOf,
        )
        assertEquals("<b>Anna</b> · 10:01<br>a &lt; b<br>zweite", p[0])
        assertEquals("<b>B&amp;c</b> · 10:02<br>[Bild]", p[1])
    }

    @Test
    fun serverAnswersAndFallback() {
        val r = ChatSelect.parseDelete("""{"deleted":["m1","m2"],"failed":{"m3":"not_own"}}""")
        assertEquals(listOf("m1", "m2"), r.deleted)
        assertEquals(mapOf("m3" to "not_own"), r.failed)
        assertEquals(ChatSelect.DeleteResult(emptyList(), emptyMap()), ChatSelect.parseDelete("{}"))
        assertEquals(ChatSelect.DeleteResult(emptyList(), emptyMap()), ChatSelect.parseDelete("kaputt"))
        assertEquals(3, ChatSelect.parseHidden("""{"hidden":3}"""))
        assertNull(ChatSelect.parseHidden("""{"ok":true}"""))
        // Rueckfall nur bei fehlendem Endpunkt, nicht bei z. B. 403
        assertTrue(ChatSelect.isMissingEndpoint(404))
        assertTrue(ChatSelect.isMissingEndpoint(405))
        assertFalse(ChatSelect.isMissingEndpoint(403))
        assertFalse(ChatSelect.isMissingEndpoint(500))
        assertEquals(listOf("a", "b"), ChatSelect.hiddenIds(json.parseToJsonElement("""{"chat_id":4,"ids":["a","b"]}""").jsonObject))
        assertEquals(emptyList<String>(), ChatSelect.hiddenIds(json.parseToJsonElement("""{"chat_id":4}""").jsonObject))
        // Pakete: messages/delete hoechstens 100, hide hoechstens 500; doppelte nur einmal
        val ids = (1..250).map { "m$it" } + "m1"
        assertEquals(listOf(100, 100, 50), ChatSelect.chunks(ids, ChatSelect.DELETE_MAX).map { it.size })
        assertEquals(listOf(250), ChatSelect.chunks(ids, ChatSelect.HIDE_MAX).map { it.size })
    }

    @Test
    fun x3WhyParsing() {
        fun sum(extra: String) = json.decodeFromString(ChatSummary.serializer(), """{"id":1$extra}""")
        // Feld fehlt (aelterer Server) bzw. x3_ok: kein Hinweis
        assertNull(X3Hint.of(sum("")))
        assertNull(X3Hint.of(sum(""","x3_ok":false""")))
        assertNull(X3Hint.of(sum(""","x3_ok":true,"x3_why":"devices"""")))
        assertNull(X3Hint.of(sum(""","x3_ok":false,"x3_why":null""")))
        assertEquals(X3Hint.Setting, X3Hint.of(sum(""","x3_ok":false,"x3_why":"setting"""")))
        assertEquals(X3Hint.Readonly, X3Hint.of(sum(""","x3_ok":false,"x3_why":"readonly"""")))
        assertEquals(X3Hint.Pending, X3Hint.of(sum(""","x3_ok":false,"x3_why":"pending"""")))
        assertEquals(X3Hint.Other, X3Hint.of(sum(""","x3_ok":false,"x3_why":"neu"""")))
        assertEquals(
            X3Hint.Devices(listOf("Linux-Rechner", "Safari · iOS"), 2, 1_800_000_000L),
            X3Hint.of(sum(""","x3_ok":false,"x3_why":"devices","x3_wait":{"mine":["Linux-Rechner","Safari · iOS"],"others":2,"until":1800000000.5}""")),
        )
        // ohne x3_wait: leer, kein Stichtag
        assertEquals(X3Hint.Devices(emptyList(), 0, null), X3Hint.of(sum(""","x3_ok":false,"x3_why":"devices"""")))
        assertEquals(
            X3Hint.Server(listOf("chat.example.org")),
            X3Hint.of(sum(""","x3_ok":false,"x3_why":"server","x3_hosts":["chat.example.org"]""")),
        )
        assertEquals(X3Hint.Server(emptyList()), X3Hint.of(sum(""","x3_ok":false,"x3_why":"server","x3_hosts":null""")))
    }
}
