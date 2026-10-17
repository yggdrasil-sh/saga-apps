package io.github.yggdrasil_sh.saga.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.LocalNetwork

/**
 * Android 17: Rueckfrage "Zugriff aufs lokale Netzwerk", wenn der Server im Heimnetz liegt (repo.localNetworkNeeded).
 * Erst erklaeren, dann das System fragen; hat das System die Frage gesperrt (zweimal abgelehnt), fuehrt der Knopf
 * zu den App-Einstellungen (Berechtigungen > Geraete in der Naehe).
 */
@Composable
fun LocalNetworkPrompt(repo: SagaRepository) {
    val host by repo.localNetworkNeeded.collectAsState()
    val h = host ?: return
    val context = LocalContext.current
    var denied by remember(h) { mutableStateOf(false) }
    // Nach der ersten Ablehnung fragt das System noch einmal (Begruendung erlaubt) - erst danach ist die Frage gesperrt
    // und nur die App-Einstellungen helfen. Knopf und Ziel danach waehlen.
    var toSettings by remember(h) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) repo.localNetworkAnswered(true) else {
            denied = true
            val act = context.findActivity()
            toSettings = LocalNetworkFlow.openSettingsNext(act != null && ActivityCompat.shouldShowRequestPermissionRationale(act, LocalNetwork.PERMISSION))
        }
    }
    AlertDialog(
        onDismissRequest = { repo.localNetworkAnswered(false) },
        title = { Text(stringResource(R.string.local_network_title)) },
        text = { Text(stringResource(if (denied) R.string.local_network_denied else R.string.local_network_text, h)) },
        confirmButton = {
            TextButton(onClick = {
                if (toSettings) {
                    openAppSettings(context)
                    repo.localNetworkAnswered(false)
                } else {
                    launcher.launch(LocalNetwork.PERMISSION)
                }
            }) { Text(stringResource(if (toSettings) R.string.local_network_settings else R.string.local_network_allow)) }
        },
        dismissButton = { TextButton(onClick = { repo.localNetworkAnswered(false) }) { Text(stringResource(R.string.action_later)) } },
    )
}

/** Ablauf der Rueckfrage (rein, testbar) */
object LocalNetworkFlow {
    /** Nach einer Ablehnung: darf das System noch fragen (Begruendung)? Sonst fuehrt der Knopf zu den Einstellungen */
    fun openSettingsNext(systemMayAskAgain: Boolean): Boolean = !systemMayAskAgain
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
    }
}
