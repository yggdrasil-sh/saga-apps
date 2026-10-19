package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Role
import io.github.yggdrasil_sh.saga.data.ShareEntry
import io.github.yggdrasil_sh.saga.data.ShareState
import io.github.yggdrasil_sh.saga.data.ShareTargets
import io.github.yggdrasil_sh.saga.data.ShareVia
import io.github.yggdrasil_sh.saga.data.TargetUser
import io.github.yggdrasil_sh.saga.ui.share.ShareBackend
import io.github.yggdrasil_sh.saga.ui.share.ShareLogic
import io.github.yggdrasil_sh.saga.ui.share.ShareModel
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeShares : ShareBackend {
    var state = ShareState(groups = listOf("sc-team"))
    val queries = ArrayList<String>()
    var targets = ShareTargets(users = listOf(TargetUser("sc-bert", "Bert Bearbeitet")))
    var failDelete = false
    var sharedFlag: Boolean? = null

    override suspend fun shares(id: Long) = state
    override suspend fun putShare(id: Long, type: String, target: String, role: String): ShareState {
        val rest = state.shares.filter { !(it.type == type && it.target == target) }
        state = state.copy(shares = rest + ShareEntry(type, target, if (type == "user") "Bert Bearbeitet" else target, role, role))
        return state
    }
    override suspend fun deleteShare(id: Long, type: String, target: String): ShareState {
        if (failDelete) throw ApiException(404, UiText.Raw("not found"))
        state = state.copy(shares = state.shares.filter { !(it.type == type && it.target == target) })
        return state
    }
    override suspend fun shareTargets(query: String): ShareTargets {
        queries += query
        return if (query.startsWith("sc-")) targets else ShareTargets()
    }
    override fun shareChanged(id: Long, shared: Boolean) { sharedFlag = shared }
    override fun friendly(e: Throwable): UiText = (e as? ApiException)?.text ?: UiText.Raw("?")
}

class ShareModelTest {
    private val viaGroup = ShareVia(type = "group", name = "sc-team", role = "edit", noteId = 5)
    private val viaPage = ShareVia(type = "user", name = "Bert", role = "comment", noteId = 4, title = "Projektplan Garten")

    @Test
    fun effectiveRoleAndRemovalText() {
        val bert = ShareEntry("user", "sc-bert", "Bert", "view", "edit", via = listOf(viaGroup))
        assertEquals(viaGroup, ShareLogic.topVia(bert))                     // "(über sc-team: Bearbeiten)"
        assertNull(ShareLogic.topVia(bert.copy(role = "edit")))             // nicht mehr als direkt
        assertEquals(
            UiText.Res(R.string.share_remove_keeps, "Bert", UiText.Raw("sc-team"), UiText.Res(R.string.share_role_edit)),
            ShareLogic.removeText(bert),
        )
        assertEquals(UiText.Res(R.string.share_remove_user, "Cora"), ShareLogic.removeText(ShareEntry("user", "sc-cora", "Cora", "view")))
        assertEquals(UiText.Res(R.string.share_remove_group, "sc-team"), ShareLogic.removeText(ShareEntry("group", "sc-team", "sc-team", "edit")))
        assertEquals(UiText.Res(R.string.share_quoted, "Projektplan Garten"), ShareLogic.viaLabel(viaPage))
    }

    @Test
    fun subLinesAndChips() {
        assertEquals(UiText.Raw("sc-bert"), ShareLogic.entrySub("user", "sc-bert", null))
        assertEquals(UiText.Res(R.string.share_group), ShareLogic.entrySub("group", "t", null))
        assertEquals(UiText.Plural(R.plurals.share_group_members, 2), ShareLogic.entrySub("group", "t", 2))
        val st = ShareState(groups = listOf("sc-team"))
        assertTrue(ShareLogic.chipsVisible(st, ""))
        assertFalse(ShareLogic.chipsVisible(st, "sc-"))                    // beim Suchen keine Chips
        assertFalse(ShareLogic.chipsVisible(st.copy(shareable = false), ""))
        assertFalse(ShareLogic.chipsVisible(ShareState(), ""))
        assertEquals(R.string.share_search_hint_scope, ShareLogic.searchHint(st.copy(scope = true), "sc"))
        assertEquals(R.string.share_help_edit, ShareLogic.roleHelp(Role.EDIT))
        assertEquals(listOf(Role.VIEW, Role.COMMENT, Role.EDIT), ShareLogic.ROLES)
    }

