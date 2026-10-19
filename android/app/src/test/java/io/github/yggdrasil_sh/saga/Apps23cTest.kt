package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.BackupLimits
import io.github.yggdrasil_sh.saga.data.BackupLogic
import io.github.yggdrasil_sh.saga.data.BackupPage
import io.github.yggdrasil_sh.saga.data.BackupStatus
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.data.NcBlockStatus
import io.github.yggdrasil_sh.saga.data.NcItem
import io.github.yggdrasil_sh.saga.data.NcLogic
import io.github.yggdrasil_sh.saga.data.NcPageStatus
import io.github.yggdrasil_sh.saga.data.NcSettings
import io.github.yggdrasil_sh.saga.live.NotifItem
import io.github.yggdrasil_sh.saga.live.NotifLogic
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.ui.push.PushUi
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saga 2.3 Teil 3: Mein Backup, Nextcloud-Bloecke, Benachrichtigungen, Mitglied entfernen (reine Logik + Editor) */
@OptIn(ExperimentalCoroutinesApi::class)
class Apps23cTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    private val ncAnna = """{"id":"n1","type":"nextcloud","data":{"inst":"https://cloud.example.org","fileid":"4711","kind":"file",
        |"name":"Bericht &amp; Plan.pdf","mime":"application/pdf","size":52311,"mtime":1792400000,"author":"anna","public":false,
        |"fed":false,"sig":"abc"}}""".trimMargin()
    private val ncBob = """{"id":"n2","type":"nextcloud","data":{"inst":"https://bob.example.org","fileid":"12","kind":"image",
        |"name":"Foto.jpg","mime":"image/jpeg","author":"bob","public":true,"fed":false,"sig":"xyz"}}""".trimMargin()

    private fun doc(vararg blocks: String): JsonObject = Json.parseToJsonElement("""{"blocks":[${blocks.joinToString(",")}]}""").jsonObject

    // ---------------------------------------------------------------- Mein Backup

    @Test
    fun backupStatusParsesServerAnswer() {
        val s = json.decodeFromString(BackupStatus.serializer(), """{"allowed":true,"ready":false,"running":false,"last_ok":null,
            "last_attempt":1792400000.5,"next":1792486400,"target":{"kind":"nextcloud","address":"https://c.example.org","user":"anna",
            "folder":"Saga-Backup","flow":true,"allowed":true},"keys":[{"short":"age1abc…xyz","fp":"ab12"}],"pending_key":null,
            "schedule":"weekly","effective_schedule":"weekly","time":"02:30","weekday":6,"keep":7,
            "cats":{"pages":true,"comments_others":false},"exclude":[5,9],
            "runs":[{"started":1792400000,"finished":1792400010,"trigger":"manuell","name":null,"ok":false,"size":null,"step":"","error":"Ziel voll"}],
            "limits":{"max_mb":512,"min_h":1,"max_schedule":"weekly"},"flow":{"state":"","login":null,"message":""},"age_ok":true}""")
        assertTrue(s.allowed)
        assertEquals("nextcloud", s.target?.kind)
        assertEquals(6, s.weekday)
        assertEquals(listOf(5L, 9L), s.exclude)
        assertEquals(false, s.cats?.get("comments_others"))
        assertEquals("Ziel voll", BackupLogic.lastError(s))
        assertFalse(BackupLogic.scheduleAllowed("daily", s.limits))
        assertTrue(BackupLogic.scheduleAllowed("weekly", s.limits))
        assertFalse(BackupLogic.shouldPoll(s))
        assertTrue(BackupLogic.shouldPoll(s.copy(running = true)))
        assertTrue(BackupLogic.shouldPoll(s.copy(flow = s.flow.copy(state = "wait"))))
        assertNull(BackupLogic.lastError(s.copy(running = true)))
    }

    @Test
    fun olderServerWithoutCategoriesHidesContentSection() {
        val s = json.decodeFromString(BackupStatus.serializer(), """{"allowed":true,"keys":[],"runs":[]}""")
        assertNull(s.cats)
        assertNull(s.exclude)
        assertTrue(BackupLogic.missingTarget(s))
        assertTrue(BackupLogic.missingKey(s))
    }

    @Test
    fun backupInputsAndBodies() {
        assertTrue(BackupLogic.timeValid("03:00"))
        assertTrue(BackupLogic.timeValid("23:59"))
        assertFalse(BackupLogic.timeValid("24:00"))
        assertFalse(BackupLogic.timeValid("3:00"))
        assertTrue(BackupLogic.keepValid(1))
        assertTrue(BackupLogic.keepValid(60))
        assertFalse(BackupLogic.keepValid(0))
        assertFalse(BackupLogic.keepValid(61))
        assertFalse(BackupLogic.keepValid(null))
        assertTrue(BackupLogic.last6Valid(" ABC123 "))
        assertFalse(BackupLogic.last6Valid("ABC12"))
        val b = BackupLogic.settingsBody("weekly", " 02:30 ", 6, 9)
        assertEquals("""{"schedule":"weekly","time":"02:30","weekday":6,"keep":9}""", b.toString())
        val c = BackupLogic.contentBody(mapOf("pages" to true, "unbekannt" to true, "trash" to false), listOf(5, 5, 9))
        assertEquals("""{"cats":{"pages":true,"trash":false},"exclude":[5,9]}""", c.toString())
        assertEquals("0 B", BackupLogic.size(0))
        assertEquals("1.5 KB", BackupLogic.size(1536))
        assertEquals("?", BackupLogic.size(null))
        assertEquals(BackupLimits().maxSchedule, "daily")
    }

    @Test
    fun excludePickerIsATree() {
        val pages = listOf(BackupPage(3, "Kind", parent = 1), BackupPage(1, "Wurzel"), BackupPage(7, "Waise", parent = 99), BackupPage(4, "Enkel", parent = 3))
        val t = BackupLogic.pageTree(pages).map { it.first.id to it.second }
        assertEquals(listOf(1L to 0, 3L to 1, 4L to 2, 7L to 0), t)
    }

    // ---------------------------------------------------------------- Nextcloud-Bloecke

    @Test
    fun cardDataAndLinks() {
        val d = doc(ncAnna)["blocks"]!!.let { (it as JsonArray)[0].jsonObject["data"]!!.jsonObject }
        assertEquals("Bericht & Plan.pdf", NcLogic.name(d))
        assertEquals("file", NcLogic.kind(d))
        assertEquals(52311L, NcLogic.size(d))
        assertEquals("📕", NcLogic.icon("file", "application/pdf"))
        assertEquals("📁", NcLogic.icon("folder", null))
        assertEquals("🖼", NcLogic.icon("image", "image/png"))
        assertEquals("https://cloud.example.org/f/4711", NcLogic.openUrl(d, remotePage = false))
        assertNull(NcLogic.openUrl(d, remotePage = true))                       // Seite einer anderen Instanz: nie
        assertEquals("api/notes/812/nc/4711?a=anna&w=1280", NcLogic.proxyPath(812, d, width = 1280))
        assertEquals("api/notes/-5/nc/4711?a=anna&w=2000", NcLogic.proxyPath(-5, d, width = 5000))
        assertEquals("api/notes/812/nc/4711?a=anna&dl=1", NcLogic.proxyPath(812, d, download = true))
        // gefaehrliche Werte: keine Links
        val bad = buildJsonObject { put("inst", "javascript:alert(1)"); put("fileid", "../x") }
        assertNull(NcLogic.openUrl(bad, false))
        assertNull(NcLogic.proxyPath(1, bad, width = 100))
    }

    @Test
    fun statusMatchesFileAndAuthor() {
        val d = buildJsonObject { put("fileid", "4711"); put("author", "anna") }
        val st = NcPageStatus(true, listOf(NcBlockStatus("4711", "bob", ok = true), NcBlockStatus("4711", "anna", ok = true, name = "neu.pdf")))
        assertEquals("neu.pdf", NcLogic.statusFor(st, d, remotePage = false)?.name)
        // fremde Instanz: author ist ein Platzhalter - nur die Datei zaehlt
        assertEquals("bob", NcLogic.statusFor(st, d, remotePage = true)?.author)
        assertNull(NcLogic.statusFor(NcPageStatus(true, emptyList()), d, false))
    }

    @Test
    fun newBlockFromOwnNextcloud() {
        val it = NcItem("99", "Plan.png", "/Bilder/Plan.png", false, "image/png", 1234, 1792400000.0, true, "image", "https://cloud.example.org")
        val b = NcLogic.newBlockData(it, "anna")
        assertEquals("https://cloud.example.org", NcLogic.str(b, "inst"))
        assertEquals("99", NcLogic.str(b, "fileid"))
        assertEquals("image", NcLogic.str(b, "kind"))
        assertEquals("anna", NcLogic.str(b, "author"))
        assertEquals(JsonPrimitive(false), b["public"])
        assertEquals(JsonPrimitive(false), b["fed"])
        assertNull(b["sig"])                                                      // setzt nur der Server
        assertNull(b["path"])                                                     // keine Pfade im Block
    }

    @Test
    fun foreignBlocksAreDetectedAndStrippedForCopies() {
        val nested = """{"id":"t1","type":"toggle","data":{"title":"x","blocks":[$ncBob]}}"""
        val d = doc(ncAnna, nested, """{"id":"p","type":"paragraph","data":{"text":"Hallo"}}""")
        assertEquals(2, NcLogic.blocks(d).size)
        val base = NcLogic.keys(doc(ncAnna))
        assertEquals(setOf("bob|https://bob.example.org|12"), NcLogic.newKeys(d, base))
        assertEquals(setOf("bob|https://bob.example.org|12"), NcLogic.foreignNew(d, base, "anna"))
        val (copy, n) = NcLogic.stripForeign(d, "anna")
        assertEquals(1, n)
        assertEquals(1, NcLogic.blocks(copy).size)
        assertEquals("anna", NcLogic.str(NcLogic.blocks(copy)[0], "author"))
        assertTrue(copy.toString().contains("\"Hallo\""))
        // eigener Block bleibt bit-genau erhalten
        assertEquals(doc(ncAnna)["blocks"]!!.let { (it as JsonArray)[0] }, (copy["blocks"] as JsonArray)[0])
    }

    @Test
    fun rejectionIsNotAConflict() {
        val nk = setOf("bob|x|1")
        assertTrue(NcLogic.isRejection(403, """{"error":"Nextcloud-Dateien anderer Personen lassen sich nicht einfügen"}""", nk))
        assertTrue(NcLogic.isRejection(409, """{"error":"Nextcloud nicht verbunden (Einstellungen → Nextcloud)."}""", nk))
        assertTrue(NcLogic.isRejection(400, """{"error":"Diese Datei liegt nicht in deinem verbundenen Nextcloud."}""", nk))
        assertFalse(NcLogic.isRejection(409, """{"error":"conflict","rev":3,"by":"Anna"}""", nk))
        assertFalse(NcLogic.isRejection(403, """{"error":"x"}""", emptySet()))         // ohne neue Bloecke: wie bisher
        assertFalse(NcLogic.isRejection(500, """{"error":"x"}""", nk))
    }

    @Test
    fun ncSettingsParse() {
        val s = json.decodeFromString(NcSettings.serializer(), """{"enabled":true,"connected":false,"me":"anna","server":null,"user":null,
            "since":null,"used":null,"suggest":"https://cloud.example.org","flow":{"state":"wait","login":"https://cloud.example.org/login/v2/flow/abc","message":""}}""")
        assertEquals("anna", s.me)
        assertEquals("wait", s.flow.state)
        assertEquals("https://cloud.example.org", s.suggest)
    }

    // ---------------------------------------------------------------- Editor: Block bleibt unveraendert, Ablehnung verstaendlich

    private class NcServer(var body: String, var role: String? = null) : PageBackend {
        var rev = 1
        val saves = ArrayList<String>()
        val copies = ArrayList<String>()
        var reject: ApiException? = null
        private val drafts = MemoryDrafts()
        private val lock = Mutex()
        override val currentServer = "https://s.example.org"
        override val signedOut = false
        override fun draftsForCurrent(): Drafts = drafts
        override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = lock.withLock { block() }
        override suspend fun loadPage(id: Long): JsonObject = buildJsonObject {
            put("id", id); put("title", "T"); put("body", body); put("format", "blocks"); put("rev", rev)
            role?.let { r -> put("role", r); putJsonObject("owner") { put("name", "Anna"); put("username", "anna"); put("me", false) } }
        }
        override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
            reject?.let { throw it }
            this.body = body ?: this.body
            saves += this.body
            return ++this.rev
        }
        override suspend fun createPageWith(title: String, body: String): Long { copies += body; return 7 }
        override suspend fun setPinned(id: Long, pinned: Boolean) {}
        override suspend fun setLocked(id: Long, locked: Boolean) {}
        override fun updateTitleLocal(id: Long, title: String) {}
        override fun visited(id: Long) {}
        override fun friendly(e: Throwable): UiText = UiText.Raw(e.message ?: "?")
        override suspend fun ncMe(): String = "anna"
    }

    private fun TestScope.editor(srv: NcServer): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, srv, backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    private fun TestScope.settle() {
        advanceTimeBy(30_000)
        runCurrent()
    }

    @Test
    fun nextcloudBlockIsSavedUnchanged() = runTest {
        val srv = NcServer("""{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Hallo"}},$ncBob]}""", role = "edit")
        val ed = editor(srv)
        ed.load()
        ed.setString(listOf("blocks", 0, "data", "text"), "Hallo Welt")
        settle()
        assertEquals(1, srv.saves.size)
        val saved = BlockDoc.blocks(BlockDoc.parse(srv.saves[0], "blocks").doc)
        assertEquals(Json.parseToJsonElement(ncBob), saved[1])                    // fremder Block bit-genau zurueck
        assertNull(ed.error)
    }

    @Test
    fun rejectedForeignBlockShowsClearMessageNotLostAccess() = runTest {
        val srv = NcServer("""{"blocks":[{"id":"p1","type":"paragraph","data":{"text":"Hallo"}}]}""", role = "edit")
        val ed = editor(srv)
        ed.load()
        val msg = "Nextcloud-Dateien anderer Personen lassen sich nicht einfügen oder ändern – nur Dateien aus deinem eigenen Nextcloud."
        srv.reject = ApiException(403, UiText.Raw(msg), """{"error":"$msg"}""")
        ed.insertBlockAfter(listOf("blocks", 0), Json.parseToJsonElement(ncBob).jsonObject, focus = false)
        settle()
        assertNull(ed.lost)                                                       // kein "Zugriff verloren"
        assertFalse(ed.conflict)
        val e = ed.error
        assertNotNull(e)
        assertTrue(e is UiText.Res && e.id == R.string.nc_save_rejected)
        assertTrue(ed.dirty)                                                      // Eingabe bleibt erhalten
    }

    @Test
    fun copyOfPageLeavesForeignNextcloudFilesOut() = runTest {
        val srv = NcServer("""{"blocks":[$ncAnna,$ncBob]}""")
        val ed = editor(srv)
        ed.load()
        val id = ed.saveCopyAndLoadServer("Kopie")
        settle()
        assertEquals(7L, id)
        val blocks = BlockDoc.blocks(BlockDoc.parse(srv.copies[0], "blocks").doc)
        assertEquals(listOf("anna"), blocks.filter { BlockDoc.type(it) == "nextcloud" }.map { NcLogic.str(BlockDoc.data(it), "author") })
        val info = ed.info
        assertTrue(info is UiText.Res && info.id == R.string.nc_copy_dropped)
    }

    // ---------------------------------------------------------------- Benachrichtigungen, Push, Gruppen

    @Test
    fun backupNotificationsAreReadableAndTappable() {
        val user = NotifItem(1, kind = "userbackup.failed", title = "Mein Backup", text = "Ziel nicht erreichbar", url = "/?settings=backup", system = true)
        assertEquals(NotifLogic.Target.Settings("backup"), NotifLogic.target(user.url))
        assertTrue(NotifLogic.tappable(user))
        assertTrue(NotifLogic.isSystem(user))
        val admin = NotifItem(2, kind = "backup.failed", title = "Backups", text = "Backup fehlgeschlagen", url = "https://admin.example.org/backups", system = true)
        assertNull(NotifLogic.target(admin.url))                                // absolute Links nie als Ziel
        assertEquals("https://admin.example.org/backups", NotifLogic.adminLink(admin))
        assertTrue(NotifLogic.tappable(admin))
        assertNull(NotifLogic.adminLink(admin.copy(kind = "share.added")))       // nur die Admin-Meldung
        assertNull(NotifLogic.adminLink(admin.copy(url = "http://admin.example.org")))
        val noUrl = admin.copy(url = null)
        assertFalse(NotifLogic.tappable(noUrl))
        assertNull(NotifLogic.target("javascript:alert(1)"))
        // bisherige Ziele unveraendert
        assertEquals(NotifLogic.Target.Page(12, "b1"), NotifLogic.target("/notes#12:b1"))
        assertEquals(NotifLogic.Target.Chat(5), NotifLogic.target("/chats#c5"))
        val parsed = json.decodeFromString(NotifItem.serializer(), """{"id":3,"kind":"userbackup.failed","system":true,"access":true,"title":"Mein Backup","text":"x","url":"/?settings=backup"}""")
        assertTrue(parsed.system)
    }

    @Test
    fun adminPushCategoryOnlyWhenServerListsIt() {
        assertTrue(PushUi.showCategory("admin", listOf("share", "admin")))
        assertFalse(PushUi.showCategory("admin", listOf("share", "comment")))
        assertFalse(PushUi.showCategory("admin", null))
        assertTrue(PushUi.showCategory("share", null))                           // wie bisher
        assertTrue(PushUi.showCategory("share", listOf("share")))
        assertFalse(PushUi.showCategory("call", listOf("share")))
    }

    @Test
    fun adminMayRemoveOthersButNotSelf() {
        assertTrue(ChatLogic.canRemoveMember(ChatPerson(username = "bob")))
        assertTrue(ChatLogic.canRemoveMember(ChatPerson(username = "~x@b.example", remote = true)))
        assertFalse(ChatLogic.canRemoveMember(ChatPerson(username = "anna", me = true)))
        assertFalse(ChatLogic.canRemoveMember(ChatPerson(username = "")))
    }
}
