#!/bin/sh
# Tests der Linux-App ohne Bildschirm: Fedora-Container, Xvfb, privater D-Bus, nachgebauter Server (tests/mockserver.py).
# Kein echter Server, kein Testrechner, keine Zugangsdaten noetig.
#   sh tests/run-container.sh                 -> startet fedora:44 (podman oder docker) und laeuft dort alles
#   sh tests/run-container.sh lint unit       -> nur diese Teile (lint | unit | ui | lang | shots | all)
#     lint: pyflakes, compileall, msgfmt, desktop-file-validate, appstreamcli   unit: pytest (tests/test_*.py)
#     ui: App startet (smoke.py)   lang: sprachen.py fuer de/en/es/ru/th   shots: Bildschirmfotos (shots.py, dialoge.py, pubshots.py, ...)
#   sh tests/run-container.sh --inside ...    -> direkt hier laufen (im Container oder auf einem Fedora-Rechner mit den Paketen)
#   PYTEST_ADDOPTS="-k konflikt" ...           -> nur passende pytest-Tests
# Bildschirmfotos (Teil "shots"): build/shots/ im Linux-Ordner. Bild: HK_IMAGE (Standard fedora:44).
set -eu
SRC=$(cd "$(dirname "$0")/.." && pwd)
if [ "${1:-}" != "--inside" ]; then
  RT=$(command -v podman || command -v docker || true)
  [ -n "$RT" ] || { echo "podman oder docker fehlt (oder: --inside auf einem Fedora-Rechner)" >&2; exit 2; }
  exec "$RT" run --rm -v "$SRC:/src" "${HK_IMAGE:-fedora:44}" sh /src/tests/run-container.sh --inside "$@"
fi
shift
PARTS=${*:-all}
case " $PARTS " in *" all "*) PARTS="lint unit ui lang shots" ;; esac

# --- Pakete (nur wenn etwas fehlt) ------------------------------------------------------------------------
# google-noto-color-emoji-fonts: wie im RPM (Requires) - Seitensymbole und Hinweise sind Emoji
#   python3-pynacl: Chat-Kryptografie (libsodium), wie im RPM (Requires)
#   gstreamer1-plugins-good: Klingelton bei Anrufen (WAV), wie im RPM (Recommends)
#   font(notosansthai): Thai-Schrift, wie im RPM (Recommends) - sonst Ersatzkaestchen in den Thai-Bildern
if ! python3 -c "import gi, pytest, pyflakes, nacl; gi.require_version('Adw', '1')" 2>/dev/null || ! command -v Xvfb >/dev/null \
    || ! fc-list 2>/dev/null | grep -qi "color emoji" || ! fc-list :lang=th 2>/dev/null | grep -q . || ! locale -a | grep -q th_TH; then
  echo "== Pakete installieren"
  dnf -y -q install python3-gobject gtk4 libadwaita libsecret python3-pillow python3-pynacl xorg-x11-server-Xvfb dbus-daemon dbus-tools \
    glibc-langpack-de glibc-langpack-es glibc-langpack-en glibc-langpack-ru glibc-langpack-th gettext python3-pyflakes python3-pytest appstream desktop-file-utils adwaita-icon-theme \
    google-noto-color-emoji-fonts 'font(notosansthai)' gstreamer1-plugins-good >/dev/null
fi

# Arbeitskopie: der Quellordner bleibt sauber (nur build/shots wird zurueckgeschrieben)
W=$(mktemp -d /tmp/hk-test.XXXXXX)
cp -a "$SRC/." "$W/"
rm -rf "$W/build" "$W/dist"
cd "$W"
sh po/build-mo.sh build/locale
FAIL=0
fail() { echo "FEHLER: $*"; FAIL=$((FAIL + 1)); }

