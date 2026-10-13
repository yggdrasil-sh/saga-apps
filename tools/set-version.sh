#!/bin/sh
# Version der Apps setzen bzw. pruefen (eine Quelle: VERSION in der Wurzel).
# Gespiegelt nach iOS/macOS (MARKETING_VERSION in ios/project.yml) und Linux (linux/hlidskjalf/__init__.py);
# Android liest VERSION direkt.
#   tools/set-version.sh 1.1.0     -> setzt die Version ueberall
#   tools/set-version.sh --check   -> prueft, ob alles uebereinstimmt (gibt die Version aus; fuer CI)
set -eu
cd "$(dirname "$0")/.."
ios() { sed -n 's/^ *MARKETING_VERSION: *"\([^"]*\)".*/\1/p' ios/project.yml | head -1; }
lin() { sed -n 's/^VERSION = "\([^"]*\)".*/\1/p' linux/hlidskjalf/__init__.py; }

if [ "${1:-}" = "--check" ]; then
  V=$(tr -d ' \t\r\n' < VERSION)
  ok=1
  echo "$V" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "VERSION ist kein SemVer: $V" >&2; ok=0; }
  [ "$(ios)" = "$V" ] || { echo "ios/project.yml hat $(ios), VERSION sagt $V" >&2; ok=0; }
  [ "$(lin)" = "$V" ] || { echo "linux/hlidskjalf/__init__.py hat $(lin), VERSION sagt $V" >&2; ok=0; }
  [ $ok = 1 ] || { echo "Versionen passen nicht zusammen - tools/set-version.sh $V" >&2; exit 1; }
  echo "$V"
  exit 0
fi

V=${1:?Version, z. B. 1.1.0 (oder --check)}
echo "$V" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "Bitte SemVer (1.2.3)" >&2; exit 1; }
echo "$V" > VERSION
# sed -i.bak laeuft mit BSD- und GNU-sed
sed -i.bak -E "s/^( *MARKETING_VERSION: ).*/\1\"$V\"/" ios/project.yml
sed -i.bak -E "s/^VERSION = .*/VERSION = \"$V\"/" linux/hlidskjalf/__init__.py
rm -f ios/project.yml.bak linux/hlidskjalf/__init__.py.bak
command -v xcodegen >/dev/null 2>&1 && (cd ios && xcodegen generate >/dev/null) || true
sh "$0" --check >/dev/null
echo "Version $V gesetzt (iOS/macOS, Linux, Android). CHANGELOG.md nicht vergessen."
