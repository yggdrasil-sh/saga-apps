package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.TargetUser
import io.github.yggdrasil_sh.saga.data.dbPeople
import io.github.yggdrasil_sh.saga.db.DbPerson
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 2.5 E2 (mention1): Person erwaehnen. Auswahl aus GET /api/notes/{id}/people?q= (mit Zugriff) - ref wird data-u,
 * name der Text. Darunter Personen ohne Zugriff aus der Teilen-Suche: "Seite teilen und erwaehnen?" -> PUT shares (Lesen),
 * dann einfuegen. Nie Anmeldenamen anzeigen (Teilen-Suche: Name + sub).
 */
@Composable
fun MentionPicker(ed: PageEditor, repo: SagaRepository, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var people by remember { mutableStateOf<List<DbPerson>>(emptyList()) }
    var others by remember { mutableStateOf<List<TargetUser>>(emptyList()) }
    var confirm by remember { mutableStateOf<TargetUser?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canShare = ed.caps.canShare
    LaunchedEffect(q) {
        delay(if (q.isEmpty()) 0 else 250)
        try {
            people = repo.dbPeople(ed.id, q)
            others = if (canShare && q.isNotBlank()) {
                val have = people.map { it.name }.toSet()
                repo.shareTargets(q).users.filter { it.username.isNotBlank() && it.name !in have }.take(8)
            } else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.e2_mention_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it.take(80) }, singleLine = true, label = { Text(stringResource(R.string.e2_mention_search)) },
                    modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(people, key = { "p-" + it.ref }) { p ->
                        PersonRow(p.name, p.avatar, if (p.me) stringResource(R.string.e2_mention_me) else null) { onPick(p.ref, p.name) }
                    }
                    if (others.isNotEmpty()) item(key = "h-others") {
                        Text(stringResource(R.string.e2_mention_no_access), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    items(others, key = { "o-" + it.username }) { u ->
                        PersonRow(u.name, u.avatar, u.sub) { confirm = u }
                    }
                }
                if (people.isEmpty() && others.isEmpty()) {
                    Text(stringResource(R.string.e2_mention_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    confirm?.let { u ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.e2_mention_share_title)) },
            text = { Text(stringResource(R.string.e2_mention_share_text, u.name)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    repo.launchAction(onError = { busy = false }, onSuccess = { busy = false }) {
                        repo.putShare(ed.id, "user", u.username, "view")
                        // Kennung (ref) der Person: nach dem Teilen steht sie in der Liste mit Zugriff
                        val ref = repo.dbPeople(ed.id, u.name).firstOrNull { it.name == u.name }?.ref ?: u.username
                        confirm = null
                        onPick(ref, u.name)
                    }
                }) { Text(stringResource(R.string.e2_mention_share_do)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PersonRow(name: String, avatar: String?, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, size = 28, avatar = avatar)
        Column(Modifier.padding(start = 10.dp)) {
            Text(name, fontWeight = FontWeight.Medium)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
