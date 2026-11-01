"""Gegenprobe Saga 2.5 Chat-Standard (docs/APP-API-CHAT.md 6a, 8, 9) gegen einen echten Saga-Server (kein pytest):
Chat-Zustand der Linux-App (chat.ChatState) fuer drei Konten, Sitzung per signiertem Cookie (wie die Server-Tests).
Prueft: Faehigkeiten, Rechte-Ableitung aus echten Antworten (chatstd.rights), Gruppenbild/Beschreibung (Steuer-Nachrichten,
entschluesselt mit Signaturpruefung), perm_send -> can_send:false + Systemzeile perm, Pins (max 3/409), Admin-Loeschen mit
Grabstein (tombs=1) und Live chat.deleted by_admin, Sperren (block2, members[].blocked, Live chat.blocks), Melden,
Stumm mit Dauer/notify, Anheften (max 5/409), ungelesen markieren + /read loescht, Ausblenden (hidden_count, ?hidden=1),
Erwaehnungen (Token + mentions, Live chat.mention), Formatierung (Darstellung der App), Linkvorschau (unfurl gegen eigenen
Testserver auf 127.0.0.2), Medienuebersicht (has=file), "Fuer mich loeschen" (POST .../hide, Live chat.hidden),
Seiten-Chat-Mitglieder (Hinzufuegen mit Rolle, removable, via).
Umgebung: HK_TEST_BASE, SESSION_SECRET; Server mit SAGA_UNFURL_TEST_PUBLIC=127.0.0.2 und SAGA_MENTION_DELAY=1.
Konten hk-test-a/b/c bzw. HK_STD_USERS=x,y,z (Wegwerf-Datenbank). Fixtures: HK_STD_FIXTURES=<ordner> schreibt die
Antworten als JSON (ohne Schluessel/Chiffrat).
Start: dbus-run-session xvfb-run python3 tests/real25std.py"""
import base64
import http.server
import json
import os
import sys
import tempfile
import threading
import time
import urllib.parse

_HOME = tempfile.mkdtemp(prefix="hk-real25std-")
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
from hlidskjalf import chatstd as K  # noqa: E402
from hlidskjalf.api import APIError, client, sse_events  # noqa: E402

B = os.environ["HK_TEST_BASE"].rstrip("/")
S = TimestampSigner(os.environ["SESSION_SECRET"])
FIX = os.environ.get("HK_STD_FIXTURES")
config.settings.server = B
RES = []


def ok(n, c, info=""):
    RES.append(bool(c))
    print(("OK  " if c else "FAIL"), n, ("  -- " + str(info)[:600]) if (info and not c) else "", flush=True)


def ck(u):
    return S.sign(base64.b64encode(json.dumps({"uid": u, "name": u, "pwc": time.time() + 5}).encode())).decode()


