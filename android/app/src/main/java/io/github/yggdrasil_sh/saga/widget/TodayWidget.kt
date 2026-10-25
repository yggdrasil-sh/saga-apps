package io.github.yggdrasil_sh.saga.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.widget.WidgetLook.sagaBackground
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Widget "Heute" (wie iOS TodayWidget): Termine von heute und Aufgaben, die heute faellig, ueberfaellig oder ohne Datum sind.
 * Kreis antippen = abhaken (ohne die App zu oeffnen), Zeile antippen = Termine/Aufgaben in der App, "+" = neue Aufgabe.
 */
class TodayWidget : GlanceAppWidget() {
    // Exact: echte Groesse des Widgets (Zeilenzahl passt sich jeder Hoehe an; nur wenige Widgets -> wenig Aufwand)
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme(colors = WidgetLook.colors()) {
                val v by WidgetStore.version.collectAsState()       // jede Aenderung im Speicher zeichnet neu
                TodayContent(WidgetStore(LocalContext.current), v)
            }
        }
    }

}

@Composable
private fun TodayContent(store: WidgetStore, @Suppress("UNUSED_PARAMETER") version: Int) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val wide = size.width >= 200.dp
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val now = System.currentTimeMillis()
    val snap = store.today()
    val hidden = store.hidden(now)
    val ui = WidgetStates.today(store.signedOut, snap, store.todayFailedAt(), today, zone, now, hidden)

    Column(GlanceModifier.fillMaxSize().sagaBackground().padding(WidgetLook.pad).clickable(actionStartActivity(WidgetLinks.intent(ctx, WidgetLinks.TODAY)))) {
        // Kopf: "Heute" (Gold) + Datum, rechts "+" (neue Aufgabe)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ctx.getString(R.string.widget_today_title), style = WidgetLook.text(WidgetLook.title, bold = true, color = WidgetLook.gold), maxLines = 1)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                today.format(DateTimeFormatter.ofPattern(
                    android.text.format.DateFormat.getBestDateTimePattern(ctx.resources.configuration.locales[0], if (wide) "EEEEMMMd" else "EEEd"),
                    ctx.resources.configuration.locales[0],
                )),
                style = WidgetLook.muted(),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (wide && ui is WidgetUi.Content) {
                Box(
                    GlanceModifier.size(32.dp).cornerRadius(16.dp).background(GlanceTheme.colors.secondaryContainer)
                        .clickable(actionStartActivity(WidgetLinks.intent(ctx, WidgetLinks.NEW_TASK)))
                        .semantics { contentDescription = ctx.getString(R.string.widget_new_task) },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.ic_widget_add), ctx.getString(R.string.widget_new_task), GlanceModifier.size(18.dp),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer))
                }
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        when (ui) {
            WidgetUi.SignedOut -> Hint(ctx.getString(R.string.widget_signed_out))
            WidgetUi.Loading, WidgetUi.NotConfigured, WidgetUi.Missing -> Hint(ctx.getString(R.string.widget_loading))
            is WidgetUi.Content -> {
                val s = snap ?: return@Column
                StatusLine(ctx, ui, s.eventsFailed, store.doneFailed)
                if (ui.empty) {
                    Hint(ctx.getString(R.string.widget_today_empty))
                } else {
                    val evs = TodayLogic.events(s.events, today, zone)
                    val tasks = TodayLogic.tasks(s.tasks, today, hidden)
                    val status = ui.staleSince != null || s.eventsFailed || store.doneFailed
                    val all = TodayLayout.rows(size.height.value, chromeDp = if (status) 72f else 56f)
                    val rows = if (evs.size + tasks.size > all) all - 1 else all       // Platz fuer "+N weitere"
                    val (ne, nt) = TodayLayout.split(rows.coerceAtLeast(1), evs.size, tasks.size)
                    evs.take(ne).forEach { EventRow(ctx, it, wide) }
                    tasks.take(nt).forEach { TaskRow(ctx, it) }
                    val rest = (evs.size - ne) + (tasks.size - nt)
                    if (rest > 0) {
                        Text(
                            ctx.resources.getQuantityString(R.plurals.widget_more, rest, rest),
                            style = WidgetLook.muted(),
                            modifier = GlanceModifier.padding(start = 4.dp, top = 2.dp)
                                .clickable(actionStartActivity(WidgetLinks.intent(ctx, if (tasks.size > nt) WidgetLinks.TASKS else WidgetLinks.EVENTS))),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = WidgetLook.muted(WidgetLook.body), modifier = GlanceModifier.padding(top = 6.dp))
}

/** "Offline · Stand 14:30", Kalender nicht lesbar, Abhaken fehlgeschlagen */
@Composable
private fun StatusLine(ctx: Context, ui: WidgetUi.Content, eventsFailed: Boolean, doneFailed: Boolean) {
    val parts = buildList {
        if (doneFailed) add(ctx.getString(R.string.widget_done_failed))
        ui.staleSince?.let { add(ctx.getString(if (ui.offline) R.string.widget_offline_since else R.string.widget_stale_since, WidgetLook.stamp(ctx, it))) }
        if (eventsFailed) add(ctx.getString(R.string.widget_calendar_failed))
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "), style = WidgetLook.muted(), maxLines = 1, modifier = GlanceModifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun EventRow(ctx: Context, e: EventLine, wide: Boolean) {
    val time = if (e.allday) ctx.getString(R.string.widget_all_day) else e.start?.let { android.text.format.DateFormat.getTimeFormat(ctx).format(Date.from(it.toInstant())) }.orEmpty()
    val color = WidgetLook.hex(e.event.color)?.let { androidx.glance.unit.ColorProvider(it) } ?: GlanceTheme.colors.tertiary
    Row(
        GlanceModifier.fillMaxWidth().height(26.dp).clickable(actionStartActivity(WidgetLinks.intent(ctx, WidgetLinks.EVENTS)))
            .semantics { contentDescription = ctx.getString(R.string.widget_event_a11y, time, e.event.summary) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.width(3.dp).height(16.dp).cornerRadius(2.dp).background(color)) {}
        Spacer(GlanceModifier.width(8.dp))
        Text(time, style = WidgetLook.muted(), maxLines = 1, modifier = GlanceModifier.width(if (wide) 64.dp else 44.dp))
        Text(e.event.summary, style = WidgetLook.text(), maxLines = 1, modifier = GlanceModifier.defaultWeight())
    }
}

@Composable
private fun TaskRow(ctx: Context, t: TaskLine) {
    Row(GlanceModifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        // Kreis = abhaken (Trefferflaeche groesser als das Symbol)
        Box(
            GlanceModifier.size(26.dp).cornerRadius(13.dp)
                .clickable(actionRunCallback<ToggleTaskAction>(actionParametersOf(ToggleTaskAction.ID to t.task.id)))
                .semantics { contentDescription = ctx.getString(R.string.widget_mark_done, t.task.text) },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(R.drawable.ic_widget_task_open), null, GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(if (t.overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant),
            )
        }
        Spacer(GlanceModifier.width(5.dp))
        val desc = buildString {
            append(t.task.text)
            if (t.overdue) append(", ").append(ctx.getString(R.string.widget_overdue))
            if (t.task.prio == 1) append(", ").append(ctx.getString(R.string.widget_high_priority))
        }
        Row(
            GlanceModifier.defaultWeight().clickable(actionStartActivity(WidgetLinks.intent(ctx, WidgetLinks.TASKS))).semantics { contentDescription = desc },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.task.text, style = WidgetLook.text(), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            if (t.task.prio == 1) Text(" !", style = WidgetLook.text(bold = true, color = WidgetLook.gold), maxLines = 1)
        }
    }
}

/** Kreis im Widget angetippt: sofort ausblenden, Server ueber WorkManager */
class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ID] ?: return
        WidgetUpdates.markDone(context, id)
    }

    companion object {
        val ID = ActionParameters.Key<Int>("task")
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdates.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetUpdates.schedule(context)
        WidgetUpdates.requestNow(context)
    }
}
