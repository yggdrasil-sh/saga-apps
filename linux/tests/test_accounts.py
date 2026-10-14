"""QA-Runde 2: Entwuerfe je Server UND Konto (N2), Uebernahme alter Entwuerfe, Serverwechsel meldet beim alten ab (N8)."""
import json
import os

from hk_helpers import idle, note_texts, pump


def _offline_draft(srv, nid, text):
    from hlidskjalf.editor import PageDoc
    d = PageDoc(nid)
    d.load()
    assert pump(lambda: d.fetched)
    srv.fail[f"PATCH api/notes/{nid}"] = 0
    d.root[0]["data"]["text"] = text
    d.mark_dirty()
    d.save()
    idle()
    del srv.fail[f"PATCH api/notes/{nid}"]
    return d


def test_account_learned_on_refresh(srv):
    from hlidskjalf import config
    from hlidskjalf.store import store
    srv.users["https://saga.test"] = "anna"
    store.refresh()
    idle()
    assert config.account() == "anna"


def test_other_account_never_sees_drafts(srv):
    """N2: Entwurf von anna; danach meldet sich ben am selben Server an -> sieht/stellt ihn nicht wieder her.
    Meldet sich anna wieder an, ist er da und wird gespeichert."""
    from hlidskjalf import config, drafts
    from hlidskjalf.editor import PageDoc
    from hlidskjalf.store import store
    store.set_account("anna")
    d = _offline_draft(srv, 3, "Annas Entwurf")
    assert d.account == "anna" and drafts.read(3, account="anna")
    # ben meldet sich an (gleicher Server)
    store.new_session()
    srv.users["https://saga.test"] = "ben"
    store.refresh()
    idle()
    assert config.account() == "ben"
    d.save()                                             # Annas offene Seite: nie unter Bens Konto senden
    idle()
    assert note_texts(srv, 3) == ["Erste Notiz"]
    b = PageDoc(3)
    b.load()
    assert pump(lambda: b.fetched)
    idle()
    assert b.root[0]["data"]["text"] == "Erste Notiz" and not b.dirty
    assert drafts.read(3) is None                        # Bens Bereich: leer
    assert note_texts(srv, 3) == ["Erste Notiz"]
    # anna wieder da (Annas Seite ist inzwischen zu): Entwurf wird beim Oeffnen wiederhergestellt und gespeichert
    from gi.repository import GLib
    if d._retry_src:
        GLib.source_remove(d._retry_src)
    store.docs.discard(d)
    store.new_session()
    store.set_account("anna")
    a = PageDoc(3)
    a.load()
    assert pump(lambda: note_texts(srv, 3) == ["Annas Entwurf"], 6)
    idle()
    assert drafts.read(3, account="anna") is None


def test_open_page_waits_for_account_then_saves(srv):
    """Neu angemeldet, Konto noch unbekannt: Eingaben der bisherigen Seite bleiben Entwurf, bis das Konto feststeht;
    ist es dasselbe, werden sie gesendet"""
    from hlidskjalf.editor import PageDoc
    from hlidskjalf.store import store
    store.set_account("anna")
    d = PageDoc(3)
    d.load()
    assert pump(lambda: d.fetched)
    store.new_session()                                  # z. B. erneute Anmeldung
    d.root[0]["data"]["text"] = "Waehrend der Anmeldung"
    d.mark_dirty()
    d.save()
    idle()
    assert note_texts(srv, 3) == ["Erste Notiz"]
    srv.users["https://saga.test"] = "anna"
    store.refresh()
    assert pump(lambda: note_texts(srv, 3) == ["Waehrend der Anmeldung"], 6)


def test_legacy_drafts_migrated_to_account(srv):
    """Entwuerfe aus der Zeit vor dem Update (nur je Server) gehoeren danach dem dort angemeldeten Konto"""
    from hlidskjalf import config, drafts
    from hlidskjalf.store import store
    legacy = drafts.path(3, "https://saga.test", None)
    os.makedirs(os.path.dirname(legacy), exist_ok=True)
    with open(legacy, "w") as f:
        json.dump({"id": 3, "rev": 1, "title": "Notizen", "time": 1,
                   "doc": {"blocks": [{"id": "b0", "type": "paragraph", "data": {"text": "Alter Entwurf"}}]}}, f)
    assert config.account() is None
    assert drafts.read(3) is not None                    # Konto unbekannt: alter Ort
    srv.users["https://saga.test"] = "anna"
    store.refresh()
    idle()
    assert not os.path.exists(legacy)
    assert drafts.read(3, account="anna")["doc"]["blocks"][0]["data"]["text"] == "Alter Entwurf"
    assert drafts.read(3, account="ben") is None


def test_server_switch_forgets_old_device_even_offline(srv):
    """N8: Serverwechsel meldet das Geraet beim alten Server ab; ist der nicht erreichbar, blockiert das nichts"""
    from hlidskjalf import config
    from hlidskjalf.store import store
    config.set_device_token("hkd_alt", "https://saga.test")
    config.set_app_key("hk_alt", "https://saga.test")
    srv.fail["POST app/forget"] = 0                      # alter Server offline
    assert store.switch_server("https://anders.example")
    assert config.settings.server == "https://anders.example"   # sofort gewechselt
    assert pump(lambda: config.get_device_token("https://saga.test") is None, 5)
    assert pump(lambda: config.app_key("https://saga.test") == "", 3)
    assert [x for x in srv.log if x[1] == "https://saga.test/app/forget"]
    assert config.account("https://saga.test") is None


def test_server_switch_forget_reaches_old_server(srv):
    from hlidskjalf import config
    from hlidskjalf.store import store
    config.set_device_token("hkd_alt", "https://saga.test")
    store.switch_server("https://anders.example")
    assert pump(lambda: srv.forgotten, 5)
    assert srv.forgotten == [("https://saga.test", "hkd_alt")]
    assert not [x for x in srv.log if "hkd_alt" in str(x[2]) and not x[1].startswith("https://saga.test/")]


def test_switched_page_never_sent_to_new_server(srv):
    """Seite des alten Servers nach dem Wechsel: bleibt Entwurf beim alten Server, nichts geht an den neuen"""
    from hlidskjalf import drafts
    from hlidskjalf.editor import PageDoc
    from hlidskjalf.store import store
    d = PageDoc(3)
    d.load()
    assert pump(lambda: d.fetched)
    srv.delay["PATCH api/notes/3"] = 0.5
    d.root[0]["data"]["text"] = "Fuer den alten Server"
    d.mark_dirty()
    d.save()
    store.switch_server("https://anders.example")
    d.root[0]["data"]["text"] = "Fuer den alten Server 2"
    d.mark_dirty()
    d.save()
    idle()
    assert not [x for x in srv.log if x[1].startswith("https://anders.example/api/notes")]
    assert drafts.read(3, "https://saga.test", None) is not None
