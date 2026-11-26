package io.github.yggdrasil_sh.saga

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruefer pcrash: Unicode-Vektoren (ai_md.py) auf der JVM */
class KiMarkdownUnicodeTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun unicode() {
        val (bad, info) = KiMdVec.check(Json.parseToJsonElement(res("vectors/ki_md_unicode.json")).jsonObject, "JVM")
        info.forEach { println("PCRASH-INFO $it") }
        bad.forEach { println("PCRASH-BAD $it") }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
