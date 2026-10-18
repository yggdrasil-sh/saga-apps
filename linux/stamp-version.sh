#!/bin/sh
# Versionslabel in einen Bau-Ordner (Kopie von linux/) schreiben - aufgerufen von build-rpm.sh und dem Flatpak-Manifest.
#   sh stamp-version.sh ORDNER LABEL APPSTREAM-VERSION
#   z. B. sh stamp-version.sh /tmp/x/hlidskjalf-2.2.0 2.2.0-rc.3 2.2.0~rc3
# - ORDNER/hlidskjalf/_buildinfo.py: VERSION_LABEL (Anzeige in Einstellungen und "Info")
# - Vorabversion (LABEL != VERSION): zusaetzlicher <release type="development"> in der AppStream-Metainfo
# Ohne LABEL passiert nichts (die App zeigt dann VERSION bzw. im Quellbaum das Label aus tools/version-label.sh).
set -eu
D=${1:?Ordner}
L=${2:-}
A=${3:-$L}
[ -n "$L" ] || exit 0
V=$(sed -n 's/^VERSION = "\([^"]*\)".*/\1/p' "$D/hlidskjalf/__init__.py")
# erlaubt: VERSION selbst oder <Basis>-rc.N mit Basis >= VERSION (nach einem Release z. B. 2.1.1-rc.N bei VERSION 2.1.0)
B=${L%%-rc.*}
ok=
case "$L" in
  "$V") ok=1 ;;
  *-rc.*) if echo "$B" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' && echo "${L##*-rc.}" | grep -Eq '^[0-9]+$' \
             && [ "$(printf '%s\n%s\n' "$V" "$B" | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)" = "$B" ]; then ok=1; fi ;;
esac
[ -n "$ok" ] || { echo "Label $L passt nicht zu VERSION $V" >&2; exit 1; }
printf '%s\n' '"""Erzeugt von stamp-version.sh beim Paketbau."""' "VERSION_LABEL = \"$L\"" > "$D/hlidskjalf/_buildinfo.py"
[ "$L" != "$V" ] || exit 0
M="$D/data/io.github.yggdrasil_sh.Saga.metainfo.xml"
[ -f "$M" ] || exit 0
TODAY=$(date -u +%Y-%m-%d)
awk -v a="$A" -v d="$TODAY" -v l="$L" '
  { print }
  /<releases>/ && !done {
    print "    <release version=\"" a "\" date=\"" d "\" type=\"development\">"
    print "      <description>"
    print "        <p>Test version " l " (release candidate) from the testing branch - not meant for everyday use.</p>"
    print "      </description>"
    print "    </release>"
    done = 1
  }' "$M" > "$M.tmp"
mv "$M.tmp" "$M"
