Name:           hlidskjalf
Version:        %{hk_version}
Release:        %{hk_release}%{?dist}
Summary:        Saga – native GNOME app (notes, databases, tasks)
Summary(de):    Saga – native GNOME-App (Notizen, Datenbanken, Aufgaben)
Summary(es):    Saga – app nativa de GNOME (notas, bases de datos, tareas)
License:        AGPL-3.0-only
URL:            https://github.com/yggdrasil-sh/saga-apps
Source0:        %{name}-%{version}.tar.gz
BuildArch:      noarch
BuildRequires:  python3
# msgfmt: Uebersetzungen (po/*.po -> .mo)
BuildRequires:  gettext
Requires:       python3 >= 3.11
Requires:       python3-gobject
# CSS-Variablen (var(--accent-color) ...) brauchen GTK >= 4.16 / libadwaita >= 1.6
Requires:       gtk4 >= 4.16
Requires:       libadwaita >= 1.6
# Schluesselbund ueber GObject-Introspection (gi.repository.Secret; Fedora/RHEL liefern dafuer kein typelib(Secret))
Requires:       libsecret
# Fedora: python3-pillow; AlmaLinux/RHEL 10: python3-pillow (AppStream bzw. EPEL)
Requires:       python3dist(pillow)
Requires:       hicolor-icon-theme
# Seitensymbole, Callouts und Hinweise sind Emoji (wie im Web) - ohne Farb-Emoji-Schrift erscheinen Ersatzkaestchen
# (die App zeigt dann fuer Seitensymbole ein einfarbiges Ersatzsymbol). Fedora und AlmaLinux/RHEL 10: gleicher Paketname
Requires:       google-noto-color-emoji-fonts
# Chats mit Ende-zu-Ende-Verschluesselung: libsodium ueber PyNaCl 1.6.x (Fedora/AlmaLinux: python3-pynacl)
Requires:       python3dist(pynacl) >= 1.6

%description
Saga is a native GNOME app (GTK4 and libadwaita) for a self-hosted
Saga server. Pages made of blocks can be edited directly, databases show
up as table, board, list, gallery or calendar, plus tasks, external
calendars (CalDAV) and GNOME search. The sign-in is kept with a device
key in the keyring (libsecret). Available in English, German and Spanish.

%description -l de
Saga ist eine native GNOME-App (GTK4 und libadwaita) für einen selbst
gehosteten Saga-Server. Seiten mit Blöcken lassen sich direkt bearbeiten,
Datenbanken erscheinen als Tabelle, Board, Liste, Galerie oder Kalender,
dazu kommen Aufgaben, externe Kalender (CalDAV) und die GNOME-Suche.
Die Anmeldung bleibt über einen Geräte-Schlüssel im Schlüsselbund
(libsecret) erhalten. Sprachen: Deutsch, Englisch, Spanisch.

%description -l es
Saga es una app nativa de GNOME (GTK4 y libadwaita) para un servidor
Saga propio. Las páginas de bloques se editan directamente, las bases de
datos se muestran como tabla, tablero, lista, galería o calendario, y
además hay tareas, calendarios externos (CalDAV) y la búsqueda de GNOME.
La sesión se mantiene con una clave del dispositivo en el llavero
(libsecret). Idiomas: español, alemán e inglés.

%prep
%setup -q

%build
sh po/build-mo.sh build/locale

