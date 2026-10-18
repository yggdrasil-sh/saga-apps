#!/bin/sh
# Angezeigte Version der Apps ("Versionslabel"). VERSION bleibt die geplante Version (x.y.z, numerisch fuer
# CFBundleShortVersionString, versionCode, RPM-Version); nur das offizielle Release zeigt sie ohne Zusatz.
#   x.y.z         offizielles Release: SAGA_RELEASE=1 (CI-Release-Job) oder HEAD traegt genau den Tag vx.y.z
#   x.y.z-rc.N    alles andere (testing-Vorabversion, Ad-hoc/MDM, lokale Builds), solange vx.y.z noch nicht
#                 veroeffentlicht ist; N = Commits seit dem letzten Release-Tag (v<a.b.c> ohne Zusatz)
#   x.y.z+dev.N   Builds nach dem Release vx.y.z, bevor VERSION erhoeht wurde (z. B. main ohne Versionssprung)
#   x.y.z-rc.0    kein Git/kein Release-Tag erreichbar (Quell-Archiv, flacher Clone ohne Tags)
# SAGA_VERSION_LABEL (falls gesetzt) gewinnt immer - so bekommen alle CI-Jobs dasselbe Label aus dem Versions-Job.
#
#   tools/version-label.sh                  -> Label, z. B. 2.2.0-rc.3
#   tools/version-label.sh --rpm-release    -> RPM-Release: 1 (Release), 0.rc3 (sortiert vor 2.2.0-1), 1.dev3
#   tools/version-label.sh --build-number   -> CFBundleVersion: SAGA_BUILD_NUMBER oder JJJJMMTTHHMM
#   tools/version-label.sh --appstream      -> Version fuer AppStream-Metainfo: 2.2.0~rc3 bzw. 2.2.0+dev3
#   tools/version-label.sh --xcconfig DATEI -> schreibt SAGA_VERSION_LABEL und CURRENT_PROJECT_VERSION (iOS/macOS)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
V=$(tr -d ' \t\r\n' < "$ROOT/VERSION")
echo "$V" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "VERSION ist kein SemVer: $V" >&2; exit 1; }

g() { git -C "$ROOT" "$@" 2>/dev/null; }

label() {
  if [ -n "${SAGA_VERSION_LABEL:-}" ]; then
    case "$SAGA_VERSION_LABEL" in
      "$V"|"$V"-rc.*|"$V"+dev.*) echo "$SAGA_VERSION_LABEL"; return ;;
      *) echo "SAGA_VERSION_LABEL=$SAGA_VERSION_LABEL passt nicht zu VERSION $V" >&2; exit 1 ;;
    esac
  fi
  case "${SAGA_RELEASE:-}" in 1|true|yes) echo "$V"; return ;; esac
  g rev-parse --git-dir >/dev/null || { echo "$V-rc.0"; return; }
  # genau der Release-Commit (z. B. lokal den Tag ausgecheckt)
  if g describe --tags --exact-match --match "v$V" HEAD >/dev/null; then echo "$V"; return; fi
  T=$(g describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' --exclude '*-*' --exclude '*+*' HEAD) || T=
  [ -n "$T" ] || { echo "$V-rc.0"; return; }
  N=$(g rev-list --count "$T..HEAD") || N=0
  if [ "$T" = "v$V" ]; then echo "$V+dev.$N"; else echo "$V-rc.$N"; fi
}

L=$(label)
case "${1:-}" in
  "") echo "$L" ;;
  --rpm-release)
    case "$L" in
      *-rc.*) echo "0.rc${L##*-rc.}" ;;
      *+dev.*) echo "1.dev${L##*+dev.}" ;;
      *) echo 1 ;;
    esac ;;
  --appstream)
    case "$L" in
      *-rc.*) echo "$V~rc${L##*-rc.}" ;;
      *+dev.*) echo "$V+dev${L##*+dev.}" ;;
      *) echo "$V" ;;
    esac ;;
  --build-number) echo "${SAGA_BUILD_NUMBER:-$(date +%Y%m%d%H%M)}" ;;
  --xcconfig)
    F=${2:?Zieldatei, z. B. ios/Config/Version.xcconfig}
    BN=${SAGA_BUILD_NUMBER:-$(date +%Y%m%d%H%M)}
    printf '%s\n' "// Erzeugt von tools/version-label.sh - nicht versioniert, nicht von Hand aendern" \
      "SAGA_VERSION_LABEL = $L" "CURRENT_PROJECT_VERSION = $BN" > "$F.tmp"
    mv "$F.tmp" "$F"
    echo "$L ($BN)" ;;
  *) echo "Aufruf: $0 [--rpm-release|--appstream|--build-number|--xcconfig DATEI]" >&2; exit 2 ;;
esac
