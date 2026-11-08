package io.github.yggdrasil_sh.saga.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yggdrasil_sh.saga.chat.ChatInfoLogic
import io.github.yggdrasil_sh.saga.chat.ChatInfoLogic.Row as InfoRow
import io.github.yggdrasil_sh.saga.chat.ChatLogic
import io.github.yggdrasil_sh.saga.chat.ChatMessage
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatStd
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.util.UiText
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.chat.ListPreviewLogic
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.settings.SettingsCategoryRow
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton

/** CH-07: Einstellungen > Chats - "Vorschau in der Chat-Liste" mit aktuellem Wert; Auswahl als Radio-Gruppe */
@Composable
fun ListPreviewSettingRow(repo: SagaRepository, modifier: Modifier = Modifier) {
    val mode by repo.chat.listPreviewMode.collectAsState()
    var open by remember { mutableStateOf(false) }
    SettingsCategoryRow(SagaIcons.message, stringResource(R.string.r5_pv_setting), modifier, summary = stringResource(listPreviewModeLabel(mode))) { open = true }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.r5_pv_setting)) },
        text = {
            Column(Modifier.selectableGroup()) {
                ListPreviewLogic.Mode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = m == mode, role = Role.RadioButton) { repo.chat.setListPreviewMode(m); open = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m == mode, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(listPreviewModeLabel(m)))
                            Text(
                                stringResource(if (m == ListPreviewLogic.Mode.TEXT) R.string.r5_pv_mode_text_hint else R.string.r5_pv_mode_kind_hint),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { SagaTextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } },
    )
}

fun listPreviewModeLabel(m: ListPreviewLogic.Mode): Int = when (m) {
    ListPreviewLogic.Mode.TEXT -> R.string.r5_pv_mode_text
    ListPreviewLogic.Mode.KIND -> R.string.r5_pv_mode_kind
}

/**
 * CH-10: Chat-Info fuer Direkt-Chats (und Chats ohne Gruppeninfo, aeltere Server) - Tipp auf den Chat-Kopf, Muster
 * wie die Gruppeninfo: Kopf mit Bild und Name, dann Medien/Dateien/Links, Stummschalten, Automatisch loeschen (aktueller
 * Wert als Untertitel), Sicherheitsnummer pruefen; unten rot Sperren und Melden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoDialog(
    repo: SagaRepository,
    s: ChatSession,
    onDismiss: () -> Unit,
    onBlock: (String, String) -> Unit,
    onReport: (List<ChatMessage>) -> Unit,
    onLeave: () -> Unit,
) {
    val c = s.summary ?: return
    val svc = repo.chat
    val std = svc.std
    var sub by remember { mutableStateOf<InfoRow?>(null) }
    val peer = if (c.kind == "dm") c.members.firstOrNull { !it.me } ?: c.peer else null
    val blockTarget = if (c.kind == "dm" && std.block && !c.remote) ChatStd.blockTarget(peer) else null
    val reportable = if (std.report) s.messages.filter { !it.mine && it.content != null && !it.tomb && it.sys == null && it.call == null }.takeLast(5) else emptyList()
    val others = c.members.filter { !it.me }
    val rows = ChatInfoLogic.rows(
        ChatInfoLogic.Flags(
            dm = c.kind == "dm", media = std.media, canArchive = s.canArchive, free = c.kind == "free", canRename = c.canRename != false,
            block = blockTarget != null, report = reportable.isNotEmpty(), hasOthers = others.isNotEmpty() || peer != null,
        ),
    )
    val nowSec = System.currentTimeMillis() / 1000.0
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.r5_info_title)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { SagaIcon(SagaIcons.x, stringResource(R.string.action_close)) } },
                )
            },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), horizontalAlignment = Alignment.CenterHorizontally) {
                item(key = "head") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        GroupAvatar(repo, c, s.name, 88.dp)
                        Text(s.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                        val subLine = when {
                            c.kind == "dm" && c.remote -> (peer?.host ?: c.home)?.let { stringResource(R.string.fed_on_host, it) }
                            c.kind == "dm" -> peer?.let { ChatLogic.subline(it.sub) }
                            else -> androidx.compose.ui.res.pluralStringResource(R.plurals.fed_people_n, c.memberCount, c.memberCount)
                        }
                        if (!subLine.isNullOrEmpty()) Text(subLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val normal = rows.filter { !ChatInfoLogic.danger(it) }
                val danger = rows.filter { ChatInfoLogic.danger(it) }
                items(normal, key = { it.name }) { r ->
                    when (r) {
                        InfoRow.MEDIA -> InfoItem(SagaIcons.image, stringResource(R.string.std_media_title)) { sub = InfoRow.MEDIA }
                        InfoRow.MEMBERS -> InfoItem(SagaIcons.users, stringResource(R.string.chat_members), androidx.compose.ui.res.pluralStringResource(R.plurals.fed_people_n, c.memberCount, c.memberCount)) { sub = InfoRow.MEMBERS }
                        InfoRow.MUTE -> if (std.mute2) Box {
                            InfoItem(if (ChatStd.mutedNow(c, nowSec)) SagaIcons.bellOff else SagaIcons.bell, stringResource(R.string.r5_info_mute), muteLabel(c, nowSec)) { sub = InfoRow.MUTE }
                            DropdownMenu(expanded = sub == InfoRow.MUTE, onDismissRequest = { sub = null }) {
                                AlltagMenuItems(repo, c, onPatched = { s.applySummary(it) }, muteOnly = true, inline = true) { sub = null }
                            }
                        } else ListItem(
                            headlineContent = { Text(stringResource(R.string.r5_info_mute)) },
                            leadingContent = { SagaIcon(if (c.muted) SagaIcons.bellOff else SagaIcons.bell, null) },
                            trailingContent = { Switch(checked = c.muted, onCheckedChange = null) },
                            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)
                                .selectable(selected = c.muted, role = Role.Switch) { repo.launchAction { s.setMuted(!c.muted) } },
                        )
                        InfoRow.RETENTION -> InfoItem(SagaIcons.timer, stringResource(R.string.chat_retention), retentionLabel(c.retentionS)) { sub = InfoRow.RETENTION }
                        InfoRow.SAFETY -> {
                            val verified = others.isNotEmpty() && others.all { s.isVerified(it.username) }
                            InfoItem(SagaIcons.shield, stringResource(R.string.r5_info_safety), if (verified) stringResource(R.string.chat_verified) else null) { sub = InfoRow.SAFETY }
                        }
                        InfoRow.RENAME -> InfoItem(SagaIcons.edit, stringResource(R.string.chat_rename)) { sub = InfoRow.RENAME }
                        InfoRow.ARCHIVE -> {
                            val arch = c.archived == true
                            InfoItem(if (arch) SagaIcons.archiveOut else SagaIcons.archive, stringResource(if (arch) R.string.chatdel_unarchive else R.string.chatdel_archive)) {
                                repo.launchAction { s.setArchived(!arch); repo.notify(UiText.Res(if (arch) R.string.chatdel_unarchived_done else R.string.chatdel_archived_done)) }
                            }
                        }
                        else -> {}
                    }
                }
                if (danger.isNotEmpty()) item(key = "div") { HorizontalDivider(Modifier.widthIn(max = 760.dp).padding(vertical = 8.dp)) }
                items(danger, key = { it.name }) { r ->
                    val red = MaterialTheme.colorScheme.error
                    when (r) {
                        InfoRow.BLOCK -> {
                            val t = blockTarget ?: return@items
                            if (peer?.blocked == true || c.blocked) InfoItem(SagaIcons.ban, stringResource(R.string.std_unblock)) {
                                repo.launchAction { s.unblock(t); repo.notify(UiText.Res(R.string.std_unblocked_done)) }
                            } else {
                                val n = peer?.let { ChatLogic.shown(it) }.orEmpty()
                                InfoItem(SagaIcons.ban, stringResource(R.string.std_block_name, n), color = red) { onBlock(n, t) }
                            }
                        }
                        InfoRow.REPORT -> InfoItem(SagaIcons.flag, stringResource(R.string.std_report_menu), color = red) { onReport(reportable) }
                        InfoRow.LEAVE -> InfoItem(SagaIcons.logout, stringResource(R.string.chat_leave), color = red) { onLeave() }
                        else -> {}
                    }
                }
                item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
    when (sub) {
        InfoRow.MEDIA -> MediaDialog(repo, s) { sub = null }
        InfoRow.MEMBERS -> MembersDialog(repo, s) { sub = null }
        InfoRow.RETENTION -> RetentionDialog(repo, s) { sub = null }
        InfoRow.SAFETY -> SafetyDialog(s, peer?.username) { sub = null }
        InfoRow.RENAME -> RenameDialog(repo, s) { sub = null }
        else -> {}
    }
}

/** Zeile der Chat-Info: Symbol, Titel, ggf. aktueller Wert als Untertitel; 56 dp, Rolle Knopf */
@Composable
internal fun InfoItem(icon: Int, title: String, value: String? = null, color: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    val col = color ?: MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = col) },
        supportingContent = value?.let { { Text(it) } },
        leadingContent = { SagaIcon(icon, null, tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
    )
}

