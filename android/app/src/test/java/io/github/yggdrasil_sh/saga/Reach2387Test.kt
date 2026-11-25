package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.CallPeer
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.HoldBar
import io.github.yggdrasil_sh.saga.call.HoldFlow
import io.github.yggdrasil_sh.saga.chat.StatusException
import io.github.yggdrasil_sh.saga.chat.UserStatusLogic
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.ShareTargets
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Bugs-DB 2387 (Pruefer a7rev, 2029-7 Android): Ausnahme-Suche mit Personen anderer Server und direkter Kennung,
 * stille Leiste nach Gespraechsende (busyElsewhere erneut) und die Verdrahtung checkRing / holdTake / hangupConfirmed.
 */
class Reach2387Test {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val h16 = "abcdefghijklmnop"
    private val h16b = "qrstuvwxyz234567"

    // ------------------------------------------------------------ 1. Ausnahme-Suche

    @Test
    fun handleLikeWeb() {
        // static/push.js HANDLE (seit 2393 mit IPv6-Hosts in [], siehe handleIpv6LikeServer)
        assertTrue(UserStatusLogic.validHandle("~$h16"))
        assertTrue(UserStatusLogic.validHandle("~$h16@b.example"))
        assertTrue(UserStatusLogic.validHandle("~$h16@b.example:8443"))
        assertFalse(UserStatusLogic.validHandle("@~$h16@b.example"))          // so liefert der Server target - nicht als Ausnahme
        assertFalse(UserStatusLogic.validHandle("~${h16.dropLast(1)}"))       // 15 Zeichen
        assertFalse(UserStatusLogic.validHandle("~${h16}a"))                  // 17 Zeichen
        assertFalse(UserStatusLogic.validHandle("~ABCDEFGHIJKLMNOP"))         // gross (wie im Web: keine Umwandlung)
        assertFalse(UserStatusLogic.validHandle("~abcdefghijklmn01"))          // 0/1 sind kein base32
        assertFalse(UserStatusLogic.validHandle("~$h16@"))
        assertFalse(UserStatusLogic.validHandle("~$h16@B.example"))
        assertFalse(UserStatusLogic.validHandle("~$h16@b.example/x"))
        assertFalse(UserStatusLogic.validHandle("anna@b.example"))
        assertFalse(UserStatusLogic.validHandle(null))
        assertFalse(UserStatusLogic.validHandle(""))
    }

    /** Bugs-DB 2393: IPv6-Hosts in eckigen Klammern wie der Server (saga/contacts.py _RREF) */
    @Test
    fun handleIpv6LikeServer() {
        assertTrue(UserStatusLogic.validHandle("~$h16@[2001:db8::1]"))
        assertTrue(UserStatusLogic.validHandle("~$h16@[2001:db8::1]:8443"))
        assertTrue(UserStatusLogic.validHandle("~$h16@[::1]"))
        assertEquals("~$h16@[2001:db8::1]", UserStatusLogic.remoteRef("remote", "@~$h16@[2001:db8::1]", null))
        assertEquals(
            listOf("~$h16@[2001:db8::1]"),
            UserStatusLogic.exceptionHits(ShareTargets(), "~$h16@[2001:db8::1]", "Gruppe").map { it.x.ref },
        )
        // weiter abgelehnt (auch vom Server): Grossbuchstaben, Leerzeichen, Schraegstrich, zweites @, leerer Host
        assertFalse(UserStatusLogic.validHandle("~$h16@[2001:DB8::1]"))
        assertFalse(UserStatusLogic.validHandle("~$h16@[2001:db8:: 1]"))
        assertFalse(UserStatusLogic.validHandle("~$h16@[2001:db8::1]/x"))
        assertFalse(UserStatusLogic.validHandle("~$h16@a@[::1]"))
        assertFalse(UserStatusLogic.validHandle("~$h16@"))
    }

