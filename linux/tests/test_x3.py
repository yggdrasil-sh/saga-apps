"""Saga 2.5 - Chat-Stufe 3 und Chat-Grundlagen (Reaktionen, Bearbeiten, Sprachnachrichten) gegen den nachgebauten Server
(mockx3.py auf mockfed.py: prueft Signaturen v2 und der Reaktionen, Empfaenger exakt): Krypto-Stufe vs. "anderer Server",
Vertrauens-Uebernahme v1 -> v2 (nur gleicher Schluessel), Umstell-Systemzeile, Altbestand (Anhaenge v1), Reaktionen
(Schnellleiste, Chips, live, ersetzen, zuruecknehmen, gefaelscht), Bearbeiten (Leiste, "bearbeitet", fremd, Pfeil hoch),
Sprachnachrichten (Aufnahme mit audiotestsrc statt Mikrofon, Grenzen, Vorschau, Wellenform, Wiedergabe ueber appsrc)."""
import base64
import json
import os

import mockfed
import mockx3
import pytest
from gi.repository import Gtk

from hk_helpers import idle, pump
from hlidskjalf import chatcrypto as C
from hlidskjalf import voice

HERE = os.path.dirname(os.path.abspath(__file__))
os.environ.setdefault("HK_AUDIO_SINK", "fakesink sync=true")
TONE = "audiotestsrc is-live=true wave=sine freq=440 volume=0.4"
GST = voice.can_play() and voice.V.has("opusenc", "oggmux", "audiotestsrc", "level")
needs_gst = pytest.mark.skipif(not GST, reason="GStreamer mit Opus/Ogg fehlt")


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label) and x.get_label()]


