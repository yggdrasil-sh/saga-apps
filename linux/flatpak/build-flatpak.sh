#!/bin/sh
# Flatpak-Bundle bauen (lokal oder in CI). Voraussetzung: flatpak und flatpak-builder.
#   sh flatpak/build-flatpak.sh     -> dist/saga-<version>-<arch>.flatpak
# GNOME-Laufzeit und SDK werden bei Bedarf von Flathub installiert (Nutzer-Installation).
# Umgebung: HK_FLATPAK_ARCH (z. B. aarch64, Standard: Architektur dieses Rechners),
#           HK_FLATPAK_BRANCH (Standard: stable)
set -eu
cd "$(dirname "$0")/.."
ID=io.github.yggdrasil_sh.Saga
MANIFEST=flatpak/$ID.yml
FLATHUB=https://dl.flathub.org/repo/flathub.flatpakrepo
for c in flatpak flatpak-builder; do
  command -v $c >/dev/null || { echo "$c fehlt (z. B. dnf install -y flatpak flatpak-builder)" >&2; exit 1; }
done
V=$(sed -n 's/^VERSION = "\([^"]*\)".*/\1/p' hlidskjalf/__init__.py)
[ -n "$V" ] || { echo "VERSION in hlidskjalf/__init__.py nicht gefunden" >&2; exit 1; }
ARCH=${HK_FLATPAK_ARCH:-$(flatpak --default-arch)}
BRANCH=${HK_FLATPAK_BRANCH:-stable}
flatpak --user remote-add --if-not-exists flathub "$FLATHUB"
# Arbeitsordner ausserhalb des Quelltexts (die Quelle "dir" kopiert linux/ in den Bau)
W=$(mktemp -d "${TMPDIR:-/tmp}/saga-flatpak.XXXXXX")
trap 'rm -rf "$W"' EXIT
flatpak-builder --user --arch="$ARCH" --default-branch="$BRANCH" --install-deps-from=flathub \
  --force-clean --disable-rofiles-fuse --state-dir="$W/state" --repo="$W/repo" "$W/build" "$MANIFEST"
mkdir -p dist
OUT=dist/saga-$V-$ARCH.flatpak
flatpak build-bundle --arch="$ARCH" --runtime-repo="$FLATHUB" "$W/repo" "$OUT" "$ID" "$BRANCH"
echo "$(pwd)/$OUT"
