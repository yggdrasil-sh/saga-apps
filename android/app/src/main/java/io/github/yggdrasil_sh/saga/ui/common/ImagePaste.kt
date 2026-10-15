package io.github.yggdrasil_sh.saga.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import io.github.yggdrasil_sh.saga.chat.ChatAttach
import io.github.yggdrasil_sh.saga.util.ImageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Ein empfangenes Bild (Tastatur, Zwischenablage, Ziehen): Adresse + Typ; release() gibt die Leseberechtigung zurueck */
class IncomingImage(val uri: Uri, val mime: String, val release: () -> Unit = {})

private val IMAGE_TYPES = arrayOf("image/*", "image/png", "image/gif", "image/jpeg", "image/webp")

/**
 * Bilder aus der Tastatur (GBoard: Sticker, GIFs, "Bild einfuegen" aus dem Zwischenablage-Verlauf) fuer Textfelder,
 * auch die klassischen BasicTextField(TextFieldValue) ohne contentReceiver: die Eingabeverbindung meldet Bild-Typen
 * (EditorInfo.contentMimeTypes) und nimmt commitContent an. onImage = null -> unveraendert.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyboardImages(onImage: ((IncomingImage) -> Unit)?, content: @Composable () -> Unit) {
    if (onImage == null) {
        content()
        return
    }
    val cb by rememberUpdatedState(onImage)
    val interceptor = remember {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(request: PlatformTextInputMethodRequest, nextHandler: PlatformTextInputSession): Nothing =
                nextHandler.startInputMethod(ImageInputRequest(request) { cb(it) })
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/** Eingabeverbindung des Textfelds + Bild-Typen + commitContent (Tastatur-Bilder) */
private class ImageInputRequest(private val inner: PlatformTextInputMethodRequest, private val onImage: (IncomingImage) -> Unit) :
    PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        val ic = inner.createInputConnection(outAttributes)
        EditorInfoCompat.setContentMimeTypes(outAttributes, IMAGE_TYPES)
        // bewusst die Variante mit Listener: die neue (mit View) liefert an den Compose-View, nicht an dieses Feld
        @Suppress("DEPRECATION")
        return InputConnectionCompat.createWrapper(ic, outAttributes) { info, flags, _ ->
            val desc = info.description
            val mime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
                ?: return@createWrapper false
            if (Build.VERSION.SDK_INT >= 25 && flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                try {
                    info.requestPermission()
                } catch (_: Exception) {
                    return@createWrapper false
                }
            }
            onImage(IncomingImage(info.contentUri, mime) { runCatching { info.releasePermission() } })
            true
        }
    }
}

/** Bilder in einem ClipData (Zwischenablage, Ziehen, contentReceiver) */
fun imagesIn(context: Context, clip: ClipData?): List<IncomingImage> {
    clip ?: return emptyList()
    val cr = context.contentResolver
    return (0 until clip.itemCount).mapNotNull { i ->
        val u = clip.getItemAt(i).uri ?: return@mapNotNull null
        val t = runCatching { cr.getType(u) }.getOrNull()
            ?: (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
        if (t?.startsWith("image/") == true) IncomingImage(u, t) else null
    }
}

/** Bilder in der Zwischenablage (leer, wenn keine oder kein Zugriff) */
fun clipboardImages(context: Context): List<IncomingImage> = try {
    val cm = context.getSystemService(ClipboardManager::class.java)
    imagesIn(context, cm?.primaryClip)
} catch (_: Exception) {
    emptyList()
}

/** Liegt ein Bild in der Zwischenablage? Folgt Aenderungen (fuer den Knopf "Bild einfügen") */
@Composable
fun rememberClipboardHasImage(): Boolean {
    val context = LocalContext.current
    val cm = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    fun check(): Boolean = try {
        cm?.primaryClipDescription?.hasMimeType("image/*") == true
    } catch (_: Exception) {
        false
    }
    var has by remember { mutableStateOf(check()) }
    DisposableEffect(cm) {
        val l = ClipboardManager.OnPrimaryClipChangedListener { has = check() }
        cm?.addPrimaryClipChangedListener(l)
        onDispose { cm?.removePrimaryClipChangedListener(l) }
    }
    return has
}

/** Bild einlesen (hoechstens readMax Bytes, sonst null); gibt danach die Leseberechtigung zurueck */
suspend fun readImage(context: Context, img: IncomingImage, readMax: Long): ByteArray? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(img.uri)?.use { ChatAttach.readLimited(it, readMax) }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        img.release()
    }
}

/**
 * Fuer den Upload vorbereiten: PNG/JPEG/GIF/WebP bis max bleiben unveraendert; andere Formate (HEIC ...) oder zu
 * grosse Bilder -> JPEG, lange Kante hoechstens 2560 px (bei Bedarf kleiner), bis es unter max passt.
 * -> (Daten, MIME) oder null (kein lesbares Bild)
 */
suspend fun prepareImage(data: ByteArray, max: Long): Pair<ByteArray, String>? = withContext(Dispatchers.Default) {
    val sniffed = ImageData.sniff(data)
    if (sniffed != null && data.size <= max) return@withContext data to sniffed
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var edge = 2560
        var quality = 85
        repeat(6) {
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / (sample * 2) >= edge) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            val scale = minOf(1f, edge.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            val b = out.toByteArray()
            if (b.size <= max) return@withContext b to "image/jpeg"
            edge = edge * 3 / 4
            quality = maxOf(60, quality - 8)
        }
        null
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }
}
