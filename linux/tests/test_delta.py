"""Seitenbaum per Abgleich (2.2, Phase 2b): GET api/notes?delta=1 / ?since=<stand> (hlidskjalf/notesync.py + store.py).
Nach jedem Abgleich muss die eingearbeitete Liste GENAU der ganzen Liste entsprechen; aeltere Server (Antwort = Liste)
laufen wie bisher; Stand je Server und Konto, verworfen bei Abmelden/Kontowechsel/Serverwechsel/Live-reset."""
import json
import os
import urllib.parse

from deltamodel import DeltaServer, Rng
from hk_helpers import idle, pump

from hlidskjalf import notesync


def _resp(raw, base, sent):
    return notesync.outcome(notesync.parse(raw), base, sent)


# --- reine Logik -------------------------------------------------------------------------------------------------
def test_zufallsfolgen_gleich_ganzem_baum():
    failed = []
    for run in range(60):
        rng = Rng(run * 7919 + 17)
        srv = DeltaServer()
        for _ in range(12):
            ids = sorted(srv.notes)
            srv.create(None if not ids or rng.next(3) == 0 else ids[rng.next(len(ids))])
        o = _resp(srv.response(None), [], False)
        assert o["full"] and not o["legacy"]
        app, cursor = o["pages"], o["cursor"]
        history = [(cursor, app)]
        ok = app == srv.list()
        for step in range(40):
            for _ in range(1 + rng.next(4)):                   # mehrere Aenderungen zwischen zwei Abgleichen
                srv.random_step(rng, step)
            o = _resp(srv.response(srv.seq_of(cursor)), app, True)
            assert not o["full"] and not o["refetch"] and not o["legacy"], (run, step)
            if o["pages"] is not None:
                app = o["pages"]
            cursor = o["cursor"]
            ok = ok and app == srv.list()
            history.append((cursor, app))
            if step % 7 == 6:                                  # Neustart mit aelterem Stand auf der Platte
                oc, ol = history[rng.next(len(history))]
                o2 = _resp(srv.response(srv.seq_of(oc)), ol, True)
                ok = ok and (o2["pages"] if o2["pages"] is not None else ol) == srv.list() and not o2["refetch"]
                again = _resp(srv.response(srv.seq_of(oc)), app, True)   # doppelt eingearbeitet: harmlos
                ok = ok and (again["pages"] if again["pages"] is not None else app) == srv.list()
        if not ok:
            failed.append(run)
    assert not failed


def _pg(i, t="", parent=None):
    return {"id": i, "title": t, "parent_id": parent, "updated": 1}


def _n(p, after):
    return (p, after)


def test_einzelfaelle():
    base = [_pg(1, "A"), _pg(2, "B"), _pg(3, "C")]
    m, ok = notesync.merge(base, [], [])
    assert ok and m == base and m is not base
    m, ok = notesync.merge(base, [_n(_pg(4, "D"), None)], [2])
    assert ok and [p["id"] for p in m] == [4, 1, 3]
    m, ok = notesync.merge(base, [_n(_pg(5), 3), _n(_pg(6), 5)], [99])
    assert ok and [p["id"] for p in m] == [1, 2, 3, 5, 6]
    m, ok = notesync.merge(base, [_n(_pg(1, "A2"), 3)], [])
    assert ok and [p["id"] for p in m] == [2, 3, 1] and m[-1]["title"] == "A2"
    m, ok = notesync.merge(base, [_n(_pg(7), 42)], [])
    assert not ok and [p["id"] for p in m] == [1, 2, 3, 7]
    raw = json.dumps({"cursor": "x", "full": False, "removed": [], "notes": [dict(_pg(7), after=42)]})
    o = _resp(raw, base, True)
    assert o["refetch"] and o["cursor"] is None and len(o["pages"]) == 4
    raw = json.dumps({"cursor": "x", "full": False, "removed": [], "notes": [_pg(8)]})   # after fehlt
    o = _resp(raw, base, True)
    assert o["refetch"] and o["pages"][-1]["id"] == 8
    assert [p["id"] for p in base] == [1, 2, 3] and base[0]["title"] == "A"            # Grundlage unveraendert
    chain = [_n(_pg(i), 3 if i == 100 else i - 1) for i in range(100, 50100)]          # lange Kette ohne Rekursion
    m, ok = notesync.merge(base, chain, [])
    assert ok and len(m) == 50003 and m[3]["id"] == 100 and m[-1]["id"] == 50099