def _ready(srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.store import store
    mockx3.enable(srv)
    store.server_version = "2.5.0"
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    k = chat.setup(C.recovery_key())
    chat.adopt_keys(k)
    pump(lambda: chat.status == "ready" and chat.me and "react" in (chat.me.get("features") or []), 5)
    pump(lambda: srv.fed["certs"]["dset"] is not None, 5)
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


def _local_v3(srv, name="Bert Hier"):
    """Person auf DIESEM Server in einem Chat der Stufe 3 (v 3, Heim hier, host = eigener Server)"""
    h = mockfed.remote_person(srv, name, mockfed.HOST)
    cid = mockfed.new_fed_chat(srv, h, extra=dict(mockx3.V3))
    return h, cid


def _msgs(cid):
    from hlidskjalf.chat import chat
    return (chat.cache.get(cid) or {}).get("msgs") or []


def _reload(cid):
    from hlidskjalf.chat import chat
    chat.cache.pop(cid, None)
    done = {}
    chat.load_messages(cid, done=lambda: done.setdefault("ok", 1))
    pump(lambda: done, 5)
    return _msgs(cid)


# --- Stufe 3: Krypto ueber Handles, aber lokal ------------------------------------------------------------------------------
def test_levels_and_header():
    from hlidskjalf import api, chatstatus
    from hlidskjalf.chat import is_fed, is_remote, x3_ok
    assert api.CHAT_V == "3"
    for c, fed, rem in (({"v": 1}, False, False), ({"v": 2}, True, True), ({"v": 3}, True, False), (None, False, False)):
        assert is_fed(c) is fed and is_remote(c) is rem and chatstatus._fed(c) is fed
    assert x3_ok({"v": 3, "x3_ok": True}) and not x3_ok({"v": 3}) and not x3_ok({"v": 3, "x3_ok": True, "request": True})
    assert not x3_ok({"v": 3, "x3_ok": True, "readonly": True}) and not x3_ok({"v": 1, "x3_ok": True})


def test_sys_crypto_line():
    from hlidskjalf.chat import sys_text
    t = sys_text({"type": "crypto", "level": 3, "v": 1}, None)
    assert "stable Saga addresses" in t and "compare" not in t
    assert "compare the safety numbers again" in sys_text({"type": "crypto", "level": 3, "verify": True}, None)


def test_v3_local_chat_ui(win, srv):
    """Chat der Stufe 3 mit einer Person dieses Servers: v2-Wege (Handles), aber keine "anderer Server"-Hinweise"""
    from hlidskjalf.chat import chat
    _ready(srv)
    h, cid = _local_v3(srv)
    mockfed.peer_send_v2(srv, cid, "Hallo in Stufe 3")
    _open(win, cid)
    m = _msgs(cid)[0]
    assert m["state"] == "ok" and m["sender_handle"] == h and m.get("_k")
    title, sub = win.current.header()
    assert title == "Bert Hier" and "on " not in sub and "saga.test" not in sub
    row_labels = _labels(win.current.list.box)
    assert not any("saga.test" in x for x in row_labels)
    # gesendet wird im Format v2 (Signatur v2 an chat_ref, exakte Empfaenger)
    resp, _miss = chat.send(cid, {"v": 1, "type": "text", "text": "Antwort"})
    assert resp["ok"] and srv.chats[cid]["msgs"][-1]["sender_handle"] == srv.fed["me"]


# --- Vertrauens-Uebernahme --------------------------------------------------------------------------------------------------
def test_trust_migrate_same_key_only(srv):
    from hlidskjalf.chat import h2u_get, h2u_note, tofu_for, trust2_get, trust_set, trust_v1_entry
    me = "test"
    ident = C.identity()
    ipub = C.b64(ident["ident_pk"])
    other = C.b64(C.identity()["ident_pk"])
    h, h2, h3 = "~abcdefghijklmnop@saga.test", "~qrstuvwxyz234567@saga.test", "~aaaabbbbccccdddd@saga.test"
    # members[].handle aus Chats v1 (nicht aus Stufe 3, nie das eigene Konto)
    n = h2u_note(me, [{"v": 1, "members": [{"username": "anna", "handle": h}, {"username": "test", "me": True, "handle": h3},
                                           {"username": "carl", "handle": h2}]},
                      {"v": 3, "members": [{"username": h3, "handle": h3}]}])
    assert n == 2 and h2u_get(me, h) == "anna" and h2u_get(me, h2) == "carl" and h2u_get(me, h3) is None
    trust_set(me, "anna", ident_pub=ipub, verified=True)
    assert trust_v1_entry(me, "anna") == {"ident": ipub, "verified": True, "changed": False}
    res, store, _st = tofu_for(me, h, ipub, 2)
    assert res == "trust" and store == {"ident": ipub, "verified": True, "dver": 2, "migrated": True}
    # anderer Schluessel als im v1-Stand, v1 ueberprueft: Blockade, nichts uebernommen (Server schiebt eine falsche Zuordnung unter)
    trust_set(me, "carl", ident_pub=ipub, verified=True)
    res, store, _st = tofu_for(me, h2, other, 1)
    assert res == "block" and store is None and trust2_get(me, h2) is None
    # nicht ueberprueft + anderer Schluessel: Warnung
    trust_set(me, "carl", ident_pub=ipub, verified=False)
    res, store, _st = tofu_for(me, h2, other, 1)
    assert res == "warn" and store["changed"] and store["old"] == ipub
    # offener Wechsel (pending) in v1 = geaendert
    trust_set(me, "carl", ident_pub=ipub, pending=other, verified=False)
    assert trust_v1_entry(me, "carl") == {"ident": other, "verified": False, "changed": True, "old": ipub}
    # schon ein trust2-Eintrag: v1 zaehlt nicht mehr (tofu_v2)
    from hlidskjalf.chat import trust2_set
    trust2_set(me, h, {"ident": ipub, "verified": False, "dver": 2})
    res, store, st = tofu_for(me, h, ipub, 2)
    assert res == "trust" and store is None and st["verified"] is False


def test_trust_migrate_in_app_with_safety_hint(win, srv):
    """Person war in v1 ueberprueft, Chat jetzt Stufe 3: "ueberprueft" bleibt, Sicherheitsnummer mit Hinweis"""
    from hlidskjalf import chatview
    from hlidskjalf.chat import chat, h2u_note, trust_set
    _ready(srv)
    h, cid = _local_v3(srv, "Vera Treu")
    p = srv.fed["remote"][h]
    h2u_note(chat.uid, [{"v": 1, "members": [{"username": "vera", "handle": h}]}])
    trust_set(chat.uid, "vera", ident_pub=C.b64(p["ident"]["ident_pk"]), verified=True)
    mockfed.peer_send_v2(srv, cid, "Ich bin es")
    _open(win, cid)
    pump(lambda: chat.fed_verified(h), 5)
    assert chat.fk[h]["stored"].get("migrated") and chat.fed_verified(h)
    assert "verified" in win.current.header()[1]
    m = next(x for x in chat.chat(cid)["members"] if x.get("handle") == h)
    d = chatview.SafetyDialogV2(win, cid, m, chat.fk[h])
    d.present(win)
    pump(None, 0.3)
    labs = _labels(d.get_child())
    d.force_close()
    assert any(x.startswith("New presentation, same key") for x in labs)
    assert not any("across servers" in x for x in labs)                 # lokal: kein "ist auf ..."


def test_v1_attachment_ad_after_switch(srv):
    """Altbestand: Anhaenge v1 haben die Chat-ID als AD - auch wenn der Chat inzwischen chat_ref hat"""
    from hlidskjalf import chatmedia
    blob, key = C.encrypt_file(7, b"alt")
    chatmedia.REFS[7] = "0123@saga.test"
    with pytest.raises(C.CryptoError):
        chatmedia.decrypt(7, blob, key)
    assert chatmedia.decrypt(7, blob, key, chatmedia.ad1({"_ad": 1})) == b"alt"
    chatmedia.REFS.pop(7, None)


# --- Reaktionen -------------------------------------------------------------------------------------------------------------
def test_reactions_quickbar_chips_live(win, srv):
    from hlidskjalf import chatx3
    from hlidskjalf.chat import QUICK, can_react, chat
    _ready(srv)
    h, cid = _local_v3(srv)
    pm = mockfed.peer_send_v2(srv, cid, "Reagier mal")
    conv = _open(win, cid)
    m = _msgs(cid)[0]
    assert can_react(chat.chat(cid), m)
    pop = conv._msg_menu(conv.rows_by_id[m["id"]].get_first_child(), m)
    pump(None, 0.3)
    bar = [w for w in _walk(pop) if isinstance(w, Gtk.Box) and w.has_css_class("hk-rq")][0]
    keys = [b.get_label() for b in _walk(bar) if isinstance(b, Gtk.Button)]
    assert keys == list(QUICK) + ["+"] and len(QUICK) == 6
    [b for b in _walk(bar) if isinstance(b, Gtk.Button) and b.get_label() == "👍"][0].emit("clicked")
    pump(lambda: srv.x3["react_log"], 3)
    pump(None, 0.3)
    r = srv.x3["react_log"][-1]
    assert r["by"] == srv.fed["me"] and r["id"] == pm["id"] and "e" not in r
    # die gesendete Reaktion ist echt: Server-Pruefung (Signatur) + Inhalt mit dem abgeleiteten Schluessel
    K = mockx3.peer_key(srv, srv.chats[cid], mockx3._find(srv.chats[cid], pm["id"]))
    assert C.decrypt_reaction(K, srv.chats[cid]["fed"]["ref"], pm["id"], r) == "👍"
    chips = [w for w in _walk(conv.msgs) if isinstance(w, Gtk.Button) and w.has_css_class("hk-rx-chip")]
    assert len(chips) == 1 and chips[0].has_css_class("hk-rx-mine") and _labels(chips[0]) == ["👍", "1"]
    # Gegenueber reagiert (live chat.react): zwei Personen, Zahl je Emoji
    pr = mockx3.peer_react(srv, cid, pm["id"], "👍")
    chat.on_live("chat.react", {"chat_id": cid, "items": [dict(pr, id=pm["id"])]})
    pump(lambda: len(_msgs(cid)[0].get("rx") or []) == 2, 3)
    pump(None, 0.3)
    chips = [w for w in _walk(conv.msgs) if isinstance(w, Gtk.Button) and w.has_css_class("hk-rx-chip")]
    assert len(chips) == 1 and _labels(chips[0]) == ["👍", "2"] and chips[0].has_css_class("hk-rx-mine")
    # Klick auf den eigenen Chip nimmt zurueck
    chips[0].emit("clicked")
    pump(lambda: len(srv.x3["react_log"]) == 2, 3)
    assert C.decrypt_reaction(K, srv.chats[cid]["fed"]["ref"], pm["id"], srv.x3["react_log"][-1]) == ""
    pump(None, 0.3)
    assert [x["e"] for x in _msgs(cid)[0]["rx"]] == ["👍"] and _msgs(cid)[0]["rx"][0]["by"] == h
    # "Reaktionen von ..." (Rechtsklick auf den Chip -> Popover mit Namen; Menue "Reaktionen …" -> Dialog)
    chip = [w for w in _walk(conv.msgs) if isinstance(w, Gtk.Button) and w.has_css_class("hk-rx-chip")][0]
    p2 = chatx3.who_popover(chip, chat.chat(cid), "👍", [h], conv)
    assert "Bert Hier" in _labels(p2)
    p2.popdown()
    d = chatx3.reactions_dialog(win, chat.chat(cid), _msgs(cid)[0])
    assert "Bert Hier" in " ".join(x.get_title() for x in _walk(d) if hasattr(x, "get_title") and isinstance(x.get_title(), str))
    d.force_close()


def test_reactions_debounce_newest_and_forged(win, srv):
    from hlidskjalf.chat import chat
    _ready(srv)
    h, cid = _local_v3(srv)
    pm = mockfed.peer_send_v2(srv, cid, "eins")
    _open(win, cid)
    for e in ("❤️", "😂", "😮"):                       # innerhalb von 500 ms: nur die letzte Wahl geht raus
        chat.react(cid, pm["id"], e)
        pump(None, 0.1)
    pump(lambda: srv.x3["react_log"], 3)
    pump(None, 0.7)
    assert len(srv.x3["react_log"]) == 1
    # gefaelschte Reaktion (anderes Geraet signiert) und neuere echte: nur die echte zaehlt; ungueltige neueste -> Person faellt weg
    mockx3.peer_react(srv, cid, pm["id"], "🙏")
    ms = _reload(cid)
    assert sorted(x["e"] for x in ms[0]["rx"]) == ["😮", "🙏"]
    mockx3.peer_react(srv, cid, pm["id"], "💩", forge=True)
    ms = _reload(cid)
    assert [x["e"] for x in ms[0]["rx"]] == ["😮"]


def test_no_reactions_without_x3(win, srv):
    """ohne x3_ok (z. B. anderer Server ohne chat3) bzw. als Nachrichtenanfrage: keine Schnellleiste, kein Bearbeiten"""
    from hlidskjalf.chat import can_edit, can_react, chat
    _ready(srv)
    h = mockfed.remote_person(srv, "Olga Alt", "b.example")
    cid = mockfed.new_fed_chat(srv, h)
    mockfed.peer_send_v2(srv, cid, "ohne x3")
    conv = _open(win, cid)
    m = _msgs(cid)[0]
    assert not can_react(chat.chat(cid), m)
    pop = conv._msg_menu(conv.rows_by_id[m["id"]].get_first_child(), m)
    assert not [w for w in _walk(pop) if isinstance(w, Gtk.Box) and w.has_css_class("hk-rq")]
    pop.popdown()
    assert not conv.mic_btn.get_visible()
    resp, _ = chat.send(cid, {"v": 1, "type": "text", "text": "meins"})
    chat.load_messages(cid)
    pump(lambda: len(_msgs(cid)) == 2, 3)
    assert not can_edit(chat.chat(cid), _msgs(cid)[-1])


# --- Bearbeiten -------------------------------------------------------------------------------------------------------------
def test_edit_bar_send_and_rules(win, srv):
    from gi.repository import Gdk
    from hlidskjalf.chat import can_edit, chat
    _ready(srv)
    h, cid = _local_v3(srv)
    conv = _open(win, cid)
    chat.send(cid, {"v": 1, "type": "text", "text": "Treffen wir uns um 8?"})
    chat.load_messages(cid)
    pump(lambda: _msgs(cid), 3)
    m = _msgs(cid)[0]
    assert m["mine"] and can_edit(chat.chat(cid), m)
    # Pfeil hoch im leeren Feld: letzte eigene Nachricht bearbeiten
    assert conv._key(None, Gdk.KEY_Up, 0, Gdk.ModifierType(0))
    assert conv.editing is m and conv.edit_bar.get_visible()
    buf = conv.entry.get_buffer()
    assert buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False) == "Treffen wir uns um 8?"
    assert not conv.mic_btn.get_visible()
    # Esc bricht ab
    assert conv._key(None, Gdk.KEY_Escape, 0, Gdk.ModifierType(0))
    assert conv.editing is None and not conv.edit_bar.get_visible()
    conv.edit_start(m)
    buf.set_text("Treffen wir uns um 9?")
    conv.send()
    pump(lambda: srv.x3["edit_log"] and conv.editing is None, 5)
    e = srv.x3["edit_log"][-1]
    ref = srv.chats[cid]["fed"]["ref"]
    p = srv.fed["remote"][h]
    api = dict(e, key=e["keys"][f"dev:{p['dev']['id']}@{p['host']}"])
    assert C.decrypt_v2(ref, api, p["dev"]["enc_pk"], p["dev"]["enc_sk"]) == {"edit": m["id"], "t": "Treffen wir uns um 9?"}
    pump(None, 0.3)
    assert _msgs(cid)[0]["text"] == "Treffen wir uns um 9?" and "edited" in _labels(conv.msgs)
    # frisch geladen: Original mit edit -> neuester Text, geprueft (gleicher Absender, edit_of)
    ms = _reload(cid)
    pump(None, 0.3)
    assert ms[0]["text"] == "Treffen wir uns um 9?" and ms[0]["orig_text"] == "Treffen wir uns um 8?" and ms[0]["edit_n"] == 1
    ed = [w for w in _walk(conv.msgs) if isinstance(w, Gtk.Label) and w.get_label() == "edited"][0]
    assert ed.get_tooltip_text().startswith("Edited: ")
    # fremde Nachricht: kein "Bearbeiten"; Bearbeitung der anderen Person an ihrer eigenen wird angezeigt
    pm = mockfed.peer_send_v2(srv, cid, "Von Bert")
    ms = _reload(cid)
    bm = next(x for x in ms if x["id"] == pm["id"])
    assert not can_edit(chat.chat(cid), bm)
    mockx3.peer_edit(srv, cid, pm["id"], "Von Bert (korrigiert)")
    chat.on_live("chat.edited", {"chat_id": cid, "id": pm["id"], "seq": bm["seq"], "edit_seq": 99})
    pump(lambda: (chat._cached(cid, pm["id"]) or {}).get("edited_at"), 5)
    assert chat._cached(cid, pm["id"])["text"] == "Von Bert (korrigiert)"


