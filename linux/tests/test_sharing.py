"""Teilen (Server 1.3): Rollen -> erlaubte Aktionen, Kopfzeile fremder Seiten, "Mit mir geteilt", schreibgeschuetzte
Seiten, Platzhalter fuer Aufgaben/Kalender/Immich, Verweise ohne Zugriff, Teilen-Dialog, Konflikt mit einer anderen
Person, Zugriff verloren (403/404), Uploads in fremde Seiten (X-Saga-Note), aeltere Server ohne Teilen."""
import json

from gi.repository import Adw, Gtk

from hk_helpers import idle, pump


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label)]


def _open(win, nid):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched, 6)
    pump(None, 0.4)
    idle()
    return v


def _blocks(texts):
    return [{"id": f"x{i}", "type": "paragraph", "data": {"text": t}} for i, t in enumerate(texts)]


def _menu(win, v):
    from hlidskjalf import page as page_mod
    seen = []
    orig = page_mod.menu_popover
    page_mod.menu_popover = lambda parent, ents: (seen.append(ents), orig(parent, ents))[1]
    try:
        v.page_menu(win.menu_btn)
    finally:
        page_mod.menu_popover = orig
    return [e[0] for e in seen[0] if e]


# --- Logik ohne GTK ------------------------------------------------------------------------------------------
def test_roles_and_allowed_actions():
    from hlidskjalf import sharing as s
    assert s.role_of({}) == "owner" and s.role_of({"role": "x"}) == "owner" and s.role_of({"role": "view"}) == "view"
    own, edit, com, view = {"role": "owner"}, {"role": "edit"}, {"role": "comment"}, {"role": "view"}
    for a in ("move", "trash", "archive", "publish", "share", "versions", "template", "favorite", "lock", "duplicate", "home"):
        assert s.allowed(own, a) and not s.allowed(edit, a) and not s.allowed(view, a), a
    assert s.allowed(edit, "wide") and s.allowed(edit, "subpage") and not s.allowed(com, "subpage") and not s.allowed(view, "wide")
    assert all(s.allowed(r, "export") and s.allowed(r, "comments") for r in (own, edit, com, view))
    assert s.can_edit(edit) and not s.can_edit(com) and s.can_comment(com) and not s.can_comment(view)
    assert s.read_only(view) and s.read_only(com) and not s.read_only(edit) and not s.read_only({})
    assert [s.role_name(r) for r in s.ROLES] == ["Can view", "Can comment", "Can edit"] and s.role_name("owner") == "Owner"
    assert s.role_you("comment") == "You can view and comment"
    assert s.role_help("edit").startswith("Can edit: change content")
    assert s.role_hint(view) == "👁 You can only view this page."
    assert s.role_hint({"role": "edit", "locked": True}) == "🔒 The owner locked this page."


def test_shared_by_and_meta_lines():
    from hlidskjalf import sharing as s
    p = {"role": "edit", "owner": {"name": "Anna & Co", "username": "anna", "me": False}}
    tmpl, name, you = s.shared_by(p)
    assert tmpl == "Shared by {name}" and name == "Anna & Co" and you == "You can edit"
    assert s.shared_by_markup(tmpl, name) == "Shared by <b>Anna &amp; Co</b>"
    assert s.shared_by({"role": "owner"}) is None
    assert s.shared_by_tooltip(dict(p, role="view")) == "Shared by Anna & Co · You can view"
    now = 1_000_000.0
    pg = {"updated": now - 120, "created_by": {"name": "Bert", "me": False}, "updated_by": {"name": "Anna", "me": False}}
    assert s.meta_lines(pg, now) == ["Created by Bert", "Last changed by Anna · 2 minutes ago"]
    assert s.meta_lines(dict(pg, updated_by={"name": "Ich", "me": True}, created_by=None), now) == []


