"""Pruefung gegen ECHTE, verbundene Saga-2.3-Server (kein pytest): Seiten anderer Server vollstaendig (docs/APP-API-FED.md
Abschnitt 8, Foederation E4/E6) mit dem Fenster der Linux-App - Anna (Server A) teilt Seite, Unterseite und Datenbanken mit
Bert (Server B, Bearbeiten); die App laeuft als Bert und spricht nur mit B (negative IDs):
  - remote.caps in "Mit mir geteilt" und in der Seite; Seitenmenue/Seitenleiste zeigen nur, was der Server der Seite kann
  - Symbol, Schlagwoerter, volle Breite; Bild hochladen (X-Saga-Note, Datei liegt bei A) und in die Seite einfuegen
  - Anwesenheit (Abo beim Eigentuemer) und Live: Annas Aenderung kommt als note.changed an, Seite laedt still neu
  - Versionen ansehen und wiederherstellen, Export (Markdown, ZIP)
  - Unterseite anlegen, verschieben (nur im geteilten Bereich)
  - Datenbank oeffnen, Zeilen (Rolle edit), Wert aendern, Zeile anlegen, Spalte hinzufuegen, Eintrag mit Eigenschaften
    HK_FED_STACKS=/pfad/stacks3.json python3 tests/realfed23b.py
stacks3.json: {"a": url, "b": url, "host": {"a": host, ...}, "cookies": {"a": {"fa-anna": "saga=..."}, "b": {"fb-bert": ...}}}
(Wegwerf-Instanzen wie tests/foederation_gruppen.py im Server-Repo). Legt nur Testseiten an; die Instanzen werden danach
verworfen."""
import io
import json
import os
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile

_HOME = tempfile.mkdtemp(prefix="hk-realfed23b-")
for k, sub in (("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache"), ("XDG_DATA_HOME", "data")):
    os.environ[k] = os.path.join(_HOME, sub)
os.environ["HK_NO_KEYRING"] = "1"
os.environ["HK_TEST"] = "realfed23b"
os.environ.setdefault("GSK_RENDERER", "cairo")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import gi  # noqa: E402

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Gtk  # noqa: E402

from hk_helpers import pump  # noqa: E402

ST = json.load(open(os.environ["HK_FED_STACKS"], encoding="utf-8"))
HA, HB = ST["host"]["a"], ST["host"]["b"]
RES = []


def check(ok, what, detail=None):
    RES.append(bool(ok))
    print(("OK     " if ok else "FEHLER ") + what, flush=True)
    if not ok and detail is not None:
        print("       ", str(detail)[:600], flush=True)


