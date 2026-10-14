"""Kopplung und Serverwechsel: Rueckfrage mit Zielhost, nur https, Server erst nach Erfolg, Schluessel an ihren Server gebunden."""
import os

import pytest

from hk_helpers import idle, pump

CODE = "x" * 24


def _app_requests(srv, host):
    return [x for x in srv.log if f"//{host}/" in x[1]]


def _dialog(win):
    return win.get_visible_dialog() if hasattr(win, "get_visible_dialog") else None


def test_external_pair_link_asks_first(win, srv):
    from hlidskjalf.config import settings
    win.open_link("hlidskjalf://pair?u=https://angreifer.example&c=" + CODE)
    pump(None, 0.3)
    d = _dialog(win)
    assert d is not None and d.get_heading() == "Pair with this server?" and "angreifer.example" in d.get_body()
    assert settings.server == "https://saga.test"
    assert not _app_requests(srv, "angreifer.example"), "ohne Bestaetigung darf nichts an den fremden Server gehen"
    d.force_close()                                      # wie "Abbrechen"
    pump(None, 0.2)
    assert settings.server == "https://saga.test"


def test_http_pair_link_refused(win, srv):
    win.pair_link("http://saga.example.org/app/pair?c=" + CODE)
    pump(None, 0.2)
    assert _dialog(win) is None
    assert not [x for x in srv.log if "app/pair" in x[1]]


def test_http_localhost_only_in_debug(monkeypatch):
    from hlidskjalf import config
    monkeypatch.setenv("HK_TEST", "")
    monkeypatch.delenv("HK_DEBUG", raising=False)
    assert not config.allowed_server("http://localhost:8080")
    assert config.allowed_server("https://saga.example.org")
    monkeypatch.setenv("HK_DEBUG", "1")
    assert config.allowed_server("http://localhost:8080")
    assert not config.allowed_server("http://saga.example.org")


def test_failed_pairing_keeps_server(win, srv):
    from hlidskjalf.api import client
    from hlidskjalf.config import settings
    cookies = dict(client.cookies)
    win._do_pair("https://neu.example", "bad" + CODE)
    assert pump(lambda: [x for x in srv.log if x[1] == "https://neu.example/app/pair"], 5)
    idle()
    pump(None, 0.3)
    assert settings.server == "https://saga.test"
    assert client.cookies == cookies and client.cookie_server == "https://saga.test"


def test_switch_server_after_success_binds_keys(win, srv):
    from hlidskjalf import config
    from hlidskjalf.api import client
    from hlidskjalf.store import store
    config.set_app_key("hk_GEHEIM_ECHT", "https://saga.test")
    config.set_device_token("hkd_echt", "https://saga.test")
    srv.devices["hkd_echt"] = "https://saga.test"
    store.visited(3)
    store.set_home(3)
    open(store._cache_path("api/notes/3"), "w").write("{}")
    win._do_pair("https://neu.example", CODE)
    assert pump(lambda: config.settings.server == "https://neu.example", 6)
    idle()
    assert pump(lambda: srv.forgotten, 3)
    assert store.recent == [] or 3 not in store.recent
    assert store.home_page is None
    assert not os.path.exists(store._cache_path("api/notes/3"))
    # N8: beim alten Server abgemeldet (nur dorthin ging dessen Schluessel), alte Schluessel hier geloescht
    assert srv.forgotten == [("https://saga.test", "hkd_echt")]
    assert pump(lambda: config.get_device_token("https://saga.test") is None and config.app_key("https://saga.test") == "", 3)
    assert config.app_key("https://neu.example") == "hk_key_for_neu.example"
    assert config.get_device_token("https://neu.example") == "hkd_neu.example"
    assert client.cookie_server == "https://neu.example"
    # neuer Server verlangt Erneuerung: resume geht nur dorthin und nur mit dessen Schluesseln
    srv.log.clear()
    srv.sessions["https://neu.example"] = "anders"
    try:
        client.request("GET", "api/notes")
    except Exception:  # noqa: BLE001
        pass
    sent = [x for x in srv.log if "app/resume" in x[1]]
    assert sent and all(x[1].startswith("https://neu.example/") for x in sent)
    assert all("hk_GEHEIM_ECHT" not in str(x[2]) and "hkd_echt" not in str(x[2]) for x in srv.log)


def test_resume_never_sends_other_servers_key(srv):
    from hlidskjalf import config
    from hlidskjalf.api import client
    config.set_device_token("hkd_A", "https://a.example")
    config.set_app_key("hk_A", "https://a.example")
    config.settings.server = "https://b.example"
    srv.log.clear()
    assert client.resume() == "rejected"                 # kein Schluessel fuer b -> nichts senden
    assert not srv.log


def test_cookies_only_for_own_host(srv):
    from hlidskjalf.api import APIError, client
    assert client.cookie_header_for("https://saga.test/api/notes")
    assert client.cookie_header_for("https://fremd.example/x") == ""
    with pytest.raises(APIError):
        client.url("https://fremd.example/files/x.png")
    assert client.url("https://saga.test/files/x.png") == "https://saga.test/files/x.png"


def test_new_page_link_asks(win, srv):
    win.open_link("hlidskjalf://new")
    pump(None, 0.3)
    assert _dialog(win) is not None
    assert not [x for x in srv.log if x[0] == "POST" and x[1].endswith("/api/notes")]


def test_settings_server_switch_clears(win, srv):
    from hlidskjalf import config
    from hlidskjalf.store import store
    store.visited(3)
    store.switch_server("https://anders.example")
    assert config.settings.server == "https://anders.example"
    assert store.recent == [] and store.pages == []
    assert config.settings.get("expanded") is None


def test_upload_filename_sanitized(srv):
    """N6: CR/LF und Anfuehrungszeichen im Dateinamen landen nicht im Multipart-Kopf"""
    from hlidskjalf.api import client
    srv.log.clear()
    try:
        client.upload(b"x", 'a"b\r\nContent-Type: evil.png', field="file")
    except Exception:  # noqa: BLE001
        pass
    raw = next(x[2] for x in srv.log if x[1].endswith("/api/files"))
    head = raw.split(b"\r\n\r\n")[0].split(b"\r\n")
    assert len(head) == 3 and head[1].startswith(b"Content-Disposition") and b"evil.png" in head[1]
