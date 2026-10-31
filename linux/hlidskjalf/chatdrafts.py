"""Chat-Entwuerfe (Saga 2.5, docs/APP-API-CHAT.md 9.7): je Chat lokal, je Server UND Konto getrennt, nie an den Server.
    ~/.local/share/hlidskjalf/chat-drafts/<kennung>.json   {"<chat-id>": {"t": text, "p": {"@Name": kennung}, "at": ms}}
<kennung> = sha256("<server>|<konto>")[:16] - ein anderes Konto sieht fremde Entwuerfe nie. Abmelden loescht die Datei des
Kontos (clear_account), Neubeginn der Chats bzw. ein entferntes Geraet ebenso. Ohne bekanntes Konto: nichts speichern."""
import hashlib
import json
import os
import re
import threading
import time

from . import config

DIR = os.path.join(config.DATA_DIR, "chat-drafts")
MAX = 20000
_lock = threading.Lock()
_mem = {}                     # Pfad -> dict (Zwischenspeicher)
REF_RX = re.compile(r"~[a-z2-7]{16}(?:@[a-z0-9.:\-]{1,260})?")


def path(server=None, account=None):
    srv = config.origin(server or config.settings.server) or ""
    acc = account if account is not None else config.account(srv)
    if not srv or not acc:
        return None
    return os.path.join(DIR, hashlib.sha256(f"{srv}|{acc}".encode()).hexdigest()[:16] + ".json")


def _all(p):
    if p is None:
        return {}
    if p in _mem:
        return _mem[p]
    try:
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
    except (OSError, ValueError):
        d = {}
    _mem[p] = d if isinstance(d, dict) else {}
    return _mem[p]


def _write(p, d):
    try:
        os.makedirs(os.path.dirname(p), mode=0o700, exist_ok=True)
        if d:
            config.atomic_write(p, json.dumps(d, ensure_ascii=False))
        elif os.path.exists(p):
            os.remove(p)
    except OSError:
        pass


def load(cid):
    """-> {"t": text, "p": {"@Name": kennung}} | None"""
    with _lock:
        e = _all(path()).get(str(cid))
    if not isinstance(e, dict) or not isinstance(e.get("t"), str) or not e["t"].strip():
        return None
    ppl = {k: v for k, v in (e.get("p") or {}).items() if isinstance(k, str) and isinstance(v, str) and REF_RX.fullmatch(v)}
    return {"t": e["t"][:MAX], "p": ppl}


def has(cid):
    with _lock:
        e = _all(path()).get(str(cid))
    return isinstance(e, dict) and bool(str(e.get("t") or "").strip())


def save(cid, text, people=None):
    """Text leer -> Entwurf weg. -> True, wenn sich etwas geaendert hat"""
    p = path()
    if p is None or cid is None:
        return False
    with _lock:
        d = _all(p)
        k = str(cid)
        t = str(text or "")[:MAX]
        if not t.strip():
            if k not in d:
                return False
            d.pop(k, None)
        else:
            new = {"t": t, "p": {a: b for a, b in (people or {}).items() if a in t}, "at": int(time.time() * 1000)}
            if (d.get(k) or {}).get("t") == t and (d.get(k) or {}).get("p") == new["p"]:
                return False
            d[k] = new
        _write(p, d)
    return True


def drop(cid):
    return save(cid, "")


def clear_account(server=None, account=None):
    """Abmelden: alle Entwuerfe dieses Kontos weg"""
    p = path(server, account)
    if p is None:
        return
    with _lock:
        _mem.pop(p, None)
        try:
            os.remove(p)
        except OSError:
            pass


def reset_cache():
    with _lock:
        _mem.clear()
