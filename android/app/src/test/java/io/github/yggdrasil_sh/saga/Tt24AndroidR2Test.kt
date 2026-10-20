package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.BlockOps
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.call.CallLogic
import io.github.yggdrasil_sh.saga.call.CallPermissions
import io.github.yggdrasil_sh.saga.chat.ChatAttach
import io.github.yggdrasil_sh.saga.chat.video.VideoLogic
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.db.DbParams
import io.github.yggdrasil_sh.saga.db.DbSchema
import io.github.yggdrasil_sh.saga.ui.common.NotifPrompt
import io.github.yggdrasil_sh.saga.ui.nav.SnackPolicy
import io.github.yggdrasil_sh.saga.ui.page.FieldState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.URLDecoder
import java.time.LocalDateTime

/** Funde der zweiten Android-Testrunde 2.4 (N-01 ... N-16, P-21/P-22) */
class Tt24AndroidR2Test {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    // ---------------------------------------------------------------- N-01 Tabelle nach "+ Spalte"

    /**
     * Zellfelder wie im Editor: je Feld ein FieldState, gemerkt wie remember(fieldKey, pre, generation). Die Zelle
     * zeigt, was ihr Feldzustand nach dem Komponieren mit dem gespeicherten Inhalt haelt.
     */
    private class Cells(val withShape: Boolean) {
        val states = HashMap<String, FieldState>()

        private fun state(block: JsonObject, r: Int, c: Int, html: String): FieldState {
            val key = "b:tb/t$r.$c" + if (withShape) "@" + BlockOps.tableShape(block) else ""
            val st = states.getOrPut(key) { FieldState(html, false, "") }
            if (st.isExternalChange(html)) {                            // wie RichField beim Komponieren
                st.accept(html)
                st.rich = InlineHtml.parse(html)
            }
            return st
        }

        fun shown(block: JsonObject): List<List<String>> = BlockOps.cells(block).mapIndexed { r, row ->
            (0 until BlockOps.columnCount(block)).map { c -> state(block, r, c, row.getOrNull(c) ?: "").rich.text }
        }

        /** Tippen: das Feld sendet html (eigenes Echo) */
        fun type(block: JsonObject, r: Int, c: Int, html: String): JsonObject {
            val st = state(block, r, c, BlockOps.cells(block)[r].getOrNull(c) ?: "")
            st.rich = InlineHtml.parse(html)
            st.sent(html)
            return BlockOps.setCell(block, r, c, html)
        }
    }

    private val empty = o("""{"id":"tb","type":"table","data":{"withHeadings":true,"content":[["Kopf1",""],["Wert1",""]]}}""")

    @Test
    fun n01CauseFieldKeptItsOwnEchoAfterInsertColumn() {
        // So war es: Feld nur nach Position gemerkt. "BBB" in Spalte 2 getippt, Cursor in Spalte 1, "+ Spalte"
        // fuegt hinter Spalte 1 ein -> gespeichert steht "BBB" in Spalte 3, die Anzeige zeigt es in Spalte 2 UND 3.
        val old = Cells(withShape = false)
        old.shown(empty)
        val typed = listOf("B", "BB", "BBB").fold(empty) { b, h -> old.type(b, 0, 1, h) }
        val inserted = BlockOps.insertColumn(typed, 1)
        assertEquals(listOf("Kopf1", "", "BBB"), BlockOps.cells(inserted)[0])                 // gespeichert
        assertEquals(listOf("Kopf1", "BBB", "BBB"), old.shown(inserted)[0])                   // angezeigt: falsch
        // Ursache: "" ist der zuletzt von aussen uebernommene Wert des Feldes -> nicht als Aenderung erkannt
        assertFalse(old.states.getValue("b:tb/t0.1").isExternalChange(""))
    }

    @Test
    fun n01FixedFieldsFollowTheTableShape() {
        val now = Cells(withShape = true)
        now.shown(empty)
        val typed = listOf("B", "BB", "BBB").fold(empty) { b, h -> now.type(b, 0, 1, h) }
        assertEquals(listOf("Kopf1", "BBB"), now.shown(typed)[0])                             // eigenes Echo bleibt
        val inserted = BlockOps.insertColumn(typed, 1)
        assertEquals(BlockOps.cells(inserted), now.shown(inserted))                          // Anzeige = gespeichert
        // Weiterschreiben in der angezeigten "BBB"-Zelle (Spalte 3) aendert genau diese Zelle
        val more = now.type(inserted, 0, 2, "BBBX")
        assertEquals(listOf("Kopf1", "", "BBBX"), BlockOps.cells(more)[0])
        assertEquals(BlockOps.cells(more), now.shown(more))
        // auch Zeile einfuegen und Spalte entfernen
        val row = BlockOps.insertRow(more, 0)
        assertEquals(BlockOps.cells(row), now.shown(row))
        val removed = BlockOps.removeColumn(row, 1)
        assertEquals(BlockOps.cells(removed), now.shown(removed))
    }

    @Test
    fun n01ShapeAndCursor() {
        assertEquals("2x2", BlockOps.tableShape(empty))
        assertEquals("2x3", BlockOps.tableShape(BlockOps.insertColumn(empty, 1)))
        assertEquals("3x2", BlockOps.tableShape(BlockOps.insertRow(empty, 0)))
        assertEquals(0 to 0, BlockOps.cellAfterInsert(0, 0, col = 1))                // "+ Spalte" rechts: Cursor bleibt
        assertEquals(0 to 1, BlockOps.cellAfterInsert(0, 0, col = 0))                // "Spalte links": Zelle rueckt
        assertEquals(2 to 1, BlockOps.cellAfterInsert(1, 1, row = 1))                // "Zeile oberhalb"
        assertEquals(1 to 1, BlockOps.cellAfterInsert(1, 1, row = 2))
    }

