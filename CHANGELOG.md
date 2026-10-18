# Changelog

Alle nennenswerten Änderungen an den Saga-Apps. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionen nach [Semantic Versioning](https://semver.org/lang/de/). Neue Version: `tools/set-version.sh x.y.z`,
Abschnitt hier ergänzen, auf `main` bringen – GitHub baut und veröffentlicht dann alle Downloads.

## [Unveröffentlicht]

### Geändert (alle Apps)
- **Testversionen als Release Candidate erkennbar:** Alles, was nicht das offizielle Release ist (Vorabversion von
  `testing`, Ad-hoc-Builds fürs MDM, lokale Builds), zeigt in Einstellungen/„Über“ jetzt `2.2.0-rc.N` statt nur
  `2.2.0` (N = Commits seit dem letzten Release) – nur das Release selbst zeigt `2.2.0`. Die Vorabversion auf GitHub
  heißt entsprechend `v2.2.0-rc.N` („Release Candidate“) statt `v2.2.0-testing`. Technisch bleiben App-Store-Version
  (iOS/macOS), versionCode (Android) und RPM-Version numerisch; das RPM einer Vorabversion heißt
  `saga-2.2.0-0.rcN` und wird vom Release `2.2.0-1` sauber ersetzt, Flatpak-Vorabversionen tragen einen
  „development“-Eintrag in den Metadaten. Der lokale Mac-Build bekommt eine echte Build-Nummer (Datum/Uhrzeit)
  statt „1“. Mac: „Über Saga“ im App-Menü zeigt dieselbe Version.
  Steht `VERSION` noch auf der gerade veröffentlichten Version (z. B. 2.1.0), zählt eine Testversion wie beim
  Server zur nächsten Patch-Version (`2.1.1-rc.N`, RPM `saga-2.1.1-0.rcN`) – Testversionen sind so immer erkennbar
  neuer als das Release.

### Behoben (alle Apps)
- **Hinweis statt „Keine Treffer“ bei name@server oder E-Mail:** Wer im Chat („Neue Unterhaltung“, auch Gruppe mit
  mehreren Personen, und „Person hinzufügen“) `name@server`, `@name@server` oder eine E-Mail-Adresse eingibt, sieht
  jetzt wie im Web, dass es Chats mit Personen anderer Saga-Server noch nicht gibt, Seiten aber per `name@server`
  (öffentlicher Name) teilbar sind und Personen hier über Name oder Benutzername gefunden werden – mit Link
  „Anleitung: Föderation“ (de/es eigene Anleitung, sonst englisch). Lokale Treffer haben Vorrang. Im Teilen-Dialog
  zeigen die Apps statt „Niemand gefunden.“ den Grund vom Server (`remote_hint` aus `GET /api/share/targets`, z. B.
  „nicht verbunden“ oder „öffentlicher Name statt E-Mail“); ältere Server ohne das Feld wie bisher.

### Schneller (iOS/iPadOS/macOS)
- **Keine Hänger mehr beim Abgleich großer Konten:** Die Seitenliste wird jetzt im Hintergrund gelesen, verglichen
  und für Baum und Suche aufbereitet; die Oberfläche übernimmt nur noch das fertige Ergebnis. Bei 25.000 Notizen
  blockierte jeder Abgleich den Main-Thread bisher ~0,1 s (unverändert) bzw. ~0,2 s (mit Änderung), jetzt praktisch
  gar nicht. Seiten nachschlagen und Unterseiten im Baum gehen über einen Index statt jedes Mal alle Notizen zu
  durchsuchen.
- **Spotlight im Hintergrund und nur mit Änderungen:** Die Systemsuche wird nicht mehr nach jeder Änderung komplett
  gelöscht und neu aufgebaut, sondern bekommt nur geänderte, neue und gelöschte Einträge – gebündelt und nie auf dem
  Main-Thread. Gefunden wird dasselbe wie bisher (Seiten, Datenbanken und Datenbank-Einträge; Einträge werden nach
  den Seiten indiziert).
- **Weniger Datenverkehr:** Neue Seiten, Unterseiten, Datenbank-Einträge („Neu“, Schaltflächen, Widget-Link) und
  umbenannte Einträge erscheinen sofort lokal in der Liste, statt dass die App die komplette Seitenliste neu lädt.
  Gleichzeitige Abgleiche (Aktion, Minutentakt, mehrere Fenster) werden zusammengefasst. Meldet der Server
  Änderungen am Seitenbaum live (`notes.tree`), lädt die App die Liste sofort (kurz entprellt) nach und der
  Minutentakt lädt sie nur noch alle 5 Minuten als Sicherheitsnetz; bei älteren Servern mit Live-Kanal alle 2 Minuten.
  Alles andere (Mit mir geteilt, Glocke, Chats, Heute, Entwürfe) weiter jede Minute; ohne Live-Kanal wie bisher alles
  jede Minute, beim Wiederverbinden (Netzwechsel, Aufwachen) wird sofort nachgeholt. Die Seitenliste wird nur noch
  bei Änderungen in den Zwischenspeicher geschrieben (bisher jede Minute komplett).
- **Minutentakt zuverlässig:** Der Takt prüfte „App im Vordergrund?“ mit dem Stand vom Öffnen des Fensters – war die
  App da noch nicht aktiv, fiel der Abgleich im Vordergrund ganz aus. Jetzt gilt der aktuelle Zustand.

### Geändert (Android)
- **Weniger Datenvolumen und schnellere Reaktion bei großen Konten:** Die App lädt die komplette Seitenliste (bei
  vielen Datenbank-Einträgen mehrere MB) nicht mehr nach jeder Aktion neu. Eine neue eigene Seite (Seitenleiste
  „+“, „+“ an einer Seite, Konfliktkopie) wird gezielt geholt und direkt eingefügt, „In den Papierkorb“ und
  „Archivieren“ entfernen die Seite samt Unterseiten sofort aus der Liste – mit denselben Daten wie nach einem
  Neuladen. Der regelmäßige Abgleich im Vordergrund läuft wie bisher jede Minute, solange der Live-Kanal nicht
  verbunden ist. Mit Live-Kanal meldet der Server (ab 2.2, Ereignis `notes.tree`) anderswo angelegte, geänderte,
  verschobene, gelöschte oder archivierte Seiten sofort – die App holt dann nur diese Seiten nach (bei Verschieben,
  Papierkorb, Archiv oder vielen Seiten die ganze Liste); der Abgleich alle 5 Minuten bleibt als Sicherheitsnetz. Der seltene Takt gilt erst, nachdem in dieser Anmeldung tatsächlich ein `notes.tree` angekommen ist (nicht anhand der Serverversion – Testserver melden schon 2.2.0, schicken das Ereignis aber evtl. noch nicht); bis dahin und nach Abmelden oder Serverwechsel weiter jede Minute. „Mit mir geteilt“, Glocke, Chats und Heute werden unabhängig davon weiter jede Minute abgeglichen (Gruppenwechsel oder Verschieben aus geteilten Bäumen lösen nicht immer ein Ereignis aus). `notes.tree` mit `full: true` lädt die ganze Liste; `mixed` wird anhand von `ops` ausgewertet, unbekannte Arten wie eine Änderung (ganze Liste). Beim
  Zurückholen der App und nach einer Unterbrechung des Live-Kanals (Netzwechsel, Aufwachen) wird wie bisher sofort
  abgeglichen. Gleichzeitige Anstöße (Start, Takt, Ziehen zum Aktualisieren, Live „reset“) laden die Liste nur
  einmal. Seitenbaum und Datenbank-Zeilen werden im Hintergrund aufbereitet (kein Ruckeln bei großen Listen).
  Antworten kommen komprimiert an, sobald der Server Kompression anbietet.
- **„+“ (Neue Seite) legt nichts doppelt an:** Während die Seite angelegt wird, zeigt der Knopf einen Fortschritt
  und ist gesperrt – mehrfaches Tippen erzeugt keine weiteren Seiten.

### Behoben (iOS/iPadOS/macOS)
- **Titelbild erscheint sofort:** Ein neu gewähltes Titelbild (Fotos) steht jetzt sofort da, noch während es
  hochgeladen wird – vorher blieb der Bereich oben leer, bis das verkleinerte Bild vom Server kam (bei langsamer
  Verbindung oder großen Fotos sekundenlang; brach der Abruf ab, sogar bis zum erneuten Öffnen der Seite). Bilder aus
  Immich oder von anderen Geräten zeigen beim Laden einen Fortschritt, beim Wechseln bleibt das alte Bild bis zum
  neuen stehen, ein abgebrochener Abruf wird wiederholt. Entfernen und „Neu positionieren“ wirken sofort (bei einem
  Fehler kommt der alte Stand zurück).
- **Titelbild blieb in der App dauerhaft leer (z. B. aus Immich):** Gespeichert wurde es richtig (das Web zeigte es),
  aber die App gab das Laden des Bildes nach 20 Sekunden auf – bei langsamer Verbindung, großen Bildern oder wenn
  der Server die Vorschau erst erzeugen muss, also bei jedem Öffnen erneut. Bilder dürfen jetzt bis 90 Sekunden
  laden, ein Fehlschlag wird automatisch wiederholt, danach bietet das Titelbild „Erneut versuchen“ an; ein
  unbrauchbarer Eintrag im Bildspeicher wird verworfen. Eigene Fotos für Titelbild/Symbol werden vor dem Hochladen
  passend verkleinert (Titelbild höchstens 3000 px, Symbol 640 px, nach EXIF gedreht, HEIC als JPEG) – ein
  iPhone-Foto mit 24/48 MP musste vorher in voller Größe hoch (Grenze des Servers: 10 MB).
- **Symbol lässt sich wieder ändern:** Bei Seiten mit Titelbild landete ein Tippen auf den oberen Teil des Symbols
  (der über dem Titelbild liegt) im Titelbild – statt der Symbol-Auswahl erschien nur die Leiste „Ändern |
  Neu positionieren | Entfernen“. Jetzt öffnet das ganze Symbol (Emoji oder Bild) die Auswahl; neu sind dort und im
  Kontextmenü (lange drücken bzw. Rechtsklick) „Zufällig“ und „Symbol entfernen“ wie im Web. Ein neues Bild-Symbol
  erscheint sofort. Auf gesperrten Seiten erscheint beim Antippen von Symbol oder Titelbild ein kurzer Hinweis
  („Die Seite ist gesperrt …“) statt stillem Nichtstun.
- **Schaltflächen „+ Neuer Eintrag“ reagieren sofort und legen nichts doppelt an:** Ein Tippen auf eine
  Schaltfläche, die einen Datenbank-Eintrag anlegt (z. B. „+ NewToDo“ auf dem Dashboard), zeigte bis zum Öffnen des
  neuen Eintrags keine Reaktion – die App lud vorher die komplette Seitenliste samt Freigaben neu, was bei langsamer
  Verbindung Sekunden dauerte. Wer deshalb mehrfach tippte, bekam mehrere Einträge. Jetzt zeigt der Knopf sofort
  einen Fortschritt und ist gesperrt, bis die Aktion durch ist; der neue Eintrag öffnet direkt (Titel ohne Vorlage
  wird zum Schreiben fokussiert, wie im Web), die Seitenliste lädt still dahinter. Fehler erscheinen unter dem Knopf.
  Titel-Platzhalter `{date}`/`{fecha}` funktionieren wie im Web. Schaltflächen bleiben auf gesperrten Seiten
  bedienbar (wie im Web); mit Leserecht (Lesen/Kommentieren) sind sie deaktiviert.

### Geändert (Linux)
- **Weniger Datenvolumen und schnellere Reaktion bei großen Konten:** Die App lädt die komplette Seitenliste (bei
  vielen Datenbank-Einträgen mehrere MB) nicht mehr nach jeder Aktion neu. Neue eigene Seiten und
  Datenbank-Einträge (Seitenleiste, „Neue Unterseite“, Block „Seite“/„Neue Datenbank“, „+ Neu“ in Datenbanken,
  Schaltflächen ohne Vorlage) werden gezielt geholt und direkt eingefügt; „In den Papierkorb“ und „Archivieren“
  entfernen die Seite samt Unterseiten sofort aus der Liste; Verschieben zeigt die neue Stelle sofort – jeweils mit
  denselben Daten wie nach einem Neuladen. Der regelmäßige Abgleich bei aktivem Fenster läuft wie bisher jede
  Minute, solange der Live-Kanal nicht verbunden ist. Mit Live-Kanal meldet der Server (ab 2.2, Ereignis
  `notes.tree`) anderswo angelegte, geänderte, verschobene, gelöschte oder archivierte Seiten sofort – die App holt
  dann nur diese Seiten nach (bei Verschieben, Papierkorb, Archiv oder vielen Seiten die ganze Liste); der Abgleich
  alle 5 Minuten bleibt als Sicherheitsnetz. Der seltene Takt gilt erst, nachdem in dieser Anmeldung tatsächlich ein
  `notes.tree` angekommen ist (nicht anhand der Serverversion – Testserver melden schon 2.2.0, schicken das Ereignis
  aber evtl. noch nicht); bis dahin und nach Abmelden oder Serverwechsel weiter jede Minute. „Mit mir geteilt“,
  Glocke, Chats und Heute werden unabhängig davon weiter jede Minute abgeglichen (Gruppenwechsel oder Verschieben
  aus geteilten Bäumen lösen nicht immer ein Ereignis aus). `notes.tree` mit `full: true` lädt die ganze Liste;
  `mixed` wird anhand von `ops` ausgewertet, unbekannte Arten wie eine Änderung (ganze Liste). Beim Zurückkehren ins Fenster und nach einer Unterbrechung des Live-Kanals
  (Netzwechsel, Aufwachen) wird wie bisher abgeglichen. Seitenbaum und Liste werden im Hintergrund aufbereitet,
  der Baum entsteht linear statt durch Suchen über alle Seiten (spürbar bei Tausenden Seiten). Die App fragt
  Antworten komprimiert an (gzip) – sobald der Server Kompression anbietet, sinkt das Datenvolumen um rund 90 %.
- **Schaltflächen „+ Neuer Eintrag“ reagieren sofort und legen nichts doppelt an:** Der Knopf zeigt sofort einen
  Fortschritt und ist gesperrt, bis der Eintrag angelegt ist – mehrfaches Klicken erzeugt keine weiteren Einträge
  (wie in iOS/macOS). Fehler erscheinen als Hinweis.

## [2.1.0] – 2026-10-18

Passt zu Saga-Server 2.1. **Die neuen Funktionen brauchen Saga-Server 2.1; ältere Server funktionieren weiter**
(dort arbeiten die Apps wie bisher – ohne Anrufe, Profilbilder, KI-Persona, KI im Chat, Archiv und Löschen von Chats).
Sprachwahl sowie Thai und Russisch in den Apps funktionieren auch mit älteren Servern.

### Neu (alle Apps)
- **Sprache wählbar:** Einstellungen → „Sprache“: Automatisch (Systemsprache), Deutsch, English, Español, ไทย (Thai)
  oder Русский (Russisch) – nur für Saga, die Sprache des Geräts bleibt unverändert. Praktisch an einem fremden
  oder geteilten Gerät: Ein kleiner Sprachumschalter steht schon **vor der Anmeldung** unten im Anmeldebildschirm,
  und das Anmeldefenster des Servers erscheint in derselben Sprache. Fehlermeldungen des Servers folgen der gewählten
  Sprache.
- **Neue Sprachen Thai und Russisch** in allen Apps, mit denselben Begriffen wie Web und Server, richtigen
  Mehrzahlformen im Russischen und Datum im Thai immer nach gregorianischem Kalender (2026 statt 2569).
  **Maschinell übersetzt – Gegenlesen durch Muttersprachler ist sehr willkommen** (gern als Issue).
- **KI mit Namen und Profilbild:** Hat der Admin der KI einen Namen und ein Bild gegeben (z. B. „Marin“), zeigen die
  Apps sie überall, wo es KI-Funktionen gibt: „Frage Marin“, Antworten mit Bild und Namen, „Marin denkt nach …“.
  Ohne Bild erscheint ein neutrales ✦-Symbol; Änderungen des Admins kommen sofort an.
- **KI im Chat – nur auf Zuruf:** Ist die KI in einem Chat erlaubt, steht dort dauerhaft ein Hinweis, was an die KI
  geht: nur Nachrichten mit „@Marin“ und höchstens 12 Nachrichten davor, im Klartext an den KI-Dienst – alles andere
  bleibt Ende-zu-Ende-verschlüsselt. Berechtigte schalten das im Chat-Menü ein oder aus. Bei „@“ ist die KI der erste
  Vorschlag; ihre Antwort läuft sichtbar mit und geht danach verschlüsselt an alle. Als KI-Antwort (mit Bild, Name und
  „gefragt von …“) erscheint eine Nachricht nur, wenn der Beleg des Servers passt – sonst mit der Warnung „Als
  KI-Antwort ausgegeben, aber nicht bestätigt“.
- **Chats archivieren und löschen:** „Archivieren“ / „Aus Archiv holen“ (nur für mich) – archivierte Chats stehen
  unten im aufklappbaren Bereich „Archiviert (n)“ und zählen nicht zu den ungelesenen; eine neue Nachricht holt den
  Chat zurück (stummgeschaltete bleiben im Archiv). **„Chat löschen …“** mit „Nur für mich“ oder „Für alle“ (wenn der
  Server es erlaubt, sonst ausgegraut mit Begründung), bei freien Gruppen auf Wunsch zusätzlich „Gruppe verlassen“.
  Gelöscht wird wirklich: Nachrichten, Bilder, Anhänge, Entwurf und Mitteilungen des Chats verschwinden auch aus dem
  Zwischenspeicher – auf allen eigenen Geräten und auch mit dem Wiederherstellungsschlüssel nicht mehr lesbar. Löscht
  jemand anderes den Chat, schließt er sich mit dem Hinweis „<Name> hat den Chat gelöscht.“
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
- **Sprache:** iPhone/iPad über die Systemfunktion je App – der Eintrag „Sprache“ in den Einstellungen zeigt die
  aktuelle Sprache und öffnet Einstellungen-App → Saga → Sprache. Am Mac Auswahl direkt in der App, wirksam nach
  „Jetzt neu starten“. Widget, Teilen-Erweiterungen, Siri-Kurzbefehle und Berechtigungstexte auch auf Thai und Russisch.
- **Anmeldebildschirm:** scrollt in kleinen Fenstern (Sprachwahl bleibt sichtbar), „Abbrechen“ (auch Esc), sobald ein
  Server bekannt ist – danach zeigt Saga die gespeicherten Inhalte mit „Nicht angemeldet · Anmelden“, statt das
  Fenster immer wieder zu öffnen. Am Mac lässt sich das Anmeldeblatt in der Größe ändern.
- Chats archivieren per Wischgeste (iPhone/iPad), Kontextmenü (Mac, iPad) oder Chat-Menü „⋯“; KI-Bild als Knopf in
  der Kopfzeile, „Frage Marin“ auch in der Befehlspalette.
- Klingeln auf iPhone/iPad über **CallKit** (Sperrbildschirm, Anrufliste) und PushKit-VoIP; Mac mit eigenem
  Anruf-Fenster, Klingelton und Mitteilung „Annehmen“/„Ablehnen“. Lautsprecher und Kamera wechseln.
- Bilder aus der Zwischenablage mit ⌘V bzw. „Einsetzen“ (vorher geschah am Mac nichts, auf iPhone/iPad landete ein
  Ersatzzeichen).
- Behoben: Auflegen während „Verbinde …“ bricht wirklich ab (vorher „Besetzt“ bis zum Neustart); Knöpfe bei großer
  Schrift; Saga drängt sich am Mac nicht mehr in den Vordergrund; transparente Profilbilder mit weißem Hintergrund;
  Tagesangaben an Server und Widget immer im gregorianischen Kalender (vorher hätte ein Gerät mit buddhistischem
  Kalender ein falsches Jahr gesendet).

### Android
- **Sprache** wirkt sofort, ohne Neustart; Umschalter (Globus) auf dem Anmeldebildschirm. Ab Android 13 auch unter
  Einstellungen → Apps → Saga → Sprache (beide Wege bleiben gleich).
- Chats archivieren per Wischen nach links, langes Drücken oder Chat-Menü – mit „Rückgängig“.
- Mengenangaben mit richtiger Mehrzahl in allen Sprachen („1 час“, „3 часа“, „5 часов“); Sprachnamen im
  KI-Übersetzen-Menü und Tagesüberschriften im Chat folgen der App-Sprache.
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
- **Sprache:** Einstellungen → Allgemein → Sprache, wirksam nach „Jetzt neu starten“ (die App speichert vorher).
  Desktop-Eintrag und Software-Center-Beschreibung auch auf Thai und Russisch; Datumswörter im Editor verstehen
  „завтра в 9:00“, „в пятницу“, „พรุ่งนี้“ usw. Für Thai empfiehlt das RPM eine Thai-Schrift (Noto Sans Thai).
- Chats archivieren im Chat-Menü und im Kontextmenü der Chatliste (Rechtsklick, Menü-Taste bzw. Umschalt+F10);
  bei „@“ übernimmt Tab den KI-Vorschlag.
- Eingehende Anrufe klingeln in der App (Leiste im Fenster, Desktop-Mitteilung, auch über UnifiedPush);
  **Annehmen und Anrufen öffnen das Gespräch im Standardbrowser**.
- Chat-Bilder: 300 Bildnachrichten nach 0,3 s statt 5 s, Speicher 240 MB statt über 2,3 GB (vorher brach die App
  nach 150 Bildern ab).
- Einfügen aus der Zwischenablage (Strg+V): Bilder und Dateien im Chat und auf Seiten.
- Behoben: Start ohne Netz lädt KI, Anrufen, Konto und Glocke nach; Klingel-Leiste im schmalen Fenster lesbar;
  HEIC nur mit passender Bildbibliothek.

### Bekannte Grenzen
- **Thai und Russisch** sind maschinell übersetzt – Verbesserungen von Muttersprachlern sind willkommen.
- **Sprachwechsel** am Mac und unter Linux gilt erst nach einem Neustart der App (die App bietet ihn direkt an).
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
