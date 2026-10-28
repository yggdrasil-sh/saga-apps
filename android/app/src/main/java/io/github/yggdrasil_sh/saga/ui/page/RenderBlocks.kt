package io.github.yggdrasil_sh.saga.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.blocks.BlockDoc
import io.github.yggdrasil_sh.saga.blocks.JsonPath
import io.github.yggdrasil_sh.saga.blocks.RenderPreview
import kotlinx.serialization.json.JsonObject

// 2.5 P6-2: Mathe (`math`) und Mermaid (`code` mit language mermaid) als Vorschaubild aus dem Web (data.render.png),
// Quelltext bleibt bearbeitbar; `render` wird beim Speichern unveraendert mitgeschickt (der Server markiert es dann
// selbst als veraltet). Synchronisierte Bloecke (`synced`) nur anzeigen, mit Sprung zum Original.

/** Vorschaubild (weisser Grund wie im Web) + Hinweis "veraltet" */
@Composable
fun RenderImage(r: RenderPreview.Render, label: String, env: BlockEnv) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val ratio = r.ratio
        AsyncImage(
            model = env.repo.absoluteUrl(r.png), contentDescription = label, contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = (r.w ?: 600).coerceIn(24, 900).dp).fillMaxWidth()
                .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.heightIn(min = 32.dp))
                .clip(RoundedCornerShape(6.dp)).background(Color.White).padding(4.dp),
        )
        if (r.stale) {
            Text(stringResource(R.string.e1_render_stale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun MathBlock(block: JsonObject, path: JsonPath, key: String, env: BlockEnv) {
    val d = BlockDoc.data(block)
    val tex = BlockDoc.str(d, "tex")
    val r = RenderPreview.render(d)
    val ed = env.editor
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (r != null) RenderImage(r, tex, env)
        // Quelltext: ohne Bild immer sichtbar, sonst klein darunter (bearbeitbar)
        if (r == null || ed.editable) {
            Row(verticalAlignment = Alignment.Top) {
                Text("∑ ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RichField(
                    tex, key, ed, path, MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    { ed.setString(path + listOf("data", "tex"), it.take(RenderPreview.TEX_MAX)) },
                    placeholder = stringResource(R.string.e1_math_placeholder), raw = true,
                )
            }
        }
    }
}

/** Mermaid: Vorschaubild ueber dem (bearbeitbaren) Code; ohne render nur Code (Aufrufer zeigt den Code-Block) */
@Composable
fun MermaidPreview(d: JsonObject, env: BlockEnv) {
    val r = RenderPreview.render(d) ?: return
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) { RenderImage(r, "Mermaid", env) }
}

@Composable
fun SyncedBlock(block: JsonObject, path: JsonPath, env: BlockEnv) {
    val d = BlockDoc.data(block)
    val src = RenderPreview.syncedSource(d)
    val ownOriginal = src == null && RenderPreview.syncedHasBlocks(d)
    val label = stringResource(R.string.e1_synced)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(10.dp)
            .semantics { contentDescription = label },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⟳ " + label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
            if (src != null && src.note != null && src.note != env.editor.id) {
                TextButton(onClick = { env.onLink("/notes#${src.note}") }) { Text(stringResource(R.string.e1_synced_open)) }
            }
        }
        if (!RenderPreview.syncedHasBlocks(d)) {
            Text(stringResource(R.string.e1_synced_noaccess), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        // Inhalt nur anzeigen (Bearbeiten im Web, P6-8): auch das Original, damit die App nichts halb aendert
        ReadOnlyBlocks(path + listOf("data", "blocks"), env)
        if (ownOriginal && env.editor.editable) {
            Text(stringResource(R.string.e1_synced_edit_web), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bloecke nur anzeigen (synchronisierter Inhalt): Texte formatiert, Listen als Punkte, sonst Typname */
@Composable
fun ReadOnlyBlocks(arrayPath: JsonPath, env: BlockEnv) {
    val arr = io.github.yggdrasil_sh.saga.blocks.JsonTree.get(env.editor.doc, arrayPath) as? kotlinx.serialization.json.JsonArray ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        arr.forEach { el ->
            val b = el as? JsonObject ?: return@forEach
            val d = BlockDoc.data(b)
            when (BlockDoc.type(b)) {
                "paragraph", "quote", "callout" -> RichTextView(BlockDoc.str(d, "text"), MaterialTheme.typography.bodyLarge, env.onLink)
                "header" -> RichTextView(BlockDoc.str(d, "text"), headerStyle(BlockDoc.int(d, "level") ?: 2), env.onLink)
                "list" -> (d["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().forEach { it2 ->
                    val t = (it2 as? JsonObject)?.let { o -> BlockDoc.str(o, "content") } ?: (it2 as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    Row { Text("• ", color = MaterialTheme.colorScheme.onSurfaceVariant); RichTextView(t, MaterialTheme.typography.bodyLarge, env.onLink) }
                }
                "code" -> Text(BlockDoc.str(d, "code"), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                "math" -> RenderPreview.render(d)?.let { RenderImage(it, BlockDoc.str(d, "tex"), env) } ?: Text(BlockDoc.str(d, "tex"), fontFamily = FontFamily.Monospace)
                else -> Text("▪ " + BlockDoc.type(b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
