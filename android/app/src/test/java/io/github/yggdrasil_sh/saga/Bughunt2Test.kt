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

    @Test fun lanPromptSettingsOnlyWhenSystemStopsAsking() {
        // erste Ablehnung: System fragt noch einmal -> Knopf "Erlauben"; danach gesperrt -> "Einstellungen oeffnen"
        assertFalse(io.github.yggdrasil_sh.saga.ui.common.LocalNetworkFlow.openSettingsNext(systemMayAskAgain = true))
        assertTrue(io.github.yggdrasil_sh.saga.ui.common.LocalNetworkFlow.openSettingsNext(systemMayAskAgain = false))
    }
}
