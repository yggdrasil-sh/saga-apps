package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallEntry
import io.github.yggdrasil_sh.saga.chat.ChatContent
import io.github.yggdrasil_sh.saga.chat.AttachLogic
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatInfoLogic
import io.github.yggdrasil_sh.saga.chat.ChatMenuLogic
import io.github.yggdrasil_sh.saga.chat.ChatMenuLogic.Item
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.ChatSys
import io.github.yggdrasil_sh.saga.chat.ListPreview
import io.github.yggdrasil_sh.saga.chat.ListPreviewLogic
import io.github.yggdrasil_sh.saga.chat.NewChatLogic
import io.github.yggdrasil_sh.saga.chat.NewChatLogic.Empty
import io.github.yggdrasil_sh.saga.chat.NewChatLogic.Mode
import io.github.yggdrasil_sh.saga.chat.PeopleGroup
import io.github.yggdrasil_sh.saga.chat.PendingFile
import io.github.yggdrasil_sh.saga.chat.PendingLogic
import io.github.yggdrasil_sh.saga.ui.common.NotifPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design-Runde 5 (r5-chat): Chat-Oberflaeche - Neuer Chat, Vorschau in der Liste, Chat-Info, Menues */
class DesignR5ChatTest {

    // ---------------------------------------------------------------- CH-06 Neuer Chat

    @Test
    fun newChatEmptyStates() {
        assertEquals(Empty.NONE, NewChatLogic.empty("", 3, null, 0, false))
        assertEquals(Empty.NONE, NewChatLogic.empty("  ", 1, "  ", 0, false))
        // aeltere Server: Regel "ab 3 Zeichen" nur als Leerzustand
        assertEquals(Empty.TOO_SHORT, NewChatLogic.empty("an", 3, "an", 0, false))
        assertEquals(Empty.NONE, NewChatLogic.empty("an", 1, null, 0, false))          // Antwort steht noch aus
        assertEquals(Empty.NO_HITS, NewChatLogic.empty("an", 1, "an", 0, false))
        assertEquals(Empty.NONE, NewChatLogic.empty("anna", 1, "ann", 0, false))       // Antwort zu altem Stand
        assertEquals(Empty.NONE, NewChatLogic.empty("a@b.example", 1, "a@b.example", 0, true))  // name@server-Hinweis statt "Niemand"
        assertEquals(Empty.NONE, NewChatLogic.empty("anna", 1, "anna", 2, false))
    }

    @Test
    fun newChatFixedEntriesAndCreate() {
        assertTrue(NewChatLogic.showFixed(Mode.PERSON, ""))
        assertFalse(NewChatLogic.showFixed(Mode.PERSON, "a"))
        assertFalse(NewChatLogic.showFixed(Mode.GROUP, ""))
        assertFalse(NewChatLogic.showFixed(Mode.TEAM, ""))
        assertFalse(NewChatLogic.canCreate(Mode.GROUP, 0, 0))
        assertTrue(NewChatLogic.canCreate(Mode.GROUP, 0, 1))      // auch nur Personen anderer Server
        assertTrue(NewChatLogic.canCreate(Mode.GROUP, 2, 0))
        assertFalse(NewChatLogic.canCreate(Mode.PERSON, 2, 0))
    }

    @Test
    fun newChatTeams() {
        val chats = listOf(
            ChatSummary(1, kind = "group", group = "Vertrieb"), ChatSummary(2, kind = "group", group = "bau"),
            ChatSummary(3, kind = "dm"), ChatSummary(4, kind = "group", group = ""),
        )
        assertEquals(listOf("bau", "Vertrieb"), NewChatLogic.teams(chats, emptyList(), ""))
        assertEquals(listOf("Einkauf", "Vertrieb"), NewChatLogic.teams(chats, listOf(PeopleGroup("Vertrieb"), PeopleGroup("Einkauf")), "e"))
        assertEquals(emptyList<String>(), NewChatLogic.teams(emptyList(), emptyList(), "x"))
    }

    // ---------------------------------------------------------------- CH-07 Vorschau in der Chat-Liste

