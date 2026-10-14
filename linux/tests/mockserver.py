"""Nachgebauter Saga-Server fuer Tests (nichts verlaesst den Rechner): ersetzt api.client._send.

    import mockserver; srv = mockserver.install()          # Standard-Daten, Server https://saga.test
    srv.delay["PATCH api/notes/1"] = 1.0                    # langsame Antwort
    srv.fail["GET api/notes"] = 0                           # 0 = nicht erreichbar, sonst HTTP-Code
    srv.log                                                 # [(methode, url, body, cookie)]
"""
import io
import json
import threading
import time
import urllib.parse

SERVER = "https://saga.test"


def _blocks(*texts):
    return json.dumps({"blocks": [{"id": f"b{i}", "type": "paragraph", "data": {"text": t}} for i, t in enumerate(texts)]})


def demo_blocks():
    return [
        {"id": "h1", "type": "header", "data": {"text": "Projekt &amp; Plan", "level": 1}},
        {"id": "p1", "type": "paragraph", "data": {"text": "Ein Absatz mit <b>fett</b>, <i>kursiv</i> und <a href=\"/notes#3\">Link</a>."}},
        {"id": "l1", "type": "list", "data": {"style": "checklist", "meta": {}, "items": [
            {"content": "Erledigt", "meta": {"checked": True}, "items": []},
            {"content": "Offen", "meta": {"checked": False}, "items": []}]}},
        {"id": "l2", "type": "list", "data": {"style": "unordered", "meta": {}, "items": [
            {"content": "Punkt eins", "meta": {}, "items": [{"content": "Unterpunkt", "meta": {}, "items": []}]},
            {"content": "Punkt zwei", "meta": {}, "items": []}]}},
        {"id": "q1", "type": "quote", "data": {"text": "Ein Zitat", "caption": "", "alignment": "left"}},
        {"id": "c1", "type": "callout", "data": {"emoji": "💡", "text": "Ein Hinweis"}},
        {"id": "t1", "type": "table", "data": {"withHeadings": True, "content": [["Name", "Wert"], ["A", "1"], ["B", "2"]]}},
        {"id": "col", "type": "columns", "data": {"cols": [
            {"blocks": [{"id": "cl1", "type": "paragraph", "data": {"text": "Linke Spalte"}}]},
            {"blocks": [{"id": "cr1", "type": "paragraph", "data": {"text": "Rechte Spalte"}}]}]}},
        {"id": "db1", "type": "database", "data": {"db": 10}},
        {"id": "p2", "type": "paragraph", "data": {"text": "Ende"}},
    ]


def _png(color=(30, 30, 30, 255), size=64):
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return b""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((6, 6, size - 6, size - 6), radius=12, fill=color)
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()


