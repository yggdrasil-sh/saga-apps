"""Saga 2.5 - App-Runde Etappe 2 (SPEC-apps-e2): Zusammenfuehren Stufe 1 (Rebase-Buchfuehrung, Delta, Konfliktblock),
Anwesenheit je Block, Aufgabenquellen + Offline-Warteschlange, Inline-Formen setzen, Erwaehnungen, synchronisierte Bloecke,
Verschieben, Zeitleiste ziehen. Logik gegen die Testvektoren des Servers (tests/vectors25), Oberflaeche gegen den
Schein-Server (tests/mockserver.py, mockv25.py)."""
import copy
import json
import os

from gi.repository import Gtk

from hk_helpers import idle, pump

VEC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vectors25")


def _vec(name):
    with open(os.path.join(VEC, name), encoding="utf-8") as f:
        return json.load(f)


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def labels(w):
    return [x.get_label() for x in walk(w) if isinstance(x, Gtk.Label)]


def _body(srv, nid):
    return json.loads(srv.notes[nid]["body"])["blocks"]


def _texts(blocks):
    return [b["data"].get("text") for b in blocks]


def _patches(srv, nid):
    return [b for m, u, b, c in srv.log if m == "PATCH" and u.endswith(f"/api/notes/{nid}") and isinstance(b, dict)]


# --- 1. Zusammenfuehren: Logik (rebase.py) -----------------------------------------------------------------------------
def test_adopt_keeps_unchanged_objects():
    from hlidskjalf import rebase
    local = [{"id": "a", "type": "paragraph", "data": {"text": "A"}}, {"id": "b", "type": "paragraph", "data": {"text": "B"}}]
    server = [{"id": "a", "type": "paragraph", "data": {"text": "A"}}, {"id": "n", "type": "paragraph", "data": {"text": "neu"}},
              {"id": "b", "type": "paragraph", "data": {"text": "B2"}}]
    out = rebase.adopt(local, server)
    assert out[0] is local[0]                                         # unveraendert: dasselbe Objekt (Widget bleibt)
    assert out[1] is server[1] and out[2] is server[2]
    assert [b["id"] for b in out] == ["a", "n", "b"]


def test_delta_from_since():
    from hlidskjalf import rebase
    base = [{"id": "a", "data": {"text": "A"}}, {"id": "b", "data": {"text": "B"}}]
    d = {"from": 3, "blocks": [{"id": "b"}, {"id": "c", "data": {"text": "C"}}, {"id": "a"}]}
    out = rebase.from_delta(base, d)
    assert out == [{"id": "b", "data": {"text": "B"}}, {"id": "c", "data": {"text": "C"}}, {"id": "a", "data": {"text": "A"}}]
    assert out[0] is not base[1]                                      # Kopie: eigener Basis-Stand bleibt unberuehrt
    assert rebase.from_delta(base, {"from": 3, "blocks": [{"id": "x"}]}) is None    # unbekannt -> ganz laden
    assert rebase.from_delta(base, {"from": 3}) is None


def test_conflict_block_take_and_drop_vectors():
    """Konfliktbloecke aus den Server-Vektoren: Uebernehmen = Original (of) weg + hkconflict weg; Verwerfen = Block weg"""
    from hlidskjalf import rebase
    n = 0
    for v in _vec("merge.json")["docs"]:
        exp = v["expect"]["blocks"]
        cids = [b["id"] for a, b in rebase._walk(exp) if rebase.conflict_info(b)]
        for cid in cids:
            n += 1
            doc = copy.deepcopy(exp)
            of = rebase.conflict_info(next(b for a, b in rebase._walk(doc) if b["id"] == cid))["of"]
            assert rebase.conflict_take(doc, cid)
            ids = [b["id"] for a, b in rebase._walk(doc)]
            assert of not in ids and cid in ids, v["name"]
            cb = next(b for a, b in rebase._walk(doc) if b["id"] == cid)
            assert "hkconflict" not in (cb.get("tunes") or {}), v["name"]
            doc = copy.deepcopy(exp)
            assert rebase.conflict_drop(doc, cid)
            ids = [b["id"] for a, b in rebase._walk(doc)]
            assert cid not in ids and of in ids, v["name"]
    assert n >= 5
    # andere Tunes bleiben beim Uebernehmen
    doc = [{"id": "a", "type": "paragraph", "data": {"text": "x"}},
           {"id": "a-k", "type": "paragraph", "data": {"text": "y"}, "tunes": {"hkstyle": {"c": "red"}, "hkconflict": {"of": "a", "name": "B"}}}]
    assert rebase.conflict_take(doc, "a-k") and doc == [{"id": "a-k", "type": "paragraph", "data": {"text": "y"}, "tunes": {"hkstyle": {"c": "red"}}}]
    assert not rebase.conflict_drop(doc, "a-k")                      # kein Konfliktblock (mehr)


# --- 1. Zusammenfuehren: PageDoc gegen den Schein-Server -----------------------------------------------------------------
THREE = [{"id": "p1", "type": "paragraph", "data": {"text": "Eins"}}, {"id": "p2", "type": "paragraph", "data": {"text": "Zwei"}},
         {"id": "p3", "type": "paragraph", "data": {"text": "Drei"}}]


def _three(srv, nid=3):
    import mockv25
    from hlidskjalf.store import store
    store.server_version = "2.5.0"                                    # Server ab 2.5: fuehrt zusammen
    srv.notes[nid]["body"] = json.dumps({"blocks": copy.deepcopy(THREE)})
    srv.notes[nid]["format"] = "blocks"
    mockv25.enable_merge(srv)


def _merge_doc(srv, nid=3):
    from hlidskjalf.editor import PageDoc
    _three(srv, nid)
    d = PageDoc(nid)
    seen = []
    d.on_conflict = lambda doc, p: seen.append(p)
    d.load()
    assert pump(lambda: d.fetched)
    return d, seen


