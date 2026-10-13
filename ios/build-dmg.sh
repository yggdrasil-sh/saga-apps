#!/bin/sh
# Baut die Mac-App als signierte, von Apple beglaubigte (notarisierte) DMG fuer GitHub-Releases.
# Signierung mit "Developer ID Application" + Hardened Runtime, ohne Bereitstellungsprofile
# (die App-Gruppe hat dafuer das Team-Praefix: <TEAM>.<App-ID>). Beglaubigung ueber einen
# App-Store-Connect-API-Schluessel:
#   ASC_KEY_PATH=.../AuthKey_XXX.p8 ASC_KEY_ID=XXX ASC_ISSUER=... sh ios/build-dmg.sh
# Ergebnis: ios/dist/Saga-<VERSION>-macOS.dmg (VERSION aus ../VERSION)
#
# Umgebung (alles optional, ausser Team und - beim Beglaubigen - dem ASC-Schluessel):
#   SAGA_TEAM_ID         Apple-Team (Vorgabe: DEVELOPMENT_TEAM aus Config/Local.xcconfig)
#   SAGA_BUNDLE_ID       App-ID (Vorgabe: io.github.yggdrasil-sh.saga - bewusst NICHT aus Local.xcconfig)
#   SAGA_APP_GROUP       App-Gruppe (Vorgabe: <TEAM>.<App-ID>)
#   SAGA_DEFAULT_SERVER  vorbelegter Server (Vorgabe: leer)
#   SAGA_MOVED_SERVERS   umgezogene Server "alt=neu" (Vorgabe: leer)
#   SAGA_SIGN_IDENTITY   Zertifikat (Name oder SHA-1; Vorgabe: "Developer ID Application" des Teams)
#   SAGA_KEYCHAIN        Schluesselbund mit dem Zertifikat (z. B. temporaerer Schluesselbund in CI)
#   SAGA_NOTARIZE=0      Beglaubigen und Stapeln ueberspringen (nur zum Testen - Gatekeeper lehnt die DMG dann ab)
#   SAGA_VERSION         Version (Vorgabe: Inhalt von ../VERSION)
#   SAGA_BUILD_NUMBER    CFBundleVersion (Vorgabe: JJJJMMTTHHMM)
# Config/local.env wird absichtlich nicht gelesen.
set -eu
cd "$(dirname "$0")"

die() { echo "FEHLER: $*" >&2; exit 1; }
# Wert aus Config/Local.xcconfig (unbedingte Zeile, letzte gewinnt) - leer, wenn nicht vorhanden
xcv() {
  [ -f Config/Local.xcconfig ] || return 0
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" Config/Local.xcconfig | tail -1 | sed 's/[[:space:]]*$//'
}

# Xcode statt der Command Line Tools verwenden
if [ -z "${DEVELOPER_DIR:-}" ]; then
  case "$(xcode-select -p 2>/dev/null)" in
    */CommandLineTools*|"") [ -d /Applications/Xcode.app ] && export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ;;
  esac
fi

VERSION=${SAGA_VERSION:-$(tr -d ' \t\r\n' < ../VERSION)}
[ -n "$VERSION" ] || die "Version fehlt (../VERSION oder SAGA_VERSION)"
TEAM=${SAGA_TEAM_ID:-$(xcv DEVELOPMENT_TEAM)}
[ -n "$TEAM" ] || die "Team-ID fehlt: SAGA_TEAM_ID setzen oder DEVELOPMENT_TEAM in Config/Local.xcconfig"
BUNDLE_ID=${SAGA_BUNDLE_ID:-io.github.yggdrasil-sh.saga}
GROUP=${SAGA_APP_GROUP:-$TEAM.$BUNDLE_ID}
NOTARIZE=${SAGA_NOTARIZE:-1}
BN=${SAGA_BUILD_NUMBER:-$(date +%Y%m%d%H%M)}
KC=${SAGA_KEYCHAIN:-}
if [ "$NOTARIZE" != 0 ]; then
  : "${ASC_KEY_PATH:?App-Store-Connect-API-Schluessel fehlt: ASC_KEY_PATH, ASC_KEY_ID, ASC_ISSUER setzen (oder SAGA_NOTARIZE=0)}"
  : "${ASC_KEY_ID:?ASC_KEY_ID fehlt}"
  : "${ASC_ISSUER:?ASC_ISSUER fehlt}"