    /** Bugs-DB 2393: Treffer anderer Server - Host nur in der Unterzeile */
    @Test
    fun remoteTitleHostOnce() {
        assertEquals("Anna Berg" to "b.example", UserStatusLogic.remoteTitle("Anna Berg · b.example", "b.example"))
        // Gleichnamige auf demselben Server: Unterscheider bleibt in der Zeile
        assertEquals("Anna Berg · anna" to "b.example", UserStatusLogic.remoteTitle("Anna Berg · b.example · anna", "b.example"))
        assertEquals("Anna Berg" to "[2001:db8::1]:8443", UserStatusLogic.remoteTitle("Anna Berg · [2001:db8::1]:8443", "[2001:db8::1]:8443"))
        // Label ohne Host (nur Name): Host als Unterzeile
        assertEquals("Anna Berg" to "b.example", UserStatusLogic.remoteTitle("Anna Berg", "b.example"))
        // Ersatz "Person auf host (tag)" enthaelt den Host schon: keine Unterzeile
        assertEquals("Person auf b.example (spqz)" to null, UserStatusLogic.remoteTitle("Person auf b.example (spqz)", "b.example"))
        // kein Host bekannt
        assertEquals("Anna Berg" to null, UserStatusLogic.remoteTitle("Anna Berg", ""))
        // Treffer ohne Namen: Kennung (enthaelt den Host) ohne Unterzeile
        val r = json.decodeFromString(ShareTargets.serializer(), """{"remote": [{"type": "remote", "target": "@~$h16@b.example", "name": "", "host": "b.example"}]}""")
        assertEquals(listOf(UserStatusLogic.ExcHit(StatusException("user", "~$h16@b.example", null), null, "~$h16@b.example")),
            UserStatusLogic.exceptionHits(r, "b.example", "Gruppe"))
    }

    @Test
    fun remoteRefFromServerShapes() {
        // Adressbuch (fed_dir.search): target '@~x@host' + handle '~x@host'
        assertEquals("~$h16@b.example", UserStatusLogic.remoteRef("remote", "@~$h16@b.example", "~$h16@b.example"))
        // mit oeffentlichem Namen: target '@anna@b.example', handle traegt die Kennung
        assertEquals("~$h16@b.example", UserStatusLogic.remoteRef("remote", "@anna@b.example", "~$h16@b.example"))
        // genaue Adresse (federation.remote_search) ohne handle: nur, wenn target selbst eine Kennung ist
        assertEquals("~$h16@b.example", UserStatusLogic.remoteRef("remote", "@~$h16@b.example", null))
        assertNull(UserStatusLogic.remoteRef("remote", "@anna@b.example", null))
        // Gruppen anderer Server gehen nicht
        assertNull(UserStatusLogic.remoteRef("remote_group", "team@b.example", null))
        // aeltere Form ohne type / alte Pruefung ("~" am Anfang) bleibt gueltig
        assertEquals("~$h16@b.example", UserStatusLogic.remoteRef(null, "~$h16@b.example", null))
    }

    @Test
    fun hitsIncludeFederatedPerson() {
        // echte Form von GET /api/share/targets mit Adressbuch-Treffer (vorher: Filter target.startsWith("~") traf nie)
        val r = json.decodeFromString(
            ShareTargets.serializer(),
            """{"users": [{"username": "~$h16b", "name": "Anna Lokal", "sub": "", "avatar": null}],
               "groups": [{"name": "team"}],
               "remote": [{"type": "remote", "target": "@~$h16@b.example", "handle": "~$h16@b.example", "name": "Anna Berg",
                           "host": "b.example", "pub": null, "label": "Anna Berg · b.example", "avatar": null},
                          {"type": "remote_group", "target": "team@b.example", "name": "team", "host": "b.example"}]}""",
        )
        val hits = UserStatusLogic.exceptionHits(r, "anna", "Gruppe")
        assertEquals(3, hits.size)
        assertEquals(UserStatusLogic.ExcHit(StatusException("user", "~$h16b", "Anna Lokal"), ""), hits[0])
        assertEquals(UserStatusLogic.ExcHit(StatusException("group", null, "team"), "Gruppe"), hits[1])
        // Bugs-DB 2393: Host nur einmal - Zeile "Anna Berg", Unterzeile "b.example" (Eintrag behaelt das Label fuer die Liste)
        assertEquals(UserStatusLogic.ExcHit(StatusException("user", "~$h16@b.example", "Anna Berg · b.example"), "b.example", "Anna Berg"), hits[2])
        // daraus wird der Body fuer PUT /api/account/status/prefs: ref '~x@host' (APP-API "Ausnahmen")
        val body = UserStatusLogic.exceptionsBody(UserStatusLogic.addException(emptyList(), hits[2].x))
        val e = body["exceptions"]!!.jsonArray[0].jsonObject
        assertEquals("user", e["type"]!!.jsonPrimitive.content)
        assertEquals("~$h16@b.example", e["ref"]!!.jsonPrimitive.content)
        assertNull(e["name"])
    }

