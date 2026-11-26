package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.chat.BookContact
import io.github.yggdrasil_sh.saga.chat.ContactExportLogic
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.settings.ContactDetailScreen
import io.github.yggdrasil_sh.saga.ui.settings.ContactExportUi
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.ShotMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 2.6 (Bugs-DB 2027b, W2): "Zu Kontakten hinzufuegen" gegen einen echten Testserver - Bilder vom Kontakt-Detail (hell/dunkel)
 * und vom System-Dialog (Auswahl neu/vorhanden, Editor vorausgefuellt); danach im Wegwerf-Emulator sichern und den
 * Inhalt des System-Adressbuchs per Shell (content query) als Text ablegen. Nur mit Argumenten, sonst uebersprungen:
 *   -e server http://127.0.0.1:<port> -e token hkd_…   (Emulator: adb reverse auf den Testserver)
 * Ablage: getExternalFilesDir()/shots/k27b-*.png und k27b-data.txt
 */
@RunWith(AndroidJUnit4::class)
class Contacts2027bShotsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private val args get() = InstrumentationRegistry.getArguments()

    @Before fun setUp() {
        val server = args.getString("server")
        val token = args.getString("token")
        assumeTrue("ohne -e server/-e token", !server.isNullOrBlank() && !token.isNullOrBlank())
        runBlocking {
            app.repo.awaitReady()
            app.repo.secure.setCredentials(server!!, token!!, null)
            app.repo.settings.setServer(server)
            app.repo.settings.setAccount("Alex Beispiel")
            app.repo.needsLogin.value = false
            app.repo.checkVersion()
        }
        ShotMode.on = true
    }

    private fun sh(cmd: String): String =
        ins.uiAutomation.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText() }

    private fun snap(name: String) {
        Thread.sleep(600)
        val b: Bitmap = ins.uiAutomation.takeScreenshot()
        val f = File(dir, "k27b-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 1000)
    }

    /** Knoten mit passendem Text (bzw. content-desc) in der Bildschirm-Struktur antippen */
    private fun tap(re: Regex): Boolean {
        sh("uiautomator dump /sdcard/k27b-ui.xml")
        val xml = sh("cat /sdcard/k27b-ui.xml")
        val node = Regex("<node [^>]*>").findAll(xml).map { it.value }.firstOrNull { n ->
            val t = Regex("(?:text|content-desc)=\"([^\"]*)\"").findAll(n).map { it.groupValues[1] }
            t.any { re.containsMatchIn(it) }
        } ?: return false
        val m = Regex("bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"").find(node) ?: return false
        val (x1, y1, x2, y2) = m.destructured
        sh("input tap ${(x1.toInt() + x2.toInt()) / 2} ${(y1.toInt() + y2.toInt()) / 2}")
        return true
    }

    private fun anna(): BookContact = runBlocking { app.repo.chat.book.list("Anna").contacts.first { it.name == "Anna Berg" } }

    @Test fun shots() {
        val a = anna()
        val p = ContactExportLogic.prefill(a)
        assertTrue("Anna hat Uebertragbares", p != null)
        val log = StringBuilder()
        for (dark in listOf(false, true)) {
            ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
                sc.onActivity { act ->
                    act.enableEdgeToEdge()
                    act.setContent {
                        SagaTheme(dark = dark) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                ContactDetailScreen(app.repo, AppNavigator(listOf(Dest.Settings, Dest.Contacts)), Chrome(showMenu = true, onMenu = {}), a.id)
                            }
                        }
                    }
                }
                Thread.sleep(3500)
                val d = if (dark) "-dunkel" else "-hell"
                snap("1-detail$d")
                sh("input swipe 540 1700 540 700 400")
                Thread.sleep(1200)
                snap("2-detail-knopf$d")
                if (dark) return@use
                // Knopf antippen (wie die Person) -> System-Auswahl "neu oder vorhanden"
                log.append("tap Knopf: ").append(tap(Regex("^Add to Contacts$"))).append('\n')
                Thread.sleep(4000)
                snap("3-auswahl")
                log.append("tap neu: ").append(tap(Regex("(?i)create new contact|^new contact$|create contact"))).append('\n')
                Thread.sleep(4000)
                snap("4-editor")
                sh("input swipe 540 1700 540 700 400")
                Thread.sleep(1000)
                snap("5-editor-unten")
                log.append("tap sichern: ").append(tap(Regex("(?i)^save$"))).append('\n')
                Thread.sleep(4000)
                snap("6-nach-sichern")
                log.append("aktiv: ").append(sh("dumpsys activity activities").lineSequence().firstOrNull { it.contains("topResumedActivity") || it.contains("mResumedActivity") }).append('\n')
                // zweiter Aufruf: jetzt steht Anna als vorhandener Kontakt zur Auswahl
                sc.onActivity { act -> log.append("open: ").append(ContactExportUi.open(act, p!!)).append('\n') }
                Thread.sleep(4000)
                snap("7-auswahl-vorhanden")
                sh("input keyevent 4")
                Thread.sleep(1500)
            }
        }
        log.append("\n== System-Adressbuch (content query) ==\n")
        log.append(sh("content query --uri content://com.android.contacts/data --projection display_name:mimetype:data1:data2"))
        log.append("\n== Saga-Berechtigungen ==\n")
        log.append(sh("dumpsys package ${ins.targetContext.packageName}").lineSequence().filter { "CONTACTS" in it }.joinToString("\n").ifEmpty { "(keine Kontakte-Berechtigung)" })
        File(dir, "k27b-data.txt").writeText(log.toString())
    }
}
