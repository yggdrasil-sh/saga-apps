"""Funde des kritischen Linux-Tests zu Saga 2.4 (Integrationsstand 6167184): Fokus nach Tabelle/Datenbank, "/"-Menue
(alle Typen, Rangfolge), Gelesen-Bestaetigung im offenen Chat, Online-Abo nach Annehmen, Gruppenanrufe (call.invite),
Rueckfrage vor "Version wiederherstellen", Block loeschen mit Rueckgaengig, Seiten im Papierkorb, Seitenverweise mit
aktuellem Titel, schmales Fenster, Initialen, Uebersetzungsluecken und kleinere Punkte."""
import json
import os

from gi.repository import Adw, Gdk, Gtk

from hk_helpers import idle, pump


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def _open_page(win, nid=1):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched and v.kit.order, 6)
    pump(None, 0.3)
    return v


def _blocks(srv, nid):
    return json.loads(srv.notes[nid]["body"])["blocks"]


def _type_slash(v, text):
    """leeren Absatz am Ende anlegen und "/..." hineintippen -> (Block-ID, RichText)"""
    ed = v.editor
    ed.continue_writing()
    pump(None, 0.2)
    bid = v.doc.root[-1]["id"]
    rt = v.kit.by_key[bid]
    rt.get_buffer().insert(rt.get_buffer().get_end_iter(), text)
    pump(None, 0.2)
    return bid, rt


# --- 1. Fokus nach dem Einfuegen (HOCH) ----------------------------------------------------------------------------
def test_table_from_slash_focuses_first_cell(win, srv):
    """"/table" + Enter: Tabelle (nicht Datenbank) und die Schreibmarke steht in der ersten Zelle - Getipptes landet dort,
    nicht in der Ueberschrift am Seitenanfang (Testbericht 025)"""
    v = _open_page(win)
    title_before = srv.notes[1]["title"]
    bid, rt = _type_slash(v, "/table")
    sg = v.suggest
    assert sg.visible and sg.items[0][3] == ("table", 0)
    assert v.kit.on_key(rt, Gdk.KEY_Return, 0) is True
    pump(lambda: v.kit.cells.get(f"{bid}/#0,0") is not None, 3)
    tv = v.kit.cells[f"{bid}/#0,0"]
    pump(lambda: win.get_focus() is tv, 3)
    assert win.get_focus() is tv and v.editor.focused_block == bid
    tv.get_buffer().insert_at_cursor("A1")
    pump(None, 0.9)
    idle()
    tb = next(b for b in _blocks(srv, 1) if b["id"] == bid)
    assert tb["type"] == "table" and tb["data"]["content"][0][0] == "A1"
    assert _blocks(srv, 1)[0]["data"]["text"] == "Projekt &amp; Plan" and srv.notes[1]["title"] == title_before
    # Zeile dazu (Neuaufbau): Fokus bleibt in der Tabelle
    win.set_focus(None)
    v.editor.set_focus(f"{bid}/#1,1", "end")
    v.editor.changed()
    pump(lambda: win.get_focus() is v.kit.cells.get(f"{bid}/#1,1"), 3)
    assert win.get_focus() is v.kit.cells[f"{bid}/#1,1"]


