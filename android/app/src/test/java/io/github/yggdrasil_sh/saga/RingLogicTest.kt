package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallInfo
import io.github.yggdrasil_sh.saga.call.CallLeg
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.RingLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.5: Klingeln bei geschlossener App - Start/Stopp-Entscheidungen des eigenen Ringers (RingLogic) */
class RingLogicTest {
    @Test fun modeFollowsDevice() {
        assertEquals(RingLogic.Mode(true, true), RingLogic.mode(RingLogic.RINGER_NORMAL, RingLogic.FILTER_ALL, false))
        assertEquals("Vibration", RingLogic.Mode(false, true), RingLogic.mode(RingLogic.RINGER_VIBRATE, RingLogic.FILTER_ALL, false))
        assertEquals("Stumm", RingLogic.Mode(false, false), RingLogic.mode(RingLogic.RINGER_SILENT, RingLogic.FILTER_ALL, false))
        assertEquals("unbekannter Filter wie alle", RingLogic.Mode(true, true), RingLogic.mode(RingLogic.RINGER_NORMAL, RingLogic.FILTER_UNKNOWN, false))
    }

    @Test fun doNotDisturb() {
        assertFalse(RingLogic.mode(RingLogic.RINGER_NORMAL, RingLogic.FILTER_NONE, true).any)
        assertFalse(RingLogic.mode(RingLogic.RINGER_NORMAL, RingLogic.FILTER_ALARMS, true).any)
        assertFalse("Nur wichtige, Anrufe nicht von allen", RingLogic.mode(RingLogic.RINGER_NORMAL, RingLogic.FILTER_PRIORITY, false).any)
        assertTrue("Nur wichtige, Anrufe von allen", RingLogic.mode(RingLogic.RINGER_NORMAL, RingLogic.FILTER_PRIORITY, true).sound)
        assertFalse("Nur wichtige + Vibration: kein Ton", RingLogic.mode(RingLogic.RINGER_VIBRATE, RingLogic.FILTER_PRIORITY, true).sound)
    }

    @Test fun startOnlyOncePerCall() {
        assertTrue(RingLogic.shouldStart(null, "a"))
        assertFalse("kein doppelter Ton (Anrufbildschirm offen)", RingLogic.shouldStart("a", "a"))
        assertTrue("anderer Anruf ersetzt", RingLogic.shouldStart("a", "b"))
        assertFalse(RingLogic.shouldStart(null, ""))
        assertTrue(RingLogic.shouldRing(canNotify = true, foreground = false))
        assertTrue(RingLogic.shouldRing(canNotify = false, foreground = true))
        assertFalse("ohne Mitteilung und ohne Oberflaeche nicht klingeln", RingLogic.shouldRing(canNotify = false, foreground = false))
    }

    @Test fun stopDecisions() {
        val dm = CallInfo(id = "x", state = "ringing", role = "callee")
        assertTrue(RingLogic.stillRinging(dm, "me"))
        assertFalse("anderes Geraet hat angenommen", RingLogic.stillRinging(dm.copy(state = "active"), "me"))
        assertFalse("Gegenseite hat aufgelegt", RingLogic.stillRinging(dm.copy(state = "ended", reason = "cancelled"), "me"))
        assertTrue(RingLogic.missed(dm.copy(state = "ended", reason = "cancelled")))
        assertFalse("auf anderem Geraet abgelehnt: nicht verpasst", RingLogic.missed(dm.copy(state = "ended", reason = "declined")))
        val g = CallInfo(id = "g", state = "active", kind = CallRoom.KIND_GROUP, invite = "ringing")
        assertTrue("Gruppe: aktiv heisst nur 'zwei sind dabei'", RingLogic.stillRinging(g, "me"))
        assertFalse("Gruppe: eigenes Geraet beigetreten", RingLogic.stillRinging(g.copy(legs = listOf(CallLeg(who = "me"))), "me"))
        assertFalse("Gruppe: auf anderem Geraet abgelehnt", RingLogic.stillRinging(g.copy(invite = "declined"), "me"))
        assertFalse("Gruppe: beendet", RingLogic.stillRinging(g.copy(state = "ended"), "me"))
    }

