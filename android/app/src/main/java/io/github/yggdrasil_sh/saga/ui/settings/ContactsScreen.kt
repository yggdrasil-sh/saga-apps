package io.github.yggdrasil_sh.saga.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.BookContact
import io.github.yggdrasil_sh.saga.chat.ContactBook
import io.github.yggdrasil_sh.saga.chat.ContactBookLogic
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.chat.ChatAvatar
import io.github.yggdrasil_sh.saga.ui.common.Chrome
import io.github.yggdrasil_sh.saga.ui.common.SagaTopBar
import io.github.yggdrasil_sh.saga.ui.nav.AppNavigator
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.theme.SagaButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.sagaSegment
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 2.6 (Bugs-DB 2027a-6): Einstellungen -> Kontakte (Adressbuch, nur mit contacts2) nach docs/APP-API.md "Kontakte".
 * Liste mit Suche und Landesvorwahl, Detail (feste Saga-Zeile mit "Nachricht", Nummern per ACTION_DIAL, Mails per
 * mailto:), Bearbeiten/Neu als Vollbild-Dialog (ContactEditDialog, auch aus dem Kontakt-Menue der Personensuche),
 * Loeschen mit Rueckfrage. Live contacts.changed laedt neu. Texte nur als Text (nie HTML).
 */
