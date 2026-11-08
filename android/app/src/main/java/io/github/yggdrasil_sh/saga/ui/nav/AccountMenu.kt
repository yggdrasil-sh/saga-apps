package io.github.yggdrasil_sh.saga.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.avatarState
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.util.AppLanguage
import io.github.yggdrasil_sh.saga.util.ShotMode
import io.github.yggdrasil_sh.saga.ui.settings.SignOutConfirmDialog
import io.github.yggdrasil_sh.saga.ui.settings.appearanceIcon
import io.github.yggdrasil_sh.saga.ui.settings.appearanceLabel
import io.github.yggdrasil_sh.saga.ui.settings.languageOptionLabel
import io.github.yggdrasil_sh.saga.ui.settings.rememberAppLanguage
import io.github.yggdrasil_sh.saga.ui.settings.rememberAppearance
import kotlinx.coroutines.CancellationException

/**
 * NAV-11 (Entscheidung 3): Avatar oben in der Seitenleiste oeffnet ein Kontomenue wie im Web - Name und Server oben,
 * Sprache ›, Darstellung ›, Einstellungen, Admin-Bereich ↗ (nur Admins), Abmelden (mit Rueckfrage, nicht hervorgehoben).
 */
@Composable
internal fun AccountAvatarMenu(repo: SagaRepository, nav: AppNavigator, onNavigate: () -> Unit, modifier: Modifier = Modifier) {
    val account by repo.account.collectAsState()
    val server by repo.server.collectAsState()
    val signIns by repo.signInCount.collectAsState()
    var ownAvatar by remember { mutableStateOf<String?>(null) }
    var adminUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(account, signIns) {
        ownAvatar = try { repo.avatarState().url } catch (e: CancellationException) { throw e } catch (_: Exception) { ownAvatar }
    }
    var open by remember { mutableStateOf(false) }
    // Admin-Link erst beim Oeffnen holen (GET /api/account, "admin_url" nur fuer Admins); Fehler -> ohne Eintrag
    LaunchedEffect(open, account, signIns) {
        if (!open) return@LaunchedEffect
        adminUrl = try { NavModel.adminUrl(repo.api.get("api/account")) } catch (e: CancellationException) { throw e } catch (_: Exception) { adminUrl }
    }
    var langDialog by remember { mutableStateOf(false) }
    // R4-Nachbesserung 60b: "Darstellung ›" als Untermenue im selben Menue (Zurueck-Kopf + drei Radio-Eintraege)
    var themeSub by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    // Sprache und Darstellung aus ui/settings (r4-settings) - dieselben Werte wie in den Einstellungen
    val (lang, chooseLang) = rememberAppLanguage()
    val (appearance, chooseAppearance) = rememberAppearance()
    if (account.isEmpty()) return

    // A11Y-08: TalkBack liest "Konto: <Name>, Schaltflaeche" statt nur der Initiale; Aktion "Kontomenue oeffnen"
    val accountDesc = stringResource(R.string.r1_account_desc, account)
    val openLabel = stringResource(R.string.r4nav_account_menu)
    Box(modifier) {
        Box(
            Modifier.clip(CircleShape)
                .clickable(onClickLabel = openLabel, role = Role.Button) { open = true }
                .clearAndSetSemantics {
                    contentDescription = accountDesc
                    role = Role.Button
                    onClick(label = openLabel) { open = true; true }
                }
                // 40 dp Avatar + 4 dp ringsum = 48-dp-Tippflaeche
                .padding(4.dp),
        ) { io.github.yggdrasil_sh.saga.ui.notifications.Avatar(account, size = 40, avatar = ownAvatar) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; themeSub = false }, modifier = Modifier.widthIn(min = 240.dp)) {
          if (themeSub) {
            AppearanceSubmenu(appearance, onBack = { themeSub = false }, onChoose = chooseAppearance)
          } else {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                Text(account, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val host = ShotMode.host(PairLink.displayHost(server))
                if (host.isNotEmpty()) {
                    Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.language)) },
                leadingIcon = { SagaIcon(SagaIcons.globe, null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(languageOptionLabel(lang), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SagaIcon(SagaIcons.chevronRight, null)
                    }
                },
                onClick = { open = false; langDialog = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.r4s_appearance)) },
                leadingIcon = { SagaIcon(appearanceIcon(appearance), null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(appearanceLabel(appearance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SagaIcon(SagaIcons.chevronRight, null)
                    }
                },
                onClick = { themeSub = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_settings)) },
                leadingIcon = { SagaIcon(SagaIcons.settings, null) },
                onClick = { open = false; nav.top(Dest.Settings); onNavigate() },
            )
            adminUrl?.let { url ->
                val d = stringResource(R.string.r4nav_admin_desc)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.r4nav_admin) + " ↗") },
                    leadingIcon = { SagaIcon(SagaIcons.shield, null) },
                    onClick = { open = false; repo.openExternalRequest.value = url },
                    modifier = Modifier.semantics { contentDescription = d },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sign_out)) },
                leadingIcon = { SagaIcon(SagaIcons.logout, null) },
                onClick = { open = false; confirmLogout = true },
            )
          }
        }
    }
    if (langDialog) {
        ChoiceDialog(
            stringResource(R.string.language), SagaIcons.globe, AppLanguage.OPTIONS, lang, { languageOptionLabel(it) },
            stringResource(R.string.language_hint), onPick = chooseLang, onDismiss = { langDialog = false },
        )
    }
    if (confirmLogout) SignOutConfirmDialog(repo, onDismiss = { confirmLogout = false })
}

/**
 * Untermenue "Darstellung": oben "‹ Darstellung" (zurueck zum Kontomenue), darunter Wie das System / Hell / Dunkel als
 * Radio-Eintraege. Die Wahl gilt sofort (Thema wechselt hinter dem Menue), das Menue bleibt offen, damit man es sieht.
 */
@Composable
private fun AppearanceSubmenu(current: io.github.yggdrasil_sh.saga.ui.settings.AppAppearance.Mode, onBack: () -> Unit, onChoose: (io.github.yggdrasil_sh.saga.ui.settings.AppAppearance.Mode) -> Unit) {
    val back = stringResource(R.string.action_back)
    DropdownMenuItem(
        text = { Text(stringResource(R.string.r4s_appearance), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)) },
        leadingIcon = { SagaIcon(SagaIcons.chevronLeft, null) },
        onClick = onBack,
        modifier = Modifier.semantics { onClick(label = back) { onBack(); true } },
    )
    HorizontalDivider()
    Column(Modifier.selectableGroup()) {
        io.github.yggdrasil_sh.saga.ui.settings.AppAppearance.Mode.entries.forEach { m ->
            DropdownMenuItem(
                text = { Text(appearanceLabel(m)) },
                leadingIcon = { RadioButton(selected = m == current, onClick = null) },
                trailingIcon = { SagaIcon(appearanceIcon(m), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { if (m != current) onChoose(m) },
                modifier = Modifier.semantics { role = Role.RadioButton; selected = m == current },
            )
        }
    }
}

/** Auswahlliste (Radio) als Dialog fuer die Sprache */
@Composable
private fun <T> ChoiceDialog(title: String, icon: Int, options: List<T>, chosen: T, label: @Composable (T) -> String, hint: String?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SagaIcon(icon, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = o == chosen, role = Role.RadioButton) { onDismiss(); if (o != chosen) onPick(o) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == chosen, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(o))
                    }
                }
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
