package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.AudioKind
import io.github.yggdrasil_sh.saga.call.CallControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 (9.11.) Geraete im Anruf: Ton-Weg ueber Telecom (Knoten-Anruf) und Ein-/Ausstecken ohne Ueberstimmen der Wahl */
class CallGeraeteTest {
    @Test
    fun telecomWege() {
        // CallAudioState.ROUTE_EARPIECE 1, ROUTE_BLUETOOTH 2, ROUTE_WIRED_HEADSET 4, ROUTE_SPEAKER 8
        assertEquals(listOf(1, 2, 4, 8), listOf(AudioKind.EARPIECE, AudioKind.BLUETOOTH, AudioKind.WIRED, AudioKind.SPEAKER).map { CallControls.telecomRoute(it) })
        // CallEndpoint.TYPE_EARPIECE 1, TYPE_BLUETOOTH 2, TYPE_WIRED_HEADSET 3, TYPE_SPEAKER 4
        assertEquals(listOf(1, 2, 3, 4), listOf(AudioKind.EARPIECE, AudioKind.BLUETOOTH, AudioKind.WIRED, AudioKind.SPEAKER).map { CallControls.endpointType(it) })
    }

    @Test
    fun einAusstecken() {
        assertFalse(CallControls.reroute(first = true, headsetAdded = true, activeRemoved = false))     // Aufruf beim Anmelden
        assertTrue(CallControls.reroute(first = false, headsetAdded = true, activeRemoved = false))     // AirPods dazu
        assertTrue(CallControls.reroute(first = false, headsetAdded = false, activeRemoved = true))     // aktives Headset weg
        assertFalse(CallControls.reroute(first = false, headsetAdded = false, activeRemoved = false))   // anderes Geraet: Wahl bleibt
    }
}
