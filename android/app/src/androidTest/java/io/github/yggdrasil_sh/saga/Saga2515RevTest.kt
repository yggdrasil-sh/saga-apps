package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.saga.SagaJobScreen
import io.github.yggdrasil_sh.saga.ui.saga.SagaScreen
import io.github.yggdrasil_sh.saga.ui.saga.SagaSettingsScreen
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.ShotMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Bugs-DB 2515 gegen einen echten Server (Gegenprobe, eigenes Test-AVD): Plan-Karte nach 409 stale ("geändert – bitte neu
 * freigeben") und 409 state (anderswo freigegeben: neu laden, "Passt, los" weg), Neuholen beim Wiederverbinden (Live-
 * Ereignis verloren), 409 changed bleibt nach neuem Aufbau der Ansicht (wie Drehen auf zwei Spalten), "Alte Fassung
 * ansehen" nur mit version_url, Hinweis "Verlauf an/aus" nach Einstellungen › Saga. Dazu Bilder der umrandeten Knoepfe
 * auf der KI-Karte (dunkel). "Anderswo" = dieselben Serverwege am Bildschirm vorbei (wie ein zweites Geraet).
 * Aufruf: -e server http://127.0.0.1:<port> -e anna <hkd_> -e bert <hkd_> -e cora <hkd_> -e stale <job> -e state <job>
 * -e resync <job> -e undo <job> (Auftraege wartet_ok bzw. undo erledigt mit Aufgabe; resync als letzter Zug im Gespraech).
 * Bilder: getExternalFilesDir()/shots/s2515-*.png
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Saga2515RevTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val repo get() = app.repo
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private val args get() = InstrumentationRegistry.getArguments()
    private fun s(id: Int) = ins.targetContext.getString(id)

    private fun login(who: String) {
        val server = args.getString("server")
        val token = args.getString(who)
        assumeTrue("ohne -e server/-e $who", !server.isNullOrBlank() && !token.isNullOrBlank())
        runBlocking {
            repo.awaitReady()
            repo.api.cookies.clear()                                      // Sitzung des vorigen Kontos weg (Anmeldung neu ueber den Schluessel)
            repo.secure.setCredentials(server!!, token!!, null)
            repo.settings.setServer(server)
            repo.settings.setAccount("Konto $who")
            repo.needsLogin.value = false
            repo.checkVersion()
            repo.ai.loadStatus()
        }
        ShotMode.on = true
    }

    private fun job(k: String): Long = args.getString(k)?.toLongOrNull().also { assumeTrue("ohne -e $k", it != null) }!!

    private fun find(timeoutMs: Long = 8000, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val roots = ins.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(ins.uiAutomation.rootInActiveWindow)
            for (r in roots) walk(r, pred)?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun count(pred: (AccessibilityNodeInfo) -> Boolean): Int {
        var n = 0
        fun w(x: AccessibilityNodeInfo) {
            if (pred(x)) n++
            for (i in 0 until x.childCount) x.getChild(i)?.let(::w)
        }
        ins.uiAutomation.rootInActiveWindow?.let(::w)
        return n
    }

    private fun walk(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> walk(c, pred)?.let { return it } }
        return null
    }

    /** Gespraech ans Ende rollen (die LazyColumn zeigt nicht immer den letzten Zug) */
    private fun scrollToEnd() {
        repeat(12) {
            val list = ins.uiAutomation.rootInActiveWindow?.let { r -> walk(r) { it.isScrollable } } ?: return
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
            Thread.sleep(300)
        }
    }

    private fun text(t: String): (AccessibilityNodeInfo) -> Boolean = { it.text?.toString() == t }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var p: AccessibilityNodeInfo? = n
        while (p != null && !p.isClickable) p = p.parent
        return p ?: n
    }

    private fun tap(t: String) {
        val n = find(pred = text(t))
        assertNotNull("nicht gefunden: $t", n)
        assertTrue("Klick: $t", clickable(n!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun enabled(t: String): Boolean? = find(2000, text(t))?.let { clickable(it).isEnabled }

    /** nur das aktive Fenster (alte Fenster frueherer Bildschirme koennen noch in windows stehen) */
    private fun activeEnabled(t: String): Boolean? =
        ins.uiAutomation.rootInActiveWindow?.let { r -> walk(r, text(t)) }?.let { clickable(it).isEnabled }

    /** Live-Kanal zu und ohne Nachholen beim naechsten Verbinden (Last-Event-ID vergessen) - so geht ein Ereignis verloren */
    private fun liveOff() {
        repo.live.clear()
        Thread.sleep(500)
    }

    private fun save(name: String) {
        Thread.sleep(1200)
        ins.waitForIdleSync()
        val b: Bitmap = ins.uiAutomation.takeScreenshot()
        val f = File(dir, "s2515-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 1000)
    }

    private fun launch(dark: Boolean = false, content: @Composable () -> Unit, block: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                a.setContent { SagaTheme(dark = dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
            }
            Thread.sleep(3000)
            block()
        }
    }

    private val chrome = Chrome(showMenu = false, onMenu = {})

    /** 409 stale: anderswo geaendert, hier "Passt, los" -> neuer Plan mit "geändert – bitte neu freigeben" */
    @Test fun a_staleShowsChangedHint() {
        login("anna")
        val id = job("stale")
        try {
            launch(content = { SagaJobScreen(repo, AppNavigator(listOf(Dest.Saga, Dest.SagaJob(id))), chrome, id) }) {
                assertNotNull("Plan-Karte", find(pred = text(s(R.string.saga_go_ahead))))
                liveOff()                                                 // ai.job.changed kaeme sonst vor dem Tippen
                assertFalse("vorher kein Hinweis", find(1500, text(s(R.string.saga_edited))) != null)
                runBlocking { repo.saga.editPlan(repo.saga.job(id).plan!!, mapOf(0 to mapOf("text" to "Anderswo geändert"))) }
                tap(s(R.string.saga_go_ahead))
                val hint = find(8000, text(s(R.string.saga_edited)))
                save("1-stale-hinweis")
                assertNotNull("nach 409 stale: Hinweis am neuen Plan", hint)
                assertTrue("neue Fassung freigebbar", enabled(s(R.string.saga_go_ahead)) == true)
            }
        } finally {
            runBlocking { runCatching { repo.saga.cancel(id) } }
            repo.live.foreground(true)
        }
    }

    /** 409 state: anderswo freigegeben, hier "Passt, los" -> Stand neu, kein aktives "Passt, los" mehr */
    @Test fun b_stateReloadsAndDisables() {
        login("anna")
        val id = job("state")
        try {
            launch(content = { SagaJobScreen(repo, AppNavigator(listOf(Dest.Saga, Dest.SagaJob(id))), chrome, id) }) {
                assertNotNull("Plan-Karte", find(pred = text(s(R.string.saga_go_ahead))))
                liveOff()
                runBlocking { repo.saga.approve(repo.saga.job(id).plan!!) }
                tap(s(R.string.saga_go_ahead))
                var still: Boolean? = true
                val end = System.currentTimeMillis() + 6000
                while (System.currentTimeMillis() < end && still == true) {
                    Thread.sleep(300)
                    still = activeEnabled(s(R.string.saga_go_ahead))
                }
                val st = runBlocking { repo.saga.job(id) }
                val shown = find(3000) { it.text?.toString()?.startsWith(st.stateText) == true } != null
                save("2-state-neu-geladen")
                assertTrue("\"Passt, los\" weg bzw. aus (war $still)", still != true)
                assertTrue("Zustand ${st.stateText} sichtbar", shown)
            }
        } finally {
            repo.live.foreground(true)
        }
    }

    /** Live-Ereignis verloren (Kanal zu), anderswo freigegeben -> beim Wiederverbinden einmal neu holen (Gespraech) */
    @Test fun c_resyncOnReconnect() {
        login("anna")
        val id = job("resync")
        launch(content = { SagaScreen(repo, AppNavigator(listOf(Dest.Saga)), chrome) }) {
            Thread.sleep(2000)
            scrollToEnd()
            assertNotNull("Plan-Karte im Gespraech", find(10000, text(s(R.string.saga_go_ahead))))
            liveOff()
            Thread.sleep(1000)
            runBlocking { repo.saga.approve(repo.saga.job(id).plan!!) }
            Thread.sleep(3000)
            scrollToEnd()
            val before = count(text(s(R.string.saga_go_ahead)))
            save("3-resync-vorher")
            repo.live.foreground(true)
            var gone = false
            val end = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < end && !gone) {
                Thread.sleep(500)
                gone = count(text(s(R.string.saga_go_ahead))) < before
            }
            save("3-resync-nachher")
            assertTrue("vorher stand die Karte noch ($before)", before >= 1)
            assertTrue("nach dem Wiederverbinden: Plan-Karte durch den neuen Stand ersetzt", gone)
        }
    }

    /** Rueckgaengig mit 409 changed (Aufgabe anderswo geaendert): Hinweis ohne "Alte Fassung", bleibt nach neuem Aufbau */
    @Test fun d_undoConflictSurvivesRebuild() {
        login("cora")
        val id = job("undo")
        val nav = AppNavigator(listOf(Dest.Saga))
        launch(dark = true, content = { SagaScreen(repo, nav, chrome) }) {
            Thread.sleep(2000)
            scrollToEnd()
            assertNotNull("Rückgängig", find(10000, text(s(R.string.saga_undo))))
            save("4-rueckgaengig-dunkel")
            val tid = runBlocking { repo.saga.job(id).changes.first { it.target.startsWith("task:") }.target.substringAfter(':').toInt() }
            runBlocking { repo.updateTask(tid, "Anderswo geändert", null, 2) }
            tap(s(R.string.saga_undo))
            find(3000, text(s(R.string.saga_delete)))?.let { clickable(it).performAction(AccessibilityNodeInfo.ACTION_CLICK) }   // "nur löschbar": Rueckfrage
            assertNotNull("409 changed: Hinweis", find(8000, text(s(R.string.saga_changed_since))))
            Thread.sleep(2500)                                            // Detail neu geladen (why vom Server)
            save("4-konflikt")
            assertTrue("Aufgabe: keine alte Fassung", activeEnabled(s(R.string.saga_old_view)) == null)
        }
        // neuer Aufbau mit demselben Zustand des Orts (wie Drehen auf zwei Spalten): Hinweis bleibt, kein "Rückgängig"
        launch(dark = true, content = { SagaScreen(repo, nav, chrome) }) {
            scrollToEnd()
            assertNotNull("nach neuem Aufbau: Hinweis", find(8000, text(s(R.string.saga_changed_since))))
            assertFalse("nach neuem Aufbau kein Rückgängig", find(1500, text(s(R.string.saga_undo))) != null)
            save("4-konflikt-neu-aufgebaut")
        }
    }

    /** Kennenlernen-Karte (dunkel): umrandeter Knopf "Ja, ohne Ideen" */
    @Test fun e_introDark() {
        login("bert")
        launch(dark = true, content = { SagaScreen(repo, AppNavigator(listOf(Dest.Saga)), chrome) }) {
            assertNotNull("Ja, ohne Ideen", find(10000, text(s(R.string.saga_yes_no_ideas))))
            scrollToEnd()
            save("5-kennenlernen-dunkel")
        }
    }

    /** Verlauf in Einstellungen › Saga umschalten -> Hinweis im Gespraech stimmt sofort */
    @Test fun f_historyHintAfterSettings() {
        login("anna")
        val nav = AppNavigator(listOf(Dest.Saga))
        var on = false
        launch(content = { SagaScreen(repo, nav, chrome) }) {
            Thread.sleep(2000)
            scrollToEnd()
            on = find(10000, text(s(R.string.saga_keep_on))) != null
            assertTrue("Hinweis da", on || find(3000, text(s(R.string.saga_keep_off))) != null)
        }
        launch(content = { SagaSettingsScreen(repo, AppNavigator(listOf(Dest.Saga, Dest.SettingsSaga)), chrome) }) {
            tap(s(R.string.saga_history))
            Thread.sleep(2500)
        }
        launch(content = { SagaScreen(repo, nav, chrome) }) {
            scrollToEnd()
            val want = if (on) R.string.saga_keep_off else R.string.saga_keep_on
            val ok = find(8000, text(s(want))) != null
            save("6-verlauf-hinweis")
            assertTrue("Hinweis nach Umschalten: ${s(want)}", ok)
        }
    }
}
