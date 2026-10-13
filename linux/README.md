# Saga für Linux

Native GNOME-App (Python, GTK4, libadwaita) für einen selbst gehosteten [Saga](https://github.com/yggdrasil-sh/saga)-Server,
mit dem Funktionsumfang der Mac-App:

- Seiten nativ bearbeiten: alle Blocktypen, `/`-Menü, `@`-Datum/Erinnerung/Seite, `:`-Emoji, Markdown-Kürzel
  (`# `, `- `, `[] `, `> `, ` ``` `, `---`), Griffe zum Verschieben (Ziehen) und Block-Menü, Spalten, Tabellen
- Titelbild (ändern, neu positionieren, Höhe), Symbol (Emoji, Bild, Immich), Tags, Eigenschaften, Inhaltsverzeichnis,
  Rückverweise, Versionsverlauf, Export, Sperren, Archiv, Papierkorb
- Datenbanken: Tabelle (Spaltenbreiten, Berechnungen, Gruppen), Board (Karten ziehen), Liste, Galerie, Kalender;
  Ansichten, Filter, Sortierung, Spalten bearbeiten, Vorlagen; Einträge im Peek (rechts oder mittig)
- Aufgaben, Termine (CalDAV), Dienste, Befehlspalette (Strg+K), Einstellungen
- Bleibt angemeldet: Geräte-Schlüssel im GNOME-Schlüsselbund (libsecret), Sitzung wird still erneuert
- GNOME-Suche (Seiten in der Aktivitäten-Übersicht), Dock-Aktionen „Neue Seite“ / „Neue Aufgabe“

App-ID: `io.github.yggdrasil_sh.Saga` · Paket und Befehl: `hlidskjalf` · Lizenz: AGPL-3.0

## Bauen und installieren (AlmaLinux 10 / Fedora)

```sh
sudo dnf install rpm-build python3                # AlmaLinux: python3-pillow kommt aus EPEL
sh build-rpm.sh                                   # -> dist/saga-<version>-<release>.noarch.rpm
sudo dnf install ./dist/saga-*.noarch.rpm
```

Benötigt: gtk4 ≥ 4.14, libadwaita ≥ 1.6, python3-gobject, libsecret, python3-pillow (auf AlmaLinux/RHEL 10 aus EPEL).
`HK_RELEASE=2 sh build-rpm.sh` setzt die Release-Nummer (Standard 1). Die Version steht in `hlidskjalf/__init__.py`
und wird für alle Apps mit `tools/set-version.sh` gesetzt. Das Paket heißt im RPM `hlidskjalf` (Befehl `hlidskjalf`),
die Datei in `dist/` trägt zur Orientierung den Namen `saga`. In CI genügt ein Container `almalinux:10` oder
`fedora:latest` mit `dnf install -y rpm-build python3`.

## Flatpak

```sh
sh flatpak/build-flatpak.sh                       # -> dist/saga-<version>-<arch>.flatpak
flatpak install --user ./dist/saga-*.flatpak      # Laufzeit org.gnome.Platform//49 kommt von Flathub
flatpak run io.github.yggdrasil_sh.Saga
```

Benötigt `flatpak` und `flatpak-builder`; GNOME-SDK und -Laufzeit installiert das Skript bei Bedarf von Flathub
(Nutzer-Installation). Manifest: `flatpak/io.github.yggdrasil_sh.Saga.yml`, Befehl im Flatpak: `saga`.
Einstellungen und Zwischenspeicher liegen im Flatpak unter `~/.var/app/io.github.yggdrasil_sh.Saga/`,
heruntergeladene Anhänge im Download-Ordner. Die GNOME-Suche funktioniert auch im Flatpak.

## Ohne Installation starten

```sh
cd linux && python3 -m hlidskjalf.main
```

## Anmelden

Die App hat keinen fest eingestellten Server. Beim ersten Start gibt es zwei Wege:

1. **Server-Adresse** eingeben (z. B. `https://saga.example.org`) und „Anmelden“ – die Anmeldung (Passkey,
   Sicherheitsschlüssel oder Passwort) öffnet sich im Browser und kehrt über `hlidskjalf://auth?…` zur App zurück.
2. **Kopplungslink**: im Web unter 👤 → Einstellungen → „Apps & Geräte“ → „App per QR-Code anmelden“ den Link
   unter dem QR-Code (`https://…/app/pair?c=…`) in der App einfügen – Server und Anmeldung werden übernommen.

Danach bleibt das Gerät per Geräte-Schlüssel angemeldet. Die Server-Adresse lässt sich in den Einstellungen ändern;
Einstellungen liegen in `~/.config/hlidskjalf`, der Zwischenspeicher in `~/.cache/hlidskjalf`.

## Tests

`tools/linux-test.sh linux/tests/<test>.py` kopiert den Stand per SSH auf einen Testrechner und startet die App dort
unsichtbar (eigener kopfloser mutter, der Bildschirm der angemeldeten Person wird nicht berührt) mit einem Testskript;
Bildschirmfotos landen in `/tmp/hk-shots`.

Testrechner und Benutzer kommen aus der Umgebung oder aus `tools/local.env` (nicht eingecheckt):

```sh
cp tools/local.env.example tools/local.env       # HK_TEST_HOST=root@… und HK_TEST_USER=… eintragen
sh tools/linux-test.sh linux/tests/smoke.py
HK_TEST_HOST=root@testrechner HK_TEST_USER=anna sh tools/linux-test.sh linux/tests/render.py
```

Voraussetzungen auf dem Testrechner: SSH-Zugang als root mit Schlüssel, `mutter`, die Abhängigkeiten oben und ein
angemeldeter Desktop-Benutzer (`HK_TEST_USER`). `render.py` braucht keine Anmeldung, die übrigen nutzen die
Saga-Anmeldung dieses Benutzers. `edit.py` und `dbtest.py` legen eigene Testseiten an und löschen sie danach endgültig.
