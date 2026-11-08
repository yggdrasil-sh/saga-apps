package io.github.yggdrasil_sh.saga.ui.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.SvgLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream

/** Ergebnis beim Nachladen eines Bildes, das Coil nicht entpacken konnte */
sealed class SvgFetch {
    class Svg(val data: ByteArray) : SvgFetch()
    object TooBig : SvgFetch()
    object None : SvgFetch()
}

/** Bild mit der Sitzung (eigener Server) laden und pruefen, ob es ein SVG ist (hoechstens MAX_BYTES + 1 lesen) */
suspend fun fetchSvg(repo: SagaRepository, path: String): SvgFetch = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder().url(repo.absoluteUrl(path)).build()
        repo.api.imageCallFactory.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext SvgFetch.None
            val src = r.body?.source() ?: return@withContext SvgFetch.None
            if (src.request(SvgLogic.MAX_BYTES + 1L)) {                       // zu gross zum Einbetten
                val head = src.buffer.peek().readByteArray(4096L)
                return@withContext if (SvgLogic.isSvg(head)) SvgFetch.TooBig else SvgFetch.None
            }
            val d = src.readByteArray()
            if (SvgLogic.isSvg(d)) SvgFetch.Svg(d) else SvgFetch.None
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        SvgFetch.None
    }
}

/** WebView, die keine Beruehrung annimmt (Antippen/Blaettern gehen an die Seite darunter) */
@SuppressLint("ViewConstructor")
private class SvgWeb(ctx: Context) : WebView(ctx) {
    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
}

/**
 * SVG anzeigen (Coil/ImageDecoder koennen kein SVG, keine neue Bibliothek): WebView ohne JavaScript, ohne Netz, ohne
 * Datei-/Content-Zugriff, nur mit dem SVG als data:-<img> (SvgLogic.html). Jede Anfrage ausser data: wird leer beantwortet.
 */
@Composable
fun SvgImage(data: ByteArray, modifier: Modifier = Modifier, cover: Boolean = false) {
    val html = remember(data, cover) { SvgLogic.html(data, cover) }
    AndroidView(
        factory = { ctx ->
            SvgWeb(ctx).apply {
                settings.javaScriptEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.setSupportMultipleWindows(false)
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isClickable = false
                isLongClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                        if (request?.url?.scheme == "data") null
                        else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier,
        onRelease = { it.destroy() },
    )
}
