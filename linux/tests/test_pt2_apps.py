"""Pentest Runde 2 (Apps/Linux): L1 tiefe JSON-Antworten (RecursionError), L2 Riesen-IDs in Links, L3 Nicht-dict im
Anruf-Eintrag. Reine Logik, ohne Server."""
import io

import pytest


def _deep(n=200000):
    return ("[" * n + "]" * n).encode()


def test_l1_json_deep_answer_is_value_error(monkeypatch):
    from hlidskjalf import api
    c = api.Client()
    monkeypatch.setattr(c, "request", lambda *a, **k: _deep())
    with pytest.raises(ValueError):
        c.json("GET", "api/x")
    monkeypatch.setattr(c, "request", lambda *a, **k: b'{"a": 1}')
    assert c.json("GET", "api/x") == {"a": 1}


def test_l1_sse_deep_event_is_skipped():
    from hlidskjalf import api
    raw = b"event: x\ndata: " + _deep() + b"\n\nevent: y\ndata: {\"ok\": 1}\n\n"
    out = list(api.sse_events(io.BytesIO(raw)))
    assert out[0][0] == "x" and out[0][1] is None
    assert out[1][0] == "y" and out[1][1] == {"ok": 1}


def test_l2_parse_url_limits_digits():
    from hlidskjalf.notifications import parse_url
    assert parse_url("/notes#12:blk") == ("page", 12, "blk")
    assert parse_url("/notes#-2049") == ("page", -2049, None)
    assert parse_url("/chats#c5") == ("chat", 5, None)
    assert parse_url("/notes#" + "9" * 18) == ("page", int("9" * 18), None)
    assert parse_url("/notes#" + "9" * 19) is None
    assert parse_url("/notes#" + "9" * 100000) is None
    assert parse_url("/chats#c" + "1" * 5000) is None
    assert parse_url(None) is None and parse_url(12) is None and parse_url(["/notes#1"]) is None
    assert parse_url("/?settings=backup") == ("settings", "backup", None)


def test_l2_link_target_limits_digits():
    from hlidskjalf.util import link_target
    assert link_target("/notes#12") == ("page", 12)
    assert link_target("/notes#-7:abc") == ("page", -7)
    assert link_target("/notes#" + "1" * 19) is None
    assert link_target("/notes#" + "1" * 100000) is None
    assert link_target("https://example.org") == ("url", "https://example.org")
    assert link_target(None) is None and link_target(5) is None
    assert link_target("javascript:alert(1)") is None


@pytest.mark.parametrize("c", [None, 1, "x", [], ["a"], {"legs": "x"}, {"legs": [1, None, "a"], "mine": "x"},
                               {"from": "x", "peer": 3}, {"from": {"chat": 5}}, {"kind": "group", "from": {"chat": {"title": 7}}},
                               {"mine": ["d1"], "legs": [{"device": "d1", "dev_name": "Laptop"}]}])
def test_l3_run_view_never_raises(c):
    from hlidskjalf import calls
    v = calls.run_view(c)
    assert set(v) == {"video", "title", "sub", "note", "actions"}
    assert isinstance(v["title"], str) and isinstance(v["sub"], str)


def test_l3_names_need_strings():
    from hlidskjalf import calls
    assert calls.caller_name({"peer": {"name": 5}}) == ""
    assert calls.caller_name({"peer": "x", "from": {"username": "anna"}}) == "anna"
    assert calls.caller_name([1]) == ""
    assert calls.group_name({"from": {"chat": {"title": ["x"]}}}) == ""
    assert calls.group_name({"from": {"chat": "Team"}}) == "Team"
