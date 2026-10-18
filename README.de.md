<p align="center">
  <img src="linux/data/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg" width="96" height="96" alt="Saga">
</p>

<h1 align="center">Saga Apps</h1>

<p align="center">
  Native Apps für <a href="https://github.com/yggdrasil-sh/saga/blob/main/README.de.md">Saga</a> – dein selbst gehostetes Zuhause für Dienste, Notizen und Aufgaben.<br>
  iPhone · iPad · Mac · Linux (GNOME) · Android
</p>

<p align="center">
  <a href="README.md">English</a> · <b>Deutsch</b> · <a href="README.es.md">Español</a>
</p>

---

Die Apps verbinden sich mit deinem eigenen Saga-Server. Seiten, Datenbanken, Aufgaben und Termine werden
**nativ** angezeigt und bearbeitet – keine eingebettete Webseite. Sie sprechen **fünf Sprachen** – Deutsch,
Englisch, Spanisch, Thai und Russisch. Sie folgen der Systemsprache (alle anderen Sprachen: Englisch), oder du
wählst die Sprache unter **Einstellungen → Sprache** – auch schon vor der Anmeldung, praktisch an einem fremden
oder geteilten Gerät. Thai und Russisch sind maschinell übersetzt; Verbesserungen von Muttersprachlern sind willkommen.

| Plattform | Technik | Ordner | Anleitung |
|---|---|---|---|
| iOS, iPadOS, macOS | SwiftUI, WidgetKit | [`ios/`](ios/) | [ios/README.md](ios/README.md) |
| Linux (GNOME) | Python, GTK4 + libadwaita, RPM | [`linux/`](linux/) | [linux/README.md](linux/README.md) |
| Android | Kotlin, Jetpack Compose | [`android/`](android/) | [android/README.md](android/README.md) |

## So sieht es aus

<p align="center">
  <img src="docs/screenshots/ios-start.png" width="200" alt="iPhone: Startseite mit Aufgaben und zuletzt geöffneten Seiten">
  <img src="docs/screenshots/ios-dunkel-board.png" width="200" alt="iPhone: Datenbank als Board im dunklen Design">
  <img src="docs/screenshots/android-dienste.png" width="200" alt="Android: Dienste nach Gruppen">
  <img src="docs/screenshots/android-heute.png" width="200" alt="Android: Heute mit Terminen und Aufgaben">
</p>
<p align="center">
  <img src="docs/screenshots/ipad-projekte.png" width="620" alt="iPad: Seitenleiste und Datenbank als Board">
</p>

## Herunterladen

