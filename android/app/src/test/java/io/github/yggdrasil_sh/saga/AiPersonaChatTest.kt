package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.AiChatLogic
import io.github.yggdrasil_sh.saga.ai.AiChunk
import io.github.yggdrasil_sh.saga.ai.AiLogic
import io.github.yggdrasil_sh.saga.ai.AiPersona
import io.github.yggdrasil_sh.saga.ai.AiPersonaLogic
import io.github.yggdrasil_sh.saga.ai.AiReplyCheck
import io.github.yggdrasil_sh.saga.ai.AiStatus
import io.github.yggdrasil_sh.saga.chat.ApiAiRef
import io.github.yggdrasil_sh.saga.chat.ApiMessage
import io.github.yggdrasil_sh.saga.chat.ChatContent
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1: Persona der KI und KI im Chat - nur auf Zuruf (docs/APP-API-KI.md) */
class AiPersonaChatTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    // ------------------------------------------------------------ Hash / Beleg

    @Test
    fun sha256WieDerServer() {
        // Server: hashlib.sha256(text.strip().encode("utf-8")).hexdigest()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AiChatLogic.sha256(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AiChatLogic.sha256("abc"))
        // Leerraum am Anfang/Ende zaehlt nicht
        assertEquals(AiChatLogic.sha256("abc"), AiChatLogic.sha256("  abc\n"))
        // UTF-8 (Umlaute, Emoji)
        assertEquals(64, AiChatLogic.sha256("Zug fährt um 9 🚆").length)
        assertFalse(AiChatLogic.sha256("fährt") == AiChatLogic.sha256("fahrt"))
    }

    @Test
    fun belegPruefen() {
        val text = "Der Zug fährt um 9:12."
        val sha = AiChatLogic.sha256(text)
        val body = json.decodeFromString(ChatContent.serializer(), ChatLogic.aiReplyContent(text, "r1", "Marin"))
        assertEquals("r1", body.aiReply)
        assertEquals("Marin", body.aiName)
        assertEquals(AiReplyCheck.OK, ChatLogic.aiCheck(body, ApiAiRef("r1", sha, "ben", "q1")))
        // Gross-/Kleinschreibung des Hex egal
        assertEquals(AiReplyCheck.OK, ChatLogic.aiCheck(body, ApiAiRef("r1", sha.uppercase())))
        // Text veraendert -> Warnung
        val forged = body.copy(text = "Der Zug fällt aus.")
        assertEquals(AiReplyCheck.BAD, ChatLogic.aiCheck(forged, ApiAiRef("r1", sha)))
        // andere Beleg-Kennung -> Warnung
        assertEquals(AiReplyCheck.BAD, ChatLogic.aiCheck(body, ApiAiRef("r2", sha)))
        // Beleg fehlt (ohne ai_reply gesendet) -> Warnung
        assertEquals(AiReplyCheck.BAD, ChatLogic.aiCheck(body, null))
        // normale Nachricht: keine KI-Antwort, auch wenn der Server (faelschlich) einen Beleg liefert
        val plain = json.decodeFromString(ChatContent.serializer(), ChatLogic.content("hallo", emptyList(), emptyList()))
        assertEquals(AiReplyCheck.NONE, ChatLogic.aiCheck(plain, ApiAiRef("r1", AiChatLogic.sha256("hallo"))))
        // kaputte Angabe macht die Nachricht nicht unlesbar, nur unbestaetigt
        val broken = json.decodeFromString(ChatContent.serializer(), """{"v":1,"type":"text","text":"x","ai":"nope"}""")
        assertEquals("x", broken.text)
        assertEquals(AiReplyCheck.BAD, ChatLogic.aiCheck(broken, ApiAiRef("r1", AiChatLogic.sha256("x"))))
        assertEquals(AiReplyCheck.NONE, ChatLogic.aiCheck(null, null))
    }

    @Test
    fun antwortInhaltUndApiFelder() {
        val o = Json.parseToJsonElement(ChatLogic.aiReplyContent("Hi", "abc", "Marin")).jsonObject
        assertEquals("text", o["type"]!!.jsonPrimitive.content)
        assertEquals("Hi", o["text"]!!.jsonPrimitive.content)
        assertEquals("abc", o["ai"]!!.jsonObject["reply"]!!.jsonPrimitive.content)
        assertEquals("Marin", o["ai"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val m = json.decodeFromString(
            ApiMessage.serializer(),
            """{"seq":3,"id":"m","ai":{"ref":"r","sha256":"h","by":"ben","q_msg":"q"}}""",
        )
        assertEquals(ApiAiRef("r", "h", "ben", "q"), m.ai)
        assertTrue(json.decodeFromString(ChatSummary.serializer(), """{"id":5,"kind":"dm","ai":true}""").ai)
        assertFalse(json.decodeFromString(ChatSummary.serializer(), """{"id":5,"kind":"dm"}""").ai)
    }

    // ------------------------------------------------------------ @-Erkennung

    @Test
    fun erwaehnung() {
        assertTrue(AiChatLogic.mentions("@Marin wann fährt der Zug?", "Marin"))
        assertTrue(AiChatLogic.mentions("hey @marin?", "Marin"))
        assertTrue(AiChatLogic.mentions("(@MARIN)", "Marin"))
        assertTrue(AiChatLogic.mentions("\"@Marin\"", "Marin"))
        assertTrue(AiChatLogic.mentions("['@Marin']", "Marin"))
        assertTrue(AiChatLogic.mentions("Frage an\n@Marin: hallo", "Marin"))
        assertTrue(AiChatLogic.mentions("@Marin, kurz", "Marin"))
        // danach Buchstabe/Ziffer/_ -> nein
        assertFalse(AiChatLogic.mentions("@Marina hallo", "Marin"))
        assertFalse(AiChatLogic.mentions("@Marin2", "Marin"))
        assertFalse(AiChatLogic.mentions("@Marin_x", "Marin"))
        assertFalse(AiChatLogic.mentions("@Marinä", "Marin"))
        // davor kein Leerraum/Klammer -> nein (z. B. E-Mail-Adresse)
        assertFalse(AiChatLogic.mentions("mail@Marin", "Marin"))
        assertFalse(AiChatLogic.mentions("Marin ohne at", "Marin"))
        // Name mit Leerzeichen und Sonderzeichen (Standardname)
        assertTrue(AiChatLogic.mentions("@Saga KI was meinst du?", "Saga KI"))
        assertFalse(AiChatLogic.mentions("@Saga Kino", "Saga KI"))
        assertTrue(AiChatLogic.mentions("@R2.D2 ok", "R2.D2"))
        assertFalse(AiChatLogic.mentions("@R2xD2 ok", "R2.D2"))
        assertFalse(AiChatLogic.mentions("@Marin", null))
        assertFalse(AiChatLogic.mentions("@", ""))
    }

    @Test
    fun atVorschlag() {
        assertEquals(0 to "", AiChatLogic.atQuery("@", 1))
        assertEquals(6 to "Ma", AiChatLogic.atQuery("hallo @Ma", 9))
        assertNull(AiChatLogic.atQuery("mail@Ma", 7))
        assertNull(AiChatLogic.atQuery("@Ma rin", 7))
        // Cursor in der Mitte: nur Text davor zaehlt
        assertEquals(0 to "M", AiChatLogic.atQuery("@Mxyz", 2))
        assertTrue(AiChatLogic.suggestAi("", "Marin"))
        assertTrue(AiChatLogic.suggestAi("ma", "Marin"))
        assertFalse(AiChatLogic.suggestAi("mx", "Marin"))
        assertFalse(AiChatLogic.suggestAi("", null))
    }

    // ------------------------------------------------------------ Verlauf

    @Test
    fun verlaufHoechstens12VorDerFrage() {
        val msgs = (1..20).map { Triple("m$it", "P$it", "Text $it") } + Triple("q", "Ben", "@Marin?") + Triple("after", "Clara", "danach")
        val h = AiChatLogic.history(msgs, "q")
        assertEquals(12, h.size)
        assertEquals(AiChatLogic.Line("P9", "Text 9"), h.first())           // aelteste zuerst
        assertEquals(AiChatLogic.Line("P20", "Text 20"), h.last())
        assertTrue(h.none { it.text == "@Marin?" || it.text == "danach" })
        // nie mehr als 12, auch wenn mehr verlangt
        assertEquals(12, AiChatLogic.history(msgs, "q", max = 50).size)
        assertEquals(3, AiChatLogic.history(msgs, "q", max = 3).size)
    }

    @Test
    fun verlaufOhneLeereUndGekuerzt() {
        val long = "x".repeat(1000)
        val msgs = listOf(
            Triple("a", "Ann", null),                  // nicht entschluesselbar
            Triple("b", "Ben", ""),                    // nur Anhang
            Triple("c", "Clara", long),
            Triple("d", "", "ohne Namen"),
            Triple("q", "Ben", "@Marin"),
        )
        val h = AiChatLogic.history(msgs, "q")
        assertEquals(2, h.size)
        assertEquals(600, h[0].text.length)
        assertEquals("?", h[1].name)
        // Frage nicht (mehr) in der Liste: alles ausser ihr
        assertEquals(2, AiChatLogic.history(msgs.dropLast(1), "q").size)
        assertEquals(1000, AiChatLogic.question("y".repeat(1500)).length)
    }

    // ------------------------------------------------------------ Umschalter, Persona, Strom

    @Test
    fun umschalterBerechtigung() {
        assertTrue(AiChatLogic.mayToggle("free", "admin", null, null))
        assertFalse(AiChatLogic.mayToggle("free", "member", true, true))
        assertTrue(AiChatLogic.mayToggle("dm", null, false, false))
        assertTrue(AiChatLogic.mayToggle("group", null, true, null))
        assertFalse(AiChatLogic.mayToggle("group", null, false, null))
        assertTrue(AiChatLogic.mayToggle("group", null, null, null))      // unbekannt: Server entscheidet
        assertTrue(AiChatLogic.mayToggle("page", null, null, true))
        assertFalse(AiChatLogic.mayToggle("page", null, null, false))
        assertFalse(AiChatLogic.mayToggle("sonstwas", "admin", true, true))
    }

    @Test
    fun personaAusStatus() {
        val st = json.decodeFromString(
            AiStatus.serializer(),
            """{"enabled":true,"host":"ai.example","chat":true,"persona":{"name":"Marin","custom_name":true,"avatar":"/api/ai/avatar?v=3","avatar_etag":"e1"}}""",
        )
        val p = AiPersonaLogic.effective(st)
        assertNotNull(p)
        assertEquals("Marin", p!!.name)
        assertTrue(st.chat)
        // aelterer Server (ohne persona/chat): bisheriges Verhalten
        val old = json.decodeFromString(AiStatus.serializer(), """{"enabled":true,"host":"x"}""")
        assertNull(AiPersonaLogic.effective(old))
        assertFalse(old.chat)
        // Assistent aus -> keine Persona
        assertNull(AiPersonaLogic.effective(st.copy(enabled = false)))
        // Profilbild nur vom eigenen Server
        assertTrue(AiPersonaLogic.avatarPathOk("/api/ai/avatar?v=3"))
        assertFalse(AiPersonaLogic.avatarPathOk("https://evil.example/api/ai/avatar"))
        assertFalse(AiPersonaLogic.avatarPathOk("/api/avatar/x"))
        assertFalse(AiPersonaLogic.avatarPathOk(null))
        // Zwischenspeicher je ETag: neues ?v= mit gleicher Kennung = gleicher Schluessel
        assertEquals(
            AiPersonaLogic.cacheKey(p, 64),
            AiPersonaLogic.cacheKey(p.copy(avatar = "/api/ai/avatar?v=4"), 64),
        )
        assertFalse(AiPersonaLogic.cacheKey(p, 64) == AiPersonaLogic.cacheKey(p.copy(avatarEtag = "e2"), 64))
        assertFalse(AiPersonaLogic.cacheKey(p, 64) == AiPersonaLogic.cacheKey(p, 128))
        assertEquals(AiPersona("Saga AI"), json.decodeFromString(AiPersona.serializer(), """{"name":"Saga AI","avatar":null}"""))
    }

    @Test
    fun stromDoneMitBeleg() {
        val d = AiLogic.chunk("done", """{"text":"Um 9:12.","reply":{"id":"abc","sha256":"ff"},"persona":{"name":"Marin"},"quota_left":4}""", json)
        assertTrue(d is AiChunk.Done)
        val done = (d as AiChunk.Done).done
        assertEquals("Um 9:12.", done.text)
        assertEquals("abc", done.replyId)
        assertEquals("ff", done.replySha)
        assertEquals(4, done.quotaLeft)
        assertEquals(AiChunk.Reset, AiLogic.chunk("reset", "{}", json))
        // aeltere done-Ereignisse ohne Beleg
        val old = (AiLogic.chunk("done", """{"quota_left":1}""", json) as AiChunk.Done).done
        assertNull(old.replyId)
        assertNull(old.text)
    }
}
