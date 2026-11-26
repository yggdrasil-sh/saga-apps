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
import io.github.yggdrasil_sh.saga.data.TodayLine
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.ui.today.TodayScreen
import io.github.yggdrasil_sh.saga.util.ShotMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Bugs-DB 2271 K-D gegen einen echten Server (Gegenprobe, Wegwerf-AVD): Abruf mit aitoday1, ETag/304, Live
 * `ai.today.changed` nach neuer Aufgabe (ohne eigenes Neuladen), ✕ -> Ruhe, „Wieder an“, drittes ✕ -> Frage, „Nein“,
 * ausblenden/einblenden (Wartet auf dich bleibt), Widget-Zeile ueber die Sitzung; Bilder von „Heute“ (hell/dunkel).
 * Aufruf: -e server http://127.0.0.1:<port> -e anna <hkd_> (Konto mit Faehigkeit ai, Aufgaben, 1 wartender Auftrag).
 * Bilder: getExternalFilesDir()/shots/2271-echt-*.png
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TodayLine2271RevTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val repo get() = app.repo
    private val svc get() = repo.todayLine
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private val args get() = InstrumentationRegistry.getArguments()

    private fun login() {
        val server = args.getString("server")
        val token = args.getString("anna")
        assumeTrue("ohne -e server/-e anna", !server.isNullOrBlank() && !token.isNullOrBlank())
        runBlocking {
            repo.awaitReady()
            repo.api.cookies.clear()
            repo.secure.setCredentials(server!!, token!!, null)
            repo.settings.setServer(server)
            repo.settings.setAccount("Anna")
            repo.needsLogin.value = false
            repo.checkVersion()
        }
    }

    private fun waitFor(ms: Long = 8000, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (ok()) return true; Thread.sleep(100) }
        return ok()
    }

    @Test fun a_flow() {
        login()
        assertTrue("Server meldet aitoday1", TodayLine.FEATURE in repo.serverFeatures.value)
        runBlocking { svc.load() }
        val s0 = svc.state.value
        assertNotNull("Stand", s0)
        val show0 = TodayLine.show(s0)
        assertEquals(listOf("over", "due"), show0.kurz?.parts?.map { it.kind })
        assertTrue("Wartet auf dich", show0.waiting?.text?.isNotEmpty() == true)
        // gleicher Stand: 304 behaelt ihn
        runBlocking { svc.load() }
        assertEquals(s0, svc.state.value)

        // Live: neue Aufgabe ueber die API -> ai.today.changed -> entprellt neu laden (ohne load())
        app.repo.live.foreground(true)
        assertTrue("Live verbunden", waitFor(10000) { repo.live.connected.value })
        runBlocking { repo.api.post("api/tasks", buildJsonObject { put("text", "Elternabend vorbereiten"); put("due", java.time.LocalDate.now().toString()); put("prio", 1) }) }
        assertTrue("Live: neuer Stand", waitFor(8000) { svc.state.value?.v != s0!!.v })
        assertNotEquals(s0!!.counts?.due, svc.state.value?.counts?.due)

        // ✕ -> Pille, Wieder an -> Zeile; das folgende ai.today.changed (v in Konto-Zone) laedt neu - Pille muss bleiben
        runBlocking { svc.quiet() }
        assertNotNull("Pille", TodayLine.show(svc.state.value).pill)
        Thread.sleep(2500)
        assertNotNull("Pille bleibt nach Live-Neuladen (304)", TodayLine.show(svc.state.value).pill)
        assertNull(TodayLine.show(svc.state.value).kurz)
        assertNotNull("Wartet bleibt bei Ruhe", TodayLine.show(svc.state.value).waiting)
        runBlocking { svc.unquiet() }
        assertNotNull("Zeile wieder da", TodayLine.show(svc.state.value).kurz)
        // drittes ✕ in 14 Tagen -> einmal die Frage; „Nein, passt“ -> Pille
        runBlocking { svc.quiet(); svc.unquiet(); svc.quiet() }
        assertNotNull("Frage", TodayLine.show(svc.state.value).ask)
        runBlocking { svc.answer(false) }
        assertNull("Frage weg", TodayLine.show(svc.state.value).ask)
        assertNotNull("Pille nach Nein", TodayLine.show(svc.state.value).pill)
        runBlocking { svc.unquiet() }
        // ausblenden: keine Zeile, Wartet bleibt; einblenden
        runBlocking { svc.setOn(false) }
        assertEquals(false, svc.state.value?.on)
        assertNull(TodayLine.show(svc.state.value).kurz)
        assertNotNull(TodayLine.show(svc.state.value).waiting)
        runBlocking { svc.setOn(true) }
        assertNotNull(TodayLine.show(svc.state.value).kurz)

        // Widget-Abruf ueber die Sitzung (ohne App-Schluessel): Zeile fuer Mittel/Gross
        runBlocking { repo.widgetTasksWithSources() }
        assertNotNull("Widget-Zeile", TodayLine.widgetText(repo.widgetLine, io.github.yggdrasil_sh.saga.data.TodayWidgetSize.MEDIUM))
    }

    private fun shot(name: String, dark: Boolean) {
        val nav = AppNavigator(listOf(Dest.Today))
        ShotMode.on = true
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
            Thread.sleep(4000)
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            val f = File(dir, "2271-echt-$name.png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(f.length() > 1000)
        }
    }

    @Test fun b_shots() {
        login()
        shot("heute-hell", dark = false)
        shot("heute-dunkel", dark = true)
        runBlocking { svc.quiet() }
        shot("heute-ruhe-hell", dark = false)
        runBlocking { svc.unquiet() }
    }
}
