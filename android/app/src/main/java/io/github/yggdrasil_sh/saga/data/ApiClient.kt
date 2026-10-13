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
 */
class ApiException(val code: Int, val text: UiText) : Exception("HTTP $code: $text")

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

/**
 * HTTP-Zugang zum Saga-Server.
 *  - jede Anfrage mit "X-Saga: 1" (der Server verlangt die Kopfzeile fuer /api/...)
 *  - 401 -> einmal still POST /app/resume {device_token, app_key} und wiederholen;
 *    401/403 dort -> Geraet abgemeldet -> onSessionLost (Anmeldebildschirm)
 */
class ApiClient(
    private val serverProvider: () -> String,
    private val secure: SecureStore,
    private val onSessionLost: () -> Unit,
    userAgent: String,
) {
    val cookies = MemoryCookieJar()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val headers = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("X-Saga", "1").header("User-Agent", userAgent).build())
    }

    /** ohne Erneuerung: Anmeldung, Erneuerung selbst, Versionsabfrage */
    val authClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .addInterceptor(headers)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** normale Anfragen (auch Bilder): mit stiller Erneuerung der Sitzung */
    val client: OkHttpClient = authClient.newBuilder().addInterceptor(SessionInterceptor()).build()

    private enum class Resume { OK, REJECTED, OFFLINE }

    private val lock = Any()
    @Volatile
    private var generation = 0

    private inner class SessionInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
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
        val tok = secure.deviceToken ?: return Resume.REJECTED
        val server = serverProvider().ifEmpty { return Resume.REJECTED }
        val body = buildJsonObject {
            put("device_token", tok)
            secure.appKey?.let { put("app_key", it) }       // Widget-Schluessel diesem Geraet zuordnen
        }
        val req = Request.Builder().url(ServerUrl.absolute(server, "app/resume"))
            .post(body.toString().toRequestBody(jsonType)).build()
        return try {
            authClient.newCall(req).execute().use { r ->
                when {
                    r.code == 401 || r.code == 403 -> {
                        secure.deviceToken = null
                        Resume.REJECTED
                    }
                    r.isSuccessful -> Resume.OK
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

    suspend fun request(method: String, path: String, body: JsonElement? = null, auth: Boolean = false): String {
        val rb = when {
            body != null -> body.toString().toRequestBody(jsonType)
            method == "GET" || method == "DELETE" -> null
            else -> "{}".toRequestBody(jsonType)
        }
        val req = Request.Builder().url(url(path)).method(method, rb).build()
        val (code, text) = execute(if (auth) authClient else client, req)
        if (code !in 200..299) throw ApiException(code, errorText(code, text))
        return text
    }

    suspend fun get(path: String) = request("GET", path)
    suspend fun post(path: String, body: JsonElement? = null) = request("POST", path, body)
    suspend fun patch(path: String, body: JsonElement) = request("PATCH", path, body)
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
