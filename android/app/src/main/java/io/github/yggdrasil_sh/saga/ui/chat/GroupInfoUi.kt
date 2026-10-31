package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatContact
import io.github.yggdrasil_sh.saga.chat.ChatFedHint
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatSummary
import io.github.yggdrasil_sh.saga.chat.Contacts
import io.github.yggdrasil_sh.saga.chat.FedPerson
import io.github.yggdrasil_sh.saga.chat.GroupInfo
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Saga 2.5 (chatadm1): Gruppeninfo als Vollbild-Dialog - Name (bearbeitbar bei can_rename), Mitglieder mit Avatar,
 * Name, Handle (nie Anmeldename), "Admin"/"Du"; bei can_manage "Mitglied hinzufügen" (Kontakte/Personensuche) und je
 * Mitglied Admin vergeben/entziehen (can_admin) bzw. entfernen; unten Stummschalten, Archivieren, Verlassen.
 * group/page nur lesend mit Hinweis. Live: die Sitzung laedt bei chat.changed neu (summary ist State).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoDialog(
    repo: SagaRepository,
    s: ChatSession,
    onDismiss: () -> Unit,
    onSafety: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val c = s.summary ?: return
    val r = GroupInfo.rights(c, repo.chat.groupInfoOn)
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ChatPerson?>(null) }
    val res = LocalResources.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.gi_title)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) } },
                )
            },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item(key = "head") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(s.name, size = 56, icon = R.drawable.ic_group)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(s.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                            Text(
                                when (c.kind) {
                                    "free" -> pluralStringResource(R.plurals.gi_kind_free, c.memberCount, c.memberCount)
                                    "group" -> pluralStringResource(R.plurals.gi_kind_group, c.memberCount, c.memberCount, c.group.orEmpty())
                                    else -> pluralStringResource(R.plurals.gi_kind_page, c.memberCount, c.memberCount)
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (c.kind == "free" && r.rename) IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Filled.Edit, stringResource(R.string.gi_rename))
                        }
                    }
                }
                fun hint(key: String, t: String) = item(key = key) {
                    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                if (c.kind == "free" && r.rename) hint("h-name", res.getString(R.string.gi_name_note))
                if (r.readOnly) hint("h-ro", res.getString(R.string.gi_readonly))
                if (c.remote) hint("h-home", if (c.homeSelf) res.getString(R.string.fed_members_home_self) else res.getString(R.string.fed_members_home_other, c.home ?: "?"))
                if (c.readonly) hint("h-lock", "🔒 " + (c.readonlyReason ?: ""))
                item(key = "mh") {
                    Text(
                        pluralStringResource(R.plurals.gi_members_n, c.memberCount, c.memberCount),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                if (c.kind == "free" && r.manage) {
                    item(key = "add") {
                        TextButton(onClick = { adding = !adding }, modifier = Modifier.padding(start = 8.dp)) { Text("+ " + stringResource(R.string.gi_add)) }
                    }
                    if (adding) addMemberItems(repo, s, c) { adding = false }
                }
                items(GroupInfo.sorted(c.members), key = { "m-" + it.username }) { m -> MemberRow(repo, c, m, r, s, onSafety) { removing = m } }
                item(key = "foot") {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { repo.launchAction { s.setMuted(!c.muted) } }) {
                            Text(stringResource(if (c.muted) R.string.chat_unmute else R.string.chat_mute))
                        }
                        if (s.canArchive) {
                            val arch = c.archived == true
                            OutlinedButton(onClick = { repo.launchAction { s.setArchived(!arch) } }) {
                                Text(stringResource(if (arch) R.string.chatdel_unarchive else R.string.chatdel_archive))
                            }
                        }
                        if (c.kind == "free") OutlinedButton(onClick = onLeave) {
                            Text(stringResource(R.string.chat_leave), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    if (renaming) RenameDialog(repo, s) { renaming = false }
    removing?.let { m ->
        val n = ChatLogic.shown(m)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.gi_remove_title, n)) },
            text = { Text(stringResource(R.string.gi_remove_text, n)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    repo.launchAction { s.removeMember(m.username); repo.notify(UiText.Res(R.string.gi_removed, n)) }
                }) { Text(stringResource(R.string.chat_member_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun MemberRow(repo: SagaRepository, c: ChatSummary, m: ChatPerson, r: GroupInfo.Rights, s: ChatSession, onSafety: (String) -> Unit, onRemove: () -> Unit) {
    val acts = GroupInfo.actions(c, m, r)
    var menu by remember { mutableStateOf(false) }
    val onHost = stringResource(R.string.fed_on_host, "%s")
    val sub = GroupInfo.subline(m, c.myHandle, c.remote) { h -> onHost.replace("%s", h) }
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ChatLogic.shown(m), fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (m.me) Chip(stringResource(R.string.gi_you))
                if (m.admin) Chip(stringResource(R.string.gi_admin), strong = true)
            }
        },
        supportingContent = if (sub.isNotEmpty()) ({ Text(sub, maxLines = 1) }) else null,
        leadingContent = { Avatar(m.name, size = 40, avatar = m.avatar) },
        trailingContent = if (acts.isEmpty()) null else ({
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.gi_actions, ChatLogic.shown(m))) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    acts.forEach { a ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (a) {
                                            GroupInfo.Act.SAFETY -> R.string.chat_safety_number
                                            GroupInfo.Act.MAKE_ADMIN -> R.string.gi_make_admin
                                            GroupInfo.Act.DROP_ADMIN -> R.string.gi_drop_admin
                                            GroupInfo.Act.REMOVE -> R.string.gi_remove
                                        },
                                    ),
                                    color = if (a == GroupInfo.Act.REMOVE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                menu = false
                                when (a) {
                                    GroupInfo.Act.SAFETY -> onSafety(m.username)
                                    GroupInfo.Act.MAKE_ADMIN -> repo.launchAction {
                                        s.setAdmin(m, true)
                                        repo.notify(if (m.me) UiText.Res(R.string.gi_sys_you_now_admin) else UiText.Res(R.string.gi_sys_now_admin, ChatLogic.shown(m)))
                                    }
                                    GroupInfo.Act.DROP_ADMIN -> repo.launchAction { s.setAdmin(m, false); repo.notify(UiText.Res(R.string.gi_admin_dropped)) }
                                    GroupInfo.Act.REMOVE -> onRemove()
                                }
                            },
                        )
                    }
                }
            }
        }),
    )
}

