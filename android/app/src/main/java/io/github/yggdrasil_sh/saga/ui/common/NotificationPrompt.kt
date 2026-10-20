package io.github.yggdrasil_sh.saga.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.yggdrasil_sh.saga.R

/**
 * Mitteilungs-Berechtigung (Android 13+, POST_NOTIFICATIONS) frueh und mit Erklaerung erfragen - beim ersten Oeffnen
 * der Chats nach der Anmeldung, nicht erst beim ersten Anruf (TT 2.4 P-05). Ohne Berechtigung laufen Uploads trotzdem;
 * der Fortschritt steht dann nur in der App (Leiste im Chat).
 */
object NotifPrompt {
    const val PREFS = "saga_ui"
    const val KEY_ASKED = "notif_prompt_asked"

    /** Erklaerung zeigen? Nur ab Android 13, ohne Berechtigung und nur einmal (spaeter: Einstellungen > Mitteilungen) */
    fun shouldAsk(sdk: Int, granted: Boolean, askedBefore: Boolean): Boolean = sdk >= 33 && !granted && !askedBefore

    /** N-02: nie waehrend eines Anrufs (Klingeln, Verbinden, laufend) - die Abfrage verdeckt sonst den Anruf */
    fun showNow(wanted: Boolean, inCall: Boolean): Boolean = wanted && !inCall

    fun granted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun askedBefore(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ASKED, true) }
}

/** Einmalige Erklaerung "Mitteilungen erlauben?" mit anschliessender Systemabfrage */
@Composable
fun NotificationPermissionPrompt() {
    val context = LocalContext.current
    var show by remember {
        mutableStateOf(NotifPrompt.shouldAsk(Build.VERSION.SDK_INT, NotifPrompt.granted(context), NotifPrompt.askedBefore(context)))
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val call by (context.applicationContext as io.github.yggdrasil_sh.saga.SagaApplication).repo.calls.ui.collectAsState()
    if (!NotifPrompt.showNow(show, inCall = call != null)) return
    val close = { later: Boolean ->
        show = false
        NotifPrompt.markAsked(context)
        if (!later && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    AlertDialog(
        onDismissRequest = { close(true) },
        icon = { Icon(Icons.Filled.Notifications, null) },
        title = { Text(stringResource(R.string.notif_prompt_title)) },
        text = { Text(stringResource(R.string.notif_prompt_text)) },
        confirmButton = { TextButton(onClick = { close(false) }) { Text(stringResource(R.string.notif_prompt_allow)) } },
        dismissButton = { TextButton(onClick = { close(true) }) { Text(stringResource(R.string.notif_prompt_later)) } },
    )
}