def test_array_full_cursor_after():
    base = [_pg(1), _pg(2), _pg(3)]
    arr = '  \n[{"id":9,"title":"","icon":"","parent_id":3,"updated":5,"tags":[],"pinned":false,"template":false,' \
          '"kind":"page","archived":false,"locked":false,"shared":false,"unread":false}]'
    r = notesync.parse(arr)
    assert r[0] == "list" and [p["id"] for p in r[1]] == [9]
    o = notesync.outcome(r, base, True)
    assert o["legacy"] and o["full"] and o["cursor"] is None and [p["id"] for p in o["pages"]] == [9]
    o = _resp('{"cursor":"k1","full":true,"removed":[],"notes":[{"id":5,"title":"X","parent_id":null,"updated":1}]}', base, True)
    assert o["full"] and [p["id"] for p in o["pages"]] == [5] and o["cursor"] == "k1" and not o["legacy"]
    o = _resp('{"cursor":"k2","notes":[{"id":5,"title":"X","updated":1}]}', base, False)
    assert [p["id"] for p in o["pages"]] == [5]
    o = _resp('{"cursor":null,"full":false,"removed":[2],"notes":[]}', base, True)
    assert o["cursor"] is None and [p["id"] for p in o["pages"]] == [1, 3]
    assert notesync.parse('{"cursor":88412,"full":false,"notes":[],"removed":[]}')[1] == "88412"
    assert _resp('{"cursor":"k3","full":false,"notes":[],"removed":[]}', base, True)["pages"] is None
    r = notesync.parse('{"cursor":"c","full":false,"removed":[],"notes":[{"id":1,"after":null},{"id":2,"after":1},{"id":3}]}')
    assert [a for _p, a in r[3]][:2] == [None, 1] and r[3][2][1] is notesync._MISSING
    assert all("after" not in p for p, _a in r[3])
    try:
        notesync.parse('{"error":"x"}')
        raise AssertionError("Objekt ohne notes muss scheitern")
    except ValueError:
        pass


def test_anfrage_und_stand():
    assert notesync.path(None) == "api/notes?delta=1" and notesync.path("") == "api/notes?delta=1"
    assert notesync.path("3f9c0a1b2c4d.88412") == "api/notes?since=3f9c0a1b2c4d.88412"
    assert notesync.path("a+b&c=d") == "api/notes?since=a%2Bb%26c%3Dd"
    now = 2_000_000
    c = {"value": "c.5", "server": "https://a.example", "account": "anna", "full_at": now - 60}
    assert notesync.cursor_usable(c, "https://a.example", "anna", now)
    assert not notesync.cursor_usable(c, "https://a.example", "bernd", now)
    assert not notesync.cursor_usable(c, "https://b.example", "anna", now)
    assert not notesync.cursor_usable(c, "https://a.example", None, now)
    assert not notesync.cursor_usable(c, None, None, now)
    assert not notesync.cursor_usable(c, "https://a.example", "anna", now + notesync.FULL_EVERY_S)
    assert not notesync.cursor_usable(dict(c, value=""), "https://a.example", "anna", now)
    assert not notesync.cursor_usable(None, "https://a.example", "anna", now)


# --- Store gegen den Schein-Server -------------------------------------------------------------------------------
def _notes_gets(srv):
    return [x[1] for x in srv.log if x[0] == "GET" and urllib.parse.urlsplit(x[1]).path.rstrip("/").endswith("/api/notes")]


def _with_delta(srv, model, full_always=False):
    """Schein-Server um den Abgleich erweitern (GET api/notes?delta=1 / ?since= aus dem Modell)"""
    orig = srv.handle

    def handle(method, url, data, cookie):
        u = urllib.parse.urlsplit(url)
        q = dict(urllib.parse.parse_qsl(u.query))
        if method == "GET" and u.path.strip("/") == "api/notes" and ("delta" in q or "since" in q):
            srv.log.append((method, url, None, cookie))
            if q.get("since") == "kaputt":
                return 400, b'{"error":"bad cursor"}', {}
            since = None if full_always or "since" not in q else DeltaServer.seq_of(q["since"])
            return 200, model.response(since), {}
        return orig(method, url, data, cookie)
    srv.handle = handle


