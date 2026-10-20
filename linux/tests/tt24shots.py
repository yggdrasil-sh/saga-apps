# Bildschirmfotos zu den Funden des kritischen Linux-Tests (Saga 2.4) mit nachgebautem Server:
#   sh tests/run-container.sh app:tt24shots.py   (HK_OUT: Zielordner; Dateien linux-tt24-NN-name.png)
# Optional HK_CSS_SHIM=<css>: zusaetzliches Stylesheet (nur fuer Testrechner mit GTK < 4.16 ohne CSS-Variablen).
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import v2shots as V  # noqa: E402 - Aufbau (Server, Chats, Konten) wie bei den 2.0-Bildern

from gi.repository import Adw, Gdk, Gtk  # noqa: E402

S = {}


def shoot(g, name, widget=None):
    g["shot"](os.path.join(g["out"], f"linux-tt24-{name}.png"), widget)


def start(g):
    shim = os.environ.get("HK_CSS_SHIM")
    if shim and os.path.exists(shim):
        p = Gtk.CssProvider()
        p.load_from_path(shim)
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), p, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1)
    V.start(g)
    srv = V.S["srv"]
    srv.notes[1]["title"] = "Projekt Testlauf"
    srv.notes[2]["title"] = "Unterseite A"
    srv.notes[1]["body"] = json.dumps({"blocks": [
        {"id": "h1", "type": "header", "data": {"text": "Überschrift Eins", "level": 2}},
        {"id": "p1", "type": "paragraph", "data": {"text": "Normaler Absatz mit Text."}},
        {"id": "lk", "type": "paragraph", "data": {"text": '📄 <a href="/notes#2">Unbenannt</a>'}},
        {"id": "c1", "type": "callout", "data": {"emoji": "💡", "text": "Wichtiger Hinweis-Text"}},
        {"id": "db1", "type": "database", "data": {"db": 10}},
        {"id": "p9", "type": "paragraph", "data": {"text": ""}}]})
    g["win"].set_default_size(1100, 760)


def page(g):
    g["win"].stack.set_visible_child_name("main")
    g["win"].show_root(("page", 1))


def slash(g):
    v = g["win"].current
    S["v"] = v
    rt = v.kit.by_key["p9"]
    rt.focus_at("end")
    rt.get_buffer().insert(rt.get_buffer().get_end_iter(), "/")


def slash_scroll(g):
    v = S["v"]
    for _ in range(18):
        v.kit.on_key(v.kit.by_key["p9"], Gdk.KEY_Down, 0)


def slash_shot(g):
    shoot(g, "01-slash-alle-typen")
    shoot(g, "01b-slash-menue-gerollt", S["v"].suggest.pop)
    v = S["v"]
    buf = v.kit.by_key["p9"].get_buffer()
    buf.set_text("/tabelle")


def slash_table_shot(g):
    shoot(g, "02-slash-tabelle-zuerst", S["v"].suggest.pop)
    v = S["v"]
    v.kit.on_key(v.kit.by_key["p9"], Gdk.KEY_Return, 0)


def table_type(g):
    v = S["v"]
    tv = v.kit.cells.get("p9/#0,0")
    if tv is not None and g["win"].get_focus() is tv:
        tv.get_buffer().insert_at_cursor("A1 (getippt)")
    adj = v.scroll.get_vadjustment()
    adj.set_value(adj.get_upper())


def table_shot(g):
    shoot(g, "03-tabelle-fokus-erste-zelle")


def undo_delete(g):
    S["v"].editor.delete("p1", undo=True)


def undo_shot(g):
    shoot(g, "04-block-geloescht-rueckgaengig")
    S["v"].last_undo_toast.emit("button-clicked")
    from hlidskjalf.dialogs import VersionsDialog
    d = VersionsDialog(g["win"], 1, lambda: None)
    d.present(g["win"])
    d.versions = [{"id": 7, "created": 1760000000, "title": "Projekt Testlauf"}]
    d.sel = 7
    S["vd"] = d


def version_ask(g):
    S["vd"]._restore()


def version_shot(g):
    shoot(g, "05-version-rueckfrage")
    V.close_all(g)
    from hlidskjalf.store import store
    store.trashed_ids = store.trashed_ids | {1}
    S["v"]._trash_state()


def trash_shot(g):
    shoot(g, "06-papierkorb-gesperrt")
    from hlidskjalf.store import store
    store.trashed_ids = set()
    S["v"]._trash_state()


def call_invite(g):
    from hlidskjalf.calls import calls
    import secrets
    srv = V.S["srv"]
    os.environ["HK_NO_RING"] = "1"
    srv.calls_api = True
    cid = secrets.token_hex(16)
    srv.calls[cid] = {"id": cid, "chat_id": V.S["team"], "media": "video", "state": "ringing", "kind": "group", "role": "member",
                      "invite": "ringing", "mine": [], "silent": False, "ring_s": 45, "expires_in": 40,
                      "from": {"username": "anna", "name": "Anna Beispiel", "chat": "Team"}, "legs": []}
    calls.on_live("call.invite", {"call_id": cid, "chat_id": V.S["team"], "kind": "group", "media": "video"})
    S["call"] = cid


def call_shot(g):
    shoot(g, "07-gruppenanruf-beitreten")
    from hlidskjalf.calls import calls
    calls._done(S["call"])
    g["win"].shortcuts()


def keys_shot(g):
    shoot(g, "08-tastenkuerzel-strg")
    V.close_all(g)
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    g["win"].show_root(("page", 1))


def dark_shot(g):
    shoot(g, "09-dunkel-seite-symbole")
    g["win"].settings_dialog()


def dark_settings_shot(g):
    shoot(g, "10-dunkel-einstellungen")
    V.close_all(g)
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT)
    g["win"].set_default_size(370, 760)


def narrow_page(g):
    g["win"].show_root(("page", 1))


def narrow_shot(g):
    shoot(g, "11-schmal-seite")
    g["win"].show_root(("page", 10))


def narrow_db_shot(g):
    shoot(g, "12-schmal-datenbank")
    g["win"].settings_dialog()


def narrow_settings_shot(g):
    shoot(g, "13-schmal-einstellungen")
    V.close_all(g)
    g["win"].set_default_size(1100, 760)


steps = [(300, start), (800, page), (1500, slash), (600, slash_scroll), (800, slash_shot), (800, slash_table_shot),
         (1200, table_type), (800, table_shot), (300, undo_delete), (600, undo_shot), (800, version_ask), (800, version_shot),
         (1200, trash_shot), (800, call_invite), (1500, call_shot), (1200, keys_shot), (1800, dark_shot), (1500, dark_settings_shot),
         (1200, narrow_page), (2500, narrow_shot), (2500, narrow_db_shot), (1800, narrow_settings_shot)]
