package io.github.yggdrasil_sh.saga

import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.yggdrasil_sh.saga.auth.BrowserReturn
import io.github.yggdrasil_sh.saga.auth.LinkGate
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.data.SharedContent
import io.github.yggdrasil_sh.saga.ui.nav.SagaRoot
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as SagaApplication

    // Android 8-12: in der App gewaehlte Sprache (ab 13 erledigt das der LocaleManager)
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(io.github.yggdrasil_sh.saga.util.AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Nur beim echten Start: nach einem Prozess-Tod stellt Android die Activity mit dem urspruenglichen Intent
        // wieder her (savedInstanceState != null) - geteilter Text/Links waeren sonst ein zweites Mal da.
        // Einmal-Links merkt sich zusaetzlich alreadyHandled; aus "Zuletzt" werden sie ignoriert.
        if (savedInstanceState == null) handleIntent(intent, newIntent = false)
        setContent {
            SagaTheme {
                SagaRoot(app.repo, app.appScope)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, newIntent = true)
    }

    override fun onStart() {
        super.onStart()
        app.appScope.launch { app.repo.refresh() }        // im Web Geaendertes uebernehmen
    }

    override fun onResume() {
        super.onResume()
        // Ein saga://auth-Link kommt vorher in onNewIntent/onCreate an. Laeuft danach noch eine Browser-Anmeldung,
        // wurde der Tab ohne Ergebnis geschlossen -> "Anmeldung abgebrochen"
        app.repo.onAppResumed()
    }

    /** Deep-Links: saga://auth?code=... (Rueckkehr aus dem Browser), saga://pair?u=...&c=... (QR-Code) */
    private fun handleIntent(intent: Intent?, newIntent: Boolean) {
        // Mitteilung angetippt: Seite/Chat oeffnen (url relativ zum Server)
        intent?.getStringExtra(io.github.yggdrasil_sh.saga.push.Notifier.EXTRA_URL)?.let { url ->
            val sig = intent.getStringExtra(io.github.yggdrasil_sh.saga.push.Notifier.EXTRA_SIG)
            intent.removeExtra(io.github.yggdrasil_sh.saga.push.Notifier.EXTRA_URL)
            intent.removeExtra(io.github.yggdrasil_sh.saga.push.Notifier.EXTRA_SIG)
            // nur aus eigenen Mitteilungen (signiert) - fremde Apps duerfen nichts oeffnen/gelesen markieren (BUG-110)
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 &&
                io.github.yggdrasil_sh.saga.push.Notifier.verify(this, url, sig)
            ) app.repo.openUrl(url)
            return
        }
        if (intent?.action == Intent.ACTION_SEND && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            SharedContent.from(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                ?.let { app.repo.pendingShare.value = it }
            intent.action = Intent.ACTION_MAIN                  // nicht ein zweites Mal verarbeiten
            return
        }
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.scheme != "saga") return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return     // aus "Zuletzt": Link ist alt
        if (alreadyHandled(data.toString(), data.host, newIntent)) return    // Aufgabe nach App-Update mit dem alten Link neu gestartet
        val repo = app.repo
        when (data.host) {
            // Code -> anmelden; error=cancelled bzw. ohne Code -> Hinweis auf dem Startbildschirm (Nachtest P7)
            "auth" -> repo.onBrowserReturn(BrowserReturn.of(data.getQueryParameter("code"), data.getQueryParameter("error")))
            "pair" -> {
                val t = PairLink.parse(data.toString())
                // nie still koppeln: erst Ziel-Host zeigen und bestaetigen lassen (PairConfirmDialog)
                if (t != null) repo.offerPair(t) else repo.notify(UiText.Res(R.string.login_invalid_pair_link)).also {
                    repo.loginError.value = UiText.Res(R.string.login_invalid_pair_link)
                }
            }
        }
        intent.data = null                                  // nicht ein zweites Mal verarbeiten
    }

    /**
     * Derselbe Einmal-Link (Kopplung/Anmeldung) kommt nach einem App-Update oder Prozess-Neustart erneut als
     * Start-Intent der Aufgabe an. Den zuletzt verarbeiteten (als Hash) merken und dann ignorieren - ausser ein
     * Kopplungslink kommt bei laufender App an (LinkGate; "Abbrechen" vergisst den Link, siehe SagaRepository.cancelPair).
     */
    private fun alreadyHandled(link: String, host: String?, newIntent: Boolean): Boolean {
        val h = LinkGate.hash(link)
        val prefs = getSharedPreferences(LinkGate.PREFS, MODE_PRIVATE)
        if (!LinkGate.shouldHandle(host, h, prefs.getString(LinkGate.KEY, null), newIntent)) return true
        prefs.edit { putString(LinkGate.KEY, h) }
        return false
    }
}
