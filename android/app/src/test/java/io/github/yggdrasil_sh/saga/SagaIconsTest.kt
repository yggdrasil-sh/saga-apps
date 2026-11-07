package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** A11Y-16: Saga-Liniensymbole (si_*.xml aus tools/icons-android.py) und SagaIcons passen zusammen. */
class SagaIconsTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun drawableDir(): File =
        listOf(File("src/main/res/drawable"), File("app/src/main/res/drawable")).first { it.isDirectory }

    private fun siFiles(): List<File> =
        drawableDir().listFiles { f -> f.name.startsWith("si_") && f.name.endsWith(".xml") }!!.sortedBy { it.name }

    @Test
    fun jedesSymbolIstGueltigesXmlImSagaStil() {
        val files = siFiles()
        assertTrue("zu wenige si_*.xml: ${files.size}", files.size >= 100)
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for (f in files) {
            val doc = dbf.newDocumentBuilder().parse(f)
            val root = doc.documentElement
            assertEquals(f.name, "vector", root.tagName)
            assertEquals(f.name, "24", root.getAttributeNS(ns, "viewportWidth"))
            assertEquals(f.name, "24", root.getAttributeNS(ns, "viewportHeight"))
            assertEquals(f.name, "24dp", root.getAttributeNS(ns, "width"))
            val paths = doc.getElementsByTagName("path")
            assertTrue("${f.name}: kein <path>", paths.length > 0)
            for (i in 0 until paths.length) {
                val p = paths.item(i) as Element
                assertEquals(f.name, "1.75", p.getAttributeNS(ns, "strokeWidth"))
                assertEquals(f.name, "round", p.getAttributeNS(ns, "strokeLineCap"))
                assertEquals(f.name, "round", p.getAttributeNS(ns, "strokeLineJoin"))
                assertEquals(f.name, "@android:color/transparent", p.getAttributeNS(ns, "fillColor"))
                assertEquals(f.name, "@android:color/white", p.getAttributeNS(ns, "strokeColor"))
                val d = p.getAttributeNS(ns, "pathData")
                assertTrue("${f.name}: pathData beginnt nicht mit M", d.startsWith("M") || d.startsWith("m"))
                // nur SVG-Pfadzeichen; Bogen-Flags stehen getrennt (kein "0 01")
                assertTrue("${f.name}: unerwartete Zeichen in $d", d.all { it in "MmLlHhVvCcSsQqTtAaZz0123456789.- " })
            }
        }
    }

    @Test
    fun sagaIconsZeigenAufVorhandeneRessourcen() {
        val files = siFiles().map { it.name.removeSuffix(".xml") }.toSet()
        val byName = SagaIcons.byName
        assertEquals("SagaIcons.byName und si_*.xml weichen ab", files, byName.keys.map { "si_" + it.replace('-', '_') }.toSet())
        for ((name, id) in byName) {
            val field = R.drawable::class.java.getField("si_" + name.replace('-', '_'))
            assertEquals(name, field.getInt(null), id)
        }
        // jede @DrawableRes-Eigenschaft steht auch in byName
        val props = SagaIcons::class.java.declaredFields.filter { it.type == Int::class.javaPrimitiveType && it.name != "INSTANCE" }
        assertEquals(byName.size, props.size)
        assertNotNull(SagaIcons.res("chevron-right"))
        assertEquals(null, SagaIcons.res("gibt-es-nicht"))
    }

    @Test
    fun hauptpunkteDerSeitenleisteNutzenSagaIcons() {
        assertEquals(R.drawable.si_today, SagaIcons.today)
        assertEquals(R.drawable.si_tasks, SagaIcons.tasks)
        assertEquals(R.drawable.si_calendar, SagaIcons.calendar)
        assertEquals(R.drawable.si_chats, SagaIcons.chats)
    }
}
