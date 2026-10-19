"""Saga 2.3 - Chats ueber Servergrenzen (docs/APP-API-FED.md) gegen den nachgebauten Server mit Foederation (mockfed.py,
prueft Signaturen v2 und Empfaenger exakt): Kopfzeile X-Saga-Chat, Zertifikate v2, Nachrichtenanfrage (Liste, Leiste,
Annehmen/Ablehnen/Sperren), Senden/Empfangen v2, TOFU (Warnung, "Verstanden", Blockade, ungueltig), Zustellstand (wartet,
nicht zugestellt, erneut senden), abgewiesen, Systemzeile, Suche mit Treffern anderer Server, Sperrliste, Anrufe nur aus
angenommenen Chats. Ohne Foederation (aeltere Server) bleibt alles wie bisher (test_chat.py)."""
import json

import mockfed
from gi.repository import Adw, Gtk

from hk_helpers import idle, pump
from hlidskjalf import chatcrypto as C


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label) and x.get_mapped()]


def _all_labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label)]


def _buttons(w):
    return {x.get_label(): x for x in _walk(w) if isinstance(x, Gtk.Button) and x.get_label()}


def _ready(srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.store import store
    mockfed.enable(srv)
    store.server_version = "2.3.0"
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    k = chat.setup(C.recovery_key())
    chat.adopt_keys(k)
    pump(lambda: chat.status == "ready" and chat.me and chat.me.get("identity"), 5)
    pump(lambda: srv.fed["certs"]["dset"] is not None, 5)              # Zertifikate v2 still hochgeladen
    idle()
    return k


def _open(win, cid):
    from hlidskjalf.chat import chat
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 5)
    win.show_route(("chat", cid))
    pump(lambda: win.current.conv is not None and win.current.conv.loaded, 5)
    pump(None, 0.4)
    return win.current.conv


def test_header_x_saga_chat(srv):
    """Jede Anfrage traegt X-Saga-Chat: 2 (erst seit v2 vollstaendig umgesetzt ist)"""
    from hlidskjalf import api, config
    seen = []

    class H(dict):
        def get_all(self, _k):
            return []

    class R:
        status = 200
        headers = H()

        def read(self):
            return b"{}"

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    old = api._opener.open
    api._opener.open = lambda req, timeout=None: (seen.append(dict(req.header_items())), R())[1]
    try:
        config.settings.server = "https://saga.test"
        api.Client._send(api.client, "GET", "api/chats")
    finally:
        api._opener.open = old
    assert seen and seen[0].get("X-saga-chat") == "2" and seen[0].get("X-saga") == "1"


