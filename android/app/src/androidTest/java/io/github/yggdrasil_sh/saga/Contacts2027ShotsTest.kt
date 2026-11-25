package io.github.yggdrasil_sh.saga

import android.graphics.Bitmap
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
import io.github.yggdrasil_sh.saga.chat.BookContact
import io.github.yggdrasil_sh.saga.chat.ContactBook
import io.github.yggdrasil_sh.saga.chat.ContactBookLogic
import io.github.yggdrasil_sh.saga.chat.ContactBookLogic.AddrDraft
import io.github.yggdrasil_sh.saga.chat.ContactBookLogic.Draft
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.settings.ContactDetailScreen
import io.github.yggdrasil_sh.saga.ui.settings.ContactEditDialog
import io.github.yggdrasil_sh.saga.ui.settings.ContactsScreen
import io.github.yggdrasil_sh.saga.ui.settings.SettingsScreen
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.ShotMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 2.6 (Bugs-DB 2027a-6): Adressbuch gegen einen echten Testserver (Stand mit contacts2) - Ablauf der App-Schicht
 * (Anlegen mit nationaler Nummer + cc, Aendern, 409 veraltet, 409 doppelt, Loeschen) und Bildschirmfotos
 * (Einstellungen, Liste, Detail, Bearbeiten; hell/dunkel). Nur mit Argumenten, sonst uebersprungen:
 *   -e server http://127.0.0.1:<port> -e token hkd_…   (Emulator: adb reverse auf den Testserver)
 * Ablage: getExternalFilesDir()/shots/kontakte-*.png
 */
@RunWith(AndroidJUnit4::class)
class Contacts2027ShotsTest {
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

    private fun shot(name: String, dark: Boolean, wait: Long = 3500, content: @Composable (AppNavigator, Chrome) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                a.setContent {
                    SagaTheme(dark = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            content(AppNavigator(listOf(Dest.Settings, Dest.Contacts)), Chrome(showMenu = true, onMenu = {}))
                        }
                    }
                }
            }
            Thread.sleep(wait)
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            val f = File(dir, "kontakte-$name${if (dark) "-dunkel" else ""}.png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(f.length() > 1000)
        }
    }

    private fun anna(): BookContact = runBlocking { app.repo.chat.book.list("Anna").contacts.first { it.name == "Anna Berg" } }

    @Test fun a_flow() = runBlocking {
        val book = app.repo.chat.book
        assertTrue("contacts2", app.repo.chat.hasContactBook())
        val before = book.list().total
        // national mit cc -> E.164
        val r = book.create(Draft(ownName = "Test Wegwerf", phones = listOf(AddrDraft("0170 999 0001", "work")), mails = listOf(AddrDraft("Weg@Example.ORG"))), "49")
        assertTrue(r.toString(), r is ContactBook.Saved.Ok)
        val c = (r as ContactBook.Saved.Ok).contact
        assertEquals(listOf("+491709990001"), c.phones.map { it.value })
        assertEquals(listOf("Weg@example.org"), c.mails.map { it.value })
        assertEquals(before + 1, book.list().total)
        assertEquals(c.id, book.list("999 0001").contacts.single().id)            // Ziffernsuche
        // aendern: Anrede, Typ
        val u = book.update(c, ContactBookLogic.draftOf(c).copy(form = "formal", phones = listOf(AddrDraft("+491709990001", "mobile"))), null)
        val c2 = (u as ContactBook.Saved.Ok).contact
        assertEquals("formal", c2.form)
        assertEquals("mobile", c2.phones.single().type)
        assertEquals(c.phones.single().id, c2.phones.single().id)                 // gleicher Wert behaelt id
        // veraltet -> Conflict mit aktuellem Stand
        val k = book.update(c, ContactBookLogic.draftOf(c).copy(salutation = "Hallo"), null)
        assertTrue(k.toString(), k is ContactBook.Saved.Conflict && (k as ContactBook.Saved.Conflict).current.ver == c2.ver)
        // doppelt -> nur contact_id
        val a = anna()
        val d = book.create(Draft(ownName = "Doppelt", phones = listOf(AddrDraft(a.phones.first().value))), null)
        assertTrue(d.toString(), d is ContactBook.Saved.Duplicate && (d as ContactBook.Saved.Duplicate).contactId == a.id)
        // feste Saga-Adresse bleibt beim Aendern
        val a2 = book.update(a, ContactBookLogic.draftOf(a).copy(salutation = a.salutation.orEmpty() + "!"), null)
        val a3 = (a2 as ContactBook.Saved.Ok).contact
        assertEquals(a.saga!!.ref, a3.addrs.first { it.fixed }.value)
        book.update(a3, ContactBookLogic.draftOf(a3).copy(salutation = a.salutation.orEmpty()), null)
        // loeschen
        book.remove(c.id)
        assertEquals(null, book.one(c.id))
        assertEquals(before, book.list().total)
        // Landesvorwahl
        assertEquals(null, book.cc())
    }

    @Test fun b_shots() {
        for (dark in listOf(false, true)) {
            shot("1-einstellungen", dark) { nav, chrome -> SettingsScreen(app.repo, nav, chrome) }
            shot("2-liste", dark) { nav, chrome -> ContactsScreen(app.repo, nav, chrome) }
            val a = anna()
            shot("3-detail", dark) { nav, chrome -> ContactDetailScreen(app.repo, nav, chrome, a.id) }
            shot("4-bearbeiten", dark) { _, _ -> ContactEditDialog(app.repo, a, "49", onDismiss = {}, onOpen = {}) {} }
            shot("5-neu", dark) { _, _ -> ContactEditDialog(app.repo, null, "49", onDismiss = {}, onOpen = {}) {} }
            val m = runBlocking { app.repo.chat.book.list("Mara").contacts.firstOrNull() }
            assertNotNull(m)
            shot("6-detail-manuell", dark) { nav, chrome -> ContactDetailScreen(app.repo, nav, chrome, m!!.id) }
        }
    }
}
