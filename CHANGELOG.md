# Changelog

Alle nennenswerten Änderungen an den Saga-Apps. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionen nach [Semantic Versioning](https://semver.org/lang/de/). Neue Version: `tools/set-version.sh x.y.z`,
Abschnitt hier ergänzen, auf `main` bringen – GitHub baut und veröffentlicht dann alle Downloads.

## [Unveröffentlicht]

Geplant als **2.5.0**, passt zu Saga-Server 2.5. **Die neuen Funktionen brauchen Saga-Server 2.5 und sind dort vorerst
abschaltbar** (Chat-Stufe 3, Gruppenanrufe über Knoten); mit älteren Servern und Gegenstellen bleibt alles wie bisher.

### Neu – Linux: Vorlagengalerie (Saga-Server 2.5)
- **„Neu“-Knopf mit Menü** → „Aus Vorlage …“, ebenso in der Befehlspalette und im Seitenmenü („Unterseite aus
  Vorlage …“): Liste der Galerie-Vorlagen des Servers (Symbol, Titel, Beschreibung), gruppiert nach Persönlich ·
  Arbeit · Team, in der App-Sprache.
- Bestätigung mit „Enthält: …“, Schalter **„Mit Beispielen“** (Standard an) und – auf einer eigenen Seite –
  „Als Unterseite von …“; danach öffnet sich die angelegte Startseite, der Seitenbaum wird neu geladen.
- Älterer Server oder Galerie abgeschaltet: Einträge ausgeblendet. Fehler (zu viele Anfragen, Server, Rechte) als
  freundlicher Hinweis. Föderierte Seiten und Datenbanken werden nicht als Elternseite angeboten.

### Neu – iOS/Mac: Favicons für Lesezeichen notfalls direkt vom Gerät (Saga-Server 2.5)
- Liefert der eigene Server das Favicon eines Lesezeichens nicht (z. B. Shops mit Bot-Schutz), lädt die App es der Reihe
  nach aus `icon_direct` direkt von der Seite – wie ein Browser-Tab: höchstens 5 s, höchstens 512 KB, nur Rasterbilder
  (PNG/ICO/JPEG/GIF/WebP, kein SVG), ohne Cookies und Anmeldedaten, nur Adressen auf dem Host des Lesezeichens, keine
  Weiterleitung von https auf http. Sonst wie bisher der Anfangsbuchstabe.
- Ergebnis lokal zwischengespeichert (7 Tage, Fehlversuch 1 Stunde), beim Abmelden gelöscht; nichts geht zum Server.
- Dienste unverändert; ältere Server (ohne `icon_direct`) wie bisher. Hinweis: iOS/macOS laden wegen App Transport
  Security nur `https`-Favicons direkt.
### Neu – iOS/Mac: Seite gestalten (Saga-Server 2.5)
- **Darstellung** der im Web gestalteten Seiten: Spaltenbreiten (iPad/Mac; iPhone weiter untereinander), Breite und
  Ausrichtung von Bildern und YouTube-Playern (iPhone mindestens 50 %), Text- und Hintergrundfarbe von Blöcken
  (Palette der Auswahl-Farben, hell/dunkel), Toggle-Überschriften (auch im Inhaltsverzeichnis).
- **Seitenmenü:** „Schrift“ (Standard · Serif · Mono) und „Kleiner Text“ neben „Volle Breite“ – nur mit Server 2.5.
- **Block-Menü:** „Farbe“ mit Textfarben und Hintergründen für Text, Überschrift, Liste, Zitat, Toggle und Hinweis.
  Umwandeln behält die Farbe.
- **Unbekannte Blöcke** sind nicht mehr unsichtbar: Hinweis-Karte mit „Im Web öffnen“ (springt zum Block).
- Speichern behält unbekannte Felder in Blöcken und Block-Tunes unverändert (mit Rundreise-Test).
### Neu – iOS/Mac: Widgets in Seiten (Saga-Server 2.5)
- **Anzeige** der im Web eingerichteten Widget-Blöcke als Karte (Titel, Größe S/M/L, Karte oder schlicht, Akzentfarbe
  aus der Palette), auch in Spalten und Toggles: **Uhr** (lokal tickend, Zeitzone, 12/24 h, Sekunden, Zeigeruhr),
  **Countdown** (bis/seit, Tage oder unter einem Tag Std./Min.), **Fortschritt** (fest oder aus einer Datenbank),
  **Kennzahl** (Zahl/Prozent/Euro in der App-Sprache, Ziel), **Zitat** (vom Server; feste Zitate notfalls lokal nach
  „täglich“/„je Öffnen“).
- Werte vom Server beim Öffnen der Seite und beim Zurückkehren in die App; freundliche Hinweise bei fehlendem Zugriff,
  gelöschter Quelle, unvollständiger Einrichtung, zu vielen Einträgen, älterem Server und fremden Servern ohne Widgets.
  Unbekannte Widget-Arten: „Widget – neuere Version nötig“.
- **Block-Menü:** „Im Web bearbeiten“ (springt zum Block). Die App speichert Widget-Blöcke unverändert (mit Rundreise-Test).
### Neu – iOS/Mac: Vorlagengalerie, einfache Auswahl (Saga-Server 2.5)
- **„Neue Seite“ → „Aus Vorlage …“** (iPhone/iPad: langes Drücken auf den Knopf; Mac: Pfeil am Knopf, Menü „Ablage“
  und Befehlspalette) sowie **„Unterseite aus Vorlage …“** im Seitenmenü eigener Seiten: Liste der Galerie-Vorlagen des
  Servers in der App-Sprache (Icon, Titel, Beschreibung), gruppiert nach Persönlich · Arbeit · Team.
- Antippen → kurze Bestätigung mit „Enthält …“ und Schalter **„Mit Beispielen“** (Standard an) → „Verwenden“ legt die
  Vorlage an, lädt die Seitenliste neu und öffnet die Startseite der Vorlage.
- Server ohne Galerie (älter, 404) oder abgeschaltet: Einträge bleiben ausgeblendet. Fehler (keine Rechte, zu viele
  Versuche, Meldung des Servers) als freundlicher Hinweis; Logik mit Logic-Tests.
### Neu – Linux: Seite gestalten (Darstellung, Farbe, Schrift) und Rückfall-Karte
- **Darstellung:** Spaltenbreiten ab 700 px Seitenbreite (schmaler untereinander), Breite und Ausrichtung von Bildern und
  YouTube-Playern, Text- und Hintergrundfarbe von Blöcken (hell/dunkel), Toggle-Überschriften, Schrift Serif/Mono und
  kleiner Text.
- **Block-Menü:** „Farbe“ (Standard, 9 Textfarben, 9 Hintergründe). **Seitenmenü:** „Schrift“ und „Kleiner Text“ – nur mit Server 2.5.
- **Unbekannte Blöcke:** Hinweis-Karte mit „Im Web öffnen“ statt `[typ]`.
- Umwandeln und Kopieren/Einfügen behalten Block-Tunes (Farbe); Speichern behält unbekannte Felder (mit Rundreise-Test).

### Neu – Android: Vorlagengalerie „Aus Vorlage …“ (Saga-Server 2.5)
- Das „+“ der Seitenleiste wird zum Menü **Neue Seite · Aus Vorlage …**, sobald der Server die Vorlagengalerie
  anbietet (sonst legt „+“ wie bisher direkt eine Seite an). Langdruck an einer eigenen oder mit „Bearbeiten“ geteilten
  Seite: „Aus Vorlage …“ als Unterseite (nicht bei Datenbanken und Seiten anderer Saga-Instanzen).
- Liste in der App-Sprache mit Icon, Titel und Beschreibung, gruppiert nach Persönlich · Arbeit · Team. Antippen →
  Bestätigung mit „Enthält: …“, Schalter **Mit Beispielen** (Standard an) und – wenn eine Seite offen ist –
  „Unter „…““. Danach öffnet die angelegte Startseite, die Seitenliste wird neu geladen.
- Älterer Server (404): Eintrag ausgeblendet. Freundliche Meldungen bei zu vielen Vorlagen in kurzer Zeit (429),
  fehlender Berechtigung (403), nicht möglicher Elternseite (400, Meldung des Servers) und verschwundener Vorlage.
- Unit-Tests für tolerantes Parsing, Gruppen, Anfrage und Fehlerfälle; Texte in de/en/es/ru/th.
### Neu – Android: Widgets in Seiten anzeigen (Saga-Server 2.5)
- Block **Widget** als Karte mit Titel, Größe (S/M/L), Stil (Karte/schlicht) und Akzentfarbe aus der Palette: **Uhr**
  (tickt lokal, Zeitzone, 12/24 h, Sekunden, analoge Zeigeruhr), **Countdown** (bis/seit, Tage oder Std./Min.),
  **Fortschritt** (fester Wert oder aus einer Datenbank), **Kennzahl** (Zahl/Prozent/Euro in App-Sprache, optional mit Ziel),
  **Zitat** (feste Liste täglich/zufällig oder aus einer Datenbank). Auch in Spalten und Toggles.
- Werte über `GET /api/notes/{id}/widgets` beim Öffnen und beim Zurückkehren in die App; freundliche Hinweise bei fehlendem
  Zugriff, gelöschter Quelle, falscher Einrichtung, zu großer Datenbank, älterem Server und Seiten anderer Server.
- Unbekannte Widget-Arten: „Widget – neuere Version nötig“. Einstellungen nur im Web („Im Web bearbeiten“ im Block-Menü);
  Widget-Blöcke werden unverändert gespeichert (mit Rundreise-Test).
### Neu – Linux: Widgets in Seiten (Anzeige, Saga-Server 2.5)
- Block `widget` als native Karte (Titel, Größe S/M/L, Karte/schlicht, Akzentfarbe): **Uhr** (Zeitzone, 12/24 h,
  Sekunden, Zeigeruhr; tickt nur, solange sichtbar), **Countdown** (bis/seit, Tage oder Std./Min.), **Fortschritt**,
  **Kennzahl** (Zahl, Prozent, Euro in der Schreibweise der Sprache), **Zitat** – auch in Spalten und Toggles.
- Werte aus der Datenbank kommen vom Server (beim Öffnen und wenn das Fenster wieder aktiv wird); freundliche Hinweise
  bei fehlendem Zugriff, gelöschter Quelle, älterem Server, Föderation ohne Widgets und offline – ein Fehler beim
  Neuladen lässt die zuletzt geladenen Werte stehen.
- Einstellungen nur im Web: Block-Menü „Im Web bearbeiten“. Unbekannte Widget-Arten: „Widget – neuere Version nötig“.
  Widget-Blöcke bleiben beim Speichern unverändert (mit Rundreise-Test).

### Neu – Android: Seite gestalten (Darstellung, Farbe, Schrift) und Rückfall-Karte
- **Spaltenbreite:** Gewichte der Spalten (`cols[].w`) gelten ab 600 dp Breite; auf Telefonen weiter untereinander.
- **Bilder und Videos:** Breite (`width`, auf Telefonen mindestens 50 %) und Ausrichtung links/Mitte/rechts wie im Web;
  „Volle Breite“ gewinnt. Link-Karten bleiben unverändert.
- **Block-Farbe:** Text- oder Hintergrundfarbe aus der Saga-Palette (hell/dunkel lesbar) wird angezeigt und lässt sich
  über „Farbe“ in der Werkzeugleiste setzen (Absatz, Überschrift, Liste, Zitat, Toggle, Hinweis).
- **Schrift und kleiner Text je Seite:** Seitenmenü „Schrift und Textgröße“ (Standard · Serif · Mono, kleiner Text);
  erscheint nur mit Servern, die die Felder liefern.
- **Toggle-Überschriften:** Titel in Überschriftengröße (H1–H3).
- **Unbekannte Blöcke:** einheitliche Karte „Dieser Block lässt sich in der App noch nicht anzeigen.“ mit „Im Web
  öffnen“ (springt zum Block). Unbekannte Felder und Block-Einstellungen bleiben beim Speichern erhalten.

### Behoben – Android: Anrufe über den Knoten (Kamera, Bildschirm, neue Teilnehmende)
- **Kamera:** Die Simulcast-Schichten hatten je Schicht eigene Bildrate und zeitliche Schichten – wie auf iOS kann
  WebRTC damit das 720p-Bild nicht kodieren. Jetzt alle Schichten mit denselben Werten, die Bildrate wird nur
  gemeinsam begrenzt (Energiesparen).
- **Bildschirm teilen:** Bei stehendem Bildschirm liefert Android keine neuen Bilder; ein vom Knoten angefordertes
  Schlüsselbild kam erst mit der nächsten Änderung. Die App wiederholt jetzt das letzte Bild alle 500 ms.
- **Knotenwechsel:** Kamera ein- oder Teilen gestartet, während die App gerade zu einem anderen Knoten wechselte – auf
  der neuen Verbindung ging dann kein Bild. Die Spuren werden beim Übernehmen abgeglichen.
- **Neue Teilnehmende:** Belegte der Knoten den Platz eines neuen Geräts, bevor dessen Schlüssel ankam, räumte die
  App die Schlüssel zwei Sekunden später wieder weg – Ton und Bild des Neuen blieben auf diesem Platz aus.
- Nach dem Auflegen entsteht keine Verbindung zum Knoten mehr (blieb sonst bis zum App-Ende liegen).

### Behoben – iOS/Mac: Bildschirm teilen über den Knoten blieb beim Gegenüber schwarz
- Ursache: Die verwendete WebRTC-Bibliothek legt für einen Sender **ohne Spur** keine Ende-zu-Ende-Verschlüsselung
  (FrameCryptor) an. Der Bildschirm-Sender ist beim Verbindungsaufbau leer, die Spur kommt erst mit dem Teilen – die
  Bilder gingen dann unverschlüsselt zum Knoten, das Gegenüber verwarf sie und sah nur eine schwarze Fläche. Gleiches
  galt für die Kamera, wenn sie erst im laufenden Anruf eingeschaltet wurde.
- Jetzt wie in der Android-App: Für das Anlegen der Verschlüsselung hängt kurz eine Platzhalter-Spur am Sender. Lässt
  sich an einem Sender trotzdem keine Verschlüsselung anlegen, wird die Verbindung zum Knoten gar nicht erst aufgebaut –
  kein Bild und kein Ton geht unverschlüsselt hinaus.
- Tests: `HornE2EVectorsTests.testLoopbackTrackSetLater` (Spur nach der Aushandlung, mit und ohne Schlüssel beim
  Empfänger), `HornNodeTests.testScreenShareStartedLater` (Teilen beginnt im laufenden Anruf über einen lokalen Knoten).

### Behoben – iOS: Bildschirm teilen über den Knoten kam stark verzögert an und blieb stehen
- Bei stehendem Bildschirm liefert iOS (ReplayKit) keine neuen Bilder. Der Knoten fordert beim Belegen des
  Bildschirm-Platzes und nach Verlusten ein Schlüsselbild an, das WebRTC aber erst mit dem nächsten Bild kodiert –
  bis zur nächsten Änderung am Bildschirm sah das Gegenüber nichts oder ein stehendes Bild.
- Die App wiederholt jetzt das letzte Bild alle 500 ms, solange keine neuen kommen (auch am Mac, ScreenCaptureKit
  verhält sich gleich). Gleiche Bilder kosten als Delta kaum Bitrate.
- Nachweis gegen einen lokalen Knoten (`HornNodeTests.testScreenShareSparseFrames*`, Bilder wie aus der Erweiterung
  884×1920): ohne Wiederholung 0 Bilder nach 5 s, mit Wiederholung sofort und bei jeder Änderung.

### Behoben – iOS/Mac: Profilbilder verschwanden weiterhin während die App lief
- Zweite Ursache neben dem leeren `304`: iOS leert den Bild-Speicher der App von selbst (Hintergrund, Speicherdruck).
  War das Bild weg, die letzte Prüfung aber jünger als 10 Minuten, zeigte die App Initialen statt es neu zu lesen – bis
  zum Neustart. Jetzt liest sie es neu (meist ohne Netz aus dem Zwischenspeicher von iOS).
- „1 people“/„1 Personen“ in der Chatliste: Personenzahl jetzt mit Einzahl (de/en/es; ru hatte schon Pluralformen).

### Neu – alle Apps: aufgeräumte Einstellungen und Update-Hinweis
- Die Einstellungen sind wie in anderen Apps geteilt in **Konto**, **Einstellungen** und **Info** (iOS/Android:
  Unterseiten, Mac: Tabs im Einstellungsfenster, Linux: drei Seiten im Einstellungsdialog, Mein Backup, Mitteilungen
  und Chats & Geräte als Unterseiten).
- Unter **Info** stehen die Versionen von App und Server. Gibt es im eigenen Kanal (Testversion bzw. stabil) eine neuere
  Version, zeigt die App das an. Eine einzige Schaltfläche: **Nach Updates suchen**, solange nichts Neues bekannt ist,
  sonst **Update** (Android: APK, Mac: Selbst-Update bzw. DMG, Linux: Release-Seite; iOS nur als Hinweis, das Update
  kommt über die Geräteverwaltung). Geprüft wird höchstens alle 12 Stunden; der Hinweis lässt sich abschalten.
- Die CI legt dafür `stable.json`/`testing.json` in den Zweig `appcast` (`tools/update-info.py`).
- Android: Startseite mit Profilbild und Name (→ Konto), „Einstellungen“ (Allgemein · Benachrichtigungen · Chat ·
  Dienste) und „Info“ mit Punkt bei bekanntem Update; „Update“ öffnet die APK von GitHub. Test `UpdateHintTest`.

### Behoben – iOS: Anrufe über den Knoten klingeln bei geschlossener App
- Seit Anrufe über Gjallarhorn-Knoten laufen, kommen auch 1:1-Anrufe als Art `call.group` (mit `dm`). Der Server
  schickt sie jetzt (wie Gruppenanrufe) als VoIP-Mitteilung; die App zeigt sie in CallKit richtig an: bei 1:1 den Namen
  der Person, bei Gruppen den Chatnamen (ohne bekannten Namen den Text „Gruppenanruf von …“), Video aus `media`.
- Annehmen auf dem Sperrbildschirm tritt dem Raum über den Knoten bei (wie in der App), auch wenn CallKit schneller
  angenommen hat, als die App den Anruf abfragen konnte; die Audio-Session aktiviert CallKit.
- Legt die anrufende Person auf, nimmt ein anderes eigenes Gerät an, wird die Einladung anderswo abgelehnt oder läuft
  das Klingeln ab (auch wenn der Gruppenanruf ohne mich weiterläuft), endet der CallKit-Anruf sofort. Ein schon
  beendeter Anruf wird gemeldet und gleich beendet; klingelt ein gemeldeter Anruf nach 20 s in der App nicht, endet er.
- Ist die App offen, zeigt sie Anruf-Mitteilungen des Servers nicht zusätzlich als Banner mit Ton (kein doppeltes
  Klingeln). Logiktests `VoipGroupTests`.

### Behoben – iOS/Mac: 1:1-Videoanruf über den Knoten zeigte kein Bild des Gegenübers
- Lief ein Videoanruf über einen Gjallarhorn-Knoten (z. B. mit „Alle Anrufe über Knoten“), war der Ton da, aber beide
  sahen vom Gegenüber nur die Initialen. Ursache: Die Kamera-Schichten (Simulcast) hatten je Schicht eine andere
  Bildrate und andere zeitliche Schichten; damit kodierte WebRTC ein Kamerabild in voller Größe gar nicht. Alle
  Schichten haben jetzt dieselben Werte, Energiesparen begrenzt die Bildrate gemeinsam. Betraf auch Gruppenanrufe
  über den Knoten von iPhone, iPad und Mac aus.
- Integrationstest gegen einen lokalen Knoten mit zwei Seiten und Testbild 1280×720 (`HornNodeTests.testOneToOneVideo*`),
  Logiktest `HornCamTests`.

### Behoben – Android: eingehende Anrufe klingeln bei geschlossener App durchgehend
- Bei geschlossener App klingelte bzw. vibrierte ein Anruf nur einmal (Pixel, gesperrt wie entsperrt). Die App
  klingelt jetzt selbst: Klingelton in Schleife und wiederholte Vibration, bis angenommen, abgelehnt, auf einem
  anderen Gerät angenommen oder aufgelegt wird; Stumm, Vibration, Ruftonlautstärke und „Bitte nicht stören“ gelten.
- Neuer Mitteilungskanal ohne eigenen Ton (der alte wird entfernt); ein kurzer Vordergrund-Dienst hält die App
  während des Klingelns wach. Test `RingLogicTest`.

### Behoben – iOS/Mac: Profilbilder verschwanden nach einigen Minuten
- Nach etwa 10 Minuten zeigten Chatliste, Konto und Mitglieder nur noch Initialen, bis die App neu startete. Ursache:
  Bei der Nachfrage „Bild unverändert?“ (ETag) reichte iOS die Antwort `304` manchmal leer durch, und die App wertete
  das als „kein Bild“. Ein leeres `304` gilt jetzt als „unverändert“ (das Bild bleibt); fehlt es im Speicher, lädt die
  App es einmal ohne Zwischenspeicher neu.

### Behoben – alle Apps: Schaltflächen mit Vorlage auf Seiten eines anderen Servers
- Auf einer über Servergrenzen geteilten Seite meldete „Neuer Eintrag“ mit Vorlage „Eintrag nicht angelegt: Nicht
  gefunden“. Android, iOS/Mac und Linux schicken die Seite mit der Schaltfläche (`via`) jetzt auch bei negativer
  Seiten-ID; der Server reicht den Eintrag an den Server des Eigentümers weiter (Server mit Fähigkeit `tplbtn`).
  Android-Test `ButtonEntryFedTest`.

### Neu – macOS: Die App aktualisiert sich selbst (DMG von GitHub)
- Die Mac-App aus der DMG erkennt neue Versionen, bietet sie an („Installieren“, „Später“, „Überspringen“), installiert
  auf Wunsch und startet neu. Menü „Saga → Nach Updates suchen …“; automatische Prüfung alle 4 Stunden, abschaltbar
  in den Einstellungen unter „Updates“. Testversionen erhalten neue Testversionen, Releases nur Releases.
- Updates und Update-Liste sind mit EdDSA signiert (Sparkle 2); Apps aus eigenen Builds oder MDM und iOS sind nicht
  betroffen. Die erste DMG mit Selbst-Update einmal von Hand installieren.

### Neu – alle Apps: Graph der Seiten wie in Obsidian (Server 2.5)
- „Graph“ in der Seitenleiste zeigt alle Seiten und ihre Verbindungen (Verweise im Text, Unterseiten,
  Datenbank-Relationen, auf Wunsch Tags als Knoten); „Lokaler Graph“ im Seitenmenü zeigt die Nachbarn einer Seite in
  Tiefe 1 oder 2. Zoomen, verschieben, Knoten ziehen, Suche und Filter; Antippen zeigt eine Karte mit „Öffnen“.
- iOS/iPadOS/macOS, Android und Linux; am Mac auch über „Gehe zu“ (⇧⌘G) und ⌘+/⌘−/⌘0. Für VoiceOver, TalkBack und
  Orca gibt es eine Listenansicht. Mit älteren Servern erscheint „Braucht Server 2.5“.

### Neu – Android: Widgets „Heute“ und „Seite“
- „Heute“ zeigt die Termine des Tages und offene Aufgaben (heute fällig, überfällig oder ohne Datum); der Kreis hakt
  eine Aufgabe direkt im Widget ab, „+“ öffnet „Heute“ mit dem Eingabefeld für eine neue Aufgabe. Offline bleibt der letzte Stand mit Uhrzeit stehen.
- „Seite“ zeigt eine frei gewählte Seite mit Symbol, Titel und Textanfang; antippen öffnet sie in der App.
- Die Widgets nutzen die Anmeldung der App und werden beim Abmelden geleert.

### Neu – iOS/macOS: Lesezeichen aus dem Teilen-Blatt (Server 2.5)
- Wer einen Link teilt (z. B. aus Safari), kann ihn jetzt als „Lesezeichen“ unter „Dienste“ ablegen: Name aus dem
  Seitentitel, Kategorie aus den vorhandenen wählbar. Die zuletzt benutzte Art und Kategorie sind vorausgewählt.
  Ältere Server melden, dass sie das noch nicht können.

### Geändert – Android: Links öffnen im Standard-Browser
- Links aus Seiten, Diensten, Lesezeichen, Datenbanken und dem Chat öffnen im Standard-Browser des Geräts statt in
  einem eingebetteten Tab. Anmeldungen (Saga, Nextcloud, Sicherheit) laufen weiter im eingebetteten Tab, damit die
  Rückkehr in die App klappt.

### Neu – Android und Linux: Datenbank-Kopf, Vorlagen verwalten, KI ordnet zu (Server 2.5)
- Wie auf iOS/macOS: eingebetteter Datenbank-Block mit ausblendbarem Titel (Android: Block antippen → „Titel
  ausblenden“; Linux: ⚙ → „Dieser Block“, dort auch die Ansichts-Reiter).
- Vorlagen einer Datenbank: Android bietet sie jetzt unter „Neuer Eintrag ▾“ an (vorher gar nicht), dazu
  „Vorlagen verwalten …“ auf Android und Linux (umbenennen, bearbeiten, duplizieren, Standard für „Neu“, „Keine
  Vorlage mehr“, Papierkorb, neue Vorlage). Linux: „Neu“ nimmt die Standardvorlage, „Leerer Eintrag“ bleibt leer.
- KI-Assistent → „Diese Seite“ → „Datenbank zuordnen“: Vorschläge je Eintrag, abhaken und übernehmen.

### Verbessert – Android und iOS/macOS: weniger Akku in Anrufen über einen Knoten
- Bisher kodierte die App das eigene Kamerabild immer in allen Schichten (UDP: 1/4, 1/2 und volle Größe), auch wenn
  niemand sie braucht. Jetzt: allein im Raum (z. B. beim Klingeln) keine; zu zweit ohne die kleine Kachel-Schicht;
  im Energiesparmodus oder wenn das Gerät heiß wird ohne die volle Größe und höchstens 15 fps. Wer beitritt, bekommt
  das Bild binnen Sekunden. Gilt für 1:1 über einen Knoten und Gruppenanrufe (iOS: Stromsparmodus bzw. Gerät
  „ernsthaft“ heiß).

### Neu/Behoben – Anrufe: eigenes Bild verschiebbar und ausblendbar, Knöpfe nach dem Drehen
- Eigenes Kamerabild (1:1 und zu zweit über einen Knoten) in jede Ecke ziehen – rastet ein – oder mit dem kleinen
  Knopf am Bild ausblenden (bleibt als Knopf in der Ecke, Tippen zeigt es wieder). Das Gerät merkt sich beides.
  VoiceOver/TalkBack: Aktion „Eigenes Bild verschieben“. iOS/iPadOS/macOS und Android.
- iOS: Nach dem Drehen (quer → hoch) blieb die Knopfreihe auf der Querformat-Breite – der Anrufbildschirm war breiter
  als das Gerät, Knöpfe und eigenes Bild links/rechts abgeschnitten. Die Breite kommt jetzt vom Fenster.
- Android: Bis zu sechs Knöpfe mit festen 64 dp passten auf schmalen Geräten bzw. mit großer Anzeigegröße nicht in
  die Reihe (z. B. OnePlus). Die Knöpfe richten sich jetzt nach der verfügbaren Breite (Kreis mindestens 44 dp).

### Behoben – alle Apps: Schaltfläche mit Vorlage auf geteilten Seiten
- „Neuer Eintrag“ mit Vorlage aus einer nicht geteilten Datenbank des Eigentümers legte für andere Personen nichts an.
  Die Apps (iOS/macOS, Android, Linux) schicken jetzt die Seite mit der Schaltfläche mit (`via`, braucht Server 2.5).

### Neu – iOS/iPadOS/macOS: Datenbank-Kopf, Vorlagen verwalten, KI ordnet zu (Server 2.5)
- Eingebettete Datenbank: unter „Ansicht“ → „Dieser Block“ lassen sich Datenbanktitel und Ansichts-Reiter je Block
  ausblenden (wie im Web, im Block gespeichert).
- „Neu ▾“ → „Vorlagen verwalten …“ (auch unter „Ansicht“): umbenennen, bearbeiten, duplizieren, Standard für „Neu“,
  „Keine Vorlage mehr“, Papierkorb, neue Vorlage. „Neu“ nimmt die Standardvorlage, „Leerer Eintrag“ bleibt leer.
- KI-Assistent → „Diese Seite“ → „Datenbank zuordnen“: Vorschläge je Eintrag für eine Eigenschaft, abhaken und
  übernehmen. Android und Linux folgen.

### Behoben – Android: Mitteilungen bei geschlossener App
- Die Abfrage „Mitteilungen erlauben?“ erteilte nur die Android-Berechtigung; Mitteilungen auf dem Gerät musste man
  zusätzlich unter Einstellungen → Mitteilungen einschalten – sonst kam bei geschlossener App nichts an (geöffnet
  zeigte die App Neues über die Live-Verbindung, deshalb fiel es kaum auf). Jetzt schaltet „Erlauben“ sie gleich ein
  (eigene Verbindung bzw. der installierte UnifiedPush-Verteiler), und beim App-Start werden sie für alle
  eingeschaltet, die die Berechtigung schon erteilt, Mitteilungen aber nie bewusst ausgeschaltet haben.
- Geprüft im Emulator: App im Hintergrund, aus den Letzten gewischt, Prozess beendet, Doze, nach Neustart und nach
  App-Update – die Mitteilung kommt jeweils, Antippen öffnet den Chat. (Nach „Stoppen erzwingen“ in den
  System-Einstellungen bleibt Android-bedingt alles aus, bis die App wieder geöffnet wird.)

### Behoben – Android: Funde aus dem 2.5-Test
- Seiten-Editor: Einfügen/Formatieren scrollt, Hoch/Runter/Löschen/Fertig bleiben rechts fest sichtbar.
- Datenbank-Tabelle: Pfeil am rechten Rand, wenn weitere Spalten folgen (antippen scrollt weiter); Filter-Wert mit
  Beschriftung; Auswahl-Chips im Dunkelmodus gut lesbar.
- Papierkorb: „noch x Tage“ rundet nicht mehr auf (29,5 Tage = „29“, unter einem Tag „1“).
- Seitenleiste: Einträge mit Freigabe-/Ungelesen-Hinweis, aber ohne Unterseiten, stehen bündig mit den anderen.
- Einstellungen: das eigene Gerät ist als „dieses Gerät“ markiert (ohne eigenes Abmelden – dafür gibt es „Abmelden“);
  nach der Rückkehr aus einer Unterseite steht die Liste wieder an derselben Stelle.
- Neuer Chat: ausgewählte Personen als Chips mit ×; Kopfzeile eines Föderations-Chats mit Anwesenheit kürzer
  (Schloss-Symbol statt E2E-Text).
- Anruf: Hinweis, wenn die Kamera nicht startet; das eigene Bild ist in der Kachelansicht immer dabei.
- Mitteilungen werden nach einer neuen Anmeldung automatisch eingeschaltet (wie nach dem Erlauben).
- **Schaltflächen** „Neuer Eintrag“ legen jetzt wirklich einen Eintrag in der Datenbank an (leer oder aus der
  gewählten Vorlage, Titel mit `{datum}`) und öffnen ihn – bisher öffneten sie nur die Datenbank. „Aufgabe“-Knöpfe
  legen die Aufgabe an; doppeltes Tippen legt nichts doppelt an.
- **GIFs** laufen animiert (Seiten, Chat, Immich) statt nur das erste Bild zu zeigen.

### Neu – iOS/iPadOS/macOS: eigene Lesezeichen
- In „Dienste“ lassen sich eigene Lesezeichen jetzt anlegen (+), bearbeiten und löschen (lange drücken bzw.
  Rechtsklick) – wie im Web und auf Android. Felder: Adresse, Name, Kategorie, Symbol (selfh.st). Standard-Kategorien
  („Lesezeichen“, „Dienste“) erscheinen in der App-Sprache statt in der Sprache des Kontos.

### Behoben – Editor und Anrufe (alle Apps)
- „/“ im letzten Listenpunkt öffnet das Block-Menü wie im Absatz (Android, iOS/iPadOS/macOS): Die Wahl beendet die
  Liste und setzt den neuen Block dahinter. Mitten in einer Liste bleibt „/“ normaler Text.
- Gruppenanrufe in freien Gruppen zeigen den Gruppennamen statt „Gruppenanruf“ – der Name ist Ende-zu-Ende-
  verschlüsselt, die Apps nehmen ihn jetzt aus der eigenen Chatliste (beim Starten, Beitreten und Klingeln).

### Behoben – „Hierher übernehmen“ bei Anrufen über einen Knoten
- Mit „Alle Anrufe über Knoten (auch 1:1)“ wurde ein Anruf nicht übernommen (z. B. iPhone → Mac): Das neue Gerät hat
  keine Direktverbindung zum alten und meldete es nur beim Server ab – das alte telefonierte weiter. Jetzt bekommt
  das alte Gerät „übernommen“ über den Signalweg des Knotens und beendet den Anruf; verschwindet es aus der
  Teilnehmerliste, während ein anderes eigenes Gerät dabei ist, beendet es ihn ebenfalls. Android kann jetzt auch
  über einen Knoten übernehmen (bisher trat es dort nur als Begleitgerät bei).

### Behoben – Mac: Cursor im Editor versetzt
- Beim Bearbeiten stand der Cursor ~5 pt rechts neben Platzhalter und Text, und in Listen/Checklisten saß die Zeile
  tiefer als Kästchen bzw. Aufzählungszeichen. Innenrand des Mac-Textfelds ausgeglichen, Grundlinie angegeben.

### Geändert – Android wie iOS: Seitenkopf, Tags, Datenbanken
- **Titelbilder auf Seiten** (fehlten bisher ganz): über die volle Breite mit gespeicherter Position; antippen =
  Ändern/Entfernen. Das Symbol (Emoji oder Bild) sitzt halb darüber; antippen öffnet die Auswahl (Emoji, eigenes
  Bild, Zufällig, Entfernen). Fehlt eins davon: „Symbol hinzufügen“ / „Titelbild hinzufügen“.
- **Tags** unter dem Titel („+ Tag“ mit Vorschlägen, × zum Entfernen) und **„Verlinkt von“** am Seitenende.
- **Datenbanken:** eine kompakte Zeile wie auf iOS – Ansicht als Auswahl-Menü (inkl. „Ansicht bearbeiten“/„Neue
  Ansicht“), Suche als Symbol, Filter und Sortieren als Symbole mit Zähler, „Neuer Eintrag“; „x von y“ nur beim
  Filtern. Galerie-Karten ohne Bild ohne leere Fläche, Symbol vor dem Namen.
- Eigenes Profilbild oben in der Seitenleiste (tippen = Einstellungen); Favoriten-Stern als Umriss, solange die Seite
  kein Favorit ist.
- Titelbild **neu positionieren** (senkrecht ziehen, Speichern/Abbrechen) und **Chats durchsuchen** (Name) wie auf iOS.

### Geändert – Anruf-Oberfläche vereinheitlicht (iOS/iPadOS/macOS, Android, Web)
Gemeinsame Regeln in `docs/ANRUF-UI.md`:
- Kopf: Titel (Gegenüber bzw. Gruppe), Dauer direkt darunter, eine Sicherheitszeile; Teilnehmenden-Chip mit Zahl nur
  in Gruppen (`11/12` erst nahe der Grenze), im 1:1 keiner.
- Ab drei Geräten ist das eigene Bild eine normale Kachel „Du“; drei Kacheln: zwei oben, die dritte über die volle
  Breite. Zu zweit: eigenes Bild klein, abgerundet, auf iOS ziehbar.
- Bildschirm geteilt: ein neutraler Hinweis „… teilt den Bildschirm“ am geteilten Bild statt eines Banners; rot nur
  für die eigene Freigabe. Der Hinweis vor dem Teilen sagt in Gruppen „sehen alle im Anruf“.
- Namensleiste: Name vor Gerät, Pseudonym-Handles in der Mitte gekürzt, eigene Kachel nur „Du“, 70 % deckend.
- „<Gastgeber> hat dich stummgeschaltet“ bleibt über der Bedienleiste stehen, bis man das Mikrofon selbst einschaltet.
- Android: Knöpfe beschriftet, Chips mit 48-dp-Trefferfläche; iOS: Klingeln als „Eingehender (Video-)Anruf“,
  Ende-Bildschirm ohne Sicherheitszeile, Gruppen mit Gruppen-Symbol.

### Behoben – Anrufe über Knoten (Test mit echten Servern, 24.10.)
**iOS/iPadOS/macOS, Android:**
- **1:1 über Knoten** („Alle Anrufe über Knoten“ am Server) klingelt und läuft jetzt wie ein 1:1-Anruf – vorher
  „Gruppen-Videoanruf“ beim Klingeln und „Gruppenanruf“ als Titel. Das Anruf-Objekt meldet `dm: true`.
- **Zu zweit über Knoten** (1:1 bzw. Gruppenanruf mit nur noch einer weiteren Person): Gegenüber groß, eigenes Bild
  klein in der Ecke wie beim direkten 1:1 – vorher zwei schmale Hochkant-Kacheln, das Bild des Gegenübers stark
  beschnitten. Android: das große Bild ist dann kein Medien-Overlay mehr, sonst verschwand das eigene Bild dahinter.
- **iOS: „Verschlüsselung wird geprüft …“** blieb in jedem Anruf über Knoten stehen, obwohl Frame-E2E aktiv war
  (die Anzeige wurde aus den – im Knoten-Anruf leeren – Mesh-Verbindungen neu berechnet).
- iOS (nur Debug): Startparameter `-hkCallAutoAnswer` gilt auch für Raum-Anrufe (Tests im Simulator).

### Neu – Saga 2.5: Chat-Stufe 3 und Chat-Grundlagen
**iOS/iPadOS/macOS, Android, Linux:**
- **Chat-Stufe 3:** Die Apps melden `X-Saga-Chat: 3`; Chats `v: 3` laufen über den Weg der Chats über Servergrenzen
  (Handles statt Anmeldenamen), lokal ohne Hinweise auf andere Server. Die Vertrauensstufe „Überprüft“ wird von
  Anmeldenamen auf Handles übernommen, wenn der Identitätsschlüssel gleich ist („Neue Darstellung, gleicher Schlüssel“).
  Altbestand bis zum Umstellpunkt bleibt im Format v1 lesbar; wird ein Chat beim Senden umgestellt, sendet die App neu
  im Format v2.
- **Reaktionen:** Schnellleiste mit 6 Emojis und Emoji-Auswahl, Chips unter der Nachricht, „Reaktionen von …“ –
  Ende-zu-Ende verschlüsselt, live.
- **Nachricht bearbeiten:** eigene Nachrichten bis 24 Stunden, höchstens 20-mal, Hinweis „bearbeitet“, live.
- **Sprachnachrichten:** Aufnahme als Opus (24 kbit/s) in Ogg, Vorschau, Hinweis nach 14 und Stopp nach 15 Minuten,
  unter 1 s verworfen (Ogg-Granule nach RFC 7845); Wiedergabe mit Wellenform und Tempo 1×/1,5×/2×.
- Tests: Vektoren `chat_v3.json` (Reaktionen, Ablehnungen, Bearbeiten, Vertrauens-Übernahme), Ogg/Opus, Wellenform –
  nie mit dem Mikrofon.

### Neu – Saga 2.5: Gruppenanrufe über Gjallarhorn-Knoten
**iOS/iPadOS/macOS, Android:**
- **Große Gruppenanrufe:** Meldet der Server `topo: horn`, messen die Apps die Knoten, verbinden sich mit dem besten
  (Ton und Bild auf TCP/TLS getrennt, auf UDP gebündelt, sonst TURN-TLS über den Knoten) und wechseln bei Abbruch,
  Wartung oder deutlich besserem Weg ohne Unterbrechung. Kleine Anrufe bleiben im Mesh.
- **Ende-zu-Ende verschlüsselt (saga-horn/v1):** Jeder Rahmen wird vor dem Knoten mit FrameCryptor verschlüsselt; die
  Schlüssel gehen über den versiegelten Signalweg, Runensiegel zum Vergleichen.
- **Oberfläche:** Kacheln (Handy 4, Tablet/Mac 9 bewegte Bilder), Sprecheransicht, Bildschirm teilen, Weg mit
  Qualitätspunkt, Teilnehmerliste mit Stummschalten/Entfernen für den Gastgeber, Bild im Bild.
- **Über Servergrenzen und Kaskade:** Die Apps melden ihre Kennungen an den Knoten im versiegelten Zustand (`state.hp`)
  und ordnen damit auch Teilnehmende an Knoten anderer Server zu. Bleibt ein Gruppenanruf über Servergrenzen ohne Knoten
  (`horn_mesh`), zeigen die Apps den Hinweis des Servers einmal an.
- **Bild über TCP/TLS:** Der Knoten setzt dort `playout-delay`; die Apps handeln die Erweiterung unverändert aus.
- **WebRTC:** iOS/macOS und Android auf webrtc-sdk 150.7871.01 (FrameCryptor mit HKDF).

### Neu – Android: Push ohne Verteiler
- Ist kein UnifiedPush-Verteiler installiert, hält die App selbst eine sparsame Verbindung zum eigenen Server
  (Vordergrund-Dienst, abschaltbar, Lebenszeichen im gelernten Abstand). Wird ein Verteiler installiert, schaltet sie um.
  Push-Einstellungen zeigen Stand und Erklärung; die Akku-Ausnahme wird nur erklärt, nie erzwungen.

## [2.4.0] – 2026-10-20

Passt zu Saga-Server 2.4. **Die neuen Funktionen brauchen Saga-Server 2.4; ältere Server funktionieren weiter**
(dort und bei Gegenstellen mit Saga 2.3 blenden die Apps die neuen Funktionen aus und arbeiten wie bisher).

**Höhepunkte**
- **Bild im Bild:** Videoanrufe laufen auf iPhone/iPad im Bild-im-Bild-Fenster und auf dem Mac im Schwebefenster
  weiter, wenn man die App verlässt.
- **Bildschirm teilen:** den eigenen Bildschirm im Anruf zeigen, auf Wunsch zusammen mit der Kamera.
- **Videos im Chat:** Videos senden (optimiert oder im Original), abspielen ohne kompletten Download und speichern –
  Ende-zu-Ende verschlüsselt.
- **Gelesen und Online:** Haken für zugestellt/gelesen und Online-Status, beides in Einstellungen → Datenschutz
  abschaltbar.
- **Gerät übergeben und hinzufügen:** einen laufenden Anruf ohne Abbruch auf ein anderes eigenes Gerät holen oder ein
  weiteres Gerät dazunehmen.
- **Gruppenanrufe:** Anrufe als Raum mit mehreren Personen, mit Kachelansicht.
- **Android auf dem Stand von iOS und Web:** Datenbanken mit Ansichten, Editor mit „/“-Menü und neuen Blöcken,
  Papierkorb und Termine.
- **Zwei kritische Testrunden** auf allen Plattformen, mit vielen Korrekturen (u. a. Papierkorb auf iPhone/iPad).

### Neu – Saga 2.4: Medien und Mehrgeräte
Die neuen Funktionen brauchen Saga-Server 2.4. Mit 2.1-/2.3-Servern und Gegenstellen mit Saga 2.3 bleibt alles wie
bisher: Die Apps handeln die Fähigkeiten aus (Server, Gerät, Partnerserver) und blenden sonst aus.

#### Anruf-Grundlage (saga-call v3) und Bild im Bild (Etappen E0/E1)
**iOS/iPadOS/macOS:**
- **Bild im Bild (iPhone/iPad):** Ein Videoanruf läuft in einem kleinen, verschiebbaren Fenster weiter – beim
  Verkleinern („Zurück zur App“) und automatisch beim Wechsel in eine andere App. Gezeigt werden nur Video und Name,
  keine Chat-Inhalte. „Zurück“ im Fenster öffnet den Anruf wieder im Vollbild. Die eigene Kamera läuft weiter, wo iOS
  das für Videoanruf-Apps erlaubt; sonst pausiert sie und das Gegenüber sieht „Kamera aus“.
- **Schwebefenster (Mac):** Wird das Fenster „Anruf“ ins Dock gelegt oder ganz verdeckt – oder per Knopf
  „Bild im Bild“ oben links –, erscheint ein kleines Fenster über allen anderen (auch auf allen Schreibtischen und neben
  Vollbild-Apps) mit dem Bild des Gegenübers und den Knöpfen Stumm, Auflegen und „Zurück zum Anruf“. Größe und Ort
  bleiben erhalten.
- **Weniger Daten im Bild im Bild:** Das Gegenüber wird gebeten, ein kleineres Bild zu senden (≤ 300 kbit/s, halbe
  bzw. drittel Auflösung) – das spart Datenvolumen und Akku auf beiden Seiten. Ältere Apps ignorieren den Hinweis.
- **Bitrate je Verbindungsweg:** Alle 2 s wird der Weg geprüft (direkt, über Relais per UDP oder per TCP/TLS 443) und
  die Senderate angepasst (z. B. über TCP/TLS ≤ 800 kbit/s und 540p, Ton 24 kbit/s) – weniger Ruckeln und Einfrieren
  über den TURN-Server.
- **Netzwechsel ohne Abbruch:** Wechselt das Gerät zwischen WLAN und Mobilfunk oder bricht die Verbindung kurz weg,
  baut Saga sie per ICE-Restart neu auf („Verbindung wird neu aufgebaut …“) statt aufzulegen – sofern das Gegenüber
  Saga 2.4 nutzt. Mit Saga 2.3 und älter verhält sich ein Anruf genau wie bisher.
- **Technik (saga-call v3):** Signale tragen zusätzlich `pc`, `caps` und `n`; Neuverhandlung nach „Perfect
  Negotiation“ (gleichzeitige Angebote werden sauber aufgelöst) nur mit Gegenübern, die `reneg` melden; unbekannte
  Signaltypen werden ignoriert. Die Prüfung der Fingerabdrücke gilt auch für jede Neuverhandlung.
- **Leistung:** Bilder aus dem Hardware-Decoder werden für das PiP-Fenster nicht kopiert (geprüft in den Logik-Tests,
  rund 13 µs je Bild am Mac). Eine Messanleitung für den Akku steht in `ios/README.md` („Energie messen: Bild im
  Bild“); die Akku- und Wärmewerte werden auf echten Geräten gemessen.

**Android:**
**Bild-im-Bild (PiP):**
- Läuft ein Videoanruf (eigene Kamera oder Bild des Gegenübers), wird der Anruf beim Verlassen der App (Home,
  App-Wechsel) automatisch zu einem kleinen, verschiebbaren Fenster über anderen Apps (Android 12+ automatisch,
  Android 8–11 beim Verlassen). Zurück (Taste/Geste) und „Anruf minimieren“ führen mit Video ebenfalls ins PiP,
  ohne Video wie bisher in den Hintergrund mit der Leiste „Anruf läuft“.
- Das Fenster zeigt nur das Bild des Gegenübers (bzw. seinen Namen) im richtigen Seitenverhältnis – keine
  Chat-Inhalte. Knöpfe „Stummschalten“ und „Auflegen“ direkt im Fenster. Tippen vergrößert wieder zum Anruf.
- Schont Akku und Datenvolumen: Im PiP bittet die App das Gegenüber, sein Video zu verkleinern (höchstens
  300 kbit/s); beim Zurückkehren wieder volle Größe. Gegenstellen mit Saga 2.3 ignorieren das, es ändert sich dort nichts.
- PiP weggewischt: Der Anruf läuft weiter (Leiste „Anruf läuft“), aufgelegt wird nur mit „Auflegen“.

**Anrufe (Grundlage saga-call v3):**
- **Netzwechsel ohne Abbruch:** Wechselt das Telefon zwischen WLAN und Mobilfunk oder reißt die Verbindung kurz ab,
  baut die App die Verbindung neu auf (ICE-Restart, höchstens drei Versuche; kommt sie nach einem Abbruch nicht binnen
  20 s wieder, endet der Anruf wie bisher). Nur wenn beide Seiten Saga 2.4 nutzen und der Server es anbietet – mit
  älteren Apps bleibt alles wie bisher.
- **Bitrate passend zum Weg:** direkt bis 1,5 Mbit/s (720p), über das Relais bis 1,2 Mbit/s, über TURN auf Port 443
  (TCP/TLS) bis 800 kbit/s mit 540p und schmalerem Ton – weniger Ruckeln auf schwachen Verbindungen. Beide Seiten
  teilen sich ihren Weg mit; es gilt der schlechtere.
- **Auflegen** meldet eine 2.4-Gegenstelle sofort („bye“), ihr Anrufbildschirm schließt ohne Verzögerung.
- **Kamera mitten im Anruf einschalten:** Der Vordergrund-Dienst bekommt den Typ „Kamera“ jetzt vor dem Kamerastart.
  Bisher konnte die Kamera stoppen, wenn man nach dem Einschalten die App verließ.
- Neue Signaltypen späterer Versionen werden ignoriert. Neuverhandlungen tragen weiter den signierten
  DTLS-Fingerabdruck; ein anderer Fingerabdruck beendet den Anruf wie bisher als Sicherheitsfehler.
- Akku-Messung im PiP: Anleitung in `docs/android-pip-akku.md`.

**Linux:**
Gespräche laufen weiter im Browser. Mit einem 2.4-Server sagen Klingel-Leiste und
Mitteilung jetzt, was dort geht – Bild-im-Bild und, sobald der Server Bildschirm v2 kann, auch Bildschirm teilen (die
Leiste „… teilt den Bildschirm“ zeigt dann der Browser). Ältere Server: Hinweis wie bisher.

#### Bildschirm übertragen (Etappe E3)
**iOS/iPadOS/macOS:**
- **Bildschirm teilen:** Im verbundenen Anruf gibt es den Knopf „Bildschirm teilen“. Am Mac wählt man im
  Systemfenster einen Bildschirm, ein Fenster oder eine App (Freigabe nur für diese Sitzung, Saga selbst wird nicht
  mitgeteilt). Auf iPhone/iPad startet die Systemübertragung mit „Saga“ – der ganze Bildschirm, auch wenn man in
  andere Apps wechselt; das Bild wird dabei verkleinert (lange Kante höchstens 1920, bei Wärme 1280) und auf 10 Bilder
  je Sekunde begrenzt. Ton des Bildschirms wird nicht übertragen.
- **Bildschirm und Kamera zugleich:** Melden beide Seiten Saga 2.4 mit `screen2`, läuft der Bildschirm als eigene
  Videospur und die Kamera daneben weiter (≤ 300 kbit/s; über TCP/TLS-Relais pausiert sie mit Hinweis „Kamera pausiert
  (langsame Verbindung)“). Mit Saga 2.3 und älter ersetzt der Bildschirm wie bisher im Web das Kamerabild.
- **Empfang:** Der Bildschirm erscheint groß und ganz (nicht beschnitten), die Kamera klein daneben; Tippen auf das
  kleine Bild bzw. „Ansicht tauschen“ tauscht. Bild im Bild (iPhone/iPad) und das Schwebefenster (Mac) zeigen den
  Bildschirm. Bildschirme aus dem Web (2.3 und 2.4) werden erkannt.
- **Datenschutz:** Vor dem ersten Teilen ein Hinweis (alles in der Auswahl ist sichtbar, auch Mitteilungen und
  Passwörter; nichts wird aufgezeichnet oder gespeichert) mit „Nicht mehr anzeigen“. Während des Teilens eine rote
  Leiste „Du teilst deinen Bildschirm“ mit „Teilen beenden“, beim Gegenüber „… teilt den Bildschirm“. Teilen endet
  beim Auflegen und bei Verlust der Verbindung.
- **Leistung:** Bildschirm je Weg mit 2,5 / 1,8 / 1,2 Mbit/s und 15 / 15 / 8 Bildern je Sekunde, Auflösung bleibt
  (Text lesbar). Am Mac gehen die Bilder ohne Kopie an den Encoder; die iPhone-Erweiterung puffert nicht (verwirft
  statt zu stauen) und bleibt so unter ihrer Speichergrenze.
- **Technik:** Neue Erweiterung `HlidskjalfBroadcast` (iPhone/iPad, eigene App-ID `<App-ID>.broadcast` mit derselben
  App-Gruppe, siehe `ios/README.md`). Signale tragen `tracks`/`msid` (Rolle je m-Zeile), `state.screen_mid` und
  `screen_audio`; die Bildschirm-m-Zeile geht beim Ende auf `recvonly` und wird beim nächsten Teilen wiederverwendet.

**Android:**
- **Bildschirm teilen** im laufenden Anruf (neuer Knopf): ganzer Bildschirm oder – ab Android 14 – eine einzelne App.
  Android fragt jedes Mal selbst nach der Erlaubnis; vorher erklärt Saga einmalig, dass das Gegenüber alles in der
  Auswahl sieht (auch Mitteilungen, Passwörter, andere Chats) und nichts aufgezeichnet oder auf dem Server gespeichert
  wird („Nicht mehr anzeigen“ je Gerät).
- Während des Teilens oben eine rote Leiste „Du teilst deinen Bildschirm“ mit „Teilen beenden“; die Anruf-Mitteilung
  zeigt es ebenfalls. Das Teilen endet auch über „Teilen beenden“ in der Statusleiste, beim Auflegen und wenn die
  Verbindung verloren geht.
- **Mit Saga 2.4 auf beiden Seiten** läuft der Bildschirm als eigene Videospur neben der Kamera: Text bleibt scharf
  (Auflösung vor Bildrate, höchstens 1080p und 15 fps, über TURN/TCP 8 fps), die Kamera läuft daneben mit höchstens
  300 kbit/s weiter – über eine langsame TCP-Verbindung pausiert sie („Kamera pausiert (langsame Verbindung)“). Die
  Bildschirm-Spur wird beim nächsten Teilen wiederverwendet, der Verbindungsaufbau wächst nicht.
- **Mit älteren Gegenstellen** (Saga 2.1–2.3) ersetzt der Bildschirm wie bisher im Web die Kamera – Teilen
  funktioniert also mit jeder Gegenstelle.
- **Systemton** (Android 10+): Ton von Videos/Spielen wird in die vorhandene Tonspur gemischt, wo die App es erlaubt;
  „Stumm“ schaltet dann nur das Mikrofon. Der Anrufton selbst wird nie erfasst (kein Echo).
- **Empfang:** Teilt das Gegenüber, ist sein Bildschirm groß und vollständig zu sehen, seine Kamera klein daneben;
  Tippen auf das kleine Bild bzw. „Ansicht tauschen“ tauscht. Rote Leiste „… teilt den Bildschirm“. Im Bild-im-Bild
  ist immer der Bildschirm zu sehen. Kamera und Bildschirm werden über die signierte Zuordnung der Spuren (`tracks`,
  `screen_mid`) erkannt, nicht über die Reihenfolge.
- Vordergrund-Dienst: Typ „mediaProjection“ wird erst nach der Erlaubnis ergänzt (Pflicht ab Android 14) und nach dem
  Teilen wieder entfernt. Bei hoher Gerätetemperatur nur noch 5 fps.

**Linux:**
Anrufe laufen im Browser; die Klingel-Leiste weist auf Bildschirm teilen hin (siehe oben).

#### Gelesen-Bestätigungen und Online-Status
**iOS/iPadOS/macOS:**
- **Haken wie gewohnt:** An eigenen Nachrichten ⏳ wird zugestellt (Chats über Servergrenzen) → ✓ zugestellt → ✓✓
  gelesen; haben alle gelesen, sind die Haken blau. In Gruppen steht neben ✓✓ die Zahl der Lesenden, Tippen auf die
  Haken bzw. „Gelesen von …“ im Kontextmenü zeigt Namen und Zeit – mit dem Hinweis, dass Personen mit abgeschalteten
  Bestätigungen dort nicht erscheinen.
- **Online-Status:** Im Kopf eines Direkt-Chats „online“ bzw. „zuletzt online vor 5 Minuten“ (iPhone/iPad unter dem
  Namen, Mac als Untertitel), in der Chat-Liste ein grüner Punkt am Bild.
- **Einstellungen → Datenschutz:** „Gelesen-Bestätigungen senden“ und „Online-Status zeigen“, standardmäßig an, getrennt
  abschaltbar und gegenseitig (wer etwas abschaltet, sieht es bei anderen auch nicht). Änderungen auf einem Gerät gelten
  sofort auf allen (`chat.privacy`).
- **Ende-zu-Ende:** Jede Bestätigung („gelesen bis Nachricht n“) signiert dieses Gerät; sie wird je anderem Mitglied für
  dessen Backup-Schlüssel versiegelt (saga-chat v1 lokal, v2 über Servergrenzen). Empfangene Bestätigungen werden nur
  gezählt, wenn sie von einem geprüften Gerät der Person stammen und an Chat und Namen gebunden sind. Der Server sieht
  nur, dass es eine Bestätigung gibt.
- **Sparsam:** Bestätigt wird erst, wenn die Nachricht auf dem Bildschirm war, die App vorne ist und der Chat offen –
  höchstens eine je Chat und 2 s (gebündelt). Der Online-Status wird nur für die sichtbare Liste bzw. den offenen Chat
  abonniert (alle 60 s erneuert, im Hintergrund abgemeldet); Live-Ereignisse `chat.receipt` (auch gebündelt mit
  `more`) und `chat.presence` aktualisieren ohne Neuladen.
- **Kompatibel:** Server ohne diese Funktion (bis 2.3) und Chats mit 2.3-Servern (`receipts_ok`/`online_ok`): alles
  ausgeblendet, wie bisher. Texte in Deutsch, Englisch, Spanisch, Russisch und Thai.
- **Tests:** Logik-Tests (`ios/Tests/Logic/ReceiptTests.swift`: Fähigkeiten, Gegenseitigkeit, fällige Bestätigung,
  Haken-Zustände, „Gelesen von“, Abo-Lebenszyklus) und die Testvektoren `tests/vectors/receipts.json` des Servers
  (Kopie `ios/Tests/vectors/receipts.json`) in `ios/Tests/ChatVectors`.

**Android:**
- **Haken wie bei WhatsApp** an eigenen Nachrichten: ⏳ wird zugestellt → ✓ zugestellt → ✓✓ gelesen (farbig).
  In Gruppen ✓✓ farbig, wenn alle gelesen haben, sonst ✓✓ mit Zähler; Antippen der Haken bzw. langes Drücken →
  „Info“ öffnet „Gelesen von“ mit Namen und Zeit (Hinweis: Wer Gelesen-Bestätigungen abgeschaltet hat, erscheint
  dort nicht).
- **Online-Status:** Im Kopf eines Direkt-Chats „online“ bzw. „zuletzt online vor …“ (5-Minuten-Raster), in der
  Chatliste ein grüner Punkt am Profilbild. Nur für Chat-Partner.
- **Einstellungen → Datenschutz:** „Gelesen-Bestätigungen senden“ und „Online-Status zeigen“, standardmäßig an,
  getrennt schaltbar und gegenseitig – wer etwas abschaltet, sieht es bei anderen auch nicht. Änderungen auf einem
  anderen Gerät gelten sofort.
- **Ende-zu-Ende:** Eine Bestätigung ist vom Gerät signiert und je anderem Mitglied für dessen Backup-Schlüssel
  versiegelt (lokal saga-chat v1, über Servergrenzen v2); der Server sieht nur, dass es eine gibt. Beim Empfang
  wird geprüft, dass Gerät und Signatur zur Person gehören. Geprüft mit `tests/vectors/receipts.json`.
- Gesendet wird erst, wenn die Nachricht wirklich auf dem Bildschirm zu sehen war und die App im Vordergrund ist –
  gebündelt, höchstens eine je Chat und 2 s.
- **Akku:** Der Online-Status wird nur für die sichtbare Chatliste bzw. den offenen Direkt-Chat abonniert (alle 60 s
  erneuert) und im Hintergrund sofort abgemeldet.
- Ältere Server bzw. Gegenstellen mit Saga 2.3: Haken, Online-Status und Schalter bleiben ausgeblendet.

**Linux:**
**Haken wie bei WhatsApp:** Eigene Nachrichten zeigen ⏳ (wird zugestellt), ✓ (zugestellt) und ✓✓ in Akzentfarbe
(gelesen). In Gruppen steht neben ✓✓ die Zahl der Lesenden, bis alle gelesen haben; ein Klick auf den Haken (oder
„Gelesen von …“ im Nachrichtenmenü) zeigt, wer wann gelesen hat – mit dem Hinweis, dass Personen mit abgeschalteten
Bestätigungen dort nicht erscheinen. Bestätigungen sind Ende-zu-Ende: vom Gerät signiert und je Mitglied für dessen
Backup-Schlüssel versiegelt (saga-chat v1 lokal, v2 über Servergrenzen); die App prüft jede empfangene Bestätigung
gegen die geprüften Geräte der lesenden Person und verwirft alles andere. Geprüft gegen `tests/vectors/receipts.json`.

**Nur was du wirklich siehst:** Die App bestätigt eine Nachricht erst, wenn das Fenster aktiv, der Chat offen und die
Nachricht im sichtbaren Bereich ist (auch nach dem Scrollen oder wenn das Fenster wieder aktiv wird) – gebündelt,
höchstens eine Bestätigung je Chat und zwei Sekunden, nachgezogen statt verworfen. Nicht bei Nachrichtenanfragen,
schreibgeschützten Chats oder wenn der andere Server es noch nicht kann.

**Online-Status:** Im Kopf eines Direkt-Chats steht „online“ bzw. „zuletzt online vor 5 Minuten“, in der Chat-Liste
zeigt ein grüner Punkt am Bild, wer gerade online ist. Abonniert wird nur, was sichtbar ist (offener Direkt-Chat,
Direkt-Chats der Liste; nicht bei minimiertem Fenster), alle 60 Sekunden erneuert und beim Verlassen abgemeldet.

**Datenschutz-Schalter:** Einstellungen → Chats → „Datenschutz“ mit „Gelesen-Bestätigungen senden“ und „Online-Status
zeigen“ – standardmäßig an, getrennt schaltbar und gegenseitig: Wer etwas abschaltet, sendet es nicht und sieht es bei
anderen auch nicht. Änderungen auf anderen Geräten übernimmt die App live. Server vor 2.4: alles bleibt ausgeblendet,
der Lese-Zeiger funktioniert wie bisher. Texte in Deutsch, Englisch, Spanisch, Russisch und Thai.

#### Videos im Chat (Etappe E2)
**iOS/iPadOS/macOS:**
- **Videos senden:** In der Fotos-Auswahl (jetzt Bilder und Videos) und unter „Datei“ lassen sich Videos anhängen. Sie
  werden nie ganz in den Speicher geladen, sondern als Datei übernommen. Das Gerät erzeugt Poster (Bild bei 1 s, längste
  Seite 320 px, JPEG ≤ 30 KB, als Vorschaubild mit eigenem Schlüssel), Dauer und Abmessungen.
- **„Optimiert (720p)“ oder Original:** Am Anhang wählbar; auf dem iPhone ist „Optimiert (720p)“ Standard
  (`AVAssetExportSession`, Preset 1280×720, `moov` vorn). Es läuft höchstens ein Export gleichzeitig, bei einem zu warmen
  Gerät pausiert er.
- **Große Videos (saga-file/v2):** Bis zur Grenze `file_max` des Servers (Standard 10 MB) bleibt alles wie bisher (v1).
  Größere Videos werden blockweise verschlüsselt (256 KiB je Block, XChaCha20-Poly1305) und in Teilen zu höchstens
  8 MiB hochgeladen – per `URLSession` im Hintergrund, mit sichtbarem Fortschritt über dem Eingabefeld und Abbrechen.
  Nach einem Netzabbruch oder Neustart geht der Upload an der Stelle weiter, an der der Server steht (`HEAD`).
  Schlüssel, Präfix, Datei und Upload-Kennung liegen bis zum Senden im Schlüsselbund. Der Text der Nachricht reist mit
  dem ersten Video.
- **Abspielen:** Im Verlauf steht nur das Poster mit Dauer und Abspiel-Symbol (lazy geladen, kein Player je Zelle).
  Erst beim Antippen startet ein gemeinsamer Player (`AVPlayer` mit eigenem Schema und Resource-Loader): Er holt nur die
  Blöcke, die er braucht (Range mit Medien-Token, bei `401` ein neues), entschlüsselt sie einzeln und kann sofort spulen.
  Der Klartext kommt nie auf die Platte. Der Ciphertext liegt in einem eigenen Cache (512 MB, die am längsten nicht
  benutzten Teile fallen zuerst heraus). Bild im Bild geht, im Mobilfunk startet ein Video erst auf Tippen.
- **Speichern:** Auf iPhone/iPad „In Fotos sichern“ (nur Hinzufügen) bzw. „In Dateien sichern“, am Mac „Sichern …“. Die
  Entschlüsselung läuft als Datenstrom in die Datei, eine temporäre Kopie wird sofort wieder gelöscht.
- **Kompatibel:** v1-Anhänge (auch kleine Videos) funktionieren wie bisher. Server ohne `file2` (bis 2.3) bzw. Chats mit
  einem 2.3-Partnerserver (`big_files: false`): Videos über `file_max` werden mit Hinweis abgelehnt. Anhänge in einem
  neueren Format zeigen „Mit einer neueren Saga-Version öffnen“. „Erneut senden“ (über Servergrenzen) kopiert große
  Videos als Datenstrom. Texte in Deutsch, Englisch, Spanisch, Russisch und Thai.
- **Tests:** Testvektoren `tests/vectors/file_v2.json` (Kopie `ios/Tests/vectors/file_v2.json`) in `ios/Tests/ChatVectors`,
  dazu ein Datenstrom-Test mit 500 MB (Speicher konstant). Logik-Tests (`ios/Tests/Logic/VideoTests.swift`): Kopf/AD/Nonce,
  Block- und Range-Zuordnung, Upload-Teile und -Fortsetzung, Antworten des Servers, Grenzen, Cache-Grenze, 500-MB-Strom.

**Android:**
- **Videos senden:** Anhängen → „Video“ (oder jede Datei vom Typ Video). Standard ist **„Optimiert 720p“**
  (Media3 Transformer, H.264 ~2,5 Mbit/s, lange Seite höchstens 1280 px, nie hochskaliert); „Original“ ist je Video
  wählbar. Poster (Bild bei 1 s bzw. 10 % der Dauer, ≤ 320 px, ≤ 30 KB), Dauer, Abmessungen und Drehung erzeugt das
  Handy selbst; das Poster geht wie Vorschaubilder als eigener v1-Anhang mit eigenem Schlüssel hoch.
- **Hochladen im Hintergrund** (WorkManager, mit Mitteilung „Video wird hochgeladen“, Fortschritt und Abbrechen – auch
  im Chat über dem Eingabefeld). Jedes Video wird eine eigene Nachricht. Bis 10 MB wie bisher (v1), darüber im neuen
  Format **saga-file/v2**: blockweise verschlüsselt als Datenstrom, Upload in Teilen, **fortsetzbar** nach Netzabbruch
  oder App-Neustart (Stand per `HEAD`, auch mitten im Block; Schlüssel und Upload-Kennung bis zum Senden im
  geschützten Speicher). Höchstens ein Export gleichzeitig, bei Überhitzung wird gewartet.
- **Abspielen ohne Komplett-Download:** Media3 ExoPlayer mit eigener DataSource – holt nur die nötigen Blöcke per
  Range mit Medien-Token, entschlüsselt je Block im Speicher (nie Klartext auf der Platte), Spulen sofort. Ein
  gemeinsamer Player je Unterhaltung, an Ort und Stelle oder im Vollbild; beim Wegscrollen wird er freigegeben.
  Ciphertext-Cache auf der Platte (512 MB, LRU, getrennt vom Bildcache), geleert beim Abmelden und „Neu beginnen“.
- **In der Galerie speichern** (Filme/Saga, ab Android 10 ohne Speicherrecht; Android 8/9 „Speichern unter“) –
  entschlüsselt als Datenstrom, auch für Videos mit mehreren hundert MB.
- **Chatliste flüssig mit vielen Videos:** nur das Poster (lazy über den vorhandenen Bild-Loader) mit Dauer und
  Abspiel-Symbol, nie ein Player je Zelle.
- **Kompatibel:** v1-Anhänge unverändert. Server ohne saga-file/v2 (2.3): Videos über 10 MB werden mit Hinweis
  abgelehnt; Chats mit einem 2.3-Server (`big_files: false`) nur bis 10 MB. Grenzen (`file_max`, `video_max`,
  `upload_part`) kommen vom Server. Anhänge in einem neueren Format zeigen „Mit einer neueren Saga-Version öffnen“.
  Erneut senden (nicht zugestellte Nachricht) kopiert große Videos in Teilen statt ganz im Speicher.
- Geprüft mit `tests/vectors/file_v2.json` (Kopf, AD/Nonce, Blöcke, SHA-256, Ablehnungen, Bereiche) sowie Tests für
  Fortsetzen des Uploads, Range-/Blockzuordnung, Cache-Grenze und konstanten Speicher bei 640 MB.

**Linux:**
**Senden in Original-Qualität:** Videos (Dateiauswahl oder Zwischenablage) erscheinen im Verlauf mit Poster, Dauer
und Abspiel-Symbol. Poster (Bild bei 1 s bzw. 10 %, 320 px, JPEG ≤ 30 KB), Dauer, Abmessungen und Drehung erzeugt die
App per GStreamer (Discoverer); das Poster geht wie ein Vorschaubild als eigener v1-Anhang hoch, damit es auch ältere
Apps zeigen. Optional „Optimiert 720p“ (H.264 mit x264enc oder openh264enc, AAC, `moov` vorn) – nur wenn die Encoder
vorhanden sind; sonst wird immer das Original gesendet. Zu große Videos lassen sich dann nur verkleinert senden.

**Große Videos (saga-file/v2):** Bis zur Anhang-Grenze des Servers (10 MB) wie bisher, darüber nur im neuen Format:
blockweise verschlüsselt (256 KiB), als Datenstrom aus der Datei erzeugt und in Teilen hochgeladen – mit Fortschritt
im Anhang und „Hochladen abbrechen“. Nach einem Netzabbruch geht es am Upload-Offset weiter (auch mitten im Block);
Schlüssel, Präfix, Pfad und Upload-Kennung liegen bis zum Senden im Schlüsselbund (sonst in einer 0600-Datei), sodass
erneutes Senden derselben Datei auch nach einem Neustart fortsetzt. Grenzen kommen vom Server (`/api/chat/me` →
`limits`). Server ohne `file2` (2.3) bzw. Gegenüber ohne große Dateien: Videos über 10 MB werden mit Hinweis abgelehnt.

**Abspielen mit Spulen, ohne Komplett-Download:** Antippen öffnet den Player (Gtk.Video mit eigenem GStreamer-Strom,
ohne GL-Zwang). Er liest über einen lokalen Proxy nur auf 127.0.0.1 (zufälliger Port, 128-Bit-Token je Wiedergabe);
die App holt dafür nur die benötigten Blöcke per Range mit Medien-Token und entschlüsselt sie einzeln – Klartext
landet nie auf der Platte. Ciphertext-Zwischenspeicher 512 MB (LRU, getrennt vom Bildspeicher, beim Abmelden und Chat
löschen geleert). In der Chatliste nur Poster (erst wenn sichtbar), nie ein Player. Fehlt GStreamer oder ein Codec:
Hinweis „Herunterladen und extern abspielen“ mit Speichern-Knopf.

**Speichern** per Dateidialog, entschlüsselt als Datenstrom (Block für Block, nie ganz im Speicher). Anhänge in einem
neueren Format zeigen „Mit einer neueren Saga-Version öffnen“. v1-Anhänge funktionieren unverändert. Tests:
Krypto-Vektoren (`tests/vectors/file_v2.json`), Range und Blockzuordnung, Fortsetzen, Zwischenspeicher, gleichbleibender
Speicher beim Hoch- und Herunterladen. Texte in Deutsch, Englisch, Spanisch, Russisch und Thai.

#### Gerät hinzufügen/übergeben und Gruppenanrufe als Mesh (Etappen E4/E5)
**iOS/iPadOS/macOS:**
- **Raum statt Paar:** Ein Anruf hat jetzt je anderem Gerät eine eigene Verbindung (Mesh, `pc` = sortiertes Paar der
  Geräte-IDs). Perfect Negotiation, ICE-Neustart, Bildschirm (Betriebsart A/B je Verbindung, eine später hinzukommende
  Verbindung bekommt den laufenden Bildschirm), Bitrate und die DTLS-Prüfung gelten je Verbindung. Ein 1:1-Anruf läuft
  genau wie bisher. Die App meldet `multi` (nur mit einem Server mit `reneg` und `multi`) in den signierten `caps` und
  per `PUT /api/calls/caps`.
- **Leiste „Anruf läuft auf <Gerät>“:** Läuft ein Anruf auf einem anderen eigenen Gerät, zeigen die anderen eigenen
  Geräte oben eine Leiste mit **Hinzufügen** (Begleitgerät: Mikrofon aus, Wiedergabe stumm, Kamera aus – ohne Frage nach
  dem Mikrofon) und dem eigenen Knopf **Hierher übernehmen**. Kann ein Gerät nicht beitreten, steht dort „Braucht
  Saga 2.4“ bzw. „Der Anruf ist voll“.
- **Hierher übernehmen ohne Abbruch:** Das neue Gerät verbindet sich still mit allen, schaltet dann Ton und Mikrofon ein
  und sagt dem alten Gerät Bescheid (`state.ready` + `replaces`); das alte schaltet sofort ab, verabschiedet sich
  (`bye`, `handover`) und verlässt den Anruf („Anruf auf <Gerät> übernommen“). Klappt es nicht in 15 s, läuft der Anruf
  auf dem alten Gerät weiter. Ein Begleitgerät übernimmt mit demselben Knopf im Anruf. Legt man im 1:1 am Hauptgerät
  auf, endet der Anruf für alle – zum Weitermachen erst übernehmen (Hinweis am Begleitgerät).
- **CallKit:** Beitreten und Übernehmen erscheinen als laufender Anruf; das abgebende Gerät meldet „anderswo
  angenommen“. Ein Gruppenanruf klingelt nicht weiter, sobald ein eigenes Gerät beigetreten ist bzw. die Einladung auf
  einem anderen Gerät abgelehnt wurde (`call.roster`, `call.state declined`). VoIP-Mitteilungen der Art `call.group`
  klingeln wie Anrufe.
- **Gruppenanrufe** in Gruppen-, freien und Seiten-Chats (Knöpfe „Gruppenanruf“/„Gruppen-Videoanruf“, nur wenn der
  Server `chat.call.group` meldet – über Servergrenzen nur, wenn alle beteiligten Server `calls3` können): Erst allein
  („Klingelt bei den anderen …“), alle anderen klingeln und treten bei bzw. lehnen ab; große Gruppen klingeln nicht,
  dort steht nur „Gruppenanruf läuft · n Geräte – Beitreten“. Läuft schon einer, tritt der Knopf bei.
- **Kachelansicht:** 2×2 auf dem iPhone, auf iPad und Mac ab fünf Geräten drei Spalten; der Sprecher (Pegel der
  empfangenen Tonspur) ist grün umrandet. Ein 1:1 mit stillem Begleitgerät bleibt Einzelansicht. Ton eigener Geräte wird
  nie abgespielt. Bild im Bild bzw. das Schwebefenster zeigt in Gruppen den aktiven Sprecher.
- **Mesh-Grenzen und Bitrate:** höchstens 4 Geräte mit Video bzw. 6 nur mit Ton (der Admin kann nur senken); ab drei
  Endpunkten Mesh-Werte je Strom (600/500/350 kbit/s, ≤ 540p bzw. 360p15). Die Ansicht zeigt „n von höchstens N Geräten“
  und Personen ohne Saga 2.4.
- **Sicherheit:** Angebote neuer Geräte werden nur angenommen, wenn das Gerät in der Liste des Servers **und** in der
  signierten Geräteliste seiner Person steht (eigene Geräte gegen den eigenen Identitätsschlüssel); sonst „Unbekanntes
  Gerät von `<Name>` abgelehnt“. Jede Verbindung zeigt Person und Gerät. Teilnehmerlisten mit Stand – eine ältere Liste
  überschreibt keine neuere.
- **Tests:** `ios/Tests/Logic/CallRoomTests.swift` (Teilnehmerliste/Stand, Mesh-Paare, Grenzen, Begleitgerät,
  Übernahme-Zustandsmaschine, Mesh-Bitrate, Leiste, Gruppen-Knopf, Sprecher, Geister-Gerät). Texte in Deutsch,
  Englisch, Spanisch, Russisch und Thai.

**Android:**
- **Anruf als Raum:** je Gerätepaar eine eigene PeerConnection (`pc` = sortiertes Paar der Geräte), DTLS-SRTP je Paar
  mit signierten Fingerabdrücken – Ende-zu-Ende wie bisher, der Server sieht keine SDP. Neuverhandlung (Perfect
  Negotiation), ICE-Restart, Bild-im-Bild-Wunsch und Bildschirm (Betriebsart A/B) laufen je Verbindung; eine später
  hinzukommende Verbindung bekommt den laufenden Bildschirm. Signale gehen wie bisher über den eigenen Server
  (föderiert sternförmig über den führenden Server). Das Gerät meldet `multi` (nur, wenn der eigene Server `reneg` und
  `multi` kann) in den signierten `caps` und per `PUT /api/calls/caps`.
- **Leiste „Anruf läuft auf <Gerät>“** auf anderen eigenen Geräten mit **Hinzufügen** und **Hierher übernehmen**;
  Gruppenanruf ohne eigenes Gerät: „Gruppenanruf läuft · Geräte: n“ mit **Beitreten**. Geht es nicht, steht der Grund
  da („braucht Saga 2.4“, „Der Anruf ist voll“).
- **Begleitgerät:** tritt ohne Mikrofon, ohne Ton und ohne Kamera bei (es fragt nicht nach dem Mikrofon); eigene Knöpfe
  „Ton an/aus“ und **„Hierher übernehmen“**. Ton eigener Geräte wird nie abgespielt.
- **Übernahme ohne Abbruch:** das neue Gerät verbindet sich still mit allen, schaltet erst dann Mikrofon und Ton an
  und meldet `state.ready` an das alte Gerät; das alte schweigt sofort, schickt `bye` (`handover`), verlässt den Anruf
  und zeigt „Anruf auf <Gerät> übernommen“. Klappt es nicht binnen `handover_s` (15 s), bleibt der Anruf auf dem alten
  Gerät. Im 1:1 beendet Auflegen am Hauptgerät den Anruf für alle; ein Begleitgerät verlässt ihn nur selbst.
- **Klingeln:** das zweite eigene Gerät hört auf zu klingeln, sobald eines angenommen hat bzw. (Gruppe) sobald die
  Teilnehmerliste ein eigenes Gerät zeigt oder die Einladung anderswo abgelehnt wurde.
- **Gruppenanrufe** in Gruppen-, freien und Seiten-Chats (Knopf nur, wenn der Server `chat.call.group` meldet):
  Klingeln für alle Mitglieder (`call.invite`, Mitteilung `call.group`; große Gruppen still), Beitreten, Verlassen.
  **Kachelansicht** mit Name und Gerät, der aktive Sprecher (Pegel der empfangenen Tonspur) ist hervorgehoben; im
  Bild-im-Bild läuft der aktive Sprecher. Ab drei Geräten Mesh-Bitrate je Strom (600/500/350 kbit/s, ≤ 540p bzw.
  360p15, `want: small` 200 kbit/s). Anzeige „Geräte: n von m“ und Personen ohne Saga 2.4.
- **Grenzen:** höchstens 4 Geräte mit Video bzw. 6 nur Ton (eigene Zusatzgeräte zählen mit); der Admin kann nur senken.
- **Teilnehmerliste mit Stand (`rv`):** eine ältere Liste überschreibt keine neuere; nur Live-Ereignisse, `bye` und
  `alive` (`leg: "left"`) trennen Verbindungen. Unbekannte Geräte (nicht in der signierten Geräteliste der Person)
  werden abgelehnt und angezeigt.
- **Kompatibel:** mit 2.1/2.3-Servern und -Geräten bleibt alles 1:1 wie bisher (ohne `multi` keine Raumfunktionen).
- Tests: Teilnehmerliste/rv, Mesh-Paare, Grenzen, Begleitgerät, Übernahme, Bitrate im Mesh, Sprecher, Leiste.

**Linux:**
- **Gruppenanrufe klingeln:** Die App behandelt `call.invite` (und Push `call.group`): Leiste und Mitteilung „Gruppenanruf
  in `<Gruppe>`“ bzw. „`<Name>` hat einen Gruppenanruf gestartet“ mit **Beitreten (im Browser)** (`/chats#join-<id>-member`,
  bei Video auch „Nur Ton“) und **Ignorieren**. Große Gruppen (`silent`) klingeln nicht, dort steht nur „Gruppenanruf
  läuft“. Das Klingeln endet, sobald ein eigenes Gerät in der Teilnehmerliste steht (`call.roster`), die Einladung
  anderswo abgelehnt wurde (`call.state declined`) oder der Anruf endet; „aktiv“ (zwei sind dabei) beendet es nicht.
  Ein beim Start laufender Gruppenanruf mit offener Einladung klingelt ebenfalls (`GET /api/calls/active`).

### Neu – Android 2.4: Datenbanken, Editor, Seiten und Termine

#### Datenbanken: Ansichten, Filter und Bearbeiten
**Android:**
- **Ansichten wie im Web und auf iOS:** Tabelle (Spalten waagrecht scrollbar, Gruppen), Board (Spalten nach einer
  Auswahl-Eigenschaft, Karten per Menü oder Bedienungshilfen-Aktion „Verschieben nach …“ in eine andere Spalte),
  Liste (Häkchen direkt antippbar), Galerie (Titelbild bzw. erstes Bild der Seite) und Kalender (Monat, Tag antippen
  zeigt seine Einträge, „Neuer Eintrag am …“). Gespeicherte Ansichten wie „Offen“ zeigen genau ihre Filter und ihre
  Sortierung; die zuletzt gewählte Ansicht bleibt je Datenbank gemerkt.
- **Ansichten anlegen, umbenennen, löschen, Layout wechseln**, Gruppierung bzw. „Spalten nach“/„Datum aus“ wählen und
  Eigenschaften ein-/ausblenden. **Filter** (alle Operatoren des Webs: enthält, ist, =, >, <, am/vor/nach, heute,
  diese Woche, diesen Monat, leer, angehakt …) und **Sortierung** (bis zu drei Stufen) bearbeiten; gespeichert wird im
  Schema mit `srev`, sodass nichts verloren geht, was jemand anderes inzwischen angelegt hat. Gesperrte Datenbanken und
  Rolle „Lesen“/„Kommentieren“ zeigen alles, ändern aber nichts.
- **Neue Einträge** mit „Neuer Eintrag“, in einer Board-Spalte oder an einem Kalendertag; Werte aus dem Filter der
  Ansicht werden übernommen („Status ist Offen“ → Status = Offen). Der neue Eintrag öffnet sich als Seite.
- **Eigenschaften bearbeiten**, alle Typen des Servers: Text, Zahl (Eingabe in der Schreibweise der Sprache wie im
  Web), Auswahl und Mehrfachauswahl (mit Suche und „… anlegen“ für neue Optionen), Datum, Häkchen, URL, Relation
  (Einträge der Ziel-Datenbank suchen); „Berechnet“, „Erstellt“ und „Zuletzt bearbeitet“ werden angezeigt. Das geht
  in Tabelle/Liste/Board und auf der Seite eines Eintrags (bisher nur lesbar).
- **Offline und Konflikte wie bei Seiten:** Werte gelten sofort; ohne Netz bleiben sie in einer Warteschlange auf dem
  Gerät (je Konto, übersteht Neustarts) und werden nachgeholt, sobald der Server erreichbar ist. Der Server führt
  Werte je Eigenschaft zusammen – Änderungen anderer an anderen Eigenschaften bleiben erhalten. Lehnt der Server einen
  Wert ab (z. B. Option inzwischen gelöscht), erscheint eine Meldung und der Stand des Servers. Ohne Netz zeigt jede
  Ansicht ihren letzten bekannten Stand („Offline – zuletzt bekannter Stand“); der Zwischenspeicher wird beim
  Abmelden gelöscht. Live-Änderungen anderer Geräte laden die Ansicht neu.
- **Große Datenbanken:** Filter, Sortierung, Suche und Gruppen wendet der Server an (`/rows` mit `view`, `filters`,
  `sorts`, `q`, `group`, `limit`/`offset`, `tz`); die App lädt 100 Zeilen je Seite (Board: 50 je Spalte, Kalender: nur
  der angezeigte Monat) und beim Scrollen nach – nie alle 1000 auf einmal. Die Zeilen stehen direkt in der Liste der
  Seite (LazyColumn, keine verschachtelten Listen). Ältere Server ohne diese Parameter liefern alle Zeilen; dann
  filtert und sortiert die App selbst genau wie das Web (1000 Zeilen lokal in wenigen Millisekunden, Unit-Test).
- **Barrierefreiheit, hell/dunkel, Tablet:** Zellen werden als „Eigenschaft: Wert“ vorgelesen, Häkchen als
  Kontrollkästchen, Gruppen- und Spaltenköpfe als Überschriften; Datenbank-Seiten nutzen auf Tablets die volle Breite
  (Galerie mit vier Spalten). Texte in Deutsch, Englisch, Spanisch, Russisch und Thai.

#### Editor, Seiten und Termine
**Android:**
- **„/“-Menü im Editor:** „/“ am Anfang eines Absatzes öffnet das Block-Menü mit Suche (auch Suchwörter auf
  Deutsch, Englisch, Spanisch, Russisch und Thai), Return nimmt den ersten Treffer. „+“ in der Werkzeugleiste zeigt
  dieselbe Liste: Text, Überschrift 1–4, Listen, Zitat, Hinweis, Ausklappbar, Code, Trenner, Bild, Bild aus Immich,
  Tabelle, 2 oder 3 Spalten, Datei, Einbetten (Video/Link), Verweis auf Seite, Unterseite, Datenbank, verknüpfte
  Datenbank, Aufgaben, Kalender und Nextcloud-Datei – jeweils nur, wo Server, Rolle und Verbindungen es erlauben.
- **Neue Blöcke:** Bild aus Galerie oder Kamera (hochladen, Bildunterschrift, „Volle Breite“, „Klein mit
  Hintergrund“, „Rahmen“), **Tabelle** (Zellen bearbeiten, Zeilen/Spalten einfügen und löschen, Kopfzeile),
  **Spalten** (2/3, „+ Block“ je Spalte; beim Verringern wandern die Blöcke in die letzte Spalte, nichts geht
  verloren), Datei als Karte, Aufgaben- und Kalender-Block mit Inhalt auf eigenen Seiten.
- **Block löschen mit „Rückgängig“:** Der Block kommt unverändert an dieselbe Stelle zurück.
- Fremde und unbekannte Blöcke bleiben weiter unverändert erhalten (Tests: Round-Trip aller Blocktypen, Tabelle,
  Spalten, „/“ auf großen Seiten). Nach einem Trenner landet der nächste neue Block dahinter (vorher davor).
- **Seiten verschieben** (Seitenmenü und Langdruck in der Seitenleiste): Ziel im Baum mit Suche, auch auf die oberste
  Ebene. Geteilte Seiten mit Rolle „Bearbeiten“ lassen sich innerhalb ihres geteilten Bereichs verschieben; vor dem
  Verschieben in eine geteilte Seite fragt die App nach (Freigaben ändern sich).
- **Papierkorb** in der Seitenleiste: gelöschte Seiten ansehen (mit Unterseiten und Frist), wiederherstellen,
  endgültig löschen und leeren – jeweils mit Rückfrage.
- **Termine anlegen, bearbeiten und löschen** (CalDAV über den Server wie im Web): Titel, ganztägig, Beginn/Ende,
  Kalender, Ort, Beschreibung. Serientermine und nur lesbare Kalender bleiben nur lesbar.
- **Eigene Lesezeichen** unter „Dienste“ anlegen, bearbeiten (lange drücken) und löschen.
- **Leistung:** Seiten mit 500 Blöcken bleiben flüssig – beim Tippen wird nur der geänderte Block neu aufgebaut
  (unveränderte Blöcke bleiben dieselben Objekte, geprüft im Unit-Test).

### Behoben – Testrunde 2.4

#### Alle Apps (Server-Änderungen der Testrunde)
- **Anmeldenamen anderer Konten werden nie mehr gezeigt** (wie im Web, Server 2.4): Personensuche beim Teilen und für
  Chats, Freigaben (auch geerbte), Mitgliederliste und „Person hinzufügen“ zeigen nur Anzeigename bzw. öffentlichen
  Namen; die Zeile darunter sagt „Person“, „Person auf diesem Server“ oder „hat Chats noch nicht eingerichtet“. Der
  Server liefert für andere Konten einen pseudonymen Handle (`~…`), die Apps reichen ihn unverändert weiter
  (Freigabe ändern/entfernen, Chat anlegen, Mitglied hinzufügen); Mitglieder werden über das neue Feld `members[].ref`
  mit Suchtreffern abgeglichen. Schlüssel, Signaturen und Sicherheitsnummern bleiben am Anmeldenamen (Chats v1,
  saga-call v1); ältere Server funktionieren wie bisher.
- **iOS/iPadOS/macOS und Android: „Hinzufügen“ und „Hierher übernehmen“ mit einem während des Anrufs gekoppelten
  Gerät:** Die Gegenstelle kannte dessen Schlüssel noch nicht (Schlüssel der Person zwischengespeichert, Android bei
  Personen anderer Server) und lehnte es als „Unbekanntes Gerät“ ab. Fehlt ein Gerät, holen die Apps die signierte
  Geräteliste jetzt einmal frisch, prüfen sie und verbinden erst dann – sonst wie bisher ablehnen.
- Tests: `ios/Tests/Logic/HandlesTests.swift`, `android/…/HandlesTest.kt`, `linux/tests/test_handles.py`.

#### Alle Apps – Server-Vertrag nach der zweiten Runde
- **Chat-Schlüssel nur für Chat-Partner:** `GET /api/chat/keys` liefert ab Server 2.4 nur noch die eigenen Schlüssel und
  die von Personen mit gemeinsamem Chat, nimmt auch Handles und antwortet unter der angefragten Kennung. Geprüft: Alle
  drei Apps legen neue Unterhaltungen, Nachrichtenanfragen und Gruppen zuerst an (`POST /api/chats`) und versiegeln
  danach mit den Empfängern des angelegten Chats (wie das Web); Schlüssel holen sie nur für Mitglieder
  (Sicherheitsnummer) und das Anruf-Gegenüber. iOS/iPadOS/macOS und Android lesen die Antwort jetzt ausdrücklich unter
  der angefragten Kennung (Kennungen URL-kodiert); Linux fragt den Endpunkt nicht ab. Bestehende Chats unverändert.
- **Gleichnamige unterscheidbar (iOS/iPadOS/macOS und Android, Linux schon in Runde 2):** Personensuche für Chats,
  „Person hinzufügen“, Mitgliederliste und Teilen zeigen unter dem Namen das Merkmal `sub` vom Server (öffentlicher
  Name bzw. Handle `~…`), nie den Anmeldenamen; ältere Server ohne Feld wie bisher.
- **Öffentlicher Name:** Der Server nimmt den eigenen Anmeldenamen nicht mehr an – die Apps zeigen seine Meldung am
  Feld. Ist ein früher gewählter Name der Anmeldename (`is_login`), steht unter dem Feld ein Hinweis, einen anderen zu
  wählen (alle drei Apps, de/en/es/ru/th).
- **Geprüft, ohne Änderung:** Zweiten Faktor entfernen (Bestätigung über `/api/account/security/confirm`) – keine App
  verwaltet TOTP/Passkeys selbst (nur Anzeige, Verwaltung im Web). Nextcloud-Blöcke: fremde Autoren kommen als Handle;
  die Apps vergleichen Block und Status über denselben Wert, reichen ihn als `?a=` weiter und erkennen eigene Blöcke
  am eigenen Namen – passt.
- Tests: `ios/Tests/Logic/ContractTests.swift`, `android/…/Contract24Test.kt`, `PublishTest.kt`,
  `linux/tests/test_contract24.py`.

#### iOS/iPadOS/macOS
- **Papierkorb: „Zurückholen“ löschte die Seite endgültig (iPhone/iPad, seit 1.0):** Zwei Knöpfe in einer Listenzeile
  ohne eigenen Stil lösten bei einem Tipp beide Aktionen aus. Jeder Knopf ist jetzt einzeln tippbar, „Endgültig
  löschen“ fragt vorher nach (mit Titel und Zahl der Unterseiten), die Zeilen sind zweizeilig und nennen die
  Elternseite. Dasselbe Muster an acht weiteren Stellen behoben (u. a. „Kopieren“/„In dieser App verwenden“ bei
  App-Schlüsseln, neuer Wiederherstellungsschlüssel „Übernehmen“/„Abbrechen“, Mein Backup, Nextcloud). Eine Prüfung in
  den Logik-Tests durchsucht alle Ansichten nach diesem Muster.
- **Datenbank: „Filter, Sortierung, Eigenschaften“ und Spalten-Einstellungen** öffnen auf iPhone/iPad als Blatt mit
  „Fertig“ (das Popover war 36 pt breit und leer); Filterzeilen sind auf dem iPhone zweizeilig.
- **Editor auf dem iPhone:** Die Schreibzeile wird über die Tastatur geholt, das „/“-Menü liegt angedockt über der
  Tastaturleiste. Schnelles Tippen beim Zeilenwechsel (Return in Liste/Checkliste/Absatz) verliert keine Zeichen mehr:
  Eingaben während des Fokuswechsels landen im neuen Feld.
- **„Umwandeln in“** gibt es nur noch für Textblöcke (Bild → Text löschte das Bild); geht beim Umwandeln etwas
  verloren (Unterpunkte, Inhalt eines Aufklapp-Blocks), fragt die App vorher.
- **Seitenverweise zeigen den aktuellen Titel** (wie im Web) – eine per „/Seite“ angelegte Unterseite blieb sonst
  „Unbenannt“. Nur aktualisierte Titel lösen kein Speichern aus.
- **Mein Backup → „Verwalten …“** öffnet die Verwaltung (schloss vorher die Einstellungen).
- **Gruppenanruf-Knöpfe** erscheinen gleich nach dem Entsperren der Chats, nicht erst nach einem Neustart.
- **Leiste „Anruf läuft auf …“** verdeckt die Navigationsleiste nicht mehr; der Gerätename darf zweizeilig sein.
- **Große Schrift:** Datenbank-Spalten wachsen mit (kein silbenweiser Umbruch, keine Überlappung), die Chatliste zeigt
  Zeit und Zähler unter dem Namen.
- **Trenner** als waagerechte Linie, **Zitat-Quelle** wird angezeigt, **Dienste** ohne rotes „Abgebrochen“ und mit
  Leerzustand, Seite nach „In den Papierkorb“ wird geschlossen, Anmeldung: Fehlermeldung direkt unter dem Feld und
  Sprachknopf bei offener Tastatur ausgeblendet, Nextcloud: ungültige Adresse wird als solche gemeldet, „Los“ auf der
  Tastatur verbindet.
- **Suche `name@server`:** Hinweis, dass dabei der öffentliche Name zählt (nicht der Anmeldename).
- **Entwickler-Builds** (Debug) erlauben http auch zu privaten Netzen (z. B. Testserver im LAN); Release unverändert
  nur https.
- Version der Apple-Apps auf 2.4.0; Swift-6-Warnung im Live-Kanal behoben.

#### iOS/iPadOS/macOS – zweite Runde
- **Editor verliert beim schnellen Tippen keine Zeichen mehr (Datenverlust, iPhone/iPad/Mac):** Nach einem Return
  gingen Zeichen und Zeilen verloren oder landeten im falschen Punkt („Mittel:⏎“ → „Mitte“ + leere Absätze,
  „Punkt B“ → „Punk“), und der Server speicherte den kaputten Stand. Ursachen: mehrere Tastendrücke in einer Änderung
  wurden am falschen Zeichen getrennt; das alte Feld setzte sich nach dem Weiterreichen zurück und verschluckte die
  nächste Eingabe; spät Weitergereichtes blieb liegen; eigene Änderungen wurden als Eingabe gedeutet. Jetzt rechnet
  jedes Feld gegen den zuletzt verbuchten Stand (`EditorFlow`), getrennt wird genau am Zeilenumbruch, und ein Feld,
  das den Fokus schon abgegeben hat, reicht nur noch Neues an das neue Feld weiter (auch Rückschritt). Return mitten
  im Text setzt den Cursor im neuen Block an den Anfang.
- **Doppeltes Return am Ende einer Liste bzw. „Typ → Text“:** Die Tastatur ging zu und weiter Getipptes war weg. Der
  leere Punkt wird erst entfernt, wenn der neue Absatz den Fokus hat; ein eben eingefügtes Feld holt sich den Fokus
  mehrmals, statt das Bearbeiten beim ersten Fehlschlag zu beenden.
- **„/“-Menü:** feste Höhe aus der Trefferzahl – ein einzelner Treffer („/Seite“) liegt nicht mehr halb unter der
  Tastaturleiste. **Return wählt den ersten Treffer** (auch am Mac).
- **„/“-Menü bei schnellem Tippen (Tipptest nach dem Zusammenführen):** Kamen „e⏎“ in einer Änderung an oder wurde
  „/Seite⏎“ direkt nach einem Return getippt (Weiterreichen ans neue Feld), entstand ein Absatz „/Seite“ statt der
  Unterseite. Gezählt wird jetzt die Zeile vor dem Return, auch bei weitergereichter Eingabe. „/Checkliste⏎eins“ in
  einem Zug verlor Zeichen („e“ statt „eins“), weil das Feld des Absatzes mit der Tastatur verschwand: Die Liste kommt
  jetzt hinter den Absatz, der erst geht, wenn der Listenpunkt den Fokus hat. Echter Tipptest im Simulator (XCUITest,
  200 Zeichen, mehrfaches Return, Checkliste mit Return-Folgen, „/Seite⏎“) speichert und zeigt den Text exakt.
- **Leiste „Anruf läuft auf …“:** „Hinzufügen“ und „Hierher übernehmen“ werden nie mehr silbenweise umbrochen; ist es
  zu eng (iPhone, große Schrift), stehen die Knöpfe unter dem Text.
- **Große Schrift:** Der Ansichtsname einer Datenbank steht in eigener Zeile statt nur „…“.
- **Datenbank:** Ein aktiver Filter ist am Regler-Symbol zu sehen (Akzentfarbe und Zahl, auch für VoiceOver).
- **Teilen:** Mit 1–2 Zeichen ohne exakten Treffer erscheint ein Hinweis auf 3 Zeichen statt „Niemand gefunden.“
- **Termin anlegen ohne Kalender:** Hinweis auf die eigene Kalender-Einstellung der App (nicht mehr „im Web“), Zeile
  „Kalender“ mit „Keiner verbunden“ und Knopf „Kalender verbinden …“.
- **Ganze Zeile antippbar:** „Papierkorb“ in der Seitenleiste und die Ziele in „Verschieben nach“.
- **Papierkorb:** „Endgültig löschen“ und „Papierkorb leeren“ fragen mit sichtbarem „Abbrechen“ nach. Hinweis
  „… in den Papierkorb verschoben“ zweizeilig und über der Suchleiste.
- **Mein Backup:** „Jetzt sichern“ und „Backups im Ziel anzeigen“ in eigenen Zeilen.
- **Chats:** Wiederherstellungsschlüssel-Feld gleich bereit zum Tippen; Chatliste auf dem iPad quer breiter, Unterzeile
  zweizeilig statt „Direkt-Chat · a…“.
- **VoiceOver:** Die Zeile „Sprache“ liest „Sprache, Deutsch“ mit Hinweis statt „Sprache, Deutsch, Sprache“.
- Tests: `ios/Tests/Logic/EditorFlowTests.swift` mit Nachbildung der Tippfolgen aus dem Testbericht (Verzögerung des
  Fokuswechsels 0–6 Tasten, 1–4 Tasten je Änderung).

#### Android
- **Video im Vollbild speichern:** Schließen, Dateiname und „Speichern“ lagen unter der Statusleiste und ließen sich
  nicht antippen – Videos konnten in der App gar nicht gespeichert werden. Kopfzeile und Bedienung halten jetzt Abstand
  zu Statusleiste, Kameraaussparung und Navigationsleiste. Gespeichert wird wie bisher ohne Berechtigung in die Galerie
  (Filme/Saga, Android 8/9: „Speichern unter“); „Gespeichert“ bzw. der Fehler erscheint jetzt auch im Vollbild.
- **Mitteilungen früh erlauben:** Beim ersten Öffnen der Chats nach der Anmeldung erklärt Saga einmal kurz, wofür
  Mitteilungen gebraucht werden (Nachrichten, Anrufe, Upload-Fortschritt), und fragt dann – nicht mehr erst beim ersten
  Anruf. Ohne Erlaubnis steht der Upload-Fortschritt weiter im Chat; nach einem Verbindungsabbruch heißt es dort
  „Wartet auf Verbindung …“ statt „Video wird vorbereitet …“.
- **Gelesen-Bestätigungen:** Bilder, Videos und Dateien zählen als gesehen, sobald ihre Zeile sichtbar war – auch wenn
  ihre Position erst danach ankommt; eine eigene Nachricht ohne Position zählt als gelesen, wenn die Bestätigung sie
  oder eine spätere Nachricht nennt (Bild blieb über 60 s bei ✓). Die „Gelesen“-Zeit älterer Nachrichten springt beim
  Öffnen und bei neuen Bestätigungen nicht mehr weiter: Es gilt die Zeit der ersten Bestätigung, die die Nachricht
  abdeckt (Zeit aus der signierten Bestätigung, nicht die Uhr des Geräts); die App merkt sich dafür nur Positionen und
  Zeiten und sendet beim erneuten Öffnen keine neue Bestätigung für schon Bestätigtes.
- **Version:** Die Apps tragen jetzt 2.4.0 (Vorabversionen „2.4.0-rc.N“ statt „2.3.1-rc.N“). Die Serverzeile zeigt,
  was der Server unter `/api/version` meldet.
- **Teilen über Servergrenzen:** `name@server` findet eine Person nur über ihren öffentlichen Namen. Ohne Treffer sucht
  die App zusätzlich im Adressbuch dieses Servers nach dem Namen und zeigt nur dessen Treffer (z. B. Nora, mit der
  schon ein Chat läuft).
- **QR-Anmeldung ohne Kamera:** verständliche Meldung statt Ausnahme-Text und der Knopf „Link einfügen“
  (Kopplungslink aus der Zwischenablage).
- **Anzeige und Bedienungshilfen:** „Benachrichtigungen“ in Seiten- und Navigationsleiste wird nicht mehr mitten im
  Wort umbrochen (Schrift passt sich an, notfalls „…“); das Anmeldefenster im Browser erscheint in der Sprache der App;
  „Neuer Chat“ hat eine Beschreibung für TalkBack.
- **Testbuild (Debug):** erlaubt http zu Testservern im privaten Netz (192.168.x.x, 10.x.x.x, 172.16–31.x.x, *.local,
  localhost/Emulator). Ein abgewiesener Kopplungslink wird nicht mehr still ignoriert, sondern mit Grund gemeldet.
  Release unverändert: nur https.
- **Versionsverlauf:** „Diese Version wiederherstellen“ fragt jetzt nach (Datum des Stands) und warnt ausdrücklich,
  wenn der gewählte Stand leer ist. Die Liste zeigt, was der Server liefert (dass nur der leere Anfangsstand erschien,
  behebt der Server).
- Tests: Lesezeit-Historie, Position aus der Reihenfolge, private Netze, Sprache im Anmeldelink, Adressbuch-Ausweichsuche,
  Mitteilungs-Abfrage, Kamera-Meldung.

#### Android – zweite Testrunde
- **Tabelle nach „+ Spalte“ (Datenverlust-Gefahr):** Die Zellfelder waren nur nach ihrer Position gemerkt. Nach dem
  Einfügen einer Spalte (oder Zeile) behielt das Feld an der alten Position seinen eigenen getippten Text und zeigte ihn
  weiter an – wer dort weiterschrieb, änderte eine andere Zelle als die angezeigte. Die Felder setzen jetzt neu auf,
  sobald sich der Aufbau der Tabelle ändert; die Anzeige entspricht immer dem Gespeicherten, die Schreibmarke bleibt in
  ihrer Zelle.
- **Erster Anruf auf einem frischen Gerät ging verloren:** Beim Annehmen kamen nacheinander die Abfragen für Mikrofon
  und Mitteilungen. Während eines Anrufs fragt Saga jetzt nie nach Mitteilungen (auch der Erklär-Dialog wartet bis nach
  dem Anruf); beim Annehmen nur nach dem Mikrofon (bei Video auch Kamera). Während der Abfrage klingelt es weiter, das
  Zeitlimit wartet (höchstens 60 s), angenommen wird erst nach der Antwort; war der Anruf inzwischen beendet, sagt die
  App das.
- **Zurück nach der Anmeldung** öffnete die alte Anmeldeseite im Browser-Tab („Die Seite war zu lange offen“). Nach der
  Rückkehr holt sich die App mit CLEAR_TOP nach vorn und schließt so den Anmelde-Tab.
- **Geteilte Seite beim Leser:** Tabellen mit leeren Zellen waren verschoben (leere Zellen flacher, Linien mitten durch
  Zellen); alle Zellen einer Zeile sind jetzt gleich hoch.
- **Videos:** Anhänge ohne echten Namen („1000000020.mp4“ aus der Foto-Auswahl) heißen jetzt nach Art, Datum und Chat,
  z. B. „VID_20261020_1130_Nora-Vogel.mp4“; echte Dateinamen bleiben. Langdruck auf ein Video bietet „Speichern“ und
  „Vollbild“, das Nachrichten-Menü „Video speichern“. Der Player zeigt keine „Zurück/Weiter“-Knöpfe mehr; ist die
  Fassung „optimiert 720p“ größer als das Original, wird das Original gesendet.
- **Anruf: „Kamera an“ ohne Kamera** meldet „Dieses Gerät hat keine Kamera.“; im Editor wird „Foto aufnehmen“ auf
  Geräten ohne Kamera nicht angeboten.
- **Bedienungshilfen:** Alle schwebenden Knöpfe („Neuer Termin“, „Lesezeichen hinzufügen“, „Neuer Chat“) tragen ihren
  Namen; Bilder im Editor ohne Unterschrift heißen „Bild“; Aufgaben-Haken nennen Text und Aktion („Erledigen“/„Wieder
  öffnen“); „⋯“ unter Tabellen heißt „Weitere Tabellen-Aktionen“.
- **Kleineres:** „Block gelöscht · Rückgängig“ wird nicht mehr nach ~3 s von der nächsten Meldung verdrängt (Meldungen
  ohne Aktion kommen danach); Fehlermeldung beim Prüfen der Server-Adresse nicht mehr doppelt („Server nicht
  erreichbar: Server nicht erreichbar“); Sortieren-Dialog zeigt Eigenschaft und Richtung untereinander in voller Breite.
- **Geprüft, ohne Änderung:** Die gespeicherte Ansicht „Offen“ zeigte alle Einträge, weil sie auf dem Teststand ohne
  Filter gespeichert ist (Demo-Daten; Filter- und Sortierdialog leer). Die App liest Filter und Sortierung gespeicherter
  Ansichten und schickt sie mit (`view`, `filters`, `sorts` an `/rows`). „Dienste“ sind Einstellungen des Servers;
  eigene Lesezeichen legt die App wie das Web an.
- Tests: `android/…/Tt24AndroidR2Test.kt` (Ursache und Behebung des Tabellenfehlers, Berechtigungen beim Annehmen,
  gespeicherte Filter, Dateinamen, Meldungen).

#### Linux
- **Fokus nach Tabelle bzw. Datenbank (Datenverlust):** Nach dem Einfügen sprang die Schreibmarke an den Seitenanfang,
  Getipptes landete in der ersten Überschrift. Jetzt steht sie in der ersten Zelle der Tabelle bzw. in einem (leeren)
  Absatz nach Datenbank, Aufgaben, Kalender, Schaltfläche und Bild; Tabellenzellen behalten den Fokus auch nach „Zeile“/
  „Spalte“. Was getippt wird, während der Server die Datenbank anlegt, bleibt erhalten.
- **„/“-Menü:** zeigt alle Blocktypen (bisher 12 von 26) und rollt mit den Pfeiltasten mit. Der Name zählt vor dem
  Stichwort wie im Web: „/tabelle“ legt eine Tabelle an, „/kalender“ den Kalender-Block (bisher jeweils eine
  Datenbank); „/überschrift 2“ mit Leerzeichen geht.
- **Gelesen ✓✓:** Neue Nachrichten im offenen, sichtbaren Chat werden nach dem Anordnen bestätigt (bisher erst beim
  Rollen oder Fensterwechsel); meldet die Chat-Liste den offenen Chat noch als ungelesen, wird er gleich wieder gelesen
  gemeldet (Zähler, Glocke). Nach dem Annehmen einer Anfrage wird der Online-Status abonniert.
- **Version wiederherstellen** nur nach Rückfrage („Der aktuelle Inhalt der Seite wird durch die Version vom … ersetzt“).
- **Block löschen** (Menü bzw. Entf auf einem ausgewählten Block) zeigt „Block gelöscht“ mit **Rückgängig**.
- **Seiten im Papierkorb:** Eine offene Seite, die (auch als Unterseite) in den Papierkorb ging, ist gesperrt und zeigt
  „Diese Seite liegt im Papierkorb …“ mit **Zurückholen**; „Zurück“ führt nie auf eine solche Seite. Meldet das Speichern
  404, sagt die App das deutlich, behält die Eingaben als Entwurf und speichert sie nach dem Zurückholen.
- **Seitenverweise** zeigen den aktuellen Titel (bisher blieb ein Verweis auf eine umbenannte Unterseite „Unbenannt“).
- **Dunkelmodus:** Eigene Symbole (Chats, Glocke, Kommentare, Teilen, Schloss …) liegen jetzt als Thema „hicolor“ vor
  und werden wie Systemsymbole eingefärbt (bisher dunkelgrau auf dunklem Grund).
- **Schmales Fenster (Telefon-Breite):** schmalere Ränder und Griffe, die Leiste unter Tabellen bricht um, die
  Datenbank-Leiste wird zweizeilig (Reiter oben, darunter Suche, ⚙, Neu) – nichts läuft mehr rechts aus dem Fenster.
- **Initialen** im Profilkreis nur aus Buchstaben des Namens („AB“ statt „A(“, „A1“); eigene Kommentare mit den
  Initialen des Kontos statt „D“ von „Du“.
- **Übersetzungen:** Dateigrößen in der Sprache der App („44 Bytes“), Reiter „Mitteilungen“ in Russisch/Spanisch/Thai
  übersetzt (bisher „Push“), Tastenkürzel-Übersicht mit „Strg“, „Umschalt“, „←“ wie in den Tooltips.
- **Kleineres:** Titel neuer Seiten bekommt zuverlässig den Fokus; Enter auf der leeren letzten Zeile bzw.
  Umschalt+Tab führt aus einem Toggle heraus; Schaltfläche „Neuer Datenbank-Eintrag“/„Seite öffnen“ ohne Ziel lässt sich
  nicht speichern, ein Klick ohne Ziel sagt es; Hinweis „n Filter“ in gefilterten Datenbank-Ansichten; Leerzustand bei
  „Dienste“; Kalender ohne Namen zeigen den letzten Teil der Adresse; Hinweis auf den Kalender nennt „Einstellungen →
  Konto → Kalender“; Personensuche mit 1–2 Zeichen erklärt „mindestens 3 Zeichen“; Adressfeld in den Einstellungen
  beim Öffnen nicht mehr markiert; Rückfragen beim Koppeln nennen den Port; geht kein Browser auf, wird die Adresse
  kopiert und angezeigt; Papierkorb-Zeilen werden umbrochen statt abgeschnitten; Videos spulen genau (nicht mehr an
  das letzte Schlüsselbild); „✓✓“ kräftiger; Hinweistext mittig zum Emoji.
- **Runde 2 – Absturz beim Blockmenü per Tastatur (Datenverlust):** Menütaste bzw. Umschalt+F10 → „Löschen“,
  „Duplizieren“ oder „Verschieben“ beendete die App (Speicherzugriffsfehler in GTK), die Änderung ging verloren. Das Menü
  hing am Textfeld des Blocks, das beim Neuaufbau verschwand. Menüs hängen jetzt nie an einem Textfeld (sie zeigen auf
  die Schreibmarke), und der gewählte Eintrag läuft erst, wenn das Menü zu und abgehängt ist – für alle Menüs der App,
  unabhängig von der GTK-Version. Löschen über die Tastatur zeigt wie über den Griff „Block gelöscht“ mit Rückgängig.
- **Runde 2 – Gelesen ✓✓ in Chats über Servergrenzen:** Eigene Nachrichten auf der anderen Instanz als dem Heim des
  Chats blieben dauerhaft bei ✓ – die schon geladene Kopie bekam ihre Position `fseq` nie. Sie kommt jetzt aus der
  Antwort beim Senden, dem Live-Ereignis der Zustellung und späteren Serverlisten; die Bestätigung wird wie bei Android
  über diese Reihenfolge zugeordnet.
- **Runde 2 – Gruppenanrufe klingeln:** Die App meldet dem Server ihre Fähigkeiten (`PUT /api/calls/caps`, nur `multi`:
  Einladung und Klingeln, das Gespräch läuft im Browser). Bisher galten reine Linux-Nutzer als „braucht Saga 2.4“ und
  wurden nicht eingeladen. Server ohne Gruppenanrufe bekommen nichts gemeldet.
- **Runde 2 – Gleichnamige Personen unterscheidbar:** Teilen (Suche und Zugriff-Liste), neue Unterhaltung, „Person
  hinzufügen“ und Mitgliederliste zeigen unter dem Namen das Merkmal vom Server (öffentlicher Name bzw. Handle `~…`),
  nie den Anmeldenamen.
- **Runde 2 – Datenbank-Eintrag im Peek:** Status, Datum usw., die in der Tabelle (auch hinter dem Peek) oder von einer
  anderen Person gesetzt werden, erscheinen sofort im offenen Eintrag statt „Leer“; Tabellen laden bei Änderungen an
  Zeilen durch andere neu.
- **Runde 2 – Tabellen:** Die Leiste unter einer Tabelle („Zeile“, „Spalte“, „Kopfzeile“, ⋮) lag über den folgenden
  Blöcken; sie steht jetzt breit in einer Zeile und bricht nur im schmalen Fenster um.
- **Runde 2 – Kleineres:** „Dienste“ ohne Einträge bzw. ohne Treffer zeigte wegen eines Programmfehlers nichts an (gleicher
  Fehler beim Kopieren im Assistenten und beim Test-Push behoben, mit Prüfung im Test); Titel neuer Seiten und
  Unterseiten bekommt den Fokus auch, wenn die Seite erst nach dem Klick aufgebaut wird; Mitgliederliste „du“ statt
  „dir“, Personen des eigenen Servers „Person auf diesem Server“ statt „auf localhost:8197“; 370 px: Bilder ohne großen
  Leerraum darüber, Geräte in Einstellungen → Chats mit Symbol-Knopf (Text bricht nicht mehr Wort für Wort um),
  Datenbank-Seite mit Rand rechts.
- Tests: `linux/tests/test_tt24.py`, `linux/tests/test_r2.py`, `linux/tests/blockmenu_keys.py` (echte Hauptschleife).

### Behoben – Chats über Servergrenzen
**Android – eigene Nachricht blieb auf „⏳ wird zugestellt“:** In einem Direkt-Chat, dessen Heim der andere Server ist,
zeigte die App eine längst zugestellte eigene Nachricht weiter als ausstehend (Server 2.3.0 melden die sofortige
Bestätigung nicht als Live-Ereignis). Die App übernimmt die Bestätigung jetzt aus der Antwort beim Senden und gleicht
wartende eigene Nachrichten beim Nachladen und alle 15 Sekunden mit dem Server ab – auch schon hängende heilen so von
selbst, „nicht zugestellt“ erscheint ebenfalls; eine vom Server verworfene Kopie verschwindet.

**iOS/iPadOS/macOS – eigene Nachricht blieb auf „⏳ wird zugestellt“:** Derselbe Fehler, dieselbe Abhilfe wie unter
Android: Die Bestätigung aus der Antwort beim Senden zählt als zugestellt, und solange eigene Nachrichten warten, gleicht
die App sie beim Nachladen und alle 15 Sekunden mit dem Server ab – nur bei sichtbarem Chat im Vordergrund. Hängende
Nachrichten heilen so auch mit Servern 2.3.0 von selbst; „nicht zugestellt“ wird übernommen, eine vom Server verworfene
Kopie verschwindet.

**Linux – eigene Nachricht blieb auf „⏳ wird zugestellt“:** Derselbe Fehler, dieselbe Abhilfe wie unter Android: Eine
Antwort beim Senden ohne „wartet“ gilt als zugestellt, auch für eine schon mit ⏳ geladene Kopie. Solange eigene
Nachrichten warten und der Chat offen ist, gleicht die App sie beim Nachladen und alle 15 Sekunden mit dem Server ab –
ohne wartende Nachricht geht keine zusätzliche Anfrage raus. Hängende Nachrichten heilen so auch mit Servern 2.3.0 von
selbst; „nicht zugestellt“ wird übernommen, eine vom Server verworfene Kopie verschwindet.

### Behoben – Anrufe
**iOS/iPadOS/macOS – Anrufe verkleinern, Mitteilungen während eines Anrufs:**
- **Zurück in die App während eines Anrufs (iPhone/iPad):** Der Anrufbildschirm hat oben links „Zurück zur App“
  (Pfeil nach unten). Der Anruf läuft weiter – Ton und Video bleiben verbunden –, Chats und Seiten sind bedienbar.
  Oben steht eine grüne Leiste „Anruf läuft – tippen zum Zurückkehren“ mit der Dauer; Tippen öffnet den Anruf wieder.
  Endet ein verkleinerter Anruf, erscheint nur ein kurzer Hinweis statt des Vollbilds. Bisher sah man nach dem Öffnen
  von Saga nur den Anrufbildschirm.
- **Hinweis im Chat:** In der Unterhaltung, zu der der laufende Anruf gehört, steht oben „Anruf läuft in diesem Chat“
  mit Dauer und „Zurück zum Anruf“.
- **Absturz beim Antippen einer Chat-Mitteilung während eines Anrufs behoben:** Bisher baute die App die Navigation
  (Bereich „Chats“ und Unterhaltung in einem Schritt) unter dem offenen Anruf-Vollbild um; die App stürzte ab, der
  Anruf brach mit ab. Jetzt wird der Anruf zuerst verkleinert, danach öffnet sich der Chat (Bereich und Unterhaltung
  nacheinander); der Anruf läuft weiter, die Leiste ist sichtbar. Dasselbe gilt für andere Mitteilungen und Links aus
  Widgets/Kurzbefehlen.
- **Mac:** Während eines Anrufs zeigt das Hauptfenster dieselbe Leiste („klicken zum Zurückkehren“ holt das Fenster
  „Anruf“ nach vorn) und den Hinweis im Chat.

**Android – Zurück in den laufenden Anruf:**
Lief ein Anruf und ging man in der App zurück in den Chat, zeigte nichts mehr, dass der Anruf noch aktiv ist – zurück
kam man nur über die Mitteilung.
- **Leiste „Anruf läuft · m:ss – tippen zum Zurückkehren“** oben in der App auf allen Bildschirmen außer dem
  Anrufbildschirm (beim Aufbau „verbindet“, beim Klingeln „Eingehender Anruf von …“). Tippen öffnet den laufenden
  Anruf wieder – ohne Neuaufbau, Ton und Bild laufen weiter.
- **Im betroffenen Chat** zusätzlich „Zum Anruf zurückkehren“; die Anruf-Knöpfe dort führen während des Anrufs
  ebenfalls zurück statt „Du telefonierst gerade schon“ zu melden.
- **Zurück (Taste/Geste) im Anrufbildschirm minimiert** nur (neu auch ein Knopf „Anruf minimieren“ oben links);
  aufgelegt wird ausschließlich mit „Auflegen“. Anrufbildschirm und Vordergrund-Dienst bleiben erhalten, erneutes
  Öffnen baut keine zweite Verbindung auf.

## [2.3.0] – 2026-10-19

Passt zu Saga-Server 2.3. **Die neuen Funktionen brauchen Saga-Server 2.3; ältere Server funktionieren weiter**
(dort blenden die Apps die neuen Bereiche aus und arbeiten wie bisher).

**Höhepunkte**
- **Föderation:** Chats, Anrufe, Gruppen, Seiten und Datenbanken über Servergrenzen – in allen Apps, Ende-zu-Ende
  verschlüsselt, mit Nachrichtenanfragen beim Erstkontakt.
- **Mein Backup:** das eigene Saga-Konto verschlüsselt in die eigene Nextcloud oder auf ein WebDAV-Ziel sichern,
  mit Zeitplan und selbst gewählten Ausnahmen.
- **Nextcloud-Dateien in Seiten:** Bilder, Dokumente und Ordner aus der eigenen Nextcloud einfügen, mit Vorschau.
- **Schneller:** Delta-Abgleich, virtuelle Listen und schnellerer Start auf allen Plattformen.

### Neu – Apps Teil 3 (Saga 2.3): Mein Backup, Nextcloud-Dateien, lesbare Backup-Meldungen
Alle drei Plattformen zeigen „Mein Backup“ und „Nextcloud“ nur bei Servern, die es können (ältere Server 2.1/2.2:
ausgeblendet), stellen den Block `nextcloud` dar und schreiben ihn unverändert zurück. Android ergänzt zusätzlich
„Mitglied entfernen“ in Gruppen.

#### iOS/iPadOS/macOS
- **Einstellungen → „Mein Backup“:** Status (letztes erfolgreiches Backup, nächster Lauf, laufender Schritt bzw. Fehler
  des letzten Laufs, letzte Läufe) und „Jetzt sichern“ direkt in den Einstellungen; „Verwalten …“ öffnet alles Weitere:
  Ziel verbinden (Nextcloud per Login Flow v2 – Link öffnet im Browser, die App fragt den Server ab – oder WebDAV mit
  Benutzer/Passwort), Verbindung prüfen, Trennen; Schlüsselpaar erzeugen (privater Schlüssel nur einmal angezeigt, zum
  Kopieren/Teilen bzw. am Mac als Datei an einen selbst gewählten Ort, nie gespeichert; Bestätigung mit den letzten 6
  Zeichen) oder eigenen öffentlichen Schlüssel eintragen, Schlüssel entfernen; Kategorien und ausgeschlossene
  Seitenbäume (nur, wenn der Server sie meldet); Zeitplan (aus/wöchentlich/täglich – täglich nur, wenn der Admin es
  erlaubt), Uhrzeit, Wochentag, Aufbewahrung; Backups im Ziel anzeigen und herunterladen; Einspielen eines Backups aus
  dem eigenen Ziel ins eigene Konto (privater Schlüssel nur für diesen Vorgang), Backup-Dateien weiter im Web.
- **Einstellungen → „Nextcloud“:** eigene Nextcloud verknüpfen (Login Flow v2), Status (verbunden als …, seit, zuletzt
  genutzt), Trennen mit Rückfrage.
- **Block „Nextcloud-Datei“:** Karte mit Symbol je Art, Name, Größe und Datum; Bilder mit Vorschau über den
  Saga-Proxy, Ordner mit den ersten Einträgen, „Herunterladen“ (Mac: Sichern-Dialog, iPhone/iPad: Teilen-Blatt) und „In
  Nextcloud öffnen“; nicht verfügbare Dateien als Karte ohne Inhalt. Der Block wird beim Speichern unverändert
  zurückgeschrieben (Freigaben ändert die einfügende Person im Web). Einfügen über „/“ → „Nextcloud-Datei“: Auswahl aus
  der eigenen Nextcloud (Zuletzt, Ordner mit „Diesen Ordner einfügen“, Suche), „Als Verweis einfügen“ oder „Als Kopie
  übernehmen“ (auf Seiten anderer Saga-Server nur als Kopie). Lehnt der Server einen neuen Nextcloud-Block ab (Datei
  einer anderen Person kopiert: 403, Nextcloud nicht verbunden: 409), zeigt die Seite die Meldung des Servers samt
  Hinweis, statt einen Konflikt oder Zugriffsverlust anzunehmen; der Entwurf bleibt erhalten.
- **Benachrichtigungen:** „Mein Backup fehlgeschlagen“ (`userbackup.failed`) und für Admins `backup.failed` erscheinen
  mit eigenem Symbol und Titel statt als Seite; Antippen öffnet „Mein Backup“ bzw. den Admin-Bereich im Browser.
  Mitteilungs-Kategorien „Admin“ und „Anrufe“ sowie „Verpasste Anrufe“ mit lesbarem Text statt Rohschlüssel.
- Ältere Server (2.1/2.2) blenden die neuen Abschnitte aus; lokale Funktionen bleiben unverändert. Texte de/en/es/ru/th.
  Geprüft mit Logik-Tests und Ende-zu-Ende gegen einen Server 2.3 mit nachgemachter Nextcloud/WebDAV
  (`tests/fakedav.py`; `ios/Tests/CloudE2E`).
- Prüfung: absolute Links aus Benachrichtigungen und Mitteilungen öffnet die App nur noch bei `backup.failed`
  (Admin-Bereich), wie Android und Linux; „Nextcloud-Datei“ im „/“-Menü erscheint nur bei Servern ab 2.3.

#### Android
- **Einstellungen → „Mein Backup“** (nur bei Servern, die es können): Status mit letztem erfolgreichem Backup, nächstem
  Lauf, Fehler des letzten Laufs und den letzten Läufen, „Jetzt sichern“ (Fortschritt wird nachgefragt), „Backups im
  Ziel anzeigen“. Ziel verbinden: Nextcloud per Login Flow v2 (Anmeldung im Browser, die App fragt über den Server nach)
  oder WebDAV mit Benutzer und Passwort, „Verbindung prüfen“ und „Trennen“. Verschlüsselung: eigenen öffentlichen
  Schlüssel eintragen oder ein Schlüsselpaar erzeugen lassen – der private Schlüssel erscheint nur einmal (Teilen oder
  als vertraulich markiert kopieren), wird nie gespeichert und mit den letzten 6 Zeichen bestätigt. „Was gesichert
  wird“ (Kategorien) und „Seiten nicht sichern“ (Seitenbäume) nur, wenn der Server sie liefert; Zeitplan (aus,
  wöchentlich mit Wochentag, täglich – „täglich“ gesperrt, wenn der Admin höchstens wöchentlich erlaubt), Uhrzeit und
  Aufbewahrung. Einspielen: Hinweis und Knopf „Im Browser öffnen“.
- **Einstellungen → „Nextcloud“:** eigenes Konto verknüpfen (Login Flow v2) bzw. trennen, mit „Verknüpft seit“ und
  „Zuletzt genutzt“.
- **Nextcloud-Dateien in Seiten:** Der Block `nextcloud` erscheint als Karte mit Typ-Symbol, Name, Größe und Datum,
  Bilder und Dateien mit Vorschau über den Saga-Proxy, Ordner mit ihren ersten Einträgen, „Herunterladen“ (über Saga,
  dann Öffnen/Teilen) und „In Nextcloud öffnen“ (nicht auf Seiten anderer Server); ohne Inhalt eine verständliche
  Karte. Der Block wird unverändert zurückgespeichert. Einfügen über „+“ → „Nextcloud-Datei …“ (Zuletzt, Ordner,
  Suche) aus der eigenen verknüpften Nextcloud. Lehnt der Server eine neu eingefügte Datei ab (z. B. aus der Nextcloud
  einer anderen Person), meldet der Editor das verständlich statt eines Konflikts oder „Zugriff verloren“; „Als Kopie
  speichern“ lässt Dateien anderer Personen mit Hinweis weg.
- **Benachrichtigungen:** `userbackup.failed` und `backup.failed` erscheinen lesbar als Systemmeldung; Antippen öffnet
  „Mein Backup“ bzw. den Admin-Bereich im Browser. Mitteilungen: Kategorie „Admin: fehlgeschlagene Server-Backups“,
  nur wenn der Server sie für das Konto anbietet.
- **Gruppen:** Admins freier Gruppen können Mitglieder entfernen (mit Rückfrage), auch in Gruppen über Servergrenzen –
  wie Web, iOS/macOS und Linux.
- Ältere Server (2.1/2.2) und lokale Funktionen bleiben unverändert. Texte de/en/es/ru/th. Geprüft mit Unit-Tests
  (`Apps23cTest`) und Ende-zu-Ende im Emulator gegen einen Server aus 9009d43 mit nachgemachter Nextcloud
  (`tests/fakedav.py`).

#### Linux
- **Einstellungen → „Mein Backup“** (Server ab 2.3, sonst unsichtbar): Status (letztes erfolgreiches Backup, nächster
  Lauf, laufendes Backup mit Schritt, Fehler des letzten Laufs, letzte Läufe), „Jetzt sichern“, Backups im Ziel
  (verschlüsselt herunterladen), Ziel verbinden – Nextcloud per Login Flow v2 (Link öffnet im Browser, die App fragt den
  Server ab) oder WebDAV mit Benutzer/Passwort –, „Verbindung prüfen“ und „Trennen“. Eigenen öffentlichen Schlüssel
  eintragen oder ein Schlüsselpaar erzeugen lassen: der private Schlüssel erscheint nur einmal im Dialog (kopieren oder
  als Datei sichern), wird mit den letzten 6 Zeichen bestätigt und nie gespeichert; Schließen ohne Bestätigung verwirft
  den Vorgang. Inhalt (Kategorien) und – wenn der Server sie liefert – ausgeschlossene Seitenbäume, Zeitplan (aus,
  wöchentlich mit Wochentag, täglich, wenn der Server es erlaubt), Uhrzeit und Anzahl der aufbewahrten Backups.
  Einspielen: Hinweis und Knopf „Im Browser öffnen“.
- **Einstellungen → Konto → „Nextcloud“:** eigenes Nextcloud-Konto per Login Flow v2 verknüpfen bzw. trennen (Server ab
  2.3, sonst unsichtbar).
- **Nextcloud-Dateien in Seiten:** Der Block `nextcloud` erscheint als Karte mit Typ-Symbol, Name, Größe und Datum,
  Bildvorschau bzw. ersten Ordnereinträgen über den Saga-Proxy, „Herunterladen“ und „In Nextcloud öffnen“; ohne Inhalt
  (getrennt, kein Zugriff, gelöscht) ein Hinweis. Die Person, die den Block eingefügt hat, kann „Auf veröffentlichter
  Seite zeigen“, „Über verbundene Server zeigen“ und „Als Kopie übernehmen“; Blöcke bleiben beim Speichern unverändert.
  Neu im „/“-Menü: „Nextcloud-Datei“ – Auswahl aus der eigenen Nextcloud (Zuletzt, Suche, Ordner, „Diesen Ordner
  einfügen“) als Verweis oder als Kopie (Seiten anderer Saga-Server: nur als Kopie). Nextcloud-Blöcke anderer Personen
  lassen sich nicht in andere Seiten einfügen (Hinweis); lehnt der Server das Speichern deswegen ab (403/400/409), sagt
  die App das verständlich, statt die Seite als „nur noch lesen“ zu behandeln.
- **Benachrichtigungen und Mitteilungen:** „Mein Backup fehlgeschlagen“ (`userbackup.failed`, Klick öffnet „Mein
  Backup“) und „Backup fehlgeschlagen“ für Admins (`backup.failed`, öffnet den Admin-Bereich im Browser) erscheinen
  lesbar mit eigenem Symbol; die Mitteilungs-Kategorie „Admin: Backup fehlgeschlagen“ zeigt die App nur, wenn der
  Server sie anbietet. Texte in Deutsch, Englisch, Spanisch, Russisch und Thai. Ältere Server (2.1/2.2) und lokale
  Funktionen bleiben unverändert. Geprüft mit `tests/test_23c.py` und Ende-zu-Ende gegen einen Server mit
  nachgemachtem Nextcloud/WebDAV-Ziel (`tests/real23c.py`).

### Neu (Linux)
- **Chats über Servergrenzen (Saga 2.3, Föderation E1–E3):** Die Linux-App versteht jetzt das Chat-Format
  `saga-chat/v2` und meldet das dem Server (`X-Saga-Chat: 2`) – Chats mit Personen verbundener Saga-Server erscheinen
  damit vollwertig statt nur als Hinweis. Personensuche in „Neue Unterhaltung“, „Person hinzufügen“ und im
  Teilen-Dialog sucht bei Servern ab 2.3 schon ab 1 Zeichen (bei 1–2 Zeichen nur genaue Treffer, auch `name@host`) und
  zeigt Personen anderer Server als „Name · host“ mit Profilbild. Ein Direkt-Chat beginnt als Nachrichtenanfrage: eigener
  Bereich oben in der Chat-Liste, statt des Eingabefelds „Annehmen“, „Ablehnen“ und „Sperren …“ (Person oder ganzer
  Server; bis zur Annahme keine Benachrichtigungen und kein Klingeln), Sperrliste mit „Entsperren“ und der Schalter „In
  Adressbüchern verbundener Server erscheinen“ unter Einstellungen → Chats. Nachrichten und Anhänge (mit
  Vorschaubild) werden mit `saga-chat/v2` verschlüsselt und signiert; Schlüssel der anderen holt die App über den
  eigenen Server und prüft Zertifikate und signierte Geräte-Liste selbst. Zertifikate v2 für die eigenen Geräte lädt
  sie still hoch (auch nach `chat.keys.changed`). Vertrauen beim ersten Kontakt je Handle (TOFU): Warnung bei
  Schlüsselwechsel (Senden und Anrufen erst nach „Verstanden“ oder Prüfen), Blockade, wenn die Person als „überprüft“
  markiert war, Ablehnung ungültiger Schlüssel oder einer zurückgedrehten Geräte-Liste; Sicherheitsnummer v2 mit
  Herkunft. Ist der andere Server gerade nicht erreichbar, zeigt die Nachricht „wird zugestellt“ (⏳), abgewiesene
  Nachrichten „nicht zugestellt“ mit „Erneut senden“; Systemzeilen (z. B. KI ein-/ausgeschaltet) erscheinen mittig.
  Anrufe gehen nur aus angenommenen Chats (Anfragen klingeln nie), das Klingeln in der App funktioniert auch für
  Anrufe von anderen Servern. Lokale Chats (v1), lokale Anrufe und ältere Server (2.1/2.2) bleiben unverändert.
  Geprüft mit allen Testvektoren `chat_v2.json` (Unit-Test) und Ende-zu-Ende gegen zwei verbundene Server
  (`tests/realfed.py`, Gegenprobe mit der Server-Referenz). Der Hinweis bei `name@server` ohne Treffer sagt jetzt,
  dass Personen anderer Server über ihren Anzeigenamen gefunden werden (statt „gibt es noch nicht“).

### Neu (Linux) – Seiten und Gruppen über Servergrenzen (Saga 2.3, Föderation E4–E6)
- **Seiten anderer Server vollständig:** Die App wertet `remote.caps` aus und zeigt auf fremden Seiten genau das, was
  der Server der Seite kann – Datenbanken öffnen und bearbeiten (Zeilen, Werte, Relationen, Spalten, neue Einträge),
  Unterseiten anlegen und innerhalb des geteilten Bereichs verschieben, Bilder und Dateien hochladen (die Datei liegt
  beim Eigentümer), Symbol, Titelbild, Schlagwörter und volle Breite, Versionen ansehen und wiederherstellen (ab
  „Bearbeiten“), Export als Markdown bzw. ZIP, Anwesenheit und Live-Aktualisierung (Abo beim Server der Seite) und der
  Seiten-Chat. Löschen, Teilen, Veröffentlichen, Duplizieren, Immich und KI bleiben wie bisher beim Eigentümer bzw. dem
  eigenen Server. Gegenstellen 2.1/2.2 (ohne `caps`) bleiben unverändert bei Lesen, Kommentieren und Titel/Inhalt.
- **Bearbeiten auf Servern ab 2.3:** Versionsverlauf und „Verschieben nach …“ (nur im geteilten Bereich mit denselben
  Freigaben, nie auf die oberste Ebene) auch mit der Rolle „Bearbeiten“.
- **Gruppen mit Personen anderer Server:** „Gruppe mit mehreren Personen“ nimmt Treffer anderer Server auf (Heim ist
  der eigene Server), Admins fügen Personen per Handle hinzu und entfernen sie; der Gruppenname kommt als
  verschlüsselte v2-Nachricht und wird mit den selbst geprüften Schlüsseln der absendenden Person entschlüsselt.
  Einladungen erscheinen als Nachrichtenanfrage („… hat dich in diese Gruppe eingeladen“), Systemzeilen zeigen
  Mitgliederwechsel mit Herkunft (hinzugefügt, entfernt, verlassen, Verbindung getrennt, Konto gelöscht) und „ab jetzt
  mit Personen anderer Saga-Server“. Kopfzeile, Chat-Liste und Mitglieder zeigen, auf welchem Server der Chat liegt.
  Umgestellte Seiten-Chats lesen ältere Nachrichten (ohne `sender_handle`) weiter mit v1; Schlüssel von Personen
  dritter Server (über das Heim) werden wie immer selbst geprüft. Der Hinweis „Gruppen mit Personen anderer
  Saga-Server kommen mit einem späteren Update“ entfällt.
- Prüfung: Schlüssel großer Gruppen (mehr als 20 Personen) werden in Teilen geholt (vorher scheiterte das Senden),
  Schlüssel-Einträge mit doppelten Geräte-IDs werden verworfen, Systemzeilen nennen den Server aus dem Handle, und auf
  gesperrten Seiten bietet nur der Eigentümer „Diese Version wiederherstellen“ an.
- Geprüft mit `tests/test_fed23b.py` (nachgebauter Server) und Ende-zu-Ende gegen drei verbundene Server
  (`tests/realfed23b.py` Seiten/Datenbanken, `tests/realfed23b_chat.py` Gruppen und Seiten-Chats).

### Neu (Android) – Chats und Anrufe über Servergrenzen (Saga 2.3, Föderation)
- **Direkt-Chats mit Personen verbundener Saga-Server:** „Neue Unterhaltung“ findet Personen anderer Server über
  ihren Anzeigenamen (Adressbuch, Anzeige „Name · Server“ mit Profilbild) bzw. `name@server`; die erste Nachricht
  kommt beim Gegenüber als Nachrichtenanfrage an. Verschlüsselung im neuen Format `saga-chat/v2` (gebunden an den
  föderierten Handle und die globale Chat-Kennung, Anhänge und Vorschaubilder inklusive); die App prüft Zertifikate,
  die signierte Geräte-Liste und die Signaturen selbst. Alle Testvektoren (`tests/vectors/chat_v2.json`) laufen als
  Unit-Test (JVM) und auf dem Gerät. Die App meldet sich dafür mit `X-Saga-Chat: 2`.
- **Nachrichtenanfragen:** eigener Bereich oben in der Chat-Liste; in der Unterhaltung „Annehmen“, „Ablehnen“ und
  „Sperren …“ (Person oder ganzer Server) statt des Eingabefelds – bis zur Annahme keine Anrufe. Sperrliste mit
  „Entsperren“ über „Gesperrt“ in der Chat-Liste.
- **Adressbuch-Schalter:** Einstellungen → Konto → „Verbundene Saga-Server“ mit „In Adressbüchern verbundener Server
  erscheinen“ (wie Web und Linux; nur sichtbar, wenn der eigene Server mit anderen verbunden ist).
- **Vertrauen (TOFU) je Person eines anderen Servers:** Hinweis bei geändertem Schlüssel mit „Verstanden“, Sperre
  nach „überprüft“ bis zum erneuten Vergleich, Sicherheitsnummer v2, „✓ überprüft“ in der Kopfzeile; Senden und
  Anrufen sind bei Warnung/Sperre blockiert. Live-Ereignisse `chat.keys.changed` und `chat.request` werden ausgewertet.
- **Zustellstand:** „wird zugestellt“, solange der andere Server nicht erreichbar ist, „nicht zugestellt“ mit
  „Erneut senden“; Systemzeilen (z. B. KI ein-/ausgeschaltet); schreibgeschützte Chats zeigen den Grund.
- **Anrufe aus angenommenen Chats über Servergrenzen** (`saga-call/v2`, Handles statt Benutzernamen).
- **Suche ab 1 Zeichen** in Chat und Teilen-Dialog (1–2 Zeichen: nur genaue Namen, z. B. kurze Namen wie „hd“);
  Treffer anderer Server im Teilen-Dialog mit „Name · Server“ und Profilbild.
- Lokale Chats (`saga-chat/v1`), lokale Anrufe und Server 2.1/2.2 bleiben unverändert nutzbar.

### Neu (Android) – Seiten und Gruppen über Servergrenzen (Saga 2.3, Föderation E4–E6)
- **Seiten anderer Saga-Server je nach Fähigkeit ihres Servers** (`remote.caps`): Unterseiten anlegen (Seitenleiste,
  Rolle Bearbeiten), Bilder hochladen (die Datei liegt beim Server der Seite), Datenbanken öffnen samt Zeilen (vom
  Server der Seite gerechnet, auch eingebettete Datenbanken), Versionsverlauf ansehen und wiederherstellen, Export,
  Anwesenheit und Live-Änderungen, Seiten-Chat. Ältere Gegenstellen (2.1/2.2) bleiben bei Lesen, Kommentieren und
  Titel/Inhalt bearbeiten.
- **Versionsverlauf und Export auch für eigene Seiten:** „Versionsverlauf“ (Eigentümer; mit Bearbeiten ab Server 2.3)
  mit Vorschau und „Diese Version wiederherstellen“; „Als Markdown exportieren“ bzw. „Mit Unterseiten exportieren
  (.zip)“ über den Speichern-Dialog des Systems.
- **Gruppen mit Personen anderer Server:** „Eigene Gruppe“ nimmt Personen verbundener Server auf (bei ihnen als
  Nachrichtenanfrage), „Person hinzufügen“ in Gruppen über Servergrenzen; Einladungen anderer Server erscheinen als
  Anfrage mit „{Name} ({Server}) hat dich in diese Gruppe eingeladen“. Gruppenname als v2-Nachricht (wird beim
  Hinzufügen erneut gesendet), Systemzeilen zu Mitgliedern mit Herkunft (hinzugefügt, verlassen, entfernt, getrennt,
  Konto gelöscht) und „ab jetzt mit Personen anderer Saga-Server“. Chat-Liste zeigt Art, Personenzahl und Heim.
  Personen dritter Server (Schlüssel über das Heim) werden wie alle selbst geprüft.
- **Umgestellte Seiten-Chats:** Nachrichten und Anhänge aus der Zeit vor der Umstellung bleiben lesbar (v1), neue
  Nachrichten gehen in v2; ein offener Chat lädt nach der Umstellung neu.
- Der Hinweis „Gruppen mit Personen anderer Saga-Server kommen mit einem späteren Update“ ist entfallen.
- Prüfung: Schlüssel-Einträge mit ungültigem Handle oder doppelten Geräte-IDs werden verworfen (wie iOS/Linux),
  Systemzeilen nennen den Server aus dem Handle, „Diese Version wiederherstellen“ auf gesperrten Seiten nur für den
  Eigentümer; zwei Lint-Fehler (`StateFlow.value` in der Oberfläche) behoben.
- **Kurzsuche nur bei Servern ab 2.3:** Personensuche in Chat und Teilen-Dialog ab 1 Zeichen nur, wenn der Server
  2.3 oder neuer ist; ältere Server wie bisher ab 3 Zeichen mit Hinweis „Mindestens 3 Zeichen“.
- Lokale Seiten und Chats sowie ältere Server bleiben unverändert.

### Behoben (Android)
- **Absturz, wenn ein Anruf gleich nach dem Annehmen scheiterte** (z. B. ungültiges Angebot): Der Anruf-Dienst wurde
  beendet, bevor er in den Vordergrund kam – Android 14+ beendete dann die App
  (`ForegroundServiceDidNotStartInTimeException`). Jetzt beendet sich der Dienst in diesem Fall selbst.

### Neu (iOS/iPadOS/macOS) – Seiten, Datenbanken und Gruppen über Servergrenzen (Föderation 2.3, E4–E6)
- **Seiten anderer Server vollständig:** Die App wertet `remote.caps` aus und bietet auf fremden Seiten genau das an,
  was der Server der Seite kann: Datenbanken öffnen und bearbeiten (Zeilen, Werte, Spalten, Ansichten; Relationen nur
  auf Datenbanken desselben Servers), Unterseiten und Zeilen anlegen, innerhalb des geteilten Bereichs verschieben,
  Bilder und Dateien hochladen (die Datei liegt beim Server der Seite), Symbol, Titelbild, Schlagwörter und volle
  Breite, Versionsverlauf ansehen und wiederherstellen (ab Rolle Bearbeiten), Export (Markdown/ZIP, macOS),
  Anwesenheit und Live-Aktualisierung sowie der Seiten-Chat. Server 2.1/2.2 bleiben bei Lesen, Kommentieren und
  Titel/Inhalt; Hochladen dorthin meldet verständlich, dass der Server es noch nicht kann.
- **Auch lokal ab Server 2.3:** Versionen und Verschieben (im geteilten Bereich) mit Rolle Bearbeiten; Fehler beim
  Wiederherstellen einer Version werden angezeigt statt verschluckt.
- **Gruppen mit Personen anderer Server:** „Gruppe mit mehreren Personen“ nimmt Treffer anderer Server auf (`handles`);
  der Gruppenname geht als verschlüsselte v2-Nachricht (`kind: title`) und wird nur nach eigener Prüfung des
  Absenders angezeigt. Eingeladene sehen „… (Server) hat dich in diese Gruppe eingeladen“ mit Annehmen/Ablehnen/Sperren.
  Admins fügen Personen anderer Server per Handle hinzu und entfernen Mitglieder; danach geht der Gruppenname erneut
  an alle (Neue sehen nur Nachrichten ab dem Beitritt). Systemzeilen zeigen Hinzufügen, Entfernen, Verlassen,
  Trennen der Server und „ab jetzt über Servergrenzen“ mit Herkunft; im Chat-Kopf stehen die Server der anderen.
- **Umgestellte Seiten-Chats:** Nachrichten aus der Zeit vor der Umstellung (ohne `sender_handle`) werden weiter mit
  v1 entschlüsselt und geprüft, neue gehen immer als v2. Schlüssel großer Gruppen holt die App in Teilen zu je 20.
- **Kurzsuche:** Personensuche ab 1 Zeichen nur noch bei Servern ab 2.3, bei älteren wie bisher ab 3 Zeichen mit Hinweis.
- **Schlüsselprüfung:** `checkKeysV2` verwirft zusätzlich ungültige Handles und Einträge mit doppelten Geräte-IDs
  (wie Linux/Android). Lokale Chats, lokale Seiten und ältere Server bleiben unverändert.
- Prüfung: Systemzeilen nennen den Server aus dem Handle, „Diese Version wiederherstellen“ auf gesperrten Seiten nur
  für den Eigentümer, russische Mehrzahl für „%lld Server“ ergänzt.
- Geprüft mit Logik-Tests (`ios/Tests/Logic`), den Testvektoren (`ios/Tests/ChatVectors`) und Ende-zu-Ende gegen
  drei verbundene Server (`ios/Tests/FedE2E`, `FED_PART=2`).

### Neu (iOS/iPadOS/macOS) – Chats und Anrufe über Servergrenzen (Föderation 2.3)
- **Personen anderer Saga-Server finden:** „Neue Unterhaltung“ zeigt Treffer aus dem Adressbuch verbundener Server
  mit Profilbild als „Name · Server“ (gleiche Namen bleiben unterscheidbar); `name@server` findet genau diese Person.
  Gesucht wird ab dem ersten Zeichen (der Server liefert bei 1–2 Zeichen nur genaue Treffer) – auch im Teilen-Dialog,
  der Personen anderer Server jetzt ebenfalls mit Herkunft zeigt.
- **Direkt-Chats über Servergrenzen** mit dem Krypto-Format `saga-chat/v2` (an den pseudonymen föderierten Handle und
  die globale Chat-Kennung gebunden): Die App lädt still ihre Zertifikate v2 und die signierte Geräte-Liste hoch, holt
  die Schlüssel der anderen Person über den eigenen Server und prüft sie selbst (Handle-Zertifikat, Geräte, signierte
  Geräte-Liste), verschlüsselt je aktivem Gerät plus Backup-Schlüssel, Anhänge und Vorschaubilder ebenfalls in v2.
  Zustellstatus („wird zugestellt“ bei nicht erreichbarem Server, „nicht zugestellt“ mit „Erneut senden“),
  Systemzeilen (z. B. KI ein/aus), KI-Antworten mit mitsigniertem Hash.
- **Nachrichtenanfragen:** Erstkontakt erscheint oben unter „Nachrichtenanfragen“ – lesbar, aber ohne Benachrichtigung
  und ohne Klingeln; „Annehmen“, „Ablehnen“ oder „Sperren …“ (Person oder ganzer Server). Gesperrte Personen und
  Server stehen unter Einstellungen → „Chats & Geräte“ und lassen sich dort entsperren. Schreibgeschützte Chats
  (Verbindung getrennt, Konto gelöscht) zeigen den Grund statt des Eingabefelds.
- **Vertrauen (TOFU):** Kopf mit „Name ist auf Server“ und „✓ überprüft“; Sicherheitsnummer v2 (mit QR-Code) und
  „Als überprüft markieren“. Hat sich der Schlüssel geändert, warnt der Chat bis „Verstanden“; war die Person
  überprüft, ist Senden bis zum erneuten Vergleich gesperrt; ungültige Zertifikate oder eine ältere Geräte-Liste
  werden abgelehnt. Live-Ereignisse `chat.keys.changed` und `chat.request` werden ausgewertet.
- **Anrufe** (`saga-call/v2`) aus angenommenen Direkt-Chats über Servergrenzen, in beide Richtungen; Anfragen klingeln
  nie.
- Die App meldet sich mit `X-Saga-Chat: 2`. Lokale Chats (v1) und Anrufe bleiben unverändert; ältere Server (2.1/2.2)
  sind weiter voll nutzbar (keine Föderations-Funktionen, sonst alles wie bisher).
- Tests: alle gemeinsamen Testvektoren `chat_v2.json` (Kopie in `ios/Tests/vectors`, Prüfung in `ios/Tests/ChatVectors`,
  TOFU-Regeln zusätzlich in den Logik-Tests) und ein E2E-Test `ios/Tests/FedE2E` gegen zwei verbundene Server mit der
  Referenz-App als Gegenseite.

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

### Behoben (iOS/iPadOS/macOS)
- **Kontowechsel nach abgelaufener Sitzung zeigt keine fremden Seiten mehr:** Lief die Sitzung ab (ohne Abmelden) und
  wurde danach ein *anderes* Konto angemeldet oder per QR-Code gekoppelt, waren die Seiten des alten Kontos kurz zu
  sehen, bis der neue Seitenbaum da war. Jetzt werden Liste, „Mit mir geteilt“, Heute und zuletzt geöffnete Seiten
  sofort ausgeblendet; gehört die neue Anmeldung zu einem anderen (oder nicht feststellbaren) Konto, werden
  Zwischenspeicher, Abgleich-Stand, Systemsuche (Spotlight), Web-Daten (außer der neuen Sitzung) und der
  Widget-/Teilen-Schlüssel des alten Kontos verworfen, bevor etwas Neues erscheint; eine offene Seite des alten Kontos
  lädt neu. Dasselbe Konto wieder angemeldet: nichts wird verworfen, die Liste ist sofort wieder da und es wird nur
  das Geänderte abgeglichen statt des ganzen Baums. Nach jeder Neuanmeldung gibt es außerdem einen frischen
  Widget-Schlüssel (der alte ging mit der alten Geräte-Anmeldung verloren); eine noch gültige alte Geräte-Anmeldung
  dieses Geräts (z. B. Schlüsselbund nicht lesbar) wird dabei samt Schlüssel abgemeldet – Geräte und Schlüssel
  sammeln sich nicht mehr an (bisher war nach ~10 Neuanmeldungen kein Schlüssel mehr frei); ein von Hand angelegter
  Schlüssel bleibt in Gebrauch. Lässt sich das Konto nicht feststellen (Server kurz nicht erreichbar), wird die
  Liste zwar ausgeblendet, eine offene Seite sendet ungesicherten Text aber weiter; kann er keinem Konto zugeordnet
  werden, erscheint er im Kopier-Dialog statt verworfen zu werden.
- **Seitenbaum beim Anmelden/Koppeln nur noch einmal geladen:** Ein noch laufender Abgleich wurde nach der
  Neuanmeldung mit dem alten Stand wiederholt und verworfen, danach kam der ganze Baum noch einmal (bei großen Konten
  zweimal die volle Liste). Laufende Abgleiche werden jetzt beim Anmelden abgebrochen und nicht wiederholt; während
  die Anmeldung übernommen wird, startet keiner – danach genau ein Abruf.

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
- **Seitenbaum per Abgleich statt immer ganz:** Mit Servern ab 2.2 holt die App nach `notes.tree`, im Takt, beim
  Wiederverbinden und beim Start (aus dem Zwischenspeicher) nur noch, was sich seit ihrem letzten Stand geändert hat
  (`GET /api/notes?since=…`), und arbeitet es in die Liste ein – statt jedes Mal den ganzen Baum zu laden. Ohne
  gültigen Stand (erste Anmeldung, nach Abmelden, Konto- oder Serverwechsel, Live-`reset`, abgelehnter oder
  abweichender Stand, spätestens einmal am Tag) kommt die ganze Liste mit Stand (`?delta=1`); `full: true` vom
  Server ersetzt die Liste jederzeit. Ältere Server (Antwort ist eine Liste) laufen unverändert wie bisher. Der
  Zwischenspeicher hält jetzt die eingearbeitete Liste (gleiches Format wie bisher) und daneben ihren Stand, der nur
  für denselben Server und dasselbe Konto gilt. Widgets und Teilen-Erweiterung sind nicht betroffen (eigene
  App-Endpunkte); Spotlight bekommt wie bisher nur die geänderten Einträge.

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

### Behoben (Android)
- **Kopplungslink bei bestehender Anmeldung fragt immer nach:** Wurde ein `saga://pair`-Link (QR-Code, „In der App
  öffnen“) erneut geöffnet – etwa nachdem die Rückfrage abgebrochen wurde –, passierte in der angemeldeten App
  nichts. Jetzt erscheint wie unter iOS jedes Mal „Mit diesem Server koppeln?“ mit dem Hinweis, welches Konto bzw.
  welcher Server abgemeldet wird (bei laufender App und beim Start per Link). „Koppeln“ wechselt wie ein neuer
  QR-Code das Konto (Seiten, Seitenbaum-Zwischenspeicher und Abgleich-Stand des alten Kontos werden verworfen),
  „Abbrechen“ lässt alles, wie es ist. Ein alter Link, den Android nach einem App-Update erneut zustellt, wirkt
  weiterhin nicht noch einmal; ungültige Links melden wie bisher einen Fehler.

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

### Schneller (Android)
- **Seitenbaum per Abgleich statt immer ganz:** Wie unter Linux holt die App mit Servern ab 2.2 beim Start, im Takt,
  beim Wiederverbinden und nach Live-Änderungen (`notes.tree`) nur noch das Geänderte (`GET /api/notes?since=…`) und
  arbeitet es ein – ohne Änderung 67 Bytes statt des ganzen Baums (341 Seiten: 70 KB). `notes.tree` für angelegte/
  geänderte Seiten braucht eine kleine Anfrage statt je Seite deren Inhalt. Neu: Die eingearbeitete Liste liegt mit
  ihrem Stand im App-Speicher (`notes-cache`, Format von `GET /api/notes`) – der Seitenbaum steht beim Start sofort da
  (nur für denselben Server und dasselbe Konto), der erste Abgleich holt nur das seitdem Geänderte. Stand verworfen bei
  Abmelden, Anmelden/Kontowechsel, Live „reset“, `notes.tree` mit `full: true`, abgelehntem oder unpassendem Stand und
  spätestens nach einem Tag. Ältere Server (Antwort = Liste) laufen wie bisher (dann kein Zwischenspeicher).

### Schneller (Linux)
- **Seitenbaum per Abgleich statt immer ganz:** Nach Änderungen anderswo (Live `notes.tree`), im Takt, beim
  Wiederverbinden und beim Start holt die App mit Servern ab 2.2 nur noch, was sich seit ihrem letzten Stand geändert
  hat (`GET /api/notes?since=…`), und arbeitet es in die Liste ein – statt jedes Mal den ganzen Baum zu laden. Gemessen
  mit 341 Seiten: ganzer Baum 70 KB (4,6 KB gzip), Abgleich ohne Änderung 67 Bytes, neue/umbenannte Seiten ~0,5 KB,
  Verschieben/Archivieren eines Teilbaums ~6–7 KB; ein Neustart mit Zwischenspeicher lädt 67 Bytes statt 70 KB. Auch
  `notes.tree` für angelegte/geänderte Seiten braucht nur noch eine kleine Anfrage statt je Seite deren ganzen Inhalt.
  Der Zwischenspeicher bleibt die Liste im bisherigen Format (GNOME-Suche liest sie unverändert), dazu eine
  Stand-Datei. Der Stand gilt je Server und Konto und wird bei Abmelden, Anmelden, Kontowechsel, Serverwechsel, Live
  „reset“, `notes.tree` mit `full: true`, abgelehntem oder unpassendem Stand und spätestens nach einem Tag verworfen
  (dann wieder der ganze Baum). Ältere Server (Antwort = Liste) laufen unverändert wie bisher. „Mit mir geteilt“
  kommt weiter aus `GET /api/shared`.

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
  geht: nur Nachrichten mit „`@Marin`“ und höchstens 12 Nachrichten davor, im Klartext an den KI-Dienst – alles andere
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
