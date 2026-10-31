"""Gegenprobe Saga 2.5 Gruppeninfo (chatadm1) und Kontakte (contacts1) gegen einen echten Saga-Server (kein pytest):
Chat-Zustand der Linux-App (chat.ChatState) fuer drei Konten hk-test-a/b/c, Sitzung per signiertem Cookie (wie die
Server-Tests). Prueft: Faehigkeiten, Felder can_*/admin aus GET /api/chats/{cid} -> gi_rights/gi_actions, Admin vergeben
und entziehen (PATCH .../members/{who}), 409 letzter Admin mit Server-Meldung, 403 ohne Recht, Unterzeilen ohne
Anmeldename, Kontakte (Liste, contact_item, Personensuche contact:true), Hinzufuegen per Kontakt-ref, Aus Kontakten
entfernen + Live chat.contacts an das eigene zweite Geraet.
Umgebung: HK_TEST_BASE, BASE_URL, SESSION_SECRET; Konten hk-test-a/b/c bzw. HK_GI_USERS=x,y,z (je Lauf frische Konten
ohne eingerichtete Chats, Wegwerf-Datenbank).
Start: dbus-run-session xvfb-run python3 tests/real25gi.py"""
import base64
import json
import os
import sys
import tempfile
import threading
import urllib.parse
import time

_HOME = tempfile.mkdtemp(prefix="hk-real25gi-")
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
from hlidskjalf import chatcrypto as C  # noqa: E402
from hlidskjalf.api import APIError, client, sse_events  # noqa: E402

B = os.environ["HK_TEST_BASE"].rstrip("/")
S = TimestampSigner(os.environ["SESSION_SECRET"])
config.settings.server = B
RES = []


def ok(n, c, info=""):
    RES.append(bool(c))
    print(("OK  " if c else "FAIL"), n, ("  -- " + str(info)[:500]) if (info and not c) else "", flush=True)


def ck(u):
    return S.sign(base64.b64encode(json.dumps({"uid": u, "name": u, "pwc": time.time() + 5}).encode())).decode()


from hlidskjalf.chat import (ChatState, contact_item, gi_actions, gi_members, gi_rights, gi_sub,  # noqa: E402
                             contact_keep_for)


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
        self.events, self.stop = [], False
        self.contacts_sig = []
        self.st.connect("contacts", lambda *_: self.contacts_sig.append(1))
        self.reader()

    def reader(self):
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

    def call(self, fn, *a, timeout=8, **kw):
        self.use()
        box = {}
        fn(*a, done=lambda r=None: box.setdefault("r", r), error=lambda e: box.setdefault("e", e), **kw)
        pump(lambda: box, timeout)
        if "e" in box:
            return box["e"]
        return box.get("r")

    def chat(self, cid):
        self.use()
        return client.get(f"api/chats/{cid}?device={self.st.device_id}")["chat"]

    def wait(self, typ, timeout=6):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for i, (ev, d) in enumerate(self.events):
                if ev == typ:
                    self.events.pop(i)
                    self.use()
                    self.st.on_live(ev, d)
                    pump(None, 0.3)
                    return d
            time.sleep(0.1)
        return None


