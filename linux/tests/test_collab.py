"""Live mitschreiben (2.5, P3 Stufe 2, E6) ohne Server: gemeinsame Vektoren (tests/vectors25/pm_schema.json aus dem
Server-Repo) gegen das Dokumentmodell der App, Rahmenformat von WS /api/collab, WebSocket-Client gegen einen kleinen
lokalen Server, Bindung (Text an der Schreibmarke, Marken, Struktur, R1), Sitzung mit einer Schein-Verbindung
(Schema-Abweichung -> nichts gesendet, reject -> Neuaufbau, mehrfach -> Stufe 1, Rechte -> nur lesen, Undo nur eigene,
Wiederverbinden mit gen, Abo bei fehlendem pycrdt). Gegenproben mit echtem Server: tests/real25live.py, tests/real25web.py."""
import json
import os
import socket
import threading

import pytest

from hk_helpers import pump

collab = pytest.importorskip("hlidskjalf.collab")
if not collab.available():
    pytest.skip("pycrdt fehlt", allow_module_level=True)
from hlidskjalf import collabws as W  # noqa: E402

M = collab.model()
HERE = os.path.dirname(os.path.abspath(__file__))
V = json.load(open(os.path.join(HERE, "vectors25", "pm_schema.json"), encoding="utf-8"))


class Res:
    def __init__(self, r):
        self.r = r

    def page(self, bid, pid, i):
        return self.r["pages"].get(str(pid), self.r["page_default"])

    def mention(self, bid, u, i):
        return self.r["people"].get(u, self.r["mention_default"])


def st(v):
    return json.dumps(M._num(v), sort_keys=True, ensure_ascii=False)


def P(i, t):
    return {"id": i, "type": "paragraph", "data": {"text": t}}


# --- Vektoren ----------------------------------------------------------------------------------------------------------
def test_schema_hash_matches_vectors():
    assert V["schema_hash"] == M.SCHEMA_HASH and V["model"] == M.MODEL and V["schema_ver"] == M.SCHEMA_VER
    assert collab.schema_info() == {"model": "saga-pm", "ver": M.SCHEMA_VER, "hash": M.SCHEMA_HASH}


@pytest.mark.parametrize("case", V["cases"], ids=[c["name"][:60] for c in V["cases"]])
def test_vectors(case):
    R = Res(V["resolve"])
    blocks = case["blocks"]
    want = case.get("out", blocks)
    assert st(M.doc_to_pm({"blocks": blocks})) == st(case["pm"])
    d = collab.new_doc()
    d.apply_update(M.build({"blocks": blocks}))
    got, _t = M.read(d, R)
    assert st(got["blocks"]) == st(want)
    assert st(M.pm_to_doc(case["pm"], R)["blocks"]) == st(want)
    # die Bindung der App schreibt beim Anzeigen nichts um (R1): write_local mit dem gelesenen Stand aendert nichts
    seen = []
    sub = d.observe(lambda e: seen.append(len(e.update)))
    collab.write_local(d, got["blocks"], None)
    d.unobserve(sub)
    if st(want) == st(blocks):
        assert not [n for n in seen if n > 2], case["name"]


# --- Rahmenformat ------------------------------------------------------------------------------------------------------
def test_frame_roundtrip_and_doc_example():
    assert W.varuint(300) == b"\xac\x02"
    f = W.encode("sub", 812, {"schema": {"model": "saga-pm"}})
    assert f[:9] == bytes.fromhex("02000000000000032c")
    k, nid, parts = W.decode(f)
    assert (k, nid) == ("sub", 812) and json.loads(parts[0]) == {"schema": {"model": "saga-pm"}}
    big = os.urandom(70000)
    assert W.decode(W.encode("update", 5, big, b"u1"))[2] == [big, b"u1"]
    assert W.decode(W.encode("ping", 0)) == ("ping", 0, [])
    with pytest.raises(ValueError):
        W.decode(b"\x06\x00")
    with pytest.raises(ValueError):
        W.decode(W.encode("update", 1, b"abc")[:-1])


