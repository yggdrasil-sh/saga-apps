"""2.5 Fehlerberichte und "Diagnose senden" (clientlog1): Bereinigung, Endpunkt-Muster, Drossel, JSON-Form gegen den
Vertrag (docs/APP-API.md "Fehlerberichte und Diagnose"), Einbindung in API-Client, Speichern und Einstellungen."""
import json
import os
import sys
import time

import pytest

from hk_helpers import pump

from hlidskjalf import clientlog as C


# --- Bereinigung -----------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("raw,bad", [
    ("Error: Seite 'Urlaub Sommer' fuer max@example.org nicht geladen", ["Urlaub", "max@", "example"]),
    ("KeyError: '3f2c9a0b-1111-2222-3333-444455556666'", ["3f2c9a0b"]),
    ("OSError: [Errno 2] No such file: '/home/nutzer/Dokumente/brief.pdf'", ["/home", "nutzer", "brief"]),
    ("ValueError: https://cloud.example.net/s/abc?token=1 failed", ["cloud", "token", "abc"]),
    ("RuntimeError: hk_ABCDEF1234567890 Bearer xyz.abc", ["ABCDEF", "xyz"]),
    ("Error: @anna und ~s545sgsgkkyhspqz", ["anna", "s545"]),
    ("TypeError: Geheimprojekt Phoenix kaputt", ["Geheimprojekt", "Phoenix", "kaputt"]),
    ("socket error at nas-01.fritz.box port 8443", ["nas-01", "fritz", "8443"]),
    ("Error: deadbeefcafe1234 und 4711 und Zw9KxQ2mP7aZ4tWbYc3d", ["deadbeef", "4711", "Zw9KxQ"]),
])
def test_scrub_removes_content(raw, bad):
    s = C.scrub(raw)
    for b in bad:
        assert b not in s, (raw, s)
    assert len(s) <= C.MSG_MAX


def test_scrub_keeps_program_words():
    s = C.scrub("AttributeError: 'NoneType' object has no attribute 'get_text'")
    assert s == "AttributeError: 'NoneType' object has no <w> 'get_text'"      # wie der Server (Wortliste)
    assert C.scrub("JSONDecodeError: http.GET api.notes.ID") == "JSONDecodeError: http.GET api.notes.ID"
    assert len(C.scrub("x" * 5000)) <= C.MSG_MAX
    assert C.scrub("a\u202eb\x00c") == "a b c"


_PROBE = r"""
import importlib.util, json, sys
sys.path.insert(0, sys.argv[1])
from saga import clientlog as srv
spec = importlib.util.spec_from_file_location("app_clientlog", sys.argv[2])
app = importlib.util.module_from_spec(spec)
spec.loader.exec_module(app)
out = []
for raw in json.loads(sys.argv[3]):
    mine = app.scrub(raw)
    out.append([raw, mine, srv.scrub(mine), srv.scrub(raw)])
st = "editor.py:612 editor.PageDoc.save.err\nGtk.py:1 Gtk.run"
out.append(["stack", st, srv.scrub_stack(st), ""])
print(json.dumps(out))
"""


