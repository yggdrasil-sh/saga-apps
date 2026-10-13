// Wurzel-Build: Plugins nur deklarieren, angewendet werden sie im Modul :app.
// AGP 9 bringt Kotlin selbst mit ("built-in Kotlin"); kotlin-android wird nur deklariert,
// damit Kotlin-Compiler, Compose- und Serialisierungs-Plugin dieselbe Version haben.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
