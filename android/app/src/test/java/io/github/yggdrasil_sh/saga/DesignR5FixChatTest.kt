package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.common.FullScreenDialog
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
}
