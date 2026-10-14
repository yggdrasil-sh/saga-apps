"""Hilfen fuer die pytest-Tests (Hauptschleife pumpen, auf Schreibzugriffe warten)"""
import json
import time

from gi.repository import GLib


def pump(cond=None, timeout=6.0):
    """Hauptschleife laufen lassen, bis cond() wahr ist (oder die Zeit um ist). -> cond()"""
    ctx = GLib.MainContext.default()
    end = time.monotonic() + timeout
    while True:
        while ctx.pending():
            ctx.iteration(False)
        if cond is not None and cond():
            return True
        if time.monotonic() > end:
            return bool(cond()) if cond is not None else True
        time.sleep(0.01)


def idle():
    """warten, bis keine Schreibzugriffe mehr laufen"""
    from hlidskjalf.util import writes_pending
    from hlidskjalf.store import store
    pump(lambda: writes_pending() == 0 and not store._refreshing and not any(d.saving for d in list(store.docs)), 10)


def note_texts(srv, nid):
    try:
        return [b["data"].get("text") for b in json.loads(srv.notes[nid]["body"])["blocks"]]
    except (KeyError, ValueError):
        return None
