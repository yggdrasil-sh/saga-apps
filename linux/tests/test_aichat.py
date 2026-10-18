"""KI-Persona und "KI im Chat - nur auf Zuruf" (Server 2.1, Vertrag docs/APP-API-KI.md): @-Erkennung wie beim Server,
Verlaufsbegrenzung (hoechstens 12, je 600 Zeichen, nur vor der Frage), Beleg-Pruefung (SHA-256 + Kennung), Ablauf
Frage -> Strom -> verschluesselte Antwort mit ai_reply, Anzeige (bestaetigt / Warnung), Persona in Kopfleiste und
Assistent, aeltere Server ohne Persona."""
import hashlib

from gi.repository import Gtk

import mockki
from hk_helpers import idle, pump
from hlidskjalf import aichat
from test_chat import _labels, _ready, _walk


# --- reine Logik -----------------------------------------------------------------------------------------------------
def test_mention_like_server():
    m = aichat.mentions
    assert m("@Marin wann fährt der Zug?", "Marin")
    assert m("Frage an @marin: hallo", "Marin")                       # Gross-/Kleinschreibung egal
    assert m("(@Marin)", "Marin") and m('"@Marin"', "Marin") and m("[@Marin]", "Marin") and m("'@Marin'", "Marin")
    assert m("Hallo\n@Marin", "Marin")
    assert not m("@Marina kommt", "Marin")                            # danach ein Buchstabe
    assert not m("@Marin2", "Marin") and not m("@Marin_x", "Marin")   # Ziffer/Unterstrich (\w wie der Server)
    assert not m("mail@Marin", "Marin")                              # davor ein Buchstabe
    assert not m("Marin, hallo", "Marin") and not m("", "Marin") and not m("@Marin", "")
    assert m("@Saga KI was ist?", "Saga KI") and m("@Mar.in ok", "Mar.in") and not m("@Marxin", "Mar.in")   # Sonderzeichen escaped
    assert m("@Marín, hola", "Marín") and not m("@Maríné", "Marín")


def test_typed_prefix_and_suggest():
    assert aichat.typed_prefix("Hallo @Ma") == (6, "Ma")
    assert aichat.typed_prefix("@") == (0, "")
    assert aichat.typed_prefix("(@m") == (1, "m")
    assert aichat.typed_prefix("mail@Ma") is None and aichat.typed_prefix("@Ma rin") is None
    assert aichat.suggest("ma", "Marin") and aichat.suggest("", "Marin")
    assert not aichat.suggest("mx", "Marin") and not aichat.suggest("Marin", "Marin") and not aichat.suggest(None, "Marin")


def test_check_reply_hash_and_ref():
    text = "  Der Zug fährt um 9 Uhr.\n"
    h = hashlib.sha256("Der Zug fährt um 9 Uhr.".encode()).hexdigest()
    assert aichat.reply_sha(text) == h
    body = {"v": 1, "type": "text", "text": text, "ai": {"reply": "r1", "name": "Marin"}}
    ok = aichat.check_reply
    assert ok(body, {"ref": "r1", "sha256": h}) == "ok"
    assert ok(body, {"ref": "r1", "sha256": h.upper()}) == "ok"
    assert ok(body, {"ref": "r2", "sha256": h}) == "bad"                 # andere Beleg-Kennung
    assert ok(body, {"ref": "r1", "sha256": "0" * 64}) == "bad"          # Hash passt nicht
    assert ok(dict(body, text=text + "!"), {"ref": "r1", "sha256": h}) == "bad"
    assert ok(body, None) == "bad" and ok(body, {}) == "bad"             # gibt sich aus, ohne Beleg
    assert ok(dict(body, text=None), {"ref": "r1", "sha256": h}) == "bad"
    assert ok({"v": 1, "type": "text", "text": "normal"}, {"ref": "r1", "sha256": h}) is None   # keine KI-Antwort
    assert ok(None, None) is None