def test_scrub_matches_server():
    """Gegenprobe gegen saga/clientlog.py (nur wenn Server-Stand und dessen Python-Umgebung da sind): unsere Ausgabe
    uebersteht die Server-Bereinigung unveraendert, und wir lassen nichts stehen, was der Server entfernt"""
    import re
    import subprocess
    root = os.environ.get("SAGA_SERVER_SRC", "/home/claude/wt/integ-clientlog")
    py = os.environ.get("SAGA_SERVER_PY", "/home/claude/srvtest/venv/bin/python")
    if not (os.path.isfile(os.path.join(root, "saga", "clientlog.py")) and os.path.isfile(py)):
        pytest.skip("Server-Quellen bzw. -Umgebung nicht vorhanden")
    raws = ["AttributeError: 'NoneType' object has no attribute 'get_text'", "Error: Seite fuer max@example.org 42",
            "ValueError: https://cloud.example.net/s/abc?token=1 failed", "OSError: [Errno 2] '/home/u/x.pdf'",
            "TypeError: Geheimprojekt Phoenix kaputt", "HTTPError.status5xx: http.PATCH api.notes.ID",
            "socket error at nas-01.fritz.box port 8443", "KeyError: 'title'"]
    env = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "LANG")}
    env.update({"SECRET_KEY": "x" * 32, "SESSION_SECRET": "x" * 32, "BASE_URL": "https://saga.test",
                "DATABASE_URL": "postgresql://saga@/nicht_benutzt?host=/nonexistent", "STATE_DIR": "/tmp/st-clog-linux"})
    try:
        r = subprocess.run([py, "-c", _PROBE, root, C.__file__, json.dumps(raws)], capture_output=True, text=True,
                           timeout=60, cwd=root, env=env)
    except (OSError, subprocess.TimeoutExpired):
        pytest.skip("Server-Umgebung nicht startbar")
    if r.returncode != 0:
        pytest.skip("Server-Modul nicht ladbar: " + r.stderr.strip().splitlines()[-1][:200] if r.stderr.strip() else "?")
    for raw, mine, again, srv_raw in json.loads(r.stdout):
        assert again == mine, (raw, mine, again)
        if raw != "stack":
            # was bei uns stehen bleibt, laesst auch der Server stehen (wir sind hoechstens strenger)
            words = lambda x: set(re.findall(r"[^\W\d_][\w$.]*", re.sub(r"<[a-z]+>", " ", x)))  # noqa: E731
            assert words(mine) <= words(srv_raw), (raw, mine, srv_raw)


def test_scrub_note():
    n = C.scrub_note("Beim Speichern hing es.\r\nMail: a@b.de, Schluessel hk_abcdef123456, https://x.test/p\x07")
    assert "<email>" in n and "<secret>" in n and "<url>" in n and "\x07" not in n
    assert "\n" in n and "Beim Speichern hing es." in n
    assert C.scrub_note("x" * 3000) == "<tok>"                  # lange Folge ohne Leerzeichen: wie der Server maskiert
    assert len(C.scrub_note("ab " * 1000)) == C.NOTE_MAX


def test_screen():
    assert C.screen("pages") == "pages"
    assert C.screen("Meine Seite") == "other"
    assert C.screen("a" * 30) == "other"


# --- Endpunkt-Muster -------------------------------------------------------------------------------------------------
def test_endpoint():
    assert C.endpoint("api/notes/123/comments?x=1") == "/api/notes/:id/comments"
    assert C.endpoint("https://srv.example/saga/api/notes/42#f") == "/api/notes/:id"
    assert C.endpoint("api/files/geheim-brief.pdf") == "/api/files/:x"
    assert C.endpoint("api/chats/0f3a9b/messages") == "/api/chats/:x/messages"
    assert C.endpoint("/static/app.js") is None
    assert C.endpoint("api/" + "/".join(["notes"] * 30)).count("/") <= 11
    assert C.dotted("/api/notes/:id/page-image") == "api.notes.ID.page_image"
    m = C.http_message(503, "PATCH", "/api/notes/:id")
    assert m == "HTTPError.status5xx: http.PATCH api.notes.ID"
    assert C.scrub(m) == m                                        # uebersteht die Bereinigung
    assert C.scrub(C.json_message("GET", "/api/notes")) == C.json_message("GET", "/api/notes")
    assert C.scrub(C.save_message(413)) == "SaveError.status4xx"


# --- Stack und Absturz-Notiz ------------------------------------------------------------------------------------------
def _boom():
    raise ValueError("Seite 'Tagebuch' kaputt bei /home/nutzer/x")