# Ein Befehl mit eigenem HOME, eigenem D-Bus und eigenem X-Server (Xvfb). Sprache: $1
xrun() {
  L=$1; shift
  H=$(mktemp -d /tmp/hk-home.XXXXXX)
  if [ -n "${HK_APP_LANGUAGE:-}" ]; then                 # in der App gewaehlte Sprache (Einstellungen -> Sprache)
    mkdir -p "$H/.config/hlidskjalf"
    printf '{"language": "%s"}\n' "$HK_APP_LANGUAGE" >"$H/.config/hlidskjalf/settings.json"
  fi
  env -i HOME="$H" PATH=/usr/bin:/bin LANG="$L" HK_OUT="${HK_OUT:-/tmp}" HK_SHOT_PREFIX="${HK_SHOT_PREFIX:-}" HK_APP_LANGUAGE="${HK_APP_LANGUAGE:-}" \
    HK_TEST="${HK_TEST:-}" PYTEST_ADDOPTS="${PYTEST_ADDOPTS:-}" HK_NO_KEYRING=1 PYTHONPATH="$W" GSK_RENDERER=cairo GDK_BACKEND=x11 NO_AT_BRIDGE=1 \
    dbus-run-session -- sh -c '
      D=:$((90 + $$ % 900)); Xvfb $D -screen 0 1400x900x24 -nolisten tcp >/dev/null 2>&1 & X=$!; sleep 1
      DISPLAY=$D "$@"; R=$?; kill $X 2>/dev/null; exit $R' sh "$@" 2>&1 \
    | grep -v -E "^\s*$|dbus-daemon|Failed to connect to the session|AT-SPI|portal" || true
  rm -rf "$H"
}

# Ein HK_TEST-Skript in der App laufen lassen; Erfolg: "TEST FERTIG", kein "FEHLER", kein Traceback
app_test() {
  name=$1; L=${2:-de_DE.UTF-8}
  echo "== App-Test $name ($L)"
  OUT=$(HK_TEST="$W/tests/$name" xrun "$L" python3 -X faulthandler -W ignore::DeprecationWarning -m hlidskjalf.main)
  echo "$OUT" | grep -v -E "^SHOT " | tail -40
  echo "$OUT" | grep -q "TEST FERTIG" || fail "$name: nicht bis zum Ende gelaufen"
  echo "$OUT" | grep -q -E "^FEHLER|Traceback" && fail "$name: $(echo "$OUT" | grep -c -E '^FEHLER|Traceback') Fehler"
  true
}

