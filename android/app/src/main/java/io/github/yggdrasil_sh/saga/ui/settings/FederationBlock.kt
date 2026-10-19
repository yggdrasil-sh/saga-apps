package io.github.yggdrasil_sh.saga.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Einstellungen -> Konto: "In Adressbuechern verbundener Server erscheinen" (Saga 2.3, GET/PUT /api/account/federation,
 * wie Web und Linux). Nur sichtbar, wenn der Server mit anderen verbunden ist (federation true); aeltere Server: ausgeblendet.
 */
@Composable
fun FederationDirectoryBlock(repo: SagaRepository, modifier: Modifier = Modifier, reloadKey: Any? = null) {
    var on by remember { mutableStateOf<Boolean?>(null) }          // null = ausgeblendet
    LaunchedEffect(reloadKey) {
        on = try {
            val o = Json.parseToJsonElement(repo.api.get("api/account/federation")) as? JsonObject
            if ((o?.get("federation") as? JsonPrimitive)?.booleanOrNull == true) (o["directory"] as? JsonPrimitive)?.booleanOrNull ?: true else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val cur = on ?: return
    Column(modifier) {
        Text(
            stringResource(R.string.fed_directory_title), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.fed_directory_switch)) },
            supportingContent = { Text(stringResource(R.string.fed_directory_desc)) },
            trailingContent = { Switch(checked = cur, onCheckedChange = null) },
            modifier = Modifier.toggleable(value = cur, role = Role.Switch) { v ->
                on = v
                repo.launchAction(onError = { on = !v }) {
                    repo.api.request("PUT", "api/account/federation", buildJsonObject { put("directory", v) })
                }
            },
        )
    }
}
