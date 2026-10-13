<p align="center">
  <img src="linux/data/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg" width="96" height="96" alt="Saga">
</p>

<h1 align="center">Saga Apps</h1>

<p align="center">
  Native apps for <a href="https://github.com/yggdrasil-sh/saga">Saga</a> – your self-hosted home for services, notes and tasks.<br>
  iPhone · iPad · Mac · Linux (GNOME) · Android
</p>

<p align="center">
  <b>English</b> · <a href="README.de.md">Deutsch</a> · <a href="README.es.md">Español</a>
</p>

---

The apps connect to your own Saga server. Pages, databases, tasks and events are shown and edited
**natively** – not in a wrapped website. They follow your system language: English, German or Spanish
(any other language falls back to English).

| Platform | Technology | Folder | Instructions |
|---|---|---|---|
| iOS, iPadOS, macOS | SwiftUI, WidgetKit | [`ios/`](ios/) | [ios/README.md](ios/README.md) |
| Linux (GNOME) | Python, GTK4 + libadwaita, RPM | [`linux/`](linux/) | [linux/README.md](linux/README.md) |
| Android | Kotlin, Jetpack Compose | [`android/`](android/) | [android/README.md](android/README.md) |

## What it looks like

<p align="center">
  <img src="docs/screenshots/ios-start.png" width="200" alt="iPhone: home with tasks and recent pages">
  <img src="docs/screenshots/ios-dunkel-board.png" width="200" alt="iPhone: database as a board in dark mode">
  <img src="docs/screenshots/android-dienste.png" width="200" alt="Android: services by group">
  <img src="docs/screenshots/android-heute.png" width="200" alt="Android: today with events and tasks">
</p>
<p align="center">
  <img src="docs/screenshots/ipad-projekte.png" width="620" alt="iPad: sidebar and database as a board">
</p>

## Download

Ready-made packages are available under [Releases](https://github.com/yggdrasil-sh/saga-apps/releases/latest):

| System | File |
|---|---|
| macOS 26 or later | `Saga-<version>-macOS.dmg` – signed and notarized by Apple |
| Linux (Fedora, AlmaLinux/RHEL 10 with EPEL) | `saga-<version>-1.noarch.rpm` |
| Linux (any distribution with Flatpak) | `saga-<version>-x86_64.flatpak` (`flatpak install --user saga-….flatpak`) |
| Android 8 or later | `Saga-<version>.apk` (allow installation from unknown sources) |

iPhone and iPad: build it yourself (see [ios/README.md](ios/README.md)) – there is no App Store version yet.

## Signing in

1. Enter your Saga server (e.g. `https://saga.example.org`) and tap **Sign in** – sign-in opens in the browser
   (local account, LDAP or SSO, depending on the server).
2. Or, more conveniently: in Saga in your browser, open 👤 at the top → **Settings** → **Apps & devices** →
   **Sign in with QR code** and scan the code with the app (Mac/Linux: paste the pairing link).

The apps then stay signed in. You can sign out any device at any time in Saga under "Apps & devices".

## Building it yourself

Personal settings (developer team, app ID, test devices, deployment commands) are **never** stored in the
repository, but in local files that git ignores. Templates sit right next to them:

- `ios/Config/Local.xcconfig.example` → `ios/Config/Local.xcconfig`
- `ios/Config/local.env.example` → `ios/Config/local.env`
- `tools/local.env.example` → `tools/local.env` (machines for the Linux tests)

Set the version of all apps: `tools/set-version.sh 1.2.0` (single source: `VERSION`). When a change to
`VERSION` lands on `main`, GitHub builds all packages and publishes them as a release
(`.github/workflows/release.yml`). New work lands on the `testing` branch first: every push there builds all
packages as a dry run (without a release).

## Versions

The apps follow [Semantic Versioning](https://semver.org/). Their settings show the app's own version and the
server's; if an app is too old for the server, it tells you.

## License

[GNU AGPL-3.0](LICENSE) – just like Saga itself.
