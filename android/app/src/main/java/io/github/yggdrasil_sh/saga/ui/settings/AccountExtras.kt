package io.github.yggdrasil_sh.saga.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.call.CallNotify
import io.github.yggdrasil_sh.saga.data.ApiException
import io.github.yggdrasil_sh.saga.data.AvatarState
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.SecurityLogic
import io.github.yggdrasil_sh.saga.data.SecurityStatus
import io.github.yggdrasil_sh.saga.data.avatarState
import io.github.yggdrasil_sh.saga.data.deleteAvatar
import io.github.yggdrasil_sh.saga.data.patchAvatar
import io.github.yggdrasil_sh.saga.data.securityStatus
import io.github.yggdrasil_sh.saga.data.uploadAvatar
import io.github.yggdrasil_sh.saga.ui.common.openUrl
import io.github.yggdrasil_sh.saga.ui.notifications.Avatar
import io.github.yggdrasil_sh.saga.ui.push.SwitchRow
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------------------- Anmeldung & Sicherheit

/**
 * "Anmeldung & Sicherheit" (2.1): Statuszeile aus GET /api/account/security, Verwalten im System-Browser (Custom Tab
 * -> <server>/security; dort gehen Passkeys, Authenticator-App und Wiederherstellungscodes). Nicht nativ nachgebaut.
 * Unsichtbar bei Servern ohne 2FA (404) bzw. Konten, die es nicht nutzen koennen (SSO: supported = false).
 */
