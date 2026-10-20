"""Gelesen-Bestaetigungen und Online-Status (Saga 2.4) gegen den nachgebauten Server (tests/mockv24.py): Haken ⏳ -> ✓ -> ✓✓,
Gruppen mit Zaehler und "Gelesen von", Senden nur bei sichtbarer Nachricht (gebuendelt, hoechstens eine je 2 s), versiegelt
und signiert wie im Vertrag, ungueltige Bestaetigungen ignoriert, Gegenseitigkeit (eigene Einstellung aus), Abo des
Online-Status (nur Sichtbares, erneuern, abmelden), aeltere Server (alles ausgeblendet), Oberflaeche."""
import json
import time

from gi.repository import Adw, Gtk

from hk_helpers import idle, pump
from hlidskjalf import chatcrypto as C
from hlidskjalf import chatstatus as S


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, (Gtk.Label, Gtk.Button)) and x.get_label()]


def _ready(srv):
    from hlidskjalf.chat import chat
    chat._pres_src.clear()                                  # Ansichten frueherer Tests (Fenster bleiben als Objekt)
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    k = chat.setup(C.recovery_key())
    chat.adopt_keys(k)
    pump(lambda: chat.status == "ready" and chat.me and chat.me.get("identity"), 5)
    idle()


def _open(srv, cid):
    from hlidskjalf.chat import chat
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    chat.load_messages(cid)
    pump(lambda: chat.cache.get(cid, {}).get("loaded"), 4)
    chat.open_chat = cid


def _receipts(srv, cid):
    return [b for b in srv.read_log if "receipt" in b]


def _my_msg(srv, cid, text):
    """eigene Nachricht ueber die App senden -> Server-Eintrag"""
    from hlidskjalf.chat import chat
    from hlidskjalf.util import bg
    out = []
    bg(chat.send, cid, {"v": 1, "type": "text", "text": text}, done=out.append)
    pump(lambda: out, 5)
    chat.load_messages(cid)
    pump(lambda: any(m["id"] == out[0][0]["id"] for m in chat.cache[cid]["msgs"]), 4)
    return next(m for m in srv.chats[cid]["msgs"] if m["id"] == out[0][0]["id"])


def _cmsg(cid, mid):
    from hlidskjalf.chat import chat
    return next(m for m in chat.cache[cid]["msgs"] if m["id"] == mid)


# --- Regeln (ohne Netz) ---------------------------------------------------------------------------------------------------
def test_rules_ticks_want_presence_text():
    me = {"features": ["receipts", "online"], "privacy": {"receipts": True, "online": True}}
    dm = {"kind": "dm", "members": [{"username": "test", "me": True}, {"username": "anna", "name": "Anna"}],
          "peer": {"username": "anna"}}
    mine = {"id": "a" * 32, "seq": 5, "mine": True, "state": "ok"}
    theirs = {"id": "b" * 32, "seq": 6, "mine": False, "state": "ok"}
    assert S.tick(me, dm, mine, {}) == ("sent", 0, False)
    assert S.tick(me, dm, mine, {"anna": {"n": 4}}) == ("sent", 0, False)
    assert S.tick(me, dm, mine, {"anna": {"n": 5, "ts": 1}}) == ("read", 1, True)
    assert S.tick(me, dm, dict(mine, fstate="pending"), {}) == "pending"
    assert S.tick(me, dm, theirs, {}) is None and S.tick(me, dm, dict(mine, state="call"), {}) is None
    assert S.tick(me, dm, dict(mine, ai_state="ok"), {}) is None           # KI-Antwort auf meine Frage: nicht meine
    # gegenseitig: eigene Einstellung aus -> nur ✓; aelterer Server -> gar keine Haken
    off = dict(me, privacy={"receipts": False, "online": True})
    assert S.tick(off, dm, mine, {"anna": {"n": 9}}) == ("sent", 0, False)
    assert S.tick({}, dm, mine, {"anna": {"n": 9}}) is None
    # Gruppe: Zaehler, alle
    grp = dict(dm, kind="free", members=dm["members"] + [{"username": "bert", "name": "Bert"}])
    assert S.tick(me, grp, mine, {"anna": {"n": 5}}) == ("read", 1, False)
    assert S.tick(me, grp, mine, {"anna": {"n": 5}, "bert": {"n": 7}}) == ("read", 2, True)
    assert S.tick_text(("read", 1, False), True)[1] == "Read by 1 person"
    assert [r["user"] for r in S.readers(grp, mine, {"anna": {"n": 5}, "bert": {"n": 2}})] == ["anna"]
    # zu bestaetigen: neueste fremde Nachricht mit Position > zuletzt bestaetigt
    assert S.want(me, dm, [theirs, mine], 0) == {"id": theirs["id"], "n": 6}
    assert S.want(me, dm, [theirs, mine], 6) is None
    assert S.want(off, dm, [theirs], 0) is None
    fed = dict(dm, v=2, request=True)
    assert S.want(me, fed, [dict(theirs, fseq=3)], 0) is None                # Nachrichtenanfrage
    fed = dict(dm, v=2, receipts_ok=False)
    assert S.want(me, fed, [dict(theirs, fseq=3)], 0) is None                # anderer Server 2.3
    assert S.want(me, dict(dm, v=2), [dict(theirs, fseq=3)], 0) == {"id": theirs["id"], "n": 3}    # v2: fseq zaehlt
    # Online
    assert S.presence_user(me, dm) == "anna" and S.presence_user(me, grp) is None
    assert S.presence_user(dict(me, privacy={"online": False}), dm) is None
    assert S.presence_user(me, dict(dm, v=2, online_ok=False)) is None
    assert S.presence_text({"online": True}) == "online"
    now = 1792449900.0
    assert S.presence_text({"online": False, "last": now - 300}, now) == "last online 5 minutes ago"
    assert S.presence_text({"online": False, "last": now}, now) == "last online 1 minute ago"      # Raster: nie "jetzt"
    assert S.presence_text({"online": False, "last": None}) == ""


