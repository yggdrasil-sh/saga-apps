package io.github.yggdrasil_sh.saga

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.github.yggdrasil_sh.saga.chat.ChatCrypto
import io.github.yggdrasil_sh.saga.chat.ChatLimits
import io.github.yggdrasil_sh.saga.chat.FileV2
import io.github.yggdrasil_sh.saga.chat.FileV2Writer
import io.github.yggdrasil_sh.saga.chat.video.UploadGone
import io.github.yggdrasil_sh.saga.chat.video.UploadHttp
import io.github.yggdrasil_sh.saga.chat.video.UploadRejected
import io.github.yggdrasil_sh.saga.chat.video.V2Uploader
import io.github.yggdrasil_sh.saga.chat.video.VideoCache
import io.github.yggdrasil_sh.saga.chat.video.VideoLogic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** Upload in Teilen (fortsetzbar), Grenzen/Wege, Cache-Grenze, MP4-faststart */
class VideoUploadTest {
    private val crypto = ChatCrypto(LazySodiumJava(SodiumJava()))
    private val key = ByteArray(32) { (3 * it).toByte() }
    private val prefix = ByteArray(16) { (90 + it).toByte() }

    /**
     * Server wie docs/APP-API.md: nur fortlaufend, 409 {offset} an falscher Stelle, HEAD liefert den Stand. failAt:
     * bei diesen PUT-Nummern bricht die Verbindung mittendrin ab (Teil halb angekommen, Antwort fehlt).
     */
    private class FakeServer(val total: Long, val failAt: Set<Int> = emptySet(), val lostReplyAt: Set<Int> = emptySet()) : UploadHttp {
        val data = ByteArrayOutputStream()
        var puts = 0
        var heads = 0
        var deleted = false
        val received get() = data.size().toLong()

        override suspend fun create(chatId: Long, size: Long) = UploadHttp.Created("U1", 0, 8L shl 20, "/api/chat/uploads/U1")

        override suspend fun put(url: String, from: Long, until: Long, total: Long, write: (OutputStream) -> Unit): UploadHttp.PutResult {
            val n = ++puts
            val body = ByteArrayOutputStream().also { write(it) }.toByteArray()
            assertEquals(until - from, body.size.toLong())
            if (from != received) return UploadHttp.PutResult(409, offset = received, error = "falsche Stelle")
            if (n in failAt) {
                data.write(body, 0, body.size / 3 + 1)                  // Teil nur halb angekommen (auch mitten im Block)
                throw IOException("Verbindung abgebrochen")
            }
            data.write(body)
            if (n in lostReplyAt) throw IOException("Antwort verloren")
            val done = received == total
            return UploadHttp.PutResult(200, offset = received, done = done, id = if (done) "U1" else null)
        }

        override suspend fun head(url: String): UploadHttp.HeadResult {
            heads++
            return UploadHttp.HeadResult(204, received, total, received == total)
        }

        override suspend fun delete(id: String) {
            deleted = true
        }
    }

    private fun writer(size: Long) = FileV2Writer(crypto, key, prefix, GenSource(size))
    private fun whole(w: FileV2Writer) = ByteArrayOutputStream().also { w.write(0, w.blobSize, it) }.toByteArray()

    @Test
    fun resumesAfterBrokenPartsAndLostReplies() = runTest {
        val size = 5L * FileV2.CS + 777
        val w = writer(size)
        val srv = FakeServer(w.blobSize, failAt = setOf(2, 5), lostReplyAt = setOf(3))
        val progress = ArrayList<Long>()
        val id = V2Uploader(srv, w, partMax = 300_000, retryWait = {}).upload("U1", "/api/chat/uploads/U1", 0) { off, _ -> progress.add(off) }
        assertEquals("U1", id)
        assertArrayEquals(whole(writer(size)), srv.data.toByteArray())
        assertTrue(srv.heads >= 3)
        assertTrue(progress.size >= 3)                                         // Fortschritt gemeldet
        assertEquals(w.blobSize, progress.last())
    }

    /** App-Neustart: Stand per HEAD (mitten im Block), ab dort neu erzeugen - der Blob ist bitgleich */
    @Test
    fun resumesFromHeadOffsetAfterRestart() = runTest {
        val size = 3L * FileV2.CS + 5
        val full = whole(writer(size))
        val srv = FakeServer(full.size.toLong())
        srv.data.write(full, 0, 400_001)                              // schon angekommen (vor dem Neustart)
        val up = V2Uploader(srv, writer(size), partMax = 1 shl 20, retryWait = {})
        val off = up.resumeOffset("/u", "U1")
        assertEquals(400_001L, off)
        up.upload("U1", "/u", off!!)
        assertArrayEquals(full, srv.data.toByteArray())
    }

