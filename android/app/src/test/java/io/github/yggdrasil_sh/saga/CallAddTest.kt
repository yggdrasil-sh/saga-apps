package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallAdd
import io.github.yggdrasil_sh.saga.call.CallAdd.Mode
import io.github.yggdrasil_sh.saga.call.CallControls
import io.github.yggdrasil_sh.saga.call.CallEntry
import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallInvite
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.CallLogic
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.CallState
import io.github.yggdrasil_sh.saga.chat.ChatContact
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.FedPerson
import io.github.yggdrasil_sh.saga.chat.PeopleResult
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.5 (L8/L9) "Person hinzufuegen" und "1:1 → Konferenz" (Vertrag Server docs/APP-API.md "Anrufe: Personen hinzufuegen"
 * und "1:1 → Konferenz"): Knopf je Anrufart und Feature, Auswahl ohne Personen im Anruf, Antwort und Fehler von /add
 * (Seiten-Rueckfrage, voll, Rechte, Rate), Umzug-Zustand (Antwort `moved`, Ereignis, Poll mit neuer chat_id),
 * Chat-Eintrag "In Gruppenanruf verschoben".
 */
class CallAddTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val both = setOf(CallAdd.FEATURE_ADD, CallAdd.FEATURE_MOVE)
    private val g = CallRoom.KIND_GROUP

    // ------------------------------------------------------------ Knopf

    @Test fun groupNeedsFeatureAndCanAdd() {
        assertEquals(Mode.ADD, CallAdd.mode(both, g, false, CallAdd.Flags(canAdd = true)))
        assertEquals(Mode.NONE, CallAdd.mode(both, g, false, CallAdd.Flags(canAdd = false)))          // Mitglieder: nur "Nicht dabei"
        assertEquals(Mode.NONE, CallAdd.mode(emptySet(), g, false, CallAdd.Flags(canAdd = true)))     // aelterer Server
        assertEquals(Mode.NONE, CallAdd.mode(both, g, false, CallAdd.Flags(canAdd = true), companion = true))
    }

    @Test fun dmOverNodeMovesWithCanMoveOrRinging() {
        assertEquals(Mode.MOVE, CallAdd.mode(both, g, true, CallAdd.Flags(canMove = true)))
        assertEquals(Mode.MOVE, CallAdd.mode(both, g, true, CallAdd.Flags(moveBlock = "ringing")))     // Objekt vom Anrufen
        assertEquals(Mode.HINT, CallAdd.mode(both, g, true, CallAdd.Flags(moveBlock = "fed", moveHint = "x")))
        assertEquals(Mode.HINT, CallAdd.mode(both, g, true, CallAdd.Flags(moveBlock = "direct")))
        assertEquals(Mode.NONE, CallAdd.mode(both, g, true, CallAdd.Flags(moveBlock = "ended")))
        assertEquals(Mode.NONE, CallAdd.mode(setOf(CallAdd.FEATURE_ADD), g, true, CallAdd.Flags(canMove = true)))   // ohne call_move
        // can_add im 1:1 zaehlt nicht (Gruppen-Regel nur ohne dm)
        assertEquals(Mode.NONE, CallAdd.mode(setOf(CallAdd.FEATURE_ADD), g, true, CallAdd.Flags(canAdd = true)))
    }

    @Test fun directDmOnlyHint() {
        assertEquals(Mode.HINT, CallAdd.mode(both, CallRoom.KIND_DM, false, CallAdd.Flags()))
        assertEquals(Mode.NONE, CallAdd.mode(setOf(CallAdd.FEATURE_ADD), CallRoom.KIND_DM, false, CallAdd.Flags()))
        assertEquals(UiText.Raw("Server sagt"), CallAdd.hint(CallAdd.Flags(moveHint = "Server sagt")))
        assertEquals(UiText.Res(R.string.add_direct_hint), CallAdd.hint(CallAdd.Flags()))
    }

    @Test fun flagsFromCallObject() {
        val k = json.decodeFromString(CallState.serializer(), """{"call":{"id":"${"a".repeat(32)}","chat_id":12,"kind":"group","dm":true,
            "can_move":false,"move_block":"ringing","move_hint":"  ","can_add":true,"add_page":"Plan"}}""").call
        val f = CallAdd.flags(k)
        assertEquals("ringing", f.moveBlock)
        assertNull(f.moveHint)                                                                         // leer = fester Text
        assertTrue(f.canAdd)
        assertEquals("Plan", f.addPage)
        // aelterer Server: nichts davon
        val old = CallAdd.flags(CallInfo(kind = g))
        assertEquals(CallAdd.Flags(), old)
    }

    @Test fun moreSheetEntry() {
        assertTrue(CallControls.More.ADD in CallControls.moreItems(true, false, people = true, addPerson = true))
        assertFalse(CallControls.More.ADD in CallControls.moreItems(true, false, people = true))
    }

    // ------------------------------------------------------------ Auswahl

    @Test fun seatsAndHint() {
        val legs = List(6) { CallLeg(who = "u$it", device = "d$it") }
        val full = CallAdd.seats(legs, 6)
        assertTrue(full.full)
        assertEquals(0, full.left)
        assertFalse(CallAdd.seats(legs, 0).full)                                                       // Grenze unbekannt
        assertEquals(UiText.Res(R.string.add_full), CallAdd.fieldHint(Mode.MOVE, full, "Plan"))
        val free = CallAdd.seats(legs.take(2), 6)
        assertEquals(4, free.left)
        assertEquals(UiText.Res(R.string.add_move_hint), CallAdd.fieldHint(Mode.MOVE, free, null))
        assertEquals(UiText.Res(R.string.add_page_hint, "Plan"), CallAdd.fieldHint(Mode.ADD, free, "Plan"))
        assertEquals(UiText.Res(R.string.add_hint), CallAdd.fieldHint(Mode.ADD, free, null))
    }

    @Test fun pickerHidesPeopleInCallAndMarksAway() {
        val legs = listOf(CallLeg(who = "anna", ref = "~a1", device = "d1"), CallLeg(who = "@~b2@peer.example", handle = "~b2@peer.example", device = "d2"))
        val inv = listOf(CallInvite(who = "cora", ref = "~c3", state = "missed"))
        val items = listOf(
            CallAdd.Pick("~a1", "Anna"), CallAdd.Pick("~b2@peer.example", "Bo"), CallAdd.Pick("~c3", "Cora"),
            CallAdd.Pick("~d4", "Dora"), CallAdd.Pick("~d4", "Dora doppelt"), CallAdd.Pick("", "leer"),
        )
        val out = CallAdd.filter(items, CallAdd.inCall(legs), CallAdd.away(inv))
        assertEquals(listOf("~c3", "~d4"), out.map { it.ref })
        assertTrue(out[0].away)
        assertFalse(out[1].away)
        val seats = CallAdd.seats(legs, 6)
        assertTrue(CallAdd.pickable(out[1], seats, busy = false))
        assertFalse(CallAdd.pickable(out[1], seats, busy = true))
        assertFalse(CallAdd.pickable(out[1], CallAdd.seats(legs, 2), busy = false))                   // voll
        assertFalse(CallAdd.pickable(out[1].copy(gone = true), seats, busy = false))
    }

    @Test fun contactsAndSearchToRows() {
        val local = CallAdd.fromContact(ChatContact(ref = "~x1", name = "Ida", sub = "ida.b"))
        assertEquals("~x1", local.ref)
        assertEquals("ida.b", local.sub)
        assertNull(local.remoteHost)
        val remote = CallAdd.fromContact(ChatContact(ref = "~y@peer.example", name = "Jo", remote = true, host = "peer.example"))
        assertEquals("peer.example", remote.remoteHost)
        assertTrue(CallAdd.fromContact(ChatContact(ref = "~z", name = "Weg", gone = true)).gone)
        val hits = CallAdd.fromSearch(PeopleResult(
            users = listOf(ChatPerson(username = "~k9", name = "Kai"), ChatPerson(username = "~me", name = "Ich", me = true)),
            remote = listOf(FedPerson(name = "Lea", label = "Lea · peer.example", host = "peer.example", handle = "~l1@peer.example")),
        ))
        assertEquals(listOf("~k9", "~l1@peer.example"), hits.map { it.ref })
        assertEquals("Lea · peer.example", hits[1].name)
    }

    // ------------------------------------------------------------ POST /add

    @Test fun replyOutcome() {
        val ok = CallAdd.parse("""{"ok":true,"n":1,"added":["~x4"],"failed":[],"pending":[],"request":[],
            "invites":[{"who":"~x4@s.example","name":"Dora Neu","state":"ringing","since":1794904205.1}],"invites_more":0,
            "seats":{"used":2,"limit":6,"left":4,"topo":"mesh"},"rv":1794904205.12,"now":1794904205.13}""")
        assertEquals(UiText.Res(R.string.inv_calling_one, "Dora"), CallAdd.outcome(ok, "Dora"))
        assertEquals(1, ok.invites?.size)
        assertEquals(4, ok.seats?.left)
        assertNull(ok.moved)
        assertEquals(UiText.Raw("Kein Chat möglich."), CallAdd.outcome(ok.copy(failed = listOf(CallAdd.Failed("~q", "Kein Chat möglich.", "unreachable"))), "Q"))
        assertEquals(UiText.Res(R.string.add_pending, "P"), CallAdd.outcome(ok.copy(pending = listOf("~p@x")), "P"))
        assertEquals(UiText.Res(R.string.add_request, "R"), CallAdd.outcome(ok.copy(request = listOf("~r@x")), "R"))
        assertEquals(UiText.Res(R.string.add_already, "A"), CallAdd.outcome(ok.copy(n = 0), "A"))
    }

    @Test fun pageConfirmAskedOnceThenNote() {
        val body = """{"error":"Dora bekommt Zugriff auf die Seite „Plan“.","page_confirm":true,"title":"Plan","names":["Dora"]}"""
        val e = CallAdd.error(409, body, confirmed = false)
        assertTrue(e is CallAdd.Err.PageConfirm)
        assertEquals(UiText.Raw("Dora bekommt Zugriff auf die Seite „Plan“."), (e as CallAdd.Err.PageConfirm).text)
        // schon bestaetigt: keine Endlosschleife, nur Hinweis
        assertTrue(CallAdd.error(409, body, confirmed = true) is CallAdd.Err.Note)
        assertEquals("""{"uids":["~d"],"confirm_page":true}""", CallAdd.body("~d", true).toString())
        assertEquals("""{"uids":["~d"]}""", CallAdd.body("~d", false).toString())
    }

    @Test fun errorsKeepServerText() {
        val full = CallAdd.error(409, """{"error":"Der Anruf ist voll.","full":true,"seats":{"used":6,"limit":6}}""", false) as CallAdd.Err.Note
        assertTrue(full.full)
        assertEquals(UiText.Raw("Der Anruf ist voll."), full.text)
        val rights = CallAdd.error(403, """{"error":"Nur Admins.","rights":true}""", false) as CallAdd.Err.Note
        assertEquals(UiText.Raw("Nur Admins."), rights.text)
        val ended = CallAdd.error(409, """{"error":"Vorbei.","ended":true}""", false) as CallAdd.Err.Note
        assertTrue(ended.ended)
        assertEquals(UiText.Res(R.string.inv_busy), (CallAdd.error(429, """{"error":"x"}""", false) as CallAdd.Err.Note).text)
        assertEquals(UiText.Res(R.string.add_failed), (CallAdd.error(500, "kaputt", false) as CallAdd.Err.Note).text)
        // 502 mit failed: Text des Servers (z. B. anderer Server nicht erreichbar); 502 ohne JSON (Proxy) -> allgemein
        assertEquals(UiText.Raw("Der Server peer.example ist nicht erreichbar."),
            (CallAdd.error(502, """{"error":"Der Server peer.example ist nicht erreichbar.","failed":[{"who":"~x@peer.example","kind":"server"}]}""", false) as CallAdd.Err.Note).text)
        assertEquals(UiText.Res(R.string.add_failed), (CallAdd.error(502, "<html>Bad Gateway</html>", false, UiText.Raw("Proxy")) as CallAdd.Err.Note).text)
        assertEquals(UiText.Res(R.string.add_failed), (CallAdd.error(null, null, false) as CallAdd.Err.Note).text)
        // ohne JSON: Text aus der ApiException
        assertEquals(UiText.Raw("vom Client"), (CallAdd.error(404, "", false, UiText.Raw("vom Client")) as CallAdd.Err.Note).text)
    }

    // ------------------------------------------------------------ L9: Umzug

    @Test fun movedReplyCarriesNewCall() {
        val id = "b".repeat(32)
        val r = CallAdd.parse("""{"ok":true,"n":1,"added":["~x4"],"failed":[],"invites":[],"rv":1.0,
            "moved":{"from_chat":12,"chat_id":37},"call":{"id":"$id","chat_id":37,"kind":"group","can_add":true,"invites":[],"limit":6}}""")
        assertEquals(12L, r.moved?.fromChat)
        assertEquals(37L, r.moved?.chatId)
        assertEquals(37L, r.call?.chatId)
        assertFalse(r.call!!.dm)
        assertTrue(r.call!!.canAdd)
    }

    @Test fun moveStateTransition() {
        val dm = CallAdd.Room(g, dm = true, chatId = 12, title = "Ben")
        val next = CallAdd.move(dm, 37, "Gruppe")!!
        assertEquals(CallAdd.Room(g, dm = false, chatId = 37, movedTo = 37, title = "Gruppe"), next)
        // danach: Knopf wie im Gruppenanruf (beide sind Admin), "Nicht dabei" erscheint (dm aus)
        assertEquals(Mode.ADD, CallAdd.mode(both, next.kind, next.dm, CallAdd.Flags(canAdd = true)))
        // Antwort UND Ereignis: nur einmal umziehen
        assertNull(CallAdd.move(next, 37, "Gruppe"))
        assertNull(CallAdd.move(dm, 12, "x"))                                                          // gleicher Chat
        assertNull(CallAdd.move(dm, null, "x"))
        assertNull(CallAdd.move(dm, 0, "x"))
        // normaler Gruppenanruf (nie dm): ein fremdes chat_id ist kein Umzug
        assertNull(CallAdd.move(CallAdd.Room(g, dm = false, chatId = 5), 6, "x"))
        // schon umgezogen und noch einmal (zweiter Umzug gibt es nicht, aber ein neueres Objekt gewinnt)
        assertEquals(40L, CallAdd.move(next, 40, "y")?.chatId)
    }

    @Test fun moveDetectedFromPolledCallObject() {
        val k = CallInfo(id = "c".repeat(32), chatId = 37, kind = g, dm = false, state = "active")
        assertEquals(37L, CallAdd.moveFromInfo(dm = true, chatId = 12, movedTo = null, k = k))
        assertNull(CallAdd.moveFromInfo(dm = true, chatId = 12, movedTo = null, k = k.copy(dm = true, chatId = 12)))   // noch 1:1
        assertNull(CallAdd.moveFromInfo(dm = true, chatId = 12, movedTo = null, k = k.copy(state = "ended")))
        assertNull(CallAdd.moveFromInfo(dm = false, chatId = 37, movedTo = 37, k = k))                 // schon da
        assertEquals("Team", CallAdd.movedTitle("Team", "Anna, Ben"))
        assertEquals("Anna, Ben", CallAdd.movedTitle(null, "Anna, Ben"))
        assertEquals("", CallAdd.movedTitle(" ", null))
    }

    @Test fun refsParsedForLegsAndInvites() {
        val legs = CallRoom.legsOf(json.parseToJsonElement("""[{"who":"anna","ref":"~a1","device":"d1"}]"""))
        assertEquals("~a1", legs.single().ref)
        val k = json.decodeFromString(CallState.serializer(), """{"call":{"invites":[{"who":"cora","ref":"~c3","state":"none"}]}}""").call
        assertEquals("~c3", k.invites!!.single().ref)
    }

    // ------------------------------------------------------------ call.move ohne eigenen Anruf (Pruefer F5)

    @Test fun openAfterMoveOnlyFromOldChat() {
        assertEquals(37L, CallAdd.openAfterMove(12L, 12L, 37L))     // altes 1:1 offen -> neue Gruppe
        assertNull(CallAdd.openAfterMove(5L, 12L, 37L))             // anderer Chat offen
        assertNull(CallAdd.openAfterMove(null, 12L, 37L))           // kein Chat offen
        assertNull(CallAdd.openAfterMove(12L, null, 37L))           // from fehlt
        assertNull(CallAdd.openAfterMove(37L, 37L, 37L))            // schon die neue Gruppe
        assertNull(CallAdd.openAfterMove(0L, 0L, 37L))
    }

    // ------------------------------------------------------------ TalkBack (Pruefer F4)

    @Test fun rowDescriptionReadsSubline() {
        assertEquals("Anna hinzufügen und anrufen, im Chat – klingelt erneut", CallAdd.rowDescription("Anna hinzufügen und anrufen", "im Chat – klingelt erneut"))
        assertEquals("Add and call Ben, Person on example.org", CallAdd.rowDescription("Add and call Ben", "Person on example.org"))
        assertEquals("Add and call Ben", CallAdd.rowDescription("Add and call Ben", null))
        assertEquals("Add and call Ben", CallAdd.rowDescription("Add and call Ben", "  "))
    }

    // ------------------------------------------------------------ Chat-Eintrag

    @Test fun movedEntry() {
        val e = CallLogic.parseEntry("""{"v":1,"type":"call","call":"x","media":"audio","status":"ended","dur":42,"from":"anna","to":"ben","moved":37}""")!!
        assertEquals(37L, e.moved)
        assertEquals(UiText.Res(R.string.call_entry_moved, "0:42"), CallLogic.entryText(e, "anna"))
        assertEquals(37L, CallAdd.movedChat(e))
        // ohne moved (aeltere Server, normaler Eintrag): wie bisher
        val plain = e.copy(moved = null)
        assertEquals(UiText.Res(R.string.call_entry_ended, UiText.Res(R.string.call_entry_audio), "0:42"), CallLogic.entryText(plain, "anna"))
        assertNull(CallAdd.movedChat(plain))
        assertNull(CallAdd.movedChat(e.copy(moved = 0)))
        assertNull(CallAdd.movedChat(e.copy(status = "missed")))
        assertNotNull(CallLogic.parseEntry("""{"type":"call","status":"ended","moved":"37"}"""))
    }
}
