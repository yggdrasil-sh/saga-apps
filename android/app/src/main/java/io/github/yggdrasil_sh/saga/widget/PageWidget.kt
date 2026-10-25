package io.github.yggdrasil_sh.saga.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.widget.WidgetLook.sagaBackground

/**
 * Widget "Seite" (wie iOS PageCardWidget/PageWidget): gewaehlte Seite mit Symbol, Titel und Anfang des Inhalts.
 * Klein (1x1/2x1) als kompakte Seiten-Karte: nur Symbol bzw. Symbol + Titel. Antippen oeffnet die Seite.
 */
class PageWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            GlanceTheme(colors = WidgetLook.colors()) {
                val v by WidgetStore.version.collectAsState()
                PageContent(WidgetStore(LocalContext.current), widgetId, v)
            }
        }
    }

}

@Composable
private fun PageContent(store: WidgetStore, widgetId: Int, @Suppress("UNUSED_PARAMETER") version: Int) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val pageId = store.pageFor(widgetId)
    val snap = pageId?.let { store.page(it) }
    val ui = WidgetStates.page(store.signedOut, pageId, snap, pageId?.let { store.pageFailedAt(it) }, System.currentTimeMillis())
    val title = snap?.title?.ifBlank { null } ?: ctx.getString(R.string.widget_untitled)
    val emoji = PageExcerpt.emoji(snap?.icon) ?: if (snap?.isDatabase == true) "🗂" else "📄"
    val open = when {
        ui == WidgetUi.NotConfigured || ui == WidgetUi.Missing -> actionStartActivity(configIntent(ctx, widgetId))
        pageId != null && ui !is WidgetUi.SignedOut -> actionStartActivity(WidgetLinks.intent(ctx, WidgetLinks.page(pageId)))
        else -> actionStartActivity(WidgetLinks.intent(ctx, WidgetLinks.TODAY))
    }
    val tiny = size.width < 100.dp
    val strip = size.height < 100.dp
    val pad = if (tiny || strip) 8.dp else WidgetLook.pad
    val a11y = when (ui) {
        is WidgetUi.Content -> ctx.getString(R.string.widget_open_page, title)
        else -> message(ctx, ui)
    }

    Box(
        GlanceModifier.fillMaxSize().sagaBackground().padding(pad).clickable(open).semantics { contentDescription = a11y },
        contentAlignment = if (tiny) Alignment.Center else Alignment.TopStart,
    ) {
        when {
            ui !is WidgetUi.Content && ui != WidgetUi.Loading -> Column(verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.Start) {
                if (!tiny) Text(ctx.getString(R.string.widget_page_title), style = WidgetLook.text(WidgetLook.title, bold = true, color = WidgetLook.gold), maxLines = 1)
                Text(message(ctx, ui), style = WidgetLook.muted(if (tiny) WidgetLook.small else WidgetLook.body), maxLines = if (tiny) 3 else 4)
            }
            tiny -> Text(emoji, style = TextStyle(fontSize = 30.sp))
            strip -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, style = TextStyle(fontSize = 22.sp))
                Spacer(GlanceModifier.width(8.dp))
                Text(title, style = WidgetLook.text(WidgetLook.title, bold = true), maxLines = 2, modifier = GlanceModifier.defaultWeight())
            }
            else -> Column(GlanceModifier.fillMaxSize()) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, style = TextStyle(fontSize = 22.sp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text(title, style = WidgetLook.text(WidgetLook.title, bold = true), maxLines = 2, modifier = GlanceModifier.defaultWeight())
                }
                Spacer(GlanceModifier.height(6.dp))
                Box(GlanceModifier.width(28.dp).height(2.dp).cornerRadius(1.dp).background(WidgetLook.goldLine)) {}
                Spacer(GlanceModifier.height(6.dp))
                val c = ui as? WidgetUi.Content
                val body = when {
                    ui == WidgetUi.Loading -> ctx.getString(R.string.widget_loading)
                    snap?.isDatabase == true && c?.empty == true -> ctx.getString(R.string.widget_page_database)
                    c?.empty == true -> ctx.getString(R.string.widget_page_empty)
                    else -> snap?.excerpt.orEmpty()
                }
                val lines = ((size.height.value - 2 * pad.value - 50f - if (c?.staleSince != null) 16f else 0f) / 17f).toInt().coerceIn(1, 14)
                Text(body, style = if (c?.empty == false) WidgetLook.text() else WidgetLook.muted(WidgetLook.body), maxLines = lines, modifier = GlanceModifier.defaultWeight())
                c?.staleSince?.let {
                    Text(
                        ctx.getString(if (c.offline) R.string.widget_offline_since else R.string.widget_stale_since, WidgetLook.stamp(ctx, it)),
                        style = WidgetLook.muted(), maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun message(ctx: Context, ui: WidgetUi): String = when (ui) {
    WidgetUi.SignedOut -> ctx.getString(R.string.widget_signed_out)
    WidgetUi.NotConfigured -> ctx.getString(R.string.widget_pick_page)
    WidgetUi.Missing -> ctx.getString(R.string.widget_page_missing)
    WidgetUi.Loading -> ctx.getString(R.string.widget_loading)
    is WidgetUi.Content -> ""
}

/** Auswahl (neu) oeffnen - z. B. wenn die Seite geloescht wurde */
fun configIntent(ctx: Context, widgetId: Int): Intent =
    Intent(ctx, PageWidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        .setData(android.net.Uri.parse("saga-widget://config/$widgetId"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

class PageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PageWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdates.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetUpdates.schedule(context)
        WidgetUpdates.requestNow(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = WidgetStore(context)
        appWidgetIds.forEach { store.removeWidget(it) }
    }
}
