package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.AiLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** KI-Darstellung (Bugs-DB 2248): AiLogic.lines/spans gegen die Server-Vektoren (vectors/ki_md.json, Kopie von
 *  docs/ki-markdown-vektoren.json aus dem Repo saga, erzeugt mit tools/ki_md_vectors.py) */
class KiMarkdownTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun vectors() {
        val j = Json.parseToJsonElement(res("vectors/ki_md.json")).jsonObject
        val cites = j["cites"]!!.jsonArray.map { it.jsonPrimitive.int }.toSet()
        val vs = j["assistant"]!!.jsonArray
        assertTrue(vs.size > 20)
        for (v in vs) {
            val o = v.jsonObject
            val inp = o["in"]!!.jsonPrimitive.content
            val want = o["lines"]!!.jsonArray.map { l ->
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
            assertEquals("ki_md: $inp", want, AiLogic.lines(inp, cites))
        }
    }
}