    @Test
    fun hitsDedupeAndSkipPubOnly() {
        val r = json.decodeFromString(
            ShareTargets.serializer(),
            """{"remote": [{"type": "remote", "target": "@anna@b.example", "name": "Anna Berg", "host": "b.example"},
                          {"type": "remote", "target": "@anna@b.example", "handle": "~$h16@b.example", "name": "Anna Berg", "host": "b.example"},
                          {"type": "remote", "target": "@~$h16@b.example", "name": "Anna Berg", "host": "b.example"}]}""",
        )
        val hits = UserStatusLogic.exceptionHits(r, "anna@b.example", "Gruppe")
        assertEquals(listOf(UserStatusLogic.ExcHit(StatusException("user", "~$h16@b.example", "Anna Berg"), "b.example", "Anna Berg")), hits)
    }

    @Test
    fun directHandleLikeWeb() {
        // ohne Treffer: gueltige Kennung direkt waehlbar (eigener Server bzw. anderer Server)
        assertEquals(
            listOf(UserStatusLogic.ExcHit(StatusException("user", "~$h16@b.example", null), "~$h16@b.example")),
            UserStatusLogic.exceptionHits(ShareTargets(), "  ~$h16@b.example ", "Gruppe"),
        )
        assertEquals(listOf("~$h16"), UserStatusLogic.exceptionHits(null, "~$h16", "Gruppe").map { it.x.ref })   // Suche fehlgeschlagen
        // ungueltig: nichts
        assertTrue(UserStatusLogic.exceptionHits(ShareTargets(), "@~$h16@b.example", "Gruppe").isEmpty())
        assertTrue(UserStatusLogic.exceptionHits(ShareTargets(), "~kurz", "Gruppe").isEmpty())
        assertTrue(UserStatusLogic.exceptionHits(null, "anna", "Gruppe").isEmpty())
        // mit Treffern keine zusaetzliche direkte Zeile (wie im Web)
        val r = ShareTargets(users = listOf(io.github.yggdrasil_sh.saga.data.TargetUser("~$h16", "Bert")))
        assertEquals(listOf("Bert"), UserStatusLogic.exceptionHits(r, "~$h16", "Gruppe").map { it.x.name })
    }

    // ------------------------------------------------------------ 2. holdFreed / busyElsewhere

    private val id = "a".repeat(32)
    private val other = "b".repeat(32)
    private val info = CallInfo(id = id, chatId = 1, state = "ringing", role = "callee", peer = CallPeer("bruno", "Bruno"), ringS = 45)

    private fun bar(mode: HoldBar.Mode, here: Boolean, elsewhere: Boolean = false, at: Long = 0L) =
        HoldBar(id, 1, mode, info.peer, false, "dnd", here, elsewhere, info, 45.0, at)

    @Test
    fun freedQuietRechecksElsewhere() {
        val f = HoldFlow.freed(bar(HoldBar.Mode.QUIET, here = true), taking = false, nowMs = 10_000)
        assertTrue(f is HoldFlow.Freed.Recheck)
        val waiting = (f as HoldFlow.Freed.Recheck).bar
        // waehrend der Pruefung: kein "Annehmen", kein "Beenden und annehmen", kein Hinweis "woanders"
        assertFalse(waiting.here)
        assertFalse(waiting.elsewhere)
        assertTrue(waiting.checking)
        assertFalse(waiting.canAccept)
        assertFalse(waiting.canTake)
        // Ergebnis: woanders laeuft ein Gespraech -> weiter kein Annehmen; sonst Annehmen
        HoldFlow.rechecked(waiting, id, elsewhere = true)!!.let { assertTrue(it.elsewhere); assertFalse(it.checking); assertFalse(it.canAccept) }
        HoldFlow.rechecked(waiting, id, elsewhere = false)!!.let { assertFalse(it.elsewhere); assertTrue(it.canAccept) }
        // inzwischen andere/keine Leiste bzw. schon entschieden: nichts aendern
        assertNull(HoldFlow.rechecked(null, id, true))
        assertNull(HoldFlow.rechecked(waiting.copy(callId = other), id, true))
        assertNull(HoldFlow.rechecked(waiting.copy(checking = false), id, true))
    }

