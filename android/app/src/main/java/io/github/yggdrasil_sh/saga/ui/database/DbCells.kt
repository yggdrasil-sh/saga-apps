package io.github.yggdrasil_sh.saga.ui.database

import io.github.yggdrasil_sh.saga.ui.theme.SagaTextButton
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcons
import io.github.yggdrasil_sh.saga.ui.theme.SagaIcon
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import io.github.yggdrasil_sh.saga.ui.theme.SagaRadius
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Farbe einer Option (wie .opt-<farbe> im Web) - als Hintergrund mit Deckkraft, Text in Vordergrundfarbe.
 * Grundton Web --hue-* (Nutzerfarben-Palette, eine Quelle: Gestalten.baseRgb), unbekannt -> Grau.
 */
fun optColor(name: String?): Color = Color(io.github.yggdrasil_sh.saga.blocks.Gestalten.baseRgb(OptPalette.norm(name)) or (0xFF shl 24))

/** A11Y-03/A11Y-20: Chip wie im Web - Tonfarbe --tc-* auf zarter Flaeche --bc-*, 6-dp-Ecken (OptPalette) */
@Composable
fun OptChip(name: String, color: String?) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Text(
        name.ifEmpty { "–" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = Color(OptPalette.textArgb(color, dark)),
        modifier = Modifier.clip(RoundedCornerShape(SagaRadius.xs)).background(Color(OptPalette.bgArgb(color, dark)))
            .padding(horizontal = 6.dp, vertical = 1.dp),
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
        "formula" -> R.string.e1_type_formula
        "rollup" -> R.string.e1_type_rollup
        "person" -> R.string.e1_type_person
        "files" -> R.string.e1_type_files
        else -> R.string.dbv_type_text
    },
)

/** Wert als Text (Tabelle, Bedienungshilfen) in der Schreibweise der App-Sprache */
class DbFormat(
    private val locale: Locale,
    private val titleOf: (Long) -> String?,
    /** 2.5 person1: Personen der Antwort (nie Anmeldenamen) */
    val person: (String) -> io.github.yggdrasil_sh.saga.db.DbPerson? = { null },
    val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    /** Text fuer geloeschte/unbekannte Personen */
    private val gone: String = "–",
) {
    private val day = DbProps.localDay(locale)
    private val num = DbProps.localNum(locale)
    private val dt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)

    fun number(p: DbProp, n: Double): String = when (p.fmt) {
        "percent", "euro" -> io.github.yggdrasil_sh.saga.blocks.NumFormat.prop(n, p.fmt, locale)
        else -> num(n)
    }

    fun day(iso: String): String = day.invoke(iso)

    /** Name einer Person: Anzeigename, geloescht -> Hinweis, unbekannt -> Kurzform der Kennung (nie Anmeldename) */
    fun personName(ref: String): String = person(ref)?.let { if (it.gone) gone else it.name.ifBlank { null } } ?: gone

    fun text(p: DbProp, v: JsonElement?): String {
        if (v == null || v is JsonNull) return ""
        if (p.computed) return computedText(p, v)
        return when (p.type) {
            "select" -> p.option(v.prim())?.let { o -> if (p.status) io.github.yggdrasil_sh.saga.db.DbProp.groupSymbol(o.group) + " " + o.name else o.name } ?: ""
            "person" -> DbValues.ids(v).joinToString(", ") { personName(it) }
            "files" -> (v as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.let { o -> (o["name"] as? JsonPrimitive)?.content } }?.joinToString(", ").orEmpty()
            "multi_select" -> DbValues.ids(v).mapNotNull { p.option(it)?.name }.joinToString(", ")
            "checkbox" -> ""
            "number", "calc" -> (v as? JsonPrimitive)?.doubleOrNull?.let { number(p, it) } ?: v.prim().orEmpty()
            "date" -> io.github.yggdrasil_sh.saga.db.DbDate.display(v.prim(), zone, locale) ?: DbValues.parseDate(v)?.let { day(it.toString()) } ?: v.prim().orEmpty()
            "created", "edited" -> (v as? JsonPrimitive)?.doubleOrNull?.let { dt.format(Date((it * 1000).toLong())) }.orEmpty()
            "relation" -> DbValues.relationIds(v).map { titleOf(it) ?: "#$it" }.joinToString(", ")
            else -> InlineHtml.plain(DbQuery.text(v))
        }
    }

    /** 2.5 Formel/Rollup: Wert je rtype (Zahl wie Zahl-Spalte, Datum lesbar, Liste "a, b", Haekchen leer) */
    fun computedText(p: DbProp, v: JsonElement): String = when (p.rtype) {
        "number" -> (v as? JsonPrimitive)?.doubleOrNull?.let { number(p, it) } ?: v.prim().orEmpty()
        "date" -> io.github.yggdrasil_sh.saga.db.DbDate.display(v.prim(), zone, locale) ?: v.prim().orEmpty()
        "checkbox" -> ""
        "list" -> (v as? kotlinx.serialization.json.JsonArray)?.joinToString(", ") { DbQuery.text(it) } ?: DbQuery.text(v)
        else -> DbQuery.text(v)
    }
}

