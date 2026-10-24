package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.call.CallConfig
import io.github.yggdrasil_sh.saga.call.CallRoom
import io.github.yggdrasil_sh.saga.call.SelfPipGeo
import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.db.DbSchema
import io.github.yggdrasil_sh.saga.db.DbTemplate
import io.github.yggdrasil_sh.saga.db.DbViews
import io.github.yggdrasil_sh.saga.ui.page.dbBlockRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bughunt 2.5 Android: A01, A03, A04, A05/A06, A07 */
class Bughunt25AndroidTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun o(s: String) = json.parseToJsonElement(s).jsonObject

    // ------------------------------------------------------------------ A07 Anrufe ueber Knoten

    private val serverAll = setOf("reneg", "multi", "horn", "group")

    @Test
    fun a07HornDmConfigParsed() {
        val c = json.decodeFromString(CallConfig.serializer(), """{"enabled":true,"horn_dm":true,"features":["reneg","multi","horn"]}""")
        assertTrue(c.hornDm)
        assertFalse(json.decodeFromString(CallConfig.serializer(), """{"enabled":true}""").hornDm)   // aelterer Server
    }

    @Test
    fun a07DmViaHornOnlyWithHornAndMulti() {
        val caps = CallRoom.myCaps(serverAll)
        assertTrue(CallRoom.dmViaHorn(true, caps))
        assertFalse(CallRoom.dmViaHorn(false, caps))                                   // Einstellung aus: direkt
        assertFalse(CallRoom.dmViaHorn(true, CallRoom.myCaps(setOf("reneg", "multi"))))   // Server ohne horn
        assertFalse(CallRoom.dmViaHorn(true, CallRoom.myCaps(setOf("reneg", "horn"))))    // ohne multi kein horn
        assertFalse(CallRoom.dmViaHorn(true, listOf("horn")))
    }

    @Test
    fun a07FallbackOnly409Direct() {
        assertTrue(CallRoom.dmHornFallback(409, o("""{"fallback":"direct","horn_block":"node","error":"x"}""")))
        assertFalse(CallRoom.dmHornFallback(409, o("""{"busy":true}""")))
        assertFalse(CallRoom.dmHornFallback(409, o("""{"incoming":true}""")))
        assertFalse(CallRoom.dmHornFallback(400, o("""{"fallback":"direct"}""")))
        assertFalse(CallRoom.dmHornFallback(409, null))
    }

    // ------------------------------------------------------------------ A01 Filter der Standardansicht

    private val schema = DbSchema(o("""
        {"props":[{"id":"status","name":"Status","type":"select","options":[{"id":"a","name":"Open"},{"id":"b","name":"In progress"}]}],
         "views":[
           {"id":"tabelle","name":"Table","type":"table","filters":[{"pid":"status","op":"is","value":"b"}]},
           {"id":"plain","name":"All","type":"table"},
           {"id":"sorted","name":"Sorted","type":"list","sorts":[{"pid":"status","dir":"desc"}]},
           {"id":"grouped","name":"Grouped","type":"table","group":"status"},
           {"id":"board","name":"Board","type":"board","group_by":"status"},
           {"id":"gal","name":"Gallery","type":"gallery"}
         ]}
    """.trimIndent()))

    @Test
    fun a01FirstPageRequeriedWhenViewHasFilter() {
        assertTrue(DbViews.needsViewQuery(schema.view(null), schema))          // Standardansicht (erste) mit Filter
        assertTrue(DbViews.needsViewQuery(schema.view("tabelle"), schema))
        assertTrue(DbViews.needsViewQuery(schema.view("sorted"), schema))
        assertTrue(DbViews.needsViewQuery(schema.view("grouped"), schema))
        assertFalse(DbViews.needsViewQuery(schema.view("plain"), schema))      // ohne Filter: erste Antwort passt
        assertFalse(DbViews.needsViewQuery(schema.view("gal"), schema))
    }

    // ------------------------------------------------------------------ A04 Standardvorlage fuer "Neu"

    private val tpls = listOf(DbTemplate(11, "Bug", null), DbTemplate(12, "Idea", "💡"))

    @Test
    fun a04DefaultTemplateResolved() {
        assertEquals(12L, DbViews.defaultTemplate(DbSchema(o("""{"default_tpl":12}""")), tpls)?.id)
        assertEquals(11L, DbViews.defaultTemplate(DbSchema(o("""{"default_tpl":11.0}""")), tpls)?.id)
        assertNull(DbViews.defaultTemplate(DbSchema(o("""{"default_tpl":99}""")), tpls))   // Vorlage geloescht
        assertNull(DbViews.defaultTemplate(DbSchema(o("""{"default_tpl":null}""")), tpls))
        assertNull(DbViews.defaultTemplate(null, tpls))
    }

    @Test
    fun a04NewUsesDefaultTemplate() {
        assertEquals(DbViews.NewAction.BLANK, DbViews.newAction(emptyList(), null))
        assertEquals(DbViews.NewAction.MENU, DbViews.newAction(tpls, null))
        assertEquals(DbViews.NewAction.DEFAULT_TEMPLATE, DbViews.newAction(tpls, tpls[1]))
    }

    // ------------------------------------------------------------------ A03 eingebetteter Block ohne Vorlagen

    @Test
    fun a03BlockRowsWithoutTemplates() {
        val all = listOf(
            PageMeta(1, "Entry", parentId = 7), PageMeta(2, "Template", parentId = 7, template = true),
            PageMeta(3, "Other db", parentId = 8), PageMeta(4, "Entry 2", parentId = 7),
        )
        assertEquals(listOf(1L, 4L), dbBlockRows(all, 7).map { it.id })
    }

    // ------------------------------------------------------------------ A05/A06 eigenes Bild ueber den Knoepfen

    @Test
    fun a06PortraitUnchanged() {
        val g = SelfPipGeo.place(400f, 800f, 108f, 156f, 80f, 190f, 16f, corner = 1, hidden = false)!!
        assertEquals(108f, g.w, 0.01f); assertEquals(156f, g.h, 0.01f)
        assertEquals(80f, g.y, 0.01f); assertEquals(400f - 108f - 16f, g.x, 0.01f)
        val b = SelfPipGeo.place(400f, 800f, 108f, 156f, 80f, 190f, 16f, corner = 2, hidden = false)!!
        assertEquals(800f - 190f - 156f, b.y, 0.01f); assertEquals(16f, b.x, 0.01f)
    }

    @Test
    fun a06LandscapeNeverOverControls() {
        // Querformat, wenig Hoehe: alle Ecken bleiben oberhalb der Knopfzone, Bild wird kleiner, Seitenverhaeltnis bleibt
        for (h in listOf(300f, 260f, 240f)) for (corner in 0..3) for (hidden in listOf(false, true)) {
            val g = SelfPipGeo.place(800f, h, 108f, 156f, 80f, 150f, 16f, corner, hidden) ?: continue
            assertTrue("h=$h c=$corner", g.y >= 0f)
            assertTrue("h=$h c=$corner bottom ${g.y + g.h}", g.y + g.h <= h - 150f + 0.01f)
            if (!hidden) assertEquals(108f / 156f, g.w / g.h, 0.01f)
        }
        val g = SelfPipGeo.place(800f, 300f, 108f, 156f, 80f, 150f, 16f, 1, false)!!
        assertTrue(g.h <= 300f * SelfPipGeo.LAND_SHARE + 0.01f)
    }

    @Test
    fun a05NoRoomNoPip() {
        assertNull(SelfPipGeo.place(800f, 180f, 110f, 160f, 48f, 150f, 8f, 1, false))     // Knopfzone fuellt fast alles
        assertNotNull(SelfPipGeo.place(800f, 200f, 110f, 160f, 48f, 150f, 8f, 1, false))  // 50 dp frei: klein
    }

    @Test
    fun a05ZeroSizeNoNaN() {
        assertNull(SelfPipGeo.place(400f, 800f, 0f, 0f, 48f, 150f, 8f, 3, false))         // keine Division durch 0
        val g = SelfPipGeo.place(400f, 800f, 108f, 156f, 48f, 150f, 8f, 3, true)!!       // ausgeblendet: Knopf 44
        assertTrue(g.w == SelfPipGeo.MIN && g.y + g.h <= 800f - 150f + 0.01f)
    }
}
