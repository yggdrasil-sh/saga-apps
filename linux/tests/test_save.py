"""Verlustfreies Speichern: 409/Konflikte, Rueckrufe von flush/refresh, Entwuerfe, Schliessen, Warteschlangen."""
import json
import os

from hk_helpers import idle, note_texts, pump


def _type(doc, text, i=0):
    doc.root[i]["data"]["text"] = text
    doc.mark_dirty()


def _stale_cache(store, nid, rev, text):
    with open(store._cache_path(f"api/notes/{nid}"), "w") as f:
        json.dump({"id": nid, "title": "Testseite", "format": "blocks", "rev": rev,
                   "body": json.dumps({"blocks": [{"id": "a1", "type": "paragraph", "data": {"text": text}}]})}, f)


def _server_page(srv, nid, rev, text):
    srv.notes[nid].update(rev=rev, title="Testseite",
                          body=json.dumps({"blocks": [{"id": "a1", "type": "paragraph", "data": {"text": text}}]}))


def test_409_keeps_input_and_asks(srv):
    """H2: veralteter Zwischenspeicher + sofort tippen -> 409: Eingabe bleibt, Konflikt wird gemeldet"""
    from hlidskjalf.editor import PageDoc, read_draft
    from hlidskjalf.store import store
    _server_page(srv, 3, 5, "Server-Stand")
    _stale_cache(store, 3, 4, "Alter Stand")
    srv.delay["GET api/notes/3"] = 0.8
    d = PageDoc(3)
    seen = []
    d.on_conflict = lambda doc, p: seen.append(p)
    d.load()
    assert d.rev == 4 and d.fetched                      # sofort aus dem Zwischenspeicher
    _type(d, "WICHTIGE EINGABE")
    d.save()
    assert pump(lambda: seen, 6), "Konflikt wurde nicht gemeldet"
    assert len(seen) == 1
    assert d.root[0]["data"]["text"] == "WICHTIGE EINGABE" and d.dirty
    assert note_texts(srv, 3) == ["Server-Stand"]        # nichts ueberschrieben
    assert read_draft(3)["doc"]["blocks"][0]["data"]["text"] == "WICHTIGE EINGABE"   # Entwurf auf der Platte
    # beide behalten: Konfliktkopie mit meiner Fassung, hier die Server-Fassung
    d.resolve("copy")
    assert pump(lambda: any((n.get("title") or "").endswith("(conflict copy)") for n in srv.notes.values()), 6)
    idle()
    copy = next(n for n in srv.notes.values() if (n.get("title") or "").endswith("(conflict copy)"))
    assert "WICHTIGE EINGABE" in copy["body"]
    assert d.root[0]["data"]["text"] == "Server-Stand" and not d.dirty and d.conflict is None
    assert read_draft(3) is None


def test_409_keep_mine(srv):
    from hlidskjalf.editor import PageDoc
    _server_page(srv, 3, 7, "Andere Fassung")
    d = PageDoc(3)
    d.on_conflict = lambda doc, p: doc.resolve("mine")
    d.load()
    pump(lambda: d.fetched)
    d.rev = 6                                            # so, als waere woanders gespeichert worden
    _type(d, "Meine Fassung")
    d.save()
    assert pump(lambda: note_texts(srv, 3) == ["Meine Fassung"], 6)
    idle()
    assert not d.dirty and d.rev == 8


def test_conflict_without_ui_creates_copy(srv):
    """ohne Dialog (Seite nicht sichtbar): automatisch Konfliktkopie, nie verwerfen"""
    from hlidskjalf.editor import PageDoc
    d = PageDoc(3)
    d.load()
    pump(lambda: d.fetched)
    d.rev = 0
    _type(d, "Offline geschrieben")
    d.save()
    assert pump(lambda: any("Offline geschrieben" in (n.get("body") or "") and n["id"] != 3 for n in srv.notes.values()), 6)


def test_newer_rev_while_dirty_reports_conflict(srv):
    """H2 Teil 2: beim Laden kommt eine neuere Revision, waehrend die Seite ungespeichert ist -> sofort melden"""
    from hlidskjalf.editor import PageDoc
    d = PageDoc(3)
    seen = []
    d.on_conflict = lambda doc, p: seen.append(p)
    d.load()
    pump(lambda: d.fetched)
    _type(d, "lokal")
    srv.notes[3]["rev"] += 3
    d.load(force=True)
    assert pump(lambda: seen, 5)
    assert d.root[0]["data"]["text"] == "lokal"


def test_flush_waits_for_running_save(srv):
    """M1: save(done) waehrend ein Speichern laeuft -> done kommt, wenn der letzte Stand gespeichert ist"""
    from hlidskjalf.editor import PageDoc
    d = PageDoc(3)
    d.load()
    pump(lambda: d.fetched)
    srv.delay["PATCH api/notes/3"] = 0.6
    _type(d, "B1")
    d.save()
    _type(d, "B2")
    done = []
    d.save(lambda: done.append(note_texts(srv, 3)))
    assert pump(lambda: done, 8), "Rueckruf ging verloren"
    assert done[0] == ["B2"]


def test_refresh_keeps_callbacks(srv):
    """M2: refresh(done) waehrend einer laufenden Auffrischung -> done kommt trotzdem (nach einer weiteren Auffrischung)"""
    from hlidskjalf.store import store
    srv.delay["GET api/notes"] = 0.6
    store.refresh()
    opened = []
    store.new_page(None, done=lambda pid: opened.append(pid))
    assert pump(lambda: opened, 8), "neue Seite wurde nicht geoeffnet"
    posts = [x for x in srv.log if x[0] == "POST" and x[1].endswith("/api/notes")]
    assert len(posts) == 1
    assert opened[0] in store.by_id                      # die neue Seite ist in der Liste


