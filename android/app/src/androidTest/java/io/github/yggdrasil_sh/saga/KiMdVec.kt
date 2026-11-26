package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.AiLogic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pruefer pcrash: Vektor -> erwartete Zeilen (wie KiMarkdownTest) */
object KiMdVec {
    fun want(o: JsonObject): List<AiLogic.Line> = o["lines"]!!.jsonArray.map { l ->
        val lo = l.jsonObject
        AiLogic.Line(lo["k"]!!.jsonPrimitive.content, n = lo["n"]?.jsonPrimitive?.int, spans = lo["s"]!!.jsonArray.map { sp ->
            val a = sp.jsonArray
            when (a[0].jsonPrimitive.content) {
                "b" -> AiLogic.Span.B(a[1].jsonPrimitive.content)
                "c" -> AiLogic.Span.C(a[1].jsonPrimitive.content)
                "n" -> AiLogic.Span.N(a[1].jsonPrimitive.int)
                else -> AiLogic.Span.T(a[1].jsonPrimitive.content)
            }
        })
    }

    /** Liefert Fehlerliste (leer = alles gleich) fuer "assistant"; known_gaps werden nur gemeldet */
    fun check(j: JsonObject, tag: String): Pair<List<String>, List<String>> {
        val cites = j["cites"]!!.jsonArray.map { it.jsonPrimitive.int }.toSet()
        val bad = ArrayList<String>()
        val info = ArrayList<String>()
        for (v in j["assistant"]!!.jsonArray) {
            val o = v.jsonObject
            val inp = o["in"]!!.jsonPrimitive.content
            val got = AiLogic.lines(inp, cites)
            if (got != want(o)) bad.add("$tag ${esc(inp)}: soll ${want(o)} ist $got")
        }
        j["known_gaps"]?.jsonArray?.forEach { v ->
            val o = v.jsonObject
            val inp = o["in"]!!.jsonPrimitive.content
            val got = AiLogic.lines(inp, cites)
            info.add("$tag GAP ${esc(inp)}: ${if (got == want(o)) "gleich" else "anders: soll ${want(o)} ist $got"}")
        }
        return bad to info
    }

    fun esc(s: String) = s.map { if (it.code < 0x20 || it.code in 0x7f..0xa0 || it.code in 0x2000..0x206f || it.code == 0x3000 || it.code == 0x1680) "\\u%04x".format(it.code) else it.toString() }.joinToString("")
}
