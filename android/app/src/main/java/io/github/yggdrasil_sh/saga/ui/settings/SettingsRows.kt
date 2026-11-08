package io.github.yggdrasil_sh.saga.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton

/**
 * Bausteine der Einstellungen (Design-Runde 4): Kategorie-Zeile mit ›, Link in die Android-Systemeinstellungen der
 * Mitteilungen, Abmelden mit Rueckfrage (auch fuer das Kontomenue am Avatar, r4-nav).
 */

/** Zeile der Hauptliste: Symbol, Titel, Kurzbeschreibung, › (eigene Unterseite); 48 dp, Rolle Knopf */
@Composable
fun SettingsCategoryRow(
    icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    external: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it, maxLines = 2) } },
        leadingContent = { SagaIcon(icon, contentDescription = null) },
        trailingContent = {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                trailing?.invoke()
                SagaIcon(if (external) SagaIcons.external else SagaIcons.chevronRight, contentDescription = null)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * Android-Einstellungen der Mitteilungen dieser App oeffnen (Kanaele, Toene, Sperrbildschirm, Nicht stoeren).
 * channelId: einen einzelnen Kanal (Android 8+, ACTION_CHANNEL_NOTIFICATION_SETTINGS); sonst die App-Seite.
 * Faellt auf die App-Details zurueck, falls ein Hersteller die Seite nicht anbietet.
 */
fun openSystemNotificationSettings(context: Context, channelId: String? = null) {
    val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val intent = if (channelId != null) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    } else {
        app
    }
    val tries = listOf(
        intent,
        app,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    for (i in tries) {
        try {
            if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

/** Rueckfrage "Auf diesem Geraet abmelden?" - Bestaetigen meldet ab (repo.logout) */
@Composable
fun SignOutConfirmDialog(repo: SagaRepository, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sign_out_this_device)) },
        text = { Text(stringResource(R.string.settings_sign_out_explainer)) },
        confirmButton = {
            SagaTextButton(onClick = { onDismiss(); repo.launchAction { repo.logout() } }) {
                Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Ende von "Konto": Abmelden als Textknopf in Fehlerfarbe (keine grosse Flaeche), mit Erklaerung darunter */
@Composable
fun SignOutRow(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        SagaTextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).padding(start = 4.dp)) {
            SagaIcon(SagaIcons.logout, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
        }
        Text(
            stringResource(R.string.settings_sign_out_explainer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        )
    }
}
