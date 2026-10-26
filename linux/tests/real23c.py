"""Pruefung gegen einen ECHTEN Saga-2.3-Server (kein pytest): persoenliches Backup ("Mein Backup") und Nextcloud-Dateien
in Seiten mit dem Fenster der Linux-App (Saga 2.3, Teil 3; docs/APP-API.md). Ziele sind nachgemachte Nextcloud/WebDAV-
Server (tests/fakedav.py im Server-Repo):
  - Mein Backup: Seite sichtbar, WebDAV-Ziel verbinden, Schluesselpaar erzeugen (privater Schluessel nur im Dialog, nie in den
    Dateien der App), bestaetigen, "Jetzt sichern", Backups im Ziel, Zeitplan/Aufbewahrung, Kategorien + Seitenbaum
    ausschliessen, Ziel trennen, Nextcloud per Login Flow v2 (Link im Browser, App fragt ab), fehlgeschlagener Lauf ->
    Benachrichtigung "userbackup.failed" lesbar, Klick oeffnet "Mein Backup"
  - Nextcloud: Konto per Login Flow v2 verknuepfen, Datei/Bild/Ordner aus der Auswahl einfuegen (author/sig setzt der
    Server), Karte mit Vorschau/Ordnerinhalt, Herunterladen ueber den Proxy, "Als Kopie", Block bleibt beim Speichern
    unveraendert, fremder Block: Einfuegen verhindert + abgelehntes Speichern verstaendlich (keine "Freigabe weg"), Trennen
    -> Karte ohne Inhalt
    HK_FED_STACKS=/pfad/stack.json python3 tests/real23c.py
stack.json: {"a": url, "nc": url, "dav": url, "cookies": {"p3-anna": "saga=..."}} (Wegwerf-Instanz). Legt nur Testseiten an und
loescht sie am Ende endgueltig."""
import json
import os
import sys
import tempfile
import time
import urllib.parse
import urllib.request

_HOME = tempfile.mkdtemp(prefix="hk-real23c-")
for k, sub in (("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache"), ("XDG_DATA_HOME", "data")):
    os.environ[k] = os.path.join(_HOME, sub)
os.environ["HK_NO_KEYRING"] = "1"
os.environ["HK_TEST"] = "real23c"
os.environ.setdefault("GSK_RENDERER", "cairo")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import gi  # noqa: E402

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from hk_helpers import pump  # noqa: E402

ST = json.load(open(os.environ["HK_FED_STACKS"], encoding="utf-8"))
A, NC, DAV = ST["a"], ST["nc"], ST["dav"]
U = "p3-anna"
RES = []
SHOTS = os.environ.get("HK_OUT")
MADE = []


def check(ok, what, detail=None):
    RES.append(bool(ok))
    print(("OK     " if ok else "FEHLER ") + what, flush=True)
    if not ok and detail is not None:
        print("       ", str(detail)[:800], flush=True)


