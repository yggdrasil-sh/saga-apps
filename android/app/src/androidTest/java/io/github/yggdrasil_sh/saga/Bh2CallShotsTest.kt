package io.github.yggdrasil_sh.saga

import android.content.pm.ActivityInfo
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
import io.github.yggdrasil_sh.saga.call.CallFlatPreviewContent
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Bughunt 2 V1/A05/A18: Knoten-Anruf hoch und quer (Vorschau mit den Bausteinen des Anrufbildschirms). Quer muss die
 * Kachelflaeche den groessten Teil der Hoehe bekommen, die Knopfreihe flach bleiben. Ablage: getExternalFilesDir()/shots/bh2-*.png
 */
@RunWith(AndroidJUnit4::class)
class Bh2CallShotsTest {
    private fun shot(name: String, land: Boolean, lang: String = "de", dark: Boolean = true): Triple<Int, Int, Int> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        var stage = 0
        var controls = 0
        var total = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { it.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(2500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            sc.onActivity { a ->
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg) {
                        SagaTheme(dark = dark) { CallFlatPreviewContent(onStage = { stage = it }, onControls = { controls = it }) }
                    }
                }
            }
            Thread.sleep(1500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            sc.onActivity { a ->
                val v = a.window.decorView.findViewById<android.view.View>(android.R.id.content)
                total = v.height
                val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(b))
                File(dir, "bh2-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        android.util.Log.i("Bh2CallShots", "$name total=$total stage=$stage controls=$controls")
        return Triple(total, stage, controls)
    }

    @Test
    fun landscapeGivesTilesTheSpace() {
        val (total, stage, controls) = shot("quer-dunkel-de", land = true)
        val dp = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Kacheln quer zu klein: $stage von $total", stage >= total * 0.5)
        assertTrue("Knopfreihe quer zu hoch: ${controls / dp} dp", controls / dp <= 64)
        shot("quer-hell-en", land = true, lang = "en", dark = false)
    }

    @Test
    fun portraitUnchanged() {
        val (total, stage, _) = shot("hoch-dunkel-de", land = false)
        assertTrue(stage >= total * 0.4)
        shot("hoch-hell-ru", land = false, lang = "ru", dark = false)
    }
}