def _other_writes(srv, nid, i, text):
    """jemand anderes aendert Block i (neue rev, Basis gemerkt)"""
    import mockv25
    n = srv.notes[nid]
    bl = json.loads(n["body"])["blocks"]
    bl[i]["data"]["text"] = text
    n["body"] = json.dumps({"blocks": bl})
    n["rev"] += 1
    mockv25.keep_base(srv, nid, n)


def test_merge_unchanged_editor_adopts_blocks(srv):
    """rev veraltet + merge: 1 -> merged; Editor seit dem Senden unveraendert: Bloecke uebernehmen, kein Dialog"""
    d, seen = _merge_doc(srv)
    first = d.root[0]
    _other_writes(srv, 3, 1, "fremd")
    d.root[0]["data"]["text"] = "meins"
    d.mark_dirty()
    d.save()
    idle()
    assert not seen and d.conflict is None and not d.dirty
    p = _patches(srv, 3)[-1]
    assert p["merge"] == 1 and "base" not in p
    caps = [h[2].get("X-Saga-Caps") for h in srv.headers_log if h[0] == "PATCH" and h[1].endswith("/api/notes/3")]
    assert caps[-1] == "inline2,cols6,merge1"
    assert d.root[0] is first                                          # eigener Block: dasselbe Objekt (Schreibmarke)
    assert d.root[0]["data"]["text"] == "meins" and d.root[1]["data"]["text"] == "fremd"
    assert d.rev == srv.notes[3]["rev"]


def test_merge_typed_on_keeps_local_and_sends_base_sent(srv):
    """waehrend des Speicherns weitergeschrieben: lokalen Stand behalten, rev uebernehmen, naechstes Speichern base: sent"""
    d, seen = _merge_doc(srv)
    _other_writes(srv, 3, 1, "fremd")
    srv.delay["PATCH api/notes/3"] = 0.4
    d.root[0]["data"]["text"] = "meins"
    d.mark_dirty()
    d.save()
    pump(None, 0.15)
    d.root[0]["data"]["text"] = "meins weiter"                        # waehrend die Antwort unterwegs ist
    d.mark_dirty()
    pump(lambda: len(_patches(srv, 3)) >= 2, 6)
    srv.delay.pop("PATCH api/notes/3", None)
    idle()
    ps = _patches(srv, 3)
    assert ps[0]["merge"] == 1 and "base" not in ps[0]
    assert ps[1]["base"] == "sent" and ps[1]["merge"] == 1
    assert not seen and not d.dirty
    assert _texts(_body(srv, 3))[:2] == ["meins weiter", "fremd"]
    assert d.root[1]["data"]["text"] == "fremd"                       # die zweite Antwort brachte den fremden Block
    assert not d.base_sent


def test_merge_conflict_block_hint_and_resolve(win, srv):
    """Ueberschneidung -> Konfliktblock (gelber Rand, "Fassung von …"), Uebernehmen/Verwerfen speichert"""
    _three(srv)
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched)
    _other_writes(srv, 3, 0, "Server sagt")
    v.doc.root[0]["data"]["text"] = "Ich sage"
    v.editor.text_changed()
    v.editor.flush()
    idle()
    pump(lambda: any(r.bid.endswith("-k") for r in v.kit.rows()), 3)
    rows = {r.bid: r for r in v.kit.rows()}
    cid = next(b for b in rows if b.endswith("-k"))
    assert rows[cid].get_first_child().get_next_sibling().has_css_class("hk-conflict")
    assert "Version by Test" in rows[cid].conflict_label.get_label()
    assert v.doc.conflict is None and _dialog(win) is None
    take, drop = rows[cid].conflict_buttons
    take.emit("clicked")
    v.editor.flush()
    idle()
    bl = _body(srv, 3)
    assert bl[0]["id"] == cid and bl[0]["data"]["text"] == "Ich sage" and "tunes" not in bl[0]


def _dialog(win):
    return win.get_visible_dialog() if hasattr(win, "get_visible_dialog") else None


def test_merge_no_base_falls_back_to_409_dialog(srv):
    """409 mit merge: 1 nur noch ohne Basis -> bisheriger Konfliktdialog (Rueckfall im Code)"""
    d, seen = _merge_doc(srv)
    srv.merge_gone.add((3, d.rev))
    _other_writes(srv, 3, 1, "fremd")
    d.root[0]["data"]["text"] = "meins"
    d.mark_dirty()
    d.save()
    assert pump(lambda: seen, 6)
    assert d.conflict is not None


def test_federated_page_without_merge1_no_merge_flag(srv):
    from hlidskjalf.editor import PageDoc
    d = PageDoc(3)
    d.fetched = True
    d.page = {"id": -5, "remote": {"caps": ["presence"]}, "format": "blocks"}
    assert not d.can_merge()
    d.page = {"id": -5, "remote": {"caps": ["presence", "merge1"]}, "format": "blocks"}
    assert d.can_merge()
    d.page = {"id": 3, "format": "blocks", "kind": "database"}
    assert not d.can_merge()


def test_live_change_pulls_delta_without_reload_hint(win, srv):
    """note.changed mit hoeherer rev: GET ?since=<rev> (delta), blockweise uebernehmen, kein "Neu laden"-Hinweis"""
    _three(srv)
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched)
    keep = v.doc.root[0]
    _other_writes(srv, 3, 1, "von Anna")
    v.live_changed({"id": 3, "rev": srv.notes[3]["rev"], "by": {"name": "Anna"}})
    assert pump(lambda: v.doc.rev == srv.notes[3]["rev"], 6)
    idle()
    assert srv.since_log and srv.since_log[-1][2]                     # mit Delta beantwortet
    assert v.doc.root[0] is keep and v.doc.root[1]["data"]["text"] == "von Anna"
    assert not v.changed_banner.get_revealed()
    # mit eigenen ungespeicherten Eingaben: sofort mit merge: 1 speichern, eigene Bloecke bleiben
    _other_writes(srv, 3, 1, "noch einmal Anna")
    v.doc.root[0]["data"]["text"] = "ungespeichert"
    v.doc.mark_dirty()
    v.live_changed({"id": 3, "rev": srv.notes[3]["rev"], "by": {"name": "Anna"}})
    idle()
    assert _texts(_body(srv, 3))[:2] == ["ungespeichert", "noch einmal Anna"]
    assert v.doc.root[1]["data"]["text"] == "noch einmal Anna"
    assert not v.changed_banner.get_revealed() and v.doc.conflict is None


