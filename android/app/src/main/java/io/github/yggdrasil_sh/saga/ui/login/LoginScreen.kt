package io.github.yggdrasil_sh.saga.ui.login

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
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.launch

/**
 * Anmeldung wie in iOS: Server eintragen -> "Anmelden" oeffnet den Browser (Passkey, Passwort + TOTP, SSO),
 * danach kommt die App per saga://auth zurueck. Alternativ QR-Code aus dem Web (Apps & Geraete) scannen.
 */
@Composable
fun LoginScreen(repo: SagaRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedServer by repo.server.collectAsState()
    var server by remember { mutableStateOf(savedServer) }
    val busy by repo.loginBusy.collectAsState()
    val loginError by repo.loginError.collectAsState()
    var localError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else cameraDenied = true
    }

    fun login() {
        localError = null
        repo.loginError.value = null
        scope.launch {
            try {
                openUrl(context, repo.prepareBrowserLogin(server))
            } catch (e: Exception) {
                localError = e.message
            }
        }
    }

    fun startScan() {
        localError = null
        repo.loginError.value = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
        else permission.launch(Manifest.permission.CAMERA)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(SagaNight),
                )
                Text("Saga", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Anmelden mit Passkey oder Passwort im Browser", textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text("Server") }, placeholder = { Text("https://saga.example.org") },
                    singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { login() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { login() }, enabled = !busy && server.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Anmelden …" else "Anmelden")
                }
                OutlinedButton(onClick = { startScan() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Mit QR-Code anmelden")
                }
                Text(
                    "Den QR-Code zeigt Saga im Browser unter Benutzer → Einstellungen → „Apps & Geräte“.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) CircularProgressIndicator(Modifier.size(28.dp))
                (localError ?: loginError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                }
                if (cameraDenied) {
                    Text(
                        "Ohne Kamera-Erlaubnis kann der QR-Code nicht gelesen werden.", textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("App-Einstellungen öffnen") }
                }
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
                    server = t.server
                    scope.launch { repo.pair(t) }
                    true
                } else false
            },
        )
    }
}