for part in $PARTS; do
  case $part in
    lint)
      echo "== pyflakes";  python3 -m pyflakes hlidskjalf tests || fail pyflakes
      echo "== compileall"; python3 -W error::SyntaxWarning -m compileall -q hlidskjalf >/dev/null || fail compileall
      echo "== msgfmt"; for l in $(cat po/LINGUAS); do printf '%s: ' "$l"; msgfmt --check --statistics -o /dev/null "po/$l.po" || fail "msgfmt $l"; done
      echo "== desktop-file-validate"; desktop-file-validate data/*.desktop || fail desktop-file-validate
      echo "== appstreamcli"; appstreamcli validate --no-net --explain data/*.metainfo.xml >/tmp/as.log 2>&1 || { cat /tmp/as.log; fail appstreamcli; }
      ;;
    unit)
      echo "== pytest (Speichern, Konflikte, Kopplung, Warteschlangen)"
      OUT=$(xrun C.UTF-8 python3 -m pytest -q -p no:cacheprovider -W ignore::DeprecationWarning -W ignore::gi.PyGIDeprecationWarning \
            --tb=short tests/)
      echo "$OUT" | tail -${HK_PYTEST_TAIL:-40}
      echo "$OUT" | grep -E "(failed|error)" | grep -v -E "^[.]+" | head -5
      echo "$OUT" | tail -1 | grep -q -E "^[0-9]+ passed( |,)" && ! echo "$OUT" | tail -1 | grep -q -E "failed|error" || fail pytest
      ;;
    ui)
      app_test smoke.py
      app_test blockmenu_keys.py                         # Blockmenue per Tastatur -> Loeschen/Duplizieren (echte Hauptschleife)
      ;;
    lang)
      for l in de_DE.UTF-8 en_US.UTF-8 es_ES.UTF-8 ru_RU.UTF-8 th_TH.UTF-8; do app_test sprachen.py "$l"; done
      # In der App gewaehlte Sprache hat Vorrang vor der Systemsprache (fremder Rechner)
      HK_APP_LANGUAGE=es app_test sprachen.py de_DE.UTF-8
      HK_APP_LANGUAGE=en app_test sprachen.py de_DE.UTF-8
      HK_APP_LANGUAGE=de app_test sprachen.py es_ES.UTF-8
      HK_APP_LANGUAGE=th app_test sprachen.py de_DE.UTF-8
      HK_APP_LANGUAGE=ru app_test sprachen.py th_TH.UTF-8
      app_test sprachwahl.py de_DE.UTF-8                 # Einstellungen -> Sprache: Auswahl, Neustart-Hinweis
      ;;
    v2shots)                                             # nur die Saga-2.0-Bildschirmfotos
      mkdir -p "$SRC/build/shots"
      HK_OUT="$SRC/build/shots" app_test v2shots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test v2shots.py es_ES.UTF-8
      ;;
    shots)
      mkdir -p "$SRC/build/shots"
      HK_OUT="$SRC/build/shots" app_test shots.py
      HK_OUT="$SRC/build/shots" app_test dialoge.py
      HK_OUT="$SRC/build/shots" app_test sprachwahl.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test sprachwahl.py es_ES.UTF-8
      HK_OUT="$SRC/build/shots" app_test pubshots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test pubshots.py es_ES.UTF-8
      HK_OUT="$SRC/build/shots" app_test shareshots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test shareshots.py es_ES.UTF-8
      HK_OUT="$SRC/build/shots" app_test v2shots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test v2shots.py es_ES.UTF-8
      HK_OUT="$SRC/build/shots" app_test v21shots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test v21shots.py es_ES.UTF-8
      HK_OUT="$SRC/build/shots" app_test v21bshots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test v21bshots.py es_ES.UTF-8
      HK_OUT="$SRC/build/shots" app_test chatdelshots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test chatdelshots.py es_ES.UTF-8
      HK_OUT="$SRC/build/shots" app_test fedshots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test fedshots.py es_ES.UTF-8
      for l in th_TH.UTF-8 ru_RU.UTF-8; do HK_OUT="$SRC/build/shots" app_test thrushots.py "$l"; done
      HK_OUT="$SRC/build/shots" app_test graphshots.py
      ;;
    graphshots)                                          # nur die Bilder zum Graph der Seiten (2.5) + Messung 2000 Knoten
      mkdir -p "$SRC/build/shots"
      HK_OUT="$SRC/build/shots" app_test graphshots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test graphshots.py es_ES.UTF-8
      ;;
    fedshots)                                            # nur die Bilder zu "Chats ueber Servergrenzen" (2.3)
      mkdir -p "$SRC/build/shots"
      HK_OUT="$SRC/build/shots" app_test fedshots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test fedshots.py es_ES.UTF-8
      ;;
    thrushots)                                           # nur die Bilder zu Thai und Russisch (Schrift, Umbruch, Laenge)
      mkdir -p "$SRC/build/shots"
      for l in th_TH.UTF-8 ru_RU.UTF-8; do HK_OUT="$SRC/build/shots" app_test thrushots.py "$l"; done
      ;;
    chatdelshots)                                        # nur die Bilder zu "Chats archivieren und loeschen" (2.1)
      mkdir -p "$SRC/build/shots"
      HK_OUT="$SRC/build/shots" app_test chatdelshots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test chatdelshots.py es_ES.UTF-8
      ;;
    v21bshots)                                           # nur die Bilder zu 2.1-b (Profilbilder, 2FA, Anrufe)
      mkdir -p "$SRC/build/shots"
      HK_OUT="$SRC/build/shots" app_test v21bshots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test v21bshots.py es_ES.UTF-8
      ;;
    v21shots)                                            # nur die Bilder zu 2.1 (Immich im Chat, Einfuegen)
      mkdir -p "$SRC/build/shots"
      HK_OUT="$SRC/build/shots" app_test v21shots.py
      HK_OUT="$SRC/build/shots" HK_SHOT_PREFIX=es- app_test v21shots.py es_ES.UTF-8
      ;;
    app:*) app_test "${part#app:}" "${HK_LANG:-de_DE.UTF-8}" ;;                  # ein einzelnes HK_TEST-Skript, z. B. app:v2shots.py
    *) echo "unbekannter Teil: $part" >&2; FAIL=$((FAIL + 1)) ;;
  esac
done
rm -rf "$W"
if [ "$FAIL" -gt 0 ]; then echo "== $FAIL FEHLER"; exit 1; fi
echo "== alles gruen"
