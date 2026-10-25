package io.github.yggdrasil_sh.saga

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.CallPeer
import io.github.yggdrasil_sh.saga.call.CallPhase
import io.github.yggdrasil_sh.saga.call.CallUiState
import io.github.yggdrasil_sh.saga.call.MeetCall
import io.github.yggdrasil_sh.saga.call.MeetDemo
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Meeting-Ansicht im echten Anrufpfad (MeetCall mit dem CallManager der App, ohne laufenden Anruf - daher ohne Bilder):
 * A1 - nach 4 s ohne Bedienung sind Kopf und Knoepfe weg, ein Tippen holt sie zurueck. Geprueft an den Pixeln des
 * unteren Bildrands (Knopfleiste hell, ausgeblendet schwarz); Bildschirmfotos meet-echt-*.png.
 */
@RunWith(AndroidJUnit4::class)
class MeetCallTest {
    private val ui: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @After
    fun unfreeze() {
        ui.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    /** Anteil nicht-schwarzer Pixel im unteren Streifen (Hoehe share) */
    private fun lit(b: Bitmap, share: Float): Float {
        val y0 = (b.height * (1f - share)).toInt()
        var n = 0
        var lit = 0
        for (y in y0 until b.height step 4) for (x in 0 until b.width step 4) {
            val p = b.getPixel(x, y)
            val l = ((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)
            if (l > 90) lit++
            n++
        }
        return lit.toFloat() / n
    }

    private fun save(b: Bitmap, name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "meet-echt-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun leistenBlendenAusUndKommenZurueck() {
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
        Thread.sleep(800)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SagaApplication
        val st = CallUiState(
            phase = CallPhase.CONNECTED, id = "demo", chatId = 1, caller = true, video = true, peer = CallPeer("anna", "Anna"),
            mic = true, cam = true, remoteVideo = true, connectedAt = System.currentTimeMillis(),
            meet = MeetDemo.devs("duo"), meetMe = "me",
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.setContent {
                    SagaTheme(dark = true) {
                        MeetCall(app.repo.calls, st, flat = false, onMinimize = {}, onCam = {}, onShare = {}, onMic = {}, withMic = { it() }, onPanel = {}, onPlayback = {})
                    }
                }
            }
            Thread.sleep(1500)
            val shown = ui.takeScreenshot()
            save(shown, "sichtbar")
            Thread.sleep(5000)                                       // > 4 s ohne Bedienung
            val hidden = ui.takeScreenshot()
            save(hidden, "ausgeblendet")
            ui.executeShellCommand("input tap ${hidden.width / 2} ${hidden.height / 2}").close()
            Thread.sleep(1200)
            val back = ui.takeScreenshot()
            save(back, "zurueck")
            val a = lit(shown, 0.12f)
            val b = lit(hidden, 0.12f)
            val c = lit(back, 0.12f)
            assertTrue("Knopfleiste sichtbar: $a", a > 0.15f)
            assertTrue("ausgeblendet: $b (vorher $a)", b < a / 3)
            assertTrue("nach Tippen zurueck: $c", c > 0.15f)
        }
    }
}