    @Test
    fun wrongOffsetFollows409() = runTest {
        val size = 2L * FileV2.CS
        val full = whole(writer(size))
        val srv = FakeServer(full.size.toLong())
        srv.data.write(full, 0, 1000)
        // Upload glaubt, bei 0 zu stehen: der Server antwortet 409 {offset: 1000}
        V2Uploader(srv, writer(size), partMax = 100_000, retryWait = {}).upload("U1", "/u", 0)
        assertArrayEquals(full, srv.data.toByteArray())
    }

    @Test
    fun goneAndRejected() = runTest {
        val w = writer(1000)
        val gone = object : UploadHttp by FakeServer(w.blobSize) {
            override suspend fun put(url: String, from: Long, until: Long, total: Long, write: (OutputStream) -> Unit) = UploadHttp.PutResult(404)
        }
        try {
            V2Uploader(gone, w, 1 shl 20, retryWait = {}).upload("U1", "/u", 0)
            fail()
        } catch (_: UploadGone) {
        }
        val big = object : UploadHttp by FakeServer(w.blobSize) {
            override suspend fun put(url: String, from: Long, until: Long, total: Long, write: (OutputStream) -> Unit) = UploadHttp.PutResult(413, error = "zu gross")
        }
        try {
            V2Uploader(big, w, 1 shl 20, retryWait = {}).upload("U1", "/u", 0)
            fail()
        } catch (e: UploadRejected) {
            assertEquals(413, e.code)
        }
        // Netz dauerhaft weg: nach maxFails aufgeben (der Worker versucht es spaeter erneut)
        val down = object : UploadHttp by FakeServer(w.blobSize) {
            override suspend fun put(url: String, from: Long, until: Long, total: Long, write: (OutputStream) -> Unit): UploadHttp.PutResult = throw IOException("offline")
            override suspend fun head(url: String): UploadHttp.HeadResult = throw IOException("offline")
        }
        try {
            V2Uploader(down, w, 1 shl 20, maxFails = 3, retryWait = {}).upload("U1", "/u", 0)
            fail()
        } catch (_: IOException) {
        }
    }

    @Test
    fun routesFromServerLimits() {
        val old = ChatLimits(fileMax = 10L shl 20)                                   // Server 2.3: kein file2
        assertEquals(VideoLogic.Route.V1, VideoLogic.route(10L shl 20, old, null))
        assertEquals(VideoLogic.Route.NO_FILE2, VideoLogic.route((10L shl 20) + 1, old, null))
        val new = ChatLimits(fileMax = 10L shl 20, file2 = true, videoMax = 500L shl 20)
        assertEquals(VideoLogic.Route.V2, VideoLogic.route(11L shl 20, new, null))
        assertEquals(VideoLogic.Route.V2, VideoLogic.route(11L shl 20, new, true))
        assertEquals(VideoLogic.Route.PEER_OLD, VideoLogic.route(11L shl 20, new, false))   // Gegenueber hat 2.3
        assertEquals(VideoLogic.Route.V1, VideoLogic.route(5L shl 20, new, false))          // bis 10 MB geht immer
        assertEquals(VideoLogic.Route.TOO_BIG, VideoLogic.route((500L shl 20) + 1, new, true))
        assertEquals(10L shl 20, VideoLogic.maxSize(new, false))
        assertEquals(500L shl 20, VideoLogic.maxSize(new, true))
    }

    @Test
    fun target720AndPoster() {
        assertEquals(1280 to 720, VideoLogic.target720(1920, 1080, null))
        assertEquals(720 to 1280, VideoLogic.target720(1080, 1920, null))
        assertEquals(1280 to 720, VideoLogic.target720(3840, 2160, 40_000_000))
        assertNull(VideoLogic.target720(1280, 720, 2_000_000))                       // schon klein und sparsam
        assertEquals(1280 to 720, VideoLogic.target720(1280, 720, 12_000_000))        // gleiche Groesse, aber zu hohe Bitrate
        assertNull(VideoLogic.target720(640, 360, null))                              // nie hochskalieren
        assertEquals(720 to 720, VideoLogic.target720(1080, 1080, null))
        assertEquals(1_000_000L, VideoLogic.posterTimeUs(83_400))
        assertEquals(500_000L, VideoLogic.posterTimeUs(5_000))                         // kurze Videos: 10 %
        assertEquals(83.4, VideoLogic.durSeconds(83_412), 0.0)
        assertEquals("1:23", VideoLogic.durText(83.4))
        assertEquals("1:02:03", VideoLogic.durText(3723.0))
        assertEquals(90, VideoLogic.rotation(-270))
        assertEquals(720 to 1280, VideoLogic.shown(1280, 720, 90))
    }

