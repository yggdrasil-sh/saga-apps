package io.github.yggdrasil_sh.saga

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.6 (Bugs-DB 2271 K-D) auf dem Geraet: Trefferflaechen der Tageszeile >= 48 dp in „Heute“ samt ✕ -> Pille -> „Wieder an“
 * (Beispielserver aus [TodayLine2271ShotsTest]). Vektoren auf dem Geraet: [TodayLine2271VectorsTest].
 */
@RunWith(AndroidJUnit4::class)
class TodayLine2271DeviceTest : TodayLine2271ShotsTest() {
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun roots() = ins.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(ins.uiAutomation.rootInActiveWindow)
    private fun walk(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> walk(c, pred)?.let { return it } }
        return null
    }
    private fun find(timeoutMs: Long = 6000, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            for (r in roots()) walk(r, pred)?.let { return it }
            Thread.sleep(200)
        }
        return null
    }
    private fun byText(t: String) = find { it.text?.toString() == t || it.contentDescription?.toString() == t }
    /** Compose meldet Texte als Kindknoten: zum naechsten klickbaren Knoten hochgehen */
    private fun target(t: String): AccessibilityNodeInfo? {
        var n = byText(t)
        var i = 0
        while (n != null && !n.isClickable && i++ < 4) n = n.parent
        return n
    }
    private val minPx get() = 48f * ins.targetContext.resources.displayMetrics.density - 1f
    private fun assertTarget(label: String, n: AccessibilityNodeInfo?) {
        assertNotNull("$label fehlt", n)
        val r = Rect(); n!!.getBoundsInScreen(r)
        assertTrue("$label klickbar", n.isClickable)
        assertTrue("$label Hoehe ${r.height()} < 48 dp", r.height() >= minPx)
        assertTrue("$label Breite ${r.width()} < 48 dp", r.width() >= minPx)
    }

    @Test fun c_touchTargetsAndQuiet() {
        lineMode = "normal"
        shot("heute-ziele", dark = false) {
            // Teile, ✕ und ⋯ - jeweils >= 48 dp
            for (t in listOf("Elternabend 19:30", "„Pavillon abholen“ überfällig (+2)", "„Kuchen backen“ heute")) assertTarget(t, target(t))
            val x = target(ins.targetContext.getString(R.string.tl_not_now))
            assertTarget("✕", x)
            assertTarget("⋯", target(ins.targetContext.getString(R.string.tl_options)))
            // Vorlesetext der ganzen Zeile an der Ueberschrift
            assertNotNull("a11y", byText("Kurz gesagt: Elternabend 19:30, „Pavillon abholen“ überfällig, plus 2, „Kuchen backen“ heute"))
            // ✕ -> Pille „Ruhe bis morgen“ mit „Wieder an“ (>= 48 dp), danach wieder die Zeile
            x!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            assertNotNull("Pille", byText("Ruhe bis morgen"))
            val undo = target("Wieder an")
            assertTarget("Wieder an", undo)
            assertTrue("POST quiet", synchronized(calls) { calls.contains("POST api/ai/today/quiet") })
            undo!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            assertNotNull("Zeile wieder da", byText("Elternabend 19:30"))
            assertTrue("DELETE quiet", synchronized(calls) { calls.contains("DELETE api/ai/today/quiet") })
            // „Wartet auf dich“ als eigene Zeile (ohne Saga-Ort nicht antippbar)
            assertNotNull("wartet", byText("1 Auftrag wartet auf dich ›"))
        }
    }
}
