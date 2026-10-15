"""QA-Runde 2: Konflikt-Dialog (Glossar), "Server-Fassung laden" mit lokaler Kopie, Esc = vertagen,
"Beide behalten" ohne Zeichenverlust, Offline-Hinweis, Kopplungs-Dialog."""
import json

from hk_helpers import idle, note_texts, pump


def _type(doc, text, i=0):
    doc.root[i]["data"]["text"] = text
    doc.mark_dirty()


def _conflicting_doc(srv, nid=3, mine="Meine Fassung", theirs="Server-Fassung"):
    """Seite laden, woanders aendern (Server: neue Revision), hier tippen + speichern -> Konflikt offen"""
    from hlidskjalf.editor import PageDoc
    d = PageDoc(nid)
    seen = []
    d.on_conflict = lambda doc, p: seen.append(p)
    d.load()
    assert pump(lambda: d.fetched)
    srv.notes[nid]["body"] = json.dumps({"blocks": [{"id": "x", "type": "paragraph", "data": {"text": theirs}}]})
    srv.notes[nid]["rev"] += 1
    _type(d, mine)
    d.save()
    assert pump(lambda: seen, 6), "Konflikt wurde nicht gemeldet"
    return d


def test_theirs_keeps_mine_as_local_copy(srv):
    """N7: "Server-Fassung laden" sichert die eigene Fassung IMMER vorher als lokale Kopie; Wiederherstellen speichert sie"""
    from hlidskjalf import drafts
    d = _conflicting_doc(srv)
    d.resolve("theirs")
    idle()
    assert d.conflict is None and not d.dirty
    assert d.root[0]["data"]["text"] == "Server-Fassung"            # Server-Fassung angezeigt
    assert note_texts(srv, 3) == ["Server-Fassung"]                  # nichts ueberschrieben
    cps = d.local_copies()
    assert len(cps) == 1 and cps[0][1]["doc"]["blocks"][0]["data"]["text"] == "Meine Fassung"
    assert drafts.read(3) is None                                    # Arbeits-Entwurf weg, Kopie bleibt
    # Wiederherstellen: ersetzt den angezeigten Stand und speichert ihn
    assert d.restore_copy(cps[0][0])
    assert pump(lambda: note_texts(srv, 3) == ["Meine Fassung"], 6)
    idle()
    assert d.local_copies() == [] and not d.dirty


def test_discard_local_copy(srv):
    d = _conflicting_doc(srv)
    d.resolve("theirs")
    idle()
    (p, _), = d.local_copies()
    d.discard_copy(p)
    assert d.local_copies() == []
    assert note_texts(srv, 3) == ["Server-Fassung"]


def test_later_overwrites_nothing(srv):
    """N7: Dialog schliessen (Esc) = vertagen: nichts ueberschreiben, Konflikt bleibt offen, Eingabe bleibt"""
    d = _conflicting_doc(srv)
    d.resolve("later")
    idle()
    assert d.conflict is not None and d.dirty
    assert d.root[0]["data"]["text"] == "Meine Fassung"
    assert note_texts(srv, 3) == ["Server-Fassung"]
    d.save()                                                         # weiter tippen/speichern: bleibt Entwurf
    idle()
    assert note_texts(srv, 3) == ["Server-Fassung"]
    from hlidskjalf import drafts
    assert drafts.read(3)["doc"]["blocks"][0]["data"]["text"] == "Meine Fassung"


