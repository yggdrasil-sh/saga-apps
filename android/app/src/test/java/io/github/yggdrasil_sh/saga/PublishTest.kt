package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.NameCheck
import io.github.yggdrasil_sh.saga.data.NameSaved
import io.github.yggdrasil_sh.saga.data.PublicNameInfo
import io.github.yggdrasil_sh.saga.data.Publish
import io.github.yggdrasil_sh.saga.data.PublishRef
import io.github.yggdrasil_sh.saga.data.PublishState
import io.github.yggdrasil_sh.saga.data.PublishVia
import io.github.yggdrasil_sh.saga.data.acceptLanguage
import io.github.yggdrasil_sh.saga.ui.publish.NameStatus
import io.github.yggdrasil_sh.saga.ui.publish.PublicNameModel
import io.github.yggdrasil_sh.saga.ui.publish.PublishBackend
import io.github.yggdrasil_sh.saga.ui.publish.PublishModel
import io.github.yggdrasil_sh.saga.ui.publish.PublishStep
import io.github.yggdrasil_sh.saga.ui.publish.PublishView
import io.github.yggdrasil_sh.saga.ui.publish.Status
import io.github.yggdrasil_sh.saga.ui.publish.StatusKind
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = "https://s.example.org"

/** Server-Attrappe fuer /publish und /account/public-name (Regeln wie saga/publish.py, vereinfacht) */
private class FakePublishServer : PublishBackend {
    var name: String? = null
    var enabled = true
    var published = false
    var slug = "meine-seite"
    var children = false
    var indexable = false
    var profile = false
    var via: PublishVia? = null
    var withdrawnAt: Double? = null
    var withdrawnBy: String? = null
    var taken = setOf("vergeben")
    var replaced: PublishRef? = null
    var publicPages = 0
    var failPut: ApiException? = null
    var failGet: ApiException? = null
    var checkGate: CompletableDeferred<Unit>? = null
    val puts = ArrayList<JsonObject>()
    val names = ArrayList<String>()
    var deletes = 0

    fun state() = PublishState(
        enabled = enabled, name = name, published = published, slug = slug, children = children, indexable = indexable, profile = profile,
        url = if (published && name != null) "$BASE/@$name/$slug" else null, profileUrl = name?.let { "$BASE/@$it" },
        publishedVia = via, withdrawnAt = if (published) null else withdrawnAt, withdrawnBy = if (published) null else withdrawnBy,
        suggestedName = if (name == null) "vorschlag" else null,
    )

    override suspend fun publishState(id: Int): PublishState {
        failGet?.let { throw it }
        return state()
    }

