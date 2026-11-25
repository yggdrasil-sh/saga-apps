"""Gegenprobe Saga 2.6 "Eigener Status" (Bugs-DB 2029-8, docs/APP-API.md "Eigener Status" bis "Anrufe und eigener Status")
gegen einen echten Saga-Server (kein pytest): Status der Linux-App (userstatus.UserStatus) und Abo des Online-Status
(chat.ChatState) fuer zwei Konten, Sitzung per signiertem Cookie (wie die Server-Tests). Prueft: userstatus1, PUT mit ver
(409 bei altem ver -> Ansicht uebernommen), zweites Konto sieht den Status im Presence-Abo (mit Text, Ende im 5-Minuten-
Raster), Live chat.status, Fokus-PUT ohne Text (Text verworfen, kept bei Fokus-Ende), Erreichbarkeit + Ausnahmen,
waiting1 in /api/calls/config und PUT /api/calls/caps.
Umgebung: HK_TEST_BASE, SESSION_SECRET; Konten hk-test-a/b bzw. HK_STATUS_USERS=x,y (Wegwerf-Datenbank, Chats noch nicht
eingerichtet).
Start: dbus-run-session xvfb-run python3 tests/realstatus2029.py"""
import base64
import json
import os
import sys
import tempfile
import threading
import time
import urllib.parse

_HOME = tempfile.mkdtemp(prefix="hk-realstatus-")
for k, sub in (("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache"), ("XDG_DATA_HOME", "data")):
    os.environ[k] = os.path.join(_HOME, sub)
os.environ["HK_NO_KEYRING"] = "1"
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from itsdangerous import TimestampSigner  # noqa: E402

import gi  # noqa: E402
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from hk_helpers import pump  # noqa: E402
from hlidskjalf import config  # noqa: E402
from hlidskjalf import calls as CL  # noqa: E402
from hlidskjalf import chatcrypto as C  # noqa: E402
from hlidskjalf import chatstatus as S  # noqa: E402
from hlidskjalf.api import APIError, client, sse_events  # noqa: E402

B = os.environ["HK_TEST_BASE"].rstrip("/")
SIG = TimestampSigner(os.environ["SESSION_SECRET"])
config.settings.server = B
RES = []


def ok(n, c, info=""):
    RES.append(bool(c))
    print(("OK  " if c else "FAIL"), n, ("  -- " + str(info)[:600]) if (info and not c) else "", flush=True)


def ck(u):
    return SIG.sign(base64.b64encode(json.dumps({"uid": u, "name": u, "pwc": time.time() + 5}).encode())).decode()


from hlidskjalf.chat import ChatState  # noqa: E402
from hlidskjalf.userstatus import UserStatus  # noqa: E402

UserStatus.available = staticmethod(lambda: True)       # je Person eigener Stand (der globale Chat-Zustand bleibt leer)


class Person:
    def __init__(self, user, display):
        self.user = user
        self.jar = {"saga": ck(user)}
        self.use()
        try:
            client.json("PATCH", "api/account", {"name": display})
        except APIError:
            pass
        self.st = ChatState()
        self.st.me = client.get("api/chat/me")
        k = self.st.setup(C.recovery_key())
        self.st.keys, self.st.status = k, "ready"
        self.st.me = client.get("api/chat/me?device=" + k["device"]["id"])
        self.us = UserStatus()
        self.events, self.stop = [], False
        resp = client.stream("GET", "api/events", timeout=60)

        def run():
            try:
                for ev, data, _eid in sse_events(resp, stop=lambda: self.stop):
                    self.events.append((ev, data))
            except Exception:  # noqa: BLE001
                pass
        threading.Thread(target=run, daemon=True).start()

    def use(self):
        client.set_cookies(B, self.jar)

    def put(self, fn, body, timeout=8):
        """UserStatus.set/prefs -> (view, conflict) oder Fehler"""
        self.use()
        box = {}
        fn(body, lambda v, c: box.setdefault("r", (v, c)), lambda e: box.setdefault("e", e))
        pump(lambda: box, timeout)
        return box.get("r", box.get("e"))

    def wait(self, typ, timeout=6, pred=None):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for i, (ev, d) in enumerate(self.events):
                if ev == typ and (pred is None or pred(d)):
                    self.events.pop(i)
                    return d
            time.sleep(0.1)
        return None