def _model_with(n=8):
    m = DeltaServer()
    for i in range(n):
        m.create(None if i % 3 == 0 else i)
    return m


def _cache_files(store):
    with open(store._cache_path("api/notes"), "rb") as f:
        lst = json.loads(f.read())
    try:
        with open(store._cache_path(store.CURSOR_CACHE), "rb") as f:
            cur = json.loads(f.read())
    except OSError:
        cur = None
    return lst, cur


def test_alter_server_liste_wie_bisher(srv):
    """Aelterer Server ignoriert ?delta=1 und liefert die Liste: alles wie bisher, kein Stand"""
    from hlidskjalf.store import store
    store.refresh()
    idle()
    gets = _notes_gets(srv)
    assert gets and gets[-1].endswith("/api/notes?delta=1")
    assert store._tree_cursor is None
    lst, cur = _cache_files(store)
    assert cur is None and [p["id"] for p in lst] == [p["id"] for p in store._all] and isinstance(lst, list)
    assert sorted(store.by_id) == sorted(srv.notes)
    store.refresh()
    idle()
    assert _notes_gets(srv)[-1].endswith("/api/notes?delta=1")   # nie since ohne Stand


def test_abgleich_im_store(srv):
    from hlidskjalf.store import Store, store
    m = _model_with()
    _with_delta(srv, m)
    store.refresh()
    idle()
    assert _notes_gets(srv)[-1].endswith("?delta=1")
    assert store._tree_cursor and store._tree_cursor["value"] == f"c.{m.seq}"
    assert store._all == m.list() and store._tree_base == m.list()
    rng = Rng(5)
    for step in range(25):
        for _ in range(1 + rng.next(3)):
            m.random_step(rng, step)
        store.refresh()
        idle()
        assert "since=c." in _notes_gets(srv)[-1]
        assert store._all == m.list(), step
        assert sorted(store.by_id) == sorted(p["id"] for p in m.list())
        lst, cur = _cache_files(store)
        assert lst == m.list() and cur["value"] == store._tree_cursor["value"]
    # Neustart: Zwischenspeicher + Stand -> erster Abgleich mit since
    m.create(None, title="nach Neustart")
    s2 = Store()
    assert s2._all == store._all and s2._tree_cursor == store._tree_cursor
    n = len(_notes_gets(srv))
    s2.refresh()
    pump(lambda: not s2._refreshing, 5)
    assert len(_notes_gets(srv)) == n + 1 and "since=" in _notes_gets(srv)[-1]
    assert s2._all == m.list()


def test_lokale_aenderung_beruehrt_grundlage_nicht(srv):
    from hlidskjalf.store import store
    m = _model_with()
    _with_delta(srv, m)
    store.refresh()
    idle()
    base = json.dumps(store._tree_base)
    store.remove_subtree_local(1, archived=True)
    store._move_local(5, None)
    store.upsert_page(dict(store.by_id[4], title="lokal"))
    assert json.dumps(store._tree_base) == base                    # Grundlage = Stand des Servers
    store.refresh()                                                # nichts geaendert: Anzeige wieder = Server
    idle()
    assert store._all == m.list()


