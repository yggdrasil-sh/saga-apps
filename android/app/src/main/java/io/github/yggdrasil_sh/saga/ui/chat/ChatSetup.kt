package io.github.yggdrasil_sh.saga.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatService
import io.github.yggdrasil_sh.saga.chat.ChatSetup
import io.github.yggdrasil_sh.saga.chat.LinkSession
import io.github.yggdrasil_sh.saga.chat.PendingSetup
import io.github.yggdrasil_sh.saga.chat.ReauthNeeded
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Saga", text))
}

fun shareText(context: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Einrichtung der Chats auf diesem Geraet (je nach Zustand) */
@Composable
fun ChatSetupView(svc: ChatService, st: ChatSetup) {
    val error by svc.error.collectAsState()
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            when (st) {
                ChatSetup.LOADING -> CircularProgressIndicator()
                ChatSetup.UNSUPPORTED -> Info(stringResource(R.string.chat_unsupported))
                ChatSetup.OFF -> Info(stringResource(R.string.chat_off))
                ChatSetup.ERROR -> {
                    Info(error?.resolve() ?: stringResource(R.string.error_generic))
                    Button(onClick = { scope.launch { svc.refresh() } }, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.action_retry)) }
                }
                ChatSetup.NEW_ACCOUNT -> NewAccount(svc)
                ChatSetup.NEW_DEVICE -> NewDevice(svc)
                ChatSetup.READY -> {}
            }
        }
    }
}