@Composable
fun SecurityBlock(repo: SagaRepository, modifier: Modifier, reloadKey: Any?) {
    val context = LocalContext.current
    var st by remember { mutableStateOf<SecurityStatus?>(null) }
    LaunchedEffect(reloadKey) {
        st = try {
            repo.securityStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val s = st ?: return
    if (!s.supported) return
    val res = androidx.compose.ui.platform.LocalResources.current
    val details = buildList {
        if (s.passkeys.isNotEmpty()) add(res.getQuantityString(R.plurals.security_passkeys, s.passkeys.size, s.passkeys.size))
        if (s.totp) add(stringResource(R.string.security_totp))
        if (s.enabled) add(res.getQuantityString(R.plurals.security_recovery_left, s.recoveryLeft, s.recoveryLeft))
    }
    Column(modifier) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.security_title)) },
            supportingContent = {
                Column {
                    Text(stringResource(if (s.enabled) R.string.security_on else R.string.security_off))
                    if (details.isNotEmpty()) {
                        Text(
                            details.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = if (SecurityLogic.recoveryLow(s)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (SecurityLogic.mustSetUp(s)) {
                        Text(stringResource(R.string.security_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            // Der Browser zeigt die Seite des DORT angemeldeten Kontos - Konto der App nennen und mitgeben (user=),
            // damit der Server bei einem anderen Konto warnen kann
            val account = repo.account.collectAsState().value.trim()
            OutlinedButton(onClick = { openUrl(context, io.github.yggdrasil_sh.saga.auth.LoginFlow.securityUrl(repo.server.value, account), inApp = true) }) {
                Text(stringResource(R.string.security_manage))
            }
            if (account.isNotEmpty()) {
                Text(
                    stringResource(R.string.security_account_check, account), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                stringResource(R.string.security_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------- Anrufe: Vollbild

/** Android 14+: ohne "Vollbild-Benachrichtigungen" klingeln Anrufe nur als Mitteilung - Hinweis + Link */
@Composable
fun CallsFullScreenHint(modifier: Modifier) {
    val context = LocalContext.current
    var can by remember { mutableStateOf(CallNotify.canFullScreen(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.currentStateFlow.collect { if (it == androidx.lifecycle.Lifecycle.State.RESUMED) can = CallNotify.canFullScreen(context) }
    }
    if (can || Build.VERSION.SDK_INT < 34) return
    ListItem(
        headlineContent = { Text(stringResource(R.string.call_settings_title)) },
        supportingContent = { Text(stringResource(R.string.call_fullscreen_hint)) },
        trailingContent = {
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName)))
                } catch (_: Exception) {
                }
            }) { Text(stringResource(R.string.call_fullscreen_allow)) }
        },
        modifier = modifier,
    )
}

// ------------------------------------------------------------------------------------------- Anrufe: ohne UnifiedPush

/**
 * Ohne Mitteilungs-Verteiler (UnifiedPush) gibt es im Hintergrund keinen Weg zum Geraet: Anrufe klingeln nur bei
 * geoeffneter App. Hinweis + "Einrichten" (Mitteilungen); nach Rueckkehr aus der Einrichtung neu pruefen.
 */
@Composable
fun CallsPushHint(push: io.github.yggdrasil_sh.saga.push.PushService, modifier: Modifier, onSetup: () -> Unit) {
    var ready by remember { mutableStateOf(true) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.currentStateFlow.collect {
            if (it == androidx.lifecycle.Lifecycle.State.RESUMED) ready = withContext(Dispatchers.IO) { push.backgroundReady() }
        }
    }
    if (ready) return
    ListItem(
        headlineContent = { Text(stringResource(R.string.call_push_title)) },
        supportingContent = { Text(stringResource(R.string.call_push_hint)) },
        trailingContent = { TextButton(onClick = onSetup) { Text(stringResource(R.string.call_push_setup)) } },
        modifier = modifier,
    )
}

/** Einmaliger Hinweis nach dem ersten Anruf ohne UnifiedPush */
@Composable
fun CallPushHintDialog(onSetup: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_push_title)) },
        text = { Text(stringResource(R.string.call_push_hint)) },
        confirmButton = { TextButton(onClick = onSetup) { Text(stringResource(R.string.call_push_setup)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.call_push_later)) } },
    )
}

// ------------------------------------------------------------------------------------------- Profilbild

/**
 * Eigenes Profilbild (2.1, wie Web): runde Vorschau, Quelle, "Bild hochladen …" (Foto-Auswahl des Systems -> eigener
 * Kreis-Zuschnitt -> JPEG 768 px, PUT Rohinhalt), "Eigenes Bild entfernen", Schalter Gravatar / veroeffentlichte Seiten.
 */
@Composable
fun ProfilePictureBlock(repo: SagaRepository, account: String, modifier: Modifier, reloadKey: Any?, onState: (AvatarState?) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<AvatarState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<UiText?>(null) }
    var crop by remember { mutableStateOf<Bitmap?>(null) }
    fun set(s: AvatarState?) {
        st = s
        onState(s)
    }
    LaunchedEffect(reloadKey) {
        try {
            set(repo.avatarState())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            set(null)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { AvatarCrop.load(context, uri) }
            if (bmp == null) err = UiText.Res(R.string.avatar_bad_image) else crop = bmp
        }
    }
    fun run(block: suspend () -> AvatarState, done: UiText? = null) {
        busy = true
        err = null
        scope.launch {
            try {
                set(block())
                done?.let { repo.notify(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                err = if (e.code == 413) UiText.Res(R.string.avatar_too_big, st?.maxMb ?: 5) else repo.friendly(e)
            } catch (e: Exception) {
                err = repo.friendly(e)
            } finally {
                busy = false
            }
        }
    }
    val s = st ?: return
    Column(modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(account, size = 72, avatar = s.url)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(stringResource(R.string.avatar_title), style = MaterialTheme.typography.titleMedium)
                val src = when (s.source) {
                    "upload" -> stringResource(R.string.avatar_src_upload)
                    "sso" -> stringResource(R.string.avatar_src_sso)
                    "gravatar" -> "Gravatar"
                    else -> null
                }
                Text(
                    if (src != null) stringResource(R.string.avatar_showing, src) else stringResource(R.string.avatar_none),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp))
        }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                Text(stringResource(R.string.avatar_upload))
            }
            if (s.upload) {
                TextButton(onClick = { run({ repo.deleteAvatar() }, UiText.Res(R.string.avatar_removed)) }, enabled = !busy) {
                    Text(stringResource(R.string.avatar_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        err?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (s.gravatarEnabled) {
            SwitchRow(stringResource(R.string.avatar_gravatar), s.gravatar, Modifier, enabled = !busy) { on -> run({ repo.patchAvatar(gravatar = on) }) }
        }
        SwitchRow(stringResource(R.string.avatar_public), s.public, Modifier, enabled = !busy) { on -> run({ repo.patchAvatar(public = on) }) }
    }
    crop?.let { b ->
        AvatarCropDialog(b, onCancel = { crop = null }) { (data, mime) ->
            crop = null
            run({ repo.uploadAvatar(data, mime) }, UiText.Res(R.string.avatar_saved))
        }
    }
}

/** Bild laden (verkleinert, EXIF-Ausrichtung angewendet) und quadratisch zuschneiden - rein bis auf das Laden */
object AvatarCrop {
    const val OUT = 768
    private const val MAX_IN = 2048

    fun load(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val f = max(w, h).toFloat() / MAX_IN
                if (f > 1f) dec.setTargetSize((w / f).roundToInt().coerceAtLeast(1), (h / f).roundToInt().coerceAtLeast(1))
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > MAX_IN) sample *= 2
            val b = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            val rot = context.contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f
                    3 -> 180f
                    8 -> 270f
                    else -> 0f
                }
            } ?: 0f
            if (b != null && rot != 0f) Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot) }, true) else b
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Ausschnitt im Bild (Pixel) aus Ansicht: Kantenlaenge view, Bild skaliert mit scale (Bildpunkte -> Ansicht),
     * Bild-Ursprung bei offset (<= 0). -> (links, oben, Kante), immer innerhalb des Bildes.
     */
    fun cropRect(imgW: Int, imgH: Int, view: Float, scale: Float, offset: Offset): Triple<Int, Int, Int> {
        val side = (view / scale).roundToInt().coerceIn(1, min(imgW, imgH))
        val l = (-offset.x / scale).roundToInt().coerceIn(0, imgW - side)
        val t = (-offset.y / scale).roundToInt().coerceIn(0, imgH - side)
        return Triple(l, t, side)
    }

    /** Verschiebung begrenzen: das Bild deckt die (quadratische) Ansicht immer ganz ab */
    fun clamp(offset: Offset, imgW: Int, imgH: Int, view: Float, scale: Float): Offset =
        Offset(offset.x.coerceIn(min(0f, view - imgW * scale), 0f), offset.y.coerceIn(min(0f, view - imgH * scale), 0f))

    /** Bild mit Transparenz als PNG (der Server behaelt Alpha), sonst JPEG - JPEG machte transparente Ecken schwarz */
    fun mimeFor(hasAlpha: Boolean): String = if (hasAlpha) "image/png" else "image/jpeg"

    /** Zuschnitt kodieren -> (Bytes, MIME-Typ) */
    fun encode(b: Bitmap, l: Int, t: Int, side: Int): Pair<ByteArray, String> {
        val sq = Bitmap.createBitmap(b, l, t, side, side)
        val out = if (side == OUT) sq else Bitmap.createScaledBitmap(sq, min(OUT, side).coerceAtLeast(64), min(OUT, side).coerceAtLeast(64), true)
        val mime = mimeFor(out.hasAlpha())
        return ByteArrayOutputStream().use {
            out.compress(if (mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)
            it.toByteArray()
        } to mime
    }
}

