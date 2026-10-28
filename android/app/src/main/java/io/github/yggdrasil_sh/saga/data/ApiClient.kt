package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.ServerUrl
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fehler vom Server (HTTP-Status + Meldung aus {"error": ...}).
 * text = Anzeige fuer die Oberflaeche (uebersetzt; Meldungen des Servers unveraendert), message nur fuers Log.
 * body = Antwort des Servers (JSON) fuer Zusatzangaben wie need_name beim Veroeffentlichen.
 */
class ApiException(val code: Int, val text: UiText, val body: String = "") : Exception("HTTP $code: $text")

/** Erneuerung der Sitzung ging nicht durch (Netz) - als "Server nicht erreichbar" anzeigen */
class ServerUnreachableException : IOException("server unreachable")

/** Sitzungs-Cookie ("saga") nur im Speicher - laeuft sie ab, erneuert der Geraete-Schluessel sie still. */
class MemoryCookieJar : CookieJar {
    private val store = ArrayList<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > now) store.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt <= now }
        return store.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() = store.clear()
}

/** 2.5: Chat-Stufe dieser App (Kopfzeile X-Saga-Chat) */
const val CHAT_LEVEL = 3

/**
 * HTTP-Zugang zum Saga-Server.
 *  - Anfragen an den eigenen Server mit "X-Saga: 1" (der Server verlangt die Kopfzeile fuer /api/...)
 *  - 401 vom eigenen Server -> einmal still POST /app/resume {device_token, app_key} und wiederholen;
 *    401/403 dort -> Geraet abgemeldet -> onSessionLost (Anmeldebildschirm)
 *  - Geraete-/App-Schluessel gehen NUR an den Server, fuer den sie ausgestellt wurden (SecureStore.tokenServer);
 *    fremde Hosts (z. B. Bilder von anderen Seiten) bekommen weder Kopfzeilen noch Erneuerung noch Cookies.
 */