def _ws_server(script):
    """Mini-WebSocket-Server (ein Client): Handshake, dann script(conn, send, recv)"""
    import base64
    import hashlib
    import struct
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]
    seen = {}

    def run():
        c, _a = srv.accept()
        head = b""
        while b"\r\n\r\n" not in head:
            head += c.recv(4096)
        lines = head.decode().split("\r\n")
        hd = {ln.split(":", 1)[0].lower(): ln.split(":", 1)[1].strip() for ln in lines[1:] if ":" in ln}
        seen.update(hd)
        acc = base64.b64encode(hashlib.sha1(hd["sec-websocket-key"].encode() + b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest()).decode()
        c.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Accept: {acc}\r\n\r\n").encode())

        def send(data, op=2):
            n = len(data)
            if n < 126:
                ln = bytes([n])
            elif n < 65536:
                ln = bytes([126]) + struct.pack(">H", n)
            else:
                ln = bytes([127]) + struct.pack(">Q", n)
            h = bytes([0x80 | op]) + ln
            c.sendall(h + data)

        def recv():
            b0, b1 = c.recv(1)[0], c.recv(1)[0]
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", c.recv(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", c.recv(8))[0]
            mask = c.recv(4)
            data = b""
            while len(data) < n:
                data += c.recv(n - len(data))
            return b0 & 0x0F, bytes(x ^ mask[i % 4] for i, x in enumerate(data))
        try:
            script(c, send, recv)
        finally:
            c.close()
            srv.close()
    threading.Thread(target=run, daemon=True).start()
    return port, seen


def test_websocket_client_against_local_server():
    import struct
    got = {}

    def script(c, send, recv):
        send(W.encode("hello", 0, {"proto": 1}))
        send(b"x" * 10, op=9)                                 # Ping vom Server -> Pong
        for _i in range(2):                                   # Reihenfolge: sub (Client) und Pong (beim naechsten recv)
            op, data = recv()
            if op == 10:
                got["pong"] = (op, data)
            else:
                got["frame"] = W.decode(data)
        send(b"text", op=1)                                   # Textrahmen: ignoriert
        send(W.encode("ack", 3, b"u1"))
        send(struct.pack(">H", 4403), op=8)
        recv()                                                # Schliessen-Antwort des Clients abwarten
    port, hd = _ws_server(script)
    ws = W.WebSocket(f"ws://127.0.0.1:{port}/api/collab", {"Authorization": "Bearer hkd_test"}).connect()
    assert W.decode(ws.recv()) == ("hello", 0, [b'{"proto":1}'])
    ws.send_binary(W.encode("sub", 3, {"schema": {}}))
    assert W.decode(ws.recv()) == ("ack", 3, [b"u1"])
    with pytest.raises(W.Closed) as ei:
        ws.recv()
    assert ei.value.code == 4403
    assert got["pong"] == (10, b"x" * 10)
    assert got["frame"][0] == "sub"
    assert hd.get("authorization") == "Bearer hkd_test" and "origin" not in hd


# --- Bindung -----------------------------------------------------------------------------------------------------------
def _doc(blocks, title="T"):
    d = collab.new_doc()
    d.apply_update(M.build({"blocks": blocks}, title))
    return d


def test_client_id_never_one():
    for _i in range(50):
        assert collab.new_doc().client_id not in (0, 1)