# --- Haken an eigenen Nachrichten ------------------------------------------------------------------------------------------
def test_ticks_from_receipts_and_checks(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    _open(srv, cid)
    sm = _my_msg(srv, cid, "Hallo Anna")
    c = chat.chat(cid)
    assert S.tick(chat.me, c, _cmsg(cid, sm["id"]), chat.rcpts.get(cid)) == ("sent", 0, False)
    # gefaelschte Bestaetigung (anderer Chat signiert) und fremdes Geraet: ignoriert
    srv.peer_receipt(cid, "anna", sm, live=False, forge=True)
    chat.load_receipts(cid)
    pump(None, 0.5)
    assert not (chat.rcpts.get(cid) or {}).get("anna")
    srv.peer_receipt(cid, "anna", sm, live=False, dev=C.device())
    chat.load_receipts(cid)
    pump(None, 0.5)
    assert not (chat.rcpts.get(cid) or {}).get("anna")
    # echte Bestaetigung beim Oeffnen
    srv.peer_receipt(cid, "anna", sm, live=False, ts=1792444800000)
    chat.load_receipts(cid)
    pump(lambda: (chat.rcpts.get(cid) or {}).get("anna"), 4)
    assert chat.rcpts[cid]["anna"]["n"] == sm["seq"] and chat.rcpts[cid]["anna"]["ts"] == 1792444800000
    assert S.tick(chat.me, c, _cmsg(cid, sm["id"]), chat.rcpts[cid]) == ("read", 1, True)
    # neue eigene Nachricht: wieder ✓, live gelesen -> ✓✓; aeltere Bestaetigung danach senkt nichts
    sm2 = _my_msg(srv, cid, "Noch etwas")
    assert S.tick(chat.me, c, _cmsg(cid, sm2["id"]), chat.rcpts[cid])[0] == "sent"
    seen = []
    h = chat.connect("receipts", lambda o, x: seen.append(x))
    sealed = srv.peer_receipt(cid, "anna", sm2, live=False)
    chat.on_live("chat.receipt", {"chat_id": cid, "receipts": [{"from": "anna", "sealed": sealed}]})
    pump(lambda: seen, 4)
    old = srv.peer_receipt(cid, "anna", sm, live=False)
    chat.on_live("chat.receipt", {"chat_id": cid, "receipts": [{"from": "anna", "sealed": old}]})
    pump(None, 0.4)
    chat.disconnect(h)
    assert chat.rcpts[cid]["anna"]["n"] == sm2["seq"]
    assert S.tick(chat.me, c, _cmsg(cid, sm2["id"]), chat.rcpts[cid]) == ("read", 1, True)
    # "more" (grosse Gruppe): Stand per GET
    n0 = sum(1 for m, u, b, k in srv.log if u.endswith(f"api/chats/{cid}/receipts"))
    chat.on_live("chat.receipt", {"chat_id": cid, "receipts": [], "more": True})
    pump(lambda: sum(1 for m, u, b, k in srv.log if u.endswith(f"api/chats/{cid}/receipts")) > n0, 3)
    # ⏳ wartend (2.3) bleibt ⏳
    m = dict(_cmsg(cid, sm2["id"]), fstate="pending")
    assert S.tick(chat.me, c, m, chat.rcpts[cid]) == "pending"


def test_group_counter_and_read_by(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    srv.chat_peer("bert", "Bert Beispiel")
    srv.chat_peer("cora", "Cora Muster", ready=False)
    cid = srv.new_chat("free", ["test", "anna", "bert"], admin="test")
    _open(srv, cid)
    sm = _my_msg(srv, cid, "Hallo zusammen")
    srv.peer_receipt(cid, "anna", sm, live=False, ts=1792444800000)
    chat.load_receipts(cid)
    pump(lambda: (chat.rcpts.get(cid) or {}).get("anna"), 4)
    c = chat.chat(cid)
    assert S.tick(chat.me, c, _cmsg(cid, sm["id"]), chat.rcpts[cid]) == ("read", 1, False)
    srv.peer_receipt(cid, "bert", sm, live=False, ts=1792444900000)
    chat.load_receipts(cid)
    pump(lambda: (chat.rcpts.get(cid) or {}).get("bert"), 4)
    assert S.tick(chat.me, c, _cmsg(cid, sm["id"]), chat.rcpts[cid]) == ("read", 2, True)
    # Oberflaeche: Haken-Knopf -> "Gelesen von" (neueste zuerst, Hinweis)
    win.show_route(("chat", cid))
    conv = win.current.conv
    pump(lambda: conv.loaded and sm["id"] in conv.ticks, 5)
    pump(lambda: chat.rcpts.get(cid, {}).get("bert"), 3)
    pump(lambda: isinstance(conv.ticks[sm["id"]][2], tuple) and conv.ticks[sm["id"]][2][0] == "read", 3)
    btn = [w for w in _walk(conv.ticks[sm["id"]][0]) if isinstance(w, Gtk.Button)]
    assert btn and btn[0].get_label() == "✓✓" and btn[0].get_tooltip_text() == "Read by everyone"
    d = conv.read_by(_cmsg(cid, sm["id"]))
    pump(None, 0.3)
    rows = [w for w in _walk(d) if isinstance(w, Adw.ActionRow)]
    assert [r.get_title() for r in rows] == ["Bert Beispiel", "Anna Beispiel"]
    assert "turned off read receipts" in d.get_body()
    d.force_close()


# --- Senden: nur sichtbar, gebuendelt, versiegelt ------------------------------------------------------------------------
def test_send_receipt_visible_gap_and_crypto(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    srv.chat_peer("bert", "Bert Beispiel")
    srv.chat_peer("cora", "Cora Muster", ready=False)
    cid = srv.new_chat("free", ["test", "anna", "bert", "cora"], admin="test")
    m1 = srv.peer_send(cid, "anna", "Erste")
    _open(srv, cid)
    chat.cache[cid].pop("read_seq", None)
    chat.mark_read(cid, visible=lambda: False)              # nicht zu sehen: nur der Lese-Zeiger (wie bisher)
    pump(lambda: {"seq": m1["seq"]} in srv.read_log, 3)
    assert {"seq": m1["seq"]} in srv.read_log and not _receipts(srv, cid)
    chat.mark_read(cid, visible=lambda: True)
    pump(lambda: _receipts(srv, cid), 3)
    b = _receipts(srv, cid)[-1]
    assert set(b["receipt"]["keys"]) == {"backup:anna", "backup:bert"}       # Cora ohne Schluessel, ich selbst nicht
    # Anna oeffnet mit ihrem Backup-Schluessel und prueft gegen mein Geraet
    a = srv.peers["anna"]["ident"]
    dev, body, sig = C.open_receipt(b["receipt"]["keys"]["backup:anna"], a["backup_pk"], a["backup_sk"])
    assert dev == chat.device_id and C.verify_receipt(str(cid), "test", dev, body, sig, chat.keys["device"]["sign_pk"])
    j = json.loads(body)
    assert j["id"] == m1["id"] and j["n"] == m1["seq"] and abs(j["ts"] / 1000 - time.time()) < 30
    pump(lambda: chat.rc_sent.get(cid) == m1["seq"], 3)
    # dasselbe nicht noch einmal
    n = len(srv.read_log)
    chat.mark_read(cid, visible=lambda: True)
    pump(None, 0.3)
    assert len(srv.read_log) == n
    # neue Nachricht gleich danach: hoechstens eine je 2 s - nachgezogen, nicht verworfen
    m2 = srv.peer_send(cid, "bert", "Zweite")
    chat.load_messages(cid)
    pump(lambda: any(x["id"] == m2["id"] for x in chat.cache[cid]["msgs"]), 3)
    t0 = time.monotonic()
    chat.mark_read(cid, visible=lambda: True)
    pump(None, 0.3)
    assert len(_receipts(srv, cid)) == 1 and cid in chat._rc_timer
    pump(lambda: len(_receipts(srv, cid)) == 2, 4)
    assert time.monotonic() - t0 >= 1.5
    assert json.loads(C.open_receipt(_receipts(srv, cid)[-1]["receipt"]["keys"]["backup:anna"], a["backup_pk"], a["backup_sk"])[1])["n"] == m2["seq"]
    # eigene Nachricht als neueste: nichts zu bestaetigen
    _my_msg(srv, cid, "Von mir")
    assert chat.receipt_want(cid) is None
    # Antwort "rate": spaeter erneut; "off": Einstellung anderswo aus -> gegenseitig
    m3 = srv.peer_send(cid, "anna", "Dritte")
    chat.load_messages(cid)
    pump(lambda: any(x["id"] == m3["id"] for x in chat.cache[cid]["msgs"]), 3)
    chat.rc_at[cid] = 0
    srv.privacy["test"] = {"receipts": False, "online": True}
    chat.mark_read(cid, visible=lambda: True)
    pump(lambda: not S.privacy(chat.me)["receipts"], 3)
    assert chat.receipt_want(cid) is None


def test_mutual_privacy_switch(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    _open(srv, cid)
    sm = _my_msg(srv, cid, "Hallo")
    srv.peer_receipt(cid, "anna", sm, live=False)
    chat.load_receipts(cid)
    pump(lambda: (chat.rcpts.get(cid) or {}).get("anna"), 4)
    # Einstellungen -> Datenschutz: zwei Schalter, Standard an
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(win)
    d.present(win)
    pump(None, 0.5)
    rows = {r.hk_field: r for r in _walk(d.pages["chats"]) if isinstance(r, Adw.SwitchRow) and hasattr(r, "hk_field")}
    assert set(rows) == {"receipts", "online"} and rows["receipts"].get_active() and rows["online"].get_active()
    assert "you don't see it for them either" in rows["receipts"].get_subtitle()
    rows["receipts"].set_active(False)
    pump(lambda: srv.privacy.get("test", {}).get("receipts") is False and not S.privacy(chat.me)["receipts"], 4)
    # gegenseitig: keine fremden Haken mehr, keine eigenen Bestaetigungen, Server liefert nichts
    assert not chat.rcpts.get(cid)
    assert S.tick(chat.me, chat.chat(cid), _cmsg(cid, sm["id"]), chat.rcpts.get(cid)) == ("sent", 0, False)
    srv.peer_send(cid, "anna", "Antwort")
    chat.load_messages(cid)
    pump(lambda: len(chat.cache[cid]["msgs"]) == 2, 3)
    assert chat.receipt_want(cid) is None
    chat.load_receipts(cid)
    pump(None, 0.4)
    assert not chat.rcpts.get(cid)
    # anderes Geraet schaltet wieder ein (Live-Ereignis chat.privacy) -> Schalter folgt, Bestaetigungen wieder sichtbar
    d.force_close()
    srv.privacy["test"] = {"receipts": True, "online": True}
    srv.peer_receipt(cid, "anna", sm, live=False)
    chat.on_live("chat.privacy", {"receipts": True, "online": True})
    pump(lambda: (chat.rcpts.get(cid) or {}).get("anna"), 4)
    assert S.privacy(chat.me)["receipts"] and S.rc_ok(chat.me, chat.chat(cid))
    # Gegenueber hat abgeschaltet: der Server liefert seine Bestaetigung nicht mehr
    srv.privacy["anna"] = {"receipts": False, "online": True}
    chat.load_receipts(cid)
    pump(lambda: not (chat.rcpts.get(cid) or {}).get("anna"), 3)


# --- Online-Status ---------------------------------------------------------------------------------------------------------
def test_presence_subscription_lifecycle(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    srv.chat_peer("bert", "Bert Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.new_chat("free", ["test", "bert"], admin="test")
    srv.online["anna"] = {"online": True, "last": None}
    srv.online["bert"] = {"online": True, "last": None}                    # nur in einer Gruppe: kein Abo
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    srv.presence_calls.clear()
    vis = {"on": True}
    chat.presence_watch("t", lambda: ["anna"] if vis["on"] else [])
    pump(lambda: chat.pres.get("anna"), 3)
    assert srv.presence_calls[-1] == {"users": ["anna"]} and chat._pres_timer
    # Live-Wechsel: offline mit "zuletzt" (5-Minuten-Raster), dann Status nicht mehr verfuegbar
    seen = []
    h = chat.connect("presence", lambda o: seen.append(1))
    last = (time.time() // 300) * 300 - 300
    chat.on_live("chat.presence", {"username": "anna", "online": False, "last": last})
    assert chat.pres["anna"] == {"online": False, "last": last} and seen
    assert S.presence_text(chat.pres["anna"]).startswith("last online ")
    chat.on_live("chat.presence", {"username": "bert", "online": True, "last": None})     # nicht abonniert: ignoriert
    assert "bert" not in chat.pres
    chat.on_live("chat.presence", {"username": "anna", "online": False, "last": None})
    assert "anna" not in chat.pres
    chat.disconnect(h)
    # erneuern nach 60 s (Takt alle 15 s)
    n = len(srv.presence_calls)
    chat._pres_at -= 61
    chat._pres_tick()
    pump(lambda: len(srv.presence_calls) > n, 3)
    # nicht mehr sichtbar: abmelden (leave), Anzeige leer, Takt aus
    vis["on"] = False
    chat._pres_tick()
    pump(lambda: srv.presence_calls[-1].get("leave"), 3)
    assert (srv.presence_calls[-1], chat.pres, chat._pres_timer, list(chat._pres_src)) == ({"users": ["anna"], "leave": True}, {}, 0, ["t"])
    # eigener Online-Status aus: gegenseitig - kein Abo
    vis["on"] = True
    srv.privacy["test"] = {"receipts": True, "online": False}
    chat.presence_sync()
    pump(lambda: not S.privacy(chat.me)["online"], 3)
    n = len(srv.presence_calls)
    chat.presence_sync()
    pump(None, 0.5)
    assert len(srv.presence_calls) == n and not chat.pres
    chat.presence_watch("t", None)


def test_presence_ui_header_and_list_dot(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(cid, "anna", "Hallo")
    srv.online["anna"] = {"online": True, "last": None}
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    win.show_route(("chat", cid))
    conv = win.current.conv
    pump(lambda: conv.loaded, 5)
    pump(lambda: any(c.get("users") == ["anna"] for c in srv.presence_calls), 4)
    pump(lambda: win.wtitle.get_subtitle() == "online", 4)
    assert win.wtitle.get_subtitle() == "online"
    lst = win.current.list
    pump(lambda: any(w.has_css_class("hk-online-dot") for w in _walk(lst)), 3)
    chat.on_live("chat.presence", {"username": "anna", "online": False, "last": time.time() - 600})
    pump(None, 0.3)
    assert win.wtitle.get_subtitle().startswith("last online ")
    assert not any(w.has_css_class("hk-online-dot") for w in _walk(lst))
    # Chat verlassen (Route weg): abgemeldet
    win.show_page(1)
    pump(lambda: srv.presence_calls and srv.presence_calls[-1].get("leave"), 4)
    assert srv.presence_calls[-1].get("leave")


def test_ui_ticks_in_conversation(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(cid, "anna", "Hallo")
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    win.show_route(("chat", cid))
    conv = win.current.conv
    pump(lambda: conv.loaded, 5)
    conv.entry.get_buffer().set_text("Antwort")
    conv.send()
    pump(lambda: any(m["sender"] == "test" for m in srv.chats[cid]["msgs"]), 5)
    sm = next(m for m in srv.chats[cid]["msgs"] if m["sender"] == "test")
    pump(lambda: sm["id"] in conv.ticks and conv.ticks[sm["id"]][2] is not None, 5)
    slot = conv.ticks[sm["id"]][0]
    assert slot.get_visible() and "✓" in _labels(slot)
    sealed = srv.peer_receipt(cid, "anna", sm, live=False)
    chat.on_live("chat.receipt", {"chat_id": cid, "receipts": [{"from": "anna", "sealed": sealed}]})
    pump(lambda: "✓✓" in _labels(conv.ticks[sm["id"]][0]), 4)
    w = [x for x in _walk(conv.ticks[sm["id"]][0]) if isinstance(x, Gtk.Label) and x.get_label() == "✓✓"][0]
    assert w.has_css_class("hk-tick-read") and w.get_tooltip_text() == "Read"
    # Sichtbarkeit: Fenster nicht aktiv -> keine Bestaetigung
    conv.win.is_active = lambda: False
    assert conv._receipt_visible() is False


def test_old_server_hides_everything(win, srv):
    from hlidskjalf.chat import chat
    srv.status_api = False
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    m = srv.peer_send(cid, "anna", "Hallo")
    _open(srv, cid)
    sm = _my_msg(srv, cid, "Hi")
    assert S.tick(chat.me, chat.chat(cid), _cmsg(cid, sm["id"]), {}) is None
    chat.mark_read(cid, visible=lambda: True)
    pump(lambda: srv.read_log or any(u.endswith("/read") for _m, u, _b, _c in srv.log), 3)
    reads = [b for mm, u, b, _c in srv.log if u.endswith(f"api/chats/{cid}/read")]
    assert reads and all("receipt" not in b for b in reads) and reads[-1]["seq"] >= m["seq"]
    chat.load_receipts(cid)
    chat.presence_watch("t", lambda: ["anna"])
    pump(None, 0.6)
    assert not any("receipts" in u or "presence" in u for _m, u, _b, _c in srv.log)
    chat.presence_watch("t", None)
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(win)
    d.present(win)
    pump(None, 0.5)
    assert not [r for r in _walk(d.pages["chats"]) if isinstance(r, Adw.SwitchRow) and hasattr(r, "hk_field")]
    d.force_close()


def test_call_browser_hint_by_server_features():
    """(2.4) Klingel-Leiste/Mitteilung: was das Gespraech im Browser kann - nach den Faehigkeiten des Servers"""
    from hlidskjalf.calls import Calls
    c = Calls()
    c.config = None
    assert c.browser_hint == "Answering opens the call in your browser."
    c.config = {"enabled": True, "features": ["reneg", "pip_hint"]}
    assert "picture-in-picture works" in c.browser_hint
    c.config = {"enabled": True, "features": ["reneg", "pip_hint", "screen2"]}
    assert "screen sharing" in c.browser_hint
    c.config = {"enabled": True, "features": "screen2"}               # Unsinn vom Server: wie aelter
    assert c.browser_hint == "Answering opens the call in your browser."


def test_no_receipts_in_requests_or_readonly_chats():
    me = {"features": ["receipts", "online"], "privacy": {"receipts": True, "online": True}}
    assert S.rc_ok(me, {"id": 1, "kind": "group"})
    assert not S.rc_ok(me, {"id": 1, "kind": "group", "readonly": True})
    assert not S.rc_ok(me, {"id": -2, "v": 2, "kind": "dm", "request": True})
    assert not S.rc_ok(me, {"id": -2, "v": 2, "kind": "dm", "receipts_ok": False})
