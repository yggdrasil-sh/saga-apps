"""Saga 2.3 - Foederation E4-E6 (docs/APP-API-FED.md Abschnitte 8 und 9) gegen den nachgebauten Server:
remote.caps (fremde Seiten zeigen nur, was der Server der Seite kann), Verschieben fuer Bearbeiter, Anwesenheit fuer negative
IDs, Gruppen ueber Servergrenzen (Systemzeilen, Einladung als Anfrage, Heim-Hinweis, Gruppe mit Handles anlegen).
Aeltere Gegenstellen (ohne caps) bleiben bei Lesen, Kommentieren und Titel/Inhalt (test_v2more.py)."""
import os

import mockfed
from gi.repository import Gtk

from hk_helpers import idle, pump

ALL = ["db", "subpages", "upload", "versions", "export", "presence", "live", "chatgroups"]


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label) and x.get_mapped()]


def _menu(v):
    from hlidskjalf import page as page_mod
    seen = []
    orig = page_mod.menu_popover
    page_mod.menu_popover = lambda parent, ents: (seen.append(ents), orig(parent, ents))[1]
    try:
        v.page_menu(v.win.menu_btn)
    finally:
        page_mod.menu_popover = orig
    return [e[0] for e in seen[0] if e]


def _remote(caps, role="edit", nid=-2049, **kw):
    r = {"peer": 1, "host": "b.example", "name": "b.example"}
    if caps is not None:
        r["caps"] = caps
    return dict({"id": nid, "role": role, "remote": r, "owner": {"username": "@anna@b.example", "name": "Anna"}}, **kw)


# --- Logik ohne GTK -----------------------------------------------------------------------------------------------------
def test_caps_decide_remote_actions():
    from hlidskjalf import sharing as S
    from hlidskjalf.store import store
    store.server_version = None
    full, old, part = _remote(ALL), _remote(None), _remote(["export", "versions"])
    assert S.remote_caps(full) == frozenset(ALL) and S.remote_caps(old) == frozenset() and S.remote_caps({"id": 3}) is None
    for a in ("subpage", "upload", "versions", "export", "wide", "icon", "cover", "tags", "move", "presence", "chat"):
        assert S.allowed(full, a), a
        assert not S.allowed(old, a), a                       # 2.1/2.2: wie bisher nur Lesen/Kommentieren/Titel+Inhalt
    for a in ("trash", "share", "publish", "duplicate", "archive", "lock", "template", "favorite", "home", "ai"):
        assert not S.allowed(full, a), a                      # nie ueber Servergrenzen
    assert S.allowed(part, "export") and S.allowed(part, "versions") and not S.allowed(part, "subpage") and not S.allowed(part, "upload")
    view = _remote(ALL, role="view")
    assert S.allowed(view, "export") and not S.allowed(view, "versions") and not S.allowed(view, "move") and not S.allowed(view, "subpage")
    assert not S.allowed(view, "icon") and not S.allowed(_remote(ALL, role="comment"), "upload")
    assert S.allowed(old, "comments") and S.REMOTE_OFF >= {"move", "upload", "export", "presence", "chat"}


def test_editor_versions_and_move_on_23_servers():
    """Server ab 2.3: Versionen und Verschieben (im geteilten Bereich) auch mit Bearbeiten - aeltere Server unveraendert"""
    from hlidskjalf import sharing as S
    from hlidskjalf.store import store
    edit, own = {"id": 5, "role": "edit"}, {"id": 6, "role": "owner"}
    try:
        store.server_version = "2.2.4"
        assert not S.allowed(edit, "versions") and not S.allowed(edit, "move") and S.allowed(own, "versions")
        store.server_version = "2.3.0"
        assert S.allowed(edit, "versions") and S.allowed(edit, "move") and not S.allowed({"id": 5, "role": "comment"}, "versions")
        assert not S.allowed(edit, "trash") and not S.allowed(edit, "share")
    finally:
        store.server_version = None


def test_shared_move_targets():
    from hlidskjalf import sharing as S
    o = {"username": "@anna@b.example"}
    sh = [_remote(ALL, nid=-1025, owner=o, parent_id=None), _remote(ALL, nid=-2049, owner=o, parent_id=-1025),
          _remote(ALL, nid=-3073, owner=o, parent_id=-2049), _remote(ALL, nid=-4097, owner=o, parent_id=-1025, kind="database"),
          _remote(ALL, nid=-5121, owner=o, parent_id=-4097), _remote(ALL, nid=-6145, role="view", owner=o, parent_id=-1025),
          _remote(ALL, nid=-7169, owner={"username": "@olga@b.example"}), {"id": 12, "role": "edit", "owner": o},
          _remote(["db"], nid=-8193, owner=o)]
    t = [p["id"] for p in S.shared_move_targets(sh[1], sh)]
    # jetzige Elternseite, eigene Nachfahren, Datenbanken und ihre Eintraege, nur Lesen, anderer Eigentuemer, dieser Server,
    # Server ohne 'subpages': alles keine Ziele
    assert t == []
    t3 = [p["id"] for p in S.shared_move_targets(sh[2], sh)]
    assert t3 == [-1025]


