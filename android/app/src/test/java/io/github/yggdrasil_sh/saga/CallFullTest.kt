package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallInvite
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.Invites
import io.github.yggdrasil_sh.saga.call.Invites.Btn
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 (Bugs-DB 2154): voller Anruf - Text des Servers bzw. "jedes Geraet zaehlt", Erneut klingeln gesperrt wie im Web */
class CallFullTest {
    @Test fun vollerAnrufText() {
        assertEquals(UiText.Raw("Der Anruf ist voll – alle 4 Plätze sind belegt."), CallRoom.blockText("full", 4, "  Der Anruf ist voll – alle 4 Plätze sind belegt. "))
        assertEquals(UiText.Res(R.string.room_block_full, 4), CallRoom.blockText("full", 4))
        assertEquals(UiText.Res(R.string.room_block_full, 4), CallRoom.blockText("full", 4, "   "))
        assertEquals(UiText.Res(R.string.room_block_full_mine, 4), CallRoom.blockText("full", 4, mine = true))
        // andere Gruende bleiben feste Texte (auch mit Servertext)
        assertEquals(UiText.Res(R.string.room_block_old), CallRoom.blockText("old", 4, "x"))
        val long = "x".repeat(1000)
        assertEquals(400, (CallRoom.blockText("full", 4, long) as UiText.Raw).text.length)
        val o = Json.parseToJsonElement("""{"error": "Voll (Server)", "join_block": "full", "limit": 4}""") as JsonObject
        assertEquals("Voll (Server)", CallRoom.joinError(o))
        assertEquals("alt", CallRoom.joinError(Json.parseToJsonElement("""{"msg": "alt"}""") as JsonObject))
        assertNull(CallRoom.joinError(null))
    }

    @Test fun vollerAnrufSperrtKlingeln() {
        val list = listOf(
            CallInvite(who = "cora", name = "Cora", state = "missed"), CallInvite(who = "dora", name = "Dora", state = "none"),
            CallInvite(who = "emil", name = "Emil", state = "old"), CallInvite(who = "finn", name = "Finn", state = "ringing", since = null),
            CallInvite(who = "gabi", name = "Gabi", state = "removed"),
        )
        val free = Invites.rows(Invites.Snapshot(list, host = true), 0L)
        assertEquals(listOf(Btn.NONE, Btn.RING_AGAIN, Btn.RING, Btn.RING_AGAIN, Btn.OLD), free.map { it.btn })
        assertEquals(listOf("cora", "dora"), Invites.ringAll(free))
        val full = Invites.rows(Invites.Snapshot(list, host = true, full = true), 0L)
        assertEquals(listOf(Btn.NONE, Btn.FULL, Btn.FULL, Btn.FULL, Btn.OLD), full.map { it.btn })
        assertTrue("kein Alle erneut klingeln", Invites.ringAll(full).isEmpty())
        // nicht Gastgeber: Entfernte ohne Knopf, auch voll
        assertEquals(Btn.NONE, Invites.button(Invites.St.REMOVED, host = false, full = true))
        // Book reicht "voll" durch
        val b = Invites.Book()
        b.apply(list, 0, 1.0, null, null, 0L)
        assertTrue(b.snapshot(true, 0L, full = true)!!.full)
        assertFalse(b.snapshot(true, 0L)!!.full)
    }

}
