Name:           hlidskjalf
Version:        %{hk_version}
Release:        %{hk_release}%{?dist}
Summary:        Saga – native GNOME-App (Notizen, Datenbanken, Aufgaben)
License:        AGPL-3.0-only
URL:            https://github.com/yggdrasil-sh/saga-apps
Source0:        %{name}-%{version}.tar.gz
BuildArch:      noarch
Requires:       python3 >= 3.11
Requires:       python3-gobject
Requires:       gtk4 >= 4.14
Requires:       libadwaita >= 1.6
Requires:       libsecret
Requires:       python3-pillow
Requires:       desktop-file-utils
Requires:       hicolor-icon-theme

%description
Native Linux-App (GTK4 + libadwaita) für den selbst gehosteten Saga-Server:
Seiten mit Blöcken nativ bearbeiten, Datenbanken (Tabelle, Board, Liste, Galerie,
Kalender), Aufgaben und externe Kalender. Bleibt per Geräte-Schlüssel im
GNOME-Schlüsselbund angemeldet.

%prep
%setup -q

%build

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

%files
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
