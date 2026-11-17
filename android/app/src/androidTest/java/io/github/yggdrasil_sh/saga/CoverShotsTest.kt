package io.github.yggdrasil_sh.saga

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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
 * 2.5 Titelbild-Galerie: Bildschirmfotos vorher (Server ohne Galerie, GET api/covers 404 - Menue wie bisher) und nachher
 * (Menue mit "Galerie …"/"Zufällig"/"Über dieses Bild", Auswahl nach Gruppen, "Über dieses Bild"); hell und dunkel.
 * Winziger HTTP-Server auf dem Geraet (127.0.0.1) mit einem Auszug der mitgelieferten Galerie (covers.json des Servers).
 * Vorschaubilder: getExternalFilesDir()/covers/<id>.jpg, falls vorhanden (per adb push), sonst Farbverlaeufe.
 * Bedienung ueber die Bedienungshilfen-Knoten (Antippen per UiAutomation). Ablage: getExternalFilesDir()/shots/titelbild-*.png
 */
@RunWith(AndroidJUnit4::class)
class CoverShotsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private val pics: File get() = File(ins.targetContext.getExternalFilesDir(null), "covers")
    private lateinit var sock: ServerSocket
    @Volatile private var coversOn = false

    private fun q(s: String) = org.json.JSONObject.quote(s)

    private val para = """{"id":"p1","type":"paragraph","data":{"text":"Fjorde, Fähren und lange Abende."}}"""
    private val note = """{"id":7,"title":"Reisetagebuch Nord","icon":"🧭","format":"blocks","rev":3,"role":"owner","cover":"/covers/hokusai-welle.jpg","cover_pos":45,"body":${q("""{"blocks":[$para]}""")}}"""

    private val listing = """
{"covers": [
 {"id": "balke-nordkap", "url": "/covers/balke-nordkap.jpg", "thumb": "/covers/balke-nordkap.jpg?w=384", "title": "Das Nordkap im Mondlicht", "artist": "Peder Balke", "date": "1848", "source_url": "https://www.metmuseum.org/art/collection/search/441379", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "nordisch", "pos": 55, "builtin": true},
 {"id": "dahl-wasserfall", "url": "/covers/dahl-wasserfall.jpg", "thumb": "/covers/dahl-wasserfall.jpg?w=384", "title": "Zwei Männer vor einem Wasserfall bei Sonnenuntergang", "artist": "Johan Christian Dahl", "date": "1823", "source_url": "https://www.metmuseum.org/art/collection/search/439344", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "nordisch", "pos": 45, "builtin": true},
 {"id": "dahl-kopenhagen", "url": "/covers/dahl-kopenhagen.jpg", "thumb": "/covers/dahl-kopenhagen.jpg?w=384", "title": "Kopenhagener Hafen im Mondlicht", "artist": "Johan Christian Dahl", "date": "1846", "source_url": "https://www.metmuseum.org/art/collection/search/439343", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "nordisch", "pos": 40, "builtin": true},
 {"id": "friedrich-mond", "url": "/covers/friedrich-mond.jpg", "thumb": "/covers/friedrich-mond.jpg?w=384", "title": "Zwei Männer betrachten den Mond", "artist": "Caspar David Friedrich", "date": "ca. 1825–30", "source_url": "https://www.metmuseum.org/art/collection/search/438417", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "romantik", "pos": 40, "builtin": true},
 {"id": "turner-walfaenger", "url": "/covers/turner-walfaenger.jpg", "thumb": "/covers/turner-walfaenger.jpg?w=384", "title": "Walfänger", "artist": "J. M. W. Turner", "date": "ca. 1845", "source_url": "https://www.metmuseum.org/art/collection/search/437854", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "romantik", "pos": 50, "builtin": true},
 {"id": "carus-milkel", "url": "/covers/carus-milkel.jpg", "thumb": "/covers/carus-milkel.jpg?w=384", "title": "Schloss Milkel im Mondlicht", "artist": "Carl Gustav Carus", "date": "ca. 1833–35", "source_url": "https://www.metmuseum.org/art/collection/search/788905", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "romantik", "pos": 40, "builtin": true},
 {"id": "hokusai-welle", "url": "/covers/hokusai-welle.jpg", "thumb": "/covers/hokusai-welle.jpg?w=384", "title": "Die große Welle vor Kanagawa", "artist": "Katsushika Hokusai", "date": "ca. 1830–32", "source_url": "https://www.metmuseum.org/art/collection/search/45434", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "holzschnitt", "pos": 45, "builtin": true},
 {"id": "hokusai-roter-fuji", "url": "/covers/hokusai-roter-fuji.jpg", "thumb": "/covers/hokusai-roter-fuji.jpg?w=384", "title": "Roter Fuji", "artist": "Katsushika Hokusai", "date": "ca. 1830–32", "source_url": "https://www.metmuseum.org/art/collection/search/36490", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "holzschnitt", "pos": 50, "builtin": true},
 {"id": "hokusai-gewitter", "url": "/covers/hokusai-gewitter.jpg", "thumb": "/covers/hokusai-gewitter.jpg?w=384", "title": "Gewitter unterhalb des Fuji", "artist": "Katsushika Hokusai", "date": "ca. 1830–32", "source_url": "https://www.metmuseum.org/art/collection/search/36492", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "holzschnitt", "pos": 35, "builtin": true},
 {"id": "bierstadt-landers-peak", "url": "/covers/bierstadt-landers-peak.jpg", "thumb": "/covers/bierstadt-landers-peak.jpg?w=384", "title": "Die Rocky Mountains, Lander’s Peak", "artist": "Albert Bierstadt", "date": "1863", "source_url": "https://www.metmuseum.org/art/collection/search/10154", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "landschaft", "pos": 40, "builtin": true},
 {"id": "bierstadt-lake-louise", "url": "/covers/bierstadt-lake-louise.jpg", "thumb": "/covers/bierstadt-lake-louise.jpg?w=384", "title": "Kanadische Rockies (Lake Louise)", "artist": "Albert Bierstadt", "date": "ca. 1889", "source_url": "https://www.metmuseum.org/art/collection/search/10149", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "landschaft", "pos": 45, "builtin": true},
 {"id": "cole-oxbow", "url": "/covers/cole-oxbow.jpg", "thumb": "/covers/cole-oxbow.jpg?w=384", "title": "The Oxbow", "artist": "Thomas Cole", "date": "1836", "source_url": "https://www.metmuseum.org/art/collection/search/10497", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "group": "landschaft", "pos": 40, "builtin": true},
 {"id": "saga-am-kaminfeuer", "url": "/covers/saga-am-kaminfeuer.jpg", "thumb": "/covers/saga-am-kaminfeuer.jpg?w=384", "title": "Am Kaminfeuer", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-stube", "pos": 20, "builtin": true},
 {"id": "saga-warme-decke-heisser-tee", "url": "/covers/saga-warme-decke-heisser-tee.jpg", "thumb": "/covers/saga-warme-decke-heisser-tee.jpg?w=384", "title": "Warme Decke, heißer Tee", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-stube", "pos": 20, "builtin": true},
 {"id": "saga-schreiben-unter-dem-nordlicht", "url": "/covers/saga-schreiben-unter-dem-nordlicht.jpg", "thumb": "/covers/saga-schreiben-unter-dem-nordlicht.jpg?w=384", "title": "Schreiben unter dem Nordlicht", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-schreibstube", "pos": 20, "builtin": true},
 {"id": "saga-lesen-am-kamin", "url": "/covers/saga-lesen-am-kamin.jpg", "thumb": "/covers/saga-lesen-am-kamin.jpg?w=384", "title": "Lesen am Kamin", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-schreibstube", "pos": 20, "builtin": true},
 {"id": "saga-im-gewaechshaus", "url": "/covers/saga-im-gewaechshaus.jpg", "thumb": "/covers/saga-im-gewaechshaus.jpg?w=384", "title": "Im Gewächshaus", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-garten", "pos": 20, "builtin": true},
 {"id": "saga-pflanzen-setzen", "url": "/covers/saga-pflanzen-setzen.jpg", "thumb": "/covers/saga-pflanzen-setzen.jpg?w=384", "title": "Pflanzen setzen", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-garten", "pos": 20, "builtin": true},
 {"id": "saga-sonnenuntergang-am-see", "url": "/covers/saga-sonnenuntergang-am-see.jpg", "thumb": "/covers/saga-sonnenuntergang-am-see.jpg?w=384", "title": "Sonnenuntergang am See", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-natur", "pos": 20, "builtin": true},
 {"id": "saga-am-hafen", "url": "/covers/saga-am-hafen.jpg", "thumb": "/covers/saga-am-hafen.jpg?w=384", "title": "Am Hafen", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-natur", "pos": 30, "builtin": true},
 {"id": "saga-mondnacht-am-weltenbaum", "url": "/covers/saga-mondnacht-am-weltenbaum.jpg", "thumb": "/covers/saga-mondnacht-am-weltenbaum.jpg?w=384", "title": "Mondnacht am Weltenbaum", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-winter", "pos": 20, "builtin": true},
 {"id": "saga-nordlicht-ueber-der-kueste", "url": "/covers/saga-nordlicht-ueber-der-kueste.jpg", "thumb": "/covers/saga-nordlicht-ueber-der-kueste.jpg?w=384", "title": "Nordlicht über der Küste", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-winter", "pos": 20, "builtin": true},
 {"id": "saga-im-schneesturm", "url": "/covers/saga-im-schneesturm.jpg", "thumb": "/covers/saga-im-schneesturm.jpg?w=384", "title": "Im Schneesturm", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-kriegerin", "pos": 20, "builtin": true},
 {"id": "saga-gewitter-ueber-dem-fjord", "url": "/covers/saga-gewitter-ueber-dem-fjord.jpg", "thumb": "/covers/saga-gewitter-ueber-dem-fjord.jpg?w=384", "title": "Gewitter über dem Fjord", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-kriegerin", "pos": 20, "builtin": true},
 {"id": "saga-herrin-des-gewitters", "url": "/covers/saga-herrin-des-gewitters.jpg", "thumb": "/covers/saga-herrin-des-gewitters.jpg?w=384", "title": "Herrin des Gewitters", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-magie", "pos": 20, "builtin": true},
 {"id": "saga-nordlichtzauber", "url": "/covers/saga-nordlichtzauber.jpg", "thumb": "/covers/saga-nordlichtzauber.jpg?w=384", "title": "Nordlichtzauber", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-magie", "pos": 20, "builtin": true},
 {"id": "saga-der-hund-mit-dem-wikingerhelm", "url": "/covers/saga-der-hund-mit-dem-wikingerhelm.jpg", "thumb": "/covers/saga-der-hund-mit-dem-wikingerhelm.jpg?w=384", "title": "Der Hund mit dem Wikingerhelm", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-gefaehrten", "pos": 30, "builtin": true},
 {"id": "saga-lesen-mit-dem-wolf", "url": "/covers/saga-lesen-mit-dem-wolf.jpg", "thumb": "/covers/saga-lesen-mit-dem-wolf.jpg?w=384", "title": "Lesen mit dem Wolf", "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "saga-gefaehrten", "pos": 30, "builtin": true}],
 "groups": [{"id": "nordisch", "label": "Nordisch"}, {"id": "romantik", "label": "Romantik"}, {"id": "holzschnitt", "label": "Holzschnitt"}, {"id": "landschaft", "label": "Landschaft"}, {"id": "saga-stube", "label": "Saga · Stube"}, {"id": "saga-schreibstube", "label": "Saga · Schreibstube"}, {"id": "saga-garten", "label": "Saga · Garten & Küche"}, {"id": "saga-natur", "label": "Saga · Natur"}, {"id": "saga-winter", "label": "Saga · Winter & Nordlicht"}, {"id": "saga-kriegerin", "label": "Saga · Kriegerin"}, {"id": "saga-magie", "label": "Saga · Magie"}, {"id": "saga-gefaehrten", "label": "Saga · Gefährten"}],
 "license": "CC0-1.0", "source": "The Metropolitan Museum of Art, Open Access (CC0 1.0)", "random_cover": true}
    """.trimIndent()

    private fun routes(): Map<String, String> = buildMap {
        put("api/version", """{"name":"Saga","version":"2.5.0","api":3,"features":[]}""")
        put("api/account", """{"username":"alex","name":"Alex Beispiel","can_edit":true}""")
        put("api/presence", """{"users":[],"rev":3}""")
        put("api/notes/7", note)
        put("api/notes/7/comments", """{"threads":[],"role":"owner"}""")
        if (coversOn) put("api/covers", listing)
    }

    /** Vorschaubild: per adb abgelegte Datei, sonst ruhiger Farbverlauf */
    private fun picture(name: String): Pair<ByteArray, String> {
        val f = File(pics, name.substringBefore('?'))
        if (f.isFile) return f.readBytes() to "image/jpeg"
        val hue = (name.hashCode() and 0x7fffffff) % 360
        val b = Bitmap.createBitmap(384, 220, Bitmap.Config.ARGB_8888)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 384f, 220f, android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.38f, 0.78f)),
                android.graphics.Color.HSVToColor(floatArrayOf(((hue + 35) % 360).toFloat(), 0.5f, 0.5f)), Shader.TileMode.CLAMP)
        }
        Canvas(b).drawRect(0f, 0f, 384f, 220f, p)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() to "image/png"
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
                        val file = path.startsWith("covers/")
                        val body = if (file) null else routes()[path]
                        val (bytes, type) = if (file) picture(path.substringAfter('/'))
                            else (body ?: """{"error":"not found"}""").toByteArray() to "application/json; charset=utf-8"
                        val out = c.getOutputStream()
                        out.write(("HTTP/1.1 ${if (file || body != null) "200 OK" else "404 Not Found"}\r\nContent-Type: $type\r\n" +
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
        val info = ins.uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ins.uiAutomation.serviceInfo = info
        ShotMode.on = true
    }

    @After fun tearDown() {
        sock.close()
    }

    private fun find(n: AccessibilityNodeInfo?, s: String): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.text?.toString() == s || n.contentDescription?.toString() == s) return n
        for (i in 0 until n.childCount) find(n.getChild(i), s)?.let { return it }
        return null
    }

    /** Knoten mit Text bzw. Beschreibung [s] in irgendeinem Fenster (Menue, Blatt) antippen */
    private fun tap(s: String) {
        val end = SystemClock.uptimeMillis() + 10_000
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
        throw AssertionError("nicht gefunden: $s")
    }

    private fun shot(name: String) {
        Thread.sleep(2500)
        ins.waitForIdleSync()
        val b: Bitmap = ins.uiAutomation.takeScreenshot()
        val f = File(dir, "titelbild-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 1000)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Seite zeigen, dann der Reihe nach antippen (null = nur Foto) und je Schritt ein Foto */
    private fun scene(dark: Boolean, steps: List<Pair<String?, String>>) {
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag("de")) }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg, androidx.activity.compose.LocalActivityResultRegistryOwner provides a) {
                        SagaTheme(dark = dark) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                PageScreen(7, app.repo, AppNavigator(listOf(Dest.Page(7))), Chrome(showMenu = true, onMenu = {}), appScope)
                            }
                        }
                    }
                }
            }
            Thread.sleep(4000)
            for ((target, name) in steps) {
                if (target != null) tap(target)
                shot(name)
            }
        }
    }

    @Test fun shots() {
        val device = if (ins.targetContext.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "handy"
        // vorher: Server ohne Galerie (wie jeder Server vor 2.5) - das Menue bleibt wie bisher
        coversOn = false
        app.repo.coverGallery.clear()
        scene(false, listOf("Titelbild" to "vorher-menue-$device-hell"))
        // nachher
        coversOn = true
        app.repo.coverGallery.clear()
        for (dark in listOf(false, true)) {
            val suf = device + "-" + if (dark) "dunkel" else "hell"
            scene(dark, listOf("Titelbild" to "nachher-menue-$suf", "Galerie …" to "nachher-galerie-$suf"))
            scene(dark, listOf("Titelbild" to "nachher-menue2-$suf", "Über dieses Bild" to "nachher-ueber-$suf"))
        }
    }
}