def main():
    from hlidskjalf.store import store
    store.check_version()
    pump(lambda: store.has("chatadm1"), 6)
    ok("Faehigkeiten chatadm1 + contacts1", {"chatadm1", "contacts1"} <= store.features, sorted(store.features))
    ua, ub, uc = (os.environ.get("HK_GI_USERS") or "hk-test-a,hk-test-b,hk-test-c").split(",")
    tag = os.environ.get("HK_GI_TAG", "")                 # eindeutige Anzeigenamen je Lauf (Suche nach Namen)
    NA, NB, NC = "Anna Probe" + tag, "Bert Probe" + tag, "Cora Probe" + tag
    a, b, c = Person(ua, NA), Person(ub, NB), Person(uc, NC)
    a.use()

    def ref_of(p, q):
        p.use()
        us = client.get("api/chat/people?q=" + urllib.parse.quote(q)).get("users") or []
        return next((u for u in us if u.get("name") == q), None)
    rb = ref_of(a, NB)
    rc = ref_of(a, NC)
    ok("Personensuche liefert Such-Handles (nie Anmeldename)", rb and rc and rb["username"].startswith("~") and "hk-test" not in rb["username"],
       (rb, rc))
    if not (rb and rc):
        return
    # freie Gruppe A + B
    a.use()
    box = {}
    a.st.new_chat({"kind": "free", "members": [rb["username"]]}, lambda r: box.setdefault("r", r), lambda e: box.setdefault("e", e))
    pump(lambda: box, 8)
    ch = box.get("r") or {}
    cid = ch.get("id")
    ok("freie Gruppe angelegt", cid, box)
    ca = a.chat(cid)
    ok("A: can_manage/can_admin/can_rename, me_admin", gi_rights(ca) == {"manage": True, "admin": True, "rename": True} and ca.get("me_admin"), ca)
    mb = next(m for m in ca["members"] if not m.get("me"))
    ok("A: Aktionen fuer B = Admin machen, entfernen", gi_actions(ca, mb) == ["admin_on", "remove"], gi_actions(ca, mb))
    subs = [gi_sub(ca, m) for m in gi_members(ca)]
    ok("Unterzeilen ohne Anmeldenamen anderer", not any("hk-test-b" in s for s in subs), subs)
    ok("Sortierung: ich zuerst, Admin", gi_members(ca)[0].get("me") and gi_members(ca)[0].get("admin"), gi_members(ca))
    cb = b.chat(cid)
    ok("B: nur Umbenennen", gi_rights(cb) == {"manage": False, "admin": False, "rename": True}, cb)
    # B ohne Recht -> 403
    r = b.call(b.st.set_admin, cid, next(m for m in cb["members"] if not m.get("me"))["username"], True)
    ok("B ohne Recht: 403 mit Meldung", isinstance(r, APIError) and r.code == 403 and str(r), r)
    # A macht B zum Admin
    b.events.clear()
    r = a.call(a.st.set_admin, cid, mb["username"], True)
    ok("A: B zum Admin (200 mit neuer Liste)", isinstance(r, dict) and any(m.get("admin") for m in r["members"] if not m.get("me")), r)
    ok("A: Zustand der App aktualisiert", any(m.get("admin") for m in a.st.chat(cid)["members"] if not m.get("me")))
    d = b.wait("chat.changed")
    ok("B: Live chat.changed", d and d.get("chat_id") == cid, b.events[-3:])
    cb = b.chat(cid)
    ok("B: jetzt can_admin", gi_rights(cb)["admin"] and cb.get("me_admin"), cb)
    # A entzieht sich selbst (B bleibt) -> ok; B entzieht sich -> 409 letzter Admin
    me_a = next(m for m in ca["members"] if m.get("me"))
    r = a.call(a.st.set_admin, cid, me_a["username"], False)
    ok("A entzieht sich selbst (anderer Admin bleibt)", isinstance(r, dict) and not r.get("me_admin"), r)
    cb = b.chat(cid)
    me_b = next(m for m in cb["members"] if m.get("me"))
    ok("B: Aktion fuer sich = Admin entziehen", gi_actions(cb, me_b) == ["admin_off"], gi_actions(cb, me_b))
    r = b.call(b.st.set_admin, cid, me_b["username"], False)
    ok("B letzter Admin: 409 mit Server-Meldung", isinstance(r, APIError) and r.code == 409 and "admin" in str(r).lower(), r)
    print("     Meldung:", str(r)[:120])
    sysm = [m for m in client.get(f"api/chats/{cid}/messages?device={b.st.device_id}").get("messages") or [] if m.get("kind") == "sys"]
    print("     Systemzeilen (nur Format v2/Stufe 3):", [json.dumps(m.get("sys"))[:80] for m in sysm] or "keine (Chat v1)")
    # Kontakte
    ks = a.call(lambda done, error: a.st.load_contacts(done, error))
    names = [k.get("name") for k in ks or []]
    ok("A: Kontakte enthalten Bert", NB in names, ks)
    kb = next((k for k in ks if k.get("name") == NB), {})
    ok("Kontakt-ref = Such-Handle, Eintrag lesbar", kb.get("ref") == rb["username"] and contact_item(kb)["name"] == NB, kb)
    ok("Filter Mitglied hinzufuegen: Bert schon dabei", not contact_keep_for(a.chat(cid))(kb))
    # A <-> C Direkt-Chat -> Kontakt, dann per Kontakt-ref in die Gruppe (B ist Admin)
    box = {}
    a.use()
    a.st.new_chat({"kind": "dm", "user": rc["username"]}, lambda r: box.setdefault("r", r), lambda e: box.setdefault("e", e))
    pump(lambda: box, 8)
    ks = a.call(lambda done, error: a.st.load_contacts(done, error))
    kc = next((k for k in ks or [] if k.get("name") == NC), None)
    ok("Direkt-Chat macht Cora zum Kontakt (zuletzt zuerst)", kc and ks[0] is kc, [k.get("name") for k in ks or []])
    ok("Filter: Cora anbietbar", kc and contact_keep_for(a.chat(cid))(kc))
    # A ist kein Admin mehr -> erst wieder Admin (durch B), dann hinzufuegen per Kontakt
    b.call(b.st.set_admin, cid, next(m for m in b.chat(cid)["members"] if not m.get("me"))["username"], True)
    a.use()
    try:
        j = client.json("POST", f"api/chats/{cid}/members", {"user": kc["ref"]})
        ok("Hinzufuegen per Kontakt-ref {user: ~…}", len(j["chat"]["members"]) == 3, j)
    except APIError as e:
        ok("Hinzufuegen per Kontakt-ref {user: ~…}", False, e)
    # Personensuche: Kontakt vorne, contact: true (auch kurz)
    a.use()
    pp = client.get("api/chat/people?q=" + urllib.parse.quote("Cora Probe" + tag[:0])).get("users") or []
    ok("Personensuche: Kontakt vorne mit contact:true", pp and pp[0].get("contact") is True and pp[0].get("name") == NC, pp)
    # Aus Kontakten entfernen + Live an das zweite Geraet von A
    a2 = Person.__new__(Person)
    a2.user, a2.jar, a2.events, a2.stop, a2.contacts_sig = ua, {"saga": ck(ua)}, [], False, []
    a2.use()
    a2.st = ChatState()
    a2.st.connect("contacts", lambda *_: a2.contacts_sig.append(1))
    a2.reader()
    time.sleep(1.0)
    r = a.call(a.st.drop_contact, kc["ref"])
    ok("DELETE /api/chat/contacts/{ref} ok", not isinstance(r, APIError), r)
    ks = a.call(lambda done, error: a.st.load_contacts(done, error))
    ok("Cora nicht mehr in der Liste", NC not in [k.get("name") for k in ks or []], ks)
    r = a.call(a.st.drop_contact, kc["ref"])
    ok("nochmal entfernen -> 404", isinstance(r, APIError) and r.code == 404, r)
    d = a2.wait("chat.contacts")
    ok("Live chat.contacts {ref, hidden} -> Signal contacts", d and d.get("hidden") is True and a2.contacts_sig, (d, a2.events[-3:]))
    for p in (a, b, c, a2):
        p.stop = True
    print(f"\n{sum(RES)}/{len(RES)} OK")


main()
sys.exit(0 if RES and all(RES) else 1)
