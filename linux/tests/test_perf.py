"""Schnelle Gewinne (2.2): Seitenliste lokal pflegen statt neu laden, linearer Baum, Takt, gzip, Schaltflaechen"""
import gzip
import random
import time

from hk_helpers import idle, pump

FIELDS = ("id", "title", "icon", "parent_id", "kind", "pinned", "archived", "template", "tags")


def _get_notes(srv):
    return [x for x in srv.log if x[0] == "GET" and x[1].split("?")[0].endswith("/api/notes")]   # auch ?delta=1/?since=


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
    # Live-Kanal verbunden, aber noch kein notes.tree (testing-Server meldet 2.2.0, schickt es aber evtl. nicht):
    # weiter jede Minute - die Serverversion entscheidet nicht
    store.server_version = "2.2.0-rc1"
    assert not store.tree_seen and store.refresh_due(True)
    store.server_version = None
    # erst nach dem ersten notes.tree seltener
    store.tree_seen = True
    assert not store.refresh_due(True)
    assert store.refresh_due(False)                                # Live-Kanal weg: wieder jede Minute
    store._refreshed_at = time.monotonic() - 300
    assert store.refresh_due(True)


def test_takt_erst_nach_notes_tree_und_vergessen(srv):
    """Gate: tree_seen erst mit einem empfangenen notes.tree; Abmelden, Kontowechsel, Serverwechsel vergessen es"""
    from hlidskjalf.store import store
    store.refresh()
    idle()
    assert not store.tree_seen
    _tree(store, {"ids": [3], "op": "updated", "ops": ["updated"], "full": False})
    assert store.tree_seen
    store.new_session()                                            # Abmelden / anderer Server
    assert not store.tree_seen
    store.tree_seen = True
    store.forget_account_data()                                    # anderes Konto am selben Server
    assert not store.tree_seen
    store.tree_seen = True
    store.switch_server("https://anders.test")
    assert not store.tree_seen


def test_geteilt_jede_minute_unabhaengig_vom_baum(srv):
    """Mit Live + notes.tree: Seitenbaum nur alle 5 min, "Mit mir geteilt" und Heute aber weiter jede Minute"""
    from hlidskjalf.store import store
    store.refresh()
    idle()
    store.tree_seen = True
    store._refreshed_at = time.monotonic() - 60
    assert not store.refresh_due(True)
    n0 = len(_get_notes(srv))
    sh0 = sum(1 for x in srv.log if x[0] == "GET" and x[1].endswith("/api/shared"))
    t0 = sum(1 for x in srv.log if x[0] == "GET" and x[1].endswith("/api/tasks"))
    # Gruppenwechsel: Seite ist jetzt mit mir geteilt, ohne dass ein Ereignis kam
    srv.add_foreign(300, "Gruppenseite", role="view")
    store.refresh_light()
    pump(lambda: store.visible(300), 5)
    idle()
    assert store.visible(300) and 300 in [p["id"] for p in store.shared_roots()]
    assert sum(1 for x in srv.log if x[0] == "GET" and x[1].endswith("/api/shared")) == sh0 + 1
    assert sum(1 for x in srv.log if x[0] == "GET" and x[1].endswith("/api/tasks")) > t0
    assert len(_get_notes(srv)) == n0                              # Seitenbaum nicht neu geladen
    # Freigabe entzogen / aus dem geteilten Baum verschoben: verschwindet ebenso im Minutentakt
    del srv.grants[300]
    store.refresh_light()
    pump(lambda: not store.visible(300), 5)
    assert not store.visible(300)
    assert len(_get_notes(srv)) == n0


def test_fenstertakt_kleine_listen(win, srv, monkeypatch):
    """_tick: nicht faellig -> refresh_light + Glocke + Chats; faellig -> voller Abgleich"""
    from hlidskjalf.chat import chat
    from hlidskjalf.live import live
    from hlidskjalf.notifications import notifications
    from hlidskjalf.store import store
    calls = []
    monkeypatch.setattr(win, "is_active", lambda: True)
    monkeypatch.setattr(store, "refresh", lambda *a, **k: calls.append("full"))
    monkeypatch.setattr(store, "refresh_light", lambda: calls.append("light"))
    monkeypatch.setattr(notifications, "count", lambda: calls.append("bell"))
    monkeypatch.setattr(chat, "load_unread", lambda: calls.append("chats"))
    monkeypatch.setattr(win, "_session_catch_up", lambda: None)
    monkeypatch.setattr(live, "connected", True)
    store.tree_seen = True
    store._refreshed_at = time.monotonic() - 60
    win._tick()
    assert calls == ["light", "bell", "chats"]
    calls.clear()
    store.tree_seen = False                                        # noch kein notes.tree: Baum jede Minute
    win._tick()
    assert calls == ["full", "bell", "chats"]


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
    kit.editor = type("E", (), {"doc": type("D", (), {"read_only": False})()})()    # fix27 #11: _press fragt die Rolle
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