def test_stack_no_paths():
    try:
        _boom()
    except ValueError as e:
        frames = C.tb_frames(e.__traceback__)
        st = C.stack_text(frames)
        msg = C.exc_message(e)
    assert "test_clientlog.py:" in st and "test_clientlog._boom" in st
    assert "/" not in st and os.path.expanduser("~") not in st
    assert "Tagebuch" not in msg and "nutzer" not in msg and msg.startswith("ValueError:")
    assert C.frame_line("/usr/lib/python3/x/<frozen runpy>", 3, "<module>") .startswith("?:3")
    assert C.frame_line("/a/b/editor.py", 612, "PageDoc.save.<locals>.err") == "editor.py:612 editor.PageDoc.save.err"


def test_crash_item_from_exception_note():
    try:
        _boom()
    except ValueError as e:
        note = C.exc_note(e, e.__traceback__)
    it = C.crash_item(note, t=5)
    assert it.k == "crash" and it.msg.startswith("ValueError") and "Tagebuch" not in it.msg
    assert it.stack and "/" not in it.stack
    assert C.crash_item("") is None and C.crash_item("irgendwas") is None


def test_crash_item_from_faulthandler():
    text = ('Fatal Python error: Segmentation fault\n\nCurrent thread 0x00007f (most recent call first):\n'
            '  File "/home/nutzer/src/hlidskjalf/editor.py", line 88 in save\n'
            '  File "/usr/lib/python3.12/site-packages/gi/overrides/Gtk.py", line 1 in run\n')
    it = C.crash_item(text, t=0)
    assert it.k == "crash" and it.msg == "Signal.SIGSEGV"
    assert it.stack == "Gtk.py:1 Gtk.run\neditor.py:88 editor.save"
    assert "nutzer" not in json.dumps(it.json())
    assert C.crash_item("Fatal Python error: Something odd\n").msg == "FatalError.other"


# --- Reporter (Drossel, Dedupe, 404, 429, 401) ----------------------------------------------------------------------
def test_reporter_dedupe_and_rate():
    r = C.Reporter()
    for _ in range(5):
        assert r.add(C.Item("error", "TypeError: x", t=1))
    assert len(r.pending) == 1 and r.pending[0].n == 5
    items = r.take(100.0)
    assert items and items[0].json()["n"] == 5
    r.add(C.Item("error", "TypeError: y", t=2))
    assert r.take(110.0) is None and r.wait(110.0) == pytest.approx(20.0)
    assert r.take(130.0) is not None                              # nach 30 s wieder
    for i in range(30):
        r.add(C.Item("error", f"E{i}Error", t=i))
    assert len(r.pending) == C.ITEMS_MAX


def test_reporter_responses():
    r = C.Reporter()
    r.add(C.Item("error", "AError", t=1))
    items = r.take(0.0)
    r.response(429, 120, items, 0.0)
    assert r.pending == []                                        # verworfen, nicht wiederholt
    r.add(C.Item("error", "BError", t=1))
    assert r.take(60.0) is None and r.take(121.0) is not None
    r.add(C.Item("error", "CError", t=1))
    items = r.take(500.0)
    r.response(401, None, items, 500.0)                           # nicht angemeldet: spaeter senden
    assert [i.msg for i in r.pending] == ["CError"] and r.due(500.0)
    items = r.take(500.0)
    r.response(404, None, items, 500.0)                           # Schalter aus: Ruhe bis zum Neustart
    assert r.off and not r.add(C.Item("error", "DError", t=1))


def test_reporter_report_cap():
    r = C.Reporter()
    for i in range(C.REPORTS_MAX + 2):
        r.add(C.Item("error", "XError", t=1))
        r.take(i * 31.0)
    assert r.off


# --- JSON-Form -------------------------------------------------------------------------------------------------------
def _check_head(b):
    import re
    assert b["v"] == 1 and b["app"] == "linux" and b.get("dev") == "desktop"
    assert re.fullmatch(r"[A-Za-z0-9._+\-]{1,40}", b["ver"])
    assert re.fullmatch(r"[A-Za-z0-9_\-]{8,40}", b["sid"])
    assert "os" not in b or re.fullmatch(r"[A-Za-z0-9._ ()+;/\-]{0,60}", b["os"])


