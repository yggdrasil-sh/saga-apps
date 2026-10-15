package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.Drafts
import io.github.yggdrasil_sh.saga.data.ImmichAsset
import io.github.yggdrasil_sh.saga.data.MemoryDrafts
import io.github.yggdrasil_sh.saga.ui.page.PageBackend
import io.github.yggdrasil_sh.saga.ui.page.PageEditor
import io.github.yggdrasil_sh.saga.util.ImageData
import io.github.yggdrasil_sh.saga.util.ImmichChat
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class ImagePageServer(var body: String, val locked: Boolean = false) : PageBackend {
    val saved = ArrayList<String>()
    override val currentServer = "https://s.example.org"
    override val signedOut = false
    private val drafts = MemoryDrafts()
    override fun draftsForCurrent(): Drafts = drafts
    override suspend fun <T> withPageLock(id: Long, block: suspend () -> T): T = block()
    override suspend fun loadPage(id: Long): JsonObject = buildJsonObject {
        put("id", id); put("title", "T"); put("body", body); put("format", "blocks"); put("rev", 1); put("locked", locked)
    }
    override suspend fun savePage(id: Long, title: String, body: String?, rev: Int?): Int? {
        saved += body.orEmpty()
        return (rev ?: 1) + 1
    }
    override suspend fun createPageWith(title: String, body: String): Long = 2
    override suspend fun setPinned(id: Long, pinned: Boolean) {}
    override suspend fun setLocked(id: Long, locked: Boolean) {}
    override fun updateTitleLocal(id: Long, title: String) {}
    override fun visited(id: Long) {}
    override fun friendly(e: Throwable): UiText = UiText.Raw(e.message ?: "?")
}

/** 2.1: Bilder einfuegen (Seite), Immich im Chat (Groessen/Fallback), Bildformate */
@OptIn(ExperimentalCoroutinesApi::class)
class V21ImagesTest {
    private fun TestScope.editor(srv: PageBackend): PageEditor {
        val d = StandardTestDispatcher(testScheduler)
        return PageEditor(1, srv, backgroundScope, io = d, cpu = d, clock = { testScheduler.currentTime })
    }

    private fun types(ed: PageEditor) = BlockDoc.blocks(ed.doc).map { BlockDoc.type(it as JsonObject) }
    private fun url(b: Any?) = BlockDoc.str((BlockDoc.data(b as JsonObject)["file"] as JsonObject), "url")

    @Test
    fun emptyParagraphBecomesTheImage() = runTest {
        val ed = editor(ImagePageServer("""{"blocks":[{"id":"a","type":"paragraph","data":{"text":"Hallo"}},{"id":"b","type":"paragraph","data":{"text":""}}]}"""))
        ed.load()
        val p = ed.insertImage(listOf("blocks", 1), "/files/abc")
        assertEquals(listOf<Any>("blocks", 1), p)
        assertEquals(listOf("paragraph", "image"), types(ed))
        assertEquals("/files/abc", url(BlockDoc.blocks(ed.doc)[1]))
        assertTrue(ed.dirty)
    }

    @Test
    fun imageGoesAfterTextBlockAndSeveralKeepTheirOrder() = runTest {
        val srv = ImagePageServer("""{"blocks":[{"id":"a","type":"paragraph","data":{"text":"Hallo"}},{"id":"z","type":"paragraph","data":{"text":"Ende"}}]}""")
        val ed = editor(srv)
        ed.load()
        var at: List<Any>? = listOf("blocks", 0)
        at = ed.insertImage(at, "/files/1")
        at = ed.insertImage(at, "/files/2")
        assertEquals(listOf<Any>("blocks", 2), at)
        assertEquals(listOf("paragraph", "image", "image", "paragraph"), types(ed))
        assertEquals(listOf("/files/1", "/files/2"), BlockDoc.blocks(ed.doc).drop(1).take(2).map { url(it) })
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("wird gespeichert", srv.saved.last().contains("\"type\":\"image\"") && srv.saved.last().contains("/files/2"))
    }