def test_edit_forged_by_other_sender_ignored(win, srv):
    """Eine 'Bearbeitung', die der Server einer fremden Nachricht unterschiebt (anderer Absender), zeigt das Original"""
    from hlidskjalf.chat import chat
    _ready(srv)
    h, cid = _local_v3(srv)
    _open(win, cid)
    chat.send(cid, {"v": 1, "type": "text", "text": "Mein Original"})
    chat.load_messages(cid)
    pump(lambda: _msgs(cid), 3)
    mine = _msgs(cid)[0]
    o = mockx3._find(srv.chats[cid], mine["id"])
    o["sender_handle"] = srv.fed["me"]
    fake = mockx3.peer_edit(srv, cid, mockfed.peer_send_v2(srv, cid, "x")["id"], "Untergeschoben")
    o["edit"], o["edited_at"], o["edit_n"] = fake, 1.0, 1
    ms = _reload(cid)
    m = next(x for x in ms if x["id"] == mine["id"])
    assert m["text"] == "Mein Original" and not m.get("edited_at")


def test_can_edit_window_and_limits():
    import time as _t
    from hlidskjalf.chat import can_edit
    c = {"v": 3, "x3_ok": True, "my_handle": "~me@x"}
    m = {"state": "ok", "mine": True, "sender_handle": "~me@x", "text": "hi", "created": _t.time() - 10, "edit_n": 0}
    lim = {"edit_window": 86400, "edit_max": 20}
    assert can_edit(c, m, lim=lim)
    assert not can_edit(c, dict(m, created=_t.time() - 86401), lim=lim)
    assert not can_edit(c, dict(m, edit_n=20), lim=lim)
    assert not can_edit(c, dict(m, ai_mark=True), lim=lim)
    assert not can_edit(c, dict(m, files=[{"id": "x", "voice": True}], text=""), lim=lim)
    assert not can_edit(c, dict(m, sender_handle="~other@x"), lim=lim)
    assert not can_edit(dict(c, x3_ok=False), m, lim=lim)


