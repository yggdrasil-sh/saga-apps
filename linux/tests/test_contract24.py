"""2.4 Server-Vertrag nach Sicherheits-/Web-Runde 2 (docs/APP-API.md): Schluessel nur fuer Chat-Partner (Chat erst anlegen,
dann versiegeln), Unterzeile sub, oeffentlicher Name != Anmeldename (is_login), Nextcloud-Autor als Handle."""
import inspect
import os

from hk_helpers import pump

H = "~aaaaaaaaaaaaaaaa"
LINUX = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _src(name):
    with open(os.path.join(LINUX, "hlidskjalf", name), encoding="utf-8") as f:
        return f.read()


def test_no_keys_before_creating_a_chat():
    """Neue Unterhaltung, Nachrichtenanfrage, Gruppe: POST api/chats, gesendet (auch der Gruppenname) wird danach mit den
    Empfaengern des angelegten Chats - /api/chat/keys liefert ab 2.4 nur Chat-Partner und wird nie vorab gefragt."""
    from hlidskjalf import chatview
    assert "api/chat/keys" not in _src("chatview.py") and "api/chat/keys" not in _src("chat.py")
    new_chat = _src("chat.py").split("def new_chat(self, body, done, error):", 1)[1].split("\n    def ", 1)[0]
    assert 'bg(client.json, "POST", "api/chats"' in new_chat and "keys" not in new_chat
    free = inspect.getsource(chatview).split("def _create_free(self):", 1)[1].split("\ndef ", 1)[0]
    assert free.index("def titled(ch):") < free.index("self._open(")    # Titel erst im Rueckruf nach dem Anlegen
    assert 'ch.get("id")' in free.split("def titled(ch):", 1)[1]
    assert "chat.recipients(" in _src("chatview.py") or "def recipients(" in _src("chat.py")


def test_sub_for_namesakes():
    from hlidskjalf import sharing as S
    assert S.person_mark({"username": H, "sub": "anna-b"}) == "anna-b"
    assert S.person_mark({"username": "anna", "ref": H, "sub": ""}) == H          # leer: Handle aus ref
    assert S.person_mark({"username": "anna"}) == ""                               # aelterer Server: nie der Anmeldename
    assert S.with_mark("Person on this server", {"username": H, "sub": "anna-b"}) == "Person on this server · anna-b"


def test_login_hint_logic():
    from hlidskjalf.publish import login_hint
    assert login_hint({"name": "test", "is_login": True}, " Test ")
    assert not login_hint({"name": "test", "is_login": True}, "test-b")
    assert not login_hint({"name": "anna", "is_login": False}, "anna")
    assert not login_hint({"name": "test"}, "test") and not login_hint(None, "x")      # aelterer Server ohne Feld


def test_public_name_login_rejected_and_hint(win, srv):
    """PUT lehnt den Anmeldenamen ab (Meldung am Feld); frueher gewaehlter: Hinweis, nach neuem Namen weg"""
    from hlidskjalf.publish import PublicNameGroup
    g = PublicNameGroup(win)
    assert pump(lambda: g.get_visible(), 3)
    assert not g.loginwarn.get_visible()
    g.row.set_text("test")                                    # Anmeldename des Schein-Servers
    assert pump(lambda: g.status.get_label().startswith("Bitte nicht den Anmeldenamen"), 3) and not g.btn.get_sensitive()
    assert g.row.has_css_class("error") and srv.public_name is None
    srv.public_name = "test"                                  # frueher (vor 2.4) so gewaehlt
    g2 = PublicNameGroup(win)
    assert pump(lambda: g2.get_visible() and g2.row.get_text() == "test", 3)
    assert g2.loginwarn.get_visible() and "login name" in g2.loginwarn.get_label()
    g2.row.set_text("test-b")
    assert not g2.loginwarn.get_visible()
    assert pump(lambda: g2.btn.get_sensitive(), 3)
    g2.btn.emit("clicked")
    assert pump(lambda: srv.public_name == "test-b", 3)
    pump(None, 0.3)
    assert not g2.loginwarn.get_visible() and not g2.p.get("is_login")


def test_nextcloud_foreign_author_is_handle():
    """Fremde Bloecke tragen den Handle (Seiteninhalt und Status), ?a= nimmt ihn; eigene Bloecke bleiben eigene"""
    from hlidskjalf import nextcloud as N
    d = {"fileid": "4711", "author": H, "inst": "https://nc.example"}
    assert "a=%7Eaaaaaaaaaaaaaaaa" in N.proxy_path(5, d) or "a=~aaaaaaaaaaaaaaaa" in N.proxy_path(5, d)
    blocks = [{"type": "nextcloud", "data": d}]
    assert N.foreign_new(blocks, set(), "test") == [d]
    assert N.foreign_new(blocks, N.keys_of(blocks), "test") == []
    assert N.foreign_new([{"type": "nextcloud", "data": dict(d, author="test")}], set(), "test") == []
