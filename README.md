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
**natively** – not in a wrapped website. They speak **five languages**: English, German, Spanish, Thai and
Russian. They follow your system language (any other language falls back to English), or you pick one under
**Settings → Language** – even before signing in, which helps on a shared or borrowed device. Thai and Russian
are machine-translated; corrections by native speakers are welcome.

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

## Publishing pages

With Saga server 1.2 or later, every app can make a page public: page menu → **Publish …**. The first time,
you choose a **public name** (it appears in the address `…/@name/…`, never your username). After that you set
the address, **Also publish subpages**, **Allow search engines** and **As profile page**, copy or open the link,
or **Stop publishing**. Public pages show **Public** in the page header; you can change your public name under
**Settings → Account**. On older servers the menu item does not appear.

## New in 2.1 (with Saga server 2.1)

- **Audio and video calls** in direct chats, end-to-end encrypted. iPhone/iPad ring via CallKit (needs an APNs key
  on the server), Android as a full-screen call (in the background with a UnifiedPush distributor), the Mac with its
  own call window; on Linux the app rings and the call runs in the browser.
- **Profile pictures** wherever people appear; upload your own (with cropping) in the settings.
- **Sign-in & security:** status of passkeys and authenticator app in the settings; two-factor sign-in runs in the
  browser window.
- **Fast chat images** with previews, **Immich in chats**, pasting images and copying image blocks.
- **AI with a name and picture:** if the admin gives the AI a name and picture (e.g. "Marin"), the apps show it
  everywhere; in chats where it is allowed you ask it with **@Marin** – only those messages (plus up to 12 before
  them) go to the AI, everything else stays end-to-end encrypted.
- **Archive and delete chats:** archived chats move to a collapsible "Archived" section; **Delete chat …** for
  yourself or – if the server allows it – for everyone, including the local cache on all your devices.
- **Choose the language** in the app, plus **Thai and Russian** as new languages (these work with any server).

The new features need Saga server 2.1; with older servers the apps work as before.

## New in 2.0 (with Saga server 2.0)

- **Sharing and comments:** share pages with people and groups (view, comment, edit), comments on blocks with
  replies and "Resolve".
- **Notifications:** a bell with everything that concerns you; on pages you see who is also here or editing.
- **Chats** with end-to-end encryption: direct, group and page chats. On first use you get a **recovery key**;
  confirm new devices with a code and compare the **safety number** with the other person.
- **Push notifications** (never with content): iOS/iPadOS via Apple Push (the server admin enters an APNs key),
  Android and Linux via [UnifiedPush](https://unifiedpush.org), e.g. with ntfy – no Google services needed.
- **AI assistant** (if the admin turns it on): **Ask your pages** and **This page**.
- **Federation:** pages from other Saga instances appear under **Shared with me**; share with `name@server`.

The new features need Saga server 2.0. All of this is optional – with older servers the apps work as before.

## Building it yourself

Personal settings (developer team, app ID, test devices, deployment commands) are **never** stored in the
repository, but in local files that git ignores. Templates sit right next to them:

- `ios/Config/Local.xcconfig.example` → `ios/Config/Local.xcconfig`
- `ios/Config/local.env.example` → `ios/Config/local.env`
- `tools/local.env.example` → `tools/local.env` (machines for the Linux tests)

Set the version of all apps: `tools/set-version.sh 2.1.0` (single source: `VERSION`). When a change to
`VERSION` lands on `main`, GitHub builds all packages and publishes them as a release
(`.github/workflows/release.yml`). New work lands on the `testing` branch first: every push there builds all
packages and replaces the pre-release `v<version>-testing` (never "Latest").

## Versions

The apps follow [Semantic Versioning](https://semver.org/). Their settings show the app's own version and the
server's; if an app is too old for the server, it tells you.

## License

[GNU AGPL-3.0](LICENSE) – just like Saga itself.
