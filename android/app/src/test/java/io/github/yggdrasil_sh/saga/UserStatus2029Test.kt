package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallEntry
import io.github.yggdrasil_sh.saga.call.CallLogic
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.HoldBar
import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallPeer
import io.github.yggdrasil_sh.saga.call.RingLogic
import io.github.yggdrasil_sh.saga.chat.ChatMe
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatPrivacy
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.PeerStatus
import io.github.yggdrasil_sh.saga.chat.PresenceHub
import io.github.yggdrasil_sh.saga.chat.PresenceLogic
import io.github.yggdrasil_sh.saga.chat.PresenceWatchLogic
import io.github.yggdrasil_sh.saga.chat.StatusDigest
import io.github.yggdrasil_sh.saga.chat.StatusException
import io.github.yggdrasil_sh.saga.chat.StatusLimitsU
import io.github.yggdrasil_sh.saga.chat.StatusTexts
import io.github.yggdrasil_sh.saga.chat.UserStatusCenter
import io.github.yggdrasil_sh.saga.chat.UserStatusLogic
import io.github.yggdrasil_sh.saga.chat.UserStatusLogic.Dur
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 2.6 (Bugs-DB 2029-7): eigener Status in der Android-App nach docs/APP-API.md "Eigener Status" bis "Grund am Chat-Eintrag":
 * Ansicht lesen, ver/409, Wirkung (effective/why), Blatt (Dauer, Text), Fokus-Nutzlast ohne Text (RT-29), Einstellungen
 * und Ausnahmen, Status anderer (Abo, chat.status, Wettlauf), Rueckfrage vor dem Anruf, Sammelmeldung (nur Zahlen),
 * Anruf-Weiche in der App (waiting1, call.quiet, besetzt), Anruf-Eintrag mit hold.
 */