/** CH-11: Anhang-Menue als Bottom Sheet mit Kachelraster (Linien-Symbole, je Kachel mindestens 72 dp) */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AttachSheet(tiles: List<io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile>, onDismiss: () -> Unit, onPick: (io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile) -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.chat_attach), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp).semantics { heading() },
        )
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp),
            maxItemsInEachRow = 4,
        ) {
            tiles.forEach { t ->
                val (icon, label) = attachTile(t)
                Column(
                    Modifier.weight(1f).heightIn(min = 88.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(io.github.yggdrasil_sh.saga.ui.theme.SagaRadius.md))
                        .clickable(role = Role.Button) { onDismiss(); onPick(t) }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    io.github.yggdrasil_sh.saga.ui.theme.SagaIconTile(size = 52.dp, container = MaterialTheme.colorScheme.secondaryContainer) {
                        SagaIcon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                }
            }
            // letzte Reihe auffuellen, damit die Kacheln gleich breit bleiben
            repeat((4 - tiles.size % 4) % 4) { Box(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun attachTile(t: io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile): Pair<Int, String> = when (t) {
    io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.GALLERY -> SagaIcons.gallery to stringResource(R.string.r5_att_gallery)
    io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.CAMERA -> SagaIcons.camera to stringResource(R.string.r5_att_camera)
    io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.VIDEO_CAMERA -> SagaIcons.video to stringResource(R.string.r5_att_video_camera)
    io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.FILE -> SagaIcons.file to stringResource(R.string.r5_att_file)
    io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.PAGE -> SagaIcons.link to stringResource(R.string.r5_att_page)
    io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.IMMICH -> SagaIcons.cloud to stringResource(R.string.r5_att_immich)
    io.github.yggdrasil_sh.saga.chat.AttachLogic.Tile.PASTE -> SagaIcons.clipboard to stringResource(R.string.r5_att_paste)
}