def test_database_and_tasks_focus_paragraph_after(win, srv):
    """Datenbank, Aufgaben, Kalender, Schaltflaeche: danach ein (leerer) Absatz mit der Schreibmarke - und Getipptes, waehrend
    der Server die Datenbank anlegt, bleibt erhalten"""
    v = _open_page(win, 3)
    bid, rt = _type_slash(v, "/database")
    srv.delay["POST api/notes"] = 0.6
    v.kit.on_key(rt, Gdk.KEY_Return, 0)
    pump(None, 0.1)
    assert v.editor.focused_block == bid                     # bis zur Antwort im Absatz
    pump(lambda: any(b.get("type") == "database" for b in v.doc.root), 4)
    i = next(k for k, b in enumerate(v.doc.root) if b.get("type") == "database")
    nxt = v.doc.root[i + 1]
    assert nxt["type"] == "paragraph" and v.editor.focus == nxt["id"]
    pump(lambda: win.get_focus() is v.kit.by_key.get(nxt["id"]), 3)
    assert win.get_focus() is v.kit.by_key[nxt["id"]]
    # Aufgaben-Block: ebenso
    bid2, rt2 = _type_slash(v, "/tasks")
    v.kit.on_key(rt2, Gdk.KEY_Return, 0)
    pump(None, 0.3)
    j = next(k for k, b in enumerate(v.doc.root) if b["id"] == bid2)
    assert v.doc.root[j]["type"] == "tasks" and v.editor.focus == v.doc.root[j + 1]["id"]
    # inzwischen weitergetippt: Text bleibt, Datenbank kommt davor
    bid3, rt3 = _type_slash(v, "/database")
    v.kit.on_key(rt3, Gdk.KEY_Return, 0)
    pump(None, 0.05)
    v.editor.block(bid3)["data"]["text"] = "weiter"
    pump(lambda: sum(b.get("type") == "database" for b in v.doc.root) == 2, 4)
    k = next(k for k, b in enumerate(v.doc.root) if b["id"] == bid3)
    assert v.doc.root[k]["data"]["text"] == "weiter" and v.doc.root[k - 1]["type"] == "database"
    srv.delay.clear()
    idle()


# --- 2. "/"-Menue ----------------------------------------------------------------------------------------------------
def test_slash_menu_all_types_and_ranking(win, srv):
    from hlidskjalf.blocks import slash_matches
    from hlidskjalf.editor import SLASH_ITEMS
    assert len(slash_matches("")) == len(SLASH_ITEMS) > 12
    assert slash_matches("table")[0][3] == ("table", 0)
    assert slash_matches("calendar")[0][3] == ("calendar", 0)
    assert slash_matches("database")[0][3] == ("db_new", 0)
    assert slash_matches("heading 2")[0][3] == ("header", 2)
    # Stichwoerter wirken weiter (Datenbank auch ueber "board"), Name zaehlt aber vor Stichwort
    assert ("db_new", 0) in [x[3] for x in slash_matches("board")]
    assert slash_matches("zzz") == []
    v = _open_page(win, 3)
    bid, rt = _type_slash(v, "/")
    sg = v.suggest
    assert sg.visible and len(sg.items) >= len(SLASH_ITEMS) - 1     # ohne Nextcloud (Server ohne)
    rows = [b for b in walk(sg.list_box) if isinstance(b, Gtk.Button)]
    assert len(rows) == len(sg.items)
    for _i in range(len(sg.items) - 1):                       # bis zum letzten: rollt mit
        v.kit.on_key(rt, Gdk.KEY_Down, 0)
    pump(None, 0.3)
    assert sg.sel == len(sg.items) - 1
    adj = sg.scroller.get_vadjustment()
    if adj.get_upper() > adj.get_page_size() + 1:
        assert adj.get_value() > 0
    # "/heading 2" mit Leerzeichen: Menue bleibt offen
    buf = rt.get_buffer()
    buf.set_text("/heading 2")
    pump(None, 0.2)
    assert sg.visible and sg.items[0][3] == ("header", 2)
    buf.set_text("/x und weiter")
    pump(None, 0.2)
    assert not sg.visible


# --- 3. Gelesen-Bestaetigung, Online-Abo -------------------------------------------------------------------------------
def test_open_chat_confirms_new_messages_after_layout(win, srv):
    """Neue Nachricht im offenen, sichtbaren Chat: nach dem Anordnen noch einmal bestaetigen (vorher nur beim Rollen)"""
    from test_receipts import _ready, _receipts
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(cid, "anna", "Hallo")
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    win.show_route(("chat", cid))
    conv = win.current.conv
    pump(lambda: conv.loaded, 5)
    conv.win.is_active = lambda: True
    pump(lambda: _receipts(srv, cid), 4)
    chat.rc_at[cid] = 0
    n = len(_receipts(srv, cid))
    m2 = srv.peer_send(cid, "anna", "Noch etwas")
    calls = []
    real = conv._rc_soon
    conv._rc_soon = lambda: (calls.append(1), real())
    chat.on_live("chat.message", {"chat_id": cid, "id": m2["id"]})
    pump(lambda: any(m["id"] == m2["id"] for m in chat.cache[cid]["msgs"]), 4)
    assert calls                                             # nach dem Zeichnen nachgezogen
    pump(lambda: len(_receipts(srv, cid)) > n, 6)
    assert len(_receipts(srv, cid)) > n
    # Liste meldet den offenen Chat noch als ungelesen (Live-Wettlauf): gleich wieder gelesen
    chat.chat(cid)["unread"] = 1
    chat.unread = 1
    calls.clear()
    chat.emit("changed")
    pump(lambda: calls, 2)
    pump(lambda: chat.unread == 0 and not chat.chat(cid).get("unread"), 3)
    assert chat.unread == 0