def test_offline_draft_with_rev_merges_after_reconnect(srv):
    """Offline-Entwurf mit rev: nach Wiederverbindung merge: 1 statt Dialog"""
    from hlidskjalf import drafts
    d, seen = _merge_doc(srv)
    srv.fail["PATCH api/notes/3"] = 0
    d.root[0]["data"]["text"] = "offline getippt"
    d.mark_dirty()
    d.save()
    idle()
    assert drafts.read(3)["rev"] == d.rev
    srv.fail.pop("PATCH api/notes/3")
    _other_writes(srv, 3, 1, "inzwischen fremd")
    d._retry()
    idle()
    assert not seen and d.conflict is None
    assert _texts(_body(srv, 3))[:2] == ["offline getippt", "inzwischen fremd"]


# --- 2. Anwesenheit je Block -----------------------------------------------------------------------------------------------
def test_presence_block_throttle(srv):
    """Blockwechsel melden: nur mit editing, hoechstens ~1x/s; schneller -> die letzte Meldung wird nachgeholt"""
    from hlidskjalf.live import Presence
    srv.live_api = True
    p = Presence(3, lambda: True, lambda: True, lambda u: None, lambda r, b: None, lambda: 1)
    p.focus_block("a")                                                # ohne Eingabe: kein editing -> nichts
    idle()
    assert not srv.presence_log
    p.typed()                                                         # editing -> Meldung mit Block
    assert pump(lambda: srv.presence_log, 3), (p.off, p.alive, p.editing)
    assert srv.presence_log[-1] == {"note_id": 3, "editing": True, "block": "a"}
    p.focus_block("b")
    p.focus_block("c")                                                # innerhalb 1 s: gedrosselt
    idle()
    assert len(srv.presence_log) == 1
    assert pump(lambda: len(srv.presence_log) == 2, 3)
    assert srv.presence_log[-1]["block"] == "c"                       # nur der letzte Stand
    pump(None, 1.2)
    assert len(srv.presence_log) == 2
    p.stop()
    # foederiert ohne presence2: kein block
    q = Presence(3, lambda: True, lambda: True, lambda u: None, lambda r, b: None, lambda: 1, blocks=False)
    n0 = len(srv.presence_log)
    q.typed()
    q.focus_block("x")
    assert pump(lambda: len(srv.presence_log) > n0, 3)
    pump(None, 1.2)
    assert all("block" not in x for x in srv.presence_log[n0:])
    q.stop()


def test_presence_marks_block_of_other_person(win, srv):
    """Anzeige je Person aus presence.users[].block/color: farbiger Rand + "{name} bearbeitet" (nur Hinweis)"""
    _three(srv)
    srv.live_api = True
    srv.presence_users = [{"username": "anna", "name": "Anna Berg", "editing": True, "block": "p2", "color": "green"},
                          {"username": "bert", "name": "Bert", "editing": False, "block": None, "color": "red"}]
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched and any(r.bid == "p2" and r.has_css_class("hk-pres-green") for r in v.kit.rows()), 6)
    rows = {r.bid: r for r in v.kit.rows()}
    assert rows["p2"].presence_lb.get_label() == "Anna Berg is editing"
    assert not rows["p1"].has_css_class("hk-pres")
    assert v.kit.by_key["p2"].get_editable()                          # keine Sperre
    v.live_presence([{"username": "anna", "name": "Anna Berg", "editing": True, "block": "p3", "color": "green"}])
    rows = {r.bid: r for r in v.kit.rows()}
    assert not rows["p2"].has_css_class("hk-pres") and rows["p2"].presence_lb is None
    assert rows["p3"].has_css_class("hk-pres-green")
    # Fokus in Block p1 + Eingabe -> Meldung mit block
    v.kit.by_key["p1"].focus_at("end")
    pump(None, 0.1)
    v.on_typed()
    assert pump(lambda: any(x.get("block") == "p1" for x in srv.presence_log), 4)


# --- 3. Aufgabenquellen + Offline-Warteschlange ------------------------------------------------------------------------------
def test_queue_last_action_wins_prev_and_codes(tmp_path):
    from hlidskjalf.tasksrc import Queue
    q = Queue(str(tmp_path / "q.json"))
    row = {"kind": "row", "id": 7, "src": 77}
    q.put(row, True, prev="opt-doing")
    assert list(q.d) == ["r:7"] and q.d["r:7"]["prev"] == "opt-doing"
    q.put(row, False)                                                # abhaken + wieder oeffnen = nichts
    assert len(q) == 0
    q.put(row, True, prev="opt-doing")
    q.put({"kind": "task", "id": 7}, True)                           # t:7 und r:7 getrennt
    assert set(q.d) == {"r:7", "t:7"}
    assert Queue(str(tmp_path / "q.json")).d == q.d                  # auf der Platte
    # Wieder-Oeffnen nach gesendetem Abhaken: mit prev (nie pauschal open_value)
    e = {"kind": "row", "id": 7, "done": False, "prev": "opt-doing"}
    assert Queue.request(e) == ("PATCH", "api/tasks/src/7", {"done": False, "value": "opt-doing"})
    assert Queue.request({"kind": "row", "id": 7, "done": True, "prev": "x"}) == ("PATCH", "api/tasks/src/7", {"done": True})
    assert Queue.request({"kind": "task", "id": 3, "done": True}) == ("PATCH", "api/tasks/3", {"done": True})
    assert [Queue.outcome(c) for c in (200, 403, 404, 400, 500, 503, None)] == ["ok", "drop", "drop", "reload", "keep", "keep", "ok"]


