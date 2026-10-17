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

### iOS, iPadOS, macOS
- **Anrufe (Audio und Video) im Direkt-Chat:** Knöpfe „Anrufen“/„Videoanruf“ im Kopf des Chats, WebRTC direkt
  zwischen den Geräten (bei Bedarf über den TURN-Server des Admins). Jede Signalisierung ist mit dem Chat-Geräteschlüssel
  signiert und für das Zielgerät versiegelt (saga-call/v1, Testvektoren grün); nach dem Verbinden wird der
  DTLS-Fingerabdruck gegen die signierte SDP geprüft – erst dann steht „Ende-zu-Ende verschlüsselt“ da. Geänderte
  Sicherheitsnummer: kein Anruf, bis sie bestätigt ist. Stumm, Kamera an/aus, Lautsprecher und Kamera wechseln
  (iPhone/iPad), Anruf-Einträge im Chat (Dauer, verpasst, abgelehnt; Tippen = zurückrufen).
- **Klingeln:** iPhone/iPad über CallKit (Sperrbildschirm, Anrufliste) und PushKit-VoIP-Mitteilungen (eigenes Abo
  „Saga-App: Anrufe“, nur für klingelnde Anrufe); Mac mit eigenem Fenster „Anruf“, Klingelton und Mitteilung mit
  „Annehmen“/„Ablehnen“.
- **Profilbilder** überall, wo Personen erscheinen (Chats, Mitglieder, Absender in Gruppen, Kommentare, Anwesenheit,
  Benachrichtigungen, Teilen, Versionen, eigenes Bild oben links auf iPhone/iPad) – nur vom eigenen Server geladen,
  mit Zwischenspeicher; Initialen als Ersatz. Eigenes Bild in den Einstellungen hochladen (runder Zuschnitt mit Zoom),
  entfernen, Gravatar und „auf veröffentlichten Seiten zeigen“ schalten.
- **Anmeldung & Sicherheit** in den Einstellungen: Status des zweiten Faktors (Passkeys, Authenticator-App, Warnung bei
  wenigen Wiederherstellungscodes, Hinweis bei Admin-Pflicht), verwaltet wird im Browser. Hinweis im Anmeldebildschirm,
  dass das Browserfenster nach Passkey oder Code fragt. Chat „Neu beginnen“ nimmt Passwort oder Code der
  Authenticator-App; reine Passkey- bzw. SSO-Konten werden in den Browser geleitet.

### iOS, iPadOS, macOS – Chat-Bilder und Bildblöcke (2.1-c)
- **Chat-Bilder schnell:** Beim Senden entsteht ein Vorschaubild (320 px, JPEG, ohne Metadaten, im Hintergrund),
  das wie jeder Anhang Ende-zu-Ende-verschlüsselt mitgeht (`w`/`h`/`th` im Anhang, Format wie Web/Android/Linux).
  Im Verlauf erscheint nur das Vorschaubild in fester Größe (nichts springt), das Original erst beim Antippen
  (Vollbild, Teilen/Sichern). Alte Nachrichten ohne Vorschaubild: einmal aus dem Original erzeugt und verschlüsselt
  zwischengespeichert. Speicher-Cache (300 Bilder/30 MB) und Platten-Cache nur mit Ciphertext (200 MB, älteste
  zuerst; geleert beim Abmelden, „Neu beginnen“ und Entfernen des Geräts). Geladen wird nur, was sichtbar ist
  (höchstens 4 gleichzeitig, Abbruch beim Wegscrollen), Entschlüsseln/Dekodieren nie im UI-Thread.
  Gemessen am Mac mit 300 Bildnachrichten (je 3–5 MB): erster Bildschirm 3,3 s → 0,6 s, beim schnellen Scrollen
  2 von 307 → 291 von 293 Bildern sofort da, danach 19,5 s → 0 s Warten, Speicher-Spitze 164 MB → 96 MB.
- **Bildblöcke kopieren und ausschneiden:** Bild auf einer Seite anklicken/antippen (Rahmen), dann ⌘C/⌘X, Entf oder
  Bearbeiten → Kopieren/Ausschneiden; auf dem iPhone über das Kontextmenü (lange drücken). In der Zwischenablage liegen
  das Bild (für andere Programme) und der Block: in derselben Seite einsetzen verschiebt ihn, in einer anderen Seite
  wird das Bild neu hochgeladen (mit Bildunterschrift). Vorher gab es für Bildblöcke keinen Empfänger für ⌘C/⌘X.

### iOS, iPadOS, macOS – Behoben (Bughunt 2)
- **Anrufe:** Auflegen, während noch „Verbinde …“ steht, bricht den Anruf jetzt wirklich ab. Vorher klingelte es beim
  Gegenüber trotzdem, die App hing auf „Klingelt …“ und lehnte bis zum Neustart jeden weiteren Anruf als „Besetzt“
  ab. Ebenso lehnt die App einen Anruf nicht mehr als „Besetzt“ ab, wenn er beim Start gleichzeitig über den
  Live-Kanal und das Nachholen gemeldet wird.
