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


def test_new_chat_from_someone_else_appears(win, srv):
    """BUG-030: ein Chat, den jemand anderes mit mir anlegt (load_chat, noch nicht in der Liste), erscheint vollstaendig -
    _merge leerte frueher das eben eingefuegte dict (KeyError 'id' in der Liste)"""
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    chat.load_chats()
    idle()
    cid = srv.new_chat("dm", ["test", "anna"])
    assert chat.chat(cid) is None
    got = []
    chat.load_chat(cid, done=got.append)
    assert pump(lambda: got, 4)
    ch = chat.chat(cid)
    assert ch is not None and ch.get("id") == cid and ch.get("kind") == "dm"
    assert all(isinstance(c.get("id"), int) for c in chat.chats)
    assert chat.title(ch) == "Anna Beispiel"


class _BrokenSecret:
    """libsecret-Typelib da, Dienst aber nicht erreichbar (lookup_fail) bzw. Speichern scheitert (nur store)"""
    COLLECTION_DEFAULT = "default"

    def __init__(self, lookup_fail):
        self.lookup_fail, self.stored = lookup_fail, []

    def password_lookup_sync(self, schema, attrs, cancel):
        from gi.repository import GLib
        if self.lookup_fail:
            raise GLib.Error("org.freedesktop.DBus.Error.ServiceUnknown")
        return None

    def password_store_sync(self, schema, attrs, coll, label, raw, cancel):
        from gi.repository import GLib
        raise GLib.Error("store failed")

    def password_clear_sync(self, schema, attrs, cancel):
        return True


def _broken_keyring(monkeypatch, lookup_fail):
    from hlidskjalf import chat as chatmod, config
    fake = _BrokenSecret(lookup_fail)
    monkeypatch.setattr(config, "Secret", fake)
    monkeypatch.setattr(chatmod, "_SCHEMA", object())
    monkeypatch.setitem(chatmod._kr, "ok", None)
    return chatmod


def test_keyring_service_missing_keeps_keys_in_memory(win, srv, monkeypatch):
    """BUG-028: Typelib da, Dienst nicht erreichbar -> Hinweis (keys_persistent False) schon vor der Einrichtung; die
    Einrichtung legt die Identitaet an UND behaelt die Schluessel (im Speicher) - kein verwaister Server-Zustand"""
    chatmod = _broken_keyring(monkeypatch, lookup_fail=True)
    assert chatmod.keys_persistent()                          # noch nicht geprueft
    chat = chatmod.chat
    chat.refresh()
    assert pump(lambda: chatmod._kr["ok"] is not None and chat.status == "setup", 5)
    assert not chatmod.keys_persistent()
    k = chat.setup(C.recovery_key())
    chat.adopt_keys(k)
    assert pump(lambda: chat.status == "ready", 5)
    assert chatmod.load_keys(chat.server, chat.uid)["device"]["id"] == k["device"]["id"]


def test_keyring_store_fails_keeps_keys_in_memory(win, srv, monkeypatch):
    """BUG-028: Dienst antwortet, Speichern scheitert -> kein Abbruch nach dem Anlegen am Server, Schluessel im Speicher,
    danach Hinweis"""
    chatmod = _broken_keyring(monkeypatch, lookup_fail=False)
    chat = chatmod.chat
    chat.refresh()
    assert pump(lambda: chatmod._kr["ok"] is not None and chat.status == "setup", 5)
    assert chatmod.keys_persistent()
    k = chat.setup(C.recovery_key())                          # frueher: ChatError nach PUT api/chat/identity
    assert not chatmod.keys_persistent()
    assert srv.idents["test"]["ident_pub"] == C.b64(k["ident_pk"])
    assert chatmod.load_keys(chat.server, chat.uid)["device"]["id"] == k["device"]["id"]


def test_safety_change_noticed_when_opening_chat(win, srv):
    """BUG-076: geaenderte Sicherheitsnummer wird beim Oeffnen des Chats bemerkt (Hinweis), nicht erst beim Senden"""
    from hlidskjalf.chat import chat, trust_get
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    chat.send(cid, {"v": 1, "type": "text", "text": "eins"})
    srv.chat_peer("anna", "Anna Beispiel")                   # Anna beginnt neu
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    assert not trust_get("test", "anna").get("pending")
    win.show_route(("chat", cid))
    v = win.current
    assert pump(lambda: trust_get("test", "anna").get("pending"), 6), "nicht beim Oeffnen geprueft"
    conv = getattr(v, "conv", None) or v
    if hasattr(conv, "warn_rev"):
        assert pump(lambda: conv.warn_rev.get_reveal_child(), 4)


def test_link_request_closed_with_esc_comes_back(win, srv, monkeypatch):
    """BUG-080: "Neues Geraet bestaetigen" mit Esc geschlossen -> Anfrage erscheint spaeter wieder (nicht nie mehr)"""
    from hlidskjalf.chat import ChatState, chat
    from hlidskjalf.chatview import approve_link
    _ready(srv)
    monkeypatch.setattr(ChatState, "LINK_AGAIN_S", 1)
    new = ChatState()
    new.me = dict(chat.me)
    new.server = chat.server
    new.link_start()
    seen = []
    h = chat.connect("link-request", lambda o, ln: seen.append(ln))
    try:
        chat.check_links()
        assert pump(lambda: seen, 4)
        pump(None, 0.3)
        d = win.get_visible_dialog() or approve_link(win, seen[0])
        d.close()                                             # wie Esc (close-response "close")
        n = len(seen)
        assert pump(lambda: len(seen) > n, 4), "Anfrage kam nicht wieder"
    finally:
        chat.disconnect(h)
        while win.get_visible_dialog() is not None:
            win.get_visible_dialog().force_close()
            pump(None, 0.1)


def test_rename_group_enter_and_start_over_redraws(win, srv):
    """BUG-116: Enter in "Gruppe umbenennen" speichert; neue Schluessel (Neu beginnen) zeichnen offene Chats neu"""
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import rename_group
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("free", ["test", "anna"])
    chat.load_chats()
    assert pump(lambda: chat.chat(cid) is not None, 4)
    n = len(srv.chats[cid]["msgs"])
    rename_group(win, cid)
    pump(None, 0.2)
    d = win.get_visible_dialog()
    en = d.get_extra_child()
    en.set_text("Neuer Name")
    en.emit("activate")
    assert pump(lambda: win.get_visible_dialog() is None, 3), "Dialog nach Enter noch offen"
    assert pump(lambda: len(srv.chats[cid]["msgs"]) > n, 4), "nichts gespeichert"
    # Neu beginnen: offene Unterhaltung bekommt "messages" (neu zeichnen), alte Klartexte bleiben nicht stehen
    chat.cache[cid] = {"msgs": [{"id": "alt", "text": "alter Klartext"}], "loaded": True}
    got = []
    h = chat.connect("messages", lambda o, c: got.append(c))
    try:
        chat.adopt_keys(chat.keys)
        assert cid in got and "alter Klartext" not in str(chat.cache.get(cid))
    finally:
        chat.disconnect(h)
