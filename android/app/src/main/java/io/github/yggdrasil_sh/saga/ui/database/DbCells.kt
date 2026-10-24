package io.github.yggdrasil_sh.saga.ui.database

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.DbProps
import io.github.yggdrasil_sh.saga.blocks.InlineHtml
import io.github.yggdrasil_sh.saga.db.DbProp
import io.github.yggdrasil_sh.saga.db.DbQuery
import io.github.yggdrasil_sh.saga.db.DbValues
import io.github.yggdrasil_sh.saga.ui.common.appLocale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/** Farbe einer Option (wie .opt-<farbe> im Web) - als Hintergrund mit Deckkraft, Text in Vordergrundfarbe */
fun optColor(name: String?): Color = when (name) {
    "brown" -> Color(0xFF9F6B53)
    "orange" -> Color(0xFFD9730D)
    "yellow" -> Color(0xFFDFAB01)
    "green" -> Color(0xFF0F7B6C)
    "blue" -> Color(0xFF0B6E99)
    "purple" -> Color(0xFF6940A5)
    "pink" -> Color(0xFFAD1A72)
    "red" -> Color(0xFFE03E3E)
    else -> Color(0xFF9B9A97)
}

/**
 * Hintergrund eines Options-Chips: hell 35 % Deckkraft; dunkel aufgehellt und deckender (Nr. 42: dunkelgruener Chip
 * verschwand fast im dunklen Grund). Text bleibt in Vordergrundfarbe (hell bzw. dunkel) und damit gut lesbar.
 */
@Composable
fun optBg(color: String?): Color {
    val base = optColor(color)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) androidx.compose.ui.graphics.lerp(base, Color.White, 0.18f).copy(alpha = 0.55f) else base.copy(alpha = 0.35f)
}