def test_share_rows_effective_role_and_remove_texts():
    from hlidskjalf import sharing as s
    st = {"shares": [
        {"type": "user", "target": "bert", "name": "Bert", "role": "comment",
         "via": [{"type": "group", "name": "team", "role": "edit", "note_id": 1}]},
        {"type": "user", "target": "cora", "name": "Cora", "role": "view", "via": []},
        {"type": "group", "target": "team", "name": "team", "role": "edit", "members": 2},
        {"type": "user", "target": "dora", "name": "Dora", "role": "edit",
         "via": [{"type": "user", "name": "Dora", "role": "view", "note_id": 5, "title": "Projekt"}]}]}
    rows = s.share_rows(st)
    assert rows[0]["via"] == "(via team: Can edit)"
    assert rows[0]["remove_body"] == "Bert keeps the role Can edit via team."
    assert rows[1]["via"] is None and rows[1]["remove_body"] == "Cora immediately loses access to this page and its subpages."
    assert rows[2]["sub"] == "Group · 2 people" and rows[2]["remove_body"].startswith("The group team immediately")
    assert rows[3]["via"] is None and rows[3]["remove_body"] == "Dora keeps the role Can view via “Projekt”."
    assert rows[1]["a11y_role"] == "Role of Cora" and rows[1]["a11y_remove"] == "Remove access for Cora"
    inh = s.inherited_rows({"inherited": [{"note_id": 1, "title": "Projekt", "type": "user", "target": "cora", "name": "Cora", "role": "comment"}]})
    assert inh == [{"type": "user", "name": "Cora", "sub": "cora", "role": "Can comment", "from": "via “Projekt”", "note_id": 1}]
    hits = s.hit_rows({"users": [{"username": "cora", "name": "Cora"}, {"username": "emil", "name": "Emil"}], "groups": [{"name": "team"}]},
                      st, "view")
    assert [(h["name"], h["has"]) for h in hits] == [("Cora", "has access"), ("Emil", "Add (Can view)"), ("team", "has access")]
    chips = s.chip_rows({"groups": ["team", "eltern"], "shares": st["shares"]}, "comment")
    assert chips[0]["on"] and chips[0]["tooltip"] == "has access: Can edit"
    assert not chips[1]["on"] and chips[1]["tooltip"] == "Add (Can comment)"
    assert s.search_hint({"scope": True}) == "Only people from your groups. At least 3 characters."
    assert s.search_hint({}) == "At least 3 characters."
    assert s.dialog_notes({"enabled": False, "shareable": False}) == [
        "The admin has turned off sharing on this server – shares currently have no effect.",
        "Archived pages and templates can't be shared."]
    assert not s.can_add({"enabled": False}) and s.can_add({})
    assert s.header_button({"title": "x"}) is None                       # aelterer Server: kein Feld 'role'
    assert s.header_button({"role": "edit"}) is None
    assert s.header_button({"role": "owner", "shared": True}) == {"on": True, "tooltip": "Shared – manage access"}


def test_texts_for_conflict_and_lost_access():
    from hlidskjalf import sharing as s
    t = s.conflict_texts("Bert")
    assert t["heading"] == "Bert just changed this page" and t["reload"] == "Load new version" and t["keep"] == "Keep my version"
    assert t["ask_heading"] == "Overwrite Bert’s changes?" and t["ask_body"] == "Bert’s version stays available in the version history."
    assert s.lost_texts(403)[0] == "View only now" and s.lost_texts(404)[0] == "No access anymore"
    blocks = [{"type": "header", "data": {"text": "Ziele"}}, {"type": "paragraph", "data": {"text": "a <b>fett</b>"}},
              {"type": "list", "data": {"items": [{"content": "eins", "items": [{"content": "zwei"}]}]}},
              {"type": "columns", "data": {"cols": [{"blocks": [{"type": "paragraph", "data": {"text": "links"}}]}]}},
              {"type": "tasks", "data": {}}]
    assert s.doc_text("Titel", blocks) == "Titel\n\nZiele\n\na fett\n\neins\n  zwei\n\nlinks"


