package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/**
 * TT 2.5 Nr. 52: Abstand unten fuer Eingabezeilen - Tastatur, Navigationsleiste UND der Bereich der Home-Geste
 * (Gestensteuerung, mandatorySystemGestures). Nur navigationBars reichte nicht: bei Gestensteuerung ist die Leiste
 * flach bzw. ausgeblendet, die Home-Geste aber trotzdem da - ein Wisch am Mikrofon-Knopf ging an das System.
 */
object SafeBottom {
    val insets: WindowInsets
        @Composable get() = WindowInsets.ime.union(WindowInsets.navigationBars)
            .union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
}