@Composable
fun ContactsScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome) {
    ContactsMemory.forSignIn(repo.signInCount.collectAsState().value)
    val book = repo.chat.book
    val tick by book.tick.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(ContactsMemory.list) }
    var total by remember { mutableStateOf(ContactsMemory.total) }
    var loading by remember { mutableStateOf(list == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cc by remember { mutableStateOf(ContactsMemory.cc) }
    var ccLoaded by remember { mutableStateOf(ContactsMemory.ccLoaded) }
    var ccEdit by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val suggest = remember { ContactRegion.suggest(context) }
    LaunchedEffect(q, tick) {
        if (q.isNotEmpty()) delay(250)
        try {
            val r = book.list(q)
            list = r.contacts
            total = r.total
            if (q.isEmpty()) {
                ContactsMemory.list = r.contacts
                ContactsMemory.total = r.total
            }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = repo.friendly(e).asString(context)
        }
        loading = false
    }
    LaunchedEffect(tick) {
        try {
            cc = book.cc()
            ccLoaded = true
            ContactsMemory.cc = cc
            ContactsMemory.ccLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    Scaffold(
        topBar = {
            SagaTopBar(stringResource(R.string.cb_title), nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) {
                IconButton(onClick = { creating = true }) { SagaIcon(SagaIcons.userPlus, stringResource(R.string.cb_new)) }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            item(key = "hint") { Hint(stringResource(R.string.cb_hint_top), w.padding(top = 8.dp)) }
            item(key = "search") {
                val desc = stringResource(R.string.cb_search)
                OutlinedTextField(
                    value = q, onValueChange = { q = it.take(100) }, singleLine = true,
                    label = { Text(desc) },
                    leadingIcon = { SagaIcon(SagaIcons.search, null) },
                    trailingIcon = if (q.isNotEmpty()) ({ IconButton(onClick = { q = "" }) { SagaIcon(SagaIcons.x, stringResource(R.string.action_clear_search)) } }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = w.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // Landesvorwahl (Konto-Einstellung): Vorschlag aus der Region des Geraets, solange keine gesetzt ist
            if (q.isEmpty() && ccLoaded) {
                val proposal = suggest?.takeIf { cc == null }
                item(key = "cc") {
                    Column(w.sagaSegment(0, 1)) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.cb_cc_title)) },
                            supportingContent = { Text(if (cc != null) stringResource(R.string.cb_cc_value, cc!!) else stringResource(R.string.cb_cc_none)) },
                            leadingContent = { SagaIcon(SagaIcons.globe, null) },
                            trailingContent = { SagaIcon(SagaIcons.chevronRight, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { ccEdit = true },
                        )
                        if (proposal != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.cb_cc_suggest, proposal), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                                )
                                SagaTextButton(onClick = {
                                    scope.launch {
                                        try {
                                            cc = book.setCc(proposal)
                                            ContactsMemory.cc = cc
                                            repo.notify(UiText.Res(R.string.cb_cc_saved))
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            repo.notify(repo.friendly(e))
                                        }
                                    }
                                }) { Text(stringResource(R.string.cb_cc_apply)) }
                            }
                        }
                    }
                }
            }
            val l = list
            item(key = "head") {
                Heading(
                    if (l != null && q.isEmpty()) pluralStringResource(R.plurals.cb_count, total, total) else stringResource(R.string.cb_title), w,
                )
            }
            when {
                loading && l == null -> item(key = "loading") {
                    Box(w.padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                error != null && l == null -> item(key = "err") { Msg(error!!, true, w) }
                l.isNullOrEmpty() -> item(key = "empty") {
                    Hint(stringResource(if (q.isEmpty()) R.string.cb_empty else R.string.cb_no_hits), w.padding(top = 8.dp))
                }
                else -> {
                    l.forEachIndexed { i, c ->
                        item(key = "c-" + c.id) {
                            ContactListRow(c, w.sagaSegment(i, l.size), { v -> ContactRegion.pretty(context, v) }) { nav.push(Dest.Contact(c.id)) }
                        }
                    }
                    if (l.size < total) item(key = "more") {
                        SagaTextButton(onClick = {
                            scope.launch {
                                try {
                                    val r = book.list(q, offset = l.size)
                                    list = (l + r.contacts).distinctBy { it.id }
                                    total = r.total
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    repo.notify(repo.friendly(e))
                                }
                            }
                        }, modifier = w.padding(horizontal = 16.dp)) { Text(stringResource(R.string.cb_more)) }
                    }
                }
            }
            if (error != null && l != null) item(key = "err2") { Msg(error!!, true, w) }
            item(key = "new") {
                SagaTextButton(onClick = { creating = true }, modifier = w.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp)) {
                    SagaIcon(SagaIcons.plus, null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.cb_new))
                }
            }
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
    if (ccEdit) CcDialog(repo, book, cc, suggest, onDismiss = { ccEdit = false }) { cc = it; ContactsMemory.cc = it }
    if (creating) ContactEditDialog(repo, null, ContactBookLogic.ccForRequest(cc, ccLoaded, suggest), onDismiss = { creating = false }, onOpen = { creating = false; nav.push(Dest.Contact(it)) }) { c ->
        creating = false
        list = ContactBookLogic.applied(list.orEmpty(), c)
        nav.push(Dest.Contact(c.id))
    }
}

/** Zuletzt geladene Liste (beim Zurueckkehren sofort zeigen) */
internal object ContactsMemory {
    var list: List<BookContact>? = null
    var total: Int = 0
    var cc: String? = null
    var ccLoaded = false
    /** Anmeldung, zu der die Liste gehoert (anderes Konto: nichts Altes zeigen) */
    var key = -1

    fun forSignIn(n: Int) {
        if (key == n) return
        clear()
        key = n
    }

    fun clear() {
        list = null
        total = 0
        cc = null
        ccLoaded = false
    }
}

@Composable
private fun ContactListRow(c: BookContact, modifier: Modifier, phone: (String) -> String, onClick: () -> Unit) {
    val gone = c.saga?.gone == true
    ListItem(
        headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = ContactBookLogic.listSub(c, phone)?.let { s -> { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { ChatAvatar(c.name, 40.dp, avatar = c.saga?.avatar?.takeIf { !gone }, icon = if (c.saga == null && c.name.isBlank()) SagaIcons.user else null) },
        trailingContent = { SagaIcon(SagaIcons.chevronRight, null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (gone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
    )
}

/** Landesvorwahl setzen/entfernen (PUT /api/contacts/settings) */
@Composable
private fun CcDialog(repo: SagaRepository, book: ContactBook, cur: String?, suggest: String?, onDismiss: () -> Unit, onSaved: (String?) -> Unit) {
    var text by remember { mutableStateOf(cur ?: suggest.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val parsed = ContactBookLogic.ccOf(text)
    val bad = text.isNotBlank() && parsed == null
    val sup = if (bad) stringResource(R.string.cb_cc_bad) else err
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cb_cc_title)) },
        text = {
            Column {
                Text(stringResource(R.string.cb_cc_dialog_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text, onValueChange = { t -> text = t.filter { it.isDigit() || it == '+' }.take(4) }, singleLine = true,
                    prefix = if (!text.startsWith("+")) ({ Text("+") }) else null,
                    isError = bad,
                    supportingText = sup?.let { m -> { Text(m) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (suggest != null && suggest != parsed) {
                    SagaTextButton(onClick = { text = suggest }) { Text(stringResource(R.string.cb_cc_suggest, suggest)) }
                }
            }
        },
        confirmButton = {
            SagaTextButton(enabled = !busy && !bad, onClick = {
                busy = true
                scope.launch {
                    try {
                        onSaved(book.setCc(if (text.isBlank()) null else parsed))
                        repo.notify(UiText.Res(R.string.cb_cc_saved))
                        onDismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        err = repo.friendly(e).asString(context)
                    }
                    busy = false
                }
            }) { Text(stringResource(R.string.cb_save)) }
        },
        dismissButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ------------------------------------------------------------------------------------------------ Detail

/** Ein Kontakt: Kopf, feste Saga-Zeile mit "Nachricht", Nummern (Anrufen), Mails, Anrede, Grussform, Loeschen */
@Composable
fun ContactDetailScreen(repo: SagaRepository, nav: AppNavigator, chrome: Chrome, id: Long) {
    ContactsMemory.forSignIn(repo.signInCount.collectAsState().value)
    val book = repo.chat.book
    val tick by book.tick.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var c by remember(id) { mutableStateOf(ContactsMemory.list?.firstOrNull { it.id == id }) }
    var missing by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (ContactsMemory.ccLoaded) return@LaunchedEffect
        try {
            ContactsMemory.cc = book.cc()
            ContactsMemory.ccLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(id, tick) {
        if (book.lastChanged == -id) {
            missing = true
            return@LaunchedEffect
        }
        try {
            val r = book.one(id)
            if (r == null) missing = true else c = r
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = repo.friendly(e).asString(context)
        }
    }
    val cur = c
    Scaffold(
        topBar = {
            SagaTopBar(cur?.name.orEmpty(), nav, chrome, contentMaxWidth = io.github.yggdrasil_sh.saga.ui.common.TopBarAlign.CONTENT) {
                if (cur != null && !missing) {
                    IconButton(onClick = { editing = true }) { SagaIcon(SagaIcons.edit, stringResource(R.string.cb_edit)) }
                    Box {
                        IconButton(onClick = { menu = true }) { SagaIcon(SagaIcons.moreVert, stringResource(R.string.gi_actions, cur.name)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cb_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { SagaIcon(SagaIcons.trash, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
            if (missing) {
                item(key = "missing") { Msg(stringResource(R.string.cb_not_found), false, w.padding(top = 16.dp)) }
                return@LazyColumn
            }
            if (cur == null) {
                item(key = "load") {
                    if (error != null) Msg(error!!, true, w) else Box(w.padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                return@LazyColumn
            }
            item(key = "head") {
                Column(w.padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ChatAvatar(cur.name, 72.dp, avatar = cur.saga?.avatar?.takeIf { cur.saga.gone != true })
                    Text(
                        cur.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp).semantics { heading() },
                    )
                    val sub = when {
                        cur.saga == null -> stringResource(R.string.cb_manual)
                        cur.ownName != null -> stringResource(R.string.cb_own_name_set)
                        else -> null
                    }
                    sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            // feste Saga-Adresse (aus einem gemeinsamen Chat)
            cur.saga?.let { s ->
                item(key = "saga-head") { Heading(stringResource(R.string.cb_saga), w) }
                item(key = "saga") {
                    val where = when {
                        s.gone && s.remote -> stringResource(R.string.ct_server_gone, s.host ?: s.sub)
                        s.gone -> stringResource(R.string.ct_account_gone)
                        s.remote -> stringResource(R.string.ct_person_on, s.host ?: s.sub)
                        else -> stringResource(R.string.cb_saga_from_chat)
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.cb_saga_person)) },
                        supportingContent = { Text(where) },
                        leadingContent = { SagaIcon(SagaIcons.chats, null) },
                        trailingContent = if (ContactBookLogic.canMessage(cur)) ({
                            SagaButton(onClick = {
                                repo.launchAction {
                                    val chat = if (s.remote) repo.chat.fed.createDm(s.ref) else repo.chat.createDm(s.ref)
                                    nav.push(Dest.Chat(chat.id))
                                }
                            }) { Text(stringResource(R.string.cb_message)) }
                        }) else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = w.sagaSegment(0, 1).heightIn(min = 56.dp),
                    )
                }
            }
            val phones = cur.phones
            if (phones.isNotEmpty()) {
                item(key = "ph-head") { Heading(stringResource(R.string.cb_phones), w) }
                phones.forEachIndexed { i, a ->
                    item(key = "ph-" + (a.id ?: i)) {
                        val desc = stringResource(R.string.cb_call_desc, a.value)
                        AddrRow(SagaIcons.phone, ContactRegion.pretty(context, a.value), typeLabel(a.type), w.sagaSegment(i, phones.size), desc) {
                            openUri(context, Intent.ACTION_DIAL, ContactBookLogic.telUri(a.value), repo, R.string.cb_no_app_dial)
                        }
                    }
                }
            }
            val mails = cur.mails
            if (mails.isNotEmpty()) {
                item(key = "ml-head") { Heading(stringResource(R.string.cb_mails), w) }
                mails.forEachIndexed { i, a ->
                    item(key = "ml-" + (a.id ?: i)) {
                        val desc = stringResource(R.string.cb_mail_desc, a.value)
                        AddrRow(SagaIcons.mail, a.value, typeLabel(a.type), w.sagaSegment(i, mails.size), desc) {
                            openUri(context, Intent.ACTION_SENDTO, ContactBookLogic.mailUri(a.value), repo, R.string.cb_no_app_mail)
                        }
                    }
                }
            }
            if (cur.form != null || !cur.salutation.isNullOrBlank()) {
                item(key = "form-head") { Heading(stringResource(R.string.cb_address_as), w) }
                val rows = listOfNotNull(
                    cur.form?.let { R.string.cb_form to (if (it == "formal") R.string.cb_form_formal else R.string.cb_form_informal) },
                    cur.salutation?.takeIf { it.isNotBlank() }?.let { R.string.cb_salutation to it },
                )
                rows.forEachIndexed { i, (k, raw) ->
                    item(key = "form-$i") {
                        val v = if (raw is Int) stringResource(raw) else raw.toString()
                        ListItem(
                            overlineContent = { Text(stringResource(k)) },
                            headlineContent = { Text(v) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = w.sagaSegment(i, rows.size),
                        )
                    }
                }
            }
            if (phones.isEmpty() && mails.isEmpty()) item(key = "noaddr") {
                Hint(stringResource(R.string.cb_no_addrs), w.padding(top = 12.dp))
            }
            item(key = "edit") {
                Row(w.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SagaTextButton(onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        SagaIcon(SagaIcons.edit, null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.cb_edit))
                    }
                    SagaTextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        SagaIcon(SagaIcons.trash, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.cb_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
    if (editing && cur != null) {
        ContactEditDialog(repo, cur, ContactBookLogic.ccForRequest(ContactsMemory.cc, ContactsMemory.ccLoaded, ContactRegion.suggest(context)), onDismiss = { editing = false }, onOpen = { editing = false; nav.push(Dest.Contact(it)) }) { n ->
            editing = false
            c = n
            ContactsMemory.list = ContactsMemory.list?.let { ContactBookLogic.applied(it, n) }
        }
    }
    if (confirmDelete && cur != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.cb_delete_q, cur.name)) },
            text = { Text(stringResource(if (cur.saga != null) R.string.cb_delete_text_chat else R.string.cb_delete_text)) },
            confirmButton = {
                SagaTextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            book.remove(cur.id)
                            ContactsMemory.list = ContactsMemory.list?.let { ContactBookLogic.applied(it, null, cur.id) }
                            repo.notify(UiText.Res(R.string.cb_deleted))
                            nav.pop()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            repo.notify(repo.friendly(e))
                        }
                    }
                }) { Text(stringResource(R.string.cb_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { SagaTextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AddrRow(icon: Int, value: String, type: String?, modifier: Modifier, desc: String, onClick: () -> Unit) {
    ListItem(
        overlineContent = type?.let { { Text(it) } },
        headlineContent = { Text(value) },
        leadingContent = { SagaIcon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = desc, onClick = onClick),
    )
}

@Composable
private fun typeLabel(t: String?): String? = when (t) {
    "mobile" -> stringResource(R.string.cb_type_mobile)
    "home" -> stringResource(R.string.cb_type_home)
    "work" -> stringResource(R.string.cb_type_work)
    "other" -> stringResource(R.string.cb_type_other)
    else -> null
}

/** tel: ueber ACTION_DIAL (waehlt nicht selbst), mailto: ueber ACTION_SENDTO */
private fun openUri(context: Context, action: String, uri: String, repo: SagaRepository, missing: Int) {
    try {
        context.startActivity(Intent(action, Uri.parse(uri)).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: ActivityNotFoundException) {
        repo.notify(UiText.Res(missing))
    } catch (_: SecurityException) {
        repo.notify(UiText.Res(missing))
    }
}

// ------------------------------------------------------------------------------------------------ Bearbeiten / Neu

/** Kontakt laden und bearbeiten (Kontakt-Menue "Kontakt bearbeiten …" in Personensuche und Mitglied hinzufuegen) */
@Composable
fun ContactEditFor(repo: SagaRepository, id: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var c by remember(id) { mutableStateOf<BookContact?>(null) }
    var cc by remember { mutableStateOf<String?>(null) }
    var ccLoaded by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf(id) }
    LaunchedEffect(openId) {
        try {
            val r = repo.chat.book.one(openId)
            if (r == null) {
                repo.notify(UiText.Res(R.string.cb_not_found))
                onDismiss()
                return@LaunchedEffect
            }
            try {
                cc = repo.chat.book.cc()
                ccLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            c = r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.notify(repo.friendly(e))
            onDismiss()
        }
    }
    val cur = c ?: return
    androidx.compose.runtime.key(cur.id) {
        ContactEditDialog(repo, cur, ContactBookLogic.ccForRequest(cc, ccLoaded, ContactRegion.suggest(context)), onDismiss = onDismiss, onOpen = { c = null; openId = it }) {
            repo.notify(UiText.Res(R.string.cb_saved))
            onDismiss()
        }
    }
}

/**
 * Bearbeiten (base != null) bzw. Neuer Kontakt (base == null) als Vollbild-Dialog: Name fuer mich, Anrede —/du/Sie,
 * Grussform, Nummern und Mails mit Typ. cc = Landesvorwahl fuer nationale Nummern (Konto, sonst Vorschlag des
 * Geraets). 409 mit contact: neuer Stand, Eingabe bleibt; 409 mit contact_id: Hinweis mit "Oeffnen".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditDialog(
    repo: SagaRepository, base: BookContact?, cc: String?, onDismiss: () -> Unit, onOpen: (Long) -> Unit, onSaved: (BookContact) -> Unit,
) {
    val book = repo.chat.book
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf(base) }
    var d by remember { mutableStateOf(ContactBookLogic.draftOf(base)) }
    var busy by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var dupId by remember { mutableStateOf<Long?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    val manual = cur?.manual ?: true
    val problem = ContactBookLogic.problem(d, manual)
    val changed = ContactBookLogic.changed(cur, d)
    val close: () -> Unit = { if (changed && !busy) askDiscard = true else onDismiss() }
    val save: () -> Unit = save@{
        tried = true
        if (problem != null || busy) return@save
        busy = true
        msg = null
        dupId = null
        scope.launch {
            try {
                val b = cur
                when (val r = if (b == null) book.create(d, cc) else book.update(b, d, cc)) {
                    is ContactBook.Saved.Ok -> onSaved(r.contact)
                    is ContactBook.Saved.Conflict -> {
                        cur = r.current
                        msg = context.getString(R.string.cb_conflict)
                    }
                    is ContactBook.Saved.Duplicate -> {
                        msg = repo.friendly(r.error).asString(context)
                        dupId = r.contactId
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = repo.friendly(e).asString(context)
            }
            busy = false
        }
    }
    Dialog(onDismissRequest = close, properties = io.github.yggdrasil_sh.saga.ui.common.FullScreenDialog.properties) {
        io.github.yggdrasil_sh.saga.ui.common.FullScreenDialogBars()
        BackHandler { close() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (base == null) R.string.cb_new else R.string.cb_edit_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = close) { SagaIcon(SagaIcons.x, stringResource(R.string.action_close)) } },
                    actions = {
                        SagaTextButton(onClick = save, enabled = !busy && (base == null || changed)) {
                            Text(stringResource(R.string.cb_save), fontWeight = FontWeight.SemiBold)
                        }
                    },
                )
            },
        ) { pad ->
            LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                val w = Modifier.widthIn(max = 760.dp).fillMaxWidth()
                val c = cur
                // feste Saga-Adresse: nur zeigen (aus einem gemeinsamen Chat, nicht aenderbar)
                if (c?.saga != null) item(key = "saga") {
                    ListItem(
                        headlineContent = { Text(c.ownName?.let { stringResource(R.string.cb_saga_person) } ?: c.name) },
                        supportingContent = { Text(stringResource(R.string.cb_saga_fixed)) },
                        leadingContent = { ChatAvatar(c.name, 40.dp, avatar = c.saga.avatar) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = w.padding(top = 8.dp).sagaSegment(0, 1),
                    )
                }
                item(key = "name") {
                    val err = tried && (problem == ContactBookLogic.Problem.NAME_NEEDED || problem == ContactBookLogic.Problem.NAME_LONG)
                    OutlinedTextField(
                        value = d.ownName, onValueChange = { d = d.copy(ownName = it) }, singleLine = true,
                        label = { Text(stringResource(R.string.cb_own_name)) },
                        isError = err,
                        supportingText = { Text(stringResource(if (c?.saga != null) R.string.cb_own_name_hint_chat else R.string.cb_own_name_hint)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = w.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                }
                item(key = "form-head") { Heading(stringResource(R.string.cb_address_as), w) }
                item(key = "form") {
                    val opts = listOf(null to stringResource(R.string.cb_form_none), "informal" to stringResource(R.string.cb_form_informal), "formal" to stringResource(R.string.cb_form_formal))
                    val label = stringResource(R.string.cb_form)
                    Column(w.padding(horizontal = 16.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
                            opts.forEachIndexed { i, (k, l) ->
                                SegmentedButton(
                                    selected = d.form == k, onClick = { d = d.copy(form = k) },
                                    shape = SegmentedButtonDefaults.itemShape(i, opts.size), label = { Text(l) },
                                )
                            }
                        }
                    }
                }
                item(key = "sal") {
                    OutlinedTextField(
                        value = d.salutation, onValueChange = { d = d.copy(salutation = it) }, singleLine = true,
                        label = { Text(stringResource(R.string.cb_salutation)) },
                        placeholder = { Text(stringResource(R.string.cb_salutation_hint)) },
                        isError = tried && problem == ContactBookLogic.Problem.SALUTATION_LONG,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        modifier = w.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                }
                // Nummern
                item(key = "ph-head") { Heading(stringResource(R.string.cb_phones), w) }
                d.phones.forEachIndexed { i, a ->
                    item(key = "ph-$i") {
                        AddrEdit(
                            a, stringResource(R.string.cb_phone), KeyboardType.Phone,
                            bad = tried && a.value.isNotBlank() && !ContactBookLogic.phoneLooksOk(a.value),
                            hint = if (ContactBookLogic.national(a.value) && ContactBookLogic.phoneLooksOk(a.value)) {
                                if (cc != null) stringResource(R.string.cb_cc_used, cc) else stringResource(R.string.cb_cc_missing)
                            } else null,
                            modifier = w,
                            onChange = { n -> d = d.copy(phones = d.phones.toMutableList().also { it[i] = n }) },
                            onRemove = { d = d.copy(phones = d.phones.filterIndexed { j, _ -> j != i }) },
                        )
                    }
                }
                if (d.phones.size < ContactBookLogic.MAX_PER_KIND) item(key = "ph-add") {
                    SagaTextButton(onClick = { d = d.copy(phones = d.phones + ContactBookLogic.AddrDraft(type = "mobile")) }, modifier = w.padding(horizontal = 12.dp).heightIn(min = 48.dp)) {
                        SagaIcon(SagaIcons.plus, null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.cb_add_phone))
                    }
                }
                // Mails
                item(key = "ml-head") { Heading(stringResource(R.string.cb_mails), w) }
                d.mails.forEachIndexed { i, a ->
                    item(key = "ml-$i") {
                        AddrEdit(
                            a, stringResource(R.string.cb_mail), KeyboardType.Email,
                            bad = tried && a.value.isNotBlank() && !ContactBookLogic.mailLooksOk(a.value),
                            hint = null, modifier = w,
                            onChange = { n -> d = d.copy(mails = d.mails.toMutableList().also { it[i] = n }) },
                            onRemove = { d = d.copy(mails = d.mails.filterIndexed { j, _ -> j != i }) },
                        )
                    }
                }
                if (d.mails.size < ContactBookLogic.MAX_PER_KIND) item(key = "ml-add") {
                    SagaTextButton(onClick = { d = d.copy(mails = d.mails + ContactBookLogic.AddrDraft()) }, modifier = w.padding(horizontal = 12.dp).heightIn(min = 48.dp)) {
                        SagaIcon(SagaIcons.plus, null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.cb_add_mail))
                    }
                }
                item(key = "msg") {
                    val shown = msg ?: problem?.takeIf { tried }?.let { stringResource(problemText(it)) }
                    if (shown != null) {
                        Column(w) {
                            Msg(shown, msg != context.getString(R.string.cb_conflict), Modifier.fillMaxWidth())
                            dupId?.let { other ->
                                SagaTextButton(onClick = { onOpen(other) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                                    Text(stringResource(R.string.cb_duplicate_open))
                                }
                            }
                        }
                    }
                }
                item(key = "end") { Box(Modifier.padding(bottom = 32.dp)) }
            }
        }
        if (askDiscard) AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.cb_discard_q)) },
            confirmButton = { SagaTextButton(onClick = { askDiscard = false; onDismiss() }) { Text(stringResource(R.string.cb_discard), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { SagaTextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.cb_keep_editing)) } },
        )
    }
}

private fun problemText(p: ContactBookLogic.Problem): Int = when (p) {
    ContactBookLogic.Problem.NAME_NEEDED -> R.string.cb_p_name_needed
    ContactBookLogic.Problem.ADDR_NEEDED -> R.string.cb_p_addr_needed
    ContactBookLogic.Problem.NAME_LONG -> R.string.cb_p_name_long
    ContactBookLogic.Problem.SALUTATION_LONG -> R.string.cb_p_sal_long
    ContactBookLogic.Problem.TOO_MANY_PHONES -> R.string.cb_p_many_phones
    ContactBookLogic.Problem.TOO_MANY_MAILS -> R.string.cb_p_many_mails
    ContactBookLogic.Problem.BAD_PHONE -> R.string.cb_p_bad_phone
    ContactBookLogic.Problem.BAD_MAIL -> R.string.cb_p_bad_mail
}

/** Eine Nummer bzw. Mail: Feld, Typ (Menue), Entfernen */
@Composable
private fun AddrEdit(
    a: ContactBookLogic.AddrDraft, label: String, kb: KeyboardType, bad: Boolean, hint: String?, modifier: Modifier,
    onChange: (ContactBookLogic.AddrDraft) -> Unit, onRemove: () -> Unit,
) {
    var typeMenu by remember { mutableStateOf(false) }
    val typeText = typeLabel(a.type) ?: stringResource(R.string.cb_type_none)
    val typeDesc = stringResource(R.string.cb_type_desc, typeText)
    Row(modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = a.value, onValueChange = { onChange(a.copy(value = it.take(300))) }, singleLine = true,
            label = { Text(label) }, isError = bad,
            supportingText = hint?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = kb, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.padding(top = 8.dp)) {
            SagaTextButton(onClick = { typeMenu = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = typeDesc }) {
                Text(typeText, maxLines = 1)
            }
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                (ContactBookLogic.TYPES + listOf<String?>(null)).forEach { t ->
                    DropdownMenuItem(text = { Text(typeLabel(t) ?: stringResource(R.string.cb_type_none)) }, onClick = { typeMenu = false; onChange(a.copy(type = t)) })
                }
            }
        }
        val rm = stringResource(R.string.cb_remove_addr, a.value.ifBlank { label })
        IconButton(onClick = onRemove, modifier = Modifier.padding(top = 4.dp)) { SagaIcon(SagaIcons.x, rm) }
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier) {
    io.github.yggdrasil_sh.saga.ui.common.SagaWrapSectionTitle(text, modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Msg(text: String, err: Boolean, modifier: Modifier) {
    Text(
        text, color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Landesvorwahl aus der Region des Geraets (Netz bzw. SIM, sonst Sprache/Region der App) */
object ContactRegion {
    fun region(context: Context): String? {
        val tm = try {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        } catch (_: Exception) {
            null
        }
        val net = try { tm?.networkCountryIso } catch (_: Exception) { null }
        val sim = try { tm?.simCountryIso } catch (_: Exception) { null }
        val loc = context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)?.country
        return listOf(sim, net, loc, java.util.Locale.getDefault().country).firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.uppercase()
    }

    /** E.164 lesbar (Gruppierung wie das System-Telefonbuch: Nummern der eigenen Region national); sonst unveraendert */
    fun pretty(context: Context, e164: String): String = try {
        android.telephony.PhoneNumberUtils.formatNumber(e164, region(context) ?: "DE")?.takeIf { it.isNotBlank() } ?: e164
    } catch (_: Exception) {
        e164
    }

    fun suggest(context: Context): String? = ContactBookLogic.ccForRegion(region(context))
}