def test_masked_link_is_not_a_link(app):
    """Verweis auf eine Seite ohne Zugriff (Text vom Server ersetzt): gedaempft, kein Link, mit 🔒 davor (auch beim
    Bearbeiten, nur Anzeige); gespeichert bleiben href und Text unveraendert"""
    from hlidskjalf.richtext import DECO_TAG, NA_LOCK, NA_TAG, RichText
    html = 'Siehe <a href="/notes#77">Page without access</a> und <a href="/notes#3">Notizen</a>.'
    for editable in (False, True):
        rt = RichText(html, editable=editable)
        buf = rt.get_buffer()
        text = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), True)
        assert text == "Siehe " + NA_LOCK + "Page without access und Notizen."
        lock = buf.get_iter_at_offset(len("Siehe "))
        assert lock.has_tag(buf.get_tag_table().lookup(DECO_TAG)) and not lock.editable(True)   # Schloss: nicht bearbeitbar
        assert rt.link_at(lock) is None                                                     # ... und kein Link
        it = buf.get_iter_at_offset(len("Siehe " + NA_LOCK) + 2)
        assert rt.link_at(it) == ("na", "/notes#77")
        assert rt.link_at(buf.get_iter_at_offset(len("Siehe " + NA_LOCK + "Page without access und ") + 1)) == ("a", "/notes#3")
        assert buf.get_tag_table().lookup(NA_TAG) is not None
        assert rt.html() == html


# --- Store: "Mit mir geteilt" ---------------------------------------------------------------------------------
def test_store_loads_shared_pages(srv):
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt Garten", role="edit", icon="🌱")
    srv.add_foreign(201, "Meilensteine", parent_id=200)
    srv.add_foreign(300, "Ohne Freigabe")
    srv.add_comment(200, "Neu von Anna", block_id="f200")
    store.refresh()
    idle()
    assert store.share_api is True and [p["id"] for p in store.shared_roots()] == [200]
    assert [p["id"] for p in store.shared_children(200)] == [201] and store.is_foreign(201) and not store.is_foreign(1)
    assert store.meta(200)["owner"]["name"] == "Anna Beispiel" and store.shared_unread() == 1
    assert store.path_text(201) == "Projekt Garten" and not store.visible(300)


def test_store_old_server_and_sharing_off(srv):
    from hlidskjalf.store import store
    srv.share_api = False
    store.refresh()
    idle()
    assert store.share_api is False and store.shared == [] and not store.share_off
    srv.share_api = True
    srv.share_enabled = False
    srv.add_foreign(200, "Projekt", role="view")
    store.refresh()
    idle()
    assert store.share_api is True and store.shared == [] and store.share_off


def test_store_drops_cache_of_pages_no_longer_shared(srv):
    """Pruefer: Freigabe entzogen -> zwischengespeicherter fremder Inhalt ist offline nicht mehr lesbar"""
    from hlidskjalf import config
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt", role="view", blocks=_blocks(["Geheim"]))
    srv.add_foreign(201, "Bleibt", role="view")
    store.refresh()
    idle()
    for pid in (200, 201):
        config.atomic_write(store._cache_path(f"api/notes/{pid}"), b'{"id": %d}' % pid)
    del srv.grants[200]
    store.refresh()
    idle()
    assert not store.visible(200) and store.cached("api/notes/200") is None
    assert store.cached("api/notes/201") == {"id": 201}


def test_read_only_database_sends_nothing(srv, monkeypatch):
    """Pruefer: Datenbank mit Rolle Lesen - Haken, Karte ziehen, neuer Eintrag senden nichts an den Server"""
    from hlidskjalf.database import DBModel
    from hlidskjalf.store import store
    sent = []
    monkeypatch.setattr(store, "run", lambda *a, **k: sent.append(a))
    m = DBModel(999)
    m._apply({"role": "view", "schema": {"props": [{"id": "ok", "type": "checkbox", "name": "Ok"}]},
              "rows": [{"id": 5, "title": "Eintrag", "props": {}}]})
    assert m.readonly and m.locked
    changed = []
    m.connect("changed", lambda *_: changed.append(1))
    m.set(5, "ok", True)
    m.rename(5, "Neu")
    m.add({"ok": True})
    m.new_from_template(7)
    assert sent == [] and m.rows[0]["props"] == {} and m.rows[0]["title"] == "Eintrag" and changed
    m._apply({"role": "edit", "schema": {}, "rows": [{"id": 5, "props": {}}]})
    m.set(5, "ok", True)
    assert len(sent) == 1


