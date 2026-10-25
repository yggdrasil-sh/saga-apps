package io.github.yggdrasil_sh.saga.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.yggdrasil_sh.saga.MainActivity
import io.github.yggdrasil_sh.saga.SagaApplication

/**
 * Sprung aus einem Widget in die App: NICHT exportiert - nur die eigenen Widgets (PendingIntent der App) erreichen sie.
 * Legt das Ziel im Repository ab (MainScreen navigiert) und holt MainActivity nach vorn. Keine eigene Oberflaeche.
 */
class WidgetLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            WidgetLinks.dest(intent)?.let { (application as SagaApplication).repo.openDestRequest.value = it }
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
