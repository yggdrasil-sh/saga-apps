"""(2.6, Bugs-DB 2029-8) Eigener Status - Stand und Server-Zugriff (ohne Oberflaeche; die steht in statusview.py).

Vertrag: docs/APP-API.md im Server "Eigener Status: Verfuegbar / Beschaeftigt / Nicht stoeren" (userstatus1). Web-Vorbild
static/userstatus.js: GET/PUT /api/account/status mit ver (409 = anderswo geaendert -> Ansicht still uebernehmen),
PUT /api/account/status/prefs (Erreichbarkeit, Ausnahmen), Live status.changed (eigene Geraete) und status.digest
(Sammelmeldung, nur Zahlen). Ohne userstatus1 (aeltere Server) bleibt alles unsichtbar. Die Linux-App sendet nur
source "manual" - eine Kopplung an das Nicht-stoeren des Desktops (source "focus") kommt spaeter."""
import time

from gi.repository import GLib, GObject

from . import chatstatus as S
from . import config
from .api import APIError, client
from .util import bg


class UserStatus(GObject.Object):
    """Signal "changed": Ansicht (oder Ende der Ruhezeit) geaendert. Signal "digest" (str): Satz der Sammelmeldung."""
    __gsignals__ = {
        "changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "digest": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
    }

    def __init__(self):
        super().__init__()
        self._plan = 0
        self._call_t = 0
        self.reset()

    def reset(self):
        self.view = None           # Ansicht des Servers (S.view_ok) oder None
        self.quiet_to = None       # Ende der Ruhezeit "07:00" (GET /api/push -> settings.quiet), fuer den Hinweis
        self._quiet_at = 0.0
        self._skew = 0.0           # Server-Uhr minus eigene Uhr
        self._srv = None
        self._loading = False
        for a in ("_plan", "_call_t"):
            if getattr(self, a, 0):
                GLib.source_remove(getattr(self, a))
                setattr(self, a, 0)

    # --- Faehigkeit ----------------------------------------------------------------------------------------------
    @staticmethod
    def available():
        """Server kann den eigenen Status (/api/version bzw. /api/chat/me -> features)"""
        from .chat import chat
        from .store import store
        return store.has("userstatus1") or S.has_userstatus(chat.me)

    @property
    def ok(self):
        return S.view_ok(self.view) and self._srv == config.settings.server

    def now(self):
        return time.time() + self._skew

    def shown(self):
        return S.shown(self.view, self.quiet_to, self.now()) if self.ok else None

    def dnd(self):
        return bool(self.ok and self.view.get("effective") == "dnd")

    # --- Laden und Setzen ----------------------------------------------------------------------------------------
    def take(self, v):
        """Ansicht uebernehmen (nur neuere) -> True, wenn uebernommen"""
        if not S.take_view(self.view if self.ok else None, v):
            return False
        self.view = v
        self._srv = config.settings.server
        if isinstance(v.get("now"), (int, float)):
            self._skew = float(v["now"]) - time.time()
        if v.get("quiet") and time.monotonic() - self._quiet_at > 300:
            self._quiet_load()
        self._schedule()
        self.emit("changed")
        return True

    def load(self, done=None):
        """GET /api/account/status (nur mit userstatus1)"""
        if not self.available():
            if self.view is not None:
                self.reset()
                self.emit("changed")
            return
        srv = config.settings.server

        def fin(j):
            self._loading = False
            if srv == config.settings.server:
                self.take(j)
            if done:
                done(self.view)

        def err(_e):
            self._loading = False
            self._schedule()
        self._loading = True
        bg(client.get, "api/account/status", done=fin, error=err)

    def _put(self, path, body, done=None, error=None):
        """PUT mit ver; 409 = anderswo geaendert: Ansicht uebernehmen, done(view, conflict=True)"""
        srv = config.settings.server

        def fin(j):
            if srv == config.settings.server:
                self.take(j)
            if done:
                done(self.view, False)

        def err(e):
            data = getattr(e, "data", None)
            if isinstance(e, APIError) and e.code == 409 and isinstance(data, dict) and S.view_ok(data):
                if srv == config.settings.server:
                    self.take(data)
                if done:
                    done(self.view, True)
                return
            if error:
                error(e)
        bg(client.json, "PUT", path, body, done=fin, error=err)

    def set(self, body, done=None, error=None):
        b = dict(body)
        b.setdefault("source", "manual")
        if self.ok and "ver" not in b:
            b["ver"] = self.view["ver"]
        self._put("api/account/status", b, done, error)

    def prefs(self, body, done=None, error=None):
        self._put("api/account/status/prefs", body, done, error)

    def _quiet_load(self):
        """Ende der Ruhezeit fuer "Ruhezeit bis 7:00" - die Ansicht nennt nur quiet: true"""
        self._quiet_at = time.monotonic()

        def fin(j):
            q = ((j or {}).get("settings") or {}).get("quiet") if isinstance(j, dict) else None
            to = q.get("to") if isinstance(q, dict) and q.get("on") else None
            to = to if isinstance(to, str) and len(to) == 5 and to[2] == ":" and (to[:2] + to[3:]).isdigit() else None
            if to != self.quiet_to:
                self.quiet_to = to
                self.emit("changed")
        bg(client.get, "api/push", done=fin, error=lambda e: None)

    def _schedule(self):
        """Ablauf, Ruhezeit und Anruf aendern effective ohne Ereignis an uns: nachsehen (spaetestens alle 5 Minuten)"""
        if self._plan:
            GLib.source_remove(self._plan)
            self._plan = 0
        if not self.available():
            return
        ms = 300000
        u = (self.view or {}).get("until") if self.ok else None
        if isinstance(u, (int, float)) and u:
            ms = int(max(2000, min(ms, (u - self.now()) * 1000 + 2500)))

        def go():
            self._plan = 0
            self.load()
            return False
        self._plan = GLib.timeout_add(ms, go)

    # --- Live ------------------------------------------------------------------------------------------------------
    def on_live(self, typ, d):
        if typ == "status.changed":
            self.take(d if isinstance(d, dict) else None)
        elif typ == "status.digest":
            t = S.digest_text(d)
            if t:
                self.emit("digest", t)
        elif typ in ("hello", "reset"):
            self.load()
        elif typ == "call.state" and self.ok:              # "Im Anruf" kommt ohne Ereignis an uns: kurz danach nachsehen
            if self._call_t:
                GLib.source_remove(self._call_t)

            def go():
                self._call_t = 0
                self.load()
                return False
            self._call_t = GLib.timeout_add(700, go)


ustatus = UserStatus()
