package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * R5-Nachbesserung: Vollbild-Dialoge (Neuer Chat, Chat-Info, Gruppeninfo) randlos wie andere Vollbildseiten - der
 * Hintergrund reicht unter Status- und Navigationsleiste (kein grauer Streifen), die Symbole der Leisten passen zum
 * Thema (hell: dunkle Symbole, dunkel: helle). Den Abstand zu den Leisten haelt der Scaffold im Dialog.
 */
object FullScreenDialog {
    val properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

    /** Symbole hell? (dunkler Hintergrund -> helle Symbole) */
    fun lightIcons(backgroundLuminance: Float): Boolean = backgroundLuminance < 0.5f
}

/** Im Inhalt eines Dialogs mit [FullScreenDialog.properties] aufrufen */
@Suppress("DEPRECATION")
@Composable
fun FullScreenDialogBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val lightIcons = FullScreenDialog.lightIcons(MaterialTheme.colorScheme.background.luminance())
    SideEffect {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
        }
    }
}