def test_sys_text_and_home_hint():
    from hlidskjalf.chat import chat_home_hint, sys_text
    me = "~me@a.example"
    anna, bert, dora = ({"name": n, "host": h, "handle": f"{n.lower()}@{h}"} for n, h in
                        (("Anna", "a.example"), ("Bert", "b.example"), ("Dora", "c.example")))
    meh = {"name": "Ich", "host": "a.example", "handle": me}
    assert sys_text({"type": "members", "added": [bert, dora], "by": anna}, me) == "Anna added Bert (b.example), Dora (c.example)."
    assert sys_text({"type": "members", "added": [meh], "by": bert}, me) == "Bert (b.example) added you."
    assert sys_text({"type": "members", "added": [bert], "by": meh}, me) == "You added Bert (b.example)."
    assert sys_text({"type": "members", "added": [dora], "by": None}, me) == "Dora (c.example) joined."
    assert sys_text({"type": "members", "removed": [bert], "by": bert}, me) == "Bert (b.example) left the group."
    assert sys_text({"type": "members", "removed": [meh], "by": meh}, me) == "You left the group."
    assert sys_text({"type": "members", "removed": [bert], "by": meh}, me) == "You removed Bert (b.example)."
    assert sys_text({"type": "members", "removed": [bert], "by": None, "reason": "disconnected"}, me).startswith(
        "Bert (b.example) is no longer here – the connection")
    assert sys_text({"type": "members", "removed": [dora], "by": None, "reason": "gone"}, me) == "Dora (c.example) is no longer here (account deleted)."
    assert sys_text({"type": "fed", "on": True, "ai_off": True}, me).endswith("The AI is now off here.")
    assert sys_text({"type": "ai", "on": True, "by": me, "name": "Ich"}, me) == "You turned on the AI in this chat."
    assert sys_text({"type": "x"}, me) == "Notice"
    assert chat_home_hint({"v": 2, "kind": "free", "home_self": False, "home": "b.example"}).startswith("This chat is on b.example.")
    assert chat_home_hint({"v": 2, "kind": "free", "home_self": True}).startswith("This chat is on this server")
    assert chat_home_hint({"v": 2, "kind": "dm"}) == "" and chat_home_hint({"kind": "free"}) == ""


def test_no_later_update_text():
    src = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "hlidskjalf")
    for f in os.listdir(src):
        if f.endswith(".py"):
            assert "later update" not in open(os.path.join(src, f), encoding="utf-8").read(), f


# --- Fenster ------------------------------------------------------------------------------------------------------------
def _open(win, nid):
    win.show_page(nid)
    pump(lambda: getattr(win.current, "id", None) == nid and win.current.doc.fetched, 5)
    pump(None, 0.3)
    return win.current


def test_remote_page_with_caps(win, srv):
    from hlidskjalf.store import store
    srv.add_fed(-2049, "Projekt Nord", caps=ALL)
    srv.add_fed(-3073, "Alt", caps=None)
    store.load_fed_shared()
    pump(lambda: store.meta(-2049) is not None, 5)
    v = _open(win, -2049)
    assert v.remote and v.rcan("db") and v.rcan("upload") and v.rcan("presence")
    m = _menu(v)
    assert {"Full width", "Version history …", "Export", "Move to …"} <= set(m), m
    assert "Move to trash" not in m and "Share …" not in m and "Duplicate" not in m
    assert any(x and x.startswith("This page is on b.example. Deleting and sharing") for x in _labels(v))
    pump(lambda: any(x.get("note_id") == -2049 for x in srv.presence_log), 5)
    assert any(x.get("note_id") == -2049 for x in srv.presence_log)            # Anwesenheit (Abo beim Eigentuemer)
    v.save_fields({"icon": "🚀", "tags": ["fern"]})
    pump(lambda: srv.fed[-2049].get("icon") == "🚀", 5)
    assert srv.fed[-2049]["icon"] == "🚀" and srv.fed[-2049]["tags"] == ["fern"]
    # aeltere Gegenstelle: wie bisher
    n0 = len(srv.presence_log)
    v2 = _open(win, -3073)
    m2 = _menu(v2)
    assert not ({"Full width", "Version history …", "Export", "Move to …"} & set(m2)), m2
    assert v2.presence is None and not any(x.get("note_id") == -3073 for x in srv.presence_log[n0:])
    assert any(x and x.startswith("This page is on b.example. Here you can read and comment") for x in _labels(v2))
    v2.remote_blocked()                                       # Hinweis statt Dateiauswahl
    idle()