# --- Sprachnachrichten ------------------------------------------------------------------------------------------------------
def test_waveform_like_web():
    lv = [0.0, 0.1, 0.5, 0.2] * 40
    wf = voice.waveform(lv)
    assert len(wf) == 64 and max(wf) == 255 and min(wf) >= 0
    assert voice.waveform([]) == bytes(64)
    assert voice.fmt(0) == "0:00" and voice.fmt(61.4) == "1:01" and voice.fmt(900) == "15:00"


def _record(sec, max_s=900):
    out = {}
    r = voice.Recorder(src=TONE, max_s=max_s, on_limit=lambda: out.setdefault("limit", True))
    r.start()
    from gi.repository import GLib
    GLib.timeout_add(int(sec * 1000), lambda: (r.stop(lambda x: out.setdefault("r", x), lambda e: out.setdefault("e", e)), False)[1])
    pump(lambda: "r" in out or "e" in out, sec + 8)
    return out


@needs_gst
def test_record_audiotestsrc_ogg_opus():
    out = _record(1.6)
    r = out["r"]
    info = voice.ogg_opus_info(r["data"])
    assert info and info["channels"] == 1 and info["rate"] == 48000 and info["tags"] and info["eos"]
    assert 0.4 < r["dur"] < 2.4 and len(r["wf"]) == 64              # Live-Quelle: Start unter Last verzoegert
    d = voice.probe_duration(r["data"])
    assert d and abs(d - r["dur"]) < 0.05
    kbps = len(r["data"]) * 8 / r["dur"] / 1000
    assert kbps < 60, kbps                                 # Sinuston (schwer fuer VBR) - Sprache liegt bei ~24 kbit/s


