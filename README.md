<p align="center">
  <img src="linux/data/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg" width="96" height="96" alt="Saga">
</p>

<h1 align="center">Saga Apps</h1>

<p align="center">
  Native Apps für <a href="https://github.com/yggdrasil-sh/saga">Saga</a> – dein selbst gehostetes Zuhause für Dienste, Notizen und Aufgaben.<br>
  iPhone · iPad · Mac · Linux (GNOME) · Android (in Arbeit)
</p>

---

Die Apps verbinden sich mit deinem eigenen Saga-Server. Seiten, Datenbanken, Aufgaben und Termine werden
**nativ** angezeigt und bearbeitet – keine eingebettete Webseite.

| Plattform | Technik | Ordner | Anleitung |
|---|---|---|---|
| iOS, iPadOS, macOS | SwiftUI, WidgetKit | [`ios/`](ios/) | [ios/README.md](ios/README.md) |
| Linux (GNOME) | Python, GTK4 + libadwaita, RPM | [`linux/`](linux/) | [linux/README.md](linux/README.md) |
| Android | Kotlin, Jetpack Compose | `android/` | folgt |

## Anmelden

1. Saga-Server eintragen (z. B. `https://saga.example.org`) und **Anmelden** – die Anmeldung öffnet sich im Browser
   (lokales Konto, LDAP oder SSO, je nach Server).
2. Oder bequemer: im Browser in Saga oben auf 👤 → **Einstellungen** → **Apps & Geräte** → **App per QR-Code anmelden**
   und den Code mit der App scannen (Mac/Linux: Kopplungslink einfügen).

Die Apps bleiben danach dauerhaft angemeldet. Abmelden lässt sich jedes Gerät jederzeit in Saga unter
„Apps & Geräte“.

## Selbst bauen

Persönliche Einstellungen (Entwickler-Team, App-ID, Testgeräte, Verteil-Befehle) stehen **nie** im Repository,
sondern in lokalen Dateien, die git ignoriert. Vorlagen liegen jeweils daneben:

- `ios/Config/Local.xcconfig.example` → `ios/Config/Local.xcconfig`
- `ios/Config/local.env.example` → `ios/Config/local.env`
- `tools/local.env.example` → `tools/local.env` (Rechner für die Linux-Tests)

Version aller Apps setzen: `tools/set-version.sh 1.2.0` (eine Quelle: `VERSION`).

## Versionen

Die Apps folgen [Semantic Versioning](https://semver.org/lang/de/). Sie zeigen in den Einstellungen ihre eigene
Version und die des Servers; ist eine App für den Server zu alt, weist sie darauf hin.

## Lizenz

[GNU AGPL-3.0](LICENSE) – wie Saga selbst.