def test_keep_both_keeps_typed_chars(srv):
    """"Beide behalten" (Konfliktkopie auf dem Server): waehrenddessen weiter getippte Zeichen gehen nicht verloren"""
    d = _conflicting_doc(srv)
    srv.delay["POST api/notes"] = 0.6
    d.resolve("copy")
    pump(None, 0.2)
    _type(d, "Meine Fassung + WEITER GETIPPT")                       # waehrend die Kopie angelegt wird
    assert pump(lambda: d.conflict is None, 8)
    idle()
    copy = next(n for n in srv.notes.values() if (n.get("title") or "").endswith("(conflict copy)"))
    assert "Meine Fassung" in copy["body"]
    texts = [c[1]["doc"]["blocks"][0]["data"]["text"] for c in d.local_copies()]
    assert texts == ["Meine Fassung + WEITER GETIPPT"], texts
    assert note_texts(srv, 3) == ["Server-Fassung"]


def test_offline_hint_once(srv):
    """Offline-Hinweis (Glossar) einmal, nicht bei jedem weiteren Versuch"""
    from hlidskjalf.editor import PageDoc
    d = PageDoc(3)
    d.load()
    pump(lambda: d.fetched)
    srv.fail["PATCH api/notes/3"] = 0
    errs = []
    d.on_error = errs.append
    for t in ("eins", "zwei"):
        _type(d, t)
        d.save()
        idle()
    assert errs == ["Offline – changes will be saved as soon as the server is reachable."]


def _dialog(win):
    return win.get_visible_dialog() if hasattr(win, "get_visible_dialog") else None


def _conflict_in_view(win, srv, nid):
    win.show_root(("page", nid))
    v = win.current
    assert pump(lambda: v.doc.fetched)
    srv.notes[nid]["rev"] += 1
    srv.notes[nid]["title"] = "Woanders umbenannt"
    v.doc.page["title"] = "Hier umbenannt"
    v.editor.text_changed()
    v.editor.flush()
    assert pump(lambda: _dialog(win) is not None, 6), "kein Konflikt-Dialog"
    return v, _dialog(win)


def test_conflict_dialog_texts_and_esc_on_database(win, srv):
    """N6 + N7: Texte nach Glossar; Esc bei einer Datenbankseite waehlt NICHT still "meine" - Hinweis bleibt"""
    v, d = _conflict_in_view(win, srv, 10)
    assert d.get_heading() == "Page changed elsewhere"
    assert d.get_body() == "This page was changed on another device in the meantime. Your changes won't be lost."
    assert d.get_response_label("mine") == "Keep my version"
    assert d.get_response_label("theirs") == "Load server version (save mine as a copy)"
    assert d.get_close_response() == "later"
    d.close()                                                        # wie Esc
    pump(None, 0.3)
    idle()
    assert srv.notes[10]["title"] == "Woanders umbenannt"            # nichts ueberschrieben
    assert v.doc.conflict is not None and v.doc.title_raw == "Hier umbenannt"
    assert v.conflict_banner.get_revealed()
    # spaeter entscheiden: Banner -> Dialog -> Server-Fassung laden (meine als lokale Kopie)
    v.conflict_banner.emit("button-clicked")
    pump(None, 0.2)
    d2 = _dialog(win)
    assert d2 is not None
    d2.emit("response", "theirs")
    d2.force_close()
    pump(None, 0.2)
    assert v.doc.conflict is None and v.doc.title_raw == "Woanders umbenannt"
    assert not v.conflict_banner.get_revealed()
    assert v.copy_rev.get_reveal_child()                             # Entwurf-Banner bietet meine Fassung an
    assert v.copy_label.get_label().startswith("Unsaved changes from ")
    v._copy_action(True)                                             # Wiederherstellen
    assert pump(lambda: srv.notes[10]["title"] == "Hier umbenannt", 6)
    idle()
    assert not v.copy_rev.get_reveal_child()


def test_pair_dialog_texts(win, srv):
    """N6: Kopplungs-Dialog nach Glossar"""
    win.open_link("hlidskjalf://pair?u=https://neu.example&c=" + "x" * 24)
    pump(None, 0.3)
    d = _dialog(win)
    assert d is not None
    assert d.get_heading() == "Pair with this server?"
    assert d.get_body().startswith("Saga will sign in to neu.example.")
    assert d.get_response_label("pair") == "Pair"
    d.force_close()