def fixture(name, obj):
    """Antwort als Fixture ablegen (Schluessel, Chiffrat, Signaturen entfernt)"""
    if not FIX:
        return
    def scrub(x):
        if isinstance(x, dict):
            return {k: ("…" if k in ("body_ct", "key", "sig", "keys", "ident_pub", "device", "enc_pub", "sign_pub") else scrub(v))
                    for k, v in x.items()}
        if isinstance(x, list):
            return [scrub(v) for v in x]
        return x
    os.makedirs(FIX, exist_ok=True)
    with open(os.path.join(FIX, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(scrub(obj), f, ensure_ascii=False, indent=1, sort_keys=True)


from hlidskjalf.chat import ChatState, sys_text  # noqa: E402


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

    def call(self, fn, *a, timeout=10, **kw):
        self.use()
        box = {}
        fn(*a, done=lambda r=None: box.setdefault("r", r), error=lambda e: box.setdefault("e", e), **kw)
        pump(lambda: box, timeout)
        if "e" in box:
            return box["e"]
        return box.get("r")

    def chat(self, cid):
        self.use()
        c = client.get(f"api/chats/{cid}?device={self.st.device_id}")["chat"]
        self.st._note_ref(c)
        if self.st.chat(cid) is None:
            self.st.chats.insert(0, c)
        else:
            self.st._merge(c)
        return self.st.chat(cid)

    def send(self, cid, text, mentions=None, **body):
        self.use()
        self.chat(cid)
        b = dict({"v": 1, "type": "text", "text": text}, **body)
        return self.st.send(cid, b, extra={"mentions": mentions} if mentions else None)[0]

    def msgs(self, cid, q=""):
        self.use()
        c = self.chat(cid)
        j = client.get(f"api/chats/{cid}/messages?device={self.st.device_id}&calls=1{q}")
        return [self.st._decode(cid, m, j.get("devices"), j.get("idents"), c) for m in j.get("messages") or []], j

    def wait(self, typ, timeout=6, pred=None):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for i, (ev, d) in enumerate(self.events):
                if ev == typ and (pred is None or pred(d)):
                    self.events.pop(i)
                    return d
            time.sleep(0.1)
        return None


class _Page(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        body = ('<html><head><meta property="og:title" content="Ein Artikel &lt;zum&gt; Test">'
                '<meta property="og:description" content="Kurz und knapp"><meta property="og:site_name" content="Testblatt">'
                '</head><body>x</body></html>').encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def main():
    from hlidskjalf.store import store
    store.check_version()
    pump(lambda: store.has("chatfmt1"), 6)
    want = set(K.ALL)
    ok("Faehigkeiten des Chat-Standards", want <= store.features, sorted(want - store.features))
    ua, ub, uc = (os.environ.get("HK_STD_USERS") or "hk-test-a,hk-test-b,hk-test-c").split(",")
    tag = os.environ.get("HK_STD_TAG", "")
    NA, NB, NC = "Anna Std" + tag, "Bert Std" + tag, "Cora Std" + tag
    a, b, c = Person(ua, NA), Person(ub, NB), Person(uc, NC)

    def ref_of(p, q):
        p.use()
        us = client.get("api/chat/people?q=" + urllib.parse.quote(q)).get("users") or []
        return next((u for u in us if u.get("name") == q), None)
    rb, rc = ref_of(a, NB), ref_of(a, NC)
    ok("Such-Handles (nie Anmeldename)", rb and rc and rb["username"].startswith("~") and "hk-test" not in rb["username"], (rb, rc))
    # --- freie Gruppe A (Admin) + B + C ---
    a.use()
    box = {}
    a.st.new_chat({"kind": "free", "members": [rb["username"], rc["username"]]}, lambda r: box.setdefault("r", r), lambda e: box.setdefault("e", e))
    pump(lambda: box, 8)
    g = (box.get("r") or {}).get("id")
    ok("freie Gruppe angelegt", g, box)
    ca = a.chat(g)
    fixture("chat_free_admin", ca)
    ra = K.rights(ca, store.features)
    ok("A (Admin): info2/perms/pin/moderate/send", ra["info2"] and ra["perms"] and ra["pin"] and ra["moderate"] and ra["send"], ra)
    cb = b.chat(g)
    rbb = K.rights(cb, store.features)
    ok("B (Mitglied): kein perms/moderate, aber pin/info2 (perm_info all)", not rbb["perms"] and not rbb["moderate"] and rbb["pin"] and rbb["info2"], rbb)
    ok("me_ref vorhanden, nie Anmeldename", ca.get("me_ref") and "hk-test" not in ca["me_ref"], ca.get("me_ref"))
    # --- Beschreibung und Gruppenbild ---
    a.use()
    a.st.send(g, {"v": 1, "type": "desc", "desc": "Worum es geht <b>"}, None, "desc")
    import io

    from PIL import Image
    bb = io.BytesIO()
    Image.new("RGB", (300, 200), (10, 120, 200)).save(bb, "PNG")
    from hlidskjalf import chatstdview as SV
    jpeg = SV.square_jpeg(bb.getvalue())
    e = a.st.upload_file(g, jpeg, "group.jpg", "image/jpeg")
    f = dict(e, w=256, h=256)
    a.st.send(g, {"v": 1, "type": "avatar", "file": f}, None, "avatar", [f["id"]])
    cb = b.chat(g)
    fixture("chat_free_desc_avatar", cb)
    ok("desc_msg/avatar_msg im Chat-Objekt", isinstance(cb.get("desc_msg"), dict) and isinstance(cb.get("avatar_msg"), dict), cb.keys())
    b.st._grp_info([cb])
    ok("B: Beschreibung entschluesselt (Signatur geprueft)", cb.get("_desc") == "Worum es geht <b>", cb.get("_desc"))
    ok("B: Gruppenbild-Eintrag entschluesselt", (cb.get("_avf") or {}).get("id") == f["id"], cb.get("_avf"))
    from hlidskjalf import chatmedia
    b.use()
    raw = chatmedia.original(g, cb["_avf"])
    ok("B: Gruppenbild laedt und ist 256x256 JPEG", raw[:2] == b"\xff\xd8" and Image.open(io.BytesIO(raw)).size == (256, 256))
    ms, _j = b.msgs(g)
    ok("Steuer-Nachrichten nicht in GET …/messages", all(m.get("text") != "Worum es geht <b>" for m in ms))
    # --- Rechte: nur Admins schreiben ---
    b.events.clear()
    r = a.call(a.st.patch, g, {"perm_send": "admins"})
    ok("PATCH perm_send admins", isinstance(r, dict) and r.get("perm_send") == "admins", r)
    d = b.wait("chat.changed")
    ok("B: Live chat.changed", d and d.get("chat_id") == g, b.events[-3:])
    cb = b.chat(g)
    ok("B: can_send false -> Eingabe aus (rights.send False)", cb.get("can_send") is False and not K.rights(cb, store.features)["send"], cb.get("can_send"))
    try:
        b.send(g, "darf ich?")
        ok("B senden -> 403", False)
    except Exception as ex:  # noqa: BLE001
        ok("B senden -> 403 mit Meldung", getattr(ex, "code", None) == 403 and str(ex), ex)
    ms, _j = b.msgs(g)
    perm = [m for m in ms if m.get("state") == "sys" and (m.get("sys") or {}).get("type") == "perm"]
    if perm:
        ok("Systemzeile perm (Text der App)", "changed the group permissions" in sys_text(perm[-1]["sys"], cb.get("my_handle")), perm[-1])
    else:
        print("     Systemzeile perm: keine (Chat v1 bekommt keine Systemzeilen)")
    a.call(a.st.patch, g, {"perm_send": "all"})
    # --- Nachrichten, Erwaehnungen, Formatierung ---
    cb = b.chat(g)
    ca = a.chat(g)
    ref_b = K.mem_ref(next(m for m in ca["members"] if m.get("name") == NB), ca)
    ok("Erwaehnungs-Kennung = ref/handle (nie Anmeldename)", ref_b and "hk-test" not in ref_b and ref_b == cb.get("me_ref"), (ref_b, cb.get("me_ref")))
    text, refs = K.apply_people(f"Hallo @{NB}, *wichtig*: <b>x</b>", {"@" + NB: ref_b})
    b.events.clear()
    r1 = a.send(g, text, refs)
    d = b.wait("chat.mention", 8) or b.wait("chat.message", 2)
    ok("B: Live chat.mention bzw. chat.message mit mentions", d and (d.get("mentions") or []) == [ref_b] or (d and d.get("chat_id") == g), d)
    ms, _j = b.msgs(g)
    m1 = next(m for m in ms if m.get("id") == r1["id"])
    ok("B: Token im entschluesselten Text", f"<@{ref_b}>" in m1["text"] and K.mentions_me(cb, m1["text"]), m1["text"])
    mk = K.message_markup(m1["text"], {}, cb, NB)
    ok("Darstellung: eigener Name fett, <b> escaped, *wichtig* fett", f"@{NB}" in mk and "&lt;b&gt;x&lt;/b&gt;" in mk and "<b>wichtig</b>" in mk, mk)
    cb = b.chat(g)
    ok("B: mention_seq gesetzt", int(cb.get("mention_seq") or 0) > 0, cb.get("mention_seq"))
    r2 = a.send(g, "Zweite")
    r3 = b.send(g, "Dritte von B")
    # --- Pins ---
    for mid in (r1["id"], r2["id"], r3["id"]):
        r = a.call(a.st.pin_msg, g, mid, True)
    ok("3 Pins", isinstance(r, list) and len(r) == 3, r)
    r4 = a.send(g, "Vierte")
    r = a.call(a.st.pin_msg, g, r4["id"], True)
    ok("4. Pin -> 409 mit Meldung", isinstance(r, APIError) and r.code == 409 and "3" in str(r), r)
    r = a.call(a.st.pin_msg, g, r3["id"], False)
    ok("Loesen -> pins ohne r3", isinstance(r, list) and r3["id"] not in r, r)
    fixture("chat_free_pins", a.chat(g))
    # --- Admin-Loeschen (A loescht B's Nachricht) ---
    b.events.clear()
    r = a.call(a.st.mod_delete, g, r3["id"])
    ok("A loescht fremde Nachricht (moddel1)", not isinstance(r, APIError), r)
    d = b.wait("chat.deleted", 6, lambda x: x.get("id") == r3["id"])
    ok("B: Live chat.deleted by_admin", d and d.get("by_admin") is True, d)
    ms, j = b.msgs(g, "&tombs=1")
    tm = next((m for m in ms if m.get("id") == r3["id"]), None)
    fixture("messages_tombs", {"messages": [x for x in j.get("messages") or [] if x.get("kind") == "tomb"]})
    ok("tombs=1: Grabstein -> state tomb", tm and tm.get("state") == "tomb", tm)
    ms, _j = b.msgs(g)
    ok("ohne tombs=1: Grabstein fehlt", all(m.get("id") != r3["id"] for m in ms))
    r = b.call(b.st.mod_delete, g, r1["id"])
    ok("B (kein Admin) loescht fremde -> 403", isinstance(r, APIError) and r.code == 403, r)
    # --- Melden ---
    body = K.report_body(g, [m1], "spam", K.report_text([m1], cb, NB))
    r = b.call(b.st.report, body)
    ok("Melden -> {ok, id}", isinstance(r, dict) and r.get("ok"), r)
    own = b.send(g, "eigene von B")
    r = b.call(b.st.report, K.report_body(g, [{"id": own["id"]}], "spam"))
    ok("eigene Nachricht melden -> 400", isinstance(r, APIError) and r.code == 400, r)
    # --- Sperren (B sperrt C) ---
    cb = b.chat(g)
    rc_h = K.mem_ref(next(m for m in cb["members"] if m.get("name") == NC), cb)
    b.events.clear()
    r = b.call(b.st.block, rc_h)
    ok("B sperrt C", isinstance(r, dict) and r.get("ok"), r)
    d = b.wait("chat.blocks", 6)
    ok("B: Live chat.blocks", d and d.get("blocked") is True, d)
    cb = b.chat(g)
    mc = next(m for m in cb["members"] if m.get("name") == NC)
    ok("members[].blocked", mc.get("blocked") is True, mc)
    rcm = c.send(g, "Nachricht von Cora")
    ms, _j = b.msgs(g)
    mcm = next(m for m in ms if m.get("id") == rcm["id"])
    ok("Nachricht der gesperrten Person wird eingeklappt", K.grp_blocked(cb, mcm), mcm.get("sender"))
    bl = b.call(lambda done, error: b.st.load_blocks(done, error))
    fixture("blocks", {"blocks": bl})
    ok("Sperrliste: local, Anzeigename, nie Anmeldename", bl and bl[0].get("local") is True and bl[0].get("name") == NC
       and "hk-test" not in json.dumps(bl), bl)
    r = b.call(b.st.unblock, bl[0]["target"])
    ok("Entsperren", not isinstance(r, APIError), r)
    # --- Alltag: Stumm, Anheften, ungelesen, ausblenden ---
    until = int(time.time()) + 3600
    r = b.call(b.st.patch, g, {"muted_until": until, "notify": "mentions"})
    ok("muted_until + notify", isinstance(r, dict) and r.get("muted_until") == until and r.get("notify") == "mentions" and r.get("muted"), r)
    ok("Anzeige \"Muted until …\"", K.mute_label(r).startswith("Muted until "), K.mute_label(r))
    r = b.call(b.st.patch, g, {"muted_until": -1})
    ok("Fuer immer: -1", isinstance(r, dict) and r.get("muted_until") == -1 and K.mute_label(r) == "Muted", r)
    b.call(b.st.patch, g, {"muted_until": 0, "notify": "all"})
    r = b.call(b.st.patch, g, {"pinned": True})
    ok("Chat anheften", isinstance(r, dict) and r.get("pinned") and r.get("pinned_at"), r)
    b.use()
    lst = client.get(f"api/chats?device={b.st.device_id}")
    fixture("chats_list", lst)
    ok("angeheftet oben", lst["chats"][0]["id"] == g and [c0["id"] for c0 in K.list_split(lst["chats"])][0] == g)
    r = b.call(b.st.patch, g, {"unread_mark": True})
    ok("als ungelesen markieren -> unread >= 1", isinstance(r, dict) and r.get("unread_mark") and r.get("unread", 0) >= 1 and K.unread_dot(r), r)
    b.use()
    b.st.cache[g] = {"msgs": [{"seq": max(m.get("seq") or 0 for m in ms)}], "loaded": True}
    b.st.chats = [b.chat(g)]
    b.st.mark_read(g)
    pump(lambda: not (client.get(f"api/chats/{g}?device={b.st.device_id}")["chat"].get("unread_mark")), 4)
    ok("POST /read loescht die Markierung", not b.chat(g).get("unread_mark"))
    r = b.call(b.st.patch, g, {"hidden": True})
    ok("freie Gruppe ausblenden -> 400 (can_hide false)", isinstance(r, APIError) and r.code == 400 and not K.rights(b.chat(g), store.features)["hide"], r)
    # Seiten-Chat: A teilt Seite, ausblenden bei B
    a.use()
    pg = client.json("POST", "api/notes", {"title": "Std-Testseite" + tag})
    nid = pg.get("id") or (pg.get("note") or {}).get("id")
    box = {}
    a.st.new_chat({"kind": "page", "note_id": nid}, lambda r: box.setdefault("r", r), lambda e: box.setdefault("e", e))
    pump(lambda: box, 8)
    pc = (box.get("r") or {}).get("id")
    cpa = a.chat(pc)
    fixture("chat_page_owner", cpa)
    ok("Seiten-Chat: can_manage auch ungeteilt", cpa.get("can_manage") is True and K.can_add_members(cpa, store.features), cpa.get("can_manage"))
    a.use()
    try:
        j = client.json("POST", f"api/chats/{pc}/members", {"user": rb["username"], "role": "comment"})
        ok("Hinzufuegen mit role comment", any(m.get("name") == NB for m in j["chat"]["members"]), j)
    except APIError as ex:
        ok("Hinzufuegen mit role comment", False, ex)
    a.use()
    sh = client.get(f"api/notes/{nid}/shares")
    ok("Freigabe der Seite mit Rolle comment", "comment" in json.dumps(sh), sh)
    cpa = a.chat(pc)
    mbp = next(m for m in cpa["members"] if m.get("name") == NB)
    ok("removable + via share", mbp.get("removable") is True and mbp.get("via") == "share" and K.page_member_action(cpa, mbp, store.features) == ("remove", None), mbp)
    ok("Hinweistext page (can_manage)", K.members_hint(cpa, store.features).startswith("Adding people shares this page"))
    cpb = b.chat(pc)
    ok("B: ohne can_manage kein via/removable", "via" not in next(m for m in cpb["members"] if m.get("me")) and K.page_member_action(cpb, mbp, store.features) is None)
    r = b.call(b.st.patch, pc, {"hidden": True})
    ok("Seiten-Chat ausblenden (can_hide)", isinstance(r, dict) and r.get("hidden"), r)
    b.use()
    lst = client.get(f"api/chats?device={b.st.device_id}")
    ok("hidden_count = 1, Chat fehlt", lst.get("hidden_count") == 1 and all(x["id"] != pc for x in lst["chats"]), lst.get("hidden_count"))
    hid = b.call(lambda done, error: b.st.load_hidden(done, error))
    ok("?hidden=1 liefert nur ihn", [x["id"] for x in hid or []] == [pc], hid)
    b.call(b.st.patch, pc, {"hidden": False})
    a.use()
    try:
        client.request("DELETE", f"api/chats/{pc}/members/{urllib.parse.quote(mbp['username'], safe='')}")
        ok("Entfernen (direkte Freigabe)", all(m.get("name") != NB for m in a.chat(pc)["members"]))
    except APIError as ex:
        ok("Entfernen (direkte Freigabe)", False, ex)
    # --- Linkvorschau ---
    srv = http.server.ThreadingHTTPServer(("127.0.0.2", 0), _Page)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    url = f"http://127.0.0.2:{srv.server_address[1]}/artikel"
    d = a.call(a.st.unfurl, url)
    ok("unfurl liefert Titel/Beschreibung/Seite", isinstance(d, dict) and d.get("title") == "Ein Artikel <zum> Test" and d.get("site") == "Testblatt", d)
    fixture("unfurl", d)
    pv = K.preview_body(d)
    r5 = a.send(g, "Lies " + url, preview=pv)
    ms, _j = b.msgs(g)
    m5 = next(m for m in ms if m.get("id") == r5["id"])
    ok("B: Vorschau aus der Nachricht (nichts nachladen)", K.preview_of(m5["body"]) and K.preview_of(m5["body"])["title"] == "Ein Artikel <zum> Test",
       m5.get("body"))
    r = a.call(a.st.unfurl, "javascript:alert(1)")
    ok("unfurl ungueltige Adresse -> 400", isinstance(r, APIError) and r.code == 400, r)
    srv.shutdown()
    # --- Medienuebersicht ---
    a.use()
    fobjs, fids = a.st.upload_attachments(g, [("bild.png", "image/png", bb.getvalue()), ("doc.txt", "text/plain", b"hallo")])
    a.st.send(g, {"v": 1, "type": "text", "text": "Anhaenge", "files": fobjs}, file_ids=fids)
    b.use()
    msgs, more, low = b.st.media_page(g, None, True)
    med, fil, _l = K.media_split(msgs)
    ok("has=file: nur Nachrichten mit Anhaengen", msgs and all(m.get("files") for m in msgs), [m.get("text") for m in msgs])
    ok("Medien/Dateien getrennt", [f["n"] for f, _m in med] == ["bild.png"] and [f["n"] for f, _m in fil] == ["doc.txt"], (med, fil))
    allm, _more, _low = b.st.media_page(g, None, False)
    ok("Links aus geladenen Nachrichten", any(u == url for u, _m in K.media_split(allm)[2]))
    # --- Fuer mich loeschen ---
    b.st.cache[g] = {"msgs": ms, "loaded": True}
    b.events.clear()
    r = b.call(b.st.hide_msgs, g, [r2["id"]])
    ok("POST …/hide -> alle Geraete", r is True, r)
    ms, _j = b.msgs(g)
    ok("ausgeblendete fehlen in GET …/messages", all(m.get("id") != r2["id"] for m in ms))
    d = b.wait("chat.hidden", 5)
    ok("Live chat.hidden an eigene Geraete", d and r2["id"] in (d.get("ids") or []), d)
    ms_a, _j = a.msgs(g)
    ok("A sieht sie weiter", any(m.get("id") == r2["id"] for m in ms_a))
    for p in (a, b, c):
        p.stop = True
    print(f"\n{sum(RES)}/{len(RES)} OK")


main()
sys.exit(0 if RES and all(RES) else 1)