def test_write_local_at_caret_with_same_letters_and_marks():
    d = _doc([P("a", "aa<b>bb</b>")])
    b = P("a", "aaa<b>bb</b>")                                # drittes "a" an Stelle 1 getippt (nicht am Ende)
    seen = []
    d.observe(lambda e: seen.append(e.update))
    collab.write_local(d, [b], "T", hint=b, caret=2)
    assert M.read(d)[0]["blocks"][0]["data"]["text"] == "aaa<b>bb</b>"
    # gleichzeitig: anderer Client tippt am Anfang; die eigene Einfuegung (Position 1-2) bleibt richtig
    o = collab.new_doc()
    o.apply_update(d.get_update())
    t = o.get(M.FRAGMENT, type=M.XmlFragment).children[0].children[0]
    t.insert(0, "X")
    d.apply_update(o.get_update())
    assert M.read(d)[0]["blocks"][0]["data"]["text"] == "Xaaa<b>bb</b>"
    # in eine Marke getippt: Marke b, ausserhalb: ohne Marke (keine geerbte Marke)
    b2 = P("a", "Xaaa<b>bbY</b>")
    collab.write_local(d, [b2], "T", hint=b2, caret=7)
    b3 = P("a", "Xaaa<b>bbY</b>Z")
    collab.write_local(d, [b3], "T", hint=b3, caret=8)
    assert M.read(d)[0]["blocks"][0]["data"]["text"] == "Xaaa<b>bbY</b>Z"
    b4 = P("a", "Xa<b>bbY</b>Z")                              # zwei Zeichen geloescht (Schreibmarke danach bei 2)
    collab.write_local(d, [b4], "T", hint=b4, caret=2)
    assert M.read(d)[0]["blocks"][0]["data"]["text"] == "Xa<b>bbY</b>Z"


def test_write_local_structure_and_opaque_untouched():
    db = {"id": "d1", "type": "database", "data": {"db": 7, "views": [{"id": "v", "type": "table"}]}}
    blocks = [P("a", "eins"), P("b", "zwei"), db]
    d = _doc(blocks)
    cur, _t = collab.read(d, collab.Resolver(), blocks)
    assert cur[2] is db                                       # unveraendert: dasselbe Objekt
    new = [cur[1], P("n", "neu"), cur[0], cur[2]]             # verschieben + einfuegen
    collab.write_local(d, new, "T")
    out = M.read(d)[0]["blocks"]
    assert [b["id"] for b in out] == ["b", "n", "a", "d1"] and out[3] == db
    del new[1]
    collab.write_local(d, new, None)
    collab.write_local(d, new, "Neuer Titel", hint="title")
    assert [b["id"] for b in M.read(d)[0]["blocks"]] == ["b", "a", "d1"] and M.read(d)[1] == "Neuer Titel"
    assert collab.pending_changes(d, new, collab.Resolver(), "Neuer Titel") == []


def test_pending_changes_detects_rewrite():
    d = _doc([P("a", "eins")])
    assert collab.pending_changes(d, [P("a", "eins")], collab.Resolver(), "T") == []
    assert collab.pending_changes(d, [P("a", "anders")], collab.Resolver(), "T") == ["a"]
    assert "title" in collab.pending_changes(d, [P("a", "eins")], collab.Resolver(), "X")


def test_resolver_like_server():
    r = collab.Resolver(titles=lambda pid: {3: "Seite drei"}.get(pid), names=lambda u: {"~a": "Anna"}.get(u), no_access="Ohne")
    r.learn([P("p", 'x <a href="/notes#9">Alt</a> <span class="hk-mention" data-u="~b">Bert</span>')])
    assert r.page("p", 3, 0) == "Seite drei" and r.page("p", 9, 0) == "Alt" and r.page("p", 11, 0) == "Ohne"
    assert r.mention("p", "~b", 0) == "Bert" and r.mention("q", "~a", 0) == "Anna" and r.mention("q", "~z", 0) == ""


def test_legacy_pages_stay_stufe1():
    assert collab.legacy([{"id": "t", "type": "toggle", "data": {"text": "alt"}}])
    assert collab.legacy([{"id": "s", "type": "synced", "data": {"sid": "x", "src": {"note": 1}}}])
    assert not collab.legacy([{"id": "t", "type": "toggle", "data": {"title": "neu", "blocks": []}}, P("a", "x")])


