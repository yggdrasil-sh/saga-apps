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
import io.github.yggdrasil_sh.saga.data.Masking

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

/**
 * Formatierten Text als AnnotatedString; mit onLink werden Links antippbar.
 * masked (fremde Seite): Verweise auf Seiten ohne Zugriff gedaempft und ohne Link.
 */
fun richAnnotated(rt: RichText, c: ColorScheme, onLink: ((String) -> Unit)? = null, masked: Boolean = false): AnnotatedString = buildAnnotatedString {
    append(rt.text)
    for (sp in rt.spans) {
        if (sp.start < 0 || sp.end > rt.text.length || sp.start >= sp.end) continue
        if (sp.tag == "a" && masked && Masking.isMasked(InlineHtml.hrefOf(sp.open), rt.text.substring(sp.start, sp.end))) {
            addStyle(SpanStyle(color = c.onSurfaceVariant), sp.start, sp.end)
            continue
        }
        styleFor(sp.tag, c)?.let { addStyle(it, sp.start, sp.end) }
        if (sp.tag == "a" && onLink != null) {
            val href = InlineHtml.hrefOf(sp.open) ?: continue
            addLink(LinkAnnotation.Clickable(href) { onLink(href) }, sp.start, sp.end)
        }
    }
}

/** Nur lesen (gesperrte/fremde Seite, Bildunterschrift): Links antippbar; foreign = verdeckte Verweise mit 🔒 */
@Composable
fun RichTextView(html: String, style: TextStyle, onLink: (String) -> Unit, modifier: Modifier = Modifier, raw: Boolean = false, foreign: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val text = remember(html, raw, c, foreign) {
        if (raw) AnnotatedString(html)
        else richAnnotated(InlineHtml.parse(if (foreign) Masking.forDisplay(html) else html), c, onLink, foreign)
    }
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
 * Unsichtbarer Anfang eines Feldes mit onBackspaceAtStart: Viele Soft-Keyboards melden die Ruecktaste am
 * Feldanfang nicht als Tastenereignis. Steht dort ein Zeichen, loescht die Tastatur es wie jedes andere -
 * das erkennen wir an der Text-Aenderung. Ein Zeilenumbruch (statt z. B. U+200B), damit die Tastatur den
 * Anfang als Absatzanfang sieht und weiter gross schreibt. Er wird nicht angezeigt und nie gespeichert.
 */
private const val START = "\n"

/**
 * Zustand eines Feldes: formatierter Text, Eingabewert (mit START) und - ohne State, nur zum Vergleich beim
 * Komponieren - das zuletzt von aussen uebernommene HTML sowie alles, was dieses Feld seitdem selbst gesendet hat.
 */
private class FieldState(html: String, val raw: Boolean, val pre: String) {
    var rich by mutableStateOf(parseHtml(html, raw))
    var value by mutableStateOf(TextFieldValue(pre + rich.text, TextRange(pre.length)))
    /** zuletzt von aussen uebernommen */
    var external: String = html
    /** aktueller Stand dieses Feldes als HTML */
    var current: String = html
    /** seit der letzten Uebernahme von aussen selbst gesendet (eigenes Echo, auch verspaetet, nie als Aenderung werten) */
    val sent = ArrayDeque<String>()

    fun isExternalChange(html: String): Boolean = html != external && html != current && html !in sent

    fun accept(html: String) {
        external = html
        current = html
        sent.clear()
    }

    fun sent(h: String) {
        current = h
        sent.addLast(h)
        if (sent.size > 200) sent.removeFirst()
    }
}

private fun parseHtml(html: String, raw: Boolean): RichText = if (raw) RichText(html) else InlineHtml.parse(html)

/** Anzeige ohne START: Cursor-Positionen um die Laenge von START verschieben */
private class StartMapping(private val p: Int, private val shownLength: Int) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = (offset - p).coerceIn(0, shownLength)
    override fun transformedToOriginal(offset: Int): Int = offset.coerceIn(0, shownLength) + p
}

