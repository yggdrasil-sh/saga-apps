package io.github.yggdrasil_sh.saga.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.auth.PairSwitch
import io.github.yggdrasil_sh.saga.auth.PairTarget
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Kopplung bestaetigen (Deep-Link saga://pair oder QR-Code): Ziel-Host zeigen, bei bestehender Anmeldung
 * deutlich sagen, dass das bisherige Konto abgemeldet wird. Ohne Bestaetigung passiert nichts.
 * Laeuft im App-Scope (der Dialog verschwindet bei Erfolg - das Koppeln darf dabei nicht abbrechen).
 */
@Composable
fun PairConfirmDialog(repo: SagaRepository, target: PairTarget, appScope: CoroutineScope) {
    val busy by repo.loginBusy.collectAsState()
    val error by repo.pairError.collectAsState()
    val needsLogin by repo.needsLogin.collectAsState()
    val server by repo.server.collectAsState()
    val account by repo.account.collectAsState()
    val signedIn = !needsLogin && server.isNotEmpty()
    var drafts by remember { mutableIntStateOf(0) }
    LaunchedEffect(target) { if (signedIn) drafts = repo.unsavedDraftCount() }
    val host = PairLink.displayHost(target.server)

    // Texte einheitlich auf allen Plattformen (Glossar "Einheitliche Dialoge")
    AlertDialog(
        onDismissRequest = { if (!busy) repo.cancelPair() },
        title = { Text(stringResource(R.string.pair_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pair_confirm_text, host), fontWeight = FontWeight.SemiBold)
                if (signedIn) {
                    // bisheriges Konto wird abgemeldet - auf demselben Server ohne den widerspruechlichen Host (Nachtest N1)
                    val warning = when (val sw = PairSwitch.of(true, server, target.server, account)) {
                        is PairSwitch.SameServer ->
                            if (sw.account.isNotEmpty()) stringResource(R.string.pair_same_server_warning, sw.account)
                            else stringResource(R.string.pair_same_server_warning_noname)
                        is PairSwitch.OtherServer -> stringResource(R.string.pair_switch_warning, sw.host)
                        PairSwitch.None -> null
                    }
                    warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (drafts > 0) Text(pluralStringResource(R.plurals.pair_drafts_kept, drafts, drafts), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.pair_confirm_trust), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { appScope.launch { repo.confirmPair() } }, enabled = !busy) {
                Text(stringResource(R.string.pair_confirm_button))
            }
        },
        dismissButton = { TextButton(onClick = { repo.cancelPair() }, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}
