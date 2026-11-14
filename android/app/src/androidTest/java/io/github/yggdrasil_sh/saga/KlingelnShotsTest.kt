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
 * 2.5 (L7) "Erneut klingeln": Bildschirmfotos der Teilnehmerliste eines Gruppenanrufs mit Abschnitt "Nicht dabei"
 * (nicht angenommen, hat abgelehnt, klingelt, App zu alt) - hell/dunkel, grosse Schrift auf schmaler Breite, nach einem
 * Klick. Nur Beispieldaten, keine Medien, kein Server. Ablage: getExternalFilesDir()/shots/klingeln-*.png.
 */
@RunWith(AndroidJUnit4::class)
class KlingelnShotsTest {
    private fun people() = HornUi(people = listOf(
        HornPerson("d1", "Anna Beispiel", "Pixel 9", me = true, host = true),
        HornPerson("d2", "Bert Beispiel", "iPhone", speaking = true),
    ))

    private fun invites(clicked: Boolean): Invites.Snapshot {
        val now = System.currentTimeMillis() / 1000.0
        val list = listOf(
            CallInvite("cora", "Cora Beispiel", state = "missed", since = now - 120),
            CallInvite("dora", "Dora Beispiel", state = "declined", since = now - 60),
            CallInvite("emil", "Emil Beispiel", state = "ringing", since = now - 5),
            CallInvite("frida", "Frida Beispiel", state = "old", since = now - 300),
        )
        return Invites.Snapshot(
            list, ringS = 45, host = true,
            pend = if (clicked) mapOf("cora" to System.currentTimeMillis() + 60_000) else emptyMap(),
            note = if (clicked) UiText.Res(R.string.inv_calling_one, "Cora Beispiel") else null,
        )
    }

    @Composable
    private fun Panel(clicked: Boolean) {
        val sc = MaterialTheme.colorScheme
        Surface(color = sc.surface) {
            Surface(shape = RoundedCornerShape(SagaRadius.xl), color = sc.surfaceContainerHigh, modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.horn_people, 2), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    HornPeopleList(people(), {}, {}, inv = invites(clicked), onRing = {})
                }
            }
        }
    }

    private fun shot(name: String, dark: Boolean, lang: String = "de", font: Float = 1f, clicked: Boolean = false) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)); fontScale = font }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    val d = LocalDensity.current
                    CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg, LocalDensity provides Density(d.density, font)) {
                        SagaTheme(dark = dark) { Panel(clicked) }
                    }
                }
            }
            Thread.sleep(1500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            sc.onActivity { a ->
                val v = a.window.decorView.findViewById<android.view.View>(android.R.id.content)
                val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(b))
                File(dir, "klingeln-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertTrue(File(dir, "klingeln-$name.png").length() > 1000)
    }

    @Test
    fun lightDark() {
        shot("hell", false)
        shot("dunkel", true)
    }

    @Test
    fun largeFontAndClick() {
        shot("schrift160-hell", false, font = 1.6f)
        shot("geklingelt-dunkel", true, clicked = true)
    }

    @Test
    fun languages() {
        shot("en-hell", false, lang = "en")
        shot("th-dunkel", true, lang = "th")
    }
}