@Composable
private fun Chip(t: String, strong: Boolean = false) {
    Text(
        t, style = MaterialTheme.typography.labelSmall,
        color = if (strong) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** "Mitglied hinzufügen" in der Gruppeninfo: ohne Eingabe Kontakte (contacts1), mit Eingabe die Personensuche */
private fun LazyListScope.addMemberItems(repo: SagaRepository, s: ChatSession, c: ChatSummary, done: () -> Unit) {
    item(key = "add-box") { AddMemberBox(repo, s, c, done) }
}

@Composable
private fun AddMemberBox(repo: SagaRepository, s: ChatSession, c: ChatSummary, done: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    var foundRemote by remember { mutableStateOf<List<FedPerson>>(emptyList()) }
    var hasRemoteHits by remember { mutableStateOf(false) }
    var fedFor by remember { mutableStateOf("") }
    val fedV2 by repo.chat.fed.supported.collectAsState()
    LaunchedEffect(q) {
        if (q.isBlank()) { found = emptyList(); foundRemote = emptyList(); hasRemoteHits = false; return@LaunchedEffect }
        delay(250)
        try {
            val r = repo.chat.people(q)
            found = r.users.filter { u -> !ChatLogic.isMember(u.username, c.members) }
            hasRemoteHits = r.remote.isNotEmpty()
            foundRemote = if (c.isFed) r.remote.filter { u -> c.members.none { it.handle == u.handle } } else emptyList()
            fedFor = if (ChatFedHint.show(q, r.users.size + foundRemote.size)) q else ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    val add: (ChatPerson) -> Unit = { u -> repo.launchAction { s.addMember(u.username); q = ""; done() } }
    val addRemote: (String) -> Unit = { h -> repo.launchAction { s.addMemberHandle(h); q = ""; done() } }
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, label = { Text(stringResource(R.string.chat_people_search)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (q.isBlank()) {
            ContactsBlock(repo, keep = { Contacts.forAdd(it, c) }) { k ->
                when (val p = Contacts.pick(k)) {
                    is Contacts.Pick.Local -> repo.launchAction { s.addMember(p.user); done() }
                    is Contacts.Pick.Remote -> addRemote(p.handle)
                }
            }
        } else {
            if (fedFor.isNotEmpty() && fedFor == q) FedChatHint(fedV2 == true)
            if (!c.isFed && hasRemoteHits) Text(
                stringResource(R.string.gi_remote_only_new), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp),
            )
            val mark = stringResource(R.string.chat_handle_mark)
            found.forEach { u ->
                val same = found.count { ChatLogic.shown(it) == ChatLogic.shown(u) } > 1
                PersonHit(repo, u.name, ChatLogic.shownMarked(u) { mark.format(it) }, ChatLogic.personSub(u.sub, same) { mark.format(it) }, u.avatar,
                    contactRef = if (u.contact) u.username else null) { add(u) }
            }
            foundRemote.forEach { u ->
                PersonHit(repo, u.name, u.shown, stringResource(R.string.ct_person_on, u.host), u.avatar, contactRef = if (u.contact) u.handle else null) { addRemote(u.handle) }
            }
        }
    }
}

/**
 * Kontaktliste ("Kontakte", zuletzt zuerst, ~30): gone ausgegraut und nicht waehlbar; Wischen bzw. Menue "Aus Kontakten
 * entfernen". Laedt bei chat.contacts (anderes Geraet) neu. Ohne contacts1 (aelterer Server) nichts.
 */
@Composable
fun ContactsBlock(repo: SagaRepository, keep: (List<ChatContact>) -> List<ChatContact> = { it }, onPick: (ChatContact) -> Unit) {
    if (!repo.chat.contactsOn) return
    val tick by repo.chat.contactsTick.collectAsState()
    var list by remember { mutableStateOf<List<ChatContact>>(emptyList()) }
    LaunchedEffect(tick) {
        list = try {
            repo.chat.contacts()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    val shown = keep(list)
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.ct_contacts), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        shown.forEach { k ->
            ContactRow(repo, k, onRemoved = { list = Contacts.applyHidden(list, k.ref, true) }) { onPick(k) }
        }
    }
}

/** Ein Kontakt: Wischen nach links = "Aus Kontakten entfernen" (gone: nicht waehlbar, ausgegraut) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactRow(repo: SagaRepository, k: ChatContact, onRemoved: () -> Unit, onPick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState()
    val remove: () -> Unit = {
        repo.launchAction { repo.chat.removeContact(k.ref); onRemoved(); repo.notify(UiText.Res(R.string.ct_removed, k.name)) }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = {
            scope.launch { state.snapTo(SwipeToDismissBoxValue.Settled) }
            remove()
        },
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
            ) { Text(stringResource(R.string.ct_remove), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge) }
        },
    ) {
        val sub = Contacts.subline(k)?.resolve()
        val dim = !Contacts.pickable(k)
        val col = if (dim) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
        PersonRowBase(
            name = k.name, shown = k.name, sub = sub, avatar = k.avatar, color = col, enabled = !dim,
            menu = { MoreRemove(k.name, remove) }, onClick = onPick,
            description = if (dim) stringResource(R.string.ct_unreachable) else null,
        )
    }
}

/** Treffer der Personensuche; Kontakte (contact:true) mit Menue "Aus Kontakten entfernen" */
@Composable
fun PersonHit(repo: SagaRepository, name: String, shown: String, sub: String?, avatar: String?, contactRef: String?, onClick: () -> Unit) {
    var gone by remember(contactRef) { mutableStateOf(false) }
    if (gone) return
    PersonRowBase(
        name, shown, sub, avatar, MaterialTheme.colorScheme.onSurface, true,
        menu = if (contactRef != null) ({
            MoreRemove(shown) { repo.launchAction { repo.chat.removeContact(contactRef); gone = true; repo.notify(UiText.Res(R.string.ct_removed, shown)) } }
        }) else null,
        onClick = onClick,
    )
}

@Composable
private fun PersonRowBase(
    name: String, shown: String, sub: String?, avatar: String?, color: androidx.compose.ui.graphics.Color, enabled: Boolean,
    menu: (@Composable () -> Unit)?, onClick: () -> Unit, description: String? = null,
) {
    ListItem(
        headlineContent = { Text(shown, color = color, maxLines = 1) },
        supportingContent = (description ?: sub)?.let { t -> { Text(listOfNotNull(sub, description.takeIf { sub != null }).joinToString(" · ").ifEmpty { t }, maxLines = 1) } },
        leadingContent = { Avatar(name, size = 32, avatar = avatar) },
        trailingContent = menu,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun MoreRemove(name: String, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.gi_actions, name)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ct_remove), color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

/** Menue "⋯" an einem Kontakt-Treffer der Personensuche: "Aus Kontakten entfernen" */
@Composable
fun ContactMore(repo: SagaRepository, name: String, ref: String, onRemoved: () -> Unit) {
    MoreRemove(name) { repo.launchAction { repo.chat.removeContact(ref); onRemoved(); repo.notify(UiText.Res(R.string.ct_removed, name)) } }
}
