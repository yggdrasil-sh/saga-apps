package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.PageMeta
import io.github.yggdrasil_sh.saga.ui.pages.HitKind
import io.github.yggdrasil_sh.saga.ui.pages.SearchHits
import io.github.yggdrasil_sh.saga.ui.today.TodayLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android-Design-Runde 4 "Heute/Suchen": Vorname, Weitermachen (NAV-13), Suchtreffer mit Kontext (NAV-14), Texte (NAV-15/16/24) */
class DesignR4TodayTest {
    private fun page(id: Long, title: String, parent: Long? = null, kind: String = "page", template: Boolean = false, archived: Boolean = false, snippet: String? = null) =
        PageMeta(id = id, title = title, parentId = parent, kind = kind, template = template, archived = archived, snippet = snippet)

    // ---------------------------------------------------------------- NAV-13 Gruss
    @Test fun firstNameTakesFirstWord() {
        assertEquals("Anna", TodayLogic.firstName("Anna Beispiel"))
        assertEquals("Anna", TodayLogic.firstName("  Anna   Maria Beispiel "))
        assertEquals("Bruno", TodayLogic.firstName("Bruno"))
    }

    @Test fun firstNameFromMailAndEmpty() {
        assertEquals("anna.b", TodayLogic.firstName("anna.b@example.org"))
        assertNull(TodayLogic.firstName(""))
        assertNull(TodayLogic.firstName("   "))
        assertNull(TodayLogic.firstName(null))
        assertNull(TodayLogic.firstName("@example.org"))
    }

    // ---------------------------------------------------------------- NAV-13 Weitermachen
    @Test fun continueKeepsRecentOrderAndSkipsMissing() {
        val pages = listOf(page(1, "Eins"), page(2, "Zwei"), page(3, "Drei"))
        val r = TodayLogic.continuePages(listOf(3, 99, 1, 2), pages)
        assertEquals(listOf(3L, 1L, 2L), r.map { it.id })
    }

    @Test fun continueSkipsTemplatesArchivedDuplicatesAndLimits() {
        val pages = (1L..10L).map { page(it, "P$it", template = it == 2L, archived = it == 3L) }
        val r = TodayLogic.continuePages(listOf(1, 1, 2, 3, 4, 5, 6, 7, 8), pages)
        assertEquals(listOf(1L, 4L, 5L, 6L, 7L), r.map { it.id })
        assertEquals(TodayLogic.CONTINUE_MAX, r.size)
        assertTrue(TodayLogic.continuePages(emptyList(), pages).isEmpty())
    }

    @Test fun continueUsesSharedPagesButPrefersOwn() {
        val own = listOf(page(1, "Eigen"))
        val shared = listOf(page(1, "Alt"), page(7, "Geteilt"))
        val r = TodayLogic.continuePages(listOf(7, 1), own, shared)
        assertEquals(listOf("Geteilt", "Eigen"), r.map { it.title })
    }

    @Test fun quickAddIndexCountsItemsBeforeTheField() {
        // Kopf, Termine-Kopf, eine Leer-Zeile, Aufgaben-Kopf -> Feld an 4
        assertEquals(4, TodayLogic.quickAddIndex(hasContinue = false, events = 0))
        assertEquals(5, TodayLogic.quickAddIndex(hasContinue = true, events = 0))
        assertEquals(7, TodayLogic.quickAddIndex(hasContinue = true, events = 2))
    }

    // ---------------------------------------------------------------- NAV-14 Suchtreffer
    private val tree = listOf(
        page(10, "Aufgaben & Projekte"),
        page(11, "Projekte", parent = 10, kind = "database"),
        page(12, "Website überarbeiten", parent = 11),
        page(13, "", parent = 10),
        page(14, "Tief", parent = 13),
        page(15, "Tiefer", parent = 14),
        page(16, "Ganz tief", parent = 15),
    )
    private val byId = SearchHits.index(tree)

    @Test fun kindDistinguishesPageDatabaseRowTemplate() {
        assertEquals(HitKind.PAGE, SearchHits.kind(tree[0], byId))
        assertEquals(HitKind.DATABASE, SearchHits.kind(tree[1], byId))
        assertEquals(HitKind.ROW, SearchHits.kind(tree[2], byId))
        assertEquals(HitKind.TEMPLATE, SearchHits.kind(page(20, "Neues Projekt", template = true), byId))
    }

