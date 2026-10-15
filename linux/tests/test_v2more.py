"""Saga 2.0, weitere Teile: Mitteilungen (UnifiedPush ueber D-Bus mit einem nachgebauten Verteiler, Einstellungen),
KI-Assistent (Strom, Quellen, Aktionen der Seite, Uebernehmen), Foederation (Seiten anderer Saga-Instanzen, negative IDs,
"Mit mir geteilt", Teilen mit name@server)."""
import json

from gi.repository import Gio, GLib, Gtk

from hk_helpers import idle, pump


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label)]


def _titles(w):
    out = []
    for x in _walk(w):
        if hasattr(x, "get_title") and isinstance(x, Gtk.Widget):
            try:
                out.append(x.get_title())
            except TypeError:
                pass
    return out


def _open(win, nid):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.loaded and (v.doc.fetched or v.doc.error), 6)
    pump(None, 0.4)
    idle()
    return v


# --- Mitteilungen ---------------------------------------------------------------------------------------------------
_DIST_XML = """<node><interface name="org.unifiedpush.Distributor2">
<method name="Register"><arg type="a{sv}" direction="in"/><arg type="a{sv}" direction="out"/></method>
<method name="Unregister"><arg type="a{sv}" direction="in"/><arg type="a{sv}" direction="out"/></method>
</interface></node>"""


def test_unifiedpush_dbus_roundtrip(win, srv, app):
    """Nachgebauter Verteiler (D-Bus): Register -> NewEndpoint an die App -> Abo beim Server; Message -> Mitteilung"""
    from hlidskjalf.push import _state, subscribed, up
    conn = app.get_dbus_connection()
    assert conn is not None and up.conn is conn
    calls = []

    def handler(c, sender, path, iface, method, params, inv):
        a = params.unpack()[0]
        calls.append((method, a))
        if method == "Register":
            inv.return_value(GLib.Variant("(a{sv})", ({"success": GLib.Variant("s", "REGISTRATION_SUCCEEDED")},)))
            args = {"token": GLib.Variant("s", a["token"]), "endpoint": GLib.Variant("s", "https://ntfy.example/upABC?up=1")}
            c.call(sender, "/org/unifiedpush/Connector", "org.unifiedpush.Connector2", "NewEndpoint", GLib.Variant("(a{sv})", (args,)),
                   None, Gio.DBusCallFlags.NONE, 3000, None, None)
        else:
            inv.return_value(GLib.Variant("(a{sv})", ({},)))
    info = Gio.DBusNodeInfo.new_for_xml(_DIST_XML)
    reg = conn.register_object("/org/unifiedpush/Distributor", info.interfaces[0], handler, None, None)
    own = Gio.bus_own_name_on_connection(conn, "org.unifiedpush.Distributor.fake", Gio.BusNameOwnerFlags.NONE, None, None)
    try:
        pump(None, 0.3)
        found = []
        up.distributors(found.append)
        pump(lambda: found, 3)
        assert "org.unifiedpush.Distributor.fake" in found[0]
        res = []
        up.register("org.unifiedpush.Distributor.fake", "BVapid", lambda ok, why: res.append(ok))
        pump(lambda: res and _state().get("sub"), 5)
        idle()
        assert res == [True] and subscribed()
        assert calls[0][0] == "Register" and calls[0][1]["service"] == "io.github.yggdrasil_sh.Saga" and calls[0][1]["vapid"] == "BVapid"
        sub = srv.push_subs[0]
        assert sub["type"] == "unifiedpush" and sub["endpoint"] == "https://ntfy.example/upABC?up=1" and "keys" not in sub
        # Mitteilung vom Verteiler
        sent = []
        app.send_notification = lambda nid, n: sent.append(nid)
        payload = json.dumps({"title": "Saga", "body": "Neue Nachricht", "url": "/chats#c3", "tag": "chat-3", "kind": "chat.message"})
        win.is_active = lambda: False
        up.message(_state()["token"], payload.encode())
        up.message("falsches-token", payload.encode())
        assert sent == ["chat-3"]
        # Ausschalten: Abo beim Server und beim Verteiler weg
        up.unregister()
        pump(lambda: not srv.push_subs and any(c[0] == "Unregister" for c in calls), 4)
        assert not subscribed() and not srv.push_subs
    finally:
        Gio.bus_unown_name(own)
        conn.unregister_object(reg)
        del app.send_notification


