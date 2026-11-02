"""Live mitschreiben (2.5, P3 Stufe 2) in der Seitenansicht: verbindet collab.Session mit PageView/PageDoc.

- Eine Verbindung (collab.Connection) fuer die App, je offener Seite (Hauptansicht, nicht im Peek) ein Abo.
- Live aktiv: die Ansicht speichert NICHT per PATCH (der Server materialisiert selbst); jede Eingabe geht sofort als
  Y-Update hinaus (Text: genau an der Schreibmarke, Struktur: kleinste Aenderung je Block).
- Fremde Aenderungen: Bloecke an Ort und Stelle ersetzt, Ansicht zeichnet nur geaenderte Bloecke neu; im eigenen
  Textfeld wird der Text ohne Neuaufbau ersetzt (Schreibmarke ueber den Text verschoben, ohne Echo).
- Rueckfall (Stufe 1): Live aus, Fehler, mehrfach abgelehnt, lange offline -> wie bisher PATCH merge: 1 (eigene, noch
  nicht bestaetigte Aenderungen werden so gespeichert).
- Nur lesen: Rolle Lesen/Kommentieren, gesperrt (vom Server), abweichendes Schema (R1, Hinweis "neu laden")."""
from gi.repository import GLib

from . import collab, config
from .api import client
from .i18n import _
from .store import store

_conn = None


def _ws_url():
    srv = (config.settings.server or "").rstrip("/")
    if srv.startswith("https://"):
        return "wss://" + srv[8:] + "/api/collab"
    if srv.startswith("http://"):
        return "ws://" + srv[7:] + "/api/collab"
    raise ValueError("kein Server")


def _ws_headers():
    """Anmeldung wie der Live-Kanal (SSE): Geraete-Schluessel hkd_ als Bearer (ohne Origin), sonst die Sitzung (Cookie)"""
    from .api import ACCEPT_LANGUAGE
    h = {"User-Agent": "Hlidskjalf-Linux/1.0", "X-Saga": "1", "Accept-Language": ACCEPT_LANGUAGE}
    tok = config.get_device_token(config.settings.server)
    if tok and tok.startswith("hkd_"):
        h["Authorization"] = "Bearer " + tok
        return h
    ck = client.cookie_header_for(_ws_url().replace("wss://", "https://", 1).replace("ws://", "http://", 1))
    if ck:
        h["Cookie"] = ck
    return h


def _on_auth():
    from .util import bg
    bg(client.resume, done=lambda r: None, error=lambda e: None)


def connection():
    global _conn
    srv = config.origin(config.settings.server or "")
    if _conn is not None and getattr(_conn, "server", None) != srv:
        _conn.close()
        _conn = None
    if _conn is None:
        _conn = collab.Connection(_ws_url, lambda: HEADERS(), on_auth=_on_auth)
        _conn.server = srv
    return _conn


HEADERS = _ws_headers            # Tests ersetzen die Anmeldung


def reset():
    """Server/Konto gewechselt, abgemeldet bzw. /api/version neu"""
    global _conn
    if _conn is not None:
        _conn.close()
        for s in list(_conn.sessions.values()):
            s.fallback("reset", final=False)
        _conn = None


def eligible(view):
    """Seite live bearbeiten? Server meldet live1, pycrdt da, eigener Server, Block-Seite, keine Datenbank, keine Altformen"""
    doc = view.doc
    if view.in_peek or not doc.fetched or doc.is_database or not store.has("live1") or not collab.available():
        return False
    if view.remote or not isinstance(doc.id, int) or doc.id <= 0 or doc.in_trash or doc.lost is not None:
        return False
    if doc.page.get("format") not in (None, "blocks") or doc.conflict is not None:
        return False
    if _conn is not None and _conn.off:
        return False
    return not collab.legacy(doc.root)


