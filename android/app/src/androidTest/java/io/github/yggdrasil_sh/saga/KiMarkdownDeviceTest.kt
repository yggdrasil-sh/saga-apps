package io.github.yggdrasil_sh.saga

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Pruefer pcrash: AiLogic mit Androids ICU-Regex (Server-Vektoren + Unicode-Vektoren) */
@RunWith(AndroidJUnit4::class)
class KiMarkdownDeviceTest {
    private fun asset(n: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(n).bufferedReader().readText()

    @Test fun engineIsIcu() {
        try {
            Pattern.compile("(?U)\\s")
            Log.i("PCRASH", "(?U) kompiliert auf diesem Geraet")
            fail("(?U) haette auf ICU werfen sollen - Probe ohne Aussage")
        } catch (e: PatternSyntaxException) {
            Log.i("PCRASH", "(?U) wirft wie erwartet: ${e.description}")
        }
    }

    @Test fun vectors() {
        val all = ArrayList<String>()
        for ((f, tag) in listOf("ki_md.json" to "SRV", "ki_md_unicode.json" to "UNI")) {
            val (bad, info) = KiMdVec.check(Json.parseToJsonElement(asset(f)).jsonObject, tag)
            info.forEach { Log.i("PCRASH", it) }
            bad.forEach { Log.e("PCRASH", it) }
            all += bad
        }
        Log.i("PCRASH", "fertig, Abweichungen: ${all.size}")
        assertTrue(all.joinToString("\n"), all.isEmpty())
    }
}
