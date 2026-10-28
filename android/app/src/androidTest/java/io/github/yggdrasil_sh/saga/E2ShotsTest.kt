package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.page.InlineColorMenu
import io.github.yggdrasil_sh.saga.ui.page.PageScreen
import io.github.yggdrasil_sh.saga.ui.tasks.TasksScreen
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.ShotMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * 2.5 Etappe 2 (Android): Bildschirmfotos mit neutralen Beispieldaten - Konfliktblock, Block-Markierung (Anwesenheit),
 * Aufgabenliste mit Quellen, Farb-Auswahl; hell und dunkel. Winziger HTTP-Server auf dem Geraet (127.0.0.1) mit
 * erfundenen Antworten. Ablage: getExternalFilesDir()/shots/e2-*.png
 */
@RunWith(AndroidJUnit4::class)
class E2ShotsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private lateinit var sock: ServerSocket
    private val today = LocalDate.now()

    private fun q(s: String) = org.json.JSONObject.quote(s)

    private val page = """{"blocks":[
        {"id":"h1","type":"header","data":{"text":"Projektplan","level":2}},
        {"id":"p1","type":"paragraph","data":{"text":"Der Termin für die Abnahme ist am <b>Freitag</b>."}},
        {"id":"p1-k","type":"paragraph","data":{"text":"Der Termin für die Abnahme ist am <b>Donnerstag</b>, 14 Uhr."},
         "tunes":{"hkconflict":{"by":"~kimkimkimkimkimk","name":"Kim Muster","at":1,"of":"p1"}}},
        {"id":"p2","type":"paragraph","data":{"text":"Offene Punkte: Budget, <span class=\"hk-c-green\">Testplan</span> und <mark class=\"hk-bg-yellow\">Schulung</mark>."}},
        {"id":"p3","type":"paragraph","data":{"text":"Rückfragen bitte an <span class=\"hk-mention\" data-u=\"~kimkimkimkimkimk\">Kim</span>."}}]}"""

    private val routes: Map<String, String> = mapOf(
        "api/version" to """{"name":"Saga","version":"2.5.0","api":3,"features":["tasksrc1","taskdue1","mention1","synced1","move1"]}""",
        "api/account" to """{"username":"alex","name":"Alex Beispiel","can_edit":true}""",
        "api/notes/7" to """{"id":7,"title":"Projektplan","format":"blocks","rev":12,"role":"owner","body":${q(page)},
            "people":{"~kimkimkimkimkimk":{"name":"Kim Muster","me":false}}}""",
        "api/presence" to """{"users":[{"username":"kim","name":"Kim Muster","editing":true,"block":"p2","color":"purple","me":false},
            {"username":"alex","name":"Alex Beispiel","editing":true,"block":"h1","color":"blue","me":true}],"rev":12}""",
        "api/notes/7/comments" to """{"threads":[],"role":"owner"}""",
        "api/tasks" to """{"open":[{"id":1,"text":"Rechnung prüfen","due":"${today.minusDays(1)}","prio":1}],"done":[],
            "items":[
              {"key":"t:1","kind":"task","id":1,"text":"Rechnung prüfen","due":"${today.minusDays(1)}","prio":1,"done":false,"overdue":true},
              {"key":"r:41","kind":"row","id":41,"src":77,"text":"Abnahme vorbereiten","due":"$today","time":"14:00","prio":1,"done":false,"overdue":false,"edit":true,"assignees":["~alexalexalexalex","~kimkimkimkimkimk"]},
              {"key":"r:42","kind":"row","id":42,"src":78,"text":"Fotos sortieren","due":"${today.plusDays(1)}","due_end":"${today.plusDays(3)}","prio":2,"done":false,"overdue":false,"edit":true,"assignees":[]},
              {"key":"r:43","kind":"row","id":43,"src":77,"text":"Freigabe Kim (nur lesen)","due":"${today.plusDays(2)}","prio":3,"done":false,"overdue":false,"edit":false,"assignees":["~kimkimkimkimkimk"]},
              {"key":"t:2","kind":"task","id":2,"text":"Blumen gießen","due":null,"prio":2,"done":false,"overdue":false}],
            "sources":[{"db":77,"title":"Projekt","icon":"🚀","role":"owner","scope":"all","own":true,"count":2,"map":{"done":"status","assignee":"wer"}},
                       {"db":78,"title":"Haushalt","icon":"🏠","role":"edit","scope":"assigned","own":false,"count":1,"map":{"done":"erl"}}],
            "people":{"~alexalexalexalex":{"name":"Alex Beispiel","me":true},"~kimkimkimkimkimk":{"name":"Kim Muster"}},
            "limits":{"sources":20,"rows":500}}""",
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
                        var len = 0
                        while (true) {
                            val h = inp.readLine() ?: break
                            if (h.isEmpty()) break
                            if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        if (len > 0) { val buf = CharArray(len); inp.read(buf) }
                        val path = line.split(" ").getOrNull(1).orEmpty().trimStart('/').substringBefore('?')
                        val body = routes[path]
                        val out = c.getOutputStream()
                        val bytes = (body ?: """{"error":"not found"}""").toByteArray()
                        out.write(("HTTP/1.1 ${if (body != null) "200 OK" else "404 Not Found"}\r\nContent-Type: application/json; charset=utf-8\r\n" +
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
            repo.serverFeatures.value = setOf("tasksrc1", "taskdue1", "mention1", "synced1", "move1")
            repo.peopleNames.value = mapOf("~kimkimkimkimkimk" to "Kim Muster")
        }
        ShotMode.on = true
    }

    @After fun tearDown() {
        sock.close()
    }

    private fun shot(name: String) {
        ins.waitForIdleSync()
        val b: Bitmap = ins.uiAutomation.takeScreenshot()
        val f = File(dir, "e2-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 1000)
    }

    private fun show(name: String, dark: Boolean, wait: Long = 4000, content: @androidx.compose.runtime.Composable () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                a.setContent {
                    SagaTheme(dark = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                    }
                }
            }
            Thread.sleep(wait)
            shot(name)
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Test fun shots() {
        val chrome = Chrome(showMenu = true, onMenu = {})
        for (dark in listOf(false, true)) {
            val suf = if (dark) "dunkel" else "hell"
            show("konflikt-markierung-$suf", dark) { PageScreen(7, app.repo, AppNavigator(listOf(Dest.Page(7))), chrome, appScope) }
            show("aufgaben-quellen-$suf", dark) { TasksScreen(app.repo, AppNavigator(listOf(Dest.Tasks)), chrome) }
            show("farbauswahl-$suf", dark, wait = 1500) {
                Box(Modifier.padding(24.dp)) {
                    Text("Offene Punkte: Budget, Testplan und Schulung.", style = MaterialTheme.typography.bodyLarge)
                    InlineColorMenu(true, "green", null, onDismiss = {}) { _, _ -> }
                }
            }
        }
    }
}