- **Anrufe – Bedienung:** Bei Bedienungshilfen-Schriftgrößen bleiben alle Knöpfe (Stumm, Kamera, Lautsprecher,
  Auflegen, Ablehnen/Annehmen) im Bild. Das eigene Kamerabild verdeckt die Ton-Diagnose nicht mehr und ist mit der
  Rückkamera nicht mehr spiegelverkehrt; „… ist stummgeschaltet“ erscheint auch, wenn das Video des Gegenübers
  läuft. iPhone/iPad: auf einem anderen Gerät angenommene Anrufe stehen in der Anrufliste nicht mehr als verpasst.
- **Mac – Anrufe:** Saga drängt sich nicht mehr bei jedem Zustandswechsel eines Anrufs in den Vordergrund (Eingaben
  in anderen Apps landeten sonst in Saga). Das Anruf-Fenster öffnet sich beim Beginn und beim Annehmen; klingelt es,
  während du in einer anderen App bist, meldet sich Saga mit einer Mitteilung (Annehmen/Ablehnen) und einmal im Dock.
- **Profilbilder:** Transparente PNGs bekommen einen weißen statt schwarzen Hintergrund. Ein kurzer Serverfehler lässt
  nicht mehr für die ganze Sitzung Initialen stehen; Bilder werden nach 10 Minuten beim nächsten Anzeigen (z. B.
  Seitenwechsel) mit dem Server abgeglichen. Abmelden leert auch den Zwischenspeicher der Profilbilder. Mit einem
  Server, der das Live-Ereignis `avatar.changed` sendet, erscheint ein neues oder entferntes Profilbild sofort.
- **Chat:** „Neu beginnen“ mit dem Code der Authenticator-App bietet nicht mehr an, den Code als Passwort zu sichern.
  Ein ausgewählter, noch nicht gesendeter Anhang verdeckt nicht mehr die neueste Nachricht.

### Android
- **Immich im Chat:** „Anhängen → Aus Immich“ (nur wenn Immich für dich eingerichtet ist) mit Zuletzt, Alben und
  Inhaltssuche. Das Foto wird über den Immich-Proxy des Servers geladen (Original, sonst große Vorschau; zu groß →
  verkleinert) und wie jeder Anhang Ende-zu-Ende-verschlüsselt gesendet.
- **Bilder einfügen:** im Chat aus der Zwischenablage (Einfügen-Menü des Felds, „Anhängen → Bild einfügen“), per
  Tastatur (GBoard: Sticker, GIFs, kopierte Bilder) und per Ziehen; auf Seiten per Tastatur und mit dem Knopf
  „Bild einfügen“ in der Werkzeugleiste (lädt hoch und setzt einen Bild-Block hinter den aktuellen Block).
- **Anrufe (Saga-Server 2.1):** Audio- und Videoanrufe aus dem Direkt-Chat (Hörer/Kamera oben im Chat), Ende-zu-Ende
  verschlüsselt mit WebRTC (DTLS-SRTP). Jedes Signal ist mit dem Chat-Geräteschlüssel signiert und für das Zielgerät
  versiegelt (`saga-call/v1`, geprüft mit den Testvektoren des Servers); „Ende-zu-Ende verschlüsselt ✓“ erst, wenn der
  DTLS-Fingerabdruck der Gegenseite zur signierten Beschreibung passt. Klingeln als Vollbild-Anruf (auch auf dem
  Sperrbildschirm) über den Live-Kanal bzw. UnifiedPush-Mitteilung der Art „Anruf“; Annehmen mit oder ohne Video,
  Ablehnen, Stumm, Kamera an/aus und wechseln, Lautsprecher, Weg (direkt/Relais) und Qualität; laufender Anruf als
  Vordergrund-Dienst. Anruf-Einträge im Chat („Videoanruf · 3:12“, „Verpasster Anruf“ mit „Zurückrufen“). Geänderte
  Sicherheitsnummer des Gegenübers verhindert den Anruf, bis sie im Chat geprüft ist.
- **Profilbilder (Saga-Server 2.1):** überall statt bzw. über den Initialen – Kopfzeile der Einstellungen, Kommentare,
  Anwesenheit, Benachrichtigungen, Teilen und Personensuche, Chat-Liste, Gruppen-Chats, Mitglieder, Anrufe. Bilder
  kommen nur vom eigenen Saga-Server (mit der App-Anmeldung, WebP, zwischengespeichert je Version). Eigenes Bild unter
  Einstellungen → Konto hochladen (Foto-Auswahl, runder Zuschnitt mit Verschieben/Zoomen), entfernen, Gravatar und
  „auf veröffentlichten Seiten zeigen“ schalten.