    @Test
    fun freedKnockRingsOrDrops() {
        assertEquals(HoldFlow.Freed.RingAgain(info, 30.0), HoldFlow.freed(bar(HoldBar.Mode.KNOCK, here = true, at = 0), false, 15_000))
        assertEquals(HoldFlow.Freed.Drop, HoldFlow.freed(bar(HoldBar.Mode.KNOCK, here = true, at = 0), false, 44_000))
        // nicht im Gespraech hier, "Beenden und annehmen" laeuft, keine Leiste: nichts
        assertEquals(HoldFlow.Freed.None, HoldFlow.freed(bar(HoldBar.Mode.QUIET, here = false), false, 0))
        assertEquals(HoldFlow.Freed.None, HoldFlow.freed(bar(HoldBar.Mode.KNOCK, here = true), true, 0))
        assertEquals(HoldFlow.Freed.None, HoldFlow.freed(null, false, 0))
    }

    @Test
    fun busyElsewhereSkipsJustEnded() {
        val mineDm = CallInfo(id = other, state = "active", role = "caller")
        assertTrue(HoldFlow.busyElsewhere(listOf(mineDm), setOf(id, null, null)))
        // das gerade hier beendete Gespraech (Server meldet es noch kurz als aktiv, samt diesem Geraet) zaehlt nicht
        assertFalse(HoldFlow.busyElsewhere(listOf(mineDm), setOf(id, null), ended = other, myDev = "dev1"))
        assertFalse(HoldFlow.busyElsewhere(listOf(mineDm.copy(mine = listOf("dev1"))), setOf(id, null), ended = other, myDev = "dev1"))
        // der wartende Anruf selbst, klingelnde Anrufe und fremde Gruppenanrufe zaehlen nicht
        assertFalse(HoldFlow.busyElsewhere(listOf(mineDm.copy(id = id)), setOf(id)))
        assertFalse(HoldFlow.busyElsewhere(listOf(mineDm.copy(state = "ringing")), setOf(id)))
        val grp = CallInfo(id = other, state = "active", kind = CallRoom.KIND_GROUP)
        assertFalse(HoldFlow.busyElsewhere(listOf(grp), setOf(id)))
        assertTrue(HoldFlow.busyElsewhere(listOf(grp.copy(mine = listOf("dev2"))), setOf(id)))
        // 1:1 ueber Knoten: Rolle genuegt
        assertTrue(HoldFlow.busyElsewhere(listOf(grp.copy(dm = true, role = "callee")), setOf(id)))
    }

