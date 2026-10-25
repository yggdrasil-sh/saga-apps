package io.github.yggdrasil_sh.saga

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.chat.ChatContent
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.X3Hint
import io.github.yggdrasil_sh.saga.ui.chat.Bubble
import io.github.yggdrasil_sh.saga.ui.chat.DeleteSelectionDialog
import io.github.yggdrasil_sh.saga.ui.chat.ForwardTargetDialog
import io.github.yggdrasil_sh.saga.ui.chat.SelectionTopBar
import io.github.yggdrasil_sh.saga.ui.chat.ToPageDialog
import io.github.yggdrasil_sh.saga.ui.chat.X3InfoButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * 25.10.: Bildschirmfotos der Chat-Mehrfachauswahl mit den echten Bausteinen (Bubble, Kopfzeile, Dialoge) und neutralen
 * Testdaten - ohne Konto und ohne Server. Ablage: getExternalFilesDir()/shots/chatsel-*.png.
 */
@RunWith(AndroidJUnit4::class)
class ChatSelShotsTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val repo get() = (ins.targetContext.applicationContext as SagaApplication).repo

    private fun shot(name: String, dark: Boolean = false, waitMs: Long = 2000, content: @Composable () -> Unit) {
        val ctx = ins.targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag("de")) }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    CompositionLocalProvider(LocalContext provides lc, androidx.compose.ui.platform.LocalResources provides lc.resources, LocalConfiguration provides cfg, androidx.activity.compose.LocalActivityResultRegistryOwner provides a) {
                        SagaTheme(dark = dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
                    }
                }
            }
            Thread.sleep(waitMs)
            // traeger Emulator: "System UI reagiert nicht" o. Ae. nicht mit aufs Bild
            ins.uiAutomation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
            Thread.sleep(500)
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            File(dir, "chatsel-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(File(dir, "chatsel-$name.png").length() > 1000)
    }

    private val t0 = 1_800_000_000_000L
    private fun msg(id: String, n: Int, text: String, mine: Boolean, who: String, files: List<ChatFile> = emptyList()) = ChatMessage(
        n.toLong(), id, ChatPerson(username = who.lowercase(), name = who), "d", t0 + n * 60_000L, null, null,
        ChatContent(text = text, files = files), true, mine,
    )

    private val msgs = listOf(
        msg("a", 1, "Wer bringt am Samstag den Grill mit?", false, "Anna"),
        msg("b", 2, "Ich – und Kohle für zwei Stunden.", true, "Ich"),
        msg("c", 3, "Super! Treffpunkt 14 Uhr am See.", false, "Ben"),
        msg("d", 4, "Passt, ich bringe Salat mit.", true, "Ich"),
        msg("e", 5, "Dann kümmere ich mich um Getränke.", false, "Anna"),
    )

    @Composable
    private fun SelectedChat(selected: Set<String>) {
        val s = androidx.compose.runtime.remember { ChatSession(990_001L, repo.chat).also { it.messages.addAll(msgs) } }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SelectionTopBar(selected.size, {}, {}, {}, {}, {})
            LazyColumn(Modifier.weight(1f), reverseLayout = true) {
                items(msgs.asReversed(), key = { it.id }) { m ->
                    Bubble(
                        m, s, group = true, aiPersona = null, onReply = {}, onCopy = {}, onDelete = {}, onOpenNote = {}, onOpenFile = {}, onUnverified = {},
                        selecting = true, selected = m.id in selected, onSelect = {},
                    )
                }
            }
        }
    }

    @Test
    fun selection() {
        shot("auswahl") { SelectedChat(setOf("b", "d")) }
        shot("auswahl-dunkel", dark = true) { SelectedChat(setOf("a", "c", "d")) }
    }

    @Test
    fun deleteDialogs() {
        shot("loeschen-eigene") {
            SelectedChat(setOf("b", "d"))
            DeleteSelectionDialog(2, forAll = true, onDismiss = {}, onForMe = {}, onForAll = {})
        }
        shot("loeschen-gemischt") {
            SelectedChat(setOf("a", "d"))
            DeleteSelectionDialog(2, forAll = false, onDismiss = {}, onForMe = {}, onForAll = {})
        }
    }

    @Test
    fun forwardAndPage() {
        repo.chat.chatsLoaded.value = true
        repo.chat.chats.value = listOf(
            ChatSummary(1, kind = "group", name = "Grillabend"), ChatSummary(2, kind = "group", name = "Familie"),
            ChatSummary(3, kind = "dm", name = "Mia Beispiel"), ChatSummary(4, kind = "group", name = "Laufgruppe"),
            ChatSummary(990_001L, kind = "group", name = "Aktueller Chat"),
        )
        shot("weiterleiten") {
            SelectedChat(setOf("b", "c"))
            ForwardTargetDialog(repo, 990_001L, onDismiss = {}) { _, _ -> }
        }
        shot("seite") {
            SelectedChat(setOf("b", "c"))
            ToPageDialog(onDismiss = {}, onNew = {}, onAppend = {})
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun x3Hint() {
        val h = X3Hint.of(
            ChatSummary(
                7, kind = "group", name = "Grillabend", x3Ok = false, x3Why = "devices",
                x3Wait = io.github.yggdrasil_sh.saga.chat.X3Wait(listOf("Linux-Laptop", "Safari · iOS"), 1, 1_800_000_000.0),
            ),
        )!!
        shot("hinweis") {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Grillabend") },
                    navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                    actions = { X3InfoButton(h, startOpen = true) {} },
                )
            }
        }
        shot("hinweis-server") {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TopAppBar(title = { Text("Projekt") }, actions = { X3InfoButton(X3Hint.Server(listOf("chat.example.org")), startOpen = true) {} })
            }
        }
    }
}