def test_history_limit_and_order():
    msgs = [{"id": f"m{i}", "state": "ok", "text": f"Nachricht {i}", "sender": {"name": "Ben" if i % 2 else "Clara"}} for i in range(20)]
    msgs[18]["ai_state"] = "ok"                                         # bestaetigte KI-Antwort -> Name der KI
    msgs[17]["state"] = "undecryptable"
    msgs[16]["text"] = ""                                               # nur Anhang
    msgs[15]["text"] = "x" * 900
    msgs[14]["state"] = "call"
    h = aichat.history(msgs, "m19", "Marin")                            # Frage = m19 (selbst nicht im Verlauf)
    assert len(h) == 12
    assert [x["text"] for x in h][-1] == "Nachricht 18" and h[-1]["name"] == "Marin"
    assert all(x["text"] != "Nachricht 19" for x in h)
    assert "Nachricht 17" not in [x["text"] for x in h] and "Nachricht 16" not in [x["text"] for x in h]
    assert any(len(x["text"]) == 600 for x in h)                         # je hoechstens 600 Zeichen
    assert h[0]["text"] == "Nachricht 4"                                 # aelteste zuerst: 4..18 ohne 14, 16, 17
    assert [x["text"][:12] for x in h][-3:] == ["Nachricht 13", "x" * 12, "Nachricht 18"]
    # Nachrichten NACH der Frage zaehlen nicht
    h2 = aichat.history(msgs, "m5", "Marin")
    assert [x["text"] for x in h2] == [f"Nachricht {i}" for i in range(5)]
    # Frage noch nicht in der Liste (eben gesendet): die letzten 12
    h3 = aichat.history(msgs[:10], "neu", "Marin")
    assert len(h3) == 10 and h3[-1]["text"] == "Nachricht 9"
    assert aichat.history([], "x", "Marin") == [] and len(aichat.history(msgs, None, "Marin", limit=50)) == 12
    assert len(aichat.question("q" * 5000)) == 1000


def test_may_toggle():
    assert aichat.may_toggle({"kind": "dm"}) and aichat.may_toggle({"kind": "page"}) and aichat.may_toggle({"kind": "group"})
    assert aichat.may_toggle({"kind": "free", "role": "admin"}) and not aichat.may_toggle({"kind": "free", "role": "member"})


# --- gegen den nachgebauten Server ---------------------------------------------------------------------------------------
def _setup(win, srv, persona=True, chat_on=True):
    from hlidskjalf.assistant import ai
    from hlidskjalf.chat import chat
    if persona:
        mockki.enable(srv, "Marin", avatar=mockki_png(), chat=chat_on)
    _ready(srv)
    ai.refresh()
    pump(lambda: ai.known, 4)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.chats[cid]["ai"] = True
    for i in range(14):
        srv.peer_send(cid, "anna", f"Vorher {i}")
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    return cid


def mockki_png():
    import base64
    return base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


def test_persona_status_and_old_server(win, srv):
    from hlidskjalf.assistant import ai, ai_name, ask_label, assistant_title, thinking_text
    srv.ai_status = {"enabled": True, "host": "ki.example"}            # aelterer Server: keine Persona
    ai.refresh()
    pump(lambda: ai.known, 4)
    assert ai.persona() is None and ask_label() == "Assistant" and assistant_title() == "AI assistant"
    assert thinking_text() == "Thinking …" and ai_name() == "Saga AI"
    mockki.enable(srv, "Marin")
    ai.refresh()
    pump(lambda: ai.persona() is not None, 4)
    assert ai_name() == "Marin" and ask_label() == "Ask Marin" and thinking_text() == "Marin is thinking …"
    assert ai.chat_allowed
    # Assistent fuer mich aus: Persona kommt aus /api/ai/persona (KI-Antworten anderer zeigen trotzdem den Namen)
    srv.ai_status = {"enabled": False}
    ai.refresh()
    pump(lambda: not ai.enabled and ai.persona() is not None, 4)
    assert ai_name() == "Marin" and not ai.chat_allowed
    # Live: ai.persona.changed -> neu abfragen
    srv.ai_persona["name"] = "Nova"
    ai.on_live("ai.persona.changed", {"name": "Nova", "avatar": None})
    pump(lambda: ai_name() == "Nova", 4)


