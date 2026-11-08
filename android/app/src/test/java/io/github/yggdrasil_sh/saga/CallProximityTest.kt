package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.AudioKind
import io.github.yggdrasil_sh.saga.call.AudioRoute
import io.github.yggdrasil_sh.saga.call.AudioRoutes
import io.github.yggdrasil_sh.saga.call.CallPeer
import io.github.yggdrasil_sh.saga.call.CallPhase
import io.github.yggdrasil_sh.saga.call.CallProximity
import io.github.yggdrasil_sh.saga.call.CallUiState
import io.github.yggdrasil_sh.saga.call.Meeting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feldtest: Display am Ohr aus - nur reine Tonanrufe ueber den Hoerer, im Vordergrund, nicht im Bild-im-Bild */
class CallProximityTest {
    private fun use(
        phase: CallPhase = CallPhase.CONNECTED, cam: Boolean = false, sharing: Boolean = false, grid: Boolean = false,
        ear: Boolean = true, fg: Boolean = true, pip: Boolean = false,
    ) = CallProximity.shouldUseProximity(phase, cam, sharing, grid, ear, fg, pip)

    @Test
    fun phasen() {
        assertTrue(use(CallPhase.RINGING))          // ausgehend, klingelt beim Gegenueber
        assertTrue(use(CallPhase.CONNECTING))
        assertTrue(use(CallPhase.CONNECTED))
        assertFalse(use(CallPhase.INCOMING))        // eigenes Klingeln, noch nicht angenommen
        assertFalse(use(CallPhase.PREPARING))
        assertFalse(use(CallPhase.ENDED))
    }

    @Test
    fun bedingungen() {
        assertFalse(use(cam = true))
        assertFalse(use(sharing = true))
        assertFalse(use(grid = true))
        assertFalse(use(ear = false))               // Lautsprecher, Bluetooth, Kabel
        assertFalse(use(fg = false))
        assertFalse(use(pip = true))
    }

    @Test
    fun hoerer() {
        val ear = AudioRoute(1, AudioKind.EARPIECE); val spk = AudioRoute(2, AudioKind.SPEAKER)
        val bt = AudioRoute(3, AudioKind.BLUETOOTH, "X"); val wired = AudioRoute(4, AudioKind.WIRED)
        assertTrue(CallProximity.onEarpiece(AudioRoutes(listOf(ear, spk), active = 1), speaker = false))
        assertFalse(CallProximity.onEarpiece(AudioRoutes(listOf(ear, spk), active = 2), speaker = true))
        assertFalse(CallProximity.onEarpiece(AudioRoutes(listOf(ear, spk, bt), active = 3), speaker = false))
        assertFalse(CallProximity.onEarpiece(AudioRoutes(listOf(ear, spk, wired), active = 4), speaker = false))
        assertFalse(CallProximity.onEarpiece(AudioRoutes(listOf(ear, spk), active = null), speaker = false))
        // ohne Liste (Android < 12)
        assertTrue(CallProximity.onEarpiece(AudioRoutes(earpiece = true), speaker = false))
        assertFalse(CallProximity.onEarpiece(AudioRoutes(earpiece = true), speaker = true))
        assertFalse(CallProximity.onEarpiece(AudioRoutes(earpiece = false), speaker = false))   // Tablet
    }

    @Test
    fun raster() {
        val st = CallUiState(CallPhase.CONNECTED, "a".repeat(32), 1, true, false, CallPeer("p", "P"))
        assertFalse(CallProximity.videoGrid(st))                                    // 1:1 ohne Raum
        // Meeting-Geraete mit Kamera, aber ohne Kachelansicht: kein Raster
        assertFalse(CallProximity.videoGrid(st.copy(meet = listOf(Meeting.Dev("w", "W", "d1", cam = true)))))
        val mesh = st.copy(room = io.github.yggdrasil_sh.saga.call.RoomUi(mesh = true))
        assertFalse(CallProximity.videoGrid(mesh))                                  // Gruppe nur mit Ton
        assertTrue(CallProximity.videoGrid(mesh.copy(meet = listOf(Meeting.Dev("w", "W", "d1", cam = true)))))
        assertEquals(1800f * 8, CallProximity.TIMEOUT_MS / 1000f, 0.01f)
    }
}
