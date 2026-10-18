package io.github.yggdrasil_sh.saga.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.github.yggdrasil_sh.saga.ai.AiPersona
import io.github.yggdrasil_sh.saga.ai.AiPersonaLogic
import io.github.yggdrasil_sh.saga.ui.common.AvatarLogic
import io.github.yggdrasil_sh.saga.util.ServerUrl

/**
 * Profilbild der KI (2.1): rund, neutrales Symbol ✦ auf Akzentfarbe; liegt ein Bild vor (persona.avatar), darueber.
 * Zwischenspeicher je avatar_etag (gleiche Kennung = kein neues Laden), Adresse nur /api/ai/avatar des eigenen Servers.
 */
@Composable
fun AiAvatar(persona: AiPersona?, size: Int = 24) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text("✦", color = MaterialTheme.colorScheme.onPrimary, fontSize = (size * 0.55f).sp, fontWeight = FontWeight.Bold)
        val p = persona ?: return@Box
        val px = with(LocalDensity.current) { size.dp.roundToPx() }
        val tier = AvatarLogic.tier(px)
        val server = AvatarLogic.server()
        if (server.isEmpty() || !AiPersonaLogic.avatarPathOk(p.avatar)) return@Box
        val a = p.avatar!!.trim()
        val url = ServerUrl.absolute(server, a) + (if (a.contains('?')) "&" else "?") + "s=" + tier + "&fmt=webp"
        var gone by remember(url) { mutableStateOf(false) }
        if (gone) return@Box
        val key = AiPersonaLogic.cacheKey(p, tier)
        val ctx = LocalPlatformContext.current
        val req = remember(url, key) { ImageRequest.Builder(ctx).data(url).memoryCacheKey(key).diskCacheKey(key).build() }
        AsyncImage(
            model = req, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
            onError = { gone = true },                       // 404 = kein Bild: Symbol bleibt
        )
    }
}

/** Kopfzeile einer KI-Antwort: Profilbild + Name (+ "gefragt von …") */
@Composable
fun AiWho(persona: AiPersona?, name: String, by: String? = null, size: Int = 20) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AiAvatar(persona, size)
        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
        if (by != null) {
            Text(
                "· $by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