def test_log_body_contract():
    hd = C.head("2.5.0-rc.3", "Fedora Linux 44", C.new_sid())
    items = [C.Item("error", "TypeError: x", stack="a.py:1 a.f", src="/home/u/editor.py", line=12, screen="pages", t=9.7),
             C.Item("crash", "Signal.SIGSEGV", t=0)]
    b = json.loads(C.log_body(hd, items))
    _check_head(b)
    assert set(b) <= {"v", "app", "ver", "os", "dev", "sid", "items"}
    it = b["items"][0]
    assert it == {"k": "error", "msg": "TypeError: x", "stack": "a.py:1 a.f", "src": "editor.py", "line": 12,
                  "screen": "pages", "t": 9}
    assert b["items"][1]["k"] == "crash"
    big = [C.Item("error", f"E{i}Error " + "x" * 290, stack="s.py:1 s.f\n" * 400, t=i) for i in range(20)]
    data = C.log_body(hd, big)
    assert len(data) <= C.LOG_BYTES and 1 <= len(json.loads(data)["items"]) <= C.ITEMS_MAX


def test_head_values():
    h = C.head("2.5.0 beta!", "Ubuntu 24 ä", "short")
    assert h["ver"] == "2.5.0beta" and h["os"] == "Ubuntu 24"
    assert len(C.new_sid()) == 16 and C.new_sid() != C.new_sid()


def test_os_label(tmp_path):
    p = tmp_path / "os-release"
    p.write_text('NAME="Fedora Linux"\nVERSION_ID=44\nID=fedora\n')
    assert C.os_label((str(tmp_path / "fehlt"), str(p))) == "Fedora Linux 44"
    assert C.os_label((str(tmp_path / "fehlt"),)) == "Linux"


def test_ring_and_diag_body():
    ring = C.Ring()
    for i in range(250):
        ring.net("PATCH", "/api/notes/:id", 409, 12.4, i)
    ring.push("save", 300, st="conflict", s=409)
    ring.err(C.Item("error", "TypeError: x", screen="pages", t=301))
    assert len(ring) == C.RING_MAX
    b = json.loads(C.diag_body(C.head("2.5.0", "Linux"), ring.events, "Hing kurz.\nMail a@b.de"))
    _check_head(b)
    assert len(b["ev"]) == C.RING_MAX and b["ev"][0]["t"] == 52                 # aelteste zuerst
    assert b["ev"][-2] == {"t": 300, "k": "save", "st": "conflict", "s": 409}
    assert b["ev"][-1] == {"t": 301, "k": "err", "kind": "error", "msg": "TypeError: x", "screen": "pages"}
    assert b["ev"][0] == {"t": 52, "k": "net", "m": "PATCH", "p": "/api/notes/:id", "s": 409, "ms": 12}
    assert "<email>" in b["note"]
    ring2 = C.Ring()
    for i in range(200):
        ring2.err(C.Item("error", "E" + "x" * 290 + "Error", stack=None, t=i))
    assert len(C.diag_body(C.head("2.5.0"), ring2.events)) <= C.DIAG_BYTES
    assert "note" not in json.loads(C.diag_body(C.head("2.5.0"), [], "   "))
    assert C.diag_id({"ok": True, "id": "D-7K3FM"}) == "D-7K3FM"
    assert C.diag_id({"id": "D-0OIL1"}) is None and C.diag_id(None) is None


# --- Einbindung (Schein-Server) -------------------------------------------------------------------------------------
def _with_clientlog(srv, feature=True, log_code=200, diag_code=200):
    """api/version meldet clientlog1; api/client/log|diag antworten wie der Vertrag"""
    orig = srv.handle
    srv.clog, srv.cdiag = [], []

    def handle(method, url, data, cookie):
        path = url.split("://", 1)[-1].split("/", 1)[-1].split("?")[0].strip("/")
        if path == "api/version":
            return srv._reply(200, {"version": "2.5.0", "min_app": "1.0.0", "features": ["clientlog1"] if feature else []})
        if path == "api/client/log":
            srv.clog.append(json.loads(data))
            return srv._reply(log_code, {"ok": True} if log_code == 200 else {"error": "x", "retry_s": 90})
        if path == "api/client/diag":
            srv.cdiag.append(json.loads(data))
            return srv._reply(diag_code, {"ok": True, "id": "D-A4ZGF"} if diag_code == 200 else {"error": "x"})
        return orig(method, url, data, cookie)
    srv.handle = handle


