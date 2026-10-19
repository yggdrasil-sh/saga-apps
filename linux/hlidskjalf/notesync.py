"""Seitenbaum per Abgleich (Leistung 2.2, Phase 2b): statt jedes Mal den ganzen Baum (GET api/notes) nur das seit dem
letzten Stand Geaenderte holen (GET api/notes?since=<cursor>). Vertrag: docs/APP-API-LIVE.md "Seitenbaum abgleichen"
(Server-Repo), gleiche Logik wie iOS (NoteSyncLogic.swift) und Android (NoteSync.kt).
- ?delta=1 -> {cursor, full: true, notes, removed: []}; ?since=c -> {cursor, full: false, notes (mit "after"), removed}
- full: true jederzeit moeglich -> Liste ersetzen; cursor: null -> beim naechsten Mal wieder delta=1
- Antwort ist eine Liste -> aelterer Server ohne Abgleich: wie bisher die ganze Liste
Reine Logik ohne GTK (tests/test_delta.py)."""
import json
import time
import urllib.parse

# Sicherheitsnetz: spaetestens nach einem Tag wieder die ganze Liste (der Server liefert full: true ohnehin, wenn
# der Stand nicht passt)
FULL_EVERY_S = 24 * 3600

_MISSING = object()            # "after" fehlt in einem Eintrag (Stand weicht ab)


def path(since):
    """Anfrage: mit Stand nur Geaendertes, sonst den ganzen Baum mit Stand (aeltere Server ignorieren beides)"""
    if not since:
        return "api/notes?delta=1"
    return "api/notes?since=" + urllib.parse.quote(str(since), safe="")


def cursor_usable(cur, server, account, now=None):
    """Stand nur fuer genau diesen Server und dieses Konto (unbekanntes Konto: nie) und hoechstens FULL_EVERY_S alt"""
    if not isinstance(cur, dict) or not cur.get("value") or not server or not account:
        return False
    if cur.get("server") != server or cur.get("account") != account:
        return False
    try:
        age = (time.time() if now is None else now) - float(cur.get("full_at") or 0)
    except (TypeError, ValueError):
        return False
    return 0 <= age < FULL_EVERY_S


def _cursor_text(v):
    if v is None or isinstance(v, bool):
        return None
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    s = str(v)
    return s or None


def parse(raw):
    """Antwort -> ("list", seiten) bei aelteren Servern oder ("delta", cursor|None, full, notes, removed).
    notes: [(seite ohne "after", after)] mit after = ID, None (= ganz vorn) oder _MISSING."""
    d = json.loads(raw) if isinstance(raw, (bytes, bytearray, str)) else raw
    if isinstance(d, list):
        return ("list", [p for p in d if isinstance(p, dict) and p.get("id") is not None])
    if not isinstance(d, dict) or not isinstance(d.get("notes"), list):
        raise ValueError("unexpected answer from api/notes")
    notes = []
    for n in d["notes"]:
        if not isinstance(n, dict) or n.get("id") is None:
            continue
        n = dict(n)
        after = n.pop("after", _MISSING)
        notes.append((n, after))
    removed = []
    for x in d.get("removed") or []:
        try:
            removed.append(int(x))
        except (TypeError, ValueError):
            pass
    return ("delta", _cursor_text(d.get("cursor")), d.get("full") is True, notes, removed)


def merge(base, notes, removed):
    """Abgleich einarbeiten (full: false): removed- und notes-IDs entfernen, notes der Reihe nach hinter ihr "after"
    (bzw. an den Anfang) einsetzen. -> (neue Liste, consistent). consistent False: ein "after" war unbekannt (Stand
    weicht ab) - die Seite steht dann am Ende, beim naechsten Mal die ganze Liste holen. base bleibt unveraendert."""
    if not notes and not removed:
        return list(base), True
    drop = set(removed)
    drop.update(p["id"] for p, _a in notes)
    follow = {}                                    # after -> einzusetzende Seiten (Listen-Reihenfolge); None = vorn
    consistent, orphans = True, []
    for p, a in notes:
        if a is _MISSING:
            consistent = False
            orphans.append(p)
        else:
            follow.setdefault(a, []).append(p)
    out = []

    def emit(key):
        # ohne Rekursion: lange Ketten neuer Seiten (jede hinter der vorigen) sprengen sonst den Stack
        f = follow.pop(key, None)
        if not f:
            return
        stack = list(reversed(f))
        while stack:
            p = stack.pop()
            out.append(p)
            g = follow.pop(p["id"], None)
            if g:
                stack.extend(reversed(g))

    emit(None)
    for p in base:
        if p["id"] not in drop:
            out.append(p)
            emit(p["id"])
    if follow:                                     # "after" unbekannt: ans Ende, Stand weicht ab
        consistent = False
        rest = {p["id"] for lst in follow.values() for p in lst}
        out.extend(p for p, _a in notes if p["id"] in rest)
    out.extend(orphans)
    return out, consistent


def outcome(resp, base, sent_since):
    """Ergebnis einer Antwort gegen die bisherige Liste -> dict(pages, cursor, full, refetch, legacy).
    pages None: unveraendert. cursor None: beim naechsten Mal ganz laden (auch bei alten Servern). refetch: Abgleich
    passte nicht (unbekanntes "after") - gleich noch einmal ganz holen. sent_since: Anfrage hatte einen Stand; ohne
    Stand ist jede Antwort die ganze Liste (auch wenn full fehlt)."""
    if resp[0] == "list":
        return {"pages": resp[1], "cursor": None, "full": True, "refetch": False, "legacy": True}
    _k, cur, full, notes, removed = resp
    if full or not sent_since:
        return {"pages": [p for p, _a in notes], "cursor": cur, "full": True, "refetch": False, "legacy": False}
    if not notes and not removed:
        return {"pages": None, "cursor": cur, "full": False, "refetch": False, "legacy": False}
    pages, ok = merge(base, notes, removed)
    return {"pages": pages, "cursor": cur if ok else None, "full": False, "refetch": not ok, "legacy": False}
