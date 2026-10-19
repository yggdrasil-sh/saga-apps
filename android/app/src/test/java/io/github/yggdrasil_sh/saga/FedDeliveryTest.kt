package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ApiMessage
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.FedDelivery
import io.github.yggdrasil_sh.saga.chat.MessagesResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.3: "⏳ wird zugestellt" bleibt nicht haengen (eigene Nachricht in einem foederierten Direkt-Chat, Heim = anderer Server) */
class FedDeliveryTest {
    private fun msg(seq: Long, id: String, fstate: String?, mine: Boolean = true) =
        ChatMessage(seq, id, null, "dev", 0L, null, null, null, true, mine, fstate = fstate)

    @Test
    fun postWithoutPendingConfirms() {
        assertTrue(FedDelivery.confirmedByPost(false))
        assertFalse(FedDelivery.confirmedByPost(true))
        // ueber das Live-Ereignis schon mit ⏳ geladen, POST bestaetigt -> zugestellt
        val ch = FedDelivery.apply(listOf(msg(5, "a", "pending")), emptyMap(), setOf("a"))
        assertEquals(listOf(0 to "a"), ch.map { it.first to it.second.id })
        assertNull(ch[0].second.fstate)
    }

    @Test
    fun serverStateHealsHangingMessage() {
        // Server 2.3.0: Bestaetigung ohne Ereignis - der Abgleich holt den Stand
        val l = listOf(msg(3, "x", null, mine = false), msg(5, "a", "pending"), msg(6, "b", "pending"))
        val ch = FedDelivery.apply(l, mapOf("x" to null, "a" to null, "b" to "failed"), emptySet()).toMap()
        assertEquals(setOf(1, 2), ch.keys)
        assertNull(ch[1]!!.fstate)
        assertEquals("failed", ch[2]!!.fstate)
    }

    @Test
    fun neverBackToPending() {
        // aelterer Serverstand (Abgleich lief vor dem Ereignis) setzt eine bestaetigte Nachricht nicht wieder auf ⏳
        assertNull(FedDelivery.state(null, "pending", fromServer = true, confirmed = false))
        assertEquals("pending", FedDelivery.state("pending", "pending", fromServer = true, confirmed = false))
        assertNull(FedDelivery.state("pending", "pending", fromServer = true, confirmed = true))
        assertEquals("failed", FedDelivery.state("failed", null, fromServer = false, confirmed = true))
        assertTrue(FedDelivery.apply(listOf(msg(5, "a", null)), mapOf("a" to "pending"), emptySet()).isEmpty())
    }

    @Test
    fun checkOnlyWhileSomethingWaits() {
        val waiting = listOf(msg(4, "o", "pending", mine = false), msg(7, "a", "pending"), msg(9, "b", "pending"))
        assertEquals(7L, FedDelivery.oldestPending(waiting))
        assertNull(FedDelivery.oldestPending(listOf(msg(7, "a", null), msg(8, "c", "failed"))))
        assertTrue(FedDelivery.due(waiting, 0L, 100_000L))
        assertFalse(FedDelivery.due(waiting, 95_000L, 100_000L))
        assertFalse(FedDelivery.due(listOf(msg(7, "a", null)), 0L, 100_000L))
    }

    @Test
    fun goneWhenServerDroppedIt() {
        val l = listOf(msg(7, "a", "pending"), msg(9, "b", "pending"), msg(500, "c", "pending"))
        assertEquals(setOf("a", "b", "c"), FedDelivery.gone(l, mapOf("z" to null), more = false, maxSeq = 8))
        // Seite voll (more): nur bis zur letzten gelieferten werten
        assertEquals(setOf("a"), FedDelivery.gone(l, mapOf("b" to null), more = true, maxSeq = 300))
    }

    @Test
    fun serverFieldDecodes() {
        val j = Json { ignoreUnknownKeys = true }
        val r = j.decodeFromString(MessagesResponse.serializer(),
            """{"messages":[{"seq":5,"id":"a","kind":"msg","fstate":"pending","sender_handle":"~x@h"},{"seq":6,"id":"b","kind":"msg","fstate":null}],"more":false}""")
        assertEquals(mapOf("a" to "pending", "b" to null), r.messages.associate { m: ApiMessage -> m.id to m.fstate })
    }
}
