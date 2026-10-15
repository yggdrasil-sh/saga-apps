"""Live-Kanal (Server ab 1.4): GET /api/events als text/event-stream (kein WebSocket, kein eigener Port) und
Anwesenheit (POST /api/presence). Hoechstens EINE Verbindung, solange das Fenster da ist; fortsetzen mit Last-Event-ID,
"reset" laedt alles neu, "bye" je nach Grund. Ereignisse kommen im Haupt-Thread als Signal "event" (typ, daten).
Aeltere Server (404): still nichts - die App fragt dann wie bisher regelmaessig."""
import random
import threading
import time
import urllib.parse

from gi.repository import GLib, GObject

from . import config
from .api import APIError, client, sse_events
from .util import bg

PING_LIMIT = 75          # Sekunden ohne jede Zeile (Server pingt alle ~25 s): Verbindung neu aufbauen


class Live(GObject.Object):
    __gsignals__ = {
        "event": (GObject.SignalFlags.RUN_FIRST, None, (str, object)),
        "state": (GObject.SignalFlags.RUN_FIRST, None, (bool,)),      # verbunden ja/nein
    }

    def __init__(self):
        super().__init__()
        self.want = False
        self.connected = False
        self.supported = None          # None = unbekannt, False = Server ohne Live-Kanal (404)
        self.last_id = None
        self.boot = None
        self.gen = 0                   # Verbindungs-Generation: stop()/Serverwechsel beendet die alte
        self._resp = None
        self._thread = None
        self._thread_gen = 0
        self._wake = threading.Event()
        self.hold_until = 0.0          # "bye limit": eine andere Verbindung hat uebernommen - erst spaeter wieder

    # --- Steuerung (Haupt-Thread) -------------------------------------------------------------------
    def start(self):
        if self.supported is False:
            return
        self.want = True
        if self._thread is None or not self._thread.is_alive() or self._thread_gen != self.gen:
            self.gen += 1                                   # ein alter Thread (gestoppt) beendet sich selbst
            self._thread_gen = self.gen
            self._thread = threading.Thread(target=self._run, args=(self.gen,), daemon=True, name="hk-live")
            self._thread.start()
        else:
            self._wake.set()

    def stop(self):
        self.want = False
        self.gen += 1
        self._close()
        self._wake.set()
        self._set_connected(False)

    def reset(self):
        """anderer Server / abgemeldet: vergessen, wo wir waren"""
        self.stop()
        self.last_id, self.boot, self.supported, self.hold_until = None, None, None, 0.0

    def poke(self):
        """Fenster wieder aktiv: nach "bye limit" jetzt wieder verbinden"""
        self.hold_until = 0.0
        if self.want:
            self.start()

    def _close(self):
        r, self._resp = self._resp, None
        if r is None:
            return
        try:                                       # blockiertes readline() im Thread sofort beenden (urllib: Socket)
            import socket
            sock = r.fp.raw._sock                  # noqa: SLF001
            sock.shutdown(socket.SHUT_RDWR)
        except (OSError, AttributeError, ValueError):
            pass
        try:
            r.close()
        except (OSError, AttributeError, ValueError):
            pass

    def _set_connected(self, on):
        if self.connected != on:
            self.connected = on
            self.emit("state", on)

    # --- Hintergrund-Thread -----------------------------------------------------------------------------
    def _sleep(self, s, gen):
        self._wake.clear()
        end = time.monotonic() + s
        while time.monotonic() < end and self.want and gen == self.gen:
            self._wake.wait(min(1.0, end - time.monotonic()))
            if self._wake.is_set():
                self._wake.clear()
                if time.monotonic() >= self.hold_until:
                    return

    def _run(self, gen):
        backoff = 2.0
        while self.want and gen == self.gen:
            from .store import store
            if not config.settings.server or store.needs_login or time.monotonic() < self.hold_until:
                self._sleep(5, gen)
                continue
            path = "api/events"
            hd = {}
            if self.last_id:
                path += "?last=" + urllib.parse.quote(self.last_id)
                hd["Last-Event-ID"] = self.last_id
            try:
                r = client.stream("GET", path, headers=hd, timeout=PING_LIMIT)
            except APIError as e:
                if gen != self.gen:
                    return
                if e.code == 404:                                   # Server ohne Live-Kanal
                    GLib.idle_add(self._unsupported, gen)
                    return
                wait = 30 if e.code == 503 else 15 if e.code == 401 else backoff
                backoff = min(60.0, backoff * 2)
                self._sleep(wait + random.random(), gen)
                continue
            if gen != self.gen:
                try:
                    r.close()
                except (OSError, AttributeError):
                    pass
                return
            self._resp = r
            delay = 1.0
            try:
                for ev, data, eid in sse_events(r, stop=lambda: not self.want or gen != self.gen):
                    if eid:
                        self.last_id = eid
                    if ev == "hello":
                        backoff = 2.0
                        self.boot = (data or {}).get("boot")
                        GLib.idle_add(self._dispatch, gen, "hello", data or {})
                        continue
                    if ev == "bye":
                        delay = self._bye((data or {}).get("reason") or "closed")
                        break
                    if ev == "reset":
                        self.last_id = None
                    if data is None:
                        continue
                    GLib.idle_add(self._dispatch, gen, ev, data)
            except (OSError, ValueError, TimeoutError, AttributeError):
                delay = min(30.0, backoff)
                backoff = min(60.0, backoff * 2)
            finally:
                self._close()
                GLib.idle_add(self._down, gen)
            if delay < 0:                                          # Konto geloescht: nicht neu
                return
            self._sleep(delay + random.random(), gen)

    def _bye(self, reason):
        """-> Wartezeit bis zum neuen Aufbau (Sekunden; < 0 = gar nicht)"""
        if reason == "refresh":
            return 0.0
        if reason == "limit":                                      # andere Verbindung hat uebernommen: nicht gleich wieder
            self.hold_until = time.monotonic() + 300
            return 1.0
        if reason == "auth":
            client.resume()
            return 1.0
        if reason == "account":
            return -1
        return 5.0

    def _unsupported(self, gen):
        if gen == self.gen:
            self.supported = False
            self.want = False
            self._set_connected(False)
        return False

    def _down(self, gen):
        if gen == self.gen:
            self._set_connected(False)
        return False

    def _dispatch(self, gen, ev, data):
        if gen != self.gen:
            return False
        if ev == "hello":
            self.supported = True
            self._set_connected(True)
        self.emit("event", ev, data)
        return False


