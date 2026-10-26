"""Update-Hinweis (Einstellungen -> Info): Logik wie ios/Tests/Logic/UpdateHintTests.swift - ohne Netz."""
import io
import json

import pytest

from hlidskjalf import updatehint as U

PAGE = "https://github.com/yggdrasil-sh/saga-apps/releases/tag/v2.5.1"


def _file(**kw):
    d = {"format": 1, "channel": "stable", "version": "2.5.1", "tag": "v2.5.1", "commit": "abc1234", "published": "2026-10-26T06:00:00Z",
         "page": PAGE, "assets": {"rpm": "https://github.com/yggdrasil-sh/saga-apps/releases/download/v2.5.1/saga-2.5.1-1.noarch.rpm"}}
    d.update(kw)
    return json.dumps(d).encode()


# --- Versionen ---------------------------------------------------------------------------------------------------------
def test_parse_version():
    assert U.parse_version("2.5.0") == (2, 5, 0, None)
    assert U.parse_version(" 2.5.0-rc.12 ") == (2, 5, 0, 12)
    for bad in ("", None, "2.5", "v2.5.0", "2.5.0-beta", "2.5.0-rc.", "2.5.0+dev.3", "2.5.0-rc.1-x", "1.2.3.4", 250, "2.5.0-RC.1"):
        assert U.parse_version(bad) is None, bad


@pytest.mark.parametrize("remote,local,newer", [
    ("2.5.1", "2.5.0", True), ("2.5.0", "2.5.0", False), ("2.4.9", "2.5.0", False),
    ("2.10.0", "2.9.9", True),                                    # numerisch, nicht als Text
    ("2.5.0", "2.5.0-rc.99", True),                               # Release > jede -rc.N
    ("2.5.0-rc.99", "2.5.0", False),
    ("2.5.0-rc.10", "2.5.0-rc.9", True),                          # -rc.N numerisch
    ("2.5.0-rc.9", "2.5.0-rc.10", False),
    ("2.5.1-rc.1", "2.5.0", True),
    ("2.5.0-rc.3", "2.5.0-rc.3", False),
    ("x", "2.5.0", False), ("2.6.0", "dev", False),               # ungueltig: nie neuer
])
def test_is_newer(remote, local, newer):
    assert U.is_newer(remote, local) is newer


def test_channel_and_url():
    assert U.channel("2.5.0") == "stable"
    assert U.channel("2.5.0-rc.3") == "testing"
    assert U.channel("2.5.0+dev") is None and U.channel("") is None
    assert U.feed_url("2.5.0-rc.3") == "https://raw.githubusercontent.com/yggdrasil-sh/saga-apps/appcast/testing.json"
    assert U.feed_url("2.5.0") == "https://raw.githubusercontent.com/yggdrasil-sh/saga-apps/appcast/stable.json"
    assert U.feed_url("dev") is None
    assert U.feed_url("2.5.0", repo="a/../b") is None and U.feed_url("2.5.0", repo="nur-ein-name") is None


# --- Datei -------------------------------------------------------------------------------------------------------------
def test_parse_format1():
    r = U.parse(_file(), "stable")
    assert r == {"version": "2.5.1", "page": PAGE, "assets": {"rpm": json.loads(_file())["assets"]["rpm"]}}


def test_parse_rejects():
    assert U.parse(_file(format=2), "stable") is None                    # unbekanntes Format
    assert U.parse(_file(format=True), "stable") is None
    assert U.parse(_file(), "testing") is None                           # anderer Kanal
    assert U.parse(_file(channel="testing"), "stable") is None
    assert U.parse(_file(version="2.5.1-rc.2"), "stable") is None        # Version passt nicht zum Kanal
    assert U.parse(_file(version="kaputt"), "stable") is None
    assert U.parse(b"{", "stable") is None and U.parse(b"[]", "stable") is None and U.parse(b"\xff", "stable") is None
    assert U.parse(b" " * (U.MAX_BYTES + 1), "stable") is None


def test_parse_without_channel_key_and_assets():
    d = json.loads(_file())
    del d["channel"]
    del d["assets"]
    r = U.parse(json.dumps(d), "stable")
    assert r["version"] == "2.5.1" and r["assets"] == {}


@pytest.mark.parametrize("url,ok", [
    (PAGE, True),
    ("https://GitHub.com/yggdrasil-sh/saga-apps/releases", True),
    ("http://github.com/x", False),
    ("https://github.com.evil.example/x", False),
    ("https://evil.example/github.com", False),
    ("https://user@github.com/x", False),
    ("https://user:pw@github.com/x", False),
    ("https://github.com:444/x", False),
    ("https://github.com:x/x", False),
    ("https://objects.githubusercontent.com/x", False),
    ("javascript:alert(1)", False),
    ("https://github.com/a b", False),
    ("https://github.com/" + "a" * 2100, False),
    (None, False), (42, False),
])
def test_safe_link(url, ok):
    assert (U.safe_link(url) is not None) is ok


