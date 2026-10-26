package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.settings.SettingsArea
import io.github.yggdrasil_sh.saga.util.UpdateHint
import io.github.yggdrasil_sh.saga.util.UpdateHint.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.5 Update-Hinweis (Einstellungen -> Info) und Spruenge in die Bereiche - gleiche Faelle wie iOS UpdateHintTests */
class UpdateHintTest {
    private fun v(s: String) = Version.parse(s)!!

    @Test fun versionCompare() {
        assertTrue(v("2.5.0") > v("2.4.9"))
        assertTrue("2.10 > 2.9 (nicht als Text)", v("2.10.0") > v("2.9.0"))
        assertTrue("Release > jede rc", v("2.5.0") > v("2.5.0-rc.140"))
        assertTrue("rc numerisch", v("2.5.0-rc.10") > v("2.5.0-rc.9"))
        assertTrue(v("2.5.1-rc.1") > v("2.5.0"))
        assertEquals(0, v("2.5.0-rc.3").compareTo(v("2.5.0-rc.3")))
        listOf("2.5", "2.5.0-beta", "dev", "2.5.0-rc.", "v2.5.0", "", "2.5.0-rc.140-debug", "2.5.0-debug").forEach {
            assertNull("ungueltig: $it", Version.parse(it))
        }
        assertTrue(UpdateHint.isNewer("2.5.0-rc.141", "2.5.0-rc.140"))
        assertFalse(UpdateHint.isNewer("2.5.0-rc.140", "2.5.0-rc.140"))
        assertFalse(UpdateHint.isNewer("2.4.0", "2.5.0"))
        assertFalse(UpdateHint.isNewer("3.0.0", "dev-build"))
    }

    @Test fun channelAndUrl() {
        assertEquals("testing", UpdateHint.channel("2.5.0-rc.140"))
        assertEquals("stable", UpdateHint.channel("2.5.0"))
        assertNull("Debug-Build: keine Pruefung", UpdateHint.channel("2.5.0-rc.3-debug"))
        assertEquals("https://raw.githubusercontent.com/yggdrasil-sh/saga-apps/appcast/testing.json", UpdateHint.feedUrl("2.5.0-rc.1"))
        assertTrue(UpdateHint.feedUrl("2.5.0")!!.endsWith("/appcast/stable.json"))
        assertNull(UpdateHint.feedUrl("local"))
    }

    @Test fun links() {
        assertNotNull(UpdateHint.safeLink("https://github.com/o/r/releases/tag/v1.0.0"))
        assertNull(UpdateHint.safeLink("http://github.com/o/r"))
        assertNull(UpdateHint.safeLink("https://github.com.evil.example/x"))
        assertNull(UpdateHint.safeLink("https://user@github.com/x"))
        assertNull(UpdateHint.safeLink("https://github.com:8443/x"))
        assertNull(UpdateHint.safeLink("https://objects.githubusercontent.com/x"))
        assertNull(UpdateHint.safeLink("javascript:alert(1)"))
        assertNull(UpdateHint.safeLink(42))
    }

    private val good = """{"format":1,"channel":"testing","version":"2.5.0-rc.141","tag":"v2.5.0-rc.141","commit":"abc1234",""" +
        """"published":"2026-10-26T06:00:00Z","page":"https://github.com/o/r/releases/tag/v2.5.0-rc.141",""" +
        """"assets":{"android":"https://github.com/o/r/releases/download/v2.5.0-rc.141/Saga.apk","macos":"https://evil.example/Saga-macOS.dmg","rpm":7}}"""

    @Test fun parseFile() {
        val r = UpdateHint.parse(good.toByteArray(), "testing")!!
        assertEquals("2.5.0-rc.141", r.version)
        assertNotNull(r.assets["android"])
        assertNull("unsicherer Link faellt weg", r.assets["macos"])
        assertNull("kein Text", r.assets["rpm"])
        assertTrue(r.page!!.startsWith("https://github.com/"))
        assertNull("fremder Kanal", UpdateHint.parse(good.toByteArray(), "stable"))
        assertNull("unbekanntes format", UpdateHint.parse(good.replace("\"format\":1", "\"format\":2").toByteArray(), "testing"))
        assertNull("format als Text", UpdateHint.parse(good.replace("\"format\":1", "\"format\":\"1\"").toByteArray(), "testing"))
        assertNull("Version passt nicht zum Kanal", UpdateHint.parse("""{"format":1,"version":"2.5.0"}""".toByteArray(), "testing"))
        assertTrue("ohne Assets", UpdateHint.parse("""{"format":1,"version":"2.5.0"}""".toByteArray(), "stable")!!.assets.isEmpty())
        assertNull("kein JSON", UpdateHint.parse("kaputt".toByteArray(), "stable"))
        assertNull("zu gross", UpdateHint.parse(ByteArray(UpdateHint.MAX_BYTES + 1) { 32 }, "stable"))
    }

