package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.accessibility.AccessibilityNodeInfo
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
import io.github.yggdrasil_sh.saga.data.ServiceItem
import io.github.yggdrasil_sh.saga.ui.services.BookmarkDialog
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.ShotMode
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
import kotlin.concurrent.thread

/**
 * Bugs-DB 2460: Dialog "Lesezeichen bearbeiten" fuer ein Lesezeichen mit eigenem Logo (icon = /files/<id>) - Bildschirmfoto
 * mit Beispieldaten (winziger HTTP-Server auf dem Geraet liefert das Logo unter icon_url). Laeuft unveraendert auch auf
 * dem alten Stand (vorher: Pfad im Feld). Nachher zusaetzlich nach "Logo entfernen" (Feld wieder frei).
 * Texte deutsch: vorher `cmd locale set-app-locales io.github.yggdrasil_sh.saga.debug --locales de-DE`.
 * Ablage: getExternalFilesDir()/shots/2460-*.png
 */
@RunWith(AndroidJUnit4::class)
class BookmarkLogo2460ShotsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private lateinit var sock: ServerSocket

    private val routes: Map<String, String> = mapOf(
        "api/version" to """{"name":"Saga","version":"2.6.0","api":3,"features":[]}""",
        "api/account" to """{"username":"alex","name":"Alex Beispiel","can_edit":true}""",
    )

    /** Beispiel-Logo: runde Plakette mit "B" */
    private fun logo(): ByteArray {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawCircle(80f, 80f, 78f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D6B.toInt() })
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 96f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("B", 80f, 80f - (t.descent() + t.ascent()) / 2, t)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before fun setUp() {
        val png = logo()
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
                        val (code, type, bytes) = when {
                            path == "api/bookmarks/7/logo" -> Triple("200 OK", "image/png", png)
                            routes[path] != null -> Triple("200 OK", "application/json", routes[path]!!.toByteArray())
                            else -> Triple("404 Not Found", "application/json", """{"error":"not found"}""".toByteArray())
                        }
                        val out = c.getOutputStream()
                        out.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(bytes)
                        out.flush()
                    }
                }
            }
        }
        runBlocking {
            app.repo.awaitReady()
            val server = "http://127.0.0.1:${sock.localPort}"
            app.repo.secure.setCredentials(server, "hkd_beispiel", null)
            app.repo.settings.setServer(server)
            app.repo.settings.setAccount("Alex Beispiel")
            app.repo.needsLogin.value = false
        }
        ShotMode.on = true
    }

    @After fun tearDown() { sock.close() }

    private fun save(name: String) {
        ins.waitForIdleSync()
        val b: Bitmap = ins.uiAutomation.takeScreenshot()
        val f = File(dir, "2460-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 1000)
    }

    private fun find(n: AccessibilityNodeInfo?, p: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        n ?: return null
        if (p(n)) return n
        for (i in 0 until n.childCount) find(n.getChild(i), p)?.let { return it }
        return null
    }

    private fun anyWindow(p: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        ins.uiAutomation.windows.firstNotNullOfOrNull { find(it.root, p) } ?: find(ins.uiAutomation.rootInActiveWindow, p)

    @Test fun dialogShots() {
        val item = ServiceItem(
            name = "Router", url = "https://router.example.org", host = "router.example.org", category = "Lesezeichen",
            icon = "/files/0123456789abcdef01234567", own = true, key = "bm7", iconUrl = "/api/bookmarks/7/logo?v=01234567",
        )
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dunkel" else "hell"
            ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
                sc.onActivity { a ->
                    a.enableEdgeToEdge()
                    a.setContent {
                        SagaTheme(dark = dark) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                BookmarkDialog(app.repo, item, "Lesezeichen", onDismiss = {}, onChanged = {})
                            }
                        }
                    }
                }
                Thread.sleep(2500)
                save("dialog-$mode")
                // nachher: "Logo entfernen" (gibt es auf dem alten Stand nicht)
                val rm = anyWindow { it.contentDescription?.toString() == "Logo entfernen" }
                if (rm != null) {
                    val btn = generateSequence(rm) { it.parent }.firstOrNull { it.isClickable } ?: rm
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(1200)
                    save("entfernt-$mode")
                }
            }
        }
    }
}