def test_presence_subscribed_after_request_accepted(win, srv):
    from test_receipts import _ready
    from hlidskjalf.chat import chat
    _ready(srv)
    srv.chat_peer("anna", "Anna Beispiel")
    cid = srv.new_chat("dm", ["test", "anna"])
    srv.online["anna"] = {"online": True, "last": None}
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    ch = chat.chat(cid)
    ch["request"] = {"from": "test"}
    chat.presence_watch("t", lambda: [u for u in [__import__("hlidskjalf.chatstatus", fromlist=["x"]).presence_user(chat.me, chat.chat(cid))] if u])
    pump(None, 0.6)
    srv.presence_calls.clear()
    acc = dict(ch)
    acc.pop("request")
    chat._merge(acc)                                         # Anfrage angenommen (load_chat nach chat.changed)
    pump(lambda: srv.presence_calls and srv.presence_calls[-1].get("users") == ["anna"], 3)
    assert srv.presence_calls[-1] == {"users": ["anna"]}
    chat.presence_watch("t", None)


# --- 4. Gruppenanrufe -------------------------------------------------------------------------------------------------
def _group_call(srv, cid, silent=False):
    import secrets
    call = secrets.token_hex(16)
    srv.calls[call] = {"id": call, "chat_id": cid, "media": "audio", "state": "ringing", "kind": "group", "role": "member",
                       "invite": "ringing", "mine": [], "silent": silent, "ring_s": 45, "expires_in": 40,
                       "from": {"username": "anna", "name": "Anna Beispiel", "chat": "Team"}, "legs": []}
    return call


