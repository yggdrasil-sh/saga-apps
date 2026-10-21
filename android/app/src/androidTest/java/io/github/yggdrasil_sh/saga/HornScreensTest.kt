package io.github.yggdrasil_sh.saga

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.HornPreviewContent
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Gjallarhorn P12 (Android): Bildschirmfotos der Gruppenanruf-Oberflaeche ueber die Debug-Vorschau (keine Medien, keine
 * Kamera, kein Mikrofon) - Kacheln, Sprecheransicht, geteilter Bildschirm, Teilnehmerliste mit Moderation, Runensiegel,
 * hell/dunkel und in allen fuenf Sprachen. Ablage: getExternalFilesDir()/shots/horn-*.png.
 */
@RunWith(AndroidJUnit4::class)
class HornScreensTest {
    private fun shot(name: String, dark: Boolean, lang: String = "en", view: String = "grid", panel: String = "", screen: Boolean = false) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg) {
                        SagaTheme(dark = dark) { HornPreviewContent(view, panel, screen) }
                    }
                }
            }
            Thread.sleep(1200)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            sc.onActivity { a ->
                val v = a.window.decorView.findViewById<android.view.View>(android.R.id.content)
                val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(b))
                File(dir, "horn-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertTrue(File(dir, "horn-$name.png").length() > 1000)
    }

    @Test
    fun gridLightDark() {
        shot("kacheln-hell", false)
        shot("kacheln-dunkel", true)
    }

    @Test
    fun speakerAndScreen() {
        shot("sprecher-hell", false, view = "speaker")
        shot("bildschirm-dunkel", true, screen = true)
    }

    @Test
    fun peopleAndRunes() {
        shot("teilnehmende-hell", false, panel = "people")
        shot("teilnehmende-dunkel", true, panel = "people")
        shot("runen-hell", false, panel = "runes")
        shot("runen-dunkel", true, panel = "runes")
    }

    @Test
    fun languages() {
        for (l in listOf("de", "es", "ru", "th")) shot("teilnehmende-$l", l == "ru", lang = l, panel = "people")
        shot("runen-de", true, lang = "de", panel = "runes")
    }
}
