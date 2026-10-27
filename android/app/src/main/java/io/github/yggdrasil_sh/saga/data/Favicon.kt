package io.github.yggdrasil_sh.saga.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Saga 2.5: Favicons nur fuer Lesezeichen, Rueckfall auf dem Geraet. Reihenfolge: erst `icon_url` ueber den eigenen
 * Server; klappt das nicht, der Reihe nach `icon_direct` (der Server liefert es nur bei Lesezeichen) direkt von der
 * Seite wie ein Browser-Tab - Zeitlimit, Groessenlimit, nur Rasterbilder, ohne Cookies/Anmeldedaten; sonst
 * Anfangsbuchstabe. Fehlt `icon_direct` (aelterer Server, Dienste), bleibt alles wie bisher. Reine Logik, testbar
 * (FaviconLogicTest).
 */
object FaviconLogic {
    const val TIMEOUT_MS = 5_000L
    const val MAX_BYTES = 512 * 1024
    const val MAX_DIRECT = 3
    const val MAX_REDIRECTS = 4
    const val OK_TTL_MS = 7L * 86_400_000
    const val FAIL_TTL_MS = 3_600_000L
    /** hoechstens so viele Pixel dekodieren (2048 x 2048): kleine Datei, riesiges Bild = Speicher voll */
    const val MAX_PIXELS = 2048L * 2048

    sealed interface Source {
        data class Server(val path: String) : Source
        data class Direct(val url: String) : Source
    }

    /**
     * Erlaubte direkte Adressen: absolut, nur http/https (bei einem https-Lesezeichen nur https), Host und Port wie das
     * Lesezeichen, keine Anmeldedaten in der Adresse, ohne Doppelte, hoechstens 3.
     */
    fun directCandidates(list: List<String>, bookmarkUrl: String): List<String> {
        if (list.isEmpty()) return emptyList()
        val b = bookmarkUrl.trim().toHttpUrlOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (raw in list.take(MAX_DIRECT)) {
            if (!raw.startsWith("http://", true) && !raw.startsWith("https://", true)) continue
            val c = raw.toHttpUrlOrNull() ?: continue
            if (b.isHttps && !c.isHttps) continue
            if (!c.host.equals(b.host, true) || !samePort(c, b)) continue
            if (c.username.isNotEmpty() || c.password.isNotEmpty()) continue
            val s = c.toString()
            if (s !in out) out += s
        }
        return out
    }

    /** gleicher Port - oder beide auf dem Standardport ihres Schemas (http-Lesezeichen, Favicon per https) */
    private fun samePort(a: HttpUrl, b: HttpUrl): Boolean =
        a.port == b.port || (a.port == HttpUrl.defaultPort(a.scheme) && b.port == HttpUrl.defaultPort(b.scheme))

    fun plan(serverPath: String?, direct: List<String>): List<Source> =
        listOfNotNull(serverPath?.let { Source.Server(it) }) + direct.map { Source.Direct(it) }

    /** Erstes Ergebnis in Planreihenfolge */
    inline fun <T : Any> first(plan: List<Source>, fetch: (Source) -> T?): Pair<Source, T>? {
        for (s in plan) fetch(s)?.let { return s to it }
        return null
    }

    /** Weiterleitung folgen? Nur http/https, nie von https auf http */
    fun redirectAllowed(from: HttpUrl, to: HttpUrl?): Boolean = to != null && !(from.isHttps && !to.isHttps)

    /** Nur Rasterbilder nach Dateianfang: PNG, ICO, JPEG, GIF, WebP. SVG/HTML/sonstiges nie. */
    fun isRaster(d: ByteArray): Boolean {
        fun at(i: Int, vararg v: Int) = d.size >= i + v.size && v.indices.all { (d[i + it].toInt() and 0xFF) == v[it] }
        fun ascii(i: Int, s: String) = at(i, *s.map { it.code }.toIntArray())
        if (d.size < 4) return false
        return at(0, 0x89, 0x50, 0x4E, 0x47) || at(0, 0, 0, 1, 0) || at(0, 0xFF, 0xD8, 0xFF) || ascii(0, "GIF8") ||
            (ascii(0, "RIFF") && ascii(8, "WEBP"))
    }

    /** Schon die Kopfzeilen verraten Fehler/Uebergroesse/SVG -> gleich abbrechen */
    fun rejectEarly(status: Int, contentType: String?, declaredLength: Long?): Boolean {
        if (status != 200) return true
        if (declaredLength != null && declaredLength > MAX_BYTES) return true
        val ct = contentType.orEmpty().lowercase()
        return "svg" in ct || ct.startsWith("text/")
    }

    /** Bildmasse vor dem Dekodieren pruefen (Dekompressionsbombe) */
    fun pixelsOk(width: Int, height: Int): Boolean = width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS

