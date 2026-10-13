package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.KeyboardOptions
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.blocks.InlineStyle
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.blocks.RichText

// ---------------------------------------------------------------- Darstellung

private fun styleFor(tag: String, c: ColorScheme): SpanStyle? = when (tag) {
    "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
    "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
    "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    "mark" -> SpanStyle(background = Color(0x66F2C94C))
    "code" -> SpanStyle(fontFamily = FontFamily.Monospace, background = c.surfaceContainerHighest)
    "a" -> SpanStyle(color = c.primary, textDecoration = TextDecoration.Underline)
    "time" -> SpanStyle(color = c.tertiary)
    else -> null
}

/** Formatierten Text als AnnotatedString; mit onLink werden Links antippbar */
fun richAnnotated(rt: RichText, c: ColorScheme, onLink: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
    append(rt.text)
    for (sp in rt.spans) {
        if (sp.start < 0 || sp.end > rt.text.length || sp.start >= sp.end) continue
        styleFor(sp.tag, c)?.let { addStyle(it, sp.start, sp.end) }
        if (sp.tag == "a" && onLink != null) {
            val href = InlineHtml.hrefOf(sp.open) ?: continue
            addLink(LinkAnnotation.Clickable(href) { onLink(href) }, sp.start, sp.end)
        }
    }
}

/** Nur lesen (gesperrte Seite, Bildunterschrift): Links antippbar */
@Composable
fun RichTextView(html: String, style: TextStyle, onLink: (String) -> Unit, modifier: Modifier = Modifier, raw: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val text = remember(html, raw, c) { if (raw) AnnotatedString(html) else richAnnotated(InlineHtml.parse(html), c, onLink) }
    BasicText(text, style = style.copy(color = if (style.color == Color.Unspecified) c.onSurface else style.color), modifier = modifier.fillMaxWidth())
}

// ---------------------------------------------------------------- Bearbeiten

/** Index eines neu eingetippten Zeilenumbruchs (Return) oder -1 */
internal fun newlineInserted(old: String, new: String): Int {
    if (new.length <= old.length) return -1
    var p = 0
    val maxP = minOf(old.length, new.length)
    while (p < maxP && old[p] == new[p]) p++
    var s = 0
    while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    val ins = new.substring(p, new.length - s)
    val i = ins.indexOf('\n')
    return if (i < 0) -1 else p + i
}

/**
 * Bearbeitbares Textfeld eines Blocks. Der Text bleibt als Inline-HTML gespeichert; Formatierung
 * (fett, kursiv, Links, unbekannte Tags ...) wird beim Tippen mitgefuehrt (InlineHtml.applyEdit).
 * raw = Klartext (Code-Block, ohne HTML).
 *
 * onEnter(links, rechts): Return wurde gedrueckt - das Feld behaelt "links", der Aufrufer legt "rechts" neu an.
 * onBackspaceAtStart: Ruecktaste am Anfang (z. B. leeren Block entfernen).
 */