def test_unsafe_links_dropped():
    r = U.parse(_file(page="https://evil.example/x", assets={"rpm": "http://github.com/x", "android": PAGE}), "stable")
    assert r["page"] is None and r["assets"] == {"android": PAGE}
    assert U.action(r) is None and U.button(False, r) == "check"         # ohne Release-Seite: nur Hinweis


# --- Zustand -----------------------------------------------------------------------------------------------------------
def test_available_only_own_channel_and_newer():
    st = U.parse(_file(), "stable")
    assert U.available(st, "2.5.0") is st
    assert U.available(st, "2.5.1") is None
    assert U.available(st, "2.5.0-rc.4") is None                         # Testversion vergleicht nur mit testing
    assert U.available(st, "dev") is None and U.available(None, "2.5.0") is None


def test_button_and_status():
    st = U.parse(_file(), "stable")
    assert U.button(True, st) == "checking" and U.button(True, None) == "checking"
    assert U.button(False, st) == "update" and U.button(False, None) == "check"
    assert U.action(st) == PAGE                                          # Linux: immer die Release-Seite
    assert U.status(st, False, 1.0) == ("available", "2.5.1")
    assert U.status(st, True, 1.0) == ("available", "2.5.1")
    assert U.status(None, True, None) == ("failed", None)
    assert U.status(None, False, 1.0) == ("uptodate", None)
    assert U.status(None, False, None) == ("none", None)


def test_due():
    h = 12 * 3600
    assert U.due(None, 1000, h)
    assert not U.due(1000, 1000 + h - 1, h)
    assert U.due(1000, 1000 + h, h)
    assert U.due(1000, 999, h)                                           # Uhr zurueckgestellt


def test_cache_roundtrip_and_garbage():
    st = U.parse(_file(), "stable")
    assert U.decode(json.loads(json.dumps(U.encode(1234.5, st)))) == (1234.5, st)
    assert U.decode(U.encode(7, None)) == (7.0, None)
    assert U.decode(None) == (None, None) and U.decode({"checked": "x"}) == (None, None) and U.decode({"checked": True}) == (None, None)
    # manipulierter Speicher: unsichere Links fallen auch hier weg, kaputte Versionen ebenso
    assert U.decode({"checked": 1, "release": {"version": "2.6.0", "page": "https://evil.example/"}}) == (1.0, {"version": "2.6.0", "page": None, "assets": {}})
    assert U.decode({"checked": 1, "release": {"version": "neu"}}) == (1.0, None)
    assert U.decode({"checked": 1, "release": "x"}) == (1.0, None)


# --- Abruf -------------------------------------------------------------------------------------------------------------
class _Res(io.BytesIO):
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class _Opener:
    def __init__(self, body, status=200):
        self.body, self.status, self.reqs = body, status, []

    def open(self, req, timeout=None):
        self.reqs.append((req, timeout))
        r = _Res(self.body)
        r.status = self.status
        return r


def test_fetch_sends_only_the_file():
    op = _Opener(_file())
    r = U.fetch(U.feed_url("2.5.0"), "stable", opener=op)
    assert r["version"] == "2.5.1"
    req, timeout = op.reqs[0]
    assert timeout == 10 and req.get_method() == "GET" and req.data is None
    names = {k.lower() for k, _v in req.header_items()}
    assert not {"cookie", "authorization"} & names
    assert req.full_url == "https://raw.githubusercontent.com/yggdrasil-sh/saga-apps/appcast/stable.json"


def test_fetch_errors():
    with pytest.raises(ValueError):
        U.fetch("https://evil.example/stable.json", "stable", opener=_Opener(_file()))
    with pytest.raises(ValueError):
        U.fetch(U.feed_url("2.5.0"), "stable", opener=_Opener(_file(), status=404))
    with pytest.raises(ValueError):
        U.fetch(U.feed_url("2.5.0"), "stable", opener=_Opener(b"<html>"))


def test_default_opener_has_no_cookie_handler():
    import urllib.request
    op = urllib.request.build_opener(U._NoRedirect)
    assert not any(isinstance(h, urllib.request.HTTPCookieProcessor) for h in op.handlers)


# --- Versionslabel: Entwickler-Builds ohne Pruefung ------------------------------------------------------------------------
def test_update_label_only_for_packages(monkeypatch):
    import hlidskjalf
    for lbl, packaged, want in (("2.5.0-rc.12", True, "2.5.0-rc.12"), ("2.5.0", True, "2.5.0"),
                                ("2.5.0-rc.0", True, None),                # Bau ohne Git-Tags
                                ("2.5.0-rc.12", False, None),              # Quellbaum / Fremdbau ohne _buildinfo.py
                                ("2.5.0+local", True, None)):
        monkeypatch.setattr(hlidskjalf, "_label", lbl)
        monkeypatch.setattr(hlidskjalf, "_packaged", packaged)
        assert hlidskjalf.update_label() == want, (lbl, packaged)
