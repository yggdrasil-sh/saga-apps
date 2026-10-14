# Saga für Linux

Native GNOME-App (Python, GTK4, libadwaita) für einen selbst gehosteten [Saga](https://github.com/yggdrasil-sh/saga)-Server,
mit dem Funktionsumfang der Mac-App:

- Seiten nativ bearbeiten: alle Blocktypen, `/`-Menü, `@`-Datum/Erinnerung/Seite, `:`-Emoji, Markdown-Kürzel
  (`# `, `- `, `[] `, `> `, ` ``` `, `---`), Griffe zum Verschieben (Ziehen) und Block-Menü, Spalten, Tabellen
- Titelbild (ändern, neu positionieren, Höhe), Symbol (Emoji, Bild, Immich), Tags, Eigenschaften, Inhaltsverzeichnis,
  Rückverweise, Versionsverlauf, Export, Sperren, Archiv, Papierkorb
- Datenbanken: Tabelle (Spaltenbreiten, Berechnungen, Gruppen), Board (Karten ziehen), Liste, Galerie, Kalender;
  Ansichten, Filter, Sortierung, Spalten bearbeiten, Vorlagen; Einträge im Peek (rechts oder mittig)
- Seiten veröffentlichen (Server ab 1.2): Seitenmenü „Veröffentlichen …“ – öffentlicher Name, Adresse, Unterseiten,
  Suchmaschinen, Profilseite, Link kopieren/öffnen, beenden; Hinweis „Öffentlich“ in der Kopfleiste;
  Einstellungen → Konto → „Öffentlicher Name“
- Teilen und Kommentare (Server ab 1.3): „Teilen …“ (Personen ab 3 Zeichen, eigene Gruppen, Rollen Lesen/Kommentieren/
  Bearbeiten, Freigaben der Elternseiten), Bereich „Mit mir geteilt“ in der Seitenleiste, fremde Seiten mit Etikett
  „Geteilt von … · Du kannst …“ (Lesen/Kommentieren schreibgeschützt), Kommentare als Seitenleiste rechts (Zahl am
  Block, Antworten, Erledigt, Erledigte zeigen), Konflikt „… hat diese Seite gerade geändert“, Zugriff verloren
- Aufgaben, Termine (CalDAV), Dienste, Befehlspalette (Strg+K), Einstellungen
- Bleibt angemeldet: Geräte-Schlüssel im GNOME-Schlüsselbund (libsecret), Sitzung wird still erneuert
- GNOME-Suche (Seiten in der Aktivitäten-Übersicht), Dock-Aktionen „Neue Seite“ / „Neue Aufgabe“
- Sprachen: Deutsch, Englisch, Spanisch – nach der Systemsprache, sonst Englisch

App-ID: `io.github.yggdrasil_sh.Saga` · Paket und Befehl: `hlidskjalf` · Lizenz: AGPL-3.0

## Bauen und installieren (AlmaLinux 10 / Fedora)

```sh
sudo dnf install rpm-build python3 gettext        # AlmaLinux: python3-pillow kommt aus EPEL
sh build-rpm.sh                                   # -> dist/saga-<version>-<release>.noarch.rpm
sudo dnf install ./dist/saga-*.noarch.rpm
```

Benötigt: gtk4 ≥ 4.14, libadwaita ≥ 1.6, python3-gobject, libsecret, python3-pillow (auf AlmaLinux/RHEL 10 aus EPEL).
`HK_RELEASE=2 sh build-rpm.sh` setzt die Release-Nummer (Standard 1). Die Version steht in `hlidskjalf/__init__.py`
und wird für alle Apps mit `tools/set-version.sh` gesetzt. Das Paket heißt im RPM `hlidskjalf` (Befehl `hlidskjalf`),
die Datei in `dist/` trägt zur Orientierung den Namen `saga`. In CI genügt ein Container `almalinux:10` oder
`fedora:latest` mit `dnf install -y rpm-build python3 gettext`.

## Flatpak

```sh
sh flatpak/build-flatpak.sh                       # -> dist/saga-<version>-<arch>.flatpak
flatpak install --user ./dist/saga-*.flatpak      # Laufzeit org.gnome.Platform//49 kommt von Flathub
flatpak run io.github.yggdrasil_sh.Saga
```

Benötigt `flatpak` und `flatpak-builder`; GNOME-SDK und -Laufzeit installiert das Skript bei Bedarf von Flathub
(Nutzer-Installation). Manifest: `flatpak/io.github.yggdrasil_sh.Saga.yml`, Befehl im Flatpak: `saga`.
Einstellungen und Zwischenspeicher liegen im Flatpak unter `~/.var/app/io.github.yggdrasil_sh.Saga/`,
Anhänge speichert man über den Speichern-Dialog (Portal) – das Flatpak braucht keinen Dateisystem-Zugriff
und keinen Zugriff auf den ganzen Schlüsselbund (Geräte-Schlüssel über das Secret-Portal). Die GNOME-Suche funktioniert auch im Flatpak.

## Ohne Installation starten

```sh
cd linux && python3 -m hlidskjalf.main
```

Ohne gebaute Übersetzungen startet die App englisch; `sh po/build-mo.sh` legt sie nach `build/locale`, wo die App
sie beim Start aus dem Quellordner findet. Andere Sprache zum Ausprobieren: `LANG=es_ES.UTF-8 python3 -m hlidskjalf.main`.

## Übersetzungen

Standard-gettext, Domain `io.github.yggdrasil_sh.Saga`. Die Texte im Quellcode sind englisch (`_("…")`,
`ngettext("…", "…", n)`, Platzhalter als `{name}` mit `str.format`); die Kataloge liegen in `po/`
(`saga.pot`, `de.po`, `es.po`, Sprachen in `po/LINGUAS`). Datum und Zahlen kommen aus der Systemsprache
(`GLib.DateTime.format`, `locale`), die Formate selbst sind übersetzbar (`util.py`).

```sh
sh po/update-po.sh --check      # neue Texte holen (xgettext -> saga.pot, msgmerge -> *.po) und prüfen
sh po/build-mo.sh               # *.po -> build/locale/<sprache>/LC_MESSAGES/io.github.yggdrasil_sh.Saga.mo
```

RPM und Flatpak bauen die Kataloge mit `msgfmt` (RPM: `/usr/share/locale`, Flatpak: `/app/share/locale`).
Name, Beschreibung und Stichwörter der Desktop-Datei und der Metainfo sind direkt dort übersetzt (`[de]`, `xml:lang`).
Verbindliche Begriffe: Glossar des Projekts (z. B. Aufgabe/Task/Tarea, Termin/Event/Evento).

## Anmelden

Die App hat keinen fest eingestellten Server. Beim ersten Start gibt es zwei Wege:

1. **Server-Adresse** eingeben (z. B. `https://saga.example.org`) und „Anmelden“ – die Anmeldung (Passkey,
   Sicherheitsschlüssel oder Passwort) öffnet sich im Browser und kehrt über `hlidskjalf://auth?…` zur App zurück.
2. **Kopplungslink**: im Web unter 👤 → Einstellungen → „Apps & Geräte“ → „App per QR-Code anmelden“ den Link
   unter dem QR-Code (`https://…/app/pair?c=…`) in der App einfügen – Server und Anmeldung werden übernommen.

Danach bleibt das Gerät per Geräte-Schlüssel angemeldet. Die Server-Adresse lässt sich in den Einstellungen ändern;
Einstellungen liegen in `~/.config/hlidskjalf`, der Zwischenspeicher in `~/.cache/hlidskjalf`.

Sicherheit beim Anmelden und Wechseln:

- Nur `https://` (Ausnahme: `http://localhost` im Test-/Debug-Modus mit `HK_TEST` bzw. `HK_DEBUG`).
- Ein Kopplungslink, der von außen kommt (Browser, andere App: `hlidskjalf://pair?…`), wird nie still übernommen –
  die App fragt mit dem Zielhost nach. Ebenso `hlidskjalf://new` (neue Seite); die Dock-Aktionen nutzen dafür
  `hlidskjalf --new-page` bzw. `--new-task`.
- Ein Server wird erst übernommen, wenn die Anmeldung dort geklappt hat. Beim Wechsel wird das Gerät beim alten Server
  abgemeldet (`/app/forget`, Fehler blockieren nicht), Geräte- und App-Schlüssel des alten Servers werden gelöscht und
  Sitzung, Zwischenspeicher, „Zuletzt“, Startseite und aufgeklappte Seiten entfernt.
- Geräte-Schlüssel (Schlüsselbund, Attribut `server`) und App-Schlüssel (`app_keys` in den Einstellungen) gehören je zu
  genau einem Server und werden nur an diesen gesendet; das Sitzungs-Cookie geht nur an den eingetragenen Server.

Speichern: Jede Seite wird mit ihrer Revision gespeichert. Wurde sie woanders geändert (409), bleibt die eigene
Eingabe erhalten und die App fragt wie iOS/Android („Meine Fassung behalten“ / „Server-Fassung laden (meine als Kopie
sichern)“). „Server-Fassung laden“ legt die eigene Fassung vorher immer als lokale Kopie ab; ein Banner bietet sie an
(„Nicht gespeicherte Änderungen vom …“: Wiederherstellen / Verwerfen). Esc schließt die Frage ohne Entscheidung: nichts
wird überschrieben, oben an der Seite bleibt der Hinweis „Seite wurde woanders geändert“ mit „Fassung wählen“.
Ungesendete Stände liegen als Entwurf in `~/.local/share/hlidskjalf/drafts/` – je Server und Konto getrennt (ein anderes
Konto sieht sie nie) – und werden beim nächsten Öffnen der Seite wiederhergestellt (Absturz, offline). Das Schließen des
Fensters wartet, bis alles gespeichert ist.

## Tests

### Ohne Testrechner: Fedora-Container mit nachgebautem Server

```sh
sh tests/run-container.sh                  # fedora:44 per podman/docker: lint, pytest, App-Start, Sprachen, Bildschirmfotos
sh tests/run-container.sh lint unit        # nur pyflakes/msgfmt/desktop-file-validate/appstreamcli und pytest
PYTEST_ADDOPTS="-k konflikt" sh tests/run-container.sh unit
```

Kein echter Server und keine Zugangsdaten nötig: `tests/mockserver.py` ersetzt den Server-Zugriff (Seiten, Datenbank,
Aufgaben, Kopplung, Veröffentlichen, Teilen und Kommentare, 409, Verzögerungen und Ausfälle). Die pytest-Tests (`tests/test_*.py`)
prüfen u. a. Konflikte, Entwürfe, Schließen während des Speicherns, Rückruf-Warteschlangen, Schreib-Reihenfolge,
Kopplung/Serverwechsel, Tastatur-Bedienung, den Teil-Neuaufbau des Editors, das Veröffentlichen (`test_publish.py`) sowie
Teilen und Kommentare (`test_sharing.py`, `test_comments.py`). Bildschirmfotos (`shots.py`, `dialoge.py`, `pubshots.py`,
`shareshots.py`) landen in `build/shots/`.

### Auf dem Testrechner

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
Die Tests laufen standardmäßig deutsch (`HK_LANG`, Standard `de_DE.UTF-8`; die Kataloge baut das Skript dort mit `msgfmt`).
`sprachen.py` prüft die Sprache des Anmeldebildschirms und der Startseite:

```sh
for l in en_US de_DE es_ES; do HK_LANG=$l.UTF-8 sh tools/linux-test.sh linux/tests/sprachen.py; done
```
