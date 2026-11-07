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
