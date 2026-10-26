"""Saga 2.3 Teil 3 - persoenliches Backup ("Mein Backup"), Nextcloud-Dateien in Seiten, Benachrichtigungen/Push fuer Backups
(docs/APP-API.md) gegen den nachgebauten Server. Alte Server (ohne die Endpunkte): nichts davon sichtbar, alles wie bisher.
Gegen einen echten Server: tests/real23c.py."""
import json

from gi.repository import Adw, Gtk

from hk_helpers import idle, pump


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _texts(w):
    out = []
    for x in _walk(w):
        if isinstance(x, Gtk.Label) and x.get_mapped():
            out.append(x.get_label())
        elif isinstance(x, Adw.PreferencesRow) and x.get_mapped():
            out.append(x.get_title())
            if isinstance(x, Adw.ActionRow):
                out.append(x.get_subtitle() or "")
    return [t for t in out if t]


def _status(**kw):
    s = {"allowed": True, "age_ok": True, "limits": {"max_mb": 200, "min_h": 0, "max_schedule": "weekly"}, "target": None,
         "flow": {"state": "", "login": None, "message": ""}, "keys": [], "pending_key": None, "schedule": "off",
         "effective_schedule": "off", "time": "02:40", "weekday": 6, "keep": 7,
         "cats": {k: True for k in ("pages", "templates", "archive", "trash", "versions", "files", "comments", "tasks", "bookmarks", "settings")},
         "exclude": [], "running": False, "last_ok": None, "last_attempt": None, "next": None, "runs": [], "ready": False,
         "pages": [{"id": 1, "title": "Startseite", "icon": "", "parent": None, "db": False},
                   {"id": 2, "title": "Unterseite", "icon": "", "parent": 1, "db": False}]}
    s["cats"]["comments_others"] = False
    s.update(kw)
    return s


def _mock23(srv, status=None, nc=None, page_nc=None):
    """Endpunkte von Saga 2.3 (persoenliches Backup, Nextcloud) im Schein-Server"""
    srv.bk = status if status is not None else _status()
    srv.nc = nc if nc is not None else {"enabled": True, "connected": True, "me": "test", "server": "https://cloud.test", "user": "test",
                                         "since": 1790000000, "used": 1790000100, "suggest": None, "flow": {"state": "", "login": None, "message": ""}}
    srv.page_nc = page_nc or {}
    srv.calls = []
    orig = srv._api

    def api(method, path, q, body):
        srv.calls.append((method, path, body))
        if path == "api/account/backup":
            return srv._reply(200, srv.bk)
        if path == "api/account/backup/key/generate":
            return srv._reply(200, {"private": "# public key: age1abc\nAGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQABCDEF",
                                    "public": "age1abc", "filename": "saga-backup-test.key"})
        if path.startswith("api/account/backup/"):
            return srv._reply(200, {"ok": True})
        if path == "api/settings/nextcloud":
            return srv._reply(200, srv.nc)
        if len(path.split("/")) == 4 and path.startswith("api/notes/") and path.endswith("/nc"):
            return srv._reply(200, srv.page_nc.get(int(path.split("/")[2]), {"enabled": True, "items": []}))
        return orig(method, path, q, body)
    srv._api = api
    return srv


# --- alte Server: unsichtbar ---------------------------------------------------------------------------------------------
def test_old_server_hides_backup_nextcloud_and_slash_item(win, srv):
    from hlidskjalf import nextcloud as nc
    from hlidskjalf.settings import SettingsDialog
    from hlidskjalf.store import store
    store.server_version = "2.2.0"
    d = SettingsDialog(win)
    d.present(win)
    pump(None, 0.5)
    idle()
    pump(None, 0.3)
    assert not d.pages["backup"].get_visible()                       # Mock: {} ohne "allowed" wie ein alter Server
    g = next(x for x in _walk(d) if isinstance(x, nc.NextcloudGroup))
    assert not g.get_visible()
    assert not nc.supported()
    d.force_close()


def test_slash_item_only_on_23(win, srv):
    from hlidskjalf import nextcloud as nc
    from hlidskjalf.store import store
    store.server_version = "2.3.0"
    assert nc.supported()
    store.server_version = "2.2.9"
    assert not nc.supported()