    private val labels = ListPreviewLogic.Labels(
        you = "Du", message = "Nachricht", photo = "Foto", video = "Video", voice = "Sprachnachricht", voiceDur = "Sprachnachricht %1\$s",
        file = "Datei", call = "Anruf", videoCall = "Videoanruf", missed = "Verpasster Anruf", missedVideo = "Verpasster Videoanruf", page = "Seite",
    )

    private fun msg(seq: Long, text: String = "", mine: Boolean = false, files: List<ChatFile> = emptyList(), who: String = "Bruno",
                    call: CallEntry? = null, sys: ChatSys? = null, exp: Double? = null, content: Boolean = true) = ChatMessage(
        seq = seq, id = "m$seq", sender = ChatPerson(username = who.lowercase(), name = who), senderDevice = "d", ts = seq * 1000,
        expiresAt = exp, replyTo = null, content = if (content && call == null && sys == null) ChatContent(text = text, files = files) else null,
        verified = true, mine = mine, call = call, sys = sys,
    )

    private fun of(ms: List<ChatMessage>, group: Boolean = false, upTo: Long = ms.maxOf { it.seq }) =
        ListPreviewLogic.of(ms, "me", upTo, group, { it.sender?.name.orEmpty() }, { if (it == 7L) "Plan" else null }, "Seite")

    @Test
    fun previewTextAndPrefixes() {
        val dm = of(listOf(msg(1, "Hallo"), msg(2, "Wie  geht's\ndir?")))!!
        assertEquals("Wie geht's dir?", ListPreviewLogic.render(dm, ListPreviewLogic.Mode.TEXT, labels))
        val mine = of(listOf(msg(3, "Bis morgen", mine = true)))!!
        assertEquals("Du: Bis morgen", ListPreviewLogic.render(mine, ListPreviewLogic.Mode.TEXT, labels))
        val grp = of(listOf(msg(4, "Siehe [[note:7]] und [[note:9]]", who = "Clara")), group = true)!!
        assertEquals("Clara: Siehe Plan und Seite", ListPreviewLogic.render(grp, ListPreviewLogic.Mode.TEXT, labels))
        assertEquals("Clara: Nachricht", ListPreviewLogic.render(grp, ListPreviewLogic.Mode.KIND, labels))
        val long = of(listOf(msg(5, "x".repeat(400))))!!
        assertEquals(ListPreviewLogic.TEXT_MAX, long.text.length)
    }

    @Test
    fun previewKinds() {
        val photo = of(listOf(msg(1, "Urlaub", files = listOf(ChatFile(id = "f", m = "image/jpeg")))))!!
        assertEquals("Foto · Urlaub", ListPreviewLogic.render(photo, ListPreviewLogic.Mode.TEXT, labels))
        assertEquals("Foto", ListPreviewLogic.render(photo, ListPreviewLogic.Mode.KIND, labels))
        val video = of(listOf(msg(1, files = listOf(ChatFile(id = "f", m = "video/mp4")))))!!
        assertEquals("Video", ListPreviewLogic.render(video, ListPreviewLogic.Mode.TEXT, labels))
        val voice = of(listOf(msg(1, mine = true, files = listOf(ChatFile(id = "f", m = "audio/ogg", voice = true, dur = 12.4)))))!!
        assertEquals("Du: Sprachnachricht 0:12", ListPreviewLogic.render(voice, ListPreviewLogic.Mode.TEXT, labels))
        val file = of(listOf(msg(1, files = listOf(ChatFile(id = "f", n = "geheim.pdf", m = "application/pdf")))))!!
        assertEquals("Datei", ListPreviewLogic.render(file, ListPreviewLogic.Mode.TEXT, labels))
        val missed = of(listOf(msg(1, call = CallEntry(status = "missed", from = "bruno", to = "me"))))!!
        assertEquals("Verpasster Anruf", ListPreviewLogic.render(missed, ListPreviewLogic.Mode.TEXT, labels))
        val out = of(listOf(msg(1, call = CallEntry(status = "ended", media = "video", from = "me", to = "bruno", dur = 30))))!!
        assertEquals("Videoanruf", ListPreviewLogic.render(out, ListPreviewLogic.Mode.KIND, labels))
        assertEquals("1:05", ListPreviewLogic.dur(65))
    }