# --- Seite: Rollen ---------------------------------------------------------------------------------------------
def test_view_role_is_read_only_and_never_saves(win, srv):
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt Garten", role="view", blocks=_blocks(["Hallo"]) + [{"id": "t1", "type": "tasks", "data": {}},
                    {"id": "c1", "type": "calendar", "data": {}}, {"id": "d1", "type": "database", "data": {"db": 999}}])
    store.refresh()
    idle()
    v = _open(win, 200)
    assert v.doc.role == "view" and v.doc.read_only and v.editor.locked
    labels = _labels(v)
    assert any("Shared by" in (x or "") for x in labels) or any("<b>Anna Beispiel</b>" in (x or "") for x in labels)
    assert "You can view" in labels
    assert "✓ Tasks · Only visible to the owner" in labels and "🗓 Calendar · Only visible to the owner" in labels
    assert "🗃️ Page without access" in labels
    assert not any(isinstance(w, Gtk.Button) and w.get_tooltip_text() == "Insert block below" for w in _walk(v))
    # Eingaben (z. B. ueber eine eingebettete Ansicht) werden nie gesendet
    n0 = len([e for e in srv.log if e[0] == "PATCH"])
    v.doc.root[0]["data"]["text"] = "geaendert"
    v.editor.text_changed()
    v.editor.flush()
    idle()
    assert len([e for e in srv.log if e[0] == "PATCH"]) == n0 and not v.doc.dirty
    assert json.loads(srv.foreign[200]["body"])["blocks"][0]["data"]["text"] == "Hallo"
    # Kopfleiste: kein Favorit, kein Teilen; Menue ohne Nur-Eigentuemer-Aktionen
    assert not win.star_btn.get_visible() and not win.share_btn.get_visible()
    labels = _menu(win, v)
    assert labels[0] == "Shared by Anna Beispiel · You can view"
    for bad in ("Move to …", "Version history …", "Duplicate", "Move to trash", "Archive", "Lock page", "As template",
                "Full width", "Set as home page", "Share …"):
        assert bad not in labels, bad
    assert "Export" in labels and "Copy link" in labels and any(x.startswith("Comments") for x in labels)


def test_edit_role_saves_and_uploads_for_owner(win, srv):
    from hlidskjalf.api import client
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt Garten", role="edit", blocks=_blocks(["Hallo"]))
    store.refresh()
    idle()
    v = _open(win, 200)
    assert v.doc.role == "edit" and not v.doc.read_only and not v.editor.locked
    v.doc.root[0]["data"]["text"] = "Bert ergänzt"
    v.editor.text_changed()
    v.editor.flush()
    assert pump(lambda: json.loads(srv.foreign[200]["body"])["blocks"][0]["data"]["text"] == "Bert ergänzt", 6)
    labels = _menu(win, v)
    assert "Full width" in labels and "Move to …" not in labels
    # Uploads in fremde Seiten: Kopfzeile X-Saga-Note (Datei gehoert dem Eigentuemer)
    assert v.upload_note == 200
    client.upload(b"x" * 10, "a.png", note=v.upload_note)
    assert srv.headers_log[-1][2] == {"X-Saga-Note": "200"} and srv.headers_log[-1][1].endswith("/api/files")
    n = len(srv.headers_log)
    client.upload(b"x" * 10, "b.png")
    assert len(srv.headers_log) == n                                     # eigene Uploads: keine Kopfzeile


