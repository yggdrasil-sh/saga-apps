package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.chat.BookContact
import io.github.yggdrasil_sh.saga.ui.chat.ChatsScreen
import io.github.yggdrasil_sh.saga.ui.chat.PersonHit
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.settings.ContactDetailScreen
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.ShotMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pruefung k27arev (2027a-6): die zwei Wege, die k27a nicht im Emulator angetippt hat - "Kontakt bearbeiten …" im
 * Kontakt-Menue (PersonHit/MoreRemove -> ContactEditFor) und "Nachricht" im Detail (createDm -> Dest.Chat).
 * Tippen ueber die Bedienungshilfen-Knoten (Texte/Beschriftungen wie TalkBack). Nur mit -e server/-e token.
 * Ablage: getExternalFilesDir()/shots/kontakte-rev-*.png
 */
@RunWith(AndroidJUnit4::class)
class Contacts2027RevTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as SagaApplication
    private val dir: File get() = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private val args get() = InstrumentationRegistry.getArguments()

    @Before fun setUp() {
        val server = args.getString("server")
        val token = args.getString("token")
        assumeTrue("ohne -e server/-e token", !server.isNullOrBlank() && !token.isNullOrBlank())
        val repo = app.repo
        runBlocking {
            repo.awaitReady()
            repo.secure.setCredentials(server!!, token!!, null)
            repo.settings.setServer(server)
            repo.settings.setAccount("Alex Beispiel")
            repo.needsLogin.value = false
            repo.checkVersion()
        }
        ShotMode.on = true
    }

    private fun anna(): BookContact = runBlocking { app.repo.chat.book.list("Anna").contacts.first { it.name == "Anna Berg" } }

    private fun find(pred: (AccessibilityNodeInfo) -> Boolean, timeoutMs: Long = 8000): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val roots = ins.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(ins.uiAutomation.rootInActiveWindow)
            for (r in roots) walk(r, pred)?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun walk(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> walk(c, pred)?.let { return it } }
        return null
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var p: AccessibilityNodeInfo? = n
        while (p != null && !p.isClickable) p = p.parent
        return p ?: n
    }

    private fun tap(what: String, pred: (AccessibilityNodeInfo) -> Boolean) {
        val n = find(pred)
        assertNotNull("nicht gefunden: $what", n)
        assertTrue("Klick: $what", clickable(n!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun text(n: AccessibilityNodeInfo) = n.text?.toString().orEmpty()
    private fun desc(n: AccessibilityNodeInfo) = n.contentDescription?.toString().orEmpty()

    private fun save(name: String) {
        Thread.sleep(1500)
        ins.waitForIdleSync()
        val b: Bitmap = ins.uiAutomation.takeScreenshot()
        val f = File(dir, "kontakte-rev-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 1000)
    }

    private fun launch(content: @Composable () -> Unit, block: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                a.setContent { SagaTheme(dark = false) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
            }
            Thread.sleep(2500)
            block()
        }
    }

    @Test fun c_menuEditContact() {
        val a = anna()
        val ctx = ins.targetContext
        val actions = ctx.getString(R.string.gi_actions, "Anna Berg")
        val edit = ctx.getString(R.string.cb_edit_contact)
        launch({ Column { PersonHit(app.repo, "Anna Berg", "Anna Berg", null, a.saga?.avatar, contactRef = a.saga!!.ref, contactId = a.id) {} } }) {
            tap(actions) { desc(it) == actions }
            save("7-menue")
            tap(edit) { text(it) == edit }
            // Vollbild-Dialog mit Annas Stand (Grussform aus dem Server)
            assertNotNull("Dialog mit Grussform", find({ text(it) == a.salutation }))
            save("8-menue-bearbeiten")
        }
    }

    @Test fun d_detailMessage() {
        val a = anna()
        val nav = AppNavigator(listOf(Dest.Settings, Dest.Contacts, Dest.Contact(a.id)))
        val chrome = Chrome(showMenu = true, onMenu = {})
        val msg = ins.targetContext.getString(R.string.cb_message)
        launch({
            when (val d = nav.current) {
                is Dest.Chat -> ChatsScreen(app.repo, nav, chrome, d.id)
                is Dest.Contact -> ContactDetailScreen(app.repo, nav, chrome, d.id)
                else -> {}
            }
        }) {
            tap(msg) { text(it) == msg }
            val end = System.currentTimeMillis() + 10000
            while (nav.current !is Dest.Chat && System.currentTimeMillis() < end) Thread.sleep(200)
            assertTrue("nach Nachricht: ${nav.current}", nav.current is Dest.Chat && (nav.current as Dest.Chat).id > 0)
            Thread.sleep(2500)
            save("9-nachricht-chat")
        }
    }
}
