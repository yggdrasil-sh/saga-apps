package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatFedHint
import io.github.yggdrasil_sh.saga.data.ShareTargets
import io.github.yggdrasil_sh.saga.data.TargetUser
import io.github.yggdrasil_sh.saga.ui.share.ShareLogic
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hinweis bei name@server/E-Mail in der Chat-Personensuche und remote_hint im Teilen-Dialog */
class FedHintTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun addressDetection() {
        for (q in listOf("bob@saga.example.org", "@bob@saga.example.org", " vorname.name@firma.de ", "bob@host:8163", "@sc-fed@b.test:8163")) {
            assertTrue(q, ChatFedHint.isAddress(q))
        }
        for (q in listOf("bob", "bob@", "@bob", "bob@localhost", "bob@@x.de", "bob @x.de", "a@b@c.de", "Bob Bauer", "")) {
            assertFalse(q, ChatFedHint.isAddress(q))
        }
        assertTrue(ChatFedHint.show("bob@saga.example.org", 0))
        assertFalse(ChatFedHint.show("bob@saga.example.org", 1))       // lokale Treffer haben Vorrang
        assertFalse(ChatFedHint.show("bob", 0))
    }

    @Test
    fun guideUrlPerLanguage() {
        assertTrue(ChatFedHint.guideUrl("de").endsWith("docs/ANLEITUNG.md#mit-anderen-saga-instanzen-teilen-f%C3%B6deration"))
        assertTrue(ChatFedHint.guideUrl("es").endsWith("docs/es/GUIA.md#compartir-con-otras-instancias-de-saga-federaci%C3%B3n"))
        for (l in listOf("en", "ru", "th", "fr")) {
            assertTrue(l, ChatFedHint.guideUrl(l).endsWith("docs/en/GUIDE.md#sharing-with-other-saga-instances-federation"))
        }
    }

    @Test
    fun remoteHintField() {
        val neu = json.decodeFromString<ShareTargets>("""{"users":[],"groups":[],"remote":[],"remote_hint":"Mit x.de ist dieser Server nicht verbunden."}""")
        assertEquals("Mit x.de ist dieser Server nicht verbunden.", neu.remoteHint)
        assertEquals(UiText.Raw("Mit x.de ist dieser Server nicht verbunden."), ShareLogic.nobodyText(neu))
        val alt = json.decodeFromString<ShareTargets>("""{"users":[],"groups":[]}""")       // aelterer Server
        assertNull(alt.remoteHint)
        assertEquals(UiText.Res(R.string.share_nobody), ShareLogic.nobodyText(alt))
        assertEquals(UiText.Res(R.string.share_nobody), ShareLogic.nobodyText(ShareTargets(remoteHint = "  ")))
        assertEquals(UiText.Res(R.string.share_nobody), ShareLogic.nobodyText(ShareTargets(users = listOf(TargetUser("x", "X")), remoteHint = null)))
    }
}
