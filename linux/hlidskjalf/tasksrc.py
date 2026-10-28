"""Aufgabenquellen (2.5, Paket 7, tasksrc1/taskdue1 - docs/APP-API.md "Aufgabenquellen"): Datenbank-Zeilen zusammen mit
den eigenen Aufgaben (Inbox) in einer Liste. Reine Logik ohne GTK (testbar):
- tasks_path(): GET /api/tasks?with_sources=1&today=&tz= (Reihenfolge rechnet der Server)
- Queue: Offline-Warteschlange fuer Abhaken/Wieder-Oeffnen, Schluessel t:<id>/r:<id>, letzte Aktion gewinnt, prev merken,
  403/404 verwerfen + neu laden, 400 neu laden (nicht wiederholen), Netz/5xx behalten; auf der Platte je Server+Konto
- targets(): Ziele fuer "Neue Aufgabe" (Inbox + Quellen mit map, aktiv, Rolle owner/edit)"""
import hashlib
import json
import os
import urllib.parse

from . import config
from .util import iso_day

DIR = os.path.join(config.DATA_DIR, "taskqueue")


def tasks_path(today=None, tz=None):
    if tz is None:
        from .push import local_tz
        tz = local_tz()
    return "api/tasks?" + urllib.parse.urlencode({"with_sources": 1, "today": today or iso_day(), "tz": tz or ""})


def item_key(it):
    k = it.get("key") if isinstance(it, dict) else None
    if isinstance(k, str) and k:
        return k
    return ("r:" if it.get("kind") == "row" else "t:") + str(it.get("id"))


def items(resp):
    """-> Liste der Eintraege in Server-Reihenfolge (nur gueltige)"""
    out = []
    for it in (resp or {}).get("items") or []:
        if isinstance(it, dict) and it.get("kind") in ("task", "row") and isinstance(it.get("id"), int):
            out.append(it)
    return out


def sources_by_db(resp):
    return {s.get("db"): s for s in (resp or {}).get("sources") or [] if isinstance(s, dict) and isinstance(s.get("db"), int)}


def targets(sources):
    """Ziele fuer eine neue Aufgabe: Quellen mit Zuordnung, nicht inaktiv, Rolle owner/edit"""
    return [s for s in sources or [] if isinstance(s, dict) and s.get("map") and not s.get("inactive") and not s.get("unmapped")
            and s.get("role") in ("owner", "edit")]


def when_text(it):
    """Datum (Tag), Uhrzeit, Zeitraum -> kurzer Text (leer ohne Datum)"""
    from .util import day_text
    due = it.get("due")
    if not isinstance(due, str) or not due:
        return ""
    s = day_text(due)
    if isinstance(it.get("time"), str) and it["time"]:
        s += " " + it["time"]
    end = it.get("due_end")
    if isinstance(end, str) and end and end != due:
        s += " – " + day_text(end)
    return s


# --- Offline-Warteschlange --------------------------------------------------------------------------------------------
def _qpath(server=None, account=None):
    srv = config.origin(server or config.settings.server) or "-"
    acc = account if account is not None else config.account(srv)
    key = f"{srv}|{acc}" if acc else srv
    return os.path.join(DIR, hashlib.sha256(key.encode()).hexdigest()[:16] + ".json")


class Queue:
    """Eintraege {key: {"kind": "task"|"row", "id", "done": bool, "value"?: prev}} - je Aufgabe/Zeile genau einer.
    Abhaken + wieder oeffnen vor dem Senden = nichts (der Eintrag faellt weg)."""

    def __init__(self, path=None):
        self.path = path or _qpath()
        self.d = {}
        self.load()

    def load(self):
        try:
            with open(self.path, encoding="utf-8") as f:
                d = json.load(f)
            self.d = {k: v for k, v in d.items() if isinstance(v, dict)} if isinstance(d, dict) else {}
        except (OSError, ValueError):
            self.d = {}

    def save(self):
        try:
            if not self.d:
                os.remove(self.path)
                return
        except OSError:
            return
        try:
            os.makedirs(os.path.dirname(self.path), mode=0o700, exist_ok=True)
            config.atomic_write(self.path, json.dumps(self.d, ensure_ascii=False))
        except (OSError, TypeError, ValueError):
            pass

    def __len__(self):
        return len(self.d)

    def entries(self):
        return list(self.d.items())

    def put(self, it, done, prev=None):
        """Aktion merken (letzte gewinnt). prev = Wert der Erledigt-Eigenschaft vor dem Abhaken (Zeilen)"""
        k = item_key(it)
        old = self.d.get(k)
        if old is not None and old.get("orig_done") == done:
            self.d.pop(k)                                    # abhaken + wieder oeffnen (bzw. umgekehrt) = nichts
            self.save()
            return None
        e = {"kind": it.get("kind") or "task", "id": it["id"], "done": bool(done),
             "orig_done": old.get("orig_done") if old else (not done)}
        p = old.get("prev") if old and "prev" in old else prev
        if p is not None or (old and "prev" in old):
            e["prev"] = p
        self.d[k] = e
        self.save()
        return e

    def drop(self, k):
        if self.d.pop(k, None) is not None:
            self.save()

    @staticmethod
    def request(e):
        """-> (methode, pfad, body) fuer den Eintrag"""
        if e.get("kind") == "row":
            b = {"done": bool(e["done"])}
            if not e["done"] and "prev" in e:
                b["value"] = e["prev"]                       # Rueckgaengig: Wert vor dem Abhaken, nie pauschal open_value
            return "PATCH", f"api/tasks/src/{e['id']}", b
        return "PATCH", f"api/tasks/{e['id']}", {"done": bool(e["done"])}

    @staticmethod
    def outcome(code):
        """Antwortcode beim Nachholen -> 'ok' | 'drop' (403/404: verwerfen, neu laden) | 'reload' (400: neu laden, nicht
        wiederholen) | 'keep' (Netz/5xx: spaeter)"""
        if code is None or 200 <= code < 300:
            return "ok"
        if code in (403, 404, 410):
            return "drop"
        if code == 400 or 400 <= code < 500 and code not in (401, 408, 429):
            return "reload"
        return "keep"