    /** G5: "Mindestens 3 Zeichen." nur bei 1-2 eingegebenen Zeichen - nicht bei leerem Feld, nicht ab 3 Zeichen */
    @Test
    fun minCharsHintOnlyForOneOrTwoCharacters() {
        val st = ShareState()
        assertNull(ShareLogic.searchHint(st, ""))
        assertNull(ShareLogic.searchHint(st, "   "))
        assertEquals(R.string.share_search_hint, ShareLogic.searchHint(st, "s"))
        assertEquals(R.string.share_search_hint, ShareLogic.searchHint(st, " sh "))
        assertNull(ShareLogic.searchHint(st, "sh-"))
        assertNull(ShareLogic.searchHint(st, "sh-and"))
    }

    @Test
    fun searchStartsAtOneCharacter() = runTest {
        val be = FakeShares()
        val m = ShareModel(1, be)
        m.load()
        m.editQuery("  ")
        m.search()
        assertTrue(be.queries.isEmpty())                                   // nie die ganze Liste
        assertNull(m.hits)
        // 2.3: ab 1 Zeichen (Server: nur genaue Treffer) - ohne Treffer kein "Niemand gefunden"
        m.editQuery("hd")
        m.search()
        assertEquals(listOf("hd"), be.queries)
        assertNull(m.searchError)
        be.queries.clear()
        m.editQuery("sc-b")
        m.search()
        assertEquals(listOf("sc-b"), be.queries)
        assertEquals("sc-bert", m.hits?.users?.single()?.username)
        m.editQuery("xyz")
        m.search()
        assertEquals(UiText.Res(R.string.share_nobody), m.searchError)
        // BUG-112: nur ein Treffer auf einer anderen Instanz -> kein "Niemand gefunden"
        be.targets = ShareTargets(remote = listOf(io.github.yggdrasil_sh.saga.data.TargetRemote("remote", "sc-fed@b.test:8163", "Foed")))
        m.editQuery("sc-fed@b.test:8163")
        m.search()
        assertNull(m.searchError)
    }

    @Test
    fun addChangeRemove() = runTest {
        val be = FakeShares()
        val m = ShareModel(1, be)
        m.load()
        m.newRole = Role.EDIT
        m.editQuery("sc-b")
        m.add("user", "sc-bert", "Bert Bearbeitet")
        assertEquals("edit", be.state.shares.single().role)
        assertEquals("", m.query)                                           // Suche geleert
        assertEquals(true, be.sharedFlag)                                   // Personen-Symbol im Baum
        assertEquals(UiText.Res(R.string.share_added, "Bert Bearbeitet", UiText.Res(R.string.share_role_edit)), m.status)
        assertEquals(mapOf("user:sc-bert" to Role.EDIT), ShareLogic.taken(m.state!!))
        m.changeRole(m.state!!.shares.single(), Role.COMMENT)
        assertEquals("comment", be.state.shares.single().role)
        assertEquals(UiText.Res(R.string.share_role_changed), m.status)
        be.failDelete = true
        m.remove(m.state!!.shares.single())
        assertEquals(UiText.Raw("not found"), m.itemErrors["user:sc-bert"])   // Fehler direkt am Eintrag
        be.failDelete = false
        m.remove(m.state!!.shares.single())
        assertTrue(be.state.shares.isEmpty())
        assertFalse(m.itemErrors.containsKey("user:sc-bert"))
        assertEquals(false, be.sharedFlag)
        assertEquals(UiText.Res(R.string.share_removed), m.status)
    }

    @Test
    fun parsesServerState() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        val s = """{"enabled":true,"scope":false,"max":200,"shareable":true,
            "shares":[{"type":"user","target":"sc-bert","name":"Bert Bearbeitet","role":"view","created":1.0,
                       "via":[{"type":"group","name":"sc-team","role":"edit","note_id":11001,"title":null}],"effective":"edit"},
                      {"type":"group","target":"sc-team","name":"sc-team","role":"edit","created":2.0,"members":2,"effective":"edit"}],
            "inherited":[{"note_id":11000,"title":"Projektplan Garten","type":"user","target":"sc-cora","name":"Cora Liest","role":"comment"}],
            "groups":["sc-team"]}"""
        val st = json.decodeFromString<ShareState>(s)
        assertEquals(2, st.shares.size)
        assertEquals("edit", st.shares[0].effective)
        assertEquals(2, st.shares[1].members)
        assertEquals(11000, st.inherited.single().noteId)
        assertEquals(viaGroup.copy(noteId = 11001), ShareLogic.topVia(st.shares[0]))
    }
}
