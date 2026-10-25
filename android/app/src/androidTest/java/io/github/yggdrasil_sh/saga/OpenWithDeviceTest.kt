package io.github.yggdrasil_sh.saga

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.chat.ChatFiles
import io.github.yggdrasil_sh.saga.ui.common.browserIntent
import io.github.yggdrasil_sh.saga.util.OpenWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Nur eigenes Wegwerf-AVD: gebaute Intents (kein Chooser bei VIEW, kein festes Paket) und was das System wirklich
 * startet (dumpsys activity: beim Oeffnen nie ChooserActivity, beim Teilen schon).
 */
@RunWith(AndroidJUnit4::class)
class OpenWithDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val app = ins.targetContext.applicationContext

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).use { it.readBytes().decodeToString() }

    /** Oberste Activity laut dumpsys (Zeile topResumedActivity bzw. mResumedActivity) */
    private fun top(): String {
        val d = shell("dumpsys activity activities")
        return d.lines().firstOrNull { it.contains("topResumedActivity") || it.contains("mResumedActivity") }?.trim() ?: ""
    }

    private fun settle(): String {
        var t = ""
        repeat(20) {
            Thread.sleep(250)
            t = top()
            if (!t.contains(ins.context.packageName + "/")) return t
        }
        return t
    }

    private fun back() {
        shell("input keyevent KEYCODE_BACK")
        Thread.sleep(500)
    }

    @Test
    fun browserIntentOhnePaket() {
        val i = browserIntent("https://example.org/a")
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull("keine feste Paketbindung", i.`package`)
        assertNull(i.component)
        android.util.Log.i("OpenWithDeviceTest", "browserIntent=$i")
    }

    @Test
    fun bildOeffnenOhneChooser() {
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", 0,
        )
        val f = ChatFiles.write(app.cacheDir, "probe.png", png)
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
        try {
            val i = OpenWith.intent(OpenWith.viewPlan(OpenWith.mime("image/png")), uri)
            assertEquals(Intent.ACTION_VIEW, i.action)
            assertEquals("image/png", i.type)
            assertEquals(uri, i.data)
            assertNull(i.`package`)
            assertNull(i.component)
            assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            android.util.Log.i("OpenWithDeviceTest", "view=$i")

            OpenWith.open(app, uri, "image/png", "probe.png")
            val opened = settle()
            android.util.Log.i("OpenWithDeviceTest", "nach Oeffnen: $opened")
            assertFalse("Oeffnen darf keinen Chooser zeigen: $opened", opened.contains("ChooserActivity"))
            back()

            OpenWith.share(app, uri, "image/png", "probe.png")
            val shared = settle()
            android.util.Log.i("OpenWithDeviceTest", "nach Teilen: $shared")
            assertTrue("Teilen zeigt die Auswahl: $shared", shared.contains("Chooser", ignoreCase = true))
            back()
        } finally {
            ChatFiles.remove(f)
        }
    }
}
