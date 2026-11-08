package io.github.yggdrasil_sh.saga.ui.common

import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
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

    /**
     * CH-24: erst fragen, wenn es um etwas geht - beim ersten Oeffnen eines Chats mit Nachrichten bzw. nach dem ersten
     * Senden (dann hat der Chat eine Nachricht), nicht schon beim Oeffnen der Chat-Liste
     */
    fun moment(opened: Boolean, hasMessages: Boolean): Boolean = opened && hasMessages

    /** Pruefer R5: auch in der Chat-Liste, sobald sie geladen und nicht leer ist (nicht ueber dem leeren Zustand) */
    fun listMoment(loaded: Boolean, chats: Int): Boolean = loaded && chats > 0

    /**
     * Pruefer R5: beim ersten eigenen Anruf die Mitteilungs-Berechtigung mit der Mikrofon-Abfrage erfragen (vor dem
     * Waehlen, das Ergebnis haelt den Anruf nicht auf) - nur, solange noch nie gefragt wurde (wie die Erklaerung)
     */
    fun forCall(sdk: Int, granted: Boolean, askedBefore: Boolean): Boolean = shouldAsk(sdk, granted, askedBefore)

    /** N-02: nie waehrend eines Anrufs (Klingeln, Verbinden, laufend) - die Abfrage verdeckt sonst den Anruf */
    fun showNow(wanted: Boolean, inCall: Boolean): Boolean = wanted && !inCall

    fun granted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun askedBefore(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ASKED, true) }
        askedNow.value = true
    }

    /** Pruefer R5: in diesem Prozess schon gefragt (Liste, Chat oder Anruf) - offene Abfragen anderer Stellen verschwinden */
    val askedNow = androidx.compose.runtime.mutableStateOf(false)

    /** welche Stelle die Erklaerung gerade zeigt (Liste und Chat sind zweispaltig gleichzeitig sichtbar) */
    val owner = androidx.compose.runtime.mutableStateOf<Any?>(null)
}

/** Einmalige Erklaerung "Mitteilungen erlauben?" mit anschliessender Systemabfrage; ready = passender Moment (CH-24) */
@Composable
fun NotificationPermissionPrompt(ready: Boolean = true) {
    val context = LocalContext.current
    var show by remember {
        mutableStateOf(NotifPrompt.shouldAsk(Build.VERSION.SDK_INT, NotifPrompt.granted(context), NotifPrompt.askedBefore(context)))
    }
    val app = context.applicationContext as io.github.yggdrasil_sh.saga.SagaApplication
    // erlaubt -> Mitteilungen auf diesem Geraet gleich einschalten (auch bei geschlossener App, wie iOS)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) app.repo.launchAction { app.repo.push.autoEnable() }
    }
    val call by app.repo.calls.ui.collectAsState()
    val me = remember { Any() }
    androidx.compose.runtime.DisposableEffect(me) { onDispose { if (NotifPrompt.owner.value === me) NotifPrompt.owner.value = null } }
    if (!ready || NotifPrompt.askedNow.value || !NotifPrompt.showNow(show, inCall = call != null)) return
    // nur eine Stelle zeigt die Erklaerung
    if (NotifPrompt.owner.value == null) androidx.compose.runtime.SideEffect { if (NotifPrompt.owner.value == null) NotifPrompt.owner.value = me }
    if (NotifPrompt.owner.value !== me) return
    val close = { later: Boolean ->
        show = false
        NotifPrompt.owner.value = null
        NotifPrompt.markAsked(context)
        if (!later && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    AlertDialog(
        onDismissRequest = { close(true) },
        icon = { SagaIcon(SagaIcons.bell, null) },
        title = { Text(stringResource(R.string.notif_prompt_title)) },
        text = { Text(stringResource(R.string.notif_prompt_text)) },
        confirmButton = { SagaTextButton(onClick = { close(false) }) { Text(stringResource(R.string.r5_notif_allow)) } },
        dismissButton = { SagaTextButton(onClick = { close(true) }) { Text(stringResource(R.string.notif_prompt_later)) } },
    )
}
