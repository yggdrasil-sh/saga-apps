package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallLogic
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fehlerrunde nach Bughunt 2 (android.md) */
class Bughunt2Test {
    @Test fun videoAspectFollowsFrameAndRotation() {
        // Querformat-Webcam auf hochkant gehaltenem Telefon: Ansicht 16:9, nicht Bildschirmformat (sonst Beschnitt)
        assertEquals(16f / 9f, CallLogic.videoAspect(1280, 720, 0)!!, 0.001f)
        assertEquals(9f / 16f, CallLogic.videoAspect(1280, 720, 90)!!, 0.001f)
        assertEquals(9f / 16f, CallLogic.videoAspect(1280, 720, 270)!!, 0.001f)
        assertEquals(16f / 9f, CallLogic.videoAspect(1280, 720, 180)!!, 0.001f)
        assertNull(CallLogic.videoAspect(0, 720, 0))
    }

    @Test fun speakerStateIsRealRoute() {
        val speaker = android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val earpiece = android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        // Tablet ohne Hoerer: "Lautsprecher aus" gewuenscht, Ton bleibt aber auf dem Lautsprecher -> Knopf zeigt "an"
        assertTrue(CallLogic.speakerActive(false, speaker))
        assertFalse(CallLogic.speakerActive(false, earpiece))
        assertTrue(CallLogic.speakerActive(true, speaker))
        assertTrue(CallLogic.speakerActive(true, null))
    }

    @Test fun backgroundRingingNeedsDistributor() {
        val ok = io.github.yggdrasil_sh.saga.push.PushLocal(distributor = "org.example.ntfy", token = "t", endpoint = "https://push.example/x")
        val inst = listOf("org.example.ntfy")
        assertTrue(io.github.yggdrasil_sh.saga.push.PushLogic.backgroundReady(ok, inst))
        // kein Verteiler eingerichtet / noch ohne Adresse / Verteiler deinstalliert -> Hinweis "klingelt nur bei offener App"
        assertFalse(io.github.yggdrasil_sh.saga.push.PushLogic.backgroundReady(null, inst))
        assertFalse(io.github.yggdrasil_sh.saga.push.PushLogic.backgroundReady(ok.copy(endpoint = null), inst))
        assertFalse(io.github.yggdrasil_sh.saga.push.PushLogic.backgroundReady(ok, emptyList()))
    }

    @Test fun loginWaitsForLocalNetworkAnswer() = kotlinx.coroutines.test.runTest {
        val gate = io.github.yggdrasil_sh.saga.util.LocalNetworkGate()
        gate.answer(false)                                   // alte Antwort einer frueheren Rueckfrage
        gate.ask()
        val r = async { gate.await() }
        testScheduler.runCurrent()
        assertFalse("waehrend die Rueckfrage offen ist, keine Meldung", r.isCompleted)
        gate.answer(true)                                    // "Erlauben" -> Anmeldung laeuft weiter
        assertTrue(r.await())
        gate.ask()
        val d = async { gate.await() }
        gate.answer(false)                                   // "Spaeter"/abgelehnt -> erst jetzt die Meldung
        assertFalse(d.await())
    }

    @Test fun securityPageNamesAppAccount() {
        val L = io.github.yggdrasil_sh.saga.auth.LoginFlow
        assertEquals("https://s.example/security?user=app-user", L.securityUrl("https://s.example/", "app-user"))
        assertEquals("https://s.example/security?user=a%20b%26c", L.securityUrl("https://s.example", " a b&c "))
        assertEquals("https://s.example/security", L.securityUrl("https://s.example", ""))
    }

    @Test fun loginAfterSignOutAsksForAccount() {
        val L = io.github.yggdrasil_sh.saga.auth.LoginFlow
        assertEquals("https://s.example/app/login?challenge=abc&scheme=saga", L.loginUrl("https://s.example", "abc", fresh = false))
        assertEquals("https://s.example/app/login?challenge=abc&scheme=saga&prompt=login", L.loginUrl("https://s.example", "abc", fresh = true))
    }

