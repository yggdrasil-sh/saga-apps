# Saga für iPhone, iPad und Mac

Native App (SwiftUI) für einen [Saga](https://github.com/yggdrasil-sh/saga)-Server – eine Codebasis für iOS, iPadOS und macOS.

## Was die App kann
- **Seiten und Notizen** lesen und bearbeiten (Blöcke, Titelbild, Symbol, Unterseiten, Favoriten, zuletzt geöffnet)
- **Datenbanken** mit Ansichten, Filtern und Sortierung; neue Einträge passend zur Ansicht
- **Aufgaben und Termine** („Heute“, Agenda, Wiederholungen, Abhaken); Kalender per CalDAV verbinden
- **Befehlspalette** (⌘K) zum Suchen nach Seitennamen und Inhalt
- **Seiten veröffentlichen** (Server ab 1.2): Seitenmenü „Veröffentlichen …“ mit öffentlichem Namen, Adresse,
  Unterseiten, Suchmaschinen, Profilseite und Link; Hinweis „Öffentlich“ im Seitenkopf; Name unter Einstellungen → Konto
- **Live** (Server 2.0): Glocke mit Benachrichtigungen, „… ist auch hier“/„… bearbeitet gerade“ auf Seiten,
  fremde Änderungen werden still nachgeladen (oder mit Hinweis, wenn eigene Eingaben offen sind)
- **Chats mit Ende-zu-Ende-Verschlüsselung** (Server 2.0): Direkt-, Gruppen-, Seiten-Chats und freie Gruppen,
  Anhänge, Antworten, Seitenverweise; Einrichtung mit Wiederherstellungsschlüssel, neue Geräte per Code bestätigen,
  Sicherheitsnummer (mit QR-Code). Kryptografie nur über libsodium (swift-sodium), private Schlüssel nur im
  Schlüsselbund dieses Geräts. Prüfung gegen die Testvektoren des Servers: `Tests/ChatVectors` (siehe unten)
- **Anrufe** (Server 2.1): Audio und Video im Direkt-Chat über WebRTC, Signalisierung signiert und versiegelt
  (saga-call/v1), DTLS-Fingerabdruck geprüft; iPhone/iPad klingeln über CallKit + PushKit-VoIP, der Mac mit eigenem
  Fenster „Anruf“
- **Profilbilder** (Server 2.1) überall, wo Personen erscheinen; eigenes Bild mit Zuschnitt unter Einstellungen
- **Anmeldung & Sicherheit** (Server 2.1): Status des zweiten Faktors, verwaltet im Browser; der 2FA-Schritt der
  Anmeldung erscheint im System-Anmeldefenster
- **Mitteilungen** (Push über APNs, Server 2.0): Einstellungen → Mitteilungen; nie mit Inhalten
- **KI-Assistent** (Server 2.0, wenn der Admin ihn einschaltet): „Frag deine Seiten“ und „Diese Seite“
- **Föderation** (Server 2.0): Seiten anderer Saga-Instanzen unter „Mit mir geteilt“, Teilen an `name@server`
- Bilder und Dateien anhängen, optional Fotos aus Immich übernehmen
- **Widgets**: „Heute“ (Termine + Aufgaben, direkt abhaken), „Seite“, „Seite oder Datenbank“
- **Teilen-Erweiterung** (iOS und macOS): Link oder Text als Seite im Eingang oder als Aufgabe ablegen
- **Kurzbefehle/Siri**: Aufgabe anlegen, Notiz ablegen, Seite öffnen, Aufgabe abhaken
- Anmeldung mit Passkey, Face ID/Touch ID oder Passwort; die Sitzung erneuert sich über einen Geräte-Schlüssel
  im Schlüsselbund ohne erneutes Anmeldefenster
- **Sprachen:** Deutsch, Englisch, Spanisch, Thai, Russisch – die App folgt der Systemsprache (sonst Englisch) oder der
  in der App gewählten Sprache (iPhone/iPad: Einstellungen-App → Saga → Sprache; Mac: Einstellungen → Sprache).
  Thai und Russisch sind maschinell übersetzt, Gegenlesen willkommen

## Voraussetzungen
- macOS mit **Xcode 26** (iOS/macOS 26 SDK) und [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`)
- Ein erreichbarer Saga-Server (https)
- Für Geräte-Builds: ein Apple-Developer-Konto (kostenloses Personal Team genügt für eigene Geräte aus Xcode)

## Einrichten
Das Xcode-Projekt wird aus `project.yml` erzeugt und nicht eingecheckt. App-ID, App-Gruppe und Team kommen aus
`Config/Base.xcconfig`; eigene Werte gehören in die **nicht versionierte** Datei `Config/Local.xcconfig`:

```sh
cd ios
cp Config/Local.xcconfig.example Config/Local.xcconfig
# DEVELOPMENT_TEAM (eigene Team-ID) und SAGA_BUNDLE_ID (eigene App-ID, z. B. com.example.saga) eintragen
xcodegen generate                # erzeugt Hlidskjalf.xcodeproj
open Hlidskjalf.xcodeproj        # Gerät wählen → ▶︎
```

- Die Erweiterungen erhalten automatisch `<App-ID>.widget`, `.share` und `.sharemac`, die App-Gruppe heißt
  `group.<App-ID>` (änderbar über `SAGA_APP_GROUP`). Xcode legt beides bei automatischer Signierung selbst an.
- Optional: `SAGA_DEFAULT_SERVER` belegt die Server-Adresse vor (z. B. für per MDM verteilte Builds).
- Ohne `Local.xcconfig` gelten die öffentlichen Vorgaben (`io.github.yggdrasil-sh.saga`, kein Team) – das reicht
  zum Prüfen ohne Signatur:

```sh
xcodebuild -project Hlidskjalf.xcodeproj -scheme Hlidskjalf \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

### Chat-Kryptografie prüfen
`App/ChatCrypto.swift` muss die Testvektoren des Servers (`tests/chat_vectors.json` im Saga-Repo) vollständig
bestehen:

```sh
cd ios/Tests/ChatVectors && swift run -c release ChatVectors /pfad/zu/saga/tests/chat_vectors.json
```

Die Anruf-Signalisierung (`App/CallCrypto.swift`, `App/CallLogic.swift`) prüft dasselbe Programm gegen
`tests/call_vectors.json` (neben `chat_vectors.json` oder als zweites Argument).

### Anrufe (WebRTC, CallKit, PushKit)
- WebRTC kommt als vorgebautes libwebrtc über SPM: seit 2.5 `webrtc-sdk` (LiveKit-Zweig, BSD; xcframework aus
  `webrtc-sdk/Specs`, Version und Prüfsumme in der Hülle `Packages/WebRTC/Package.swift`, weil das Specs-Manifest mit
  Xcode 27 nicht lädt). Modul `WebRTC`, Klassen ohne Präfix wie bisher; dazu FrameCryptor mit HKDF für Gjallarhorn.
  Am Mac heißt die Video-Ansicht `RTCMTLVideoView` (ein NSView; `RTCMTLNSVideoView` gibt es dort nicht mehr).
- **Gjallarhorn-Frame-E2E (2.5, `saga-horn/v1`, Vertrag `docs/HORN-E2E.md` im Saga-Repo):** Logik `App/HornE2E.swift`
  (CryptoKit: Ableitungen, Rahmenformat, Empfänger mit Wiederholungsschutz, Runensiegel, `fkey`), FrameCryptor-Einstellungen
  `App/HornCryptor.swift`. Test (XCTest, ohne Host-App) gegen `Tests/vectors/horn_e2e.json` (Kopie aus dem Saga-Repo):
  `xcodebuild -scheme HornE2ETests -destination 'platform=macOS' test` bzw. mit einer iOS-Simulator-ID. Der Loopback
  zweier PeerConnections mit FrameCryptor läuft nur im Simulator (stilles Audio-Gerät, Testbild aus dem Speicher; am Mac
  übersprungen, damit kein Test CoreAudio/Mikrofon berührt).
- **Gruppenanrufe über Gjallarhorn-Knoten (2.5, P11 Stufe 2; Vertrag `docs/APP-API.md` „Anrufe über
  Gjallarhorn-Knoten“):** Meldet der Server `topo: "horn"`, misst die App die Kandidaten (TLS + Pings über `/probe`,
  UDP-Probe; ≤ 800 ms), verbindet je Knoten eine Ton- und eine Bild-Leitung (ICE-TCP, sonst TURN-TLS über den Knoten;
  auf UDP eine Leitung) und wechselt live make-before-break mit derselben Hysterese wie das Web (`static/horn.js`).
  Frame-E2E per FrameCryptor mit Saga-Ratsche (Plätze vorab), `fkey`/`fkey-ack` über den versiegelten Signalweg.
  Dateien: `App/HornLogic.swift` (Entscheidungen, Ereigniskanal, Schlüsselplan), `App/HornNode.swift` (Knoten-API),
  `App/HornMedia.swift` (Bein = PeerConnection), `App/HornCall.swift` (Ablauf), `App/HornViews.swift` (Kacheln,
  Sprecher, Teilnehmerliste, Weg, Runensiegel). Gerät meldet `horn` in `caps` (nur mit Server-Feature `horn`).
  Tests: `HornLogicTests` (Fälle wie `tests/horn_logik.js`) im Ziel `HornE2ETests`; Integrationstest `HornNodeTests`
  gegen einen lokalen Knoten (nur 127.0.0.1, `HORN_DEV=1 HORN_UDP=0 HORN_TLS_SELF=1 HORN_ICE_IPS=127.0.0.1
  HORN_LISTEN=127.0.0.1:<port> gjallarhorn node`), Aufruf mit `TEST_RUNNER_SAGA_HORN_NODE=https://127.0.0.1:<port>`
  und einer eigenen iOS-Simulator-ID (am Mac läuft nur die Messung). Bilder ohne Server: Startparameter
  `-hkHornPreview grid|speaker|share|people|runes` (Debug, erfundener Zustand).
- iPhone/iPad: Hintergrundmodi `voip` und `audio` (Info.plist), CallKit für das Klingeln, PushKit-Token als eigenes
  Mitteilungs-Abo `apns-voip`. Zusätzliche Berechtigungen braucht die App-ID nicht: die Fähigkeit „Push
  Notifications“ reicht, der Server schickt mit demselben APNs-Schlüssel (.p8) an das Thema `<App-ID>.voip`.
- Mac: Kamera/Mikrofon und eingehende Verbindungen sind in `App/App-macOS*.entitlements` freigegeben (Sandbox).
- Tests ohne Kamera/Mikrofon (Simulator, Debug): Startparameter `-hkFakeMedia YES` (Testbild + Testton, kein CallKit).
- **Saga 2.4 (saga-call v3, Vertrag: `docs/APP-API.md` im Saga-Repo):** Signal-Bodys tragen zusätzlich `pc`
  (sortiertes Paar der device_ids, vom Anrufer festgelegt), `caps` (`reneg` nur mit Server-Feature `reneg`, dazu `want`,
  `bye`), `n` und bei ICE-Restart `restart`; ICE-Kandidaten tragen `usernameFragment`; neu sind `bye` und
  `state.pip`/`want`/`path`. Neuverhandlung (Perfect Negotiation, höflich = größere device_id) und ICE-Restart
  (`disconnected` > 2,5 s, `failed`, Netzwechsel per `NWPathMonitor`; 3 Versuche, 20 s) nur, wenn beide Seiten `reneg`
  melden – mit 2.3-Apps bleibt alles wie bisher. Bitrate je Pfad (direkt/Relay-UDP/Relay-TCP, alle 2 s aus getStats; es gilt der
  schlechtere von eigenem und gemeldetem Weg) über `RTCRtpSender.parameters`. Logik: `App/CallV3Logic.swift`, Tests: `Tests/Logic/CallV3Tests.swift`.
- **Bild im Bild:** iPhone/iPad über `AVPictureInPictureController` (Videoanruf-Quelle, `AVSampleBufferDisplayLayer`,
  `App/CallPiP.swift`, Bilder ohne Kopie: `App/PiPBuffer.swift`); Mac als schwebendes `NSPanel` (`App/CallPanel.swift`).
  Kamera im PiP nur, wo `AVCaptureSession.isMultitaskingCameraAccessSupported` gilt (sonst pausiert sie, das Gegenüber
  sieht „Kamera aus“); eine zusätzliche Berechtigung wird nicht eingetragen.
- **Mehrere Geräte und Gruppen (2.4, Vertrag „Anrufe: Mehrgeräte und Gruppen“):** Ein Anruf ist ein Raum mit je
  anderem Gerät einer Verbindung (`App/CallLink.swift`, Mesh, `pc` = sortiertes Paar). Begleitgerät, „Hierher
  übernehmen“, Gruppenanrufe, Leiste „Anruf läuft“ und Teilnehmerliste mit Stand: `App/CallRoom.swift`; Logik
  `App/CallRoomLogic.swift`, Tests `Tests/Logic/CallRoomTests.swift`. Höchstens 4 Geräte mit Video, 6 nur mit Ton.
- **Bildschirm teilen (2.4, Vertrag „Anrufe: Bildschirm v2“):** Betriebsart B (eigene Video-m-Zeile über die
  Neuverhandlung, Kamera daneben ≤ 300 kbit/s bzw. über Relais TCP aus) nur, wenn beide Seiten `reneg` und `screen2`
  melden; sonst A (Bildschirm statt Kamera auf der Kamera-m-Zeile, wie 2.1/2.3). Beim Ende geht die Bildschirm-m-Zeile
  auf `recvonly` und wird beim nächsten Teilen wiederverwendet. Empfang: Zuordnung über `state.screen_mid` und die
  Rollen `tracks` (mid ist auf beiden Seiten gleich). Logik: `App/ScreenShareLogic.swift`, Tests:
  `Tests/Logic/ScreenShareTests.swift`.
  - Mac: ScreenCaptureKit mit `SCContentSharingPicker` (Freigabe je Sitzung, keine dauerhafte
    Bildschirmaufnahme-Berechtigung), Saga selbst ist ausgeschlossen; NV12-Bilder gehen ohne Kopie an WebRTC
    (`App/ScreenCapture.swift`).
  - iPhone/iPad: ReplayKit-Erweiterung `HlidskjalfBroadcast` (`Broadcast/SampleHandler.swift`, Bundle-ID
    `<App-ID>.broadcast`, dieselbe App-Gruppe). Sie verkleinert auf ≤ 1920 (heiß 1280) lange Kante, ≤ 10 fps (heiß 5)
    und schickt NV12 über einen Unix-Socket im App-Gruppen-Container (`Shared/ScreenFrame.swift`); ist das vorige Bild
    noch unterwegs, wird das neue verworfen (kein Puffern, Speichergrenze der Erweiterung ca. 50 MB).
  - Systemton wird nicht übertragen (`screen_audio: false`): die WebRTC-Fassung hat am Mac kein eigenes Audio-Gerät,
    am iPhone bräuchte der Ton der Erweiterung einen zweiten Weg in die Tonspur.
  - **Signierung:** Die Erweiterung braucht eine eigene App-ID `<App-ID>.broadcast` mit der Fähigkeit „App Groups“
    (dieselbe Gruppe wie die App). `build-adhoc.sh`/`build-testflight.sh` legen App-ID und Profil mit
    `-allowProvisioningUpdates` selbst an, wenn der API-Schlüssel die Rolle „Admin“ oder „App Manager“ hat. Sonst einmal
    im Developer-Konto: Certificates, Identifiers & Profiles → Identifiers → + → App IDs → App → Bundle ID (explizit)
    `<App-ID>.broadcast` → App Groups anhaken → Continue/Register; dann den Identifier öffnen → App Groups → Configure →
    die Gruppe der App wählen → Save. Für MDM/Ad-hoc ein Profil „Ad Hoc“ für diese App-ID mit den registrierten Geräten.

#### Energie messen: Bild im Bild (Konzept 2.4 §3.5/§8)
Nur auf echten Geräten (iPhone 12-Klasse und aktuelles iPhone), Debug-Build über Xcode, Gegenüber im selben WLAN,
Bildschirmhelligkeit fest, Akku über 50 %, kein Ladekabel bei MetricKit-Läufen. Jede Messung dreimal, Median.
1. **Vollbild (Referenz):** 10 min Videoanruf im Vollbild. In Xcode → Debug-Navigator → *Energy Impact* den
   Durchschnitt notieren; parallel Instruments → *Time Profiler* + *Thermal State* mitlaufen lassen.
2. **Bild im Bild:** Gleicher Anruf, nach 30 s „Zurück zur App“ (Pfeil oben links) bzw. Home-Geste – PiP öffnet sich.
   10 min so lassen, dieselben Werte notieren.
3. **Gegenseite:** Ist das Gegenüber ein Browser (Saga 2.4), zeigt `chrome://webrtc-internals` → `outbound-rtp`
   (video) während Schritt 2 die gesendete Rate: erwartet ≤ 300 kbit/s, nach der Rückkehr wieder bis 1,5 Mbit/s.
4. **Ziel:** Energie im PiP ≤ 90 % des Vollbilds, Wärme keine Stufe höher, Mehr-CPU für die Bildübergabe ≤ 3 %
   (Time Profiler: `PiPRenderer.renderFrame` + `PiPSampleMaker.wrap`). Werte in die Tabelle des Release-Dokuments.
5. **MetricKit (optional):** Zwei Läufe an verschiedenen Tagen, `MXCPUMetric`/`MXDisplayMetric` aus dem Tagesbericht.

### Mitteilungen (APNs)
iOS/iPadOS haben die Berechtigung `aps-environment` immer (`App/App-iOS.entitlements`); die App-ID braucht dafür die
Fähigkeit „Push Notifications“ (automatische Signierung richtet sie ein). Am Mac braucht Push ein
Bereitstellungsprofil und ist deshalb abschaltbar: `SAGA_MAC_PUSH_SUFFIX = -push` in `Config/Local.xcconfig`
nutzt `App/App-macOS-push.entitlements` (für ad-hoc signierte DMG-Builds leer lassen). Den APNs-Schlüssel trägt der
Admin im Saga-Admin-Bereich ein; Debug-Builds melden sich als `sandbox`, alle anderen als `production` an.

## Sprachen
Alle Texte stehen in String Catalogs (Quellsprache Englisch = Rückfall für andere Systemsprachen):
`Shared/Localizable.xcstrings` (App, Widget, Teilen-Erweiterungen), `App/AppShortcuts.xcstrings` (Siri-Sätze),
`App/InfoPlist.xcstrings` (Kamera/Mikrofon/Fotos-Hinweise). Die Schlüssel sind die deutschen Texte aus dem Code; neue Texte
in SwiftUI (`Text("…")`, `Button("…")` …) übernimmt Xcode beim Bauen automatisch in den Catalog, Texte in Variablen
und Fehlermeldungen mit `String(localized: "…")` schreiben. Datum und Zahlen immer über `.formatted()`/Formatter
der Systemsprache. `xcodebuild` kompiliert die Catalogs auch auf der Kommandozeile.

## Anmelden
Beim ersten Start fragt die App nach dem Server (z. B. `https://saga.example.org`) und öffnet das
System-Anmeldefenster. Schneller geht es per **QR-Code**: in Saga im Browser 👤 → Einstellungen →
„Apps & Geräte“ den Kopplungs-Code anzeigen und in der App „Mit QR-Code anmelden“ wählen (am Mac den
Kopplungslink einfügen). Widgets und Teilen-Erweiterung nutzen einen eng begrenzten **App-Schlüssel**
(darf nur ablegen und „Heute“ lesen); die App übernimmt ihn bei der Anmeldung automatisch.

## Bauen und Verteilen
Die Skripte lesen optional `Config/local.env` (nicht versioniert, Vorlage `Config/local.env.example`).

| Skript | Zweck |
|---|---|
| `./build-install.sh` | Baut für iPhone (`SAGA_DEVICE_ID`, falls verbunden) bzw. prüft den Simulator-Build und baut die Mac-App nach `/Applications/Saga.app` |
| `./build-testflight.sh` | App-Store-Build, Upload zu TestFlight |
| `./build-adhoc.sh` | Ad-hoc-`.ipa` (registrierte Geräte, 1 Jahr gültig) nach `dist/`; ruft danach optional `SAGA_PUBLISH_CMD <ipa>` auf (z. B. Upload ins MDM) |
| `./build-dmg.sh` | Mac-App als signierte, notarisierte DMG für GitHub-Releases nach `dist/Saga-<Label>-macOS.dmg` (Developer ID, ohne Profile; Optionen im Skriptkopf) |

**Angezeigte Version:** Alle Builds außer dem offiziellen Release zeigen in Einstellungen/„Über“ `2.2.0-rc.N`
(Info.plist-Schlüssel `SagaVersionLabel`; `CFBundleShortVersionString` bleibt `2.2.0`), die Build-Nummer
(`CFBundleVersion`) ist Datum/Uhrzeit. Beides kommt aus `../tools/version-label.sh` – die Skripte setzen es direkt,
`xcodegen generate` schreibt es nach `Config/Version.xcconfig` (nicht versioniert; auch für Builds aus Xcode).
Für das offizielle Release bzw. den App Store: `SAGA_RELEASE=1` (oder vom Release-Tag aus bauen).

TestFlight und Ad-hoc signieren über einen **App-Store-Connect-API-Schlüssel**, übergeben in
`ASC_KEY_PATH`, `ASC_KEY_ID` und `ASC_ISSUER`; `build-dmg.sh` nutzt ihn zum Notarisieren.

- **DMG (GitHub-Releases):** braucht ein Zertifikat „Developer ID Application“ (anlegen kann es nur der
  Account Holder: Xcode → Einstellungen → Accounts → Zertifikate verwalten → + → Developer ID Application).
  Die Mac-App nutzt dafür die App-Gruppe `<TEAM>.<App-ID>` (kein Bereitstellungsprofil nötig);
  `build-dmg.sh` baut immer mit der öffentlichen App-ID `io.github.yggdrasil-sh.saga` (außer `SAGA_BUNDLE_ID` ist gesetzt)
  und ohne vorbelegten Server.
- **Selbst-Update der DMG (Sparkle 2):** Die DMG-Fassung sucht selbst nach neuen Versionen (Menü „Saga → Nach Updates
  suchen …“, automatisch alle 4 Stunden, abschaltbar in den Einstellungen) und installiert sie auf Wunsch – trotz
  Sandbox über Sparkles Installer-Dienst. Testversionen (`x.y.z-rc.N`) lesen `appcast-rc.xml`, Releases `appcast.xml`,
  beide im Zweig `appcast` (`https://raw.githubusercontent.com/<repo>/appcast/…`). Die CI erzeugt und signiert sie
  (EdDSA, `tools/appcast.py`, `tools/publish-appcast.sh`), wenn das Secret `SPARKLE_ED_PRIVATE_KEY` gesetzt ist; ohne
  das Secret bleibt das Selbst-Update in der DMG aus. Nur `build-dmg.sh` schaltet es ein (Info.plist-Schlüssel,
  mach-lookup-Ausnahmen `<App-ID>-spks/-spki`); eigene Builds, MDM und iOS bleiben ohne.
  Schlüssel einmalig anlegen: Sparkle 2.10.0 von GitHub laden, `./bin/generate_keys` (legt ihn im Schlüsselbund ab),
  `./bin/generate_keys -x <datei>` exportieren, als Secret hinterlegen (`gh secret set SPARKLE_ED_PRIVATE_KEY < <datei>`),
  Datei löschen. Den Schlüssel sicher aufbewahren: Ohne ihn bekommen installierte Apps keine Updates mehr.

- **Personal Team (kostenlos):** Installation nur aus Xcode, die App läuft 7 Tage; auf dem Gerät ist der Entwicklermodus nötig.
- **TestFlight (empfohlen):** kein Entwicklermodus, keine Geräte-Registrierung; Tester per Apple-ID einladen.
  Builds laufen nach 90 Tagen ab → neu hochladen.
- **Ad-hoc / MDM:** Geräte im Developer-Konto registrieren, `.ipa` per Apple Configurator oder MDM
  (`InstallApplication`) verteilen. Ad-hoc-Apps verlangen auf dem Gerät den Entwicklermodus.

Wichtig für Updates: App-ID, App-Gruppe und Team eines einmal verteilten Builds nicht mehr ändern – sonst
gelten Updates als neue App und gespeicherte Anmeldungen (Schlüsselbund `<App-ID>.device-login`) gehen verloren.

## Screenshots für das README

`Screenshots/` enthält einen UI-Test, der die Bilder in `docs/screenshots/` kopflos im Simulator aufnimmt
(eigenes Projekt aus `screenshots.yml`, der normale Build bleibt unberührt). **Nur gegen einen Demo-Server
mit Beispieldaten verwenden** (z. B. `tools/screens-remote.sh <port>` im Server-Repo).

```sh
xcrun simctl boot <UDID>
SAGA_PAIR_LINK='saga://pair?u=http://127.0.0.1:<port>&c=<code>' \
  Screenshots/make-screenshots.sh <UDID> ../docs/screenshots build
```

Sprache wählen mit `SAGA_SHOT_LANG=de|en|es` (Vorgabe `de`); englische und spanische Bilder bekommen die
Endung `-en`/`-es` (z. B. `ios-start-en.png`).

Den Kopplungs-Link (`SAGA_PAIR_LINK`) nie einchecken – er gilt ohnehin nur zwei Minuten.

## Lizenz
AGPL-3.0 – siehe `LICENSE` im Repository.