def test_tasks_path_and_targets():
    from hlidskjalf import tasksrc
    p = tasksrc.tasks_path("2026-10-28", "Europe/Berlin")
    assert p == "api/tasks?with_sources=1&today=2026-10-28&tz=Europe%2FBerlin"
    srcs = [{"db": 1, "map": {"done": "s"}, "role": "owner"}, {"db": 2, "map": None, "role": "owner"},
            {"db": 3, "map": {"done": "s"}, "role": "view"}, {"db": 4, "map": {"done": "s"}, "role": "edit", "inactive": True},
            {"db": 5, "map": {"done": "s"}, "role": "edit"}]
    assert [s["db"] for s in tasksrc.targets(srcs)] == [1, 5]
    assert tasksrc.when_text({"due": "2026-10-28", "time": "09:30"}).endswith("09:30")
    assert " – " in tasksrc.when_text({"due": "2026-10-28", "due_end": "2026-10-30"})


def _src_setup(srv):
    from hlidskjalf.store import store
    srv.v25 = True
    srv.tasksrc = True
    srv.src_sources = [{"db": 77, "title": "Projekt", "icon": "☑️", "role": "owner", "scope": "all", "own": True, "count": 2,
                        "map": {"done": "status", "done_type": "status", "assignee": "wer"}},
                       {"db": 78, "title": "Fremd", "icon": "", "role": "view", "scope": "assigned", "own": False, "count": 1,
                        "map": {"done": "erl"}}]
    srv.src_people = {"~annaannaannaanna": {"name": "Anna Berg", "avatar": None, "me": False}}
    srv.src_items = [{"key": "r:500", "kind": "row", "id": 500, "src": 77, "text": "Zeile mit Termin", "due": "2026-10-29",
                      "time": "09:30", "prio": 1, "done": False, "overdue": False, "edit": True, "assignees": ["~annaannaannaanna"]},
                     {"key": "r:501", "kind": "row", "id": 501, "src": 78, "text": "Nur lesen", "done": False, "overdue": False,
                      "edit": False}]
    store.check_version()
    assert pump(lambda: store.has("tasksrc1"), 4)
    store.load_today()
    assert pump(lambda: store.task_items is not None, 4)


def test_task_list_with_sources(win, srv):
    """Liste in Server-Reihenfolge, Zeilen mit Quelle, Uhrzeit, Zugewiesene (Name aus people), edit: false ausgegraut"""
    from hlidskjalf.agenda import SourceRow
    _src_setup(srv)
    win.show_root(("tasks",))
    tl = win.current.task_list
    pump(lambda: any(isinstance(w, SourceRow) for w in walk(tl)), 4)
    rows = [w for w in walk(tl) if isinstance(w, SourceRow)]
    assert [r.it["key"] for r in rows] == [x["key"] for x in srv.src_items[:1]] + [f"t:{t['id']}" for t in srv.tasks] + ["r:501"]
    ls = labels(rows[0])
    assert any("☑️ Projekt" == x for x in ls) and any("09:30" in (x or "") for x in ls) and "Anna Berg" in ls
    assert not any("anna" == x for x in ls)                           # nie Anmeldenamen
    assert not rows[-1].check.get_sensitive()
    assert any(l.startswith("Inbox") for l in labels(tl.target)) or tl.target.get_label() == "Inbox"
    assert ["Projekt"] == [s.get("title") for s in __import__("hlidskjalf.tasksrc", fromlist=["x"]).targets(__import__("hlidskjalf.store", fromlist=["x"]).store.task_sources)]


def test_row_done_undo_with_prev(win, srv):
    """Abhaken -> PATCH /api/tasks/src/{zeile}; Rueckgaengig schickt prev als value"""
    from hlidskjalf.agenda import SourceRow
    from hlidskjalf.store import store
    _src_setup(srv)
    srv.src_prev[500] = "opt-doing"
    win.show_root(("tasks",))
    tl = win.current.task_list
    pump(lambda: any(isinstance(w, SourceRow) for w in walk(tl)), 4)
    row = next(w for w in walk(tl) if isinstance(w, SourceRow) and w.it["id"] == 500)
    got = []
    store.set_item_done(row.it, True, undo=got.append)
    assert pump(lambda: got, 4) and got == ["opt-doing"]
    idle()
    it = next(x for x in srv.src_items if x["id"] == 500)
    assert it["done"]
    store.set_item_done(dict(row.it, done=True), False, got[0])
    assert pump(lambda: not it["done"], 4)
    idle()
    assert any(b == {"done": False, "value": "opt-doing"} for m, p, b in srv.src_log)
    assert len(store.tqueue) == 0, store.tqueue.d


def test_offline_queue_and_error_codes(win, srv):
    """offline abhaken: bleibt in der Warteschlange (Liste zeigt es), nach Wiederverbindung nachgeholt; 403 -> verworfen"""
    from hlidskjalf.store import store
    _src_setup(srv)
    srv.fail["PATCH api/tasks/src/500"] = 0
    it = next(x for x in store.task_items if x["id"] == 500)
    store.set_item_done(it, True, prev="opt-doing")
    idle()
    assert "r:500" in store.tqueue.d and store.tqueue.d["r:500"]["prev"] == "opt-doing"
    store.load_today()                                               # neu geladen: noch nicht gesendet -> abgehakt gezeigt
    idle()
    assert next(x for x in store.task_items if x["id"] == 500)["done"]
    srv.fail.pop("PATCH api/tasks/src/500")
    store.flush_task_queue()
    idle()
    assert len(store.tqueue) == 0 and next(x for x in srv.src_items if x["id"] == 500)["done"]
    # 403 beim Nachholen: verwerfen und neu laden (kein endloses Wiederholen)
    srv.src_fail[501] = 403
    it2 = {"kind": "row", "id": 501, "src": 78, "edit": True}
    store.set_item_done(it2, True)
    idle()
    assert len(store.tqueue) == 0
    srv.src_fail[501] = 503                                          # 5xx: behalten
    store.set_item_done(it2, True)
    idle()
    assert "r:501" in store.tqueue.d
    srv.src_fail[501] = 400                                          # 400: neu laden, nicht wiederholen
    store.flush_task_queue()
    idle()
    assert len(store.tqueue) == 0