@needs_gst
def test_web_muxer_file_decodes_and_plays():
    """Datei aus dem Web-Muxer (static/chat-voice.js oggOpus, aufgenommen in Chromium): GStreamer dekodiert sie, Dauer passt,
    der Spieler (appsrc, Zufallszugriff) spielt sie mit 2x zu Ende"""
    data = open(os.path.join(HERE, "voice_web.ogg"), "rb").read()
    info = voice.ogg_opus_info(data)
    meta = json.load(open(os.path.join(HERE, "voice_web.json")))
    assert info and abs(info["dur"] - meta["dur"]) < 0.1
    d = voice.probe_duration(data)
    assert d and abs(d - meta["dur"]) < 0.1
    p = voice.Player(voice.BytesSource(data), meta["dur"], rate=2.0)
    ended = []
    p.connect("ended", lambda *_: ended.append(1))
    p.play()
    pump(lambda: ended or p.error, meta["dur"] / 2 + 6)
    p.close()
    assert ended and not p.error


@needs_gst
def test_player_seek_and_rate():
    out = _record(2.2)
    data, dur = out["r"]["data"], out["r"]["dur"]
    p = voice.Player(voice.BytesSource(data), dur)
    p.play()
    pump(lambda: p.position() > 0.3, 5)
    p.seek(0.5)
    pump(None, 0.3)
    assert abs(p.position() - dur / 2) < 0.6
    p.set_rate(1.5)
    pump(None, 0.2)
    assert p.rate == 1.5
    ended = []
    p.connect("ended", lambda *_: ended.append(1))
    pump(lambda: ended, 6)
    assert ended and p.pos == 0.0
    p.close()