    @Test fun timesAndService() {
        assertEquals(48_000L, RingLogic.ringMs(null, null))
        assertEquals(33_000L, RingLogic.ringMs(30.0, 45))
        assertEquals("mindestens 5 s", 8_000L, RingLogic.ringMs(1.0, null))
        assertEquals("mit Dienst: Empfaenger sofort frei", 0L, RingLogic.holdMs(true, 30_000))
        assertEquals(30_000L, RingLogic.holdMs(false, 30_000))
        assertEquals("Broadcast-Frist", RingLogic.HOLD_MAX_MS, RingLogic.holdMs(false, 90_000))
        assertEquals(0L, RingLogic.holdMs(false, -5))
        assertEquals("Vordergrund-Broadcast: 10-s-Frist", 9_000L, RingLogic.holdMs(false, 30_000, fgBroadcast = true))
        assertEquals("schon 6 s vergangen", 3_000L, RingLogic.holdMs(false, 30_000, fgBroadcast = true, elapsedMs = 6_000))
        assertEquals(0L, RingLogic.holdMs(false, 30_000, fgBroadcast = true, elapsedMs = 12_000))
        assertEquals(45_000L, RingLogic.holdMs(false, 90_000, elapsedMs = 10_000))
        assertTrue("ohne Dienst nach dem Festhalten still", RingLogic.stopAfterHold(ringing = true, inService = false))
        assertFalse(RingLogic.stopAfterHold(ringing = true, inService = true))
        assertFalse(RingLogic.stopAfterHold(ringing = false, inService = false))
        assertEquals(4, RingLogic.serviceType(34))
        assertEquals(0, RingLogic.serviceType(28))
        assertEquals("Vibration wiederholt sich (an/aus)", 3, RingLogic.PATTERN.size)
    }

    @Test fun soundSource() {
        assertEquals(RingLogic.Sound.NOW, RingLogic.sound(foreground = true, serviceStarted = false, audibleInBackground = false))
        assertEquals("eigene Verbindung: Ton, sobald der Dienst laeuft", RingLogic.Sound.AFTER_SERVICE,
            RingLogic.sound(foreground = false, serviceStarted = true, audibleInBackground = true))
        assertEquals("Dienst aus dem Hintergrund: stummgeschaltet -> Systemton", RingLogic.Sound.NONE,
            RingLogic.sound(foreground = false, serviceStarted = true, audibleInBackground = false))
        assertEquals(RingLogic.Sound.NONE, RingLogic.sound(foreground = false, serviceStarted = false, audibleInBackground = true))
        val loud = RingLogic.Mode(true, true)
        assertTrue("ohne Dienst: Systemton ueber den Ersatzkanal", RingLogic.fallback(RingLogic.Sound.NONE, loud, notified = true))
        assertFalse("App klingelt selbst: kein zweiter Ton", RingLogic.fallback(RingLogic.Sound.NOW, loud, notified = true))
        assertFalse(RingLogic.fallback(RingLogic.Sound.AFTER_SERVICE, loud, notified = true))
        assertFalse("Vibrationsmodus: kein Ton", RingLogic.fallback(RingLogic.Sound.NONE, RingLogic.Mode(false, true), notified = true))
        assertFalse(RingLogic.fallback(RingLogic.Sound.NONE, loud, notified = false))
    }

    /** Neuer Kanal ohne Ton, alter wird geloescht; Klingel-Dienst mit gueltigem Typ im Manifest */
    @Test fun channelAndManifest() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val svc = File(main, "java/io/github/yggdrasil_sh/saga/call/CallService.kt").readText()
        assertTrue(svc.contains("CH_RING = \"saga_call_ring2\""))
        assertTrue(svc.contains("deleteNotificationChannel(CH_RING_OLD)"))
        assertTrue("FLAG_INSISTENT nur fuer den Ersatzkanal", svc.contains("if (audible) n.flags = n.flags or Notification.FLAG_INSISTENT"))
        val mf = File(main, "AndroidManifest.xml").readText()
        assertTrue(Regex("""\.call\.CallRingService"\s+android:exported="false"\s+android:foregroundServiceType="phoneCall"""").containsMatchIn(mf))
        assertTrue(mf.contains("android.permission.FOREGROUND_SERVICE_PHONE_CALL") && mf.contains("android.permission.MANAGE_OWN_CALLS"))
    }
}
