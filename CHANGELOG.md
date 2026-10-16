# Changelog

Alle nennenswerten Änderungen an den Saga-Apps. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionen nach [Semantic Versioning](https://semver.org/lang/de/). Neue Version: `tools/set-version.sh x.y.z`,
Abschnitt hier ergänzen, auf `main` bringen – GitHub baut und veröffentlicht dann alle Downloads.

## [Unveröffentlicht]

### Neu
- **Android:** Datenbanken werden jetzt nativ angezeigt, als ganze Seite und als Block auf einer Seite.
  Unterstützt werden die Ansichten Liste/Tabelle, Board, Galerie und Kalender mit Filtern, Sortierung und
  bedingten Farben wie im Web. Haken lassen sich direkt setzen, neue Einträge werden passend zum Filter
  angelegt, auch aus Vorlagen.
- **Android:** Die Blöcke Aufgaben, Kalender, Schaltflächen, Einbetten, Anhang und Tabelle werden angezeigt
  statt „wird hier noch nicht unterstützt“.

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
