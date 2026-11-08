package io.github.yggdrasil_sh.saga.util

import android.util.Base64

/**
 * SVG-Bilder in Seiten (Saga 2.5, Trilium-Import: bereinigte SVGs als Bild) - reine Logik, gleiche Regeln wie iOS
 * SvgLogic.swift. Anzeige nur als <img> in einer WebView ohne JavaScript, ohne Netz (blockNetworkLoads) und mit CSP
 * "default-src 'none'; img-src data:" - SVG im <img> fuehrt nie Skript aus und laedt nichts nach.
 */
object SvgLogic {
    /** groessere SVGs nicht einbetten (Platzhalter mit "Bild öffnen") */
    const val MAX_BYTES = 3 * 1024 * 1024

    private val ATTR_CACHE = HashMap<String, Regex>()
    private fun attrRe(n: String) = ATTR_CACHE.getOrPut(n) { Regex("""(?i)(^|\s)$n\s*=\s*("[^"]*"|'[^']*')""") }

    /** Daten sind ein SVG (Anfang: optional BOM, <?xml ...?>, Kommentare, DOCTYPE, dann <svg) */
    fun isSvg(d: ByteArray): Boolean {
        if (d.isEmpty()) return false
        var s = String(d, 0, minOf(d.size, 4096), Charsets.UTF_8).lowercase()
        if (s.startsWith("﻿")) s = s.substring(1)
        while (true) {
            s = s.trimStart()
            s = when {
                s.startsWith("<?") -> s.indexOf("?>").takeIf { it >= 0 }?.let { s.substring(it + 2) } ?: return false
                s.startsWith("<!--") -> s.indexOf("-->").takeIf { it >= 0 }?.let { s.substring(it + 3) } ?: return false
                s.startsWith("<!doctype") -> s.indexOf('>').takeIf { it >= 0 }?.let { s.substring(it + 1) } ?: return false
                else -> break
            }
        }
        if (!s.startsWith("<svg")) return false
        val n = s.getOrNull(4) ?: return true
        return n.isWhitespace() || n == '>' || n == '/'
    }

    /** Seitenverhaeltnis aus dem <svg>-Element: width/height (Zahl, px oder ohne Einheit), sonst viewBox; null = unbekannt */
    fun ratio(d: ByteArray): Float? {
        val head = String(d, 0, minOf(d.size, 16_384), Charsets.UTF_8)
        val a = head.indexOf("<svg", ignoreCase = true).takeIf { it >= 0 } ?: return null
        val e = head.indexOf('>', a + 4).takeIf { it >= 0 } ?: return null
        val tag = head.substring(a + 4, e)
        fun attr(n: String): String? = attrRe(n).find(tag)?.groupValues?.get(2)?.trim('"', '\'', ' ')
        fun num(s: String?): Double? {
            var t = s?.trim()?.lowercase() ?: return null
            if (t.isEmpty() || t.endsWith("%")) return null
            if (t.endsWith("px")) t = t.dropLast(2)
            return t.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        }
        val w = num(attr("width"))
        val h = num(attr("height"))
        if (w != null && h != null) return clamp(w / h)
        val vb = attr("viewBox") ?: return null
        val p = vb.split(' ', ',').filter { it.isNotBlank() }.mapNotNull { it.toDoubleOrNull() }
        if (p.size == 4 && p[2] > 0 && p[3] > 0 && p[2].isFinite() && p[3].isFinite()) return clamp(p[2] / p[3])
        return null
    }

    private fun clamp(r: Double): Float = r.coerceIn(0.05, 20.0).toFloat()

    /** Seite fuer die WebView: nur das SVG als data:-<img>, kein Skript, nichts nachladen */
    fun html(d: ByteArray, cover: Boolean = false, b64: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }): String {
        val fit = if (cover) "cover" else "contain"
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:transparent;overflow:hidden}" +
            "img{display:block;width:100%;height:100%;object-fit:$fit;pointer-events:none}</style>" +
            "</head><body><img alt=\"\" src=\"data:image/svg+xml;base64,${b64(d)}\"></body></html>"
    }
}
