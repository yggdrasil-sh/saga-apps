"""Gegenprobe Saga 2.6 "Kontakte" (Bugs-DB 2027a-7, docs/APP-API.md "Kontakte (2.6, Bugs-DB 2027a, contacts2)") gegen einen
echten Saga-Server (kein pytest): Adressbuch der Linux-App (contactbook.ContactBook, edit_body, conflict_of) fuer ein Konto mit
Chat-Kontakten, Sitzung per signiertem Cookie (wie die Server-Tests). Prueft: contacts2, Liste ohne Anmeldenamen, PATCH mit
ver (Nummer national mit cc), 409 bei altem ver (Stand im Fehler), doppelte Nummer -> 409 contact_id ohne Wert, Neu ohne
Landesvorwahl -> 400, Landesvorwahl setzen, Suche nach Ziffern, contact_id in /api/chat/contacts, Live contacts.changed,
Loeschen (Chat-Vorschlag ausgeblendet).
Umgebung: HK_TEST_BASE, SESSION_SECRET; Konto HK_BOOK_USER (Standard hk-test-a) mit Chat-Kontakten, deren Anzeigename
HK_BOOK_PEER enthaelt (Standard "Berta"). Wegwerf-Datenbank.
Start: dbus-run-session xvfb-run python3 tests/realkontakte2.py"""
import base64
import json
import os
import sys
import tempfile
import threading
import time

_HOME = tempfile.mkdtemp(prefix="hk-realbook-")
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
from hlidskjalf import contactbook as CB  # noqa: E402
from hlidskjalf.api import client, sse_events  # noqa: E402
from hlidskjalf.chat import contact_id_of  # noqa: E402

B = os.environ["HK_TEST_BASE"].rstrip("/")
SIG = TimestampSigner(os.environ["SESSION_SECRET"])
USER = os.environ.get("HK_BOOK_USER", "hk-test-a")
PEER = os.environ.get("HK_BOOK_PEER", "Berta")
config.settings.server = B
RES = []


def ok(n, c, info=""):
    RES.append(bool(c))
    print(("OK  " if c else "FAIL"), n, ("  -- " + str(info)[:400]) if (info and not c) else "", flush=True)


def ck(u):
    return SIG.sign(base64.b64encode(json.dumps({"uid": u, "name": u, "pwc": time.time() + 5}).encode())).decode()


def call(fn, *a):
    """ContactBook-Aufruf mit Rueckrufen -> ("ok", wert) | ("err", fehler)"""
    box = {}
    fn(*a, lambda *r: box.setdefault("r", ("ok", r[0] if r else None)), lambda e: box.setdefault("r", ("err", e)))
    pump(lambda: box, 8)
    return box.get("r", ("timeout", None))


def lst(q=""):
    box = {}
    CB.book.list(q, lambda cs, total: box.setdefault("r", cs))
    pump(lambda: box, 8)
    return box.get("r") or []


def get(cid):
    box = {}
    CB.book.get(cid, lambda c: box.setdefault("r", c))
    pump(lambda: "r" in box, 8)
    return box.get("r")