private fun JsonElement?.prim(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

@Composable
fun rememberDbFormat(titleOf: (Long) -> String?): DbFormat {
    val locale = appLocale()
    return remember(locale) { DbFormat(locale, titleOf) }
}

/** 2.5: mit Personen-Verzeichnis (Name, Avatar, "ich", geloescht) */
@Composable
fun rememberDbFormat(titleOf: (Long) -> String?, people: Map<String, io.github.yggdrasil_sh.saga.db.DbPerson>): DbFormat {
    val locale = appLocale()
    val gone = stringResource(R.string.e1_person_gone)
    return remember(locale, people, gone) { DbFormat(locale, titleOf, { people[it] }, gone = gone) }
}

/** Person als Chip: Avatar (oder Initiale), Name, "ich" hervorgehoben, geloescht ausgegraut */
@Composable
fun PersonChip(ref: String, fmt: DbFormat) {
    val p = fmt.person(ref)
    val gone = p == null || p.gone
    val name = fmt.personName(ref)
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(SagaRadius.md)).background(if (p?.me == true) cs.primaryContainer.copy(alpha = 0.6f) else cs.surfaceContainerHigh)
            .padding(start = 2.dp, end = 6.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(16.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (gone) cs.outlineVariant else cs.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (!gone && p?.avatar != null) {
                val repo = LocalDbRepo.current
                coil3.compose.AsyncImage(model = repo?.absoluteUrl(p.avatar) ?: p.avatar, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(16.dp))
            } else Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onTertiaryContainer)
        }
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (gone) cs.onSurfaceVariant else cs.onSurface)
    }
}

/** Fuer Avatare/Dateien: Repository (absolute Adressen); null in Vorschauen */
val LocalDbRepo = androidx.compose.runtime.staticCompositionLocalOf<io.github.yggdrasil_sh.saga.data.SagaRepository?> { null }

