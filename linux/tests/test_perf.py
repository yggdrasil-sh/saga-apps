"""Schnelle Gewinne (2.2): Seitenliste lokal pflegen statt neu laden, linearer Baum, Takt, gzip, Schaltflaechen"""
import gzip
import random
import time

from hk_helpers import idle, pump

FIELDS = ("id", "title", "icon", "parent_id", "kind", "pinned", "archived", "template", "tags")


def _get_notes(srv):
    return [x for x in srv.log if x[0] == "GET" and x[1].endswith("/api/notes")]


def _snap(store):
    """sichtbarer Zustand der Seitenliste: Baum, Archiv, by_id"""
    def tree(parent):
        return [(p["id"], tuple(p.get(k) for k in FIELDS if k != "id"), tree(p["id"])) for p in store.children(parent)]
    return tree(None), [p["id"] for p in store.archived], sorted(store.by_id), [p["id"] for p in store.pages]


def _after_reload(store):
    store.refresh()
    idle()
    return _snap(store)


def test_baum_linear_wie_bisher():
    """Kinder-Index liefert dasselbe wie die bisherige Suche ueber alle Seiten (auch Datenbank-Zeilen, Archiv)"""
    from hlidskjalf.store import Store, is_db
    rnd = random.Random(7)
    allp = []
    for i in range(1, 600):
        par = rnd.choice([None] + [p["id"] for p in allp[-50:]]) if allp else None
        allp.append({"id": i, "title": f"S{i}", "parent_id": par, "kind": "database" if i % 37 == 0 else "page",
                     "archived": i % 41 == 0})
    allp_, by_id, pages, archived, kids = Store.split_pages(allp)

    def old_children(parent):
        out = []
        for p in pages:
            if p.get("parent_id") == parent:
                par = by_id.get(parent) if parent is not None else None
                if par is not None and is_db(par):
                    continue
                out.append(p)
        return out
    s = Store.__new__(Store)
    s.by_id, s.pages, s._kids = by_id, pages, kids
    for pid in [None] + list(by_id):
        assert Store.children(s, pid) == old_children(pid)
        p = by_id.get(pid)
        assert Store.has_children(s, pid) == (False if is_db(p) else any(x.get("parent_id") == pid for x in pages))
    arch = {p["id"] for p in allp if p.get("archived")}
    assert archived == [p for p in allp if p.get("archived") and p.get("parent_id") not in arch]


def test_neue_seite_lokal_statt_neu_laden(srv):
    from hlidskjalf.store import store
    store.refresh()
    idle()
    n0 = len(_get_notes(srv))
    got = []
    store.new_page(1, done=got.append, title="Neu")
    pump(lambda: got, 5)
    idle()
    assert got and got[0] in store.by_id
    assert len(_get_notes(srv)) == n0                              # keine ganze Liste mehr
    assert any(x[0] == "GET" and x[1].endswith(f"/api/notes/{got[0]}") for x in srv.log)
    assert [p["id"] for p in store.children(1)][-1] == got[0]
    local = _snap(store)
    assert local == _after_reload(store)                           # dasselbe wie nach einem Neuladen


def test_neue_seite_unter_fremder_seite_laedt_wie_bisher(srv):
    """Unterseite einer geteilten (fremden) Seite gehoert deren Eigentuemer -> voller Abgleich wie bisher"""
    from hlidskjalf.store import store
    store.refresh()
    idle()
    n0 = len(_get_notes(srv))
    got = []
    store.page_added(999, 424242, done=lambda: got.append(1))      # Elternseite nicht eigen
    pump(lambda: got, 5)
    idle()
    assert got and len(_get_notes(srv)) == n0 + 1