    @Test
    fun withoutPathTheImageIsAppended() = runTest {
        val ed = editor(ImagePageServer("""{"blocks":[{"id":"a","type":"paragraph","data":{"text":"Hallo"}}]}"""))
        ed.load()
        ed.insertImage(null, "/files/x")
        assertEquals(listOf("paragraph", "image"), types(ed))
        val d = BlockDoc.data(BlockDoc.blocks(ed.doc)[1] as JsonObject)
        assertEquals("", BlockDoc.str(d, "caption"))
    }

    @Test
    fun lockedPageGetsNoImage() = runTest {
        val ed = editor(ImagePageServer("""{"blocks":[{"id":"a","type":"paragraph","data":{"text":""}}]}""", locked = true))
        ed.load()
        assertNull(ed.insertImage(listOf("blocks", 0), "/files/x"))
        assertEquals(listOf("paragraph"), types(ed))
    }

    @Test
    fun sniffsServerImageFormats() {
        assertEquals("image/png", ImageData.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("image/jpeg", ImageData.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", ImageData.sniff("GIF89a....".toByteArray()))
        assertEquals("image/webp", ImageData.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(ImageData.sniff("\u0000\u0000\u0000\u0018ftypheic".toByteArray(Charsets.ISO_8859_1)))   // HEIC -> wird umgewandelt
        assertNull(ImageData.sniff(ByteArray(0)))
        assertEquals("IMG_1.jpg", ImageData.nameFor("IMG_1.HEIC", "image/jpeg"))
        assertEquals("bild.png", ImageData.nameFor("", "image/png"))
        assertEquals("anim.gif", ImageData.nameFor("anim.gif", "image/gif"))
    }

    @Test
    fun immichChatPrefersOriginalAndFallsBackToPreview() = runTest {
        assertEquals(listOf("original", "preview"), ImmichChat.sizes("image/jpeg"))
        assertEquals(listOf("original", "preview"), ImmichChat.sizes("image/gif"))
        assertEquals(listOf("preview"), ImmichChat.sizes("image/heic"))
        assertEquals(listOf("preview"), ImmichChat.sizes(null))
        assertEquals("api/immich/thumb/ab-12?size=preview", ImmichChat.path("ab-12", "preview"))

        val code: (Throwable) -> Int? = { (it as? ApiException)?.code }
        for (c in listOf(404, 413, 415)) {
            val asked = ArrayList<String>()
            val r = ImmichChat.firstAvailable(listOf("original", "preview"), code) { s ->
                asked += s
                if (s == "original") throw ApiException(c, UiText.Raw("x")) else "P"
            }
            assertEquals("P", r)
            assertEquals(listOf("original", "preview"), asked)
        }
        // anderer Fehler (Server kaputt, kein Netz): nicht still auf die Vorschau ausweichen
        try {
            ImmichChat.firstAvailable(listOf("original", "preview"), code) { throw ApiException(500, UiText.Raw("x")) }
            fail()
        } catch (e: ApiException) {
            assertEquals(500, e.code)
        }
        // alles nicht verfuegbar -> letzter Fehler
        try {
            ImmichChat.firstAvailable(listOf("original", "preview"), code) { s -> throw ApiException(if (s == "original") 415 else 404, UiText.Raw(s)) }
            fail()
        } catch (e: ApiException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun immichListsDecodeLikeTheServerSendsThem() {
        val j = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        val a = j.decodeFromString<io.github.yggdrasil_sh.saga.data.ImmichAssets>(
            """{"items":[{"id":"x1","name":"a.gif","mime":"image/gif","date":"2026-01-01"},{"id":"x2","name":"b.heic","mime":null}],"next":2}""",
        )
        assertEquals(2, a.next)
        assertTrue(a.items[0].isGif)
        assertEquals(ImmichAsset("x2", "b.heic", null, null), a.items[1])
        val al = j.decodeFromString<io.github.yggdrasil_sh.saga.data.ImmichAlbums>("""{"albums":[{"id":"al","name":"Urlaub","count":3,"thumb":null}]}""")
        assertEquals(3, al.albums[0].count)
        val st = j.decodeFromString<io.github.yggdrasil_sh.saga.data.ImmichStatus>("""{"available":true,"connected":false,"suggest":"https://immich.example"}""")
        assertTrue(st.available && !st.connected)
    }
}
