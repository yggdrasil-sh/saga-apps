package io.github.yggdrasil_sh.saga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.data.TodayLine
import io.github.yggdrasil_sh.saga.data.TodayLineFetch
import io.github.yggdrasil_sh.saga.data.TodayWidgetSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 2.6 (Bugs-DB 2271 K-D): Gegenprobe der Vektoren tests/today_vectors.json auf dem Geraet (ART/ICU statt JVM) */
@RunWith(AndroidJUnit4::class)
class TodayLine2271VectorsTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    @Test fun vectorsOnDevice() {
        val v = Json.parseToJsonElement(ins.context.assets.open("today_vectors.json").bufferedReader().readText()).jsonObject
        val cases = v["cases"]!!.jsonArray.map { it.jsonObject }
        val normal = TodayLine.parse(cases.first { it.str("id") == "normal" }["response"].toString())
        var n = 0
        for (c in cases) {
            val id = c.str("id")!!
            val show = c["show"]!!.jsonObject
            val resp = c["response"]?.takeIf { it !is JsonNull }?.toString()
            if (c["request"]!!.jsonObject.str("path")!!.startsWith("/api/app/today")) {
                assertEquals(id, show.str("medium"), TodayLine.widgetText(TodayLine.widgetLine(resp), TodayWidgetSize.MEDIUM))
                n++; continue
            }
            val st = when (val r = TodayLine.result((c["status"] as JsonPrimitive).intOrNull!!, resp)) {
                is TodayLineFetch.State -> r.s
                TodayLineFetch.Keep -> normal
                TodayLineFetch.Empty -> null
            }
            val got = TodayLine.show(st)
            val k = show["kurz"] as? JsonObject
            assertEquals("$id Teile", (k?.get("parts") as? JsonArray)?.map { (it as JsonPrimitive).content }, got.kurz?.parts?.map { it.text })
            assertEquals("$id a11y", k?.str("a11y"), got.kurz?.a11y)
            assertEquals("$id Pille", (show["pill"] as? JsonObject)?.str("text"), got.pill?.text)
            assertEquals("$id Frage", (show["ask"] as? JsonObject)?.str("text"), got.ask?.text)
            assertEquals("$id wartet", show.str("waiting"), got.waiting?.text)
            n++
        }
        assertEquals(cases.size, n)
        assertEquals("api/ai/today/quiet?tz=Etc/GMT%2B1", TodayLine.quietPath("Etc/GMT+1"))
    }

}