def test_owner_page_shows_share_button_and_menu(win, srv):
    v = _open(win, 1)
    assert win.share_btn.get_visible() and win.share_btn.get_tooltip_text() == "Share with people or groups"
    assert win.star_btn.get_visible()
    labels = _menu(win, v)
    assert "Share …" in labels and "Move to …" in labels and "Move to trash" in labels
    srv.shares[1] = [{"type": "user", "target": "bert", "role": "view"}]
    v.doc.load(force=True)
    pump(None, 0.5)
    idle()
    assert win.share_btn.get_tooltip_text() == "Shared – manage access" and "✓ Share …" in _menu(win, v)


def test_old_server_has_no_sharing_ui(win, srv):
    from hlidskjalf.store import store
    srv.share_api = False
    store.refresh()
    idle()
    v = _open(win, 1)
    assert not win.share_btn.get_visible() and not win.cm_btn.get_visible()
    assert not [w for w in _walk(v) if isinstance(w, Gtk.Button) and (w.has_css_class("hk-cm-add") or w.has_css_class("hk-cm-bubble"))]
    labels = _menu(win, v)
    assert "Share …" not in labels and not any(x.startswith("Comments") for x in labels)


def test_sidebar_shared_with_me(win, srv):
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt Garten", role="edit")
    srv.add_foreign(201, "Meilensteine", parent_id=200)
    srv.add_comment(200, "Neu", block_id="f200")
    srv.shares[3] = [{"type": "group", "target": "team", "role": "view"}]
    store.refresh()
    idle()
    sb = win.sidebar
    sb.fill(force=True)
    labels = _labels(sb.box)
    assert "Shared with me" in labels and "Projekt Garten" in labels and "Anna Beispiel" in labels and "●" in labels
    row = sb.rows[("page", 200)]
    assert row.get_tooltip_text() == "Shared by Anna Beispiel · You can edit"
    own = sb.rows[("page", 3)]
    assert any(isinstance(w, Gtk.Image) and w.get_tooltip_text() == "Shared" for w in _walk(own))
    assert "Meilensteine" not in labels
    sb.toggle(200)
    assert "Meilensteine" in _labels(sb.box)
    # Seitenaktionen einer fremden Seite: nur Unterseite (Bearbeiten) und Link
    from hlidskjalf import window as wmod
    seen = []
    orig = wmod.menu_popover
    wmod.menu_popover = lambda parent, ents: (seen.append(ents), orig(parent, ents))[1]
    try:
        win.page_actions(sb.rows[("page", 200)], store.meta(200))        # nach toggle neu gezeichnet: aktuelle Zeile
    finally:
        wmod.menu_popover = orig
    assert [e[0] for e in seen[0]] == ["New subpage", "Copy link"]


def test_sidebar_hint_when_sharing_off(win, srv):
    from hlidskjalf.store import store
    srv.share_enabled = False
    srv.add_foreign(200, "Projekt", role="view")
    store.refresh()
    idle()
    win.sidebar.fill(force=True)
    labels = _labels(win.sidebar.box)
    assert "Shared with me" in labels
    assert "Sharing is currently turned off on this server – pages shared with you are not available." in labels


# --- Konflikt mit einer anderen Person -------------------------------------------------------------------------
def _person_conflict(win, srv, nid=200):
    v = _open(win, nid)
    store_note = srv.foreign.get(nid) or srv.notes[nid]
    store_note["rev"] += 1
    store_note["updated_by"] = "bert"
    store_note["body"] = json.dumps({"blocks": _blocks(["Fassung von Bert"])})
    v.doc.root[0]["data"]["text"] = "Meine Fassung"
    v.editor.text_changed()
    v.editor.flush()
    assert pump(lambda: win.get_visible_dialog() is not None, 6), "kein Konflikt-Dialog"
    return v, win.get_visible_dialog()