/** Kreis-Zuschnitt: verschieben und zoomen (zwei Finger oder Regler), Speichern -> JPEG bzw. PNG (Transparenz) */
@Composable
private fun AvatarCropDialog(bmp: Bitmap, onCancel: () -> Unit, onDone: (Pair<ByteArray, String>) -> Unit) {
    val img: ImageBitmap = remember(bmp) { bmp.asImageBitmap() }
    var view by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val base = if (view > 0f) max(view / bmp.width, view / bmp.height) else 1f
    val scale = base * zoom
    LaunchedEffect(view) {
        if (view > 0f) offset = Offset((view - bmp.width * base) / 2f, (view - bmp.height * base) / 2f)
    }
    val scrim = Color.Black.copy(alpha = 0.55f)
    val ring = MaterialTheme.colorScheme.primary
    val desc = stringResource(R.string.avatar_crop_hint)
    val zoomDesc = stringResource(R.string.avatar_zoom)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.avatar_crop_title)) },
        text = {
            Column {
                Box(
                    Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).clipToBounds()
                        .onSizeChanged { s: IntSize -> view = s.width.toFloat() }
                        .semantics { contentDescription = desc }
                        .pointerInput(bmp) {
                            detectTransformGestures { centroid, pan, gz, _ ->
                                val v = size.width.toFloat()
                                val b = max(v / bmp.width, v / bmp.height)
                                val nz = (zoom * gz).coerceIn(1f, 5f)
                                val s0 = b * zoom
                                val s1 = b * nz
                                // um den Mittelpunkt der Geste zoomen, dann verschieben
                                val o = (offset - centroid) * (s1 / s0) + centroid + pan
                                zoom = nz
                                offset = AvatarCrop.clamp(o, bmp.width, bmp.height, v, s1)
                            }
                        },
                ) {
                    Canvas(Modifier.matchParentSizeCompat()) {
                        drawImage(
                            img, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                            dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                            dstSize = IntSize((bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt()),
                        )
                        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(size.width, size.height))) }
                        clipPath(circle, clipOp = ClipOp.Difference) { drawRect(scrim) }
                        val w = 2.dp.toPx()
                        drawCircle(ring, radius = size.minDimension / 2f - w / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                    }
                }
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = zoom, valueRange = 1f..5f,
                    onValueChange = { nz ->
                        val v = view
                        if (v <= 0f) return@Slider
                        val c = Offset(v / 2f, v / 2f)
                        val o = (offset - c) * (nz / zoom) + c
                        zoom = nz
                        offset = AvatarCrop.clamp(o, bmp.width, bmp.height, v, base * nz)
                    },
                    modifier = Modifier.semantics { contentDescription = zoomDesc },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (view <= 0f) return@TextButton
                val (l, t, side) = AvatarCrop.cropRect(bmp.width, bmp.height, view, scale, offset)
                onDone(AvatarCrop.encode(bmp, l, t, side))
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.then(Modifier.fillMaxWidth().aspectRatio(1f))