    override suspend fun publish(id: Int, body: JsonObject): PublishState {
        puts += body
        failPut?.let { throw it }
        if (name == null) throw ApiException(409, UiText.Raw("Bitte zuerst einen öffentlichen Namen wählen."),
            """{"error": "Bitte zuerst einen öffentlichen Namen wählen.", "need_name": true, "suggested_name": "vorschlag"}""")
        fun b(k: String, old: Boolean) = (body[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: old
        (body["slug"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { slug = it }
        children = b("children", children); indexable = b("indexable", indexable); profile = b("profile", profile)
        published = true
        return state().copy(replacedProfile = replaced)
    }

    override suspend fun unpublish(id: Int): PublishState {
        deletes++
        published = false
        withdrawnAt = 1_760_000_000.0
        withdrawnBy = "user"
        return state()
    }

    override suspend fun publicName(): PublicNameInfo {
        failGet?.let { throw it }
        return PublicNameInfo(name = name, url = name?.let { "$BASE/@$it" }, enabled = enabled, published = publicPages, suggested = name ?: "vorschlag")
    }

    override suspend fun checkPublicName(name: String): NameCheck {
        checkGate?.await()
        return when {
            name.length < 2 -> NameCheck(false, name, "2–32 Zeichen")
            name in taken -> NameCheck(false, name, "Dieser Name ist schon vergeben.")
            else -> NameCheck(true, name)
        }
    }

    override suspend fun setPublicName(name: String): NameSaved {
        names += name
        if (name in taken) throw ApiException(409, UiText.Raw("Dieser Name ist schon vergeben."), """{"error": "Dieser Name ist schon vergeben."}""")
        this.name = name
        return NameSaved(true, name, "$BASE/@$name")
    }

    override fun friendly(e: Throwable): UiText = if (e is ApiException) e.text else UiText.Raw(e.message ?: "?")
}

/** Zustand -> Anzeige (wie draw() im Web-Dialog) */
class PublishViewTest {
    private val named = PublishState(name = "anna", slug = "garten", profileUrl = "$BASE/@anna")

    @Test
    fun notPublishedShowsPreviewNoLink() {
        val v = PublishView.of(named, "Garten ", BASE)
        assertFalse(v.switchOn)
        assertTrue(v.switchEnabled)
        assertEquals(R.string.publish_public, v.switchLabel)
        assertEquals("$BASE/@anna/garten", v.preview)
        assertNull(v.link)
        assertFalse(v.showStop)
        assertFalse(v.showChangeAddress)
        assertFalse(v.addressPending)
        assertEquals("$BASE/@anna/…", PublishView.of(named, "  ", BASE).preview)
    }

    @Test
    fun publishedShowsLinkStopAndChangeAddressOnlyWhenChanged() {
        val st = named.copy(published = true, url = "$BASE/@anna/garten", home = true)
        val v = PublishView.of(st, "garten", BASE)
        assertTrue(v.switchOn)
        assertEquals("$BASE/@anna/garten", v.link)
        assertTrue(v.showStop)
        assertTrue(v.showChangeAddress)
        assertFalse(v.changeAddressEnabled)
        assertFalse(v.addressPending)
        assertNull(v.preview)
        assertTrue(v.home)
        val changed = PublishView.of(st, "Beete", BASE)
        assertTrue(changed.changeAddressEnabled)
        assertTrue(changed.addressPending)
        assertFalse(PublishView.of(st, " ", BASE).changeAddressEnabled)     // leere Adresse nie uebernehmen
    }

    @Test
    fun publishedViaParent() {
        val via = PublishVia(7, "Reisen", "$BASE/@anna/reisen/9")
        val v = PublishView.of(named.copy(publishedVia = via), "garten", BASE)
        assertEquals(via, v.via)
        assertEquals(R.string.publish_also_own, v.switchLabel)
        // selbst veroeffentlicht: kein Hinweis auf die Elternseite, Schalter heisst wieder "Oeffentlich"
        val own = PublishView.of(named.copy(publishedVia = via, published = true, url = "$BASE/@anna/garten"), "garten", BASE)
        assertNull(own.via)
        assertEquals(R.string.publish_public, own.switchLabel)
    }

    @Test
    fun disabledByAdmin() {
        val v = PublishView.of(named.copy(enabled = false), "garten", BASE)
        assertTrue(v.disabledWarning)
        assertFalse(v.switchEnabled)
        // schon veroeffentlicht: beenden bleibt moeglich
        assertTrue(PublishView.of(named.copy(enabled = false, published = true), "garten", BASE).switchEnabled)
    }

    @Test
    fun withdrawnByAdminOrUser() {
        val admin = PublishView.of(named.copy(withdrawnAt = 1.7e9, withdrawnBy = "admin"), "garten", BASE)
        assertEquals(1.7e9, admin.withdrawnAt!!, 0.0)
        assertTrue(admin.withdrawnByAdmin)
        val user = PublishView.of(named.copy(withdrawnAt = 1.7e9, withdrawnBy = "user"), "garten", BASE)
        assertFalse(user.withdrawnByAdmin)
        assertNull(PublishView.of(named.copy(withdrawnAt = 1.7e9, published = true), "garten", BASE).withdrawnAt)
    }

    @Test
    fun profileHintNamesOtherProfilePage() {
        assertEquals(UiText.Res(R.string.publish_profile_hint), PublishView.of(named, "", BASE).profileHint)
        val v = PublishView.of(named.copy(profileOther = PublishRef(3, "Über mich")), "", BASE)
        assertEquals(UiText.Res(R.string.publish_profile_replaces, "Über mich"), v.profileHint)
    }
}

/** Antworten des Servers lesen, need_name, aeltere Server, Accept-Language */
class PublishParseTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    @Test
    fun parsesServerState() {
        val s = """{"enabled": true, "name": "anna", "published": false, "home": false, "url": null,
            "profile_url": "https://s.example.org/@anna", "published_via": {"note_id": 7, "title": "Reisen", "url": "https://s.example.org/@anna/reisen/9"},
            "withdrawn_at": 1760000000.5, "withdrawn_by": "admin", "slug": "garten", "children": true, "indexable": false, "profile": false,
            "created": 1.0, "updated": 2.0, "profile_other": {"note_id": 3, "title": "Über mich"}}"""
        val st = json.decodeFromString<PublishState>(s)
        assertEquals("anna", st.name)
        assertEquals(PublishVia(7, "Reisen", "https://s.example.org/@anna/reisen/9"), st.publishedVia)
        assertEquals("admin", st.withdrawnBy)
        assertTrue(st.children)
        assertEquals(PublishRef(3, "Über mich"), st.profileOther)
        assertEquals("https://s.example.org", Publish.publicBase(st, "https://anders.example.org"))
        // ohne Namen: Vorschlag, Basis vom eingestellten Server
        val fresh = json.decodeFromString<PublishState>("""{"enabled": true, "name": null, "published": false, "profile_url": null, "slug": "x", "suggested_name": "anna"}""")
        assertNull(fresh.name)
        assertEquals("anna", fresh.suggestedName)
        assertEquals("https://anders.example.org", Publish.publicBase(fresh, "https://anders.example.org/"))
    }

    @Test
    fun needNameFrom409() {
        val e = ApiException(409, UiText.Raw("Bitte zuerst …"), """{"error": "Bitte zuerst …", "need_name": true, "suggested_name": "anna"}""")
        assertEquals(Publish.NeedName("anna"), Publish.needName(e))
        val bad = ApiException(400, UiText.Raw("2–32 Zeichen"), """{"error": "2–32 Zeichen", "need_name": true}""")
        assertEquals(Publish.NeedName(null), Publish.needName(bad))
        assertNull(Publish.needName(ApiException(409, UiText.Raw("Adresse vergeben"), """{"error": "Diese Adresse benutzt schon eine andere deiner Seiten."}""")))
        assertNull(Publish.needName(ApiException(500, UiText.Raw("x"), """{"need_name": true}""")))
        assertNull(Publish.needName(ApiException(409, UiText.Raw("x"), "kein json")))
        assertNull(Publish.needName(IllegalStateException("x")))
    }

    @Test
    fun olderServers() {
        assertTrue(Publish.unsupported(ApiException(404, UiText.Raw("x"), """{"detail": "Not Found"}""")))
        assertFalse(Publish.unsupported(ApiException(404, UiText.Raw("x"), """{"error": "not found"}""")))   // Seite fehlt
        assertFalse(Publish.unsupported(ApiException(500, UiText.Raw("x"))))
        val old = buildJsonObject { put("id", 1); put("title", "A") }
        assertFalse(Publish.supported(old))
        val new = buildJsonObject {
            put("id", 1); put("published", false)
            put("published_via", buildJsonObject { put("note_id", 7); put("title", "Reisen"); put("url", "$BASE/@a/r/1") })
        }
        assertTrue(Publish.supported(new))
        assertEquals(PublishVia(7, "Reisen", "$BASE/@a/r/1"), Publish.via(new))
        assertNull(Publish.via(old))
    }

    @Test
    fun acceptLanguageFromAppLanguage() {
        assertEquals("de", acceptLanguage("de"))
        assertEquals("es-MX, es;q=0.9", acceptLanguage("es_MX"))
        assertNull(acceptLanguage(""))
        assertNull(acceptLanguage("und"))
        assertNull(acceptLanguage("de\r\nX-Evil: 1"))
    }
}

/** Ablauf im Dialog gegen die Server-Attrappe */
@OptIn(ExperimentalCoroutinesApi::class)
class PublishModelTest {
    private val server = FakePublishServer()
    private val changes = ArrayList<PublishState>()
    private fun model() = PublishModel(5, server, BASE) { changes += it }

    @Test
    fun withoutNameStartsWithNameStepAndChecksLive() = runTest {
        val m = model()
        m.load()
        assertEquals(PublishStep.NAME, m.step)
        assertEquals("vorschlag", m.nameInput)
        assertEquals(NameStatus.Checking, m.nameStatus)
        m.checkName()
        assertEquals(NameStatus.Free, m.nameStatus)
        assertTrue(m.canContinue)
        assertEquals("$BASE/@vorschlag", m.namePreview)
        m.editName(" Vergeben ")
        assertFalse(m.canContinue)
        m.checkName()
        assertEquals(NameStatus.Bad(UiText.Raw("Dieser Name ist schon vergeben.")), m.nameStatus)
        m.editName("")
        assertEquals(NameStatus.Idle, m.nameStatus)
    }

    @Test
    fun staleNameCheckIsIgnored() = runTest {
        val m = model()
        m.load()
        server.checkGate = CompletableDeferred()
        val job = launch { m.checkName() }
        runCurrent()
        m.editName("vergeben")                          // weitergetippt, waehrend die Pruefung laeuft
        server.checkGate!!.complete(Unit)
        job.join()
        assertEquals(NameStatus.Checking, m.nameStatus)  // Ergebnis fuer "vorschlag" gilt nicht mehr
    }

    @Test
    fun confirmNameGoesToMainStep() = runTest {
        val m = model()
        m.load()
        m.editName("  Anna ")
        m.checkName()
        m.confirmName()
        assertEquals(listOf("anna"), server.names)
        assertEquals(PublishStep.MAIN, m.step)
        assertEquals("anna", m.state?.name)
        assertEquals("$BASE/@anna/meine-seite", m.view?.preview)
    }

    @Test
    fun publishSendsAddressAndOptions() = runTest {
        server.name = "anna"
        val m = model()
        m.load()
        assertEquals(PublishStep.MAIN, m.step)
        m.setOption("children", true)                   // vor dem Veroeffentlichen nur gemerkt
        m.setOption("profile", true)
        assertTrue(server.puts.isEmpty())
        m.editSlug(" Garten ")
        m.setPublic(true)
        assertEquals(buildJsonObject { put("slug", "garten"); put("children", true); put("indexable", false); put("profile", true) }, server.puts.single())
        assertTrue(m.state!!.published)
        assertEquals("$BASE/@anna/garten", m.view?.link)
        assertEquals(Status(UiText.Res(R.string.publish_saved), StatusKind.OK), m.status)
        assertTrue(changes.last().published)              // Seitenkopf erfaehrt es
    }

    @Test
    fun optionsSaveImmediatelyOncePublished() = runTest {
        server.name = "anna"; server.published = true
        val m = model()
        m.load()
        m.setOption("indexable", true)
        assertEquals(buildJsonObject { put("indexable", true) }, server.puts.single())
        assertTrue(m.indexable)
    }

    @Test
    fun needNameOnPublishShowsNameStep() = runTest {
        server.name = "anna"
        val m = model()
        m.load()
        server.name = null                              // Name inzwischen weg (z. B. vom Admin)
        m.setPublic(true)
        assertEquals(PublishStep.NAME, m.step)
        assertEquals("vorschlag", m.nameInput)
        assertEquals(NameStatus.Bad(UiText.Raw("Bitte zuerst einen öffentlichen Namen wählen.")), m.nameStatus)
    }

    @Test
    fun serverErrorKeepsSavedOptions() = runTest {
        server.name = "anna"; server.published = true
        val m = model()
        m.load()
        server.failPut = ApiException(409, UiText.Raw("Diese Adresse benutzt schon eine andere deiner Seiten."), """{"error": "Diese Adresse benutzt schon eine andere deiner Seiten."}""")
        m.setOption("children", true)
        assertEquals(Status(UiText.Raw("Diese Adresse benutzt schon eine andere deiner Seiten."), StatusKind.ERROR), m.status)
        assertFalse(m.children)                          // Schalter zeigt wieder den gespeicherten Stand
        assertEquals(PublishStep.MAIN, m.step)
    }

    @Test
    fun changeAddressOnlyWhenChanged() = runTest {
        server.name = "anna"; server.published = true
        val m = model()
        m.load()
        m.changeSlug()
        assertTrue(server.puts.isEmpty())
        m.editSlug("beete")
        assertTrue(m.view!!.changeAddressEnabled)
        m.changeSlug()
        assertEquals(buildJsonObject { put("slug", "beete") }, server.puts.single())
        assertEquals("$BASE/@anna/beete", m.view?.link)
        assertFalse(m.view!!.addressPending)
        assertEquals(Status(UiText.Res(R.string.publish_address_saved), StatusKind.OK), m.status)
    }

    @Test
    fun replacedProfileIsReported() = runTest {
        server.name = "anna"; server.replaced = PublishRef(3, "Über mich")
        val m = model()
        m.load()
        m.setPublic(true)
        assertEquals(Status(UiText.Res(R.string.publish_saved_replaced, "Über mich"), StatusKind.OK), m.status)
    }

    @Test
    fun stopPublishing() = runTest {
        server.name = "anna"; server.published = true
        val m = model()
        m.load()
        m.setPublic(false)
        assertEquals(1, server.deletes)
        assertFalse(m.state!!.published)
        assertEquals(Status(UiText.Res(R.string.publish_stopped), StatusKind.OK), m.status)
        assertNotNull(m.view?.withdrawnAt)
        assertFalse(changes.last().published)
    }

    @Test
    fun disabledServerSendsNothing() = runTest {
        server.name = "anna"; server.enabled = false
        val m = model()
        m.load()
        assertTrue(m.view!!.disabledWarning)
        m.setPublic(true)
        assertTrue(server.puts.isEmpty())
        assertEquals(Status(UiText.Res(R.string.publish_disabled), StatusKind.ERROR), m.status)
    }

    @Test
    fun loadErrorsAndOlderServer() = runTest {
        server.failGet = ApiException(404, UiText.Raw("Not Found"), """{"detail": "Not Found"}""")
        val m = model()
        m.load()
        assertTrue(m.unsupported)
        assertNull(m.state)
        assertEquals(UiText.Raw("Not Found"), m.loadError)
        server.failGet = ApiException(502, UiText.Raw("Bad Gateway"))
        val m2 = model()
        m2.load()
        assertFalse(m2.unsupported)
        assertFalse(m2.loading)
    }
}

/** Einstellungen -> Konto: Block "Oeffentlicher Name" */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicNameModelTest {
    private val server = FakePublishServer()

    @Test
    fun changeNameWithConfirmWhenPagesArePublic() = runTest {
        server.name = "anna"; server.publicPages = 2
        val m = PublicNameModel(server)
        m.load()
        assertEquals("anna", m.input)
        m.edit("anna")
        assertEquals(NameStatus.Idle, m.status)           // unveraendert: nichts zu speichern
        assertFalse(m.canSave)
        m.edit("Anna-B")
        m.check()
        assertEquals(NameStatus.Free, m.status)
        assertTrue(m.canSave)
        assertTrue(m.needsConfirm)
        m.save()
        assertEquals(listOf("anna-b"), server.names)
        assertEquals(NameStatus.Saved, m.status)
        assertEquals("$BASE/@anna-b", m.info?.url)
        assertFalse(m.needsConfirm)
    }

    @Test
    fun firstNameNeedsNoConfirmAndErrorsAreShown() = runTest {
        val m = PublicNameModel(server)
        m.load()
        assertEquals("", m.input)
        assertEquals("vorschlag", m.info?.suggested)
        m.edit("vergeben")
        assertFalse(m.needsConfirm)
        m.check()
        assertEquals(NameStatus.Bad(UiText.Raw("Dieser Name ist schon vergeben.")), m.status)
        m.save()                                         // Server lehnt ab -> Meldung des Servers
        assertEquals(NameStatus.Bad(UiText.Raw("Dieser Name ist schon vergeben.")), m.status)
    }

    @Test
    fun hiddenOnOlderServer() = runTest {
        server.failGet = ApiException(404, UiText.Raw("Not Found"), """{"detail": "Not Found"}""")
        val m = PublicNameModel(server)
        m.load()
        assertTrue(m.hidden)
    }
}
