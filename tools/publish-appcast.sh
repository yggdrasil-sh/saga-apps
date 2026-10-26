#!/bin/sh
# Legt einen Appcast (Selbst-Update der Mac-App, tools/appcast.py) oder eine Update-Info (Update-Hinweis aller Apps,
# tools/update-info.py) in den Zweig "appcast" - von dort lesen die Apps ueber
# https://raw.githubusercontent.com/<repo>/appcast/<datei>. Der Zweig hat genau einen Commit (wird ersetzt) mit
# appcast.xml/stable.json (Releases) und appcast-rc.xml/testing.json (Vorabversionen); die anderen Dateien bleiben.
# Aufruf in der CI nach dem Anlegen des Releases (Rechte: contents: write):
#   sh tools/publish-appcast.sh <ordner> appcast-rc.xml "<Label>"
# Fehlt die Datei (kein Secret SPARKLE_ED_PRIVATE_KEY), nur ein Hinweis - kein Fehler.
set -eu
DIR=$1; FILE=$2; L=${3:-}
case "$FILE" in appcast.xml|appcast-rc.xml|stable.json|testing.json) ;; *) echo "::error::unerwartete Datei $FILE"; exit 1 ;; esac
case "$FILE" in
  *.json)
    [ -s "$DIR/$FILE" ] || { echo "::error::$FILE fehlt"; exit 1; }
    python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["format"]==1 and d["version"]' "$DIR/$FILE" \
      || { echo "::error::$FILE ist ungueltig"; exit 1; } ;;
  *)
    if [ ! -s "$DIR/$FILE" ]; then
      echo "::notice::Kein $FILE (Secret SPARKLE_ED_PRIVATE_KEY fehlt?) - das Selbst-Update der Mac-App bietet diese Version nicht an"
      exit 0
    fi
    # nur signierte Appcasts veroeffentlichen (die App nimmt ohnehin nur solche an: SURequireSignedFeed)
    grep -q '<!-- sparkle-signatures:' "$DIR/$FILE" || { echo "::error::$FILE ist nicht signiert"; exit 1; } ;;
esac
B=appcast
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
n=0
while :; do
  n=$((n + 1))
  rm -rf "$W/t"; mkdir -p "$W/t"
  OLD=$(git ls-remote origin "refs/heads/$B" | cut -f1)
  if [ -n "$OLD" ]; then
    git fetch -q origin "$OLD"
    git archive "$OLD" | tar -x -C "$W/t"
  fi
  cp "$DIR/$FILE" "$W/t/$FILE"
  printf '%s\n' "Update-Daten der Apps - automatisch von .github/workflows/release.yml erzeugt. Nicht von Hand aendern." "" \
    "- appcast.xml / appcast-rc.xml: Selbst-Update der Mac-App (Sparkle, EdDSA-signiert), Releases / Vorabversionen" \
    "- stable.json / testing.json: Update-Hinweis aller Apps (tools/update-info.py), Releases / Vorabversionen" > "$W/t/README.md"
  T=$(for f in "$W"/t/*; do printf '100644 blob %s\t%s\n' "$(git hash-object -w "$f")" "$(basename "$f")"; done | git mktree)
  C=$(git -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
        commit-tree "$T" -m "${FILE}: ${L:-?}")
  if git push -q --force-with-lease="refs/heads/$B:$OLD" origin "$C:refs/heads/$B"; then
    echo "$FILE veroeffentlicht (${L:-?}): Zweig $B, Commit $(echo "$C" | cut -c1-7)"
    exit 0
  fi
  [ "$n" -lt 4 ] || { echo "::error::Appcast konnte nicht in den Zweig $B geschrieben werden"; exit 1; }
  echo "Zweig $B hat sich geaendert - neuer Versuch ($n)"
  sleep $((n * 5))
done