@Composable
private fun Info(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Erstes Geraet: Schluessel erzeugen, Wiederherstellungsschluessel zeigen und bestaetigen lassen */
@Composable
private fun NewAccount(svc: ChatService) {
    var pending by remember { mutableStateOf<PendingSetup?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<UiText?>(null) }
    val scope = rememberCoroutineScope()
    val p = pending
    if (p == null) {
        Text(stringResource(R.string.chat_setup_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.chat_setup_text), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = {
            busy = true
            scope.launch {
                try {
                    pending = svc.prepareSetup()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    err = svc.repo.friendly(e)
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy, modifier = Modifier.padding(top = 20.dp)) { Text(stringResource(R.string.chat_setup_start)) }
        err?.let { ErrorText(it) }
    } else {
        RecoveryKeyConfirm(p.recoveryKey, busy, err, onCancel = { pending = null; err = null }) {
            busy = true
            err = null
            scope.launch {
                try {
                    svc.completeSetup(p)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    err = svc.repo.friendly(e)
                } finally {
                    busy = false
                }
            }
        }
    }
}

@Composable
private fun ErrorText(t: UiText) {
    Text(
        t.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Wiederherstellungsschluessel gross zeigen (kopieren/teilen), letzte 4 Zeichen bestaetigen */
@Composable
fun RecoveryKeyConfirm(key: String, busy: Boolean, err: UiText?, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val context = LocalContext.current
    var last4 by remember(key) { mutableStateOf("") }
    val norm = ChatFormat.normRecovery(key).orEmpty()
    val ok = last4.trim().uppercase().replace('0', 'O').replace('1', 'I').replace('8', 'B') == norm.takeLast(4)
    Text(stringResource(R.string.chat_recovery_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(
        stringResource(R.string.chat_recovery_text), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
    )
    Card(Modifier.padding(top = 16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Text(
            key.chunked(20).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { copyText(context, key) }) { Text(stringResource(R.string.text_copy)) }
        OutlinedButton(onClick = { shareText(context, key) }) { Text(stringResource(R.string.chat_share)) }
    }
    OutlinedTextField(
        value = last4, onValueChange = { if (it.length <= 4) last4 = it }, singleLine = true,
        label = { Text(stringResource(R.string.chat_recovery_last4)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.padding(top = 16.dp),
    )
    err?.let { ErrorText(it) }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        Button(onClick = onConfirm, enabled = ok && !busy) { Text(stringResource(R.string.chat_recovery_done)) }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
    if (busy) {
        Text(stringResource(R.string.chat_keys_busy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Weiteres Geraet: koppeln, Wiederherstellungsschluessel eingeben oder (mit Warnung) neu beginnen */
@Composable
private fun NewDevice(svc: ChatService) {
    var mode by remember { mutableStateOf("choose") }
    Text(stringResource(R.string.chat_new_device_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    when (mode) {
        "link" -> LinkWait(svc) { mode = "choose" }
        "recover" -> RecoverForm(svc) { mode = "choose" }
        "restart" -> StartOver(svc) { mode = "choose" }
        else -> {
            Text(
                stringResource(R.string.chat_new_device_text), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = { mode = "link" }, modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) { Text(stringResource(R.string.chat_link_other)) }
            OutlinedButton(onClick = { mode = "recover" }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) { Text(stringResource(R.string.chat_enter_recovery)) }
            TextButton(onClick = { mode = "restart" }, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.chat_start_over), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LinkWait(svc: ChatService, onBack: () -> Unit) {
    var ls by remember { mutableStateOf<LinkSession?>(null) }
    var err by remember { mutableStateOf<UiText?>(null) }
    var expired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            ls = svc.startLink()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = svc.repo.friendly(e)
        }
    }
    // alle 3 s fragen; ein chat.link-Ereignis (approved) beschleunigt das ueber svc.refresh()
    LaunchedEffect(ls) {
        val s = ls ?: return@LaunchedEffect
        while (true) {
            delay(3_000)
            try {
                when (svc.pollLink(s)) {
                    "approved" -> return@LaunchedEffect
                    "expired", "gone" -> {
                        expired = true
                        return@LaunchedEffect
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err = svc.repo.friendly(e)
            }
        }
    }
    Text(
        stringResource(R.string.chat_link_wait_text), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
    )
    val s = ls
    if (s != null && !expired) {
        Text(
            linkCodeLines(s.code), fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
        )
        CircularProgressIndicator(Modifier.padding(top = 16.dp).size(24.dp), strokeWidth = 2.dp)
    } else if (expired) {
        Info(stringResource(R.string.chat_link_expired))
        Button(onClick = { expired = false; ls = null; scope.launch { ls = svc.startLink() } }, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.action_retry))
        }
    } else if (err == null) {
        CircularProgressIndicator(Modifier.padding(top = 16.dp))
    }
    err?.let { ErrorText(it) }
    TextButton(onClick = {
        ls?.let { scope.launch { svc.cancelLink(it) } }
        onBack()
    }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.action_cancel)) }
}

@Composable
private fun RecoverForm(svc: ChatService, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<UiText?>(null) }
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = key, onValueChange = { key = it }, label = { Text(stringResource(R.string.chat_recovery_key)) },
        placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
    )
    err?.let { ErrorText(it) }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        Button(onClick = {
            busy = true
            err = null
            scope.launch {
                try {
                    svc.recover(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    err = svc.repo.friendly(e)
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy && ChatFormat.normRecovery(key) != null) { Text(stringResource(R.string.chat_recover)) }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/**
 * "Neu beginnen" (mit Warnung): neue Schluessel, alte Geraete abgemeldet, alte Nachrichten unlesbar. Verlangt eine frische
 * Anmeldung: Passwort (Feld) bzw. Hinweis "neu anmelden" (SSO).
 */
@Composable
fun StartOver(svc: ChatService, onBack: () -> Unit) {
    var confirmed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingSetup?>(null) }
    var password by remember { mutableStateOf("") }
    var needPassword by remember { mutableStateOf(svc.me.value?.reauth == "password") }
    var needLogin by remember { mutableStateOf(svc.me.value?.reauth == "login") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<UiText?>(null) }
    val scope = rememberCoroutineScope()
    if (!confirmed) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.chat_start_over_title)) },
            text = { Text(stringResource(R.string.chat_start_over_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmed = true
                    scope.launch { pending = svc.prepareSetup() }
                }) { Text(stringResource(R.string.chat_start_over), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_cancel)) } },
        )
        return
    }
    val p = pending ?: run {
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    if (needLogin) {
        Info(stringResource(R.string.chat_reauth_login))
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.action_close)) }
        return
    }
    if (needPassword) {
        OutlinedTextField(
            value = password, onValueChange = { password = it }, singleLine = true,
            label = { Text(stringResource(R.string.chat_reauth_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        )
    }
    RecoveryKeyConfirm(p.recoveryKey, busy, err, onCancel = onBack) {
        busy = true
        err = null
        scope.launch {
            try {
                svc.completeSetup(p, reset = true, password = password.ifEmpty { null })
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReauthNeeded) {
                if (e.kind == "login") needLogin = true else needPassword = true
                err = e.text
            } catch (e: Exception) {
                err = svc.repo.friendly(e)
            } finally {
                busy = false
            }
        }
    }
}