    /**
     * Bugs-DB 2393: Gruppenanruf hier aufgelegt, ein zweites eigenes Geraet spricht darin weiter -> "woanders im Gespraech"
     * (kein "Annehmen"). Nur dieses Geraet (bzw. keins) noch im Anruf -> zaehlt nicht.
     */
    @Test
    fun busyElsewhereGroupWithSecondOwnDevice() {
        val grp = CallInfo(id = other, state = "active", kind = CallRoom.KIND_GROUP, role = "member")
        // vorher: das beendete Gespraech wurde immer uebersprungen -> faelschlich frei
        assertTrue(HoldFlow.busyElsewhere(listOf(grp.copy(mine = listOf("dev2"))), setOf(id, null), ended = other, myDev = "dev1"))
        assertTrue(HoldFlow.busyElsewhere(listOf(grp.copy(mine = listOf("dev1", "dev2"))), setOf(id, null), ended = other, myDev = "dev1"))
        // nur noch dieses Geraet (Server meldet es kurz weiter) bzw. keins: frei
        assertFalse(HoldFlow.busyElsewhere(listOf(grp.copy(mine = listOf("dev1"))), setOf(id, null), ended = other, myDev = "dev1"))
        assertFalse(HoldFlow.busyElsewhere(listOf(grp), setOf(id, null), ended = other, myDev = "dev1"))
        // inzwischen vorbei: frei
        assertFalse(HoldFlow.busyElsewhere(listOf(grp.copy(state = "ended", mine = listOf("dev2"))), setOf(id, null), ended = other, myDev = "dev1"))
        // 1:1 (auch ueber Knoten): beendet ohne weiteres eigenes Geraet frei, mit Begleitgeraet bzw. nach Uebergabe belegt
        val dm = CallInfo(id = other, state = "active", role = "caller", mine = listOf("dev1"))
        assertFalse(HoldFlow.busyElsewhere(listOf(dm, grp.copy(dm = true, mine = listOf("dev1"))), setOf(id, null), ended = other, myDev = "dev1"))
        assertTrue(HoldFlow.busyElsewhere(listOf(dm.copy(mine = listOf("dev3"))), setOf(id, null), ended = other, myDev = "dev1"))
        // Geraete-ID unbekannt: lieber kein "Annehmen" (sonst zwei Gespraeche)
        assertTrue(HoldFlow.busyElsewhere(listOf(grp.copy(mine = listOf("dev1"))), setOf(id, null), ended = other, myDev = ""))
        // ein ANDERES Gespraech laeuft woanders weiter: zaehlt wie bisher
        val g2 = grp.copy(id = "c".repeat(32), mine = listOf("dev2"))
        assertTrue(HoldFlow.busyElsewhere(listOf(grp.copy(mine = listOf("dev1")), g2), setOf(id, null), ended = other, myDev = "dev1"))
        // Ergebnis in die stille Leiste: kein "Annehmen"
        val waiting = (HoldFlow.freed(bar(HoldBar.Mode.QUIET, here = true), taking = false, nowMs = 1_000) as HoldFlow.Freed.Recheck).bar
        val b = HoldFlow.busyElsewhere(listOf(grp.copy(mine = listOf("dev2"))), setOf(id, null), ended = other, myDev = "dev1")
        HoldFlow.rechecked(waiting, id, b)!!.let { assertTrue(it.elsewhere); assertFalse(it.canAccept) }
    }

    // ------------------------------------------------------------ 3. checkRing / holdTake / hangupConfirmed

    private fun route(i: CallInfo, gone: Boolean = false, goneAt: Long? = null, cur: String? = null, ring: String? = null, inCall: Boolean = cur != null) =
        HoldFlow.route(i, i.id, gone, goneAt, cur, ring, inCall, "me", 1_000_000L)

    @Test
    fun checkRingDm() {
        assertEquals(HoldFlow.Route.Ring(info), route(info))
        assertEquals(HoldFlow.Route.Hold(info.copy(quiet = true), HoldBar.Mode.QUIET, "dnd"), route(info.copy(quiet = true)))
        assertEquals(HoldFlow.Route.Hold(info.copy(waiting = true), HoldBar.Mode.KNOCK, null), route(info.copy(waiting = true), cur = other))
        assertEquals(HoldFlow.Route.Hold(info.copy(waiting = true), HoldBar.Mode.KNOCK, null), route(info.copy(waiting = true)))   // woanders
        assertEquals(HoldFlow.Route.DeclineBusy, route(info, cur = other))
        assertEquals(HoldFlow.Route.DeclineBusy, route(info, ring = other))
        // schon hier, vorbei, nicht (mehr) klingelnd, nicht angerufen
        assertEquals(HoldFlow.Route.Ignore, route(info, cur = id))
        assertEquals(HoldFlow.Route.Ignore, route(info, ring = id))
        assertEquals(HoldFlow.Route.Ignore, route(info, gone = true))
        assertEquals(HoldFlow.Route.Ignore, route(info.copy(state = "active")))
        assertEquals(HoldFlow.Route.Ignore, route(info.copy(role = "caller")))
    }