    @Test
    fun previewSkipsAndValidity() {
        // Systemzeile und unlesbare Nachricht ueberspringen; nur Systemzeilen -> nichts
        val p = of(listOf(msg(1, "alt"), msg(2, content = false), msg(3, sys = ChatSys(type = "ai"))))!!
        assertEquals(1L, p.seq)
        assertEquals(3L, p.upTo)
        assertNull(of(listOf(msg(1, sys = ChatSys(type = "ai")))))
        val c = ChatSummary(1, lastSeq = 3)
        assertTrue(ListPreviewLogic.valid(p, c, 0.0))
        assertFalse(ListPreviewLogic.valid(p, c.copy(lastSeq = 4), 0.0))            // neuere Nachricht unbekannt -> Rueckfall
        assertFalse(ListPreviewLogic.valid(p, c.copy(clearedSeq = 1), 0.0))         // Verlauf geloescht
        assertFalse(ListPreviewLogic.valid(p.copy(exp = 10.0), c, 11.0))            // abgelaufen
        assertFalse(ListPreviewLogic.valid(null, c, 0.0))
        assertFalse(ListPreviewLogic.valid(p, c.copy(lastSeq = 0), 0.0))
    }

    @Test
    fun previewStoreKeepsNewest() {
        var m: Map<Long, ListPreview> = emptyMap()
        for (i in 1..(ListPreviewLogic.KEEP_MAX + 5)) m = ListPreviewLogic.put(m, i.toLong(), ListPreview(seq = 1))
        assertEquals(ListPreviewLogic.KEEP_MAX, m.size)
        assertFalse(1L in m)
        m = ListPreviewLogic.put(m, 10L, null)
        assertFalse(10L in m)
        assertEquals(ListPreviewLogic.Mode.KIND, ListPreviewLogic.Mode.of("kind"))
        assertEquals(ListPreviewLogic.Mode.TEXT, ListPreviewLogic.Mode.of(null))
    }

    // ---------------------------------------------------------------- CH-10 Menue und Chat-Info

    @Test
    fun chatMenuAtMostSix() {
        val dm = ChatMenuLogic.items(ChatMenuLogic.Flags(canArchive = true))
        assertEquals(listOf(Item.INFO, Item.SELECT, Item.MUTE, Item.ARCHIVE, Item.DELETE), dm)
        assertTrue(ChatMenuLogic.dividerBefore(dm, 4))
        assertFalse(ChatMenuLogic.dividerBefore(dm, 3))
        // freie Gruppe mit KI und Archiv: 7 -> Archivieren weicht (steht in der Gruppeninfo)
        val full = ChatMenuLogic.items(ChatMenuLogic.Flags(free = true, ai = true, canArchive = true))
        assertEquals(listOf(Item.INFO, Item.SELECT, Item.MUTE, Item.AI, Item.LEAVE, Item.DELETE), full)
        assertTrue(full.size <= ChatMenuLogic.MAX)
        assertTrue(ChatMenuLogic.dividerBefore(full, 4))
        assertFalse(ChatMenuLogic.dividerBefore(full, 5))      // nur ein Trenner vor dem destruktiven Block
        // aelterer Server ohne Archiv: kein destruktiver Eintrag, kein Trenner
        val old = ChatMenuLogic.items(ChatMenuLogic.Flags())
        assertEquals(listOf(Item.INFO, Item.SELECT, Item.MUTE), old)
        for (free in listOf(true, false)) for (ai in listOf(true, false)) for (a in listOf(true, false)) {
            val l = ChatMenuLogic.items(ChatMenuLogic.Flags(free, ai, a))
            assertTrue(l.size <= ChatMenuLogic.MAX)
            assertEquals(Item.INFO, l.first())
            assertEquals(l.sortedBy { it.destructive }, l)       // destruktive immer unten
        }
    }