def main():
    client.set_cookies(B, {"saga": ck(USER)})
    events, stop = [], []
    resp = client.stream("GET", "api/events", timeout=60)

    def run():
        try:
            for ev, data, _eid in sse_events(resp, stop=lambda: bool(stop)):
                events.append((ev, data))
        except Exception:  # noqa: BLE001
            pass
    threading.Thread(target=run, daemon=True).start()
    time.sleep(0.5)

    from hlidskjalf.store import store
    store.check_version()
    pump(lambda: store.has("contacts2"), 6)
    ok("contacts2 in /api/version -> App bietet das Adressbuch an", CB.has_feature(), sorted(store.features))
    cs = lst()
    ok("Liste: Chat-Kontakte vorhanden", len(cs) >= 1, len(cs))
    users = [USER] + [u for u in os.environ.get("HK_BOOK_OTHERS", "hk-test-b,hk-test-c").split(",") if u]
    ok("Liste: kein Anmeldename in den Kontakten", not any(u in json.dumps(cs) for u in users), json.dumps(cs)[:300])
    peer = next((c for c in cs if PEER in (c.get("name") or "")), None)
    ok("Chat-Kontakt mit fester Saga-Adresse", peer and CB.saga_of(peer) and peer["origin"] == "chat"
       and CB.addrs_of(peer, fixed=True)[0]["kind"] == "saga", peer)
    if not peer:
        return
    # Bearbeiten mit ver; nationale Nummer mit cc der Anfrage (Konto ohne Landesvorwahl)
    call(CB.book.put_cc, None)
    events.clear()
    b = CB.edit_body("Bertie", "formal", "Sehr geehrte Frau Beispiel", [("e164", "0170 555-0123", "mobile"), ("email", "b@Example.ORG", "work")],
                     ver=peer["ver"], cc="49")
    ok("Anfrage: cc nur wegen der nationalen Nummer", b.get("cc") == "49" and b["ver"] == peer["ver"], b)
    st, c = call(CB.book.update, peer["id"], b)
    ok("PATCH: Nummer als E.164, Domain klein, feste Adresse bleibt", st == "ok" and
       [a["value"] for a in CB.addrs_of(c, fixed=False)] == ["+491705550123", "b@example.org"] and CB.saga_of(c)
       and c["own_name"] == "Bertie" and c["form"] == "formal", c)
    ok("Detail: tel:/mailto:", [CB.addr_href(a) for a in CB.addrs_of(c, fixed=False)] == ["tel:+491705550123", "mailto:b@example.org"])
    pump(lambda: any(ev == "contacts.changed" for ev, _d in events), 5)
    ok("Live contacts.changed {id} an die eigenen Verbindungen",
       any(ev == "contacts.changed" and (d or {}).get("id") == peer["id"] for ev, d in events), events[-3:])
    # veraltetes ver -> 409 mit aktuellem Kontakt
    st, e = call(CB.book.update, peer["id"], dict(b, ver=peer["ver"], salutation="Hallo"))
    k = CB.conflict_of(e) if st == "err" else None
    ok("409 bei altem ver -> Stand im Fehler (conflict_of changed)", k and k[0] == "changed" and k[1]["ver"] == c["ver"], (st, e))
    # doppelte Nummer bei einem neuen Kontakt -> 409 contact_id, Wert nicht in der Meldung
    st, e = call(CB.book.create, CB.edit_body("Doppelt", None, "", [("e164", "+49 170 5550123", None)], new=True))
    k = CB.conflict_of(e) if st == "err" else None
    ok("doppelte Nummer -> 409 contact_id (Sprung zum Kontakt)", k == ("duplicate", peer["id"]), (st, e, getattr(e, "data", None)))
    ok("Meldung ohne die Nummer", st == "err" and "5550123" not in str(e), str(e))
    # Neu: nationale Nummer ohne Landesvorwahl -> 400; mit Konto-Landesvorwahl klappt es
    st, e = call(CB.book.create, CB.edit_body("Neu Kontakt", None, "", [("e164", "030 5550987", "home")], new=True))
    ok("nationale Nummer ohne Landesvorwahl -> 400", st == "err" and getattr(e, "code", 0) == 400 and "5550987" not in str(e), (st, e))
    st, cc = call(CB.book.put_cc, "+49")
    ok("Landesvorwahl setzen (+49 -> 49)", st == "ok" and cc == "49" and CB.book.cc == "49", (st, cc))
    st, n = call(CB.book.create, CB.edit_body("Neu Kontakt", None, "", [("e164", "030 5550987", "home")], new=True))
    ok("Neu: manuell, Nummer mit Konto-Landesvorwahl", st == "ok" and n["origin"] == "manual" and CB.saga_of(n) is None
       and CB.addrs_of(n)[0]["value"] == "+49305550987", (st, n))
    ok("Suche nach Ziffern (030 555)", any(x["id"] == n["id"] for x in lst("030 555")) if st == "ok" else False)
    ok("Suche nach Name", [x["id"] for x in lst("Bertie")] == [peer["id"]])
    # contact_id in /api/chat/contacts
    chc = client.get("api/chat/contacts?limit=50").get("contacts") or []
    ok("contact_id in /api/chat/contacts", any(contact_id_of(x) == peer["id"] for x in chc), chc[:2])
    # Loeschen: manueller weg; Chat-Kontakt mit Daten -> raus aus den Chat-Vorschlaegen
    if st == "ok":
        st2, _r = call(CB.book.delete, n["id"])
        ok("Loeschen: manueller Kontakt weg (404)", st2 == "ok" and get(n["id"]) is None)
    ref = CB.saga_of(c)["ref"]
    st2, _r = call(CB.book.delete, peer["id"])
    chc = client.get("api/chat/contacts?limit=50").get("contacts") or []
    ok("Loeschen: Saga-Adresse aus den Chat-Vorschlaegen", st2 == "ok" and all(x.get("ref") != ref for x in chc))
    call(CB.book.put_cc, None)
    stop.append(1)


if __name__ == "__main__":
    try:
        main()
    finally:
        print(f"\nREALKONTAKTE2 {sum(RES)}/{len(RES)} - " + ("ALLES GRUEN" if RES and all(RES) else "FEHLER"), flush=True)
    os._exit(0 if RES and all(RES) else 1)
