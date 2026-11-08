package io.github.yggdrasil_sh.saga.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ChatFedHint
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatPerson
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.Contacts
import io.github.yggdrasil_sh.saga.chat.FedPerson
import io.github.yggdrasil_sh.saga.chat.NewChatLogic
import io.github.yggdrasil_sh.saga.chat.NewChatLogic.Mode
import io.github.yggdrasil_sh.saga.chat.PeopleGroup
import io.github.yggdrasil_sh.saga.chat.quietly
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.common.SagaLineEmptyState
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * CH-06 (Design-Runde 5): "Neuer Chat" als Vollbild-Dialog. Suchfeld oben ("Name eingeben"), darunter ohne Eingabe die
 * festen Eintraege "Neue Gruppe" (mehrere Personen auswaehlen) und "Gruppe aus deinen Teams", dann die Kontakte; Treffer
 * als Listeneintraege mit Avatar (ein Tipp oeffnet bzw. waehlt). Die Mindestlaenge steht nur als Leerzustand da.
 * Personen anderer Server (name@server) und Mehrfachauswahl wie bisher; "Neue Gruppe" hat oben "Erstellen".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewChatDialog(repo: SagaRepository, onDismiss: () -> Unit, onOpen: (Long) -> Unit) {
    val svc = repo.chat
    var mode by remember { mutableStateOf(Mode.PERSON) }
    var q by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    var groups by remember { mutableStateOf<List<PeopleGroup>>(emptyList()) }
    var remote by remember { mutableStateOf<List<FedPerson>>(emptyList()) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    val fedV2 by svc.fed.supported.collectAsState()
    val serverVersion by repo.serverVersion.collectAsState()
    val chats by svc.chats.collectAsState()
    var picked by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    // 2.3 (E5): Personen anderer Server in der neuen Gruppe (Heim = dieser Server, bei ihnen als Anfrage)
    var pickedRemote by remember { mutableStateOf<List<FedPerson>>(emptyList()) }
    var fedFor by remember { mutableStateOf("") }        // Suche, fuer die der name@server-Hinweis gilt
    /** 2.5: Name der neuen Gruppe (wie im Web als verschluesselte title-Nachricht direkt nach dem Anlegen) */
    var groupName by remember { mutableStateOf("") }
    val multi = mode == Mode.GROUP
    val focus = remember { FocusRequester() }
    LaunchedEffect(q) {
        delay(250)
        try {
            val r = svc.people(q)
            users = r.users
            groups = r.groups
            remote = r.remote
            fedFor = if (ChatFedHint.show(q, r.users.size + r.groups.size + r.remote.size)) q else ""
            loadedFor = q
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(mode) { quietly { focus.requestFocus() } }
    val back: () -> Unit = {
        if (mode != Mode.PERSON) { mode = Mode.PERSON; picked = emptyList(); pickedRemote = emptyList(); q = "" } else onDismiss()
    }
    val create: () -> Unit = {
        repo.launchAction {
            val c = if (pickedRemote.isEmpty()) svc.createFree(picked.map { it.username })
                else svc.fed.createGroup(picked.map { it.username }, pickedRemote.map { it.handle })
            // Name als title-Nachricht (scheitert sie, bleibt die Gruppe ohne Namen - umbenennen geht spaeter)
            val t = groupName.trim()
            if (t.isNotEmpty()) quietly {
                val ses = ChatSession(c.id, svc)
                ses.prepareSend()
                ses.rename(t)
            }
            onOpen(c.id)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(enabled = mode != Mode.PERSON) { back() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(when (mode) {
                            Mode.PERSON -> R.string.chat_new
                            Mode.GROUP -> R.string.r5_new_group
                            Mode.TEAM -> R.string.r5_team_group
                        }))
                    },
                    navigationIcon = {
                        IconButton(onClick = back) {
                            if (mode == Mode.PERSON) SagaIcon(SagaIcons.x, stringResource(R.string.action_close))
                            else SagaIcon(SagaIcons.arrowLeft, stringResource(R.string.r5_back))
                        }
                    },
                    actions = {
                        if (multi) SagaTextButton(onClick = create, enabled = NewChatLogic.canCreate(mode, picked.size, pickedRemote.size)) {
                            Text(stringResource(R.string.chat_create))
                        }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    label = { Text(stringResource(if (mode == Mode.TEAM) R.string.r5_team_search else R.string.r5_name_search)) },
                    leadingIcon = { SagaIcon(SagaIcons.search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
                )
                if (multi) OutlinedTextField(
                    value = groupName, onValueChange = { groupName = it.take(80) }, singleLine = true,
                    label = { Text(stringResource(R.string.std_group_name)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (multi && (picked.isNotEmpty() || pickedRemote.isNotEmpty())) {
                    // Nr. 56: gewaehlte Personen als Chips mit x
                    val rm = stringResource(R.string.chat_remove_picked)
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        picked.forEach { u ->
                            InputChip(
                                selected = false, onClick = { picked = picked.filter { it.username != u.username } },
                                label = { Text(u.name.ifEmpty { ChatLogic.shown(u) }, maxLines = 1) },
                                trailingIcon = { SagaIcon(SagaIcons.x, "$rm: ${u.name}", Modifier.size(16.dp)) },
                            )
                        }
                        pickedRemote.forEach { u ->
                            InputChip(
                                selected = false, onClick = { pickedRemote = pickedRemote.filter { it.handle != u.handle } },
                                label = { Text(u.shown, maxLines = 1) },
                                trailingIcon = { SagaIcon(SagaIcons.x, "$rm: ${u.shown}", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                val min = io.github.yggdrasil_sh.saga.chat.ChatLogic.peopleMin(serverVersion)
                val teams = if (mode == Mode.TEAM) NewChatLogic.teams(chats, groups, q) else emptyList()
                val hits = if (mode == Mode.TEAM) teams.size else users.size + remote.size + (if (multi) 0 else groups.size)
                val empty = NewChatLogic.empty(q, min, loadedFor, hits, fedFor.isNotEmpty() && fedFor == q)
                LazyColumn(Modifier.fillMaxSize()) {
                    if (NewChatLogic.showFixed(mode, q)) {
                        item(key = "fx-group") {
                            FixedEntry(SagaIcons.users, stringResource(R.string.r5_new_group), stringResource(R.string.r5_new_group_sub)) {
                                mode = Mode.GROUP; q = ""
                            }
                        }
                        item(key = "fx-team") {
                            FixedEntry(SagaIcons.users, stringResource(R.string.r5_team_group), stringResource(R.string.r5_team_group_sub)) {
                                mode = Mode.TEAM; q = ""
                            }
                        }
                        item(key = "fx-div") { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                    }
                    when (empty) {
                        NewChatLogic.Empty.TOO_SHORT -> item(key = "e-short") {
                            SagaLineEmptyState(stringResource(R.string.r5_min_chars, min), SagaIcons.search, compact = true)
                        }
                        NewChatLogic.Empty.NO_HITS -> item(key = "e-none") {
                            SagaLineEmptyState(stringResource(R.string.r5_no_hits), SagaIcons.search, compact = true, text = stringResource(R.string.r5_no_hits_hint))
                        }
                        NewChatLogic.Empty.NONE -> {}
                    }
                    if (mode == Mode.TEAM) {
                        if (q.isBlank() && teams.isEmpty()) item(key = "t-hint") {
                            SagaLineEmptyState(stringResource(R.string.r5_team_hint), SagaIcons.users, compact = true)
                        }
                        items(teams, key = { "t-$it" }) { g ->
                            ListItem(
                                headlineContent = { Text(g) },
                                supportingContent = { Text(stringResource(R.string.chat_kind_group)) },
                                leadingContent = { Avatar(g, size = 40, icon = SagaIcons.users) },
                                modifier = Modifier.clickable { repo.launchAction { onOpen(svc.createGroup(g).id) } },
                            )
                        }
                        return@LazyColumn
                    }
                    // 2.5 (contacts1): ohne Eingabe "Kontakte" (zuletzt zuerst); gone ausgegraut, Wischen = aus Kontakten entfernen
                    if (q.isBlank()) item(key = "contacts") {
                        Column {
                            ContactsBlock(repo, headerInset = 16.dp) { k ->
                                when (val p = Contacts.pick(k)) {
                                    is Contacts.Pick.Local ->
                                        if (multi) { if (picked.none { it.username == p.user }) picked = picked + Contacts.asPerson(k) }
                                        else repo.launchAction { onOpen(svc.createDm(p.user).id) }
                                    is Contacts.Pick.Remote ->
                                        if (multi) { if (pickedRemote.none { it.handle == p.handle }) pickedRemote = pickedRemote + Contacts.asFedPerson(k) }
                                        else repo.launchAction { onOpen(svc.fed.createDm(p.handle).id) }
                                }
                            }
                        }
                    }
                    if (fedFor.isNotEmpty() && fedFor == q) item(key = "fed") { Column(Modifier.padding(horizontal = 16.dp)) { FedChatHint(fedV2 == true) } }
                    if (q.isNotBlank()) items(users, key = { "u-" + it.username }) { u ->
                        val on = picked.any { it.username == u.username }
                        val mark = stringResource(R.string.chat_handle_mark)
                        val same = users.count { ChatLogic.shown(it) == ChatLogic.shown(u) } > 1
                        ListItem(
                            headlineContent = { Text(ChatLogic.shownMarked(u) { mark.format(it) }) },
                            // 2.4 (W4): nie den Anmeldenamen - sub (Gleichnamige) und der Hinweis, falls Chats fehlen
                            supportingContent = ChatLogic.subline(
                                ChatLogic.personSub(u.sub, same) { mark.format(it) },
                                if (u.ready) null else stringResource(R.string.chat_not_ready, ChatLogic.shownMarked(u) { mark.format(it) }),
                            )?.let { t -> { Text(t) } },
                            leadingContent = { Avatar(u.name, size = 40, avatar = u.avatar) },
                            trailingContent = if (multi) ({ Checkbox(checked = on, onCheckedChange = null) })
                                else if (u.contact) ({ ContactMore(repo, ChatLogic.shown(u), u.username) { users = users.filter { it.username != u.username } } }) else null,
                            modifier = Modifier.clickable {
                                if (multi) picked = if (on) picked.filter { it.username != u.username } else picked + u
                                else repo.launchAction { onOpen(svc.createDm(u.username).id) }
                            },
                        )
                    }
                    if (!multi && q.isNotBlank()) {
                        items(groups, key = { "g-" + it.name }) { g ->
                            ListItem(
                                headlineContent = { Text(g.name) },
                                supportingContent = { Text(stringResource(R.string.chat_kind_group)) },
                                leadingContent = { Avatar(g.name, size = 40, icon = SagaIcons.users) },
                                modifier = Modifier.clickable { repo.launchAction { onOpen(svc.createGroup(g.name).id) } },
                            )
                        }
                    }
                    // 2.3: Personen verbundener Server (Adressbuch) -> Direkt-Chat bzw. Gruppe ueber Servergrenzen (als Anfrage)
                    if (q.isNotBlank()) items(remote, key = { "r-" + it.handle }) { u ->
                        val on = pickedRemote.any { it.handle == u.handle }
                        FedPersonItem(
                            u, if (u.contact && !multi) stringResource(R.string.ct_person_on, u.host)
                                else stringResource(if (multi) R.string.fed_person_sub_group else R.string.fed_person_sub, u.host),
                            trailing = if (multi) ({ Checkbox(checked = on, onCheckedChange = null) })
                                else if (u.contact) ({ ContactMore(repo, u.shown, u.handle) { remote = remote.filter { it.handle != u.handle } } }) else null,
                        ) {
                            if (multi) pickedRemote = if (on) pickedRemote.filter { it.handle != u.handle } else pickedRemote + u
                            else repo.launchAction { onOpen(svc.fed.createDm(u.handle).id) }
                        }
                    }
                }
            }
        }
    }
}

/** Fester Eintrag oben in "Neuer Chat" (Symbol im Kreis, Titel fett, Untertitel) */
@Composable
private fun FixedEntry(icon: Int, title: String, sub: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(sub) },
        leadingContent = { Avatar(title, size = 40, icon = icon) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
