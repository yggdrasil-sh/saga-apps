package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.FaviconLogic
import io.github.yggdrasil_sh.saga.data.FaviconLogic.CacheDecision
import io.github.yggdrasil_sh.saga.data.FaviconLogic.Source
import io.github.yggdrasil_sh.saga.data.ServiceItem
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 Lesezeichen-Favicons: Rueckfall auf dem Geraet - Reihenfolge, URL-Pruefung, Limits, Cache (wie iOS FaviconTests) */
class FaviconLogicTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val bm = "https://shop.example.org/start?x=1"
    private val direct = listOf("https://shop.example.org/apple-touch-icon.png", "https://shop.example.org/favicon.ico")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun parsedFromServer() {
        val s = json.decodeFromString<ServiceItem>(
            """{"name":"Shop","url":"$bm","own":true,"key":"bm1","icon_url":"/api/services/favicon/bm1?h=1",
               "icon_direct":["${direct[0]}","${direct[1]}"]}"""
        )
        assertEquals(direct, s.directIcons())
        assertEquals("api/services/favicon/bm1?h=1", s.iconPath())
    }

    @Test
    fun olderServerAndServicesUnchanged() {
        val old = json.decodeFromString<ServiceItem>("""{"name":"Shop","url":"$bm","own":true,"icon_url":"/api/services/favicon/bm1"}""")
        assertTrue(old.directIcons().isEmpty())
        val svc = json.decodeFromString<ServiceItem>("""{"name":"NC","url":"https://nc.example.org","icon":"nextcloud"}""")
        assertTrue(svc.directIcons().isEmpty())
        assertEquals("api/services/icon/nextcloud", svc.iconPath())
    }

    @Test
    fun candidateChecks() {
        val c = FaviconLogic::directCandidates
        assertEquals(direct, c(direct, bm))
        assertTrue(c(listOf("https://andere.example.org/favicon.ico"), bm).isEmpty())
        assertEquals(1, c(listOf("https://SHOP.example.org/favicon.ico"), bm).size)
        assertTrue(c(listOf("http://shop.example.org/favicon.ico"), bm).isEmpty())
        assertEquals(2, c(listOf("http://shop.example.org/favicon.ico", "https://shop.example.org/favicon.ico"), "http://shop.example.org/").size)
        assertTrue(c(listOf("ftp://shop.example.org/f.ico", "data:image/png;base64,AA", "javascript:alert(1)", "/favicon.ico"), bm).isEmpty())
        assertTrue(c(listOf("https://u:p@shop.example.org/favicon.ico"), bm).isEmpty())
        assertTrue(c(listOf("https://shop.example.org:8443/favicon.ico"), bm).isEmpty())
        assertEquals(1, c(listOf("https://shop.example.org:443/favicon.ico"), bm).size)
        assertTrue(c(direct, "mailto:a@example.org").isEmpty())
        assertEquals(3, c((1..5).map { "https://shop.example.org/i$it.png" }, bm).size)
        assertEquals(1, c(listOf(direct[1], direct[1]), bm).size)
    }

    @Test
    fun orderServerThenDirect() {
        val p = FaviconLogic.plan("api/services/favicon/k", direct)
        assertEquals(listOf(Source.Server("api/services/favicon/k"), Source.Direct(direct[0]), Source.Direct(direct[1])), p)
        assertTrue(FaviconLogic.plan(null, emptyList()).isEmpty())
        val tried = mutableListOf<Source>()
        val r = FaviconLogic.first(p) { tried += it; if (it == Source.Direct(direct[1])) png else null }
        assertEquals(Source.Direct(direct[1]), r?.first)
        assertEquals(3, tried.size)
        tried.clear()
        FaviconLogic.first(p) { tried += it; png }
        assertEquals(listOf(p[0]), tried)
        assertNull(FaviconLogic.first(p) { null as ByteArray? })
    }

    @Test
    fun rasterOnly() {
        val ico = byteArrayOf(0, 0, 1, 0, 1, 0)
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val gif = "GIF89a".toByteArray()
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()
        listOf(png, ico, jpg, gif, webp).forEach { assertTrue(FaviconLogic.isRaster(it)) }
        assertFalse(FaviconLogic.isRaster("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertFalse(FaviconLogic.isRaster("<!doctype html>".toByteArray()))
        assertFalse(FaviconLogic.isRaster("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        assertFalse(FaviconLogic.isRaster(byteArrayOf(0x89.toByte())))
    }

    @Test
    fun limits() {
        assertTrue(FaviconLogic.TIMEOUT_MS <= 5_000 && FaviconLogic.MAX_BYTES == 524_288)
        assertTrue(FaviconLogic.accept(200, "application/octet-stream", null, png))
        assertFalse(FaviconLogic.accept(503, "image/png", null, png))
        assertFalse(FaviconLogic.accept(200, "image/svg+xml", null, png))
        assertFalse(FaviconLogic.accept(200, "text/html; charset=utf-8", null, png))
        assertFalse(FaviconLogic.accept(200, "image/png", null, png + ByteArray(FaviconLogic.MAX_BYTES)))
        assertTrue(FaviconLogic.accept(200, "image/png", null, png + ByteArray(FaviconLogic.MAX_BYTES - png.size)))
        assertFalse(FaviconLogic.accept(200, "image/png", FaviconLogic.MAX_BYTES + 1L, png))
        assertTrue(FaviconLogic.rejectEarly(200, "image/png", 10_000_000))
        assertTrue(FaviconLogic.rejectEarly(404, "image/png", null))
        assertFalse(FaviconLogic.rejectEarly(200, null, null))
    }

    @Test
    fun redirects() {
        val https = "https://shop.example.org/favicon.ico".toHttpUrl()
        val http = "http://shop.example.org/favicon.ico".toHttpUrl()
        assertTrue(FaviconLogic.redirectAllowed(https, "https://cdn.example.org/f.ico".toHttpUrl()))
        assertFalse(FaviconLogic.redirectAllowed(https, http))
        assertTrue(FaviconLogic.redirectAllowed(http, https))
        assertFalse(FaviconLogic.redirectAllowed(https, null))
    }

    @Test
    fun cache() {
        val t0 = 1_800_000_000_000L
        val day = 86_400_000L
        assertEquals(CacheDecision.USE, FaviconLogic.cacheDecision(true, t0, t0 + 6 * day))
        assertEquals(CacheDecision.RELOAD, FaviconLogic.cacheDecision(true, t0, t0 + 7 * day + 1))
        assertEquals(CacheDecision.KNOWN_MISSING, FaviconLogic.cacheDecision(false, t0, t0 + 59 * 60_000))
        assertEquals(CacheDecision.RELOAD, FaviconLogic.cacheDecision(false, t0, t0 + 3_601_000))
        assertEquals(CacheDecision.RELOAD, FaviconLogic.cacheDecision(true, t0, t0 - 10))
        val k = FaviconLogic.cacheKey(direct)
        assertEquals(k, FaviconLogic.cacheKey(direct))
        assertEquals(16, k.length)
        assertTrue(k != FaviconLogic.cacheKey(direct.reversed()))
        assertEquals("cbf29ce484222325", FaviconLogic.cacheKey(emptyList()))
    }
}