live = Live()


def watch_live(widget, cb):
    """Live-Ereignisse nur, solange das Widget sichtbar ist: cb(typ, daten)"""
    state = {"h": None}

    def on_map(*_):
        if state["h"] is None:
            state["h"] = live.connect("event", lambda o, t, d: cb(t, d))

    def on_unmap(*_):
        if state["h"] is not None:
            live.disconnect(state["h"])
            state["h"] = None
    widget.connect("map", on_map)
    widget.connect("unmap", on_unmap)
    if widget.get_mapped():
        on_map()


# --- Anwesenheit ------------------------------------------------------------------------------------------
class Presence:
    """Eine offene Seite: alle ~20 s POST /api/presence {note_id, editing}; editing in den ersten 30 s nach einer Eingabe
    (nur mit Bearbeiten). Beim Verlassen {leave:true}. on_users(liste), on_rev(rev, by) bei fremder Aenderung."""
    INTERVAL = 20
    EDIT_WINDOW = 30

    def __init__(self, nid, can_edit, active, on_users, on_rev, rev):
        self.nid, self.can_edit, self.active, self.on_users, self.on_rev, self.rev = nid, can_edit, active, on_users, on_rev, rev
        self.last_input = 0.0
        self.src = 0
        self.alive = True
        self.off = False               # Server ohne Anwesenheit (404 fuer alles) bzw. kein Zugriff
        self.users = []

    def start(self):
        if self.nid is None or self.nid < 0:                       # Seiten anderer Saga-Instanzen: keine Anwesenheit
            return
        self.beat()
        self.src = GLib.timeout_add_seconds(self.INTERVAL, self._tick)

    def typed(self):
        was = self.editing
        self.last_input = time.monotonic()
        if not was and self.can_edit():
            self.beat()

    @property
    def editing(self):
        return self.can_edit() and time.monotonic() - self.last_input < self.EDIT_WINDOW

    def _tick(self):
        if not self.alive:
            return False
        if self.active():
            self.beat()
        return True

    def beat(self):
        if not self.alive or self.off:
            return
        nid = self.nid

        def fin(r):
            if not self.alive or not isinstance(r, dict) or r.get("skipped"):
                return
            self.users = [u for u in r.get("users") or [] if isinstance(u, dict)]
            self.on_users(self.users)
            rev = r.get("rev")
            if isinstance(rev, int) and rev > (self.rev() or 0):
                self.on_rev(rev, r.get("by"))

        def err(e):
            if isinstance(e, APIError) and e.code in (403, 404):
                self.off = True
        bg(client.json, "POST", "api/presence", {"note_id": nid, "editing": self.editing}, done=fin, error=err)

    def stop(self):
        if not self.alive:
            return
        self.alive = False
        if self.src:
            GLib.source_remove(self.src)
            self.src = 0
        if not self.off and self.nid is not None and self.nid > 0 and self.users:
            bg(client.json, "POST", "api/presence", {"note_id": self.nid, "leave": True}, error=lambda e: None)

    def live_update(self, users):
        if self.alive:
            self.users = [u for u in users or [] if isinstance(u, dict)]
            self.on_users(self.users)