def test_header_button_with_persona(win, srv):
    from hlidskjalf.assistant import ai
    mockki.enable(srv, "Marin", avatar=mockki_png())
    win.show_page(1)
    pump(lambda: win.current is not None and getattr(win.current, "doc", None) and win.current.doc.loaded, 5)
    ai.refresh()
    pump(lambda: ai.persona() is not None, 4)
    idle()
    win.update_header()
    assert win.ai_btn.get_visible() and win.ai_label.get_label() == "Ask Marin"
    assert win.ai_btn.get_tooltip_text() == "Ask Marin"


def test_ask_in_chat_flow(win, srv):
    """@Marin -> Frage mit Verlauf (12, ohne die Frage) -> Antwort verschluesselt mit ai_reply -> bestaetigt angezeigt"""
    from hlidskjalf.chat import chat
    cid = _setup(win, srv)
    chat.load_messages(cid)
    pump(lambda: chat.cache.get(cid, {}).get("loaded"), 4)
    resp, _miss = chat.send(cid, {"v": 1, "type": "text", "text": "@Marin wann fährt der Zug?"})
    qid = resp["id"]
    assert len(qid) == 32
    errs = []
    assert chat.ask_ai(cid, "@Marin wann fährt der Zug?", qid, on_error=errs.append)
    assert not chat.ask_ai(cid, "@Marin nochmal", qid)                 # eine Frage je Person gleichzeitig
    pump(lambda: cid not in chat.ai_pending, 6)
    assert not errs
    c2, body = srv.ki_log[-1]
    assert c2 == cid and body["msg_id"] == qid and body["q"] == "@Marin wann fährt der Zug?"
    assert len(body["history"]) == 12 and body["history"][-1] == {"name": "Anna Beispiel", "text": "Vorher 13"}
    assert body["history"][0]["text"] == "Vorher 2"
    sm = srv.chats[cid]["msgs"][-1]
    assert sm["ai"]["by"] == "test" and sm["reply_to"] == qid
    assert list(srv.ki_receipts.values())[-1]["used"]
    m = chat.cache[cid]["msgs"][-1]
    assert m["ai_state"] == "ok" and m["text"] == "Der Zug fährt um 9:12 Uhr ab Gleis 3." and m["body"]["ai"]["name"] == "Marin"
    # Beleg nur einmal
    from hlidskjalf.chat import ChatError
    try:
        chat.send(cid, {"v": 1, "type": "text", "text": "x", "ai": {"reply": sm["ai"]["ref"]}}, extra={"ai_reply": sm["ai"]["ref"]})
        raise AssertionError("409 erwartet")
    except ChatError as e:
        assert e.code == 409