def test_new_task_with_target_and_sources_dialog(win, srv):
    from hlidskjalf.agenda import SourcesDialog
    from hlidskjalf.store import store
    _src_setup(srv)
    win.show_root(("tasks",))
    tl = win.current.task_list
    pump(lambda: tl.target.get_visible(), 3)
    tl.target.choose(77)
    tl.entry.set_text("In die Quelle")
    tl.entry.emit("activate")
    idle()
    assert any(m == "POST" and b == {"text": "In die Quelle", "src": 77} for m, p, b in srv.src_log)
    assert pump(lambda: any(x.get("text") == "In die Quelle" for x in store.task_items), 3)
    dlg = SourcesDialog(win)
    dlg.present(win)
    pump(lambda: dlg.group is not None and any(getattr(r, "hk_db", None) == 77 for r in walk(dlg.group)), 3)
    r77 = next(r for r in walk(dlg.group) if getattr(r, "hk_db", None) == 77)
    r77.hk_scope.set_selected(1)
    assert pump(lambda: next(x for x in srv.src_sources if x["db"] == 77)["scope"] == "assigned", 3)
    r77.hk_remove.emit("clicked")
    assert pump(lambda: [x["db"] for x in srv.src_sources] == [78], 3)
    dlg.force_close()


def test_use_as_source_needs_map(win, srv):
    from hlidskjalf.agenda import use_as_source
    _src_setup(srv)
    srv.notes[10]["schema"] = dict(srv.notes[10].get("schema") or {}, tasks=None)
    use_as_source(win, 10)
    assert pump(lambda: _dialog(win) is not None, 3)
    assert _dialog(win).get_heading() == "Set up on the web"
    _dialog(win).force_close()
    srv.notes[10]["schema"]["tasks"] = {"done": "x"}
    use_as_source(win, 10)
    assert pump(lambda: any(x["db"] == 10 for x in srv.src_sources), 3)


def test_digest_settings(srv):
    from hlidskjalf.agenda import DigestGroup
    _src_setup(srv)

    class D:
        toasts = []

        def add_toast(self, t):
            self.toasts.append(t)
    g = DigestGroup(D())
    assert pump(lambda: g.get_visible(), 3)
    assert not g.sw.get_active() and g.tm.get_text() == "07:30"
    g.sw.set_active(True)
    assert pump(lambda: srv.digest["on"] is True, 3)
    g.tm.set_text("6:45")
    g.tm.emit("apply")
    assert pump(lambda: srv.digest["time"] == "06:45", 3)


# --- 5. Inline-Formen setzen -------------------------------------------------------------------------------------------------
def _rtx(html):
    from hlidskjalf.richtext import RichText
    rt = RichText(html)
    return rt, rt.get_buffer()


def _sel(buf, a, b):
    buf.select_range(buf.get_iter_at_offset(a), buf.get_iter_at_offset(b))


def test_inline_set_colors_order_and_remove():
    rt, buf = _rtx("Hallo Welt")
    _sel(buf, 6, 10)
    assert rt.set_inline_color("color", "red")
    assert rt.html() == 'Hallo <span class="hk-c-red">Welt</span>'
    _sel(buf, 6, 10)
    rt.set_inline_color("bg", "blue")                                 # span aussen, mark innen
    assert rt.html() == 'Hallo <span class="hk-c-red"><mark class="hk-bg-blue">Welt</mark></span>'
    assert rt.inline_colors() == ("red", "blue")
    _sel(buf, 6, 10)
    rt.set_inline_color("color", "green")                            # genau eine Farbe je Stelle
    assert rt.html() == 'Hallo <span class="hk-c-green"><mark class="hk-bg-blue">Welt</mark></span>'
    _sel(buf, 6, 10)
    rt.set_inline_color("color", None)
    assert rt.html() == 'Hallo <mark class="hk-bg-blue">Welt</mark>'
    _sel(buf, 6, 10)
    rt.set_inline_color("bg", None)
    assert rt.html() == "Hallo Welt"
    _sel(buf, 0, 5)
    assert not rt.set_inline_color("color", "lila")                  # nur Palette
    # Teil einer Farbe umfaerben, Fett bleibt
    rt, buf = _rtx('<span class="hk-c-red">ab <b>cd</b></span>')
    _sel(buf, 3, 5)
    rt.set_inline_color("color", "blue")
    assert rt.html() == '<span class="hk-c-red">ab </span><span class="hk-c-blue"><b>cd</b></span>'
    # Marker (Gelb) wird durch Hintergrundfarbe ersetzt
    rt, buf = _rtx('<mark class="cdx-marker">gelb</mark>')
    _sel(buf, 0, 4)
    rt.set_inline_color("bg", "pink")
    assert rt.html() == '<mark class="hk-bg-pink">gelb</mark>'


def test_inline_set_results_are_server_clean():
    """was die App setzt, laesst die Bereinigung des Servers (Vektoren sanitize) unveraendert"""
    vec = {inp: out for _n, inp, out in _vec("editor_inline.json")["sanitize"]}
    for kind, c in (("color", "red"), ("bg", "blue")):
        rt, buf = _rtx("abc")
        _sel(buf, 0, 3)
        rt.set_inline_color(kind, c)
        h = rt.html()
        assert vec.get(h, h) == h
    rt, buf = _rtx("x ")
    buf.place_cursor(buf.get_end_iter())
    rt.set_math("a<b")
    assert rt.html() == 'x <code class="hk-math">a&lt;b</code>'
    assert vec['<code class="hk-math">a&lt;b</code>'] == '<code class="hk-math">a&lt;b</code>'