/**
 * Bearbeitbares Textfeld eines Blocks. Der Text bleibt als Inline-HTML gespeichert; Formatierung
 * (fett, kursiv, Links, unbekannte Tags ...) wird beim Tippen mitgefuehrt (InlineHtml.applyEdit).
 * raw = Klartext (Code-Block, ohne HTML).
 *
 * onEnter(links, rechts): Return wurde gedrueckt - das Feld behaelt "links", der Aufrufer legt "rechts" neu an.
 * onBackspaceAtStart(leer): Ruecktaste am Anfang (leeren Block entfernen bzw. mit dem vorigen zusammenfuehren) -
 * erkannt ueber die Text-Aenderung (unsichtbares START-Zeichen), funktioniert so auch mit Soft-Keyboards.
 *
 * WICHTIG (Fehler "getippt" -> "getptppt"): Frueher wurde jedes html != zuletzt gesendet als Aenderung von aussen
 * gewertet (in einem LaunchedEffect). Kam ein veralteter Wert an (eigenes Echo von ein paar Tastendruecken vorher),
 * wurde das Feld auf diesen alten Stand zurueckgesetzt, waehrend weiter getippt wurde - Zeichen gingen verloren oder
 * landeten an falscher Stelle. Jetzt: eigenes Echo erkennen (FieldState.sent), synchron beim Komponieren pruefen.
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
            RichTextView(html, style, onLink, modifier, raw, foreign = editor.caps.foreign)
        }
        return
    }
    val foreign = editor.caps.foreign
    val pre = if (onBackspaceAtStart != null) START else ""
    val st = remember(fieldKey, pre) { FieldState(html, raw, pre) }
    val p = pre.length

    // Aenderung von aussen (Neu laden, Umwandeln, Zusammenfuehren) -> Feld neu aufsetzen. Eigene Eingaben kommen
    // hier als Echo an - auch VERSPAETET: das Feld kann mit einem alten html-Parameter neu komponiert werden, bevor
    // das Listenelement (LazyColumn) den neuen Stand weiterreicht. Ein Echo setzt nie zurueck.
    if (st.isExternalChange(html)) {
        st.accept(html)
        st.rich = parseHtml(html, raw)
        val sel = st.value.selection.end.coerceIn(p, p + st.rich.text.length)
        st.value = TextFieldValue(pre + st.rich.text, TextRange(sel))
    }

    val emit by rememberUpdatedState(onHtml)
    val enter by rememberUpdatedState(onEnter)
    val backspace by rememberUpdatedState(onBackspaceAtStart)
    val pathNow by rememberUpdatedState(blockPath)
    val itemNow by rememberUpdatedState(itemPath)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(editor.focusRequest) {
        if (editor.focusRequest == fieldKey) {
            editor.focusRequest = null
            val len = st.rich.text.length
            val at = (editor.focusOffset ?: if (editor.focusAtEnd) len else 0).coerceIn(0, len)
            editor.focusOffset = null
            st.value = st.value.copy(selection = TextRange(p + at))
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun publish(r: RichText) {
        st.rich = r
        val h = if (raw) r.text else InlineHtml.toHtml(r)
        if (h != st.current) {
            st.sent(h)
            emit(h)
        }
    }

    /** Auswahl ohne START (Positionen im Klartext) */
    fun sel(): TextRange = TextRange((st.value.selection.start - p).coerceAtLeast(0), (st.value.selection.end - p).coerceAtLeast(0))

    val active = remember(fieldKey) {
        ActiveField(
            key = fieldKey,
            blockPath = { pathNow },
            itemPath = { itemNow },
            toggle = if (raw) null else { s ->
                val r = sel()
                if (!r.collapsed) publish(InlineHtml.toggle(st.rich, r.min, r.max, s))
            },
            isActive = if (raw) null else { s -> sel().let { r -> InlineHtml.isActive(st.rich, r.min, r.max, s) } },
            setLink = if (raw) null else { href ->
                val r = sel()
                if (!r.collapsed) publish(InlineHtml.setLink(st.rich, r.min, r.max, href))
            },
            linkAtCursor = if (raw) null else { { InlineHtml.linkAt(st.rich, sel().start) } },
            hasSelection = { !st.value.selection.collapsed },
        )
    }

    val transformation = if (raw && p == 0) VisualTransformation.None else VisualTransformation { t ->
        if (p > 0 && !t.text.startsWith(pre)) return@VisualTransformation TransformedText(t, OffsetMapping.Identity)
        val shown = t.text.substring(p)
        val r = st.rich
        val ann = if (!raw && r.text == shown) richAnnotated(r, c, masked = foreign) else AnnotatedString(shown)
        TransformedText(ann, if (p == 0) OffsetMapping.Identity else StartMapping(p, shown.length))
    }

    /** Cursor/Auswahl nie vor START */
    fun clamp(v: TextFieldValue): TextFieldValue {
        if (p == 0) return v
        val s = v.selection
        if (s.start >= p && s.end >= p) return v
        return v.copy(selection = TextRange(s.start.coerceAtLeast(p), s.end.coerceAtLeast(p)))
    }

    BasicTextField(
        value = st.value,
        onValueChange = { nv ->
            val old = st.value
            if (p > 0 && !nv.text.startsWith(pre)) {
                if (nv.text == old.text.substring(p)) {
                    // START geloescht = Ruecktaste am Feldanfang (Hardware- oder Soft-Keyboard)
                    st.value = TextFieldValue(old.text, TextRange(p))
                    backspace?.invoke(st.rich.text.isEmpty())
                    return@BasicTextField
                }
                // ungewoehnliche Ersetzung inkl. START (z. B. Einfuegen ueber alles): START wieder davor
                val fixed = nv.copy(text = pre + nv.text, selection = TextRange(nv.selection.start + p, nv.selection.end + p), composition = null)
                applyChange(st, fixed, p, raw, enter, ::publish)
                return@BasicTextField
            }
            if (nv.text == old.text) {
                st.value = clamp(nv)
                return@BasicTextField
            }
            applyChange(st, clamp(nv), p, raw, enter, ::publish)
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
            },
        decorationBox = { inner ->
            Box {
                if (st.value.text.length <= p && placeholder.isNotEmpty() && editor.active?.key == fieldKey) {
                    Text(placeholder, style = style.copy(color = c.onSurfaceVariant.copy(alpha = 0.6f)))
                }
                inner()
            }
        },
    )
}

/** Neue Eingabe uebernehmen: Return erkennen (Block teilen), sonst Text + Formatierung nachfuehren */
private fun applyChange(
    st: FieldState, nv: TextFieldValue, p: Int, raw: Boolean,
    enter: ((RichText, RichText) -> Unit)?, publish: (RichText) -> Unit,
) {
    val plain = nv.text.substring(p)
    val updated = if (raw) RichText(plain) else InlineHtml.applyEdit(st.rich, plain)
    if (!raw && enter != null) {
        val nl = newlineInserted(st.value.text.substring(p), plain)
        if (nl >= 0) {
            val left = InlineHtml.split(updated, nl).first
            val right = InlineHtml.split(updated, nl + 1).second
            st.value = TextFieldValue(st.pre + left.text, TextRange(p + left.text.length))
            publish(left)
            enter(left, right)
            return
        }
    }
    st.value = nv
    publish(updated)
}

/** Hilfsfunktion fuer die Werkzeugleiste */
fun ActiveField.styleOn(st: InlineStyle): Boolean = isActive?.invoke(st) ?: false
