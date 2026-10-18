# Saga für Android

Native App (Kotlin, Jetpack Compose, Material 3) für einen eigenen Saga-Server. Android 8 (API 26) oder neuer.

## Bauen

Voraussetzung: JDK 17 oder 21 und das Android-SDK (z. B. aus Android Studio).

```sh
cd android
./gradlew assembleDebug          # -> dist/Saga-<Version>-debug.apk (App-ID ...saga.debug, parallel installierbar)
./gradlew testDebugUnitTest      # Unit-Tests
./gradlew connectedDebugAndroidTest   # Chat-Testvektoren (tests/chat_vectors.json) mit libsodium auf Geraet/Emulator
./gradlew assembleRelease        # -> dist/Saga-<Version>.apk
```

Die Version kommt aus `../VERSION` (`tools/set-version.sh`), der `versionCode` wird daraus berechnet
(`major*1000000 + minor*10000 + patch*100`). Der `versionName` ist nur im offiziellen Release die reine Version
(`SAGA_RELEASE=1` bzw. `-PsagaRelease=true`), sonst `2.2.0-rc.N` (`../tools/version-label.sh`).

Bildschirmfotos (nur Debug-Build): `adb shell settings put global saga_screenshot_mode 1` vor dem App-Start zeigt
statt der echten Server-Adresse `saga.example.org` und als App-Version `2.0.0` (`util/ShotMode.kt`); die Statusleiste
setzt man mit dem System-Demo-Modus (`com.android.systemui.demo`, Uhrzeit 9:41).

## Signieren

Release-Builds werden signiert, wenn ein Schlüssel angegeben ist – sonst entsteht `Saga-<Version>-unsigned.apk`.

- **CI:** Umgebungsvariablen `SAGA_KEYSTORE_FILE`, `SAGA_KEYSTORE_PASSWORD`, `SAGA_KEY_ALIAS`, `SAGA_KEY_PASSWORD`
- **Lokal:** `android/keystore.properties` (wird von git ignoriert):

  ```properties
  storeFile=~/.saga-android/saga-release.jks
  storePassword=…
  keyAlias=saga
  keyPassword=…
  ```

Eigener Schlüssel: `keytool -genkeypair -v -keystore saga-release.jks -alias saga -keyalg RSA -keysize 4096 -validity 10950`.
Den Schlüssel nie verlieren – ohne ihn lassen sich installierte Apps nicht mehr aktualisieren.

## Mitteilungen (Push)

Ohne Google-Dienste über [UnifiedPush](https://unifiedpush.org): einen Verteiler wie ntfy installieren, dann in Saga
Einstellungen → Mitteilungen einschalten. Inhalte sind Ende-zu-Ende verschlüsselt (Web Push, RFC 8291).
