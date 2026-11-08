package io.github.yggdrasil_sh.saga.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.live.NotifItem
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton

/** Hoechstens so viele Zeilen im Blatt; der Rest unter "Alle anzeigen" */
internal const val SHEET_ROWS = 20

/**
 * Design-Runde 4 (Entscheidung 2, NAV-03/08/22): Glocke = Blatt von unten, Inhalt gebuendelt wie im Web
 * (GET /api/notifications?group=1). Abschnitte "Neu"/"Früher"; Tipp oeffnet das Ziel und markiert die ganze Zeile
 * (alle ids + group_key) gelesen; "Alle gelesen" nur bei Ungelesenem; "Alle anzeigen" -> bisheriger Bildschirm.
 *
 * Schnittstelle fuer r4-nav: [onOpen] bekommt die angetippte (gebuendelte) Zeile als [NotifItem] - derselbe Typ, mit dem
 * der Bildschirm Eintraege oeffnet: `onOpen = { open(repo, nav, it) }` (NotificationsScreen.kt). Das Blatt schliesst
 * sich danach nicht selbst; der Aufrufer ruft ggf. onDismiss. Gelesen-Markieren macht das Blatt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(onDismiss: () -> Unit, onOpen: (target: NotifItem) -> Unit, onShowAll: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as SagaApplication).repo
    val live = repo.live
    val items by live.items.collectAsState()
    val loading by live.loading.collectAsState()
    val unreadRows by NotificationCounts.unreadGroups.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    LaunchedEffect(Unit) { live.loadNotifications() }
    val shown = remember(items) { items.take(SHEET_ROWS) }
    val (fresh, earlier) = remember(shown) { NotifGroups.sections(shown) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.notifications), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            // NAV-22: nur wenn es etwas zu tun gibt (sonst ausgeblendet statt grau)
            if (fresh.isNotEmpty() || unreadRows > 0) {
                SagaTextButton(onClick = { repo.launchAction { live.markAllRead() } }) { Text(stringResource(R.string.notifications_mark_all)) }
            }
        }
        LazyColumn(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.fillMaxWidth()
            if (items.isEmpty() && !loading) item { NotifEmpty(w, compact = true) }
            if (items.isEmpty() && loading) item { CircularProgressIndicator(Modifier.padding(32.dp)) }
            notifSections(fresh, earlier, w) { n ->
                repo.launchAction { live.markRead(n) }
                onOpen(n)
            }
            item(key = "all") {
                SagaTextButton(onClick = onShowAll, modifier = Modifier.padding(vertical = 8.dp).navigationBarsPadding()) {
                    Text(stringResource(R.string.r4n_show_all))
                }
            }
        }
    }
}