- **Anmeldung & Sicherheit (Saga-Server 2.1):** Status in den Einstellungen (zweiter Faktor an/aus, Passkeys,
  Authenticator-App, übrige Wiederherstellungscodes, Hinweis bei Pflicht durch den Admin) und „Im Browser verwalten“.
  Die Anmeldung mit Passkey oder Code läuft im Browserfenster wie bisher; „Neu beginnen“ im Chat nimmt jetzt auch den
  6-stelligen Code der Authenticator-App.

### Android – Behoben (2.1-c)
- **Server im Heimnetz unter Android 17:** Seit Android 17 erreichen Apps Adressen im lokalen Netz (z. B. eine Domain,
  die zu Hause auf 192.168.x.x zeigt, `*.local`, 10.x, fd00::) nur mit der Berechtigung „Geräte in der Nähe“ – vorher
  liefen alle Anfragen still in eine Zeitüberschreitung, keine Seite ließ sich öffnen. Die App erkennt das jetzt
  (Anmelden, Koppeln, Start, Fehler beim Laden), erklärt es und fragt nach der Berechtigung (abgelehnt → Weg in die
  App-Einstellungen); Fehlermeldungen sagen „Kein Zugriff aufs lokale Netzwerk“ statt „Zeitüberschreitung“.
- **Seiten aus einem Notion-Import:** Datenbanken zeigen ihre Einträge (antippen öffnet sie), Einträge zeigen ihre
  Eigenschaften (Auswahl, Mehrfachauswahl, Datum, Zahl/Prozent/Euro, Checkbox, Link, Text, Relation, erstellt/geändert,
  berechnet) – bisher waren Datenbank-Zeilen in der App gar nicht erreichbar. Eingebettete Datenbanken, Schaltflächen
  und Einbettungen erscheinen lesbar statt als „noch nicht unterstützt“; nummerierte Listen beginnen bei `start`.
  Unbekannte oder beschädigte Blöcke bleiben Platzhalter, die übrige Seite wird immer angezeigt.
- **Chat-Bilder schnell:** Beim Senden entsteht ein Vorschaubild (320 px, JPEG, ohne Metadaten, eigener Schlüssel,
  `w`/`h`/`th` im Anhang); im Verlauf wird nur das Vorschaubild geladen – Platz in richtiger Größe sofort, höchstens
  4 gleichzeitig, Abbruch beim Wegscrollen, Entschlüsseln/Dekodieren im Hintergrund. Original erst beim Antippen. Alte
  Nachrichten ohne Vorschaubild: einmal verkleinert und verschlüsselt zwischengespeichert. Cache: Bitmaps im Speicher
  (LRU, 32 MB) und nur Ciphertext auf der Platte (LRU, 200 MB), geleert bei Abmelden, „Neu beginnen“ und entferntem Gerät.
- **APK halb so groß:** Das Release-APK enthält nur noch arm64-v8a und armeabi-v7a (echte Telefone/Tablets, auch
  32-Bit); 23 statt 50 MB. Emulator-/Chromebook-Builds mit `-PsagaAllAbis=true`.

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

### Linux (2.1-c)
- **Bilder im Chat schnell, auch in großen Chats:** beim Senden entsteht ein kleines Vorschaubild (320 px, eigener
  verschlüsselter Anhang, dazu Bildgröße im Nachrichteninhalt – gemeinsames Format aller Apps). Im Verlauf wird nur das
  Vorschaubild geladen, und nur für sichtbare Nachrichten; der Platz ist schon vorher richtig groß, nichts springt.
  Das Original lädt erst beim Anklicken (Ansicht mit „Speichern“). Laden, Entschlüsseln und Dekodieren laufen im
  Hintergrund, Weggescrolltes wird nicht mehr geladen. Ältere Bildnachrichten ohne Vorschaubild bekommen einmalig eines
  auf dem Gerät. Zwischenspeicher: Vorschaubilder im Speicher, verschlüsselte Anhänge (nur Ciphertext) bis 200 MB
  unter `~/.cache/hlidskjalf/chat-blobs` – geleert beim Abmelden und bei „Neu beginnen“. Gemessen mit 300
  Bildnachrichten (3,7 MB je Foto): erster Bildschirm nach 0,3 s statt 5 s, Scrollen ohne Ruckeln, Speicher 240 MB
  statt über 2,3 GB (vorher brach die App nach 150 Bildern ab).
- **Bildblöcke kopieren, ausschneiden, einfügen:** Bildblock anklicken (Rahmen), dann Strg+C/Strg+X; Strg+V fügt
  ihn in derselben oder einer anderen Seite ein (auch Blöcke aus dem Web-Editor), in anderen Programmen und im Chat
  kommt er als Bild an. Im Blockmenü „Block kopieren“/„Block ausschneiden“; Entf löscht den gewählten Bildblock.
  In Seiten anderer Personen wird das Bild für deren Eigentümer neu hochgeladen.

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
