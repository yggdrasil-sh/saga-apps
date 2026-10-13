# Saga für iPhone, iPad und Mac

Native App (SwiftUI) für einen [Saga](https://github.com/yggdrasil-sh/saga)-Server – eine Codebasis für iOS, iPadOS und macOS.

## Was die App kann
- **Seiten und Notizen** lesen und bearbeiten (Blöcke, Titelbild, Symbol, Unterseiten, Favoriten, zuletzt geöffnet)
- **Datenbanken** mit Ansichten, Filtern und Sortierung; neue Einträge passend zur Ansicht
- **Aufgaben und Termine** („Heute“, Agenda, Wiederholungen, Abhaken); Kalender per CalDAV verbinden
- **Befehlspalette** (⌘K) zum Suchen nach Seitennamen und Inhalt
- Bilder und Dateien anhängen, optional Fotos aus Immich übernehmen
- **Widgets**: „Heute“ (Termine + Aufgaben, direkt abhaken), „Seite“, „Seite oder Datenbank“
- **Teilen-Erweiterung** (iOS und macOS): Link oder Text als Seite im Eingang oder als Aufgabe ablegen
- **Kurzbefehle/Siri**: Aufgabe anlegen, Notiz ablegen, Seite öffnen, Aufgabe abhaken
- Anmeldung mit Passkey, Face ID/Touch ID oder Passwort; die Sitzung erneuert sich über einen Geräte-Schlüssel
  im Schlüsselbund ohne erneutes Anmeldefenster
- **Sprachen:** Deutsch, Englisch, Spanisch – die App folgt der Systemsprache, sonst Englisch

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

## Sprachen
Alle Texte stehen in String Catalogs (Quellsprache Englisch = Rückfall für andere Systemsprachen):
`Shared/Localizable.xcstrings` (App, Widget, Teilen-Erweiterungen), `App/AppShortcuts.xcstrings` (Siri-Sätze),
`App/InfoPlist.xcstrings` (Kamera/Fotos-Hinweise). Die Schlüssel sind die deutschen Texte aus dem Code; neue Texte
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
| `./build-dmg.sh` | Mac-App als signierte, notarisierte DMG für GitHub-Releases nach `dist/Saga-<VERSION>-macOS.dmg` (Developer ID, ohne Profile; Optionen im Skriptkopf) |

TestFlight und Ad-hoc signieren über einen **App-Store-Connect-API-Schlüssel**, übergeben in
`ASC_KEY_PATH`, `ASC_KEY_ID` und `ASC_ISSUER`; `build-dmg.sh` nutzt ihn zum Notarisieren.

- **DMG (GitHub-Releases):** braucht ein Zertifikat „Developer ID Application“ (anlegen kann es nur der
  Account Holder: Xcode → Einstellungen → Accounts → Zertifikate verwalten → + → Developer ID Application).
  Die Mac-App nutzt dafür die App-Gruppe `<TEAM>.<App-ID>` (kein Bereitstellungsprofil nötig);
  `build-dmg.sh` baut immer mit der öffentlichen App-ID `io.github.yggdrasil-sh.saga` (außer `SAGA_BUNDLE_ID` ist gesetzt)
  und ohne vorbelegten Server.

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