def test_certs_request_accept_send_receive(win, srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import ChatList
    k = _ready(srv)
    st = mockfed._v2me(srv)
    assert not st["need"] and st["device_set_ok"] and chat.my_handle == srv.fed["me"]
    h = mockfed.remote_person(srv, "Bert Gast", "b.example")
    cid = mockfed.new_fed_chat(srv, h, request=True, home_self=False)
    mockfed.peer_send_v2(srv, cid, "Hallo über Servergrenzen 👋")
    conv = _open(win, cid)
    c = chat.chat(cid)
    assert c["v"] == 2 and c["request"] and chat.title(c) == "Bert Gast"
    # Liste: eigener Bereich "Nachrichtenanfragen" oben, Untertitel mit Herkunft
    lst = win.current.list
    labels = _all_labels(lst.box)
    assert "Message requests (1)" in labels and any(x == "Request · b.example" for x in labels)
    assert isinstance(lst, ChatList)
    # Nachricht: entschluesselt, echt; Kopf mit Herkunft und "Anfrage"
    m = chat.cache[cid]["msgs"][0]
    assert m["state"] == "ok" and m["text"] == "Hallo über Servergrenzen 👋" and m["sender_handle"] == h and not m["mine"]
    title, sub = win.current.header()
    assert title == "Bert Gast" and sub.startswith("on b.example") and "Request" in sub
    # Anfrage: Leiste statt Eingabefeld, kein Anruf
    assert conv.state_box.get_visible() and not conv.composer_box.get_visible()
    assert any("wants to write to you" in x for x in _labels(conv.state_box))
    assert win.current.call_chat() is None
    _buttons(conv.state_box)["Accept"].emit("clicked")
    pump(lambda: not (chat.chat(cid) or {}).get("request"), 5)
    pump(None, 0.3)
    assert conv.composer_box.get_visible() and not conv.state_box.get_visible()
    # Senden v2: der Server prueft Signatur v2 und Empfaenger exakt; Bert kann lesen
    resp, missing = chat.send(cid, {"v": 1, "type": "text", "text": "Antwort an B"}, reply_to=m["id"])
    assert resp["ok"] and resp["fseq"] and not missing
    sm = srv.chats[cid]["msgs"][-1]
    p = srv.fed["remote"][h]
    assert set(sm["keys"]) == mockfed._want(srv, srv.chats[cid])
    api = {"id": sm["id"], "ts": sm["ts"], "sender_handle": srv.fed["me"], "sender_device": sm["sender_device"], "body_ct": sm["body_ct"],
           "sig": sm["sig"], "reply_to": sm["reply_to"], "ai": None, "key": sm["keys"][f"dev:{p['dev']['id']}@b.example"]}
    assert C.decrypt_v2(srv.chats[cid]["fed"]["ref"], api, p["dev"]["enc_pk"], p["dev"]["enc_sk"])["text"] == "Antwort an B"
    assert C.sig_ok_v2(srv.chats[cid]["fed"]["ref"], api, k["device"]["sign_pk"])
    chat.load_messages(cid)
    pump(lambda: len(chat.cache[cid]["msgs"]) == 2, 5)
    mine = chat.cache[cid]["msgs"][-1]
    assert mine["mine"] and mine["state"] == "ok" and mine["text"] == "Antwort an B"


def test_tofu_warn_ok_block(win, srv):
    from hlidskjalf.chat import SafetyChanged, chat, trust2_get
    _ready(srv)
    h = mockfed.remote_person(srv, "Cleo Team", "c.example")
    cid = mockfed.new_fed_chat(srv, h)
    mockfed.peer_send_v2(srv, cid, "erste")
    conv = _open(win, cid)
    pump(lambda: (chat.fk.get(h) or {}).get("trust") == "trust", 5)
    assert trust2_get(chat.uid, h)["ident"] == C.b64(srv.fed["remote"][h]["ident"]["ident_pk"])
    assert not conv.warn_rev.get_reveal_child()
    # Cleo beginnt neu -> Warnung, Senden gesperrt
    mockfed.new_keys(srv, h)
    chat.on_live("chat.keys.changed", {"handle": h})
    pump(lambda: (chat.fk.get(h) or {}).get("trust") == "warn", 5)
    pump(None, 0.3)
    assert conv.warn_rev.get_reveal_child() and "safety number has changed" in conv.warn_label.get_label()
    assert conv.warn_ok.get_visible() and conv.warn_btn.get_visible()
    try:
        chat.send(cid, {"v": 1, "type": "text", "text": "x"})
        raise AssertionError("haette nicht senden duerfen")
    except SafetyChanged:
        pass
    assert win.current.call_chat() == cid or win.current.call_chat() is None
    # "Verstanden" -> wieder senden
    conv._warn_ok()
    pump(None, 0.2)
    assert not conv.warn_rev.get_reveal_child()
    resp, _m = chat.send(cid, {"v": 1, "type": "text", "text": "nach Verstanden"})
    assert resp["ok"]
    # ueberprueft + erneuter Wechsel -> Blockade (ohne "Verstanden")
    from hlidskjalf.chatview import SafetyDialogV2
    d = SafetyDialogV2(win, cid, chat.chat(cid)["peer"], chat.fk[h])
    d.present(win)
    pump(None, 0.2)
    labels = _all_labels(d)
    num = C.safety_number_v2(srv.fed["me"], chat.keys["ident_pk"], h, srv.fed["remote"][h]["ident"]["ident_pk"])
    assert any(num.split()[0] in (x or "") for x in labels) and any("c.example" in (x or "") for x in labels)
    d._verify()
    d.force_close()
    assert chat.fed_verified(h)
    assert "✓ verified" in win.current.header()[1]
    mockfed.new_keys(srv, h)
    chat.fed_keys([h], True)
    conv._check_trust()
    assert chat.fk[h]["trust"] == "block" and conv.warn_rev.get_reveal_child() and not conv.warn_ok.get_visible()
    assert "⛔ blocked" in win.current.header()[1]
    try:
        chat.send(cid, {"v": 1, "type": "text", "text": "x"})
        raise AssertionError("haette nicht senden duerfen")
    except Exception as e:  # noqa: BLE001
        assert "compare the safety number" in str(e)
    # Geraete-Liste aelter als schon gesehen (Widerruf zurueckgedreht) -> ungueltig
    chat.fed_verify(h, chat.fk[h]["keys"]["ident_pub"], True)
    p = srv.fed["remote"][h]
    old = mockfed._remote_entry
    mockfed._remote_entry = lambda pp, ids=None, ver=None: old(pp, ids, 1)
    try:
        chat.fed_keys([h], True)
    finally:
        mockfed._remote_entry = old
    assert chat.fk[h]["trust"] == "reject" and p["ver"] > 1
    conv._check_trust()
    assert "invalid" in conv.warn_label.get_label() and not conv.warn_btn.get_visible()


def test_pending_failed_resend_rejected(win, srv):
    from hlidskjalf.chat import FedNotDelivered, chat
    _ready(srv)
    h = mockfed.remote_person(srv, "Dora Fern", "d.example")
    cid = mockfed.new_fed_chat(srv, h)
    conv = _open(win, cid)
    srv.fed["offline"] = True
    buf = conv.entry.get_buffer()
    buf.set_text("wartet")
    conv.send()
    pump(lambda: any(m.get("text") == "wartet" for m in (chat.cache.get(cid) or {}).get("msgs") or []), 5)
    pump(None, 0.3)
    m = next(x for x in chat.cache[cid]["msgs"] if x.get("text") == "wartet")
    assert m["fstate"] == "pending" and "⏳" in _labels(conv.msgs)
    # spaeter abgewiesen: Live chat.message {id, failed} -> "nicht zugestellt", Menue "Erneut senden"
    srv.fed["offline"] = False
    sm = srv.chats[cid]["msgs"][-1]
    sm["fstate"] = "failed"
    chat.on_live("chat.message", {"chat_id": cid, "id": m["id"], "failed": True})
    pump(lambda: any("not delivered" in (x or "") for x in _labels(conv.msgs)), 5)
    assert any("not delivered" in (x or "") for x in _labels(conv.msgs))
    done = []
    chat.resend(cid, m, done=lambda r: done.append(r))
    pump(lambda: done, 5)
    pump(lambda: [x.get("text") for x in chat.cache[cid]["msgs"]] == ["wartet"] and not chat.cache[cid]["msgs"][0].get("fstate"), 5)
    assert [x["text"] for x in chat.cache[cid]["msgs"]] == ["wartet"] and chat.cache[cid]["msgs"][0]["id"] != m["id"]
    assert m["id"] not in [x["id"] for x in srv.chats[cid]["msgs"]]
    # vom Heim abgewiesen (z. B. gesperrt): 409 mit Text
    srv.fed["reject"] = "Nicht zugestellt (gesperrt)."
    try:
        chat.send(cid, {"v": 1, "type": "text", "text": "x"})
        raise AssertionError("haette abgewiesen werden muessen")
    except FedNotDelivered as e:
        assert "gesperrt" in str(e)


def test_sys_row_decline_block_and_blocklist(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    h = mockfed.remote_person(srv, "Eve Aussen", "e.example")
    cid = mockfed.new_fed_chat(srv, h)
    mockfed.add_sys(srv, cid, {"type": "ai", "on": True, "by": h, "name": "Eve Aussen"})
    mockfed.add_sys(srv, cid, {"type": "ai", "on": False, "by": srv.fed["me"], "name": "Test"})
    conv = _open(win, cid)
    labels = _labels(conv.msgs)
    assert "Eve Aussen turned on the AI in this chat." in labels and "You turned off the AI in this chat." in labels
    # Anfrage einer anderen Person sperren (ganzer Server)
    h2 = mockfed.remote_person(srv, "Fritz Fremd", "f.example")
    cid2 = mockfed.new_fed_chat(srv, h2, request=True, home_self=False)
    mockfed.peer_send_v2(srv, cid2, "Spam")
    conv2 = _open(win, cid2)
    conv2._answer("block", "instance")
    pump(lambda: chat.chat(cid2) is None, 5)
    assert srv.fed["blocks"] and srv.fed["blocks"][0]["kind"] == "instance" and srv.fed["blocks"][0]["target"] == "f.example"
    # Einstellungen -> Chats: Sperrliste mit "Entsperren", Adressbuch-Schalter
    from hlidskjalf.chatview import ChatDevicesPage

    class Dlg:
        def __init__(self):
            self.win, self.toasts = win, []

        def add_toast(self, t):
            self.toasts.append(t.get_title())
    dlg = Dlg()
    page = ChatDevicesPage(dlg)
    pump(lambda: any(g.get_title() == "Blocked (other Saga servers)" and g.get_visible() for g in page.groups), 5)
    g = next(x for x in page.groups if x.get_title() == "Blocked (other Saga servers)")
    assert "f.example" in _all_labels(g)
    pump(lambda: any(x.get_title() == "Connected Saga servers" and x.get_visible() for x in page.groups), 5)   # eigene Anfrage
    assert any(x.get_title() == "Connected Saga servers" and x.get_visible() for x in page.groups)
    _buttons(g)["Unblock"].emit("clicked")
    pump(lambda: not srv.fed["blocks"], 5)
    assert not srv.fed["blocks"] and "Unblocked." in dlg.toasts
    sw = next(x for x in _walk(next(x for x in page.groups if x.get_title() == "Connected Saga servers")) if isinstance(x, Adw.SwitchRow))
    sw.set_active(False)
    pump(lambda: srv.fed.get("directory") is False, 5)


def test_search_remote_hits_new_chat(win, srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import NewChatDialog
    from hlidskjalf.sharing import hit_rows, search_min
    _ready(srv)
    assert search_min() == 1
    mockfed.remote_person(srv, "Anna Berg", "b.example", public="anna")
    h2 = mockfed.remote_person(srv, "Hd", "c.example")
    d = NewChatDialog(win)
    d.present(win)
    d.search.set_text("hd")                               # 1-2 Zeichen: genaue Treffer (Server)
    pump(lambda: any("Hd · c.example" in (x or "") for x in _all_labels(d.results)), 5)
    labels = _all_labels(d.results)
    assert "Hd · c.example" in labels and any("first message as a request" in (x or "") for x in labels)
    rows = [x for x in _walk(d.results) if isinstance(x, Adw.ActionRow) and x.get_title() == "Hd · c.example"]
    rows[0].emit("activated")
    pump(lambda: any((c.get("peer") or {}).get("handle") == h2 for c in chat.chats), 5)
    c = next(c for c in chat.chats if (c.get("peer") or {}).get("handle") == h2)
    assert c["v"] == 2 and not c["request"]
    # Teilen: Treffer anderer Server mit "Name · host"
    hits = hit_rows({"remote": [{"type": "remote", "target": "@anna@b.example", "label": "Anna Berg · b.example", "name": "Anna Berg",
                                 "host": "b.example", "avatar": None}]}, {}, "view")
    assert hits[0]["name"] == "Anna Berg · b.example" and hits[0]["sub"] == "Person on b.example"


def test_old_server_no_fed(win, srv):
    """Aeltere Server (ohne /api/chat/v2): keine Zertifikate, Suche ab 3 Zeichen, Chats v1 wie bisher"""
    from hlidskjalf.chat import chat
    from hlidskjalf.sharing import search_min
    from hlidskjalf.store import store
    store.server_version = "2.2.0"
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    chat.adopt_keys(chat.setup(C.recovery_key()))
    pump(lambda: chat.status == "ready", 5)
    assert chat.fed_certs() is None and chat.my_handle is None and search_min() == 3
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(cid, "anna", "lokal")
    conv = _open(win, cid)
    assert chat.cache[cid]["msgs"][0]["state"] == "ok" and not conv.state_box.get_visible() and conv.composer_box.get_visible()
    resp, _m = chat.send(cid, {"v": 1, "type": "text", "text": "v1"})
    assert resp["ok"] and json.loads(json.dumps(resp))


# --- "⏳ wird zugestellt" bleibt nicht haengen (eigene Nachricht, Heim = anderer Server; Server bis 2.3.0 ohne Ereignis) -----
def _fm(seq, mid, fstate, mine=True):
    return {"seq": seq, "id": mid, "fstate": fstate, "mine": mine}


def test_fed_delivery_logic():
    from hlidskjalf.chat import fed_apply, fed_check_due, fed_gone, fed_oldest_pending, fed_state
    # per POST bestaetigt: ueber das Live-Ereignis schon mit ⏳ geladene Kopie -> zugestellt
    ms = [_fm(5, "a", "pending")]
    assert fed_apply(ms, {}, {"a"}) and ms[0]["fstate"] is None
    # Abgleich: Serverstand heilt haengende, "nicht zugestellt" wird uebernommen, fremde bleiben
    ms = [_fm(3, "x", None, mine=False), _fm(5, "a", "pending"), _fm(6, "b", "pending")]
    assert fed_apply(ms, {"x": None, "a": None, "b": "failed"}, set())
    assert [m["fstate"] for m in ms] == [None, None, "failed"]
    # nie zurueck auf "wird zugestellt" (aelterer Serverstand)
    assert fed_state(None, "pending", True, False) is None
    assert fed_state("pending", "pending", True, False) == "pending"
    assert fed_state("pending", "pending", True, True) is None
    assert fed_state("failed", None, False, True) == "failed"
    ms = [_fm(5, "a", None)]
    assert not fed_apply(ms, {"a": "pending"}, set()) and ms[0]["fstate"] is None
    # Abgleich nur, solange eigene Nachrichten warten, gedrosselt (15 s)
    w = [_fm(4, "o", "pending", mine=False), _fm(7, "a", "pending"), _fm(9, "b", "pending")]
    assert fed_oldest_pending(w) == 7
    assert fed_oldest_pending([_fm(7, "a", None), _fm(8, "c", "failed")]) is None
    assert fed_check_due(w, 0, 100) and not fed_check_due(w, 95, 100)
    assert not fed_check_due([_fm(7, "a", None)], 0, 100)
    # vom Server entfernt: nur im abgeglichenen Bereich werten
    g = [_fm(7, "a", "pending"), _fm(9, "b", "pending"), _fm(500, "c", "pending")]
    assert fed_gone(g, {"z": None}, False, 8) == {"a", "b", "c"}
    assert fed_gone(g, {"b": None}, True, 300) == {"a"}


def test_pending_heals_without_event(win, srv):
    import time
    from hlidskjalf.chat import chat
    _ready(srv)
    h = mockfed.remote_person(srv, "Ella Moor", "e.example")
    cid = mockfed.new_fed_chat(srv, h)
    conv = _open(win, cid)
    c = chat.cache[cid]
    assert conv._dcheck_src                                   # Abgleich laeuft, solange der Chat offen ist
    assert not chat.check_delivery(cid, force=True)           # nichts wartet -> keine Anfrage

    def mine(t):
        return next((m for m in c["msgs"] if m.get("text") == t), None)

    def send(t):
        conv.entry.get_buffer().set_text(t)
        conv.send()
        pump(lambda: mine(t) is not None and conv.entry.get_sensitive(), 5)
        pump(None, 0.3)
    # 1) Server speichert noch 'pending', POST antwortet ohne pending (sofort bestaetigt) -> gleich zugestellt
    srv.fed["stale"] = True
    send("eins")
    assert srv.chats[cid]["msgs"][-1]["fstate"] == "pending"
    pump(lambda: mine("eins")["fstate"] is None, 3)
    assert mine("eins")["fstate"] is None and "⏳" not in _labels(conv.msgs)
    srv.chats[cid]["msgs"][-1]["fstate"] = None
    srv.fed["stale"] = False
    # 2) haengende: Heim bestaetigt bzw. weist ab bzw. Server verwirft - alles ohne Live-Ereignis
    srv.fed["offline"] = True
    for t in ("zwei", "drei", "vier"):
        send(t)
    assert [mine(t)["fstate"] for t in ("zwei", "drei", "vier")] == ["pending"] * 3 and "⏳" in _labels(conv.msgs)
    srv.fed["offline"] = False
    sm = {m["id"]: m for m in srv.chats[cid]["msgs"]}
    sm[mine("zwei")["id"]]["fstate"] = None
    sm[mine("drei")["id"]]["fstate"] = "failed"
    srv.chats[cid]["msgs"].remove(sm[mine("vier")["id"]])
    pump(lambda: not c.get("dcheck_run"), 5)
    c["dcheck"] = time.monotonic()
    assert not chat.check_delivery(cid)                      # gedrosselt
    c["dcheck"] = 0
    assert conv._dcheck_tick()                                # Takt (alle 15 s) startet den Abgleich
    pump(lambda: mine("vier") is None and mine("zwei")["fstate"] is None, 5)
    assert mine("zwei")["fstate"] is None and mine("drei")["fstate"] == "failed" and mine("vier") is None
    pump(lambda: any("not delivered" in (x or "") for x in _labels(conv.msgs)), 5)
    assert "⏳" not in _labels(conv.msgs) and any("not delivered" in (x or "") for x in _labels(conv.msgs))
    assert not chat.check_delivery(cid, force=True)           # nichts wartet mehr -> keine Anfrage
