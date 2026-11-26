package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.push.PushLocal
import io.github.yggdrasil_sh.saga.push.PushServerInfo
import io.github.yggdrasil_sh.saga.push.PushSubLogic
import io.github.yggdrasil_sh.saga.widget.WidgetPushLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.6 (Bugs-DB 2188): stiller Widget-Push - Abo-Faehigkeit "widgets", Erkennen der Nachricht, Meldung der Widgets */
class WidgetPushLogicTest {
    @Test
    fun capsWidgetsOnlyWhenServerOffersIt() {
        assertEquals(listOf("update", "widgets"), PushSubLogic.caps(PushServerInfo(payload = 2, subCaps = listOf("update", "widgets"))))
        assertEquals(listOf("update"), PushSubLogic.caps(PushServerInfo(payload = 2, subCaps = listOf("update"))))
        assertEquals(listOf("update"), PushSubLogic.caps(PushServerInfo(payload = 2)))      // keine Liste: nur update (wie 2.5)
        assertTrue(PushSubLogic.caps(PushServerInfo(payload = 1, subCaps = listOf("widgets"))).isEmpty())
    }

    @Test
    fun renewWhenWidgetsNotYetReported() {
        val srv = PushServerInfo(payload = 2, subCaps = listOf("update", "widgets"))
        val l = PushLocal(distributor = "x", token = "t", endpoint = "https://up.example/e", subId = 4, lang = "de", caps = true)
        assertTrue(PushSubLogic.needsRenew(l, srv, "de"))                                    // Server neu: widgets melden
        assertFalse(PushSubLogic.needsRenew(l.copy(wcaps = true), srv, "de"))
        assertTrue(PushSubLogic.needsRenew(l.copy(wcaps = true), PushServerInfo(payload = 2, subCaps = listOf("update")), "de"))
        assertFalse(PushSubLogic.needsRenew(l, PushServerInfo(payload = 2, subCaps = listOf("update")), "de"))
    }

    @Test
    fun widgetPushIsRecognizedAndNeverShown() {
        assertTrue(WidgetPushLogic.isWidgetPush("widgets.update"))
        assertFalse(WidgetPushLogic.isWidgetPush("chat.message"))
        assertFalse(WidgetPushLogic.isWidgetPush(null))
        val raw = """{"title":"","body":"","url":"/","tag":"widgets","kind":"widgets.update","ts":1,"ttl":300,"v":2,"lang":"de","update":true}"""
        val m = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(io.github.yggdrasil_sh.saga.push.PushMessage.serializer(), raw)
        assertTrue(WidgetPushLogic.isWidgetPush(m.kind) && m.update && m.title.isEmpty() && m.body.isEmpty())
    }

    @Test
    fun reportPagesAndFingerprint() {
        assertEquals(listOf(3L, 7L, 12L), WidgetPushLogic.pages(listOf(12L, 3L, 7L, 3L, 0L, -5L)))
        assertEquals(64, WidgetPushLogic.pages((1L..100L).toList()).size)
        val a = WidgetPushLogic.fingerprint("s|k", listOf(3L, 7L), true)
        assertNotEquals(a, WidgetPushLogic.fingerprint("s|k", listOf(3L, 7L), false))
        assertNotEquals(a, WidgetPushLogic.fingerprint("s|k2", listOf(3L, 7L), true))      // anderes Konto: neu melden
        assertNotEquals(a, WidgetPushLogic.fingerprint("s|k", listOf(3L), true))
        assertTrue(WidgetPushLogic.unsupported(404) && WidgetPushLogic.unsupported(405) && !WidgetPushLogic.unsupported(500))
    }
}
