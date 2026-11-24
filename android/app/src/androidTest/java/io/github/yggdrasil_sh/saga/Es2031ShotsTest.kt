package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.events.EventsScreen
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.settings.SettingsInfoScreen
import io.github.yggdrasil_sh.saga.ui.tasks.TasksScreen
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.ui.today.TodayScreen
import io.github.yggdrasil_sh.saga.util.ShotMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 2.6 (Bugs-DB 2031): leere Zustaende mit naechstem Schritt (Heute, Aufgaben, Termine) und Einstellungen > Info, mit
 * einem neuen Beispielkonto (keine Aufgaben, kein Kalender). Winziger HTTP-Server auf dem Geraet (127.0.0.1) mit
 * erfundenen Antworten. Laeuft auch auf dem Stand vor 2031 (Vorher-Bilder). Ablage: getExternalFilesDir()/shots/2031-*.png
 */
@RunWith(AndroidJUnit4::class)
open class Es2031ShotsTest {
    protected val ins get() = InstrumentationRegistry.getInstrumentation()
    protected val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private lateinit var sock: ServerSocket

    private val routes: Map<String, String> = mapOf(
        "api/version" to """{"name":"Saga","version":"2.6.0-rc.1","api":3}""",
        "api/account" to """{"username":"alex","name":"Alex Beispiel","can_edit":true}""",
        "api/tasks" to """{"open":[],"done":[]}""",
        "api/calendar/events" to """{"events":[]}""",
        "api/settings/calendars" to """{"accounts":[]}""",
        "api/notes" to """[]""",
        "api/first-steps" to """{"enabled":true,"ask":true,"state":null,"page":null,"items":[{"key":"avatar","done":false},""" +
            """{"key":"chat","done":false},{"key":"app","done":true},{"key":"page","done":false},{"key":"task","done":false}]}""",
    )

    @Before fun setUp() {
        sock = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!sock.isClosed) {
                val s = try { sock.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    s.use { c ->
                        val inp = c.getInputStream().bufferedReader()
                        val line = inp.readLine() ?: return@use
                        while (true) { val h = inp.readLine() ?: break; if (h.isEmpty()) break }
                        val path = line.split(" ").getOrNull(1).orEmpty().trimStart('/').substringBefore('?')
                        val body = routes[path]
                        val out = c.getOutputStream()
                        val bytes = (body ?: """{"error":"not found"}""").toByteArray()
                        out.write(("HTTP/1.1 ${if (body != null) "200 OK" else "404 Not Found"}\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        out.write(bytes)
                        out.flush()
                    }
                }
            }
        }
        val repo = app.repo
        runBlocking {
            repo.awaitReady()
            val server = "http://127.0.0.1:${sock.localPort}"
            repo.secure.setCredentials(server, "hkd_beispiel", null)
            repo.settings.setServer(server)
            repo.settings.setAccount("Alex Beispiel")
            repo.needsLogin.value = false
        }
        ShotMode.on = true
    }

    @After fun tearDown() {
        sock.close()
    }

    protected fun shot(name: String, content: @Composable (AppNavigator, Chrome) -> Unit) {
        val nav = AppNavigator(listOf(Dest.Today))
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                a.setContent {
                    SagaTheme(dark = false) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Box(Modifier.fillMaxSize()) { content(nav, Chrome(showMenu = true, onMenu = {})) }
                        }
                    }
                }
            }
            Thread.sleep(3500)                                // Antworten des Beispielservers laden, Frage nach 600 ms
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            val f = File(dir, "2031-$name.png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(f.length() > 1000)
        }
    }

    @Test open fun screens() {
        shot("heute") { nav, chrome -> TodayScreen(app.repo, nav, chrome) }
        shot("aufgaben") { nav, chrome -> TasksScreen(app.repo, nav, chrome) }
        shot("termine") { nav, chrome -> EventsScreen(app.repo, nav, chrome) }
        shot("info") { nav, chrome -> SettingsInfoScreen(app.repo, nav, chrome) }
    }
}
