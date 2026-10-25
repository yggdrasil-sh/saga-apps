#!/bin/sh
# Legt einen Appcast (Selbst-Update der Mac-App, tools/appcast.py) in den Zweig "appcast" - von dort liest ihn die App
# ueber https://raw.githubusercontent.com/<repo>/appcast/<datei>. Der Zweig hat genau einen Commit (wird ersetzt) mit
# appcast.xml (Releases) und appcast-rc.xml (Vorabversionen); die jeweils andere Datei bleibt erhalten.
# Aufruf in der CI nach dem Anlegen des Releases (Rechte: contents: write):
#   sh tools/publish-appcast.sh <ordner> appcast-rc.xml "<Label>"
# Fehlt die Datei (kein Secret SPARKLE_ED_PRIVATE_KEY), nur ein Hinweis - kein Fehler.
set -eu
DIR=$1; FILE=$2; L=${3:-}
case "$FILE" in appcast.xml|appcast-rc.xml) ;; *) echo "::error::unerwartete Datei $FILE"; exit 1 ;; esac
if [ ! -s "$DIR/$FILE" ]; then
  echo "::notice::Kein $FILE (Secret SPARKLE_ED_PRIVATE_KEY fehlt?) - das Selbst-Update der Mac-App bietet diese Version nicht an"
  exit 0
fi
# nur signierte Appcasts veroeffentlichen (die App nimmt ohnehin nur solche an: SURequireSignedFeed)
grep -q '<!-- sparkle-signatures:' "$DIR/$FILE" || { echo "::error::$FILE ist nicht signiert"; exit 1; }
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
  printf '%s\n' "Appcasts fuer das Selbst-Update der Mac-App (Sparkle) - automatisch von .github/workflows/release.yml" \
    "erzeugt und mit EdDSA signiert. Nicht von Hand aendern." "" \
    "- appcast.xml: stabile Releases" "- appcast-rc.xml: Vorabversionen (Release Candidates)" > "$W/t/README.md"
  T=$(for f in "$W"/t/*; do printf '100644 blob %s\t%s\n' "$(git hash-object -w "$f")" "$(basename "$f")"; done | git mktree)
  C=$(git -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
        commit-tree "$T" -m "Appcast: ${L:-$FILE}")
  if git push -q --force-with-lease="refs/heads/$B:$OLD" origin "$C:refs/heads/$B"; then
    echo "Appcast $FILE veroeffentlicht (${L:-?}): Zweig $B, Commit $(echo "$C" | cut -c1-7)"
    exit 0
  fi
  [ "$n" -lt 4 ] || { echo "::error::Appcast konnte nicht in den Zweig $B geschrieben werden"; exit 1; }
  echo "Zweig $B hat sich geaendert - neuer Versuch ($n)"
  sleep $((n * 5))
done