    fun accept(status: Int, contentType: String?, declaredLength: Long?, data: ByteArray): Boolean =
        !rejectEarly(status, contentType, declaredLength) && data.isNotEmpty() && data.size <= MAX_BYTES && isRaster(data)

    enum class CacheDecision { USE, KNOWN_MISSING, RELOAD }

    /** Treffer 7 Tage, Fehlversuch 1 Stunde; Zeit in der Zukunft (Uhr verstellt) -> neu laden */
    fun cacheDecision(ok: Boolean, savedAt: Long, now: Long): CacheDecision {
        val age = now - savedAt
        return when {
            age < 0 -> CacheDecision.RELOAD
            ok -> if (age < OK_TTL_MS) CacheDecision.USE else CacheDecision.RELOAD
            else -> if (age < FAIL_TTL_MS) CacheDecision.KNOWN_MISSING else CacheDecision.RELOAD
        }
    }

    /** Schluessel je Kandidatenliste (FNV-1a 64, wie iOS) */
    fun cacheKey(urls: List<String>): String {
        var h = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325
        for (b in urls.joinToString("\n").toByteArray()) { h = h xor (b.toLong() and 0xFF); h *= 0x100000001b3L }
        return "%016x".format(h)
    }
}

/**
 * Laedt Lesezeichen-Favicons direkt von der Seite: eigener OkHttp-Client ohne Cookies, ohne Anmeldung (kein
 * Authenticator), Weiterleitungen selbst geprueft, 5 s fuer den ganzen Abruf, hoechstens 512 KB. Ergebnis (auch
 * Fehlversuch) in cacheDir/favicons; beim Abmelden geloescht. Nichts geht zum Server.
 */
object FaviconLoader {
    private const val DIR = "favicons"
    private val memory = ConcurrentHashMap<String, ByteArray>()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).cache(null)
            .callTimeout(FaviconLogic.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(FaviconLogic.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(FaviconLogic.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Erstes brauchbare Bild der Kandidaten oder null (dann Anfangsbuchstabe); `decodes` prueft das Bild wirklich */
    suspend fun load(cacheDir: File, urls: List<String>, decodes: (ByteArray) -> Boolean): ByteArray? {
        if (urls.isEmpty()) return null
        val key = FaviconLogic.cacheKey(urls)
        memory[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val dir = File(cacheDir, DIR)
            val img = File(dir, "$key.img"); val miss = File(dir, "$key.miss")
            val now = System.currentTimeMillis()
            if (img.isFile && FaviconLogic.cacheDecision(true, img.lastModified(), now) == FaviconLogic.CacheDecision.USE) {
                val d = runCatching { img.readBytes() }.getOrNull()
                if (d != null && decodes(d)) { memory[key] = d; return@withContext d }
            }
            if (miss.isFile && FaviconLogic.cacheDecision(false, miss.lastModified(), now) == FaviconLogic.CacheDecision.KNOWN_MISSING) {
                return@withContext null
            }
            for (u in urls) {
                ensureActive()                       // Kachel weg: nichts als Fehlversuch merken
                val d = fetch(u) ?: continue
                if (!decodes(d)) continue
                dir.mkdirs(); runCatching { img.writeBytes(d) }; miss.delete()
                memory[key] = d
                return@withContext d
            }
            ensureActive()
            dir.mkdirs(); runCatching { miss.writeBytes(ByteArray(0)); miss.setLastModified(System.currentTimeMillis()) }; img.delete()
            null
        }
    }

    fun clear(cacheDir: File) {
        memory.clear()
        File(cacheDir, DIR).deleteRecursively()
    }

    private fun fetch(start: String): ByteArray? {
        var url = start.toHttpUrlOrNull() ?: return null
        val deadline = System.currentTimeMillis() + FaviconLogic.TIMEOUT_MS
        repeat(FaviconLogic.MAX_REDIRECTS + 1) {
            if (System.currentTimeMillis() > deadline) return null
            val req = Request.Builder().url(url)
                .header("Accept", "image/png,image/x-icon,image/vnd.microsoft.icon,image/jpeg,image/gif,image/webp,*/*;q=0.1")
                .build()
            try {
                client.newCall(req).execute().use { r ->
                    if (r.code in 300..399) {
                        val next = r.header("Location")?.let { url.resolve(it) }
                        if (!FaviconLogic.redirectAllowed(url, next)) return null
                        url = next!!
                        return@repeat
                    }
                    val body = r.body
                    val declared = body.contentLength().takeIf { it >= 0 }
                    val ct = r.header("Content-Type")
                    if (FaviconLogic.rejectEarly(r.code, ct, declared)) return null
                    val src = body.source()
                    src.request(FaviconLogic.MAX_BYTES + 1L)
                    if (src.buffer.size > FaviconLogic.MAX_BYTES) return null
                    val d = src.buffer.readByteArray()
                    return if (FaviconLogic.accept(r.code, ct, declared, d)) d else null
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }
}