class UserStatus2029Test {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)

    private val VIEW = """{"state":"dnd","until":1792450200.0,"text":"Im Kundentermin","source":"manual","since":1792440000.0,
        "updated":1792440000.0,"ver":7,"effective":"dnd","why":"manual","quiet":false,"in_call":false,
        "prefs":{"quiet_dnd":true,"quiet_digest":false,"in_call":"busy","show_call":false,"busy":"silent","repeat":true,"repeat_s":240,"mention_break":true},
        "exceptions":[{"type":"user","ref":"~abcdefghijklmnop","name":"Bruno Beispiel"},{"type":"group","name":"familie"},{"type":"contact","ref":"x"}],
        "limits":{"text":80,"exceptions":100,"repeat_s":[30,3600],"until_max":31622400,"dur_min":60},"now":1792440100.0}"""

    @Test
    fun parseView() {
        val v = UserStatusLogic.parse(VIEW)!!
        assertEquals("dnd", v.state)
        assertEquals(7L, v.ver)
        assertEquals(1792450200.0, v.until!!, 0.0)
        assertEquals("Im Kundentermin", v.text)
        assertEquals("busy", v.prefs.inCall)
        assertFalse(v.prefs.showCall)
        assertEquals("silent", v.prefs.busy)
        assertEquals(240, v.prefs.repeatS)
        assertTrue(v.prefs.mentionBreak)
        // reservierte Art (contact) wird nicht angezeigt
        assertEquals(listOf(StatusException("user", "~abcdefghijklmnop", "Bruno Beispiel"), StatusException("group", null, "familie")), v.exceptions)
        assertEquals(StatusLimitsU(80, 100, 30, 3600), v.limits)
        // unbrauchbar: ohne ver, unbekannter Zustand, kein JSON
        assertNull(UserStatusLogic.parse("""{"state":"dnd"}"""))
        assertNull(UserStatusLogic.parse("""{"state":"away","ver":1}"""))
        assertNull(UserStatusLogic.parse("nicht json"))
        // Standardwerte, wenn prefs/limits fehlen
        val m = UserStatusLogic.parse("""{"state":"available","ver":1}""")!!
        assertEquals("knock", m.prefs.inCall)
        assertEquals(180, m.prefs.repeatS)
        assertEquals("available", m.effective)
    }

    @Test
    fun verOrder() {
        val a = UserStatusLogic.parse("""{"state":"busy","ver":5}""")!!
        val older = UserStatusLogic.parse("""{"state":"dnd","ver":4}""")!!
        val newer = UserStatusLogic.parse("""{"state":"dnd","ver":6}""")!!
        assertTrue(UserStatusLogic.take(null, a))
        assertFalse(UserStatusLogic.take(a, older))
        assertTrue(UserStatusLogic.take(a, newer))
        assertTrue(UserStatusLogic.take(a, a))
    }

    @Test
    fun shownIsHonest() {
        fun v(j: String) = UserStatusLogic.parse(j)!!
        // Ruhezeit zaehlt als Nicht stoeren - kein "bis" des gesetzten Status
        val q = UserStatusLogic.shown(v("""{"state":"available","ver":1,"effective":"dnd","why":"quiet","quiet":true}"""))
        assertEquals("dnd", q.state)
        assertTrue(q.quiet)
        assertNull(q.until)
        // im Anruf, darunter Beschaeftigt
        val c = UserStatusLogic.shown(v("""{"state":"busy","until":100.0,"ver":1,"effective":"in_call","why":"call"}"""))
        assertEquals("in_call", c.state)
        assertEquals("busy", c.under)
        assertNull(c.until)
        // gesetzt mit Ende
        val d = UserStatusLogic.shown(v("""{"state":"dnd","until":100.0,"ver":1,"effective":"dnd","why":"manual"}"""))
        assertEquals(100.0, d.until!!, 0.0)
        assertFalse(d.focus)
        // vom Geraet (Fokus)
        assertTrue(UserStatusLogic.shown(v("""{"state":"dnd","source":"focus","ver":1,"effective":"dnd","why":"manual"}""")).focus)
        assertFalse(UserStatusLogic.shown(v("""{"state":"available","source":"focus","ver":1}""")).focus)
    }

    @Test
    fun tomorrowSeven() {
        val (late, today1) = UserStatusLogic.tomorrow7(at(2026, 11, 25, 14, 30))
        assertFalse(today1)
        assertEquals(at(2026, 11, 26, 7).toEpochSecond(), late)
        val (early, today2) = UserStatusLogic.tomorrow7(at(2026, 11, 25, 3, 10))
        assertTrue(today2)
        assertEquals(at(2026, 11, 25, 7).toEpochSecond(), early)
    }

    @Test
    fun putBodyFromSheet() {
        val v = UserStatusLogic.parse(VIEW)!!
        val now = at(2026, 11, 25, 14, 30)
        val h1 = UserStatusLogic.putBody("busy", Dur.H1, "  Mittag  ", v, now)
        assertEquals("busy", h1["state"]!!.jsonPrimitive.content)
        assertEquals("manual", h1["source"]!!.jsonPrimitive.content)
        assertEquals(3600L, h1["dur"]!!.jsonPrimitive.long)
        assertEquals("Mittag", h1["text"]!!.jsonPrimitive.content)
        assertEquals(7L, h1["ver"]!!.jsonPrimitive.long)
        assertNull(h1["until"])
        val t7 = UserStatusLogic.putBody("dnd", Dur.T7, "", v, now)
        assertEquals(at(2026, 11, 26, 7).toEpochSecond(), t7["until"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, t7["text"])
        val keep = UserStatusLogic.putBody("dnd", Dur.KEEP, "x", v, now)
        assertEquals("1792450200", keep["until"]!!.jsonPrimitive.content)          // ganze Zahl, keine Exponent-Schreibweise
        val off = UserStatusLogic.putBody("dnd", Dur.OFF, "x", v, now)
        assertNull(off["until"])
        assertNull(off["dur"])
        // Verfuegbar: nie Dauer
        val av = UserStatusLogic.putBody("available", Dur.H1, "", v, now)
        assertNull(av["dur"])
        assertNull(av["until"])
        // Text hoechstens 80 Zeichen (Codepoints)
        val long = "😀".repeat(100)
        val cl = UserStatusLogic.putBody("busy", Dur.OFF, long, v, now)["text"]!!.jsonPrimitive.content
        assertEquals(80, cl.codePointCount(0, cl.length))
        assertEquals(80, UserStatusLogic.count(UserStatusLogic.clip(long, 80)))
    }

    @Test
    fun keepAndDefault() {
        val v = UserStatusLogic.parse(VIEW)!!
        assertTrue(UserStatusLogic.keepOffered(v, 1792440000.0))
        assertEquals(Dur.KEEP, UserStatusLogic.defaultDur(v, 1792440000.0))
        assertFalse(UserStatusLogic.keepOffered(v, 1792450200.0 - 30))   // weniger als eine Minute hin
        assertEquals(Dur.OFF, UserStatusLogic.defaultDur(v, 1792450200.0))
        assertEquals("Im Kundentermin", UserStatusLogic.ownText(v))
        assertEquals("", UserStatusLogic.ownText(v.copy(source = "focus")))
    }

    /** RT-29: Fokus-PUT nur {state, source:"focus"} - kein Text, kein Name, kein ver */
    @Test
    fun focusPayloadWithoutText() {
        val on = UserStatusLogic.focusBody(true)
        assertEquals(setOf("state", "source"), on.keys)
        assertEquals("dnd", on["state"]!!.jsonPrimitive.content)
        assertEquals("focus", on["source"]!!.jsonPrimitive.content)
        assertEquals(setOf("state", "source"), UserStatusLogic.focusBody(false).keys)
        assertEquals("available", UserStatusLogic.focusBody(false)["state"]!!.jsonPrimitive.content)
        // Unterbrechungsfilter des Systems
        assertEquals(false, UserStatusLogic.focusDnd(RingLogic.FILTER_ALL))
        assertEquals(true, UserStatusLogic.focusDnd(RingLogic.FILTER_PRIORITY))
        assertEquals(true, UserStatusLogic.focusDnd(RingLogic.FILTER_NONE))
        assertEquals(true, UserStatusLogic.focusDnd(RingLogic.FILTER_ALARMS))
        assertNull(UserStatusLogic.focusDnd(RingLogic.FILTER_UNKNOWN))
    }

    @Test
    fun prefsAndExceptions() {
        assertEquals("""{"in_call":"busy"}""", UserStatusLogic.prefBody("in_call", "busy").toString())
        assertEquals("""{"repeat":false}""", UserStatusLogic.prefBody("repeat", false).toString())
        assertEquals("""{"repeat_s":300}""", UserStatusLogic.prefBody("repeat_s", 300).toString())
        val b = StatusException("user", "bruno", "Bruno Beispiel")
        val g = StatusException("group", null, "familie")
        val l = UserStatusLogic.addException(UserStatusLogic.addException(emptyList(), b), g)
        assertEquals(l, UserStatusLogic.addException(l, StatusException("user", "bruno")))      // doppelt
        val body = UserStatusLogic.exceptionsBody(l)
        assertEquals("""{"exceptions":[{"type":"user","ref":"bruno"},{"type":"group","name":"familie"}]}""", body.toString())
        assertEquals(listOf(g), UserStatusLogic.removeException(l, b))
        // hoechstens 100
        val many = (1..120).map { StatusException("user", "u$it") }
        assertEquals(100, UserStatusLogic.exceptionsBody(many, 100)["exceptions"]!!.jsonArray.size)
        val lim = StatusLimitsU()
        assertEquals(30, UserStatusLogic.repeatSeconds("30", lim))
        assertEquals(3600, UserStatusLogic.repeatSeconds(" 3600 ", lim))
        assertNull(UserStatusLogic.repeatSeconds("29", lim))
        assertNull(UserStatusLogic.repeatSeconds("3601", lim))
        assertNull(UserStatusLogic.repeatSeconds("", lim))
    }

    @Test
    fun peersAndPreCall() {
        val o = Json.parseToJsonElement(
            """{"bert":{"s":"dnd","until":1792450200.0,"text":"Im Kundentermin"},"cora":{"s":"in_call"},
               "@~abcdefghijklmnop@b.example":{"s":"busy"},"dora":{"s":"weg"},"emil":{"s":"available"}}""",
        ).jsonObject
        val p = UserStatusLogic.peers(o)
        assertEquals(PeerStatus("dnd", 1792450200.0, "Im Kundentermin"), p["bert"])
        assertEquals(PeerStatus("in_call"), p["cora"])
        assertEquals(PeerStatus("busy"), p["@~abcdefghijklmnop@b.example"])
        assertNull(p["dora"])
        assertNull(UserStatusLogic.visible(p["emil"]))             // Verfuegbar zeigt nichts
        assertNull(UserStatusLogic.peer(JsonNull))                 // chat.status null = entfernen
        assertEquals(UserStatusLogic.PreCall.ASK, UserStatusLogic.preCall(p["bert"]))
        assertEquals(UserStatusLogic.PreCall.HINT, UserStatusLogic.preCall(p["cora"]))
        assertEquals(UserStatusLogic.PreCall.CALL, UserStatusLogic.preCall(p["@~abcdefghijklmnop@b.example"]))
        assertEquals(UserStatusLogic.PreCall.CALL, UserStatusLogic.preCall(null))
    }

    @Test
    fun liveStatusWinsAgainstOlderAnswer() {
        val got = mapOf("bert" to PeerStatus("busy"), "cora" to PeerStatus("dnd"))
        val old = mapOf("bert" to PeerStatus("dnd"))
        // chat.status fuer bert kam nach dem Absenden (Zeit 200 >= 100), fuer cora entfernt (kein alter Stand), dora alt
        val m = PresenceLogic.mergeStatus(got, old, mapOf("bert" to 200L, "cora" to 150L, "dora" to 50L), sentAt = 100L)
        assertEquals(PeerStatus("dnd"), m["bert"])
        assertNull(m["cora"])
        // vor dem Absenden: Antwort gilt
        assertEquals(got, PresenceLogic.mergeStatus(got, old, mapOf("bert" to 99L), sentAt = 100L))
        // Status zaehlt fuer Personen anderer Server als Antwort
        assertFalse(PresenceLogic.missingRemote(listOf("@~x@b.example"), emptyMap(), mapOf("@~x@b.example" to PeerStatus("busy"))))
        assertTrue(PresenceLogic.missingRemote(listOf("@~x@b.example"), emptyMap(), emptyMap()))
    }

    @Test
    fun watchEvenWithOwnOnlineOff() {
        val c = ChatSummary(id = 1, kind = "dm", peer = ChatPerson("bert", "Bert"))
        val off = ChatMe(enabled = true, uid = "anna", privacy = ChatPrivacy(receipts = true, online = false), features = listOf("online"))
        assertNull(PresenceWatchLogic.watchFor(off, c))
        assertEquals("bert", PresenceWatchLogic.watchFor(off.copy(features = listOf("online", "userstatus1")), c))
        assertNull(PresenceWatchLogic.watchFor(off.copy(features = listOf("online", "userstatus1")), c.copy(request = true)))
        assertNull(PresenceWatchLogic.watchFor(off.copy(features = listOf("online", "userstatus1")), c.copy(kind = "group")))
        assertEquals("bert", PresenceWatchLogic.watchFor(off.copy(privacy = ChatPrivacy()), c))
    }

    /** Abo mit eigenem Online-Status aus (enabled:false): Status kommt weiter, kein "online" */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun hubKeepsStatusWhenOnlineOff() = runTest {
        var calls = 0
        var disabled = false
        val hub = PresenceHub(backgroundScope, post = {
            calls++
            """{"enabled":false,"users":{},"status":{"bert":{"s":"dnd","text":"Termin"}}}"""
        }, allowed = { true }, onDisabled = { disabled = true }, statusOn = { true })
        hub.show("list", listOf("bert"))
        advanceTimeBy(401); runCurrent()
        assertTrue(disabled)
        assertTrue(hub.state.value.isEmpty())
        assertEquals(PeerStatus("dnd", null, "Termin"), hub.statuses.value["bert"])
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, calls)                                     // weiter im 60-s-Takt
        hub.onStatus("bert", null)                                 // chat.status null -> entfernen
        assertNull(hub.statuses.value["bert"])
        hub.onStatus("bert", PeerStatus("in_call"))
        assertEquals(PeerStatus("in_call"), hub.statuses.value["bert"])
        hub.hide("list")
        advanceTimeBy(500); runCurrent()
        assertTrue(hub.statuses.value.isEmpty())
    }

    @Test
    fun digestOnlyNumbers() {
        assertNull(UserStatusLogic.digest(Json.parseToJsonElement("""{"messages":0,"calls":0,"other":0}""").jsonObject))
        val d = UserStatusLogic.digest(Json.parseToJsonElement("""{"messages":4,"calls":1,"other":0,"since":1.0,"until":2.0}""").jsonObject)!!
        assertEquals(StatusDigest(4, 1, 0), d)
        val t = StatusTexts.digest(d) as UiText.Res
        assertEquals(R.string.us_digest, t.id)
        val items = t.args[0] as UiText.Res
        assertEquals(R.string.us_join, items.id)
        assertEquals(UiText.Plural(R.plurals.us_digest_messages, 4), items.args[0])
        assertEquals(UiText.Plural(R.plurals.us_digest_calls, 1), items.args[1])
        // nur eine Art: ohne Aufzaehlung
        val one = StatusTexts.digest(StatusDigest(0, 0, 3)) as UiText.Res
        assertEquals(UiText.Plural(R.plurals.us_digest_other, 3), one.args[0])
        assertNull(StatusTexts.digest(StatusDigest()))
    }

    @Test
    fun whenKinds() {
        val now = at(2026, 11, 25, 9)                              // Mittwoch
        assertEquals(UserStatusLogic.WhenKind.TODAY, UserStatusLogic.whenKind(at(2026, 11, 25, 14, 30).toEpochSecond().toDouble(), now))
        assertEquals(UserStatusLogic.WhenKind.TOMORROW, UserStatusLogic.whenKind(at(2026, 11, 26, 7).toEpochSecond().toDouble(), now))
        assertEquals(UserStatusLogic.WhenKind.WEEK, UserStatusLogic.whenKind(at(2026, 11, 27, 14, 30).toEpochSecond().toDouble(), now))
        assertEquals(UserStatusLogic.WhenKind.DATE, UserStatusLogic.whenKind(at(2026, 12, 24, 14, 30).toEpochSecond().toDouble(), now))
        // Nachladen kurz nach Ablauf, hoechstens alle 5 Minuten
        val v = UserStatusLogic.parse("""{"state":"dnd","until":1060.0,"ver":1}""")!!
        assertEquals(62_500L, UserStatusLogic.reloadInMs(v, 1000.0))
        assertEquals(2_000L, UserStatusLogic.reloadInMs(v, 2000.0))
        assertEquals(300_000L, UserStatusLogic.reloadInMs(null, 0.0))
    }

    /** Ein Server, der 409 meldet: Stand uebernehmen (conflict), kein Fehler; ver und Fokus-Nutzlast im PUT */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun centerConflictAndFocus() = runTest {
        val puts = ArrayList<Pair<String, JsonObject>>()
        var conflict = false
        val c = UserStatusCenter(
            backgroundScope,
            get = { """{"state":"available","ver":3}""" },
            put = { p, b ->
                puts.add(p to b)
                if (conflict) throw ApiException(409, UiText.Raw("veraltet"), """{"state":"busy","ver":5,"error":"veraltet"}""")
                """{"state":"${b["state"]!!.jsonPrimitive.content}","ver":4,"source":"${b["source"]!!.jsonPrimitive.content}"}"""
            },
            supported = { true },
        )
        c.load()
        assertEquals(3L, c.view.value!!.ver)
        val r1 = c.set("dnd", Dur.H1, "")
        assertFalse(r1.conflict)
        assertEquals(3L, puts[0].second["ver"]!!.jsonPrimitive.long)
        assertEquals("dnd", c.view.value!!.state)
        conflict = true
        val r2 = c.set("dnd", Dur.OFF, "x")
        assertTrue(r2.conflict)
        assertEquals("busy", c.view.value!!.state)
        assertEquals(5L, c.view.value!!.ver)
        conflict = false
        c.focus(true)
        assertEquals(setOf("state", "source"), puts.last().second.keys)
        // Live: aelterer Stand ueberholt nicht, Sammelmeldung kommt an
        assertEquals(5L, c.view.value!!.ver)                      // Antwort mit kleinerem ver ueberholt nicht
        c.onLive("status.changed", Json.parseToJsonElement("""{"state":"available","ver":1}""").jsonObject)
        assertEquals(5L, c.view.value!!.ver)
        c.onLive("status.changed", Json.parseToJsonElement("""{"state":"available","ver":9}""").jsonObject)
        assertEquals("available", c.view.value!!.state)
        // ohne Server-Faehigkeit: nichts
        val none = UserStatusCenter(backgroundScope, get = { error("nie") }, put = { _, _ -> error("nie") }, supported = { false })
        assertNull(none.load())
        assertNull(none.focus(true).view)
    }

    /** Antworten des echten Servers (saga main fedec6a auf saga-test, 25.11.2026, Demo-Konten) - Vertrag wie gelesen */
    @Test
    fun realServerResponses() {
        val conflict = """{"state": "busy", "until": null, "text": null, "source": "manual", "since": 1795590108.294977, "updated": 1795590108.294977,
            "ver": 1, "effective": "busy", "why": "manual", "quiet": false, "in_call": false, "prefs": {"quiet_dnd": true, "quiet_digest": false,
            "in_call": "knock", "show_call": true, "busy": "show", "repeat": true, "repeat_s": 180, "mention_break": false}, "exceptions": [],
            "limits": {"text": 80, "exceptions": 100, "repeat_s": [30, 3600], "until_max": 31622400, "dur_min": 60}, "now": 1795591511.236,
            "error": "Der Status wurde inzwischen geändert – bitte neu laden."}"""
        val v = UserStatusLogic.parse(conflict)!!
        assertEquals("busy", v.state)
        assertEquals(1L, v.ver)
        assertEquals("Der Status wurde inzwischen geändert – bitte neu laden.", v.error)
        // Fokus-PUT {state:"dnd", source:"focus", text:"Arbeit"} -> der Server verwirft den Text
        val focus = UserStatusLogic.parse("""{"state": "dnd", "until": null, "text": null, "source": "focus", "ver": 2, "effective": "dnd", "why": "manual"}""")!!
        assertNull(focus.text)
        assertTrue(UserStatusLogic.shown(focus).focus)
        val pres = Json.parseToJsonElement("""{"enabled": true, "ttl": 90, "users": {"bruno": {"online": false, "last": null}, "cora": {"online": false, "last": null}},
            "status": {"bruno": {"s": "dnd", "until": 1795597500.0, "text": "Im Kundentermin"}, "cora": {"s": "available"}}}""").jsonObject
        val st = UserStatusLogic.peers(pres["status"]!!.jsonObject)
        assertEquals(PeerStatus("dnd", 1795597500.0, "Im Kundentermin"), st["bruno"])
        assertNull(UserStatusLogic.visible(st["cora"]))
    }

    // ------------------------------------------------------------------ Anrufe

    @Test
    fun capsWaitingOnlyWithServer() {
        val base = CallRoom.myCaps(setOf("reneg", "multi"))
        assertFalse(RingLogic.CAP_WAITING in base)
        val w = CallRoom.myCaps(setOf("reneg", "multi", "waiting1"))
        assertTrue(RingLogic.CAP_WAITING in w)
        assertEquals(base + "waiting1", w)
        assertTrue(RingLogic.CAP_WAITING in CallRoom.myCaps(setOf("waiting1")))     // auch ohne Raum-Faehigkeiten
        // hoechstens 16, ohne Doppelte
        val many = (1..20).map { "c$it" }
        assertEquals(16, RingLogic.withWaiting(many, setOf("waiting1")).size)
        assertEquals(listOf("a", "waiting1"), RingLogic.withWaiting(listOf("a", "waiting1"), setOf("waiting1")))
    }

    @Test
    fun incomingDecision() {

        // wie bisher
        assertEquals(RingLogic.Incoming.RING, RingLogic.incoming(waiting = false, quiet = false, inCallHere = false, ringingHere = false))
        assertEquals(RingLogic.Incoming.BUSY, RingLogic.incoming(waiting = false, quiet = false, inCallHere = true, ringingHere = false))
        assertEquals(RingLogic.Incoming.BUSY, RingLogic.incoming(waiting = false, quiet = false, inCallHere = false, ringingHere = true))
        // Anklopfen: im Gespraech hier bzw. woanders - nie klingeln
        assertEquals(RingLogic.Incoming.KNOCK, RingLogic.incoming(waiting = true, quiet = false, inCallHere = true, ringingHere = false))
        assertEquals(RingLogic.Incoming.KNOCK_ELSEWHERE, RingLogic.incoming(waiting = true, quiet = false, inCallHere = false, ringingHere = false))
        // zurueckgehalten geht vor
        assertEquals(RingLogic.Incoming.QUIET, RingLogic.incoming(waiting = false, quiet = true, inCallHere = false, ringingHere = false))
        assertEquals(RingLogic.Incoming.QUIET, RingLogic.incoming(waiting = true, quiet = true, inCallHere = true, ringingHere = false))
        assertTrue(RingLogic.holdGone("ended"))
        assertTrue(RingLogic.holdGone("declined"))
        assertTrue(RingLogic.holdGone("active"))
        assertFalse(RingLogic.holdGone("ringing"))
        // nach dem eigenen Gespraech: Rest der Klingelzeit
        assertEquals(30.0, RingLogic.freedLeftS(45.0, 15_000)!!, 0.001)
        assertNull(RingLogic.freedLeftS(45.0, 44_000))
        // Anrufer
        assertEquals(R.string.us_knocking, RingLogic.ringingText(true))
        assertEquals(R.string.call_ringing, RingLogic.ringingText(false))
        assertNull(RingLogic.againText(false, "x", "Ben"))
        assertEquals(UiText.Raw("Ben telefoniert noch – du hast schon angeklopft."), RingLogic.againText(true, "Ben telefoniert noch – du hast schon angeklopft.", "Ben"))
        assertEquals(UiText.Res(R.string.us_again, "Ben"), RingLogic.againText(true, null, "Ben"))
    }

    @Test
    fun holdButtons() {
        val info = CallInfo(id = "a".repeat(32), state = "ringing", role = "callee", peer = CallPeer("bruno", "Bruno"))
        fun bar(mode: HoldBar.Mode, here: Boolean, elsewhere: Boolean) = HoldBar(info.id, 1, mode, info.peer, false, "dnd", here, elsewhere, info, 45.0, 0)
        // Anklopfen im Gespraech hier: Ablehnen + Beenden und annehmen
        bar(HoldBar.Mode.KNOCK, true, false).let { assertTrue(it.canDecline); assertTrue(it.canTake); assertFalse(it.canAccept) }
        // Anklopfen, Gespraech woanders: nur Ablehnen
        bar(HoldBar.Mode.KNOCK, false, true).let { assertTrue(it.canDecline); assertFalse(it.canTake); assertFalse(it.canAccept) }
        // still ohne eigenes Gespraech: Annehmen (kein Ablehnen, nur Ausblenden)
        bar(HoldBar.Mode.QUIET, false, false).let { assertFalse(it.canDecline); assertFalse(it.canTake); assertTrue(it.canAccept) }
        // still, eigenes Gespraech auf einem anderen Geraet: kein Annehmen
        bar(HoldBar.Mode.QUIET, false, true).let { assertFalse(it.canAccept); assertFalse(it.canTake) }
        // still im Gespraech hier: Beenden und annehmen
        bar(HoldBar.Mode.QUIET, true, false).let { assertTrue(it.canTake) }
    }

    @Test
    fun callInfoFields() {
        val j = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
        val k = j.decodeFromString(CallInfo.serializer(), """{"id":"x","state":"ringing","role":"callee","waiting":true,"quiet":true,"silent":true}""")
        assertTrue(k.waiting)
        assertTrue(k.quiet)
        assertTrue(k.silent)
        assertFalse(j.decodeFromString(CallInfo.serializer(), """{"id":"x"}""").waiting)
    }

    @Test
    fun callEntryHold() {
        fun e(json: String) = CallLogic.parseEntry(json)!!
        val dnd = e("""{"v":1,"type":"call","call":"c","media":"audio","status":"missed","from":"bruno","to":"alex","hold":"dnd"}""")
        assertEquals("dnd", dnd.hold)
        assertEquals(UiText.Res(R.string.call_entry_missed_dnd), CallLogic.entryText(dnd, "alex"))
        assertEquals(UiText.Res(R.string.call_entry_missed_video_dnd), CallLogic.entryText(dnd.copy(media = "video"), "alex"))
        assertEquals(UiText.Res(R.string.call_entry_missed_knock), CallLogic.entryText(dnd.copy(hold = "knock"), "alex"))
        assertEquals(UiText.Res(R.string.call_entry_missed_knock), CallLogic.entryText(dnd.copy(hold = "again"), "alex"))
        // ohne hold wie bisher
        assertEquals(UiText.Res(R.string.call_entry_missed), CallLogic.entryText(dnd.copy(hold = null), "alex"))
        // anrufende Seite: wie bisher "nicht angenommen" (der Server entfernt hold ohnehin)
        assertEquals(UiText.Res(R.string.call_entry_not_answered, UiText.Res(R.string.call_entry_audio)), CallLogic.entryText(dnd, "bruno"))
        assertTrue(CallLogic.missed(dnd, "alex"))
        assertNotNull(CallEntry())
    }
}