def test_map_offset():
    assert collab.map_offset("Hallo Welt", "Web: Hallo Welt", 5) == 10
    assert collab.map_offset("Hallo Welt", "Hallo Welt!!", 10) == 10
    assert collab.map_offset("abc", "abc", 2) == 2


# --- Sitzung mit Schein-Verbindung ---------------------------------------------------------------------------------
class FakeConn:
    def __init__(self, server_doc):
        self.sessions, self.sent, self.open, self.off, self.fails = {}, [], True, False, 0
        self.hello = {"proto": 1, "schema": collab.schema_info()}
        self.stats = {"sent": 0, "recv": 0, "acked": 0, "rejects": 0}
        self.server = server_doc
        self.on_auth = None

    def add(self, s):
        self.sessions[s.nid] = s

    def remove(self, s):
        self.sessions.pop(s.nid, None)

    def ensure(self):
        pass

    def send(self, kind, nid, *parts):
        # wie die echte Verbindung: Teile so, wie sie im Rahmen stehen (JSON als Bytes)
        self.sent.append((kind, nid, W.decode(W.encode(kind, nid, *parts))[2]))
        return True

    def kinds(self):
        return [k for k, _n, _p in self.sent]

    # Server-Seite
    def deliver(self, s, kind, *parts):
        enc = [p if isinstance(p, bytes) else json.dumps(p).encode() for p in parts]
        s.frame(kind, enc)


class Host:
    def __init__(self, blocks, title="T"):
        self.blocks, self.title = blocks, title
        self.modes, self.fallbacks, self.notices, self.aw = [], [], [], {}
        self.busy = False
        self.hold = False

    def live_blocks(self):
        return self.blocks

    def live_title(self):
        return self.title

    def live_busy(self):
        return self.busy

    def live_bind(self, blocks, title):
        self.blocks[:] = blocks
        self.title = title

    def live_apply(self, blocks, title):
        self.blocks[:] = blocks
        self.title = title

    def live_mode(self, m):
        self.modes.append(m)

    def live_fallback(self, pending):
        self.fallbacks.append(pending)

    def live_notice(self, c):
        self.notices.append(c)

    def live_gone(self, r):
        self.notices.append("gone:" + r)

    def live_aw(self, st):
        self.aw = st

    def live_focus(self):
        return None

    def live_hold(self):
        return self.hold


def _session(blocks, role="owner", ro=False, schema=None):
    srv = _doc(blocks)
    conn = FakeConn(srv)
    if schema is not None:
        conn.hello = {"proto": 1, "schema": schema}
    host = Host([dict(b) for b in blocks])
    s = collab.Session(conn, 5, host, collab.Resolver())
    s.start()
    if conn.kinds() == ["sub"]:
        conn.deliver(s, "hello", {"proto": 1, "gen": "g1", "role": role, "ro": ro, "why": None, "schema": collab.schema_info(),
                                  "me": 1, "name": "Anna", "color": "blue"})
        conn.deliver(s, "sync1", srv.get_state())
        conn.deliver(s, "sync2", srv.get_update())
    return s, conn, host, srv


def _server_apply(conn, s, srv):
    """letztes update des Clients beim Server anwenden + ack"""
    k, nid, parts = [x for x in conn.sent if x[0] == "update"][-1]
    srv.apply_update(parts[0])
    conn.deliver(s, "ack", parts[1])
    return parts[0]


def test_session_bind_sends_nothing_then_types(srv):
    s, conn, host, sd = _session([P("a", "Hallo")])
    assert s.state == "live" and host.modes[-1] == "live"
    assert conn.kinds() == ["sub"] and s.loaddrops == 0
    sub = conn.sent[0][2][0]
    assert json.loads(sub)["schema"] == collab.schema_info()
    host.blocks[0] = P("a", "Hallo!")
    s.local(host.blocks[0], 6)
    assert conn.kinds()[-1] == "update"
    _server_apply(conn, s, sd)
    assert M.read(sd)[0]["blocks"][0]["data"]["text"] == "Hallo!" and not s.unacked


