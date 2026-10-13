"""Zentraler Zustand wie Store.swift: Seiten, Aufgaben, Termine, Zuletzt, Startseite. Signale fuer die Oberflaeche."""
import datetime as _dt
import json
import os

from gi.repository import GLib, GObject

from . import config
from .api import APIError, client
from .i18n import _
from .util import bg, iso_day

PAGE_FIELDS = ("id", "title", "icon", "parent_id", "kind", "pinned", "updated", "snippet", "archived", "template", "tags")


def label(p):
    t = (p or {}).get("title") or ""
    return t if t else _("Untitled")


def is_db(p):
    return (p or {}).get("kind") == "database"


class Store(GObject.Object):
    __gsignals__ = {
        "pages-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "today-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "data-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),     # Datenbank-Ansichten neu laden
        "version-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),  # Server-Version bekannt (Hinweis "App zu alt")
        "events-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "need-login": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "logged-in": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "error": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
        "undo": (GObject.SignalFlags.RUN_FIRST, None, (int, str)),      # Papierkorb: Rueckgaengig anbieten
    }

    def __init__(self):
        super().__init__()
        self.pages = []
        self.archived = []
        self.by_id = {}
        self.tasks = []
        self.done_tasks = []
        self.events = []
        self.loading = False
        self.needs_login = False
        self._refreshing = False
        self.recent = list(config.settings.get("recent", []))
        client.on_need_login = lambda: GLib.idle_add(self._need_login)
        c = self.cached("api/notes")
        if isinstance(c, list):
            self._set_pages(c)

    # --- Zwischenspeicher --------------------------------------------------------------------
    @staticmethod
    def _cache_path(path):
        return os.path.join(config.CACHE_DIR, path.replace("/", "_").replace("?", "_").replace("&", "_"))

    def cached(self, path):
        try:
            with open(self._cache_path(path), "rb") as f:
                return json.loads(f.read())
        except (OSError, ValueError):
            return None

    def get_cached(self, path):
        """GET + im Zwischenspeicher ablegen (Hintergrund-Thread)"""
        raw = client.request("GET", path)
        try:
            tmp = self._cache_path(path) + ".tmp"
            with open(tmp, "wb") as f:
                f.write(raw)
            os.replace(tmp, self._cache_path(path))
        except OSError:
            pass
        return json.loads(raw)

    def drop_cache(self, path):
        try:
            os.remove(self._cache_path(path))
        except OSError:
            pass

    def clear_cache(self):
        import shutil
        shutil.rmtree(config.CACHE_DIR, ignore_errors=True)
        os.makedirs(os.path.join(config.CACHE_DIR, "files"), exist_ok=True)

    # --- Anmeldung ----------------------------------------------------------------------------
    def _need_login(self):
        if not self.needs_login:
            self.needs_login = True
            self.emit("need-login")
        return False

    def logged_in(self):
        self.needs_login = False
        self.emit("logged-in")
        self.refresh()

    def logout(self, done=None):
        def work():
            client.forget()
        def fin(_):
            self.clear_cache()
            self.pages, self.archived, self.by_id, self.tasks, self.done_tasks, self.events = [], [], {}, [], [], []
            self.recent = []
            config.settings.set("recent", None)
            self.emit("pages-changed")
            self.emit("today-changed")
            self.needs_login = True
            self.emit("need-login")
            if done:
                done()
        bg(work, done=fin, error=fin)

    # --- Seiten -------------------------------------------------------------------------------
    def _set_pages(self, allp):
        self.by_id = {p["id"]: p for p in allp}
        self.pages = [p for p in allp if not p.get("archived")]
        arch = {p["id"] for p in allp if p.get("archived")}
        self.archived = [p for p in allp if p.get("archived") and not (p.get("parent_id") in arch)]

    def page(self, pid):
        p = self.by_id.get(pid)
        return p if p and not p.get("archived") else p

    def children(self, parent):
        out = []
        for p in self.pages:
            if p.get("parent_id") == parent:
                par = self.by_id.get(parent) if parent is not None else None
                if par is not None and is_db(par):
                    continue
                out.append(p)
        return out

    def has_children(self, pid):
        p = self.by_id.get(pid)
        if is_db(p):
            return False
        return any(x.get("parent_id") == pid for x in self.pages)

    @property
    def favorites(self):
        return [p for p in self.pages if p.get("pinned")]

    def path(self, pid):
        out, cur, n = [], (self.by_id.get(pid) or {}).get("parent_id"), 0
        while cur is not None and cur in self.by_id and n < 20:
            p = self.by_id[cur]
            out.insert(0, p)
            cur, n = p.get("parent_id"), n + 1
        return out

    def path_text(self, pid):
        return " / ".join(label(p) for p in self.path(pid))

    def descendants(self, pid):
        s, changed = {pid}, True
        while changed:
            changed = False
            for p in self.pages:
                if p.get("parent_id") in s and p["id"] not in s:
                    s.add(p["id"])
                    changed = True
        return s

    def databases(self):
        return [p for p in self.pages if is_db(p)]

    def templates(self):
        return [p for p in self.pages if p.get("template")]

    def all_tags(self):
        return sorted({t for p in self.pages for t in (p.get("tags") or [])})

    def refresh(self, done=None):
        if self._refreshing:
            return
        self._refreshing = True
        self.loading = True

        def work():
            pages = self.get_cached("api/notes")
            t = client.get("api/tasks")
            return pages, t

        def fin(r):
            self._refreshing = False
            self.loading = False
            pages, t = r
            self._set_pages(pages)
            self.tasks, self.done_tasks = t.get("open", []), t.get("done", []) or []
            self.needs_login = False
            self.emit("pages-changed")
            self.emit("today-changed")
            self.load_events()
            bg(client.ensure_device_token, error=lambda e: None)
            if self.server_version is None:
                self.check_version()
            if done:
                done()

        def err(e):
            self._refreshing = False
            self.loading = False
            if not (isinstance(e, APIError) and e.code == 401):
                self.emit("error", str(e))
            if done:
                done()

        bg(work, done=fin, error=err)

    server_version = None
    min_app = None

    def check_version(self):
        """Server-Version + aelteste App-Version, die er voll unterstuetzt (/api/version, ohne Anmeldung)"""
        def fin(j):
            if isinstance(j, dict):
                self.server_version, self.min_app = j.get("version"), j.get("min_app")
                self.emit("version-changed")
        bg(client.get, "api/version", done=fin, error=lambda e: None)

    @property
    def app_too_old(self):
        from . import VERSION, version_tuple
        return bool(self.min_app) and version_tuple(VERSION) < version_tuple(self.min_app)

    def run(self, method, path, body=None, done=None, error=None, refresh=False, data_changed=False):
        """Anfrage im Hintergrund; danach optional Seitenliste/Datenbanken auffrischen"""
        def fin(r):
            if data_changed:
                self.emit("data-changed")
            if refresh:
                self.refresh()
            if done:
                done(r)

        def err(e):
            if error:
                error(e)
            elif not (isinstance(e, APIError) and e.code == 401):
                self.emit("error", str(e))
        bg(client.json, method, path, body, done=fin, error=err)

    def patch(self, pid, fields, done=None, refresh=False):
        p = self.by_id.get(pid)
        if p:
            for k in ("icon", "pinned", "title", "tags", "template"):
                if k in fields:
                    p[k] = fields[k]
        self.drop_cache(f"api/notes/{pid}")
        self.run("PATCH", f"api/notes/{pid}", fields, done=done, refresh=refresh)
        if any(k in fields for k in ("icon", "pinned", "title")):
            self.emit("pages-changed")

    def update_title(self, pid, title):
        p = self.by_id.get(pid)
        if p and p.get("title") != title:
            p["title"] = title
            self.emit("pages-changed")

    def new_page(self, parent=None, done=None, kind=None, title=""):
        b = {"title": title}
        if parent is not None:
            b["parent_id"] = parent
        if kind:
            b["kind"] = kind
            b["icon"] = "🗃️"
        def fin(r):
            self.refresh(done=lambda: done and done(r["id"]))
        self.run("POST", "api/notes", b, done=fin)

    def trash(self, pid, done=None):
        t = label(self.by_id.get(pid))
        def fin(_):
            self.emit("undo", pid, t)
            self.refresh()
            self.emit("data-changed")
            if done:
                done()
        self.run("DELETE", f"api/notes/{pid}", done=fin)

    def restore(self, pid):
        self.run("POST", f"api/trash/{pid}/restore", {}, refresh=True, data_changed=True)

    def move(self, pid, parent, done=None, error=None):
        self.run("POST", f"api/notes/{pid}/move", {"parent_id": parent, "index": 0}, done=lambda r: (self.refresh(), done and done()), error=error)

    def duplicate(self, pid, done=None):
        self.run("POST", f"api/notes/{pid}/duplicate", {}, done=lambda r: self.refresh(done=lambda: done and done(r["id"])), data_changed=True)

    def set_archived(self, pid, on):
        self.run("POST", f"api/notes/{pid}/archive", {"archived": on}, refresh=True, data_changed=True)

    def search(self, q, done):
        if len(q.strip()) < 2:
            done([])
            return
        bg(client.json, "POST", "api/notes/search", {"q": q}, done=done, error=lambda e: done([]))

    def visited(self, pid):
        self.recent = [pid] + [x for x in self.recent if x != pid]
        self.recent = self.recent[:8]
        config.settings.set("recent", self.recent)

    @property
    def home_page(self):
        return config.settings.get("home_page")

    def set_home(self, pid):
        config.settings.set("home_page", pid)

    # --- Aufgaben -------------------------------------------------------------------------------
    def load_today(self):
        def fin(t):
            self.tasks, self.done_tasks = t.get("open", []), t.get("done", []) or []
            self.emit("today-changed")
        bg(client.get, "api/tasks", done=fin, error=lambda e: None)
        self.load_events()

    def add_task(self, text, due=None, prio=2, rep=None):
        b = {"text": text, "prio": prio, "today": iso_day()}
        if due:
            b["due"] = due
        if rep:
            b["repeat"] = rep
        self.run("POST", "api/tasks", b, done=lambda r: self.load_today())

    def update_task(self, tid, fields):
        self.run("PATCH", f"api/tasks/{tid}", fields, done=lambda r: self.load_today())

    def complete_task(self, t):
        self.tasks = [x for x in self.tasks if x["id"] != t["id"]]
        self.emit("today-changed")
        self.update_task(t["id"], {"done": True})

    def delete_task(self, tid):
        self.tasks = [x for x in self.tasks if x["id"] != tid]
        self.done_tasks = [x for x in self.done_tasks if x["id"] != tid]
        self.emit("today-changed")
        self.run("DELETE", f"api/tasks/{tid}", done=lambda r: self.load_today())

    # --- Termine --------------------------------------------------------------------------------
    @staticmethod
    def ev_path(days):
        t0 = _dt.date.today()
        return f"api/calendar/events?start={t0.isoformat()}&end={(t0 + _dt.timedelta(days=days)).isoformat()}"

    def cached_events(self, days):
        c = self.cached(self.ev_path(days))
        return (c or {}).get("events") if isinstance(c, dict) else None

    def fetch_events(self, days, done, fresh=False):
        base = self.ev_path(days)

        def work():
            j = client.get(base + ("&fresh=1" if fresh else ""))
            try:
                with open(self._cache_path(base), "w", encoding="utf-8") as f:
                    json.dump(j, f)
            except OSError:
                pass
            return j.get("events", [])
        bg(work, done=done, error=lambda e: None)

    def load_events(self):
        if not self.events:
            c = self.cached_events(1)
            if c:
                self.events = c
                self.emit("today-changed")

        def fin(ev):
            self.events = ev
            self.emit("today-changed")
        self.fetch_events(1, fin)

    def cal_targets(self, done):
        def work():
            j = client.get("api/settings/calendars")
            out = []
            for a in j.get("accounts", []):
                for c in a.get("calendars", []):
                    if c.get("show") is False or c.get("writable") is False or not c.get("href"):
                        continue
                    name = (a.get("label") or a.get("username") or "") + " · " + (c.get("name") or "")
                    out.append({"account": a.get("id"), "cal": c["href"], "name": name, "color": c.get("color")})
            return out
        bg(work, done=done, error=lambda e: done([]))

    def page_url(self, pid):
        return f"{config.settings.server}/notes#{pid}"


store = Store()


def watch(widget, signal, cb):
    """Store-Signal nur verbunden, solange das Widget sichtbar ist (GTK4 raeumt sonst nicht auf)"""
    state = {"h": None}

    def on_map(*_):
        if state["h"] is None:
            state["h"] = store.connect(signal, lambda *a: cb())

    def on_unmap(*_):
        if state["h"] is not None:
            store.disconnect(state["h"])
            state["h"] = None
    widget.connect("map", on_map)
    widget.connect("unmap", on_unmap)