def test_inline_math_edit_and_remove():
    rt, buf = _rtx('vor <code class="hk-math">x^2</code> nach')
    buf.place_cursor(buf.get_iter_at_offset(5))
    rng = rt.math_range()
    assert rng and buf.get_text(rng[0], rng[1], True) == "x^2"
    rt.set_math("\\frac{a}{b}", rng)
    assert rt.html() == 'vor <code class="hk-math">\\frac{a}{b}</code> nach'
    buf.place_cursor(buf.get_iter_at_offset(6))
    rt.set_math("", rt.math_range())
    assert rt.html() == "vor  nach"


def test_inline_mention_insert_html():
    rt, buf = _rtx("Hallo @An")
    buf.place_cursor(buf.get_end_iter())
    rt.insert_mention("~abcdefghijklmnop", "Anna Berg", n_chars=3)
    assert rt.html() == 'Hallo <span class="hk-mention" data-u="~abcdefghijklmnop">Anna Berg</span> '
    rt, buf = _rtx("")
    rt.insert_mention('@bob@b.example', "Bob")
    assert rt.html() == '<span class="hk-mention" data-u="@bob@b.example">Bob</span> '


def test_color_picker_ui_and_caps(win, srv):
    """Farb-Auswahl auf einer Seite: Strg+Umschalt+C auf Auswahl, speichern mit inline2,cols6(,merge1)"""
    srv.notes[3]["body"] = json.dumps({"blocks": [{"id": "c1", "type": "paragraph", "data": {"text": "Farbe hier"}}]})
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched and "c1" in v.kit.by_key)
    rt = v.kit.by_key["c1"]
    buf = rt.get_buffer()
    _sel(buf, 0, 5)
    pop = v.color_popover(rt)
    pump(None, 0.2)
    btn = next(b for b in walk(pop) if getattr(b, "hk_color", None) == ("color", "orange"))
    btn.emit("clicked")
    v.editor.flush()
    assert pump(lambda: "hk-c-orange" in srv.notes[3]["body"], 5)
    idle()
    assert json.loads(srv.notes[3]["body"])["blocks"][0]["data"]["text"] == '<span class="hk-c-orange">Farbe</span> hier'
    caps = [h[2].get("X-Saga-Caps") for h in srv.headers_log if h[0] == "PATCH" and h[1].endswith("/api/notes/3")]
    assert caps[-1].startswith("inline2,cols6")


# --- 4. Erwaehnungen + synchronisierte Bloecke ---------------------------------------------------------------------------------
def test_comment_mention_markup_and_token():
    from hlidskjalf.comments import mention_markup, mention_token
    ppl = {"~abcdefghijklmnop": {"name": "Anna <B>", "me": False}, "~zyxwvutsrqponmlk": {"name": "Weg", "gone": True},
           "~meeeeeeeeeeeeeee": {"name": "Ich", "me": True}}
    m = mention_markup("Schau <@~abcdefghijklmnop> und <@~zyxwvutsrqponmlk> <@~meeeeeeeeeeeeeee> <@~unbekanntunbekan> https://x.example", ppl)
    assert "@Anna &lt;B&gt;" in m and "<s>@Weg</s>" in m and "#0b689340" in m
    assert "&lt;@~unbekanntunbekan&gt;" in m and '<a href="https://x.example">' in m
    assert mention_token("~abcdefghijklmnop") == "<@~abcdefghijklmnop>"
    assert mention_token("@bob@b.example") == "<@@bob@b.example>"


def _mention_setup(srv):
    from hlidskjalf.store import store
    srv.v25 = True
    srv.extra_features = ["mention1", "synced1"]
    srv.v25_people = {"~annaannaannaanna": {"name": "Anna Berg", "me": False}}
    store.check_version()
    assert pump(lambda: store.has("mention1"), 4)


def test_mention_insert_via_at(win, srv):
    """"@" im Text: Personen mit Zugriff aus /people -> span.hk-mention (data-u = ref, Text = name)"""
    _mention_setup(srv)
    srv.notes[3]["body"] = json.dumps({"blocks": [{"id": "m1", "type": "paragraph", "data": {"text": ""}}]})
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched and "m1" in v.kit.by_key)
    rt = v.kit.by_key["m1"]
    rt.grab_focus()
    buf = rt.get_buffer()
    buf.insert_at_cursor("Hallo @An")
    assert pump(lambda: v.suggest.visible and any(isinstance(it[3], dict) for it in v.suggest.items), 4)
    i = next(k for k, it in enumerate(v.suggest.items) if isinstance(it[3], dict))
    v.suggest.sel = i
    v.suggest.pick_current()
    v.editor.flush()
    assert pump(lambda: "hk-mention" in srv.notes[3]["body"], 5)
    idle()
    assert json.loads(srv.notes[3]["body"])["blocks"][0]["data"]["text"] == \
        'Hallo <span class="hk-mention" data-u="~annaannaannaanna">Anna Berg</span> '


def test_mention_person_without_access_shares_first(win, srv):
    _mention_setup(srv)
    srv.share_api = True
    srv.names["~bertbertbertbert"] = "Bert"                           # Konto (Handle) bekannt
    srv.notes[3]["body"] = json.dumps({"blocks": [{"id": "m1", "type": "paragraph", "data": {"text": "x @Be"}}]})
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched and "m1" in v.kit.by_key)
    rt = v.kit.by_key["m1"]
    buf = rt.get_buffer()
    buf.place_cursor(buf.get_end_iter())
    dlg = v.share_and_mention(rt, {"ref": "~bertbertbertbert", "name": "Bert", "access": False}, 3)
    assert dlg.get_heading() == "Share page and mention?"
    dlg.emit("response", "view")
    dlg.force_close()
    v.editor.flush()
    assert pump(lambda: "hk-mention" in srv.notes[3]["body"], 5)
    idle()
    assert any(m == "PUT" and u.endswith("/api/notes/3/shares") and b.get("target") == "~bertbertbertbert" and b.get("role") == "view"
               for m, u, b, c in srv.log if isinstance(b, dict))
    assert json.loads(srv.notes[3]["body"])["blocks"][0]["data"]["text"] == \
        'x <span class="hk-mention" data-u="~bertbertbertbert">Bert</span> '


