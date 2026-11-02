"""2.5 (L6, Auftrag ho-linux): Leiste "Anruf laeuft" - ein anderes eigenes Geraet ist im Anruf ("Hinzufuegen",
"Hierher uebernehmen") bzw. Gruppenanruf im eigenen Chat ("Beitreten"); alles oeffnet den Browser mit #join-<id>-<rolle>.
Vertrag: docs/APP-API.md "Geraete-Uebergabe hin und her (2.5, handover2)", Konzept anruf-uebergabe-hinzufuegen.md L6."""
import secrets

from hk_helpers import pump

D_PHONE, D_WEB, D_OTHER = "a" * 32, "b" * 32, "c" * 32


def _running(srv, cid, kind="dm", mine=(D_PHONE,), media="audio", **kw):
    call = secrets.token_hex(16)
    legs = [{"device": d, "who": "test", "dev_name": "Telefon", "role": "member"} for d in mine] + \
        [{"device": D_OTHER, "who": "anna", "dev_name": "Mac", "role": "member"}]
    c = {"id": call, "chat_id": cid, "media": media, "state": "active", "kind": kind, "role": "caller", "mine": list(mine),
         "legs": legs, "joinable": True, "join_block": None, "limit": 8, "takeover": bool(mine),
         "peer": {"username": "anna", "name": "Anna Beispiel"}, "from": {"username": "anna", "name": "Anna Beispiel"}}
    c.update(kw)
    srv.calls[call] = c
    return call


def test_run_view_and_urls():
    from hlidskjalf import calls as M
    from hlidskjalf.config import settings
    old = settings.server
    settings.server = "https://saga.test/"
    try:
        cid = "d" * 32
        assert M.join_url(cid, "video", "handover") == f"https://saga.test/chats#join-{cid}-handover-video"
        assert M.join_url(cid, "video", "companion") == f"https://saga.test/chats#join-{cid}-companion"   # nie mit Video
        assert M.join_url(cid, "audio") == f"https://saga.test/chats#join-{cid}-member"
        assert M.join_url(cid, "audio", "boese") == f"https://saga.test/chats#join-{cid}-member"
    finally:
        settings.server = old
    base = {"id": "d" * 32, "state": "active", "kind": "dm", "mine": [D_PHONE], "joinable": True, "takeover": True,
            "legs": [{"device": D_PHONE, "dev_name": "Telefon", "role": "member"}], "peer": {"name": "Anna"}}
    assert M.run_shown(base)
    assert not M.run_shown(dict(base, state="ringing"))                 # 1:1 erst nach dem Annehmen
    assert not M.run_shown(dict(base, mine=[]))                         # 1:1 ohne eigenes Geraet: nichts
    assert not M.run_shown(base, ringing_id="d" * 32)                   # klingelt hier: eigene Leiste
    assert not M.run_shown(base, local={D_PHONE})                       # nur der Browser dieses Rechners
    assert not M.run_shown(base, here=D_PHONE)
    assert not M.run_shown(dict(base, state="ended"))
    assert M.run_shown(dict(base, kind="group", mine=[], state="ringing"))
    assert not M.run_shown(dict(base, kind="group", dm=True, mine=[], state="ringing"))    # 1:1 ueber Knoten wie 1:1
    v = M.run_view(base)
    assert v["title"] == "Call in progress on Telefon" and v["sub"] == "Anna"
    assert [(a[0], a[2]) for a in v["actions"]] == [("companion", "Add"), ("handover", "Move here")]
    # voller Anruf: nur Hierher uebernehmen (E6), Grund dazu
    v = M.run_view(dict(base, joinable=False, join_block="full", limit=4))
    assert [a[0] for a in v["actions"]] == ["handover"] and v["note"] == "The call is full (at most 4 devices)."
    v = M.run_view(dict(base, joinable=False, join_block="old", takeover=False))
    assert v["actions"] == [] and "2.4" in v["note"]
    # Gruppe ohne eigenes Geraet: Beitreten (Video: zusaetzlich Nur Ton)
    g = dict(base, kind="group", mine=[], media="video", legs=[{"device": D_OTHER}, {"device": D_WEB}], **{"from": {"chat": "Team"}})
    v = M.run_view(g)
    assert v["title"] == "Group call running" and v["sub"] == "Team · 2 devices"
    assert [(a[0], a[1]) for a in v["actions"]] == [("member", False), ("member", True)]
    assert M.run_view(dict(base, legs=[]))["title"] == "Call in progress on another device"


def test_runbar_take_over_and_add_open_browser(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    call = _running(srv, cid, media="video")
    M.calls.check_active()
    bar = win.runbar
    assert pump(lambda: bar.get_reveal_child() and bar.cid == call, 4)
    assert bar.title.get_label() == "Call in progress on Telefon" and bar.sub.get_label() == "Anna Beispiel"
    assert [b.short for b in bar.buttons] == ["Add", "Move here"]
    bar.buttons[1].emit("clicked")
    assert opened[-1] == f"https://saga.test/chats#join-{call}-handover-video"
    # der Browser dieses Rechners uebernimmt: neues eigenes Geraet, das alte geht -> keine Leiste fuer den Browser hier
    c = srv.calls[call]
    c["mine"] = [D_WEB]
    c["legs"] = [{"device": D_WEB, "who": "test", "dev_name": "Firefox", "role": "member"}, c["legs"][-1]]
    M.calls.on_live("call.roster", {"call_id": call, "legs": c["legs"]})
    assert pump(lambda: not bar.get_reveal_child(), 4)
    # spaeter auf ein anderes Geraet gewechselt (Telefon) -> wieder da, mit Hinzufuegen (Begleitgeraet, ohne Video)
    c["mine"] = [D_PHONE]
    c["legs"][0] = {"device": D_PHONE, "who": "test", "dev_name": "Telefon", "role": "member"}
    M.calls.on_live("call.roster", {"call_id": call, "legs": c["legs"]})
    assert pump(lambda: bar.get_reveal_child(), 4)
    bar.buttons[0].emit("clicked")
    assert opened[-1] == f"https://saga.test/chats#join-{call}-companion"
    # Anruf endet: Leiste weg
    c["state"] = "ended"
    M.calls.on_live("call.state", {"call_id": call, "state": "ended"})
    assert pump(lambda: not bar.get_reveal_child() and not M.calls.running, 4)


def test_runbar_full_call_and_narrow(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    call = _running(srv, cid, kind="group", joinable=False, join_block="full", limit=4,
                    **{"from": {"username": "anna", "name": "Anna Beispiel", "chat": "Team"}})
    M.calls.check_active()
    bar = win.runbar
    assert pump(lambda: bar.get_reveal_child() and bar.cid == call, 4)
    assert [b.role for b in bar.buttons] == ["handover"] and bar.sub.get_label() == "Team"
    assert bar.note.get_label() == "The call is full (at most 4 devices)."
    bar.set_compact(True)
    assert bar.buttons[0].get_child().get_label() == "" and bar.buttons[0].get_tooltip_text()
    assert not bar.note.get_visible()
    bar.set_compact(False)
    assert bar.buttons[0].get_child().get_label() == "Move here"
    # Kontowechsel/Abmelden: Leiste weg
    M.calls.reset()
    assert not bar.get_reveal_child()
