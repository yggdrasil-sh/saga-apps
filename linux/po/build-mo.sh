#!/bin/sh
# Kataloge uebersetzen: po/<sprache>.po -> <ziel>/<sprache>/LC_MESSAGES/io.github.yggdrasil_sh.Saga.mo
#   sh po/build-mo.sh                    -> build/locale (wird beim Start aus dem Quellordner gefunden)
#   sh po/build-mo.sh /usr/share/locale  -> Installation (RPM: %{buildroot}%{_datadir}/locale, Flatpak: /app/share/locale)
# Voraussetzung: msgfmt (gettext). Ohne Kataloge laeuft die App englisch.
set -eu
cd "$(dirname "$0")/.."
OUT=${1:-build/locale}
DOMAIN=io.github.yggdrasil_sh.Saga
command -v msgfmt >/dev/null || { echo "msgfmt fehlt (gettext installieren)" >&2; exit 1; }
for l in $(cat po/LINGUAS); do
  mkdir -p "$OUT/$l/LC_MESSAGES"
  msgfmt --check -o "$OUT/$l/LC_MESSAGES/$DOMAIN.mo" "po/$l.po"
done
