package io.github.yggdrasil_sh.saga.ui.nav

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.CancellationException

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Darstellung der App (Android 12+: je App ueber UiModeManager, vom System gespeichert); Wahl fuer die Anzeige gemerkt */
private object AppTheme {
    private const val PREFS = "saga_r4nav"
    private const val KEY = "theme"
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31

    fun current(ctx: Context): NavModel.Theme =
        NavModel.Theme.of(ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun set(ctx: Context, t: NavModel.Theme) {
        if (Build.VERSION.SDK_INT < 31) return
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, t.key).apply()
        ctx.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(NavModel.nightMode(t))
    }
}

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
    var themeDialog by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
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
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                Text(account, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val host = ShotMode.host(PairLink.displayHost(server))
                if (host.isNotEmpty()) {
                    Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider()
            val ctx = LocalContext.current
            val config = LocalConfiguration.current
            val lang = remember(config) { AppLanguage.current(ctx) }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.language)) },
                leadingIcon = { SagaIcon(SagaIcons.globe, null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(languageLabel(lang), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SagaIcon(SagaIcons.chevronRight, null)
                    }
                },
                onClick = { open = false; langDialog = true },
            )
            if (AppTheme.supported) {
                val theme = remember(config) { AppTheme.current(ctx) }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.r4nav_appearance)) },
                    leadingIcon = { SagaIcon(if (theme == NavModel.Theme.DARK) SagaIcons.moon else SagaIcons.sun, null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(themeLabel(theme), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SagaIcon(SagaIcons.chevronRight, null)
                        }
                    },
                    onClick = { open = false; themeDialog = true },
                )
            }
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
    if (langDialog) LanguageDialog { langDialog = false }
    if (themeDialog) ThemeDialog { themeDialog = false }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.settings_sign_out_this_device)) },
            confirmButton = {
                SagaTextButton(onClick = { confirmLogout = false; repo.launchAction { repo.logout() } }) {
                    Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { SagaTextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun languageLabel(tag: String): String = if (tag.isEmpty()) stringResource(R.string.language_auto) else AppLanguage.nativeName(tag)

@Composable
private fun themeLabel(t: NavModel.Theme): String = stringResource(
    when (t) {
        NavModel.Theme.SYSTEM -> R.string.r4nav_theme_system
        NavModel.Theme.LIGHT -> R.string.r4nav_theme_light
        NavModel.Theme.DARK -> R.string.r4nav_theme_dark
    },
)

/** Auswahlliste (Radio) als Dialog - gemeinsam fuer Sprache und Darstellung */
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

@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val chosen = remember { AppLanguage.current(ctx) }
    ChoiceDialog(
        stringResource(R.string.language), SagaIcons.globe, AppLanguage.OPTIONS, chosen, { languageLabel(it) },
        stringResource(R.string.language_hint),
        onPick = { tag -> ctx.findActivity()?.let { AppLanguage.set(it, tag) } }, onDismiss = onDismiss,
    )
}

@Composable
private fun ThemeDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val chosen = remember { AppTheme.current(ctx) }
    ChoiceDialog(
        stringResource(R.string.r4nav_appearance), SagaIcons.sun, NavModel.Theme.entries, chosen, { themeLabel(it) }, null,
        onPick = { AppTheme.set(ctx, it) }, onDismiss = onDismiss,
    )
}
