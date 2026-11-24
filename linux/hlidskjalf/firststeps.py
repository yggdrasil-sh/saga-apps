"""Erste Schritte (2.6, Bugs-DB 2031): beim ersten Anmelden einmal fragen, ob man eine kurze Einfuehrung moechte.
Ja -> der Server legt eine Seite "Erste Schritte" mit einer Haekchenliste an und hakt Erledigtes selbst ab; Nein -> nichts.
Spaeter unter Einstellungen > Info > Erste Schritte wieder abrufbar. Ganz ohne KI.

  GET  api/first-steps  -> {enabled, ask, state: null|"yes"|"no", page: id|null, items: [{key, done}]}
  POST api/first-steps {"answer": "yes"|"no"} -> {ok, page?}   (enabled false: 403, aeltere Server: 404)

Jeder GET hakt auf dem Server neu Erledigtes ab - die App ruft ihn beim Start und beim Wechsel in den Vordergrund
(hoechstens alle MIN_GAP Sekunden). Aeltere Server (404) und unbrauchbare Antworten: still nichts zeigen."""
import time

from gi.repository import GObject

from .api import APIError, client
from .util import bg

PATH = "api/first-steps"
MIN_GAP = 60.0


def _pid(v):
    return v if isinstance(v, int) and not isinstance(v, bool) and v > 0 else None


def parse(d):
    """Antwort von GET api/first-steps -> {enabled, ask, state, page, items} oder None (unbrauchbar bzw. aelterer
    Server). enabled False: keine Frage, keine Seite, keine Punkte."""
    if not isinstance(d, dict) or not isinstance(d.get("enabled"), bool):
        return None
    on = d["enabled"]
    items = []
    for x in d.get("items") if isinstance(d.get("items"), list) else []:
        if isinstance(x, dict) and isinstance(x.get("key"), str) and x["key"]:
            items.append({"key": x["key"], "done": x.get("done") is True})
    return {"enabled": on, "ask": on and d.get("ask") is True, "state": d.get("state") if d.get("state") in ("yes", "no") else None,
            "page": _pid(d.get("page")) if on else None, "items": items if on else []}


def progress(st):
    """(erledigt, alle) der Punkte"""
    items = (st or {}).get("items") or []
    return sum(1 for x in items if x.get("done")), len(items)


class FirstSteps(GObject.Object):
    """Stand der "Ersten Schritte" fuer das angemeldete Konto (st: parse(...) oder None)"""
    __gsignals__ = {"changed": (GObject.SignalFlags.RUN_FIRST, None, ())}

    def __init__(self, clock=time.monotonic):
        super().__init__()
        self.clock = clock
        self.gen = 0
        self.reset()

    def reset(self):
        """anderer Server bzw. anderes Konto: alles vergessen"""
        self.gen += 1
        self.st = None
        self.supported = None          # None = unbekannt, False = aelterer Server (404) bzw. unbrauchbare Antwort
        self.last = None               # Zeitpunkt (clock) des letzten Abrufs
        self.busy = False
        self.asked = False             # Frage in dieser Sitzung schon gezeigt

    def due(self):
        return self.last is None or self.clock() - self.last >= MIN_GAP

    def check(self, force=False, done=None):
        """GET api/first-steps (gedrosselt: hoechstens alle MIN_GAP s, force = beim Start). done(st) im Hauptthread,
        st None bei Fehler/aelterem Server. -> True, wenn abgefragt wird"""
        if self.busy or not (force or self.due()):
            return False
        if self.supported is False and not force:      # aelterer Server (404): erst beim naechsten Start wieder fragen
            return False
        self.busy, self.last, gen = True, self.clock(), self.gen

        def fin(j):
            if gen != self.gen:
                return
            self.busy = False
            self.st = parse(j)
            self.supported = self.st is not None
            self.emit("changed")
            if done:
                done(self.st)

        def err(e):
            if gen != self.gen:
                return
            self.busy = False
            if isinstance(e, APIError) and e.code in (404, 405):
                self.st, self.supported = None, False
                self.emit("changed")
            if done:
                done(None)
        bg(client.get, PATH, done=fin, error=err)
        return True

    def answer(self, yes, done=None, error=None):
        """POST {"answer": "yes"|"no"} -> done(antwort) mit page (Ja), error(exc) (z. B. 403: abgeschaltet)"""
        gen = self.gen

        def fin(r):
            if gen != self.gen:                        # inzwischen anderes Konto/anderer Server: Antwort gehoert zum alten
                return
            r = r if isinstance(r, dict) else {}
            st = dict(self.st or {"enabled": True, "items": []})
            was = st.get("state")
            st.update(ask=False, state="yes" if was == "yes" or yes else "no")
            if yes:
                st["page"] = _pid(r.get("page"))
            self.st = st
            self.emit("changed")
            if done:
                done(r)

        def err(e):
            if gen == self.gen and error:
                error(e)
        bg(client.json, "POST", PATH, {"answer": "yes" if yes else "no"}, done=fin, error=err)


first_steps = FirstSteps()
