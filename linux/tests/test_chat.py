"""Chat mit Ende-zu-Ende-Verschluesselung (Server 1.5) gegen den nachgebauten Server (der wie saga/chat.py Signaturen
und Empfaenger prueft): Einrichtung, Senden/Empfangen (Geraet + Backup), 409 = neu versiegeln, Echtheit, Sicherheitsnummer
geaendert (TOFU), Wiederherstellung, Kopplung neues Geraet, Anhaenge, neuer Wiederherstellungsschluessel, Neu beginnen mit
Passwort, Geraet entfernt, Oberflaeche (Liste, Unterhaltung, Einrichtung)."""
import json

from gi.repository import Gtk

from hk_helpers import idle, pump
from hlidskjalf import chatcrypto as C


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label)]


def _ready(srv, rk=None):
    """dieses Geraet einrichten (wie "Chats einrichten") -> Wiederherstellungsschluessel"""
    from hlidskjalf.chat import chat
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    rk = rk or C.recovery_key()
    k = chat.setup(rk)
    chat.adopt_keys(k)
    pump(lambda: chat.status == "ready" and chat.me and chat.me.get("identity"), 5)
    idle()
    return rk


def test_setup_send_receive(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    assert chat.ready and srv.idents["test"]["ident_pub"] == C.b64(chat.keys["ident_pk"])
    me_dev = [d for d in srv.cdevices.values() if d["uid"] == "test"]
    assert len(me_dev) == 1 and me_dev[0]["id"] == chat.device_id
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(cid, "anna", "Hallo Test 👋 [[note:1]]")
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    assert chat.chat(cid)["unread"] == 1 and chat.unread == 1 and chat.title(chat.chat(cid)) == "Anna Beispiel"
    chat.load_messages(cid)
    pump(lambda: chat.cache.get(cid, {}).get("loaded"), 4)
    m = chat.cache[cid]["msgs"][0]
    assert m["state"] == "ok" and m["text"] == "Hallo Test 👋 [[note:1]]" and not m["mine"]
    pump(lambda: 1 in chat.notes, 3)
    assert chat.notes[1]["title"] == "Startseite"
    # Senden: Server prueft Signatur und Empfaenger; Anna kann lesen
    resp, missing = chat.send(cid, {"v": 1, "type": "text", "text": "Antwort"}, reply_to=m["id"])
    assert resp["ok"] and not missing
    sm = srv.chats[cid]["msgs"][-1]
    assert set(sm["keys"]) == {chat.device_id, "backup:test", srv.peers["anna"]["device"]["id"], "backup:anna"}
    p = srv.peers["anna"]
    api = {"id": sm["id"], "ts": sm["ts"], "sender": {"username": "test"}, "sender_device": sm["sender_device"], "body_ct": sm["body_ct"],
           "reply_to": sm["reply_to"], "key": sm["keys"][p["device"]["id"]], "sig": sm["sig"]}
    assert C.decrypt(cid, api, p["device"]["enc_pk"], p["device"]["enc_sk"])["text"] == "Antwort"
    assert C.sig_ok(cid, api, C.b64(chat.keys["device"]["sign_pk"]))
    # Backup-Schluessel des Gegenuebers oeffnet ebenfalls
    api["key"] = sm["keys"]["backup:anna"]
    assert C.decrypt(cid, api, p["ident"]["backup_pk"], p["ident"]["backup_sk"])["text"] == "Antwort"


def test_409_reseal_and_unverified_and_not_ready(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    srv.chat_peer("cora", "Cora Muster", ready=False)
    cid = srv.new_chat("free", ["test", "anna", "cora"], admin="test")
    chat.recipients(cid)                                     # zwischengespeichert ...
    dev2 = C.device()                                        # ... dann meldet Anna ein zweites Geraet an
    ident = srv.peers["anna"]["ident"]
    srv.cdevices[dev2["id"]] = dict(C.device_api("anna", ident["ident_sk"], dev2, "Tablet"), uid="anna", created=0, last_seen=0, revoked=None)
    resp, missing = chat.send(cid, {"v": 1, "type": "text", "text": "an alle"})
    assert resp["ok"] and missing == ["Cora Muster"]
    assert dev2["id"] in srv.chats[cid]["msgs"][-1]["keys"]
    # gefaelschte Signatur -> "Echtheit nicht bestaetigt" (nicht verworfen)
    srv.peer_send(cid, "anna", "gefälscht", forge=True)
    chat.load_messages(cid)
    pump(lambda: chat.cache[cid].get("loaded") and len(chat.cache[cid]["msgs"]) == 2, 4)
    states = {m["text"]: m["state"] for m in chat.cache[cid]["msgs"]}
    assert states == {"an alle": "ok", "gefälscht": "unverified"}


def test_safety_number_changed_blocks_sending(win, srv):
    from hlidskjalf.chat import SafetyChanged, chat, safety_for, trust_accept, trust_get
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    chat.send(cid, {"v": 1, "type": "text", "text": "eins"})
    first = srv.idents["anna"]["ident_pub"]
    assert trust_get("test", "anna")["ident_pub"] == first
    num = safety_for("test", chat.keys["ident_pk"], "anna", first)
    assert len(num.replace(" ", "")) == 60
    assert num == C.safety_number("anna", C.unb64(first), "test", chat.keys["ident_pk"])
    srv.chat_peer("anna", "Anna Beispiel")                   # Anna beginnt neu: neue Identitaet
    chat.cache[cid].pop("rc", None)
    try:
        chat.send(cid, {"v": 1, "type": "text", "text": "zwei"})
        raise AssertionError("haette nicht senden duerfen")
    except SafetyChanged as e:
        assert e.uid == "anna" and "Anna Beispiel's safety number has changed" in str(e)
    trust_accept("test", "anna")
    resp, _m = chat.send(cid, {"v": 1, "type": "text", "text": "zwei"})
    assert resp["ok"] and trust_get("test", "anna")["ident_pub"] == srv.idents["anna"]["ident_pub"]


def test_recovery_unlock_and_backup_messages(win, srv):
    from hlidskjalf import chat as chatmod
    from hlidskjalf.chat import chat
    rk = _ready(srv)
    srv.chat_peer("anna")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(cid, "anna", "vor dem Gerätewechsel")
    old_dev = chat.device_id
    # neues Geraet: keine lokalen Schluessel
    chatmod._mem.clear()
    chat.clear()
    chat.refresh()
    pump(lambda: chat.status == "device", 5)
    try:
        chat.unlock(C.recovery_key())
        raise AssertionError("falscher Schluessel angenommen")
    except chatmod.ChatError as e:
        assert str(e) == "This key doesn't match."
    k = chat.unlock(rk.lower().replace("-", " "))
    chat.adopt_keys(k)
    pump(lambda: chat.status == "ready", 4)
    assert chat.device_id != old_dev and srv.cdevices[chat.device_id]["uid"] == "test"
    chat.load_messages(cid)
    pump(lambda: chat.cache.get(cid, {}).get("loaded"), 4)
    m = chat.cache[cid]["msgs"][0]
    assert m["text"] == "vor dem Gerätewechsel" and m["state"] == "ok"   # ueber den Backup-Schluessel


def test_link_new_device(win, srv):
    from hlidskjalf.chat import ChatState, chat
    _ready(srv)
    new = ChatState()                                        # zweites Geraet desselben Kontos
    new.me = dict(chat.me)
    new.server = chat.server
    lw = new.link_start()
    assert len(lw["code"].replace(" ", "")) == 20
    seen = []
    chat.connect("link-request", lambda o, ln: seen.append(ln))
    chat.check_links()
    pump(lambda: seen, 4)
    ln = seen[0]
    assert chat.link_code_for(ln) == lw["code"]               # beide zeigen denselben Code
    assert new.link_poll(lw) == ("pending", None)
    chat.link_approve(ln)
    st, k = new.link_poll(lw)
    assert st == "approved" and k["ident_pk"] == chat.keys["ident_pk"] and k["device"]["id"] == lw["device"]["id"]
    assert srv.cdevices[lw["device"]["id"]]["uid"] == "test"


def test_files_new_key_start_over(win, srv):
    from hlidskjalf import chat as chatmod
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna")
    cid = srv.new_chat("dm", ["test", "anna"])
    data = "Anhang äöü\n".encode() * 10
    f = chat.upload_file(cid, data, "notiz.txt", "text/plain")
    blob = srv.cfiles[f["id"]][2]
    assert data not in blob and len(blob) == len(data) + 40          # nur Ciphertext beim Server
    assert chat.fetch_file(cid, f) == data
    resp, _m = chat.send(cid, {"v": 1, "type": "text", "text": "", "files": [f]}, file_ids=[f["id"]])
    assert resp["ok"]
    # neuer Wiederherstellungsschluessel
    rk2 = C.recovery_key()
    chat.new_recovery(rk2)
    assert C.open_bundle("test", rk2, srv.idents["test"]["bundle"])["ident_pk"] == chat.keys["ident_pk"]
    # Neu beginnen: Passwort verlangt
    srv.reauth_need = "password"
    try:
        chat.setup(C.recovery_key(), None, True)
        raise AssertionError("ohne Passwort neu begonnen")
    except chatmod.ChatError as e:
        assert e.code == 403 and e.data["reauth"] == "password"
    old = chat.keys["ident_pk"]
    k = chat.setup(C.recovery_key(), "pw", True)
    assert k["ident_pk"] != old and srv.idents["test"]["ident_pub"] == C.b64(k["ident_pk"])


def test_device_revoked_live(win, srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.live import live
    _ready(srv)
    pump(lambda: live.connected, 5)
    did = chat.device_id
    srv.cdevices[did]["revoked"] = 1.0
    srv.push_event("chat.device", {"id": did, "revoked": True})
    pump(lambda: chat.status == "device", 5)
    assert chat.keys is None and chat.status == "device"


def test_ui_setup_list_and_conversation(win, srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import ChatsScreen, Conversation, text_markup
    assert text_markup("<b>x</b> https://a.example/x. [[note:1]] [[note:9]]", {1: {"title": "Start", "icon": "📘"}, 9: None}) == \
        '&lt;b&gt;x&lt;/b&gt; <a href="https://a.example/x">https://a.example/x</a>. <a href="saga-note:1">📘 Start</a> <i>Page (no access)</i>'
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    win.show_root(("chats",))
    pump(None, 0.3)
    assert isinstance(win.current, ChatsScreen) and "Set up chats" in _labels(win.current)
    assert ("chats",) in win.sidebar.rows
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(cid, "anna", "Wie geht's?")
    srv.peer_send(cid, "anna", "nicht echt", forge=True)
    chat.load_chats()
    pump(lambda: chat.chat(cid), 4)
    win.show_route(("chat", cid))
    conv = win.current.conv
    assert isinstance(conv, Conversation)
    pump(lambda: conv.loaded and len(chat.cache[cid]["msgs"]) == 2, 5)
    pump(None, 0.4)
    labels = _labels(conv)
    assert "Wie geht's?" in labels and "Authenticity not confirmed" in labels
    assert win.wtitle.get_title() == "Anna Beispiel" and win.wtitle.get_subtitle() == "End-to-end encrypted"
    # gelesen
    pump(lambda: any(m == "POST" and u.endswith(f"api/chats/{cid}/read") for m, u, b, c in srv.log), 3)
    # Senden ueber das Eingabefeld
    conv.entry.get_buffer().set_text("Gut, danke!")
    conv.send()
    pump(lambda: any(m["sender"] == "test" for m in srv.chats[cid]["msgs"]), 5)
    idle()
    pump(lambda: "Gut, danke!" in _labels(conv), 4)
    assert "Gut, danke!" in _labels(conv)
    # Sicherheitsnummer-Dialog
    from hlidskjalf.chatview import open_safety
    open_safety(win, cid, "anna")
    pump(lambda: win.get_visible_dialog() is not None, 4)
    d = win.get_visible_dialog()
    nums = [x for x in _labels(d) if x and x.count(" ") >= 3 and x.replace(" ", "").replace("\n", "").isdigit()]
    assert nums and len(nums[0].replace(" ", "").replace("\n", "")) == 60
    d.force_close()
    # Live: neue Nachricht im offenen Chat
    from hlidskjalf.live import live
    pump(lambda: live.connected, 5)
    m = srv.peer_send(cid, "anna", "Noch etwas")
    srv.push_event("chat.message", {"chat_id": cid, "seq": m["seq"], "id": m["id"], "sender": "anna"})
    pump(lambda: "Noch etwas" in _labels(conv), 5)
    assert "Noch etwas" in _labels(conv)
    assert json.dumps(chat.limits)
