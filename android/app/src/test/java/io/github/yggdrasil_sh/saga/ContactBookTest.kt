package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.BookContact
import io.github.yggdrasil_sh.saga.chat.ChatContacts
import io.github.yggdrasil_sh.saga.chat.ContactBook
import io.github.yggdrasil_sh.saga.chat.ContactBookLogic
import io.github.yggdrasil_sh.saga.chat.ContactBookLogic.AddrDraft
import io.github.yggdrasil_sh.saga.chat.ContactBookLogic.Draft
import io.github.yggdrasil_sh.saga.chat.ContactBookLogic.Problem
import io.github.yggdrasil_sh.saga.chat.PeopleResult
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.settings.SettingsArea
import io.github.yggdrasil_sh.saga.ui.settings.SettingsLogic
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saga 2.6 (Bugs-DB 2027a-6): Adressbuch (contacts2) nach docs/APP-API.md "Kontakte". contacts2_server.json enthaelt
 * echte Antworten des Testservers (Stand k27s, erfundene Testkonten/Nummern/Mails).
 */
class ContactBookTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    private val example = """{"id": 17, "ver": 3, "origin": "chat", "name": "Anna Berg", "own_name": null,
 "saga": {"ref": "~k3aaaaaaaaaaaaaa", "remote": false, "host": null, "sub": "", "avatar": "/api/avatar/x", "ready": true, "gone": false},
 "form": "informal", "salutation": "Liebe Anna", "neu": 1,
 "addrs": [{"id": 51, "kind": "saga", "value": "~k3aaaaaaaaaaaaaa", "type": null, "fixed": true},
           {"id": 52, "kind": "e164", "value": "+491701234567", "type": "mobile", "fixed": false},
           {"id": 53, "kind": "email", "value": "anna@example.org", "type": "work", "fixed": false}],
 "last_at": 1793460000.5, "created": 1793000000.0, "updated": 1793460000.5}"""

    private fun fixture(): JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("contacts2_server.json")!!.readText(),
    ).jsonObject

    @Test fun parseContractExample() {
        val c = ContactBookLogic.parse(example)!!
        assertEquals(17L, c.id)
        assertEquals(3L, c.ver)
        assertFalse(c.manual)
        assertEquals("Anna Berg", c.name)
        assertNull(c.ownName)
        assertEquals("~k3aaaaaaaaaaaaaa", c.saga!!.ref)
        assertEquals(listOf("+491701234567"), c.phones.map { it.value })
        assertEquals(listOf("anna@example.org"), c.mails.map { it.value })
        assertEquals("work", c.mails[0].type)
        assertTrue(ContactBookLogic.canMessage(c))
        assertNull(ContactBookLogic.parse("""{"error":"x"}"""))
        assertNull(ContactBookLogic.parse("kaputt"))
    }

    @Test fun parseServerFixture() {
        val f = fixture()
        val list = ContactBookLogic.parseList(f["list"].toString())
        assertEquals(list.total, list.contacts.size)
        assertTrue(list.contacts.size >= 4)
        val anna = list.contacts.first { it.name == "Anna Berg" }
        assertEquals("chat", anna.origin)
        assertEquals("informal", anna.form)
        assertEquals("Liebe Anna", anna.salutation)
        assertEquals(listOf("+491701234567"), anna.phones.map { it.value })
        assertTrue(anna.addrs.first().fixed)
        assertEquals("saga", anna.addrs.first().kind)
        val mara = list.contacts.first { it.name == "Mara Muster" }
        assertTrue(mara.manual)
        assertNull(mara.saga)
        assertEquals("formal", mara.form)
        assertEquals(listOf("+491705550101"), mara.phones.map { it.value })        // 0170 … mit cc 49
        assertFalse(ContactBookLogic.canMessage(mara))
        // 409 veraltetes ver: aktueller Kontakt; doppelter Wert: nur contact_id
        val cur = ContactBookLogic.conflictContact(f["conflict"].toString())
        assertTrue(cur != null && cur.ver > 1)
        val dup = ContactBookLogic.duplicateId(f["duplicate"].toString())
        assertTrue(dup != null && dup > 0)
        assertNull(ContactBookLogic.conflictContact(f["duplicate"].toString()))
        assertFalse(f["duplicate"].toString().contains("+49"))
        // alte Liste (contacts1) und Personensuche tragen contact_id
        val old = json.decodeFromString<ChatContacts>(f["chat_contacts"].toString())
        assertTrue(old.contacts.isNotEmpty() && old.contacts.all { it.contactId != null })
        assertEquals(anna.id, old.contacts.first { it.name == "Anna Berg" }.contactId)
        val people = json.decodeFromString<PeopleResult>(f["people"].toString())
        assertTrue(people.users.any { it.contact && it.contactId == anna.id })
        assertEquals(null, ContactBookLogic.ccFrom(f["settings"].toString()))
    }

    @Test fun listPath() {
        assertEquals("api/contacts?limit=200", ContactBookLogic.listPath())
        assertEquals("api/contacts?limit=200&offset=200&q=0170+123", ContactBookLogic.listPath(" 0170 123 ", offset = 200))
        assertEquals("api/contacts?limit=200&q=M%C3%BCller%26Co", ContactBookLogic.listPath("Müller&Co"))
    }

    @Test fun draftsAndProblems() {
        val n = ContactBookLogic.draftOf(null)
        assertEquals(1, n.phones.size)
        assertEquals("mobile", n.phones[0].type)
        assertEquals(Problem.NAME_NEEDED, ContactBookLogic.problem(n, manual = true))
        assertEquals(Problem.ADDR_NEEDED, ContactBookLogic.problem(n.copy(ownName = "Mara"), manual = true))
        // Chat-Kontakt: weder Name noch Adresse Pflicht
        assertNull(ContactBookLogic.problem(Draft(), manual = false))
        val ok = Draft(ownName = "Mara", phones = listOf(AddrDraft("+49 170 123 4567")))
        assertNull(ContactBookLogic.problem(ok, manual = true))
        assertEquals(Problem.BAD_PHONE, ContactBookLogic.problem(ok.copy(phones = listOf(AddrDraft("0170 abc"))), true))
        assertEquals(Problem.BAD_PHONE, ContactBookLogic.problem(ok.copy(phones = listOf(AddrDraft("170 1234567"))), true))
        assertEquals(Problem.BAD_MAIL, ContactBookLogic.problem(ok.copy(mails = listOf(AddrDraft("a@b@c"))), true))
        assertEquals(Problem.BAD_MAIL, ContactBookLogic.problem(ok.copy(mails = listOf(AddrDraft("a b@c.de"))), true))
        assertEquals(Problem.NAME_LONG, ContactBookLogic.problem(ok.copy(ownName = "x".repeat(121)), true))
        assertEquals(Problem.SALUTATION_LONG, ContactBookLogic.problem(ok.copy(salutation = "x".repeat(81)), true))
        val many = (0..10).map { AddrDraft("+49170000000$it") }
        assertEquals(Problem.TOO_MANY_PHONES, ContactBookLogic.problem(ok.copy(phones = many), true))
        // gleiche Nummer in anderer Schreibweise zaehlt einmal
        assertNull(ContactBookLogic.problem(ok.copy(phones = (0..10).map { AddrDraft(if (it % 2 == 0) "+49 170 1234567" else "+491701234567") }), true))
        // Leere Zeilen zaehlen nicht
        assertNull(ContactBookLogic.problem(ok.copy(mails = listOf(AddrDraft("  "))), true))
        assertTrue(ContactBookLogic.phoneLooksOk("+49 (0) 170 / 123-45.67"))
        assertTrue(ContactBookLogic.phoneLooksOk("0049 170 1234567"))
        assertTrue(ContactBookLogic.national("0170 1234567"))
        assertFalse(ContactBookLogic.national("0049 170 1234567"))
        assertFalse(ContactBookLogic.national("+49 170"))
    }

    @Test fun createBody() {
        val d = Draft(ownName = " Mara ", form = "formal", salutation = " ", phones = listOf(AddrDraft("0170 5550101", "mobile"), AddrDraft("")),
            mails = listOf(AddrDraft("Mara@Example.org"), AddrDraft("Mara@example.ORG", "home")))
        val b = ContactBookLogic.createBody(d, "49")
        assertEquals("Mara", b["own_name"]!!.jsonPrimitive.content)
        assertEquals("formal", b["form"]!!.jsonPrimitive.content)
        assertFalse("salutation" in b)
        val a = b["addrs"]!!.jsonArray
        assertEquals(2, a.size)                          // leere Zeile weg, gleiche Mail (Domain egal) einmal
        assertEquals("e164", a[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("0170 5550101", a[0].jsonObject["value"]!!.jsonPrimitive.content)       // der Server normalisiert
        assertEquals("mobile", a[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("type" in a[1].jsonObject)
        assertEquals("49", b["cc"]!!.jsonPrimitive.content)
        // ohne nationale Nummer kein cc
        assertFalse("cc" in ContactBookLogic.createBody(d.copy(phones = listOf(AddrDraft("+491705550101"))), "49"))
        assertFalse("cc" in ContactBookLogic.createBody(d, null))
    }

    @Test fun patchBodyOnlyChanges() {
        val c = ContactBookLogic.parse(example)!!
        val d0 = ContactBookLogic.draftOf(c)
        assertFalse(ContactBookLogic.changed(c, d0))
        assertEquals(buildJsonObject { put("ver", 3) }, ContactBookLogic.patchBody(c, d0, "49"))
        // own_name setzen, Anrede leeren, Grussform leeren
        val b = ContactBookLogic.patchBody(c, d0.copy(ownName = "Anni", form = null, salutation = ""), "49")
        assertEquals(3L, b["ver"]!!.jsonPrimitive.long)
        assertEquals("Anni", b["own_name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, b["form"])
        assertEquals(JsonNull, b["salutation"])
        assertFalse("addrs" in b)
        // Adressen: Typ geaendert -> ganze private Liste, feste nie dabei
        val b2 = ContactBookLogic.patchBody(c, d0.copy(phones = listOf(AddrDraft("+491701234567", "work"))), null)
        val a = b2["addrs"]!!.jsonArray
        assertEquals(listOf("+491701234567", "anna@example.org"), a.map { it.jsonObject["value"]!!.jsonPrimitive.content })
        assertTrue(a.none { it.jsonObject["kind"]!!.jsonPrimitive.content == "saga" })
        // alle privaten entfernt -> leere Liste
        val b3 = ContactBookLogic.patchBody(c, d0.copy(phones = emptyList(), mails = emptyList()), null)
        assertEquals(0, b3["addrs"]!!.jsonArray.size)
        // nationale Nummer -> cc mit
        assertEquals("49", ContactBookLogic.patchBody(c, d0.copy(phones = listOf(AddrDraft("030 123456"))), "49")["cc"]!!.jsonPrimitive.content)
    }

    @Test fun countryCode() {
        assertEquals("49", ContactBookLogic.ccOf("+49"))
        assertEquals("49", ContactBookLogic.ccOf(" 49 "))
        assertEquals("1", ContactBookLogic.ccOf("1"))
        assertEquals("352", ContactBookLogic.ccOf("352"))
        assertNull(ContactBookLogic.ccOf("0049"))
        assertNull(ContactBookLogic.ccOf("4912"))
        assertNull(ContactBookLogic.ccOf(""))
        assertNull(ContactBookLogic.ccOf("de"))
        assertEquals(buildJsonObject { put("cc", JsonNull) }, ContactBookLogic.ccBody(null))
        assertEquals("49", ContactBookLogic.ccFrom("""{"cc":"49"}"""))
        assertNull(ContactBookLogic.ccFrom("""{"cc":null}"""))
        assertEquals("49", ContactBookLogic.ccForRegion("de"))
        assertEquals("43", ContactBookLogic.ccForRegion("AT"))
        assertEquals("1", ContactBookLogic.ccForRegion("US"))
        assertEquals("66", ContactBookLogic.ccForRegion("TH"))
        assertEquals("7", ContactBookLogic.ccForRegion("RU"))
        assertEquals("34", ContactBookLogic.ccForRegion("ES"))
        assertNull(ContactBookLogic.ccForRegion("XX"))
        assertNull(ContactBookLogic.ccForRegion(null))
        assertNull(ContactBookLogic.ccForRegion("DEU"))
        // Pruefung k27arev: Geraete-Vorschlag nur, wenn das Konto keine hat - und nur nach geladener Konto-Einstellung
        assertEquals("49", ContactBookLogic.ccForRequest("49", true, "1"))
        assertEquals("1", ContactBookLogic.ccForRequest(null, true, "1"))
        assertNull(ContactBookLogic.ccForRequest(null, false, "1"))
        assertNull(ContactBookLogic.ccForRequest(null, true, null))
    }

    @Test fun displayHelpers() {
        val c = ContactBookLogic.parse(example)!!
        assertEquals("tel:+491701234567", ContactBookLogic.telUri("+49 170 1234567"))
        assertEquals("mailto:anna@example.org", ContactBookLogic.mailUri(" anna@example.org "))
        // Pruefung k27arev: ? # % & im Teil vor @ kodiert (keine Abfrage/kein Fragment, kein untergeschobenes cc)
        assertEquals("mailto:a%3Fcc%3Dx@example.org", ContactBookLogic.mailUri("a?cc=x@example.org"))
        assertEquals("mailto:a%23b%25c%26d@example.org", ContactBookLogic.mailUri("a#b%c&d@example.org"))
        assertEquals("mailto:j%C3%BCrgen+tag@example.org", ContactBookLogic.mailUri("jürgen+tag@example.org"))
        assertEquals("+491701234567", ContactBookLogic.listSub(c))
        assertEquals("0170 1234567", ContactBookLogic.listSub(c) { "0170 1234567" })      // Anzeige-Format von aussen
        val remote = c.copy(saga = c.saga!!.copy(remote = true, host = "b.example"), addrs = emptyList())
        assertEquals("b.example", ContactBookLogic.listSub(remote))
        assertFalse(ContactBookLogic.canMessage(c.copy(saga = c.saga.copy(gone = true))))
        val manual = BookContact(id = 5, name = "Praxis", addrs = listOf(io.github.yggdrasil_sh.saga.chat.BookAddr(1, "email", "p@example.org")))
        assertEquals("p@example.org", ContactBookLogic.listSub(manual))
        // Live
        assertEquals(17L to false, ContactBookLogic.changedEvent(buildJsonObject { put("id", 17) }))
        assertEquals(17L to true, ContactBookLogic.changedEvent(buildJsonObject { put("id", 17); put("deleted", true) }))
        assertNull(ContactBookLogic.changedEvent(buildJsonObject { put("x", 1) }))
        // Liste fortschreiben
        val l = listOf(c, manual)
        assertEquals(listOf(5L), ContactBookLogic.applied(l, null, 17).map { it.id })
        assertEquals("Neu", ContactBookLogic.applied(l, c.copy(name = "Neu")).first().name)
        assertEquals(listOf(9L, 17L, 5L), ContactBookLogic.applied(l, manual.copy(id = 9)).map { it.id })
    }

    private class Fake {
        val calls = ArrayList<String>()
        var answer: (String, JsonObject?) -> String = { _, _ -> "{}" }
        fun book(on: Boolean = true) = ContactBook(
            get = { p -> calls += "GET $p"; answer(p, null) },
            post = { p, b -> calls += "POST $p"; answer(p, b) },
            patch = { p, b -> calls += "PATCH $p $b"; answer(p, b) },
            put = { p, b -> calls += "PUT $p $b"; answer(p, b) },
            delete = { p -> calls += "DELETE $p"; answer(p, null) },
            supported = { on },
        )
    }

    @Test fun serviceRoundTrips() = runBlocking {
        val f = Fake()
        val c = ContactBookLogic.parse(example)!!
        // ohne contacts2: keine Anfrage
        assertEquals(0, f.book(on = false).list().contacts.size)
        assertTrue(f.calls.isEmpty())
        val book = f.book()
        // nichts geaendert -> kein PATCH
        assertEquals(ContactBook.Saved.Ok(c), book.update(c, ContactBookLogic.draftOf(c), null))
        assertTrue(f.calls.isEmpty())
        // gespeichert: tick + lastChanged
        f.answer = { _, _ -> example.replace("\"ver\": 3", "\"ver\": 4") }
        val r = book.update(c, ContactBookLogic.draftOf(c).copy(ownName = "Anni"), null)
        assertTrue(r is ContactBook.Saved.Ok && r.contact.ver == 4L)
        assertTrue(f.calls.single().startsWith("PATCH api/contacts/17 {\"ver\":3,\"own_name\":\"Anni\"}"))
        assertEquals(1, book.tick.value)
        assertEquals(17L, book.lastChanged)
        // 409 mit contact -> Conflict (aktueller Stand)
        f.answer = { _, _ -> throw ApiException(409, UiText.Raw("x"), """{"error":"x","contact":${example.replace("\"ver\": 3", "\"ver\": 9")}}""") }
        val k = book.update(c, ContactBookLogic.draftOf(c).copy(ownName = "B"), null)
        assertTrue(k is ContactBook.Saved.Conflict && k.current.ver == 9L)
        // 409 mit contact_id -> Duplicate
        f.answer = { _, _ -> throw ApiException(409, UiText.Raw("doppelt"), """{"error":"doppelt","contact_id":4}""") }
        val dup = book.create(Draft(ownName = "X", phones = listOf(AddrDraft("+491701234567"))), null)
        assertTrue(dup is ContactBook.Saved.Duplicate && dup.contactId == 4L)
        // 404 -> null, andere Fehler weiter
        f.answer = { _, _ -> throw ApiException(404, UiText.Raw("nf")) }
        assertNull(book.one(3))
        // Loeschen: lastChanged negativ
        f.answer = { _, _ -> """{"ok":true}""" }
        book.remove(17)
        assertEquals(-17L, book.lastChanged)
        assertTrue(f.calls.last() == "DELETE api/contacts/17")
        // Landesvorwahl
        f.answer = { _, _ -> """{"cc":"49"}""" }
        assertEquals("49", book.setCc("49"))
        assertEquals("PUT api/contacts/settings {\"cc\":\"49\"}", f.calls.last())
        // Live
        val t = book.tick.value
        book.onLive(buildJsonObject { put("id", 5); put("deleted", true) })
        assertEquals(t + 1, book.tick.value)
        assertEquals(-5L, book.lastChanged)
    }

    @Test fun settingsAndNav() {
        assertFalse(SettingsLogic.Category.CONTACTS in SettingsLogic.categories(chats = true, backup = true, nextcloud = false))
        val cats = SettingsLogic.categories(chats = true, backup = true, nextcloud = false, contacts = true)
        assertEquals(cats.indexOf(SettingsLogic.Category.CHATS) + 1, cats.indexOf(SettingsLogic.Category.CONTACTS))
        assertEquals(SettingsArea.Sub.CONTACTS, SettingsArea.sub("contacts"))
        assertEquals(SettingsArea.Sub.CONTACTS, SettingsArea.sub("Kontakte"))
        assertTrue(ContactBookLogic.has(listOf("contacts1", "contacts2")))
        assertFalse(ContactBookLogic.has(listOf("contacts1")))
        assertFalse(ContactBookLogic.has(null))
        for (d in listOf(Dest.Contacts, Dest.Contact(42))) assertEquals(d, Dest.decode(Dest.encode(d)))
        assertNull(Dest.decode("contact:x"))
    }
}
