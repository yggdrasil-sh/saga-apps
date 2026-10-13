#!/bin/sh
# RPM bauen (AlmaLinux 10 / Fedora, lokal oder in CI). Voraussetzung: dnf install -y rpm-build python3
#   sh build-rpm.sh                 -> dist/saga-<version>-<release>.noarch.rpm (+ ~/rpmbuild/RPMS/noarch/)
#   HK_RELEASE=2 sh build-rpm.sh    -> Release-Nummer (Standard 1)
# Die Version steht in hlidskjalf/__init__.py (VERSION = "x.y.z").
set -eu
cd "$(dirname "$0")"
command -v rpmbuild >/dev/null || { echo "rpmbuild fehlt: dnf install -y rpm-build python3" >&2; exit 1; }
V=$(sed -n 's/^VERSION = "\([^"]*\)".*/\1/p' hlidskjalf/__init__.py)
[ -n "$V" ] || { echo "VERSION in hlidskjalf/__init__.py nicht gefunden" >&2; exit 1; }
R=${HK_RELEASE:-1}
TOP=${HK_RPM_TOPDIR:-$HOME/rpmbuild}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/hlidskjalf-$V" "$TOP/SOURCES" "$TOP/RPMS" "$TOP/BUILD" "$TOP/SPECS" "$TOP/SRPMS"
cp -a hlidskjalf data "$T/hlidskjalf-$V/"
find "$T" -name __pycache__ -prune -exec rm -rf {} +
tar -C "$T" -czf "$TOP/SOURCES/hlidskjalf-$V.tar.gz" "hlidskjalf-$V"
rpmbuild -bb --define "_topdir $TOP" --define "hk_version $V" --define "hk_release $R" hlidskjalf.spec
# Ergebnis mit verstaendlichem Namen nach dist/ (Paketname im RPM bleibt hlidskjalf)
RPM=$(ls -1t "$TOP"/RPMS/noarch/hlidskjalf-"$V"-"$R".*noarch.rpm "$TOP"/RPMS/noarch/hlidskjalf-"$V"-"$R".noarch.rpm 2>/dev/null | head -n 1)
[ -n "$RPM" ] || { echo "RPM nicht gefunden" >&2; exit 1; }
mkdir -p dist
cp -f "$RPM" "dist/saga-$V-$R.noarch.rpm"
echo "$RPM"
echo "$(pwd)/dist/saga-$V-$R.noarch.rpm"