def test_conflict_with_person_load_new_version(win, srv):
    from hlidskjalf.sharing import TextDialog
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt", role="edit", blocks=_blocks(["Start"]))
    store.refresh()
    idle()
    v, d = _person_conflict(win, srv)
    assert isinstance(d, Adw.AlertDialog) and d.get_heading() == "Bert Beispiel just changed this page"
    assert d.get_body() == "Your latest changes are not saved yet."
    assert d.get_response_label("reload") == "Load new version" and d.get_default_response() == "reload"
    assert d.get_close_response() == "later"
    d.emit("response", "reload")
    d.force_close()
    assert pump(lambda: isinstance(win.get_visible_dialog(), TextDialog), 3)
    t = win.get_visible_dialog()
    assert isinstance(t, Adw.AlertDialog) and t.get_heading() == "Your draft" and "Meine Fassung" in t.text
    assert v.doc.root[0]["data"]["text"] == "Fassung von Bert" and v.doc.conflict is None
    assert v.copy_rev.get_reveal_child()                                 # eigene Fassung auch als lokale Kopie
    # einheitlicher Zustandsdialog: Hauptknopf "Kopieren", zweiter Knopf "Schliessen" (Esc), Textfeld als extra child
    assert t.get_response_label("copy") == "Copy" and t.get_response_label("close") == "Close"
    assert t.get_default_response() == "copy" and t.get_close_response() == "close"
    assert t.get_extra_child() is t.scroll and t.view.get_buffer().get_property("text") == t.text
    assert t.scroll.get_max_content_height() <= int(win.get_height() * 0.4) + 1
    toasts = []
    win.toast = lambda x: toasts.append(x)
    t.emit("response", "copy")
    assert toasts == ["Text copied"]
    del win.toast
    t.force_close()


def test_conflict_with_person_keep_mine_asks_and_overwrites(win, srv, monkeypatch):
    from hlidskjalf import publish
    from hlidskjalf.store import store
    asked = []
    monkeypatch.setattr(publish, "confirm", lambda parent, h, b, label, ok, cancelled=None, destructive=True:
                        (asked.append((h, b, label)), ok()))
    srv.add_foreign(200, "Projekt", role="edit", blocks=_blocks(["Start"]))
    store.refresh()
    idle()
    v, d = _person_conflict(win, srv)
    d.emit("response", "mine")
    d.force_close()
    assert pump(lambda: json.loads(srv.foreign[200]["body"])["blocks"][0]["data"]["text"] == "Meine Fassung", 6)
    assert asked == [("Overwrite Bert Beispiel’s changes?", "Bert Beispiel’s version stays available in the version history.",
                      "Overwrite")]
    assert srv.last_overwrite is True and v.doc.conflict is None


def test_conflict_banner_names_person(win, srv):
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt", role="edit", blocks=_blocks(["Start"]))
    store.refresh()
    idle()
    v, d = _person_conflict(win, srv)
    d.close()                                                            # Esc: spaeter entscheiden
    pump(None, 0.3)
    assert v.conflict_banner.get_revealed()
    assert v.conflict_banner.get_title() == "⚠ Bert Beispiel just changed this page. Your latest changes are not saved yet."
    assert json.loads(srv.foreign[200]["body"])["blocks"][0]["data"]["text"] == "Fassung von Bert"


