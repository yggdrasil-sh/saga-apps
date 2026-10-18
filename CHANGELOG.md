# Changelog

Alle nennenswerten Änderungen an den Saga-Apps. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionen nach [Semantic Versioning](https://semver.org/lang/de/). Neue Version: `tools/set-version.sh x.y.z`,
Abschnitt hier ergänzen, auf `main` bringen – GitHub baut und veröffentlicht dann alle Downloads.

## [Unveröffentlicht]

### Sprache wählbar – iOS, iPadOS, macOS
- **Einstellungen → „Sprache“:** Automatisch (Systemsprache), Deutsch, English oder Español – unabhängig von der
  Sprache des Geräts. iPhone/iPad nutzen die Systemfunktion je App (Eintrag zeigt die aktuelle Sprache und öffnet
  Einstellungen-App → Saga → Sprache); am Mac Auswahl direkt in der App mit Hinweis und Knopf „Jetzt neu starten“.
- **Schon vor der Anmeldung:** kleiner Sprachumschalter unten im Anmeldebildschirm (fremdes Gerät); das
  Anmeldefenster des Servers bekommt die App-Sprache mit (`lang=`).
- Fehlertexte des Servers folgen der gewählten Sprache (Accept-Language der App-Anfragen).
- **Anmeldebildschirm:** scrollt bei kleinen Fenstern, Sprachwahl bleibt unten sichtbar; „Abbrechen“ (auch Esc),
  sobald ein Server bekannt ist – danach zeigt Saga den Zwischenspeicher mit Hinweis „Nicht angemeldet · Anmelden“
  statt das Fenster bei jeder Anfrage erneut zu öffnen. Am Mac lässt sich das Anmeldeblatt in der Größe ändern.

### Neu – Android: Sprache der App wählbar
- **Einstellungen → Sprache** (ganz oben): Automatisch (Gerätesprache), Deutsch, English, Español – gilt nur für Saga,
  die Gerätesprache bleibt unverändert (z. B. auf einem fremden oder geteilten Gerät). Sofort wirksam, ohne Neustart.
- **Auch vor dem Anmelden:** kleiner Umschalter (Globus) unten auf dem Anmeldebildschirm.
- Android 13+: über die Sprache pro App des Systems (auch unter Einstellungen → Apps → Saga → Sprache, beide Wege
  bleiben gleich); Android 8–12: eigene Einstellung der App. Die Anfragen an den Server (`Accept-Language`) folgen
  der gewählten Sprache.

## [2.1.0] – 2026-10-17

Passt zu Saga-Server 2.1. **Die neuen Funktionen brauchen Saga-Server 2.1; ältere Server funktionieren weiter**
(dort arbeiten die Apps wie bisher, ohne Anrufe und Profilbilder).

### Neu (alle Apps)
- **Audio- und Videoanrufe im Direkt-Chat:** WebRTC direkt zwischen den Geräten, jede Signalisierung mit dem
  Chat-Geräteschlüssel signiert und für das Zielgerät versiegelt (`saga-call/v1`, geprüft mit den Testvektoren des
  Servers); „Ende-zu-Ende verschlüsselt“ erst, wenn der DTLS-Fingerabdruck zur signierten Beschreibung passt.
  Geänderte Sicherheitsnummer: kein Anruf, bis sie geprüft ist. Anruf-Einträge im Chat (Dauer, verpasst, abgelehnt).
- **Profilbilder** überall, wo Personen erscheinen (Chats, Mitglieder, Kommentare, Anwesenheit, Glocke, Teilen,
  Versionen) – nur vom eigenen Server geladen, zwischengespeichert, Initialen als Ersatz; Wechsel erscheinen sofort.
  Eigenes Bild hochladen (runder Zuschnitt mit Zoom), entfernen, Gravatar und „auf veröffentlichten Seiten zeigen“.
- **Anmeldung & Sicherheit:** Stand des zweiten Faktors in den Einstellungen (Passkeys, Authenticator-App, übrige
  Wiederherstellungscodes, Hinweis bei Pflicht durch den Admin), Verwaltung im Browser. Passkey oder Code fragt das
  Browserfenster der Anmeldung ab; Chat „Neu beginnen“ nimmt auch den Code der Authenticator-App.
- **Schnelle Chat-Bilder:** Vorschaubild beim Senden (gemeinsames Format aller Apps und des Webs), im Verlauf nur
  Vorschaubilder in fester Größe, Original erst beim Antippen; Laden und Entschlüsseln im Hintergrund, Cache nur mit
  Ciphertext (200 MB, geleert beim Abmelden). Gemessen mit 300 Bildnachrichten: erster Bildschirm in unter einer
  Sekunde statt 3–5 s, deutlich weniger Speicher.
- **Immich im Chat:** „Anhängen → Aus Immich“ (wenn Immich eingerichtet ist), verschlüsselt gesendet wie jeder Anhang.
- **Bilder einfügen und kopieren:** Bilder aus der Zwischenablage im Chat (als Anhang) und auf Seiten (als Bildblock);
  Bildblöcke kopieren, ausschneiden und in andere Seiten einsetzen (iOS/iPadOS/macOS, Linux).

### iOS, iPadOS, macOS
- Klingeln auf iPhone/iPad über **CallKit** (Sperrbildschirm, Anrufliste) und PushKit-VoIP; Mac mit eigenem
  Anruf-Fenster, Klingelton und Mitteilung „Annehmen“/„Ablehnen“. Lautsprecher und Kamera wechseln.
- Bilder aus der Zwischenablage mit ⌘V bzw. „Einsetzen“ (vorher geschah am Mac nichts, auf iPhone/iPad landete ein
  Ersatzzeichen).
- Behoben: Auflegen während „Verbinde …“ bricht wirklich ab (vorher „Besetzt“ bis zum Neustart); Knöpfe bei großer
  Schrift; Saga drängt sich am Mac nicht mehr in den Vordergrund; transparente Profilbilder mit weißem Hintergrund.

### Android
- Klingeln als **Vollbild-Anruf** (auch auf dem Sperrbildschirm) über den Live-Kanal bzw. UnifiedPush; laufender
  Anruf als Vordergrund-Dienst; Weg (direkt/Relais) und Qualität sichtbar.
- **Android 17 im Heimnetz:** Die App erkennt die neue Berechtigung „Geräte in der Nähe“ für Adressen im lokalen Netz,
  erklärt sie und fragt danach (vorher liefen alle Anfragen still in eine Zeitüberschreitung).
- **Notion-Importe:** Datenbank-Zeilen und ihre Eigenschaften sind in der App erreichbar, eingebettete Datenbanken
  und Schaltflächen lesbar.
- Bilder einfügen aus Zwischenablage, Tastatur (GBoard) und per Ziehen; Knopf „Bild einfügen“ auf Seiten.
- APK halb so groß (nur arm64-v8a und armeabi-v7a, 23 statt 50 MB).
- Behoben: Dunkelmodus in Datenbank-Blöcken, Videobild nicht mehr beschnitten, Kontowechsel im Browser, Datumstrenner
  im Chat, Profilbilder mit Transparenz.

### Linux
- Eingehende Anrufe klingeln in der App (Leiste im Fenster, Desktop-Mitteilung, auch über UnifiedPush);
  **Annehmen und Anrufen öffnen das Gespräch im Standardbrowser**.
- Chat-Bilder: 300 Bildnachrichten nach 0,3 s statt 5 s, Speicher 240 MB statt über 2,3 GB (vorher brach die App
  nach 150 Bildern ab).
- Einfügen aus der Zwischenablage (Strg+V): Bilder und Dateien im Chat und auf Seiten.
- Behoben: Start ohne Netz lädt KI, Anrufen, Konto und Glocke nach; Klingel-Leiste im schmalen Fenster lesbar;
  HEIC nur mit passender Bildbibliothek.

### Bekannte Grenzen
- **Linux:** Anrufe laufen im Browser (die App klingelt und öffnet das Gespräch dort).
- **Android:** Im Hintergrund klingelt Saga nur mit einem UnifiedPush-Verteiler (z. B. ntfy); ohne nur bei offener App.
- **iPhone/iPad:** Klingeln über CallKit/VoIP braucht einen APNs-Schlüssel am Server (Admin → Mitteilungen); ohne
  klingelt es nur bei offener App.
- Anrufe nur zu zweit (keine Gruppenanrufe), ohne TURN-Server am Saga-Server nicht in allen Netzen.

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
