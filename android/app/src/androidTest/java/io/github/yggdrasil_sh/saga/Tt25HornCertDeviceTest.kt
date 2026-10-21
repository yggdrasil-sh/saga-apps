package io.github.yggdrasil_sh.saga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yggdrasil_sh.saga.call.HornLogic
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * TT 2.5 Nr. 67 auf dem Geraet, ohne Konto: der echte Fehler beim TLS-Aufbau zu einem Knoten mit selbst signiertem
 * Zertifikat (Teststand) wird als Zertifikatsfehler erkannt - Grundlage fuer die Meldung statt endloser Wiederholung.
 * Knoten per Argument: -e hornNode https://<knoten>:8245 (ohne Argument uebersprungen).
 */
@RunWith(AndroidJUnit4::class)
class Tt25HornCertDeviceTest {
    @Test
    fun selfSignedNodeIsReportedAsCertificateError() {
        val node = InstrumentationRegistry.getArguments().getString("hornNode")
        assumeTrue("kein Knoten angegeben", !node.isNullOrEmpty())
        val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val err = try {
            http.newCall(Request.Builder().url("$node/probe").build()).execute().use { null }
        } catch (e: Exception) {
            e
        }
        android.util.Log.i("Tt25HornCert", "Fehler: ${err?.javaClass?.name}: ${err?.message}")
        assertTrue("erwartet: Zertifikatsfehler, war: $err", err != null && HornLogic.certError(err))
        assertTrue(!HornLogic.certError(java.net.ConnectException("refused")))
    }
}
