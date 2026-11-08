package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.AudioKind
import io.github.yggdrasil_sh.saga.call.AudioRoute
import io.github.yggdrasil_sh.saga.call.AudioRoutes
import io.github.yggdrasil_sh.saga.call.CallControls
import io.github.yggdrasil_sh.saga.call.CallControls.AudioBtn
import io.github.yggdrasil_sh.saga.call.CallControls.Btn
import io.github.yggdrasil_sh.saga.call.CallControls.More
import io.github.yggdrasil_sh.saga.ui.chat.ChatUi
import io.github.yggdrasil_sh.saga.ui.theme.SagaCallToggleColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design-Runde 5 (Anruf-Oberflaeche): Knopfleiste, Reihen, Audio-Ausgabe, Infozeile, Mehr-Blatt */
class R5CallTest {
    private val ear = AudioRoute(1, AudioKind.EARPIECE)
    private val spk = AudioRoute(2, AudioKind.SPEAKER)
    private val bt = AudioRoute(3, AudioKind.BLUETOOTH, "Kopfhoerer X")
    private val btBle = AudioRoute(4, AudioKind.BLUETOOTH, "Kopfhoerer X")

    /** CH-19: Telefon -> Umschalter, Tablet ohne Hoerer -> kein Knopf, mehr als zwei Ausgaben -> Auswahl */
    @Test
    fun audioKnopf() {
        assertEquals(AudioBtn.TOGGLE, CallControls.audioButton(AudioRoutes(listOf(ear, spk), active = 1)))
        assertEquals(AudioBtn.NONE, CallControls.audioButton(AudioRoutes(listOf(spk), active = 2, earpiece = false)))
        assertEquals(AudioBtn.PICKER, CallControls.audioButton(AudioRoutes(listOf(ear, spk, bt), active = 3)))
        // Tablet mit Bluetooth: zwischen Lautsprecher und Kopfhoerer waehlen
        assertEquals(AudioBtn.PICKER, CallControls.audioButton(AudioRoutes(listOf(spk, bt), active = 2, earpiece = false)))
        // SCO und BLE desselben Geraets zaehlen einmal (Tablet: Lautsprecher + ein Kopfhoerer -> Auswahl, nicht 3 Eintraege)
        assertEquals(2, CallControls.pickerRoutes(AudioRoutes(listOf(spk, bt, btBle), earpiece = false)).size)
        assertEquals(AudioBtn.PICKER, CallControls.audioButton(AudioRoutes(listOf(ear, spk, bt, btBle), active = 4)))
        // ohne Liste (Android < 12): nur der Hoerer entscheidet
        assertEquals(AudioBtn.TOGGLE, CallControls.audioButton(AudioRoutes(earpiece = true)))
        assertEquals(AudioBtn.NONE, CallControls.audioButton(AudioRoutes(earpiece = false)))
    }

    @Test
    fun audioAuswahl() {
        val r = AudioRoutes(listOf(bt, spk, btBle, ear), active = 4)
        assertEquals(listOf(ear, spk, bt), CallControls.pickerRoutes(r))
        // aktiv ist der BLE-Eintrag - das Haekchen steht am (einzigen) Bluetooth-Eintrag
        assertTrue(CallControls.isActive(r, bt))
        assertFalse(CallControls.isActive(r, spk))
        assertFalse(CallControls.isActive(AudioRoutes(listOf(ear, spk), active = null), ear))
    }

    /** CH-18: vier feste Knoepfe + Mehr; Begleitgeraet: "Ton" statt Audio-Ausgabe; Tablet ohne Audio-Knopf */
    @Test
    fun knopfleiste() {
        assertEquals(listOf(Btn.MIC, Btn.CAM, Btn.AUDIO, Btn.MORE, Btn.HANGUP), CallControls.bar(AudioBtn.TOGGLE, playback = false))
        assertEquals(listOf(Btn.MIC, Btn.CAM, Btn.AUDIO, Btn.MORE, Btn.HANGUP), CallControls.bar(AudioBtn.PICKER, playback = false))
        assertEquals(listOf(Btn.MIC, Btn.CAM, Btn.MORE, Btn.HANGUP), CallControls.bar(AudioBtn.NONE, playback = false))
        assertEquals(listOf(Btn.MIC, Btn.CAM, Btn.PLAYBACK, Btn.MORE, Btn.HANGUP), CallControls.bar(AudioBtn.NONE, playback = true))
    }

