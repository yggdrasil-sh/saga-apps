# Android: Akku im Bild-im-Bild messen (Battery Historian)

Ziel aus dem Konzept 2.4 (§3.5/§8): **10 Minuten Videoanruf im Bild-im-Bild verbrauchen höchstens 90 %** dessen, was
derselbe Anruf im Vollbild verbraucht. Wärme: keine Stufe höher als im Vollbild.

## Vorbereitung (einmalig)

- Testgerät: Mittelklasse-Pixel, Akku über 60 %, Bildschirmhelligkeit fest (z. B. 50 %), automatische Helligkeit aus.
- Gegenstelle: zweites Gerät oder Browser mit Saga 2.4, Kamera an, gleiches WLAN (bzw. dasselbe Netzprofil).
- Battery Historian ohne Installation über Docker auf einem beliebigen Rechner:
  `docker run -p 9999:9999 gcr.io/android-battery-historian/stable:3.1 --port 9999` → http://localhost:9999
- Gerät per USB verbinden, `adb devices` zeigt es an. **Während der Messung das Kabel abziehen** (USB lädt sonst).

## Messung (dreimal je Szenario, Median)

1. Statistik zurücksetzen: `adb shell dumpsys batterystats --reset`
2. Kabel ab. Szenario **A – Vollbild**: Videoanruf starten, Anrufbildschirm offen lassen, 10 Minuten warten.
   Auflegen.
3. Kabel an, Bericht sichern: `adb bugreport bugreport-vollbild-1.zip`
   (zusätzlich `adb shell dumpsys thermalservice > thermal-vollbild-1.txt` für die Wärmestufe).
4. Wieder `adb shell dumpsys batterystats --reset`, Kabel ab. Szenario **B – Bild-im-Bild**: Videoanruf starten,
   Home-Taste/Zurück → das Anruffenster wird klein. Eine ruhige App (z. B. Einstellungen) offen lassen,
   10 Minuten warten. Auflegen über den Knopf im PiP-Fenster.
5. Kabel an: `adb bugreport bugreport-pip-1.zip`, `adb shell dumpsys thermalservice > thermal-pip-1.txt`.

## Auswertung

- Bericht in Battery Historian hochladen → Reiter **App Selection** → `io.github.yggdrasil_sh.saga`:
  „Device estimated power use“ (mAh) und „CPU user/system time“ ablesen.
- Kurz ohne Historian: `adb shell dumpsys batterystats --charged io.github.yggdrasil_sh.saga | grep -i "uid\|computed drain"`.
- Ergebnis: B / A ≤ 0,90. Wärme: `Current thermal status` in B nicht höher als in A.
- Zusatzprüfung Bandbreite (Wirkung von `want: small`): auf der **Gegenstelle** im Browser
  `chrome://webrtc-internals` → outbound-rtp (video) → „bytesSent/s“ sollte im PiP auf ≤ 300 kbit/s fallen und nach
  dem Zurückkehren wieder steigen.

## Was die App im PiP spart

- Kein zusätzlicher Renderer: das PiP zeigt dieselbe `SurfaceViewRenderer`-Instanz des Fernbilds; die eigene
  Vorschau wird im PiP entfernt (deren Renderer freigegeben), die Kamera sendet weiter.
- Das Gegenüber bekommt `state.want.video = "small"` und sendet höchstens 300 kbit/s mit halber bis drittel Auflösung
  (Tabelle Konzept §1.3) – weniger Daten und weniger Dekodierarbeit auf diesem Gerät.

Ergebnisse bitte als Tabelle (A, B, Verhältnis, Wärmestufe) im Release-Dokument 2.4 eintragen.
