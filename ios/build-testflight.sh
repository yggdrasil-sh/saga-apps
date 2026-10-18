#!/bin/zsh
# Baut eine App-Store-Version und laedt sie zu TestFlight hoch (kein Entwicklermodus, keine UDIDs noetig).
# Signierung und Upload ueber einen App-Store-Connect-API-Schluessel in ASC_KEY_PATH/ASC_KEY_ID/ASC_ISSUER:
#   ASC_KEY_PATH=... ASC_KEY_ID=... ASC_ISSUER=... ./build-testflight.sh
# Builds laufen in TestFlight nach 90 Tagen ab -> einfach erneut hochladen.
set -euo pipefail
cd "$(dirname "$0")"
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
# Lokale Einstellungen (optional, nicht versioniert): Config/local.env - Vorlage: Config/local.env.example
if [ -f Config/local.env ]; then . ./Config/local.env; fi
xcv() { [ -f Config/Local.xcconfig ] || return 0; sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" Config/Local.xcconfig | tail -1 | sed 's/[[:space:]]*$//' || true; }
TEAM=${SAGA_TEAM_ID:-$(xcv DEVELOPMENT_TEAM)}
BUNDLE_ID=${SAGA_BUNDLE_ID:-$(xcv SAGA_BUNDLE_ID)}; BUNDLE_ID=${BUNDLE_ID:-io.github.yggdrasil-sh.saga}
: "${ASC_KEY_PATH:?App-Store-Connect-API-Schluessel in ASC_KEY_PATH/ASC_KEY_ID/ASC_ISSUER setzen}"
: "${TEAM:?Team-ID fehlt: DEVELOPMENT_TEAM in Config/Local.xcconfig oder SAGA_TEAM_ID in Config/local.env}"
BN=$(date +%Y%m%d%H%M)
# angezeigte Version: 2.2.0-rc.N - fuer die App-Store-Version des offiziellen Releases mit SAGA_RELEASE=1 bauen
# (oder vom Release-Tag aus, dann ohne Zusatz)
VL=$(sh ../tools/version-label.sh)
echo "Saga $VL ($BN)"
W=/tmp/hk-testflight
AR=$W/Hlidskjalf.xcarchive
rm -rf $W && mkdir -p $W
AUTH=(-allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID"
      -authenticationKeyIssuerID "$ASC_ISSUER")
cat > $W/ExportOptions.plist <<XML
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>signingStyle</key><string>automatic</string>
  <key>teamID</key><string>$TEAM</string>
  <key>manageAppVersionAndBuildNumber</key><false/>
  <key>uploadSymbols</key><true/>
  <key>testFlightInternalTestingOnly</key><false/>
</dict></plist>
XML
xcodegen generate --quiet
out=$(xcodebuild -project Hlidskjalf.xcodeproj -scheme Hlidskjalf -configuration Release \
      -destination 'generic/platform=iOS' -archivePath "$AR" -derivedDataPath $W/dd \
      CURRENT_PROJECT_VERSION=$BN SAGA_VERSION_LABEL="$VL" "${AUTH[@]}" archive 2>&1) || { echo "$out" | grep -E "error:" | sort -u | head -15; echo "ARCHIV FEHLGESCHLAGEN"; exit 1; }
out=$(xcodebuild -exportArchive -archivePath "$AR" -exportPath $W/export \
      -exportOptionsPlist $W/ExportOptions.plist "${AUTH[@]}" 2>&1) || { echo "$out" | grep -iE "error|fail" | sort -u | head -20; echo "UPLOAD FEHLGESCHLAGEN"; exit 1; }
echo "$out" | grep -iE "upload|succeeded" | tail -3
echo "hochgeladen: Build $BN ($BUNDLE_ID) - Apple verarbeitet ihn jetzt (meist 5-15 Min), dann steht er in TestFlight."
echo "Status: ${SAGA_STATUS_HINT:-App Store Connect -> Apps -> TestFlight}"