# --- Zugriff verloren -----------------------------------------------------------------------------------------
def test_lost_access_403_offers_text_and_stops_saving(win, srv):
    from hlidskjalf import drafts
    from hlidskjalf.sharing import TextDialog
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt", role="edit", blocks=_blocks(["Start"]))
    store.refresh()
    idle()
    v = _open(win, 200)
    srv.grants[200] = "view"                                             # Rolle auf Lesen gefallen
    v.doc.root[0]["data"]["text"] = "Mein letzter Text"
    v.editor.text_changed()
    v.editor.flush()
    assert pump(lambda: isinstance(win.get_visible_dialog(), TextDialog), 6)
    t = win.get_visible_dialog()
    assert t.get_heading() == "View only now" and "Mein letzter Text" in t.text
    assert t.get_body().startswith("You can no longer edit this page.")
    assert drafts.read(200) is None and not v.doc._retry_src
    # die Seite zeigt sofort (hinter dem Dialog) den neuen Zustand: schreibgeschuetzt, Etikett "Du kannst lesen"
    assert v.editor.locked and "You can view" in _labels(v.scroll.get_child())
    assert "You can edit" not in _labels(v.scroll.get_child())
    assert not v.copy_restore.get_visible()                              # kein "Wiederherstellen" bei Nur-Lesen
    t.force_close()
    assert pump(lambda: v.doc.fetched and v.doc.role == "view", 6)
    idle()
    assert v.editor.locked and json.loads(srv.foreign[200]["body"])["blocks"][0]["data"]["text"] == "Start"


def test_lost_access_404_shows_no_access(win, srv):
    from hlidskjalf.sharing import TextDialog
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt", role="edit", blocks=_blocks(["Start"]))
    store.refresh()
    idle()
    v = _open(win, 200)
    del srv.grants[200]                                                  # Freigabe entzogen
    v.doc.root[0]["data"]["text"] = "Weg"
    v.editor.text_changed()
    v.editor.flush()
    assert pump(lambda: isinstance(win.get_visible_dialog(), TextDialog), 6)
    t = win.get_visible_dialog()
    assert t.get_heading() == "No access anymore" and "Weg" in t.text
    assert v.no_access and any(isinstance(w, Adw.StatusPage) for w in _walk(v.scroll.get_child()))  # sofort, hinter dem Dialog
    t.force_close()
    assert pump(lambda: v.doc.loaded and not v.doc.fetched, 6)
    idle()
    page = v.scroll.get_child()
    sp = next(w for w in _walk(page) if isinstance(w, Adw.StatusPage))
    assert sp.get_title() == "No access to this page"
    btns = [w for w in _walk(sp) if isinstance(w, Gtk.Button)]
    assert [b.get_label() for b in btns] == ["Try again", "Go to “Shared with me”"]
    # Kopfzeile ohne Titel/Symbol der Seite, kein Seitenmenue, kein Kommentar-Knopf
    assert win.wtitle.get_title() == "" and not win.menu_btn.get_visible() and not win.cm_btn.get_visible()
    shown = []
    win.sidebar.focus_shared = lambda: shown.append(1)
    btns[1].emit("clicked")
    assert pump(lambda: shown, 2) and win.split.get_show_sidebar()
    del win.sidebar.focus_shared


# --- Teilen-Dialog -------------------------------------------------------------------------------------------
def _share_dialog(win, nid=1):
    from hlidskjalf.sharing import ShareDialog
    win.share_dialog(nid)
    assert pump(lambda: isinstance(win.get_visible_dialog(), ShareDialog), 5)
    d = win.get_visible_dialog()
    pump(None, 0.2)
    return d