def main():
    ver = client.get("api/version")
    ok("userstatus1 in /api/version", "userstatus1" in (ver.get("features") or []), ver.get("features"))
    tag = os.environ.get("HK_STATUS_TAG", "")
    NA, NB = "Alex Beispiel" + tag, "Bruno Beispiel" + tag
    ua_, ub_ = (os.environ.get("HK_STATUS_USERS") or "hk-test-a,hk-test-b").split(",")
    a, b = Person(ua_, NA), Person(ub_, NB)
    ok("userstatus1 in /api/chat/me", S.has_userstatus(a.st.me) and S.presence_on(b.st.me), a.st.me.get("features"))

    def ref_of(p, q):
        p.use()
        us = client.get("api/chat/people?q=" + urllib.parse.quote(q)).get("users") or []
        return next((u for u in us if u.get("name") == q), None)
    ra, rb = ref_of(b, NA), ref_of(a, NB)
    ok("Such-Handles", ra and rb and ra["username"].startswith("~"), (ra, rb))
    a.use()
    box = {}
    a.st.new_chat({"kind": "free", "members": [rb["username"]]}, lambda r: box.setdefault("r", r), lambda e: box.setdefault("e", e))
    pump(lambda: box, 8)
    gid = (box.get("r") or {}).get("id")
    ok("gemeinsamer Chat angelegt", gid, box)
    b.use()                                              # Kennung von A, wie B sie im Chat sieht (members[].username)
    mem = client.get(f"api/chats/{gid}?device={b.st.device_id}")["chat"].get("members") or []
    ua = next((m.get("username") for m in mem if not m.get("me") and m.get("username")), None)
    ok("A in B's Chat", ua, mem)
    ra = dict(ra, username=ua)
    # --- eigener Status: laden, setzen mit ver, 409 ---
    a.use()
    a.us.load()
    pump(lambda: a.us.ok, 6)
    v0 = a.us.view
    ok("GET /api/account/status: Ansicht", S.view_ok(v0) and v0.get("prefs") and v0.get("limits", {}).get("text") == 80, v0)
    if v0 and v0.get("state") != "available":
        a.put(a.us.set, {"state": "available"})
        v0 = a.us.view
    r = a.put(a.us.set, S.status_body("dnd", "h1", "Im Kundentermin", a.us.view))
    v1 = a.us.view
    ok("PUT dnd 1 h mit Text", isinstance(r, tuple) and not r[1] and v1["state"] == "dnd" and v1["text"] == "Im Kundentermin"
       and v1["ver"] > v0["ver"] and v1.get("effective") == "dnd" and abs(v1["until"] - time.time() - 3600) < 120, r)
    old = dict(v0)
    a.use()
    r = a.put(a.us.set, {"state": "busy", "ver": old["ver"]})
    ok("409 bei altem ver -> Ansicht uebernommen (conflict)", isinstance(r, tuple) and r[1] and a.us.view["state"] == "dnd"
       and a.us.view["ver"] == v1["ver"], r)
    a.use()
    try:
        client.json("PUT", "api/account/status", {"state": "busy", "ver": old["ver"]})
        ok("409 direkt", False, "kein Fehler")
    except APIError as e:
        ok("409 direkt mit Ansicht + error", e.code == 409 and isinstance(e.data, dict) and S.view_ok(e.data) and e.data.get("error"), e.data)
    # --- zweites Konto: Presence-Abo mit status ---
    b.use()
    b.st._pres_src["t"] = lambda: [ra["username"]]
    b.st.presence_sync()
    pump(lambda: b.st.ustat.get(ra["username"]), 8)
    st = b.st.ustat.get(ra["username"]) or {}
    ok("B sieht A: dnd mit Text, Ende im 5-Minuten-Raster", st.get("s") == "dnd" and st.get("text") == "Im Kundentermin"
       and st.get("until") and int(st["until"]) % 300 == 0 and st["until"] >= v1["until"], (st, b.st.ustat))
    ok("Anzeige bei B", S.peer_text(st, True).startswith("Do not disturb until ") and S.peer_text(st, True).endswith(" · Im Kundentermin"),
       S.peer_text(st, True))
    # Live chat.status bei Aenderung
    b.events.clear()
    r = a.put(a.us.set, S.status_body("busy", "off", "", a.us.view))
    d = b.wait("chat.status", 8, lambda d: d.get("username") == ra["username"])
    ok("Live chat.status an B (busy, ohne Text)", d and (d.get("status") or {}).get("s") == "busy" and not (d.get("status") or {}).get("text"), d)
    if d:
        b.st._pres_users = [ra["username"]]
        b.st._status_live(d)
    ok("B: ChatState uebernimmt chat.status", (b.st.ustat.get(ra["username"]) or {}).get("s") == "busy", b.st.ustat)
    # --- Fokus-PUT ohne Text: der Server verwirft einen Text, Fokus-Ende haelt den manuellen Status ---
    a.use()
    j = client.json("PUT", "api/account/status", {"state": "dnd", "source": "focus", "text": "Fokusname", "ver": a.us.view["ver"]})
    ok("Fokus-PUT: Text verworfen, source focus", j.get("source") == "focus" and not j.get("text") and j.get("state") == "dnd", j)
    a.us.take(j)
    ok("Hinweis vom Geraet (Fokus)", "(Focus)" in (S.shown(j) or {}).get("hint", ""), S.shown(j))
    b.use()
    pres = client.json("POST", "api/chat/presence", {"users": [ra["username"]]})
    sa = (pres.get("status") or {}).get(ra["username"]) or {}
    ok("B sieht Fokus-dnd ohne Text", sa.get("s") == "dnd" and "text" not in sa, pres)
    a.put(a.us.set, S.status_body("busy", "off", "Mittag", a.us.view))
    a.use()
    j = client.json("PUT", "api/account/status", {"state": "available", "source": "focus"})
    ok("Fokus-Ende haelt manuellen Status (kept)", j.get("kept") is True and j.get("state") == "busy", j)
    # --- Erreichbarkeit und Ausnahmen ---
    r = a.put(a.us.prefs, {"in_call": "busy", "exceptions": [{"type": "user", "ref": rb["username"]}]})
    v = a.us.view
    ok("prefs + Ausnahme (mit Name zurueck)", isinstance(r, tuple) and v["prefs"]["in_call"] == "busy"
       and [x.get("ref") for x in v["exceptions"]] == [rb["username"]] and v["exceptions"][0].get("name"), v.get("exceptions"))
    a.put(a.us.prefs, {"in_call": "knock", "exceptions": []})
    ok("Ausnahmen leeren", a.us.view["exceptions"] == [] and a.us.view["prefs"]["in_call"] == "knock")
    # --- Anrufe: waiting1 ---
    a.use()
    cfg = client.get("api/calls/config")
    if cfg.get("enabled"):
        caps = CL.device_caps(cfg)
        ok("waiting1 in /api/calls/config und den caps der App", "waiting1" in (cfg.get("features") or []) and "waiting1" in caps, (cfg.get("features"), caps))
        j = client.json("PUT", "api/calls/caps", {"device": a.st.device_id, "caps": caps})
        ok("PUT /api/calls/caps mit waiting1", j.get("ok") is True, j)
    else:
        print("INFO Anrufe auf dem Testserver aus - waiting1 nicht geprueft", flush=True)
    a.put(a.us.set, {"state": "available"})
    ok("zurueck auf Verfuegbar", a.us.view["state"] == "available" and a.us.view["text"] is None, a.us.view)
    for p in (a, b):
        p.stop = True
    print(f"\n{sum(RES)}/{len(RES)} OK")


main()
sys.exit(0 if RES and all(RES) else 1)