    @Test
    fun checkRingGroup() {
        val lead = CallLeg(who = "cora", name = "Cora")
        val g = CallInfo(id = id, chatId = 2, state = "active", kind = CallRoom.KIND_GROUP, invite = "ringing", legs = listOf(lead), peer = CallPeer("x", "X"))
        val rung = HoldFlow.route(g, id, false, null, null, null, false, "me", 0)
        assertTrue(rung is HoldFlow.Route.RingGroup)
        (rung as HoldFlow.Route.RingGroup).let {
            assertEquals("cora", it.info.peer.username)
            assertEquals("ringing", it.info.state)
            assertEquals("callee", it.info.role)
            assertFalse(it.clearGone)
        }
        // stille Einladung: nur Leiste "Gruppenanruf laeuft"; 1:1 ueber Knoten zurueckgehalten: stille Leiste
        assertEquals(HoldFlow.Route.Refresh, route(g.copy(silent = true, quiet = true)))
        assertEquals(HoldFlow.Route.Hold(g.copy(dm = true, quiet = true), HoldBar.Mode.QUIET, "dnd"), route(g.copy(dm = true, quiet = true)))
        // 1:1 ueber Knoten klopft an: Anrufer aus legs
        val k = route(g.copy(dm = true, waiting = true))
        assertTrue(k is HoldFlow.Route.Hold && k.mode == HoldBar.Mode.KNOCK && k.info.peer.username == "cora")
        // eigenes Geraet schon dabei, Einladung vorbei, hier schon vorbei ohne erneutes Klingeln
        assertEquals(HoldFlow.Route.Ignore, route(g.copy(legs = listOf(lead, CallLeg(who = "me")))))
        assertEquals(HoldFlow.Route.Ignore, route(g.copy(invite = "declined")))
        assertEquals(HoldFlow.Route.Ignore, route(g, gone = true, goneAt = 5_000))
        // erneut geklingelt (invite_since nach dem Ende hier): klingelt und raeumt gone
        val again = HoldFlow.route(g.copy(inviteSince = 100.0, now = 100.0), id, true, 50_000, null, null, false, "me", 100_000)
        assertTrue(again is HoldFlow.Route.RingGroup && again.clearGone)
        // Klingelzeit ab invite_since abgelaufen
        assertEquals(HoldFlow.Route.Ignore, route(g.copy(inviteSince = 0.0, now = 100.0, ringS = 45)))
    }

    @Test
    fun endConfirmedClassifiesErrors() = runTest {
        var posts = 0
        assertTrue(HoldFlow.endConfirmed(true) { posts++ })
        assertEquals(1, posts)
        assertTrue(HoldFlow.endConfirmed(false) { posts++ })                      // schon im Beenden: kein zweites /end
        assertEquals(1, posts)
        assertTrue(HoldFlow.endConfirmed(true) { throw ApiException(404, UiText.Raw("x")) })   // schon vorbei
        assertTrue(HoldFlow.endConfirmed(true) { throw ApiException(410, UiText.Raw("x")) })
        assertFalse(HoldFlow.endConfirmed(true) { throw ApiException(500, UiText.Raw("x")) })
        assertFalse(HoldFlow.endConfirmed(true) { throw ApiException(403, UiText.Raw("x")) })
        assertFalse(HoldFlow.endConfirmed(true) { throw java.io.IOException("offline") })
        try {
            HoldFlow.endConfirmed(true) { throw CancellationException("weg") }
            fail("Abbruch muss durchgehen")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun holdTakeOrder() = runTest {
        val h = bar(HoldBar.Mode.KNOCK, here = true, at = 0)
        val log = ArrayList<String>()
        // Ende bestaetigt -> annehmen (erst auflegen, dann annehmen)
        assertEquals(HoldFlow.Take.Accept, HoldFlow.take(h, true, { log += "end"; true }) { 10_000 })
        assertEquals(listOf("end"), log)
        // kein Gespraech mehr hier: gleich annehmen, nicht auflegen
        log.clear()
        assertEquals(HoldFlow.Take.Accept, HoldFlow.take(h, false, { log += "end"; true }) { 10_000 })
        assertTrue(log.isEmpty())
        // Ende nicht sicher: nicht annehmen; Rest der Klingelzeit -> klingelt wieder, zu kurz -> weg
        assertEquals(HoldFlow.Take.Failed(35.0), HoldFlow.take(h, true, { false }) { 10_000 })
        assertEquals(HoldFlow.Take.Failed(null), HoldFlow.take(h, true, { false }) { 44_500 })
        // ueber endConfirmed verdrahtet (wie CallManager.hangupConfirmed): Netzfehler -> Failed
        assertEquals(HoldFlow.Take.Failed(35.0), HoldFlow.take(h, true, { HoldFlow.endConfirmed(true) { throw java.io.IOException() } }) { 10_000 })
        assertEquals(HoldFlow.Take.Accept, HoldFlow.take(h, true, { HoldFlow.endConfirmed(true) { throw ApiException(410, UiText.Raw("x")) } }) { 10_000 })
    }
}
