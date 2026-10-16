# Changelog

Alle nennenswerten Änderungen an den Saga-Apps. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionen nach [Semantic Versioning](https://semver.org/lang/de/). Neue Version: `tools/set-version.sh x.y.z`,
Abschnitt hier ergänzen, auf `main` bringen – GitHub baut und veröffentlicht dann alle Downloads.

## [Unveröffentlicht]

### Neu
- **iOS, iPadOS, macOS:** Chat – „Aus Immich“ beim Anhängen (wenn Immich eingerichtet ist): dieselbe Auswahl wie auf
  Seiten; das Foto wird über den Server geladen (Original, sonst große Vorschau; zu groß → verkleinert) und wie jeder
  Anhang Ende-zu-Ende-verschlüsselt gesendet.

### Behoben
- **iOS, iPadOS, macOS:** Bilder aus der Zwischenablage (Bildschirmfoto, „Bild kopieren“, Bilddatei aus dem Finder)
  lassen sich mit ⌘V bzw. „Einsetzen“ einfügen – auf Seiten als Bildblock, im Chat als verschlüsselter Anhang mit
  Vorschau vor dem Senden. Vorher geschah am Mac nichts, auf iPhone/iPad landete ein leeres Ersatzzeichen im Text.
  Text einsetzen bleibt unverändert.

### Android
- **Immich im Chat:** „Anhängen → Aus Immich“ (nur wenn Immich für dich eingerichtet ist) mit Zuletzt, Alben und
  Inhaltssuche. Das Foto wird über den Immich-Proxy des Servers geladen (Original, sonst große Vorschau; zu groß →
  verkleinert) und wie jeder Anhang Ende-zu-Ende-verschlüsselt gesendet.
- **Bilder einfügen:** im Chat aus der Zwischenablage (Einfügen-Menü des Felds, „Anhängen → Bild einfügen“), per
  Tastatur (GBoard: Sticker, GIFs, kopierte Bilder) und per Ziehen; auf Seiten per Tastatur und mit dem Knopf
  „Bild einfügen“ in der Werkzeugleiste (lädt hoch und setzt einen Bild-Block hinter den aktuellen Block).

### Neu (Linux)
- **Immich im Chat:** Büroklammer → „Aus Immich …“ (nur wenn Immich verbunden ist), gleiche Auswahl wie auf Seiten;
  das Bild wird über den Immich-Proxy des Servers geladen (Original bzw. große Vorschau, bei Bedarf verkleinert) und
  wie jeder Anhang Ende-zu-Ende verschlüsselt hochgeladen.
- **Einfügen aus der Zwischenablage (Strg+V):** Bilder (Bildschirmfotos, kopierte Bilder) und Dateien aus dem
  Dateimanager kommen im Chat als Anhang mit Vorschau, auf Seiten als Bild- bzw. Dateiblock; Text wie bisher.
  Zu große Bilder werden im Chat automatisch verkleinert.

### Linux
- **Anmeldung & Sicherheit (Server 2.1):** Einstellungen → Konto zeigt den Stand des zweiten Faktors (Passkeys,
  Authenticator-App, verbleibende Wiederherstellungscodes, Hinweis, wenn der Admin ihn verlangt) und öffnet die
  Verwaltung mit „Im Browser verwalten“. Die Anmeldung mit Passkey bzw. Code läuft wie bisher im Browser; der
  Anmeldebildschirm weist darauf hin.
- **Chats neu beginnen mit Code:** zur Bestätigung geht neben dem Passwort auch der 6-stellige Code der
  Authenticator-App; Konten nur mit Passkey werden in den Browser geleitet.
- **Profilbilder:** überall, wo Personen erscheinen (Chats, Unterhaltungen, Mitglieder, Personensuche, Glocke,
  Anwesenheit, Kommentare, Teilen, eigenes Konto in der Seitenleiste) – geladen nur von Saga, zwischengespeichert, mit
  Initialen als Rückfall. Eigenes Bild hochladen mit rundem Zuschnitt (verschieben, zoomen), entfernen, Gravatar und
  „auf veröffentlichten Seiten zeigen“ schalten.
- **Anrufe:** eingehende Audio- und Videoanrufe klingeln in der App (Leiste im Fenster, Desktop-Mitteilung mit
  „Ablehnen“/„Annehmen“, auch über UnifiedPush) – genau einmal je Anruf, und nicht mehr, sobald er anderswo
  angenommen oder beendet wurde. Annehmen und Anrufen öffnen das Gespräch im Standardbrowser. Verpasste, abgelehnte
  und beendete Anrufe stehen im Chat, „Verpasster Anruf“ auch in der Glocke.

## [2.0.0] – 2026-10-15

Passt zu Saga-Server 2.0. **Die neuen Funktionen brauchen Saga-Server 2.0; ältere Server funktionieren weiter**
(dort arbeiten die Apps wie bisher).

### Neu (alle Apps)
- **Teilen und Kommentare:** Seiten mit Personen und Gruppen teilen (Lesen, Kommentieren, Bearbeiten), „Mit mir
  geteilt“, Kopfzeile „Geteilt von …“, Kommentare am Block mit Antworten und „Erledigt“.
- **Live-Kanal und Benachrichtigungen:** Glocke mit Ungelesen-Zahl und Einstellungen je Art; auf Seiten „… ist auch
  hier“ / „… bearbeitet gerade“; Änderungen anderer werden still nachgeladen (sonst Hinweis mit „Neu laden“).
- **Chats mit Ende-zu-Ende-Verschlüsselung** (libsodium, Format v1, geprüft mit den Testvektoren des Servers):
  Einrichtung mit Wiederherstellungsschlüssel, neues Gerät per 20-stelligem Code bestätigen oder wiederherstellen,
  Sicherheitsnummer mit QR-Code und Warnung bei Wechsel, Direkt-, Gruppen-, Seiten-Chats und freie Gruppen,
  Antworten, verschlüsselte Anhänge, „Chats & Geräte“. Private Schlüssel bleiben auf dem Gerät.
- **Mitteilungen**, nie mit Inhalten: Bereiche, Namen, Ruhezeiten, Test, Geräteliste.
- **KI-Assistent** (wenn der Server ihn anbietet): „Frag deine Seiten“ mit Quellen und „Diese Seite“
  (zusammenfassen, Aufgaben vorschlagen, Text verbessern, übersetzen) – übernommen wird nur nach Klick.
- **Föderation:** Seiten anderer Saga-Server unter „Mit mir geteilt“ (lesen, kommentieren, mit Bearbeiten Titel und
  Inhalt ändern), Teilen an `name@server`.

### Je Plattform
- **iOS, iPadOS, macOS:** Chats über swift-sodium; Mitteilungen über Apple Push (iOS/iPadOS; am Mac optional, braucht
  ein Bereitstellungsprofil mit Push; der Server-Admin trägt einen APNs-Schlüssel ein).
- **Android:** Chats über lazysodium-android; Mitteilungen über UnifiedPush (z. B. ntfy, ohne Google FCM),
  Ende-zu-Ende-verschlüsselt (RFC 8291); Live nur im Vordergrund; Seiten-IDs jetzt 64-Bit.
- **Linux:** Chats über PyNaCl; Mitteilungen über UnifiedPush (D-Bus, z. B. KUnifiedPush mit ntfy) auch bei
  geschlossener App, sonst Desktop-Mitteilung, solange Saga offen ist.

### Behoben
- **Alle:** Entwürfe gehen offline nicht mehr verloren und werden nachgesendet; nach Abmelden/Kontowechsel öffnet
  sich nicht mehr die Startseite des alten Kontos; nicht gespeicherter Geräte-Schlüssel führt zu einem Hinweis statt
  stiller Abmeldung (BUG-020, BUG-060, BUG-100, BUG-104).
- **iOS, iPadOS, macOS:** Tastaturleiste erreicht alle Editor-Funktionen und verdeckt weder Schreibzeile noch
  „Kommentieren“; große Schrift sprengt die Seite nicht mehr; Anmelden prüft erst die Serveradresse;
  Kommentar-Benachrichtigung öffnet die Kommentare auch bei offener Seite; kleinere Korrekturen in Einstellungen,
  Menüleiste und Seitenkopf (BUG-018, BUG-019, BUG-058–064, BUG-101–105).
- **Android:** keine Abstürze mehr im Chat (Nachladefehler, große Dateien, Kopplung ohne Netz); Chat-Einrichtung,
  Kopplung und Entwürfe überstehen Drehen und Prozess-Tod; entschlüsselte Anhänge nur kurz im Cache; Teilen „als
  Aufgabe“ speichert den Text; Netzwerkfehler ohne interne Adressen; Mitteilungs-Links nur aus eigenen Mitteilungen;
  bessere Lesbarkeit bei großer Schrift und mit TalkBack (BUG-003–005, BUG-021–025, BUG-065–074, BUG-106–112).
- **Linux:** Chat-Schlüssel auch ohne Schlüsselbund-Dienst, Konflikt-Dialog nicht mitten im Tippen, Kommentar-Leiste
  live, Kontowechsel am selben Server, Esc in Suchdialogen, schmale Fenster; RPM verlangt gobject-introspection und
  PyNaCl ab 1.5 (BUG-006, BUG-026–031, BUG-075–082, BUG-113–117).

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