def test_session_remote_update_keeps_objects(srv):
    s, conn, host, sd = _session([P("a", "eins"), P("b", "zwei")])
    keep = host.blocks[1]
    o = collab.new_doc()
    o.apply_update(sd.get_update())
    sv = o.get_state()
    cur, _t = M.read(o)
    cur["blocks"][0]["data"]["text"] = "eins fremd"
    with o.transaction():
        M.write(o, cur, None)
    conn.deliver(s, "update", o.get_update(sv))
    assert host.blocks[0]["data"]["text"] == "eins fremd" and host.blocks[1] is keep
    assert conn.kinds() == ["sub"]                              # kein Echo


def test_session_schema_mismatch_sends_nothing(srv):
    s, conn, host, sd = _session([P("a", "x")], schema={"model": "saga-pm", "ver": 99, "hash": "0" * 16})
    assert s.state == "lock" and host.modes[-1] == "lock"
    assert "update" not in conn.kinds() and "sub" not in conn.kinds()
    s.local(P("a", "y"))
    assert "update" not in conn.kinds()


def test_session_schema_why_in_hello_locks(srv):
    sd = _doc([P("a", "x")])
    conn = FakeConn(sd)
    host = Host([P("a", "x")])
    s = collab.Session(conn, 5, host, collab.Resolver())
    s.start()
    conn.deliver(s, "hello", {"gen": "g", "role": "edit", "ro": True, "why": "schema"})
    assert s.state == "lock" and conn.kinds() == ["sub", "unsub"]


def test_session_load_rewrite_locks(srv):
    """Stand im Zustand, den die App nicht unveraendert zeigen wuerde (hier: Host schreibt beim Binden um) -> nur lesen"""
    s0 = _doc([P("a", "x")])
    conn = FakeConn(s0)
    host = Host([P("a", "x")])
    host.live_bind = lambda blocks, title: host.blocks.__setitem__(slice(None), [P("a", "umgeschrieben")])
    s = collab.Session(conn, 5, host, collab.Resolver())
    s.start()
    conn.deliver(s, "hello", {"gen": "g", "role": "owner", "ro": False, "why": None})
    conn.deliver(s, "sync2", s0.get_update())
    assert s.state == "lock" and "update" not in conn.kinds()


def test_session_readonly_role_sends_nothing(srv):
    s, conn, host, sd = _session([P("a", "x")], role="view", ro=True)
    assert s.state == "live" and host.modes[-1] == "ro" and not s.editable
    host.blocks[0] = P("a", "y")
    s.local(host.blocks[0], 1)
    assert "update" not in conn.kinds()
    conn.deliver(s, "hello", {"update": True, "role": "edit", "ro": False})
    assert s.editable and host.modes[-1] == "live"
    conn.deliver(s, "hello", {"update": True, "role": "view", "ro": True})
    assert not s.editable and host.modes[-1] == "ro"


def test_session_reject_rebuild_and_fallback(srv):
    s, conn, host, sd = _session([P("a", "x")])
    for i in range(2):
        host.blocks[0] = P("a", "x" + "y" * (i + 1))
        s.local(host.blocks[0], i + 2)
        ref = conn.sent[-1][2][1].decode()
        conn.deliver(s, "reject", {"code": "sanitize", "ref": ref}, sd.get_update())
        assert s.state == "live" and host.blocks[0]["data"]["text"] == "x"
        assert host.notices[-1] in ("rejected", "rejected_lost")
    old_client = s.doc.client_id
    host.blocks[0] = P("a", "xz")
    s.local(host.blocks[0], 2)
    ref = conn.sent[-1][2][1].decode()
    conn.deliver(s, "reject", {"code": "type", "ref": ref}, sd.get_update())
    assert s.state == "off" and host.fallbacks                    # 3x in 60 s -> Stufe 1
    assert old_client


