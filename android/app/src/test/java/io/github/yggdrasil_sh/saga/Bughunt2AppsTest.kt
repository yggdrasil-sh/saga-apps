package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallLabels
import io.github.yggdrasil_sh.saga.call.CallLayout
import io.github.yggdrasil_sh.saga.chat.ChatOffline
import io.github.yggdrasil_sh.saga.data.MemorySecretStore
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SecureStore
import io.github.yggdrasil_sh.saga.data.SignOutChat
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbSchema
import io.github.yggdrasil_sh.saga.db.DbViews
import io.github.yggdrasil_sh.saga.ui.database.pickerDescription
import io.github.yggdrasil_sh.saga.ui.page.rowParentMaybeDb
import io.github.yggdrasil_sh.saga.ui.page.rowProps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bughunt-Runde 2 (Rolle apps): A02, A09, A10, A11/A12, A14, A18 */
class Bughunt2AppsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun o(s: String) = json.parseToJsonElement(s).jsonObject

    // ------------------------------------------------------------------ A11/A12 Schema nicht von aelteren Antworten ueberschreiben

    private val filtered = DbSchema(o("""{"srev":5,"props":[{"id":"st","name":"Status","type":"select","options":[{"id":"ip","name":"In progress"}]}],
        "views":[{"id":"tabelle","name":"","type":"table","filters":[{"pid":"st","op":"is","value":"ip"}]}]}"""))
    private val stale = DbSchema(o("""{"srev":4,"props":[{"id":"st","name":"Status","type":"select","options":[{"id":"ip","name":"In progress"}]}],
        "views":[{"id":"tabelle","name":"","type":"table"}]}"""))

    @Test
    fun a11SrevParsed() {
        assertEquals(5, filtered.srev)
        assertEquals(0, DbSchema(o("""{"props":[]}""")).srev)          // aelterer Server ohne srev
    }

    @Test
    fun a11OlderResponseDoesNotReplaceLocalSchema() {
        assertFalse(DbViews.acceptSchema(filtered, stale, asked = 3, now = 3))     // kleinere srev
        assertFalse(DbViews.acceptSchema(filtered, filtered, asked = 2, now = 3))  // vor der eigenen Aenderung angefragt
        assertTrue(DbViews.acceptSchema(filtered, filtered, asked = 3, now = 3))
        assertTrue(DbViews.acceptSchema(null, stale, asked = 0, now = 5))          // erstes Laden
        val newer = DbSchema(o("""{"srev":6,"props":[],"views":[]}"""))
        assertTrue(DbViews.acceptSchema(filtered, newer, asked = 3, now = 3))      // jemand anderes hat gespeichert
    }

    @Test
    fun a11NewEntryTakesFilterValueOfKeptSchema() {
        val v = filtered.view("tabelle")
        assertEquals(JsonPrimitive("ip"), DbQuery(filtered).defaults(v)["st"])
        assertTrue(DbQuery(stale).defaults(stale.view("tabelle")).isEmpty())       // so war es mit dem alten Schema
    }

    // ------------------------------------------------------------------ A02 Eigenschaften auch leer

    @Test
    fun a02EmptyPropsShownForRowsAndTemplates() {
        assertEquals(0, rowProps(o("""{"props":{}}"""), 7)!!.size)
        assertEquals(0, rowProps(o("""{}"""), 7)!!.size)                          // Feld fehlt: trotzdem Zeile
        assertNull(rowProps(o("""{}"""), null))                                   // ohne Elternseite keine Zeile
        assertEquals(1, rowProps(o("""{"props":{"st":"ip"}}"""), 7)!!.size)
    }

    @Test
    fun a02OnlyUnderDatabases() {
        assertTrue(rowParentMaybeDb(null))                                        // unbekannt (geteilt): nachfragen
        assertTrue(rowParentMaybeDb(PageMeta(1, kind = "database")))
        assertFalse(rowParentMaybeDb(PageMeta(1, kind = "page")))                 // normale Unterseite: nichts laden
    }

    // ------------------------------------------------------------------ A09 Offline-Chat

    @Test
    fun a09ReopenAfterFailedLoad() {
        assertTrue(ChatOffline.reopen(opened = false, loading = false, notFound = false))
        assertFalse(ChatOffline.reopen(opened = true, loading = false, notFound = false))
        assertFalse(ChatOffline.reopen(opened = false, loading = true, notFound = false))
        assertFalse(ChatOffline.reopen(opened = false, loading = false, notFound = true))
    }

    @Test
    fun a09Draft() {
        assertNull(ChatOffline.draftToStore("   "))
        assertEquals("Offline geschrieben", ChatOffline.draftToStore("Offline geschrieben"))
        assertEquals(20_000, ChatOffline.draftToStore("x".repeat(30_000))!!.length)
    }

    @Test
    fun a09DraftLivesWithChatEntriesAndIsClearedOnSignOut() {
        val sec = SecureStore(MemorySecretStore())
        val scope = SignOutChat.scope("https://a.example.org", "u:mia")!!
        val store = io.github.yggdrasil_sh.saga.chat.ChatStore(sec) { scope }
        store.saveDraft(8, "Hallo")
        assertEquals("Hallo", store.draft(8))
        assertEquals(1, sec.clearChat(scope))
        assertNull(store.draft(8))
    }

    // ------------------------------------------------------------------ A10

    @Test
    fun a10Scope() {
        assertNull(SignOutChat.scope("", "u:mia"))
        assertNull(SignOutChat.scope("https://a.example.org", ""))
        assertEquals("https://a.example.org|u:mia", SignOutChat.scope("https://a.example.org", "u:mia"))
        assertTrue(SignOutChat.sameAccount("https://a.example.org", "u:mia", "https://a.example.org", "u:mia"))
        assertFalse(SignOutChat.sameAccount("https://a.example.org", "u:mia", "https://b.example.org", "u:mia"))
        assertFalse(SignOutChat.sameAccount("", "", "", ""))
    }

    @Test
    fun a10ClearChatIgnoresEmptyScope() {
        val sec = SecureStore(MemorySecretStore())
        sec.put("chat_keys:|", "x")
        assertEquals(0, sec.clearChat("|"))
        assertEquals(0, sec.clearChat(""))
    }

    // ------------------------------------------------------------------ A14 / A18

    @Test
    fun a14PickerDescriptionFollowsValue() {
        assertEquals("Eigenschaft: Status", pickerDescription("Eigenschaft", "Status"))
        assertEquals("In progress", pickerDescription("", "In progress"))
        assertEquals("Wert", pickerDescription("Wert", ""))
    }

    @Test
    fun a18FontShrinksUntilLongestWordFits() {
        // Breite je Zeichen ~ 0,6 * sp; Spalte 60 px: "Stummschalten" (13 Zeichen) passt erst bei <= 7,6 sp
        val fits = { sp: Float -> CallLabels.words("Stummschalten").all { it.length * 0.6f * sp <= 60f } }
        assertEquals(7.5f, CallLabels.fitFont(11f, 6f, fits), 0.001f)
        assertEquals(11f, CallLabels.fitFont(11f, 8f) { true }, 0.001f)
        assertEquals(8f, CallLabels.fitFont(11f, 8f) { false }, 0.001f)
        assertEquals(listOf("Unmute", "microphone"), CallLabels.words("Unmute microphone"))
        assertEquals(CallLabels.Fit(7.5f, false), CallLabels.fit(11f, 6f, fits))
        assertEquals(CallLabels.Fit(11f, true), CallLabels.fit(11f, 9f, fits))      // passt nie: Silbentrennung
    }

    @Test
    fun v1FlatOnlyForLowLandscape() {
        assertTrue(CallLayout.flat(914, 411))       // Telefon quer (420 dpi)
        assertTrue(CallLayout.flat(686, 308))       // Telefon quer, grosse Anzeige (560 dpi)
        assertFalse(CallLayout.flat(411, 914))      // hoch
        assertFalse(CallLayout.flat(1280, 800))     // Tablet quer: normales Layout
        assertFalse(CallLayout.flat(0, 0))
    }
}
