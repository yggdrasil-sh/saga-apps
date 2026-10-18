"""Chat-Personensuche: Hinweis bei name@server/E-Mail statt "Keine Treffer" (Chats ueber Servergrenzen gibt es noch
nicht); Teilen-Dialog: remote_hint vom Server (GET api/share/targets) statt "Niemand gefunden." - aeltere Server ohne Feld
wie bisher."""
from gi.repository import Gtk

from hk_helpers import pump


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def test_fed_address():
    from hlidskjalf.chat import fed_address, fed_hint_show
    for q in ("bob@saga.example.org", "@bob@saga.example.org", " vorname.name@firma.de ", "bob@host:8163", "@sc-fed@b.test:8163"):
        assert fed_address(q), q
    for q in ("bob", "bob@", "@bob", "bob@localhost", "bob@@x.de", "bob @x.de", "a@b@c.de", "Bob Bauer", "", None):
        assert not fed_address(q), q
    assert fed_hint_show("bob@saga.example.org", 0)
    assert not fed_hint_show("bob@saga.example.org", 1)          # lokale Treffer haben Vorrang
    assert not fed_hint_show("bob", 0)


def test_fed_guide_url():
    from hlidskjalf.chat import fed_guide_url
    assert fed_guide_url("de").endswith("docs/ANLEITUNG.md#mit-anderen-saga-instanzen-teilen-f%C3%B6deration")
    assert fed_guide_url("es").endswith("docs/es/GUIA.md#compartir-con-otras-instancias-de-saga-federaci%C3%B3n")
    for lang in ("en", "ru", "th", "fr", "", None):
        assert fed_guide_url(lang).endswith("docs/en/GUIDE.md#sharing-with-other-saga-instances-federation"), lang


def test_nobody_text_remote_hint():
    from hlidskjalf.i18n import _
    from hlidskjalf.sharing import nobody_text
    assert nobody_text({"users": [], "groups": [], "remote": [], "remote_hint": "Mit x.de ist dieser Server nicht verbunden."}) \
        == "Mit x.de ist dieser Server nicht verbunden."
    assert nobody_text({"users": [], "groups": []}) == _("No one found.")        # aelterer Server
    assert nobody_text({"remote_hint": None}) == _("No one found.")
    assert nobody_text({"remote_hint": "  "}) == _("No one found.")
    assert nobody_text(None) == _("No one found.")


def test_new_chat_dialog_hint(win):
    from hlidskjalf.chat import fed_hint_texts
    from hlidskjalf.chatview import NewChatDialog
    from hlidskjalf.i18n import _
    d = NewChatDialog(win)
    d.present(win)
    text, link = fed_hint_texts()

    def show(q, j):
        d.q = q
        d._show(q, j)
        pump(None, 0.05)
        ws = _walk(d.results)
        return [x.get_label() for x in ws if isinstance(x, Gtk.Label)], [x for x in ws if isinstance(x, Gtk.LinkButton)]
    labels, links = show("bob@saga.example.org", {"users": [], "groups": []})
    assert text in labels and len(links) == 1 and links[0].get_label() == link
    assert links[0].get_uri().startswith("https://github.com/yggdrasil-sh/saga/blob/main/docs/")
    labels, links = show("bob", {"users": [], "groups": []})
    assert _("No matches.") in labels and not links
    labels, links = show("bob@saga.example.org", {"users": [{"username": "bob", "name": "Bob Bauer", "ready": True}], "groups": []})
    assert "Bob Bauer" in labels and text not in labels and not links                 # lokale Treffer haben Vorrang
    labels, links = show("bob@saga.example.org", {"error": "Netzwerkfehler"})
    assert "Netzwerkfehler" in labels and not links                                  # Fehler bleiben Fehler
    d.force_close()
