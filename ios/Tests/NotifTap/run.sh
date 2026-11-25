#!/bin/zsh
# Bugs-DB 2187: Mitteilung antippen -> Saga oeffnet das Ziel (echter Weg ueber SpringBoard, eigener Simulator).
# Aufruf (in ios/, Projekt mit xcodegen erzeugt, Simulator gebootet):
#   Tests/NotifTap/run.sh build <sim-id> <derived-data>
#   Tests/NotifTap/run.sh case  <sim-id> <derived-data> <fall fg|bg|cold> <payload.json> <banner-text> <ziel-text> <bild.png> [startparameter]
# payload.json im Format des Servers (saga/push.py _send_apns), z. B. {"aps":{"alert":{...}},"url":"/notes#12:p3","kind":"comment.new"}.
# Beispiele: seite.json (Kommentar auf Seite 2, Block p30), chat.json (Nachricht in Chat 1) - IDs an den Testserver anpassen.
# Ergebnis: Zeile "NOTIFTAP_ERGEBNIS <fall> ZIEL|KEIN_ZIEL", Bildschirmfoto nach dem Antippen, dazu <bild>-banner.png (Banner).
# 2262: `simctl push` reicht die Mitteilung direkt an SpringBoard (CoreSimulatorBridge) - die Notification Service
# Extension laeuft dabei NICHT (Xcode 27 / iOS 27 geprueft). Ende-zu-Ende-Banner: Tests/Notify/run.sh.
set -u
CMD=$1 SIM=$2 DD=$3
BUNDLE=${SAGA_BUNDLE_ID:-io.github.yggdrasil-sh.saga}
if [[ $CMD == build ]]; then
  xcodebuild build-for-testing -project Hlidskjalf.xcodeproj -scheme NotifTapUITests -configuration Debug \
    -destination "id=$SIM" -derivedDataPath $DD CODE_SIGNING_ALLOWED=NO | tail -3
  exit $pipestatus[1]
fi
FALL=$4 PAYLOAD=$5 MARK=$6 EXPECT=$7 SHOT=$8 ARGS=${9:-}
RUN=(${DD}/Build/Products/*.xctestrun(N))
[[ ${#RUN} -gt 0 ]] || { echo "kein .xctestrun in $DD"; exit 2; }
LOG=$(mktemp -t notiftap)
export TEST_RUNNER_NOTIF_CASE=$FALL TEST_RUNNER_NOTIF_MARK=$MARK TEST_RUNNER_NOTIF_EXPECT=$EXPECT TEST_RUNNER_NOTIF_ARGS=$ARGS
export TEST_RUNNER_NOTIF_HOLD=${NOTIF_HOLD:-3}
xcodebuild test-without-building -xctestrun $RUN[1] -destination "id=$SIM" -only-testing:NotifTapUITests > $LOG 2>&1 &
XB=$!
for i in {1..120}; do grep -q "NOTIFTAP_BEREIT" $LOG && break; kill -0 $XB 2>/dev/null || break; sleep 1; done
grep -q "NOTIFTAP_BEREIT" $LOG || { echo "nicht bereit"; tail -20 $LOG; kill $XB 2>/dev/null; exit 3; }
sleep 1
xcrun simctl push $SIM $BUNDLE $PAYLOAD
# 2262: Bannerfoto, sobald der Test das Banner sieht (NOTIF_HOLD s Pause vor dem Antippen)
for i in {1..70}; do grep -q "NOTIFTAP_BANNER" $LOG && { xcrun simctl io $SIM screenshot ${SHOT:r}-banner.png > /dev/null 2>&1; break; }; kill -0 $XB 2>/dev/null || break; sleep 1; done
for i in {1..90}; do grep -q "NOTIFTAP_ERGEBNIS" $LOG && break; kill -0 $XB 2>/dev/null || break; sleep 1; done
sleep 1
xcrun simctl io $SIM screenshot $SHOT > /dev/null 2>&1
wait $XB; RC=$?
grep -E "NOTIFTAP_|error:|failed|passed" $LOG | grep -v "^\s*$" | head -12
echo "rc=$RC log=$LOG"
