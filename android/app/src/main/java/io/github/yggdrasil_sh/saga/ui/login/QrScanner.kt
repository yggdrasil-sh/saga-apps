package io.github.yggdrasil_sh.saga.ui.login

import androidx.compose.ui.res.stringResource
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.resolve
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR-Code-Scanner ohne Google-Dienste: CameraX-Vorschau + zxing (nur QR).
 * onResult liefert true, wenn der Text passt (dann schliesst der Aufrufer den Dialog).
 */
@Composable
fun QrScannerDialog(onDismiss: () -> Unit, onResult: (String) -> Boolean) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        // Geraet ohne Kamera (Emulator, manche Tablets/Chromebooks): gar nicht erst CameraX starten - sonst rohe
        // InitializationException im Bild (TT 2.4 P-08). Ausweg immer: Kopplungslink aus der Zwischenablage einfuegen.
        val hasCamera = remember { QrCamera.available(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) }
        var hint by remember { mutableStateOf<UiText>(UiText.Res(if (hasCamera) R.string.qr_frame_hint else R.string.qr_no_camera_paste)) }
        val result by rememberUpdatedState(onResult)
        val executor = remember { Executors.newSingleThreadExecutor() }
        val done = remember { AtomicBoolean(false) }
        val main = remember { Handler(Looper.getMainLooper()) }
        val paste: () -> Unit = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
            when {
                text.isEmpty() -> hint = UiText.Res(R.string.qr_paste_empty)
                result(text) -> done.set(true)
                else -> hint = UiText.Res(R.string.qr_paste_not_saga)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                done.set(true)
                if (hasCamera) {
                    val f = ProcessCameraProvider.getInstance(context)
                    f.addListener({ runCatching { f.get().unbindAll() } }, ContextCompat.getMainExecutor(context))
                }
                executor.shutdown()
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCamera) AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val view = PreviewView(ctx)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        if (done.get()) return@addListener              // Dialog schon zu: Kamera nicht mehr binden
                        try {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            analysis.setAnalyzer(executor, QrAnalyzer { text ->
                                main.post {
                                    if (!done.get()) {
                                        if (result(text)) done.set(true) else hint = UiText.Res(R.string.qr_not_saga)
                                    }
                                }
                            })
                            val selector = when {
                                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                                else -> null
                            }
                            if (selector == null) {
                                hint = UiText.Res(R.string.qr_no_camera)
                            } else {
                                provider.unbindAll()
                                if (!done.get()) provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                            }
                        } catch (e: Exception) {
                            // keine Ausnahme-Texte (englisch, technisch) zeigen - verstaendliche Meldung mit Ausweg
                            android.util.Log.w("SagaQr", "Kamera: " + e.javaClass.simpleName)
                            hint = UiText.Res(QrCamera.errorText(e))
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                },
            )
            Box(
                Modifier.align(Alignment.Center).size(240.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(18.dp)),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    hint.resolve(), color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp)).padding(12.dp),
                )
                FilledTonalButton(onClick = paste, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.qr_paste_link))
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = Color.White)
            }
        }
    }
}

/** Reine Regeln (Tests): Kamera vorhanden, welche Meldung bei Fehlern */
object QrCamera {
    fun available(featureCameraAny: Boolean): Boolean = featureCameraAny

    /** Fehler beim Start: keine Kamera -> "Keine Kamera gefunden …", sonst "Kamera nicht verfuegbar …" (ohne Ausnahme-Text) */
    fun errorText(e: Throwable): Int {
        val m = (e.message.orEmpty() + " " + (e.cause?.message ?: "")).lowercase()
        return if (m.contains("available cameras: 0") || m.contains("no camera") || m.contains("hassystemfeature"))
            R.string.qr_no_camera_paste else R.string.qr_camera_unavailable_paste
    }
}

/** Ein Bild der Kamera (Y-Ebene = Helligkeit) mit zxing nach einem QR-Code durchsuchen */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val data = ByteArray(buf.remaining())
            buf.get(data)
            val stride = plane.rowStride
            val h = minOf(image.height, data.size / maxOf(stride, 1))
            val w = minOf(image.width, stride)
            val source = PlanarYUVLuminanceSource(data, stride, h, 0, 0, w, h, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (!text.isNullOrBlank()) onText(text)
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }
}
