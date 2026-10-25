package io.github.yggdrasil_sh.saga.ui.login

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.ui.theme.SagaNight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Anmeldung wie in iOS: Server eintragen -> "Anmelden" oeffnet den Browser (Passkey, Passwort + TOTP, SSO),
 * danach kommt die App per saga://auth zurueck. Alternativ QR-Code aus dem Web (Apps & Geraete) scannen.
 */
@Composable
fun LoginScreen(repo: SagaRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val savedServer by repo.server.collectAsState()
    var server by remember { mutableStateOf(savedServer) }
    val busy by repo.loginBusy.collectAsState()
    val loginError by repo.loginError.collectAsState()
    val loginHint by repo.loginHint.collectAsState()
    var localError by remember { mutableStateOf<UiText?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else cameraDenied = true
    }

    fun login() {
        // Tastatur zu: sonst springt sie bei der Rueckkehr aus dem Browser wieder auf und verdeckt den Hinweis
        focus.clearFocus()
        localError = null
        repo.loginError.value = null
        repo.loginHint.value = null
        scope.launch {
            try {
                val url = repo.prepareBrowserLogin(server)
                repo.browserLoginStarted()                  // Rueckkehr ohne Code -> "Anmeldung abgebrochen"
                // nach dem Abmelden in der App (Kontowechsel): privater Tab ohne die Sitzung des bisherigen Kontos
                openUrl(context, url, ephemeral = repo.freshLogin, inApp = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = repo.friendly(e)
            }
        }
    }

    // Lokales Netz in den App-Einstellungen erlaubt: beim Zurueckkommen Meldung weg und Anmeldung fortsetzen
    val lanError = UiText.Res(R.string.error_local_network)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.currentStateFlow.collect {
            if (it == androidx.lifecycle.Lifecycle.State.RESUMED && localError == lanError &&
                io.github.yggdrasil_sh.saga.util.LocalNetwork.granted(context) && !busy && server.isNotBlank()
            ) login()
        }
    }

    fun startScan() {
        localError = null
        repo.loginError.value = null
        repo.loginHint.value = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
        else permission.launch(Manifest.permission.CAMERA)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Design 2: ein Hauch Gold von oben (wie das Licht im App-Symbol), sonst ruhige Flaeche
        val glow = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.gold.copy(alpha = 0.16f)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to glow, 0.45f to androidx.compose.ui.graphics.Color.Transparent)))
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(104.dp).shadow(12.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(SagaNight),
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.login_subtitle), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text(stringResource(R.string.login_server_label)) },
                    // Platzhalter klar als Beispiel erkennbar (sonst wirkt er wie ein schon eingetragener Wert)
                    placeholder = { Text(stringResource(R.string.server_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontStyle = FontStyle.Italic) },
                    supportingText = { Text(stringResource(R.string.login_server_hint)) },
                    singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { login() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { login() }, enabled = !busy && server.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(if (busy) R.string.login_button_busy else R.string.login_button))
                }
                OutlinedButton(onClick = { startScan() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.login_qr))
                }
                // Design 2: die beiden Hinweise gesammelt in einer ruhigen Karte statt loser grauer Zeilen
                io.github.yggdrasil_sh.saga.ui.theme.SagaCard(Modifier.fillMaxWidth().padding(top = 4.dp), padding = 14.dp) {
                    Text(
                        stringResource(R.string.login_qr_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 2.1: Zwei-Faktor-Anmeldung laeuft im Browserfenster (Passkey/Code) - die App braucht dafuer nichts
                    Text(
                        stringResource(R.string.login_2fa_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // Kontowechsel ohne privaten Tab (Browser kann es nicht): der Browser bleibt beim bisherigen Konto angemeldet
                val switchHint = remember { repo.freshLogin && !io.github.yggdrasil_sh.saga.ui.common.ephemeralTabsSupported(context) }
                if (switchHint) {
                    Text(
                        stringResource(R.string.login_switch_hint),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(28.dp))
                (localError ?: loginError)?.let {
                    Text(it.resolve(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                }
                // Browser ohne Ergebnis verlassen (abgebrochen, kein Code): neutraler Hinweis + erneut versuchen
                if (localError == null && loginError == null && !busy) loginHint?.let {
                    Text(it.resolve(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    if (server.isNotBlank()) TextButton(onClick = { login() }) { Text(stringResource(R.string.action_retry)) }
                }
                if (cameraDenied) {
                    Text(
                        stringResource(R.string.login_camera_denied), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text(stringResource(R.string.login_open_app_settings)) }
                }
                // Sprache vor dem Anmelden waehlbar (fremdes Geraet in einer anderen Sprache)
                io.github.yggdrasil_sh.saga.ui.settings.LanguageMenuButton()
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (scanning) {
        QrScannerDialog(
            onDismiss = { scanning = false },
            onResult = { text ->
                val t = PairLink.parse(text)
                if (t != null) {
                    scanning = false
                    repo.offerPair(t)                       // Ziel-Host bestaetigen lassen (auch beim QR-Scan)
                    true
                } else false
            },
        )
    }
}
