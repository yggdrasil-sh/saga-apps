package io.github.yggdrasil_sh.saga.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Gemeinsame Bausteine des Saga-Designs (Karten, Abschnittskoepfe, leere Zustaende, Symbol-Kacheln, Pillen,
 * Druck- und Einblend-Bewegung). Alle respektieren "Animationen entfernen" (LocalReducedMotion).
 */

/** Karte: tonale Flaeche mit weichen Ecken, im Hellen mit feiner Kante. onClick != null -> ganze Karte tippbar */
@Composable
fun SagaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.large,
    padding: Dp = SagaSpace.l,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    val inner: @Composable () -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) {
        val src = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick, modifier = modifier.sagaPress(src), shape = shape, color = color, border = border,
            interactionSource = src,
        ) { inner() }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) { inner() }
    }
}

/**
 * Abschnittskopf: kurzer Goldbalken + Titel (als Ueberschrift fuer TalkBack), optional Anzahl als Pille und
 * eine Aktion rechts (z. B. "Alle anzeigen").
 */
@Composable
fun SagaSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = SagaSpace.touch).padding(horizontal = SagaSpace.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(LocalSagaAccent.current.gold))
        Spacer(Modifier.width(SagaSpace.s + 2.dp))
        Text(
            text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(SagaSpace.s))
            SagaPill(count.toString())
        }
        Spacer(Modifier.weight(1f))
        action?.invoke(this)
    }
}

/** Kleine Pille (Anzahl, Status). Standard: zartes Gold */
@Composable
fun SagaPill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = LocalSagaAccent.current.goldSoft,
    content: Color = LocalSagaAccent.current.onGoldSoft,
) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1,
        modifier = modifier.clip(CircleShape).background(container).padding(horizontal = SagaSpace.s, vertical = SagaSpace.xxs),
    )
}

/** Symbol-Kachel: Emoji oder Symbol mittig in einer runden, getoenten Flaeche */
@Composable
fun SagaIconTile(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(container),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Leerer Zustand: grosses Symbol in einer Goldkachel, Titel, kurzer Text und (optional) die naechste Aktion.
 */
@Composable
fun SagaEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    emoji: String? = null,
    text: String? = null,
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().widthIn(max = 480.dp).padding(horizontal = SagaSpace.xl, vertical = if (compact) SagaSpace.l else SagaSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SagaSpace.s),
    ) {
        if (emoji != null) {
            SagaIconTile(size = if (compact) 48.dp else 64.dp, container = LocalSagaAccent.current.goldSoft) {
                Text(emoji, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(SagaSpace.xs))
        }
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

/** Druck-Rueckmeldung: Element gibt beim Antippen leicht nach (Feder). Aus bei "Animationen entfernen". */
@Composable
fun Modifier.sagaPress(source: MutableInteractionSource, pressedScale: Float = 0.97f): Modifier {
    if (LocalReducedMotion.current) return this
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, SagaMotion.current.fastSpatial(), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Sanftes Erscheinen beim ersten Anzeigen (Einblenden + kleiner Weg nach oben), gestaffelt nach index (max. 8 Stufen).
 * Aus bei "Animationen entfernen".
 */
@Composable
fun Modifier.sagaAppear(index: Int = 0): Modifier {
    if (LocalReducedMotion.current) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(18L * index.coerceIn(0, 8))
        p.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 420f))
    }
    return this.graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * 10.dp.toPx()
    }
}

/**
 * Form eines Eintrags in einer segmentierten Liste (Material 3 Expressive): aussen stark gerundet, innen nur leicht,
 * dazwischen 2 dp Luft - so wirkt eine Liste wie eine zusammenhaengende Karte aus einzelnen Kacheln.
 */
fun sagaSegmentShape(index: Int, count: Int, outer: Dp = SagaRadius.l, inner: Dp = SagaRadius.xs): androidx.compose.ui.graphics.Shape {
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer else inner, topEnd = if (first) outer else inner,
        bottomStart = if (last) outer else inner, bottomEnd = if (last) outer else inner,
    )
}

/** Eintrag einer segmentierten Liste: seitlicher Rand, 2 dp Abstand, Kachelform und tonale Flaeche */
@Composable
fun Modifier.sagaSegment(index: Int, count: Int, color: Color = MaterialTheme.colorScheme.surfaceContainerLow, horizontal: Dp = SagaSpace.gutter): Modifier =
    this.padding(start = horizontal, end = horizontal, top = if (index == 0) 0.dp else 2.dp)
        .clip(sagaSegmentShape(index, count)).background(color)
