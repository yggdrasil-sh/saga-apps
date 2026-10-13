import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version aus der gemeinsamen Datei ../VERSION (eine Quelle fuer iOS, Linux, Android).
// versionCode wird deterministisch aus der Version gebildet (reproduzierbar, F-Droid-tauglich):
// major*1_000_000 + minor*10_000 + patch*100 + build, also 1.2.3 -> 1020300.
// Zusaetzliche Builds derselben Version: -PsagaBuild=1..99. minor/patch muessen 0..99 sein,
// sonst wuerde der versionCode nicht mehr streng mit der Version steigen.
val sagaVersion: String = rootProject.file("../VERSION").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } ?: "0.0.0"
val sagaVersionCode: Int = run {
    val p = sagaVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    require(p[1] in 0..99 && p[2] in 0..99) { "VERSION '$sagaVersion': minor und patch muessen zwischen 0 und 99 liegen" }
    val build = (findProperty("sagaBuild") as String?)?.toIntOrNull() ?: 0
    p[0] * 1_000_000 + p[1] * 10_000 + p[2] * 100 + build.coerceIn(0, 99)
}

// Signierschluessel fuer Release - nie im Repo:
//  1. Umgebungsvariablen (CI): SAGA_KEYSTORE_FILE, SAGA_KEYSTORE_PASSWORD, SAGA_KEY_ALIAS, SAGA_KEY_PASSWORD
//  2. sonst lokale Datei android/keystore.properties (ignoriert) mit storeFile, storePassword, keyAlias, keyPassword
// Fehlt beides, wird das Release-APK unsigniert gebaut (mit Warnung, ohne Abbruch).
data class SagaSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

val sagaSigning: SagaSigning? = run {
    fun env(name: String): String? = providers.environmentVariable(name).orNull?.trim()?.ifEmpty { null }
    val envFile = env("SAGA_KEYSTORE_FILE")
    if (envFile != null) {
        val sp = env("SAGA_KEYSTORE_PASSWORD")
        val ka = env("SAGA_KEY_ALIAS")
        val kp = env("SAGA_KEY_PASSWORD") ?: sp
        val f = file(envFile)
        return@run if (f.isFile && sp != null && ka != null && kp != null) SagaSigning(f, sp, ka, kp) else {
            logger.warn("WARNUNG: SAGA_KEYSTORE_FILE gesetzt, aber Datei fehlt oder SAGA_KEYSTORE_PASSWORD/SAGA_KEY_ALIAS fehlen - Release bleibt unsigniert.")
            null
        }
    }
    val propsFile = rootProject.file("keystore.properties")
    if (!propsFile.isFile) return@run null
    val props = Properties().apply { propsFile.inputStream().use { load(it) } }
    val sf = props.getProperty("storeFile")?.trim()?.ifEmpty { null }
    val sp = props.getProperty("storePassword")
    val ka = props.getProperty("keyAlias")
    val kp = props.getProperty("keyPassword") ?: sp
    // storeFile darf absolut sein oder mit ~/ beginnen; relative Pfade gelten ab android/
    val f = sf?.let { rootProject.file(if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it) }
    if (f != null && f.isFile && sp != null && ka != null && kp != null) SagaSigning(f, sp, ka, kp) else {
        logger.warn("WARNUNG: keystore.properties unvollstaendig oder storeFile nicht gefunden - Release bleibt unsigniert.")
        null
    }
}

android {
    namespace = "io.github.yggdrasil_sh.saga"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.yggdrasil_sh.saga"
        minSdk = 26
        targetSdk = 37
        versionCode = sagaVersionCode
        versionName = sagaVersion
    }

    signingConfigs {
        sagaSigning?.let { s ->
            create("release") {
                storeFile = s.storeFile
                storePassword = s.storePassword
                keyAlias = s.keyAlias
                keyPassword = s.keyPassword
                enableV1Signing = false // minSdk 26: v2/v3 genuegen
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // F-Droid: keinen verschluesselten Abhaengigkeits-Block (nur fuer Google Play lesbar) ins APK schreiben
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Fertige APKs zusaetzlich als android/dist/Saga-<version>.apk ablegen (dist/ ist ignoriert).
// Unsignierte Release-Builds heissen Saga-<version>-unsigned.apk, damit sie nicht verwechselt werden.
val sagaDistDir = rootProject.layout.projectDirectory.dir("dist")
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val isRelease = variant.buildType == "release"
        val unsigned = isRelease && sagaSigning == null
        val versionLabel = variant.outputs.firstOrNull()?.versionName?.orNull ?: sagaVersion
        val apkName = "Saga-" + versionLabel + (if (unsigned) "-unsigned" else "") + ".apk"
        val distTask = tasks.register<Copy>("sagaDist$cap") {
            group = "distribution"
            description = "Kopiert das $cap-APK nach dist/$apkName"
            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { apkName }
            }
            into(sagaDistDir)
            doLast {
                if (unsigned) {
                    logger.warn("WARNUNG: Release-APK ist UNSIGNIERT (kein Signierschluessel: SAGA_KEYSTORE_* oder keystore.properties fehlt).")
                }
                logger.lifecycle("APK: dist/$apkName")
            }
        }
        tasks.named { it == "assemble$cap" }.configureEach { finalizedBy(distTask) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
