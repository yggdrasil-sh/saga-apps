package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ApiMessage
import io.github.yggdrasil_sh.saga.chat.ChatMe
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.ChatSys
import io.github.yggdrasil_sh.saga.chat.ChatX3
import io.github.yggdrasil_sh.saga.chat.EditReject
import io.github.yggdrasil_sh.saga.chat.FedSysText
import io.github.yggdrasil_sh.saga.chat.OpenedReaction
import io.github.yggdrasil_sh.saga.chat.Reaction
import io.github.yggdrasil_sh.saga.chat.X3Limits
import io.github.yggdrasil_sh.saga.data.CHAT_LEVEL
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat-Grundlagen (ChatX3): Reaktionen, Bearbeiten, Stufe 3 im Chat-Objekt, Systemzeile, Faehigkeiten des Servers */
class ChatX3LogicTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    @Test
    fun reactionsLogic() {
        val r = { by: String, ts: Long -> Reaction(by, "d", ts, "c$ts", "s") }
        assertEquals(listOf(1L, 5L), ChatX3.newest(listOf(r("a", 2), r("a", 5), r("b", 1), r("a", 3))).map { it.ts })
        assertEquals(listOf(5L, 9L), ChatX3.merge(listOf(r("a", 5)), listOf(r("a", 4), r("b", 9))).map { it.ts })     // aelteres ersetzt nichts
        val o = listOf(OpenedReaction("~me", "👍", 3), OpenedReaction("~b", "❤️", 1), OpenedReaction("~c", "👍", 2), OpenedReaction("~d", "", 4))
        val chips = ChatX3.chips(o, "~me")
        assertEquals(listOf("❤️", "👍"), chips.map { it.e })                                  // Reihenfolge der ersten Reaktion
        assertEquals(listOf(1, 2), chips.map { it.n })
        assertEquals(listOf(false, true), chips.map { it.mine })
        assertEquals(listOf("~c", "~me"), chips[1].who)
        assertEquals("👍", ChatX3.mine(o, "~me"))
        assertNull(ChatX3.mine(o, "~d"))
        assertEquals("", ChatX3.toggle("👍", "👍"))                                          // gleiches nochmal nimmt zurueck
        assertEquals("😂", ChatX3.toggle("👍", "😂"))
        val l = ChatX3.applyLocal(o, "~me", "😂", 9)
        assertEquals("😂", ChatX3.mine(l, "~me"))
        assertEquals(1, l.count { it.by == "~me" })
        assertNull(ChatX3.mine(ChatX3.applyLocal(o, "~me", "", 9), "~me"))
        assertEquals(6, ChatX3.QUICK.size)
        assertEquals(500L, ChatX3.DEBOUNCE_MS)
    }

    @Test
    fun emojiAndBody() {
        listOf("👍", "❤️", "👩🏽‍💻", "🇩🇪", "1️⃣", "🏳️‍🌈", "😂").forEach { assertTrue(it, ChatX3.emojiOk(it)) }
        listOf("👍👍", "", "\u0007", "🇩🇪🇫🇷", "ab").forEach { assertFalse(it, ChatX3.emojiOk(it)) }
        assertEquals("👍", ChatX3.reactBody("{\"e\":\"👍\",\"ts\":5}", 5))
        assertEquals("", ChatX3.reactBody("{\"e\":\"\",\"ts\":5}", 5))                          // zurueckgenommen
        assertNull(ChatX3.reactBody("{\"e\":\"👍\",\"ts\":6}", 5))                              // andere Zeit
        assertNull(ChatX3.reactBody("{\"e\":\"👍\",\"ts\":5,\"x\":1}", 5))                      // Zusatzfelder
        assertNull(ChatX3.reactBody("{\"e\":\"👍\",\"ts\":5.5}", 5))
        assertNull(ChatX3.reactBody("{\"e\":\"👍\",\"ts\":\"5\"}", 5))
        assertNull(ChatX3.reactBody("{\"e\":\"" + "x".repeat(33) + "\",\"ts\":5}", 5))           // zu lang
        assertNull(ChatX3.reactBody("kein json", 5))
        assertEquals("{\"e\":\"a\\\"b\",\"ts\":1}", ChatX3.reactBodyJson("a\"b", 1))
        assertEquals("{\"e\":\"👍\",\"ts\":1}", ChatX3.reactBodyJson("👍", 1))                // UTF-8 wie JSON.stringify
    }

    @Test
    fun editRules() {
        val lim = X3Limits()
        val now = 1_000_000.0
        fun ok(mine: Boolean = true, text: Boolean = true, ai: Boolean = false, voice: Boolean = false, pending: Boolean = false, age: Double = 60.0, n: Int = 0) =
            ChatX3.canEdit(mine, text, ai, voice, pending, now - age, n, now, lim)
        assertTrue(ok())
        assertFalse(ok(mine = false))
        assertFalse(ok(text = false))
        assertFalse(ok(ai = true))
        assertFalse(ok(voice = true))
        assertFalse(ok(pending = true))
        assertTrue(ok(age = 86_399.0))
        assertFalse(ok(age = 86_400.0))                                                       // 24 h
        assertTrue(ok(n = 19))
        assertFalse(ok(n = 20))                                                               // hoechstens 20-mal
        val body = Json.parseToJsonElement("{\"edit\":\"m\",\"t\":\"neu\"}").jsonObject
        assertNull(ChatX3.editCheck("m", "~a", false, 0.0, "m", "~a", 10.0, body))
        assertEquals(EditReject.OTHER_SENDER, ChatX3.editCheck("m", "~a", false, 0.0, "m", "~b", 10.0, body))
        assertEquals(EditReject.AI_ORIGINAL, ChatX3.editCheck("m", "~a", true, 0.0, "m", "~a", 10.0, body))
        assertEquals(EditReject.WINDOW, ChatX3.editCheck("m", "~a", false, 0.0, "m", "~a", 86_401.0, body))
        assertEquals(EditReject.EDIT_OF_MISMATCH, ChatX3.editCheck("x", "~a", false, 0.0, "m", "~a", 1.0, body))
        assertEquals(EditReject.EDIT_OF_MISMATCH, ChatX3.editCheck("m", "~a", false, 0.0, "m", "~a", 1.0, null))
        assertEquals("{\"edit\":\"m1\",\"t\":\"Zeile\\n\\\"2\\\"\"}", ChatX3.editBody("m1", "Zeile\n\"2\""))
    }

    @Test
    fun level3ChatObject() {
        val c = json.decodeFromString(ChatSummary.serializer(), """{"id":5,"kind":"dm","v":3,"ref":"0123456789abcdef0123456789abcdef@a.example",
            "fed":false,"home_self":true,"my_handle":"~aaaaaaaaaaaaaaaa@a.example","x3_ok":true,"v3":{"seq":41,"at":1.0,"forced":false},
            "members":[{"username":"~bbbbbbbbbbbbbbbb@a.example","handle":"~bbbbbbbbbbbbbbbb@a.example","name":"Bea","host":"a.example"},
                       {"username":"ich","handle":"~aaaaaaaaaaaaaaaa@a.example","name":"Ich","me":true,"host":"a.example"}]}""")
        assertTrue(c.isFed)                                          // Krypto v2 (Handles, chat_ref)
        assertFalse(c.remote)                                        // aber kein Chat ueber Servergrenzen
        assertEquals(c.ref, c.cryptoId)
        assertEquals(41L, c.v3Seq)
        assertTrue(ChatX3.chatOk(c))
        assertFalse(ChatX3.chatOk(c.copy(request = true)))
        assertFalse(ChatX3.chatOk(c.copy(readonly = true)))
        assertFalse(ChatX3.chatOk(c.copy(x3Ok = false)))
        assertFalse(ChatX3.chatOk(c.copy(x3Ok = null)))             // Server ohne Stufe 3
        assertTrue(c.copy(v = 2).remote)
        val v1 = c.copy(v = 1)
        assertFalse(v1.isFed)
        assertEquals("5", v1.cryptoId)
        assertEquals(3, CHAT_LEVEL)
    }

    @Test
    fun messageExtras() {
        val m = json.decodeFromString(ApiMessage.serializer(), """{"seq":7,"id":"m","sender_handle":"~a","created":10.5,
            "rx":[{"by":"~b","dev":"d","ts":5,"ct":"c","sig":"s"}],"edited_at":20.0,"edit_n":2,
            "edit":{"seq":9,"id":"e","kind":"edit","edit_of":"m","sender_handle":"~a","body_ct":"x","created":19.0}}""")
        assertEquals(1, m.rx.size)
        assertEquals("~b", m.rx[0].by)
        assertEquals(2, m.editN)
        assertEquals(20.0, m.editedAt!!, 0.0)
        assertEquals("m", m.edit!!.editOf)
        assertEquals("edit", m.edit!!.kind)
        val me = json.decodeFromString(ChatMe.serializer(), """{"features":["receipts","chat3","react","edit","voice"],
            "x3_limits":{"edit_window":3600,"edit_max":5,"voice_max_s":120,"voice_max_bytes":1000}}""")
        assertTrue(ChatX3.has(me, "react") && ChatX3.has(me, "voice"))
        assertFalse(ChatX3.has(null, "react"))
        assertEquals(3600.0, me.x3Limits!!.editWindow, 0.0)
        assertEquals(5, me.x3Limits!!.editMax)
        assertEquals(120.0, me.x3Limits!!.voiceMaxS, 0.0)
        assertEquals(24, me.x3Limits!!.voiceKbps)                                             // Standard
    }

    @Test
    fun cryptoSystemLine() {
        val s = json.decodeFromString(ChatSys.serializer(), """{"type":"crypto","level":3}""")
        assertEquals(3, s.level)
        assertEquals(UiText.Res(R.string.x3_sys_crypto), FedSysText.text(s, null))
        val v = json.decodeFromString(ChatSys.serializer(), """{"type":"crypto","level":3,"verify":true}""")
        assertEquals(UiText.Res(R.string.x3_sys_crypto_verify), FedSysText.text(v, null))
    }

    @Test
    fun jsStringLikeBrowser() {
        assertEquals("\"\\u0001x\\n\"", ChatX3.jsString("\u0001x\n"))
        assertEquals("\"ä€👍\"", ChatX3.jsString("ä€👍"))
        val obj = Json.parseToJsonElement(ChatX3.editBody("a", "ä\t\\")) as JsonObject
        assertEquals("ä\t\\", ChatX3.editText(obj))
    }
}
