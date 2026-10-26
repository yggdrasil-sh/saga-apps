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
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.settings.SettingsAccountScreen
import io.github.yggdrasil_sh.saga.ui.settings.SettingsGeneralScreen
import io.github.yggdrasil_sh.saga.ui.settings.SettingsInfoScreen
import io.github.yggdrasil_sh.saga.ui.settings.SettingsScreen
import io.github.yggdrasil_sh.saga.ui.settings.UpdateHintModel
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.ShotMode
import io.github.yggdrasil_sh.saga.util.UpdateHint
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
 * 2.5 Einstellungen (Konto · Einstellungen · Info): Bildschirmfotos mit Beispieldaten. Ein winziger HTTP-Server auf
 * dem Geraet (127.0.0.1, nur Debug-Build erlaubt http) liefert erfundene Antworten; Update-Cache vorgegeben (keine
 * Anfrage an GitHub). Ablage: getExternalFilesDir()/shots/settings-*.png
 */
@RunWith(AndroidJUnit4::class)
class Settings25ShotsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private lateinit var sock: ServerSocket

    private val now = System.currentTimeMillis() / 1000.0
    private val routes: Map<String, String> = mapOf(
        "api/version" to """{"name":"Saga","version":"2.5.0-rc.141","api":3}""",
        "api/account" to """{"username":"alex","name":"Alex Beispiel","can_edit":true}""",
        "api/account/security" to """{"supported":true,"enabled":true,"passkeys":[{}],"totp":false,"recovery_left":8}""",
        "api/account/public-name" to """{"name":"alex","enabled":true,"published":2}""",
        "api/account/backup" to """{"allowed":true,"ready":true,"last_ok":${now - 7200}}""",
        "api/settings/apps" to """{"devices":[{"id":1,"name":"Pixel (Beispiel)","created":${now - 86400 * 30},"last_used":$now,"current":true},""" +
            """{"id":2,"name":"MacBook (Beispiel)","created":${now - 86400 * 90},"last_used":${now - 3600}}],"keys":[]}""",
        "api/settings/nextcloud" to """{"enabled":true,"connected":true,"me":"alex","user":"alex"}""",
        "api/notifications/settings" to """{"kinds":[{"kind":"share.added","on":true},{"kind":"comment.new","on":true},{"kind":"chat.message","on":true},{"kind":"call.missed","on":false}]}""",
        "api/push" to """{"server":{"enabled":true,"unifiedpush":true},"settings":{},"categories":[],"devices":[]}""",
        "api/calls/config" to """{"enabled":true}""",
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
        UpdateHintModel.label = "2.5.0-rc.140"
        UpdateHintModel.preset(
            ins.targetContext,
            UpdateHint.Cache(
                System.currentTimeMillis() - 5 * 60_000,          // frisch: Info fragt nicht nach
                UpdateHint.Release(
                    "2.5.0-rc.141", "https://github.com/example/saga-apps/releases/tag/v2.5.0-rc.141",
                    mapOf("android" to "https://github.com/example/saga-apps/releases/download/v2.5.0-rc.141/Saga.apk"),
                ),
            ),
        )
    }

    @After fun tearDown() {
        sock.close()
    }

    private fun show(dest: Dest, name: String, dark: Boolean = false) {
        val nav = AppNavigator(listOf(Dest.Settings, dest).distinct())
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                a.setContent {
                    SagaTheme(dark = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            val chrome = Chrome(showMenu = true, onMenu = {})
                            when (dest) {
                                Dest.SettingsAccount -> SettingsAccountScreen(app.repo, nav, chrome)
                                Dest.SettingsGeneral -> SettingsGeneralScreen(app.repo, nav, chrome)
                                Dest.SettingsInfo -> SettingsInfoScreen(app.repo, nav, chrome)
                                else -> SettingsScreen(app.repo, nav, chrome)
                            }
                        }
                    }
                }
            }
            Thread.sleep(3500)                                // Antworten des Beispielservers und Bilder laden
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            val f = File(dir, "settings-$name.png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(f.length() > 1000)
        }
    }

    @Test fun shots() {
        show(Dest.Settings, "1-start")
        show(Dest.SettingsAccount, "2-konto")
        show(Dest.SettingsGeneral, "3-einstellungen")
        show(Dest.SettingsInfo, "4-info")
        show(Dest.SettingsInfo, "4-info-dunkel", dark = true)
    }
}