def test_conflict_dialog_waits_for_typing_pause(win, srv):
    """BUG-027: mitten im Tippen geht kein Dialog auf (nur der Hinweis); danach ist "Meine Fassung behalten" nicht die
    Standardantwort und die Knoepfe sind kurz gesperrt - ein gerade getippter Tastendruck entscheidet nichts"""
    from gi.repository import GLib
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched)
    srv.notes[3]["rev"] += 1
    srv.notes[3]["title"] = "Woanders umbenannt"
    v.doc.page["title"] = "Hier umbenannt"
    v.editor.text_changed()
    v.editor.flush()
    assert pump(lambda: v.doc.conflict is not None, 6)
    t0 = GLib.get_monotonic_time()
    while GLib.get_monotonic_time() - t0 < 1_500_000:            # weiter tippen
        v.on_typed()
        pump(None, 0.2)
        assert _dialog(win) is None, "Dialog mitten im Tippen"
        assert v.conflict_banner.get_revealed()
    assert pump(lambda: _dialog(win) is not None, 5), "kein Dialog nach der Tipp-Pause"
    d = _dialog(win)
    assert d.get_default_response() != "mine"
    assert not d.get_response_enabled("mine") and not d.get_response_enabled("theirs")
    pump(None, 1.2)
    assert d.get_response_enabled("mine") and d.get_response_enabled("theirs")
    assert srv.notes[3]["title"] == "Woanders umbenannt"             # nichts ueberschrieben
    d.close()
    pump(None, 0.3)


def test_open_conflict_one_banner_and_reload_opens_dialog(win, srv):
    """BUG-079: bei offenem Konflikt kein zweiter Hinweis; "Neu laden" fuehrt zur Fassungswahl mit der neuesten Fassung"""
    v, d = _conflict_in_view(win, srv, 3)
    d.close()                                                        # Esc: spaeter
    pump(None, 0.3)
    assert v.conflict_banner.get_revealed()
    srv.notes[3]["rev"] += 1
    srv.notes[3]["title"] = "Noch einmal woanders"
    v.live_changed({"id": 3, "rev": srv.notes[3]["rev"], "by": {"name": "Anna"}})
    assert pump(lambda: (v.doc.conflict or {}).get("rev") == srv.notes[3]["rev"], 6)
    idle()
    assert not v.changed_banner.get_revealed()
    assert v.conflict_banner.get_revealed()
    v.changed_banner.set_revealed(True)                              # aelterer Hinweis noch sichtbar: Neu laden
    v._reload_from_banner()
    pump(None, 0.3)
    assert _dialog(win) is not None
    assert not v.changed_banner.get_revealed()
    _dialog(win).close()
    pump(None, 0.3)


def test_page_too_large_explained_once_and_recovers(win, srv):
    """BUG-081: 413 (Seite zu gross) wird einmal erklaert, bleibt als Hinweis an der Seite und beim Schliessen; nach dem
    Kuerzen speichert die Seite wieder"""
    win.show_root(("page", 3))
    v = win.current
    assert pump(lambda: v.doc.fetched)
    errs = []
    v.doc.on_error = errs.append
    srv.fail["PATCH api/notes/3"] = 413
    try:
        for t in ("sehr lang 1", "sehr lang 2"):
            _type(v.doc, t)
            v.doc.save()
            idle()
        assert len(errs) == 1 and "too large to save" in errs[0]
        assert v.doc.too_large and v.doc.dirty
        v.update_banners()
        assert v.large_banner.get_revealed()
    finally:
        srv.fail.pop("PATCH api/notes/3", None)
    _type(v.doc, "gekuerzt")
    v.doc.save()
    assert pump(lambda: note_texts(srv, 3) == ["gekuerzt"], 6)
    idle()
    assert not v.doc.too_large and not v.large_banner.get_revealed()
