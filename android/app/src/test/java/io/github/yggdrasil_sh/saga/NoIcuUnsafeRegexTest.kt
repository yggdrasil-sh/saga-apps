package io.github.yggdrasil_sh.saga

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Waechter (Vorschlag pcrash): Androids ICU-Regex kennt das Inline-Flag (?U) und UNICODE_CHARACTER_CLASS nicht
 *  (PatternSyntaxException/IllegalArgumentException erst auf dem Geraet - die JVM-Tests sehen es nicht). */
class NoIcuUnsafeRegexTest {
    @Test fun noUnicodeCharacterClassFlag() {
        val root = File("src/main/java")
        assertTrue("Quellbaum fehlt: ${root.absolutePath}", root.isDirectory)
        val bad = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]|UNICODE_CHARACTER_CLASS""")
        val hits = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                val code = l.substringBefore("//")
                if (bad.containsMatchIn(code)) "${f.path}:${i + 1}: ${l.trim()}" else null
            }
        }.toList()
        assertTrue("ICU-unvertraeglich:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
