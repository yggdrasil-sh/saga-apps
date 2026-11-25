#!/bin/zsh
# Bugs-DB 2262: Notification Service Extension gegen die Server-Vektoren (Tests/Notify/NotifyTests.swift), optional mit
# Bannerbildern. Aufruf (in ios/, Projekt mit xcodegen erzeugt, Simulator gebootet):
#   Tests/Notify/run.sh <sim-id> <derived-data> [bilder-ordner]
# Bilder nur, wenn Mitteilungen fuer Saga im Simulator erlaubt sind (z. B. einmal Tests/NotifTap mit -hkAskNotif YES).
set -u
SIM=$1 DD=$2 OUT=${3:-}
LOG=$(mktemp -t notify)
[[ -n $OUT ]] && export TEST_RUNNER_NOTIFY_BANNER=1
xcodebuild test -project Hlidskjalf.xcodeproj -scheme NotifyTests -configuration Debug -destination "id=$SIM" \
  -derivedDataPath $DD CODE_SIGNING_ALLOWED=NO > $LOG 2>&1 &
XB=$!
typeset -A shot
while kill -0 $XB 2>/dev/null; do
  if [[ -n $OUT ]]; then
    for n in $(grep -o "NOTIFY_BANNER [a-z]*" $LOG | awk '{print $2}'); do
      [[ -n ${shot[$n]:-} ]] && continue
      shot[$n]=1; sleep 2; xcrun simctl io $SIM screenshot $OUT/notify-$n.png > /dev/null 2>&1
    done
  fi
  sleep 0.5
done
wait $XB; RC=$?
grep -E "Test Case .*(passed|failed)|error:|Executed" $LOG | tail -15
echo "rc=$RC log=$LOG"