class MockServer:
    def __init__(self):
        self.lock = threading.Lock()
        self.log = []
        self.delay = {}          # "METHODE pfad" -> Sekunden
        self.fail = {}           # "METHODE pfad" -> HTTP-Code (0 = nicht erreichbar)
        self.sessions = {SERVER: "sess-ok"}       # Server -> gueltiges Sitzungs-Cookie
        self.devices = {}        # Geraete-Schluessel -> Server
        self.forgotten = []      # [(server, geraete-schluessel)] - /app/forget
        self.users = {}          # Server -> angemeldeter Benutzername (/api/account), Standard "test"
        self.next_id = 100
        now = time.time()
        self.notes = {
            1: {"id": 1, "title": "Startseite", "format": "blocks", "body": json.dumps({"blocks": demo_blocks()}), "rev": 5,
                "parent_id": None, "icon": "📘", "updated": now, "created": now, "pinned": True},
            2: {"id": 2, "title": "Unterseite", "format": "blocks", "body": _blocks("Text der Unterseite"), "rev": 1,
                "parent_id": 1, "icon": "", "updated": now, "created": now},
            3: {"id": 3, "title": "Notizen", "format": "blocks", "body": _blocks("Erste Notiz"), "rev": 1,
                "parent_id": None, "icon": "📝", "updated": now, "created": now},
            10: {"id": 10, "title": "Aufgabenliste", "kind": "database", "format": "blocks", "body": "", "rev": 1, "parent_id": None,
                 "icon": "🗃️", "updated": now, "created": now,
                 "schema": {"props": [
                     {"id": "st", "name": "Status", "type": "select", "options": [
                         {"id": "o1", "name": "Offen", "color": "blue"}, {"id": "o2", "name": "Fertig", "color": "green"}]},
                     {"id": "dt", "name": "Datum", "type": "date"},
                     {"id": "nr", "name": "Zahl", "type": "number"},
                     {"id": "ok", "name": "Erledigt", "type": "checkbox"}],
                     "views": [{"id": "tabelle", "name": "Tabelle", "type": "table"}, {"id": "board", "name": "Board", "type": "board"}]}},
            11: {"id": 11, "title": "Zeile A", "format": "blocks", "body": _blocks("Inhalt A"), "rev": 1, "parent_id": 10,
                 "updated": now, "created": now, "props": {"st": "o1", "dt": "2026-10-14", "nr": 3}},
            12: {"id": 12, "title": "Zeile B", "format": "blocks", "body": _blocks("Inhalt B"), "rev": 1, "parent_id": 10,
                 "updated": now, "created": now, "props": {"st": "o2", "nr": 5, "ok": True}},
        }
        self.trash = []
        self.tasks = [{"id": 1, "text": "Einkaufen", "prio": 2, "due": time.strftime("%Y-%m-%d")},
                      {"id": 2, "text": "Bericht schreiben", "prio": 1}]
        self.done_tasks = [{"id": 3, "text": "Erledigte Aufgabe", "done": True}]
        self.services = [{"name": "Dunkles Logo", "host": "dunkel.example.org", "url": "https://dunkel.example.org", "icon": "dark",
                          "category": "Werkzeuge"},
                         {"name": "Helles Logo", "host": "hell.example.org", "url": "https://hell.example.org", "icon": "light",
                          "category": "Werkzeuge"},
                         {"name": "Ohne Symbol mit sehr langem Namen", "host": "lang.example.org", "url": "https://lang.example.org",
                          "category": "Medien"}]

    # --- Hilfen ------------------------------------------------------------------------------------
    def note_meta(self, n):
        return {k: n.get(k) for k in ("id", "title", "icon", "parent_id", "kind", "pinned", "updated", "archived", "template", "tags")}

    def _reply(self, code, obj=None, headers=None):
        body = obj if isinstance(obj, bytes) else json.dumps(obj if obj is not None else {}).encode()
        return code, body, headers or {}

    # --- Anfrage ------------------------------------------------------------------------------------
    def handle(self, method, url, data, cookie):
        u = urllib.parse.urlsplit(url)
        base = f"{u.scheme}://{u.netloc}"
        path = u.path.strip("/")
        q = dict(urllib.parse.parse_qsl(u.query))
        body = None
        if data:
            try:
                body = json.loads(data)
            except (ValueError, UnicodeDecodeError):
                body = None
        self.log.append((method, url, body if body is not None else data, cookie))
        key = f"{method} {path}"
        if key in self.delay:
            time.sleep(self.delay[key])
        if key in self.fail:
            code = self.fail[key]
            if code == 0:
                raise OSError("mock: nicht erreichbar")
            return self._reply(code, {"error": f"mock {code}"})
        if path == "app/forget":
            self.forgotten.append((base, (body or {}).get("device_token")))
        if path == "api/version":
            return self._reply(200, {"version": "1.0.0", "min_app": "1.0.0"})
        if path == "app/pair":
            if (body or {}).get("code", "").startswith("bad"):
                return self._reply(400, {"error": "Code ungueltig"})
            tok = "hkd_" + u.netloc
            self.devices[tok] = base
            out = {"device_token": tok, "name": "Test"}
            if (body or {}).get("app_key"):
                out["app_key"] = "hk_key_for_" + u.netloc
            return self._reply(200, out, {"Set-Cookie": [f"hk_session=sess-{u.netloc}; Path=/; HttpOnly"]})
        if path == "app/exchange":
            tok = "hkd_" + u.netloc
            self.devices[tok] = base
            return self._reply(200, {"device_token": tok, "name": "Test"}, {"Set-Cookie": [f"hk_session=sess-{u.netloc}; Path=/"]})
        if path == "app/resume":
            tok = (body or {}).get("device_token")
            if self.devices.get(tok) == base:
                return self._reply(200, {}, {"Set-Cookie": [f"hk_session={self.sessions.get(base, 'sess-' + u.netloc)}; Path=/"]})
            return self._reply(401, {"error": "unknown device"})
        if path in ("app/forget", "app/device"):
            return self._reply(200, {})
        # angemeldet?
        want = self.sessions.get(base)
        if not cookie or want is None or f"hk_session={want}" not in cookie:
            return self._reply(401, {"error": "unauthorized"})
        if path == "api/account":
            return self._reply(200, {"username": self.users.get(base, "test"), "name": "Test"})
        with self.lock:
            return self._api(method, path, q, body)

    def _api(self, method, path, q, body):
        parts = path.split("/")
        if path == "api/notes" and method == "GET":
            return self._reply(200, [self.note_meta(n) for n in self.notes.values()])
        if path == "api/notes" and method == "POST":
            nid = self.next_id
            self.next_id += 1
            n = {"id": nid, "title": body.get("title") or "", "format": "blocks", "body": json.dumps({"blocks": []}), "rev": 0,
                 "parent_id": body.get("parent_id"), "kind": body.get("kind"), "icon": body.get("icon") or "",
                 "updated": time.time(), "created": time.time(), "props": body.get("props") or {}}
            if n["kind"] == "database":
                n["schema"] = {"props": [], "views": [{"id": "tabelle", "name": "Table", "type": "table"}]}
            self.notes[nid] = n
            return self._reply(200, {"id": nid, "updated": n["updated"]})
        if path == "api/notes/search":
            qq = (body or {}).get("q", "").lower()
            return self._reply(200, [self.note_meta(n) for n in self.notes.values() if qq in (n.get("title") or "").lower()])
        if len(parts) >= 3 and parts[:2] == ["api", "notes"] and parts[2].isdigit():
            nid = int(parts[2])
            n = self.notes.get(nid)
            if n is None:
                return self._reply(404, {"error": "not found"})
            rest = parts[3:]
            if not rest and method == "GET":
                out = dict(n)
                return self._reply(200, out)
            if not rest and method == "PATCH":
                content = "body" in body or "title" in body
                if content and body.get("rev") is not None and body["rev"] != n["rev"]:
                    return self._reply(409, {"error": "conflict", "rev": n["rev"]})
                for k in ("title", "body", "icon", "pinned", "cover", "cover_pos", "tags", "locked", "wide", "template", "schema"):
                    if k in body:
                        n[k] = body[k]
                if "props" in body:
                    n.setdefault("props", {}).update(body["props"])
                    n["props"] = {k: v for k, v in n["props"].items() if v is not None}
                if content:
                    n["rev"] += 1
                n["updated"] = time.time()
                return self._reply(200, {"ok": True, "updated": n["updated"], "rev": n["rev"]})
            if not rest and method == "DELETE":
                self.trash.append(self.notes.pop(nid))
                return self._reply(200, {"ok": True})
            if rest == ["rows"]:
                rows = [{"id": r["id"], "title": r["title"], "icon": r.get("icon") or "", "props": r.get("props") or {},
                         "updated": r["updated"], "created": r["created"]} for r in self.notes.values() if r.get("parent_id") == nid]
                return self._reply(200, {"schema": n.get("schema") or {"props": []}, "rows": rows, "templates": []})
            if rest == ["backlinks"]:
                return self._reply(200, [])
            if rest == ["versions"]:
                return self._reply(200, [])
            if rest == ["duplicate"]:
                cp = dict(n, id=self.next_id, title=(n.get("title") or "") + " (Kopie)")
                self.notes[cp["id"]] = cp
                self.next_id += 1
                return self._reply(200, {"id": cp["id"]})
            if rest == ["move"]:
                n["parent_id"] = body.get("parent_id")
                return self._reply(200, {"ok": True})
            if rest == ["archive"]:
                n["archived"] = bool(body.get("archived"))
                return self._reply(200, {"ok": True})
        if path == "api/tasks" and method == "GET":
            return self._reply(200, {"open": self.tasks, "done": self.done_tasks})
        if path == "api/tasks" and method == "POST":
            t = dict(body, id=self.next_id)
            self.next_id += 1
            self.tasks.append(t)
            return self._reply(200, t)
        if parts[:2] == ["api", "tasks"] and len(parts) == 3:
            tid = int(parts[2])
            if method == "DELETE":
                self.tasks = [t for t in self.tasks if t["id"] != tid]
                return self._reply(200, {"ok": True})
            for t in self.tasks:
                if t["id"] == tid:
                    t.update(body or {})
            return self._reply(200, {"ok": True})
        if path.startswith("api/calendar/events"):
            return self._reply(200, {"events": [{"summary": "Besprechung", "start": time.strftime("%Y-%m-%dT10:00:00"),
                                                 "end": time.strftime("%Y-%m-%dT11:00:00"), "color": "#e01b24; } * { color: red",
                                                 "calendar": "Arbeit"}]})
        if path == "api/services":
            return self._reply(200, self.services)
        if path.startswith("api/services/icon/"):
            name = parts[-1]
            return self._reply(200, _png((25, 25, 25, 255) if name == "dark" else (240, 240, 240, 255)))
        if path == "api/trash":
            return self._reply(200, [{"id": n["id"], "title": n.get("title"), "icon": n.get("icon"), "purge_at": time.time() + 86400 * 30}
                                     for n in self.trash])
        if parts[:2] == ["api", "trash"] and len(parts) == 3 and method == "DELETE":
            self.trash = [n for n in self.trash if n["id"] != int(parts[2])]
            return self._reply(200, {"ok": True})
        if path.startswith("api/settings"):
            return self._reply(200, {})
        if path.startswith("files/"):
            return self._reply(200, b"x" * 300000)
        return self._reply(200, {})


