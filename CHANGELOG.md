# Changelog

Alle nennenswerten Änderungen an den Saga-Apps. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionen nach [Semantic Versioning](https://semver.org/lang/de/). Neue Version: `tools/set-version.sh x.y.z`,
Abschnitt hier ergänzen, auf `main` bringen – GitHub baut und veröffentlicht dann alle Downloads.

## [Unveröffentlicht]

### Neu (Android, passt zu Saga-Server 2.0; alles optional, ältere Server unverändert)
- **Live-Kanal und Benachrichtigungen:** Glocke mit Zähler und Liste (gelesen, „Alle gelesen“, „Ältere laden“),
  Einstellungen je Art unter Einstellungen → Benachrichtigungen. Auf Seiten: wer gerade da ist bzw. bearbeitet,
  Änderungen anderer werden still nachgeladen (sonst Hinweis „… hat diese Seite geändert“ + „Neu laden“),
  Kommentare live. Eine Verbindung, nur im Vordergrund.
- **Chats mit Ende-zu-Ende-Verschlüsselung** (libsodium über lazysodium-android): Einrichtung mit
  Wiederherstellungsschlüssel, neues Gerät per „Mit anderem Gerät bestätigen“ (20-stelliger Code) oder
  Wiederherstellungsschlüssel, „Neu beginnen“ (mit Passwort), Sicherheitsnummer mit QR-Code und Warnung bei Wechsel,
  Direkt-, Gruppen-, Seiten-Chats und freie Gruppen, Antworten, Anhänge (verschlüsselt), @-Seiten, „tippt …“,
  „Für alle löschen“, Stummschalten, automatisches Löschen; Einstellungen → Chats & Geräte. Prüft die gemeinsamen
  Testvektoren (`tests/chat_vectors.json`) als Gerätetest.
- **Mitteilungen** über UnifiedPush (z. B. ntfy als Verteiler, kein Google FCM), Ende-zu-Ende-verschlüsselt
  (Web Push, RFC 8291); worüber, Namen, Ruhezeiten, Test, Geräteliste.
- **KI-Assistent** (wenn der Server ihn anbietet): „Frag deine Seiten“ mit Quellen und „Diese Seite“
  (Zusammenfassen, Aufgaben vorschlagen, markierten Text verbessern, übersetzen) – Übernehmen nur nach Klick.
- **Föderation:** Seiten anderer Saga-Instanzen unter „Mit mir geteilt“ (lesen, kommentieren, mit Bearbeiten Titel
  und Inhalt ändern), Teilen an `name@server`. Seiten-IDs sind jetzt 64-Bit.

### Neu (Linux, Saga-Server 2.0)
- **Live-Kanal und Benachrichtigungen:** Glocke mit Ungelesen-Zahl, Anwesenheit im Seitenkopf, fremde Änderungen an der
  offenen Seite werden still nachgeladen (bzw. Hinweis mit „Neu laden“), Desktop-Mitteilung solange Saga offen ist.
- **Chats mit Ende-zu-Ende-Verschlüsselung** (PyNaCl/libsodium, Format v1, geprüft mit den Testvektoren des Servers):
  Einrichtung mit Wiederherstellungsschlüssel, neues Gerät bestätigen (20-stelliger Code) oder wiederherstellen,
  Sicherheitsnummer mit QR-Code, Anhänge, Antworten, „Chats & Geräte“. Private Schlüssel nur im Schlüsselbund.
- **Mitteilungen** über UnifiedPush (D-Bus, z. B. KUnifiedPush mit ntfy) – auch bei geschlossener App.
- **KI-Assistent** („Frag deine Seiten“, „Diese Seite“) und **Föderation** (Seiten anderer Saga-Instanzen, Teilen
  mit name@server).

## [1.2.0] – 2026-10-14

Passt zu Saga-Server 1.2. Enthält auch alles, was für 1.1.0 vorgesehen war (1.1.0 wurde nicht separat veröffentlicht).

### Neu
- **Seiten veröffentlichen** (braucht Saga-Server 1.2) in allen Apps: Seitenmenü „Veröffentlichen …“ mit
  öffentlichem Namen, Adresse, Unterseiten, Suchmaschinen, Profilseite, Link kopieren/öffnen und „Veröffentlichung
  beenden“; Hinweis „Öffentlich“ im Seitenkopf; „Öffentlicher Name“ unter Einstellungen → Konto.
  Fehlermeldungen des Servers kommen in der App-Sprache (Accept-Language).
  Dialog auf allen Plattformen gleich: Schalter → Link (solange privat gedämpft; bei Profilseite mit Profiladresse)
  → Adresse („Adresse ändern“ erst bei abweichender Eingabe) → Optionen → „Veröffentlichung beenden“; Schalter statt
  Häkchen, Fehler direkt am Feld, einheitliche Rückfragen mit Titel. Android-Seitenmenü: „App-Link kopieren“ und
  „Im Web-Editor öffnen“.
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
