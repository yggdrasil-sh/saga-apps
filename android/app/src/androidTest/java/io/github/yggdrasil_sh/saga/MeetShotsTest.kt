package io.github.yggdrasil_sh.saga

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.MeetDemoContent
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Meeting-Ansicht (MEETING-SPEC.md): Bildschirmfotos mit Demo-Teilnehmenden aus der Debug-Quelle (MeetDemo, keine
 * Medien, keine Kamera, kein Mikrofon) - Praesentation, Galerie, Sprecher, 1:1 jeweils hoch und quer, Leisten sichtbar
 * und ausgeblendet. Ablage: getExternalFilesDir()/shots/meet-*.png (ganzer Bildschirm ueber UiAutomation).
 */
@RunWith(AndroidJUnit4::class)
class MeetShotsTest {
    private val ui: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @After
    fun unfreeze() {
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
        ui.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    private fun shot(name: String, scenario: String, land: Boolean, hidden: Boolean = false) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        ui.setRotation(if (land) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
        Thread.sleep(1200)
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a -> a.setContent { SagaTheme(dark = true) { MeetDemoContent(scenario, hidden) } } }
            Thread.sleep(1800)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val b: Bitmap = ui.takeScreenshot()
            File(dir, "meet-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(File(dir, "meet-$name.png").length() > 1000)
    }

    @Test
    fun presentation() {
        shot("praesentation-hoch", "presentation", false)
        shot("praesentation-quer", "presentation", true)
        shot("praesentation-hoch-leer", "presentation", false, hidden = true)
        shot("praesentation-quer-leer", "presentation", true, hidden = true)
    }

    @Test
    fun gallery() {
        shot("galerie-hoch", "gallery", false)
        shot("galerie-quer", "gallery", true)
    }

    @Test
    fun speaker() {
        shot("sprecher-hoch", "speaker", false)
        shot("sprecher-quer", "speaker", true)
    }

    @Test
    fun duo() {
        shot("duo-hoch", "duo", false)
        shot("duo-quer", "duo", true, hidden = true)
    }
}