class ApiClient(
    private val serverProvider: () -> String,
    private val secure: SecureStore,
    private val onSessionLost: () -> Unit,
    private val userAgent: String,
    private val onAccount: (String) -> Unit = {},
    /** Sprache der App-Oberflaeche (BCP 47, z. B. "de") - der Server uebersetzt damit seine Fehlermeldungen */
    private val language: () -> String = { "" },
) {
    val cookies = MemoryCookieJar()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Gehoert die Adresse zum eingestellten Server (Schema, Host, Port und Pfad-Anfang)? */
    fun isOwn(url: HttpUrl): Boolean = sameServer(serverProvider(), url)

    private val headers = Interceptor { chain ->
        val req = chain.request()
        if (!isOwn(req.url)) return@Interceptor chain.proceed(req)
        // X-Saga-Chat: hoechste Chat-Stufe der App - 3 (Saga 2.5): lokale Chats der Stufe 3 (saga-chat/v2 ueber Handles),
        // Reaktionen, Bearbeiten, Sprachnachrichten; mit `device` meldet der Server dieses Geraet als chat3. Server 2.3/2.4
        // werten es wie 2 (Chats ueber Servergrenzen), aeltere ignorieren es.
        val b = req.newBuilder().header("X-Saga", "1").header("X-Saga-Chat", CHAT_LEVEL.toString()).header("User-Agent", userAgent)
        acceptLanguage(language())?.let { b.header("Accept-Language", it) }
        chain.proceed(b.build())
    }

    /** ohne Erneuerung: Anmeldung, Erneuerung selbst, Versionsabfrage */
    val authClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .addInterceptor(headers)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** normale Anfragen an den eigenen Server: mit stiller Erneuerung der Sitzung */
    val client: OkHttpClient = authClient.newBuilder().addInterceptor(SessionInterceptor()).build()

    /** fremde Hosts (Bilder aus Bild-Bloecken): ohne Cookies, Kopfzeilen und Erneuerung */
    private val plainClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Fuer Coil: eigener Server ueber die Sitzung, alles andere ueber einen neutralen Client */
    val imageCallFactory: Call.Factory = Call.Factory { req -> (if (isOwn(req.url)) client else plainClient).newCall(req) }

    private enum class Resume { OK, REJECTED, OFFLINE }

    private val lock = Any()
    @Volatile
    private var generation = 0

    private inner class SessionInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            if (!isOwn(req.url)) return chain.proceed(req)          // nie fuer fremde Hosts erneuern oder abmelden
            val gen = generation
            val resp = chain.proceed(req)
            if (resp.code != 401) return resp
            // gleichzeitige Anfragen teilen sich einen Erneuerungs-Versuch
            val r = synchronized(lock) {
                if (generation != gen) Resume.OK else resumeBlocking().also { if (it == Resume.OK) generation++ }
            }
            when (r) {
                Resume.OK -> {
                    resp.close()
                    val again = chain.proceed(req)
                    if (again.code == 401) onSessionLost()
                    return again
                }
                Resume.REJECTED -> {
                    onSessionLost()
                    return resp
                }
                Resume.OFFLINE -> {
                    resp.close()
                    throw ServerUnreachableException()
                }
            }
        }
    }

    private fun resumeBlocking(): Resume {
        val server = serverProvider().ifEmpty { return Resume.REJECTED }
        val tok = secure.deviceTokenFor(server) ?: return Resume.REJECTED     // Schluessel gehoert zu einem anderen Server
        val body = buildJsonObject {
            put("device_token", tok)
            secure.appKeyFor(server)?.let { put("app_key", it) }             // Widget-Schluessel diesem Geraet zuordnen
        }
        val req = Request.Builder().url(ServerUrl.absolute(server, "app/resume"))
            .post(body.toString().toRequestBody(jsonType)).build()
        return try {
            authClient.newCall(req).execute().use { r ->
                when {
                    r.code == 401 || r.code == 403 -> {
                        if (secure.tokenServer == server) secure.clearCredentials()
                        Resume.REJECTED
                    }
                    r.isSuccessful -> {
                        runCatching {
                            ((Json.parseToJsonElement(r.body.string()) as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                        }.getOrNull()?.takeIf { it.isNotBlank() }?.let(onAccount)
                        Resume.OK
                    }
                    else -> Resume.OFFLINE
                }
            }
        } catch (_: IOException) {
            Resume.OFFLINE
        }
    }

    // ---------------------------------------------------------------- Aufrufe

    fun url(path: String): String {
        val server = serverProvider()
        if (server.isEmpty()) throw ApiException(401, UiText.Res(R.string.error_enter_server))
        return ServerUrl.absolute(server, path)
    }

    /**
     * base: anderer Server als der eingestellte (Kopplung/Anmeldung, bevor der Server uebernommen wird) -
     * dann immer ohne Erneuerung.
     */
    suspend fun request(
        method: String, path: String, body: JsonElement? = null, auth: Boolean = false, base: String? = null,
        bearer: String? = null, headers: Map<String, String> = emptyMap(),
    ): String {
        val rb = when {
            body != null -> body.toString().toRequestBody(jsonType)
            method == "GET" || method == "DELETE" -> null
            else -> "{}".toRequestBody(jsonType)
        }
        val target = if (base != null) ServerUrl.absolute(base, path) else url(path)
        val rq = Request.Builder().url(target).method(method, rb).header("X-Saga", "1").header("User-Agent", userAgent)
        if (bearer != null) rq.header("Authorization", "Bearer $bearer")
        headers.forEach { (k, v) -> rq.header(k, v) }
        val req = rq.build()
        val (code, text) = execute(if (auth || base != null || bearer != null) authClient else client, req)
        if (code !in 200..299) throw ApiException(code, errorText(code, text), text)
        return text
    }

    /** Rohdaten hochladen (verschluesselte Chat-Anhaenge, application/octet-stream) */
    suspend fun postBytes(path: String, data: ByteArray): String {
        val req = Request.Builder().url(url(path))
            .post(data.toRequestBody("application/octet-stream".toMediaType())).header("X-Saga", "1").build()
        val (code, text) = execute(client, req)
        if (code !in 200..299) throw ApiException(code, errorText(code, text), text)
        return text
    }

    /** Rohinhalt mit eigenem Typ (PUT, z. B. Profilbild image/jpeg) */
    suspend fun putBytes(path: String, data: ByteArray, mime: String): String {
        val req = Request.Builder().url(url(path)).put(data.toRequestBody(mime.toMediaType())).header("X-Saga", "1").build()
        val (code, text) = execute(client, req)
        if (code !in 200..299) throw ApiException(code, errorText(code, text), text)
        return text
    }

    /**
     * Datei als Formular hochladen (POST api/files, Feld "image" bzw. "file" wie im Web-Editor).
     * note = fremde Seite (Rolle Bearbeiten): die Datei gehoert deren Eigentuemer (Kopfzeile X-Saga-Note).
     */
    suspend fun postMultipart(path: String, field: String, fileName: String, mime: String, data: ByteArray, note: Long? = null): String {
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart(field, fileName, data.toRequestBody(mime.toMediaType()))
            .build()
        val rq = Request.Builder().url(url(path)).post(body).header("X-Saga", "1")
        if (note != null) rq.header("X-Saga-Note", note.toString())
        val (code, text) = execute(client, rq.build())
        if (code !in 200..299) throw ApiException(code, errorText(code, text), text)
        return text
    }

    /** Rohdaten laden (verschluesselte Chat-Anhaenge) */
    suspend fun getBytes(path: String): ByteArray {
        val req = Request.Builder().url(url(path)).get().header("X-Saga", "1").build()
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (it.code !in 200..299) {
                                val t = it.body.string()
                                if (cont.isActive) cont.resumeWithException(ApiException(it.code, errorText(it.code, t), t))
                            } else {
                                val b = it.body.bytes()
                                if (cont.isActive) cont.resume(b)
                            }
                        }
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }
    }

    /** Antwort mit Code, Kopfzeilen und Text (Upload in Teilen: Upload-Offset, Content-Range; Fehler werfen nicht) */
    class RawResponse(val code: Int, val headers: okhttp3.Headers, val bytes: ByteArray) {
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    /** Beliebige Anfrage an den eigenen Server (mit Sitzung und stiller Erneuerung); Netzfehler -> IOException */
    suspend fun raw(method: String, path: String, body: okhttp3.RequestBody? = null, headers: Map<String, String> = emptyMap()): RawResponse {
        val rq = Request.Builder().url(url(path)).method(method, body).header("X-Saga", "1")
        headers.forEach { (k, v) -> rq.header(k, v) }
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(rq.build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val r = try {
                        response.use { RawResponse(it.code, it.headers, it.body.bytes()) }
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(r)
                }
            })
        }
    }

    /**
     * Medien-Abruf mit Medien-Token (2.4, Videos): ohne Cookies und ohne Sitzungs-Erneuerung - die Berechtigung steht
     * im Kopf X-Saga-Media-Token. Nur fuer den eigenen Server verwenden.
     */
    val mediaClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun get(path: String) = request("GET", path)
    suspend fun post(path: String, body: JsonElement? = null) = request("POST", path, body)
    suspend fun patch(path: String, body: JsonElement) = request("PATCH", path, body)
    suspend fun put(path: String, body: JsonElement) = request("PUT", path, body)
    suspend fun delete(path: String) = request("DELETE", path)

    private suspend fun execute(client: OkHttpClient, req: Request): kotlin.Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val r = try {
                        response.use { it.code to it.body.string() }
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(r)
                }
            })
        }

    private fun errorText(code: Int, text: String): UiText {
        val msg = try {
            ((Json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonPrimitive)?.content
        } catch (_: Exception) {
            null
        }
        return when {
            code == 401 && (msg.isNullOrBlank() || msg == "unauthorized") -> UiText.Res(R.string.error_sign_in_required)
            msg.isNullOrBlank() -> UiText.Res(R.string.error_server_code, code)
            msg == "not found" -> UiText.Res(R.string.error_not_found)
            else -> UiText.Raw(msg)
        }
    }
}


/**
 * Accept-Language aus der App-Sprache: "de" -> "de", "es-MX" -> "es-MX, es;q=0.9"; ohne Sprache keine Kopfzeile.
 * Nur Buchstaben, Ziffern und "-" (nie Steuerzeichen in einer Kopfzeile).
 */
fun acceptLanguage(tag: String): String? {
    val t = tag.trim().replace('_', '-')
    if (t.isEmpty() || t.equals("und", ignoreCase = true) || !Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*").matches(t)) return null
    val primary = t.substringBefore('-').lowercase()
    return if (t.contains('-')) "$t, $primary;q=0.9" else primary
}

/** Gehoert url zum Server-Ursprung server (Schema, Host, Port, Pfad-Anfang)? */
fun sameServer(server: String, url: HttpUrl): Boolean {
    if (server.isEmpty()) return false
    val s = server.toHttpUrlOrNull() ?: return false
    if (s.scheme != url.scheme || !s.host.equals(url.host, ignoreCase = true) || s.port != url.port) return false
    val base = s.encodedPath.trimEnd('/')
    return base.isEmpty() || url.encodedPath == base || url.encodedPath.startsWith("$base/")
}