def api(k, user, m, p, body=None, raw=None, hdr=None):
    h = {"X-Saga": "1", "Cookie": ST["cookies"][k][user]}
    h.update(hdr or {})
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    if body is not None:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(ST[k] + p, data=data, method=m, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            b = r.read()
            return r.status, (json.loads(b) if b[:1] in (b"{", b"[") else b)
    except urllib.error.HTTPError as e:
        b = e.read()
        try:
            return e.code, json.loads(b)
        except ValueError:
            return e.code, b


def wait(fn, sec=20):
    end = time.monotonic() + sec
    while time.monotonic() < end:
        pump(None, 0.2)
        v = fn()
        if v:
            return v
    return fn()


def png(w=40, h=30):
    from PIL import Image
    b = io.BytesIO()
    Image.new("RGB", (w, h), (200, 80, 40)).save(b, "PNG")
    return b.getvalue()


def ref(pa, rid):
    return -(rid * 1024 + pa)


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def labels(w):
    return [x.get_label() for x in walk(w) if isinstance(x, Gtk.Label) and x.get_mapped()]


def menu_labels(v):
    from hlidskjalf import page as page_mod
    seen = []
    orig = page_mod.menu_popover
    page_mod.menu_popover = lambda parent, ents: (seen.append(ents), orig(parent, ents))[1]
    try:
        v.page_menu(v.win.menu_btn)
    finally:
        page_mod.menu_popover = orig
    out = [e[0] for e in seen[0] if e]
    for pop in [x for x in walk(v.win) if isinstance(x, Gtk.Popover)]:
        pop.popdown()
    return out


SHOTS = os.environ.get("HK_OUT")


def shot(win, name):
    """Bildschirmfoto (nur mit HK_OUT)"""
    if SHOTS:
        from hlidskjalf.testing import shot as _shot
        os.makedirs(SHOTS, exist_ok=True)
        pump(None, 0.8)
        _shot(win, os.path.join(SHOTS, f"linux-fed23b-{name}.png"))


def note_a(nid):
    return api("a", "fa-anna", "GET", f"/api/notes/{nid}")[1]


def main():
    # --- Anna (A): Seiten und Datenbanken anlegen, mit Bert (B) teilen ----------------------------------------------------
    blocks = json.dumps({"blocks": [{"id": "b1", "type": "paragraph", "data": {"text": "Start"}}]})
    P = api("a", "fa-anna", "POST", "/api/notes", {"title": "Projekt Fern"})[1]["id"]
    api("a", "fa-anna", "PATCH", f"/api/notes/{P}", {"body": blocks, "format": "blocks"})
    CH = api("a", "fa-anna", "POST", "/api/notes", {"title": "Kapitel", "parent_id": P})[1]["id"]
    D2 = api("a", "fa-anna", "POST", "/api/notes", {"title": "Kunden", "kind": "database", "parent_id": P})[1]["id"]
    k1 = api("a", "fa-anna", "POST", "/api/notes", {"title": "Kunde Eins", "parent_id": D2})[1]["id"]
    D = api("a", "fa-anna", "POST", "/api/notes", {"title": "Aufgaben", "kind": "database", "parent_id": P})[1]["id"]
    sch = {"props": [{"id": "status", "name": "Status", "type": "select", "options": [{"id": "offen", "name": "Offen", "color": "gray"},
                                                                                      {"id": "fertig", "name": "Fertig", "color": "green"}]},
                     {"id": "zahl", "name": "Zahl", "type": "number"}, {"id": "kunde", "name": "Kunde", "type": "relation", "target": D2}],
           "views": [{"id": "tabelle", "name": "Tabelle", "type": "table"}]}
    api("a", "fa-anna", "PATCH", f"/api/notes/{D}", {"schema": sch})
    rows = [api("a", "fa-anna", "POST", "/api/notes", {"title": f"Aufgabe {i}", "parent_id": D, "props": {"status": "offen", "zahl": i}})[1]["id"]
            for i in range(3)]
    s, _ = api("a", "fa-anna", "PUT", f"/api/notes/{P}/shares", {"type": "remote", "target": f"@bert@{HB}", "role": "edit"})
    check(s == 200, f"A: Projekt mit bert@B geteilt (Bearbeiten) ({s})")

    def fed_items():
        s, d = api("b", "fb-bert", "GET", "/api/fed/shared")
        return d.get("items") if s == 200 and isinstance(d, dict) else None
    want = {"db", "subpages", "upload", "versions", "export", "presence"}
    items = wait(lambda: (lambda it: it if it and any(want <= set((x.get("remote") or {}).get("caps") or []) for x in it)
                          and len(it) >= 6 else None)(fed_items()), 40)
    check(bool(items), f"B: 'Mit mir geteilt' mit remote.caps ({len(items or [])} Eintraege)")
    pa = ST["peer"]["ba"]                                 # Kennung von A auf B: negative ID = -(id_dort * 1024 + pa)
    check(any(x["id"] == ref(pa, P) for x in items or []), "B: Projekt mit negativer ID")
    rP, rC, rD, rD2, rk1 = (ref(pa, x) for x in (P, CH, D, D2, k1))
    rrows = [ref(pa, x) for x in rows]

    # --- Bert (B): App-Fenster ----------------------------------------------------------------------------------------
    from hlidskjalf import config, sharing as S
    from hlidskjalf.api import client
    from hlidskjalf.main import App
    from hlidskjalf.store import store
    from hlidskjalf.window import MainWindow
    config.settings.server = ST["b"]
    client.set_cookies(ST["b"], {"saga": ST["cookies"]["b"]["fb-bert"].split("=", 1)[1]})
    app = App()
    app.register(None)
    win = MainWindow(app)
    win.present()
    wait(lambda: store.server_version, 15)
    store.load_fed_shared()
    check(wait(lambda: store.meta(rP) is not None and store.meta(rD) is not None, 30), "App: fremde Seiten in der Seitenleiste (Mit mir geteilt)")
    mp = store.meta(rP) or {}
    caps = S.remote_caps(mp) or set()
    check(want <= caps, f"App: remote.caps der Seite ({sorted(caps)})")
    check(all(S.allowed(mp, a) for a in ("subpage", "upload", "versions", "export", "wide", "icon", "move", "presence"))
          and not any(S.allowed(mp, a) for a in ("trash", "share", "publish", "duplicate", "archive", "lock", "ai")),
          "App: Aktionen je Faehigkeit (Unterseite, Hochladen, Versionen, Export, Breite, Symbol, Verschieben, Anwesenheit; "
          "nie Loeschen/Teilen/Veroeffentlichen)")

    # --- Seite oeffnen ------------------------------------------------------------------------------------------------
    win.show_page(rP)
    v = wait(lambda: win.current if getattr(win.current, "id", None) == rP and win.current.doc.fetched else None, 20)
    check(v is not None and v.remote and v.rcan("db") and v.rcan("upload"), "App: fremde Seite geoeffnet, Server kann db/upload")
    if v is None:
        return False
    pump(None, 0.5)
    ml = menu_labels(v)
    check({"Version history …", "Export", "Full width", "Move to …"} <= set(ml) and "Move to trash" not in ml and "Share …" not in ml,
          f"App: Seitenmenue einer fremden Seite (2.3) ({ml})")
    check(any(x and x.startswith("This page is on " + HA) and "owner" in x for x in labels(v)), "App: Hinweis 'Diese Seite liegt auf …'")
    # Symbol, Schlagwoerter, Breite
    v.save_fields({"icon": "🚀", "tags": ["fern"], "wide": True})
    check(wait(lambda: (lambda n: n.get("icon") == "🚀" and "fern" in (n.get("tags") or []) and n.get("wide"))(note_a(P)), 15),
          "A: Symbol, Schlagwort, volle Breite von Bert gespeichert")
    # Bild hochladen (X-Saga-Note) und einfuegen
    box = {}
    from hlidskjalf.util import bg
    bg(win.upload_image, png(), "t.png", v.upload_note, done=lambda u: box.setdefault("u", u), error=lambda e: box.setdefault("e", e))
    wait(lambda: box, 20)
    url = box.get("u") or ""
    check(isinstance(url, str) and url.startswith(f"/api/fed/files/{rP}/"), f"App: Bild in fremde Seite hochgeladen ({url or box.get('e')})")
    if url:
        v.editor.insert_image_url(url, after=v.doc.root[0]["id"])
        v.editor.flush()
        check(wait(lambda: "image" in json.dumps(note_a(P).get("body") or ""), 15), "A: Bildblock gespeichert (Datei liegt bei A)")
        from hlidskjalf import images
        data = None
        try:
            data = images.fetch_bytes(url)
        except Exception as e:  # noqa: BLE001
            data = e
        check(isinstance(data, bytes) and data[:4] == b"\x89PNG", "App: Bild ueber den eigenen Server wieder geladen")
    # Anwesenheit + Live
    check(wait(lambda: v.presence is not None and any(u.get("me") for u in v.presence.users), 15),
          "App: Anwesenheit auf der fremden Seite (Abo beim Eigentuemer)")
    s, _ = api("a", "fa-anna", "POST", "/api/presence", {"note_id": P, "editing": True})
    check(wait(lambda: any(not u.get("me") for u in (v.presence.users if v.presence else [])), 30),
          f"App: Anna (A) erscheint in der Anwesenheit ({[u.get('name') for u in (v.presence.users if v.presence else [])]})")
    rev0 = v.doc.rev
    n = note_a(P)
    b2 = json.loads(n["body"])
    b2["blocks"].append({"id": "b9", "type": "paragraph", "data": {"text": "Von Anna live"}})
    s, _ = api("a", "fa-anna", "PATCH", f"/api/notes/{P}", {"body": json.dumps(b2), "format": "blocks", "rev": n["rev"]})
    check(s == 200 and wait(lambda: v.doc.rev > rev0 and "Von Anna live" in json.dumps(v.doc.root), 30),
          f"App: Live - Annas Aenderung kommt an, Seite laedt still neu ({s})")
    shot(win, "01-fremde-seite")
    # Versionen
    from hlidskjalf.dialogs import VersionsDialog
    restored = {}
    vd = VersionsDialog(win, rP, lambda: restored.setdefault("ok", 1))
    vd.present(win)
    check(wait(lambda: len(vd.versions) >= 1, 20), f"App: Versionen der fremden Seite ({len(vd.versions)})")
    if vd.versions:
        row = vd.list.get_row_at_index(len(vd.versions) - 1)
        vd.list.select_row(row)
        wait(lambda: vd.restore_btn.get_sensitive(), 10)
        vd._restore()
        check(wait(lambda: restored, 15), "App: Version wiederhergestellt (Rolle Bearbeiten)")
    vd.force_close()
    # Export
    md = client.request("GET", f"api/notes/{rP}/export", timeout=60)
    zp = client.request("GET", f"api/notes/{rP}/export?sub=1", timeout=60)
    zok = False
    try:
        zok = len(zipfile.ZipFile(io.BytesIO(zp)).namelist()) >= 2
    except zipfile.BadZipFile:
        pass
    check(b"Projekt Fern" in md and zok, "App: Export Markdown und ZIP (mit Unterseiten)")

    # --- Unterseite anlegen, verschieben ------------------------------------------------------------------------------
    got = {}
    store.new_page(rP, done=lambda nid: got.setdefault("id", nid))
    nid = wait(lambda: got.get("id"), 20)
    check(isinstance(nid, int) and nid < 0, f"App: Unterseite auf dem anderen Server angelegt ({nid})")
    if nid:
        win.show_page(nid)
        nv = wait(lambda: win.current if getattr(win.current, "id", None) == nid and win.current.doc.fetched else None, 20)
        if nv is not None:
            nv.doc.set_title("Neu von Bert")
            nv.editor.flush()
        loc = -nid
        loc = (loc - pa) // 1024
        check(wait(lambda: note_a(loc).get("title") == "Neu von Bert" and note_a(loc).get("parent_id") == P, 15),
              "A: Unterseite gehoert Anna, Titel von Bert")
        check(wait(lambda: store.meta(nid) is not None, 30), "App: neue Unterseite erscheint unter 'Mit mir geteilt'")
        tg = [p["id"] for p in S.shared_move_targets(store.meta(nid) or {"id": nid}, store.shared)]
        check(rC in tg and rD not in tg and nid not in tg and rk1 not in tg and not set(rrows) & set(tg),
              f"App: Verschieben-Ziele nur im geteilten Bereich, keine Datenbanken/Eintraege ({tg})")
        mv = {}
        store.move(nid, rC, done=lambda: mv.setdefault("ok", 1), error=lambda e: mv.setdefault("e", e))
        wait(lambda: mv, 15)
        check(mv.get("ok") and wait(lambda: note_a(loc).get("parent_id") == CH, 10), f"A: Unterseite unter 'Kapitel' verschoben ({mv})")

    # --- Datenbank ----------------------------------------------------------------------------------------------------
    win.show_page(rD)
    dv = wait(lambda: win.current if getattr(win.current, "id", None) == rD and win.current.doc.fetched else None, 20)
    check(dv is not None and dv.doc.is_database, "App: fremde Datenbank geoeffnet")
    wait(lambda: False, 2)
    shot(win, "02-fremde-datenbank")
    from hlidskjalf.database import DBModel
    m = DBModel(rD)
    m.load()
    wait(lambda: m.loaded and not m.error and m.rows, 20)
    check(len(m.rows) == 3 and m.role == "edit" and not m.readonly and any(p.get("target") == rD2 for p in m.props),
          f"App: Zeilen der fremden Datenbank, Rolle edit, Relation auf negative ID ({len(m.rows)}, {m.role}, {m.error})")
    if m.rows:
        r0 = m.rows[0]["id"]
        m.set(r0, "zahl", 42)
        m.set(r0, "kunde", [rk1])
        loc0 = ((-r0) - pa) // 1024
        check(wait(lambda: (note_a(loc0).get("props") or {}).get("zahl") == 42 and (note_a(loc0).get("props") or {}).get("kunde") == [k1], 15),
              "A: Zellenwert und Relation von Bert gespeichert")
        made = {}
        m.add({"status": "fertig"}, done=lambda i: made.setdefault("id", i))
        wait(lambda: made, 15)
        check(made.get("id", 0) < 0 and wait(lambda: len(m.rows) == 4, 15), f"App: Zeile in fremder Datenbank angelegt ({made})")
        m.edit_schema(lambda sc: sc["props"].append({"id": "notiz", "name": "Notiz", "type": "text"}))
        check(wait(lambda: any(p["id"] == "notiz" for p in (note_a(D).get("schema") or {}).get("props") or []), 15),
              "A: Spalte von Bert hinzugefuegt")
        # Eintrag oeffnen: Eigenschaften der Datenbank (EditableProps)
        win.show_page(r0)
        ev = wait(lambda: win.current if getattr(win.current, "id", None) == r0 and win.current.doc.fetched and
                  win.current.doc.parent_schema is not None else None, 20)
        check(ev is not None and any(p.get("id") == "notiz" for p in ev.doc.parent_schema.get("props") or []),
              "App: Eintrag der fremden Datenbank mit Eigenschaften")
    # Aufraeumen nicht noetig: Wegwerf-Instanzen
    win._close_ok = True
    win.destroy()
    pump(None, 0.3)
    print(f"\nREALFED23B {sum(RES)}/{len(RES)} OK – " + ("ALLES GRUEN" if all(RES) else "FEHLER"), flush=True)
    return all(RES)


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
