"""Gegenprobe Saga 2.5 E6: Linux-App (Konto hk-test-a, live) <-> Web-Editor (Tiptap/y-tiptap, static/pm-live.js, Konto
hk-test-b im Browser) auf derselben Seite. Gleichzeitig tippen im selben Absatz, Marke aus der App, Block aus der App
einfuegen/loeschen, Web-IME (Komposition), Liste - beide Seiten konvergieren, der Web-Editor bleibt live (kein Rueckfall,
kein Schema-Lock), weder App noch Web speichern den Inhalt per PATCH.
Umgebung wie tests/real25live.py, dazu Node + Playwright (NODE_PATH, PLAYWRIGHT, PW_CHANNEL) und Admin editor.engine =
choice. Start: dbus-run-session xvfb-run python3 tests/real25web.py"""
import json
import os
import subprocess
import sys
import threading
import queue

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("HK_LIVE_NO_MAIN", "1")
import real25live as R  # noqa: E402  (baut App + Fenster A auf, prueft nichts, solange HK_LIVE_NO_MAIN gesetzt ist)

ok, api, pump, P = R.ok, R.api, R.pump, R.P
HERE = os.path.dirname(os.path.abspath(__file__))


class Web:
    def __init__(self):
        env = dict(os.environ, CK=R.ck("hk-test-b"), NO_PROXY="*")
        self.p = subprocess.Popen(["node", os.path.join(HERE, "web_live_driver.js")], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, env=env, text=True, bufsize=1)
        self.q = queue.Queue()
        threading.Thread(target=self._rx, daemon=True).start()
        self.n = 0
        first = self._get(60)
        assert first and first.get("v") == "ready", first

    def _rx(self):
        for line in self.p.stdout:
            try:
                self.q.put(json.loads(line))
            except ValueError:
                pass

    def _get(self, t):
        import time
        end = time.monotonic() + t
        while time.monotonic() < end:
            pump(None, 0.02)                     # Hauptschleife der App laeuft weiter (Live-Updates)
            try:
                return self.q.get_nowait()
            except queue.Empty:
                continue
        return None

    def __call__(self, cmd, **kw):
        self.n += 1
        kw.update(id=self.n, cmd=cmd)
        self.p.stdin.write(json.dumps(kw) + "\n")
        self.p.stdin.flush()
        while True:
            r = self._get(60)
            if r is None or r.get("id") == self.n:
                return r.get("v") if r and r.get("ok") else ("ERR", r)

    def close(self):
        try:
            self("close")
        except Exception:  # noqa: BLE001
            pass
        self.p.wait(timeout=20)


def until(fn, t=8.0):
    import time
    end = time.monotonic() + t
    while time.monotonic() < end:
        try:
            if fn():
                return True
        except Exception:  # noqa: BLE001
            pass
        pump(None, 0.1)
    return False