    /** Nachrichteninhalt: v1-Anhaenge unveraendert (kein x), v2 mit x/cs/dur/rot/fs; groessere x -> nicht unterstuetzt */
    @Test
    fun contentFields() {
        val v1 = io.github.yggdrasil_sh.saga.chat.ChatLogic.content("", listOf(io.github.yggdrasil_sh.saga.chat.ChatFile("a", "k", "n.mp4", "video/mp4", 5)), emptyList())
        assertFalse(v1.contains("\"x\""))
        assertFalse(v1.contains("\"cs\""))
        val f2 = io.github.yggdrasil_sh.saga.chat.ChatFile("a", "k", "n.mp4", "video/mp4", 5, w = 1280, h = 720, x = 2, cs = 262144, dur = 83.4, rot = 90, fs = true)
        val v2 = io.github.yggdrasil_sh.saga.chat.ChatLogic.content("", listOf(f2), emptyList())
        for (part in listOf("\"x\":2", "\"cs\":262144", "\"dur\":83.4", "\"rot\":90", "\"fs\":true")) assertTrue(part, v2.contains(part))
        val back = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<io.github.yggdrasil_sh.saga.chat.ChatContent>(v2).files.single()
        assertTrue(back.isV2 && back.isVideo && !back.unsupported)
        assertEquals(f2, back)
        assertTrue(io.github.yggdrasil_sh.saga.chat.ChatFile(x = 3).unsupported)
        assertFalse(io.github.yggdrasil_sh.saga.chat.ChatFile().unsupported)
    }

    private fun box(type: String, payload: Int): ByteArray {
        val n = 8 + payload
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + type.toByteArray() + ByteArray(payload)
    }

    @Test
    fun faststartDetection() {
        assertEquals(true, VideoLogic.moovFirst(ByteArrayInputStream(box("ftyp", 16) + box("moov", 100) + box("mdat", 1000))))
        assertEquals(false, VideoLogic.moovFirst(ByteArrayInputStream(box("ftyp", 16) + box("free", 8) + box("mdat", 1000) + box("moov", 100))))
        // 64-Bit-Groesse (size == 1)
        val big = byteArrayOf(0, 0, 0, 1) + "wide".toByteArray() + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 24) + ByteArray(8)
        assertEquals(true, VideoLogic.moovFirst(ByteArrayInputStream(big + box("moov", 4))))
        assertNull(VideoLogic.moovFirst(ByteArrayInputStream(ByteArray(3))))
    }

    /** Ciphertext-Cache: 512 MB, LRU - aelteste Bereiche fallen weg, die Grenze wird eingehalten */
    @Test
    fun cacheLimitLru() {
        assertEquals(512L * 1024 * 1024, VideoCache.MAX_BYTES)
        val ev = VideoCache.evictor(4L * 1024 * 1024)
        val cache = LruProbe(ev)
        for (k in 0 until 10) cache.add("f$k", 1L shl 20)
        assertTrue(cache.space <= 4L shl 20)
        assertFalse("aelteste weg", cache.has("f0"))
        assertTrue("neueste bleibt", cache.has("f9"))
    }
}

/** Minimaler Cache fuer den LRU-Evictor (nur removeSpan/getCacheSpace; ueber einen Proxy, unabhaengig von der Media3-Version) */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LruProbe(private val ev: androidx.media3.datasource.cache.CacheEvictor) {
    private val spans = LinkedHashMap<String, androidx.media3.datasource.cache.CacheSpan>()
    var space = 0L
        private set
    private var t = 0L
    private lateinit var cache: androidx.media3.datasource.cache.Cache

    init {
        cache = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.media3.datasource.cache.Cache::class.java),
        ) { _, m, a ->
            when (m.name) {
                "removeSpan" -> {
                    val sp = a[0] as androidx.media3.datasource.cache.CacheSpan
                    if (spans.remove(sp.key) != null) {
                        space -= sp.length
                        ev.onSpanRemoved(cache, sp)
                    }
                    null
                }
                "getCacheSpace" -> space
                else -> null
            }
        } as androidx.media3.datasource.cache.Cache
    }

    fun add(key: String, len: Long) {
        ev.onStartFile(cache, key, 0, len)
        val sp = androidx.media3.datasource.cache.CacheSpan(key, 0, len, ++t, null)
        spans[key] = sp
        space += len
        ev.onSpanAdded(cache, sp)
    }

    fun has(key: String) = key in spans
}
