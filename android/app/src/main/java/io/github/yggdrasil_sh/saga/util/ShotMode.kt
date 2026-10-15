package io.github.yggdrasil_sh.saga.util

/**
 * Nur in Debug-Builds: Bildschirmfoto-Modus fuer Dokumentation/Review. Zeigt statt der echten Server-Adresse eine
 * Beispieladresse und statt "x.y.z-debug" die Release-Version. Eingeschaltet per
 * `adb shell settings put global saga_screenshot_mode 1` (vor dem App-Start).
 */
object ShotMode {
    @Volatile
    var on = false
    const val HOST = "saga.example.org"
    const val VERSION = "2.0.0"

    fun host(real: String): String = if (on && real.isNotEmpty()) HOST else real

    /** "Saga 1.3.0-debug (42)" -> "Saga 2.0.0" */
    fun version(real: String): String = if (on) "Saga $VERSION" else real
}
