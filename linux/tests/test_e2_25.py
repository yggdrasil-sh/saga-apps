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
