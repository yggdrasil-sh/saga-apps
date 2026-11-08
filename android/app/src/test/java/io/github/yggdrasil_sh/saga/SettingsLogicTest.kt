package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.settings.AppAppearance
import io.github.yggdrasil_sh.saga.ui.settings.SettingsLogic
import io.github.yggdrasil_sh.saga.ui.settings.SettingsLogic.Category
import io.github.yggdrasil_sh.saga.ui.settings.SettingsLogic.NotifGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design-Runde 4 (r4-settings): Kategorien der Hauptliste, Gruppen der Benachrichtigungen, Darstellung */
class SettingsLogicTest {
    @Test fun categoriesAlwaysAndOptional() {
        assertEquals(
            listOf(Category.NOTIFICATIONS, Category.ACCOUNT, Category.INFO),
            SettingsLogic.categories(chats = false, backup = false, nextcloud = false),
        )
        assertEquals(
            listOf(Category.NOTIFICATIONS, Category.CHATS, Category.BACKUP, Category.NEXTCLOUD, Category.ACCOUNT, Category.INFO),
            SettingsLogic.categories(chats = true, backup = true, nextcloud = true),
        )
        assertEquals(
            listOf(Category.NOTIFICATIONS, Category.BACKUP, Category.ACCOUNT, Category.INFO),
            SettingsLogic.categories(chats = false, backup = true, nextcloud = false),
        )
    }

    @Test fun notifGroups() {
        assertEquals(NotifGroup.PAGES, SettingsLogic.groupOf("share.added"))
        assertEquals(NotifGroup.PAGES, SettingsLogic.groupOf("comment.reply"))
        assertEquals(NotifGroup.PAGES, SettingsLogic.groupOf("db.assigned"))
        assertEquals(NotifGroup.MENTIONS, SettingsLogic.groupOf("page.mention"))
        assertEquals(NotifGroup.MENTIONS, SettingsLogic.groupOf("chat.mention"))
        assertEquals(NotifGroup.CHATS, SettingsLogic.groupOf("chat.message"))
        assertEquals(NotifGroup.CHATS, SettingsLogic.groupOf("call.missed"))
        assertEquals(NotifGroup.OTHER, SettingsLogic.groupOf("backup.failed"))
        assertEquals("unbekannte Art bleibt sichtbar", NotifGroup.OTHER, SettingsLogic.groupOf("neu.irgendwas"))
    }

    @Test fun groupedKeepsServerOrderAndDropsEmpty() {
        val kinds = listOf("chat.message", "share.added", "page.mention", "comment.new", "call.missed", "userbackup.failed")
        val g = SettingsLogic.grouped(kinds) { it }
        assertEquals(listOf(NotifGroup.PAGES, NotifGroup.MENTIONS, NotifGroup.CHATS, NotifGroup.OTHER), g.map { it.first })
        assertEquals(listOf("share.added", "comment.new"), g[0].second)
        assertEquals(listOf("chat.message", "call.missed"), g[2].second)
        assertEquals(listOf(NotifGroup.CHATS), SettingsLogic.grouped(listOf("chat.message")) { it }.map { it.first })
        assertTrue(SettingsLogic.grouped(emptyList<String>()) { it }.isEmpty())
    }

    @Test fun appearance() {
        assertEquals(AppAppearance.Mode.SYSTEM, AppAppearance.Mode.of(null))
        assertEquals(AppAppearance.Mode.SYSTEM, AppAppearance.Mode.of("quatsch"))
        assertEquals(AppAppearance.Mode.DARK, AppAppearance.Mode.of("dark"))
        AppAppearance.Mode.entries.forEach { assertEquals(it, AppAppearance.Mode.of(it.key)) }
        assertTrue(AppAppearance.isDark(AppAppearance.Mode.SYSTEM, systemDark = true))
        assertFalse(AppAppearance.isDark(AppAppearance.Mode.SYSTEM, systemDark = false))
        assertFalse(AppAppearance.isDark(AppAppearance.Mode.LIGHT, systemDark = true))
        assertTrue(AppAppearance.isDark(AppAppearance.Mode.DARK, systemDark = false))
        // UiModeManager: AUTO = 0, NO = 1, YES = 2
        assertEquals(0, AppAppearance.nightMode(AppAppearance.Mode.SYSTEM))
        assertEquals(1, AppAppearance.nightMode(AppAppearance.Mode.LIGHT))
        assertEquals(2, AppAppearance.nightMode(AppAppearance.Mode.DARK))
    }

    /** Texte in allen Sprachen der App */
    @Test fun stringsInAllLanguages() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val re = Regex("name=\"([a-z0-9_]+)\"")
        val keys = re.findAll(File(main, "res/values/strings_r4settings.xml").readText()).map { it.groupValues[1] }.toList()
        assertTrue(keys.size >= 20)
        listOf("values-de", "values-es", "values-ru", "values-th").forEach { d ->
            val s = File(main, "res/$d/strings_r4settings.xml").readText()
            assertEquals(d, keys, re.findAll(s).map { it.groupValues[1] }.toList())
        }
    }
}