    @Test fun pathShowsParentsTopDown() {
        assertNull(SearchHits.path(tree[0], byId, "Ohne Titel"))
        assertEquals("Aufgaben & Projekte", SearchHits.path(tree[1], byId, "Ohne Titel"))
        assertEquals("Aufgaben & Projekte › Projekte", SearchHits.path(tree[2], byId, "Ohne Titel"))
        assertEquals("Aufgaben & Projekte › Ohne Titel", SearchHits.path(tree[4], byId, "Ohne Titel"))
        // tiefer als PATH_DEPTH: vorne gekuerzt
        assertEquals("… › Ohne Titel › Tief › Tiefer", SearchHits.path(tree[6], byId, "Ohne Titel"))
    }

    @Test fun pathUnknownParentAndCycle() {
        assertNull(SearchHits.path(page(30, "X", parent = 999), byId, "?"))
        val cyc = SearchHits.index(listOf(page(1, "A", parent = 2), page(2, "B", parent = 1)))
        assertEquals("A › B", SearchHits.path(page(3, "C", parent = 2), cyc, "?"))
    }

    @Test fun splitSeparatesTemplates() {
        val (hits, tpl) = SearchHits.split(listOf(page(1, "A"), page(2, "Neues Projekt", template = true), page(3, "B")))
        assertEquals(listOf(1L, 3L), hits.map { it.id })
        assertEquals(listOf(2L), tpl.map { it.id })
    }

    @Test fun snippetDropsLeadingEllipsisAndCutWord() {
        assertEquals(
            "Ladezeiten. Aufgaben in diesem Projekt",
            SearchHits.cleanSnippet("…hnellere Ladezeiten. Aufgaben in diesem Projekt", "projekt"),
        )
        // angeschnittenes Wort mit Suchbegriff bleibt
        assertEquals("rojektplan steht", SearchHits.cleanSnippet("…rojektplan steht", "rojekt"))
        assertEquals("Anfang ohne Kuerzung", SearchHits.cleanSnippet("Anfang ohne Kuerzung", "anf"))
        // hinten gekuerzt: "…" bleibt
        assertEquals("ein Text mit Ende …", SearchHits.cleanSnippet("…xyz ein Text mit Ende …", "text"))
    }

    @Test fun snippetRemovesEmojiFromDatabaseCells() {
        assertEquals(
            "fällig Erledigt Neue Aufgabe Neues Projekt Aufgaben Projekte",
            SearchHits.cleanSnippet("… fällig Erledigt ➕ Neue Aufgabe 📁 Neues Projekt\nAufgaben Projekte", "projekt"),
        )
        assertEquals("Erledigt", SearchHits.cleanSnippet("✅️ Erledigt ‍", "erl"))
        assertEquals("Familie", SearchHits.cleanSnippet("👨‍👩‍👧 Familie", "fam"))
        assertNull(SearchHits.cleanSnippet("📁 ➕ ✅", "x"))
        assertNull(SearchHits.cleanSnippet("   ", "x"))
        assertNull(SearchHits.cleanSnippet(null, "x"))
    }

    // ---------------------------------------------------------------- Texte: alle Sprachen, gleiche Schluessel und Platzhalter
    @Test fun stringsCompleteInAllLanguages() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val re = Regex("<string name=\"([^\"]+)\">([^<]*)</string>")
        fun load(dir: String) = re.findAll(File(res, "$dir/strings_r4today.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        val base = load("values")
        assertTrue(base.isNotEmpty())
        for (l in listOf("values-de", "values-es", "values-ru", "values-th")) {
            val m = load(l)
            assertEquals(l, base.keys, m.keys)
            for ((k, v) in base) {
                val ph = Regex("%\\d\\$[sd]")
                assertEquals("$l/$k", ph.findAll(v).map { it.value }.toList(), ph.findAll(m.getValue(k)).map { it.value }.toList())
            }
        }
        val de = load("values-de")
        assertEquals("Suchen", de["r4t_search_title"])
        assertEquals("Suchen", de["r4t_search_placeholder"])
        assertEquals("Dienste &amp; Links", de["r4t_services_title"])
        assertEquals("Link hinzufügen", de["r4t_link_add"])
    }
}
