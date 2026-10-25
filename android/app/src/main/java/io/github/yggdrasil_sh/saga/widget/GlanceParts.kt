package io.github.yggdrasil_sh.saga.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNight
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.theme.Dark
import io.github.yggdrasil_sh.saga.ui.theme.Light
import java.text.DateFormat

/** Gestaltung der Widgets: Material You (dynamische Farben ab Android 12), sonst Saga hell/dunkel; Gold als Akzent */
object WidgetLook {
    /** Saga-Farben fuer Android 8-11 (keine dynamischen Farben) */
    private val saga = ColorProviders(light = Light, dark = Dark)

    @Composable
    fun colors() = if (Build.VERSION.SDK_INT >= 31) GlanceTheme.colors else saga

    /** Gold als Text/Symbol (Kontrast >= 4.5:1 auf hell bzw. dunkel, wie LightAccent/DarkAccent der App) */
    val gold: ColorProvider = DayNight(day = Color(0xFF7A5A12), night = Color(0xFFE2C07E))
    /** Gold als Zierlinie */
    val goldLine: ColorProvider = DayNight(day = Color(0xFFC9A45C), night = Color(0xFFC9A45C))

    val title = 15.sp
    val body = 13.sp
    val small = 11.sp

    @Composable
    fun text(size: androidx.compose.ui.unit.TextUnit = body, bold: Boolean = false, color: ColorProvider = GlanceTheme.colors.onSurface) =
        TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)

    @Composable
    fun muted(size: androidx.compose.ui.unit.TextUnit = small) = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = size)

    /** Hintergrund mit runden Ecken: ab Android 12 Systemradius, davor eine runde Form (drawable/widget_bg, hell/dunkel) */
    @Composable
    fun GlanceModifier.sagaBackground(): GlanceModifier =
        if (Build.VERSION.SDK_INT >= 31) {
            appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(android.R.dimen.system_app_widget_background_radius)
        } else {
            appWidgetBackground().background(ImageProvider(R.drawable.widget_bg))
        }

    /** Farbe aus "#RRGGBB" (Kalenderfarbe); sonst null */
    fun hex(s: String?): Color? {
        val h = s?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6 && h.length != 8) return null
        val v = h.toLongOrNull(16) ?: return null
        return if (h.length == 6) Color(0xFF000000 or v) else Color(v)
    }

    /** "Stand 14:30" bzw. mit Datum, wenn nicht von heute */
    fun stamp(context: Context, ms: Long): String =
        DateUtils.formatSameDayTime(ms, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT).toString()

    val pad = 12.dp
}

/** Antippen: Sprung in die App ueber eine NICHT exportierte Activity (fremde Apps koennen so nichts oeffnen) */
object WidgetLinks {
    const val TODAY = "today"
    const val TASKS = "tasks"
    const val EVENTS = "events"
    const val NEW_TASK = "new-task"
    fun page(id: Long) = "page:$id"

    fun intent(context: Context, dest: String): Intent =
        Intent(context, WidgetLinkActivity::class.java)
            .setData(Uri.parse("saga-widget://open/" + Uri.encode(dest)))      // eindeutig je Ziel (PendingIntent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun dest(intent: Intent?): String? = intent?.data?.takeIf { it.scheme == "saga-widget" && it.host == "open" }?.lastPathSegment
}
