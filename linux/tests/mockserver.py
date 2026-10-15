"""Nachgebauter Saga-Server fuer Tests (nichts verlaesst den Rechner): ersetzt api.client._send.

    import mockserver; srv = mockserver.install()          # Standard-Daten, Server https://saga.test
    srv.delay["PATCH api/notes/1"] = 1.0                    # langsame Antwort
    srv.fail["GET api/notes"] = 0                           # 0 = nicht erreichbar, sonst HTTP-Code
    srv.log                                                 # [(methode, url, body, cookie)]
"""
import io
import json
import re
import threading
import time
import urllib.parse

import mockv2

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
        # Teilen + Kommentare (Server ab 1.3): share_api=False wie ein aelterer Server (404, keine Felder in der Seite).
        # Fremde Seiten (add_foreign) gehoeren z. B. "anna", ich bin "test"; meine Rolle dort steht in grants.
        self.share_api = True
        self.share_enabled = True
        self.me = "test"
        self.names = {"test": "Test", "anna": "Anna Beispiel", "bert": "Bert Beispiel", "cora": "Cora Muster"}
        self.groups = {"team": ["test", "bert"]}           # Gruppe -> Mitglieder
        self.foreign = {}        # Seiten anderer Konten: id -> Seite mit "uid" (Eigentuemer)
        self.grants = {}         # fremde Seite -> Rolle fuer mich (vererbt ueber parent_id)
        self.shares = {}         # eigene Seite -> [{type, target, role}]
        self.comments = []       # [{id, note_id, thread_id, block_id, uid, body, created, edited, resolved_at, resolved_by}]
        self.seen = {}           # (uid, thread) -> Zeitpunkt
        self.comment_429 = False
        self.last_overwrite = None
        self.headers_log = []    # [(methode, url, zusaetzliche Kopfzeilen)]
        # Veroeffentlichen (Server ab 1.2): publish_api=False wie ein aelterer Server (404, keine Felder in der Seite)
        self.publish_api = True
        self.publish_enabled = True
        self.public_name = None
        self.taken_names = {"vergeben"}
        self.pubs = {}           # Seite -> {slug, children, indexable, profile, active, withdrawn_at, withdrawn_by}
        self.public_home = 0
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
        mockv2.init(self)                    # Saga 2.0: Live, Benachrichtigungen, Chat, Push, KI, Foederation

    # --- Saga 2.0 (mockv2.py) --------------------------------------------------------------------------
    def push_event(self, ev, data):
        mockv2.push_event(self, ev, data)

    def chat_peer(self, uid, name=None, ready=True):
        return mockv2.chat_peer(self, uid, name, ready)

    def new_chat(self, kind, members, **kw):
        return mockv2.new_chat(self, kind, members, **kw)

    def peer_send(self, cid, uid, text, **kw):
        return mockv2.peer_send(self, cid, uid, text, **kw)

    def add_fed(self, nid, title, **kw):
        return mockv2.add_fed(self, nid, title, **kw)

    def add_notif(self, kind, **kw):
        nid = 9000 + len(self.notifs)
        it = dict({"id": nid, "kind": kind, "created": time.time(), "read": False, "actor": {"name": "Anna Beispiel", "username": "anna"},
                   "count": 1, "access": True, "note": {"id": 1, "title": "Startseite", "icon": "📘"}, "title": "Startseite",
                   "text": "", "url": "/notes#1"}, **kw)
        self.notifs.insert(0, it)
        return it

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
            self._raw = data
            return self._api(method, path, q, body)

    def _api(self, method, path, q, body):
        parts = path.split("/")
        r = mockv2.handle(self, method, path, parts, q, body if isinstance(body, dict) else {}, getattr(self, "_raw", None))
        if r is not None:
            return r
        r = self._share_api(method, path, parts, q, body if isinstance(body, dict) else {})
        if r is not None:
            return r
        if path == "api/notes" and method == "GET":
            if self.share_api:
                return self._reply(200, [dict(self.note_meta(n), shared=bool(self.shares.get(n["id"])),
                                              unread=any(t["unread"] for t in self._threads(n["id"], False)))
                                         for n in self.notes.values()])
            return self._reply(200, [self.note_meta(n) for n in self.notes.values()])
        if path == "api/notes" and method == "POST":
            nid = self.next_id
            self.next_id += 1
            n = {"id": nid, "title": body.get("title") or "", "format": "blocks", "body": json.dumps({"blocks": []}), "rev": 0,
                 "parent_id": body.get("parent_id"), "kind": body.get("kind"), "icon": body.get("icon") or "",
                 "updated": time.time(), "created": time.time(), "props": body.get("props") or {}}
            if n["kind"] == "database":
                n["schema"] = {"props": [], "views": [{"id": "tabelle", "name": "Table", "type": "table"}]}
            par = body.get("parent_id")
            if par in self.foreign:                            # Unterseite einer fremden Seite: gehoert deren Eigentuemer
                if self.RANK.get(self.role_for(par), 0) < self.RANK["edit"]:
                    return self._reply(403, {"error": "Dafür fehlt dir die Berechtigung."})
                n.update(uid=self.foreign[par]["uid"], updated_by=self.me, created_by=self.me)
                self.foreign[nid] = n
                return self._reply(200, {"id": nid, "updated": n["updated"]})
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
                if self.publish_api:
                    st = self.pub_state(nid)
                    out.update(published=st["published"], published_via=st["published_via"], public_url=st["url"])
                if self.share_api:
                    out.update(self._share_fields(nid, n, "owner"))
                return self._reply(200, out)
            if rest == ["publish"]:
                return self._publish(method, nid, n, body or {}) if self.publish_api else self._reply(404, {"error": "not found"})
            if not rest and method == "PATCH":
                content = "body" in body or "title" in body
                if content and body.get("rev") is not None and body["rev"] != n["rev"]:
                    by = n.get("updated_by")
                    out = {"error": "conflict", "rev": n["rev"]}
                    if self.share_api:
                        out["by"] = self.names.get(by, by) if by and by != self.me else None
                    return self._reply(409, out)
                if content:
                    self.last_overwrite = bool(body.get("overwrite"))
                    n["updated_by"] = self.me
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
        if path == "api/account/public-name":
            return self._public_name(method, body or {}) if self.publish_api else self._reply(404, {"error": "not found"})
        if path.startswith("api/settings"):
            return self._reply(200, {})
        if path.startswith("files/"):
            up = getattr(self, "uploads", {}).get("/" + path.split("?")[0])
            return self._reply(200, up if up else b"x" * 300000)
        return self._reply(200, {})


    # --- Veroeffentlichen (wie saga/publish.py, vereinfacht) -------------------------------------------
    NAME_RE = re.compile(r"[a-z0-9][a-z0-9_-]{1,31}")
    SLUG_RE = re.compile(r"[a-z0-9][a-z0-9_-]{1,59}")

    @staticmethod
    def slugify(s):
        s = (s or "").lower().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        return re.sub(r"[^a-z0-9]+", "-", s).strip("-")[:60].strip("-")

    def name_problem(self, name, check_taken=True):
        if not self.NAME_RE.fullmatch(name or ""):
            return "2–32 Zeichen: a–z, 0–9, - und _ (klein, am Anfang ein Buchstabe oder eine Ziffer)."
        if name in ("admin", "api", "saga"):
            return "Dieser Name ist reserviert."
        if check_taken and name in self.taken_names:
            return "Dieser Name ist schon vergeben."
        return None

    def pub_via(self, nid):
        name = self.public_name
        if not name:
            return None
        p = self.notes.get(nid, {}).get("parent_id")
        while p:
            x = self.pubs.get(p)
            if x and x["active"] and x["children"]:
                return {"note_id": p, "title": self.notes[p].get("title") or "Unbenannt", "url": f"{SERVER}/@{name}/{x['slug']}/{nid}"}
            p = self.notes.get(p, {}).get("parent_id")
        return None

    def pub_state(self, nid):
        name = self.public_name
        r = self.pubs.get(nid)
        active = bool(r and r["active"])
        out = {"enabled": self.publish_enabled, "name": name, "published": active, "home": False, "url": None,
               "profile_url": f"{SERVER}/@{name}" if name else None, "published_via": self.pub_via(nid),
               "withdrawn_at": None, "withdrawn_by": None}
        if r:
            out.update({k: r[k] for k in ("slug", "children", "indexable", "profile")})
            if active:
                out["url"] = f"{SERVER}/@{name}/{r['slug']}" if name else None
                out["home"] = self.public_home == nid
            else:
                out.update(withdrawn_at=r.get("withdrawn_at"), withdrawn_by=r.get("withdrawn_by"))
        else:
            out.update(slug=self.slugify(self.notes[nid].get("title")) or "unbenannt", children=False, indexable=False, profile=False)
        other = next((k for k, x in self.pubs.items() if x["profile"] and x["active"] and k != nid), None)
        out["profile_other"] = {"note_id": other, "title": self.notes[other].get("title") or "Unbenannt"} if other else None
        if not name:
            out["suggested_name"] = "test-autor"
        return out

    def _publish(self, method, nid, n, b):
        if method == "GET":
            return self._reply(200, self.pub_state(nid))
        if method == "DELETE":
            r = self.pubs.get(nid)
            if r and r["active"]:
                r.update(active=False, withdrawn_at=time.time(), withdrawn_by="user")
            return self._reply(200, dict(self.pub_state(nid), ok=True, removed=bool(r)))
        if not self.publish_enabled:
            return self._reply(403, {"error": "Veröffentlichen ist auf diesem Server abgeschaltet."})
        if n.get("template"):
            return self._reply(400, {"error": "Vorlagen können nicht veröffentlicht werden."})
        if not self.public_name:
            if b.get("name"):
                err = self.name_problem(str(b["name"]).strip().lower())
                if err:
                    return self._reply(400, {"error": err, "need_name": True})
                self.public_name = str(b["name"]).strip().lower()
            else:
                return self._reply(409, {"error": "Bitte zuerst einen öffentlichen Namen wählen.", "need_name": True,
                                         "suggested_name": "test-autor"})
        old = self.pubs.get(nid) or {"slug": None, "children": False, "indexable": False, "profile": False}
        if str(b.get("slug") or "").strip():
            slug = str(b["slug"]).strip().lower()
            if not self.SLUG_RE.fullmatch(slug):
                return self._reply(400, {"error": "Adresse: 2–60 Zeichen, a–z, 0–9, - und _ (klein)."})
            if any(x["slug"] == slug and k != nid for k, x in self.pubs.items()):
                return self._reply(409, {"error": "Diese Adresse benutzt schon eine andere deiner Seiten."})
        else:
            slug = old["slug"] or self.slugify(n.get("title")) or "unbenannt"
        fl = {k: (bool(b[k]) if k in b else old[k]) for k in ("children", "indexable", "profile")}
        replaced = None
        if fl["profile"]:
            replaced = next((k for k, x in self.pubs.items() if x["profile"] and x["active"] and k != nid), None)
            for k, x in self.pubs.items():
                if k != nid:
                    x["profile"] = False
        self.pubs[nid] = dict(fl, slug=slug, active=True, withdrawn_at=None, withdrawn_by=None)
        out = self.pub_state(nid)
        if replaced:
            out["replaced_profile"] = {"note_id": replaced, "title": self.notes[replaced].get("title") or "Unbenannt"}
        return self._reply(200, out)

    def _public_name(self, method, b):
        if method == "GET":
            n = sum(1 for x in self.pubs.values() if x["active"])
            return self._reply(200, {"name": self.public_name, "url": f"{SERVER}/@{self.public_name}" if self.public_name else None,
                                     "enabled": self.publish_enabled, "published": n, "suggested": self.public_name or "test-autor"})
        name = str(b.get("name") or "").strip().lower()
        err = self.name_problem(name)
        if b.get("check"):
            return self._reply(200, {"ok": not err, "available": not err, "name": name, **({"error": err} if err else {})})
        if err:
            return self._reply(409 if "vergeben" in err else 400, {"error": err})
        self.public_name = name
        return self._reply(200, {"ok": True, "name": name, "url": f"{SERVER}/@{name}"})


    # --- Teilen + Kommentare (wie saga/sharing.py und saga/comments.py, vereinfacht) -------------------------------
    RANK = {None: 0, "view": 1, "comment": 2, "edit": 3, "owner": 4}

    def add_foreign(self, nid, title, owner="anna", role=None, parent_id=None, blocks=None, **kw):
        """Seite eines anderen Kontos anlegen; role: meine Rolle (direkt freigegeben), sonst geerbt"""
        now = time.time()
        n = {"id": nid, "title": title, "format": "blocks", "rev": 1, "parent_id": parent_id, "icon": kw.pop("icon", ""),
             "updated": now, "created": now, "uid": owner, "updated_by": kw.pop("updated_by", owner),
             "body": json.dumps({"blocks": blocks if blocks is not None else [
                 {"id": f"f{nid}", "type": "paragraph", "data": {"text": f"Text von {title}"}}]})}
        n.update(kw)
        self.foreign[nid] = n
        if role:
            self.grants[nid] = role
        return n

    def role_for(self, nid):
        """meine Rolle fuer eine Seite: eigene -> owner, fremde -> hoechste Freigabe in der Kette (oder None)"""
        if nid in self.notes:
            return "owner"
        if nid not in self.foreign or not self.share_enabled:
            return None
        r, x, guard = None, nid, 0
        while x is not None and x in self.foreign and guard < 50:
            g = self.grants.get(x)
            if self.RANK.get(g, 0) > self.RANK.get(r, 0):
                r = g
            x, guard = self.foreign[x].get("parent_id"), guard + 1
        return r

    def person(self, uid):
        if not uid or uid not in self.names:
            return {"name": "Gelöschtes Konto", "username": None, "me": False}
        return {"name": self.names[uid], "username": uid, "me": uid == self.me}

    def _counts(self, nid):
        per, unread = {}, 0
        for t in self._threads(nid, True):
            if t["resolved_at"]:
                continue
            per[t["block_id"]] = per.get(t["block_id"], 0) + 1
            unread += 1 if t["unread"] else 0
        per.pop("", None)
        return per, sum(1 for t in self._threads(nid, False)), unread

    def _share_fields(self, nid, n, role):
        per, n_open, n_unread = self._counts(nid)
        owner = n.get("uid") or self.me
        ub = n.get("updated_by")
        return {"role": role, "owner": self.person(owner), "shared": bool(self.shares.get(nid)) if role == "owner" else True,
                "comment_counts": per, "comments_open": n_open, "comments_unread": n_unread,
                "comments_resolved": sum(1 for c in self.comments if c["note_id"] == nid and c["thread_id"] is None and c["resolved_at"]),
                "updated_by": self.person(ub) if ub else None,
                "created_by": self.person(n["created_by"]) if n.get("created_by") and n.get("created_by") != owner else None}

    def _foreign_meta(self, nid, role):
        n = self.foreign[nid]
        d = self.note_meta(n)
        par = n.get("parent_id")
        root = not (par in self.foreign and self.role_for(par))
        d.update(role=role, root=root, shared=True, pinned=False, owner={"name": self.names.get(n["uid"]), "username": n["uid"], "me": False},
                 unread=any(t["unread"] for t in self._threads(nid, False)))
        if root:
            d["parent_id"] = None
        return d

    def _share_state(self, nid):
        rows = self.shares.get(nid, [])
        inh = []
        p = self.notes[nid].get("parent_id")
        while p is not None and p in self.notes:
            for s in self.shares.get(p, []):
                inh.append(dict(s, note_id=p, title=self.notes[p].get("title") or "Unbenannt",
                                name=self.names.get(s["target"], s["target"]) if s["type"] == "user" else s["target"]))
            p = self.notes[p].get("parent_id")
        out = []
        for s in rows:
            d = dict(s, name=self.names.get(s["target"], s["target"]) if s["type"] == "user" else s["target"], created=0)
            if s["type"] == "user":
                via = [{"type": "group", "name": g["target"], "role": g["role"], "note_id": nid, "title": None}
                       for g in rows if g["type"] == "group" and s["target"] in self.groups.get(g["target"], [])]
                via += [{"type": x["type"], "name": x["name"], "role": x["role"], "note_id": x["note_id"], "title": x["title"]}
                        for x in inh if x["type"] == "user" and x["target"] == s["target"]]
                via.sort(key=lambda v: -self.RANK[v["role"]])
                d.update(via=via, effective=max([s["role"]] + [v["role"] for v in via], key=lambda r: self.RANK[r]))
            else:
                d.update(members=len(self.groups.get(s["target"], [])), effective=s["role"])
            out.append(d)
        return {"enabled": self.share_enabled, "scope": False, "max": 200, "shareable": not self.notes[nid].get("archived"),
                "shares": out, "inherited": inh, "groups": sorted(g for g, m in self.groups.items() if self.me in m)}

    def _threads(self, nid, resolved):
        roots = [c for c in self.comments if c["note_id"] == nid and c["thread_id"] is None and (resolved or not c["resolved_at"])]
        blocks = set()
        n = self.notes.get(nid) or self.foreign.get(nid) or {}
        try:
            blocks = {b.get("id") for b in json.loads(n.get("body") or "{}").get("blocks", [])}
        except ValueError:
            pass
        out = []
        for r in roots:
            cms = [r] + [c for c in self.comments if c["thread_id"] == r["id"]]
            seen = self.seen.get((self.me, r["id"]), 0)
            last_other = max([c["created"] for c in cms if c["uid"] != self.me] or [0])
            out.append({"id": r["id"], "block_id": r["block_id"], "block_exists": (not r["block_id"]) or r["block_id"] in blocks,
                        "resolved_at": r["resolved_at"], "resolved_by": self.person(r["resolved_by"]) if r["resolved_by"] else None,
                        "unread": last_other > seen,
                        "comments": [{"id": c["id"], "thread_id": c["thread_id"], "block_id": c["block_id"], "author": self.person(c["uid"]),
                                      "body": c["body"], "created": c["created"], "edited": c["edited"], "mine": c["uid"] == self.me,
                                      "deleted": not c["body"]} for c in cms]})
        return out

    def add_comment(self, nid, body, uid="anna", block_id="", thread_id=None, created=None, resolved=False):
        """Kommentar von jemand anderem anlegen (Testdaten) -> id"""
        cid = self.next_id
        self.next_id += 1
        self.comments.append({"id": cid, "note_id": nid, "thread_id": thread_id, "block_id": block_id, "uid": uid, "body": body,
                              "created": created or time.time(), "edited": None, "resolved_at": time.time() if resolved else None,
                              "resolved_by": uid if resolved else None})
        return cid

    def _share_api(self, method, path, parts, q, b):
        """Endpunkte fuer Teilen/Kommentare und fremde Seiten - None: weiter im normalen Ablauf"""
        new = path in ("api/shared", "api/share/info", "api/share/targets") or (len(parts) >= 2 and parts[:2] == ["api", "comments"]) or \
            (len(parts) == 4 and parts[:2] == ["api", "notes"] and parts[3] in ("shares", "comments"))
        if path == "api/files" and method == "POST":
            self.next_id += 1
            raw = bytes(getattr(self, "_raw", None) or b"")
            i = min([x for x in (raw.find(b"\x89PNG"), raw.find(b"\xff\xd8\xff")) if x >= 0] or [-1])   # Bild wieder ausliefern
            if i >= 0:
                if not hasattr(self, "uploads"):
                    self.uploads = {}
                self.uploads[f"/files/up{self.next_id}"] = raw[i:raw.rfind(b"\r\n--")]
            return self._reply(200, {"file": {"url": f"/files/up{self.next_id}", "name": "datei"}})
        if not self.share_api:
            return self._reply(404, {"error": "not found"}) if new else None
        if path == "api/shared":
            vis = [(i, self.role_for(i)) for i in self.foreign]
            return self._reply(200, [self._foreign_meta(i, r) for i, r in vis if r])
        if path == "api/share/info":
            return self._reply(200, {"enabled": self.share_enabled, "received": sum(1 for _ in self.grants)})
        if path == "api/share/targets":
            qq = (q.get("q") or "").strip().lower()
            if len(qq) < 3:
                return self._reply(200, {"users": [], "groups": []})
            users = [{"username": u, "name": n} for u, n in self.names.items() if u != self.me and (u.startswith(qq) or qq in n.lower())]
            groups = [{"name": g} for g, m in self.groups.items() if self.me in m and qq in g]
            return self._reply(200, {"users": users, "groups": groups})
        if len(parts) >= 3 and parts[:2] == ["api", "notes"] and parts[2].isdigit():
            nid = int(parts[2])
            rest = parts[3:]
            role = self.role_for(nid)
            if nid in self.foreign:
                n = self.foreign[nid]
                if not role:
                    return self._reply(404, {"error": "not found"})
                if not rest and method == "GET":
                    out = dict(n)
                    out.pop("uid", None)
                    out.update(self._share_fields(nid, n, role))
                    out["pinned"] = False
                    if out.get("parent_id") is not None and not self.role_for(out["parent_id"]):
                        out["parent_id"] = None
                    return self._reply(200, out)
                if not rest and method == "PATCH":
                    if self.RANK[role] < self.RANK["edit"]:
                        return self._reply(403, {"error": "Dafür fehlt dir die Berechtigung."})
                    if any(k in b for k in ("pinned", "template", "locked", "parent_id")):
                        return self._reply(403, {"error": "Das darf nur der Eigentümer der Seite."})
                    content = "body" in b or "title" in b
                    if content and b.get("rev") is not None and b["rev"] != n["rev"]:
                        by = n.get("updated_by")
                        return self._reply(409, {"error": "conflict", "rev": n["rev"], "by": self.names.get(by) if by != self.me else None})
                    for k in ("title", "body", "icon", "cover", "tags", "wide"):
                        if k in b:
                            n[k] = b[k]
                    if content:
                        self.last_overwrite = bool(b.get("overwrite"))
                        n["rev"] += 1
                        n["updated_by"] = self.me
                    n["updated"] = time.time()
                    return self._reply(200, {"ok": True, "updated": n["updated"], "rev": n["rev"]})
                if rest == ["backlinks"]:
                    return self._reply(200, [])
                if rest == ["rows"]:
                    rows = [{"id": r["id"], "title": r["title"], "props": r.get("props") or {}, "updated": r["updated"], "created": r["created"]}
                            for r in self.foreign.values() if r.get("parent_id") == nid]
                    return self._reply(200, {"schema": n.get("schema") or {"props": []}, "rows": rows, "templates": [], "role": role})
                if rest == ["shares"]:
                    return self._reply(404, {"error": "not found"})
            elif nid in self.notes and rest == ["rows"] and method == "GET":
                return None
            if rest == ["shares"] and nid in self.notes:
                if method == "GET":
                    return self._reply(200, self._share_state(nid))
                if method == "PUT":
                    if not self.share_enabled:
                        return self._reply(403, {"error": "Teilen ist auf diesem Server abgeschaltet."})
                    typ, tg, r = b.get("type"), str(b.get("target") or "").lower(), b.get("role")
                    if typ == "user" and tg == self.me:
                        return self._reply(400, {"error": "Mit dir selbst musst du nicht teilen."})
                    if typ == "user" and tg not in self.names:
                        return self._reply(400, {"error": "Dieses Konto gibt es nicht."})
                    lst = self.shares.setdefault(nid, [])
                    ex = next((s for s in lst if s["type"] == typ and s["target"] == tg), None)
                    if ex:
                        ex["role"] = r
                    else:
                        lst.append({"type": typ, "target": tg, "role": r})
                    return self._reply(200, self._share_state(nid))
                if method == "DELETE":
                    lst = self.shares.get(nid, [])
                    n0 = len(lst)
                    self.shares[nid] = [s for s in lst if not (s["type"] == q.get("type") and s["target"] == q.get("target"))]
                    return self._reply(200, dict(self._share_state(nid), removed=len(self.shares[nid]) < n0))
            if rest == ["comments"]:
                if not role:
                    return self._reply(404, {"error": "not found"})
                if method == "GET":
                    th = self._threads(nid, q.get("resolved") == "1")
                    nres = sum(1 for c in self.comments if c["note_id"] == nid and c["thread_id"] is None and c["resolved_at"])
                    return self._reply(200, {"role": role, "can_comment": self.RANK[role] >= 2, "is_owner": role == "owner",
                                             "threads": th, "resolved_count": nres, "max": 2000})
                if method == "POST":
                    if self.RANK[role] < self.RANK["comment"]:
                        return self._reply(403, {"error": "Dafür fehlt dir die Berechtigung."})
                    body = str(b.get("body") or "").strip()
                    if not body:
                        return self._reply(400, {"error": "Der Kommentar ist leer."})
                    if len(body) > 2000:
                        return self._reply(400, {"error": "Höchstens 2000 Zeichen."})
                    if self.comment_429:
                        return self._reply(429, {"error": "Zu viele Kommentare in kurzer Zeit – bitte kurz warten."})
                    tid = b.get("thread_id")
                    block = str(b.get("block_id") or "")
                    if tid is not None:
                        root = next((c for c in self.comments if c["id"] == tid), None)
                        if root is None:
                            return self._reply(404, {"error": "not found"})
                        tid = root["thread_id"] or root["id"]
                        block = next(c for c in self.comments if c["id"] == tid)["block_id"]
                        next(c for c in self.comments if c["id"] == tid).update(resolved_at=None, resolved_by=None)
                    cid = self.add_comment(nid, body, uid=self.me, block_id=block, thread_id=tid)
                    self.seen[(self.me, tid or cid)] = time.time() + 0.001
                    return self._reply(200, {"comment": {"id": cid}, "thread_id": tid or cid})
        if len(parts) >= 3 and parts[:2] == ["api", "comments"] and parts[2].isdigit():
            cid = int(parts[2])
            c = next((x for x in self.comments if x["id"] == cid), None)
            role = self.role_for(c["note_id"]) if c else None
            if c is None or not role:
                return self._reply(404, {"error": "not found"})
            rest = parts[3:]
            if not rest and method == "PATCH":
                if c["uid"] != self.me or self.RANK[role] < 2:
                    return self._reply(403, {"error": "Nur eigene Kommentare lassen sich bearbeiten."})
                c.update(body=str(b.get("body") or "").strip(), edited=time.time())
                return self._reply(200, {"ok": True})
            if not rest and method == "DELETE":
                if not (role == "owner" or (c["uid"] == self.me and self.RANK[role] >= 2)):
                    return self._reply(403, {"error": "Nur eigene Kommentare lassen sich löschen."})
                if c["thread_id"] is None:
                    others = [x for x in self.comments if x["thread_id"] == cid and x["uid"] != c["uid"]]
                    if others and q.get("all") != "1":
                        c["body"] = ""
                        return self._reply(200, {"ok": True, "deleted": 1, "soft": True})
                    self.comments = [x for x in self.comments if x["id"] != cid and x["thread_id"] != cid]
                else:
                    self.comments = [x for x in self.comments if x["id"] != cid]
                return self._reply(200, {"ok": True, "deleted": 1, "soft": False})
            if rest == ["resolve"]:
                if self.RANK[role] < 2:
                    return self._reply(403, {"error": "Dafür fehlt dir die Berechtigung."})
                root = next(x for x in self.comments if x["id"] == (c["thread_id"] or cid))
                on = b.get("resolved", True) is not False
                root.update(resolved_at=time.time() if on else None, resolved_by=self.me if on else None)
                return self._reply(200, {"ok": True, "thread_id": root["id"], "resolved_at": root["resolved_at"]})
            if rest == ["seen"]:
                self.seen[(self.me, c["thread_id"] or cid)] = time.time() + 0.001
                return self._reply(200, {"ok": True})
        return None


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
        if kw.get("headers"):
            srv.headers_log.append((method, url, dict(kw["headers"])))
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
    client._open_stream = lambda method, url, data, headers, timeout: mockv2.open_stream(srv, method, url, data, headers)
    if logged_in:
        config.settings.server = server
        if hasattr(client, "set_cookies"):
            client.set_cookies(server, {"hk_session": srv.sessions[server]})
        else:
            client.cookies = {"hk_session": srv.sessions[server]}
    return srv
