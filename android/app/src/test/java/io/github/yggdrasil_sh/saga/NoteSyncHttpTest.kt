package io.github.yggdrasil_sh.saga

import com.sun.net.httpserver.HttpServer
import io.github.yggdrasil_sh.saga.data.ApiClient
import io.github.yggdrasil_sh.saga.data.MemorySecretStore
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.data.SecureStore
import io.github.yggdrasil_sh.saga.data.TreeSync
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files

/**
 * Seitenbaum-Abgleich ueber echtes HTTP (ApiClient/OkHttp wie in der App): Anfragepfade, Kodierung des Stands,
 * abgelehnter Stand (400) und aelterer Server, der die Parameter ignoriert und die Liste schickt.
 */
class NoteSyncHttpTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    private inline fun <T> withServer(noinline handler: (path: String, query: String) -> Pair<Int, String>, body: (String) -> T): T {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            val (code, text) = handler(ex.requestURI.path, ex.requestURI.rawQuery.orEmpty())
            val b = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        http.start()
        try {
            return body("http://127.0.0.1:${http.address.port}")
        } finally {
            http.stop(0)
        }
    }

    private fun api(base: String) = ApiClient({ base }, SecureStore(MemorySecretStore()), {}, "SagaTest/1.0")

    @Test
    fun abgleich_ueber_http() = runBlocking {
        val srv = NoteSyncTest.Server()
        repeat(20) { srv.create(if (it % 4 == 0) null else it.toLong()) }
        val queries = ArrayList<String>()
        withServer({ path, q ->
            queries += q
            when {
                path != "/api/notes" -> 404 to "{}"
                q == "since=" + "kaputt" -> 400 to """{"error":"bad cursor"}"""
                else -> {
                    val since = q.split('&').firstOrNull { it.startsWith("since=") }?.removePrefix("since=")?.let { URLDecoder.decode(it, "UTF-8") }
                    200 to srv.response(since?.removePrefix("c.")?.toInt())
                }
            }
        }) { base ->
            val dir = Files.createTempDirectory("notesync-http").toFile()
            val a = api(base)
            val ts = TreeSync(json, dir) { a.get(it) }
            assertEquals(srv.list(), ts.load(base, "anna"))
            assertEquals("delta=1", queries.last())
            val rng = NoteSyncTest.Rng(11UL)
            for (step in 0 until 15) {
                repeat(1 + rng.next(3)) { srv.randomStep(rng, step) }
                assertEquals(srv.list(), ts.load(base, "anna"))
                assertTrue(queries.last().startsWith("since=c."))
            }
            // abgelehnter Stand (400): in derselben Abfrage ganz holen
            val f = TreeSync::class.java.getDeclaredField("cursor"); f.isAccessible = true
            f.set(ts, ts.cursor!!.copy(value = "kaputt"))
            val n = queries.size
            assertEquals(srv.list(), ts.load(base, "anna"))
            assertEquals(listOf("since=kaputt", "delta=1"), queries.drop(n))
            dir.deleteRecursively()
        }
        Unit
    }

    @Test
    fun aelterer_server_ignoriert_parameter() = runBlocking {
        val list = """[{"id":1,"title":"A","parent_id":null},{"id":2,"title":"B","parent_id":1,"archived":true}]"""
        val queries = ArrayList<String>()
        withServer({ _, q -> queries += q; 200 to list }) { base ->
            val ts = TreeSync(json, null) { api(base).get(it) }
            val l: List<PageMeta> = ts.load(base, "anna")
            assertEquals(listOf(1L, 2L), l.map { it.id })
            assertTrue(l[1].archived)
            ts.load(base, "anna")
            assertEquals(listOf("delta=1", "delta=1"), queries)
            assertNull(ts.cursor)
        }
        Unit
    }
}
