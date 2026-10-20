package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Schwebender Knopf mit Text, der seinen Namen fuer Bedienungshilfen selbst traegt. Ohne das stand der Knopf als
 * namenloses Element im Baum (TalkBack las nichts, uiautomator fand ihn nicht) - TT 2.4 P-12 "Neuer Chat",
 * N-07 "Neuer Termin". Alle Listen-Bildschirme nutzen diesen Knopf.
 */
@Composable
fun LabeledFab(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Add) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(icon, null) },
        text = { Text(label, modifier = Modifier.clearAndSetSemantics {}) },
        modifier = modifier.semantics { contentDescription = label; role = Role.Button },
    )
}
