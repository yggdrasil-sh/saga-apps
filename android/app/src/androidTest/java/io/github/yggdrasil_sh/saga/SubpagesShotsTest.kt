package io.github.yggdrasil_sh.saga

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.page.PageScreen
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 2.5 Widget-Art `subpages` (Unterseiten): Bildschirmfotos mit erfundenen Beispieldaten - Galerie im Hochformat,
 * Galerie mit Titelbild/ohne Bildflaeche, Liste, Leerzustand; hell und dunkel. Winziger HTTP-Server auf dem Geraet
 * (127.0.0.1) mit erfundenen Antworten und einfarbigen Verlaufsbildern statt echter Dateien. Handy oder Tablet je nach
 * Bildschirmbreite des Geraets. Ablage: getExternalFilesDir()/shots/unterseiten-*.png
 */
@RunWith(AndroidJUnit4::class)
class SubpagesShotsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private lateinit var sock: ServerSocket

    private fun q(s: String) = org.json.JSONObject.quote(s)

    private fun note(id: Int, title: String, blocks: String) =
        """{"id":$id,"title":${q(title)},"icon":"📚","format":"blocks","rev":3,"role":"owner","body":${q("""{"blocks":[$blocks]}""")}}"""

    private fun wg(id: String, cfg: String, extra: String = "") = """{"id":"$id","type":"widget","data":{"v":1,"kind":"subpages","size":"m"$extra,"cfg":$cfg}}"""

    private val titles = listOf(
        "Der Nebelturm", "Salz und Sterne", "Die Kartenmacherin von Ostwind", "Mondgarten", "Das leise Archiv",
        "Kupferherz", "Wo die Flüsse schlafen", "Lichtfänger", "Unter dem Glasmeer", "Die siebte Laterne",
    )
    private val icons = listOf("📖", "🧂", "🗺️", "🌙", "🗄️", "⚙️", "🏞️", "🔦", "🌊", "🏮")

    /** W.data: jede vierte Seite ohne Bild (Platzhalter), eine Datenbank, ein fehlender Titel */
    private fun values(bid: String, n: Int, img: Boolean, more: Int = 0) = """{"widgets":{"$bid":{"kind":"subpages","value":${n + more},"max":null,"text":null,"fmt":null,
        "as_of":"2026-11-09T20:00:00Z","error":null,"data":{"items":[${(0 until n).joinToString(",") { i ->
            val t = if (i == 8) "" else titles[i % titles.size]
            val pic = if (img && i % 4 != 3) "\"/files/${"%024x".format(i + 1)}\"" else "null"
            """{"id":${800 + i},"title":${q(t)},"icon":"${if (i == 5) "" else icons[i % icons.size]}","img":$pic,"kind":"${if (i == 5) "database" else "page"}"}"""
        }}],"total":${n + more},"more":$more,"can_add":true}}}}"""

    private val para = """{"id":"p1","type":"paragraph","data":{"text":"Was ich gelesen habe und was noch wartet."}}"""

    private val routes: Map<String, String> = mapOf(
        "api/version" to """{"name":"Saga","version":"2.5.0","api":3,"features":["subpages1"]}""",
        "api/account" to """{"username":"alex","name":"Alex Beispiel","can_edit":true}""",
        "api/presence" to """{"users":[],"rev":3}""",
        "api/notes/7" to note(7, "Bücherregal", para + "," + wg("g1", """{"view":"gallery","image":"content"}""")),
        "api/notes/7/widgets" to values("g1", 10, true),
        "api/notes/8" to note(8, "Bücherregal", para + "," + wg("l1", """{"view":"list","image":"cover"}""", ""","title":"Leseliste"""")),
        "api/notes/8/widgets" to values("l1", 7, false, more = 12),
        "api/notes/9" to note(9, "Reisen", wg("g2", """{"view":"gallery","image":"cover"}""") + "," + wg("g3", """{"view":"gallery","image":"none"}""", ""","style":"card","title":"Ohne Bild"""")),
        "api/notes/9/widgets" to values("g2", 4, true).dropLast(2) + "," + values("g3", 4, false).substringAfter("""{"widgets":{"""),
        "api/notes/10" to note(10, "Neues Projekt", wg("e1", """{"view":"gallery","image":"content"}""")),
        "api/notes/10/widgets" to """{"widgets":{"e1":{"kind":"subpages","value":0,"error":null,"data":{"items":[],"total":0,"more":0,"can_add":true}}}}""",
    ) + listOf(7, 8, 9, 10).associate { "api/notes/$it/comments" to """{"threads":[],"role":"owner"}""" }

    /** Platzhalterbild: ruhiger Farbverlauf, Farbe aus der Datei-ID */
    private fun picture(id: String): ByteArray {
        val n = id.takeLast(4).toIntOrNull(16) ?: 0
        val hue = (n * 47 + 200) % 360
        val b = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 240f, 360f, android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.38f, 0.78f)),
                android.graphics.Color.HSVToColor(floatArrayOf(((hue + 35) % 360).toFloat(), 0.5f, 0.5f)), Shader.TileMode.CLAMP)
        }
        Canvas(b).drawRect(0f, 0f, 240f, 360f, p)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
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
                            if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        if (len > 0) { val buf = CharArray(len); inp.read(buf) }
                        val path = line.split(" ").getOrNull(1).orEmpty().trimStart('/').substringBefore('?')
                        val file = path.startsWith("files/")
                        val body = if (file) null else routes[path]
                        val bytes = if (file) picture(path.substringAfter('/')) else (body ?: """{"error":"not found"}""").toByteArray()
                        val out = c.getOutputStream()
                        out.write(("HTTP/1.1 ${if (file || body != null) "200 OK" else "404 Not Found"}\r\nContent-Type: ${if (file) "image/png" else "application/json; charset=utf-8"}\r\n" +
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
            repo.serverFeatures.value = setOf("subpages1")
        }
        ShotMode.on = true
    }

    @After fun tearDown() {
        sock.close()
    }

    private fun show(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                // deutsche Texte unabhaengig von der Geraetesprache
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag("de")) }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg, androidx.activity.compose.LocalActivityResultRegistryOwner provides a) {
                        SagaTheme(dark = dark) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                        }
                    }
                }
            }
            Thread.sleep(4500)
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            val f = File(dir, "unterseiten-$name.png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(f.length() > 1000)
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Test fun shots() {
        val chrome = Chrome(showMenu = true, onMenu = {})
        val device = if (ins.targetContext.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "handy"
        for (dark in listOf(false, true)) {
            val suf = device + "-" + if (dark) "dunkel" else "hell"
            show("galerie-hochformat-$suf", dark) { PageScreen(7, app.repo, AppNavigator(listOf(Dest.Page(7))), chrome, appScope) }
            show("liste-$suf", dark) { PageScreen(8, app.repo, AppNavigator(listOf(Dest.Page(8))), chrome, appScope) }
            show("galerie-titelbild-ohne-bild-$suf", dark) { PageScreen(9, app.repo, AppNavigator(listOf(Dest.Page(9))), chrome, appScope) }
            show("leer-$suf", dark) { PageScreen(10, app.repo, AppNavigator(listOf(Dest.Page(10))), chrome, appScope) }
        }
    }
}