def test_push_settings_page(win, srv):
    from hlidskjalf.push import PushPage
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(win)
    d.present(win)
    d.show_page("push")
    page = d.pages["push"]
    assert isinstance(page, PushPage)
    pump(lambda: page.data is not None and page.dists is not None, 5)
    pump(None, 0.3)
    t = _titles(page)
    assert "Push notifications on this device" in t and "Chat messages" in t and "Quiet hours" in t
    sw = next(x for x in _walk(page) if hasattr(x, "get_title") and isinstance(x, Gtk.Widget) and x.get_title() == "Push notifications on this device")
    assert not sw.get_sensitive()                              # kein Verteiler auf dem Testrechner
    chat_row = next(x for x in _walk(page) if hasattr(x, "get_title") and isinstance(x, Gtk.Widget) and x.get_title() == "Chat messages")
    chat_row.set_active(False)
    idle()
    pump(None, 0.3)
    assert srv.push_settings["cats"]["chat"] is False
    srv.push_server["enabled"] = False
    page.load()
    pump(lambda: page.data and not page.data["server"]["enabled"], 4)
    pump(None, 0.2)
    sw = next(x for x in _walk(page) if hasattr(x, "get_title") and isinstance(x, Gtk.Widget) and x.get_title() == "Push notifications on this device")
    assert sw.get_subtitle() == "Push notifications are turned off on this server."
    d.force_close()


# --- KI-Assistent ---------------------------------------------------------------------------------------------------
def test_ai_markup():
    from hlidskjalf.assistant import cite_markup
    assert cite_markup("<b>A</b> [1] [2] [x]", [{"n": 1, "id": 5, "title": "T"}]) == '&lt;b&gt;A&lt;/b&gt; <a href="src:1">[1]</a> [2] [x]'


def test_ai_ask_and_page_actions(win, srv):
    from hlidskjalf.assistant import AnswerBox, AssistantDialog, ai
    srv.ai_status = {"enabled": True, "host": "ollama.lan:11434", "model": "m", "provider": "ollama", "quota": 20, "used": 2,
                     "quota_left": 18, "history_allowed": True, "history": False,
                     "notice": "Inhalte, die du dem Assistenten gibst, gehen an ollama.lan:11434.", "targets": ["en", "de", "es"],
                     "q_max": 1000, "blocks_max": 50}
    v = _open(win, 1)
    ai.refresh()
    pump(lambda: ai.enabled, 4)
    win.update_header()
    assert win.ai_btn.get_visible()
    win.assistant()
    pump(lambda: isinstance(win.get_visible_dialog(), AssistantDialog), 3)
    d = win.get_visible_dialog()
    pump(lambda: d.notice.get_label(), 3)
    assert d.notice.get_label().startswith("Inhalte, die du") and d.quota.get_label() == "18 of 20 requests left today"
    d.q.get_buffer().set_text("Was steht im Plan?")
    d.inc_tasks.set_active(True)
    d.ask()
    pump(lambda: d.stream is None and any(isinstance(x, AnswerBox) for x in _walk(d.ask_list)), 5)
    pump(None, 0.3)
    box = next(x for x in _walk(d.ask_list) if isinstance(x, AnswerBox))
    assert box.text == "Die Seite beschreibt den Plan für den Garten [1]." and 'href="src:1"' in box.answer.get_label()
    assert srv.ai_log[0] == ("api/ai/ask", {"q": "Was steht im Plan?", "tasks": True})
    assert "Sources:" in _labels(box) and d.quota.get_label() == "17 of 20 requests left today"
    # Diese Seite: Aufgaben vorschlagen -> in Aufgaben uebernehmen
    d.run_action("tasks")
    pump(lambda: d.stream is None and srv.ai_log[-1][1].get("action") == "tasks", 5)
    pump(None, 0.3)
    btn = next(b for b in _walk(d.result) if isinstance(b, Gtk.Button) and b.get_label() == "Add to tasks")
    n0 = len(srv.tasks)
    btn.emit("clicked")
    pump(lambda: len(srv.tasks) == n0 + 2, 4)
    assert [t["text"] for t in srv.tasks[-2:]] == ["Hochbeet bauen", "Erde bestellen"]
    # Markierten Text verbessern -> Ersetzen (Text des Blocks, danach gespeichert)
    v.ai_toggle("p1")
    d.update_selection()
    assert d.sel_label.get_label() == "Selected: 1 block"
    d.run_action("improve")
    pump(lambda: d.stream is None and srv.ai_log[-1][1].get("action") == "improve", 5)
    assert srv.ai_log[-1] == ("api/ai/page/1", {"action": "improve", "blocks": ["p1"]})
    pump(None, 0.3)
    box2 = next(x for x in _walk(d.result) if isinstance(x, AnswerBox))
    box2._replace([{"id": "p1", "type": "paragraph", "replaceable": True}], ["Verbessert & neu"])
    v.editor.flush()
    idle()
    body = json.loads(srv.notes[1]["body"])
    assert next(b for b in body["blocks"] if b["id"] == "p1")["data"]["text"] == "Verbessert &amp; neu"
    d.force_close()


