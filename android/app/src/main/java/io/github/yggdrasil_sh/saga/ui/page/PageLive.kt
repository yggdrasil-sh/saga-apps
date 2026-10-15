package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.live.LiveEvent
import io.github.yggdrasil_sh.saga.ui.comments.CommentsModel
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Anwesende einer Seite (POST /api/presence, Live "presence") */
data class PresenceUser(val username: String, val name: String, val editing: Boolean, val me: Boolean)

/** Reine Regeln fuer fremde Aenderungen an der offenen Seite (Tests) */
object PageLiveLogic {
    enum class Action { NONE, RELOAD_SILENT, SHOW_BANNER, GONE }

    /** note.changed: neuere Revision ohne eigene ungespeicherte Aenderungen -> still neu laden; sonst Hinweis */
    fun onChanged(theirRev: Int?, ownRev: Int?, dirty: Boolean, gone: Boolean): Action = when {
        gone -> Action.GONE
        theirRev == null || ownRev == null || theirRev <= ownRev -> Action.NONE
        dirty -> Action.SHOW_BANNER
        else -> Action.RELOAD_SILENT
    }

    /** editing:true nur in den ersten 30 s nach einer Eingabe und nur mit Rolle owner/edit */
    fun editing(now: Long, lastInput: Long, canEdit: Boolean): Boolean = canEdit && lastInput > 0 && now - lastInput < 30_000

