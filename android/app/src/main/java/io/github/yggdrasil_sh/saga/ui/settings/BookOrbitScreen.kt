package io.github.yggdrasil_sh.saga.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.BookOrbit
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.bookorbitConnect
import io.github.yggdrasil_sh.saga.data.bookorbitDisconnect
import io.github.yggdrasil_sh.saga.data.bookorbitStatus
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.common.dateTimeText
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaOutlinedButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException

/**
 * Einstellungen -> "BookOrbit" (Saga 2.5, bookorbit1): einmal mit Benutzer und Passwort anmelden (der Server behaelt nur
 * das Token, nie das Passwort), bei abgelaufener Anmeldung neu anmelden, trennen. Wie im Web (Einstellungen -> Verbindungen).
 */
@Composable
fun BookOrbitScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    var st by remember { mutableStateOf<BookOrbit.Status?>(null) }
    var missing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<UiText, Boolean>?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var askOff by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            val s = repo.bookorbitStatus()
            if (s == null) missing = true else {
                st = s
                if (url == null) url = s.url ?: s.suggest.orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            msg = repo.friendly(e) to false
        }
    }
    LaunchedEffect(Unit) { load() }

    fun connect() {
        if (busy || !BookOrbit.canConnect(url.orEmpty(), user, pw)) return
        busy = true
        msg = UiText.Res(R.string.ub_checking) to true
        repo.launchAction(onError = { e -> busy = false; pw = ""; msg = repo.friendly(e) to false }) {
            repo.bookorbitConnect(url.orEmpty(), user, pw)
            pw = ""
            user = ""
            msg = null
            busy = false
            load()
        }
    }

    Scaffold(topBar = { SagaTopBar("BookOrbit", nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            if (missing) {
                item { Hint(stringResource(R.string.bo_unsupported), w.padding(top = 8.dp)) }
                return@LazyColumn
            }
            val s = st ?: run {
                if (msg == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                msg?.let { m -> item { Hint(m.first.resolve(), w, error = !m.second) } }
                return@LazyColumn
            }
            if (!s.available) {
                item { Hint(stringResource(R.string.bo_unsupported), w.padding(top = 8.dp)) }
                return@LazyColumn
            }
            if (s.connected) {
                item {
                    ListItem(
                        headlineContent = { Text("✅ " + (s.name?.let { stringResource(R.string.nc_connected_as, it) } ?: stringResource(R.string.bo_connected))) },
                        supportingContent = { if (s.host.isNotEmpty()) Text(s.host) },
                        modifier = w.padding(top = 8.dp),
                    )
                }
                s.since?.let { t -> item { ListItem(headlineContent = { Text(stringResource(R.string.nc_since)) }, trailingContent = { Text(dateTimeText((t * 1000).toLong(), true)) }, modifier = w) } }
                if (s.state == BookOrbit.State.EXPIRED) item { Hint(stringResource(R.string.bo_hint_expired), w, error = true) }
                else item { Hint(stringResource(R.string.bo_connected_hint), w) }
                item {
                    SagaOutlinedButton(onClick = { askOff = true }, enabled = !busy, modifier = w.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(stringResource(R.string.ub_disconnect), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (s.needsLogin) {
                item { Hint(stringResource(R.string.bo_intro), w.padding(top = 8.dp)) }
                item {
                    Column(w.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            url.orEmpty(), { url = it }, label = { Text(stringResource(R.string.bo_address)) }, placeholder = { Text("https://bookorbit.example.org") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            user, { user = it.take(100) }, label = { Text(stringResource(R.string.bo_username)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                        )
                        OutlinedTextField(
                            pw, { pw = it.take(1024) }, label = { Text(stringResource(R.string.bo_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { connect() }),
                            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                        )
                        SagaButton(onClick = { connect() }, enabled = !busy && BookOrbit.canConnect(url.orEmpty(), user, pw)) { Text(stringResource(R.string.bo_sign_in)) }
                    }
                }
            }
            msg?.let { m -> item { Hint(m.first.resolve(), w, error = !m.second) } }
        }
    }
    if (askOff) {
        AlertDialog(
            onDismissRequest = { askOff = false },
            title = { Text(stringResource(R.string.bo_disconnect_title)) },
            text = { Text(stringResource(R.string.bo_disconnect_text)) },
            confirmButton = {
                SagaTextButton(onClick = {
                    askOff = false
                    busy = true
                    repo.launchAction(onError = { e -> busy = false; msg = repo.friendly(e) to false }) { repo.bookorbitDisconnect(); busy = false; msg = null; load() }
                }) { Text(stringResource(R.string.ub_disconnect), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { SagaTextButton(onClick = { askOff = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