def test_full_true_und_after_unbekannt(srv):
    from hlidskjalf.store import store
    m = _model_with()
    _with_delta(srv, m)
    store.refresh()
    idle()
    # Grundlage weicht vom Server ab (Seite fehlt) -> "after" unbekannt -> in derselben Auffrischung ganz holen
    store._tree_base = [p for p in store._tree_base if p["id"] != 3]
    m.create(3, title="hinter 3")
    m.touch(4, sort=m.notes[3]["sort"] + 0.5)                       # steht jetzt direkt hinter 3
    n = len(_notes_gets(srv))
    store.refresh()
    idle()
    g = _notes_gets(srv)[n:]
    assert len(g) == 2 and "since=" in g[0] and g[1].endswith("?delta=1")
    assert store._all == m.list() and store._tree_cursor["value"] == f"c.{m.seq}"
    # full: true auf since: ersetzen
    srv2 = DeltaServer()
    srv2.create(None, title="ganz neu")
    _with_delta(srv, srv2, full_always=True)
    store.refresh()
    idle()
    assert [p["title"] for p in store._all] == ["ganz neu"]
    # abgelehnter Stand (400): ganz holen
    store._tree_cursor = dict(store._tree_cursor, value="kaputt")
    n = len(_notes_gets(srv))
    store.refresh()
    idle()
    g = _notes_gets(srv)[n:]
    assert "since=kaputt" in g[0] and g[-1].endswith("?delta=1") and store._tree_cursor["value"] != "kaputt"


def test_stand_verwerfen(srv, win):
    from hlidskjalf import config
    from hlidskjalf.store import store
    m = _model_with()
    _with_delta(srv, m)
    store.refresh()
    idle()
    assert store._tree_cursor and os.path.exists(store._cache_path(store.CURSOR_CACHE))
    # anderes Konto am selben Server: Stand nicht benutzen
    acc = config.account(config.settings.server)
    store._tree_cursor = dict(store._tree_cursor, account="jemand-anders")
    store.refresh()
    idle()
    assert _notes_gets(srv)[-1].endswith("?delta=1") and store._tree_cursor["account"] == acc
    # Live "reset" (Fenster) und notes.tree full: true
    win._live_event("reset", {})
    assert store._tree_cursor is None
    idle()
    assert _notes_gets(srv)[-1].endswith("?delta=1")
    store.on_tree_event({"ids": [], "op": "mixed", "ops": [], "full": True})
    pump(lambda: store._tree_cursor is None or store._refreshing, 2)
    idle()
    assert _notes_gets(srv)[-1].endswith("?delta=1")
    # neue Anmeldung / Abmelden: Stand weg (Speicher und Datei)
    store.new_session()
    assert store._tree_cursor is None and not os.path.exists(store._cache_path(store.CURSOR_CACHE))
    store.refresh()
    idle()
    store.forget_account_data()
    assert store._tree_cursor is None and store._tree_base == [] and not os.path.exists(store._cache_path(store.CURSOR_CACHE))


def test_stand_bei_serverwechsel(srv):
    from hlidskjalf.store import store
    m = _model_with()
    _with_delta(srv, m)
    store.refresh()
    idle()
    assert store._tree_cursor
    store.switch_server("https://anders.example")
    assert store._tree_cursor is None and store._tree_base == [] and store._all == []


def test_notes_tree_gezielt_per_since(srv):
    """notes.tree created/updated: mit Abgleich ein GET ?since= statt je Seite GET api/notes/{id}"""
    from hlidskjalf.store import store
    m = _model_with()
    _with_delta(srv, m)
    store.refresh()
    idle()
    nid = m.create(2, title="anderswo angelegt")
    m.touch(4, title="anderswo umbenannt")
    n, n_log = len(_notes_gets(srv)), len(srv.log)
    store.on_tree_event({"ids": [nid, 4], "op": "mixed", "ops": ["created", "updated"]})
    pump(lambda: nid in store.by_id, 3)
    idle()
    assert nid in store.by_id and store.by_id[4]["title"] == "anderswo umbenannt"
    g = _notes_gets(srv)[n:]
    assert len(g) == 1 and "since=" in g[0]
    assert not [x for x in srv.log[n_log:] if x[0] == "GET" and urllib.parse.urlsplit(x[1]).path.endswith((f"/api/notes/{nid}", "/api/notes/4"))]
    assert store._all == m.list()
    # ID, die nicht (mehr) eigene Seite ist (z. B. geteilte Seite eines anderen): wie bisher alles neu laden
    n = len(_notes_gets(srv))
    store.on_tree_event({"ids": [987654], "op": "updated"})
    pump(lambda: len(_notes_gets(srv)) >= n + 2, 3)
    idle()
    assert len(_notes_gets(srv)) >= n + 2