@Composable
fun OptChip(name: String, color: String?) {
    Text(
        name.ifEmpty { "–" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(optBg(color)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Typ-Name fuer Bedienungshilfen und Menues */
@Composable
fun typeName(type: String): String = stringResource(
    when (type) {
        "number" -> R.string.dbv_type_number
        "select" -> R.string.dbv_type_select
        "multi_select" -> R.string.dbv_type_multi_select
        "date" -> R.string.dbv_type_date
        "checkbox" -> R.string.dbv_type_checkbox
        "url" -> R.string.dbv_type_url
        "relation" -> R.string.dbv_type_relation
        "calc" -> R.string.dbv_type_calc
        "created" -> R.string.dbv_type_created
        "edited" -> R.string.dbv_type_edited
        else -> R.string.dbv_type_text
    },
)

/** Wert als Text (Tabelle, Bedienungshilfen) in der Schreibweise der App-Sprache */
class DbFormat(private val locale: Locale, private val titleOf: (Long) -> String?) {
    private val day = DbProps.localDay(locale)
    private val num = DbProps.localNum(locale)
    private val dt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)

    fun number(p: DbProp, n: Double): String = when (p.fmt) {
        "percent" -> num(n) + " %"
        "euro" -> java.text.NumberFormat.getCurrencyInstance(locale).apply { currency = java.util.Currency.getInstance("EUR") }.format(n)
        else -> num(n)
    }

    fun day(iso: String): String = day.invoke(iso)

    fun text(p: DbProp, v: JsonElement?): String {
        if (v == null || v is JsonNull) return ""
        return when (p.type) {
            "select" -> p.option(v.prim())?.name ?: ""
            "multi_select" -> DbValues.ids(v).mapNotNull { p.option(it)?.name }.joinToString(", ")
            "checkbox" -> ""
            "number", "calc" -> (v as? JsonPrimitive)?.doubleOrNull?.let { number(p, it) } ?: v.prim().orEmpty()
            "date" -> DbValues.parseDate(v)?.let { day(it.toString()) } ?: v.prim().orEmpty()
            "created", "edited" -> (v as? JsonPrimitive)?.doubleOrNull?.let { dt.format(Date((it * 1000).toLong())) }.orEmpty()
            "relation" -> DbValues.relationIds(v).map { titleOf(it) ?: "#$it" }.joinToString(", ")
            else -> InlineHtml.plain(DbQuery.text(v))
        }
    }
}

private fun JsonElement?.prim(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

@Composable
fun rememberDbFormat(titleOf: (Long) -> String?): DbFormat {
    val locale = appLocale()
    return remember(locale) { DbFormat(locale, titleOf) }
}

/** Ein Wert in Tabelle/Karte/Liste: Auswahl als farbige Marke, Haekchen als Symbol, Fortschritt als Balken */
@Composable
fun DbValueView(p: DbProp, v: JsonElement?, fmt: DbFormat, maxLines: Int = 1) {
    when (p.type) {
        "select" -> p.option(v.prim())?.let { OptChip(it.name, it.color) }
        "multi_select" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DbValues.ids(v).mapNotNull { p.option(it) }.forEach { OptChip(it.name, it.color) }
        }
        "checkbox" -> {
            val on = DbQuery.truthy(v)
            val cd = stringResource(if (on) R.string.dbv_checked else R.string.dbv_unchecked)
            androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.size(24.dp).semantics { contentDescription = cd })
        }
        "number", "calc" -> {
            val n = (v as? JsonPrimitive)?.doubleOrNull
            if (n != null && p.fmt == "percent" && (p.show == "bar" || p.show == "ring")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(progress = { (n / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.width(56.dp))
                    Text(fmt.number(p, n), style = MaterialTheme.typography.bodySmall)
                }
            } else Text(fmt.text(p, v), maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        "url" -> Text(fmt.text(p, v), color = MaterialTheme.colorScheme.primary, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        else -> Text(fmt.text(p, v), maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Beschreibung fuer Bedienungshilfen: "Status: In Arbeit" */
@Composable
fun valueDescription(p: DbProp, v: JsonElement?, fmt: DbFormat): String {
    val t = when (p.type) {
        "checkbox" -> stringResource(if (DbQuery.truthy(v)) R.string.dbv_checked else R.string.dbv_unchecked)
        else -> fmt.text(p, v).ifEmpty { stringResource(R.string.dbv_value_empty) }
    }
    return stringResource(R.string.dbv_value_of, p.name, t)
}

/**
 * Wert bearbeiten - je Typ: Text/URL/Zahl als Eingabe, Auswahl/Mehrfachauswahl/Relation als Liste (mit Suche und
 * "neue Option"), Datum mit Kalender. Haekchen wird direkt umgeschaltet (kein Dialog). onSet(null) = leeren.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropValueEditor(
    p: DbProp,
    value: JsonElement?,
    onSet: (JsonElement?) -> Unit,
    onDismiss: () -> Unit,
    onNewOption: ((String) -> Unit)? = null,
    relationRows: (suspend (String) -> List<Pair<Long, String>>)? = null,
) {
    val locale = appLocale()
    when (p.type) {
        "select", "multi_select" -> OptionDialog(p, value, onSet, onDismiss, onNewOption)
        "relation" -> RelationDialog(p, value, onSet, onDismiss, relationRows)
        "date" -> {
            val init = DbValues.parseDate(value)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            val state = rememberDatePickerState(initialSelectedDateMillis = init)
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = {
                        onSet(DbValues.date(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }))
                        onDismiss()
                    }) { Text(stringResource(R.string.action_apply)) }
                },
                dismissButton = {
                    Row {
                        if (value != null && value !is JsonNull) TextButton(onClick = { onSet(JsonNull); onDismiss() }) { Text(stringResource(R.string.dbv_clear)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    }
                },
            ) { DatePicker(state) }
        }
        "checkbox", "calc", "created", "edited" -> LaunchedEffect(Unit) { onDismiss() }
        else -> {
            val num = p.type == "number"
            var text by remember { mutableStateOf(if (num) DbValues.editText(value, locale) else value.prim().orEmpty()) }
            var bad by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(p.name) },
                text = {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it; bad = false }, singleLine = p.type != "text", isError = bad,
                        supportingText = if (bad) ({ Text(stringResource(R.string.dbv_number_invalid)) }) else null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (p.type) {
                                "number" -> KeyboardType.Decimal
                                "url" -> KeyboardType.Uri
                                "email" -> KeyboardType.Email
                                "phone" -> KeyboardType.Phone
                                else -> KeyboardType.Text
                            },
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val v = if (num) DbValues.number(text, locale) else DbValues.text(text)
                        if (v == null) bad = true else {
                            onSet(v)
                            onDismiss()
                        }
                    }) { Text(stringResource(R.string.action_apply)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}

@Composable
private fun OptionDialog(p: DbProp, value: JsonElement?, onSet: (JsonElement?) -> Unit, onDismiss: () -> Unit, onNewOption: ((String) -> Unit)?) {
    val multi = p.type == "multi_select"
    var cur by remember { mutableStateOf(value) }
    var q by remember { mutableStateOf("") }
    val chosen = DbValues.ids(cur).toSet()
    AlertDialog(
        onDismissRequest = { if (multi) onSet(cur); onDismiss() },
        title = { Text(p.name) },
        text = {
            Column {
                OutlinedTextField(q, { q = it.take(40) }, singleLine = true, placeholder = { Text(stringResource(R.string.dbv_option_search)) }, modifier = Modifier.fillMaxWidth())
                val shown = p.options.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { o ->
                        val on = o.id in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable(role = if (multi) Role.Checkbox else Role.RadioButton) {
                                if (multi) cur = DbValues.toggle(cur, o.id) else {
                                    onSet(DbValues.select(value, o.id))
                                    onDismiss()
                                }
                            }.padding(vertical = 10.dp).semantics { contentDescription = o.name + if (on) ", " else "" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OptChip(o.name, o.color)
                            Spacer(Modifier.weight(1f))
                            if (on) Icon(Icons.Filled.Check, stringResource(R.string.dbv_checked), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    val name = q.trim()
                    if (onNewOption != null && name.isNotEmpty() && p.options.none { it.name.equals(name, ignoreCase = true) }) {
                        item(key = "new") {
                            Row(
                                Modifier.fillMaxWidth().clickable { onNewOption(name); onDismiss() }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Filled.Add, null)
                                Text(stringResource(R.string.dbv_new_option, name))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multi) TextButton(onClick = { onSet(cur); onDismiss() }) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = {
            Row {
                if (DbValues.ids(value).isNotEmpty()) TextButton(onClick = { onSet(JsonNull); onDismiss() }) { Text(stringResource(R.string.dbv_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun RelationDialog(p: DbProp, value: JsonElement?, onSet: (JsonElement?) -> Unit, onDismiss: () -> Unit, source: (suspend (String) -> List<Pair<Long, String>>)?) {
    var cur by remember { mutableStateOf(value) }
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    LaunchedEffect(q) {
        kotlinx.coroutines.delay(250)
        found = try {
            source?.invoke(q).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    val chosen = DbValues.relationIds(cur).toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column {
                OutlinedTextField(q, { q = it.take(200) }, singleLine = true, placeholder = { Text(stringResource(R.string.dbv_relation_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    if (found.isEmpty()) item { Text(stringResource(R.string.dbv_no_entries), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(found, key = { it.first }) { (id, title) ->
                        val on = id in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { cur = DbValues.toggleRelation(cur, id) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title.ifEmpty { stringResource(R.string.page_untitled) }, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (on) Icon(Icons.Filled.Check, stringResource(R.string.dbv_checked), tint = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSet(cur ?: JsonNull); onDismiss() }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Zeile "Name  Wert" zum Antippen (Eintrag als Seite, Karte) */
@Composable
fun PropRow(p: DbProp, v: JsonElement?, fmt: DbFormat, editable: Boolean, onEdit: () -> Unit, onToggle: () -> Unit, onLink: ((String) -> Unit)? = null) {
    val desc = valueDescription(p, v, fmt)
    val canEdit = editable && p.editable
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .then(if (canEdit) Modifier.clickable(onClickLabel = stringResource(R.string.dbv_edit_prop, p.name), role = if (p.type == "checkbox") Role.Checkbox else Role.Button) { if (p.type == "checkbox") onToggle() else onEdit() } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(p.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f)) {
            if (DbQuery.isEmpty(v) && p.type != "checkbox") {
                Text(if (canEdit) stringResource(R.string.dbv_value_empty) else "", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
            } else DbValueView(p, v, fmt, maxLines = 4)
        }
        val link = if (p.type == "url" || p.type == "email" || p.type == "phone") DbValues.safeLink(p.type, v.prim().orEmpty()) else null
        if (link != null && onLink != null) {
            TextButton(onClick = { onLink(link) }) { Text(stringResource(R.string.dbv_open_link)) }
        }
    }
}
