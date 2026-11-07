package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.NetworkState
import io.github.yggdrasil_sh.saga.data.OfflineLogic
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Zustand der Offline-Leiste: sichtbar (entprellt) und Uhrzeit des gezeigten Stands (null = unbekannt) */
@Stable
class OfflineState {
    var visible by mutableStateOf(false)
        internal set
    var since by mutableStateOf<Long?>(null)
        internal set
}

/**
 * NAV-02: beobachtet Netz (ConnectivityManager) und Live-Kanal. Die Leiste erscheint erst nach [OfflineLogic.DEBOUNCE_MS]
 * ohne Verbindung (kurzes Funkloch, Aufbau des Live-Kanals nach dem Start) und verschwindet sofort, wenn sie wieder steht.
 */
@Composable
fun rememberOfflineState(repo: SagaRepository): OfflineState {
    val ctx = LocalContext.current.applicationContext
    val state = remember { OfflineState() }
    val net by remember(ctx) { NetworkState.flow(ctx) }.collectAsState(initial = true)
    val supported by repo.live.supported.collectAsState()
    val connected by repo.live.connected.collectAsState()
    val offline = OfflineLogic.offline(net, supported, connected)
    LaunchedEffect(offline) {
        if (!offline) {
            state.visible = false
            // solange verbunden: Stempel laufend nachziehen (der Stand ist "jetzt")
            while (true) {
                state.since = OfflineLogic.stamp(state.since, online = true, nowMs = System.currentTimeMillis())
                delay(30_000)
            }
        } else {
            // eben noch verbunden: der Stand ist der von jetzt (nicht der letzte 30-s-Takt)
            if (state.since != null) state.since = System.currentTimeMillis()
            delay(OfflineLogic.DEBOUNCE_MS)
            state.visible = true
        }
    }
    return state
}

/** Schmale Leiste oben (unter der Anruf-Leiste): Wolke durchgestrichen + "Offline – du siehst den Stand von 9:12 …" */
@Composable
fun OfflineBar(state: OfflineState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = state.visible, modifier = modifier,
        enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
    ) {
        val since = state.since
        val text = if (since == null) stringResource(R.string.r1_offline_bar_unknown)
        else stringResource(R.string.r1_offline_bar, formatStamp(since))
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_cloud_off), null, Modifier.size(20.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** 9:12 (heute) bzw. Datum + Uhrzeit (aelter) in der Sprache der Oberflaeche */
@Composable
private fun formatStamp(ms: Long): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val f = if (OfflineLogic.sameDay(ms, System.currentTimeMillis(), zone)) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    return Instant.ofEpochMilli(ms).atZone(zone).format(f.withLocale(locale))
}

/** Sofort (ohne Entprellung): jetzt offline? Fuer "Aktualisieren" (Snackbar statt stiller Fehlschlag) */
fun offlineNow(ctx: android.content.Context, repo: SagaRepository): Boolean =
    OfflineLogic.offline(NetworkState.online(ctx), repo.live.supported.value, repo.live.connected.value)
