package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.ChatSys
import io.github.yggdrasil_sh.saga.chat.FedSysText
import io.github.yggdrasil_sh.saga.chat.SysPerson
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.RemoteCaps
import io.github.yggdrasil_sh.saga.data.RemoteRef
import io.github.yggdrasil_sh.saga.data.Role
import io.github.yggdrasil_sh.saga.data.ShareState
import io.github.yggdrasil_sh.saga.data.ShareTargets
import io.github.yggdrasil_sh.saga.ui.page.PageToolsLogic
import io.github.yggdrasil_sh.saga.ui.page.RemoteRowsLogic
import io.github.yggdrasil_sh.saga.ui.share.ShareBackend
import io.github.yggdrasil_sh.saga.ui.share.ShareLogic
import io.github.yggdrasil_sh.saga.ui.share.ShareModel
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.Versions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saga 2.3 Foederation E4-E6 in der Android-App: Faehigkeiten fremder Seiten, Versionen/Export, Gruppen, Kurzsuche */
class Fed23bTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    // ------------------------------------------------------------ E4/E6: remote.caps

    @Test
    fun remoteCapsFromPageObject() {
        val p = json.parseToJsonElement(
            """{"id":-2049,"title":"Fremd","remote":{"peer":1,"host":"b.test:8193","name":"B","caps":["db","subpages","upload","versions","export","presence","live","chatgroups",7]}}""",
        ) as JsonObject
        val caps = RemoteCaps.of(p)
        assertEquals(setOf("db", "subpages", "upload", "versions", "export", "presence", "live", "chatgroups"), caps)   // nur Texte
        // aeltere Gegenstelle (2.1/2.2): kein Feld caps -> nichts Neues
        assertTrue(RemoteCaps.of(json.parseToJsonElement("""{"remote":{"host":"old.test"}}""") as JsonObject).isEmpty())
        assertTrue(RemoteCaps.of(json.parseToJsonElement("""{"title":"eigene Seite"}""") as JsonObject).isEmpty())
        // eigene Seiten koennen alles, fremde nur mit Faehigkeit
        assertTrue(RemoteCaps.has(false, emptySet(), RemoteCaps.UPLOAD))
        assertFalse(RemoteCaps.has(true, emptySet(), RemoteCaps.UPLOAD))
        assertTrue(RemoteCaps.has(true, caps, RemoteCaps.UPLOAD))
    }

    @Test
    fun pageMetaCapsFromSharedList() {
        val items = json.decodeFromString<List<PageMeta>>(
            """[{"id":-3073,"title":"Neu","role":"edit","remote":{"host":"b.test","caps":["subpages","db"]}},
                {"id":-4097,"title":"Alt","role":"edit","remote":{"host":"old.test"}},
                {"id":12,"title":"Eigen"}]""",
        )
        assertTrue(items[0].can(RemoteCaps.SUBPAGES))
        assertFalse(items[0].can(RemoteCaps.EXPORT))
        assertFalse(items[1].can(RemoteCaps.SUBPAGES))                     // 2.2-Gegenstelle: keine Unterseiten
        assertTrue(items[2].can(RemoteCaps.SUBPAGES))                      // eigene Seite
        assertEquals(RemoteRef("b.test", null, listOf("subpages", "db")), items[0].remote)
    }

    @Test
    fun versionsAndExportRules() {
        val all = setOf("versions", "export")
        // eigene Seite: Eigentuemer immer; Bearbeiten erst ab Server 2.3
        assertTrue(PageToolsLogic.canVersions(Role.OWNER, false, emptySet(), server23 = false, database = false))
        assertFalse(PageToolsLogic.canVersions(Role.EDIT, false, emptySet(), server23 = false, database = false))
        assertTrue(PageToolsLogic.canVersions(Role.EDIT, false, emptySet(), server23 = true, database = false))
        assertFalse(PageToolsLogic.canVersions(Role.COMMENT, false, emptySet(), server23 = true, database = false))
        assertFalse(PageToolsLogic.canVersions(Role.OWNER, false, emptySet(), server23 = true, database = true))
        // fremder Server: Faehigkeit + Bearbeiten
        assertTrue(PageToolsLogic.canVersions(Role.EDIT, true, all, server23 = true, database = false))
        assertFalse(PageToolsLogic.canVersions(Role.VIEW, true, all, server23 = true, database = false))
        assertFalse(PageToolsLogic.canVersions(Role.EDIT, true, emptySet(), server23 = true, database = false))
        // Export: alle mit Zugriff; fremde nur mit "export"
        assertTrue(PageToolsLogic.canExport(false, emptySet()))
        assertTrue(PageToolsLogic.canExport(true, all))
        assertFalse(PageToolsLogic.canExport(true, setOf("versions")))
        assertEquals("Plan- A.md", PageToolsLogic.exportName("Plan/ A", "Seite", false))
        assertEquals("Seite.zip", PageToolsLogic.exportName("  ", "Seite", true))
        assertTrue(PageToolsLogic.hasChildren(-5, listOf(PageMeta(-7, parentId = -5))))
        assertFalse(PageToolsLogic.hasChildren(-5, listOf(PageMeta(-7, parentId = -6))))
    }

    @Test
    fun remoteDatabaseRows() {
        val rows = RemoteRowsLogic.parse(
            """{"schema":{"props":[]},"templates":[],"rows":[{"id":-5121,"title":"Zeile A","icon":"📌","props":{"p1":"x"}},
                {"id":-6145,"title":"","icon":"","props":{}},{"title":"ohne id"}],"role":"edit"}""",
            -4097,
        )
        assertEquals(listOf(-5121L, -6145L), rows.map { it.id })
        assertEquals(listOf(-4097L, -4097L), rows.map { it.parentId })
        assertEquals("📌", rows[0].icon)
        assertNull(rows[1].icon)
        assertTrue(RemoteRowsLogic.parse("""{"error":"x"}""", -1).isEmpty())
    }

    // ------------------------------------------------------------ E5: Gruppen, Systemzeilen

    private val me = "~aaaaaaaaaaaaaaaa@a.test:8191"
    private val bert = SysPerson("Bert Gast", "b.test:8193", "~bbbbbbbbbbbbbbbb@b.test:8193")
    private val dora = SysPerson("Dora Dritt", "c.test:8195", "~cccccccccccccccc@c.test:8195")
    private val anna = SysPerson("Anna Berg", "a.test:8191", me)
    private val otto = SysPerson("Otto Ohne", "a.test:8191", "~oooooooooooooooo@a.test:8191")

    private fun list(vararg x: UiText) = FedSysText.list(x.toList())

    @Test
    fun sysLinesParseLeniently() {
        // "by" ist bei "members" eine Person, bei "ai" ein Handle - beides lesbar (vorher: Systemzeile kaputt)
        val m = ChatLogic.sys("""{"type":"members","added":[{"name":"Dora Dritt","host":"c.test:8195","handle":"~cccccccccccccccc@c.test:8195"}],"removed":[],"by":{"name":"Bert Gast","host":"b.test:8193","handle":"~bbbbbbbbbbbbbbbb@b.test:8193"}}""")
        assertEquals("members", m.type)
        assertEquals(bert, m.byPerson)
        assertEquals(listOf(dora), m.added)
        val ai = ChatLogic.sys("""{"type":"ai","on":true,"by":"$me","name":"Anna Berg"}""")
        assertEquals(me, ai.byHandle)
        assertNull(ai.byPerson)
        val f = ChatLogic.sys("""{"type":"fed","on":true,"ai_off":true}""")
        assertTrue(f.aiOff)
        assertEquals("", ChatLogic.sys("kaputt").type)
    }

    @Test
    fun sysLineTexts() {
        // Herkunft nur bei anderen Servern; ich als "dich"
        assertEquals(UiText.Raw("Bert Gast (b.test:8193)"), FedSysText.who(bert, me, false))
        assertEquals(UiText.Raw("Otto Ohne"), FedSysText.who(otto, me, false))
        assertEquals(UiText.Res(R.string.fed_sys_you_acc), FedSysText.who(anna, me, true))
        val bertN = UiText.Raw("Bert Gast (b.test:8193)")
        val doraN = UiText.Raw("Dora Dritt (c.test:8195)")
        // Bert hat Dora und mich hinzugefuegt
        assertEquals(
            UiText.Res(R.string.fed_sys_added_by, bertN, list(doraN, UiText.Res(R.string.fed_sys_you_acc))),
            FedSysText.text(ChatSys(type = "members", added = listOf(dora, anna), by = byJson(bert)), me),
        )
        // ich habe Bert hinzugefuegt
        assertEquals(UiText.Res(R.string.fed_sys_you_added, list(bertN)), FedSysText.text(ChatSys(type = "members", added = listOf(bert), by = byJson(anna)), me))
        // ohne Urheber (Heim): "ist jetzt dabei"
        assertEquals(UiText.Res(R.string.fed_sys_joined, list(doraN)), FedSysText.text(ChatSys(type = "members", added = listOf(dora)), me))
        // verlassen, entfernt, getrennt, Konto geloescht
        assertEquals(UiText.Res(R.string.fed_sys_left, list(bertN)), FedSysText.text(ChatSys(type = "members", removed = listOf(bert), by = byJson(bert)), me))
        assertEquals(UiText.Res(R.string.fed_sys_you_left), FedSysText.text(ChatSys(type = "members", removed = listOf(anna), by = byJson(anna)), me))
        assertEquals(UiText.Res(R.string.fed_sys_removed_by, bertN, list(doraN)), FedSysText.text(ChatSys(type = "members", removed = listOf(dora), by = byJson(bert)), me))
        assertEquals(UiText.Res(R.string.fed_sys_you_removed, list(doraN)), FedSysText.text(ChatSys(type = "members", removed = listOf(dora), by = byJson(anna)), me))
        assertEquals(UiText.Res(R.string.fed_sys_disconnected, list(bertN)), FedSysText.text(ChatSys(type = "members", removed = listOf(bert), reason = "disconnected"), me))
        assertEquals(UiText.Res(R.string.fed_sys_gone, list(doraN)), FedSysText.text(ChatSys(type = "members", removed = listOf(dora), reason = "gone"), me))
        assertEquals(UiText.Res(R.string.fed_sys_not_anymore, list(doraN)), FedSysText.text(ChatSys(type = "members", removed = listOf(dora)), me))
        // beides in einer Zeile
        assertEquals(
            UiText.Res(R.string.fed_sys_two, UiText.Res(R.string.fed_sys_added_by, bertN, list(doraN)), UiText.Res(R.string.fed_sys_removed_by, bertN, list(UiText.Raw("Otto Ohne")))),
            FedSysText.text(ChatSys(type = "members", added = listOf(dora), removed = listOf(otto), by = byJson(bert)), me),
        )
        // Seiten-Chat umgestellt, KI (wie bisher)
        assertEquals(UiText.Res(R.string.fed_sys_fed), FedSysText.text(ChatSys(type = "fed", on = true), me))
        assertEquals(UiText.Res(R.string.fed_sys_fed_ai_off), FedSysText.text(ChatSys(type = "fed", on = true, aiOff = true), me))
        assertEquals(UiText.Res(R.string.fed_sys_ai_on, UiText.Res(R.string.fed_sys_you)), FedSysText.text(ChatLogic.sys("""{"type":"ai","on":true,"by":"$me"}"""), me))
        assertEquals(UiText.Res(R.string.fed_sys_ai_off, UiText.Raw("Bert")), FedSysText.text(ChatLogic.sys("""{"type":"ai","on":false,"by":"~x@b","name":"Bert"}"""), me))
        assertEquals(UiText.Res(R.string.fed_sys_note), FedSysText.text(ChatSys(type = "neu"), me))
        assertEquals(UiText.Res(R.string.fed_list_join, UiText.Raw("A"), UiText.Raw("B")), list(UiText.Raw("A"), UiText.Raw("B")))
    }

    private fun byJson(p: SysPerson) = json.parseToJsonElement("""{"name":"${p.name}","host":"${p.host}","handle":"${p.handle}"}""")

    @Test
    fun groupInvitationAndTitleMessage() {
        val c = json.decodeFromString<ChatSummary>(
            """{"id":7,"kind":"free","v":2,"ref":"0123456789abcdef0123456789abcdef@b.test:8193","fed":true,"home":"b.test:8193","home_self":false,
                "my_handle":"$me","state":"request","request":true,"invited_by":{"name":"Bert Gast","host":"b.test:8193","label":"Bert Gast · b.test:8193"},
                "title_msg":{"seq":3,"id":"m1","kind":"title","sender_device":"d1","ts":1,"body_ct":"x","sig":"s","key":"k","key_for":"device","sender_handle":"~bbbbbbbbbbbbbbbb@b.test:8193"},
                "members":[{"username":"@~bbbbbbbbbbbbbbbb@b.test:8193","name":"Bert Gast","handle":"~bbbbbbbbbbbbbbbb@b.test:8193","host":"b.test:8193","remote":true},
                           {"username":"fa-anna","name":"Anna Berg","me":true,"handle":"$me"}],"member_count":2}""",
        )
        assertTrue(c.isFed)
        assertTrue(c.request)
        assertEquals("Bert Gast", c.invitedBy?.name)
        assertEquals("b.test:8193", c.invitedBy?.host)
        assertEquals("~bbbbbbbbbbbbbbbb@b.test:8193", c.titleMsg?.senderHandle)
        assertEquals("title", c.titleMsg?.kind)
        // lokaler Chat: kein invited_by
        assertNull(json.decodeFromString<ChatSummary>("""{"id":1,"kind":"free"}""").invitedBy)
    }

    // ------------------------------------------------------------ Kurzsuche nur ab Server 2.3

    @Test
    fun shortSearchOnlyFrom23() {
        assertTrue(Versions.atLeast("2.3.0", "2.3.0"))
        assertTrue(Versions.atLeast("2.4.1-rc1", "2.3.0"))
        assertFalse(Versions.atLeast("2.2.9", "2.3.0"))
        assertFalse(Versions.atLeast(null, "2.3.0"))
        assertEquals(1, ChatLogic.peopleMin("2.3.0"))
        assertEquals(3, ChatLogic.peopleMin("2.2.0"))
        assertEquals(3, ChatLogic.peopleMin(null))                       // noch unbekannt: wie bisher
        val st = ShareState()
        assertTrue(ShareLogic.canSearch("h", true))
        assertFalse(ShareLogic.canSearch("hd", false))
        assertTrue(ShareLogic.canSearch("hdx", false))
        assertEquals(R.string.share_search_hint, ShareLogic.searchHint(st, "hd", true))
        assertEquals(R.string.share_search_hint_min3, ShareLogic.searchHint(st, "hd", false))
        assertEquals(R.string.share_search_hint_scope_min3, ShareLogic.searchHint(st.copy(scope = true), "h", false))
        assertNull(ShareLogic.searchHint(st, "hdx", false))
    }

    private class OldServer : ShareBackend {
        val queries = ArrayList<String>()
        override suspend fun shares(id: Long) = ShareState()
        override suspend fun putShare(id: Long, type: String, target: String, role: String) = ShareState()
        override suspend fun deleteShare(id: Long, type: String, target: String) = ShareState()
        override suspend fun shareTargets(query: String): ShareTargets {
            queries += query
            return ShareTargets()
        }
        override fun friendly(e: Throwable): UiText = UiText.Raw("?")
        override val shortSearch: Boolean get() = false
    }

    @Test
    fun oldServerSearchesFromThreeCharacters() = runTest {
        val be = OldServer()
        val m = ShareModel(1, be)
        m.load()
        m.editQuery("hd")
        assertFalse(m.canSearch)
        m.search()
        assertTrue(be.queries.isEmpty())                                   // Server bis 2.2: erst ab 3 Zeichen
        assertEquals(R.string.share_search_hint_min3, m.searchHint(ShareState()))
        m.editQuery("hdx")
        m.search()
        assertEquals(listOf("hdx"), be.queries)
    }
}
