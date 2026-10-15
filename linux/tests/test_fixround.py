"""Einheitliche Darstellung (alle Plattformen): Sicherheitsnummer 3 x 4, Kopplungscode eine Zeile (schmal 2 x 2),
"Echtheit nicht bestaetigt" voll deckend mit Erklaerung, Wiederherstellungsschluessel 2 x 4 mit Haekchen vor "Weiter",
Grenzen beim Oeffnen des Schluessel-Buendels, Geraetename, Hinweis bei fremden Seiten je Rolle."""
import json

import pytest
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
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label)]


# --- 1) Sicherheitsnummer und Kopplungscode --------------------------------------------------------------------------
def test_safety_number_3x4(win, srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import SafetyDialog
    from test_chat import _ready
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    r = dict(srv.peers["anna"]["user"]) if "user" in srv.peers["anna"] else None
    if r is None:
        r = {"username": "anna", "name": "Anna Beispiel", "ready": True, "ident_pub": C.b64(srv.peers["anna"]["ident"]["ident_pk"])}
    d = SafetyDialog(win, r)
    d.present(win)
    idle()
    num = next(x for x in _walk(d) if isinstance(x, Gtk.Label) and "hk-safety" in x.get_css_classes()).get_label()
    lines = num.split("\n")
    assert len(lines) == 3 and all(len(ln.split()) == 4 and all(len(g) == 5 and g.isdigit() for g in ln.split()) for ln in lines)
    assert chat.ready
    d.force_close()


def test_link_code_one_line_or_two(win):
    from hlidskjalf.chatview import CodeView, code_halves
    code = "12345 67890 11111 22222"
    assert code_halves(code) == ["12345 67890", "11111 22222"]
    cv = CodeView(code)
    # breit genug: eine Zeile
    box = Gtk.Box()
    box.append(cv)
    w = Gtk.Window(child=box, default_width=600, default_height=200)
    w.present()
    pump(None, 0.4)
    assert cv.text() == ["12345 67890 11111 22222"]
    # zu schmal fuer eine Zeile: zwei Zeilen zu je 2 Gruppen (nie mitten in einer Gruppe)
    one = cv.get_first_child().measure(Gtk.Orientation.HORIZONTAL, -1)[1]
    cv.set_size_request(one + 10, -1)
    cv.set_halign(Gtk.Align.START)
    w.set_default_size(one + 20, 200)
    pump(None, 0.4)
    assert cv.text() == ["12345 67890", "11111 22222"]
    w.destroy()


# --- 3) Echtheit nicht bestaetigt ------------------------------------------------------------------------------------
def test_unverified_opaque_with_explanation(win, srv):
    from hlidskjalf.chat import chat
    from test_chat import _ready
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(cid, "anna", "nicht echt", forge=True)
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    win.show_route(("chat", cid))
    pump(lambda: chat.cache.get(cid, {}).get("loaded"), 4)
    pump(None, 0.4)
    btn = next(x for x in _walk(win.current) if isinstance(x, Gtk.Button) and "hk-unv" in x.get_css_classes())
    assert "Authenticity not confirmed" in _labels(btn)
    bubble = btn
    while bubble is not None and "hk-bubble" not in bubble.get_css_classes():
        bubble = bubble.get_parent()
    assert bubble is not None and bubble.get_opacity() == 1.0
    css = open(__import__("hlidskjalf").__path__[0] + "/style.css").read()
    rule = next(ln for ln in css.splitlines() if ".hk-bubble.hk-bubble-warn" in ln)
    assert "opacity" not in rule                                      # voll deckend, nur Rahmen
    btn.emit("clicked")
    pump(None, 0.3)
    d = win.get_visible_dialog()
    assert isinstance(d, Adw.AlertDialog) and "confirmed device of the sender" in d.get_body()
    assert d.has_response("safety")
    d.emit("response", "safety")
    pump(None, 0.4)
    from hlidskjalf.chatview import SafetyDialog
    assert isinstance(win.get_visible_dialog(), SafetyDialog)
    win.get_visible_dialog().force_close()


# --- 5) Wiederherstellungsschluessel ---------------------------------------------------------------------------------
def test_recovery_key_lines_and_checkbox(win, srv):
    from hlidskjalf.chat import recovery_lines
    from hlidskjalf.chatview import SetupDialog
    rk = C.recovery_key()
    lines = recovery_lines(rk).split("\n")
    assert len(lines) == 2 and all(len(ln.split("-")) == 4 and all(len(g) == 4 for g in ln.split("-")) for ln in lines)
    d = SetupDialog(win)
    d.present(win)
    d._create()
    pump(None, 0.3)
    p = d.keys_pages
    key_lbl = next(x for x in _walk(p.p1) if isinstance(x, Gtk.Label) and "hk-recovery" in x.get_css_classes())
    assert key_lbl.get_label() == recovery_lines(p.rk) and not key_lbl.get_wrap()
    assert "Copy" in [x.get_label() for x in _walk(p.p1) if isinstance(x, Gtk.Button)]
    assert any("Without it your messages are lost" in (t or "") for t in _labels(p.p1))
    warn = next(x for x in _walk(p.p1) if isinstance(x, Gtk.Label) and "Without it" in (x.get_label() or ""))
    assert "heading" in warn.get_css_classes() and "dim-label" not in warn.get_css_classes()
    assert not p.cont.get_sensitive()                                 # "Weiter" erst nach dem Haekchen
    p.stored.set_active(True)
    assert p.cont.get_sensitive()
    d.force_close()


# --- 8) Grenzen beim Oeffnen des Buendels ----------------------------------------------------------------------------
@pytest.mark.parametrize("ops,mem", [(11, 8192), (1, 256 * 1024 * 1024 + 1024), (0, 8192), (1, 1024)])
def test_bundle_limits(ops, mem):
    rk = C.recovery_key()
    k = C.identity()
    b = json.loads(C.seal_bundle("test", rk, k, ops=1, mem=8192))
    b["ops"], b["mem"] = ops, mem
    with pytest.raises(C.CryptoError) as e:
        C.open_bundle("test", rk, json.dumps(b))
    assert e.value.args[0] == "kdf"


def test_bundle_limits_message(win, srv):
    """zu grosse Werte im Buendel: "Unbekanntes Schluesselformat" statt Rechnen mit Gigabytes"""
    from hlidskjalf.chat import ChatError, chat
    from test_chat import _ready
    rk = _ready(srv)
    b = json.loads(srv.idents["test"]["bundle"])
    b["mem"] = 1 << 30
    srv.idents["test"]["bundle"] = json.dumps(b)
    chat.refresh()
    pump(lambda: chat.me and json.loads(chat.me["identity"]["bundle"])["mem"] == 1 << 30, 4)
    with pytest.raises(ChatError) as e:
        chat.unlock(rk)
    assert str(e.value) == "Unknown key format."


# --- 9) Geraetename --------------------------------------------------------------------------------------------------
def test_device_name(monkeypatch):
    from hlidskjalf import config
    monkeypatch.setattr(config, "_pretty_hostname", lambda: "")
    monkeypatch.setattr(config.GLib, "get_host_name", lambda: "werkbank.lan")
    assert config.device_name() == "werkbank (Linux)"
    monkeypatch.setattr(config.GLib, "get_host_name", lambda: "d4a2f9dd5c93")
    monkeypatch.setattr(config, "_os_name", lambda: "Fedora Linux")
    assert config.device_name() == "Fedora Linux"
    monkeypatch.setattr(config, "_pretty_hostname", lambda: "Annas Laptop")
    assert config.device_name() == "Annas Laptop (Linux)"


# --- 10) fremde Seite: Hinweis je Rolle ------------------------------------------------------------------------------
def test_remote_info_by_role():
    from hlidskjalf.sharing import remote_info
    p = {"id": -2049, "remote": {"host": "b.example"}}
    assert "comment" in remote_info(dict(p, role="edit")) and "edit access" in remote_info(dict(p, role="edit"))
    assert remote_info(dict(p, role="comment")) == "This page is on b.example. Here you can read and comment on it."
    assert remote_info(dict(p, role="view")) == "This page is on b.example. Here you can read it."
