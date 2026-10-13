#!/bin/sh
# Uebersetzungen pflegen: Texte aus den Quellen holen (xgettext -> po/saga.pot) und in die Kataloge einarbeiten (msgmerge).
#   sh po/update-po.sh           -> po/saga.pot neu, po/<sprache>.po aktualisiert (neue Texte leer, geaenderte "fuzzy")
#   sh po/update-po.sh --check   -> danach Pruefung je Sprache (msgfmt --check --statistics)
# Danach neue und "fuzzy" Eintraege in po/*.po uebersetzen und die Markierung "#, fuzzy" entfernen.
# Quelltexte sind englisch: _("...") bzw. ngettext("...", "...", n); Platzhalter {name} (str.format) nie uebersetzen.
# Neue Sprache: Kuerzel in po/LINGUAS eintragen, "msginit -l <kuerzel> -i po/saga.pot -o po/<kuerzel>.po" und uebersetzen.
# Voraussetzung: gettext (Fedora/AlmaLinux: dnf install gettext, macOS: brew install gettext)
set -eu
cd "$(dirname "$0")/.."
for c in xgettext msgmerge msgfmt; do
  command -v $c >/dev/null || { echo "$c fehlt (gettext installieren)" >&2; exit 1; }
done
xgettext --from-code=UTF-8 --language=Python --keyword=_ --keyword=ngettext:1,2 --keyword=pgettext:1c,2 \
  --add-comments=Translators: --sort-by-file --package-name=saga \
  --msgid-bugs-address=https://github.com/yggdrasil-sh/saga-apps/issues \
  -o po/saga.pot hlidskjalf/*.py
for l in $(cat po/LINGUAS); do
  msgmerge --quiet --update --backup=none --previous "po/$l.po" po/saga.pot
done
if [ "${1:-}" = "--check" ]; then
  for l in $(cat po/LINGUAS); do
    printf '%s: ' "$l"
    msgfmt --check --statistics -o /dev/null "po/$l.po"
  done
fi