def test_papierkorb_und_archiv_lokal(srv):
    from hlidskjalf.store import store
    store.refresh()
    idle()
    n0 = len(_get_notes(srv))
    store.trash(1)                                                 # samt Unterseite 2
    idle()
    pump(lambda: 1 not in store.by_id, 3)
    assert 1 not in store.by_id and 2 not in store.by_id
    assert len(_get_notes(srv)) == n0
    local = _snap(store)
    assert local == _after_reload(store)
    n1 = len(_get_notes(srv))
    store.set_archived(3, True)
    idle()
    pump(lambda: any(p["id"] == 3 for p in store.archived), 3)
    assert 3 not in [p["id"] for p in store.pages] and 3 in [p["id"] for p in store.archived]
    assert len(_get_notes(srv)) == n1
    local = _snap(store)
    assert local == _after_reload(store)


def test_verschieben_zeigt_sofort(srv):
    from hlidskjalf.store import store
    store.refresh()
    idle()
    store.move(3, 1)
    pump(lambda: store.by_id.get(3, {}).get("parent_id") == 1, 3)
    assert 3 in [p["id"] for p in store.children(1)]
    idle()
    assert 3 in [p["id"] for p in store.children(1)]               # nach der Auffrischung unveraendert


def test_veraltete_auffrischung_ueberschreibt_lokales_nicht(srv):
    """Neue Seite waehrend einer laufenden Auffrischung: deren (aeltere) Liste darf die Seite nicht verschlucken"""
    from hlidskjalf.store import store
    store.refresh()
    idle()
    srv.delay["GET api/notes"] = 0.6
    store.refresh()                                                # laeuft ...
    nid = srv.next_id
    srv.notes[nid] = {"id": nid, "title": "Zwischendurch", "parent_id": None, "format": "blocks", "body": "", "rev": 1,
                      "updated": time.time(), "created": time.time()}
    srv.next_id += 1
    store.insert_page(store.meta_from_detail(srv.notes[nid]))      # ... waehrenddessen lokal eingefuegt
    srv.delay.pop("GET api/notes")
    idle()
    pump(lambda: not store._refreshing, 5)
    idle()
    assert nid in store.by_id


def test_takt(srv):
    from hlidskjalf.store import store
    store._refreshed_at = time.monotonic()
    assert not store.refresh_due(False) and not store.refresh_due(True)
    store._refreshed_at = time.monotonic() - 60
    assert store.refresh_due(False)                                # ohne Live-Kanal wie bisher jede Minute
    assert not store.refresh_due(True)                             # mit Live-Kanal seltener
    store._refreshed_at = time.monotonic() - 300
    assert store.refresh_due(True)


def test_gzip_wird_entpackt():
    from hlidskjalf.api import _decoded
    raw = b'[{"id": 1}]' * 200
    assert _decoded(gzip.compress(raw), {"Content-Encoding": "gzip"}) == raw
    assert _decoded(raw, {}) == raw
    assert _decoded(b"", {"Content-Encoding": "gzip"}) == b""


def test_schaltflaeche_neuer_eintrag_einmal(win, srv):
    """Doppelklick auf "Neuer Eintrag": nur EIN Eintrag, Knopf sofort gesperrt mit Fortschritt"""
    from gi.repository import Gtk
    from hlidskjalf.blocks import BlockKit
    from hlidskjalf.store import store
    store.refresh()
    idle()
    srv.delay["POST api/notes"] = 0.5
    states = []

    class V:
        def run_button(self, cfg, busy=None):
            def b(on):
                states.append(on)
                busy(on)
            win.run_button(cfg, busy=b)

    kit = BlockKit.__new__(BlockKit)
    kit.view = V()
    bt = Gtk.Button(label="➕ Neu")
    cfg = {"label": "Neu", "action": "db", "target": 10, "title": "Eintrag"}
    posts0 = sum(1 for x in srv.log if x[0] == "POST" and x[1].endswith("/api/notes"))
    kit._press(bt, cfg)
    assert states == [True] and not bt.get_sensitive()             # sofort gesperrt
    kit._press(bt, cfg)
    kit._press(bt, cfg)
    pump(lambda: states[-1] is False, 5)
    srv.delay.pop("POST api/notes", None)
    idle()
    assert sum(1 for x in srv.log if x[0] == "POST" and x[1].endswith("/api/notes")) == posts0 + 1
    assert bt.get_sensitive() and bt.get_label() == "➕ Neu"