def test_group_call_invite_rings_and_joins_in_browser(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    started = []
    monkeypatch.setattr(M.calls.ringer, "start", lambda: started.append(1))
    call = _group_call(srv, cid)
    ev = {"call_id": call, "chat_id": cid, "kind": "group", "media": "audio", "silent": False, "ring_s": 45,
          "from": {"username": "anna", "name": "Anna Beispiel", "chat": "Team"}, "members": ["test", "anna", "bert"]}
    win._live_event("call.invite", ev)
    assert pump(lambda: M.calls.ringing is not None, 4)
    r = M.calls.ringing
    assert r["group"] and not r["silent"] and started
    bar = win.callbar
    assert bar.get_reveal_child() and bar.title.get_label() == "Team"
    assert "Anna Beispiel" in bar.kind.get_label() and bar.acc_a.short == "Join (in browser)"
    assert [i for i, n in notes.sent] == ["call-" + call]
    # "aktiv" heisst bei Gruppen nur: zwei sind dabei - weiter klingeln
    M.calls.on_live("call.state", {"call_id": call, "state": "active", "kind": "group"})
    assert M.calls.ringing is not None
    bar.acc_a.emit("clicked")
    assert opened == [f"https://saga.test/chats#join-{call}-member"] and M.calls.ringing is None


def test_group_call_silent_roster_and_declined(win, srv, app, monkeypatch):
    from test_v21b import _calls_setup
    M, notes, opened, cid = _calls_setup(win, srv, app, monkeypatch)
    started = []
    monkeypatch.setattr(M.calls.ringer, "start", lambda: started.append(1))
    call = _group_call(srv, cid, silent=True)
    M.calls.on_live("call.invite", {"call_id": call, "chat_id": cid, "kind": "group", "media": "audio", "silent": True,
                                    "from": {"username": "anna", "name": "Anna Beispiel", "chat": "Grosse Runde"}})
    assert pump(lambda: M.calls.ringing is not None, 4)
    assert M.calls.ringing["silent"] and not started                   # grosse Gruppe: kein Ton
    assert win.callbar.kind.get_label() == "Group call running"
    # eigenes anderes Geraet tritt bei: hier aufhoeren
    me = M.calls._me()
    M.calls.on_live("call.roster", {"call_id": call, "legs": [{"who": "anna"}, {"who": me}]})
    assert M.calls.ringing is None
    # Einladung anderswo abgelehnt
    call2 = _group_call(srv, cid)
    srv.calls[call2]["media"] = "video"
    srv.calls[call2]["from"] = {"username": "anna", "name": "Anna Beispiel"}        # ohne Gruppennamen: wer anruft
    M.calls.on_live("call.invite", {"call_id": call2, "chat_id": cid, "kind": "group", "media": "video",
                                    "from": {"username": "anna", "name": "Anna Beispiel"}})
    assert pump(lambda: M.calls.ringing is not None, 4)
    assert win.callbar.acc_v.get_visible() and win.callbar.title.get_label() == "Anna Beispiel"
    M.calls.on_live("call.state", {"call_id": call2, "state": "declined", "kind": "group"})
    assert M.calls.ringing is None
    # schon beigetreten (mine) bzw. Einladung erledigt: klingelt nicht
    call3 = _group_call(srv, cid)
    srv.calls[call3]["mine"] = ["x" * 32]
    M.calls.on_live("call.invite", {"call_id": call3, "kind": "group"})
    pump(None, 0.4)
    assert M.calls.ringing is None
    # Push der Art call.group (Rueckfall bei laufender App): Beitreten-Link
    call4 = _group_call(srv, cid)
    M.calls.on_push({"kind": "call.group", "call": call4, "chat": cid, "media": "audio", "caller": "Anna", "group": True})
    assert pump(lambda: M.calls.ringing is not None and M.calls.ringing["id"] == call4, 4)
    M.calls.accept(call4)
    assert opened[-1] == f"https://saga.test/chats#join-{call4}-member"


# --- 5. Datenverlust-Fallen ---------------------------------------------------------------------------------------------
def test_restore_version_asks_first(win, srv):
    from hlidskjalf.dialogs import VersionsDialog
    restored = []
    d = VersionsDialog(win, 1, lambda: restored.append(1))
    d.present(win)
    pump(None, 0.3)
    d.versions = [{"id": 7, "created": 1760000000, "title": "Alt"}]
    d.sel = 7
    n = len(srv.log)
    d._restore()
    pump(None, 0.3)
    assert isinstance(d.confirm, Adw.AlertDialog)
    assert not [x for x in srv.log[n:] if x[0] == "POST" and "versions/7/restore" in x[1]]
    d.confirm.emit("response", "cancel")
    pump(None, 0.3)
    assert not [x for x in srv.log[n:] if x[0] == "POST"] and not restored
    d._restore()
    d.confirm.emit("response", "ok")
    pump(lambda: [x for x in srv.log[n:] if x[0] == "POST" and "versions/7/restore" in x[1]], 3)
    assert [x for x in srv.log[n:] if x[0] == "POST" and "versions/7/restore" in x[1]]
    d.force_close()


def test_delete_block_with_undo(win, srv):
    v = _open_page(win)
    ed = v.editor
    toasts = []
    v.block_undo_toast = lambda undo: toasts.append(undo)
    ids = [b["id"] for b in v.doc.root]
    ed.delete("q1", undo=True)
    assert "q1" not in [b["id"] for b in v.doc.root] and len(toasts) == 1
    toasts[0]()
    assert [b["id"] for b in v.doc.root] == ids
    # im Spalten-Block ebenso; Ruecktaste auf leerem Absatz: kein Hinweis
    ed.delete("cr1", undo=True)
    toasts[-1]()
    assert ed.locate("cr1") is not None
    n = len(toasts)
    ed.delete("p2")
    assert len(toasts) == n
    idle()
    # echter Hinweis mit "Rueckgaengig" im Fenster
    del v.block_undo_toast
    ed.delete("q1", undo=True)
    t = v.last_undo_toast
    assert isinstance(t, Adw.Toast) and t.get_button_label() == "Undo"
    t.emit("button-clicked")
    assert ed.locate("q1") is not None


def test_trashed_parent_locks_open_subpage_and_404_is_reported(win, srv):
    from hlidskjalf.store import store
    store.trashed_ids = set()
    store.refresh()
    pump(lambda: 2 in store.by_id, 4)
    v = _open_page(win, 2)                                   # Unterseite offen (z. B. im Peek), Elternseite geht weg
    store.trash(1)
    pump(lambda: 2 in store.trashed_ids, 4)
    pump(None, 0.3)
    assert v.doc.in_trash and v.doc.read_only and v.editor.locked
    assert v.trash_banner.get_revealed()
    # Zurueckholen ueber den Hinweis: wieder bearbeitbar
    v._restore_from_trash()
    pump(lambda: 2 not in store.trashed_ids and not v.doc.in_trash, 4)
    pump(None, 0.3)
    assert not v.editor.locked and not v.trash_banner.get_revealed()
    # Verlauf Unterseite -> Startseite, Startseite in den Papierkorb: "Zurueck" nicht auf die Unterseite (Testbericht 038)
    store.restore(1)
    pump(lambda: 1 in store.by_id and 2 in store.by_id, 4)
    _open_page(win, 2)
    win.show_page(1)
    pump(None, 0.3)
    store.trash(1, done=win.go_back_or_home)
    pump(lambda: win.route != ("page", 1), 4)
    pump(None, 0.3)
    assert win.route == ("today",)
    # anderswo geloescht: Speichern meldet 404 -> klare Meldung, Entwurf bleibt, gesperrt
    v = _open_page(win, 3)
    errs = []
    v.doc.on_error = errs.append
    srv.notes.pop(3)
    v.editor.block(v.doc.root[0]["id"])["data"]["text"] = "Neu getippt"
    v.editor.text_changed()
    pump(lambda: errs, 4)
    pump(None, 0.3)
    assert "trash" in errs[0] and v.doc.trash_hit and v.doc.dirty and v.editor.locked
    from hlidskjalf import drafts
    assert drafts.read(3, v.doc.server, v.doc._acct()) is not None
    v.doc.dirty = False
    drafts.drop(3, v.doc.server, v.doc._acct())
    store.trashed_ids = set()


def test_page_link_shows_current_title(win, srv):
    from hlidskjalf.blocks import fresh_links, linked_titles
    from hlidskjalf.store import store
    store.refresh()
    pump(lambda: 2 in store.by_id, 4)
    assert fresh_links('📄 <a href="/notes#2">Untitled</a>') == '📄 <a href="/notes#2">Unterseite</a>'
    assert fresh_links('<a href="/notes#3">x</a>') == '<a href="/notes#3">📝 Notizen</a>'
    assert fresh_links('<a href="/notes#999">weg</a>') == '<a href="/notes#999">weg</a>'
    srv.notes[1]["body"] = json.dumps({"blocks": [{"id": "lk", "type": "paragraph",
                                                    "data": {"text": '📄 <a href="/notes#2">Untitled</a>'}}]})
    v = _open_page(win, 1)
    rt = v.kit.by_key["lk"]
    assert "Unterseite" in rt.text()
    store.by_id[2]["title"] = "Unterseite A"                 # umbenannt (z. B. in der Unterseite selbst)
    store.emit("pages-changed")
    pump(lambda: "Unterseite A" in v.kit.by_key["lk"].text(), 3)
    assert "Unterseite A" in v.kit.by_key["lk"].text()
    assert linked_titles(v.doc.root) == ((2, "Unterseite A", ""),)
    assert v.doc.root[0]["data"]["text"] == '📄 <a href="/notes#2">Untitled</a>'     # nur Anzeige, kein Speichern


# --- 6. Oberflaeche ---------------------------------------------------------------------------------------------------------
def test_narrow_window_fits(win, srv):
    """370 px: Seite (mit Tabelle, Spalten, Datenbank) und Datenbank-Leiste passen ins Fenster (Testbericht 180-184)"""
    win.set_default_size(370, 760)
    pump(lambda: win.very_narrow, 3)
    pump(None, 0.4)
    v = _open_page(win, 1)
    pump(lambda: v.narrow, 3)
    pump(None, 0.5)
    vp = v.scroll.get_child()
    assert v.narrow and vp.measure(Gtk.Orientation.HORIZONTAL, -1)[0] <= 340
    from hlidskjalf.database import DatabaseView
    dvs = [w for w in walk(v) if isinstance(w, DatabaseView)]
    assert dvs and all(d.bar.narrow and d.bar.get_orientation() == Gtk.Orientation.VERTICAL for d in dvs)
    win.set_default_size(1280, 820)
    pump(lambda: not win.very_narrow, 3)
    pump(lambda: not win.current.narrow, 3)
    assert not win.current.narrow


def test_avatar_initials_and_texts():
    from hlidskjalf.avatars import initials_text
    assert initials_text("Anna Berg (127.0.0.1:8195)") == "Anna Berg"
    assert initials_text("anna@127.0.0.1:8195") == "anna"
    assert initials_text("Bert Gast (localhost:8197)") == "Bert Gast"
    assert initials_text("(x)") == "?" and initials_text("") == "?"
    assert initials_text("1. Olga 2 Aus") == "Olga Aus"
    from hlidskjalf.util import human_size
    assert human_size(44) == "44 bytes" and human_size(1) == "1 byte"
    assert human_size(1500) == "1.5 kB" and human_size(2_500_000) == "2.5 MB"
    from hlidskjalf.window import accel_label
    assert accel_label("<Control>k") == "Ctrl+K" and accel_label("<Alt>Left") == "Alt+←"
    assert accel_label("<Control><Shift>z") == "Ctrl+Shift+Z" and accel_label("slash") == "/"
    from hlidskjalf.sharing import nobody_text
    assert "at least 3" in nobody_text({}, "an") and nobody_text({}, "anna") == "No one found."


def test_icons_symbolic_in_dark_mode(win):
    from gi.repository import Gdk
    d = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "hlidskjalf", "icons", "hicolor", "scalable",
                     "actions")
    th = Gtk.IconTheme.get_for_display(Gdk.Display.get_default())
    for f in os.listdir(d):
        ic = th.lookup_icon(f[:-4], None, 16, 1, Gtk.TextDirection.NONE, 0)
        assert ic.is_symbolic(), f


