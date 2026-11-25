package io.github.yggdrasil_sh.saga

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.CallDetailsSheet
import io.github.yggdrasil_sh.saga.call.CallPeer
import io.github.yggdrasil_sh.saga.call.CallPhase
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.CallUiState
import io.github.yggdrasil_sh.saga.call.HornPeopleDialog
import io.github.yggdrasil_sh.saga.call.RoomUi
import io.github.yggdrasil_sh.saga.call.hornPreviewUi
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Bildschirmfotos der Verbindungsdetails eines Anrufs ueber den Knoten und der Teilnehmerliste mit erfundenen
 * Beispieldaten - kein Server, keine Medien. 2.5: mit Marke/Hinweis "experimentell"; seit 2.6 (2263-7) ohne - gleiche
 * Dateinamen fuer den Vorher/Nachher-Vergleich. Fenster samt Blatt/Dialog per
 * UiAutomation.takeScreenshot. Ablage: getExternalFilesDir()/shots/hornexp-*.png
 */
@RunWith(AndroidJUnit4::class)
class HornExpShotsTest {
    private fun state() = CallUiState(
        phase = CallPhase.CONNECTED, id = "a".repeat(32), chatId = 1, caller = true, video = true, peer = CallPeer(name = "Projekt Nordlicht"),
        verified = true, quality = "good", audioIn = "ok", room = RoomUi(kind = CallRoom.KIND_GROUP, mesh = true, horn = hornPreviewUi()),
    )

    private fun shot(name: String, dark: Boolean = false, lang: String = "de", body: @Composable () -> Unit) {
        val ins = InstrumentationRegistry.getInstrumentation()
        val dir = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg, LocalResources provides lc.resources) {
                        SagaTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { body() } }
                    }
                }
            }
            Thread.sleep(1800)
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            File(dir, "hornexp-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(File(dir, "hornexp-$name.png").length() > 1000)
    }

    @Test
    fun details() {
        shot("details-hell") { CallDetailsSheet(state(), hasTurn = true) {} }
        shot("details-dunkel", dark = true) { CallDetailsSheet(state(), hasTurn = true) {} }
        shot("details-en", lang = "en") { CallDetailsSheet(state(), hasTurn = true) {} }
    }

    @Test
    fun people() {
        shot("teilnehmende-hell") { HornPeopleDialog(hornPreviewUi(), onMute = {}, onRemove = {}) {} }
        shot("teilnehmende-th", dark = true, lang = "th") { HornPeopleDialog(hornPreviewUi(), onMute = {}, onRemove = {}) {} }
    }
}
