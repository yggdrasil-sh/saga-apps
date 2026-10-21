package io.github.yggdrasil_sh.saga

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.X3Limits
import io.github.yggdrasil_sh.saga.ui.chat.VoiceMicButton
import io.github.yggdrasil_sh.saga.ui.chat.VoiceRecUi
import io.github.yggdrasil_sh.saga.ui.common.SafeBottom
import io.github.yggdrasil_sh.saga.ui.common.SectionHeader
import io.github.yggdrasil_sh.saga.ui.page.SlashMenuCard
import io.github.yggdrasil_sh.saga.ui.push.SwitchRow
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * TT 2.5 Android (Live-Test): Bilder "nachher" fuer 52, 15, 35/38 und 55 - ohne Konto, mit den echten Bausteinen der App
 * (SafeBottom, VoiceMicButton, SlashMenuCard, SwitchRow, CallPushHintDialog, ChatLogic). Bildschirmfoto des ganzen
 * Bildschirms (mit Status- und Gestenleiste) ueber UiAutomation. Ablage: getExternalFilesDir()/shots/tt25-*.png.
 */
@RunWith(AndroidJUnit4::class)
class Tt25ShotsTest {
    private fun shot(name: String, waitMs: Long = 1500, content: @Composable () -> Unit) {
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.enableEdgeToEdge()
                val cfg = Configuration(a.resources.configuration).apply { setLocale(Locale.forLanguageTag("de")) }
                val lc = a.createConfigurationContext(cfg)
                a.setContent {
                    CompositionLocalProvider(LocalContext provides lc, LocalConfiguration provides cfg, androidx.activity.compose.LocalActivityResultRegistryOwner provides a) {
                        SagaTheme(dark = false) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
                    }
                }
            }
            Thread.sleep(waitMs)
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            File(dir, "tt25-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(File(dir, "tt25-$name.png").length() > 1000)
    }

    /** Eingabezeile wie im Chat; insets = Abstand unten; rot = Bereich der Home-Geste (mandatorySystemGestures) */
    @Composable
    private fun ChatBottom(insets: WindowInsets, label: String) {
        val scope = rememberCoroutineScope()
        val rec = remember { VoiceRecUi(scope) {} }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
                Text(label, Modifier.statusBarsPadding().padding(16.dp), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Add, null) }
                    OutlinedTextField(
                        "", {}, placeholder = { Text(stringResource(R.string.chat_message_hint)) }, shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                    VoiceMicButton(rec, X3Limits(), notify = {}, onDenied = {})
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
                    .background(Color(0x55FF0000)),
            )
        }
    }

    @Test
    fun t52_chatInputAboveHomeGesture() {
        shot("52-vorher") { ChatBottom(WindowInsets.ime.union(WindowInsets.navigationBars), "52 vorher: ime ∪ navigationBars") }
        shot("52-nachher") { ChatBottom(SafeBottom.insets, "52 nachher: SafeBottom (+ Home-Geste)") }
    }

    /** "/"-Menue wie vor dem Fix (ohne bringIntoView) - nur fuer das Vorher-Bild */
    @Composable
    private fun OldSlashCard() {
        val items = io.github.yggdrasil_sh.saga.ui.page.slashItems("", emptySet())
        Surface(tonalElevation = 4.dp, shadowElevation = 4.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 4.dp).widthIn(max = 320.dp)) {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                items.forEach { Text(io.github.yggdrasil_sh.saga.ui.page.slashLabel(it), Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp)) }
            }
        }
    }

    @Composable
    private fun SlashPage(title: String, card: @Composable () -> Unit) {
        val st = rememberLazyListState()
        val fr = remember { FocusRequester() }
        val kb = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            st.scrollToItem(30)
            fr.requestFocus()
            kb?.show()
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            Text(title, Modifier.statusBarsPadding().padding(16.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = st) {
                items(30) { i -> Text("Absatz ${i + 1} – Beispieltext auf einer langen Seite.", Modifier.padding(vertical = 8.dp)) }
                item {
                    Column {
                        BasicTextField("/", {}, Modifier.fillMaxWidth().focusRequester(fr), textStyle = TextStyle(fontSize = 16.sp))
                        card()
                    }
                }
            }
        }
    }

    @Test
    fun t15_slashMenuAtPageEnd() {
        shot("15-vorher", waitMs = 4000) { SlashPage("15 vorher: „/“ am Seitenende") { OldSlashCard() } }
        shot("15-nachher", waitMs = 4000) { SlashPage("15 nachher: „/“ am Seitenende") { SlashMenuCard("", emptySet()) {} } }
    }

    @Test
    fun t35_38_pushTexts() {
        shot("35-38-nachher") {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
                Text("35/38 nachher: Mitteilungen aus", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                SwitchRow("Mitteilungen auf diesem Gerät", false, Modifier) {}
                SectionHeader(stringResource(R.string.push_stream_title), Modifier)
                Text(stringResource(R.string.push_stream_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                SwitchRow(stringResource(R.string.push_stream_switch), true, Modifier, hint = stringResource(R.string.push_stream_needs_push), enabled = false) {}
                Spacer(Modifier.height(12.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.call_push_title)) },
                    supportingContent = { Text(stringResource(R.string.call_push_hint)) },
                    trailingContent = { TextButton(onClick = {}) { Text(stringResource(R.string.call_push_setup)) } },
                )
            }
        }
    }

    @Test
    fun t55_peopleSearch() {
        val mark = InstrumentationRegistry.getInstrumentation().targetContext.let { c ->
            val cfg = Configuration(c.resources.configuration).apply { setLocale(Locale.forLanguageTag("de")) }
            c.createConfigurationContext(cfg).getString(R.string.chat_handle_mark)
        }
        val hits = listOf(
            ChatPerson(username = "~4wup3msnxxjvemea", name = "Lea Sommer", ready = true, sub = "~4wup3msnxxjvemea"),
            ChatPerson(username = "~k2v9x7qa1mrt3pz", name = "Paul Wagner", ready = true, sub = "~k2v9x7qa1mrt3pz"),
            ChatPerson(username = "~m3n8b2c7d6e5f4g", name = "Paul Wagner", ready = false, sub = "~m3n8b2c7d6e5f4g"),
            ChatPerson(username = "~z9y8x7w6v5u4t3s", name = "", ready = true, sub = "~z9y8x7w6v5u4t3s"),
        )
        shot("55-nachher") {
            // wie der Dialog "Neuer Chat" (als Flaeche gezeichnet: ein echter Dialog naehme die App-Sprache nicht an)
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).statusBarsPadding(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(24.dp)) {
                    Column(Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.chat_new), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                        OutlinedTextField("a", {}, singleLine = true, label = { Text(stringResource(R.string.chat_people_search)) }, modifier = Modifier.fillMaxWidth())
                        hits.forEach { u ->
                            val same = hits.count { ChatLogic.shown(it) == ChatLogic.shown(u) } > 1
                            ListItem(
                                headlineContent = { Text(ChatLogic.shownMarked(u) { mark.format(it) }) },
                                supportingContent = ChatLogic.subline(
                                    ChatLogic.personSub(u.sub, same) { mark.format(it) },
                                    if (u.ready) null else stringResource(R.string.chat_not_ready, ChatLogic.shownMarked(u) { mark.format(it) }),
                                )?.let { t -> { Text(t) } },
                                trailingContent = { Checkbox(checked = false, onCheckedChange = null) },
                            )
                        }
                        TextButton(onClick = {}, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
    }
}
