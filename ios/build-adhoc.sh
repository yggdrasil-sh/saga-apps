#!/bin/zsh
# Baut eine Ad-hoc-Version (ein Jahr gueltig, fuer die im Developer-Konto registrierten Geraete)
# und legt sie als dist/Hlidskjalf.ipa ab - z. B. zum Verteilen ueber ein MDM.
# Signierung automatisch ueber einen App-Store-Connect-API-Schluessel in ASC_KEY_PATH/ASC_KEY_ID/ASC_ISSUER:
#   ASC_KEY_PATH=... ASC_KEY_ID=... ASC_ISSUER=... ./build-adhoc.sh
# Ist SAGA_PUBLISH_CMD gesetzt (Config/local.env), wird es danach mit dem Pfad der .ipa aufgerufen
# (abschalten mit MDM_PUBLISH=0).
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
# angezeigte Version: 2.2.0-rc.N (Ad-hoc/MDM ist nie das offizielle Release; SAGA_VERSION_LABEL ueberschreibt)
VL=$(sh ../tools/version-label.sh)
echo "Saga $VL ($BN)"
AR=/tmp/hk-adhoc/Hlidskjalf.xcarchive
rm -rf /tmp/hk-adhoc && mkdir -p /tmp/hk-adhoc dist
AUTH=(-allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID"
      -authenticationKeyIssuerID "$ASC_ISSUER")
cat > /tmp/hk-adhoc/ExportOptions.plist <<XML
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>release-testing</string>
  <key>signingStyle</key><string>automatic</string>
  <key>teamID</key><string>$TEAM</string>
  <key>thinning</key><string>&lt;none&gt;</string>
  <key>compileBitcode</key><false/>
</dict></plist>
XML
out=$(xcodebuild -project Hlidskjalf.xcodeproj -scheme Hlidskjalf -configuration Release \
      -destination 'generic/platform=iOS' -archivePath "$AR" -derivedDataPath /tmp/hk-adhoc/dd \
      CURRENT_PROJECT_VERSION=$BN SAGA_VERSION_LABEL="$VL" "${AUTH[@]}" archive 2>&1) || { echo "$out" | grep -E "error:" | sort -u | head -15; echo "ARCHIV FEHLGESCHLAGEN"; exit 1; }
out=$(xcodebuild -exportArchive -archivePath "$AR" -exportPath /tmp/hk-adhoc/export \
      -exportOptionsPlist /tmp/hk-adhoc/ExportOptions.plist "${AUTH[@]}" 2>&1) || { echo "$out" | grep -E "error" | sort -u | head -15; echo "EXPORT FEHLGESCHLAGEN"; exit 1; }
cp /tmp/hk-adhoc/export/*.ipa dist/Hlidskjalf.ipa
echo "fertig: dist/Hlidskjalf.ipa (Build $BN, $(du -h dist/Hlidskjalf.ipa | cut -f1))"
# optional direkt verteilen (z. B. ins MDM laden)
if [ "${MDM_PUBLISH:-1}" = 1 ] && [ -n "${SAGA_PUBLISH_CMD:-}" ]; then
  "$SAGA_PUBLISH_CMD" dist/Hlidskjalf.ipa
fi
