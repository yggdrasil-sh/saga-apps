package io.github.yggdrasil_sh.saga

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.AddPageAskDialog
import io.github.yggdrasil_sh.saga.call.AddPersonDialog
import io.github.yggdrasil_sh.saga.call.CallAdd
import io.github.yggdrasil_sh.saga.call.CallAddAsk
import io.github.yggdrasil_sh.saga.call.CallAddUi
import io.github.yggdrasil_sh.saga.call.CallEntry
import io.github.yggdrasil_sh.saga.call.CallInvite
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.CallToast
import io.github.yggdrasil_sh.saga.call.HornPeopleList
import io.github.yggdrasil_sh.saga.call.HornPerson
import io.github.yggdrasil_sh.saga.call.HornUi
import io.github.yggdrasil_sh.saga.call.Invites
import io.github.yggdrasil_sh.saga.call.addOpener
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.ui.chat.CallEntryRow
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * 2.5 (L8/L9) Person hinzufuegen / 1:1 -> Konferenz: Bildschirmfotos mit erfundenen Beispieldaten - Teilnehmerliste
 * vorher (ohne Knopf) und nachher (mit "Person hinzufuegen"), Auswahl (Kontakte, wer im Anruf ist fehlt, Mitglied ohne
 * Geraet "klingelt erneut", voll), Seiten-Rueckfrage, Ansicht nach dem Umzug (Gruppe, "Nicht dabei", Hinweis),
 * Chat-Eintrag vorher/nachher. Kein Server, keine Medien. Fenster samt Dialogen per UiAutomation.takeScreenshot.
 * Ablage: getExternalFilesDir()/shots/dazu-*.png
 */
@RunWith(AndroidJUnit4::class)
class DazuShotsTest {
    private val legs = listOf(
        CallLeg(who = "anna", name = "Anna Beispiel", ref = "~a1", device = "d1", me = true),
        CallLeg(who = "bert", name = "Bert Beispiel", ref = "~b2", device = "d2"),
    )
    private val contacts = listOf(
        CallAdd.Pick("~b2", "Bert Beispiel"),                                       // im Anruf -> fehlt
        CallAdd.Pick("~c3", "Cora Beispiel", sub = "cora.b"),
        CallAdd.Pick("~d4", "Dora Neu"),
        CallAdd.Pick("~e5@peer.example", "Emil Fern", remoteHost = "peer.example"),
        CallAdd.Pick("~f6", "Frida Weg", gone = true),
    )

    private fun people(n: Int = 2) = HornUi(people = listOf(
        HornPerson("d1", "Anna Beispiel", "Pixel 9", me = true, host = true),
        HornPerson("d2", "Bert Beispiel", "iPhone", speaking = true),
        HornPerson("d3", "Dora Neu", "Pixel 8"),
    ).take(n))

    private fun addUi(mode: CallAdd.Mode, used: Int = 2, limit: Int = 6, page: String? = null, note: UiText? = null): CallAddUi {
        val seats = CallAdd.Seats(used, limit)
        return CallAddUi(
            mode, seats, page, CallAdd.fieldHint(mode, seats, page), CallAdd.inCall(legs),
            CallAdd.away(listOf(CallInvite("cora", "Cora Beispiel", ref = "~c3", state = "missed"))), note = note,
        )
    }

    @Composable
    private fun Panel(title: String, content: @Composable () -> Unit) {
        val sc = MaterialTheme.colorScheme
        Surface(color = sc.surface, modifier = Modifier.fillMaxSize()) {
            Surface(shape = RoundedCornerShape(SagaRadius.xl), color = sc.surfaceContainerHigh, modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        }
    }

    private fun shot(name: String, dark: Boolean = false, lang: String = "de", body: @Composable () -> Unit) {
        val ins = InstrumentationRegistry.getInstrumentation()
        val dir = File(ins.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg, LocalResources provides lc.resources) { SagaTheme(dark = dark) { body() } }
                }
            }
            Thread.sleep(1800)
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            File(dir, "dazu-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(File(dir, "dazu-$name.png").length() > 1000)
    }

    private fun inv(after: Boolean): Invites.Snapshot {
        val now = System.currentTimeMillis() / 1000.0
        return Invites.Snapshot(
            listOf(CallInvite("cora", "Cora Beispiel", state = if (after) "ringing" else "missed", since = now - 3)), ringS = 45,
        )
    }