@Composable
fun RichField(
    html: String,
    fieldKey: String,
    editor: PageEditor,
    blockPath: JsonPath,
    style: TextStyle,
    onHtml: (String) -> Unit,
    modifier: Modifier = Modifier,
    itemPath: JsonPath? = null,
    placeholder: String = "",
    raw: Boolean = false,
    onEnter: ((RichText, RichText) -> Unit)? = null,
    onBackspaceAtStart: ((isEmpty: Boolean) -> Unit)? = null,
    onLink: (String) -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    if (!editor.editable) {
        if (html.isEmpty()) {
            Box(modifier)
        } else {
            RichTextView(html, style, onLink, modifier, raw)
        }
        return
    }
    var rich by remember(fieldKey) { mutableStateOf(if (raw) RichText(html) else InlineHtml.parse(html)) }
    var value by remember(fieldKey) { mutableStateOf(TextFieldValue(rich.text)) }
    var lastHtml by remember(fieldKey) { mutableStateOf(html) }
    val emit by rememberUpdatedState(onHtml)
    val enter by rememberUpdatedState(onEnter)
    val backspace by rememberUpdatedState(onBackspaceAtStart)
    val pathNow by rememberUpdatedState(blockPath)
    val itemNow by rememberUpdatedState(itemPath)
    val focusRequester = remember { FocusRequester() }

    // Aenderung von aussen (Neu laden, Umwandeln) -> Feld neu aufsetzen
    LaunchedEffect(html) {
        if (html != lastHtml) {
            lastHtml = html
            rich = if (raw) RichText(html) else InlineHtml.parse(html)
            val sel = value.selection.end.coerceAtMost(rich.text.length)
            value = TextFieldValue(rich.text, TextRange(sel))
        }
    }
    LaunchedEffect(editor.focusRequest) {
        if (editor.focusRequest == fieldKey) {
            editor.focusRequest = null
            val at = if (editor.focusAtEnd) rich.text.length else 0
            value = value.copy(selection = TextRange(at))
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun publish(r: RichText) {
        rich = r
        val h = if (raw) r.text else InlineHtml.toHtml(r)
        if (h != lastHtml) {
            lastHtml = h
            emit(h)
        }
    }

    val active = remember(fieldKey) {
        ActiveField(
            key = fieldKey,
            blockPath = { pathNow },
            itemPath = { itemNow },
            toggle = if (raw) null else { st ->
                val s = value.selection
                if (!s.collapsed) publish(InlineHtml.toggle(rich, s.min, s.max, st))
            },
            isActive = if (raw) null else { st -> InlineHtml.isActive(rich, value.selection.min, value.selection.max, st) },
            setLink = if (raw) null else { href ->
                val s = value.selection
                if (!s.collapsed) publish(InlineHtml.setLink(rich, s.min, s.max, href))
            },
            linkAtCursor = if (raw) null else { { InlineHtml.linkAt(rich, value.selection.start) } },
            hasSelection = { !value.selection.collapsed },
        )
    }

    val transformation = if (raw) VisualTransformation.None else VisualTransformation { t ->
        val r = rich
        TransformedText(if (r.text == t.text) richAnnotated(r, c) else AnnotatedString(t.text), OffsetMapping.Identity)
    }

    BasicTextField(
        value = value,
        onValueChange = { nv ->
            if (nv.text == value.text) {
                value = nv
                return@BasicTextField
            }
            val updated = if (raw) RichText(nv.text) else InlineHtml.applyEdit(rich, nv.text)
            val e = enter
            if (!raw && e != null) {
                val nl = newlineInserted(value.text, nv.text)
                if (nl >= 0) {
                    val left = InlineHtml.split(updated, nl).first
                    val right = InlineHtml.split(updated, nl + 1).second
                    value = TextFieldValue(left.text, TextRange(left.text.length))
                    publish(left)
                    e(left, right)
                    return@BasicTextField
                }
            }
            value = nv
            publish(updated)
        },
        textStyle = style.copy(color = if (style.color == Color.Unspecified) c.onSurface else style.color),
        cursorBrush = SolidColor(c.primary),
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(capitalization = if (raw) KeyboardCapitalization.None else KeyboardCapitalization.Sentences, autoCorrectEnabled = !raw),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (it.isFocused) editor.active = active
                else if (editor.active?.key == fieldKey) editor.active = null
            }
            .onPreviewKeyEvent { ev ->
                val b = backspace
                if (b != null && ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace && value.selection == TextRange(0)) {
                    b(value.text.isEmpty())
                    true
                } else false
            },
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty() && placeholder.isNotEmpty() && editor.active?.key == fieldKey) {
                    Text(placeholder, style = style.copy(color = c.onSurfaceVariant.copy(alpha = 0.6f)))
                }
                inner()
            }
        },
    )
}

/** Hilfsfunktion fuer die Werkzeugleiste */
fun ActiveField.styleOn(st: InlineStyle): Boolean = isActive?.invoke(st) ?: false
