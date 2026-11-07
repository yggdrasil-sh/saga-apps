#!/usr/bin/env python3
"""Saga-Liniensymbole fuer Android: static/icons.js (Saga-Web) -> VectorDrawables res/drawable/si_<name>.xml.

Aufruf (aus dem Repo-Wurzelverzeichnis):
    python3 tools/icons-android.py [PFAD/ZU/icons.js] [--check] [--print-kotlin]

PFAD/ZU/icons.js liegt im Server-Repo (anderes Repo, nur gelesen). Standard: Umgebungsvariable SAGA_ICONS_JS,
sonst ../saga/static/icons.js (Server-Repo als Nachbarordner dieses Repos).

Zusaetzlich liest das Skript tools/icons-android-extra.json: Motive, die das Web (noch) nicht hat (Abschnitt EXTRA,
gleiche Kurzform), und die Liste MIRRORED (Richtungssymbole mit android:autoMirrored="true" fuer RTL).

Kurzform je Teil (wie HK.icon): Pfad "M...", "c cx cy r" (Kreis), "r x y b h rx" (Rechteck); Teile durch "|".
Kreise und Rechtecke werden in Pfade umgerechnet (Kreis = zwei Halbboegen, Rechteck mit rx = Linien + Viertelboegen).
Pfaddaten werden neu ausgegeben (Zahlen und Bogen-Flags immer mit Leerzeichen getrennt), damit der Android-Pfadparser
keine zusammengezogenen Flags ("0 01") falsch liest.

Stil wie im Web: 24er-Viewport, 24 dp, fill transparent, Strich 1.75, runde Enden und Ecken.
Farbe: strokeColor @android:color/white, eingefaerbt wird beim Zeichnen (Compose Icon(tint = ...) bzw. View-tint).
Grund: Compose-Icon faerbt jede Ressource per SrcIn mit LocalContentColor; Weiss ist fuer jede Tint-Farbe neutral und
haengt nicht davon ab, ob ein Theme-Attribut (?attr/colorControlNormal) in painterResource aufgeloest wird.

Idempotent: schreibt nur Dateien, deren Inhalt sich aendert, und loescht nur veraltete si_*.xml. Andere Dateien
werden nie angefasst. --check: nichts schreiben, Exit-Code 1, wenn etwas abweicht. --print-kotlin: Eigenschaften fuer
ui/theme/SagaIcons.kt auf stdout ausgeben.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res', 'drawable')
EXTRA = os.path.join(HERE, 'icons-android-extra.json')
DEFAULT_JS = os.environ.get('SAGA_ICONS_JS') or os.path.join(ROOT, '..', 'saga', 'static', 'icons.js')

STROKE = '1.75'


# ---------------------------------------------------------------- icons.js lesen
def _eval_expr(expr, consts):
    """'a' + NAME + 'b' -> String (nur String-Literale und vorher definierte Konstanten)."""
    out = []
    for tok in [t.strip() for t in expr.split('+')]:
        m = re.fullmatch(r"'([^']*)'", tok)
        if m:
            out.append(m.group(1))
        elif tok in consts:
            out.append(consts[tok])
        else:
            raise ValueError('unbekannter Ausdruck: %r' % tok)
    return ''.join(out)


def read_icons_js(path):
    src = open(path, encoding='utf-8').read()
    consts = {}
    for m in re.finditer(r"^\s*var\s+([A-Z0-9_]+)\s*=\s*(.+?);\s*$", src, re.M):
        consts[m.group(1)] = _eval_expr(m.group(2), consts)
    body = re.search(r"var\s+I\s*=\s*\{(.*?)\n\s*\};", src, re.S)
    if not body:
        raise ValueError('Objekt "var I = {...}" nicht gefunden in ' + path)
    icons = {}
    for line in body.group(1).split('\n'):
        line = line.strip()
        if not line or line.startswith('//'):
            continue
        m = re.match(r"^(?:'([a-z0-9-]+)'|([a-z0-9]+))\s*:\s*(.+?)\s*,?\s*(?://.*)?$", line)
        if not m:
            raise ValueError('Zeile nicht verstanden: ' + line)
        name = m.group(1) or m.group(2)
        icons[name] = _eval_expr(m.group(3).rstrip(','), consts)
    return icons


# ---------------------------------------------------------------- Pfade normalisieren
_NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
_ARGS = {'m': 2, 'l': 2, 'h': 1, 'v': 1, 'c': 6, 's': 4, 'q': 4, 't': 2, 'a': 7, 'z': 0}


def fmt(x):
    x = round(float(x), 4)
    if x == 0:
        return '0'
    s = ('%.4f' % x).rstrip('0').rstrip('.')
    return s


def normalize_path(d):
    """SVG-Pfad parsen und mit eindeutigen Trennzeichen neu schreiben. Wirft ValueError bei Fehlern."""
    i, n, out, cmd = 0, len(d), [], None
    first = True

    def skip(i):
        while i < n and d[i] in ' ,\t\n':
            i += 1
        return i

    while True:
        i = skip(i)
        if i >= n:
            break
        ch = d[i]
        if ch.isalpha():
            if ch.lower() not in _ARGS:
                raise ValueError('unbekannter Befehl %r in %r' % (ch, d))
            cmd = ch
            i += 1
            if cmd.lower() == 'z':
                out.append('Z' if cmd == 'Z' else 'z')
                continue
        elif cmd is None or cmd.lower() == 'z':
            raise ValueError('Zahl ohne Befehl in %r' % d)
        if first and cmd.lower() != 'm':
            raise ValueError('Pfad beginnt nicht mit M: %r' % d)
        first = False
        k = _ARGS[cmd.lower()]
        vals = []
        for j in range(k):
            i = skip(i)
            if cmd.lower() == 'a' and j in (3, 4):
                if i >= n or d[i] not in '01':
                    raise ValueError('Bogen-Flag erwartet in %r' % d)
                vals.append(d[i])
                i += 1
                continue
            m = _NUM.match(d, i)
            if not m:
                raise ValueError('Zahl erwartet bei %d in %r' % (i, d))
            vals.append(fmt(m.group(0)))
            i = m.end()
        out.append(cmd + ' '.join(vals))
        # weitere Zahlengruppen ohne Befehl: nach M/m gilt L/l
        if cmd == 'M':
            cmd = 'L'
        elif cmd == 'm':
            cmd = 'l'
    return ' '.join(out)


def circle_path(cx, cy, r):
    cx, cy, r = float(cx), float(cy), float(r)
    return 'M%s %s a%s %s 0 1 0 %s 0 a%s %s 0 1 0 %s 0 z' % (
        fmt(cx - r), fmt(cy), fmt(r), fmt(r), fmt(2 * r), fmt(r), fmt(r), fmt(-2 * r))


def rect_path(x, y, w, h, rx=0):
    x, y, w, h, rx = float(x), float(y), float(w), float(h), float(rx or 0)
    rx = max(0.0, min(rx, w / 2, h / 2))
    if rx == 0:
        return 'M%s %s h%s v%s h%s z' % (fmt(x), fmt(y), fmt(w), fmt(h), fmt(-w))
    a = 'a%s %s 0 0 1' % (fmt(rx), fmt(rx))
    return ('M%s %s h%s %s %s %s v%s %s %s %s h%s %s %s %s v%s %s %s %s z' % (
        fmt(x + rx), fmt(y), fmt(w - 2 * rx), a, fmt(rx), fmt(rx), fmt(h - 2 * rx), a, fmt(-rx), fmt(rx),
        fmt(-(w - 2 * rx)), a, fmt(-rx), fmt(-rx), fmt(-(h - 2 * rx)), a, fmt(rx), fmt(-rx)))


def parts_to_paths(spec):
    paths = []
    for part in spec.split('|'):
        a = part.split(' ')
        if a[0] == 'c':
            paths.append(circle_path(*a[1:4]))
        elif a[0] == 'r':
            paths.append(rect_path(*a[1:6]))
        else:
            paths.append(normalize_path(part))
    return paths


# ---------------------------------------------------------------- Ausgabe
def res_name(name):
    return 'si_' + name.replace('-', '_')


def kt_name(name):
    p = name.split('-')
    return p[0] + ''.join(s[:1].upper() + s[1:] for s in p[1:])


def vector_xml(name, spec, source, mirrored):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Saga-Liniensymbol "%s" (%s). Erzeugt von tools/icons-android.py - nicht von Hand aendern. -->' % (name, source),
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24"%s>' % ('\n    android:autoMirrored="true"' if mirrored else ''),
    ]
    for d in parts_to_paths(spec):
        lines += [
            '    <path',
            '        android:pathData="%s"' % d,
            '        android:fillColor="@android:color/transparent"',
            '        android:strokeColor="@android:color/white"',
            '        android:strokeWidth="%s"' % STROKE,
            '        android:strokeLineCap="round"',
            '        android:strokeLineJoin="round" />',
        ]
    lines.append('</vector>')
    return '\n'.join(lines) + '\n'


def load_all(js_path):
    web = read_icons_js(js_path)
    extra_doc = json.load(open(EXTRA, encoding='utf-8'))
    extra = extra_doc.get('EXTRA', {})
    clash = sorted(set(web) & set(extra))
    if clash:
        raise SystemExit('Fehler: EXTRA ueberschneidet icons.js: ' + ', '.join(clash) + ' (in icons-android-extra.json streichen)')
    mirrored = set(extra_doc.get('MIRRORED', []))
    unknown = sorted(mirrored - set(web) - set(extra))
    if unknown:
        raise SystemExit('Fehler: MIRRORED nennt unbekannte Symbole: ' + ', '.join(unknown))
    allv = {}
    for k, v in web.items():
        allv[k] = (v, 'aus static/icons.js')
    for k, v in extra.items():
        allv[k] = (v, 'Android-Ergaenzung, tools/icons-android-extra.json')
    return allv, mirrored, web, extra


def main(argv):
    args = [a for a in argv if not a.startswith('--')]
    check = '--check' in argv
    js_path = args[0] if args else DEFAULT_JS
    if not os.path.isfile(js_path):
        raise SystemExit('icons.js nicht gefunden: %s (Pfad als Argument oder SAGA_ICONS_JS setzen)' % js_path)
    allv, mirrored, web, extra = load_all(js_path)

    if '--print-kotlin' in argv:
        for k in sorted(allv):
            print('    @DrawableRes val %s = R.drawable.%s' % (kt_name(k), res_name(k)))
        print()
        print('    val byName: Map<String, Int> = mapOf(')
        for k in sorted(allv):
            print('        "%s" to %s,' % (k, kt_name(k)))
        print('    )')
        return 0

    wanted = {}
    for k, (spec, source) in allv.items():
        wanted[res_name(k) + '.xml'] = vector_xml(k, spec, source, k in mirrored)
    changed = []
    os.makedirs(OUT, exist_ok=True)
    for fn, content in sorted(wanted.items()):
        p = os.path.join(OUT, fn)
        old = open(p, encoding='utf-8').read() if os.path.exists(p) else None
        if old != content:
            changed.append(fn)
            if not check:
                with open(p, 'w', encoding='utf-8', newline='\n') as f:
                    f.write(content)
    stale = sorted(f for f in os.listdir(OUT) if f.startswith('si_') and f.endswith('.xml') and f not in wanted)
    if not check:
        for f in stale:
            os.remove(os.path.join(OUT, f))
    print('%d Symbole (%d aus icons.js, %d Android-Ergaenzungen), %d geaendert, %d veraltet%s' % (
        len(wanted), len(web), len(extra), len(changed), len(stale), ' (nur geprueft)' if check else ''))
    return 1 if check and (changed or stale) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