    // ---------------------------------------------------------------- N-02 Anruf annehmen

    @Test
    fun n02NoNotificationPromptWhileAnswering() {
        val answer = CallPermissions.pick(mic = false, cam = false, notif = false, video = false, notifications = false)
        assertEquals(listOf(android.Manifest.permission.RECORD_AUDIO), answer)
        val video = CallPermissions.pick(mic = true, cam = false, notif = false, video = true, notifications = false)
        assertEquals(listOf(android.Manifest.permission.CAMERA), video)
        // sonst (z. B. beim Anrufen) weiter mit Mitteilungen
        assertTrue(android.Manifest.permission.POST_NOTIFICATIONS in CallPermissions.pick(true, true, false, false, true))
        assertTrue(CallPermissions.pick(true, true, true, true, false).isEmpty())
        // Erklaer-Dialog "Mitteilungen erlauben?" nie waehrend eines Anrufs
        assertFalse(NotifPrompt.showNow(wanted = true, inCall = true))
        assertTrue(NotifPrompt.showNow(wanted = true, inCall = false))
        assertFalse(NotifPrompt.showNow(wanted = false, inCall = false))
        assertTrue(CallLogic.RING_HOLD_MS in 30_000L..120_000L)
    }

    // ---------------------------------------------------------------- N-03 gespeicherte Ansicht "Offen"

    @Test
    fun n03SavedViewFiltersAreReadAndSent() {
        // Aufbau wie tools/perf/gen.py (Ansicht "Offen" mit Filter + Sortierung)
        val schema = DbSchema(o("""{"props":[{"id":"status","name":"Status","type":"select","options":[{"id":"todo","name":"Offen"},{"id":"done","name":"Erledigt"}]},
            {"id":"datum","name":"Datum","type":"date"}],
            "views":[{"id":"tabelle","name":"Tabelle","type":"table"},
                     {"id":"offen","name":"Offen","type":"table","filters":[{"pid":"status","op":"is_not","value":"done"}],"sorts":[{"pid":"datum","dir":"asc"}]}]}"""))
        val v = schema.view("offen")
        assertEquals(1, v.filters.size)
        assertEquals("is_not", v.filters.single().op)
        val q = URLDecoder.decode(DbParams.query(view = v.id, filters = v.filters, sorts = v.sorts), "UTF-8")
        assertTrue(q, q.contains("view=offen"))
        assertTrue(q, q.contains("\"op\":\"is_not\"") && q.contains("\"value\":\"done\""))
        assertTrue(q, q.contains("\"dir\":\"asc\""))
        // Ansicht ohne gespeicherte Filter (Demo-Daten des Teststands): nichts zu filtern - "6 von 6" ist dann richtig
        assertTrue(DbSchema(o("""{"props":[],"views":[{"id":"offen","name":"Offen","type":"table"}]}""")).view("offen").filters.isEmpty())
    }

    // ---------------------------------------------------------------- P-22 Videoname, N-13

    @Test
    fun p22MeaningfulAttachmentNames() {
        val at = LocalDateTime.of(2026, 10, 20, 11, 30)
        assertEquals("VID_20261020_1130_Nora-Vogel.mp4", ChatAttach.niceName("1000000020.mp4", "video/mp4", "Nora Vogel", at))
        assertEquals("IMG_20261020_1130_Team.jpg", ChatAttach.niceName("1000000021", "image/jpeg", "Team", at))
        assertEquals("VID_20261020_1130.mov", ChatAttach.niceName("datei", "video/quicktime", "", at))
        assertEquals("VID_20261020_1130_Мия.mp4", ChatAttach.niceName("42.mp4", "video/mp4", "Мия", at))
        assertEquals("tt2-video.mp4", ChatAttach.niceName("tt2-video.mp4", "video/mp4", "Nora", at))   // echter Name bleibt
        assertEquals("2026 Urlaub.mp4", ChatAttach.niceName("2026 Urlaub.mp4", "video/mp4", "Nora", at))
        assertTrue(ChatAttach.meaningless("1000000020.mp4"))
        assertFalse(ChatAttach.meaningless("a1.mp4"))
    }

    @Test
    fun n13OptimizedNeverLarger() {
        assertFalse(VideoLogic.keepOptimized(2_600_000, 2_000_000))
        assertTrue(VideoLogic.keepOptimized(1_000_000, 2_000_000))
        assertTrue(VideoLogic.keepOptimized(1_000_000, null))
        assertFalse(VideoLogic.keepOptimized(0, null))
    }

    // ---------------------------------------------------------------- N-04, N-09, N-12

    @Test
    fun n04LoginTabIsClearedFromTheTask() {
        val f = BrowserTabFlags.CLOSE_TAB
        assertTrue(f and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(f and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(0, f and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun n09UnreachableNotDoubled() {
        assertTrue(SagaRepository.probeSelfExplaining(ConnectException("refused")))
        assertFalse(SagaRepository.probeSelfExplaining(java.io.IOException("x")))
    }

    @Test
    fun n12UndoSnackbarIsNotReplacedByPlainMessage() {
        assertTrue(SnackPolicy.keepCurrent(currentActive = true, currentHasAction = true, nextHasAction = false))
        assertFalse(SnackPolicy.keepCurrent(currentActive = true, currentHasAction = true, nextHasAction = true))
        assertFalse(SnackPolicy.keepCurrent(currentActive = true, currentHasAction = false, nextHasAction = false))
        assertFalse(SnackPolicy.keepCurrent(currentActive = false, currentHasAction = true, nextHasAction = false))
    }
}
