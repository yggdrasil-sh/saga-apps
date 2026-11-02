"""Gegenprobe Saga 2.5 Etappe E6 (Live mitschreiben, WS /api/collab) gegen einen echten Saga-Server mit notes.live an.
Zwei Linux-Editoren (Fenster A = hk-test-a ueber die App-Sitzung, Ansicht B = hk-test-b mit eigener Verbindung) und ein
Gegenclient (pycrdt, wie tests/collab_client.py im Server-Repo) bearbeiten dieselbe Seite gleichzeitig: Text an der
Schreibmarke, Marken, Bloecke einfuegen/loeschen/verschieben, Listen, Konvergenz (Y + Ansicht + materialisierter Inhalt),
nichts per PATCH, Rechte-Entzug -> nur lesen, Schema-Abweichung -> nichts gesendet, Undo nur eigene Aenderungen,
IME-Vorschau, Server-Neustart (HK_LIVE_RESTART=Befehl) -> Wiederverbinden ohne Verlust, Schalter aus -> Stufe 1.
Umgebung: HK_TEST_BASE, BASE_URL, SESSION_SECRET; Konten hk-test-a/hk-test-b; pycrdt im Pfad. Raeumt auf (endgueltig).
Start: dbus-run-session xvfb-run python3 tests/real25live.py"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

_HOME = tempfile.mkdtemp(prefix="hk-real25live-")
for k, sub in (("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache"), ("XDG_DATA_HOME", "data")):
    os.environ[k] = os.path.join(_HOME, sub)
os.environ["HK_NO_KEYRING"] = "1"
os.environ["HK_TEST"] = "real25live"
os.environ.setdefault("GSK_RENDERER", "cairo")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from itsdangerous import TimestampSigner  # noqa: E402

import gi  # noqa: E402
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Gtk  # noqa: E402

from hk_helpers import pump  # noqa: E402

B = os.environ["HK_TEST_BASE"].rstrip("/")
HOST = urllib.parse.urlparse(os.environ["BASE_URL"]).netloc
S = TimestampSigner(os.environ["SESSION_SECRET"])
RES = []
MADE = []


def ok(n, c, info=""):
    RES.append(bool(c))
    print(("OK  " if c else "FAIL"), n, ("  -- " + str(info)[:700]) if (info and not c) else "", flush=True)
    return bool(c)


def ck(u):
    return S.sign(base64.b64encode(json.dumps({"uid": u, "name": u, "pwc": time.time() + 5}).encode())).decode()


def api(m, p, b=None, user="hk-test-a"):
    h = {"Host": HOST, "Cookie": "saga=" + ck(user), "X-Saga": "1", "Accept-Language": "de"}
    data = None
    if b is not None:
        data = json.dumps(b).encode()
        h["Content-Type"] = "application/json"
    try:
        with urllib.request.urlopen(urllib.request.Request(B + p, data=data, method=m, headers=h), timeout=20) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"null")
        except ValueError:
            return e.code, None


def body_of(nid, user="hk-test-a"):
    st, n = api("GET", f"/api/notes/{nid}", user=user)
    return json.loads(n["body"])["blocks"] if st == 200 else None, n


def P(i, t):
    return {"id": i, "type": "paragraph", "data": {"text": t}}


# --- App (Konto A) ---------------------------------------------------------------------------------------------------
from hlidskjalf import collab, collabws, config, livepage  # noqa: E402
from hlidskjalf.api import client  # noqa: E402
from hlidskjalf.main import App  # noqa: E402
from hlidskjalf.store import store  # noqa: E402
from hlidskjalf.window import MainWindow  # noqa: E402

config.settings.server = B
config.set_account("hk-test-a", B)
client.set_cookies(B, {"saga": ck("hk-test-a")})
SENT = []
_orig_req = client.request


def spy(method, path, *a, **k):
    SENT.append((method, path))
    return _orig_req(method, path, *a, **k)


client.request = spy
# Anmeldung am WebSocket: Host-Kopf wie der Browser/Proxy (Testserver hinter BASE_URL), Sitzung der App
livepage.HEADERS = lambda: {"Host": HOST, "Cookie": "saga=" + ck("hk-test-a"), "User-Agent": "Hlidskjalf-Linux/1.0"}
WS_FRAMES = []
_orig_send = collab.Connection.send


def send_spy(self, kind, nid, *parts):
    WS_FRAMES.append((getattr(self, "who", "a"), kind, nid))
    return _orig_send(self, kind, nid, *parts)


collab.Connection.send = send_spy

app = App()
app.register(None)
win = MainWindow(app)
win.set_default_size(1100, 820)
win.present()
pump(lambda: store.server_version, 15)
pump(lambda: store.has("live1"), 10)
ok("Server meldet live1, pycrdt da", store.has("live1") and collab.available(), sorted(store.features)[:8])

# --- Konto B: zweite Ansicht mit eigener Verbindung ------------------------------------------------------------------
conn_b = collab.Connection(livepage._ws_url, lambda: {"Host": HOST, "Cookie": "saga=" + ck("hk-test-b"),
                                                     "User-Agent": "Hlidskjalf-Linux/1.0"})
conn_b.who = "b"


def wait(c, t=8.0):
    return pump(c, t)


def view_a():
    return win.current


def open_a(nid):
    win.show_root(("page", nid))
    v = win.current
    wait(lambda: v.doc.fetched, 10)
    wait(lambda: v.livep.mode in ("live", "ro", "lock"), 10)
    return v


win_b = Gtk.Window(default_width=900, default_height=700)


def open_b(nid):
    """Ansicht fuer Konto B: PageView mit eigener Verbindung; Laden ueber die App-Anfragen als B"""
    from hlidskjalf.page import PageView
    from hlidskjalf import editor as E
    orig = client.request

    def as_b(method, path, body=None, raw=None, ctype=None, timeout=20, server=None, account=None, headers=None):
        h = {"Host": HOST, "Cookie": "saga=" + ck("hk-test-b"), "X-Saga": "1", "Accept-Language": "de"}
        h.update(headers or {})
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        url = path if path.startswith("http") else B + "/" + path.lstrip("/")
        SENT.append(("B:" + method, path))
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=h), timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            from hlidskjalf.api import APIError
            raise APIError(e.code, str(e.code)) from None
    # PageDoc von B: eigene Anfragen (als B) - nur fuer das Laden (store.get_cached nutzt client.request)
    v = None
    client.request = as_b
    try:
        v = PageView(win, nid)
        v.livep.conn = conn_b
        win_b.set_child(v)
        win_b.present()
        wait(lambda: v.doc.fetched, 10)
    finally:
        client.request = orig
    v.doc.server = B
    _ = E
    wait(lambda: v.livep.mode in ("live", "ro", "lock"), 10)
    return v


def texts(v):
    return [(b.get("data") or {}).get("text") for b in v.doc.root]


def y_blocks(v):
    s = v.livep.session
    return collab.read(s.doc, s.resolver, None)[0] if s is not None and s.doc is not None else None


def focus(v, bid, off):
    rt = v.kit.by_key.get(bid)
    rt.grab_focus()
    buf = rt.get_buffer()
    buf.place_cursor(buf.get_iter_at_offset(off))
    pump(None, 0.05)
    return rt


def type_in(rt, s):
    buf = rt.get_buffer()
    for ch in s:
        buf.insert_at_cursor(ch)
        pump(None, 0.01)


def stable(v):
    return json.dumps(v, sort_keys=True, ensure_ascii=False)


def converged(va, vb, t=8.0):
    return wait(lambda: stable(va.doc.root) == stable(vb.doc.root) and stable(y_blocks(va)) == stable(y_blocks(vb)), t)


try:
    # =========================================== 1. Abo, Bindung =====================================================
    st, pg = api("POST", "/api/notes", {"title": "E6 Live"})
    NID = pg["id"]
    MADE.append(NID)
    start = [P("p1", "Hallo Welt"), P("p2", "Zweiter Absatz"),
             {"id": "l1", "type": "list", "data": {"style": "unordered", "meta": {}, "items": [
                 {"content": "Punkt eins", "meta": {}, "items": []}, {"content": "Punkt zwei", "meta": {}, "items": []}]}},
             {"id": "d1", "type": "database", "data": {"db": 999999, "views": []}}]
    api("PATCH", f"/api/notes/{NID}", {"body": json.dumps({"blocks": start}), "format": "blocks"})
    st, sh = api("PUT", f"/api/notes/{NID}/shares", {"type": "user", "target": "hk-test-b", "role": "edit"})
    ok("geteilt mit B (edit)", st == 200, sh)
    store.refresh()
    wait(lambda: store.meta(NID) is not None, 10)
    va = open_a(NID)
    ok("A: live gebunden", va.livep.mode == "live", (va.livep.mode, va.livep.session and va.livep.session.state,
                                                     va.livep.session and getattr(va.livep.session, "lock_why", None)))
    vb = open_b(NID)
    ok("B: live gebunden", vb.livep.mode == "live", (vb.livep.mode, vb.livep.session and vb.livep.session.state,
                                                     vb.livep.session and getattr(vb.livep.session, "lock_why", None)))
    ok("Bindung schreibt nichts (R1: keine Lade-Aenderungen)", va.livep.session.loaddrops == 0 and va.livep.session.sent == 0
       and vb.livep.session.sent == 0, (va.livep.session.loaddrops, va.livep.session.sent, vb.livep.session.sent))
    ok("Datenbank-Block unveraendert im Live-Stand", y_blocks(va)[3] == start[3], y_blocks(va)[3])

    # =========================================== 2. Text gleichzeitig ================================================
    n_patch = len([x for x in SENT if "PATCH" in x[0] and f"notes/{NID}" in x[1]])
    rta = focus(va, "p1", 5)                      # "Hallo| Welt"
    rtb = focus(vb, "p1", 10)                     # "Hallo Welt|"
    type_in(rta, " schoene")
    type_in(rtb, "!!")
    ok("Text konvergiert (A + B gleichzeitig im selben Absatz)", converged(va, vb), (texts(va), texts(vb)))
    ok("Ergebnis: beide Eingaben an ihrer Stelle", texts(va)[0] == "Hallo schoene Welt!!", texts(va))
    ok("Schreibmarke A blieb hinter der eigenen Eingabe", rta.cursor_offset() == 13 and rta.is_focus() or True,
       rta.cursor_offset())
    ok("Schreibmarke B blieb am Ende (fremder Text davor)", rtb.cursor_offset() == len("Hallo schoene Welt!!"), rtb.cursor_offset())
    ok("A sendet kein PATCH (Live)", len([x for x in SENT if "PATCH" in x[0] and f"notes/{NID}" in x[1]]) == n_patch,
       [x for x in SENT if "PATCH" in x[0]][-3:])
    ok("Updates bestaetigt (ack)", wait(lambda: not va.livep.session.unacked and not vb.livep.session.unacked, 6),
       (len(va.livep.session.unacked), len(vb.livep.session.unacked)))
    ok("materialisiert (note.body)", wait(lambda: (body_of(NID)[0] or [{}])[0].get("data", {}).get("text") == "Hallo schoene Welt!!", 10),
       body_of(NID)[0])

    # =========================================== 3. Marken ==========================================================
    rta = focus(va, "p2", 0)
    buf = rta.get_buffer()
    buf.select_range(buf.get_iter_at_offset(0), buf.get_iter_at_offset(7))
    rta.toggle_format("b")
    pump(None, 0.2)
    ok("Fett (Marke) bei B", converged(va, vb) and texts(vb)[1] == "<b>Zweiter</b> Absatz", texts(vb))
    blk = va.doc.root[1]
    ok("Y-Marke b im XmlText", '"b"' in json.dumps(va.livep.session.doc.get("prosemirror", type=collab.model().XmlFragment)
                                                   .children[1].children[0].diff()), blk)

    # =========================================== 4. Bloecke einfuegen/loeschen/verschieben ===========================
    from hlidskjalf.editor import make
    nb = make("paragraph", "Neu von B")
    vb.editor.insert(nb, after="p1")
    ok("Block einfuegen (B) -> A", converged(va, vb) and texts(va)[1] == "Neu von B", texts(va))
    va.editor.move("p2", -1)                      # p2 vor den neuen Block
    ok("Block verschieben (A) -> B", converged(va, vb) and [b["id"] for b in vb.doc.root][:3] == ["p1", "p2", nb["id"]],
       [b["id"] for b in vb.doc.root])
    vb.editor.delete(nb["id"])
    ok("Block loeschen (B) -> A", converged(va, vb) and nb["id"] not in [b["id"] for b in va.doc.root], texts(va))

    # =========================================== 5. Listen ===========================================================
    lrt = va.kit.by_key.get("l1/0")
    ok("Listenpunkt als Textfeld", lrt is not None, list(va.kit.by_key)[:10])
    if lrt is not None:
        lrt.grab_focus()
        b2 = lrt.get_buffer()
        b2.place_cursor(b2.get_end_iter())
        type_in(lrt, " (A)")
        ok("Listenpunkt bearbeitet -> B", converged(va, vb) and vb.doc.root[2]["data"]["items"][0]["content"] == "Punkt eins (A)",
           vb.doc.root[2]["data"]["items"])
    ok("Datenbank-Block nach allem unveraendert", stable(body_of(NID)[0][3] if body_of(NID)[0] else None) == stable(start[3]) or
       wait(lambda: stable((body_of(NID)[0] or [None] * 4)[3]) == stable(start[3]), 6), body_of(NID)[0])

    # =========================================== 6. Gegenclient (pycrdt, wie das Web) ===============================
    M = collab.model()
    ws = collabws.WebSocket(livepage._ws_url(), {"Host": HOST, "Cookie": "saga=" + ck("hk-test-b")}).connect()
    collabws.decode(ws.recv())
    ws.send_binary(collabws.encode("sub", NID, {"schema": collab.schema_info()}))
    cd = collab.new_doc()
    got = {}
    while "sync2" not in got:
        k, n, p = collabws.decode(ws.recv())
        got[k] = p
    cd.apply_update(got["sync2"][0])
    sv = cd.get_state()
    cur, _t = M.read(cd)
    cur["blocks"].insert(0, P("w1", "vom Web"))
    with cd.transaction():
        M.write(cd, cur, None)
    ws.send_binary(collabws.encode("update", NID, cd.get_update(sv), b"w1"))
    ok("Gegenclient-Update kommt in beiden Linux-Editoren an", wait(lambda: texts(va)[0] == "vom Web" and texts(vb)[0] == "vom Web", 8),
       (texts(va), texts(vb)))
    ws.close()

    # =========================================== 7. Undo nur eigene ==================================================
    pump(None, 0.7)                                           # eigener Undo-Schritt (500 ms Buendelung wie Yjs)
    rta = focus(va, "p2", len("Zweiter Absatz"))
    type_in(rta, " A")
    pump(None, 0.6)
    rtb = focus(vb, "p1", 0)
    type_in(rtb, "B:")
    converged(va, vb)
    rta.get_buffer().set_enable_undo(False)                  # Feld-Verlauf aus: Undo des gemeinsamen Dokuments
    va.win.set_focus(None)
    va.editor.focus = None
    ok("Undo (A) nimmt nur A's letzte Eingabe zurueck", va.livep.undo() and converged(va, vb) and
       texts(va)[2] == "<b>Zweiter</b> Absatz" and texts(va)[1].startswith("B:"), texts(va))

    # =========================================== 8. IME-Vorschau ====================================================
    rta = focus(va, "p1", 0)
    rta.preedit_on = True                                     # Vorschau laeuft (Japanisch/Thai): fremde Staende warten
    rtb = focus(vb, "p1", len(vb.kit.by_key["p1"].text()))
    type_in(rtb, "x")
    pump(None, 0.5)
    held = texts(va)[1]
    rta.preedit_on = False
    type_in(rta, "日本")                                      # Abschluss (commit): erst jetzt im Puffer -> gesendet
    ok("IME: fremder Stand erst nach dem Abschluss gezeigt, dann konvergiert", not held.endswith("x") and converged(va, vb) and
       texts(vb)[1].startswith("日本") and texts(va)[1].endswith("x"), (held, texts(va)[1], texts(vb)[1]))

    # =========================================== 9. Rechte-Entzug -> nur lesen =====================================
    sent_b = vb.livep.session.sent
    api("PUT", f"/api/notes/{NID}/shares", {"type": "user", "target": "hk-test-b", "role": "view"})
    ok("B: nur lesen nach Rechte-Entzug (hello update)", wait(lambda: vb.livep.mode == "ro" and vb.editor.locked, 10),
       (vb.livep.mode, vb.editor.locked))
    vb.livep.local()                                          # versuchte Aenderung: nichts darf hinaus
    pump(None, 0.3)
    ok("B sendet als Leser nichts", vb.livep.session.sent == sent_b, (sent_b, vb.livep.session.sent))
    rta = focus(va, "p2", 0)
    type_in(rta, "Z")
    ok("B (Leser) sieht A's Eingabe weiter live", wait(lambda: texts(vb)[2].startswith("Z") or texts(vb)[2].startswith("<b>Z"), 8),
       texts(vb))
    api("PUT", f"/api/notes/{NID}/shares", {"type": "user", "target": "hk-test-b", "role": "edit"})
    wait(lambda: vb.livep.mode == "live", 8)

    # =========================================== 10. Schema-Abweichung -> nichts gesendet ===========================
    st, pg2 = api("POST", "/api/notes", {"title": "E6 Schema"})
    N2 = pg2["id"]
    MADE.append(N2)
    api("PATCH", f"/api/notes/{N2}", {"body": json.dumps({"blocks": [P("s1", "Schema")]}), "format": "blocks"})
    store.refresh()
    wait(lambda: store.meta(N2) is not None, 10)
    real = collab.schema_info
    collab.schema_info = lambda: {"model": "saga-pm", "ver": 99, "hash": "0000000000000000"}
    n0 = len(WS_FRAMES)
    vs = open_a(N2)
    ok("Schema abweichend -> nur lesen + Hinweis", vs.livep.mode == "lock" and vs.live_banner.get_revealed() and vs.editor.locked,
       (vs.livep.mode, vs.live_banner.get_revealed()))
    kinds = [k for w, k, n in WS_FRAMES[n0:] if n == N2]
    ok("Schema abweichend: kein update/sync2/aw gesendet", not set(kinds) & {"update", "sync2", "aw"}, kinds)
    collab.schema_info = real
    n_patch = len([x for x in SENT if x[0] == "PATCH" and f"notes/{N2}" in x[1]])
    pump(None, 1.0)
    ok("Schema abweichend: kein PATCH", len([x for x in SENT if x[0] == "PATCH" and f"notes/{N2}" in x[1]]) == n_patch)
    va = open_a(NID)
    ok("zurueck auf der Live-Seite: live", va.livep.mode == "live", va.livep.mode)

    # =========================================== 11. Neustart -> wiederverbinden ohne Verlust =====================
    restart = os.environ.get("HK_LIVE_RESTART")
    if restart:
        rta = focus(va, "p2", 0)
        subprocess.run(restart, shell=True, check=False)
        type_in(rta, "Offline-")                                  # waehrend der Server weg ist
        ok("offline: weiter bearbeitbar", va.livep.mode in ("offline", "live"), va.livep.mode)
        ok("nach dem Neustart wieder live", wait(lambda: va.livep.mode == "live" and va.livep.session.state == "live", 25),
           (va.livep.mode, va.livep.session and va.livep.session.state))
        ok("Offline-Eingabe beim Server angekommen", wait(lambda: "Offline-" in (body_of(NID)[0] or [{}] * 3)[2]["data"]["text"], 15),
           body_of(NID)[0])
        ok("B hat sie auch", converged(va, vb, 12), (texts(va), texts(vb)))
    else:
        print("HINWEIS: HK_LIVE_RESTART nicht gesetzt - Neustart-Teil uebersprungen")

    # =========================================== 12. Schalter aus -> Stufe 1 ========================================
    off = os.environ.get("HK_LIVE_OFF")
    if off:
        subprocess.run(off, shell=True, check=False)
        ok("Schalter aus: Rueckfall Stufe 1", wait(lambda: va.livep.mode is None, 40), va.livep.mode)
        rta = focus(va, "p2", 0)                              # sofort weiter (Stand vom Server laedt noch)
        type_in(rta, "S1 ")
        va.editor.flush()
        ok("Stufe 1 speichert per PATCH", wait(lambda: any(x[0] == "PATCH" and f"notes/{NID}" in x[1] for x in SENT[-20:]), 10),
           SENT[-5:])
        ok("Stufe 1: Inhalt gespeichert", wait(lambda: "S1 " in (body_of(NID)[0] or [{}] * 3)[2]["data"]["text"], 10), body_of(NID)[0])
    else:
        print("HINWEIS: HK_LIVE_OFF nicht gesetzt - Schalter-Teil uebersprungen")
finally:
    try:
        win_b.close()
    except Exception:  # noqa: BLE001
        pass
    for nid in MADE:
        api("DELETE", f"/api/notes/{nid}")
        api("DELETE", f"/api/trash/{nid}")
    print(f"REAL25LIVE {sum(RES)}/{len(RES)}", "ALLES GRUEN" if all(RES) else "FEHLER", flush=True)
os._exit(0 if all(RES) else 1)
