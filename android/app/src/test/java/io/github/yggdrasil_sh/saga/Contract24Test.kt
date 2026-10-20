package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.PeopleResult
import io.github.yggdrasil_sh.saga.data.NcBlockStatus
import io.github.yggdrasil_sh.saga.data.NcLogic
import io.github.yggdrasil_sh.saga.data.NcPageStatus
import io.github.yggdrasil_sh.saga.data.ShareTargets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.4 Server-Vertrag nach Sicherheits-/Web-Runde 2 (docs/APP-API.md): Schluessel nur fuer Chat-Partner, sub, Nextcloud-Autor */
class Contract24Test {
    private val json = Json { ignoreUnknownKeys = true }
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun src(p: String) = File("src/main/java/io/github/yggdrasil_sh/saga/$p").readText()

    @Test fun keysAnswerUnderRequestedId() {
        val r = o("""{"users": {"anna": {"ident_pub": "QUJD"}, "~abcdefghijklmnop": {"ident_pub": "REVG"}, "otto": null}}""")
        assertEquals("QUJD", (ChatLogic.keyEntry(r, "anna")?.get("ident_pub") as JsonPrimitive).content)
        assertEquals("REVG", (ChatLogic.keyEntry(r, "~abcdefghijklmnop")?.get("ident_pub") as JsonPrimitive).content)   // Handle bleibt Handle
        assertNull(ChatLogic.keyEntry(r, "otto"))                    // null = noch keine Chats eingerichtet
        assertNull(ChatLogic.keyEntry(r, "fremd"))                   // ab 2.4: kein gemeinsamer Chat -> fehlt
        assertNull(ChatLogic.keyEntry(null, "anna"))
        assertNull(ChatLogic.keyEntry(o("""{"users": {}}"""), "anna"))
    }

    @Test fun newChatCreatesFirstThenSealsWithRecipients() {
        // Neue Unterhaltung / Gruppe anlegen: nie Schluessel vor dem Anlegen - erst POST /api/chats, gesendet wird dann mit
        // den Empfaengern des angelegten Chats (GET /api/chats/{id}/recipients); Schluessel nur fuer Mitglieder bzw. Anruf-Gegenueber
        val dialogs = src("ui/chat/ChatDialogs.kt")
        val service = src("chat/ChatService.kt")
        val session = src("chat/ChatSession.kt")
        assertFalse(dialogs.contains("api/chat/keys"))
        val create = service.substringAfter("suspend fun create(body: JsonObject)").substringBefore("suspend fun pages(")
        assertTrue(create.contains("\"api/chats\""))
        assertFalse(create.contains("api/chat/keys"))
        assertTrue(session.contains("api/chats/\$chatId/recipients"))
        val idents = session.substringAfter("suspend fun loadIdents()").substringBefore("private fun handles()")
        assertTrue(idents.contains("summary?.members"))               // nur Mitglieder eines bestehenden Chats
    }

    @Test fun sublineForNamesakes() {
        val p = json.decodeFromString(PeopleResult.serializer(), """{"users":[{"username":"~aaaaaaaaaaaaaaaa","name":"Anna","sub":"anna-b","ready":true},
            {"username":"~bbbbbbbbbbbbbbbb","name":"Anna","ready":false}]}""")
        assertEquals("anna-b", p.users[0].sub)
        assertNull(p.users[1].sub)                                    // aelterer Server ohne Feld
        assertEquals("anna-b · hat Chats noch nicht eingerichtet", ChatLogic.subline("anna-b", "hat Chats noch nicht eingerichtet"))
        assertEquals("x", ChatLogic.subline(" ", null, "x"))
        assertNull(ChatLogic.subline(null, ""))
        val m = json.decodeFromString(ChatPerson.serializer(), """{"username":"anna","ref":"~aaaaaaaaaaaaaaaa","name":"Anna","sub":"~aaaaaaaaaaaaaaaa"}""")
        assertEquals("~aaaaaaaaaaaaaaaa", m.sub)
        val t = json.decodeFromString(ShareTargets.serializer(), """{"users":[{"username":"~aaaaaaaaaaaaaaaa","name":"Anna","sub":"anna-b"}]}""")
        assertEquals("anna-b", t.users[0].sub)
    }

    @Test fun nextcloudForeignAuthorIsHandle() {
        // fremder Block: author = Handle (Seiteninhalt und Status gleich), ?a= traegt ihn; eigener Block bleibt eigener Name
        val d = buildJsonObject { put("fileid", "4711"); put("author", "~aaaaaaaaaaaaaaaa"); put("inst", "https://nc.example") }
        val st = NcPageStatus(true, listOf(NcBlockStatus("4711", "~aaaaaaaaaaaaaaaa", ok = true, name = "a.pdf")))
        assertEquals("a.pdf", NcLogic.statusFor(st, d, remotePage = false)?.name)
        assertTrue(NcLogic.proxyPath(5, d)!!.contains("?a=%7Eaaaaaaaaaaaaaaaa"))
        assertEquals(setOf(NcLogic.key(d)), NcLogic.foreignNew(o("""{"blocks":[{"type":"nextcloud","data":$d}]}"""), emptySet(), "me"))
        assertTrue(NcLogic.foreignNew(o("""{"blocks":[{"type":"nextcloud","data":$d}]}"""), setOf(NcLogic.key(d)), "me").isEmpty())
    }
}