    @Test
    fun mehrBlatt() {
        assertEquals(listOf(More.DETAILS), CallControls.moreItems(connected = false, cam = false))
        assertEquals(listOf(More.SHARE, More.SWITCH_CAM, More.DETAILS), CallControls.moreItems(connected = true, cam = true))
        assertEquals(
            listOf(More.SHARE, More.LAYOUT, More.PEOPLE, More.SELF_SIZE, More.FULLSCREEN, More.DETAILS),
            CallControls.moreItems(connected = true, cam = false, layout = true, people = true, selfSize = true, fullscreen = true),
        )
    }

    /** CH-15: nur mittlere/schwache Qualitaet zeigt den Hinweis; TURN-Hinweis nur ohne TURN, direkt und schwach */
    @Test
    fun qualitaetUndTurn() {
        assertFalse(CallControls.weak("good")); assertFalse(CallControls.weak("")); assertTrue(CallControls.weak("mid")); assertTrue(CallControls.weak("bad"))
        assertTrue(CallControls.turnHint(false, "direct", "bad"))
        assertFalse(CallControls.turnHint(true, "direct", "bad"))
        assertFalse(CallControls.turnHint(null, "direct", "bad"))
        assertFalse(CallControls.turnHint(false, "relay", "bad"))
        assertFalse(CallControls.turnHint(false, "direct", "good"))
    }

    /** CH-17: eine Schrift je Reihe, mind. 11 sp; passt es nicht, kommt der Rest in die zweite Reihe */
    @Test
    fun reihen() {
        // passt mit 5 je Reihe
        assertEquals(CallControls.Plan(5, 11f), CallControls.plan(5) { 11f })
        // erst ab 3 je Reihe passt es -> 3 + 2
        val p = CallControls.plan(5) { k -> if (k <= 3) 11f else null }
        assertEquals(3, p.perRow); assertEquals(listOf(3, 2), p.rows(5))
        // nie mehr als zwei Reihen: passt es erst einzeln, lieber halbe Reihe mit Trennung
        val q = CallControls.plan(5) { k -> if (k == 1) 11f else null }
        assertEquals(CallControls.Plan(3, 11f, hyphenate = true), q)
        // Obergrenze je Reihe
        assertEquals(4, CallControls.plan(5, maxPerRow = 4) { 11f }.perRow)
        assertEquals(listOf(4), CallControls.Plan(4, 11f).rows(4))
        assertEquals(emptyList<Int>(), CallControls.Plan(4, 11f).rows(0))
    }

    @Test
    fun reihenSchrift() {
        assertEquals(12f, CallControls.rowFont(12f) { true }!!, 0.001f)
        assertEquals(11.5f, CallControls.rowFont(12f) { it <= 11.5f }!!, 0.001f)
        assertNull(CallControls.rowFont(12f) { false })
        assertEquals(11f, CallControls.rowFont(10f) { true }!!, 0.001f)       // nie unter 11 sp
        assertNull(CallControls.rowFont(11f) { it < 11f })
    }

    /** CH-03: an = hell mit dunklem Symbol, aus = dunkel mit hellem Symbol (beide >= 4,5:1), kein Gold */
    @Test
    fun umschalterFarben() {
        val c = SagaCallToggleColors.Default
        assertTrue(ChatUi.contrast(c.onContent, c.onContainer) >= 4.5)
        assertTrue(ChatUi.contrast(c.offContent, c.offContainer) >= 4.5)
        // an und aus klar unterscheidbar (Flaechen untereinander >= 3:1)
        assertTrue(ChatUi.contrast(c.onContainer, c.offContainer) >= 3.0)
    }

    /** R5-Fix: Werte im Blatt "Verbindungsdetails" nie mitten im Wort trennen */
    @Test
    fun keineSilbentrennung() {
        assertEquals(androidx.compose.ui.text.style.Hyphens.None, io.github.yggdrasil_sh.saga.call.CallDesign.wordWrap.hyphens)
        assertEquals(androidx.compose.ui.text.style.LineBreak.Simple, io.github.yggdrasil_sh.saga.call.CallDesign.wordWrap.lineBreak)
    }

    /** R5-Fix: Infozeile immer kurz (klingelnd wie verbunden), Langform nur im Blatt */
    @Test
    fun sicherheitszeile() {
        assertEquals(R.string.call_e2e, CallControls.securityShort(true))
        assertEquals(R.string.r5_call_encrypted_short, CallControls.securityShort(false))
        assertEquals(R.string.call_e2e, CallControls.securityLong(true))
        assertEquals(R.string.r5_call_unverified, CallControls.securityLong(false))
    }
}