SYNC_PAGE = [
    {"id": "o1", "type": "synced", "data": {"sid": "OrigOrigOrigOrig", "blocks": [{"id": "oi", "type": "paragraph", "data": {"text": "Original hier"}}],
                                            "src": {"original": True, "refs": 1}}},
    {"id": "c1", "type": "synced", "data": {"sid": "KopieKopieKopieK", "blocks": [{"id": "ki", "type": "paragraph", "data": {"text": "aus A"}}],
                                            "src": {"note": 4, "block": "x", "role": "edit", "rev": 7, "refs": 2}}},
    {"id": "c2", "type": "synced", "data": {"sid": "LesenLesenLesenL", "blocks": [{"id": "li", "type": "paragraph", "data": {"text": "nur lesen"}}],
                                            "src": {"note": 5, "block": "y", "role": "view", "rev": 3, "refs": 1}}},
    {"id": "c3", "type": "synced", "data": {"sid": "OrigOrigOrigOrig", "src": {"missing": True}}},
]


def test_synced_copy_edit_writes_back_with_src(win, srv):
    """Kopie mit Bearbeiten: Inhalt bearbeitbar, zurueck mit unveraendertem data.src, Kopf sync1; synced_rev -> src.rev.
    Original und (zweite) Kopie derselben sid auf derselben Seite bleiben erhalten."""
    _mention_setup(srv)
    srv.notes[3]["body"] = json.dumps({"blocks": copy.deepcopy(SYNC_PAGE)})
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched and "ki" in v.kit.by_key)
    assert v.kit.by_key["ki"].get_editable() and v.kit.by_key["oi"].get_editable()
    assert "li" not in v.kit.by_key or not v.kit.by_key["li"].get_editable()       # Rolle view: nur lesen
    srv.patch_extra = {"synced": {"KopieKopieKopieK": "ok"}, "synced_rev": {"KopieKopieKopieK": 8}}
    buf = v.kit.by_key["ki"].get_buffer()
    buf.insert(buf.get_end_iter(), " + hier")
    v.editor.flush()
    assert pump(lambda: "+ hier" in srv.notes[3]["body"], 5)
    idle()
    sent = json.loads(srv.notes[3]["body"])["blocks"]
    assert [b["id"] for b in sent] == ["o1", "c1", "c2", "c3"]                    # nichts verloren
    assert sent[1]["data"]["src"] == SYNC_PAGE[1]["data"]["src"]                   # src unveraendert gesendet
    assert sent[1]["data"]["blocks"][0]["data"]["text"] == "aus A + hier"
    assert sent[3] == SYNC_PAGE[3] and sent[0]["data"]["blocks"] == SYNC_PAGE[0]["data"]["blocks"]
    assert "blocks" not in sent[2]["data"] and sent[2]["data"]["src"] == SYNC_PAGE[2]["data"]["src"]   # nur lesen: kein Inhalt
    caps = [h[2].get("X-Saga-Caps") for h in srv.headers_log if h[0] == "PATCH" and h[1].endswith("/api/notes/3")]
    assert "sync1" in caps[-1].split(",")
    assert v.doc.root[1]["data"]["src"]["rev"] == 8
    # stale -> neu laden + Hinweis
    srv.patch_extra = {"synced": {"KopieKopieKopieK": "stale"}, "synced_rev": {}}
    gets = len([1 for m, u, b, c in srv.log if m == "GET" and u.endswith("/api/notes/3")])
    buf.insert(buf.get_end_iter(), "!")
    v.editor.flush()
    assert pump(lambda: len([1 for m, u, b, c in srv.log if m == "GET" and u.endswith("/api/notes/3")]) > gets, 5)
    srv.patch_extra = None
    idle()


# --- 6. Verschieben (move1) ------------------------------------------------------------------------------------------------
def test_move_block_to_page_and_undo(win, srv):
    from hlidskjalf.store import store
    srv.v25 = True
    store.check_version()
    assert pump(lambda: store.has("move1"), 4)
    _three(srv)
    srv.notes[2]["body"] = json.dumps({"blocks": [{"id": "z1", "type": "paragraph", "data": {"text": "Ziel"}}]})
    srv.notes[2]["format"] = "blocks"
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched and "p2" in v.kit.by_key)
    v.doc.root[0]["data"]["text"] = "ungespeichert"                    # vorher speichern
    v.editor.text_changed()
    srv.notes[3]["rev"] += 0
    assert any(p["id"] == 2 for p in v.move_targets()) and all(p["id"] != 3 for p in v.move_targets())
    v.move_blocks(["p2"], 2)
    assert pump(lambda: "Zwei" in srv.notes[2]["body"], 5)
    idle()
    assert _texts(_body(srv, 3)) == ["ungespeichert", "Drei"]
    assert _texts(_body(srv, 2)) == ["Ziel", "Zwei"]
    a, b = srv.move_log[-1]
    assert a == 3 and b["ids"] == ["p2"] and b["to"] == 2 and isinstance(b["rev_a"], int)
    pump(lambda: "p2" not in [x["id"] for x in v.doc.root], 4)
    # Rueckgaengig: Gegenrichtung hinter den alten Vorgaenger
    v._undo_move(2, ["p2"], 3, {"after": "p1"})
    assert pump(lambda: "Zwei" in srv.notes[3]["body"], 5)
    idle()
    assert _texts(_body(srv, 3)) == ["ungespeichert", "Zwei", "Drei"]
    assert srv.move_log[-1] == (2, {"ids": ["p2"], "to": 3, "rev_a": srv.notes[2]["rev"] - 1, "after": "p1"})


