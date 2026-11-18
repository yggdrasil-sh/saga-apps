package io.github.yggdrasil_sh.saga

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.CallInvite
import io.github.yggdrasil_sh.saga.call.HornPeopleList
import io.github.yggdrasil_sh.saga.call.HornPerson
import io.github.yggdrasil_sh.saga.call.HornUi
import io.github.yggdrasil_sh.saga.call.Invites
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.UiText
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * 2.5 (Bugs-DB 2154): "Nicht dabei" bei vollem Gruppenanruf - Klingeln/Erneut klingeln gesperrt wie im Web, darueber der
 * Hinweis "Der Anruf ist voll – erst wenn jemand auflegt …"; hell/dunkel. Nur Beispieldaten, kein Server.
 * Ablage: getExternalFilesDir()/shots/voll-*.png.
 */
@RunWith(AndroidJUnit4::class)
class VollShotsTest {
    private fun people() = HornUi(people = listOf(
        HornPerson("d1", "Anna Beispiel", "Pixel 9", me = true, host = true),
        HornPerson("d2", "Bert Beispiel", "iPhone", speaking = true),
        HornPerson("d3", "Bert Beispiel", "iPad (Teilen)"),
        HornPerson("d4", "Cora Beispiel", "Laptop"),
    ))

    private fun invites(): Invites.Snapshot {
        val now = System.currentTimeMillis() / 1000.0
        val list = listOf(
            CallInvite("dora", "Dora Beispiel", state = "missed", since = now - 120),
            CallInvite("emil", "Emil Beispiel", state = "declined", since = now - 60),
            CallInvite("frida", "Frida Beispiel", state = "none"),
        )
        return Invites.Snapshot(list, ringS = 45, host = true, full = true)
    }

    @Composable
    private fun Panel() {
        val sc = MaterialTheme.colorScheme
        Surface(color = sc.surface) {
            Surface(shape = RoundedCornerShape(SagaRadius.xl), color = sc.surfaceContainerHigh, modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.horn_people, 4), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    HornPeopleList(people(), {}, {}, inv = invites(), onRing = {})
                }
            }
        }
    }

    private fun shot(name: String, dark: Boolean, lang: String = "de") {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg) {
                        SagaTheme(dark = dark) { Panel() }
                    }
                }
            }
            Thread.sleep(1500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            sc.onActivity { a ->
                val v = a.window.decorView.findViewById<android.view.View>(android.R.id.content)
                val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(b))
                File(dir, "voll-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertTrue(File(dir, "voll-$name.png").length() > 1000)
    }

    @Test
    fun lightDark() {
        shot("nichtdabei-hell", false)
        shot("nichtdabei-dunkel", true)
        shot("nichtdabei-en-hell", false, lang = "en")
    }
}