/** Ein Wert in Tabelle/Karte/Liste: Auswahl als farbige Marke, Haekchen als Symbol, Fortschritt als Balken */
@Composable
fun DbValueView(p: DbProp, v: JsonElement?, fmt: DbFormat, maxLines: Int = 1, err: String? = null) {
    // 2.5 Formel/Rollup: Laufzeitfehler je Zelle
    if (p.computed && err != null) {
        val why = stringResource(R.string.e1_fx_error_hint, err)
        Text(stringResource(R.string.e1_fx_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = why })
        return
    }
    if (p.computed) {
        when (p.rtype) {
            "checkbox" -> {
                val on = DbQuery.truthy(v)
                val cd = stringResource(if (on) R.string.dbv_checked else R.string.dbv_unchecked)
                androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null, enabled = false, modifier = Modifier.size(24.dp).semantics { contentDescription = cd })
            }
            "list" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ((v as? kotlinx.serialization.json.JsonArray) ?: listOfNotNull(v)).forEach { OptChip(DbQuery.text(it), "gray") }
            }
            "number" -> {
                val n = (v as? JsonPrimitive)?.doubleOrNull
                if (n != null && p.fmt == "percent" && (p.show == "bar" || p.show == "ring")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(progress = { (n / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.width(56.dp), trackColor = io.github.yggdrasil_sh.saga.ui.theme.SagaProgressTrack)
                        Text(fmt.number(p, n), style = MaterialTheme.typography.bodySmall)
                    }
                } else Text(fmt.text(p, v), maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            else -> Text(fmt.text(p, v), maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    when (p.type) {
        "person" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DbValues.ids(v).forEach { PersonChip(it, fmt) }
        }
        "files" -> FilesView(v)
        "select" -> p.option(v.prim())?.let { OptChip(if (p.status) io.github.yggdrasil_sh.saga.db.DbProp.groupSymbol(it.group) + " " + it.name else it.name, it.color) }
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
                    LinearProgressIndicator(progress = { (n / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.width(56.dp), trackColor = io.github.yggdrasil_sh.saga.ui.theme.SagaProgressTrack)
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
    val t = when (p.effType) {
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
    /** 2.5: Personen-Auswahl, Datei-Upload, Server kann Datum mit Uhrzeit (datetime1) */
    people: (suspend (String) -> List<io.github.yggdrasil_sh.saga.db.DbPerson>)? = null,
    upload: (suspend (android.net.Uri) -> io.github.yggdrasil_sh.saga.db.DbFiles.File?)? = null,
    fmt: DbFormat? = null,
    datetime: Boolean = false,
) {
    val locale = appLocale()
    if (p.computed) {
        LaunchedEffect(Unit) { onDismiss() }                         // Formel/Rollup: nur im Web
        return
    }
    when (p.type) {
        "person" -> if (people != null && fmt != null) PersonDialog(p, value, fmt, people, onSet, onDismiss) else LaunchedEffect(Unit) { onDismiss() }
        "files" -> if (upload != null) FilesDialog(p, value, upload, onSet, onDismiss) else LaunchedEffect(Unit) { onDismiss() }
        // ED-26: direkt der Kalender (mit Schnellwahl); Uhrzeit/Ende nur mit datetime1
        "date" -> DbDlgDateDialog(p, value, datetime, onSet, onDismiss)
        else -> PropValueEditorBase(p, value, onSet, onDismiss, onNewOption, relationRows, locale)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropValueEditorBase(
    p: DbProp, value: JsonElement?, onSet: (JsonElement?) -> Unit, onDismiss: () -> Unit,
    onNewOption: ((String) -> Unit)?, relationRows: (suspend (String) -> List<Pair<Long, String>>)?, locale: Locale,
) {
    when (p.type) {
        "select", "multi_select" -> OptionDialog(p, value, onSet, onDismiss, onNewOption)
        "relation" -> RelationDialog(p, value, onSet, onDismiss, relationRows)
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
                // ED-24: "Leeren" links abgesetzt, rechts Abbrechen und Uebernehmen
                confirmButton = {
                    DbDlgButtons(
                        onCancel = onDismiss,
                        clearLabel = stringResource(R.string.dbv_clear),
                        onClear = if (!DbQuery.isEmpty(value)) ({ onSet(JsonNull); onDismiss() }) else null,
                        onConfirm = {
                            val v = if (num) DbValues.number(text, locale) else DbValues.text(text)
                            if (v == null) bad = true else {
                                onSet(v)
                                onDismiss()
                            }
                        },
                    )
                },
            )
        }
    }
}

/**
 * Auswahl/Mehrfachauswahl (ED-24/-25): Einfachauswahl uebernimmt beim Tippen sofort. Mehrfachauswahl uebernimmt erst
 * mit "Übernehmen"; Tippen ausserhalb verwirft (mit "Rückgängig", falls etwas geaendert war). "Leeren" links.
 */
@Composable
private fun OptionDialog(p: DbProp, value: JsonElement?, onSet: (JsonElement?) -> Unit, onDismiss: () -> Unit, onNewOption: ((String) -> Unit)?) {
    val multi = p.type == "multi_select"
    var cur by remember { mutableStateOf(value) }
    var q by remember { mutableStateOf("") }
    val chosen = DbValues.ids(cur).toSet()
    val discard = rememberDbDlgDiscard()
    val changed = multi && DbDlg.changed(DbValues.ids(value), DbValues.ids(cur))
    AlertDialog(
        onDismissRequest = { discard.dismiss(changed, close = onDismiss, undo = { onSet(cur ?: JsonNull) }) },
        title = { Text(p.name) },
        text = {
            Column {
                OutlinedTextField(q, { q = it.take(40) }, singleLine = true, placeholder = { Text(stringResource(R.string.dbv_option_search)) }, modifier = Modifier.fillMaxWidth())
                // 2.5 Status: nach Gruppen geordnet (Zu erledigen, In Arbeit, Erledigt), Gruppensymbol vor dem Namen
                val shown = p.optionsGrouped().filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { o ->
                        DbDlgOptionRow(multi, selected = o.id in chosen, onClick = {
                            if (multi) cur = DbValues.toggle(cur, o.id) else {
                                onSet(DbValues.select(value, o.id))
                                onDismiss()
                            }
                        }) {
                            OptChip(if (p.status) io.github.yggdrasil_sh.saga.db.DbProp.groupSymbol(o.group) + " " + o.name else o.name, o.color)
                        }
                    }
                    val name = q.trim()
                    if (onNewOption != null && name.isNotEmpty() && p.options.none { it.name.equals(name, ignoreCase = true) }) {
                        item(key = "new") {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.dbv_new_option, name)) },
                                leadingContent = { SagaIcon(SagaIcons.plus, null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onNewOption(name); onDismiss() },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            DbDlgButtons(
                onCancel = onDismiss,
                clearLabel = stringResource(R.string.dbv_clear),
                onClear = if (DbValues.ids(value).isNotEmpty()) ({ onSet(JsonNull); onDismiss() }) else null,
                onConfirm = if (multi) ({ onSet(cur ?: JsonNull); onDismiss() }) else null,
            )
        },
    )
    DbDlgDiscardAsk(discard, close = onDismiss)
}

/** Relation (Mehrfachauswahl): Zeilen mit Checkbox, "Übernehmen"; ausserhalb tippen verwirft (ED-24) */
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
    val discard = rememberDbDlgDiscard()
    val changed = DbDlg.changed(DbValues.relationIds(value), DbValues.relationIds(cur))
    AlertDialog(
        onDismissRequest = { discard.dismiss(changed, close = onDismiss, undo = { onSet(cur ?: JsonNull) }) },
        title = { Text(p.name) },
        text = {
            Column {
                OutlinedTextField(q, { q = it.take(200) }, singleLine = true, placeholder = { Text(stringResource(R.string.dbv_relation_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    if (found.isEmpty()) item { Text(stringResource(R.string.dbv_no_entries), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(found, key = { it.first }) { (id, title) ->
                        DbDlgOptionRow(multi = true, selected = id in chosen, onClick = { cur = DbValues.toggleRelation(cur, id) }) {
                            Text(title.ifEmpty { stringResource(R.string.page_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            DbDlgButtons(
                onCancel = onDismiss,
                clearLabel = stringResource(R.string.dbv_clear),
                onClear = if (DbValues.relationIds(value).isNotEmpty()) ({ onSet(JsonNull); onDismiss() }) else null,
                onConfirm = { onSet(cur ?: JsonNull); onDismiss() },
            )
        },
    )
    DbDlgDiscardAsk(discard, close = onDismiss)
}

/**
 * Zeile "Name  Wert" zum Antippen (Eintrag als Seite, Karte). ED-28: mindestens 48 dp, Ripple ueber die volle Breite,
 * leere Werte als Platzhalter "Hinzufügen …" (onSurfaceVariant 60 %), Hover-Zustand fuer Maus/Trackpad (Tablet).
 */
@Composable
fun PropRow(p: DbProp, v: JsonElement?, fmt: DbFormat, editable: Boolean, onEdit: () -> Unit, onToggle: () -> Unit, onLink: ((String) -> Unit)? = null, err: String? = null) {
    val desc = valueDescription(p, v, fmt)
    val canEdit = editable && p.editable
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(SagaRadius.xs))
            .then(if (canEdit && hovered) Modifier.background(cs.onSurface.copy(alpha = 0.06f)) else Modifier)
            .then(
                if (canEdit) Modifier.hoverable(src).clickable(
                    interactionSource = src, indication = ripple(),
                    onClickLabel = stringResource(R.string.dbv_edit_prop, p.name), role = if (p.type == "checkbox") Role.Checkbox else Role.Button,
                ) { if (p.type == "checkbox") onToggle() else onEdit() } else Modifier,
            )
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(p.name, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            modifier = Modifier.width(120.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f)) {
            if (DbQuery.isEmpty(v) && p.effType != "checkbox" && err == null) {
                Text(if (canEdit) stringResource(R.string.r3d_add_value) else "", color = cs.onSurfaceVariant.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            } else DbValueView(p, v, fmt, maxLines = 4, err = err)
            if (p.computed) Text(stringResource(R.string.e1_fx_web), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        val link = if (p.type == "url" || p.type == "email" || p.type == "phone") DbValues.safeLink(p.type, v.prim().orEmpty()) else null
        if (link != null && onLink != null) {
            SagaTextButton(onClick = { onLink(link) }) { Text(stringResource(R.string.dbv_open_link)) }
        }
    }
}
