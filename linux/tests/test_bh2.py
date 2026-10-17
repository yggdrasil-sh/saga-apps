"""Regressionstests Bughunt Runde 2 (Linux): Start ohne Netz, Vorschaubilder je Nachricht (file_ids_per_msg),
Klingel-Leiste (Name lesbar), Profilbilder der Gegenueber, HEIC nur mit Dekoder, Bildblock-Hoehe, Mindestbreite,
Gravatar ohne E-Mail, Immich ohne Namen, Push-Mitteilung ohne Netz."""
import io
import time

from gi.repository import GLib, Gtk

import mockv21
from hk_helpers import idle, pump

from test_v21b import _calls_setup, _ready, _walk
from test_v21c import _conv, _img


# --- [S2] Start ohne Netz -------------------------------------------------------------------------------------------
def test_offline_start_catches_up(app, srv):
    from hlidskjalf.assistant import ai
    from hlidskjalf.calls import calls
    from hlidskjalf.notifications import notifications
    from hlidskjalf.window import MainWindow
    calls.reset()
    srv.ai_status = {"enabled": True, "notice": "x"}
    off = ["GET api/ai/status", "GET api/calls/config", "GET api/account", "GET api/notifications"]
    for k in off:
        srv.fail[k] = 0                                  # Server (noch) nicht erreichbar
    w = MainWindow(app)
    w.present()
    try:
        pump(lambda: all(any(m == "GET" and k.split()[1] in u for m, u, b, c in srv.log) for k in off), 4)
        pump(None, 0.3)
        assert not ai.known and calls.config is None and not getattr(w, "me", None) and not notifications.loaded
        for k in off:
            srv.fail.pop(k)
        # gleich nach dem Start: erst nach den Anfragen vom Sitzungsbeginn nachholen (keine Doppel)
        n0 = len(srv.log)
        w._live_event("hello", {})
        pump(None, 0.2)
        assert not any("api/ai/status" in u for m, u, b, c in srv.log[n0:]) and w._catch_up_soon
        GLib.source_remove(w._catch_up_soon)
        w._catch_up_soon = 0
        w._session_t -= 60                               # spaeter: Live-Kanal steht -> alles Fehlende laden
        w._live_event("hello", {})
        assert pump(lambda: ai.known and ai.enabled and calls.config is not None and (w.me or {}).get("name")
                    and notifications.loaded, 5)
        assert w.acct_btn.get_visible()
        # schon alles da: nichts doppelt
        n1 = len(srv.log)
        w._session_catch_up()
        pump(None, 0.3)
        assert not [u for m, u, b, c in srv.log[n1:] if "api/ai/status" in u or "api/calls/config" in u or u.endswith("api/account")]
    finally:
        w._close_ok = True
        w.destroy()
        pump(None, 0.1)


# --- [S3] Vorschaubilder: file_ids_per_msg ----------------------------------------------------------------------------
def test_thumbs_use_file_ids_per_msg(win, srv):
    from hlidskjalf.chat import chat
    cid, conv = _conv(win, srv)
    big = _img(900, 600, fmt="JPEG")
    files = [(f"b{i}.jpg", "image/jpeg", big) for i in range(10)]
    chat.limits = dict(chat.limits or {}, files_per_msg=10, file_ids_per_msg=20)
    out, ids = chat.upload_attachments(cid, files)
    assert len(ids) == 20 and all("th" in e for e in out)
    # aelterer Server ohne file_ids_per_msg: Rueckfall auf files_per_msg
    chat.limits.pop("file_ids_per_msg")
    out, ids = chat.upload_attachments(cid, files[:6])
    assert len(ids) == 10 and sum("th" in e for e in out) == 4
