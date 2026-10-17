package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.DbProps
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.util.LocalNetwork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** 2.1-c: lokales Netz (Android 17), Datenbank-Zeilen, Seiten aus einem Notion-Import */
class V21cTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun localAddresses() {
        listOf("192.168.0.20", "10.0.2.2", "172.16.0.1", "172.31.255.1", "169.254.1.1", "100.64.0.1", "100.127.0.1",
            "fe80::1", "fd12:3456::1", "fc00::1").forEach { assertTrue(it, LocalNetwork.isLocalAddress(ip(it))) }
        listOf("127.0.0.1", "::1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1", "0.0.0.0").forEach {
            assertFalse(it, LocalNetwork.isLocalAddress(ip(it)))
        }
    }

    @Test fun localServer() {
        val split = { _: String -> arrayOf(ip("192.168.0.20")) }        // Split-DNS: oeffentlicher Name -> Heimnetz
        val public = { _: String -> arrayOf(ip("203.0.113.5")) }
        assertTrue(LocalNetwork.isLocalServer("https://saga.example.org", split))
        assertFalse(LocalNetwork.isLocalServer("https://saga.example.org", public))
        assertTrue(LocalNetwork.isLocalServer("https://nas.local:8443", public))
        assertTrue(LocalNetwork.isLocalServer("http://10.0.2.2:8150"))
        assertFalse(LocalNetwork.isLocalServer("http://127.0.0.1:8150"))
        assertFalse(LocalNetwork.isLocalServer("https://x.example") { throw java.net.UnknownHostException() })
        assertFalse(LocalNetwork.isLocalServer("kaputt ::"))
        assertEquals("fd00::1", LocalNetwork.hostOf("https://[fd00::1]:8443"))
        assertTrue(LocalNetwork.applies(37))
        assertFalse(LocalNetwork.applies(36))
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val schema = obj(
        """{"props":[
        {"id":"status","name":"Status","type":"select","options":[{"id":"todo","name":"Offen"},{"id":"done","name":"Erledigt"}]},
        {"id":"tags","name":"Tags","type":"multi_select","options":[{"id":"a","name":"Alpha"},{"id":"b","name":"Beta"}]},
        {"id":"datum","name":"Datum","type":"date"},{"id":"menge","name":"Menge","type":"number"},
        {"id":"anteil","name":"Anteil","type":"number","fmt":"percent"},{"id":"fertig","name":"Fertig","type":"checkbox"},
        {"id":"link","name":"Link","type":"url"},{"id":"notiz","name":"Notiz","type":"text"},
        {"id":"erstellt","name":"Erstellt","type":"created"},{"id":"rel","name":"Kontakt","type":"relation","target":5},
        {"id":"calc","name":"Quote","type":"calc","a":"menge","b":"anteil","op":"pct"},
        {"id":"neu","name":"Neuer Typ","type":"rollup"},{"name":"ohne id","type":"text"}, 7, null]}""",
    )

    @Test fun rowProperties() {
        val props = obj(
            """{"status":"done","tags":["a","x"],"datum":"2026-10-04","menge":12,"anteil":50,"fertig":true,
            "link":"https://e.org/?a=1&amp;b=2","notiz":"<b>fett</b> &amp; mehr","rel":[3,99],"neu":["r1","r2"],"unbekannt":1}""",
        )
        val f = DbProps.fields(schema, props, created = 1.0, titleOf = { if (it == 3L) "Kontakt 3" else null }, time = { "T$it" })
            .associate { it.name to it.value }
        assertEquals("Erledigt", f["Status"])
        assertEquals("Alpha, x", f["Tags"])
        assertEquals("2026-10-04", f["Datum"])
        assertEquals("12", f["Menge"])
        assertEquals("50 %", f["Anteil"])
        assertEquals("☑", f["Fertig"])
        assertEquals("https://e.org/?a=1&b=2", f["Link"])
        assertEquals("fett & mehr", f["Notiz"])
        assertEquals("T1.0", f["Erstellt"])
        assertEquals("Kontakt 3, 99", f["Kontakt"])
        assertEquals("24", f["Quote"])                                 // 12 / 50 * 100
        assertEquals("r1, r2", f["Neuer Typ"])
    }

    @Test fun rowPropertiesTolerant() {
        // falsche Formen: nichts darf werfen
        val props = obj("""{"status":["done"],"tags":"a","datum":{"start":"2026-01-01","end":"2026-01-02"},"menge":"abc","fertig":null,"rel":"3","calc":5}""")
        val f = DbProps.fields(schema, props).associate { it.name to it.value }
        assertEquals("Alpha", f["Tags"])
        assertEquals("2026-01-01 – 2026-01-02", f["Datum"])
        assertEquals("abc", f["Menge"])
        assertEquals("3", f["Kontakt"])
        assertEquals(null, f["Status"])
        assertTrue(DbProps.fields(null, props).isEmpty())
        assertTrue(DbProps.fields(obj("""{"props":"kaputt"}"""), props).isEmpty())
        assertEquals(null, DbProps.calc(obj("""{"a":"x","b":"y","op":"div"}"""), obj("""{"x":1,"y":0}""")))
        assertEquals(3.0, DbProps.calc(obj("""{"a":"x","b":"y","op":"add"}"""), obj("""{"x":3}""")))
    }

    /** Seite mit der Block-Vielfalt eines Notion-Imports (Struktur wie in einer echten Installation, Inhalte erfunden) */
    private val notionPage = """{"time":1,"version":"2.31.0","blocks":[
      {"id":"h1","type":"header","data":{"text":"Titel","level":1}},
      {"id":"p1","type":"paragraph","data":{"text":"a &amp; <b>b</b> <a href=\"/notes#12\">x</a> &#128512; &#xD83D; &#0; <span style=\"color:red\">rot</span> <b><i>x</b></i>"}},
      {"id":"l1","type":"list","data":{"style":"checklist","meta":{},"items":[{"content":"a","meta":{"checked":true},"items":[{"content":"b","meta":{"checked":false},"items":[]}]}]}},
      {"id":"l2","type":"list","data":{"style":"ordered","meta":{"start":3,"counterType":"lower-roman"},"items":[{"content":"x","meta":{},"items":[]}]}},
      {"id":"l3","type":"list","data":{"style":"unordered","meta":{},"items":["alt",null,5,{"content":null}]}},
      {"id":"i1","type":"image","data":{"caption":"c","file":{"url":"/files/abc"},"stretched":false,"withBackground":false,"withBorder":true}},
      {"id":"c1","type":"code","data":{"code":"x < y","language":"python"}},
      {"id":"d1","type":"database","data":{"db":1234567890123,"view":null,"views":[{"id":"v","type":"board"}]}},
      {"id":"e1","type":"embed","data":{"kind":"youtube","title":"T","url":"https://youtu.be/x?a=1&amp;b=2"}},
      {"id":"b1","type":"buttons","data":{"buttons":[{"label":"Neu","icon":"➕","action":"db","target":5,"tpl":null}]}},
      {"id":"q1","type":"quote","data":{"alignment":"left","caption":"","text":"q"}},
      {"id":"co","type":"columns","data":{"cols":[{"blocks":[{"id":"t1","type":"toggle","data":{"blocks":[{"id":"p2","type":"paragraph","data":{"text":"in"}}],"open":false,"title":"T"}}]},{"blocks":[]},"kaputt"]}},
      {"id":"ca","type":"callout","data":{"emoji":"💡","text":"c"}},
      {"id":"cal","type":"calendar","data":{"days":7,"view":"week"}},
      {"id":"n1","type":"paragraph","data":null},
      {"id":"n2","type":"header","data":{"text":null,"level":"zwei"}},
      {"type":"paragraph","data":{"text":"ohne id"}},
      "kein Objekt", null, 42
    ]}"""

    @Test fun notionPageParses() {
        val p = BlockDoc.parse(notionPage, "blocks")
        val blocks = BlockDoc.blocks(p.doc)
        assertEquals(20, blocks.size)
        // alle Lesehilfen vertragen jeden Block (auch null, Zahl, Text, fehlende/kaputte data)
        blocks.forEach { b ->
            BlockDoc.type(b); BlockDoc.id(b); BlockDoc.data(b); BlockDoc.mainText(b)
            val d = BlockDoc.data(b)
            d.keys.forEach { k -> BlockDoc.str(d, k); BlockDoc.int(d, k); BlockDoc.bool(d, k) }
            (d["items"] as? JsonArray)?.forEach { it as? JsonObject }
        }
        assertEquals(null, BlockDoc.int(BlockDoc.data(blocks[15]), "level"))
        assertEquals("", BlockDoc.str(BlockDoc.data(blocks[14]), "text"))
        assertEquals(3, BlockDoc.int(BlockDoc.data(blocks[3])["meta"] as JsonObject, "start"))
        // Inline-HTML: Entitaeten, unbekannte Tags, falsch verschachtelt -> Text bleibt lesbar
        val rt = InlineHtml.parse(BlockDoc.str(BlockDoc.data(blocks[1]), "text"))
        assertTrue(rt.text.startsWith("a & b x 😀"))
        assertTrue(rt.text.contains("rot"))
        rt.spans.forEach { assertTrue(it.start >= 0 && it.end <= rt.text.length && it.start < it.end) }
        InlineHtml.toHtml(rt)
        // lange IDs (Datenbank-Block) bleiben exakt
        assertEquals("1234567890123", BlockDoc.str(BlockDoc.data(blocks[7]), "db").ifEmpty { BlockDoc.data(blocks[7])["db"].toString() })
    }

    @Test fun brokenBodyIsReportedNotSilentlyEmpty() {
        var thrown = false
        try { BlockDoc.parse("{\"blocks\": 5}", "blocks") } catch (_: BlockDoc.UnreadableException) { thrown = true }
        assertTrue(thrown)
        // altes Textformat und unbekanntes Format: als Absaetze
        assertEquals(2, BlockDoc.blocks(BlockDoc.parse("a\n\nb", "markdown").doc).size)
    }
}
