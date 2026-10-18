#!/bin/sh
# Angezeigte Version der Apps ("Versionslabel"). VERSION bleibt die geplante Version (x.y.z, numerisch fuer
# CFBundleShortVersionString, versionCode); nur das offizielle Release zeigt sie ohne Zusatz.
#   x.y.z         offizielles Release: SAGA_RELEASE=1 (CI-Release-Job) oder HEAD traegt genau den Tag vx.y.z
#   x.y.z-rc.N    alles andere (testing-Vorabversion, Ad-hoc/MDM, lokale Builds); N = Commits seit dem letzten
#                 Release-Tag (v<a.b.c> ohne Zusatz). Ist vx.y.z (oder neuer) schon veroeffentlicht, bevor VERSION
#                 erhoeht wurde, zaehlt der RC zur naechsten Patch-Version des Tags (z. B. 2.1.1-rc.N nach v2.1.0) -
#                 wie beim Server (tools/rc-version.sh): Testversionen sind immer erkennbar neuer als das Release.
#   x.y.z-rc.0    kein Git/kein Release-Tag erreichbar (Quell-Archiv, flacher Clone ohne Tags)
# SAGA_VERSION_LABEL (falls gesetzt) gewinnt immer - so bekommen alle CI-Jobs dasselbe Label aus dem Versions-Job.
#
#   tools/version-label.sh                  -> Label, z. B. 2.2.0-rc.3
#   tools/version-label.sh --base           -> Version ohne -rc.N (RPM-Version), z. B. 2.2.0 bzw. 2.1.1
#   tools/version-label.sh --rpm-release    -> RPM-Release: 1 (Release), 0.rc3 (sortiert vor <Basis>-1)
#   tools/version-label.sh --build-number   -> CFBundleVersion: SAGA_BUILD_NUMBER oder JJJJMMTTHHMM
#   tools/version-label.sh --appstream      -> Version fuer AppStream-Metainfo: 2.2.0 bzw. 2.2.0~rc3
#   tools/version-label.sh --xcconfig DATEI -> schreibt SAGA_VERSION_LABEL und CURRENT_PROJECT_VERSION (iOS/macOS)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
V=$(tr -d ' \t\r\n' < "$ROOT/VERSION")
SEMVER='^[0-9]+\.[0-9]+\.[0-9]+$'
echo "$V" | grep -Eq "$SEMVER" || { echo "VERSION ist kein SemVer: $V" >&2; exit 1; }

g() { git -C "$ROOT" "$@" 2>/dev/null; }
# $1 <= $2 (numerisch, x.y.z)
le() { [ "$(printf '%s\n%s\n' "$1" "$2" | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)" = "$2" ]; }
next_patch() { echo "$1" | awk -F. '{ printf "%d.%d.%d", $1, $2, $3 + 1 }'; }

label() {
  if [ -n "${SAGA_VERSION_LABEL:-}" ]; then
    B=${SAGA_VERSION_LABEL%%-rc.*}
    case "$SAGA_VERSION_LABEL" in
      "$V") echo "$V"; return ;;
      *-rc.*)
        if echo "$B" | grep -Eq "$SEMVER" && echo "${SAGA_VERSION_LABEL##*-rc.}" | grep -Eq '^[0-9]+$' && le "$V" "$B"; then
          echo "$SAGA_VERSION_LABEL"; return
        fi ;;
    esac
    echo "SAGA_VERSION_LABEL=$SAGA_VERSION_LABEL passt nicht zu VERSION $V" >&2; exit 1
  fi
  case "${SAGA_RELEASE:-}" in 1|true|yes) echo "$V"; return ;; esac
  g rev-parse --git-dir >/dev/null || { echo "$V-rc.0"; return; }
  # genau der Release-Commit (z. B. lokal den Tag ausgecheckt)
  if g describe --tags --exact-match --match "v$V" HEAD >/dev/null; then echo "$V"; return; fi
  T=$(g describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' --exclude '*-*' --exclude '*+*' HEAD) || T=
  [ -n "$T" ] || { echo "$V-rc.0"; return; }
  N=$(g rev-list --count "$T..HEAD") || N=0
  B=$V
  TV=${T#v}
  if echo "$TV" | grep -Eq "$SEMVER" && le "$V" "$TV"; then B=$(next_patch "$TV"); fi
  echo "$B-rc.$N"
}

L=$(label)
case "$L" in *-rc.*) B=${L%%-rc.*}; RC=${L##*-rc.} ;; *) B=$L; RC= ;; esac
case "${1:-}" in
  "") echo "$L" ;;
  --base) echo "$B" ;;
  --rpm-release) if [ -n "$RC" ]; then echo "0.rc$RC"; else echo 1; fi ;;
  --appstream) if [ -n "$RC" ]; then echo "$B~rc$RC"; else echo "$B"; fi ;;
  --build-number) echo "${SAGA_BUILD_NUMBER:-$(date +%Y%m%d%H%M)}" ;;
  --xcconfig)
    F=${2:?Zieldatei, z. B. ios/Config/Version.xcconfig}
    BN=${SAGA_BUILD_NUMBER:-$(date +%Y%m%d%H%M)}
    printf '%s\n' "// Erzeugt von tools/version-label.sh - nicht versioniert, nicht von Hand aendern" \
      "SAGA_VERSION_LABEL = $L" "CURRENT_PROJECT_VERSION = $BN" > "$F.tmp"
    mv "$F.tmp" "$F"
    echo "$L ($BN)" ;;
  *) echo "Aufruf: $0 [--base|--rpm-release|--appstream|--build-number|--xcconfig DATEI]" >&2; exit 2 ;;
esac
