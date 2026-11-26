package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
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
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * 2.6 (Bugs-DB 2271 K-D): Bilder von „Heute“ mit der Tageszeile „Kurz gesagt“ (hell/dunkel, Ruhe-Pille). Winziger
 * HTTP-Server auf dem Geraet (127.0.0.1) mit erfundenen Antworten (Antworten der Tageszeile aus tests/today_vectors.json).
 * Nutzt nur TodayScreen - laeuft auch auf dem Stand vor 2271 (Vorher-Bilder). Ablage: getExternalFilesDir()/shots/2271-*.png
 */
@RunWith(AndroidJUnit4::class)
open class TodayLine2271ShotsTest {
    protected val ins get() = InstrumentationRegistry.getInstrumentation()
    protected val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private lateinit var sock: ServerSocket
    /** "normal" | "ruhe": Antwort von GET /api/ai/today */
    @Volatile protected var lineMode = "normal"
    @Volatile protected var calls = mutableListOf<String>()
    /** false: Server ohne aitoday1 (= Ansicht wie vor 2271) */
    @Volatile protected var feature = true

    private val info = "Ohne KI berechnet – aus deinen Aufgaben, Terminen und Aufträgen. Nichts davon geht an ein Sprachmodell."
    private val waiting = """{"n": 1, "text": "1 Auftrag wartet auf dich ›", "target": {"type": "jobs", "group": "wartet_auf_dich"}}"""
    protected val normal = """{"enabled": true, "on": true, "day": "2026-07-07", "tz": "Europe/Berlin", "label": "Kurz gesagt", "line": {"text": "Elternabend 19:30 · „Pavillon abholen“ überfällig (+2) · „Kuchen backen“ heute", "a11y": "Kurz gesagt: Elternabend 19:30, „Pavillon abholen“ überfällig, plus 2, „Kuchen backen“ heute", "parts": [{"kind": "event", "text": "Elternabend 19:30", "target": {"type": "events", "date": "2026-07-07"}}, {"kind": "over", "text": "„Pavillon abholen“ überfällig (+2)", "n": 3, "target": {"type": "task", "id": 7}}, {"kind": "due", "text": "„Kuchen backen“ heute", "target": {"type": "row", "id": 512}}]}, "waiting": $waiting, "counts": {"events": 1, "over": 3, "due": 1, "waiting": 1}, "quiet": null, "ask": null, "pending": [], "info": "$info", "v": "4c01b4a34ebc"}"""
    protected val ruhe = """{"enabled": true, "on": true, "day": "2026-07-07", "tz": "Europe/Berlin", "label": "Kurz gesagt", "line": null, "waiting": $waiting, "counts": {"events": 1, "over": 3, "due": 1, "waiting": 1}, "quiet": {"until": "2026-07-08T05:00:00+02:00", "text": "Ruhe bis morgen", "undo": "Wieder an"}, "ask": null, "pending": [], "info": "$info", "v": "89c0cb5de019"}"""

    protected val aus = """{"enabled": true, "on": false, "label": "Kurz gesagt", "line": null, "waiting": $waiting, "quiet": null, "ask": null, "info": "$info", "v": "aus000000000"}"""

    private fun route(method: String, path: String): String? {
        val today = LocalDate.now()
        val ev = ZonedDateTime.now().withHour(19).withMinute(30).withSecond(0).withNano(0)
        return when (path) {
            "api/version" -> """{"name":"Saga","version":"2.6.0-rc.1","api":3,"features":[${if (feature) "\"aitoday1\"" else ""}]}"""
            "api/account" -> """{"username":"anna","name":"Anna Beispiel","can_edit":true}"""
            "api/tasks" -> """{"open":[{"id":7,"text":"Pavillon abholen","due":"${today.minusDays(2)}","prio":1},""" +
                """{"id":8,"text":"Kuchen backen","due":"$today","prio":2}],"done":[]}"""
            "api/calendar/events" -> """{"events":[{"summary":"Elternabend","start":"${ev.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}",""" +
                """"end":"${ev.plusHours(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}","allday":false,"color":"#4184e4"}]}"""
            "api/settings/calendars" -> """{"accounts":[]}"""
            "api/notes" -> "[]"
            "api/first-steps" -> """{"enabled":false}"""
            "api/ai/today" -> when (method) {
                "PUT" -> aus
                else -> if (lineMode == "ruhe") ruhe else normal
            }
            "api/ai/today/quiet" -> if (method == "DELETE") { lineMode = "normal"; normal } else { lineMode = "ruhe"; ruhe }
            else -> null
        }
    }

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
                            if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        if (len > 0) { val buf = CharArray(len); inp.read(buf, 0, len) }
                        val method = line.substringBefore(' ')
                        val path = line.split(" ").getOrNull(1).orEmpty().trimStart('/').substringBefore('?')
                        synchronized(calls) { calls.add("$method $path") }
                        val body = route(method, path)
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
            repo.settings.setAccount("Anna Beispiel")
            repo.needsLogin.value = false
            repo.checkVersion()
        }
        ShotMode.on = true
    }

    @After fun tearDown() {
        sock.close()
    }

    /** „Heute“ zeigen, nach dem Laden ein Bild; [then] laeuft danach, solange die Ansicht offen ist */
    protected fun shot(name: String, dark: Boolean, then: (() -> Unit)? = null) {
        val nav = AppNavigator(listOf(Dest.Today))
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                a.setContent {
                    SagaTheme(dark = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Box(Modifier.fillMaxSize()) { TodayScreen(app.repo, nav, Chrome(showMenu = true, onMenu = {})) }
                        }
                    }
                }
            }
            Thread.sleep(3500)
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            val f = File(dir, "2271-$name.png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(f.length() > 1000)
            then?.invoke()
        }
    }

    /** Vorher: Server ohne aitoday1 - „Heute“ wie vor 2271 (keine Abfrage, keine Zeile) */
    @Test fun v_vorher() {
        feature = false
        runBlocking { app.repo.checkVersion() }
        shot("heute-hell-vorher", dark = false)
        shot("heute-dunkel-vorher", dark = true)
        assertTrue("ohne aitoday1 keine Abfrage", synchronized(calls) { calls.none { it.contains("api/ai/today") } })
        feature = true
        runBlocking { app.repo.checkVersion() }
    }

    @Test fun a_shots() {
        lineMode = "normal"
        shot("heute-hell", dark = false)
        shot("heute-dunkel", dark = true)
        lineMode = "ruhe"
        shot("heute-ruhe-hell", dark = false)
        shot("heute-ruhe-dunkel", dark = true)
        lineMode = "normal"
    }
}