web = None
try:
    st, pg = api("POST", "/api/notes", {"title": "E6 Web"})
    NID = pg["id"]
    R.MADE.append(NID)
    api("PATCH", f"/api/notes/{NID}", {"body": json.dumps({"blocks": [P("p1", "Hallo"), P("p2", "Zwei"),
         {"id": "l1", "type": "list", "data": {"style": "unordered", "meta": {}, "items": [{"content": "eins", "meta": {}, "items": []}]}}]}),
        "format": "blocks"})
    api("PUT", f"/api/notes/{NID}/shares", {"type": "user", "target": "hk-test-b", "role": "edit"})
    R.store.refresh()
    until(lambda: R.store.meta(NID) is not None, 10)
    va = R.open_a(NID)
    ok("Linux live", va.livep.mode == "live", va.livep.mode)
    web = Web()
    s = web("open", nid=NID)
    ok("Web live (Tiptap)", isinstance(s, dict) and s.get("mode") == "live" and not s.get("ro"), s)
    pump(None, 0.5)
    ok("Bindung: Linux hat nichts gesendet", va.livep.session.sent == 0 and va.livep.session.loaddrops == 0,
       (va.livep.session.sent, va.livep.session.loaddrops))

    # 1) gleichzeitig im selben Absatz
    rta = R.focus(va, "p1", 5)
    th = threading.Thread(target=lambda: None)
    th.start()
    R.type_in(rta, " von Linux")
    w = web("type", bid="p1", pos="start", s="Web: ")
    ok("selber Absatz: beide konvergieren", until(lambda: R.texts(va)[0] == web("txt", bid="p1"), 10) and
       R.texts(va)[0] == "Web: Hallo von Linux", (R.texts(va)[0], web("txt", bid="p1"), w))
    ok("Linux-Schreibmarke blieb hinter der eigenen Eingabe", rta.cursor_offset() == len("Web: Hallo von Linux"), rta.cursor_offset())

    # 2) Marke aus der App
    rta = R.focus(va, "p2", 0)
    buf = rta.get_buffer()
    buf.select_range(buf.get_iter_at_offset(0), buf.get_iter_at_offset(4))
    rta.toggle_format("i")
    ok("Kursiv aus der App erscheint im Web (Marke i)", until(lambda: web("state").get("mode") == "live" and
       R.body_of(NID)[0][1]["data"]["text"] == "<i>Zwei</i>", 10), R.body_of(NID)[0])
    # 3) Block einfuegen/loeschen aus der App
    from hlidskjalf.editor import make
    nb = make("paragraph", "Aus Linux")
    va.editor.insert(nb, after="p2")
    ok("Block aus der App im Web", until(lambda: nb["id"] in (web("blocks") or []), 10), web("blocks"))
    web("type", bid=nb["id"], pos="end", s=" + Web")
    ok("... im Web weitergeschrieben -> Linux", until(lambda: any(b.get("id") == nb["id"] and (b.get("data") or {}).get("text") == "Aus Linux + Web"
                                                                   for b in va.doc.root), 10), R.texts(va))
    va.editor.delete(nb["id"])
    ok("Block in der App geloescht -> Web", until(lambda: nb["id"] not in (web("blocks") or [nb["id"]]), 10), web("blocks"))
    # 4) Web-IME (Komposition), Liste
    web("ime", bid="p2", s="日本")
    ok("Web-IME -> Linux (ganzes Wort)", until(lambda: va.kit.by_key["p2"].text() == "Zwei日本", 10), R.texts(va))
    lrt = va.kit.by_key.get("l1/0")
    lrt.grab_focus()
    b2 = lrt.get_buffer()
    b2.place_cursor(b2.get_end_iter())
    R.type_in(lrt, " (Linux)")
    ok("Listenpunkt aus Linux im Web", until(lambda: R.body_of(NID)[0][2]["data"]["items"][0]["content"] == "eins (Linux)", 10),
       R.body_of(NID)[0])
    s = web("state")
    ok("Web blieb live (kein Rueckfall, kein Schema-Lock, keine Ablehnung)", s.get("mode") == "live" and not s.get("lock") and
       s.get("stats", {}).get("rejects", 0) == 0 and s.get("stats", {}).get("fallbacks", 0) == 0, s)
    ok("Linux: keine Ablehnung, nicht gesperrt", va.livep.mode == "live" and R.collab.Connection and va.livep.session.conn.stats["rejects"] == 0,
       va.livep.session.conn.stats)
    ok("Web speichert den Inhalt nicht per PATCH", web("patches") == 0, web("patches"))
    ok("keine Skriptfehler im Web", web("errs") == [], web("errs"))
    ok("materialisiert = Linux-Ansicht", until(lambda: R.stable([b["data"] for b in R.body_of(NID)[0]]) == R.stable([b["data"] for b in va.doc.root]), 10),
       (R.body_of(NID)[0], va.doc.root))
finally:
    if web is not None:
        web.close()
    for nid in R.MADE:
        api("DELETE", f"/api/notes/{nid}")
        api("DELETE", f"/api/trash/{nid}")
    print(f"REAL25WEB {sum(R.RES)}/{len(R.RES)}", "ALLES GRUEN" if all(R.RES) else "FEHLER", flush=True)
os._exit(0 if all(R.RES) else 1)
