package io.github.yggdrasil_sh.saga

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.RunBar
import io.github.yggdrasil_sh.saga.call.RunningCallBar
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.nav.Sidebar
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
 * 2.5 (Bugs-DB 2156/2154): Bildschirmfotos Saga-Siegel - Seitenkopf mit Siegel, "Verlinkt von"-Liste und Seitenbaum mit
 * Siegeln, Symbol-Dialog (Bereich "Saga-Siegel"), Leiste "Anruf laeuft" bei vollem Anruf; hell und dunkel. Bewusst nur mit
 * Schnittstellen, die es schon vor 2.5-Siegel gab - derselbe Test laeuft gegen den alten Stand ("vorher": Pfad als Text).
 * Winziger HTTP-Server auf dem Geraet (127.0.0.1): api/version mit siegel1, api/siegel (deutsche Namen wie der Server),
 * Siegel-PNGs aus /data/local/tmp/siegel/<farbe>/<motiv>.png (per adb push; sonst einfarbige Kreise).
 * Ablage: getExternalFilesDir()/shots/siegel-<stand>-*.png (Stand per Instrumentation-Argument "stand", Standard "nachher").
 */
@RunWith(AndroidJUnit4::class)
class SiegelShotsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private val stand: String get() = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("stand") ?: "nachher"
    private lateinit var sock: ServerSocket

    private fun q(s: String) = org.json.JSONObject.quote(s)

    private val para = """{"id":"p1","type":"paragraph","data":{"text":"Fjorde, Fähren und lange Abende."}}"""
    private val note = """{"id":7,"title":"Reisetagebuch Nord","icon":"/siegel/smaragd/rabe.svg","format":"blocks","rev":3,"role":"owner","cover":"/covers/saga-am-hafen.jpg","cover_pos":45,"body":${q("""{"blocks":[$para]}""")}}"""
    private val backlinks = """[{"id":8,"title":"Packliste","icon":"/siegel/weinrot/truhe.svg"},{"id":9,"title":"Fährplan Hurtigruten","icon":"/siegel/elfenbein/drachenboot.svg"},{"id":10,"title":"Notizen","icon":"📝"}]"""
    private val listing = """{"colors": [{"id": "weinrot", "label": "Weinrot"}, {"id": "smaragd", "label": "Smaragd"}, {"id": "elfenbein", "label": "Elfenbein"}], "motifs": [{"id": "yggdrasil", "label": "Yggdrasil"}, {"id": "rabe", "label": "Rabe"}, {"id": "drachenboot", "label": "Drachenboot"}, {"id": "horn", "label": "Horn"}, {"id": "rune", "label": "Rune"}, {"id": "buch", "label": "Buch"}, {"id": "feder", "label": "Feder"}, {"id": "schriftrolle", "label": "Schriftrolle"}, {"id": "kerze", "label": "Kerze"}, {"id": "laterne", "label": "Laterne"}, {"id": "mond", "label": "Mond"}, {"id": "sonne", "label": "Sonne"}, {"id": "nordlicht", "label": "Nordlicht"}, {"id": "berg", "label": "Berg"}, {"id": "wald", "label": "Wald"}, {"id": "welle", "label": "Welle"}, {"id": "kompass", "label": "Kompass"}, {"id": "karte", "label": "Karte"}, {"id": "schluessel", "label": "Schlüssel"}, {"id": "schild", "label": "Schild"}, {"id": "hammer", "label": "Hammer"}, {"id": "axt", "label": "Axt"}, {"id": "krone", "label": "Krone"}, {"id": "kristall", "label": "Kristall"}, {"id": "trank", "label": "Trank"}, {"id": "feuer", "label": "Feuer"}, {"id": "stern", "label": "Stern"}, {"id": "herz", "label": "Herz"}, {"id": "brief", "label": "Brief"}, {"id": "sanduhr", "label": "Sanduhr"}, {"id": "zahnrad", "label": "Zahnrad"}, {"id": "truhe", "label": "Truhe"}, {"id": "server", "label": "Server"}, {"id": "terminal", "label": "Terminal"}, {"id": "katze", "label": "Katze"}, {"id": "teetasse", "label": "Teetasse"}], "path": "/siegel/{color}/{motif}.svg", "sizes": [64, 128, 256]}"""

    private fun routes(): Map<String, String> = buildMap {
        put("api/version", """{"name":"Saga","version":"2.5.0","api":3,"features":["siegel1"]}""")
        put("api/account", """{"username":"alex","name":"Alex Beispiel","can_edit":true}""")
        put("api/presence", """{"users":[],"rev":3}""")
        put("api/notes/7", note)
        put("api/notes/7/comments", """{"threads":[],"role":"owner"}""")
        put("api/notes/7/backlinks", backlinks)
        put("api/siegel", listing)
    }

    private val shellPics = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Siegel-PNG (per adb nach /data/local/tmp/siegel gelegt, nur fuer die Shell lesbar - daher ueber UiAutomation) */
    private fun siegelPng(farbe: String, motiv: String): ByteArray {
        if (!Regex("[a-z]{2,20}").matches(farbe) || !Regex("[a-z]{2,20}").matches(motiv)) return fallback(farbe)
        return shellPics.getOrPut("$farbe/$motiv") {
            val pfd = ins.uiAutomation.executeShellCommand("cat /data/local/tmp/siegel/$farbe/$motiv.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }.takeIf { it.size > 8 && it[1] == 'P'.code.toByte() } ?: fallback(farbe)
    }

    private fun fallback(farbe: String): ByteArray {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = when (farbe) { "weinrot" -> 0xFF7A1F2E.toInt(); "smaragd" -> 0xFF1E6B4E.toInt(); else -> 0xFFEDE3C8.toInt() }
        Canvas(b).drawCircle(64f, 64f, 60f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c })
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun cover(): ByteArray {
        val b = Bitmap.createBitmap(640, 300, Bitmap.Config.ARGB_8888)
        val p = Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 640f, 300f, 0xFF35577A.toInt(), 0xFFB98B5A.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        Canvas(b).drawRect(0f, 0f, 640f, 300f, p)
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
                        val seal = Regex("siegel/([a-z]+)/([a-z]+)\\.(svg|png)").matchEntire(path)
                        val img = seal != null || path.startsWith("covers/")
                        val body = if (img) null else routes()[path]
                        val bytes = when {
                            seal != null -> siegelPng(seal.groupValues[1], seal.groupValues[2])
                            img -> cover()
                            else -> (body ?: """{"error":"not found"}""").toByteArray()
                        }
                        val type = if (img) "image/png" else "application/json; charset=utf-8"
                        val out = c.getOutputStream()
                        out.write(("HTTP/1.1 ${if (img || body != null) "200 OK" else "404 Not Found"}\r\nContent-Type: $type\r\n" +
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
            repo.checkVersion()
        }
        val info = ins.uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ins.uiAutomation.serviceInfo = info
        ShotMode.on = true
    }

    @After fun tearDown() {
        app.repo.calls.run.value = null
        sock.close()
    }

    private fun find(n: AccessibilityNodeInfo?, s: String): AccessibilityNodeInfo? {
        if (n == null) return null
        val t = n.text?.toString()
        val d = n.contentDescription?.toString()
        if (t == s || d == s || (s.endsWith("*") && (t?.startsWith(s.dropLast(1)) == true || d?.startsWith(s.dropLast(1)) == true))) return n
        for (i in 0 until n.childCount) find(n.getChild(i), s)?.let { return it }
        return null
    }

    /** Knoten mit Text bzw. Beschreibung [s] ("…*" = Anfang) in irgendeinem Fenster antippen; [optional]: fehlt -> nichts */
    private fun tap(s: String, optional: Boolean = false) {
        val end = SystemClock.uptimeMillis() + if (optional) 3_000 else 10_000
        while (SystemClock.uptimeMillis() < end) {
            val roots = ins.uiAutomation.windows.mapNotNull { it.root }.reversed() + listOfNotNull(ins.uiAutomation.rootInActiveWindow)
            val n = roots.firstNotNullOfOrNull { find(it, s) }
            if (n != null) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val t = SystemClock.uptimeMillis()
                for ((a, dt) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 60L)) {
                    val e = MotionEvent.obtain(t, t + dt, a, r.exactCenterX(), r.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    ins.uiAutomation.injectInputEvent(e, true)
                    e.recycle()
                }
                return
            }
            Thread.sleep(300)
        }
        if (!optional) throw AssertionError("nicht gefunden: $s")
    }

    /** Langsamer Emulator: Systemdialog "System UI isn't responding" wegtippen ("Wait"/"Warten"), sonst nichts */
    private fun noAnr() {
        val roots = ins.uiAutomation.windows.mapNotNull { it.root }
        for (w in listOf("Wait", "Warten")) {
            val n = roots.firstNotNullOfOrNull { find(it, w) } ?: continue
            val r = Rect().also { n.getBoundsInScreen(it) }
            val t = SystemClock.uptimeMillis()
            for ((a, dt) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 60L)) {
                val e = MotionEvent.obtain(t, t + dt, a, r.exactCenterX(), r.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                ins.uiAutomation.injectInputEvent(e, true)
                e.recycle()
            }
            Thread.sleep(800)
        }
    }

    private fun shot(name: String) {
        Thread.sleep(2500)
        ins.waitForIdleSync()
        noAnr()
        val b: Bitmap = ins.uiAutomation.takeScreenshot()
        val f = File(dir, "siegel-$stand-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 1000)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun scene(dark: Boolean, steps: List<Pair<String?, String>>, content: @Composable () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
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
            Thread.sleep(4000)
            for ((target, name) in steps) {
                noAnr()
                if (target != null) {
                    if (target.startsWith("?")) tap(target.drop(1), optional = true) else tap(target)
                }
                shot(name)
            }
        }
    }

    @Test fun shots() {
        val device = if (ins.targetContext.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "handy"
        val pages = listOf(
            PageMeta(7, "Reisetagebuch Nord", "/siegel/smaragd/rabe.svg", updated = 3.0),
            PageMeta(8, "Packliste", "/siegel/weinrot/truhe.svg", updated = 2.0),
            PageMeta(9, "Fährplan Hurtigruten", "/siegel/elfenbein/drachenboot.svg", updated = 1.0),
            PageMeta(11, "Rezepte", "/siegel/smaragd/teetasse.svg", updated = 0.5),
            PageMeta(10, "Notizen", "📝", updated = 0.2),
        )
        for (dark in listOf(false, true)) {
            val suf = device + "-" + if (dark) "dunkel" else "hell"
            // Seitenkopf + "Verlinkt von" (Liste), dann Symbol-Dialog (Seitensymbol antippen; neu: Bereich Saga-Siegel)
            scene(dark, listOf(null to "kopf-$suf", "Seitensymbol*" to "auswahl-$suf")) {
                PageScreen(7, app.repo, AppNavigator(listOf(Dest.Page(7))), Chrome(showMenu = true, onMenu = {}), appScope)
            }
            // Seitenbaum (Seitenleiste)
            app.repo.pages.value = pages
            scene(dark, listOf(null to "baum-$suf")) {
                Sidebar(app.repo, AppNavigator(listOf(Dest.Page(7))), onNavigate = {})
            }
        }
        // Leiste "Anruf laeuft" bei vollem Gruppenanruf (Text ohne Servertext: fester Text der App)
        for (dark in listOf(false, true)) {
            val suf = device + "-" + if (dark) "dunkel" else "hell"
            app.repo.calls.run.value = RunBar("c1", 3, "group", "audio", "", 4, joinable = false, block = "full", limit = 4)
            scene(dark, listOf(null to "voll-leiste-$suf")) {
                Column { RunningCallBar(app.repo.calls, null, statusBar = true) }
            }
            app.repo.calls.run.value = RunBar("c1", 3, "group", "audio", "Pixel 9", 4, joinable = false, block = "full", limit = 4, takeover = true)
            scene(dark, listOf(null to "voll-leiste-eigenes-$suf")) {
                Column { RunningCallBar(app.repo.calls, null, statusBar = true) }
            }
        }
    }
}