fi

# Zertifikat suchen: "Developer ID Application: ... (TEAM)" - als SHA-1, damit es eindeutig ist
if [ -n "${SAGA_SIGN_IDENTITY:-}" ]; then
  IDENT=$SAGA_SIGN_IDENTITY
else
  IDENT=$(security find-identity -v -p codesigning ${KC:+"$KC"} |
          sed -n "s/^.*) \([0-9A-F]\{40\}\) \"Developer ID Application: .*($TEAM)\"$/\1/p" | head -1)
  [ -n "$IDENT" ] || die "kein Zertifikat \"Developer ID Application\" fuer Team $TEAM im Schluesselbund.
  Anlegen kann es nur der Account Holder: Xcode -> Einstellungen -> Accounts -> Team -> Zertifikate verwalten -> + -> Developer ID Application"
fi
sign() { codesign --force --timestamp --options runtime --sign "$IDENT" ${KC:+--keychain "$KC"} "$@"; }

WORK=$(mktemp -d "${TMPDIR:-/tmp}/saga-dmg.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
trap 'exit 130' INT TERM
mkdir -p dist
DMG="dist/Saga-$VERSION-macOS.dmg"

# 1. Projekt erzeugen (nicht versioniert) und Archiv bauen - zunaechst nur ad hoc signiert;
#    Xcode setzt dabei die Entitlements ($(SAGA_APP_GROUP) usw.) ein, die echte Signatur folgt unten.
if command -v xcodegen >/dev/null 2>&1; then xcodegen generate --quiet
elif [ ! -d Hlidskjalf.xcodeproj ]; then die "xcodegen fehlt (brew install xcodegen)"; fi
echo "baue Saga $VERSION ($BN) fuer macOS: $BUNDLE_ID, Gruppe $GROUP, Team $TEAM"
if ! xcodebuild -project Hlidskjalf.xcodeproj -scheme Hlidskjalf -configuration Release \
      -destination 'generic/platform=macOS' -archivePath "$WORK/Saga.xcarchive" -derivedDataPath "$WORK/dd" \
      SAGA_BUNDLE_ID="$BUNDLE_ID" SAGA_APP_GROUP="$GROUP" \
      SAGA_DEFAULT_SERVER="${SAGA_DEFAULT_SERVER:-}" SAGA_MOVED_SERVERS="${SAGA_MOVED_SERVERS:-}" \
      DEVELOPMENT_TEAM="$TEAM" MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BN" \
      CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- PROVISIONING_PROFILE_SPECIFIER= PROVISIONING_PROFILE= \
      CODE_SIGN_INJECT_BASE_ENTITLEMENTS=NO ENABLE_HARDENED_RUNTIME=YES \
      archive > "$WORK/build.log" 2>&1; then
  grep -E "error:" "$WORK/build.log" | sort -u | head -20 >&2
  die "Archiv fehlgeschlagen"
fi

# 2. App bereitstellen (als Saga.app) und von innen nach aussen mit Developer ID signieren
STAGE="$WORK/dmg"
APP="$STAGE/Saga.app"
mkdir -p "$STAGE"
ditto "$WORK/Saga.xcarchive/Products/Applications/Hlidskjalf.app" "$APP"
find "$APP/Contents" -depth \( -name '*.appex' -o -name '*.framework' -o -name '*.xpc' -o -name '*.app' -o -name '*.dylib' \) -print > "$WORK/nested.txt"
echo "$APP" >> "$WORK/nested.txt"
n=0
while IFS= read -r b; do
  n=$((n + 1)); ent="$WORK/ent-$n.plist"
  # Entitlements aus der Xcode-Signatur uebernehmen (leer bei Frameworks/Bibliotheken)
  codesign -d --entitlements - --xml "$b" > "$ent" 2>/dev/null || :
  if [ -s "$ent" ]; then sign --entitlements "$ent" "$b"; else sign "$b"; fi
done < "$WORK/nested.txt"
codesign --verify --strict --deep "$APP" || die "Signatur der App ungueltig"

# Pruefen: richtige App-ID, Gruppe in Info.plist und Entitlements gleich, kein get-task-allow
plist() { /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null || :; }
for b in "$APP" "$APP"/Contents/PlugIns/*.appex; do
  [ -d "$b" ] || continue
  codesign -d --entitlements - --xml "$b" > "$WORK/check.plist" 2>/dev/null
  eg=$(plist "$WORK/check.plist" "com.apple.security.application-groups:0")
  ig=$(plist "$b/Contents/Info.plist" SagaAppGroup)
  id=$(plist "$b/Contents/Info.plist" CFBundleIdentifier)
  echo "  $(basename "$b"): $id, Gruppe $ig"
  case "$id" in "$BUNDLE_ID"|"$BUNDLE_ID".*) ;; *) die "$(basename "$b"): unerwartete App-ID $id" ;; esac
  [ "$eg" = "$GROUP" ] && [ "$ig" = "$GROUP" ] || die "$(basename "$b"): App-Gruppe passt nicht (Entitlements: $eg, Info.plist: $ig)"
  [ "$(plist "$WORK/check.plist" com.apple.security.get-task-allow)" != true ] || die "$(basename "$b"): get-task-allow gesetzt"
done

notarize() { # $1 = zip oder dmg
  xcrun notarytool submit "$1" --key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER" \
        --wait --output-format json > "$WORK/notary.json" 2> "$WORK/notary.err" || :
  st=$(plutil -extract status raw -o - "$WORK/notary.json" 2>/dev/null || echo unbekannt)
  sid=$(plutil -extract id raw -o - "$WORK/notary.json" 2>/dev/null || :)
  echo "  Beglaubigung $(basename "$1"): $st${sid:+ ($sid)}"
  if [ "$st" != Accepted ]; then
    cat "$WORK/notary.err" >&2
    [ -z "$sid" ] || xcrun notarytool log "$sid" --key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER" >&2 || :
    die "Beglaubigung abgelehnt"
  fi
}

# 3. App beglaubigen und Ticket anheften (dann startet sie auch offline ohne Rueckfrage bei Apple)
if [ "$NOTARIZE" != 0 ]; then
  ditto -c -k --keepParent "$APP" "$WORK/Saga.zip"
  notarize "$WORK/Saga.zip"
  xcrun stapler staple -q "$APP"
fi

# 4. DMG: App + Verknuepfung zu /Applications, signieren, beglaubigen, Ticket anheften
ln -s /Applications "$STAGE/Applications"
rm -f "$DMG"
hdiutil create -quiet -volname "Saga $VERSION" -srcfolder "$STAGE" -fs HFS+ -format UDZO \
        -imagekey zlib-level=9 -ov "$DMG"
codesign --force --timestamp --sign "$IDENT" ${KC:+--keychain "$KC"} "$DMG"
if [ "$NOTARIZE" != 0 ]; then
  notarize "$DMG"
  xcrun stapler staple -q "$DMG"
fi

# 5. Gatekeeper-Pruefung (DMG und App darin, nur lesend eingehaengt)
check() { if "$@"; then :; elif [ "$NOTARIZE" != 0 ]; then die "Gatekeeper lehnt ab: $*"; else echo "  (ohne Beglaubigung erwartet abgelehnt)"; fi; }
check spctl -a -t open --context context:primary-signature -vv "$DMG"
MNT=$(mktemp -d "$WORK/mnt.XXXXXX")
hdiutil attach -quiet -nobrowse -readonly -noautoopen -mountpoint "$MNT" "$DMG"
trap 'hdiutil detach -quiet "$MNT" 2>/dev/null || :; rm -rf "$WORK"' EXIT
codesign -dv --verbose=2 "$MNT/Saga.app" 2>&1 | grep -E "^(Identifier|Authority|TeamIdentifier|Timestamp|Runtime Version)=" || :
check spctl -a -vv "$MNT/Saga.app"
hdiutil detach -quiet "$MNT"
trap 'rm -rf "$WORK"' EXIT
echo "fertig: ios/$DMG ($(du -h "$DMG" | cut -f1 | tr -d ' '))"