def test_move_conflict_retry_and_errors(win, srv):
    from hlidskjalf.store import store
    srv.v25 = True
    store.check_version()
    assert pump(lambda: store.has("move1"), 4)
    _three(srv)
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched)
    srv.notes[3]["rev"] += 1                                         # woanders geaendert -> 409, neu laden, noch einmal
    v.move_blocks(["p3"], 2)
    assert pump(lambda: "Drei" in (srv.notes[2].get("body") or ""), 6)
    idle()
    assert len([1 for a, b in srv.move_log if a == 3]) == 2
    toasts = []
    win.toast = toasts.append
    srv.move_fail = 507
    v.move_blocks(["p1"], 2)
    assert pump(lambda: toasts, 4) and "storage" in toasts[-1]
    srv.move_fail = None


# --- 7. Zeitleiste ziehen ---------------------------------------------------------------------------------------------------
def test_timeline_shift_rules():
    import datetime as dt
    from zoneinfo import ZoneInfo
    from hlidskjalf import dbvalues as dv
    B = ZoneInfo("Europe/Berlin")
    assert dv.shift_value("2026-10-28", 2) == "2026-10-30"
    assert dv.shift_value("2026-10-28/2026-10-30", 1) == "2026-10-29/2026-10-31"
    assert dv.shift_value("2026-10-28/2026-10-30", 0, end_days=3) == "2026-10-28/2026-11-02"   # Ende ziehen
    assert dv.shift_value("2026-10-28/2026-10-30", 0, end_days=-5) == "2026-10-28"             # nie vor dem Beginn
    assert dv.shift_value("2026-10-28", 0, end_days=2) == "2026-10-28/2026-10-30"              # eintaegig -> Zeitraum
    # mit Uhrzeit: Uhrzeit bleibt, Offset des neuen Tages (Sommerzeit-Ende 25.10.)
    assert dv.shift_value("2026-10-24T09:30+02:00", 2, viewer=B) == "2026-10-26T09:30+01:00"
    assert dv.shift_value("2026-10-28T09:00+01:00/2026-10-28T11:00+01:00", 1, viewer=B) == \
        "2026-10-29T09:00+01:00/2026-10-29T11:00+01:00"
    sp, ep = {"id": "s", "type": "date"}, {"id": "e", "type": "date"}
    assert dv.timeline_patch(sp, ep, "2026-10-28", "2026-10-30", 2) == {"s": "2026-10-30", "e": "2026-11-01"}
    assert dv.timeline_patch(sp, ep, "2026-10-28", "2026-10-30", 2, end_only=True) == {"e": "2026-11-01"}
    assert dv.timeline_patch(sp, None, "2026-10-28/2026-10-30", None, -1) == {"s": "2026-10-27/2026-10-29"}
    assert dv.timeline_patch({"id": "c", "type": "created"}, None, "2026-10-28", None, 1) == {}
    assert dv.timeline_patch(sp, None, "2026-10-28", None, 1, full=False) == {"s": "2026-10-29"}
    assert dt.date(2026, 10, 28)


def test_timeline_drop_patches_both_dates(win, srv):
    from hlidskjalf.database import DBModel
    from hlidskjalf.dbviews25 import TimelineView
    from hlidskjalf.store import store
    srv.v25 = True
    srv.extra_features = ["timeline1"]
    store.check_version()
    assert pump(lambda: store.has("timeline1"), 4)
    sc = srv.notes[10]["schema"]
    sc["props"].append({"id": "en", "name": "Ende", "type": "date"})
    sc["views"].append({"id": "tl", "name": "Gantt", "type": "timeline", "date_by": "dt", "end_by": "en", "scale": "day"})
    srv.notes[11]["props"]["en"] = "2026-10-16"
    m = DBModel(10)
    m.load()
    assert pump(lambda: m.rows, 4)
    view = next(v for v in m.all_views() if v.get("id") == "tl")
    tv = TimelineView(win, m, view)
    assert tv.can_drag()
    r = next(x for x in m.rows if x["id"] == 11)
    assert tv.drop(r, 3) == {"dt": "2026-10-17", "en": "2026-10-19"}
    assert pump(lambda: srv.notes[11]["props"].get("dt") == "2026-10-17", 4)
    idle()
    assert srv.notes[11]["props"]["en"] == "2026-10-19"
    p = [b for mm, u, b, c in srv.log if mm == "PATCH" and u.endswith("/api/notes/11")][-1]
    assert p == {"props": {"dt": "2026-10-17", "en": "2026-10-19"}}                  # ein Aufruf, beide Daten
    assert tv.drop(r, 2, end_only=True) == {"en": "2026-10-21"}


# --- 8. API-Tokens (nur auflisten/widerrufen) ---------------------------------------------------------------------------------
def test_api_tokens_list_and_revoke(win, srv):
    from hlidskjalf.settings import ApiTokensGroup
    from hlidskjalf.store import store
    srv.v25 = True
    srv.extra_features = ["api1"]
    srv.api_tokens = [{"id": 1, "name": "Skript", "prefix": "sga_abcd", "expires": None, "last_used": None, "revoked": None},
                      {"id": 2, "name": "Alt", "prefix": "sga_old0", "revoked": 5.0}]
    store.check_version()
    assert pump(lambda: store.has("api1"), 4)
    g = ApiTokensGroup(win)
    assert pump(lambda: g.get_visible() and g.rows, 3)
    assert [r.get_title() for r in g.rows] == ["Skript"]               # widerrufene nicht
    dlg = g._revoke(srv.api_tokens[0])
    dlg.emit("response", "revoke")
    dlg.force_close()
    assert pump(lambda: srv.api_tokens[0]["revoked"], 3)
    assert pump(lambda: [r.get_title() for r in g.rows] == ["No API tokens"], 3)
