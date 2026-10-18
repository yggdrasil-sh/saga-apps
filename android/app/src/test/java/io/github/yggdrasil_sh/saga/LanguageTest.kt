package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.acceptLanguage
import io.github.yggdrasil_sh.saga.util.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Sprachwahl in der App: Automatisch/de/en/es/ru/th, Accept-Language folgt der Wahl */
class LanguageTest {
    @Test fun optionsAreAutoGermanEnglishSpanishRussianThai() {
        assertEquals(listOf("", "de", "en", "es", "ru", "th"), AppLanguage.OPTIONS)
        assertEquals(listOf("Deutsch", "English", "Español", "Русский", "ไทย"), AppLanguage.OPTIONS.drop(1).map(AppLanguage::nativeName))
    }

    @Test fun thaiDatesUseGregorianCalendar() {
        val th = AppLanguage.formatLocale(java.util.Locale.forLanguageTag("th-TH"))
        assertEquals("gregory", th.getUnicodeLocaleType("ca"))
        assertEquals("th", th.language)
        assertEquals(java.util.Locale.GERMANY, AppLanguage.formatLocale(java.util.Locale.GERMANY))
        // ausdruecklich gewaehlter Kalender bleibt
        assertEquals("buddhist", AppLanguage.formatLocale(java.util.Locale.forLanguageTag("th-TH-u-ca-buddhist")).getUnicodeLocaleType("ca"))
        val d = java.time.LocalDate.of(2026, 10, 18)
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM y", th))
        assertTrue(d, d.contains("2026"))
    }

    @Test fun normalizeKeepsSupportedPrimaryLanguage() {
        assertEquals("de", AppLanguage.normalize("de-DE"))
        assertEquals("de", AppLanguage.normalize("de_AT"))
        assertEquals("es", AppLanguage.normalize("ES-mx"))
        assertEquals("ru", AppLanguage.normalize("ru-RU"))
        assertEquals("th", AppLanguage.normalize("th_TH"))
        assertEquals("en", AppLanguage.normalize("en"))
        assertEquals("", AppLanguage.normalize("fr-FR"))
        assertEquals("", AppLanguage.normalize(""))
        assertEquals("", AppLanguage.normalize(null))
        assertEquals("", AppLanguage.normalize("de\r\nX-Evil: 1"))
    }

    @Test fun acceptLanguageFollowsChoiceElseUiLanguage() {
        // Geraet auf Deutsch, in der App English gewaehlt -> en
        assertEquals("en", acceptLanguage(AppLanguage.headerLanguage("en", "de")))
        // Automatisch -> Sprache der angezeigten Texte
        assertEquals("de", acceptLanguage(AppLanguage.headerLanguage("", "de")))
        assertEquals("es", acceptLanguage(AppLanguage.headerLanguage("es", "en")))
        assertEquals("ru", acceptLanguage(AppLanguage.headerLanguage("ru", "de")))
        assertEquals("th", acceptLanguage(AppLanguage.headerLanguage("th", "en")))
        assertEquals("th-TH, th;q=0.9", acceptLanguage("th-TH"))
    }

    @Test fun localeConfigAndManifestListAllLanguages() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val cfg = File(main, "res/xml/locales_config.xml").readText()
        listOf("en", "de", "es", "ru", "th").forEach { assertTrue("locales_config: $it", cfg.contains("android:name=\"$it\"")) }
        assertTrue(File(main, "AndroidManifest.xml").readText().contains("android:localeConfig=\"@xml/locales_config\""))
        // Texte der Sprachwahl in allen Sprachen
        listOf("values", "values-de", "values-es", "values-ru", "values-th").forEach { d ->
            val s = File(main, "res/$d/strings_lang.xml").readText()
            listOf("language", "language_auto", "language_hint", "language_change").forEach { k ->
                assertTrue("$d: $k", s.contains("name=\"$k\""))
            }
        }
    }
}
