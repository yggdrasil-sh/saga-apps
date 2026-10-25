package io.github.yggdrasil_sh.saga

import android.content.Intent
import io.github.yggdrasil_sh.saga.util.Links
import io.github.yggdrasil_sh.saga.util.OpenWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Links im Standard-Browser, Anhaenge ohne Chooser oeffnen ("Immer" moeglich) - Regeln und Quelltext-Waechter */
class OpenWithTest {
    @Test
    fun viewOhneChooserMitLeserecht() {
        val p = OpenWith.viewPlan("image/jpeg")
        assertEquals(Intent.ACTION_VIEW, p.action)
        assertFalse("VIEW darf nicht in einen Chooser (der kennt kein 'Immer')", p.chooser)
        assertEquals("image/jpeg", p.type)
        assertTrue(p.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(p.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun teilenMitChooser() {
        val p = OpenWith.sharePlan("application/pdf", "a.pdf")
        assertEquals(Intent.ACTION_SEND, p.action)
        assertTrue(p.chooser)
        assertEquals("a.pdf", p.title)
        assertTrue(p.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun mimeNormalisieren() {
        assertEquals("image/png", OpenWith.mime("Image/PNG"))
        assertEquals("text/plain", OpenWith.mime("text/plain; charset=utf-8"))
        assertEquals(OpenWith.FALLBACK, OpenWith.mime(""))
        assertEquals(OpenWith.FALLBACK, OpenWith.mime(null))
        assertEquals(OpenWith.FALLBACK, OpenWith.mime("kaputt"))
        // leer/octet-stream: nach Endung raten
        val guess = { e: String -> mapOf("pdf" to "application/pdf", "jpg" to "image/jpeg")[e] }
        assertEquals("application/pdf", OpenWith.mime("", "Rechnung.PDF", guess))
        assertEquals("image/jpeg", OpenWith.mime("application/octet-stream", "foto.jpg", guess))
        assertEquals(OpenWith.FALLBACK, OpenWith.mime("", "ohne-endung", guess))
        // angegebener Typ gewinnt
        assertEquals("video/mp4", OpenWith.mime("video/mp4", "x.pdf", guess))
    }

    @Test
    fun linksImText() {
        assertEquals(
            listOf(Links.Part("siehe "), Links.Part("https://example.org/a", "https://example.org/a"), Links.Part(".")),
            Links.split("siehe https://example.org/a."),
        )
        assertEquals(listOf(Links.Part("javascript:alert(1) intent://x")), Links.split("javascript:alert(1) intent://x"))
        assertEquals(2, Links.split("http://a.example und https://b.example").count { it.url != null })
        assertEquals(emptyList<Links.Part>(), Links.split(""))
    }

    private fun sources(): List<File> {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return main.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    @Test
    fun keinChooserUmView() {
        // createChooser nur fuer ACTION_SEND: steht in einer Datei ein createChooser, darf er kein VIEW umhuellen
        for (f in sources()) {
            f.readLines().forEachIndexed { i, l ->
                if (l.contains("createChooser(")) {
                    assertFalse("${f.name}:${i + 1} Chooser um VIEW", l.contains("view", ignoreCase = true) && !l.contains("p.title"))
                }
                assertFalse("${f.name}:${i + 1} EXTRA_INITIAL_INTENTS (Chooser um VIEW)", l.contains("EXTRA_INITIAL_INTENTS"))
            }
        }
    }

    @Test
    fun linksNurUeberOpenUrl() {
        for (f in sources()) {
            val t = f.readText()
            // Custom Tabs nur in openUrl (Anmeldungen mit inApp = true)
            if (t.contains("CustomTabsIntent")) assertEquals("CustomTabsIntent ausserhalb von openUrl: ${f.name}", "Common.kt", f.name)
            assertFalse("Paketbindung an Chrome: ${f.name}", t.contains("com.android.chrome"))
            assertFalse("LinkAnnotation.Url umgeht openUrl: ${f.name}", t.contains("LinkAnnotation.Url"))
            assertFalse("LocalUriHandler umgeht openUrl: ${f.name}", t.contains("LocalUriHandler"))
            // http(s) direkt per ACTION_VIEW starten statt openUrl
            assertFalse("ACTION_VIEW mit http-Link: ${f.name}", Regex("""ACTION_VIEW[^\n]*"https?://""").containsMatchIn(t))
        }
    }
}