class LivePage:
    """Host fuer collab.Session (siehe dort)"""

    def __init__(self, view):
        self.view = view
        self.doc = view.doc
        self.mode = None
        self.session = None
        self.applying = False            # fremder Stand wird gerade gezeigt: Ansicht meldet keine eigenen Aenderungen
        self.aw = {}
        self.notified = None
        self.conn = None                 # eigene Verbindung (Tests: zweites Konto); sonst die der App
        self.resolver = collab.Resolver(self._title_of, self._name_of, _("Page without access"))

    # --- Steuerung ---
    def start(self):
        if self.session is not None and self.session.state != "idle":
            return
        self.resolver.learn(self.doc.root)
        self.session = collab.Session(self.conn or connection(), self.doc.id, self, self.resolver)
        self.session.start()

    def stop(self):
        s, self.session = self.session, None
        if s is not None:
            s.stop()
        self._set_mode(None)

    @property
    def active(self):
        """Live gebunden (live, offline oder nur lesen) - dann speichert die Seite nicht per PATCH"""
        return self.session is not None and self.session.state in ("live", "offline", "lock") and self.mode is not None

    @property
    def can_write(self):
        return self.session is not None and self.session.editable

    def local(self, hint=None, caret=None):
        if self.applying or self.session is None:
            return
        self.session.local(hint, caret)

    def undo(self):
        return self.session is not None and self.session.undo()

    def redo(self):
        return self.session is not None and self.session.redo()

    def focus(self, bid):
        if self.session is not None:
            self.session.aw_out(bid)

    # --- Host-Schnittstelle (collab.Session) ---
    def live_blocks(self):
        return self.doc.root

    def live_title(self):
        return self.doc.title_raw

    def live_busy(self):
        d = self.doc
        return d.saving or d.dirty or self.view.editor._save_src != 0

    def live_focus(self):
        return self.view.editor.focused_block

    def live_hold(self):
        return any(getattr(rt, "preedit_on", False) for _k, rt in self.view.kit.order)

    def live_bind(self, blocks, title):
        self._adopt(blocks, title, first=True)

    def live_apply(self, blocks, title):
        self._adopt(blocks, title)

    def _adopt(self, blocks, title, first=False):
        d = self.doc
        old = list(d.root)
        same = [a is b for a, b in zip(blocks, old)]
        if not first and len(blocks) == len(old) and all(same) and title == d.title_raw:
            return
        self.applying = True
        try:
            d.root[:] = blocks                                  # dieselbe Liste (Ansicht/Editor halten sie)
            d.doc["blocks"] = d.root
            tchanged = title != d.title_raw
            if tchanged:
                d.page["title"] = title
                store.update_title(d.id, title)
            self.view.live_adopted(tchanged)
        finally:
            self.applying = False

    def live_mode(self, mode):
        self._set_mode(mode)

    def _set_mode(self, mode):
        if mode == self.mode:
            return
        was = self.mode
        self.mode = mode
        v = self.view
        if mode in ("live", "offline"):
            ed = v.editor
            if ed._save_src:                                    # Stufe-1-Speichern nicht mehr noetig
                GLib.source_remove(ed._save_src)
                ed._save_src = 0
            d = self.doc
            d.dirty = False
            from . import drafts
            drafts.drop(d.id, d.server, d._acct())
        relock = (mode in ("ro", "lock")) != (was in ("ro", "lock"))
        v.live_state(mode, relock)

    def live_fallback(self, pending):
        """zurueck auf Stufe 1; eigene, nicht bestaetigte Aenderungen per PATCH (merge: 1, base: sent) speichern"""
        was = self.mode
        self.mode = None
        if self.session is not None and self.session.state == "idle":
            self.session = None                                 # nicht endgueltig: beim naechsten Aufbau neu versuchen
        d = self.doc
        if pending and d.fetched and not d.read_only:
            d.base_sent = d.base_blocks is not None
            d.mark_dirty()
            GLib.idle_add(lambda: (d.save(), False)[1])
        self.view.live_state(None, was in ("ro", "lock"))
        if was is not None and not pending and d.fetched:
            d.live_refetch = True                               # Stand vom Server (Revision, Basis) holen, dann speichern
            d.load(force=True)

    def live_notice(self, code):
        txt = {"readonly": _("Change not applied – you can’t edit this page right now."),
               "rejected": _("Change not applied."),
               "rejected_lost": _("Change not applied – your latest input could not be kept.")}.get(code)
        if txt and self.view.win is not None:
            self.view.win.toast(txt)

    def live_gone(self, reason):
        self.doc.load(force=True)

    def live_aw(self, states):
        self.aw = states
        self.view.live_cursors(states)

    # --- Texte fuer Verweise/Erwaehnungen ---
    @staticmethod
    def _title_of(pid):
        from .blocks import _link_label
        p = store.by_id.get(pid)
        if p is None:
            return None
        return _link_label(p, False)

    @staticmethod
    def _name_of(ref):
        from .richtext import MENTION_NAMES
        return MENTION_NAMES.get(ref)