# --- 7. Kleinere Punkte --------------------------------------------------------------------------------------------------
def test_toggle_exit_and_button_target(win, srv):
    v = _open_page(win, 3)
    ed = v.editor
    p = ed.doc.root[0]
    ed.convert(p["id"], "toggle", 2)
    pump(None, 0.2)
    tg = next(b for b in ed.doc.root if b["type"] == "toggle")
    inner = tg["data"]["blocks"]
    inner.append({"id": "in2", "type": "paragraph", "data": {"text": ""}})
    ed.changed()
    pump(None, 0.3)
    rt = v.kit.by_key["in2"]
    assert v.kit.on_key(rt, Gdk.KEY_Return, 0) is True        # leere letzte Zeile: heraus
    i = ed.doc.root.index(tg)
    assert ed.locate("in2") is None and ed.doc.root[i + 1]["type"] == "paragraph"
    assert ed.focus == ed.doc.root[i + 1]["id"]
    inner.append({"id": "in3", "type": "paragraph", "data": {"text": "Text"}})
    ed.changed()
    pump(None, 0.3)
    assert v.kit.on_key(v.kit.by_key["in3"], Gdk.KEY_ISO_Left_Tab, Gdk.ModifierType.SHIFT_MASK) is True
    assert ed.doc.root[ed.doc.root.index(tg) + 1]["id"] == "in3"
    # Schaltflaeche "Neuer Datenbank-Eintrag" ohne Datenbank: nicht speichern; Klick ohne Ziel: Hinweis
    from hlidskjalf.dialogs import ButtonsConfig
    data = {"buttons": [{"label": "Neu", "action": "db"}]}
    saved = []
    dlg = ButtonsConfig(data, lambda: saved.append(1))
    dlg.present(win)
    pump(None, 0.2)
    dlg._done()
    assert not saved and isinstance(dlg.missing_dialog, Adw.AlertDialog)
    dlg.missing_dialog.force_close() if hasattr(dlg.missing_dialog, "force_close") else None
    dlg.force_close()
    toasts = []
    win.toast = lambda t: toasts.append(t)
    win.run_button({"action": "db"})
    assert toasts and "no target" in toasts[0]
    del win.toast
    idle()


def test_restore_and_trash_rows_and_settings_focus(win, srv):
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(win)
    d.present(win)
    pump(None, 0.6)
    foc = d.get_focus()
    assert foc is None or not (foc is d.server or foc.is_ancestor(d.server))
    d.force_close()