def test_notes_tree_vertrag_tolerant():
    from hlidskjalf.store import Store
    assert Store.tree_parse({"ids": [5, "7", 9.0, "x", None], "op": "created", "ops": ["created"], "full": False,
                             "by": {"name": "A"}, "neu": 1}) == ([5, 7, 9], {"created"}, False)
    assert Store.tree_parse(None) == ([], {""}, False)
    assert Store.tree_parse({"ids": "kaputt", "ops": "auch"}) == ([], {""}, False)
    assert Store.tree_plan([5, 7, 5], {"created", "updated"}) == {5, 7}
    for op in ("moved", "trashed", "restored", "archived", "deleted", "shared", "mixed", "", "irgendwas", "changed"):
        ids, ops, _ = Store.tree_parse({"ids": [5], "op": op, "ops": [op], "full": False})
        assert Store.tree_plan(ids, ops) is None, op
    assert Store.tree_plan([], {"updated"}) is None
    assert Store.tree_plan([5], set()) is None
    assert Store.tree_plan(range(1, 22), {"updated"}) is None


def test_notes_tree_full_und_mixed():
    """full: true -> immer voller Abgleich; mixed: es zaehlen die gesammelten Arten (ops)"""
    from hlidskjalf.store import Store
    ids, ops, full = Store.tree_parse({"ids": [], "op": "updated", "ops": ["updated"], "full": True})
    assert full and Store.tree_plan(ids, ops) is None
    ids, ops, full = Store.tree_parse({"ids": [1, 2], "op": "created", "ops": ["created"], "full": True})
    assert full and Store.tree_plan(ids, ops) is None
    assert Store.tree_parse({"ids": [1], "op": "created", "full": "ja"})[2] is False   # nur echtes true zaehlt
    ids, ops, _ = Store.tree_parse({"ids": [3, 4], "op": "mixed", "ops": ["created", "updated"], "full": False})
    assert ops == {"created", "updated"} and Store.tree_plan(ids, ops) == {3, 4}
    ids, ops, _ = Store.tree_parse({"ids": [3], "op": "mixed", "ops": ["moved", "updated"], "full": False})
    assert Store.tree_plan(ids, ops) is None
    ids, ops, _ = Store.tree_parse({"ids": [3], "op": "mixed"})                     # mixed ohne ops: unbekannt
    assert Store.tree_plan(ids, ops) is None
    assert Store.tree_plan([1, 2], {"created", Store.TREE_FULL}) is None            # gesammelt: ein full macht alles voll


def _tree(store, d):
    store.on_tree_event(d)
    pump(lambda: not store._tree_src, 3)
    idle()
    pump(None, 0.3)
    idle()


def test_notes_tree_gezielt_nachladen(srv):
    """Anderswo angelegt/umbenannt/geloescht: nur die betroffenen Seiten holen, Ergebnis wie nach einem Neuladen"""
    from hlidskjalf.store import store
    store.refresh()
    idle()
    n0 = len(_get_notes(srv))
    nid = srv.next_id
    srv.next_id += 1
    srv.notes[nid] = {"id": nid, "title": "Aus dem Web", "parent_id": 1, "format": "blocks", "body": "", "rev": 1,
                      "icon": "", "updated": time.time(), "created": time.time()}
    _tree(store, {"ids": [nid], "op": "created", "zusatz": True})
    assert nid in store.by_id and [p["id"] for p in store.children(1)][-1] == nid
    srv.notes[3]["title"] = "Umbenannt im Web"
    _tree(store, {"ids": [3], "op": "updated", "ops": ["updated"], "full": False})
    assert store.by_id[3]["title"] == "Umbenannt im Web"
    srv.trash.append(srv.notes.pop(nid))                       # inzwischen weg -> 404 -> lokal entfernen
    _tree(store, {"ids": [nid], "op": "updated", "ops": ["updated"], "full": False})
    assert nid not in store.by_id
    assert len(_get_notes(srv)) == n0                          # keine ganze Liste
    local = _snap(store)
    assert local == _after_reload(store)


def test_notes_tree_verschieben_voller_abgleich(srv):
    from hlidskjalf.store import store
    store.refresh()
    idle()
    n0 = len(_get_notes(srv))
    srv.notes[3]["parent_id"] = 1
    _tree(store, {"ids": [3], "op": "moved"})
    assert len(_get_notes(srv)) == n0 + 1
    assert 3 in [p["id"] for p in store.children(1)]
    _tree(store, {"op": "trashed"})                              # ohne IDs: ebenfalls voller Abgleich
    assert len(_get_notes(srv)) == n0 + 2


def test_notes_tree_full_laedt_alles(srv):
    """full: true (zu viele Seiten, ids leer) -> ganzer Baum neu, auch bei op "updated" """
    from hlidskjalf.store import store
    store.refresh()
    idle()
    n0 = len(_get_notes(srv))
    srv.notes[3]["title"] = "Viele geaendert"
    _tree(store, {"ids": [], "op": "updated", "ops": ["updated"], "full": True})
    assert len(_get_notes(srv)) == n0 + 1
    assert store.by_id[3]["title"] == "Viele geaendert"
