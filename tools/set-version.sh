#!/bin/sh
# Version der Apps setzen (eine Quelle: VERSION): tools/set-version.sh 1.1.0
# -> VERSION, iOS/macOS (MARKETING_VERSION in ios/project.yml), Linux (linux/hlidskjalf/__init__.py)
set -e
cd "$(dirname "$0")/.."
V=${1:?Version, z. B. 1.1.0}
echo "$V" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "Bitte SemVer (1.2.3)"; exit 1; }
echo "$V" > VERSION
sed -i '' -E "s/^(    MARKETING_VERSION: ).*/\1\"$V\"/" ios/project.yml
sed -i '' -E "s/^VERSION = .*/VERSION = \"$V\"/" linux/hlidskjalf/__init__.py
(cd ios && xcodegen generate >/dev/null)
echo "Version $V gesetzt (iOS/macOS, Linux). Changelog nicht vergessen."
