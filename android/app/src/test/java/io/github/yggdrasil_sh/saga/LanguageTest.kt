package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.acceptLanguage
import io.github.yggdrasil_sh.saga.util.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Sprachwahl in der App: Automatisch/de/en/es, Accept-Language folgt der Wahl */
class LanguageTest {
    @Test fun optionsAreAutoGermanEnglishSpanish() {
        assertEquals(listOf("", "de", "en", "es"), AppLanguage.OPTIONS)
        assertEquals(listOf("Deutsch", "English", "Español"), AppLanguage.OPTIONS.drop(1).map(AppLanguage::nativeName))
    }

    @Test fun normalizeKeepsSupportedPrimaryLanguage() {
        assertEquals("de", AppLanguage.normalize("de-DE"))
        assertEquals("de", AppLanguage.normalize("de_AT"))
        assertEquals("es", AppLanguage.normalize("ES-mx"))
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
    }

    @Test fun localeConfigAndManifestListAllLanguages() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val cfg = File(main, "res/xml/locales_config.xml").readText()
        listOf("en", "de", "es").forEach { assertTrue("locales_config: $it", cfg.contains("android:name=\"$it\"")) }
        assertTrue(File(main, "AndroidManifest.xml").readText().contains("android:localeConfig=\"@xml/locales_config\""))
        // Texte der Sprachwahl in allen drei Sprachen
        listOf("values", "values-de", "values-es").forEach { d ->
            val s = File(main, "res/$d/strings_lang.xml").readText()
            listOf("language", "language_auto", "language_hint", "language_change").forEach { k ->
                assertTrue("$d: $k", s.contains("name=\"$k\""))
            }
        }
    }
}
