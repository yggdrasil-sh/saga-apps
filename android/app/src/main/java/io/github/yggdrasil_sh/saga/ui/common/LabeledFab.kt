package io.github.yggdrasil_sh.saga.ui.common

import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import androidx.compose.material3.ExtendedFloatingActionButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
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
fun LabeledFab(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int = SagaIcons.plus) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { SagaIcon(icon, null) },
        text = { Text(label, modifier = Modifier.clearAndSetSemantics {}) },
        modifier = modifier.semantics { contentDescription = label; role = Role.Button },
    )
}
