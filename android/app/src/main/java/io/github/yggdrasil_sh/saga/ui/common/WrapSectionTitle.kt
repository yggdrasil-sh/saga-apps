package io.github.yggdrasil_sh.saga.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.ui.theme.LocalSagaAccent
import io.github.yggdrasil_sh.saga.ui.theme.SagaSpace

/**
 * Abschnittskopf wie `SagaSectionTitle` (Goldbalken + Titel als TalkBack-Ueberschrift), aber der Titel nimmt die ganze
 * Restbreite und bricht um statt abzuschneiden - ohne Silbentrennung, Umbruch an Wortgrenzen (R4-Nachbesserung 61).
 * Hintergrund: In `SagaSectionTitle` teilen sich Titel (`weight(1f, fill = false)`) und der Abstandhalter
 * (`weight(1f)`) den Restplatz, der Titel bekommt hoechstens die halbe Breite und ist einzeilig.
 */
@Composable
fun SagaWrapSectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = SagaSpace.touch).padding(horizontal = SagaSpace.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(LocalSagaAccent.current.gold))
        Spacer(Modifier.width(SagaSpace.s + 2.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(hyphens = Hyphens.None, lineBreak = LineBreak.Heading),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}
