#!/bin/sh
# RPM bauen (AlmaLinux 10 / Fedora, lokal oder in CI). Voraussetzung: dnf install -y rpm-build python3 gettext
#   sh build-rpm.sh                 -> dist/saga-<version>-<release>.noarch.rpm (+ ~/rpmbuild/RPMS/noarch/)
#   HK_RELEASE=2 sh build-rpm.sh    -> Release-Nummer erzwingen
# Die Version steht in hlidskjalf/__init__.py (VERSION = "x.y.z"). Release-Nummer und angezeigte Version kommen aus
# ../tools/version-label.sh: offizielles Release (SAGA_RELEASE=1) -> x.y.z-1, sonst Vorabversion x.y.z-0.rcN
# (sortiert vor x.y.z-1, die App zeigt "x.y.z-rc.N"; nach einem Release ohne
# Versionssprung x.y.(z+1)-0.rcN). SAGA_VERSION_LABEL gibt das Label vor (CI).
set -eu
cd "$(dirname "$0")"
command -v rpmbuild >/dev/null || { echo "rpmbuild fehlt: dnf install -y rpm-build python3 gettext" >&2; exit 1; }
command -v msgfmt >/dev/null || { echo "msgfmt fehlt: dnf install -y gettext" >&2; exit 1; }
V=$(sed -n 's/^VERSION = "\([^"]*\)".*/\1/p' hlidskjalf/__init__.py)
[ -n "$V" ] || { echo "VERSION in hlidskjalf/__init__.py nicht gefunden" >&2; exit 1; }
VL=../tools/version-label.sh
LABEL=$(sh "$VL")
R=${HK_RELEASE:-$(sh "$VL" --rpm-release)}
# RPM-Version = Basis des Labels: VERSION, oder nach einem Release (VERSION noch nicht erhoeht) die naechste
# Patch-Version (2.1.1-0.rcN) - so ist die Testversion auch fuer dnf neuer als das Release 2.1.0-1
PV=$(sh "$VL" --base)
echo "Saga $LABEL (RPM $PV-$R)"
TOP=${HK_RPM_TOPDIR:-$HOME/rpmbuild}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/hlidskjalf-$PV" "$TOP/SOURCES" "$TOP/RPMS" "$TOP/BUILD" "$TOP/SPECS" "$TOP/SRPMS"
cp -a hlidskjalf data po "$T/hlidskjalf-$PV/"
rm -f "$T/hlidskjalf-$PV/hlidskjalf/_buildinfo.py"
sh stamp-version.sh "$T/hlidskjalf-$PV" "$LABEL" "$(sh "$VL" --appstream)"
find "$T" -name __pycache__ -prune -exec rm -rf {} +
tar -C "$T" -czf "$TOP/SOURCES/hlidskjalf-$PV.tar.gz" "hlidskjalf-$PV"
rpmbuild -bb --define "_topdir $TOP" --define "hk_version $PV" --define "hk_release $R" hlidskjalf.spec
# Ergebnis mit verstaendlichem Namen nach dist/ (Paketname im RPM bleibt hlidskjalf)
RPM=$(ls -1t "$TOP"/RPMS/noarch/hlidskjalf-"$PV"-"$R".*noarch.rpm "$TOP"/RPMS/noarch/hlidskjalf-"$PV"-"$R".noarch.rpm 2>/dev/null | head -n 1)
[ -n "$RPM" ] || { echo "RPM nicht gefunden" >&2; exit 1; }
mkdir -p dist
cp -f "$RPM" "dist/saga-$PV-$R.noarch.rpm"
echo "$RPM"
echo "$(pwd)/dist/saga-$PV-$R.noarch.rpm"
