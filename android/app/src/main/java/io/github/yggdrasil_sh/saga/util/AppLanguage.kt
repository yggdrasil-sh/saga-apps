package io.github.yggdrasil_sh.saga.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * Sprache der App unabhaengig von der Geraetesprache (fremdes/geteiltes Geraet): Automatisch, Deutsch, English, Español,
 * Русский, ไทย.
 *
 * Android 13+: Sprache pro App ueber den LocaleManager - das System merkt sie sich, zeigt sie auch unter
 * Einstellungen > Apps > Saga > Sprache und wendet sie sofort an (MainActivity behandelt locale-Aenderungen selbst,
 * Compose zeichnet neu). Android 8-12: eigene Einstellung, Activities per attachBaseContext umgestellt und neu
 * erzeugt, App-Ressourcen (Mitteilungen) direkt umgestellt. Accept-Language folgt der gewaehlten Sprache.
 */
object AppLanguage {
    /** "" = Automatisch (Systemsprache) */
    val OPTIONS = listOf("", "de", "en", "es", "ru", "th")
    private val SUPPORTED = setOf("de", "en", "es", "ru", "th")
    private const val PREFS = "saga_ui"
    private const val KEY = "app_lang"

    /** "de-DE"/"de_AT" -> "de"; nicht unterstuetzte oder leere Sprache -> "" (Automatisch) */
    fun normalize(tag: String?): String {
        val p = tag?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT).orEmpty()
        return if (p in SUPPORTED) p else ""
    }

    /** Name einer Option in ihrer eigenen Sprache (auch fuer jemanden lesbar, der die aktuelle Sprache nicht spricht) */
    fun nativeName(tag: String): String = when (tag) {
        "de" -> "Deutsch"
        "en" -> "English"
        "es" -> "Español"
        "ru" -> "Русский"
        "th" -> "ไทย"
        else -> ""
    }

    /**
     * Locale zum Formatieren von Datum/Zeit: Thai immer im gregorianischen Kalender (th-TH-u-ca-gregory) wie Web und
     * iOS - ICU nimmt fuer th-TH sonst den buddhistischen Kalender (Jahr 2569 statt 2026). Andere Sprachen unveraendert.
     */
    fun formatLocale(l: Locale): Locale =
        if (l.language == "th" && l.getUnicodeLocaleType("ca") == null) {
            Locale.Builder().setLocale(l).setUnicodeLocaleKeyword("ca", "gregory").build()
        } else {
            l
        }

    /** Sprache fuer Accept-Language: gewaehlte Sprache, sonst die der angezeigten Texte (uiLanguage) */
    fun headerLanguage(chosen: String, uiLanguage: String): String = normalize(chosen).ifEmpty { uiLanguage }

    /** Gewaehlte Sprache ("" = Automatisch) */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            val l = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (l == null || l.isEmpty) "" else normalize(l[0].toLanguageTag())
        } else {
            stored(context)
        }

    private fun stored(context: Context): String =
        normalize(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""))

    /** Sprache waehlen und sofort anwenden */
    fun set(activity: Activity, tag: String) {
        val t = normalize(tag)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (t.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(t)
            return
        }
        if (t == stored(activity)) return
        activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, t) }
        applyToApp(activity.applicationContext)
        activity.recreate()
    }

    /** Android 8-12: Basis-Kontext einer Activity auf die gewaehlte Sprache umstellen */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val t = stored(base)
        val locale = if (t.isEmpty()) systemLocale() else Locale.forLanguageTag(t)
        Locale.setDefault(formatLocale(locale))
        // auch bei "Automatisch" ausdruecklich setzen: die App-Ressourcen (applyToApp) koennen die vorige Wahl tragen
        val cfg = Configuration(base.resources.configuration)
        if (t.isEmpty()) cfg.setLocales(Resources.getSystem().configuration.locales) else cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }

    /** Android 8-12: Ressourcen des App-Kontexts (Mitteilungen, Dienste) auf die gewaehlte Sprache umstellen */
    @Suppress("DEPRECATION")
    fun applyToApp(app: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val t = stored(app)
        val locale = if (t.isEmpty()) systemLocale() else Locale.forLanguageTag(t)
        Locale.setDefault(formatLocale(locale))
        val res = app.resources
        val cfg = Configuration(res.configuration)
        if (t.isEmpty()) cfg.setLocales(Resources.getSystem().configuration.locales) else cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        res.updateConfiguration(cfg, res.displayMetrics)
    }

    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
}