def test_ai_off_hides(win, srv):
    from hlidskjalf.assistant import ai
    _open(win, 1)
    ai.refresh()
    pump(lambda: ai.known, 4)
    win.update_header()
    assert not ai.enabled and not win.ai_btn.get_visible()


# --- Foederation -------------------------------------------------------------------------------------------------------
def test_federation_shared_and_remote_page(win, srv):
    from hlidskjalf import sharing as S
    from hlidskjalf.store import store
    srv.add_fed(-2049, "Projekt Nord", blocks=[{"id": "r1", "type": "paragraph", "data": {"text": "Fern-Text"}}])
    srv.fed_unreachable = ["c.example"]
    store.load_fed_shared()
    pump(lambda: store.meta(-2049) is not None, 5)
    pump(None, 0.3)
    labels = _labels(win.sidebar)
    assert "Projekt Nord" in labels and "Anna Eigner (anna@b.example)" in labels
    assert "c.example can't be reached right now" in labels
    p = store.meta(-2049)
    assert S.is_remote(p) and store.is_foreign(-2049)
    for a in ("share", "move", "export", "subpage", "upload", "publish", "versions", "favorite", "wide"):
        assert not S.allowed(dict(p, role="edit"), a), a
    assert S.allowed(dict(p, role="edit"), "comments")
    v = _open(win, -2049)
    assert v.doc.fetched and v.remote
    assert any(x and x.startswith("This page is on b.example.") for x in _labels(v))
    # Bearbeiten: nur Titel/Inhalt (+rev/format) gehen an den Server
    v.doc.root[0]["data"]["text"] = "Fern-Text geändert"
    v.editor.text_changed()
    v.editor.flush()
    idle()
    patch = [b for m, u, b, c in srv.log if m == "PATCH" and u.endswith("api/notes/-2049")]
    assert patch and set(patch[-1]) <= {"title", "body", "format", "rev", "overwrite"}
    assert "Fern-Text geändert" in srv.fed[-2049]["body"]
    # Seitenmenue: keine Eigentuemer-/Datei-Aktionen
    from hlidskjalf import page as page_mod
    seen = []
    orig = page_mod.menu_popover
    page_mod.menu_popover = lambda parent, ents: (seen.append(ents), orig(parent, ents))[1]
    try:
        v.page_menu(win.menu_btn)
    finally:
        page_mod.menu_popover = orig
    names = [e[0] for e in seen[0] if e]
    assert "Share …" not in names and "Move to …" not in names and "Export" not in names and "Duplicate" not in names
    assert any(n.startswith("Comments") for n in names)
    # anderer Server nicht erreichbar
    srv.fed[-2049]["_down"] = True
    store.drop_cache("api/notes/-2049")
    win.show_root(("today",))
    v2 = _open(win, -2049)
    assert not v2.doc.fetched and v2.doc.error_code == 502
    assert "Page could not be loaded" in [x.get_title() for x in _walk(v2) if hasattr(x, "get_title") and isinstance(x, Gtk.Widget)
                                          and type(x).__name__ == "StatusPage"]


def test_share_with_remote_person(win, srv):
    from hlidskjalf import sharing as S
    st = {"federation": True, "shares": [{"type": "remote", "target": "@bert@b.example", "name": "Bert (bert@b.example)",
                                          "role": "view", "effective": "view", "remote": True},
                                         {"type": "remote_group", "target": "team@b.example", "name": "team@b.example", "role": "edit"}]}
    rows = S.share_rows(st)
    assert rows[0]["sub"] == "Other Saga instance" and rows[1]["sub"] == "Group on another Saga instance"
    hits = S.hit_rows({"users": [], "groups": [], "remote": [{"type": "remote", "target": "@bert@b.example", "name": "Bert (bert@b.example)",
                                                              "host": "b.example"}]}, st, "view")
    assert hits[0]["type"] == "remote" and hits[0]["has"] == "has access" and hits[0]["sub"] == "Other Saga instance"
    assert S.search_hint(st).endswith("Other Saga instances: enter name@server.")
