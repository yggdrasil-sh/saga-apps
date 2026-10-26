package io.github.yggdrasil_sh.saga.ui.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.yggdrasil_sh.saga.BuildConfig
import io.github.yggdrasil_sh.saga.util.UpdateHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Update-Hinweis (Einstellungen -> Info, wie ios/App/UpdateHintModel.swift): fragt die Update-Info der CI ab
 * (util/UpdateHint.kt), merkt das Ergebnis in SharedPreferences und liefert Statuszeile + die eine Schaltflaeche.
 * Anfrage: eigener Client ohne Cookies, Cache und Kopfzeilen der App (keine Kennungen), nur die Datei, 10 s Zeitlimit.
 * Fehler bleiben still - sichtbar nur nach "Nach Updates suchen".
 */
object UpdateHintModel {
    private const val PREFS = "saga_update"
    private const val KEY_CACHE = "cache"
    private const val KEY_ENABLED = "enabled"

    /** Eigene Version (Debug-Builds "-debug": kein Kanal, keine Pruefung); Bildschirmfotos setzen sie im Test */
    @Volatile var label: String = BuildConfig.VERSION_NAME

    private val _checked = MutableStateFlow<Long?>(null)
    val checked: StateFlow<Long?> = _checked
    private val _release = MutableStateFlow<UpdateHint.Release?>(null)
    val release: StateFlow<UpdateHint.Release?> = _release
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private var prefs: SharedPreferences? = null
    private var scope: CoroutineScope? = null

    /** Ohne CookieJar, ohne Cache, ohne Interceptors der App; https-Weiterleitungen nur nach https */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .followSslRedirects(false)
            .callTimeout(UpdateHint.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(UpdateHint.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(UpdateHint.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    fun init(context: Context, appScope: CoroutineScope) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        scope = appScope
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
        UpdateHint.decode(p.getString(KEY_CACHE, null))?.let { c -> _checked.value = c.checked; _release.value = c.release }
    }

    /** Bekanntes Update im eigenen Kanal (null: keins bzw. Hinweis aus) */
    fun available(enabled: Boolean, release: UpdateHint.Release?): UpdateHint.Release? =
        if (enabled) UpdateHint.available(release, label) else null

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        if (!on) _failed.value = false
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }

    /** App-Start: hoechstens alle 12 h */
    fun appStarted() = auto(UpdateHint.START_INTERVAL_MS)

    /** Info geoeffnet: nur, wenn das letzte Ergebnis nicht ganz frisch ist */
    fun infoOpened() = auto(UpdateHint.INFO_INTERVAL_MS)

    private fun auto(interval: Long) {
        if (!_enabled.value || _checking.value || !UpdateHint.due(_checked.value, System.currentTimeMillis(), interval)) return
        scope?.launch { check(manual = false) }
    }

    /** "Nach Updates suchen": sofort, ohne Zwischenspeicher */
    fun checkNow() {
        scope?.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        if (!_enabled.value || _checking.value) return
        val ch = UpdateHint.channel(label)
        val url = UpdateHint.feedUrl(label)
        if (ch == null || url == null) {
            if (manual) _failed.value = true
            return
        }
        _checking.value = true
        if (manual) _failed.value = false
        try {
            val r = withContext(Dispatchers.IO) { fetch(url, ch) }
            val now = System.currentTimeMillis()
            _release.value = r
            _checked.value = now
            _failed.value = false
            prefs?.edit()?.putString(KEY_CACHE, UpdateHint.encode(UpdateHint.Cache(now, r)))?.apply()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (manual) _failed.value = true                     // automatische Pruefung: still, alter Stand bleibt
        } finally {
            _checking.value = false
        }
    }

    private class Bad : Exception()

    private fun fetch(url: String, channel: String): UpdateHint.Release {
        val req = Request.Builder().url(url).get().cacheControl(CacheControl.FORCE_NETWORK).build()
        http.newCall(req).execute().use { res ->
            if (res.code != 200) throw Bad()
            val src = res.body.source()
            if (src.request(UpdateHint.MAX_BYTES + 1L)) throw Bad()   // groesser als erlaubt
            return UpdateHint.parse(src.readByteArray(), channel) ?: throw Bad()
        }
    }

    /** Nur fuer Bildschirmfotos/Tests (Debug): Zwischenspeicher vorgeben, ohne Anfrage */
    fun preset(context: Context, cache: UpdateHint.Cache?) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        if (cache == null) p.edit().remove(KEY_CACHE).apply() else p.edit().putString(KEY_CACHE, UpdateHint.encode(cache)).apply()
        _checked.value = cache?.checked
        _release.value = cache?.release
        _failed.value = false
    }
}