%install
install -d %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf
cp -a hlidskjalf/*.py hlidskjalf/style.css %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/
# eigene Symbole (z. B. saga-globe-symbolic), App-eigener Suchpfad (main.py), nicht im System-Symbolthema
install -d %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons
install -m644 hlidskjalf/icons/*.svg %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/
# Testmodus (HK_TEST, ctypes, fuehrt Skripte aus) gehoert nicht ins Paket
rm -f %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/testing.py
install -Dm755 data/hlidskjalf %{buildroot}%{_bindir}/hlidskjalf
install -Dm644 data/io.github.yggdrasil_sh.Saga.desktop %{buildroot}%{_datadir}/applications/io.github.yggdrasil_sh.Saga.desktop
install -Dm644 data/io.github.yggdrasil_sh.Saga.metainfo.xml %{buildroot}%{_metainfodir}/io.github.yggdrasil_sh.Saga.metainfo.xml
install -Dm644 data/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg %{buildroot}%{_datadir}/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg
install -Dm644 data/io.github.yggdrasil_sh.Saga.SearchProvider.ini %{buildroot}%{_datadir}/gnome-shell/search-providers/io.github.yggdrasil_sh.Saga.SearchProvider.ini
install -Dm644 data/io.github.yggdrasil_sh.Saga.SearchProvider.service %{buildroot}%{_datadir}/dbus-1/services/io.github.yggdrasil_sh.Saga.SearchProvider.service
# Mitteilungen (UnifiedPush): ein Verteiler startet die App per D-Bus ohne Fenster (--gapplication-service)
install -Dm644 data/io.github.yggdrasil_sh.Saga.service %{buildroot}%{_datadir}/dbus-1/services/io.github.yggdrasil_sh.Saga.service
python3 -m compileall -q -d %{_datadir}/hlidskjalf/hlidskjalf %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf
# Uebersetzungen: /usr/share/locale/<sprache>/LC_MESSAGES/io.github.yggdrasil_sh.Saga.mo
mkdir -p %{buildroot}%{_datadir}/locale
cp -a build/locale/. %{buildroot}%{_datadir}/locale/
%find_lang io.github.yggdrasil_sh.Saga

%check
# Pruefungen nur, wenn die Werkzeuge da sind (CI installiert nur rpm-build, python3, gettext)
if command -v desktop-file-validate >/dev/null; then
  desktop-file-validate %{buildroot}%{_datadir}/applications/io.github.yggdrasil_sh.Saga.desktop
fi
if command -v appstreamcli >/dev/null; then
  appstreamcli validate --no-net %{buildroot}%{_metainfodir}/io.github.yggdrasil_sh.Saga.metainfo.xml
fi
test ! -e %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/testing.py
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-globe-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-comment-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-people-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-lock-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-page-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-database-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-bell-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-chat-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-assistant-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/icons/saga-send-symbolic.svg
test -s %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf/chatcrypto.py

# Desktop-Datenbank und Symbol-Zwischenspeicher aktualisieren Fedora/RHEL selbst (Datei-Trigger) - keine Scriptlets noetig

%files -f io.github.yggdrasil_sh.Saga.lang
%{_bindir}/hlidskjalf
%{_datadir}/hlidskjalf
%{_datadir}/applications/io.github.yggdrasil_sh.Saga.desktop
%{_metainfodir}/io.github.yggdrasil_sh.Saga.metainfo.xml
%{_datadir}/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg
%{_datadir}/gnome-shell/search-providers/io.github.yggdrasil_sh.Saga.SearchProvider.ini
%{_datadir}/dbus-1/services/io.github.yggdrasil_sh.Saga.SearchProvider.service
%{_datadir}/dbus-1/services/io.github.yggdrasil_sh.Saga.service

%changelog
* Thu Oct 15 2026 Saga-Projekt <342035222+yggdrasil-sh@users.noreply.github.com> - 2.0.0-1
- Live-Kanal, Benachrichtigungen, Anwesenheit; Chats mit Ende-zu-Ende-Verschluesselung (PyNaCl)
- Mitteilungen ueber UnifiedPush (D-Bus), KI-Assistent, Seiten anderer Saga-Instanzen (Foederation)
* Wed Oct 14 2026 Saga-Projekt <342035222+yggdrasil-sh@users.noreply.github.com> - 1.0.0-1
- Kopplung und Serverwechsel mit Rueckfrage, Schluessel an ihren Server gebunden
- Speichern ohne Datenverlust (Konflikte, Entwuerfe, Schliessen wartet), Tastatur- und Touch-Bedienung
* Sun Oct 11 2026 Saga-Projekt <342035222+yggdrasil-sh@users.noreply.github.com> - 1.0.0-1
- Erste native Linux-Version
