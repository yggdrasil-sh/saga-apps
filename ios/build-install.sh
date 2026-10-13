#!/bin/sh
# Baut + spielt die App nur auf, wenn der Build WIRKLICH erfolgreich war.
# iPhone (SAGA_DEVICE_ID in Config/local.env) nicht gesetzt/verbunden -> wird uebersprungen, die Mac-App trotzdem gebaut.
cd "$(dirname "$0")"; export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
# Lokale Einstellungen (optional, nicht versioniert): Config/local.env - Vorlage: Config/local.env.example
if [ -f Config/local.env ]; then . ./Config/local.env; fi
xcv() { [ -f Config/Local.xcconfig ] || return 0; sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" Config/Local.xcconfig | tail -1 | sed 's/[[:space:]]*$//' || true; }
TEAM=${SAGA_TEAM_ID:-$(xcv DEVELOPMENT_TEAM)}
BUNDLE_ID=${SAGA_BUNDLE_ID:-$(xcv SAGA_BUNDLE_ID)}; BUNDLE_ID=${BUNDLE_ID:-io.github.yggdrasil-sh.saga}
D=${SAGA_DEVICE_ID:-}
BN=$(date +%Y%m%d%H%M)   # neue Build-Nummer: sonst haelt macOS die alte Widget-Liste fuer aktuell
b() { out=$(xcodebuild "$@" CURRENT_PROJECT_VERSION=$BN 2>&1); echo "$out" | grep -E "error:" | sort -u | head -8; echo "$out" | grep -q "BUILD SUCCEEDED"; }
if [ -n "$D" ] && xcrun devicectl list devices 2>/dev/null | grep "$D" | grep -qE "connected|available"; then   # auch per WLAN (gekoppelt)
  if b -project Hlidskjalf.xcodeproj -scheme Hlidskjalf -destination "id=$D" -derivedDataPath /tmp/hk-dd-dev -allowProvisioningUpdates build; then
    xcrun devicectl device install app --device "$D" /tmp/hk-dd-dev/Build/Products/Debug-iphoneos/Hlidskjalf.app 2>&1 | grep -q "App installed" && echo "iPhone: aufgespielt" || echo "iPhone: Installation fehlgeschlagen"
  elif echo "$out" | grep -q "Unable to find a destination"; then   # gekoppelt, aber gerade nicht erreichbar (schlaeft)
    b -project Hlidskjalf.xcodeproj -scheme Hlidskjalf -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/hk-dd CODE_SIGNING_ALLOWED=NO build || { echo "iOS-Build FEHLGESCHLAGEN"; exit 1; }
    echo "iPhone: nicht erreichbar - iOS nur geprueft (kompiliert), nicht aufgespielt"
  else echo "iPhone-Build FEHLGESCHLAGEN - nichts aufgespielt"; exit 1; fi
else
  b -project Hlidskjalf.xcodeproj -scheme Hlidskjalf -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/hk-dd CODE_SIGNING_ALLOWED=NO build || { echo "iOS-Build FEHLGESCHLAGEN"; exit 1; }
  echo "iPhone: nicht verbunden - iOS nur geprueft (kompiliert), nicht aufgespielt"
fi
b -project Hlidskjalf.xcodeproj -scheme Hlidskjalf -destination 'platform=macOS' -configuration Release -derivedDataPath /tmp/hk-dd-mac -allowProvisioningUpdates build || { echo "Mac-Build FEHLGESCHLAGEN - nichts aufgespielt"; exit 1; }
osascript -e "quit app id \"$BUNDLE_ID\"" 2>/dev/null; for i in $(seq 1 20); do pgrep -x Hlidskjalf >/dev/null || break; sleep 0.5; done
rm -rf /Applications/Hlidskjalf.app /Applications/Saga.app && ditto /tmp/hk-dd-mac/Build/Products/Release/Hlidskjalf.app /Applications/Saga.app && sleep 1 && open /Applications/Saga.app && echo "Mac: aufgespielt (/Applications/Saga.app)"
