package io.github.yggdrasil_sh.saga.ui.settings

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Design-Runde 4 (NAV-11): Darstellung Hell / Dunkel / System - im Einstellungsbildschirm und als Schnellweg im
 * Kontomenue (r4-nav). Gespeichert in den App-Einstellungen ("saga_ui", wie die App-Sprache).
 *
 * Android 12+: zusaetzlich ueber UiModeManager.setApplicationNightMode - das System merkt es sich und liefert der App
 * die passende Konfiguration (MainActivity behandelt uiMode selbst, Compose zeichnet sofort neu; isSystemInDarkTheme()
 * folgt). Android 8-11: wirkt erst, wenn die Wurzel `SagaTheme(dark = rememberSagaDarkTheme())` nutzt.
 */
object AppAppearance {
    enum class Mode(val key: String) {
        SYSTEM("system"), LIGHT("light"), DARK("dark");

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: SYSTEM
        }
    }

    private const val PREFS = "saga_ui"
    private const val KEY = "app_theme"
    private val state = MutableStateFlow<Mode?>(null)

    /** Gewaehlte Darstellung (null = noch nicht geladen) */
    val mode: StateFlow<Mode?> = state.asStateFlow()

    /** Dunkel? (rein) */
    fun isDark(mode: Mode, systemDark: Boolean): Boolean = when (mode) {
        Mode.SYSTEM -> systemDark
        Mode.LIGHT -> false
        Mode.DARK -> true
    }

    /** Wert fuer UiModeManager.setApplicationNightMode (rein) */
    fun nightMode(mode: Mode): Int = when (mode) {
        Mode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        Mode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        Mode.DARK -> UiModeManager.MODE_NIGHT_YES
    }

    /** Gespeicherte Darstellung laden (einmal je Prozess) */
    fun load(context: Context): Mode {
        state.value?.let { return it }
        val m = Mode.of(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
        state.value = m
        return m
    }

    /** Darstellung waehlen und sofort anwenden */
    fun set(context: Context, mode: Mode) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, mode.key) }
        state.value = mode
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightMode(mode))
            } catch (_: Exception) {
                // einzelne Hersteller-ROMs: dann wirkt nur der Compose-Weg (rememberSagaDarkTheme)
            }
        }
    }
}

/** Fuer die Wurzel: `SagaTheme(dark = rememberSagaDarkTheme())` - beachtet die gewaehlte Darstellung auch vor Android 12 */
@Composable
fun rememberSagaDarkTheme(): Boolean {
    val context = LocalContext.current
    val chosen by AppAppearance.mode.collectAsState()
    val mode = chosen ?: remember { AppAppearance.load(context) }
    return AppAppearance.isDark(mode, isSystemInDarkTheme())
}

/** Gewaehlte Darstellung als Zustand und Setzer (fuer eigene Menues, z. B. Kontomenue "Darstellung ›") */
@Composable
fun rememberAppearance(): Pair<AppAppearance.Mode, (AppAppearance.Mode) -> Unit> {
    val context = LocalContext.current
    val chosen by AppAppearance.mode.collectAsState()
    val mode = chosen ?: remember { AppAppearance.load(context) }
    return mode to { m: AppAppearance.Mode -> AppAppearance.set(context, m) }
}

/** Beschriftung einer Darstellung ("Hell", "Dunkel", "Wie das System") */
@Composable
fun appearanceLabel(mode: AppAppearance.Mode): String = stringResource(
    when (mode) {
        AppAppearance.Mode.SYSTEM -> R.string.r4s_appearance_system
        AppAppearance.Mode.LIGHT -> R.string.r4s_appearance_light
        AppAppearance.Mode.DARK -> R.string.r4s_appearance_dark
    },
)

/** Symbol einer Darstellung */
fun appearanceIcon(mode: AppAppearance.Mode): Int = when (mode) {
    AppAppearance.Mode.SYSTEM -> SagaIcons.smartphone
    AppAppearance.Mode.LIGHT -> SagaIcons.sun
    AppAppearance.Mode.DARK -> SagaIcons.moon
}

/** Einstellungen -> Allgemein: Zeile "Darstellung" mit aktueller Wahl; Antippen oeffnet die Auswahl */
@Composable
fun AppearanceRow(modifier: Modifier = Modifier) {
    val (mode, choose) = rememberAppearance()
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.r4s_appearance)) },
        supportingContent = { Text(appearanceLabel(mode)) },
        leadingContent = { SagaIcon(appearanceIcon(mode), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { open = true },
    )
    if (open) AppearanceDialog(mode, onDismiss = { open = false }) { choose(it) }
}

/** Auswahl Hell / Dunkel / System als Dialog (Radio-Gruppe) */
@Composable
fun AppearanceDialog(current: AppAppearance.Mode, onDismiss: () -> Unit, onChoose: (AppAppearance.Mode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SagaIcon(appearanceIcon(current), contentDescription = null) },
        title = { Text(stringResource(R.string.r4s_appearance)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppAppearance.Mode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = m == current, role = Role.RadioButton) { onDismiss(); if (m != current) onChoose(m) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(appearanceLabel(m))
                    }
                }
            }
        },
        confirmButton = { SagaTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