Fertige Pakete gibt es unter [Releases](https://github.com/yggdrasil-sh/saga-apps/releases/latest):

| System | Datei |
|---|---|
| macOS 26 oder neuer | `Saga-<Version>-macOS.dmg` – signiert und von Apple beglaubigt |
| Linux (Fedora, AlmaLinux/RHEL 10 mit EPEL) | `saga-<Version>-1.noarch.rpm` |
| Linux (jede Distribution mit Flatpak) | `saga-<Version>-x86_64.flatpak` (`flatpak install --user saga-….flatpak`) |
| Android 8 oder neuer | `Saga-<Version>.apk` (Installation aus unbekannten Quellen erlauben) |

iPhone und iPad: selbst bauen (siehe [ios/README.md](ios/README.md)) – für den App Store gibt es noch keine Version.

## Anmelden

1. Saga-Server eintragen (z. B. `https://saga.example.org`) und **Anmelden** – die Anmeldung öffnet sich im Browser
   (lokales Konto, LDAP oder SSO, je nach Server).
2. Oder bequemer: im Browser in Saga oben auf 👤 → **Einstellungen** → **Apps & Geräte** → **App per QR-Code anmelden**
   und den Code mit der App scannen (Mac/Linux: Kopplungslink einfügen).

Die Apps bleiben danach dauerhaft angemeldet. Abmelden lässt sich jedes Gerät jederzeit in Saga unter
„Apps & Geräte“.

## Seiten veröffentlichen

Ab Saga-Server 1.2 kann jede App eine Seite öffentlich machen: Seitenmenü → **Veröffentlichen …**. Beim ersten
Mal wählst du einen **öffentlichen Namen** (er steht in der Adresse `…/@name/…`, nie der Anmeldename). Danach
legst du Adresse, **Unterseiten mit veröffentlichen**, **Suchmaschinen erlauben** und **Als Profilseite** fest,
kopierst oder öffnest den Link oder wählst **Veröffentlichung beenden**. Öffentliche Seiten zeigen im Seitenkopf
**Öffentlich**; den öffentlichen Namen änderst du unter **Einstellungen → Konto**. Bei älteren Servern fehlt der
Menüpunkt.

## Neu in 2.1 (mit Saga-Server 2.1)

- **Audio- und Videoanrufe** im Direkt-Chat, Ende-zu-Ende verschlüsselt. iPhone/iPad klingeln über CallKit (braucht
  einen APNs-Schlüssel am Server), Android als Vollbild-Anruf (im Hintergrund mit einem UnifiedPush-Verteiler), der Mac
  mit eigenem Anruf-Fenster; unter Linux klingelt die App und das Gespräch läuft im Browser.
- **Profilbilder** überall, wo Personen erscheinen; eigenes Bild mit Zuschnitt in den Einstellungen.
- **Anmeldung & Sicherheit:** Stand von Passkeys und Authenticator-App in den Einstellungen; die Anmeldung mit
  zweitem Faktor läuft im Browserfenster.
- **Schnelle Chat-Bilder** mit Vorschaubildern, **Immich im Chat**, Bilder einfügen und Bildblöcke kopieren.
- **KI mit Namen und Profilbild:** Gibt der Admin der KI einen Namen und ein Bild (z. B. „Marin“), zeigen die Apps sie
  überall; in Chats, in denen sie erlaubt ist, fragst du sie mit **@Marin** – nur diese Nachrichten (und bis zu 12
  davor) gehen an die KI, alles andere bleibt Ende-zu-Ende-verschlüsselt.
- **Chats archivieren und löschen:** Archivierte Chats wandern in einen aufklappbaren Bereich „Archiviert“;
  **Chat löschen …** nur für dich oder – wenn der Server es erlaubt – für alle, samt Zwischenspeicher auf allen
  eigenen Geräten.
- **Sprache in der App wählen** und neu **Thai und Russisch** (funktioniert mit jedem Server).

Die neuen Funktionen brauchen Saga-Server 2.1; mit älteren Servern arbeiten die Apps wie bisher.

## Neu in 2.0 (mit Saga-Server 2.0)

- **Teilen und Kommentare:** Seiten mit Personen und Gruppen teilen (Lesen, Kommentieren, Bearbeiten), Kommentare
  am Block mit Antworten und „Erledigt“.
- **Benachrichtigungen:** Glocke mit allem, was dich betrifft; auf Seiten siehst du, wer auch hier ist oder bearbeitet.
- **Chats** mit Ende-zu-Ende-Verschlüsselung: Direkt-, Gruppen- und Seiten-Chats. Beim ersten Mal bekommst du einen
  **Wiederherstellungsschlüssel**; neue Geräte bestätigst du per Code, die **Sicherheitsnummer** vergleichst du mit
  deinem Gegenüber.
- **Mitteilungen** (nie mit Inhalten): iOS/iPadOS über Apple Push (der Admin trägt einen APNs-Schlüssel ein), Android
  und Linux über [UnifiedPush](https://unifiedpush.org), z. B. mit ntfy – ohne Google-Dienste.
- **KI-Assistent** (wenn der Admin ihn einschaltet): **Frag deine Seiten** und **Diese Seite**.
- **Föderation:** Seiten anderer Saga-Instanzen erscheinen unter **Mit mir geteilt**; Teilen an `name@server`.

Die neuen Funktionen brauchen Saga-Server 2.0. Alles ist optional – mit älteren Servern arbeiten die Apps wie bisher.

## Selbst bauen

Persönliche Einstellungen (Entwickler-Team, App-ID, Testgeräte, Verteil-Befehle) stehen **nie** im Repository,
sondern in lokalen Dateien, die git ignoriert. Vorlagen liegen jeweils daneben:

- `ios/Config/Local.xcconfig.example` → `ios/Config/Local.xcconfig`
- `ios/Config/local.env.example` → `ios/Config/local.env`
- `tools/local.env.example` → `tools/local.env` (Rechner für die Linux-Tests)

Version aller Apps setzen: `tools/set-version.sh 2.1.0` (eine Quelle: `VERSION`). Landet die Änderung an
`VERSION` auf `main`, baut GitHub alle Pakete und veröffentlicht sie als Release (`.github/workflows/release.yml`).
Neues landet zuerst auf dem Branch `testing`: Jeder Push dort baut alle Pakete und ersetzt die
Vorabversion `v<Version>-testing` (Pre-release, nie „Latest“).

## Versionen

Die Apps folgen [Semantic Versioning](https://semver.org/lang/de/). Sie zeigen in den Einstellungen ihre eigene
Version und die des Servers; ist eine App für den Server zu alt, weist sie darauf hin.

## Lizenz

[GNU AGPL-3.0](LICENSE) – wie Saga selbst.
