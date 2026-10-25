package io.github.yggdrasil_sh.saga

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.compose
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.widget.PageWidget
import io.github.yggdrasil_sh.saga.widget.TodayWidget
import io.github.yggdrasil_sh.saga.widget.WidgetDemo
import io.github.yggdrasil_sh.saga.widget.WidgetStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Feinschliff 25.10.: Bildschirmfotos der Widgets (Heute, Seite) hell und dunkel, angemeldet und "nicht angemeldet",
 * ohne Startbildschirm: GlanceAppWidget.compose() liefert die RemoteViews, die hier in eine Activity gelegt werden.
 * Neutrale Beispieldaten (WidgetDemo). Ablage: getExternalFilesDir()/shots/widgets-*.png.
 */
@RunWith(AndroidJUnit4::class)
class WidgetShotsTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val pageWidgetId = 990_123

    private fun render(dark: Boolean, signedOut: Boolean): List<Pair<String, RemoteViews>> = runBlocking {
        val store = WidgetStore(ctx)
        store.setPageFor(pageWidgetId, WidgetDemo.pages.first().id)
        WidgetDemo.fill(store)
        store.signedOut = signedOut
        val cfg = Configuration(ctx.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("de"))
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        val c = ctx.createConfigurationContext(cfg)
        listOf(
            "Heute 4×2" to TodayWidget().compose(c, AppWidgetId(990_001), size = DpSize(320.dp, 190.dp)),
            "Heute 2×2" to TodayWidget().compose(c, AppWidgetId(990_002), size = DpSize(160.dp, 170.dp)),
            "Seite 2×2" to PageWidget().compose(c, AppWidgetId(pageWidgetId), size = DpSize(170.dp, 170.dp)),
        )
    }

    private fun shot(name: String, dark: Boolean, signedOut: Boolean) {
        val views = render(dark, signedOut)
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val cfg = Configuration(a.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag("de"))
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                }
                val host = a.createConfigurationContext(cfg)
                val dp = a.resources.displayMetrics.density
                val col = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * dp).toInt(), (56 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
                    // Hintergrund wie ein Startbildschirm (neutraler Verlauf)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        if (dark) intArrayOf(0xFF1B2433.toInt(), 0xFF0B0F16.toInt()) else intArrayOf(0xFFB9D3E8.toInt(), 0xFFE9DCC3.toInt()),
                    )
                }
                val sizes = listOf(320 to 190, 160 to 170, 170 to 170)
                views.forEachIndexed { i, (label, rv) ->
                    col.addView(TextView(a).apply {
                        text = label; setTextColor(if (dark) 0xFFE4E2DD.toInt() else 0xFF1E1B16.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
                    })
                    val frame = FrameLayout(a)
                    frame.addView(rv.apply(host, frame))
                    col.addView(frame, LinearLayout.LayoutParams((sizes[i].first * dp).toInt(), (sizes[i].second * dp).toInt()))
                }
                a.setContentView(col)
            }
            Thread.sleep(1500)
            ins.uiAutomation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
            Thread.sleep(300)
            ins.waitForIdleSync()
            val b: Bitmap = ins.uiAutomation.takeScreenshot()
            File(dir, "widgets-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(File(dir, "widgets-$name.png").length() > 1000)
    }

    @Test
    fun widgets() {
        shot("hell", dark = false, signedOut = false)
        shot("dunkel", dark = true, signedOut = false)
        shot("abgemeldet-hell", dark = false, signedOut = true)
        shot("abgemeldet-dunkel", dark = true, signedOut = true)
        WidgetStore(ctx).signedOut = false
    }
}
