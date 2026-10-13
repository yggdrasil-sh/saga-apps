# Saga für Android

Native App (Kotlin, Jetpack Compose, Material 3) für einen eigenen Saga-Server. Android 8 (API 26) oder neuer.

## Bauen

Voraussetzung: JDK 17 oder 21 und das Android-SDK (z. B. aus Android Studio).

```sh
cd android
./gradlew assembleDebug          # -> dist/Saga-<Version>-debug.apk (App-ID ...saga.debug, parallel installierbar)
./gradlew testDebugUnitTest      # Unit-Tests
./gradlew assembleRelease        # -> dist/Saga-<Version>.apk
```

Die Version kommt aus `../VERSION` (`tools/set-version.sh`), der `versionCode` wird daraus berechnet
(`major*1000000 + minor*10000 + patch*100`).

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
