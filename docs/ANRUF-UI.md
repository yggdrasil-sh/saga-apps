# Anruf-Oberfläche – gemeinsame Regeln (iOS/iPadOS/macOS, Android, Web)

Gilt für alle Anrufe, besonders für Anrufe über Gjallarhorn-Knoten (2.5). Ziel: dieselbe Situation sieht auf allen
Plattformen gleich aus, nur die Bedienelemente folgen der Plattform (HIG, Material 3, Web).

## Kopf (oben, mittig)
1. **Titel:** 1:1 der Name des Gegenübers, Gruppe der Chat-Name (ohne Namen: „Gruppenanruf“).
2. **Dauer** direkt unter dem Titel (`0:54`, tabellarische Ziffern) – nicht unten bei den Knöpfen.
3. **Eine Sicherheitszeile:** Weg-Chip (Punkt + `UDP`/`TCP`/`TLS`), Runensiegel-Chip, Schloss + „Ende-zu-Ende
   verschlüsselt“ (ohne zusätzlichen Haken). Chips antippbar (Einzelheiten), Trefferfläche ≥ 44 pt / 48 dp.
4. **Gruppe:** Teilnehmenden-Chip mit Personen-Symbol und Zahl der Geräte (`3`); die Grenze erst ab
   Grenze − 2 (`11/12`). Im 1:1 kein Zähler.
5. Hinweise (Wiederverbinden, Wechsel des Knotens) als eine Zeile darunter.

## Bühne
- **Zu zweit** (1:1 oder Gruppe mit einer weiteren Person): Gegenüber füllt die Bühne, eigenes Bild klein oben rechts
  (abgerundet 14 pt/dp, dünner Rand, ziehbar), Beschriftung „Du“ unten links im kleinen Bild.
- **Gruppe ab drei:** Kacheln im Raster; das eigene Bild ist eine normale Kachel (kein schwebendes Bild-im-Bild).
  Drei Kacheln: zwei oben, die dritte über die volle Breite.
- **Bildschirm geteilt:** das geteilte Bild groß (ganz sichtbar, Tippen = Vollbild/Zoom), darunter eine Filmleiste
  mit allen Kacheln, die ohne Anschnitt passt (bis 3) bzw. sichtbar weiterscrollt. **Ein** Hinweis „<Name> teilt den
  Bildschirm“ als Bildunterschrift am geteilten Bild, neutral (dunkel), kein Banner oben. Rot nur für „Du teilst
  gerade deinen Bildschirm“.

## Namensleiste (in jeder Kachel)
- Name vor Gerät: bei Platzmangel wird zuerst das Gerät gekürzt bzw. weggelassen, dann der Name.
- Pseudonym-Handles (`~abc…`) in der Mitte kürzen (`~5hjc…3mv4ex`), normale Namen am Ende.
- Eigene Kachel: nur „Du“ (ohne Handle).
- Leiste ca. 70 % deckend schwarz, Zusatztext weiß 80 % – lesbar auch über hellem Bild (≥ 4,5:1).

## Bedienleiste
- Feste Reihenfolge: Mikrofon, Kamera, (Kamera wechseln), Bildschirm, Lautsprecher, Auflegen.
- Zustand eindeutig: „aus“ = durchgestrichenes Symbol; Beschriftung unter dem Knopf (iOS, Android) bzw. Tooltip (Web);
  Screenreader: Name + Wert („Mikrofon, aus“).
- Klingeln: „Ablehnen“, „Annehmen“, „Mit Video“ – beschriftet; Status „Eingehender Anruf“ / „Eingehender Videoanruf“
  (1:1 über Knoten genauso, nicht als Gruppenanruf).

## Moderation und Ende
- „<Gastgeber> hat dich stummgeschaltet“ bleibt als Zeile über der Bedienleiste stehen, bis man das Mikrofon selbst
  wieder einschaltet (zusätzlich Screenreader-Ansage).
- Ende-Bildschirm: ohne Sicherheitszeile und Zähler; Gruppe mit Gruppen-Symbol statt Initialen; klare Ursache
  („<Gastgeber> hat dich aus dem Anruf entfernt.“), Knopf „Schließen“.
