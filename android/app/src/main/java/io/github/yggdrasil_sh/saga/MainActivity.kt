package io.github.yggdrasil_sh.saga

import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
import java.security.MessageDigest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.data.SharedContent
import io.github.yggdrasil_sh.saga.ui.nav.SagaRoot
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as SagaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Nur beim echten Start: nach einem Prozess-Tod stellt Android die Activity mit dem urspruenglichen Intent
        // wieder her (savedInstanceState != null) - geteilter Text/Links waeren sonst ein zweites Mal da.
        // Einmal-Links merkt sich zusaetzlich alreadyHandled; aus "Zuletzt" werden sie ignoriert.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            SagaTheme {
                SagaRoot(app.repo, app.appScope)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.appScope.launch { app.repo.refresh() }        // im Web Geaendertes uebernehmen
    }

    /** Deep-Links: saga://auth?code=... (Rueckkehr aus dem Browser), saga://pair?u=...&c=... (QR-Code) */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            SharedContent.from(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                ?.let { app.repo.pendingShare.value = it }
            intent.action = Intent.ACTION_MAIN                  // nicht ein zweites Mal verarbeiten
            return
        }
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.scheme != "saga") return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return     // aus "Zuletzt": Link ist alt
        if (alreadyHandled(data.toString())) return         // Aufgabe nach App-Update mit dem alten Link neu gestartet
        val repo = app.repo
        when (data.host) {
            "auth" -> data.getQueryParameter("code")?.let { code -> app.appScope.launch { repo.completeBrowserLogin(code) } }
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
     * Start-Intent der Aufgabe an. Den zuletzt verarbeiteten (als Hash) merken und dann ignorieren.
     */
    private fun alreadyHandled(link: String): Boolean {
        val h = MessageDigest.getInstance("SHA-256").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }
        val prefs = getSharedPreferences("saga_ui", MODE_PRIVATE)
        if (prefs.getString("last_link", null) == h) return true
        prefs.edit { putString("last_link", h) }
        return false
    }
}