def test_offline_draft_restored(srv):
    """H3/offline: Speichern scheitert -> Entwurf auf der Platte -> beim naechsten Oeffnen wiederhergestellt und gespeichert"""
    from hlidskjalf.editor import PageDoc, read_draft
    d = PageDoc(3)
    d.load()
    pump(lambda: d.fetched)
    srv.fail["PATCH api/notes/3"] = 0                    # nicht erreichbar
    errs = []
    d.on_error = errs.append
    _type(d, "Unterwegs geschrieben")
    d.save()
    assert pump(lambda: errs, 5)
    assert read_draft(3)["doc"]["blocks"][0]["data"]["text"] == "Unterwegs geschrieben"
    del srv.fail["PATCH api/notes/3"]
    d2 = PageDoc(3)                                      # z. B. nach einem Neustart
    notes = []
    d2.on_notice = notes.append
    d2.load()
    assert pump(lambda: note_texts(srv, 3) == ["Unterwegs geschrieben"], 6)
    idle()
    assert notes and read_draft(3) is None and not d2.dirty


def test_never_saves_unloaded_page(srv):
    from hlidskjalf.editor import PageDoc
    srv.fail["GET api/notes/3"] = 500
    d = PageDoc(3)
    d.load()
    pump(lambda: d.loaded)
    assert not d.fetched
    d.mark_dirty()
    called = []
    d.save(lambda: called.append(1))
    pump(None, 0.3)
    assert called and not any(x[0] == "PATCH" for x in srv.log)


def test_serial_queue_keeps_order(srv):
    """M3: zwei schnelle Aenderungen derselben Zeile kommen in der richtigen Reihenfolge an"""
    from hlidskjalf.store import store
    srv.delay["PATCH api/notes/11"] = 0.4
    store.run("PATCH", "api/notes/11", {"props": {"ok": True}}, key=("note", 11))
    srv.delay["PATCH api/notes/11"] = 0.0
    store.run("PATCH", "api/notes/11", {"props": {"ok": False}}, key=("note", 11))
    idle()
    assert srv.notes[11]["props"].get("ok") is False


def test_schema_writes_coalesce(srv):
    """M3: schnelle Schema-Aenderungen: kein veraltetes Schema ueberholt ein neueres, wartende werden zusammengefasst"""
    from hlidskjalf.database import DBModel
    m = DBModel(10)
    m.load()
    pump(lambda: m.loaded and m.props)
    srv.delay["PATCH api/notes/10"] = 0.4
    for i in range(4):
        m.edit_prop("nr", lambda p, i=i: p.__setitem__("name", f"Zahl {i}"))
    idle()
    patches = [x for x in srv.log if x[0] == "PATCH" and x[1].endswith("/api/notes/10")]
    assert len(patches) <= 2
    assert next(p for p in srv.notes[10]["schema"]["props"] if p["id"] == "nr")["name"] == "Zahl 3"


def test_rename_uses_rev_no_409_in_peek(srv):
    """M1/H2: Titel in der Tabelle aendern, waehrend der Eintrag im Peek offen ist -> kein 409 danach"""
    from hlidskjalf.database import DBModel
    from hlidskjalf.editor import PageDoc
    peek = PageDoc(11)
    peek.load()
    pump(lambda: peek.fetched)
    m = DBModel(10)
    m.load()
    pump(lambda: m.loaded and m.rows)
    m.rename(11, "Neuer Titel")
    idle()
    assert srv.notes[11]["title"] == "Neuer Titel"
    errs = []
    peek.on_error = errs.append
    peek.on_conflict = lambda d, p: errs.append("Konflikt")
    _type(peek, "Text im Peek")
    peek.save()
    idle()
    assert not errs and note_texts(srv, 11) == ["Text im Peek"]
    # ohne offene Seite: Titel mit aktueller Revision
    m.rename(12, "Zeile B neu")
    idle()
    p = [x for x in srv.log if x[0] == "PATCH" and x[1].endswith("/api/notes/12")][-1]
    assert p[2].get("rev") is not None


def test_close_waits_for_save(win, srv):
    """H3: Fenster schliessen, waehrend gespeichert wird -> die letzte Eingabe kommt trotzdem an"""
    win.show_root(("page", 3))
    v = win.current
    pump(lambda: v.doc.fetched and v.kit.order)
    srv.delay["PATCH api/notes/3"] = 1.0
    rt = v.kit.order[0][1]
    rt.get_buffer().set_text("Erster Teil")
    v.editor.flush()
    rt.get_buffer().set_text("Erster Teil + LETZTE EINGABE")
    win.close()
    assert win.get_visible(), "Fenster haette auf das Speichern warten muessen"
    assert pump(lambda: not win.get_visible(), 10), "Fenster schloss nach dem Speichern nicht"
    assert note_texts(srv, 3) == ["Erster Teil + LETZTE EINGABE"]


def test_drafts_are_per_server(srv):
    from hlidskjalf import config
    from hlidskjalf.editor import PageDoc, _draft_path
    d = PageDoc(3)
    d.load()
    pump(lambda: d.fetched)
    srv.fail["PATCH api/notes/3"] = 0
    _type(d, "Entwurf")
    d.save()
    idle()
    assert os.path.exists(_draft_path(3, "https://saga.test"))
    assert not os.path.exists(_draft_path(3, "https://anders.example"))
    assert config.origin("https://SAGA.test:443/x") == "https://saga.test"
