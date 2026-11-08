package io.github.yggdrasil_sh.saga

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.ui.chat.ChatUi
import io.github.yggdrasil_sh.saga.ui.theme.Dark
import io.github.yggdrasil_sh.saga.ui.theme.Light
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import io.github.yggdrasil_sh.saga.ui.theme.SagaShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android-Design-Runde 2c: Ecken wie im Web, Anhang-Menue mit Symbolen, sichtbare Balken-Spur, Pfeil "Weitere Spalten" */
class DesignR2cTest {
    private val src: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        .resolve("io/github/yggdrasil_sh/saga")

    private fun read(rel: String) = src.resolve(rel).readText()
    private fun ktFiles() = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun knopfEckenSindSechsDp() {
        // Web .btn und Felder: --r-sm = 6 px
        assertEquals(6.dp, SagaRadius.s)
        assertEquals(RoundedCornerShape(6.dp), SagaShapes.small)
        assertEquals(RoundedCornerShape(6.dp), SagaShapes.extraSmall)
        // Dialoge/Sheets: 16 dp
        assertEquals(RoundedCornerShape(16.dp), SagaShapes.extraLarge)
        val b = read("ui/theme/Buttons.kt")
        assertTrue(b.contains("val SagaButtonShape: Shape @Composable get() = MaterialTheme.shapes.small"))
        listOf("SagaButton", "SagaFilledTonalButton", "SagaElevatedButton", "SagaOutlinedButton", "SagaTextButton").forEach { n ->
            assertTrue(n, Regex("fun $n\\(\\s*onClick: \\(\\) -> Unit,[^)]*shape: Shape = SagaButtonShape,").containsMatchIn(b))
        }
    }

    @Test fun keineMaterialKnoepfeAusserhalbDesThemes() {
        // Material-Knoepfe sind standardmaessig Pillen; ausserhalb von ui/theme nur die Saga-Wrapper verwenden
        val direct = Regex("""(import androidx\.compose\.material3\.(Button|TextButton|OutlinedButton|FilledTonalButton|ElevatedButton)\b(?!\w))|androidx\.compose\.material3\.(Button|TextButton|OutlinedButton|FilledTonalButton|ElevatedButton)\(""")
        val bad = ktFiles().filter { !it.path.contains("/ui/theme/") && direct.containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("Material-Knoepfe direkt in: $bad", bad.isEmpty())
    }

    @Test fun seitenleisteUndSuchfelderOhnePille() {
        val side = read("ui/nav/Sidebar.kt")
        assertTrue(side.contains("shape = MaterialTheme.shapes.small,"))
        assertTrue(side.contains(".padding(horizontal = 12.dp).clip(MaterialTheme.shapes.small)"))
        assertFalse(read("ui/pages/SearchScreen.kt").contains("CircleShape"))
        assertTrue(read("ui/chat/ChatsScreen.kt").contains("shape = MaterialTheme.shapes.small,"))
        assertFalse(read("ui/graph/GraphScreen.kt").contains("shape = MaterialTheme.shapes.extraLarge"))
    }

    @Test fun anhangMenueHatUeberallSymbole() {
        // R5 (CH-11): Anhang-Menue ist ein Bottom Sheet mit Kacheln - jede Kachel hat ein Linien-Symbol
        val c = read("ui/chat/Conversation.kt")
        assertTrue(c.contains("AttachSheet("))
        assertFalse(c.contains("DropdownMenu(expanded = attach"))
        val ui = read("ui/chat/ChatR5Ui.kt")
        val icons = Regex("AttachLogic\\.Tile\\.[A-Z_]+ -> SagaIcons\\.").findAll(ui).count()
        assertEquals(io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.entries.size, icons)
    }

    @Test fun balkenSpurIstOutline() {
        assertTrue(read("ui/theme/Components.kt").contains("val SagaProgressTrack: Color @Composable get() = MaterialTheme.colorScheme.outline"))
        // alle Balken mit Fortschrittswert (Tabelle/Board, Widgets, Upload) nutzen die sichtbare Spur
        listOf("ui/database/DbCells.kt" to 2, "ui/page/PageWidgetsUi.kt" to 2, "ui/chat/ChatVideo.kt" to 2).forEach { (f, n) ->
            assertEquals(f, n, Regex("trackColor = io\\.github\\.yggdrasil_sh\\.saga\\.ui\\.theme\\.SagaProgressTrack").findAll(read(f)).count())
        }
    }

    @Test fun balkenSpurKontrastAufKarten() {
        // Board-Karten: hell surfaceContainerLowest, dunkel surfaceContainerHigh (cardColor); Tabelle/Widgets auf
        // surface bis surfaceContainer (dunkel bis High). Spur >= 3:1 gegen jede dieser Flaechen, hell und dunkel.
        // (hell erreicht surfaceContainerHigh nur 2,9:1, dort liegen aber keine Balken)
        val cards = { s: androidx.compose.material3.ColorScheme -> listOf(s.surface, s.background, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer) }
        for ((scheme, name, bgs) in listOf(Triple(Light, "hell", cards(Light)), Triple(Dark, "dunkel", cards(Dark) + Dark.surfaceContainerHigh))) {
            bgs.forEach { bg ->
                val c = ChatUi.contrast(scheme.outline, bg)
                assertTrue("Spur $name auf $bg: $c", c >= 3.0)
            }
        }
    }

    @Test fun pfeilWeitereSpaltenHatEigenenPlatz() {
        val d = read("ui/database/DatabaseView.kt")
        val head = d.substring(d.indexOf("internal fun TableHead("), d.indexOf("/** ED-17"))
        // Scrollbereich gewichtet, Pfeil in eigener Box danach, kein Ueberlagern mehr per align(CenterEnd)
        // R3/ED-14: zusaetzlich Linie an der fixierten Spalte und Einrasten - der Pfeil bleibt in seiner eigenen Spalte
        // R3-Nachbesserung: dazu Fensterbreite (Pfeilziel) und nur ganze Spalten im Ruhezustand
        assertTrue(head.contains("Row(Modifier.weight(1f, fill = false).onSizeChanged { viewportPx = it.width }.frozenEdge(st.hScroll).wholeColumns(st.hScroll, movingW).horizontalScroll(st.hScroll, flingBehavior = snap))"))
        assertTrue(head.contains("Box(Modifier.size(MORE_COLUMNS_SLOT)"))
        assertFalse(head.contains("Alignment.CenterEnd"))
        val row = d.substring(d.indexOf("internal fun TableRow("), d.indexOf("// ---------------------------------------------------------------- Liste"))
        assertTrue(row.contains("Row(Modifier.weight(1f, fill = false).frozenEdge(st.hScroll).wholeColumns(st.hScroll, movingW).horizontalScroll(st.hScroll, flingBehavior = snap)"))
        assertTrue(row.contains("if (moving.isNotEmpty()) Spacer(Modifier.width(MORE_COLUMNS_SLOT))"))
    }
}
