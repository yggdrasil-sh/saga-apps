package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.JsonTree
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockDocTest {
    /** Seite mit bekannten UND unbekannten Bloecken, Zusatzfeldern und Zahlen in verschiedenen Schreibweisen */
    private val sample = """{"time":1712345678901,"version":"2.31.0","blocks":[""" +
        """{"id":"a1","type":"paragraph","data":{"text":"Hallo <b>Welt</b> &amp; <a href=\"/notes#12\">Seite</a>","extra":{"x":1.0}}},""" +
        """{"id":"a2","type":"header","data":{"text":"Titel","level":2}},""" +
        """{"id":"a3","type":"list","data":{"style":"checklist","meta":{},"items":[{"content":"eins","meta":{"checked":true},"items":[]},{"content":"zwei","meta":{"checked":false},"items":[{"content":"zwei.a","meta":{},"items":[]}]}]}},""" +
        """{"id":"a4","type":"zukunftsblock","data":{"irgendwas":[1,2,{"tief":null}],"flag":false,"num":3.25}},""" +
        """{"id":"a5","type":"table","data":{"withHeadings":true,"content":[["A","B"],["1","2"]]}},""" +
        """{"id":"a6","type":"toggle","data":{"title":"Mehr","open":false,"blocks":[{"id":"t1","type":"paragraph","data":{"text":"innen"}},{"id":"t2","type":"unbekannt","data":{"k":"v"}}]}},""" +
        """{"id":"a7","type":"code","data":{"code":"if (a < b) { x = \"y\"; }\n","language":"kotlin"}},""" +
        """{"id":"a8","type":"image","data":{"file":{"url":"/files/abc"},"caption":"Bild","withBorder":false,"stretched":false}}""" +
        """]}"""

    @Test
    fun roundtripIsLossless() {
        val p = BlockDoc.parse(sample, "blocks")
        val out = BlockDoc.serialize(p.doc)
        assertEquals(sample, out)                                                  // exakt gleich (kompaktes JSON)
        assertEquals(Json.parseToJsonElement(sample), Json.parseToJsonElement(out))
    }

    @Test
    fun editingOneFieldKeepsEverythingElse() {
        val doc = BlockDoc.parse(sample, "blocks").doc
        val edited = JsonTree.set(doc, listOf("blocks", 0, "data", "text"), JsonPrimitive("Neu")) as JsonObject
        val before = doc["blocks"] as JsonArray
        val after = edited["blocks"] as JsonArray
        assertEquals(before.size, after.size)
        for (i in 1 until before.size) assertSame("Block $i muss dasselbe Objekt bleiben", before[i], after[i])
        // Zusatzfeld im geaenderten Block bleibt
        val d0 = (after[0] as JsonObject)["data"] as JsonObject
        assertEquals("Neu", (d0["text"] as JsonPrimitive).content)
        assertEquals(Json.parseToJsonElement("""{"x":1.0}"""), d0["extra"])
        assertEquals("1.0", ((d0["extra"] as JsonObject)["x"] as JsonPrimitive).content)
        // unbekannter Block unveraendert
        assertEquals(before[3], after[3])
        assertTrue(BlockDoc.serialize(edited).contains(""""num":3.25"""))
    }

    @Test
    fun nestedEditInToggleKeepsUnknownChild() {
        val doc = BlockDoc.parse(sample, "blocks").doc
        val edited = JsonTree.set(doc, listOf("blocks", 5, "data", "blocks", 0, "data", "text"), JsonPrimitive("geändert")) as JsonObject
        val inner = JsonTree.get(edited, listOf("blocks", 5, "data", "blocks")) as JsonArray
        assertEquals(2, inner.size)
        assertEquals(Json.parseToJsonElement("""{"id":"t2","type":"unbekannt","data":{"k":"v"}}"""), inner[1])
    }

    @Test
    fun insertAndRemoveBlocks() {
        val doc = BlockDoc.parse(sample, "blocks").doc
        val nb = BlockDoc.newBlock("paragraph", "x")
        val ins = JsonTree.insert(doc, listOf("blocks"), 1, nb) as JsonObject
        assertEquals(9, BlockDoc.blocks(ins).size)
        assertEquals("paragraph", BlockDoc.type(BlockDoc.blocks(ins)[1]))
        val rem = JsonTree.remove(ins, listOf("blocks"), 1) as JsonObject
        assertEquals(BlockDoc.serialize(doc), BlockDoc.serialize(rem))
        val moved = JsonTree.move(doc, listOf("blocks"), 0, 1) as JsonObject
        assertEquals("a2", BlockDoc.id(BlockDoc.blocks(moved)[0]))
        assertEquals("a1", BlockDoc.id(BlockDoc.blocks(moved)[1]))
    }

    @Test
    fun textNotesBecomeParagraphs() {
        val p = BlockDoc.parse("Zeile 1\n\nZeile <2> & mehr", "text")
        assertTrue(p.converted)
        val b = BlockDoc.blocks(p.doc)
        assertEquals(2, b.size)
        assertEquals("Zeile &lt;2&gt; &amp; mehr", BlockDoc.str(BlockDoc.data(b[1]), "text"))
    }

    @Test(expected = BlockDoc.UnreadableException::class)
    fun brokenBlocksJsonIsNotTreatedAsText() {
        BlockDoc.parse("{kaputt", "blocks")                     // darf NICHT als Text weiterlaufen (sonst Ueberschreiben)
    }

    @Test
    fun emptyBlocksBody() {
        assertEquals(0, BlockDoc.blocks(BlockDoc.parse("", "blocks").doc).size)
        assertEquals(0, BlockDoc.blocks(BlockDoc.parse("""{"blocks":[]}""", "blocks").doc).size)
    }

    @Test
    fun newBlocksLikeIos() {
        val l = BlockDoc.newBlock("checklist", "a")
        assertEquals("list", BlockDoc.type(l))
        assertEquals("checklist", BlockDoc.str(BlockDoc.data(l), "style"))
        val item = (BlockDoc.data(l)["items"] as JsonArray)[0] as JsonObject
        assertEquals("a", BlockDoc.str(item, "content"))
        assertEquals(false, BlockDoc.bool(item["meta"] as JsonObject, "checked"))
        assertEquals(10, BlockDoc.id(l)!!.length)
        val h = BlockDoc.newBlock("header", "T", level = 3)
        assertEquals(3, BlockDoc.int(BlockDoc.data(h), "level"))
        assertEquals("toggle", BlockDoc.type(BlockDoc.newBlock("toggle")))
    }

    @Test
    fun convertKeepsIdAndText() {
        val doc = BlockDoc.parse(sample, "blocks").doc
        val para = BlockDoc.blocks(doc)[0] as JsonObject
        val h = BlockDoc.convert(para, "header", 1)!!
        assertEquals("a1", BlockDoc.id(h))
        assertEquals("header", BlockDoc.type(h))
        assertEquals(BlockDoc.str(BlockDoc.data(para), "text"), BlockDoc.str(BlockDoc.data(h), "text"))
        // Liste -> andere Liste: alle Punkte bleiben
        val list = BlockDoc.blocks(doc)[2] as JsonObject
        val ordered = BlockDoc.convert(list, "ordered")!!
        assertEquals("ordered", BlockDoc.str(BlockDoc.data(ordered), "style"))
        assertEquals(BlockDoc.data(list)["items"], BlockDoc.data(ordered)["items"])
        // unbekannte Bloecke lassen sich nicht umwandeln
        assertNull(BlockDoc.convert(BlockDoc.blocks(doc)[3] as JsonObject, "paragraph"))
        // Code -> Absatz: Text wird escaped
        val code = BlockDoc.convert(BlockDoc.blocks(doc)[6] as JsonObject, "paragraph")
        assertNotNull(code)
        assertTrue(BlockDoc.str(BlockDoc.data(code), "text").startsWith("if (a &lt; b)"))
    }

    @Test
    fun setCreatesMissingObjects() {
        val doc = BlockDoc.parse("""{"blocks":[{"id":"x","type":"list","data":{"items":[{"content":"a"}]}}]}""", "blocks").doc
        val d = JsonTree.set(doc, listOf("blocks", 0, "data", "items", 0, "meta", "checked"), JsonPrimitive(true))
        assertEquals(
            Json.parseToJsonElement("""{"blocks":[{"id":"x","type":"list","data":{"items":[{"content":"a","meta":{"checked":true}}]}}]}"""), d,
        )
    }

    @Test
    fun withTimeOnlyChangesTime() {
        val doc = BlockDoc.parse(sample, "blocks").doc
        val t = BlockDoc.withTime(doc, 42L)
        assertEquals("42", (t["time"] as JsonPrimitive).content)
        assertSame(doc["blocks"], t["blocks"])
        assertEquals(doc["version"], t["version"])
    }
}
