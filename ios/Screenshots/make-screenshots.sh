#!/bin/zsh
# README-Screenshots der iOS-/iPadOS-App per UI-Test (kopflos, ohne Simulator.app).
# NUR gegen einen Demo-Server mit Beispieldaten verwenden.
#
#   make-screenshots.sh <Simulator-UDID> <Zielordner> [build]
#   SAGA_PAIR_LINK='hlidskjalf://pair?u=...&c=...'   nur noetig, solange die App im Simulator nicht angemeldet ist
#   SAGA_SHOT_PAGE / SAGA_SHOT_DB                      Seiten-IDs (Vorgabe 1 / 5)
#   SAGA_SHOT_MODES                                    Vorgabe "light dark"
#
# Der Simulator muss gebootet sein (xcrun simctl boot <UDID>). Mit "build" wird vorher
# gebaut (oeffentliche Konfiguration, ohne Signatur, eigenes Projekt aus screenshots.yml).
set -eu
UDID=$1; OUT=${2:A}; DD=${SAGA_SHOT_DD:-/tmp/saga-shots-dd}
cd "${0:A:h}/.."
COMMON=(-project SagaScreenshots.xcodeproj -scheme SagaScreenshots -destination "id=$UDID" -derivedDataPath "$DD"
        SAGA_BUNDLE_ID=io.github.yggdrasil-sh.saga SAGA_DEFAULT_SERVER= SAGA_MOVED_SERVERS= CODE_SIGNING_ALLOWED=NO)
if [[ ${3:-} == build ]]; then
  xcodegen generate --spec screenshots.yml
  xcodebuild "${COMMON[@]}" build-for-testing
fi
# Deutsch, feste Uhrzeit, volle Batterie
xcrun simctl spawn "$UDID" defaults write -g AppleLanguages '(de)'
xcrun simctl spawn "$UDID" defaults write -g AppleLocale de_DE
statusbar() { xcrun simctl status_bar "$UDID" override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3 }
# Hell/dunkel: ohne Simulator.app wirkt der Wechsel erst nach einem Neustart des Simulators
appearance() {
  [[ $(xcrun simctl ui "$UDID" appearance) == $1 ]] && return
  xcrun simctl ui "$UDID" appearance "$1"
  xcrun simctl shutdown "$UDID"; xcrun simctl boot "$UDID"; xcrun simctl bootstatus "$UDID" >/dev/null; sleep 15
  statusbar
}
statusbar
for MODE in ${=SAGA_SHOT_MODES:-light dark}; do
  appearance "$MODE"
  TEST_RUNNER_SAGA_SHOT_DIR="$OUT" TEST_RUNNER_SAGA_SHOT_MODE="$MODE" TEST_RUNNER_SAGA_PAIR_LINK="${SAGA_PAIR_LINK:-}" \
  TEST_RUNNER_SAGA_SHOT_PAGE="${SAGA_SHOT_PAGE:-}" TEST_RUNNER_SAGA_SHOT_DB="${SAGA_SHOT_DB:-}" \
    xcodebuild "${COMMON[@]}" test-without-building -only-testing:SagaScreenshots
done
appearance light