def api(m, p, body=None, user=U):
    h = {"X-Saga": "1", "Cookie": ST["cookies"][user]}
    data = json.dumps(body).encode() if body is not None else None
    if body is not None:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(A + p, data=data, method=m, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            b = r.read()
            return r.status, (json.loads(b) if b[:1] in (b"{", b"[") else b)
    except urllib.error.HTTPError as e:
        b = e.read()
        try:
            return e.code, json.loads(b)
        except ValueError:
            return e.code, b


def fake(base, path, data=None):
    req = urllib.request.Request(base + path, data=data, method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=20) as r:
        b = r.read()
        return json.loads(b) if b[:1] == b"{" else b


def wait(fn, sec=20):
    end = time.monotonic() + sec
    while time.monotonic() < end:
        pump(None, 0.2)
        v = fn()
        if v:
            return v
    return fn()


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def texts(w):
    out = []
    for x in walk(w):
        if isinstance(x, Gtk.Label) and x.get_mapped():
            out.append(x.get_label())
        elif isinstance(x, Adw.PreferencesRow) and x.get_mapped():
            out.append(x.get_title())
            if isinstance(x, Adw.ActionRow):
                out.append(x.get_subtitle() or "")
    return [t for t in out if t]


def buttons(w, label):
    return [x for x in walk(w) if isinstance(x, Gtk.Button) and x.get_label() == label and x.get_mapped()]


def shot(win, name, widget=None):
    if SHOTS:
        from hlidskjalf.testing import shot as _shot
        os.makedirs(SHOTS, exist_ok=True)
        pump(None, 0.8)
        _shot(win, os.path.join(SHOTS, f"linux-23c-{name}.png"), widget)


def status():
    return api("GET", "/api/account/backup")[1]


def blocks_of(nid):
    return json.loads(api("GET", f"/api/notes/{nid}")[1].get("body") or "{}").get("blocks") or []


def nc_of(nid):
    from hlidskjalf.nextcloud import walk_nc
    return walk_nc(blocks_of(nid))


def main():
    from hlidskjalf import config
    from hlidskjalf.api import client
    from hlidskjalf.main import App
    from hlidskjalf.store import store
    from hlidskjalf.window import MainWindow
    config.settings.server = A
    client.set_cookies(A, {"saga": ST["cookies"][U].split("=", 1)[1]})
    app = App()
    app.register(None)
    win = MainWindow(app)
    win.set_default_size(1200, 860)
    win.present()
    opened, toasts = [], []
    win.open_uri = lambda u: opened.append(u)               # Browser: nur merken (Login-Links, Admin, Web)
    orig_toast = win.toast
    win.toast = lambda m: (toasts.append(str(m)), orig_toast(m))
    wait(lambda: store.server_version, 15)
    check(store.server_version == "2.3.0", f"Server 2.3 ({store.server_version})")

    # Testseiten: Projekt mit Unterseite (Ausschluss), Nextcloud-Seite
    P = api("POST", "/api/notes", {"title": "Backup-Projekt 23c"})[1]["id"]
    PS = api("POST", "/api/notes", {"title": "Geheim 23c", "parent_id": P})[1]["id"]
    N = api("POST", "/api/notes", {"title": "Nextcloud 23c"})[1]["id"]
    MADE.extend([PS, P, N])
    api("PATCH", f"/api/notes/{N}", {"body": json.dumps({"blocks": [{"id": "p1", "type": "paragraph", "data": {"text": "Start"}},
                                                                    {"id": "p2", "type": "paragraph", "data": {"text": ""}}]}),
                                     "format": "blocks"})
    store.refresh()
    wait(lambda: store.meta(N) is not None, 15)

    # ================================ Mein Backup ===================================================================
    from hlidskjalf.settings import SettingsDialog
    dlg = SettingsDialog(win)
    dlg.present(win)
    bp = dlg.pages["backup"]
    check(wait(lambda: bp.get_visible() and bp.S is not None, 15), "Mein Backup: Seite sichtbar (Server 2.3)")
    dlg.show_page("backup")
    pump(None, 0.6)
    tx = texts(bp)
    check("Last successful backup" in tx and "none yet" in tx and "Schedule off" in tx, "Status: noch kein Backup, Zeitplan aus", tx[:30])
    check(not bp.run_btn.get_sensitive(), "Jetzt sichern: gesperrt ohne Ziel und Schluessel")
    check(bp.ex_row is not None and "pages" in bp.S, "Ausschluesse sichtbar (Server liefert Seiten)")
    shot(win, "backup-leer")

    # WebDAV-Ziel ueber das Formular
    url, user, pw, folder, go = bp.target_form
    url.set_text(DAV + "/dav")
    user.set_text("plain-user")
    pw.set_text("plain-pass-123")
    folder.set_text("Saga-Backup")
    go.emit("clicked")
    s = wait(lambda: (lambda x: x if x.get("target") else None)(status()), 20)
    check(s and s["target"]["kind"] == "webdav", "Ziel: WebDAV verbunden", s)
    check(wait(lambda: getattr(bp, "test_row", None) is not None and bp.test_row.get_mapped(), 10), "Ziel: Anzeige mit Pruefen/Trennen")
    buttons(bp, "Check connection")[0].emit("clicked")
    check(wait(lambda: "Connection OK" in (bp.test_row.get_subtitle() or ""), 15), f"Ziel: Verbindung pruefen ({bp.test_row.get_subtitle()})")

    # Schluesselpaar erzeugen
    buttons(bp, "Generate")[0].emit("clicked")
    kd = wait(lambda: bp.key_dialog, 15)
    check(kd is not None and "AGE-SECRET-KEY-" in kd.private, "Schluessel: privater Schluessel im Dialog")
    shot(win, "backup-schluessel")
    priv = kd.private
    secret = next(x for x in priv.splitlines() if x.startswith("AGE-SECRET-KEY-"))
    kd.last.set_text("XXXXXX")
    kd._confirm()
    check(wait(lambda: kd.msg.get_label(), 10) and not kd.done, f"Schluessel: falsche 6 Zeichen abgewiesen ({kd.msg.get_label()})")
    kd.last.set_text(secret[-6:])
    kd._confirm()
    check(wait(lambda: kd.done, 10) and kd.private == "", "Schluessel: bestaetigt, Dialog vergisst den privaten Schluessel")
    s = wait(lambda: (lambda x: x if x.get("keys") else None)(status()), 10)
    check(s and len(s["keys"]) == 1 and s["ready"], "Schluessel: eingetragen, bereit zum Sichern")
    leak = []
    for root, _d, files in os.walk(_HOME):
        for f in files:
            try:
                if secret[-24:].encode() in open(os.path.join(root, f), "rb").read():
                    leak.append(f)
            except OSError:
                pass
    check(not leak, f"Schluessel: privater Schluessel in keiner Datei der App ({leak})")
    check(wait(lambda: bp.S.get("ready") and bp.run_btn.get_sensitive(), 10), "Jetzt sichern: freigegeben")

    # Kategorien + Ausschluss + Zeitplan
    bp.cat_rows["bookmarks"].set_active(False)
    names = [bp.ex_pick.get_model().get_string(i) for i in range(bp.ex_pick.get_model().get_n_items())]
    idx = next(i for i, n in enumerate(names) if "Backup-Projekt 23c" in n)
    bp.ex_pick.set_selected(idx)
    pump(None, 0.5)
    check(P in bp.excluded, "Ausschluss: Seite gewaehlt")
    bp.content_save.emit("clicked")
    s = wait(lambda: (lambda x: x if P in (x.get("exclude") or []) else None)(status()), 10)
    check(s and s["cats"]["bookmarks"] is False, "Inhalt: Kategorie aus und Seitenbaum ausgeschlossen (Server)", s and s.get("exclude"))
    sch, tm, wd, keep, sv, msg = bp.plan
    sch.set_selected(1)                                  # woechentlich
    tm.set_text("03:15")
    wd.set_selected(2)
    keep.set_value(5)
    sv.emit("clicked")
    s = wait(lambda: (lambda x: x if x.get("schedule") == "weekly" else None)(status()), 10)
    check(s and s["time"] == "03:15" and s["weekday"] == 2 and s["keep"] == 5 and s["next"], "Zeitplan: woechentlich Mi 03:15, 5 behalten",
          s and {k: s.get(k) for k in ("schedule", "time", "weekday", "keep", "next")})
    check(wait(lambda: "Saved." in (msg.get_subtitle() or ""), 5), "Zeitplan: gespeichert gemeldet")
    shot(win, "backup-zeitplan")

    # Jetzt sichern
    bp.run_btn.emit("clicked")
    s = wait(lambda: (lambda x: x if x.get("last_ok") else None)(status()), 60)
    check(bool(s), "Jetzt sichern: Backup erfolgreich", status().get("runs"))
    wait(lambda: bp.S.get("last_ok") and not bp.S.get("running"), 20)
    bp.load()
    pump(None, 1.5)
    tx = texts(bp)
    check("none yet" not in tx and "Recent runs" in tx, "Status: letzter Lauf angezeigt, Laeufe-Liste", tx[:20])
    rem = next(x for x in walk(bp) if isinstance(x, Adw.ExpanderRow) and x.get_title() == "Backups in the target")
    rem.set_expanded(True)
    check(wait(lambda: any(t.endswith(".zip.age") for t in texts(rem)), 15), "Backups im Ziel: Datei gelistet", texts(rem))
    shot(win, "backup-gesichert")

    # Ziel trennen, Nextcloud per Login Flow v2
    buttons(bp, "Disconnect")[0].emit("clicked")
    pump(None, 0.5)
    bp.ask.emit("response", "ok")
    bp.ask.close()
    check(wait(lambda: status().get("target") is None, 10), "Ziel getrennt")
    wait(lambda: getattr(bp, "target_form", None) and bp.target_form[0].get_mapped(), 10)
    url, user, pw, folder, go = bp.target_form
    url.set_text(NC)
    go.emit("clicked")
    check(wait(lambda: any("/login/v2" in u or "flow" in u or u.startswith(NC) for u in opened), 15), f"Nextcloud: Login-Link im Browser ({opened[-1:]})")
    check(wait(lambda: "Nextcloud found" in texts(bp), 10), "Nextcloud: 'In Nextcloud anmelden' angezeigt")
    shot(win, "backup-flow")
    fake(NC, "/_grant")
    s = wait(lambda: (lambda x: x if x.get("target") else None)(status()), 25)
    check(s and s["target"]["kind"] == "nextcloud" and s["target"]["flow"], "Nextcloud: verbunden (App-Passwort per Login Flow)", s)
    check(wait(lambda: any("Nextcloud ·" in t for t in texts(bp)), 15), "Nextcloud: Ziel in der App ohne Neuladen", texts(bp)[:20])

    # fehlgeschlagener Lauf -> Benachrichtigung lesbar
    fake(NC, "/_ctl?down=1")
    api("POST", "/api/account/backup/run")
    wait(lambda: (lambda x: x.get("runs") and x["runs"][0].get("finished"))(status()), 60)
    fake(NC, "/_ctl?down=0")
    from hlidskjalf.notifications import item_text, item_title, notifications, parse_url
    notifications.load()
    it = wait(lambda: next((x for x in notifications.items if x.get("kind") == "userbackup.failed"), None), 20)
    check(it is not None, "Benachrichtigung userbackup.failed angekommen")
    if it:
        t1, t2 = item_text(it), item_title(it)
        check(t1 and t1 != "userbackup.failed" and "backup" in t1.lower(), f"Benachrichtigung lesbar: {t1!r} / {t2!r}")
        check(parse_url(it.get("url")) == ("settings", "backup", None), f"Benachrichtigung: Ziel Mein Backup ({it.get('url')})")
    dlg.close()
    pump(None, 0.5)
    if it:
        win.open_notification(it)
        d2 = wait(lambda: next((x for x in walk(win) if isinstance(x, SettingsDialog)), None) or
                  (lambda dd: dd if isinstance(dd, SettingsDialog) else None)(win.get_visible_dialog()), 10)
        check(d2 is not None and wait(lambda: d2.sub == "backup" and d2.pages["backup"].get_mapped(), 15), "Klick oeffnet Einstellungen -> Mein Backup")
        shot(win, "backup-fehler")
        if d2 is not None:
            d2.close()
            pump(None, 0.5)
    from hlidskjalf.notifications import NotificationList
    nl = NotificationList(win)
    win2 = Gtk.Window(child=nl, default_width=380, default_height=500)
    win2.present()
    pump(None, 1.0)
    check(any("backup" in t.lower() for t in texts(nl)), "Glocke: Eintrag lesbar", texts(nl)[:6])
    if SHOTS:
        from hlidskjalf.testing import shot as _s
        _s(win2, os.path.join(SHOTS, "linux-23c-glocke.png"))
    win2.close()
    api("DELETE", "/api/account/backup/target")

    # ================================ Nextcloud =========================================================================
    dlg = SettingsDialog(win)
    dlg.present(win)
    dlg.show_page("settings")
    from hlidskjalf.nextcloud import NextcloudGroup
    ng = next(x for x in walk(dlg) if isinstance(x, NextcloudGroup))
    check(wait(lambda: ng.get_visible() and ng.rows, 10), "Nextcloud: Abschnitt sichtbar (Server 2.3)")
    er = next(x for x in ng.rows if isinstance(x, Adw.EntryRow))
    er.set_text(NC)
    n0 = len(opened)
    next(b for b in walk(ng) if isinstance(b, Gtk.Button) and b.get_label() == "Connect").emit("clicked")
    check(wait(lambda: len(opened) > n0, 15), "Nextcloud: Login-Link im Browser geoeffnet")
    check(wait(lambda: any("Nextcloud found" == t for t in texts(ng)), 10), "Nextcloud: wartet auf Anmeldung")
    fake(NC, "/_grant")
    check(wait(lambda: any(t.startswith("✅") for t in texts(ng)), 25), "Nextcloud: verbunden angezeigt", texts(ng))
    shot(win, "nc-verbunden")
    dlg.close()
    pump(None, 0.5)

    fid_img = fake(NC, "/_put?f=" + urllib.parse.quote("Bilder/foto.png"), _png())["fileid"]
    fake(NC, "/_put?f=" + urllib.parse.quote("Dokumente/bericht.pdf"), b"%PDF-1.4 bericht " + b"x" * 500)
    fake(NC, "/_put?f=" + urllib.parse.quote("Dokumente/notiz.txt"), b"Notiz-Inhalt")

    win.show_page(N)
    v = wait(lambda: win.current if getattr(win.current, "id", None) == N and win.current.doc.fetched else None, 20)
    check(v is not None, "Seite geoeffnet")
    from hlidskjalf import nextcloud as ncm
    from hlidskjalf.editor import SLASH_ITEMS
    check(any(x[1] == "nextcloud" for x in SLASH_ITEMS) and ncm.supported(), "/-Menue: 'Nextcloud file' (Server 2.3)")
    # Bild ueber die Auswahl (Suche) als Verweis
    pickers = []
    orig_init = ncm.NcPicker.__init__

    def track(self, *a, **k):
        orig_init(self, *a, **k)
        pickers.append(self)
    ncm.NcPicker.__init__ = track
    v.pick_nextcloud("p2")
    pk = wait(lambda: pickers and getattr(pickers[-1], "list", None) and pickers[-1].list.get_row_at_index(0) and pickers[-1], 15)
    check(pk is not None, "Auswahl: zuletzt geaenderte Dateien")
    pk.search.set_text("foto")
    pk._load()
    row = wait(lambda: (lambda r: r if r and r.item.get("name") == "foto.png" else None)(pk.list.get_row_at_index(0)), 15)
    check(row is not None, "Auswahl: Suche findet foto.png")
    pk._activate(row.item)
    pump(None, 0.5)
    shot(win, "nc-auswahl")
    buttons(pk.foot, "Insert as link")[0].emit("clicked")
    pump(None, 0.5)
    v.editor.flush()
    d = wait(lambda: (lambda x: x[0] if x and x[0].get("sig") else None)(nc_of(N)), 20)
    check(d and d["author"] == U and d["fileid"] == str(fid_img) and d["kind"] == "image", "Block gespeichert: author/sig vom Server", d)
    card = wait(lambda: next((x for x in walk(v) if isinstance(x, ncm.NcCard)), None), 10)
    from hlidskjalf import images
    check(card is not None and wait(lambda: any(isinstance(x, images.ImageView) and x.dec is not None for x in walk(card)), 25),
          "Karte: Bildvorschau ueber den Saga-Proxy")
    shot(win, "nc-bild")
    # Ordner ueber "Ordner" + "Diesen Ordner einfuegen"
    v.editor.insert({"id": "p3", "type": "paragraph", "data": {"text": ""}}, after=v.doc.root[-1]["id"])
    v.pick_nextcloud("p3")
    pk = wait(lambda: len(pickers) >= 2 and getattr(pickers[-1], "tabs", None) and pickers[-1], 15)
    pk.tabs.set_visible_child_name("dirs")
    row = wait(lambda: next((pk.list.get_row_at_index(i) for i in range(10) if pk.list.get_row_at_index(i) is not None
                             and pk.list.get_row_at_index(i).item.get("name") == "Dokumente"), None), 15)
    check(row is not None, "Auswahl: Ordner Dokumente in der Wurzel")
    pk._activate(row.item)
    wait(lambda: pk.list.get_row_at_index(0) is not None, 10)
    check(pk.here.get_visible() and pk.back.get_visible(), "Auswahl: im Ordner, 'Diesen Ordner einfuegen'")
    pk.here.emit("clicked")
    buttons(pk.foot, "Insert as link")[0].emit("clicked")
    pump(None, 0.5)
    v.editor.flush()
    ds = wait(lambda: (lambda x: x if len(x) == 2 and all(y.get("sig") for y in x) else None)(nc_of(N)), 20)
    check(ds and ds[1]["kind"] == "folder", "Ordner-Block gespeichert", ds)
    wait(lambda: len([x for x in walk(v) if isinstance(x, ncm.NcCard)]) == 2, 10)
    ncm.drop_status(N)
    v.editor.changed()
    pump(None, 3)
    cards = [x for x in walk(v) if isinstance(x, ncm.NcCard)]
    check(len(cards) == 2 and wait(lambda: "notiz.txt" in texts(cards[-1]) and "bericht.pdf" in texts(cards[-1]), 15),
          "Ordner-Karte: erste Eintraege", texts(cards[-1]) if cards else None)
    shot(win, "nc-ordner")
    # Herunterladen ueber den Proxy
    dl = []
    win.download_open = lambda u, n: dl.append((u, n))
    next(b for b in walk(cards[0]) if isinstance(b, Gtk.Button) and b.get_tooltip_text() == "Download").emit("clicked")
    check(dl and "dl=1" in dl[0][0] and dl[0][1] == "foto.png", f"Herunterladen: Proxy-Adresse ({dl})")
    got = bytearray()
    client.download(dl[0][0].lstrip("/"), got.extend)
    check(bytes(got) == _png(), "Herunterladen: Datei ueber Saga")
    check(any(u.endswith(f"/f/{fid_img}") for u in [ncm.open_url(cards[0].d) or ""]), "In Nextcloud oeffnen: /f/<fileid>")
    # Block bleibt beim Speichern unveraendert (Text dazu, Block identisch)
    before = json.dumps(nc_of(N), sort_keys=True)
    v.doc.root[0]["data"]["text"] = "Start geaendert"
    v.editor.changed()
    v.editor.flush()
    wait(lambda: blocks_of(N)[0]["data"]["text"] == "Start geaendert", 15)
    check(json.dumps(nc_of(N), sort_keys=True) == before, "Speichern: Nextcloud-Bloecke unveraendert")
    # Als Kopie
    v.editor.insert({"id": "p4", "type": "paragraph", "data": {"text": ""}}, after=v.doc.root[-1]["id"])
    v.pick_nextcloud("p4")
    pk = wait(lambda: len(pickers) >= 3 and getattr(pickers[-1], "list", None) and pickers[-1].list.get_row_at_index(0) and pickers[-1], 15)
    pk.search.set_text("bericht")
    pk._load()
    row = wait(lambda: (lambda r: r if r and r.item.get("name") == "bericht.pdf" else None)(pk.list.get_row_at_index(0)), 15)
    pk._activate(row.item)
    buttons(pk.foot, "Insert as copy")[0].emit("clicked")
    b4 = wait(lambda: (lambda r: r[0][r[1]] if r and r[0][r[1]]["type"] == "attaches" else None)(v.editor.locate("p4")), 20)
    check(b4 and (b4["data"].get("file") or {}).get("url", "").startswith("/files/"), "Als Kopie: Dateiblock mit Saga-Datei", b4)
    v.editor.flush()
    # fremder Block: Einfuegen aus der Zwischenablage verhindert
    from hlidskjalf import blockclip
    foreign = {"inst": NC, "fileid": "999", "kind": "file", "name": "fremd.txt", "author": "p3-bert", "public": False, "fed": False,
               "sig": "x"}
    items = blockclip.parse(json.dumps([{"id": "z", "tool": "nextcloud", "data": foreign}]).encode())
    n_before = len(v.doc.root)
    toasts.clear()
    v._paste_blocks(items, v.doc.root[-1]["id"])
    pump(None, 0.5)
    check(len(v.doc.root) == n_before and any("can’t be copied" in t for t in toasts), f"Fremder Block: Einfuegen verhindert ({toasts})")
    # ... und falls er doch im Inhalt landet: Server lehnt ab (403) -> verstaendliche Meldung, Seite bleibt bearbeitbar
    v.doc.root.append({"id": "zz", "type": "nextcloud", "data": dict(foreign)})
    errs = []
    oe = v.doc.on_error
    v.doc.on_error = lambda t: (errs.append(t), oe and oe(t))
    v.doc.dirty = True
    v.editor.changed()
    v.doc.save()
    check(wait(lambda: errs, 15) and v.doc.lost is None and not v.doc.read_only and v.doc.nc_error,
          f"Fremder Block: Ablehnung verstaendlich, keine 'Freigabe weg' ({errs[-1:]})")
    v.doc.root.pop()
    v.editor.changed()
    v.editor.flush()
    check(wait(lambda: not v.doc.dirty and v.doc.nc_error is None, 15), "Nach Entfernen: wieder gespeichert")
    # Trennen -> Karte ohne Inhalt
    api("DELETE", "/api/settings/nextcloud")
    ncm.forget_info()
    ncm.drop_status()
    win.show_page(P)
    wait(lambda: getattr(win.current, "id", None) == P, 10)
    win.show_page(N)
    v = wait(lambda: win.current if getattr(win.current, "id", None) == N and win.current.doc.fetched else None, 20)
    pump(None, 2)
    cards = [x for x in walk(v) if isinstance(x, ncm.NcCard)]
    check(cards and wait(lambda: any("Not available" in t for t in texts(cards[0])), 15), "Getrennt: Karte ohne Inhalt", texts(cards[0]) if cards else None)
    shot(win, "nc-getrennt")
    return True


def _png(w=48, h=32):
    import io
    from PIL import Image
    b = io.BytesIO()
    Image.new("RGB", (w, h), (40, 120, 200)).save(b, "PNG")
    return b.getvalue()


def cleanup():
    for nid in MADE:
        api("DELETE", f"/api/notes/{nid}")
        api("DELETE", f"/api/trash/{nid}")
    api("DELETE", "/api/account/backup/target")
    api("DELETE", "/api/settings/nextcloud")


if __name__ == "__main__":
    try:
        main()
    except Exception:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        RES.append(False)
    finally:
        try:
            cleanup()
        except Exception as e:  # noqa: BLE001
            print("Aufraeumen:", e)
    n = len(RES)
    print(f"\n{sum(RES)}/{n} OK", flush=True)
    sys.exit(0 if RES and all(RES) else 1)