    @Test fun buttonStatusAvailable() {
        val apk = UpdateHint.Release("2.5.0", "https://github.com/o/r/releases/tag/v2.5.0", mapOf("android" to "https://github.com/o/r/releases/download/v2.5.0/Saga.apk"))
        val pageOnly = apk.copy(assets = emptyMap())
        val bare = UpdateHint.Release("2.5.0", null, emptyMap())
        assertEquals("APK zuerst", apk.assets["android"], UpdateHint.action(apk))
        assertEquals("sonst Release-Seite", pageOnly.page, UpdateHint.action(pageOnly))
        assertNull(UpdateHint.action(bare))
        assertEquals(UpdateHint.Button.CHECKING, UpdateHint.button(true, apk))
        assertEquals(UpdateHint.Button.UPDATE, UpdateHint.button(false, apk))
        assertEquals(UpdateHint.Button.CHECK, UpdateHint.button(false, null))
        assertEquals("ohne Link kein Update-Knopf", UpdateHint.Button.CHECK, UpdateHint.button(false, bare))
        val now = 1_800_000_000_000L
        assertEquals(UpdateHint.Status.Available("2.5.0"), UpdateHint.status(apk, true, now))
        assertEquals(UpdateHint.Status.Failed, UpdateHint.status(null, true, now))
        assertEquals(UpdateHint.Status.UpToDate, UpdateHint.status(null, false, now))
        assertEquals(UpdateHint.Status.None, UpdateHint.status(null, false, null))
        assertNull("Release nicht fuer den testing-Kanal", UpdateHint.available(apk, "2.5.0-rc.9"))
        assertEquals(apk, UpdateHint.available(apk, "2.4.1"))
        assertNull(UpdateHint.available(apk, "2.5.0"))
        assertNull(UpdateHint.available(apk, "2.4.1-debug"))
    }

    @Test fun dueAndCache() {
        val now = 1_800_000_000_000L
        val h = 3600_000L
        assertTrue(UpdateHint.due(null, now, UpdateHint.START_INTERVAL_MS))
        assertFalse(UpdateHint.due(now - h, now, UpdateHint.START_INTERVAL_MS))
        assertTrue(UpdateHint.due(now - 13 * h, now, UpdateHint.START_INTERVAL_MS))
        assertTrue("Uhr zurueckgestellt", UpdateHint.due(now + h, now, UpdateHint.START_INTERVAL_MS))
        val c = UpdateHint.Cache(now, UpdateHint.Release("2.5.0", "https://github.com/o/r/releases/tag/v2.5.0", mapOf("android" to "https://github.com/o/r/a.apk")))
        assertEquals(c, UpdateHint.decode(UpdateHint.encode(c)))
        assertEquals(UpdateHint.Cache(now, null), UpdateHint.decode(UpdateHint.encode(UpdateHint.Cache(now, null))))
        assertNull(UpdateHint.decode("x"))
        assertNull(UpdateHint.decode(null))
        // manipulierter Cache: fremder Link faellt weg
        val bad = UpdateHint.encode(c).replace("https://github.com/o/r/a.apk", "https://evil.example/a.apk")
        assertTrue(UpdateHint.decode(bad)!!.release!!.assets.isEmpty())
    }

    @Test fun settingsJumps() {
        assertEquals(SettingsArea.EINSTELLUNGEN, SettingsArea.of("chats"))
        assertEquals("chats", SettingsArea.scrollTarget("chats"))
        assertEquals(SettingsArea.KONTO, SettingsArea.of("backup"))
        assertEquals(SettingsArea.Sub.BACKUP, SettingsArea.sub("backup"))
        assertNull("Unterseite statt Scrollen", SettingsArea.scrollTarget("backup"))
        assertEquals(SettingsArea.EINSTELLUNGEN, SettingsArea.of("nextcloud"))
        assertEquals(SettingsArea.Sub.NEXTCLOUD, SettingsArea.sub("nextcloud"))
        assertEquals(SettingsArea.KONTO, SettingsArea.of("public"))
        assertEquals(SettingsArea.EINSTELLUNGEN, SettingsArea.of("notifs"))
        assertEquals(SettingsArea.INFO, SettingsArea.of("info"))
        assertNull(SettingsArea.scrollTarget("info"))
        assertNull(SettingsArea.of("YES"))
        assertNull(SettingsArea.of(null))
    }

    /** Texte in allen Sprachen der App */
    @Test fun stringsInAllLanguages() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val keys = Regex("name=\"([a-z0-9_]+)\"").findAll(File(main, "res/values/strings_settings25.xml").readText()).map { it.groupValues[1] }.toList()
        assertTrue(keys.size >= 20)
        listOf("values-de", "values-es", "values-ru", "values-th").forEach { d ->
            val s = File(main, "res/$d/strings_settings25.xml").readText()
            keys.forEach { k -> assertTrue("$d: $k", s.contains("name=\"$k\"")) }
        }
    }
}
