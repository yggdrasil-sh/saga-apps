#!/bin/sh
# Linux-App auf der Test-Surface pruefen - unsichtbar (eigener kopfloser mutter mit privatem D-Bus),
# der Bildschirm der angemeldeten Person wird nicht beruehrt. Bildschirmfotos landen in /tmp/hk-shots (Mac).
#   tools/linux-test.sh linux/tests/smoke.py
# Testrechner und Benutzer: Umgebung (HK_TEST_HOST, HK_TEST_USER) oder tools/local.env (nicht eingecheckt,
# Vorlage: tools/local.env.example). Werte aus der Umgebung haben Vorrang.
set -e
cd "$(dirname "$0")/.."
H=${HK_TEST_HOST:-}
U=${HK_TEST_USER:-}
if [ -f tools/local.env ]; then
  . ./tools/local.env
  H=${H:-${HK_TEST_HOST:-}}
  U=${U:-${HK_TEST_USER:-}}
fi
if [ -z "$H" ] || [ -z "$U" ]; then
  echo "Kein Testrechner angegeben: HK_TEST_HOST (z. B. root@testrechner.example.org) und HK_TEST_USER" >&2
  echo "(angemeldeter Desktop-Benutzer dort) setzen oder tools/local.env anlegen - Vorlage: tools/local.env.example" >&2
  exit 2
fi
S=${1:?Testskript angeben, z. B. linux/tests/smoke.py}
COPYFILE_DISABLE=1 tar --no-mac-metadata -C linux -czf /tmp/hk-linux.tgz hlidskjalf data
scp -q -o BatchMode=yes /tmp/hk-linux.tgz "$S" "$H:/tmp/"
ssh -o BatchMode=yes "$H" "U=$U; S=$(basename "$S")"'
UID_=$(id -u $U)
rm -rf /tmp/hk-linux /tmp/hk-shots && mkdir -p /tmp/hk-linux /tmp/hk-shots && tar -C /tmp/hk-linux -xzf /tmp/hk-linux.tgz 2>/dev/null
cp /tmp/$S /tmp/hk-linux/test.py
chown -R $U /tmp/hk-linux /tmp/hk-shots
runuser -u $U -- env -i HOME=$(getent passwd $U | cut -d: -f6) USER=$U PATH=/usr/bin:/bin LANG=de_DE.UTF-8 \
  XDG_RUNTIME_DIR=/run/user/$UID_ USERBUS=unix:path=/run/user/$UID_/bus \
  dbus-run-session -- sh -c "
    mutter --headless --no-x11 --virtual-monitor 1400x900 --wayland-display hk-test >/tmp/hk-shots/mutter.log 2>&1 &
    M=\$!; sleep 3
    cd /tmp/hk-linux && DBUS_SESSION_BUS_ADDRESS=\$USERBUS WAYLAND_DISPLAY=hk-test GDK_BACKEND=wayland GSK_RENDERER=cairo \
      HK_TEST=/tmp/hk-linux/test.py HK_OUT=/tmp/hk-shots timeout 120 python3 -W ignore::DeprecationWarning -m hlidskjalf.main > /tmp/hk-shots/app.log 2>&1
    kill \$M 2>/dev/null; true"
grep -v -E "^\s*$|bus can.t be made" /tmp/hk-shots/app.log | tail -80
' 2>&1 | grep -v Pseudo
rm -rf /tmp/hk-shots && mkdir -p /tmp/hk-shots
scp -q -o BatchMode=yes "$H:/tmp/hk-shots/*.png" /tmp/hk-shots/ 2>/dev/null || true
ls /tmp/hk-shots