# --- Mein Backup -----------------------------------------------------------------------------------------------------------
def test_backup_page_status_flow_and_schedule(win, srv):
    _mock23(srv, status=_status(flow={"state": "wait", "login": "https://cloud.test/login/v2/flow/abc", "message": ""}))
    from hlidskjalf.settings import SettingsDialog
    from hlidskjalf.store import store
    store.server_version = "2.3.0"
    opened = []
    win.open_uri = lambda u: opened.append(u)
    d = SettingsDialog(win)
    d.present(win)
    d.show_page("backup")
    bp = d.pages["backup"]
    assert pump(lambda: bp.get_visible() and bp.S is not None, 5)
    pump(lambda: d.sub == "backup" and bp.get_mapped(), 3)
    assert d.sub == "backup" and bp.get_mapped()                       # erst unsichtbar, dann doch gezeigt (Unterseite)
    tx = _texts(bp)
    assert "none yet" in tx and "Nextcloud found" in tx
    btn = next(b for b in _walk(bp) if isinstance(b, Gtk.Button) and b.get_label() == "Sign in to Nextcloud")
    btn.emit("clicked")
    assert opened == ["https://cloud.test/login/v2/flow/abc"]
    assert bp.timer                                                    # fragt waehrend der Anmeldung ab
    # max_schedule weekly: "taeglich" nicht angeboten; Ausschluesse aus "exclude"/"pages"
    sch = bp.plan[0]
    assert sch.get_model().get_n_items() == 2
    assert bp.ex_pick.get_model().get_n_items() == 3
    bp.ex_pick.set_selected(2)                                         # Unterseite
    pump(None, 0.2)
    assert bp.excluded == [2]
    bp.content_save.emit("clicked")
    assert pump(lambda: any(c[0] == "PUT" and c[1] == "api/account/backup/settings" for c in srv.calls), 5)
    put = [c for c in srv.calls if c[1] == "api/account/backup/settings"][-1][2]
    assert put["exclude"] == [2] and put["cats"]["comments_others"] is False
    bp._stop()
    d.force_close()


def test_backup_page_without_exclusions_and_disabled(win, srv):
    s = _status()
    s.pop("exclude")
    s.pop("pages")
    _mock23(srv, status=s)
    from hlidskjalf.userbackup import BackupPage
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(win)
    d.present(win)
    bp = d.pages["backup"]
    assert pump(lambda: bp.S is not None, 5)
    assert getattr(bp, "ex_row", None) is None and bp.cat_rows        # Server ohne Ausschluesse: nur Kategorien
    d.show_page("backup")
    srv.bk = _status(allowed=False)
    bp.load()
    assert pump(lambda: bp.S.get("allowed") is False, 5)
    assert "Personal backups are turned off on this server." in _texts(bp)
    assert isinstance(bp, BackupPage)
    d.force_close()


def test_new_key_dialog_never_stores_and_drops_on_close(win, srv):
    _mock23(srv)
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(win)
    d.present(win)
    bp = d.pages["backup"]
    assert pump(lambda: bp.S is not None, 5)
    bp._generate(Gtk.Button())
    assert pump(lambda: bp.key_dialog is not None, 5)
    kd = bp.key_dialog
    assert "AGE-SECRET-KEY-" in kd.private
    pump(None, 0.5)
    kd.close()
    assert pump(lambda: any(c[1] == "api/account/backup/key/confirm" and c[2] == {"drop": True} for c in srv.calls), 5)
    assert kd.private == "" and kd.view.get_buffer().get_property("text") == ""
    from hlidskjalf import config
    import os
    for root, _d, files in os.walk(config.DATA_DIR):
        for f in files:
            assert b"AGE-SECRET-KEY" not in open(os.path.join(root, f), "rb").read()
    d.force_close()


# --- Benachrichtigungen + Push --------------------------------------------------------------------------------------------
def test_backup_notifications_readable():
    from hlidskjalf.notifications import item_text, item_title, kind_label, parse_url
    it = {"kind": "userbackup.failed", "text": "Persönliches Backup fehlgeschlagen: Ziel nicht erreichbar", "title": "Mein Backup",
          "url": "/?settings=backup", "access": True, "system": True}
    assert item_text(it) == it["text"] and item_title(it) == "Mein Backup"
    assert item_text({"kind": "userbackup.failed"}) == "My backup failed" and item_title({"kind": "userbackup.failed"}) == "My backup"
    assert item_text({"kind": "backup.failed"}) == "Backup failed" and item_title({"kind": "backup.failed"}) == "Backups"
    assert kind_label("backup.failed") == "Admin: backup failed" and kind_label("userbackup.failed") == "My backup failed"
    assert parse_url("/?settings=backup") == ("settings", "backup", None)
    assert parse_url("/notes#12") == ("page", 12, None)