def test_ui_note_hint_suggest_and_display(win, srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import Conversation
    cid = _setup(win, srv)
    mockki.peer_reply(srv, cid, "anna", "Echte Antwort der KI.")
    mockki.peer_reply(srv, cid, "anna", "Gefälschte Antwort.", ok=False)
    mockki.peer_reply(srv, cid, "anna", "Ohne Beleg.", meta=False)
    win.show_route(("chat", cid))
    conv = win.current.conv
    assert isinstance(conv, Conversation)
    pump(lambda: chat.cache.get(cid, {}).get("loaded") and len(chat.cache[cid]["msgs"]) == 17, 5)
    idle()
    states = [m.get("ai_state") for m in chat.cache[cid]["msgs"][-3:]]
    assert states == ["ok", "bad", "bad"]
    labs = _labels(conv)
    assert conv.ai_note.get_visible()
    assert any("Marin is allowed in this chat – only when mentioned" in (x or "") and "ki.example" in (x or "") for x in labs)
    assert "asked by Anna Beispiel" in labs
    assert labs.count("Presented as an AI reply, but not confirmed") == 2
    # Tippen: "@Ma" -> Vorschlag, Tab uebernimmt; Nachricht mit @Marin -> Hinweis
    buf = conv.entry.get_buffer()
    buf.set_text("Frage an @Ma")
    buf.place_cursor(buf.get_end_iter())
    idle()
    assert conv.ai_suggest.get_visible() and not conv.ai_hint.get_visible()
    assert conv._ai_apply()
    idle()
    txt = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
    assert txt == "Frage an @Marin " and conv.ai_hint.get_visible() and not conv.ai_suggest.get_visible()
    assert any("Goes to Marin" in (x or "") for x in _labels(conv.ai_hint))
    # Senden -> Frage -> Antwort erscheint als KI-Antwort ("asked by you")
    conv.send()
    pump(lambda: srv.ki_log and cid not in chat.ai_pending, 8)
    pump(lambda: chat.cache[cid]["msgs"][-1].get("ai_state") == "ok", 4)
    idle()
    assert srv.ki_log[-1][1]["q"] == "Frage an @Marin"
    assert "asked by you" in _labels(conv)
    # KI hier nicht mehr erlaubt (anderes Mitglied schaltet um): Hinweis weg, kein Vorschlag, keine Frage
    srv.chats[cid]["ai"] = False
    chat.on_live("chat.changed", {"chat_id": cid})
    pump(lambda: not chat.chat(cid).get("ai"), 4)
    idle()
    assert not conv.ai_note.get_visible()
    buf.set_text("@Ma")
    buf.place_cursor(buf.get_end_iter())
    idle()
    assert not conv.ai_suggest.get_visible()
    # Persona geaendert (Live-Ereignis): KI-Antworten zeigen den neuen Namen
    from hlidskjalf.assistant import ai
    srv.ai_persona["name"] = "Nova"
    srv.ai_status["persona"] = dict(srv.ai_persona)
    ai.on_live("ai.persona.changed", {"name": "Nova"})
    pump(lambda: "Nova" in _labels(conv), 4)
    assert "Nova" in _labels(conv) and "Marin" not in _labels(conv)


def test_toggle_and_errors(win, srv):
    from hlidskjalf.chat import chat
    cid = _setup(win, srv)
    srv.chats[cid]["ai"] = False
    done = []
    chat.set_ai(cid, True, done=lambda: done.append(1))
    pump(lambda: done, 4)
    assert srv.chats[cid]["ai"] and chat.chat(cid)["ai"] and srv.ki_toggles[-1] == (cid, True)
    # freie Gruppe ohne Admin-Rolle: 403 vom Server
    srv.chat_peer("ben", "Ben")
    g = srv.new_chat("free", ["anna", "test", "ben"], admin="anna")
    errs = []
    chat.set_ai(g, True, error=errs.append)
    pump(lambda: errs, 4)
    assert errs and getattr(errs[0], "code", None) == 403
    # Server-Fehler bei der Frage (z. B. Kontingent): Meldung, nichts gesendet
    chat.load_messages(cid)
    pump(lambda: chat.cache.get(cid, {}).get("loaded"), 4)
    srv.ki_error = (429, "Dein Tageskontingent für den Assistenten ist aufgebraucht.")
    n = len(srv.chats[cid]["msgs"])
    errs2 = []
    chat.ask_ai(cid, "@Marin hallo", "a" * 32, on_error=errs2.append)
    pump(lambda: errs2, 5)
    assert "429" in str(errs2[0]) and len(srv.chats[cid]["msgs"]) == n and cid not in chat.ai_pending   # Schein-Strom: ohne Text


def test_old_server_chat_unchanged(win, srv):
    """Server ohne Persona/KI im Chat: kein Hinweis, kein Vorschlag, kein Eintrag im Menue, Nachrichten wie bisher"""
    from hlidskjalf.chat import chat
    cid = _setup(win, srv, persona=False)
    assert "ai" not in chat.chat(cid) and not chat.ai_on(chat.chat(cid))
    win.show_route(("chat", cid))
    conv = win.current.conv
    pump(lambda: chat.cache.get(cid, {}).get("loaded"), 4)
    idle()
    assert not conv.ai_note.get_visible()
    conv.entry.get_buffer().set_text("@Ma")
    conv.entry.get_buffer().place_cursor(conv.entry.get_buffer().get_end_iter())
    idle()
    assert not conv.ai_suggest.get_visible() and not conv.ai_hint.get_visible()
    assert all(m.get("ai_state") is None for m in chat.cache[cid]["msgs"])
    assert not any(isinstance(w, Gtk.Label) and "Marin" in (w.get_label() or "") for w in _walk(conv))
