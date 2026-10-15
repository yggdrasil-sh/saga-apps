package io.github.yggdrasil_sh.saga

import android.app.Application
import android.os.Build
import android.provider.Settings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.yggdrasil_sh.saga.data.AppSettings
import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.data.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-weite Objekte (einfacher "Container" ohne DI-Bibliothek) */
class SagaApplication : Application(), SingletonImageLoader.Factory {
    /** lebt so lange wie der Prozess - fuer Speichern beim Verlassen einer Seite */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repo: SagaRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = SagaRepository(
            settings = AppSettings(this),
            secure = SecureStore(this),
            scope = appScope,
            deviceName = deviceName(),
            userAgent = "Saga-Android/${BuildConfig.VERSION_NAME}",
            appVersion = BuildConfig.VERSION_NAME,
            filesDir = filesDir,
            // App-Sprache = Sprache der angezeigten Texte (de/en/es; sonst Englisch), nicht die Geraetesprache
            language = { resources.getString(R.string.app_language) },
            appContext = this,
        )
        // Live-Kanal nur im Vordergrund (hoechstens eine Verbindung); im Hintergrund kommen Mitteilungen (Push)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = repo.live.foreground(true)
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = repo.live.foreground(false)
        })
    }

    /** Bilder: eigener Server (/files/...) mit derselben Sitzung wie die API, fremde Hosts ohne Sitzung/Kopfzeilen */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { repo.api.imageCallFactory })) }
            .crossfade(true)
            .build()

    /** Name, unter dem das Geraet in "Apps & Geraete" erscheint */
    private fun deviceName(): String {
        val n = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}"
        return (n?.takeIf { it.isNotBlank() } ?: model).replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "Android" }
    }
}
