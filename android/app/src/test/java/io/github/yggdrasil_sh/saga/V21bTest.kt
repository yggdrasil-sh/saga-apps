package io.github.yggdrasil_sh.saga

import androidx.compose.ui.geometry.Offset
import io.github.yggdrasil_sh.saga.call.CallConfig
import io.github.yggdrasil_sh.saga.call.CallEntry
import io.github.yggdrasil_sh.saga.call.CallLogic
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatMe
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.data.AvatarState
import io.github.yggdrasil_sh.saga.data.Person
import io.github.yggdrasil_sh.saga.data.SecurityLogic
import io.github.yggdrasil_sh.saga.data.SecurityStatus
import io.github.yggdrasil_sh.saga.live.NotifActor
import io.github.yggdrasil_sh.saga.push.PushMessage
import io.github.yggdrasil_sh.saga.ui.common.AvatarLogic
import io.github.yggdrasil_sh.saga.ui.settings.AvatarCrop
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saga 2.1 (Welle b): 2FA-Reauth, Profilbilder, Anrufe - reine Regeln und Antwortformen */
class V21bTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    // ------------------------------------------------------------------ 2FA

    @Test
    fun reauthField() {
        val totp = listOf("password", "totp")
        assertEquals("code" to "123456", ChatLogic.reauthField("123456", totp))
        assertEquals("code" to "123456", ChatLogic.reauthField(" 123 456 ", totp))
        assertEquals("password" to "123456", ChatLogic.reauthField("123456", listOf("password")))   // ohne App: Passwort
        assertEquals("password" to "1234567", ChatLogic.reauthField("1234567", totp))
        assertEquals("password" to "geheim 1", ChatLogic.reauthField("geheim 1", totp))
        assertNull(ChatLogic.reauthField("   ", totp))
        assertTrue(ChatLogic.reauthWithCode(totp))
        assertFalse(ChatLogic.reauthWithCode(listOf("password", "passkey")))
    }

    @Test
    fun chatMeReauthMethods() {
        val m = json.decodeFromString(ChatMe.serializer(), """{"enabled":true,"uid":"a","reauth":"password","reauth_methods":["password","passkey","totp"]}""")
        assertEquals(listOf("password", "passkey", "totp"), m.reauthMethods)
        assertEquals(emptyList<String>(), json.decodeFromString(ChatMe.serializer(), """{"enabled":true}""").reauthMethods)
    }

    @Test
    fun securityStatus() {
        val s = json.decodeFromString(SecurityStatus.serializer(),
            """{"supported":true,"source":"local","enabled":true,"required":true,"passkeys":[{"id":3,"name":"Schluessel"}],"totp":true,"recovery_left":2,"fresh":false}""")
        assertTrue(s.supported && s.enabled && s.totp)
        assertEquals(1, s.passkeys.size)
        assertTrue(SecurityLogic.recoveryLow(s))
        assertFalse(SecurityLogic.mustSetUp(s))
        assertTrue(SecurityLogic.mustSetUp(SecurityStatus(supported = true, required = true)))
        assertFalse(SecurityLogic.recoveryLow(SecurityStatus(supported = true, enabled = false)))
    }

    // ------------------------------------------------------------------ Profilbilder

    @Test
    fun avatarFields() {
        assertEquals("/api/avatar/k?v=2", json.decodeFromString(Person.serializer(), """{"name":"A","avatar":"/api/avatar/k?v=2"}""").avatar)
        assertEquals("/api/avatar/x?v=1", json.decodeFromString(ChatPerson.serializer(), """{"username":"a","avatar":"/api/avatar/x?v=1"}""").avatar)
        assertNull(json.decodeFromString(NotifActor.serializer(), """{"name":"A","avatar":null}""").avatar)
        val st = json.decodeFromString(AvatarState.serializer(),
            """{"url":"/api/avatar/k?v=3","source":"upload","upload":true,"gravatar":false,"gravatar_enabled":true,"has_email":true,"public":false,"sso":null,"max_mb":5}""")
        assertEquals("upload", st.source)
        assertTrue(st.upload && st.gravatarEnabled)
        assertEquals(5, st.maxMb)
    }

    @Test
    fun avatarUrl() {
        val s = "https://saga.example.org"
        assertEquals("https://saga.example.org/api/avatar/k1?v=3&s=128&fmt=webp", AvatarLogic.url(s, "/api/avatar/k1?v=3", 120))
        assertEquals("https://saga.example.org/sub/api/avatar/k1?s=64&fmt=webp", AvatarLogic.url("https://saga.example.org/sub/", "/api/avatar/k1", 40))
        assertEquals(256, AvatarLogic.tier(300))
        assertEquals(64, AvatarLogic.tier(1))
        // nie fremde Adressen, nie ohne Server, nie andere Pfade
        assertNull(AvatarLogic.url(s, "https://www.gravatar.com/avatar/abc", 64))
        assertNull(AvatarLogic.url(s, "//evil.example/api/avatar/x", 64))
        assertNull(AvatarLogic.url(s, "/files/x.png", 64))
        assertNull(AvatarLogic.url(s, "/api/avatar/../x", 64))
        assertNull(AvatarLogic.url("", "/api/avatar/k", 64))
        assertNull(AvatarLogic.url(s, null, 64))
        // 404: bis zur neuen Version nicht erneut laden
        val u = AvatarLogic.url(s, "/api/avatar/gone?v=1", 64)!!
        AvatarLogic.markFailed(u)
        assertNull(AvatarLogic.url(s, "/api/avatar/gone?v=1", 64))
        assertEquals("https://saga.example.org/api/avatar/gone?v=2&s=64&fmt=webp", AvatarLogic.url(s, "/api/avatar/gone?v=2", 64))
        AvatarLogic.reset()
        assertEquals(u, AvatarLogic.url(s, "/api/avatar/gone?v=1", 64))
    }

    @Test
    fun cropRect() {
        // 2000x1000, Ansicht 500 px, Grundskalierung 0.5 (Hoehe deckt), mittig: Ausschnitt 1000x1000 ab x=500
        val (l, t, side) = AvatarCrop.cropRect(2000, 1000, 500f, 0.5f, Offset(-250f, 0f))
        assertEquals(Triple(500, 0, 1000), Triple(l, t, side))
        // gezoomt (x2): Kante 500
        assertEquals(500, AvatarCrop.cropRect(2000, 1000, 500f, 1.0f, Offset(-700f, -200f)).third)
        // nie ausserhalb des Bildes
        val r = AvatarCrop.cropRect(2000, 1000, 500f, 0.5f, Offset(100f, 100f))
        assertEquals(0, r.first)
        assertEquals(0, r.second)
        assertEquals(Offset(0f, 0f), AvatarCrop.clamp(Offset(30f, 10f), 2000, 1000, 500f, 0.5f))
        assertEquals(Offset(-500f, 0f), AvatarCrop.clamp(Offset(-900f, -20f), 2000, 1000, 500f, 0.5f))
    }

    // ------------------------------------------------------------------ Anrufe

    @Test
    fun callEntryTexts() {
        val e = CallLogic.parseEntry("""{"v":1,"type":"call","call":"c0","media":"video","status":"ended","dur":125,"from":"anna","to":"bert"}""")!!
        assertEquals(UiText.Res(R.string.call_entry_ended, UiText.Res(R.string.call_video), "2:05"), CallLogic.entryText(e, "bert"))
        val miss = e.copy(media = "audio", status = "missed", dur = null)
        assertEquals(UiText.Res(R.string.call_entry_missed), CallLogic.entryText(miss, "bert"))
        assertEquals(UiText.Res(R.string.call_entry_not_answered, UiText.Res(R.string.call_entry_audio)), CallLogic.entryText(miss, "anna"))
        assertEquals(UiText.Res(R.string.call_entry_missed_video), CallLogic.entryText(miss.copy(media = "video"), "bert"))
        assertEquals(UiText.Res(R.string.call_entry_busy), CallLogic.entryText(miss.copy(status = "busy"), "anna"))
        assertEquals(UiText.Res(R.string.call_entry_busy_missed), CallLogic.entryText(miss.copy(status = "busy"), "bert"))
        assertEquals(UiText.Res(R.string.call_entry_declined, UiText.Res(R.string.call_entry_audio)), CallLogic.entryText(miss.copy(status = "declined"), "bert"))
        assertEquals(UiText.Res(R.string.call_entry_failed, UiText.Res(R.string.call_entry_audio)), CallLogic.entryText(miss.copy(status = "failed"), "anna"))
        assertTrue(CallLogic.missed(miss, "bert"))
        assertFalse(CallLogic.missed(miss, "anna"))
        assertNull(CallLogic.parseEntry("""{"type":"text"}"""))
        assertNull(CallLogic.parseEntry("kaputt"))
    }

    @Test
    fun endTexts() {
        assertEquals(UiText.Res(R.string.call_declined_by, "Bert"), CallLogic.endText("declined", true, "Bert", null))
        assertEquals(UiText.Res(R.string.call_declined), CallLogic.endText("declined", false, "Bert", null))
        assertEquals(UiText.Res(R.string.call_no_answer, "Bert"), CallLogic.endText("timeout", true, "Bert", null))
        assertEquals(UiText.Res(R.string.call_missed), CallLogic.endText("timeout", false, "Bert", null))
        assertEquals(UiText.Res(R.string.call_hung_up_by, "Bert"), CallLogic.endText("cancelled", false, "Bert", null))
        assertEquals(UiText.Res(R.string.call_busy_peer, "Bert"), CallLogic.endText("busy", true, "Bert", null))
        assertEquals(UiText.Res(R.string.call_lost), CallLogic.endText("lost", true, "Bert", 10))
        assertEquals(UiText.Res(R.string.call_ended_dur, "1:01:01"), CallLogic.endText("hangup", true, "Bert", 3661))
        assertEquals(UiText.Res(R.string.call_ended), CallLogic.endText(null, true, "Bert", null))
    }

    @Test
    fun callRules() {
        assertEquals("0:00", CallLogic.duration(-3))
        assertEquals("12:34", CallLogic.duration(754))
        assertEquals("", CallLogic.quality(0.0, null, 0))
        assertEquals("good", CallLogic.quality(0.0, 0.05, 100))
        assertEquals("mid", CallLogic.quality(0.03, 0.05, 100))
        assertEquals("bad", CallLogic.quality(0.0, 0.9, 100))
        assertEquals(listOf("d1"), CallLogic.targets("d1", listOf("d1", "d2")))
        assertEquals(listOf("d1", "d2"), CallLogic.targets(null, listOf("d1", "d2")))
        assertEquals(listOf(40, 40, 5), CallLogic.iceBatches((1..85).toList()).map { it.size })
        assertTrue(CallLogic.validId("c0ffee00c0ffee00c0ffee00c0ffee00"))
        assertFalse(CallLogic.validId("C0FFEE00c0ffee00c0ffee00c0ffee00"))
        assertFalse(CallLogic.validId("../x"))
        assertFalse(CallLogic.validId(null))
    }

    @Test
    fun callConfig() {
        val c = json.decodeFromString(CallConfig.serializer(),
            """{"enabled":true,"ice_servers":[{"urls":["stun:a:3478","turn:a:3478"],"username":"u","credential":"p"},{"urls":"stun:b"}],"relay_only":false,"turn":true,"stun":true,"ring_s":45,"alive_s":15,"max_s":14400,"format":"saga-call/v1"}""")
        assertTrue(c.enabled && c.hasTurn)
        assertEquals(listOf("stun:a:3478", "turn:a:3478"), c.iceServers[0].urlList)
        assertEquals(listOf("stun:b"), c.iceServers[1].urlList)
        assertFalse(json.decodeFromString(CallConfig.serializer(), """{"enabled":true,"turn":false}""").hasTurn)
        assertFalse(json.decodeFromString(CallConfig.serializer(), """{"enabled":true,"turn":""}""").hasTurn)
    }

    @Test
    fun pushCallMessage() {
        val m = json.decodeFromString(PushMessage.serializer(),
            """{"title":"Saga","body":"Anruf","url":"/chats#call-c0","tag":"call-c0","kind":"call","ts":1,"call":"c0ffee00c0ffee00c0ffee00c0ffee00","chat":7,"media":"video","caller":null}""")
        assertEquals("call", m.kind)
        assertEquals("c0ffee00c0ffee00c0ffee00c0ffee00", m.call)
        assertEquals(7L, m.chat)
    }
}
