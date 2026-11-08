package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.util.SvgLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.5: SVG-Bilder (Trilium-Import) und Seitenlinks auf Dateien - gleiche Faelle wie iOS SvgTests.swift */
class SvgLogicTest {
    private fun d(s: String) = s.toByteArray()
    private fun src(rel: String): String {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return File(main, "java/io/github/yggdrasil_sh/saga/$rel").readText()
    }

    @Test
    fun detect() {
        assertTrue(SvgLogic.isSvg(d("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))
        assertTrue(SvgLogic.isSvg(d("﻿<?xml version=\"1.0\"?>\n<!-- x -->\n<!DOCTYPE svg>\n<SVG width=\"1\">")))
        assertFalse(SvgLogic.isSvg(d("<html><svg></svg></html>")))
        assertFalse(SvgLogic.isSvg(d("<svgx>")))
        assertFalse(SvgLogic.isSvg(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
        assertFalse(SvgLogic.isSvg(ByteArray(0)))
    }

    @Test
    fun ratio() {
        assertEquals(2f, SvgLogic.ratio(d("<svg width=\"200\" height=\"100\">")))
        assertEquals(2f, SvgLogic.ratio(d("<svg width='300px' height='150px'>")))
        assertEquals(0.5f, SvgLogic.ratio(d("<svg stroke-width=\"5\" viewBox=\"0 0 40 80\">")))
        assertEquals(3f, SvgLogic.ratio(d("<svg width=\"100%\" height=\"100%\" viewBox=\"0,0,30,10\">")))
        assertNull(SvgLogic.ratio(d("<svg>")))
        assertNull(SvgLogic.ratio(d("<svg width=\"0\" height=\"5\">")))
        assertEquals(20f, SvgLogic.ratio(d("<svg width=\"10000\" height=\"1\">")))
    }

    @Test
    fun html() {
        val b64: (ByteArray) -> String = { java.util.Base64.getEncoder().encodeToString(it) }
        val h = SvgLogic.html(d("<svg/>"), b64 = b64)
        assertTrue(h.contains("default-src 'none'; img-src data:"))
        assertTrue(h.contains("<img alt=\"\" src=\"data:image/svg+xml;base64,PHN2Zy8+\">"))
        assertFalse(h.lowercase().contains("<script") || h.contains("<svg"))
        assertTrue(SvgLogic.html(d("<svg/>"), cover = true, b64 = b64).contains("object-fit:cover"))
    }

    @Test
    fun wiring() {
        val w = src("ui/page/SvgImage.kt")
        assertTrue(w.contains("settings.javaScriptEnabled = false") && w.contains("settings.blockNetworkLoads = true"))
        assertTrue(w.contains("settings.allowFileAccess = false") && w.contains("settings.allowContentAccess = false"))
        assertTrue(w.contains("shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true"))
        val b = src("ui/page/BlocksExtra.kt")
        assertTrue(b.contains("onError = { failed = true }") && b.contains("svg = fetchSvg(env.repo, sized)"))
        // Seitenlink /files/<id>: wie ein Anhang ueber die Server-Adresse oeffnen
        assertTrue(src("ui/page/PageScreen.kt").contains("href.startsWith(\"/\") -> openUrl(context, repo.absoluteUrl(href))"))
    }
}
