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
# 2262: Mitteilungs-Erweiterung HlidskjalfNotify (eigene App-ID <App-ID>.notify, ohne weitere Faehigkeiten). Laesst sie
# sich nicht signieren (App-ID/Profil fehlt, API-Schluessel ohne Rechte), wird ohne sie weitergebaut: die App schickt dann
# keine Schluessel ans Mitteilungs-Abo und bekommt Mitteilungen wie mit 2.5. SAGA_NOTIFY=0 baut gleich ohne sie.
NOTIFY=${SAGA_NOTIFY:-1}
nonotify() {
  echo "WARNUNG: ohne Erweiterung HlidskjalfNotify gebaut (Mitteilungen ohne Ende-zu-Ende wie 2.5) - App-ID <App-ID>.notify einrichten, siehe ios/README.md"
  sed '/SAGA_NOTIFY_DEP/d' project.yml > project-nonotify.yml
  local rc=0; xcodegen generate --quiet --spec project-nonotify.yml || rc=$?
  rm -f project-nonotify.yml; NOTIFY=0; return $rc
}
# Nur Fehlerzeilen zaehlen (die Ausgabe nennt HlidskjalfNotify immer, z. B. im Abhaengigkeitsgraphen) - und nur, wenn
# JEDE Fehlerzeile die Erweiterung betrifft; Fehler der App selbst (oder im gemeinsamen Code) brechen wie bisher ab.
notifyerr() {
  [ "$NOTIFY" = 1 ] || return 1
  local e; e=$(print -r -- "$1" | grep -E "error:" | sort -u) || return 1
  print -r -- "$e" | grep -vqE "HlidskjalfNotify|\.notify['\"]" && return 1
  return 0
}
[ "$NOTIFY" = 1 ] || nonotify
archive() {
  rm -rf "$AR" /tmp/hk-adhoc/export
  xcodebuild -project Hlidskjalf.xcodeproj -scheme Hlidskjalf -configuration Release \
      -destination 'generic/platform=iOS' -archivePath "$AR" -derivedDataPath /tmp/hk-adhoc/dd \
      CURRENT_PROJECT_VERSION=$BN SAGA_VERSION_LABEL="$VL" "${AUTH[@]}" archive 2>&1
}
export_ipa() {
  xcodebuild -exportArchive -archivePath "$AR" -exportPath /tmp/hk-adhoc/export \
      -exportOptionsPlist /tmp/hk-adhoc/ExportOptions.plist "${AUTH[@]}" 2>&1
}
for try in 1 2; do
  out=$(archive) || {
    if [ $try = 1 ] && notifyerr "$out"; then echo "$out" | grep -E "error:" | sort -u | head -5; nonotify; continue; fi
    echo "$out" | grep -E "error:" | sort -u | head -15; echo "ARCHIV FEHLGESCHLAGEN"; exit 1; }
  out=$(export_ipa) || {
    if [ $try = 1 ] && notifyerr "$out"; then echo "$out" | grep -E "error" | sort -u | head -5; nonotify; continue; fi
    echo "$out" | grep -E "error" | sort -u | head -15; echo "EXPORT FEHLGESCHLAGEN"; exit 1; }
  break
done
cp /tmp/hk-adhoc/export/*.ipa dist/Hlidskjalf.ipa
echo "fertig: dist/Hlidskjalf.ipa (Build $BN, $(du -h dist/Hlidskjalf.ipa | cut -f1))$([ "$NOTIFY" = 1 ] || echo ' - OHNE HlidskjalfNotify')"
# optional direkt verteilen (z. B. ins MDM laden)
if [ "${MDM_PUBLISH:-1}" = 1 ] && [ -n "${SAGA_PUBLISH_CMD:-}" ]; then
  "$SAGA_PUBLISH_CMD" dist/Hlidskjalf.ipa
fi