class _Conv:
    def __init__(self):
        self.toasts, self.ended, self.sent = [], [], []

    def toast(self, t):
        self.toasts.append(t)

    def record_ended(self, t):
        self.ended.append(t)

    def send_voice(self, res):
        self.sent.append(res)


@needs_gst
def test_record_bar_limits(monkeypatch):
    from hlidskjalf.chatx3 import RecordBar
    monkeypatch.setenv("HK_VOICE_SRC", TONE)
    # unter 1 s: verworfen mit Hinweis
    c = _Conv()
    bar = RecordBar(c)
    assert bar.start() and bar.state == "rec"
    pump(None, 0.4)
    bar.stop()
    pump(lambda: c.ended, 6)
    assert c.ended == ["Too short – not sent."] and not c.sent
    # Grenze (hier 3 s statt 15 min): Warnung eine "Minute" vorher (WARN_BEFORE_S), dann Stopp und Vorschau
    monkeypatch.setattr(voice, "WARN_BEFORE_S", 1)
    c = _Conv()
    bar = RecordBar(c, max_s=3)
    bar.start()
    pump(lambda: bar.state == "prev", 10)
    assert bar.state == "prev" and 2.5 < bar.res["dur"] <= 3.0
    assert "One more minute – then the recording ends." in c.toasts and any("recording ended" in t for t in c.toasts)
    assert bar.preview is not None and bar.preview.dur == bar.res["dur"]
    labels = [w.get_label() for w in _walk(bar) if isinstance(w, Gtk.Button) and w.get_label()]
    assert "Send" in labels
    bar.send()
    assert c.sent and c.sent[0]["dur"] == bar.res["dur"] and bar.state == "send"
    bar.sent(True)
    assert c.ended == [None]
    # Abbrechen waehrend der Aufnahme: nichts gesendet
    c = _Conv()
    bar = RecordBar(c)
    bar.start()
    pump(None, 0.5)
    bar.cancel()
    assert c.ended == [None] and not c.sent and bar.rec is None


