package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallPeer
import io.github.yggdrasil_sh.saga.call.CallPhase
import io.github.yggdrasil_sh.saga.call.CallReturn
import io.github.yggdrasil_sh.saga.call.CallUiState
import io.github.yggdrasil_sh.saga.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Laufender Anruf ausserhalb des Anrufbildschirms: Leiste, Rueckkehr aus dem Chat, Zurueck = minimieren */
class CallReturnTest {
    private val peer = CallPeer("marin", "Marin")
    private fun st(phase: CallPhase, chat: Long = 7, connectedAt: Long = 0) =
        CallUiState(phase, "a".repeat(32), chat, true, false, peer, connectedAt = connectedAt)

    @Test
    fun activeFuerAlleLaufendenPhasen() {
        assertFalse(CallReturn.active(null))
        assertFalse(CallReturn.active(st(CallPhase.ENDED)))
        for (p in CallPhase.entries - CallPhase.ENDED) assertTrue(p.name, CallReturn.active(st(p)))
    }

    @Test
    fun leisteZeigtDauerNurVerbunden() {
        val t0 = 1_000_000L
        assertEquals(UiText.Res(R.string.call_bar_active, "1:05"), CallReturn.barText(st(CallPhase.CONNECTED, connectedAt = t0), t0 + 65_000))
        // verbunden, aber noch ohne Startzeit sowie Aufbau/Klingeln: "verbindet"
        assertEquals(UiText.Res(R.string.call_bar_connecting, "Marin"), CallReturn.barText(st(CallPhase.CONNECTED), t0))
        for (p in listOf(CallPhase.PREPARING, CallPhase.RINGING, CallPhase.CONNECTING)) {
            assertEquals(UiText.Res(R.string.call_bar_connecting, "Marin"), CallReturn.barText(st(p), t0))
        }
        assertEquals(UiText.Res(R.string.call_bar_incoming, "Marin"), CallReturn.barText(st(CallPhase.INCOMING), t0))
        // ohne Anzeigenamen: Benutzername
        val anon = st(CallPhase.RINGING).copy(peer = CallPeer("marin", ""))
        assertEquals(UiText.Res(R.string.call_bar_connecting, "marin"), CallReturn.barText(anon, t0))
    }

    @Test
    fun keineLeisteOhneOderNachAnruf() {
        assertNull(CallReturn.barText(null, 0))
        assertNull(CallReturn.barText(st(CallPhase.ENDED, connectedAt = 5), 10_000))
    }

    @Test
    fun uhrVorDerStartzeitZaehltNichtNegativ() {
        assertEquals(UiText.Res(R.string.call_bar_active, "0:00"), CallReturn.barText(st(CallPhase.CONNECTED, connectedAt = 10_000), 9_000))
    }

    @Test
    fun hinweisNurImBetroffenenChat() {
        assertTrue(CallReturn.inChat(st(CallPhase.CONNECTED, chat = 7), 7))
        assertFalse(CallReturn.inChat(st(CallPhase.CONNECTED, chat = 7), 8))
        assertFalse(CallReturn.inChat(st(CallPhase.ENDED, chat = 7), 7))
        assertFalse(CallReturn.inChat(null, 7))
        assertFalse(CallReturn.inChat(st(CallPhase.CONNECTED, chat = 0), 0))
    }

    @Test
    fun anrufKnopfImChat() {
        assertEquals(CallReturn.Dial.START, CallReturn.dial(null, 7))
        assertEquals(CallReturn.Dial.START, CallReturn.dial(st(CallPhase.ENDED), 7))
        assertEquals(CallReturn.Dial.RETURN, CallReturn.dial(st(CallPhase.CONNECTED, chat = 7), 7))
        assertEquals(CallReturn.Dial.RETURN, CallReturn.dial(st(CallPhase.RINGING, chat = 7), 7))
        assertEquals(CallReturn.Dial.BUSY, CallReturn.dial(st(CallPhase.CONNECTED, chat = 7), 9))
    }

    @Test
    fun zurueckMinimiertStattAufzulegen() {
        for (p in CallPhase.entries - CallPhase.ENDED) assertTrue(p.name, CallReturn.backMinimizes(st(p)))
        assertFalse(CallReturn.backMinimizes(st(CallPhase.ENDED)))
        assertFalse(CallReturn.backMinimizes(null))
    }
}
