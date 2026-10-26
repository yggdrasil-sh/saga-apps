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
import kotlinx.coroutines.launch

/** App-weite Objekte (einfacher "Container" ohne DI-Bibliothek) */
class SagaApplication : Application(), SingletonImageLoader.Factory {
    /** lebt so lange wie der Prozess - fuer Speichern beim Verlassen einer Seite */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repo: SagaRepository
        private set

    override fun onCreate() {
        super.onCreate()
        io.github.yggdrasil_sh.saga.util.PersonName.ctx = this                // D01: lesbarer Ersatz fuer Personen ohne Namen
        io.github.yggdrasil_sh.saga.util.AppLanguage.applyToApp(this)     // Android 8-12: gewaehlte App-Sprache
        if (BuildConfig.DEBUG) {
            // nur Debug: Netzwerk auf dem Main-Thread ins Log (StrictMode), z. B. beim Strom der KI-Antwort
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectNetwork().detectCustomSlowCalls().penaltyLog().build(),
            )
            // nur Debug: Bildschirmfotos mit Beispieladresse/-version (adb shell settings put global saga_screenshot_mode 1)
            io.github.yggdrasil_sh.saga.util.ShotMode.on = try {
                Settings.Global.getString(contentResolver, "saga_screenshot_mode") == "1"
            } catch (_: Exception) {
                false
            }
        }
        // Klartext-Kopien von Chat-Anhaengen aus einem frueheren Prozess entfernen (BUG-023)
        appScope.launch(Dispatchers.IO) { io.github.yggdrasil_sh.saga.chat.ChatFiles.purge(cacheDir) }
        repo = SagaRepository(
            settings = AppSettings(this),
            secure = SecureStore(this),
            scope = appScope,
            deviceName = deviceName(),
            userAgent = "Saga-Android/${BuildConfig.VERSION_NAME}",
            appVersion = BuildConfig.VERSION_NAME,
            filesDir = filesDir,
            // App-Sprache: in der App gewaehlte Sprache, sonst die der angezeigten Texte (de/en/es; sonst Englisch)
            language = {
                io.github.yggdrasil_sh.saga.util.AppLanguage.headerLanguage(
                    io.github.yggdrasil_sh.saga.util.AppLanguage.current(this), resources.getString(R.string.app_language),
                )
            },
            appContext = this,
        )
        // 2.5: Update-Hinweis (Einstellungen -> Info): Zwischenspeicher laden, beim Start hoechstens alle 12 h pruefen
        io.github.yggdrasil_sh.saga.ui.settings.UpdateHintModel.init(this, appScope)
        io.github.yggdrasil_sh.saga.ui.settings.UpdateHintModel.appStarted()
        // Profilbilder nur vom eingestellten Server; Anrufe hoeren ab jetzt auf den Live-Kanal (Klingeln, Signale)
        io.github.yggdrasil_sh.saga.ui.common.AvatarLogic.server = { repo.settings.server.value }
        repo.calls
        // Homescreen-Widgets: nach Aenderungen in der App neu laden; periodischen Abgleich sicherstellen, falls Widgets da sind
        appScope.launch { io.github.yggdrasil_sh.saga.widget.WidgetUpdates.followApp(this@SagaApplication, repo) }
        appScope.launch(Dispatchers.IO) {
            val has = io.github.yggdrasil_sh.saga.widget.WidgetUpdates.present(this@SagaApplication)
            if (has.today || has.page) io.github.yggdrasil_sh.saga.widget.WidgetUpdates.schedule(this@SagaApplication)
        }
        // 2.5: eigene Push-Verbindung (ohne Verteiler) nach einem Prozess-Neustart wieder aufnehmen bzw. beenden
        appScope.launch {
            repo.awaitReady()
            if (!repo.needsLogin.value) {
                repo.push.ensure()
                repo.push.autoEnable()        // Mitteilungen erlaubt, aber nie eingeschaltet: jetzt einschalten
            }
        }
        // Live-Kanal nur im Vordergrund (hoechstens eine Verbindung); im Hintergrund kommen Mitteilungen (Push)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = repo.live.foreground(true)
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = repo.live.foreground(false)
        })
    }

    /** Bilder: eigener Server (/files/...) mit derselben Sitzung wie die API, fremde Hosts ohne Sitzung/Kopfzeilen */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { repo.api.imageCallFactory }))
                // Ohne eigenen Decoder zeigt Coil von GIFs nur das erste Bild
                if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory()) else add(coil3.gif.GifDecoder.Factory())
            }
            .crossfade(true)
            .build()

    /** Name, unter dem das Geraet in "Apps & Geraete" erscheint */
    private fun deviceName(): String {
        val n = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        return io.github.yggdrasil_sh.saga.chat.DeviceNames.pretty(n, Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())
    }
}