def test_notification_opens_backup_settings_and_admin_in_browser(win, srv):
    _mock23(srv)
    opened = []
    win.open_uri = lambda u: opened.append(u)
    win.open_notification({"kind": "backup.failed", "url": "https://admin.saga.test/backups", "access": True})
    assert opened == ["https://admin.saga.test/backups"]
    win.open_notification({"kind": "userbackup.failed", "url": "/?settings=backup", "access": True})
    from hlidskjalf.settings import SettingsDialog
    assert pump(lambda: isinstance(win.get_visible_dialog(), SettingsDialog) and win.get_visible_dialog().sub == "backup" and
                win.get_visible_dialog().pages["backup"].get_mapped(), 5)
    win.get_visible_dialog().force_close()


def test_push_admin_category_only_when_offered(win, srv):
    from hlidskjalf.push import PushPage
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(win)
    pp = d.pages["push"]
    assert isinstance(pp, PushPage)
    base = {"server": {"enabled": True, "unifiedpush": True}, "devices": [],
            "settings": {"cats": {"share": True, "comment": True, "chat": True, "publish": True, "call": True, "admin": True}, "names": False}}
    pp.data = dict(base, categories=["share", "comment", "chat", "publish", "call"])
    pp.fill()
    assert "Admin: backup failed" not in [r.get_title() for r in _walk(pp) if isinstance(r, Adw.SwitchRow)]
    pp.data = dict(base, categories=["share", "comment", "chat", "publish", "call", "admin"])
    pp.fill()
    assert "Admin: backup failed" in [r.get_title() for r in _walk(pp) if isinstance(r, Adw.SwitchRow)]
    pp.data = dict(base)                                                 # aelterer Server ohne "categories": wie bisher
    pp.fill()
    assert "Admin: backup failed" in [r.get_title() for r in _walk(pp) if isinstance(r, Adw.SwitchRow)]
    d.force_close()


# --- Nextcloud-Block -------------------------------------------------------------------------------------------------------
NCB = {"inst": "https://cloud.test", "fileid": "4711", "kind": "file", "name": "Bericht.pdf", "mime": "application/pdf", "size": 52311,
       "mtime": 1792400000, "author": "anna", "public": False, "fed": False, "sig": "s1"}


def test_nc_block_card_kept_unchanged_on_save(win, srv):
    _mock23(srv, page_nc={3: {"enabled": True, "items": [{"fileid": "4711", "author": "anna", "ok": True, "name": "Bericht.pdf",
                                                           "size": 52311, "mtime": 1792400000, "mime": "application/pdf"}]}})
    srv.notes[3]["body"] = json.dumps({"blocks": [{"id": "a", "type": "paragraph", "data": {"text": "Eins"}},
                                                  {"id": "n", "type": "nextcloud", "data": dict(NCB)}]})
    from hlidskjalf import nextcloud as nc
    nc.drop_status()
    win.show_page(3)
    v = win.current
    assert pump(lambda: v.doc.fetched, 5)
    card = None
    assert pump(lambda: any(isinstance(x, nc.NcCard) for x in _walk(v)), 5)
    card = next(x for x in _walk(v) if isinstance(x, nc.NcCard))
    assert pump(lambda: "Bericht.pdf" in _texts(card), 5)
    assert any("Inserted from another person" in t for t in _texts(card))      # fremder Block: keine Schalter
    assert not any(isinstance(x, Gtk.CheckButton) for x in _walk(card))
    v.doc.root[0]["data"]["text"] = "Eins geaendert"
    v.editor.changed()
    v.editor.flush()
    idle()
    saved = json.loads(srv.notes[3]["body"])["blocks"]
    assert saved[1] == {"id": "n", "type": "nextcloud", "data": NCB}


def test_nc_card_unavailable_and_own_switches(win, srv):
    own = dict(NCB, author="test", fileid="5", kind="image", mime="image/png", name="foto.png")
    _mock23(srv, page_nc={3: {"enabled": True, "items": [{"fileid": "5", "author": "test", "ok": False}]}})
    srv.notes[3]["body"] = json.dumps({"blocks": [{"id": "n", "type": "nextcloud", "data": own}]})
    from hlidskjalf import nextcloud as nc
    nc.drop_status()
    nc.info(lambda s: None, fresh=True)
    pump(lambda: nc.me() == "test", 3)
    win.show_page(3)
    v = win.current
    assert pump(lambda: v.doc.fetched and any(isinstance(x, nc.NcCard) for x in _walk(v)), 5)
    card = next(x for x in _walk(v) if isinstance(x, nc.NcCard))
    assert pump(lambda: any("Not available" in t for t in _texts(card)), 5)
    cbs = [x for x in _walk(card) if isinstance(x, Gtk.CheckButton)]
    assert len(cbs) == 2                                                # eigener Block: veroeffentlicht / verbundene Server
    cbs[0].set_active(True)
    v.editor.flush()
    idle()
    d = json.loads(srv.notes[3]["body"])["blocks"][0]["data"]
    assert d["public"] is True and "sig" not in d and d["fileid"] == "5"


