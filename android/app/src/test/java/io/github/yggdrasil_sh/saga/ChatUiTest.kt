package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.BundleFormatException
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.DeviceNames
import io.github.yggdrasil_sh.saga.push.PushState
import io.github.yggdrasil_sh.saga.ui.chat.ChatUi
import io.github.yggdrasil_sh.saga.ui.push.PushUi
import io.github.yggdrasil_sh.saga.ui.theme.Dark
import io.github.yggdrasil_sh.saga.ui.theme.Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Nachbesser-Runde 2.0: Darstellung von Codes, "Echtheit nicht bestätigt", Schluessel-Buendel, Geraetenamen, Mitteilungen */
class ChatUiTest {
    private val number = (0 until 12).joinToString(" ") { "%05d".format(it * 7919 % 100000) }

    // 1. Sicherheitsnummer 3 x 4, Kopplungs-Code eine Zeile (sonst 2 x 2)
    @Test
    fun safetyNumberThreeRowsOfFour() {
        val rows = ChatFormat.safetyRows(number)
        assertEquals(3, rows.size)
        rows.forEach { r ->
            assertEquals(4, r.size)
            r.forEach { g -> assertTrue(g.length == 5 && g.all(Char::isDigit)) }
        }
        assertEquals(number, rows.flatten().joinToString(" "))
        // auch aus mehrzeiliger Eingabe
        assertEquals(rows, ChatFormat.safetyRows(rows.joinToString("\n") { it.joinToString(" ") }))
    }

    @Test
    fun linkCodeOneLineOrTwoByTwo() {
        val code = "01234 56789 13579 24680"
        assertEquals(listOf(listOf("01234", "56789", "13579", "24680")), ChatFormat.linkCodeRows(code, true))
        assertEquals(listOf(listOf("01234", "56789"), listOf("13579", "24680")), ChatFormat.linkCodeRows(code, false))
    }

    // 3. "Echtheit nicht bestätigt": Hinweis gut lesbar (>= 4,5:1 Text, >= 3:1 Symbol) auf beiden Blasen, hell und dunkel
    @Test
    fun unverifiedHintContrast() {
        for (scheme in listOf(Light, Dark)) {
            for (mine in listOf(false, true)) {
                val bg = ChatUi.bubble(scheme, mine)
                val t = ChatUi.contrast(ChatUi.unverifiedText(scheme, mine), bg)
                val i = ChatUi.contrast(ChatUi.unverifiedIcon(scheme, mine), bg)
                assertTrue("Text ${if (scheme === Light) "hell" else "dunkel"} mine=$mine: $t", t >= 4.5)
                assertTrue("Symbol mine=$mine: $i", i >= 3.0)
                assertEquals(1f, bg.alpha)                                  // Blase voll deckend
            }
        }
    }

    // 5. Wiederherstellungsschluessel: 2 Zeilen a 4 Gruppen, nie mitten in einer Gruppe
    @Test
    fun recoveryKeyTwoRowsOfFour() {
        val key = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val rows = ChatFormat.recoveryRows(ChatFormat.formatRecovery(key))
        assertEquals(listOf("ABCD-EFGH-IJKL-MNOP", "QRST-UVWX-YZ23-4567"), rows)
        rows.forEach { r -> assertTrue(r.split('-').all { it.length == 4 }) }
        assertEquals(rows, ChatFormat.recoveryRows(key))
    }

    // 8. Grenzen beim Oeffnen des Schluessel-Buendels: ops <= 10, mem <= 256 MiB, sonst "Unbekanntes Schlüsselformat"
    @Test
    fun bundleLimits() {
        fun b(ops: Any, mem: Any, v: Any = 1, kdf: String = "argon2id13") =
            "{\"v\":$v,\"kdf\":\"$kdf\",\"ops\":$ops,\"mem\":$mem,\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"nonce\":\"x\",\"ct\":\"x\"}"
        assertEquals(2L to 67108864L, ChatFormat.bundleKdf(b(2, 67108864)))
        assertEquals(10L to (256L shl 20), ChatFormat.bundleKdf(b(10, 256L shl 20)))
        for (bad in listOf(b(11, 67108864), b(2, (256L shl 20) + 1), b(2, 1L shl 30), b(0, 67108864), b(2, 1024), b(2, 67108864, v = 2),
            b(2, 67108864, kdf = "scrypt"), b("\"x\"", 67108864), "nicht json", "[]")) {
            try {
                ChatFormat.bundleKdf(bad)
                fail("haette abgelehnt werden muessen: $bad")
            } catch (_: BundleFormatException) {
            }
        }
    }

    // 9. Geraetenamen menschlich
    @Test
    fun deviceNames() {
        assertEquals("Android Emulator", DeviceNames.pretty("sdk_gphone64_arm64", "Google", "sdk_gphone64_arm64"))
        assertEquals("Annas Pixel", DeviceNames.pretty("Annas Pixel", "Google", "Pixel 8"))
        assertEquals("Google Pixel 8", DeviceNames.pretty(null, "google", "Pixel 8"))
        assertEquals("Samsung SM-S911B", DeviceNames.pretty("", "samsung", "SM-S911B"))
        assertEquals("OnePlus CPH2449", DeviceNames.pretty("CPH2449", "OnePlus", "CPH2449"))
        assertEquals("Fairphone FP5", DeviceNames.pretty("fp5_eea", "Fairphone", "FP5"))
    }

    // 6. Mitteilungen: Hauptschalter/Zustand widerspruchsfrei, "Worüber?" ausgegraut ohne Mitteilungen
    @Test
    fun pushSwitches() {
        assertFalse(PushUi.switchOn(false, PushState.ON))
        assertTrue(PushUi.switchOn(true, PushState.REGISTERING))
        assertFalse(PushUi.switchOn(true, PushState.OFF))
        assertFalse(PushUi.categoriesEnabled(false, PushState.ON, 3))
        assertFalse(PushUi.categoriesEnabled(true, PushState.OFF, 0))
        assertTrue(PushUi.categoriesEnabled(true, PushState.OFF, 1))
        assertTrue(PushUi.categoriesEnabled(true, PushState.ON, 0))
    }
}