@needs_gst
def test_voice_send_and_bubble(win, srv, monkeypatch):
    """Sprachnachricht senden (saga-file/v2 beim Schein-Server) und empfangen: Spieler mit 64 Balken, Dauer aus dur, Tempo"""
    from hlidskjalf import chatx3
    from hlidskjalf.chat import chat
    monkeypatch.setenv("HK_VOICE_SRC", TONE)
    _ready(srv)
    h, cid = _local_v3(srv)
    conv = _open(win, cid)
    assert conv.mic_btn.get_visible() and not conv.send_btn.get_visible()
    conv.entry.get_buffer().set_text("x")
    pump(None, 0.1)
    assert not conv.mic_btn.get_visible() and conv.send_btn.get_visible()
    conv.entry.get_buffer().set_text("")
    # Gegenueber schickt eine Sprachnachricht
    out = _record(1.5)
    pm, fid = mockx3.peer_voice(srv, cid, out["r"]["data"], round(out["r"]["dur"], 1), out["r"]["wf"])
    chat.load_messages(cid)
    pump(lambda: _msgs(cid), 5)
    pump(None, 0.5)
    vv = conv.voice_views.get(fid)
    assert isinstance(vv, chatx3.VoiceView) and vv.get_root() is not None
    assert vv.time.get_label() == voice.fmt(round(out["r"]["dur"], 1)) and len(vv.wave.vals) == 64
    vv.play()
    pump(lambda: vv.player and vv.player.playing and vv.player.pos > 0.2, 6)
    assert vv.player.playing and not vv.player.error
    vv.next_speed()
    assert vv.player.rate == 1.5 and vv.speed_btn.get_label() == "1.5×"
    vv.next_speed()
    vv.next_speed()
    vv.stop()
    # selbst aufnehmen (Klick startet, Klick stoppt), Vorschau, senden
    conv.mic_btn.emit("clicked")
    pump(lambda: conv.rec_bar is not None and conv.rec_bar.state == "rec", 5)
    assert not conv.input_row.get_visible()
    pump(None, 1.4)
    conv.rec_bar.stop()
    pump(lambda: conv.rec_bar.state == "prev", 6)
    conv.rec_bar.send()
    pump(lambda: conv.rec_bar is None, 10)
    assert conv.input_row.get_visible()
    sm = srv.chats[cid]["msgs"][-1]
    p = srv.fed["remote"][h]
    api = dict(sm, key=sm["keys"][f"dev:{p['dev']['id']}@{p['host']}"], sender_handle=srv.fed["me"])
    body = C.decrypt_v2(srv.chats[cid]["fed"]["ref"], api, p["dev"]["enc_pk"], p["dev"]["enc_sk"])
    f = body["files"][0]
    assert f["voice"] is True and f["x"] == 2 and f["m"] == "audio/ogg; codecs=opus" and f["n"] == "voice.ogg"
    assert 1.0 < f["dur"] < 2.5 and len(base64.b64decode(f["wf"])) == 64 and body["text"] == ""
