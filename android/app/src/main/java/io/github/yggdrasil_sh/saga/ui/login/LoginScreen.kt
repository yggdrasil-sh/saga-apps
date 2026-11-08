package io.github.yggdrasil_sh.saga.ui.login

import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
/** Feste Masse der Anmeldung (kein Wechsel mit der Tastatur) und die Enter-Tasten */
internal object LoginLayout {
    val LOGO = 104.dp
    val TOP_PADDING = 48.dp
    /** Wartezeit nach der letzten Groessenaenderung, bevor Feld + Knopf ins Bild gescrollt werden */
    const val SETTLE_MS = 80L
    fun isEnter(k: Key): Boolean = k == Key.Enter || k == Key.NumPadEnter
    /** A11Y-27: ab dieser Breite (dp) steht die Anmeldung als Karte in der Mitte */
    const val CARD_FROM_DP = 600f
    val CARD_MAX = 480.dp
    val CARD_MARGIN = 24.dp
    /** Hoehe mindestens so viel, dass es kein Phone quer ist (Pruefung R6: "Phone unveraendert") */
    const val CARD_MIN_H_DP = 480f
    fun card(widthDp: Float, heightDp: Float = Float.MAX_VALUE): Boolean = widthDp >= CARD_FROM_DP && heightDp >= CARD_MIN_H_DP
    /**
     * R6-Rest: Karte auf niedriger Sichtflaeche dichter (kleineres Logo, engere Abstaende), damit sie mit grosser Schrift
     * (Tablet quer, 130 %) nicht bis zur Gestenleiste reicht. Massstab: Hoehe geteilt durch die Schriftgroesse.
     */
    const val CARD_COMPACT_BELOW_DP = 640f
    val LOGO_COMPACT = 72.dp
    fun cardCompact(viewportHDp: Float, fontScale: Float = 1f): Boolean = viewportHDp / maxOf(fontScale, 1f) < CARD_COMPACT_BELOW_DP
    fun logo(card: Boolean, viewportHDp: Float, fontScale: Float = 1f): androidx.compose.ui.unit.Dp =
        if (card && cardCompact(viewportHDp, fontScale)) LOGO_COMPACT else LOGO
    fun cardPadding(viewportHDp: Float, fontScale: Float = 1f): androidx.compose.ui.unit.Dp = if (cardCompact(viewportHDp, fontScale)) 20.dp else 28.dp
    fun gap(card: Boolean, viewportHDp: Float, fontScale: Float = 1f): androidx.compose.ui.unit.Dp =
        if (card && cardCompact(viewportHDp, fontScale)) 10.dp else 14.dp
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
        // NAV-06: neuer Versuch - alter Kamera-Hinweis weg
        cameraDenied = false
        // A11Y-14: Knopf bleibt aktiv; leeres Feld erklaert sich am Feld statt eines grauen, "kaputt" wirkenden Knopfs
        if (server.isBlank()) {
            repo.loginError.value = null
            localError = UiText.Res(R.string.r1_login_err_empty)
            return
        }
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
                // NAV-06: kein Netz / Adresse nicht gefunden / keine Antwort / kein Saga-Server unterscheiden
                val kind = io.github.yggdrasil_sh.saga.data.LoginErrors.classify(e, io.github.yggdrasil_sh.saga.data.NetworkState.online(context))
                localError = io.github.yggdrasil_sh.saga.data.LoginErrors.text(kind, repo.friendly(e))
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
        cameraDenied = false
        localError = null
        repo.loginError.value = null
        repo.loginHint.value = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
        else permission.launch(Manifest.permission.CAMERA)
    }

    var qrHelp by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Design 2: ein Hauch Gold von oben (wie das Licht im App-Symbol), sonst ruhige Flaeche
        val glow = io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent.current.gold.copy(alpha = 0.16f)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to glow, 0.45f to androidx.compose.ui.graphics.Color.Transparent)))
        // NAV-05: oben ausgerichtet statt zentriert - der Block springt nicht mit der Tastatur (Fehltipps auf QR).
        // R1-Nachbesserung: das Logo bleibt gleich gross (das Schrumpfen schob Feld und Knoepfe um 48 dp nach oben).
        // Passt der Block nicht ueber die Tastatur (Tablet quer), wird nur gescrollt: Feld + "Anmelden" ins Bild.
        // A11Y-27: ab 600 dp Breite (Tablet) als Karte (max. 480 dp) mittig, scrollbar; Phone unveraendert oben ausgerichtet
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            val card = LoginLayout.card(maxWidth.value, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat())
            val viewportH = maxHeight
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val gap = LoginLayout.gap(card, viewportH.value, fontScale)
            val fieldArea = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            var fieldFocused by remember { mutableStateOf(false) }
            var viewport by remember { mutableStateOf(0) }
            // Sichtflaeche schrumpft (Tastatur auf) oder Feld bekommt den Fokus: nach dem Ende der Inset-Animation
            // (LaunchedEffect startet bei jedem Frame neu = entprellt) Feld und Knopf zeigen - passt es schon, scrollt nichts
            androidx.compose.runtime.LaunchedEffect(viewport, fieldFocused) {
                if (fieldFocused && viewport > 0) {
                    kotlinx.coroutines.delay(LoginLayout.SETTLE_MS)
                    fieldArea.bringIntoView()
                }
            }
            val form: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                val logo = LoginLayout.logo(card, viewportH.value, fontScale)
                Image(
                    painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(logo).shadow(12.dp, RoundedCornerShape(logo * 0.27f)).clip(RoundedCornerShape(logo * 0.27f)).background(SagaNight),
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.login_subtitle), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Feld und "Anmelden" als ein Bereich: er wird bei offener Tastatur gemeinsam ins Bild gescrollt
                Column(
                    Modifier.fillMaxWidth().bringIntoViewRequester(fieldArea),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    // NAV-06: Fehler direkt am Feld (isError, supportingText, Semantik error)
                    val fieldError = (localError ?: loginError)?.resolve()
                    OutlinedTextField(
                        value = server,
                        onValueChange = {
                            server = it
                            // Adresse geaendert: die alte Meldung passt nicht mehr
                            if (localError != null || loginError != null) {
                                localError = null
                                repo.loginError.value = null
                            }
                        },
                        label = { Text(stringResource(R.string.login_server_label)) },
                        // Platzhalter klar als Beispiel erkennbar (sonst wirkt er wie ein schon eingetragener Wert)
                        placeholder = { Text(stringResource(R.string.server_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontStyle = FontStyle.Italic) },
                        supportingText = { Text(fieldError ?: stringResource(R.string.login_server_hint)) },
                        isError = fieldError != null,
                        singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { login() }),
                        modifier = Modifier.fillMaxWidth()
                            .onFocusChanged { fieldFocused = it.isFocused }
                            // Hardware-Enter (Tablet-Tastatur, Emulator) startet die Anmeldung wie "Los" der Bildschirmtastatur
                            .onPreviewKeyEvent { ev ->
                                if (!LoginLayout.isEnter(ev.key)) return@onPreviewKeyEvent false
                                if (ev.type == KeyEventType.KeyDown && !busy) login()
                                true
                            }
                            .semantics { if (fieldError != null) error(fieldError) },
                    )
                    SagaButton(onClick = { login() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(if (busy) R.string.login_button_busy else R.string.login_button))
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.size(28.dp))
                // Browser ohne Ergebnis verlassen (abgebrochen, kein Code): neutraler Hinweis + erneut versuchen
                if (localError == null && loginError == null && !busy) loginHint?.let {
                    Text(it.resolve(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    if (server.isNotBlank()) SagaTextButton(onClick = { login() }) { Text(stringResource(R.string.action_retry)) }
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
                // NAV-05: QR-Weg deutlich von "Anmelden" abgesetzt ("oder"), damit ein verrutschter Tipp nicht die Kamera fragt
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.r1_login_or), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    HorizontalDivider(Modifier.weight(1f))
                }
                SagaOutlinedButton(onClick = { startScan() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.login_qr))
                }
                if (cameraDenied) {
                    Text(
                        stringResource(R.string.login_camera_denied), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SagaTextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text(stringResource(R.string.login_open_app_settings)) }
                }
                // NAV-29: statt der dauerhaften Hinweis-Karte ein Textknopf mit Blatt (QR-Weg, Zwei-Faktor)
                SagaTextButton(onClick = { qrHelp = true }) { Text(stringResource(R.string.r1_login_qr_where)) }
                // Sprache vor dem Anmelden waehlbar (fremdes Geraet in einer anderen Sprache)
                io.github.yggdrasil_sh.saga.ui.settings.LanguageMenuButton()
                Spacer(Modifier.height(8.dp))
            }
            if (!card) {
                Column(
                    Modifier.widthIn(max = 420.dp).fillMaxWidth()
                        .onSizeChanged { viewport = it.height }
                        .verticalScroll(rememberScrollState())
                        .padding(start = 28.dp, end = 28.dp, bottom = 28.dp, top = LoginLayout.TOP_PADDING),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = form,
                )
            } else {
                // mindestens so hoch wie die Sichtflaeche, damit die Karte mittig steht; passt sie nicht (Tastatur), scrollt sie
                Column(
                    Modifier.fillMaxSize()
                        .onSizeChanged { viewport = it.height }
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = viewportH)
                        .padding(LoginLayout.CARD_MARGIN),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    io.github.yggdrasil_sh.saga.ui.theme.SagaCard(
                        Modifier.widthIn(max = LoginLayout.CARD_MAX).fillMaxWidth(), padding = LoginLayout.cardPadding(viewportH.value, fontScale),
                    ) {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(gap),
                            content = form,
                        )
                    }
                }
            }
        }
    }
    if (qrHelp) LoginHelpSheet { qrHelp = false }

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

/** NAV-29: Hilfe zur Anmeldung als Blatt - wo der QR-Code steht, Zwei-Faktor im Browserfenster */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LoginHelpSheet(onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.r1_login_qr_where), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.login_qr_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 2.1: Zwei-Faktor-Anmeldung laeuft im Browserfenster (Passkey/Code) - die App braucht dafuer nichts
            Text(stringResource(R.string.login_2fa_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SagaTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_done)) }
        }
    }
}