    @Test
    fun participantsBeforeAfter() {
        // vorher: Gruppenanruf ohne Knopf (Stand testing)
        shot("1-teilnehmer-vorher") {
            Panel("Teilnehmende (2)") { HornPeopleList(people(), {}, {}, inv = inv(false), onRing = {}) }
        }
        // nachher: Knopf oben (can_add + call_add)
        val open = addOpener(addUi(CallAdd.Mode.ADD)) {}
        assertNotNull(open)
        shot("1-teilnehmer-nachher") {
            Panel("Teilnehmende (2)") { HornPeopleList(people(), {}, {}, inv = inv(false), onRing = {}, onAdd = open) }
        }
        shot("1-teilnehmer-nachher-dunkel", dark = true) {
            Panel("Teilnehmende (2)") { HornPeopleList(people(), {}, {}, inv = inv(false), onRing = {}, onAdd = open) }
        }
        // ohne Recht: kein Knopf
        assertNull(addOpener(null) {})
    }

    @Test
    fun picker() {
        shot("2-auswahl") {
            Box(Modifier.fillMaxSize()) {
                AddPersonDialog(addUi(CallAdd.Mode.ADD), onPick = {}, load = { q -> if (q.isEmpty()) contacts else emptyList() }) {}
            }
        }
        shot("2-auswahl-1zu1") {
            Box(Modifier.fillMaxSize()) {
                AddPersonDialog(addUi(CallAdd.Mode.MOVE, note = UiText.Res(R.string.inv_calling_one, "Dora Neu")), onPick = {}, load = { contacts }) {}
            }
        }
        shot("2-auswahl-voll-dunkel", dark = true) {
            Box(Modifier.fillMaxSize()) { AddPersonDialog(addUi(CallAdd.Mode.ADD, used = 6), onPick = {}, load = { contacts }) {} }
        }
        shot("2-auswahl-en", lang = "en") {
            Box(Modifier.fillMaxSize()) { AddPersonDialog(addUi(CallAdd.Mode.ADD, page = "Projektplan"), onPick = {}, load = { contacts }) {} }
        }
    }

    @Test
    fun pageAsk() {
        val ask = CallAddAsk(CallAdd.Pick("~d4", "Dora Neu"), UiText.Raw("Dora Neu bekommt Zugriff auf die Seite „Projektplan“."))
        shot("3-seite-teilen") { Box(Modifier.fillMaxSize()) { AddPageAskDialog(ask) {} } }
        shot("3-seite-teilen-dunkel", dark = true) { Box(Modifier.fillMaxSize()) { AddPageAskDialog(ask) {} } }
    }

    @Test
    fun afterMove() {
        // Umzug-Zustand: 1:1 (dm) -> Gruppe am neuen Chat, danach Knopf wie in Gruppen
        val next = CallAdd.move(CallAdd.Room("group", dm = true, chatId = 12, title = "Bert Beispiel"), 37, "Anna, Bert, Dora")!!
        assertEquals(37L, next.chatId)
        assertEquals(CallAdd.Mode.ADD, CallAdd.mode(setOf(CallAdd.FEATURE_ADD, CallAdd.FEATURE_MOVE), next.kind, next.dm, CallAdd.Flags(canAdd = true)))
        // vorher (aeltere App): Anzeige bleibt beim Gegenueber, kein "Nicht dabei"
        shot("4-umzug-vorher") {
            Panel("Bert Beispiel") { HornPeopleList(people(3), {}, {}) }
        }
        shot("4-umzug-nachher") {
            Box(Modifier.fillMaxSize()) {
                Panel(next.title) { HornPeopleList(people(3), {}, {}, inv = inv(true), onRing = {}, onAdd = {}) }
                CallToast(UiText.Res(R.string.add_moved_note), Modifier.align(Alignment.BottomCenter).padding(24.dp))
            }
        }
    }

    @Composable
    private fun EntryPanel(moved: Long?) {
        val e = CallEntry(call = "x", status = "ended", dur = 42, from = "anna", to = "bert", moved = moved)
        val m = ChatMessage(1, "m1", null, "", 1_794_900_000_000L, null, null, null, true, true, call = e)
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(top = 80.dp)) {
                CallEntryRow(m, e, "anna", canCall = true, onGroup = {}) {}
            }
        }
    }

    @Test
    fun chatEntry() {
        shot("5-eintrag-vorher") { EntryPanel(null) }
        shot("5-eintrag-nachher") { EntryPanel(37) }
        shot("5-eintrag-nachher-dunkel", dark = true) { EntryPanel(37) }
    }
}