def test_session_reject_keeps_independent_changes(srv):
    s, conn, host, sd = _session([P("a", "x"), P("b", "y")])
    host.blocks[0] = P("a", "x1")
    s.local(host.blocks[0], 2)
    u1 = conn.sent[-1]
    host.blocks[1] = P("b", "y1")
    s.local(host.blocks[1], 2)
    # Server lehnt das erste ab (z. B. client), das zweite ist noch unbestaetigt -> bleibt erhalten
    conn.deliver(s, "reject", {"code": "forbidden", "ref": u1[2][1].decode()}, sd.get_update())
    assert host.blocks[0]["data"]["text"] == "x" and host.blocks[1]["data"]["text"] == "y1"
    assert conn.kinds()[-1] == "update"


def test_session_undo_only_own(srv):
    s, conn, host, sd = _session([P("a", "eins"), P("b", "zwei")])
    host.blocks[0] = P("a", "eins A")
    s.local(host.blocks[0], 6)
    o = collab.new_doc()
    o.apply_update(s.doc.get_update())
    sv = o.get_state()
    cur, _t = M.read(o)
    cur["blocks"][1]["data"]["text"] = "zwei B"
    with o.transaction():
        M.write(o, cur, None)
    conn.deliver(s, "update", o.get_update(sv))
    assert s.undo()
    assert [b["data"]["text"] for b in host.blocks] == ["eins", "zwei B"]
    assert conn.kinds()[-1] == "update"                          # Undo geht als eigenes Update hinaus
    assert s.redo() and host.blocks[0]["data"]["text"] == "eins A"


def test_session_ime_hold_merges_without_loss(srv):
    s, conn, host, sd = _session([P("a", "Start")])
    host.hold = True
    o = collab.new_doc()
    o.apply_update(s.doc.get_update())
    sv = o.get_state()
    cur, _t = M.read(o)
    cur["blocks"][0]["data"]["text"] = "Start fremd"
    with o.transaction():
        M.write(o, cur, None)
    conn.deliver(s, "update", o.get_update(sv))
    assert host.blocks[0]["data"]["text"] == "Start"           # zurueckgehalten
    host.hold = False
    host.blocks[0] = P("a", "日本Start")                       # Abschluss der Eingabe am Anfang (gegen alten Stand)
    s.local(host.blocks[0], 2)
    pump(lambda: host.blocks[0]["data"]["text"] == "日本Start fremd", 2)
    assert M.read(s.doc)[0]["blocks"][0]["data"]["text"] == "日本Start fremd"
    assert host.blocks[0]["data"]["text"] == "日本Start fremd"


def test_session_reconnect_same_gen_sends_missing(srv):
    s, conn, host, sd = _session([P("a", "x")])
    conn.open = False
    s.conn_closed(1012)
    assert s.state == "offline"
    host.blocks[0] = P("a", "x offline")
    s.local(host.blocks[0], 9)
    assert s.offline_dirty and "update" not in conn.kinds()
    conn.open = True
    s.conn_open()
    sub = [x for x in conn.sent if x[0] == "sub"][-1]
    assert json.loads(sub[2][0]).get("gen") == "g1" and len(sub[2]) == 2
    conn.deliver(s, "hello", {"gen": "g1", "role": "owner", "ro": False, "why": None})
    conn.deliver(s, "sync1", sd.get_state())
    conn.deliver(s, "sync2", b"\x00\x00")
    assert s.state == "live" and conn.kinds()[-2:-1] == ["sync2"] or "sync2" in conn.kinds()
    miss = [x for x in conn.sent if x[0] == "sync2"][-1][2][0]
    sd.apply_update(miss)
    assert M.read(sd)[0]["blocks"][0]["data"]["text"] == "x offline"