    @Test
    fun chatInfoRows() {
        val f = ChatInfoLogic.Flags(dm = true, media = true, canArchive = true, free = false, canRename = true, block = true, report = true, hasOthers = true)
        val r = ChatInfoLogic.rows(f)
        assertEquals(
            listOf(ChatInfoLogic.Row.MEDIA, ChatInfoLogic.Row.MUTE, ChatInfoLogic.Row.RETENTION, ChatInfoLogic.Row.SAFETY, ChatInfoLogic.Row.ARCHIVE,
                ChatInfoLogic.Row.BLOCK, ChatInfoLogic.Row.REPORT), r,
        )
        assertEquals(listOf(ChatInfoLogic.Row.BLOCK, ChatInfoLogic.Row.REPORT), r.filter { ChatInfoLogic.danger(it) })
        // aelterer Server, freie Gruppe: Mitglieder, Umbenennen, Verlassen; ohne Medien/Sperren
        val g = ChatInfoLogic.rows(f.copy(dm = false, media = false, free = true, block = true, report = false))
        assertTrue(ChatInfoLogic.Row.MEMBERS in g && ChatInfoLogic.Row.RENAME in g && ChatInfoLogic.Row.LEAVE in g)
        assertFalse(ChatInfoLogic.Row.MEDIA in g || ChatInfoLogic.Row.BLOCK in g)
        assertFalse(ChatInfoLogic.Row.SAFETY in ChatInfoLogic.rows(f.copy(hasOthers = false)))
    }

    // ---------------------------------------------------------------- CH-11 Anhang-Menue, CH-12 Vorschaureihe

    @Test
    fun attachTiles() {
        assertEquals(
            listOf(AttachLogic.Tile.GALLERY, AttachLogic.Tile.CAMERA, AttachLogic.Tile.VIDEO_CAMERA, AttachLogic.Tile.FILE, AttachLogic.Tile.PAGE, AttachLogic.Tile.IMMICH),
            AttachLogic.tiles(camera = true, immich = true, clipImage = false),
        )
        assertEquals(listOf(AttachLogic.Tile.GALLERY, AttachLogic.Tile.FILE, AttachLogic.Tile.PAGE, AttachLogic.Tile.PASTE), AttachLogic.tiles(false, false, true))
    }

    @Test
    fun pendingRow() {
        val img = PendingFile("a.jpg", "image/jpeg", ByteArray(1))
        val svg = PendingFile("b.svg", "image/svg+xml", ByteArray(1))
        val pdf = PendingFile("c.pdf", "application/pdf", ByteArray(1))
        assertEquals(listOf(img), PendingLogic.images(listOf(img, svg, pdf)))
        assertEquals(listOf(svg, pdf), PendingLogic.others(listOf(img, svg, pdf)))
        assertTrue(PendingLogic.caption(listOf(img), 0))
        assertTrue(PendingLogic.caption(emptyList(), 1))
        assertFalse(PendingLogic.caption(listOf(pdf), 0))
        assertEquals(1, PendingLogic.sample(100, 100, 192))
        assertEquals(8, PendingLogic.sample(4000, 3000, 192))
        assertEquals(1, PendingLogic.sample(0, 0, 192))
    }

    // ---------------------------------------------------------------- CH-21 keine Emoji in Chat-Oberflaechen

    @Test
    fun chatUiWithoutEmoji() {
        val src = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }.resolve("io/github/yggdrasil_sh/saga/ui/chat")
        val emoji = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{23F0}-\\x{23FF}]")
        // Reaktionen bleiben Emoji (ChatX3Ui); Kommentare zaehlen nicht
        val hits = src.listFiles()!!.filter { it.extension == "kt" && it.name != "ChatX3Ui.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                val code = l.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) null
                else if (emoji.containsMatchIn(code.replace("✓", "").replace("✕", ""))) "${f.name}:${i + 1}" else null
            }
        }
        assertEquals(emptyList<String>(), hits)
    }

    // ---------------------------------------------------------------- CH-24 Mitteilungs-Frage, CH-26 Wischen, R1 Link

    @Test
    fun notifPromptMoment() {
        assertFalse(NotifPrompt.moment(opened = false, hasMessages = true))
        assertFalse(NotifPrompt.moment(opened = true, hasMessages = false))     // leerer Chat: erst nach dem ersten Senden
        assertTrue(NotifPrompt.moment(opened = true, hasMessages = true))
        val src = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }.resolve("io/github/yggdrasil_sh/saga/ui/chat")
        assertFalse(src.resolve("ChatsScreen.kt").readText().contains("NotificationPermissionPrompt("))   // nicht mehr beim Oeffnen der Liste
        assertTrue(src.resolve("Conversation.kt").readText().contains("NotificationPermissionPrompt("))
    }

}
