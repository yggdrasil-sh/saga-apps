package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatInfoLogic
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.HeadLine
import io.github.yggdrasil_sh.saga.ui.common.FullScreenDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R5-Nachbesserung (Nachher-Bilder): Chat-Oberflaeche */
class DesignR5FixChatTest {
    private val src = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        .resolve("io/github/yggdrasil_sh/saga")
    private fun read(rel: String) = src.resolve(rel).readText()

    @Test
    fun fullScreenDialogBars() {
        assertTrue(FullScreenDialog.lightIcons(0.05f))      // dunkler Hintergrund -> helle Symbole
        assertFalse(FullScreenDialog.lightIcons(0.95f))
        assertFalse(FullScreenDialog.properties.decorFitsSystemWindows)
        assertFalse(FullScreenDialog.properties.usePlatformDefaultWidth)
        assertTrue(read("ui/chat/NewChatUi.kt").contains("FullScreenDialogBars()"))
    }

    @Test
    fun chatInfoMuteSubtitleAndBars() {
        val c = ChatSummary(1)
        assertEquals(ChatInfoLogic.Mute.ALL, ChatInfoLogic.muteState(c, 100.0, true))
        assertEquals(ChatInfoLogic.Mute.MENTIONS, ChatInfoLogic.muteState(c.copy(notify = "mentions"), 100.0, true))
        assertEquals(ChatInfoLogic.Mute.OFF, ChatInfoLogic.muteState(c.copy(notify = "none"), 100.0, true))
        assertEquals(ChatInfoLogic.Mute.UNTIL, ChatInfoLogic.muteState(c.copy(mutedUntil = 5000.0), 100.0, true))
        assertEquals(ChatInfoLogic.Mute.ALL, ChatInfoLogic.muteState(c.copy(mutedUntil = 50.0), 100.0, true))   // abgelaufen
        assertEquals(ChatInfoLogic.Mute.ALWAYS, ChatInfoLogic.muteState(c.copy(muted = true), 100.0, false))
        assertEquals(640, ChatInfoLogic.MAX_WIDTH_DP)
        val ui = read("ui/chat/ChatR5Ui.kt")
        assertFalse(ui.contains("760.dp"))
        assertTrue(ui.contains("infoMuteLabel(c, nowSec, true)"))
        assertTrue(read("ui/chat/GroupInfoUi.kt").contains("FullScreenDialogBars()"))
    }

    @Test
    fun attachTilesNoHyphens() {
        val ui = read("ui/chat/ChatR5Ui.kt")
        val sheet = ui.substring(ui.indexOf("fun AttachSheet("), ui.indexOf("private fun attachTile("))
        assertTrue(sheet.contains("Hyphens.None") && sheet.contains("maxLines = 2"))
        assertTrue(ui.contains("VIDEO_CAMERA -> SagaIcons.video to stringResource(R.string.r5f_att_video)"))
    }

    @Test
    fun groupHeadLineShort() {
        val e2e = "Ende-zu-Ende-verschlüsselt"
        assertEquals("5 Mitglieder", HeadLine.shown("5 Mitglieder", "", e2e))
        assertEquals("5 Mitglieder · $e2e", HeadLine.full("5 Mitglieder", "", e2e))
        assertEquals("online · auf b.example", HeadLine.shown("online", "auf b.example", e2e))
        assertEquals(e2e, HeadLine.shown(null, "", e2e))                    // ohne Stand/Zahl wie bisher
        assertEquals("auf b.example · $e2e", HeadLine.shown(null, "auf b.example", e2e))
    }

    @Test
    fun newChatSameAvatarSize() {
        val ui = read("ui/chat/NewChatUi.kt")
        assertFalse(Regex("Avatar\\([^)]*size = \\d").containsMatchIn(ui))           // keine feste Zahl mehr
        assertTrue(ui.contains("ContactsBlock(repo, headerInset = 16.dp, avatarSize = NewChatLogic.AVATAR_DP)"))
        assertTrue(ui.contains("avatarSize = NewChatLogic.AVATAR_DP,"))                 // Personen anderer Server
    }
}