def test_session_reconnect_new_gen_falls_back_with_pending(srv):
    s, conn, host, sd = _session([P("a", "x")])
    conn.open = False
    s.conn_closed(1006)
    host.blocks[0] = P("a", "x2")
    s.local(host.blocks[0], 2)
    conn.open = True
    s.conn_open()
    conn.deliver(s, "hello", {"gen": "NEU", "role": "owner", "ro": False, "why": None})
    assert host.fallbacks == [True]                               # eigene Aenderung per Stufe 1 (PATCH) retten


def test_session_bye_off_and_access(srv):
    s, conn, host, sd = _session([P("a", "x")])
    conn.deliver(s, "bye", {"reason": "off"})
    assert s.state == "off" and conn.off and host.fallbacks == [False]
    s, conn, host, sd = _session([P("a", "x")])
    conn.deliver(s, "bye", {"reason": "access"})
    assert s.state == "off" and "gone:access" in host.notices


def test_session_permanent_close_codes(srv):
    s, conn, host, sd = _session([P("a", "x")])
    s.conn_closed(4403)
    assert s.state == "off" and host.fallbacks


def test_session_awareness(srv):
    s, conn, host, sd = _session([P("a", "x")])
    conn.deliver(s, "aw", {"sid": 7, "state": {"block": "a", "name": "Bert", "color": "green", "me": False}})
    assert host.aw == {7: {"name": "Bert", "color": "green", "block": "a"}}
    conn.deliver(s, "aw", {"sid": 7, "state": None})
    assert host.aw == {}
    s.aw_out("a")
    pump(lambda: "aw" in conn.kinds(), 1)
    aw = [x for x in conn.sent if x[0] == "aw"][-1]
    assert json.loads(aw[2][0]) == {"block": "a", "cursor": None}


def test_without_pycrdt_stays_stufe1(srv, monkeypatch):
    from hlidskjalf import livepage
    from hlidskjalf.store import store
    monkeypatch.setattr(collab, "available", lambda: False)
    store.features = frozenset({"live1"})

    class V:
        in_peek, remote = False, False

        class doc:
            fetched, is_database, id, in_trash, lost, conflict = True, False, 5, False, None, None
            page, root = {"format": "blocks"}, []
    assert not livepage.eligible(V)
    monkeypatch.setattr(collab, "available", lambda: True)
    assert livepage.eligible(V)
    store.features = frozenset()
    assert not livepage.eligible(V)


def test_ws_url_and_headers(srv):
    from hlidskjalf import config, livepage
    config.settings.server = "https://saga.test"
    assert livepage._ws_url() == "wss://saga.test/api/collab"
    config.set_device_token("hkd_abc", "https://saga.test")
    h = livepage._ws_headers()
    assert h["Authorization"] == "Bearer hkd_abc" and "Origin" not in h
    config.set_device_token(None, "https://saga.test")


def test_flush_all_rescues_unconfirmed_live_changes(srv):
    """Schliessen/Kontowechsel (_flush_all) waehrend Live offline: eigene, noch nicht bestaetigte Eingaben gehen auf
    Stufe 1 (PATCH bzw. Entwurf) - sonst haelt das Fenster nichts fuer ungespeichert und die Eingabe ginge verloren"""
    from hlidskjalf import livepage
    from hlidskjalf.window import MainWindow

    class Sess:
        unacked, offline_dirty = [], True

    class LP:
        stopped = 0
        session = Sess()
        pending = livepage.LivePage.pending

        def stop(self):
            self.stopped += 1

    class View:
        livep = LP()

    class Win:
        current, peek = None, None

    v, w = View(), Win()
    w._views = lambda: [v]
    MainWindow._flush_all(w)
    assert v.livep.stopped == 1
    v.livep.session.offline_dirty = False
    MainWindow._flush_all(w)
    assert v.livep.stopped == 1                                     # nichts offen: Live bleibt
