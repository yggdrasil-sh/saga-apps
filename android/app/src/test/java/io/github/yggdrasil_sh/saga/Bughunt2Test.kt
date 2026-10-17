package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallLogic
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
}