def test_remote_icon_picker_without_immich(win, srv):
    from hlidskjalf.page import IconPicker
    from hlidskjalf.store import store
    srv.add_fed(-2049, "Projekt Nord", caps=ALL)
    store.load_fed_shared()
    pump(lambda: store.meta(-2049) is not None, 5)
    v = _open(win, -2049)
    ip = IconPicker(v)
    lb = [x.get_label() for x in _walk(ip.get_child()) if isinstance(x, Gtk.Label)]
    assert "Image" in lb and "Immich" not in lb
    srv.add_fed(-4097, "Ohne Hochladen", caps=["db"])
    store.load_fed_shared()
    pump(lambda: store.meta(-4097) is not None, 5)
    v2 = _open(win, -4097)
    lb2 = [x.get_label() for x in _walk(IconPicker(v2).get_child()) if isinstance(x, Gtk.Label)]
    assert "Image" not in lb2 and "Immich" not in lb2


def _fed_ready(srv):
    from hlidskjalf import chatcrypto as C
    from hlidskjalf.chat import chat
    from hlidskjalf.store import store
    mockfed.enable(srv)
    store.server_version = "2.3.0"
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    k = chat.setup(C.recovery_key())
    chat.adopt_keys(k)
    pump(lambda: chat.status == "ready" and chat.me and chat.me.get("identity"), 5)
    idle()


def test_group_invite_request_and_sys_rows(win, srv):
    from hlidskjalf.chat import chat
    _fed_ready(srv)
    h = mockfed.remote_person(srv, "Bert Gast", "b.example", public="bert")
    cid = mockfed.new_fed_chat(srv, h, request=True, home_self=False, kind="free",
                               extra={"invited_by": {"name": "Bert Gast", "host": "b.example", "label": "Bert Gast · b.example"}})
    mockfed.add_sys(srv, cid, {"type": "members", "added": [{"name": "Ich", "host": mockfed.HOST, "handle": srv.fed["me"]}],
                               "by": {"name": "Bert Gast", "host": "b.example", "handle": h}})
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 5)
    win.show_route(("chat", cid))
    pump(lambda: win.current.conv is not None and win.current.conv.loaded, 5)
    pump(None, 0.4)
    conv = win.current.conv
    lb = _labels(conv)
    assert any("Bert Gast (b.example) invited you to this group" in (x or "") for x in lb), lb
    assert any(x == "Bert Gast (b.example) added you." for x in lb), lb
    t, sub = win.current.header()
    assert "on b.example" in sub and "Request" in sub
    rows = _labels(win.current)
    assert any(x and x.startswith("Request · ") and "b.example" in x for x in rows), rows


def test_new_group_with_people_on_other_servers(win, srv):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import NewChatDialog
    _fed_ready(srv)
    dlg = NewChatDialog(win)
    dlg.present(win)
    dlg._toggle_multi()
    dlg._show("", {"users": [{"username": "carl", "name": "Carl", "ready": True}],
                   "remote": [{"handle": "bert@b.example", "name": "Bert Gast", "host": "b.example", "label": "Bert Gast · b.example"}]})
    dlg.q = ""
    dlg._pick_user({"username": "carl", "name": "Carl"})
    dlg._pick_remote({"handle": "bert@b.example", "name": "Bert Gast", "host": "b.example", "label": "Bert Gast · b.example"})
    assert set(dlg.picked) == {"carl", "bert@b.example"} and dlg.create.get_sensitive()
    seen = []
    orig = chat.new_chat
    chat.new_chat = lambda body, done, error: seen.append(body)
    try:
        dlg._create_free()
    finally:
        chat.new_chat = orig
    assert seen == [{"kind": "free", "members": ["carl"], "handles": ["bert@b.example"]}]
    dlg.force_close()
    # nur Personen dieses Servers: wie bisher ohne handles
    dlg = NewChatDialog(win)
    dlg._toggle_multi()
    dlg._pick_user({"username": "carl", "name": "Carl"})
    seen = []
    chat.new_chat = lambda body, done, error: seen.append(body)
    try:
        dlg._create_free()
    finally:
        chat.new_chat = orig
    assert seen == [{"kind": "free", "members": ["carl"]}]
    dlg.force_close()
