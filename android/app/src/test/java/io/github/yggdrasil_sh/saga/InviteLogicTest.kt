package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallInvite
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.CallState
import io.github.yggdrasil_sh.saga.call.InviteReply
import io.github.yggdrasil_sh.saga.call.Invites
import io.github.yggdrasil_sh.saga.call.Invites.Btn
import io.github.yggdrasil_sh.saga.call.Invites.St
import io.github.yggdrasil_sh.saga.call.RingLogic
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.5 (L7) "Erneut klingeln" (Vertrag Server docs/APP-API.md "Anrufe: Erneut klingeln und »Nicht dabei«"): Zustaende,
 * Knoepfe, Reihenfolge, Ablauf des Klingelns ueber die Server-Uhr, eigener rv-Zaehler, Klick-Ablauf, Fehlertexte und
 * eigenes erneutes Klingeln (Alter ab invite_since).
 */
class InviteLogicTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private fun x(who: String, state: String, since: Double? = null, name: String = who.replaceFirstChar { it.uppercase() } + " Beispiel") =
        CallInvite(who = who, name = name, state = state, since = since)

    // Server-Uhr 1000 s, Geraete-Uhr 400 s (Abstand +600 s)
    private val dev = 400_000L
    private val skew = 600_000L

    @Test
    fun parsesCallObjectAndReply() {
        val s = """{"call": {"id": "c1", "kind": "group", "invite": "ringing", "invite_since": 990.5, "now": 1000.0, "ring_s": 45,
            "rv": 7.5, "invites_more": 2, "invites": [
              {"who": "cora", "name": "Cora Beispiel", "avatar": "/api/avatar/cora", "state": "ringing", "since": 999.0},
              {"who": "@~abcdefghijklmnop@peer.example", "name": "Person auf peer.example (mnop)", "host": "peer.example",
               "handle": "~abcdefghijklmnop@peer.example", "state": "missed", "since": 950.0},
              {"who": "dora", "name": "Dora Beispiel", "state": "old", "since": 900.0, "neu": 1}]}}"""
        val k = json.decodeFromString(CallState.serializer(), s).call
        assertEquals(990.5, k.inviteSince!!, 0.0)
        assertEquals(1000.0, k.now!!, 0.0)
        assertEquals(2, k.invitesMore)
        assertEquals(3, k.invites!!.size)
        assertEquals("/api/avatar/cora", k.invites!![0].avatar)
        assertEquals("peer.example", k.invites!![1].host)
        // aelterer Server: Feld fehlt -> null (Abschnitt nicht zeigen)
        val old = json.decodeFromString(CallState.serializer(), """{"call": {"id": "c1", "kind": "group"}}""").call
        assertNull(old.invites)
        assertNull(old.inviteSince)
        val r = json.decodeFromString(InviteReply.serializer(),
            """{"ok": true, "n": 1, "rv": 1794677400.123, "now": 1794677400.125, "invites_more": 0, "invites": [{"who": "cora", "state": "ringing", "since": 1794677400.12}]}""")
        assertEquals(1, r.n)
        assertEquals("ringing", r.invites!![0].state)
    }

    @Test
    fun parsesEvent() {
        val o = json.decodeFromString(JsonObject.serializer(),
            """{"call_id": "c", "rv": 12.5, "now": 1000.0, "ring_s": 30, "invites_more": 1, "invites": [{"who": "cora", "state": "declined"}, {"name": "ohne who"}]}""")
        val l = Invites.parse(o["invites"])!!
        assertEquals(listOf("cora"), l.map { it.who })
        assertNull(Invites.parse(o["fehlt"]))
        assertEquals(12.5, Invites.num(o, "rv")!!, 0.0)
        assertEquals(30, Invites.int(o, "ring_s"))
    }

    @Test
    fun statesAndUnknown() {
        assertEquals(St.RINGING, Invites.st("ringing"))
        assertEquals(St.OLD, Invites.st("old"))
        assertEquals(St.NONE, Invites.st("spaeter_neu"))
        assertEquals(St.NONE, Invites.st(null))
    }

    @Test
    fun buttons() {
        assertEquals(Btn.NONE, Invites.button(St.RINGING, host = true))
        assertEquals(Btn.RING_AGAIN, Invites.button(St.DECLINED, false))
        assertEquals(Btn.RING_AGAIN, Invites.button(St.MISSED, false))
        assertEquals(Btn.RING_AGAIN, Invites.button(St.LEFT, false))
        assertEquals(Btn.RING, Invites.button(St.NONE, false))
        // entfernt: nur der Gastgeber
        assertEquals(Btn.NONE, Invites.button(St.REMOVED, false))
        assertEquals(Btn.RING_AGAIN, Invites.button(St.REMOVED, true))
        assertEquals(Btn.OLD, Invites.button(St.OLD, true))
    }

    @Test
    fun orderByStateThenName() {
        val s = Invites.Snapshot(
            listOf(x("dora", "old"), x("emil", "none"), x("cora", "missed", 900.0), x("bert", "declined"), x("anna", "ringing", 990.0),
                x("finn", "left"), x("gus", "removed"), x("alma", "missed", 900.0), x("zoe", "komisch")),
            skewMs = skew,
        )
        val rows = Invites.rows(s, dev)
        assertEquals(listOf("anna", "bert", "alma", "cora", "finn", "emil", "zoe", "gus", "dora"), rows.map { it.x.who })
        assertEquals(St.NONE, rows.first { it.x.who == "zoe" }.st)
        assertEquals(9, Invites.count(s))
        assertEquals(11, Invites.count(s.copy(more = 2)))
    }

    @Test
    fun ringingExpiresByServerClock() {
        // klingelt seit Server 990, ring_s 45 -> bis 1035 (Geraete-Uhr 435 s)
        val s = Invites.Snapshot(listOf(x("cora", "ringing", 990.0)), skewMs = skew, ringS = 45)
        assertEquals(St.RINGING, Invites.state(s.list[0], s, 434_000L))
        assertEquals(St.MISSED, Invites.state(s.list[0], s, 435_000L))
        assertEquals(Btn.RING_AGAIN, Invites.rows(s, 436_000L)[0].btn)
        assertEquals(35_000L, Invites.nextChangeMs(s, dev))
        assertNull(Invites.nextChangeMs(s, 440_000L))
        // ohne since: bleibt klingelnd (Server stellt um)
        val n = Invites.Snapshot(listOf(x("cora", "ringing")), skewMs = skew)
        assertEquals(St.RINGING, Invites.state(n.list[0], n, 999_999_999L))
    }

    @Test
    fun ringAllOnlyWhenMoreThanOne() {
        val s = Invites.Snapshot(listOf(x("cora", "missed", 900.0), x("dora", "old"), x("emil", "ringing", 999.0), x("finn", "left")), skewMs = skew)
        assertEquals(emptyList<String>(), Invites.ringAll(Invites.rows(s, dev)))   // nur eine Person missed/declined/none
        val t = s.copy(list = s.list + x("bert", "declined") + x("gus", "none"))
        assertEquals(listOf("bert", "cora", "gus"), Invites.ringAll(Invites.rows(t, dev)))
        val many = Invites.Snapshot((1..70).map { x("p%02d".format(it), "none") })
        assertEquals(Invites.MAX, Invites.ringAll(Invites.rows(many, dev)).size)
    }

    @Test
    fun visibility() {
        assertFalse(Invites.visible(null))                                  // aelterer Server
        assertFalse(Invites.visible(Invites.Snapshot(emptyList())))         // alle dabei
        assertTrue(Invites.visible(Invites.Snapshot(emptyList(), more = 3)))
        assertTrue(Invites.visible(Invites.Snapshot(listOf(x("cora", "none")))))
    }

    @Test
    fun bookKeepsOwnRvAndClock() {
        val b = Invites.Book()
        assertNull(b.snapshot(false, dev))
        assertFalse(b.apply(null, 0, 5.0, 1000.0, 45, dev))                 // Feld fehlt
        assertTrue(b.apply(listOf(x("cora", "declined")), 0, 10.0, 1000.0, 30, dev))
        assertEquals(skew, b.skewMs)
        assertEquals(30, b.ringS)
        // aelterer Stand nach einem neueren: verworfen
        assertFalse(b.apply(listOf(x("cora", "ringing", 999.0)), 0, 9.0, 1001.0, null, dev))
        assertEquals("declined", b.list!![0].state)
        // gleicher rv und ohne rv: uebernehmen
        assertTrue(b.apply(listOf(x("cora", "missed", 950.0)), 2, 10.0, null, null, dev))
        assertTrue(b.apply(listOf(x("cora", "left", 950.0)), -1, null, null, 0, dev))
        assertEquals(0, b.more)
        assertEquals(30, b.ringS)
        val s = b.snapshot(host = true, deviceMs = dev)!!
        assertTrue(s.host)
        assertEquals(30, s.ringS)
    }

    @Test
    fun clickFlowPendingAndSettle() {
        val b = Invites.Book()
        b.apply(listOf(x("cora", "missed", 900.0), x("dora", "declined")), 0, 1.0, 1000.0, 45, dev)
        val until = b.pending(listOf("cora"), dev)
        var s = b.snapshot(false, dev)!!
        val cora = Invites.rows(s, dev).first { it.x.who == "cora" }
        assertEquals(St.RINGING, cora.st)                                    // sofort "klingelt …" ohne Knopf
        assertEquals(Btn.NONE, cora.btn)
        assertEquals(Invites.PEND_MS, Invites.nextChangeMs(s, dev))
        // neuerer Klick fuer dieselbe Person bleibt, wenn der alte zurueckgenommen wird
        val until2 = b.pending(listOf("cora"), dev + 1000)
        b.settle(listOf("cora"), until)
        assertEquals(until2, b.pend["cora"])
        // Fehler: zuruecknehmen -> wieder "nicht angenommen" mit Knopf
        b.settle(listOf("cora"), until2)
        s = b.snapshot(false, dev)!!
        assertEquals(St.MISSED, Invites.rows(s, dev).first { it.x.who == "cora" }.st)
        // vorlaeufig laeuft nach PEND_MS von selbst ab
        b.pending(listOf("dora"), dev)
        assertEquals(St.DECLINED, Invites.rows(b.snapshot(false, dev + Invites.PEND_MS)!!, dev + Invites.PEND_MS).first { it.x.who == "dora" }.st)
        // Hinweis nur bis noteUntil
        b.note = UiText.Raw("x"); b.noteUntil = dev + 10
        assertEquals(UiText.Raw("x"), b.snapshot(false, dev)!!.note)
        assertNull(b.snapshot(false, dev + 10)!!.note)
    }

    @Test
    fun errorTexts() {
        assertEquals(UiText.Res(R.string.inv_busy), Invites.errorText(429, UiText.Raw("Too many")))
        val srv = UiText.Raw("Nur wer den Anruf begonnen hat, kann entfernte Personen erneut klingeln lassen.")
        assertEquals(srv, Invites.errorText(403, srv))
        assertEquals(UiText.Res(R.string.inv_failed), Invites.errorText(null, null))
        assertEquals(UiText.Res(R.string.inv_failed), Invites.errorText(502, UiText.Raw("Bad Gateway")))
        assertEquals(UiText.Res(R.string.inv_calling_one, "Cora Beispiel"), Invites.doneText(listOf("Cora Beispiel")))
        assertEquals(UiText.Res(R.string.inv_calling_n, 2), Invites.doneText(listOf("a", "b")))
    }

    // ---------------------------------------------------------------- eigenes erneutes Klingeln

    private fun call(invite: String? = "ringing", since: Double? = 1000.0, now: Double? = 1010.0, mine: List<String> = emptyList(), state: String = "active") =
        CallInfo(id = "c", kind = "group", state = state, invite = invite, inviteSince = since, now = now, ringS = 45, mine = mine,
            legs = listOf(CallLeg(who = "anna", device = "d1")))

    @Test
    fun ringLeftFromInviteSince() {
        assertEquals(35.0, Invites.ringLeftS(call())!!, 0.001)
        assertNull(Invites.ringLeftS(call(since = null)))                  // aelterer Server: wie bisher
        assertTrue(Invites.ringLeftS(call(since = 900.0))!! < 0)
        // Ablauf-Zeit des Klingelns daraus (mind. 5 s + 3 s)
        assertEquals(38_000L, RingLogic.ringMs(Invites.ringLeftS(call()), 45))
    }

    @Test
    fun ringsAgainAfterGone() {
        // Geraete-Uhr = Server-Uhr - 600 s; Server: invite_since 1000 -> Geraet 400 s
        val c = call(since = 1000.0, now = 1010.0)
        val devNow = 410_000L
        assertTrue(Invites.ringsAgain(c, null, devNow))                      // nie vorbei gewesen
        assertTrue(Invites.ringsAgain(c, 390_000L, devNow))                  // abgelehnt um 390, erneut geklingelt um 400
        assertFalse(Invites.ringsAgain(c, 405_000L, devNow))                 // Klingeln vor dem Ablehnen (Ablehnen kam nicht an)
        assertFalse(Invites.ringsAgain(call(since = null), 390_000L, devNow)) // aelterer Server: nicht erneut
        assertFalse(Invites.ringsAgain(call(invite = "declined"), 390_000L, devNow))
        assertFalse(Invites.ringsAgain(call(invite = "missed"), null, devNow))
        assertFalse(Invites.ringsAgain(call(mine = listOf("d9")), 390_000L, devNow))   // eigenes Geraet dabei
        assertFalse(Invites.ringsAgain(call(state = "ended"), 390_000L, devNow))
        assertFalse(Invites.ringsAgain(call().copy(kind = "dm"), null, devNow))
    }
}
