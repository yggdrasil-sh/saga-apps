package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallPeer
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.HoldBar
import io.github.yggdrasil_sh.saga.call.HoldCallBar
import io.github.yggdrasil_sh.saga.call.HoldFlow
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.settings.ReachScreen
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
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
 * Bugs-DB 2393: Bildschirmfotos mit Beispieldaten (ein winziger HTTP-Server auf dem Geraet, nur Debug-Build erlaubt http).
 *  - reach-*: Erreichbarkeit › Ausnahme hinzufuegen - Treffer einer Person eines anderen Servers (Host nur in der Unterzeile)
 *    und direkte Eingabe einer Kennung mit IPv6-Host ('~x@[2001:db8::1]').
 *  - hold-*: stille Leiste nach dem Auflegen eines Gruppenanrufs, in dem ein zweites eigenes Geraet weiter spricht - Entscheidung
 *    mit HoldFlow.freed/busyElsewhere/rechecked (wie CallManager.holdFreed); "vorher" = altes Ueberspringen des beendeten Anrufs.
 * Ablage: getExternalFilesDir()/shots/2393-*.png
 */
@RunWith(AndroidJUnit4::class)
class Reach2393ShotsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private lateinit var sock: ServerSocket
    private val h16 = "abcdefghijklmnop"

    private val routes: Map<String, String> = mapOf(
        "api/version" to """{"name":"Saga","version":"2.6.0-rc.1","api":3,"features":["userstatus1"]}""",
        "api/account" to """{"username":"alex","name":"Alex Beispiel","can_edit":true}""",
        "api/account/status" to """{"ver":3,"state":"dnd","source":"manual","effective":"dnd","prefs":{"quiet_dnd":true,"repeat":true,"repeat_s":180},""" +
            """"limits":{"text":80,"exceptions":100,"repeat_s":[30,3600]},"exceptions":[]}""",
        "api/calls/config" to """{"enabled":true}""",
    )
    @Volatile private var targets = "{}"

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
                        val body = if (path == "api/share/targets") targets else routes[path]
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

    @After fun tearDown() {
        sock.close()
        app.repo.calls.hold.value = null
    }

    private fun save(name: String) {
        ins.waitForIdleSync()
        val b: Bitmap = ins.uiAutomation.takeScreenshot()
        val f = File(dir, "2393-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 1000)
    }

    private fun find(n: AccessibilityNodeInfo?, p: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        n ?: return null
        if (p(n)) return n
        for (i in 0 until n.childCount) find(n.getChild(i), p)?.let { return it }
        return null
    }

    private fun scroll(times: Int) = repeat(times) {
        find(ins.uiAutomation.rootInActiveWindow) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        Thread.sleep(400)
    }

    /** Erreichbarkeit: bis zum Suchfeld blaettern, [query] eintragen (SetText, ohne Tastatur), Treffer zeigen */
    private fun search(query: String, name: String) {
        val nav = AppNavigator(listOf(Dest.Settings, Dest.Reach))
        app.repo.serverFeatures.value = app.repo.serverFeatures.value + "userstatus1"   // Erreichbarkeit nur mit userstatus1
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                a.setContent {
                    SagaTheme(dark = false) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            ReachScreen(app.repo, nav, Chrome(showMenu = true, onMenu = {}))
                        }
                    }
                }
            }
            Thread.sleep(2500)
            var field: AccessibilityNodeInfo? = null
            for (i in 0 until 12) {
                field = find(ins.uiAutomation.rootInActiveWindow) { it.isEditable }
                if (field != null) break
                scroll(1)
            }
            assertTrue("Suchfeld nicht gefunden", field != null)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
            field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Thread.sleep(1800)                               // 250 ms Verzoegerung + Antwort des Beispielservers
            scroll(3)
            Thread.sleep(600)
            save(name)
        }
    }

    @Test fun reachShots() {
        targets = """{"users":[],"groups":[],"remote":[{"type":"remote","target":"@~$h16@b.example","handle":"~$h16@b.example",""" +
            """"name":"Bert Gast","host":"b.example","pub":null,"label":"Bert Gast · b.example","avatar":null}]}"""
        search("bert", "reach-1-foederiert")
        targets = """{"users":[],"groups":[]}"""
        search("~$h16@[2001:db8::1]", "reach-2-ipv6")
    }

    @Test fun holdShots() {
        val calls = app.repo.calls
        val waitId = "fedcba9876543210fedcba9876543210"
        val ended = "0123456789abcdef0123456789abcdef"
        val info = CallInfo(id = waitId, chatId = 1, media = "audio", state = "ringing", role = "callee", peer = CallPeer("bruno", "Bruno Beispiel"), ringS = 45)
        val here = HoldBar(waitId, 1, HoldBar.Mode.QUIET, info.peer, false, "dnd", true, false, info, 45.0, System.currentTimeMillis())
        // Gruppenanruf hier aufgelegt, das zweite eigene Geraet (dev2) spricht weiter - so meldet es GET /api/calls/active
        val active = listOf(CallInfo(id = ended, chatId = 2, state = "active", kind = CallRoom.KIND_GROUP, role = "member", mine = listOf("dev2")))
        val waiting = (HoldFlow.freed(here, taking = false, nowMs = System.currentTimeMillis()) as HoldFlow.Freed.Recheck).bar
        val before = HoldFlow.busyElsewhere(active, setOf(waitId, null, ended))          // alter Weg: beendeter Anruf uebersprungen
        val after = HoldFlow.busyElsewhere(active, setOf(waitId, null), ended, "dev1")
        for ((tag, b) in listOf("vorher" to before, "nachher" to after)) {
            ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
                sc.onActivity { a ->
                    a.enableEdgeToEdge()
                    a.setContent {
                        SagaTheme(dark = false) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                Column(Modifier.statusBarsPadding()) {
                                    HoldCallBar(calls)
                                    Text(
                                        "Beispiel: Gruppenanruf hier aufgelegt, zweites eigenes Gerät noch im Anruf ($tag)",
                                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                calls.hold.value = HoldFlow.rechecked(waiting, waitId, b)
                Thread.sleep(1500)
                save("hold-$tag")
            }
        }
        assertTrue("vorher faelschlich frei", !before)
        assertTrue("nachher belegt", after)
    }
}
