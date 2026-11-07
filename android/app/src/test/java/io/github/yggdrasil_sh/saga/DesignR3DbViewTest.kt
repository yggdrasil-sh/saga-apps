package io.github.yggdrasil_sh.saga

import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.db.DbDate
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.ui.database.DbCompact
import io.github.yggdrasil_sh.saga.ui.database.colWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Android-Design-Runde 3 (Datenbank-Ansichten): kompakte Liste, Einrasten, Pillen, Kurzdatum, Kalender kompakt */
class DesignR3DbViewTest {
    private val src: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        .resolve("io/github/yggdrasil_sh/saga")
    private fun read(rel: String) = src.resolve(rel).readText()
    private val de = Locale.GERMANY
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 7)

    @Test fun ed12KompakteListeNurTabelleAufDemHandy() {
        assertTrue(DbCompact.compactList("table", wide = false, asTable = false))
        assertFalse(DbCompact.compactList("table", wide = false, asTable = true))   // Schalter "Als Tabelle"
        assertFalse(DbCompact.compactList("table", wide = true, asTable = false))   // Tablet: Tabelle
        assertTrue(DbCompact.compactList("zukunft", wide = false, asTable = false)) // unbekannte Art = Tabelle
        for (t in listOf("board", "list", "gallery", "calendar", "chart", "timeline")) {
            assertFalse(t, DbCompact.compactList(t, wide = false, asTable = false))
            assertFalse(t, DbCompact.offerTableToggle(t, wide = false))
        }
        assertTrue(DbCompact.offerTableToggle("table", wide = false))
        assertFalse(DbCompact.offerTableToggle("table", wide = true))
    }

    @Test fun ed12SchalterLokalJeAnsichtUndBlock() {
        assertEquals("db_astable_db_5_v1", DbCompact.asTableKey(5, null, "v1"))
        assertEquals("db_astable_db_view_9_b1_v1", DbCompact.asTableKey(5, "db_view_9_b1", "v1"))
        // nicht ueber den Server: DbViewState schreibt in die lokalen Einstellungen
        val st = read("ui/database/DbViewState.kt")
        assertTrue(st.contains("appContextPrefs()?.edit { putBoolean(DbCompact.asTableKey("))
        // Vollbild und eingebettet nutzen dieselbe Regel
        assertTrue(read("ui/database/DatabaseView.kt").contains("DbCompact.compactList(v.type, wide, st.asTable(v.id))"))
        assertTrue(read("ui/database/EmbeddedDatabase.kt").contains("DbCompact.compactList(v.type, wide, st.asTable(v.id))"))
    }

    @Test fun ed12EigenschaftenDerKompaktenZeile() {
        val status = DbProp("st", "Status", "select", status = true)
        val start = DbProp("start", "Start", "date")
        val who = DbProp("who", "Person", "person")
        val prog = DbProp("p", "Fortschritt", "number", fmt = "percent", show = "bar")
        val text = DbProp("t", "Notiz", "text")
        val empty = DbProp("e", "Leer", "date")
        val visible = listOf(text, prog, who, empty, start, status)
        val picked = DbCompact.compactProps(visible, { it.id != "e" })
        assertEquals(listOf("st", "start", "who"), picked.map { it.id })
        assertEquals(listOf("start", "p"), DbCompact.compactProps(listOf(prog, start, text), { true }).map { it.id })
    }

    @Test fun ed12ed19KurzesDatum() {
        assertEquals("02.11.", DbCompact.shortDay(LocalDate.of(2026, 11, 2), today, de))
        assertEquals("02.11.25", DbCompact.shortDay(LocalDate.of(2025, 11, 2), today, de))
        val range = DbDate.parse("2026-11-16/2026-12-18", zone)!!
        assertTrue(DbCompact.isRange(range, zone))
        assertEquals("16.11. → 18.12.2026", DbCompact.shortDate(range, zone, today, de))
        val cross = DbDate.parse("2026-12-30/2027-01-02", zone)!!
        assertEquals("30.12.26 → 02.01.2027", DbCompact.shortDate(cross, zone, today, de))
        val single = DbDate.parse("2026-11-02", zone)!!
        assertFalse(DbCompact.isRange(single, zone))
        assertEquals("02.11.", DbCompact.shortDate(single, zone, today, de))
        // andere Sprachen: Muster der Sprache ohne Jahr, kein Trenner am Ende
        assertEquals("11/2", DbCompact.shortDay(LocalDate.of(2026, 11, 2), today, Locale.US))
    }

    @Test fun ed14SchmalereSpaltenUndEinrasten() {
        assertEquals(96.dp, colWidth(DbProp("s", "Status", "select"), false))
        assertEquals(96.dp, colWidth(DbProp("d", "Start", "date"), false))
        val edges = DbCompact.columnEdges(listOf(100f, 100f, 100f), 250f)
        assertEquals(listOf(0f, 100f, 200f, 250f), edges)
        assertEquals(-30f, DbCompact.snapOffset(edges, 130f, 0f))   // ohne Schwung: naechste Kante
        assertEquals(70f, DbCompact.snapOffset(edges, 130f, 1f))    // nach rechts: naechste Kante rechts
        assertEquals(-30f, DbCompact.snapOffset(edges, 130f, -1f))  // nach links
        assertEquals(0f, DbCompact.snapOffset(edges, 200f, 1f))     // steht schon auf einer Kante
        val d = read("ui/database/DatabaseView.kt")
        assertTrue(d.contains("Box(Modifier.size(MORE_COLUMNS_SLOT)"))     // Pfeil bleibt in eigener Spalte
    }

    @Test fun ed16AktivePilleGanzImBild() {
        // Pille 300..380 bei Breite 320, Scroll 0 -> so weit scrollen, dass rechts noch der Verlauf (24) Platz hat
        assertEquals(84, DbCompact.pillScroll(300, 380, 320, 0, 500, 24))
        // schon sichtbar: bleibt
        assertEquals(0, DbCompact.pillScroll(40, 120, 320, 0, 500, 24))
        // links angeschnitten
        assertEquals(76, DbCompact.pillScroll(100, 180, 320, 150, 500, 24))
        // nie ueber das Ende hinaus
        assertEquals(50, DbCompact.pillScroll(300, 360, 320, 0, 50, 24))
    }

    @Test fun ed18ZweigeteilterKnopf() {
        val d = read("ui/database/DatabaseView.kt")
        val tb = d.substring(d.indexOf("internal fun DbToolbar("), d.indexOf("internal fun ViewPills("))
        assertTrue(tb.contains("Row(horizontalArrangement = Arrangement.spacedBy(2.dp)"))   // sichtbarer Spalt
        assertTrue(tb.contains("Modifier.size(48.dp).clip(trailShape)"))                   // Pfeil 48 x 48
        assertTrue(tb.contains("heightIn(min = 48.dp)"))
        assertTrue(tb.contains("onLongClick = if (split)"))                                // langer Druck bleibt
    }

    @Test fun ed20ed21BoardUndKalender() {
        assertEquals(363f, DbCompact.boardColumnWidth(411f))
        assertEquals(280f, DbCompact.boardColumnWidth(800f))
        assertEquals(LocalDate.of(2026, 11, 7), DbCompact.agendaDay(LocalDate.of(2026, 11, 1), today))
        assertEquals(LocalDate.of(2026, 12, 1), DbCompact.agendaDay(LocalDate.of(2026, 12, 1), today))
        assertEquals(3, DbCompact.dots(7))
        assertEquals(0, DbCompact.dots(0))
        val d = read("ui/database/DatabaseView.kt")
        assertTrue(d.contains("rememberSnapFlingBehavior(rowState"))
    }
}