@pytest.fixture
def fresh_log(monkeypatch):
    log = C.ClientLog()
    monkeypatch.setattr(C, "log", log)
    return log


def test_without_feature_nothing_sent(srv, fresh_log):
    from hlidskjalf.store import store
    _with_clientlog(srv, feature=False)
    store.check_version()
    pump(lambda: store.server_version == "2.5.0")
    fresh_log.crash_note = "EXC ValueError: x\n"
    fresh_log.ready()
    fresh_log.report("error", "TypeError: x")
    pump(timeout=2.0)
    assert srv.clog == [] and fresh_log.reporter.pending == [] and fresh_log.crash_note is None
    got = []
    fresh_log.send_diag("hallo", lambda k, v: got.append(k))
    assert got == ["off"] and srv.cdiag == []


def test_api_5xx_and_json_reported(srv, fresh_log, monkeypatch):
    from hlidskjalf.api import APIError, client
    from hlidskjalf.store import store
    monkeypatch.setattr(C, "RATE_S", 0.0)
    _with_clientlog(srv)
    store.check_version()
    pump(lambda: store.has("clientlog1"))
    srv.fail["GET api/notes/77"] = 503
    for _ in range(3):
        with pytest.raises(APIError):
            client.get("api/notes/77")
    orig = srv.handle
    srv.handle = lambda m, u, d, c: (200, b"<html>kaputt", {}) if "api/settings" in u else orig(m, u, d, c)
    with pytest.raises(ValueError):
        client.get("api/settings")
    srv.handle = orig
    assert pump(lambda: srv.clog, 6.0)
    b = srv.clog[0]
    _check_head(b)
    msgs = {i["msg"]: i for i in b["items"]}
    assert msgs["HTTPError.status5xx: http.GET api.notes.ID"]["n"] == 3
    assert "JSONDecodeError: http.GET api.settings" in msgs
    text = json.dumps(b)
    assert "saga.test" not in text and "mock 503" not in text
    net = [e for e in fresh_log.ring.events if e["k"] == "net"]
    assert {"m": "GET", "p": "/api/notes/:id", "s": 503} == {k: net[-2][k] for k in ("m", "p", "s")} or \
        any(e["p"] == "/api/notes/:id" and e["s"] == 503 for e in net)
    assert not any(e["p"].startswith("/api/client") for e in net)


def test_log_404_quiet_and_429(srv, fresh_log, monkeypatch):
    from hlidskjalf.store import store
    monkeypatch.setattr(C, "RATE_S", 0.0)
    _with_clientlog(srv, log_code=404)
    store.check_version()
    pump(lambda: store.has("clientlog1"))
    fresh_log.report("error", "TypeError: a")
    assert pump(lambda: srv.clog, 6.0)
    pump(lambda: fresh_log.reporter.off, 2.0)
    assert fresh_log.reporter.off
    fresh_log.report("error", "TypeError: b")
    pump(timeout=2.0)
    assert len(srv.clog) == 1


def test_crash_note_sent_once(srv, fresh_log):
    from hlidskjalf.store import store
    _with_clientlog(srv)
    fresh_log.crash_note = 'Fatal Python error: Aborted\n  File "/home/nutzer/a/window.py", line 5 in run\n'
    store.check_version()
    assert pump(lambda: srv.clog, 8.0)
    it = srv.clog[0]["items"][0]
    assert it["k"] == "crash" and it["msg"] == "Signal.SIGABRT" and it["stack"] == "window.py:5 window.run"
    assert fresh_log.crash_note is None


