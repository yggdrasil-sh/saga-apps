package io.github.yggdrasil_sh.saga

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.yggdrasil_sh.saga.auth.PairLink
import io.github.yggdrasil_sh.saga.ui.nav.SagaRoot
import io.github.yggdrasil_sh.saga.ui.theme.SagaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as SagaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.scheme != "saga") return
        val repo = app.repo
        when (data.host) {
            "auth" -> data.getQueryParameter("code")?.let { code -> app.appScope.launch { repo.completeBrowserLogin(code) } }
            "pair" -> {
                val t = PairLink.parse(data.toString())
                if (t != null) app.appScope.launch { repo.pair(t) } else repo.loginError.value = "Das ist kein gültiger Saga-Kopplungslink."
            }
        }
        intent.data = null                                  // nicht ein zweites Mal verarbeiten
    }
}