class _Headers:
    def __init__(self, h):
        self.h = h

    def get_all(self, k):
        v = self.h.get(k)
        return v if isinstance(v, list) else ([v] if v else None)


def install(server=SERVER, logged_in=True):
    """Schein-Server einsetzen und (optional) angemeldet starten. Gibt den MockServer zurueck."""
    from hlidskjalf import api, config
    from hlidskjalf.api import client
    srv = MockServer()

    def send(method, path, data=None, ctype=None, timeout=20, auth=True, **kw):
        url = client.url(path, kw["base"]) if kw.get("base") else client.url(path)
        jar = kw.get("jar")
        cookie = None
        if auth:
            cookie = client.cookie_header_for(url, jar) if hasattr(client, "cookie_header_for") else client._cookie_header()
        try:
            code, body, headers = srv.handle(method, url, data, cookie)
        except OSError as e:
            raise api.APIError(0, str(e)) from e
        if headers:
            hh = _Headers(headers)
            if hasattr(client, "take_cookies_for"):
                client.take_cookies_for(url, hh, jar)
            else:
                client._take_cookies(hh)
        return code, body

    client._send = send
    send.mock = srv
    if logged_in:
        config.settings.server = server
        if hasattr(client, "set_cookies"):
            client.set_cookies(server, {"hk_session": srv.sessions[server]})
        else:
            client.cookies = {"hk_session": srv.sessions[server]}
    return srv