    fun users(arr: JsonArray?): List<PresenceUser> = arr?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        PresenceUser(
            username = (o["username"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            editing = (o["editing"] as? JsonPrimitive)?.booleanOrNull ?: false,
            me = (o["me"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }.orEmpty()
}

/** Live-Zustand der offenen Seite: Anwesende, Hinweis auf fremde Aenderung, Statuszeile nach stillem Neuladen */
@Stable
class PageLiveState {
    var users by mutableStateOf<List<PresenceUser>>(emptyList())
    /** "{name} hat diese Seite geändert" + "Neu laden" (eigene ungespeicherte Aenderungen) */
    var changedBy by mutableStateOf<String?>(null)
    /** "Aktualisiert – geändert von {name}" nach stillem Neuladen */
    var updatedBy by mutableStateOf<String?>(null)
    val others: List<PresenceUser> get() = users.filter { !it.me }
}

private fun byName(o: JsonObject?): String = ((o?.get("by") as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * Anwesenheit senden (alle ~20 s, nur im Vordergrund, nicht fuer Seiten anderer Instanzen) und Live-Ereignisse der Seite
 * verarbeiten (note.changed, presence, comments.changed). Beim Verlassen {leave:true}.
 */
@Composable
fun rememberPageLive(id: Long, repo: SagaRepository, ed: PageEditor, comments: CommentsModel, appScope: CoroutineScope): PageLiveState {
    val st = remember(ed) { PageLiveState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sendPresence = id > 0

    suspend fun reloadSilently(name: String) {
        if (ed.dirty) {
            st.changedBy = name
            return
        }
        ed.load()
        st.changedBy = null
        st.updatedBy = name.ifEmpty { null }
    }

    if (sendPresence) {
        LaunchedEffect(ed, ed.fetched) {
            if (!ed.fetched) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    try {
                        val body = buildJsonObject {
                            put("note_id", id)
                            put("editing", PageLiveLogic.editing(System.currentTimeMillis(), ed.lastInputAt, ed.caps.canEditContent))
                        }
                        val r = repo.api.post("api/presence", body)
                        val o = kotlinx.serialization.json.Json.parseToJsonElement(r) as? JsonObject
                        if (o != null && (o["skipped"] as? JsonPrimitive)?.booleanOrNull != true) {
                            st.users = PageLiveLogic.users(o["users"] as? JsonArray)
                            val rev = (o["rev"] as? JsonPrimitive)?.intOrNull
                            when (PageLiveLogic.onChanged(rev, ed.currentRev, ed.dirty, false)) {
                                PageLiveLogic.Action.RELOAD_SILENT -> reloadSilently(byName(o))
                                PageLiveLogic.Action.SHOW_BANNER -> st.changedBy = byName(o)
                                else -> {}
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 404 (kein Zugriff) oder offline: beim naechsten Mal wieder
                    }
                    delay(20_000)
                }
            }
        }
        DisposableEffect(ed) {
            onDispose {
                appScope.launch {
                    try {
                        repo.api.post("api/presence", buildJsonObject { put("note_id", id); put("leave", true) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        }
    } else {
        // Seiten anderer Instanzen: kein note.changed - beim Zurueckkehren in die Seite neu laden (Konflikt-Schutz ueber rev)
        LaunchedEffect(ed) {
            var first = true
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!first && ed.fetched && !ed.dirty) ed.load()
                first = false
            }
        }
    }

    LaunchedEffect(ed) {
        repo.live.events.collect { ev: LiveEvent ->
            when (ev.type) {
                "note.changed" -> if (ev.long("id") == id) {
                    val name = byName(ev.data)
                    when (PageLiveLogic.onChanged(ev.int("rev"), ed.currentRev, ed.dirty, ev.bool("gone"))) {
                        PageLiveLogic.Action.GONE -> ed.load()
                        PageLiveLogic.Action.RELOAD_SILENT -> reloadSilently(name)
                        PageLiveLogic.Action.SHOW_BANNER -> st.changedBy = name
                        PageLiveLogic.Action.NONE -> {}
                    }
                }
                "presence" -> if (ev.long("note_id") == id) st.users = PageLiveLogic.users(ev.data["users"] as? JsonArray)
                "comments.changed" -> if (ev.long("note_id") == id) {
                    try {
                        comments.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                "reset" -> if (!ed.dirty) ed.load()
                // Freigaben geaendert (auch durch andere Instanzen): fremde Seite neu oeffnen (Rolle/Zugriff)
                "shared.changed" -> if (ed.caps.foreign && !ed.dirty) ed.load()
            }
        }
    }
    return st
}

/** Kopfzeile: Anfangsbuchstaben der anderen Anwesenden (hoechstens 3, dann "+n") */
@Composable
fun PresenceAvatars(st: PageLiveState) {
    val others = st.others
    if (others.isEmpty()) return
    val desc = stringResource(R.string.presence_group) + ": " + others.joinToString(", ") { it.name }
    Row(Modifier.padding(end = 4.dp).semantics(mergeDescendants = true) { contentDescription = desc }, verticalAlignment = Alignment.CenterVertically) {
        others.take(3).forEachIndexed { i, u ->
            Box(Modifier.offset(x = (-6 * i).dp)) { Avatar(u.name, size = 26) }
        }
        if (others.size > 3) Text("+${others.size - 3}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Unter der Kopfzeile: wer ist hier / bearbeitet gerade; Hinweis auf fremde Aenderung mit "Neu laden" */
@Composable
fun PageLiveBanner(st: PageLiveState, onReload: () -> Unit) {
    st.changedBy?.let { name ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (name.isNotEmpty()) stringResource(R.string.live_changed_by, name) else stringResource(R.string.live_changed),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onReload) { Text(stringResource(R.string.live_reload)) }
            }
        }
        HorizontalDivider()
    }
}

/** Seitenkopf: "{name} ist auch hier" / "{name} bearbeitet gerade", "Aktualisiert – geändert von {name}" */
@Composable
fun PresenceLine(st: PageLiveState) {
    val others = st.others
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (others.isNotEmpty()) {
            val editing = others.firstOrNull { it.editing }
            val u = editing ?: others.first()
            val more = others.size - 1
            // wie Apple: kleiner Avatar + Sekundaerfarbe (Palette), kein Blau
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                io.github.yggdrasil_sh.saga.ui.notifications.Avatar(u.name, size = 20)
                Text(
                    (if (editing != null) stringResource(R.string.presence_editing, u.name) else stringResource(R.string.presence_here, u.name)) +
                        if (more > 0) " (+$more)" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        st.updatedBy?.let {
            Text(
                stringResource(R.string.live_updated_by, it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