def test_install_writes_and_reads_note(tmp_path, monkeypatch):
    """Notiz-Datei: Ausnahme beim Beenden -> beim naechsten Start gelesen (Dateirechte 0600, nur Dateinamen)"""
    import faulthandler
    monkeypatch.setattr(C, "_note_path", lambda: str(tmp_path / "crash-note.txt"))
    monkeypatch.setattr(C, "_note_file", None)
    monkeypatch.setattr(C, "_keep", False)
    log = C.ClientLog()
    monkeypatch.setattr(C, "log", log)
    hook, thook = sys.excepthook, __import__("threading").excepthook
    try:
        C.install()
        assert oct(os.stat(tmp_path / "crash-note.txt").st_mode & 0o777) == "0o600"
        try:
            _boom()
        except ValueError as e:
            C.note_exception(e, e.__traceback__)
        text = (tmp_path / "crash-note.txt").read_text()
        assert text.startswith("EXC ValueError") and "nutzer" not in text and "/" not in text.split("\n", 1)[1]
        # excepthook meldet in den Ringpuffer
        sys.excepthook(ValueError, ValueError("x"), None)
        assert any(e["k"] == "err" and e["msg"].startswith("ValueError") for e in log.ring.events)
        # naechster Start: Notiz wird gelesen
        monkeypatch.setattr(C, "_note_file", None)
        log2 = C.ClientLog()
        monkeypatch.setattr(C, "log", log2)
        sys.excepthook, __import__("threading").excepthook = hook, thook
        C.install()
        assert log2.crash_note and log2.crash_note.startswith("EXC ValueError")
    finally:
        faulthandler.disable()
        sys.excepthook, __import__("threading").excepthook = hook, thook
        if C._note_file is not None:
            C._note_file.close()


def test_save_events(srv, fresh_log):
    from hlidskjalf import editor
    from hlidskjalf.api import APIError
    editor._clientlog_save(APIError(409))
    editor._clientlog_save(APIError(0))
    editor._clientlog_save(APIError(413))
    editor._clientlog_save(APIError(-1))
    ev = [e for e in fresh_log.ring.events if e["k"] == "save"]
    assert [(e["st"], e.get("s")) for e in ev] == [("conflict", 409), ("offline", 0), ("error", 413)]
    assert any(e["k"] == "err" and e["msg"] == "SaveError.status4xx" for e in fresh_log.ring.events)


def test_settings_diag(srv, fresh_log):
    from hlidskjalf.settings import DiagnoseGroup
    from hlidskjalf.store import store

    class Dlg:
        toasts = []

        def add_toast(self, t):
            self.toasts.append(t)
    g = DiagnoseGroup(Dlg())
    assert not g.btn.get_sensitive()                              # ohne Feature ausgegraut
    _with_clientlog(srv)
    store.check_version()
    pump(lambda: store.has("clientlog1"))
    g.refresh()
    assert g.btn.get_sensitive()
    fresh_log.view(("page", 12))
    g.note.set_text("Beim Speichern hing es. a@b.de")
    g.send()
    assert pump(lambda: g.sent_id, 6.0)
    assert g.sent_id == "D-A4ZGF" and g.res.get_subtitle() == "D-A4ZGF" and g.note.get_text() == ""
    b = srv.cdiag[0]
    _check_head(b)
    assert "<email>" in b["note"] and any(e == {"t": e["t"], "k": "view", "screen": "pages"} for e in b["ev"])
    assert "12" not in json.dumps([e for e in b["ev"] if e["k"] == "view"])
    g._copy()
    assert Dlg.toasts


def test_settings_diag_too_many(srv, fresh_log):
    from hlidskjalf.settings import DiagnoseGroup
    from hlidskjalf.store import store
    _with_clientlog(srv, diag_code=429)
    store.check_version()
    pump(lambda: store.has("clientlog1"))
    g = DiagnoseGroup(object())
    g.send()
    assert pump(lambda: g.res.get_visible(), 6.0)
    assert g.sent_id is None and "Too many" in g.res.get_title() or "viele" in g.res.get_title()
    assert g.btn.get_sensitive()
    time.sleep(0)
