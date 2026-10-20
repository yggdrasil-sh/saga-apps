package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.AiReplyCheck
import io.github.yggdrasil_sh.saga.chat.ChatMe
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatPrivacy
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.Presence
import io.github.yggdrasil_sh.saga.chat.PresenceHub
import io.github.yggdrasil_sh.saga.chat.PresenceLogic
import io.github.yggdrasil_sh.saga.chat.ReadMark
import io.github.yggdrasil_sh.saga.chat.ReceiptLogic
import io.github.yggdrasil_sh.saga.chat.Tick
import io.github.yggdrasil_sh.saga.chat.TickKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saga 2.4: Gelesen-Haken (Zustandsfolge), Senden nur Sichtbares, Gegenseitigkeit, Online-Abo (Lebenszyklus) */
class ChatStatusTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val anna = ChatPerson("anna", "Anna", me = true)
    private val bert = ChatPerson("bert", "Bert")
    private val cora = ChatPerson("cora", "Cora")

    private fun msg(seq: Long, mine: Boolean, fstate: String? = null, fseq: Long? = null, ai: AiReplyCheck = AiReplyCheck.NONE) =
        ChatMessage(seq, "m$seq", if (mine) anna else bert, "d", seq * 1000, null, null, null, true, mine, ai = ai, fstate = fstate, fseq = fseq)

    private val me24 = ChatMe(enabled = true, uid = "anna", privacy = ChatPrivacy(), features = listOf("receipts", "online"))
    private val dm = ChatSummary(1, kind = "dm", members = listOf(anna, bert), peer = bert)

    @Test
    fun meParsesAndOldServerHides() {
        val m = json.decodeFromString(ChatMe.serializer(),
            """{"enabled":true,"uid":"anna","privacy":{"receipts":true,"online":false},"features":["receipts","online"],
               "status_limits":{"receipt_gap":2.0,"presence_ttl":90,"presence_users":50,"last_round":300}}""")
        assertTrue(ReceiptLogic.has(m, "receipts"))
        assertFalse(ReceiptLogic.privacy(m).online)
        assertEquals(300, m.statusLimits!!.lastRound)
        // aelterer Server: keine Felder -> alles ausblenden (keine Haken, kein Online-Status)
        val old = json.decodeFromString(ChatMe.serializer(), """{"enabled":true,"uid":"anna"}""")
        assertFalse(ReceiptLogic.enabled(old, dm))
        assertNull(ReceiptLogic.onlineFor(old, dm))
        assertEquals(Tick(TickKind.NONE), ReceiptLogic.tick(msg(5, true), ReceiptLogic.has(old, "receipts"), false, false, dm.members, emptyMap()))
        val s = json.decodeFromString(ChatSummary.serializer(), """{"id":3,"v":2,"ref":"aa@b","receipts_ok":false,"online_ok":false,"kind":"dm","peer":{"username":"@~x@b"}}""")
        assertFalse(ReceiptLogic.enabled(me24, s))
        assertNull(ReceiptLogic.onlineFor(me24, s))
    }

    @Test
    fun tickSequenceDirect() {
        val f = true
        // ⏳ wird zugestellt -> ✓ zugestellt -> ✓✓ gelesen
        assertEquals(TickKind.PENDING, ReceiptLogic.tick(msg(0, true, fstate = "pending"), f, true, true, dm.members, emptyMap()).kind)
        val sent = msg(7, true, fseq = 4)
        assertEquals(TickKind.SENT, ReceiptLogic.tick(sent, f, true, true, dm.members, emptyMap()).kind)
        assertEquals(TickKind.SENT, ReceiptLogic.tick(sent, f, true, true, dm.members, mapOf("bert" to ReadMark(3, "x", 1))).kind)
        val t = ReceiptLogic.tick(sent, f, true, true, dm.members, mapOf("bert" to ReadMark(4, "x", 1)))
        assertEquals(Tick(TickKind.READ, 1, true), t)
        // lokal zaehlt seq, nicht fseq
        assertEquals(TickKind.SENT, ReceiptLogic.tick(sent, f, true, false, dm.members, mapOf("bert" to ReadMark(6, "x", 1))).kind)
        assertEquals(TickKind.READ, ReceiptLogic.tick(sent, f, true, false, dm.members, mapOf("bert" to ReadMark(7, "x", 1))).kind)
        // fremde Nachrichten, KI-Antworten, nicht zugestellte: kein Haken
        assertEquals(TickKind.NONE, ReceiptLogic.tick(msg(8, false), f, true, false, dm.members, emptyMap()).kind)
        assertEquals(TickKind.NONE, ReceiptLogic.tick(msg(9, true, ai = AiReplyCheck.OK), f, true, false, dm.members, emptyMap()).kind)
        assertEquals(TickKind.NONE, ReceiptLogic.tick(msg(10, true, fstate = "failed"), f, true, false, dm.members, emptyMap()).kind)
    }

    @Test
    fun tickGroupCountsAndReaders() {
        val members = listOf(anna, bert, cora)
        val m = msg(10, true)
        val one = mapOf("bert" to ReadMark(10, "m10", 5))
        assertEquals(Tick(TickKind.READ, 1, false), ReceiptLogic.tick(m, true, true, false, members, one))
        val two = one + ("cora" to ReadMark(12, "m12", 6))
        assertEquals(Tick(TickKind.READ, 2, true), ReceiptLogic.tick(m, true, true, false, members, two))
        assertEquals(listOf("bert", "cora"), ReceiptLogic.readers(m, false, members, two).map { it.first.username })
        // ich selbst zaehle nie
        assertEquals(1, ReceiptLogic.readers(m, false, members, one + ("anna" to ReadMark(99, "x", 1))).size)
    }

    @Test
    fun mutual() {
        // eigene Bestaetigungen aus: ich sende keine UND sehe keine (nur ✓)
        val off = me24.copy(privacy = ChatPrivacy(receipts = false, online = true))
        assertFalse(ReceiptLogic.enabled(off, dm))
        val reads = mapOf("bert" to ReadMark(7, "m7", 1))
        assertEquals(TickKind.SENT, ReceiptLogic.tick(msg(7, true), true, ReceiptLogic.enabled(off, dm), false, dm.members, reads).kind)
        assertEquals(TickKind.READ, ReceiptLogic.tick(msg(7, true), true, ReceiptLogic.enabled(me24, dm), false, dm.members, reads).kind)
        // Online-Status aus: kein Abo, nichts sehen; Einstellung getrennt
        val noOnline = me24.copy(privacy = ChatPrivacy(receipts = true, online = false))
        assertNull(ReceiptLogic.onlineFor(noOnline, dm))
        assertTrue(ReceiptLogic.enabled(noOnline, dm))
        assertEquals("bert", ReceiptLogic.onlineFor(me24, dm))
        assertNull(ReceiptLogic.onlineFor(me24, dm.copy(kind = "group")))
        assertNull(ReceiptLogic.onlineFor(me24, dm.copy(request = true)))
        // Nachrichtenanfrage/schreibgeschuetzt: keine Bestaetigungen
        assertFalse(ReceiptLogic.enabled(me24, dm.copy(request = true)))
        assertFalse(ReceiptLogic.enabled(me24, dm.copy(readonly = true)))
    }

    @Test
    fun sendOnlyWhatWasSeen() {
        val msgs = listOf(msg(1, false), msg(2, true), msg(3, false), msg(4, true))
        // nichts gesehen -> nichts
        assertNull(ReceiptLogic.want(msgs, false, 0, 0))
        // bis 3 gesehen -> neueste fremde = 3
        assertEquals("m3" to 3L, ReceiptLogic.want(msgs, false, 3, 0))
        // schon bestaetigt -> nichts
        assertNull(ReceiptLogic.want(msgs, false, 3, 3))
        // nur 1 gesehen (nach oben gerollt) -> 1
        assertEquals("m1" to 1L, ReceiptLogic.want(msgs, false, 1, 0))
        // v2: Position ist fseq; ohne fseq nichts
        val fed = listOf(msg(10, false, fseq = 7), msg(11, false))
        assertEquals("m10" to 7L, ReceiptLogic.want(fed, true, 7, 0))
        // gebuendelt: hoechstens eine je 2 s
        assertEquals(1500L, ReceiptLogic.wait(10_500, 10_000, 2_000))
        assertEquals(0L, ReceiptLogic.wait(12_000, 10_000, 2_000))
        // je Person nur das groesste n
        assertTrue(ReceiptLogic.take(null, ReadMark(3, "a", 1)))
        assertFalse(ReceiptLogic.take(ReadMark(5, "b", 1), ReadMark(3, "a", 1)))
        assertTrue(ReceiptLogic.take(ReadMark(3, "a", 1), ReadMark(5, "b", 2)))
    }

    @Test
    fun presenceParsingAndRaster() {
        val o = Json.parseToJsonElement(
            """{"enabled":true,"ttl":90,"users":{"bert":{"online":true,"last":null},"@~abcdefghijklmnop@b.example":{"online":false,"last":1792449900.0}}}""",
        ) as JsonObject
        val (en, users) = PresenceLogic.parse(o)
        assertTrue(en)
        assertEquals(Presence(true, null), users["bert"])
        assertEquals(1792449900.0, users["@~abcdefghijklmnop@b.example"]!!.last!!, 0.0)
        assertNull(PresenceLogic.event(false, null))
        assertTrue(PresenceLogic.missingRemote(listOf("bert", "@~x@b"), mapOf("bert" to Presence(true, null))))
        // 5-Minuten-Raster: mindestens "vor 5 Minuten", in der ersten Stunde volle 5 Minuten
        assertEquals(1000.0 - 300, PresenceLogic.shownLast(1000.0, 990.0), 0.0)
        assertEquals(10_000.0 - 600, PresenceLogic.shownLast(10_000.0, 10_000.0 - 720), 0.0)
        assertEquals(1.0, PresenceLogic.shownLast(100_000.0, 1.0), 0.0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun presenceLifecycle() = runTest {
        val posts = ArrayList<JsonObject>()
        var allowed = true
        val hub = PresenceHub(backgroundScope, post = { b ->
            posts.add(b)
            """{"enabled":true,"ttl":90,"users":{"bert":{"online":true,"last":null}}}"""
        }, allowed = { allowed })
        fun users(i: Int) = posts[i]["users"]!!.jsonArray.map { it.jsonPrimitive.content }
        fun leave(i: Int) = posts[i]["leave"]?.jsonPrimitive?.content == "true"

        // sichtbar -> nach 400 ms gebuendelt abonnieren
        hub.show("list", listOf("bert"))
        hub.show("chat:1", listOf("bert"))
        advanceTimeBy(399); runCurrent()
        assertEquals(0, posts.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, posts.size)
        assertEquals(listOf("bert"), users(0))
        assertEquals(Presence(true, null), hub.state.value["bert"])
        // erneuern alle 60 s
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, posts.size)
        // Live-Ereignis: abgeschaltet -> entfernen
        hub.onEvent("bert", false, null)
        assertNull(hub.state.value["bert"])
        // eine Ansicht weg, andere noch sichtbar -> Abo bleibt
        hub.hide("chat:1")
        advanceTimeBy(500); runCurrent()
        assertTrue(hub.active)
        // Hintergrund: alles weg -> abmelden (leave), Stand verwerfen, keine weiteren Abfragen
        hub.hide("list")
        runCurrent()
        assertFalse(hub.active)
        assertTrue(leave(posts.size - 1))
        assertTrue(hub.state.value.isEmpty())
        val n = posts.size
        advanceTimeBy(300_000); runCurrent()
        assertEquals(n, posts.size)
        // eigene Einstellung aus: gar kein Abo
        allowed = false
        hub.show("list", listOf("bert"))
        advanceTimeBy(120_000); runCurrent()
        assertEquals(n, posts.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun presenceRetriesRemoteThenNormal() = runTest {
        var calls = 0
        val hub = PresenceHub(backgroundScope, post = { calls++; """{"enabled":true,"users":{}}""" }, allowed = { true })
        hub.show("chat:2", listOf("@~abcdefghijklmnop@b.example"))
        advanceTimeBy(401); runCurrent()
        assertEquals(1, calls)
        // eine Minute lang alle 10 s
        repeat(6) { advanceTimeBy(10_000); runCurrent() }
        assertEquals(7, calls)
        // danach im 60-s-Takt
        advanceTimeBy(10_000); runCurrent()
        assertEquals(7, calls)
        advanceTimeBy(50_000); runCurrent()
        assertEquals(8, calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun presenceDisabledByServer() = runTest {
        var disabled = false
        var calls = 0
        val hub = PresenceHub(backgroundScope, post = { calls++; """{"enabled":false,"users":{}}""" }, allowed = { !disabled }, onDisabled = { disabled = true })
        hub.show("list", listOf("bert"))
        advanceTimeBy(401); runCurrent()
        assertTrue(disabled)
        advanceTimeBy(200_000); runCurrent()
        assertEquals(1, calls)
    }
}
