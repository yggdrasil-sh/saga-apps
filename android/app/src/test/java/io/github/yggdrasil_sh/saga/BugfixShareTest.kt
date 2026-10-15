package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BUG-024: Teilen "als Aufgabe" ohne Titel sendet den Text mit (Server nimmt die erste Zeile) */
class BugfixShareTest {
    private fun s(o: kotlinx.serialization.json.JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content

    @Test
    fun taskCaptureCarriesText() {
        val b = SagaRepository.captureBody("task", "", "Milch kaufen", null)
        assertEquals("task", s(b, "kind"))
        assertEquals("", s(b, "title"))
        assertEquals("Milch kaufen", s(b, "text"))
        assertNull(b["url"])
    }

    @Test
    fun pageCaptureUnchanged() {
        val b = SagaRepository.captureBody("page", "Betreff", "Absatz 1\n\nAbsatz 2", "https://example.org/x")
        assertEquals("page", s(b, "kind"))
        assertEquals("Absatz 1\n\nAbsatz 2", s(b, "text"))
        assertEquals("https://example.org/x", s(b, "url"))
    }
}