    @Test fun transparentAvatarStaysPng() {
        assertEquals("image/png", io.github.yggdrasil_sh.saga.ui.settings.AvatarCrop.mimeFor(hasAlpha = true))
        assertEquals("image/jpeg", io.github.yggdrasil_sh.saga.ui.settings.AvatarCrop.mimeFor(hasAlpha = false))
    }

    @Test fun chatDayDividers() {
        val z = java.time.ZoneId.of("Europe/Berlin")
        fun ms(s: String) = java.time.ZonedDateTime.parse(s).toInstant().toEpochMilli()
        val F = io.github.yggdrasil_sh.saga.chat.ChatFormat
        // Anruf vom 15.10. direkt vor einer Nachricht vom 17.10. -> Trenner; gleicher Tag -> keiner; aelteste -> immer
        assertTrue(F.dayStarts(ms("2026-10-17T09:00:00+02:00[Europe/Berlin]"), ms("2026-10-15T04:24:00+02:00[Europe/Berlin]"), z))
        assertFalse(F.dayStarts(ms("2026-10-17T23:59:00+02:00[Europe/Berlin]"), ms("2026-10-17T00:01:00+02:00[Europe/Berlin]"), z))
        assertTrue(F.dayStarts(ms("2026-10-17T09:00:00+02:00[Europe/Berlin]"), null, z))
        // lokaler Tag zaehlt, nicht UTC (00:30 Ortszeit ist in UTC noch der Vortag)
        assertTrue(F.dayStarts(ms("2026-10-17T00:30:00+02:00[Europe/Berlin]"), ms("2026-10-16T23:30:00+02:00[Europe/Berlin]"), z))
    }

    @Test fun dbValuesLocalized() {
        val D = io.github.yggdrasil_sh.saga.blocks.DbProps
        val es = java.util.Locale.forLanguageTag("es-ES")
        val de = java.util.Locale.GERMANY
        assertEquals("1,7857", D.localNum(es)(1.7857))
        assertEquals("12", D.localNum(de)(12.0))
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"props":[{"id":"d","name":"Datum","type":"date"},{"id":"q","name":"Quote","type":"calc","a":"x","b":"y","op":"div"}]}""",
        ) as kotlinx.serialization.json.JsonObject
        val props = kotlinx.serialization.json.Json.parseToJsonElement("""{"d":"2026-10-04","x":25,"y":14}""") as kotlinx.serialization.json.JsonObject
        val f = D.fields(schema, props, day = D.localDay(de), number = D.localNum(de)).associate { it.name to it.value }
        assertEquals("04.10.2026", f["Datum"])                     // nicht mehr ISO "2026-10-04"
        assertEquals("1,7857", f["Quote"])
        assertEquals("kaputt", D.localDay(de)("kaputt"))
    }

    @Test fun reencodedImageKeepsExifOrientation() {
        val t = { o: Int -> io.github.yggdrasil_sh.saga.ui.common.exifTransform(o) }
        assertEquals(90 to false, t(6))                            // typisches Hochkant-Handyfoto
        assertEquals(270 to false, t(8))
        assertEquals(180 to false, t(3))
        assertEquals(0 to true, t(2))
        assertEquals(90 to true, t(5))
        assertEquals(0 to false, t(1))
        assertEquals(0 to false, t(0))                             // unbekannt -> unveraendert
    }

    @Test fun lanPromptSettingsOnlyWhenSystemStopsAsking() {
        // erste Ablehnung: System fragt noch einmal -> Knopf "Erlauben"; danach gesperrt -> "Einstellungen oeffnen"
        assertFalse(io.github.yggdrasil_sh.saga.ui.common.LocalNetworkFlow.openSettingsNext(systemMayAskAgain = true))
        assertTrue(io.github.yggdrasil_sh.saga.ui.common.LocalNetworkFlow.openSettingsNext(systemMayAskAgain = false))
    }
}
