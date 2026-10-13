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
Requires:       gtk4 >= 4.14
Requires:       libadwaita >= 1.6
Requires:       libsecret
# Fedora: python3-pillow; AlmaLinux/RHEL 10: python3-pillow (AppStream bzw. EPEL)
Requires:       python3dist(pillow)
Requires:       desktop-file-utils
Requires:       hicolor-icon-theme

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
install -Dm755 data/hlidskjalf %{buildroot}%{_bindir}/hlidskjalf
install -Dm644 data/io.github.yggdrasil_sh.Saga.desktop %{buildroot}%{_datadir}/applications/io.github.yggdrasil_sh.Saga.desktop
install -Dm644 data/io.github.yggdrasil_sh.Saga.metainfo.xml %{buildroot}%{_metainfodir}/io.github.yggdrasil_sh.Saga.metainfo.xml
install -Dm644 data/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg %{buildroot}%{_datadir}/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg
install -Dm644 data/io.github.yggdrasil_sh.Saga.SearchProvider.ini %{buildroot}%{_datadir}/gnome-shell/search-providers/io.github.yggdrasil_sh.Saga.SearchProvider.ini
install -Dm644 data/io.github.yggdrasil_sh.Saga.SearchProvider.service %{buildroot}%{_datadir}/dbus-1/services/io.github.yggdrasil_sh.Saga.SearchProvider.service
python3 -m compileall -q -d %{_datadir}/hlidskjalf/hlidskjalf %{buildroot}%{_datadir}/hlidskjalf/hlidskjalf
# Uebersetzungen: /usr/share/locale/<sprache>/LC_MESSAGES/io.github.yggdrasil_sh.Saga.mo
mkdir -p %{buildroot}%{_datadir}/locale
cp -a build/locale/. %{buildroot}%{_datadir}/locale/
%find_lang io.github.yggdrasil_sh.Saga

%post
update-desktop-database -q %{_datadir}/applications &>/dev/null || :
touch --no-create %{_datadir}/icons/hicolor &>/dev/null || :

%postun
update-desktop-database -q %{_datadir}/applications &>/dev/null || :
if [ $1 -eq 0 ]; then
  touch --no-create %{_datadir}/icons/hicolor &>/dev/null || :
  gtk-update-icon-cache %{_datadir}/icons/hicolor &>/dev/null || :
fi

%posttrans
gtk-update-icon-cache %{_datadir}/icons/hicolor &>/dev/null || :

%files -f io.github.yggdrasil_sh.Saga.lang
%{_bindir}/hlidskjalf
%{_datadir}/hlidskjalf
%{_datadir}/applications/io.github.yggdrasil_sh.Saga.desktop
%{_metainfodir}/io.github.yggdrasil_sh.Saga.metainfo.xml
%{_datadir}/icons/hicolor/scalable/apps/io.github.yggdrasil_sh.Saga.svg
%{_datadir}/gnome-shell/search-providers/io.github.yggdrasil_sh.Saga.SearchProvider.ini
%{_datadir}/dbus-1/services/io.github.yggdrasil_sh.Saga.SearchProvider.service

%changelog
* Sun Oct 11 2026 Saga-Projekt <342035222+yggdrasil-sh@users.noreply.github.com> - 1.0.0-1
- Erste native Linux-Version
