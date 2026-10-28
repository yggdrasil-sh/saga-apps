"""Gleichzeitig bearbeiten (2.5, P3 Stufe 1, docs/APP-API.md "Gleichzeitig bearbeiten"): die App fuehrt nur Buch, das
Zusammenfuehren rechnet der Server. Reine Logik ohne GTK (testbar):
- adopt(): Stand vom Server blockweise uebernehmen - unveraenderte Bloecke bleiben dieselben Objekte (die Ansicht
  verwendet ihre Widgets weiter, Schreibmarke bleibt), geaenderte/neue kommen vom Server
- from_delta(): GET ?since=<rev> -> delta {from, blocks}: {"id"} allein = eigener Stand von "from"
- Konfliktblock (tunes.hkconflict {by, name, at, of}): conflict_info/take/drop"""
import copy
import json


def key(b):
    try:
        return json.dumps(b, sort_keys=True, ensure_ascii=False)
    except (TypeError, ValueError):
        return None


def _by_id(arr):
    return {b.get("id"): b for b in arr or [] if isinstance(b, dict) and b.get("id")}


def adopt(local, server):
    """-> neue Liste der obersten Ebene: je Block des Servers der lokale (gleiches Objekt), wenn er inhaltlich gleich ist,
    sonst die Fassung des Servers (Reihenfolge des Servers)"""
    mine = _by_id(local)
    out = []
    for b in server or []:
        if not isinstance(b, dict):
            continue
        m = mine.get(b.get("id"))
        out.append(m if m is not None and key(m) == key(b) else b)
    return out


def from_delta(base, delta):
    """delta {from, blocks} + eigener Stand von "from" (base) -> vollstaendige Blockliste oder None (fehlt ein Block)"""
    if not isinstance(delta, dict) or not isinstance(delta.get("blocks"), list):
        return None
    have = _by_id(base)
    out = []
    for b in delta["blocks"]:
        if not isinstance(b, dict) or not b.get("id"):
            return None
        if set(b) == {"id"}:
            x = have.get(b["id"])
            if x is None:
                return None                                  # eigener Stand passt nicht: ganz laden
            out.append(copy.deepcopy(x))
        else:
            out.append(b)
    return out


# --- Konfliktblock -------------------------------------------------------------------------------------------------------
def conflict_info(b):
    """tunes.hkconflict {by, name, at, of} eines Blocks oder None"""
    t = b.get("tunes") if isinstance(b, dict) else None
    c = t.get("hkconflict") if isinstance(t, dict) else None
    return c if isinstance(c, dict) and c else None


def _walk(arr):
    for b in arr or []:
        if not isinstance(b, dict):
            continue
        yield arr, b
        d = b.get("data") if isinstance(b.get("data"), dict) else {}
        if isinstance(d.get("blocks"), list):
            yield from _walk(d["blocks"])
        for c in d.get("cols") or []:
            if isinstance(c, dict) and isinstance(c.get("blocks"), list):
                yield from _walk(c["blocks"])


def _find(root, bid):
    return next(((a, b) for a, b in _walk(root) if b.get("id") == bid), (None, None))


def conflict_take(root, bid):
    """Uebernehmen: Original (of) loeschen, hkconflict entfernen (andere Tunes bleiben) -> True, wenn geaendert"""
    arr, b = _find(root, bid)
    c = conflict_info(b) if b is not None else None
    if c is None:
        return False
    of = c.get("of")
    if isinstance(of, str) and of and of != bid:
        oa, ob = _find(root, of)
        if ob is not None:
            oa.remove(ob)
    b["tunes"].pop("hkconflict", None)
    if not b["tunes"]:
        b.pop("tunes", None)
    return True


def conflict_drop(root, bid):
    """Verwerfen: Konfliktblock loeschen -> True, wenn geaendert"""
    arr, b = _find(root, bid)
    if b is None or conflict_info(b) is None:
        return False
    arr.remove(b)
    return True
