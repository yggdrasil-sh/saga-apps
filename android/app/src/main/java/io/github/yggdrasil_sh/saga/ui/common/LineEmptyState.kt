package io.github.yggdrasil_sh.saga.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import io.github.yggdrasil_sh.saga.ui.theme.SagaIconTile
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace

/**
 * A11Y-21: Leerer Zustand wie [io.github.yggdrasil_sh.saga.ui.theme.SagaEmptyState], aber mit Saga-Liniensymbol
 * statt Emoji in der Goldkachel (Emoji sehen je Geraet anders aus und werden von TalkBack vorgelesen; das Symbol
 * ist Schmuck, daher ohne Beschreibung). Gleiche Masse wie SagaEmptyState. Kann spaeter als `icon`-Parameter in
 * SagaEmptyState (ui/theme) aufgehen.
 */
@Composable
fun SagaLineEmptyState(
    title: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    text: String? = null,
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().widthIn(max = 480.dp).padding(horizontal = SagaSpace.xl, vertical = if (compact) SagaSpace.l else SagaSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SagaSpace.s),
    ) {
        val accent = LocalSagaAccent.current
        SagaIconTile(size = if (compact) 48.dp else 64.dp, container = accent.goldSoft) {
            SagaIcon(icon, null, Modifier.size(if (compact) 24.dp else 32.dp), tint = accent.onGoldSoft)
        }
        Spacer(Modifier.height(SagaSpace.xs))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (text != null) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(SagaSpace.xs))
            action()
        }
    }
}