def test_share_dialog_search_add_change_remove(win, srv, monkeypatch):
    from hlidskjalf import publish
    asked = []
    monkeypatch.setattr(publish, "confirm", lambda parent, h, b, label, ok, cancelled=None, destructive=True:
                        (asked.append((h, b, label, destructive)), ok()))
    _open(win, 1)
    d = _share_dialog(win)
    toasts = []
    d.toast = lambda t: toasts.append(t)
    assert d.get_title() == "Share" and d.q.get_title() == "Search for a person or group"
    assert d.role.get_subtitle().startswith("Can view: see and export")
    assert not d.status.get_visible() and not d.q.has_css_class("error")     # leer: kein "Mindestens 3 Zeichen."
    assert d.chips.get_visible() and "team" in _labels(d.chips)              # eigene Gruppen als Chips
    assert d.link.note.get_label() == "The link opens the page for everyone who has access."   # Untertitel der Link-Zeile
    assert "Not shared with anyone yet. Search for a person or group above." in [r.get_title() for r in d._rows[d.g_access]]
    assert d.g_access.get_description() == "Subpages and entries are included."
    assert d.link.raw == "https://saga.test/notes#1"
    d.q.set_text("co")                                                       # unter 3 Zeichen: keine Suche
    pump(None, 0.4)
    assert not d.g_hits.get_visible()
    assert d.status.get_visible() and d.status.get_label() == "At least 3 characters." and d.q.has_css_class("error")
    d.role.set_selected(1)                                                   # Kommentieren
    d.q.set_text("cor")
    assert pump(lambda: d.g_hits.get_visible(), 3)
    assert not d.status.get_visible() and not d.q.has_css_class("error")
    hit = d._rows[d.g_hits][0]
    assert hit.get_title() == "Cora Muster" and "Add (Can comment)" in _labels(hit)
    hit.emit("activated")
    assert pump(lambda: srv.shares.get(1) == [{"type": "user", "target": "cora", "role": "comment"}], 5)
    idle()
    assert toasts[-1] == "✓ Shared with Cora Muster (Can comment)" and d.q.get_text() == ""
    assert win.share_btn.get_tooltip_text() == "Shared – manage access"
    row = next(r for r in d._rows[d.g_access] if r.get_title() == "Cora Muster")
    dd = next(w for w in _walk(row) if isinstance(w, Gtk.DropDown))
    dd.set_selected(2)                                                       # Bearbeiten
    assert pump(lambda: srv.shares[1][0]["role"] == "edit", 5)
    idle()
    assert toasts[-1] == "✓ Role changed"
    # Gruppe per Chip, danach hat bert ueber team mehr Rechte als direkt
    d.role.set_selected(2)
    chip = next(w for w in _walk(d.chips) if isinstance(w, Gtk.Button))
    assert not any(isinstance(w, Gtk.Image) and w.has_css_class("hk-chip-check") for w in _walk(chip))
    chip.emit("clicked")
    assert pump(lambda: any(s["type"] == "group" for s in srv.shares[1]), 5)
    idle()
    chip = next(w for w in _walk(d.chips) if isinstance(w, Gtk.Button))    # Gruppe hat jetzt Zugriff: ✓ im Chip
    assert any(isinstance(w, Gtk.Image) and w.has_css_class("hk-chip-check") for w in _walk(chip))
    srv.shares[1].append({"type": "user", "target": "bert", "role": "view"})
    d._sync(srv._share_state(1))
    bert = next(r for r in d._rows[d.g_access] if r.get_title() == "Bert Beispiel")
    assert "(via team: Can edit)" in bert.get_subtitle()
    rm = next(w for w in _walk(bert) if isinstance(w, Gtk.Button) and w.get_tooltip_text() == "Remove access")
    rm.emit("clicked")
    assert asked[-1] == ("Remove access?", "Bert Beispiel keeps the role Can edit via team.", "Remove", True)
    assert pump(lambda: not any(s["target"] == "bert" for s in srv.shares[1]), 5)
    idle()
    assert toasts[-1] == "✓ Access removed"
    d.force_close()


def test_share_dialog_inherited_and_errors(win, srv):
    srv.shares[1] = [{"type": "user", "target": "cora", "role": "comment"}]
    _open(win, 2)
    d = _share_dialog(win, 2)
    assert d.g_inh.get_visible() and d.g_inh.get_title() == "Through parent pages"
    r = d._rows[d.g_inh][0]
    assert r.get_title() == "Cora Muster" and "Can comment" in _labels(r) and "via “Startseite”" in _labels(r)
    d.q.set_text("niemand")
    assert pump(lambda: d.status.get_label() == "No one found.", 3)
    assert d.q.has_css_class("error")
    d.force_close()
    srv.share_enabled = False
    d = _share_dialog(win, 1)
    assert d.g_notes.get_visible() and "turned off sharing" in d.l_notes.get_label()
    assert not d.q.get_sensitive() and not d.chips.get_visible()
    d.force_close()
