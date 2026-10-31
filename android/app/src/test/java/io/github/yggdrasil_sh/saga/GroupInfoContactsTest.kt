package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatContact
import io.github.yggdrasil_sh.saga.chat.ChatContacts
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.ChatSys
import io.github.yggdrasil_sh.saga.chat.ChatWrap
import io.github.yggdrasil_sh.saga.chat.Contacts
import io.github.yggdrasil_sh.saga.chat.FedSysText
import io.github.yggdrasil_sh.saga.chat.GroupInfo
import io.github.yggdrasil_sh.saga.chat.PeopleResult
import io.github.yggdrasil_sh.saga.chat.SysPerson
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saga 2.5: Gruppeninfo + Admins (chatadm1) und Kontakte (contacts1). groupinfo_server.json enthaelt echte Antworten
 * des Testservers (testing, Stufe 3; Anmeldenamen durch uid-a/b/c ersetzt, Host a.test:8191).
 */
class GroupInfoContactsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val fx: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("groupinfo_server.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun chat(k: String) = json.decodeFromJsonElement(ChatWrap.serializer(), JsonObject(mapOf("chat" to fx[k]!!))).chat

    // ------------------------------------------------------------ Rechte

    @Test
    fun rightsFromCanFieldsOfRealServer() {
        val a = chat("chat_admin")
        assertEquals(3, a.v)
        assertEquals(true, a.meAdmin)
        assertEquals(GroupInfo.Rights(manage = true, admin = true, rename = true, readOnly = false), GroupInfo.rights(a, true))
        val b = chat("chat_member")
        assertEquals(GroupInfo.Rights(manage = false, admin = false, rename = true, readOnly = false), GroupInfo.rights(b, true))
        // members[].admin: nur Anna (ich)
        assertEquals(listOf("Anna Admin"), a.members.filter { it.admin }.map { it.name })
    }

    @Test
    fun rightsWithoutFeatureOrOldServer() {
        val old = ChatSummary(id = 1, kind = "free", role = "admin")
        assertEquals(GroupInfo.Rights(manage = true, admin = false, rename = true, readOnly = false), GroupInfo.rights(old, true))
        // Feature fehlt: can_* werden ignoriert, keine Admin-Vergabe
        val c = ChatSummary(id = 1, kind = "free", role = "member", canManage = true, canAdmin = true, canRename = true)
        assertFalse(GroupInfo.rights(c, false).admin)
        assertFalse(GroupInfo.rights(c, false).manage)
        // schreibgeschuetzte Gruppe ueber Servergrenzen: can_manage false, can_rename false
        val ro = ChatSummary(id = 1, kind = "free", role = "admin", canManage = false, canAdmin = false, canRename = false)
        assertEquals(GroupInfo.Rights(false, false, false, false), GroupInfo.rights(ro, true))
        // group/page: nur lesend
        assertTrue(GroupInfo.rights(ChatSummary(id = 2, kind = "group", canManage = false), true).readOnly)
        assertTrue(GroupInfo.rights(ChatSummary(id = 3, kind = "page"), true).readOnly)
        assertFalse(GroupInfo.rights(ChatSummary(id = 4, kind = "dm"), true).readOnly)
        // Gruppeninfo nur fuer Gruppen und mit Feature
        assertTrue(GroupInfo.usesGroupInfo(ChatSummary(id = 1, kind = "free"), true))
        assertTrue(GroupInfo.usesGroupInfo(ChatSummary(id = 1, kind = "page"), true))
        assertFalse(GroupInfo.usesGroupInfo(ChatSummary(id = 1, kind = "dm"), true))
        assertFalse(GroupInfo.usesGroupInfo(ChatSummary(id = 1, kind = "free"), false))
    }

    @Test
    fun memberActionsAndOrder() {
        val a = chat("chat_admin")
        val r = GroupInfo.rights(a, true)
        val me = a.members.first { it.me }
        val bert = a.members.first { it.name == "Bert Admin" }
        assertEquals(listOf(GroupInfo.Act.DROP_ADMIN), GroupInfo.actions(a, me, r))       // ich (Admin): nur mir entziehen
        assertEquals(listOf(GroupInfo.Act.SAFETY, GroupInfo.Act.MAKE_ADMIN, GroupInfo.Act.REMOVE), GroupInfo.actions(a, bert, r))
        val b = chat("chat_member")
        val rb = GroupInfo.rights(b, true)
        assertEquals(listOf(GroupInfo.Act.SAFETY), GroupInfo.actions(b, b.members.first { !it.me }, rb))
        assertEquals(emptyList<GroupInfo.Act>(), GroupInfo.actions(b, b.members.first { it.me }, rb))
        // Reihenfolge: ich, Admins, Name
        val ms = listOf(ChatPerson("x", "Zoe"), ChatPerson("y", "Adam"), ChatPerson("z", "Max", admin = true), ChatPerson("m", "Ich", me = true))
        assertEquals(listOf("Ich", "Max", "Adam", "Zoe"), GroupInfo.sorted(ms).map { it.name })
    }

    @Test
    fun handleLineNeverLoginName() {
        val a = chat("chat_admin")
        val onHost: (String) -> String = { "auf $it" }
        a.members.forEach { m ->
            val line = GroupInfo.subline(m, a.myHandle, a.remote, onHost)
            assertFalse(line, line.contains("uid-"))
            assertTrue(line, line.startsWith("~") && !line.contains("@"))         // eigener Server: ohne @host
        }
        // Person eines anderen Servers in einer Gruppe ueber Servergrenzen
        val p = ChatPerson("~bbbbbbbbbbbbbbbb@b.test", "Bert", handle = "~bbbbbbbbbbbbbbbb@b.test", host = "b.test", remote = true)
        assertEquals("auf b.test · ~bbbbbbbbbbbbbbbb", GroupInfo.subline(p, "~aaaaaaaaaaaaaaaa@a.test", true, onHost))
        // Chats v1: ref ist der Such-Handle, username bleibt verborgen
        assertEquals("~k3abcdefgh", GroupInfo.subline(ChatPerson("anna", "Anna", ref = "~k3abcdefgh"), null, false, onHost))
        assertEquals("", GroupInfo.subline(ChatPerson("anna", "Anna", ref = "anna"), null, false, onHost))
        // who-Pfad: '~' und '@' bleiben, ':' kodiert
        assertEquals("~ab@a.test%3A8191", GroupInfo.whoPath(ChatPerson("~ab@a.test:8191", "X")))
    }

    // ------------------------------------------------------------ Systemzeile admin

    @Test
    fun adminSysLinesFromRealServer() {
        val a = chat("chat_admin")
        val lines = fx["sys_admin"]!!.jsonArray.map { ChatLogic.sys(it.toString()) }
        assertEquals(2, lines.size)
        assertEquals("admin", lines[0].type)
        assertEquals("Bert Admin", lines[0].target?.name)
        val bertN = UiText.Raw("Bert Admin")
        // aus meiner Sicht (Anna hat vergeben/entzogen)
        assertEquals(UiText.Res(R.string.gi_sys_you_made_admin, bertN), FedSysText.text(lines[0], a.myHandle))
        assertEquals(UiText.Res(R.string.gi_sys_you_dropped_admin, bertN), FedSysText.text(lines[1], a.myHandle))
        // aus Berts Sicht: "Anna hat dich zum Admin gemacht"
        val bertHandle = lines[0].target!!.handle
        assertEquals(UiText.Res(R.string.gi_sys_made_admin, UiText.Raw("Anna Admin"), UiText.Res(R.string.fed_sys_you_acc)), FedSysText.text(lines[0], bertHandle))
        assertEquals(UiText.Res(R.string.gi_sys_dropped_admin, UiText.Raw("Anna Admin"), UiText.Res(R.string.fed_sys_you_acc)), FedSysText.text(lines[1], bertHandle))
    }

    @Test
    fun adminSysLineSelfAndWithoutBy() {
        val me = "~aaaaaaaaaaaaaaaa@a.test"
        val ann = SysPerson("Anna", "a.test", me)
        val bert = SysPerson("Bert", "b.test", "~bbbbbbbbbbbbbbbb@b.test")
        fun by(p: SysPerson) = json.parseToJsonElement("""{"name":"${p.name}","host":"${p.host}","handle":"${p.handle}"}""")
        // by == target
        assertEquals(UiText.Res(R.string.gi_sys_you_now_admin), FedSysText.text(ChatSys(type = "admin", on = true, target = ann, by = by(ann)), me))
        assertEquals(UiText.Res(R.string.gi_sys_you_no_admin), FedSysText.text(ChatSys(type = "admin", on = false, target = ann, by = by(ann)), me))
        assertEquals(UiText.Res(R.string.gi_sys_no_admin, UiText.Raw("Bert (b.test)")), FedSysText.text(ChatSys(type = "admin", on = false, target = bert, by = by(bert)), me))
        // ohne Urheber (by null): ohne Urheber
        assertEquals(UiText.Res(R.string.gi_sys_now_admin, UiText.Raw("Bert (b.test)")), FedSysText.text(ChatSys(type = "admin", on = true, target = bert), me))
        assertEquals(UiText.Res(R.string.gi_sys_no_admin, UiText.Raw("Bert (b.test)")),
            FedSysText.text(ChatLogic.sys("""{"type":"admin","target":{"name":"Bert","host":"b.test","handle":"~bbbbbbbbbbbbbbbb@b.test"},"on":false,"by":null}"""), me))
    }

    // ------------------------------------------------------------ Antworten / Fehler

    @Test
    fun patchAndErrorsFromRealServer() {
        val p = fx["patch_admin"]!!.jsonObject
        assertEquals(200, p["status"]!!.jsonPrimitive.int)
        val c = json.decodeFromJsonElement(ChatWrap.serializer(), p["body"]!!).chat
        assertTrue(c.members.first { it.name == "Bert Admin" }.admin)
        assertEquals(3, c.members.size)
        val last = fx["patch_last_admin"]!!.jsonObject
        assertEquals(409, last["status"]!!.jsonPrimitive.int)
        assertTrue(last["body"]!!.jsonObject["error"]!!.jsonPrimitive.content.contains("letzte Admin"))
        assertEquals(200, fx["contact_delete"]!!.jsonObject["status"]!!.jsonPrimitive.int)
        assertEquals(404, fx["contact_delete_again"]!!.jsonObject["status"]!!.jsonPrimitive.int)
    }

    // ------------------------------------------------------------ Kontakte

    @Test
    fun contactsParseGoneAndPick() {
        val ks = json.decodeFromJsonElement(ChatContacts.serializer(), fx["contacts"]!!).contacts
        assertEquals(listOf("Bert Admin", "Cora Admin"), ks.map { it.name })
        assertTrue(ks.all { it.ref.startsWith("~") && !it.remote && it.ready && !it.gone })
        assertFalse(fx["contacts"].toString().contains("uid-"))
        assertEquals(Contacts.Pick.Local(ks[0].ref), Contacts.pick(ks[0]))
        val gone = json.decodeFromJsonElement(ChatContacts.serializer(), fx["contacts_gone"]!!).contacts
        val cora = gone.first { it.name == "Cora Admin" }
        assertTrue(cora.gone)
        assertFalse(Contacts.pickable(cora))
        assertTrue(Contacts.pickable(gone.first { it.name == "Bert Admin" }))
        assertEquals(UiText.Res(R.string.ct_account_gone), Contacts.subline(cora))
        // Kennung als sub wird nicht gezeigt
        assertNull(Contacts.subline(gone.first { it.name == "Bert Admin" }))
    }

    @Test
    fun contactsRemoteSortAddFilterAndHidden() {
        val rem = ChatContact(ref = "~bslpaaaaaaaaaaaa@b.example", name = "Bert Gast", sub = "b.example", host = "b.example", remote = true, ready = true, lastAt = 10.0)
        val loc = ChatContact(ref = "~k3aaaaaaaaaaaaaa", name = "Anna Berg", sub = "Anna B.", ready = false, lastAt = 20.0)
        val off = rem.copy(ref = "~cccccccccccccccc@c.example", host = "c.example", sub = "c.example", gone = true, lastAt = 5.0)
        assertEquals(Contacts.Pick.Remote(rem.ref), Contacts.pick(rem))
        assertEquals(listOf(loc, rem, off), Contacts.sorted(listOf(rem, off, loc)))
        assertEquals(UiText.Res(R.string.ct_person_on, "b.example"), Contacts.subline(rem))
        assertEquals(UiText.Res(R.string.ct_server_gone, "c.example"), Contacts.subline(off))
        assertEquals(UiText.Res(R.string.ct_sub_not_ready, "Anna B."), Contacts.subline(loc))
        // Mitglied hinzufuegen: ohne Mitglieder, fremde nur in Gruppen ueber Servergrenzen
        val lokal = ChatSummary(id = 1, kind = "free", members = listOf(ChatPerson("anna", "Anna", ref = loc.ref)))
        assertEquals(emptyList<ChatContact>(), Contacts.forAdd(listOf(loc, rem), lokal))
        val fed = ChatSummary(id = 2, kind = "free", v = 3, ref = "abc@a.test", members = emptyList())
        assertEquals(listOf(loc, rem), Contacts.forAdd(listOf(loc, rem), fed))
        // Live: chat.contacts {ref, hidden:true}
        assertEquals(listOf(rem), Contacts.applyHidden(listOf(loc, rem), loc.ref, true))
        assertEquals(listOf(loc, rem), Contacts.applyHidden(listOf(loc, rem), loc.ref, false))
        // DELETE-Pfad: '@' darf bleiben
        assertEquals("api/chat/contacts/~bslpaaaaaaaaaaaa@b.example", Contacts.deletePath(rem.ref))
        assertEquals(rem.ref, Contacts.asFedPerson(rem).handle)
        assertEquals("b.example", Contacts.asFedPerson(rem).host)
        assertEquals(loc.ref, Contacts.asPerson(loc).username)
    }

    @Test
    fun peopleMarksContactsFirst() {
        val r = json.decodeFromJsonElement(PeopleResult.serializer(), fx["people"]!!)
        assertTrue(r.users.first().contact)
        assertEquals("Bert Admin", r.users.first().name)
        assertFalse(json.decodeFromString(PeopleResult.serializer(), """{"users":[{"username":"~x","name":"X"}]}""").users[0].contact)
    }
}
