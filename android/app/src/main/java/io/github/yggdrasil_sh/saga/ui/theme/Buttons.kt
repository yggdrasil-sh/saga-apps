package io.github.yggdrasil_sh.saga.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/*
 * Knoepfe mit Web-Ecken (Design-Runde 2c): Das Web rundet .btn mit --r-sm (6 px), Material 3 macht Knoepfe dagegen
 * zur Pille (ButtonDefaults.shape = CornerFull). Compose kennt keinen globalen Override dafuer, deshalb gibt es hier
 * je Knopfart einen Saga-Wrapper mit gleicher Signatur, nur mit shape = MaterialTheme.shapes.small (6 dp) als
 * Standard. Alle Knoepfe der App nutzen diese Wrapper (Test DesignR2cTest prueft, dass keine Datei ausserhalb von
 * ui/theme die Material-Knoepfe direkt importiert). Runde Symbolknoepfe (IconButton, FAB, Anruf-Rundknoepfe) bleiben rund.
 */

/** Standardform aller Saga-Knoepfe: 6 dp wie Web .btn (--r-sm) */
val SagaButtonShape: Shape @Composable get() = MaterialTheme.shapes.small

@Composable
fun SagaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = SagaButtonShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, elevation = elevation,
    border = border, contentPadding = contentPadding, interactionSource = interactionSource, content = content,
)

@Composable
fun SagaFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = SagaButtonShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = FilledTonalButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, elevation = elevation,
    border = border, contentPadding = contentPadding, interactionSource = interactionSource, content = content,
)

@Composable
fun SagaElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = SagaButtonShape,
    colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.elevatedButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = ElevatedButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, elevation = elevation,
    border = border, contentPadding = contentPadding, interactionSource = interactionSource, content = content,
)

@Composable
fun SagaOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = SagaButtonShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, elevation = elevation,
    border = border, contentPadding = contentPadding, interactionSource = interactionSource, content = content,
)

@Composable
fun SagaTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = SagaButtonShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = TextButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, elevation = elevation,
    border = border, contentPadding = contentPadding, interactionSource = interactionSource, content = content,
)

/**
 * Design-Runde 5 (CH-03): Umschalter im Anruf (Mikrofon, Kamera, Lautsprecher) - immer ein Kreis, an = helle Flaeche
 * mit dunklem Symbol, aus = neutral dunkle Flaeche mit hellem, durchgestrichenem Symbol (kein Gold als Zustand).
 * Bedienungshilfen: Rolle Schalter, Name = Geraet ("Mikrofon"), Zustand ueber stateDescription ("an"/"aus").
 * Mindest-Trefferflaeche 48 dp, auch wenn der Kreis kleiner ist.
 */
@Composable
fun SagaCallToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @androidx.annotation.DrawableRes iconOn: Int,
    @androidx.annotation.DrawableRes iconOff: Int,
    label: String,
    stateOn: String,
    stateOff: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 60.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
    colors: SagaCallToggleColors = SagaCallToggleColors.Default,
) {
    val bg by androidx.compose.animation.animateColorAsState(if (checked) colors.onContainer else colors.offContainer, label = "tbg")
    val fg by androidx.compose.animation.animateColorAsState(if (checked) colors.onContent else colors.offContent, label = "tfg")
    val state = if (checked) stateOn else stateOff
    androidx.compose.foundation.layout.Box(
        modifier.minimumInteractiveComponentSize().size(size).clip(CircleShape).background(bg, CircleShape)
            .then(if (!checked) Modifier.border(1.dp, colors.offEdge, CircleShape) else Modifier)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.Icon(painterResource(if (checked) iconOn else iconOff), null, Modifier.size(iconSize), tint = fg)
    }
}

/** Farben des Anruf-Umschalters (Anruf-Buehne ist immer dunkel: feste Werte aus den Anruf-Token) */
data class SagaCallToggleColors(
    val onContainer: Color, val onContent: Color, val offContainer: Color, val offContent: Color, val offEdge: Color,
) {
    companion object {
        val Default = SagaCallToggleColors(
            onContainer = LightSignals.callFg, onContent = LightSignals.callBg,
            offContainer = LightSignals.callBtn, offContent = LightSignals.callFg, offEdge = LightSignals.callLine,
        )
    }
}
