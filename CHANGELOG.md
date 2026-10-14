# Changelog

Alle nennenswerten Änderungen an den Saga-Apps. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionen nach [Semantic Versioning](https://semver.org/lang/de/). Neue Version: `tools/set-version.sh x.y.z`,
Abschnitt hier ergänzen, auf `main` bringen – GitHub baut und veröffentlicht dann alle Downloads.

## [Unveröffentlicht]

### Neu
- **Seiten veröffentlichen** (braucht Saga-Server 1.2) in allen Apps: Seitenmenü „Veröffentlichen …“ mit
  öffentlichem Namen, Adresse, Unterseiten, Suchmaschinen, Profilseite, Link kopieren/öffnen und „Veröffentlichung
  beenden“; Hinweis „Öffentlich“ im Seitenkopf; „Öffentlicher Name“ unter Einstellungen → Konto.
  Fehlermeldungen des Servers kommen in der App-Sprache (Accept-Language).
  Dialog auf allen Plattformen gleich: Schalter → Link (solange privat gedämpft; bei Profilseite mit Profiladresse)
  → Adresse („Adresse ändern“ erst bei abweichender Eingabe) → Optionen → „Veröffentlichung beenden“; Schalter statt
  Häkchen, Fehler direkt am Feld, einheitliche Rückfragen mit Titel. Android-Seitenmenü: „App-Link kopieren“ und
  „Im Web-Editor öffnen“.

## [1.1.0] – 2026-10-14

### Neu
- **Drei Sprachen:** Alle Apps auf Deutsch, Englisch und Spanisch (iOS/macOS String Catalogs, Linux gettext,
  Android strings.xml mit Sprache je App). Andere Systemsprachen fallen auf Englisch zurück.
- **Android:** Teilen-Ziel („Teilen → Saga“), Seitenmenü mit Archivieren und Papierkorb, Rückgängig beim Abhaken,
  eigene Zertifizierungsstellen (z. B. für den eigenen Server).
- Einheitliche Dialoge für Kopplung, Konflikt, Entwurf und Offline in allen Apps.

### Sicherheit
- Kopplungslinks (`saga://pair`) werden erst nach einer Rückfrage mit Zielserver übernommen, nur über https.
  Der Server wird erst nach erfolgreicher Kopplung gewechselt.
- Geräte- und App-Schlüssel sind an den Server gebunden. Ein Serverwechsel meldet sauber ab.
- iOS: Widget-Schlüssel liegt im Schlüsselbund statt in der App-Gruppe.

### Behoben
- Speichern verliert keine Eingaben mehr: Speicherungen laufen nacheinander, ein Konflikt (409) wird als Konflikt
  gezeigt statt verworfen, Entwürfe bleiben je Server und Konto erhalten, Schließen wartet aufs Speichern.
- Android: Haken in „Heute“ wird gespeichert, schnelles Tippen verdreht keine Zeichen mehr.
- iOS: Tabellen-Block überschreibt keine Tabellen mehr und behält die Formatierung.
- Anmeldung im Browser abgebrochen: Die Apps zeigen einen Hinweis statt still zum Start zurückzukehren.
- Dienst-Symbole im Dunkelmodus sind wieder gut sichtbar (helle Plakette).

### Verbessert
- Linux: Editor ändert nur betroffene Blöcke (schnell auch bei langen Seiten), Schreib-Warteschlangen,
  Seitenleiste per Tastatur und Screenreader bedienbar.
- iOS: Beschriftungen für Symbol-Knöpfe (VoiceOver), Ansichtsmenü für Datenbanken auf dem iPhone, weniger Neuzeichnen.
- Tests: Linux 56 (im Container), Android 64, iOS-Logik 70.

## [1.0.0] – 2026-10-13

Erste öffentliche Version der nativen Apps für einen eigenen [Saga](https://github.com/yggdrasil-sh/saga)-Server.

### Neu
- **iOS/iPadOS und macOS** (SwiftUI, WidgetKit): Seiten, Datenbanken, Aufgaben und Termine nativ anzeigen und
  bearbeiten. Anmeldung mit Serveradresse oder per QR-Code aus „Apps & Geräte“.
- **Linux/GNOME** (GTK4 + libadwaita): dieselben Bereiche, einklappbare Seitenleiste, GNOME-Suche.
- **Android** (Kotlin, Jetpack Compose): Heute, Seiten, Aufgaben, Termine, Dienste und Suche; Anmeldung mit
  Serveradresse oder per QR-Code.

### Downloads
- macOS als signierte und von Apple beglaubigte DMG.
- Linux als RPM (Fedora, AlmaLinux/RHEL 10 mit EPEL) und als Flatpak.
- Android als signierte APK.