def test_strip_pasted_and_known_keys():
    from hlidskjalf import nextcloud as nc
    mine = dict(NCB, author="test", fileid="1")
    other = dict(NCB, author="bert", fileid="2")
    here = dict(NCB, author="cora", fileid="3")
    known = nc.keys_of([{"type": "nextcloud", "data": here}])
    bl = [{"type": "nextcloud", "data": mine}, {"type": "nextcloud", "data": other}, {"type": "nextcloud", "data": here},
          {"type": "columns", "data": {"cols": [{"blocks": [{"type": "nextcloud", "data": other}, {"type": "paragraph", "data": {}}]}]}}]
    rest, n = nc.strip_pasted(json.loads(json.dumps(bl)), known, "test")
    assert n == 2 and [b["data"].get("fileid") for b in rest[:3]] == ["1", "3", None]
    assert len(rest[2]["data"]["cols"][0]["blocks"]) == 1
    rest, n = nc.strip_pasted(json.loads(json.dumps(bl[:3])), known, "test", remote=True)
    assert n == 2 and [b["data"]["fileid"] for b in rest] == ["3"]       # andere Saga-Server: nur schon vorhandene
    assert nc.proxy_path(-2049, NCB, w=320) == "api/notes/-2049/nc/4711?a=anna&w=320"
    assert nc.open_url(NCB) == "https://cloud.test/f/4711" and nc.open_url(dict(NCB, inst="javascript:x")) is None


def test_paste_drops_foreign_nc_block(win, srv):
    _mock23(srv)
    from hlidskjalf import blockclip
    from hlidskjalf import nextcloud as nc
    nc.info(lambda s: None, fresh=True)
    pump(lambda: nc.me() == "test", 3)
    win.show_page(3)
    v = win.current
    assert pump(lambda: v.doc.fetched, 5)
    toasts = []
    win.toast = lambda m: toasts.append(m)
    n0 = len(v.doc.root)
    items = blockclip.parse(json.dumps([{"id": "z", "tool": "nextcloud", "data": NCB},
                                        {"id": "y", "tool": "nextcloud", "data": dict(NCB, author="test", fileid="9")}]).encode())
    v._paste_blocks(items, v.doc.root[-1]["id"])
    pump(None, 0.3)
    assert len(v.doc.root) == n0 + 1 and v.doc.root[-1]["data"]["fileid"] == "9"
    assert any("can’t be copied" in t for t in toasts)


def test_save_rejected_for_new_nc_block_is_explained_not_lost(win, srv):
    """Fremde Seite (Bearbeiten): der Server lehnt einen neuen fremden Nextcloud-Block mit 403 ab -> verstaendliche Meldung,
    KEIN 'Freigabe weg' (Seite bleibt bearbeitbar); ein echter Konflikt (409 mit rev) bleibt ein Konflikt."""
    from hlidskjalf.api import APIError
    from hlidskjalf.editor import PageDoc
    doc = PageDoc(77)
    doc.fetched = doc.loaded = True
    msgs = []
    doc.on_error = msgs.append
    doc.nc_known = set()
    e = APIError(403, "Nextcloud-Dateien anderer Personen lassen sich nicht einfügen")
    e.data = {"error": str(e)}
    assert doc._nc_rejected(e, {"bert|https://cloud.test|2"})
    assert msgs and "Not saved" in msgs[0] and doc.lost is None
    assert not doc._nc_rejected(e, set())                                # ohne neuen Block: wie bisher (z. B. Freigabe weg)
    c = APIError(409, "conflict")
    c.data = {"error": "conflict", "rev": 4}
    assert not doc._nc_rejected(c, {"x|y|z"})
    n = APIError(409, "Nextcloud nicht verbunden")
    n.data = {"error": "Nextcloud nicht verbunden"}
    assert doc._nc_rejected(n, {"x|y|z"})
