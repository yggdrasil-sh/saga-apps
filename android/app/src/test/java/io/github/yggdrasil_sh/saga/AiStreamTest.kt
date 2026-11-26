package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ai.AiChunk
import io.github.yggdrasil_sh.saga.ai.AiLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bugs-DB 2514: der KI-Strom (AiService.stream) verlor bei langsamem Sammler Stuecke - trySend verwirft bei vollem
 * Puffer (callbackFlow: 64). Jetzt liest AiLogic.pump Zeile fuer Zeile und wartet auf send; gleiche Bauart wie dort.
 */
class AiStreamTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** wie AiService.stream: Leser auf IO, Kanal mit Standardpuffer, emit = send */
    private fun flowOf(lines: List<String>): Flow<AiChunk> = callbackFlow {
        val iter = lines.iterator()
        val job = launch(Dispatchers.IO) {
            try {
                AiLogic.pump({ if (iter.hasNext()) iter.next() else null }, json) { c -> send(c) }
            } finally {
                close()
            }
        }
        awaitClose { job.cancel() }
    }

    private fun sse(n: Int, tail: List<String>) = buildList {
        add(":" + " ".repeat(2048))
        add("")
        repeat(n) { i ->
            add("event: delta")
            add("data: {\"t\":\"$i,\"}")
            add("")
        }
        addAll(tail)
    }

    @Test fun slowCollectorGetsEveryChunk() = runBlocking {
        val lines = sse(500, listOf("event: done", "data: {\"quota_left\":7}", "", "event: delta", "data: {\"t\":\"nach done\"}", ""))
        val got = ArrayList<AiChunk>()
        flowOf(lines).collect { c ->
            delay(1)                                     // Sammler langsamer als der Leser: Puffer (64) laeuft voll
            got.add(c)
        }
        assertEquals("500 delta + done, nichts verworfen", 501, got.size)
        val text = got.filterIsInstance<AiChunk.Delta>().joinToString("") { it.text }
        assertEquals((0 until 500).joinToString("") { "$it," }, text)
        assertTrue(got.last() is AiChunk.Done)
        assertEquals(7, (got.last() as AiChunk.Done).done.quotaLeft)
    }

    @Test fun endsAfterErrorAndCountsLastEventWithoutBlankLine() = runBlocking {
        val err = ArrayList<AiChunk>()
        flowOf(sse(3, listOf("event: error", "data: {\"error\":\"weg\",\"partial\":true}", "", "event: delta", "data: {\"t\":\"x\"}", "")))
            .collect { err.add(it) }
        assertEquals(4, err.size)
        assertTrue((err.last() as AiChunk.Failed).partial)
        // Strom endet ohne abschliessende Leerzeile: das letzte Ereignis geht nicht verloren
        val last = ArrayList<AiChunk>()
        flowOf(sse(2, listOf("event: done", "data: {\"quota_left\":1}"))).collect { last.add(it) }
        assertEquals(3, last.size)
        assertTrue(last.last() is AiChunk.Done)
    }
}
