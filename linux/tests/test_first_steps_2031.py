"""2.6 (Bugs-DB 2031): leere Zustaende mit naechstem Schritt und "Erste Schritte" (GET/POST api/first-steps) - ohne Netz.
Antworten (enabled false, 404, ask), Drosselung, Frage (Ja/Nein, nicht ueber anderen Dialogen), Einstellungen > Info,
leere Aufgaben/Heute/Termine/Datenbank/Seitenleiste."""
import json

from gi.repository import Adw, Gtk

from hk_helpers import idle, pump


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _empties(w):
    return [x for x in _walk(w) if x.has_css_class("hk-emptystate")]


def _texts(box):
    return [x.get_label() for x in _walk(box) if isinstance(x, Gtk.Label)]


def _fs_reqs(srv, method=None):
    return [(m, b) for m, u, b, c in srv.log if u.endswith("/api/first-steps") and (method is None or m == method)]


# --- Antworten (rein) -------------------------------------------------------------------------------------------------
def test_parse_answers():
    from hlidskjalf.firststeps import parse, progress
    d = parse({"enabled": True, "ask": True, "state": None, "page": None,
               "items": [{"key": "avatar", "done": True}, {"key": "task", "done": False}, {"key": "", "done": True}, "x", {"done": 1}]})
    assert d == {"enabled": True, "ask": True, "state": None, "page": None,
                 "items": [{"key": "avatar", "done": True}, {"key": "task", "done": False}]}
    assert progress(d) == (1, 2)
    # abgeschaltet: nichts fragen, keine Seite, keine Punkte
    off = parse({"enabled": False, "ask": True, "state": "yes", "page": 7, "items": [{"key": "app", "done": True}]})
    assert off["enabled"] is False and off["ask"] is False and off["page"] is None and off["items"] == []
    # unbrauchbar bzw. aelterer Server (leere Antwort) -> None
    for junk in (None, {}, [], "x", {"enabled": "yes"}, {"enabled": 1}):
        assert parse(junk) is None, junk
    # Seite nur als positive Zahl, Zustand nur yes/no, ask nur echt true
    d = parse({"enabled": True, "ask": "true", "state": "vielleicht", "page": True, "items": "kaputt"})
    assert d["ask"] is False and d["state"] is None and d["page"] is None and d["items"] == []
    assert parse({"enabled": True, "page": 12, "state": "yes"})["page"] == 12
    assert progress(None) == (0, 0)


def test_throttle_404_and_force(srv):
    from hlidskjalf.firststeps import MIN_GAP, FirstSteps
    now = [1000.0]
    fs = FirstSteps(clock=lambda: now[0])
    got = []
    assert fs.check(done=got.append)                       # erster Abruf (aelterer Server: 404)
    assert pump(lambda: got, 3) and got == [None] and fs.supported is False and fs.st is None
    assert not fs.check()                                  # innerhalb der Minute: kein Abruf
    now[0] += MIN_GAP - 1
    assert not fs.check()
    now[0] += 2
    srv.first_steps = {"state": None}
    assert not fs.check()                                  # aelterer Server (404): ohne force nicht wieder fragen
    assert fs.check(force=True, done=got.append)           # naechster Start: wieder
    assert pump(lambda: len(got) == 2, 3)
    assert fs.supported is True and fs.st["ask"] is True and fs.st["page"] is None
    assert [k["key"] for k in fs.st["items"]] == ["avatar", "chat", "app", "page", "task"]
    assert not fs.check()
    now[0] += MIN_GAP + 1
    assert fs.check()                                      # Server kennt es jetzt: nach einer Minute wieder
    pump(lambda: not fs.busy, 3)
    assert fs.check(force=True)                            # Start: immer
    assert not fs.check(force=True)                        # laeuft noch: nicht doppelt
    pump(lambda: not fs.busy, 3)
    assert len(_fs_reqs(srv, "GET")) == 4
    # anderer Server/anderes Konto: Antwort eines alten Abrufs wird verworfen
    now[0] += MIN_GAP + 1
    fs.check()
    fs.reset()
    pump(None, 0.3)
    assert fs.st is None and fs.supported is None and not fs.busy


def test_answer_yes_no_and_403(srv):
    from hlidskjalf.firststeps import FirstSteps
    fs = FirstSteps()
    srv.first_steps = {"state": None}
    res, errs = [], []
    fs.answer(False, done=res.append, error=errs.append)
    assert pump(lambda: res, 3) and res[0] == {"ok": True} and srv.first_steps["state"] == "no"
    assert fs.st["state"] == "no" and fs.st["ask"] is False and _fs_reqs(srv, "POST")[-1][1] == {"answer": "no"}
    fs.answer(True, done=res.append, error=errs.append)
    assert pump(lambda: len(res) == 2, 3)
    pid = res[1]["page"]
    assert pid in srv.notes and srv.notes[pid]["title"] == "Erste Schritte" and fs.st["page"] == pid and fs.st["state"] == "yes"
    fs.answer(True, done=res.append)                       # Seite vorhanden: dieselbe
    assert pump(lambda: len(res) == 3, 3) and res[2]["page"] == pid
    srv.first_steps["enabled"] = False                     # Admin hat abgeschaltet: 403 als Fehler
    fs.answer(True, done=res.append, error=errs.append)
    assert pump(lambda: errs, 3) and errs[0].code == 403 and "abgeschaltet" in str(errs[0])


# --- Frage im Fenster -------------------------------------------------------------------------------------------------
def _ask_ready(win, srv, **fs):
    from hlidskjalf.firststeps import first_steps
    srv.first_steps = dict({"state": None}, **fs)
    first_steps.reset()
    win.first_steps_check(force=True)


def _dialog(win):
    d = win.get_visible_dialog()
    return d if isinstance(d, Adw.AlertDialog) else None


def test_ask_yes_opens_page(win, srv):
    _ask_ready(win, srv)
    assert pump(lambda: _dialog(win) is not None, 4)
    d = _dialog(win)
    assert d.get_heading() == "Would you like a short introduction?"
    assert d.get_response_label("yes") == "Yes, please" and d.get_response_label("no") == "No, thanks"
    assert d.get_default_response() == "yes" and d.get_close_response() == "close"
    d.emit("response", "yes")
    d.force_close()
    assert pump(lambda: srv.first_steps.get("page") and win.route == ("page", srv.first_steps["page"]), 5)
    from hlidskjalf.store import store
    assert srv.first_steps["page"] in store.by_id                      # in der Seitenliste
    # nicht noch einmal fragen (Server sagt ask false, auch nach erneutem Abruf)
    win.first_steps_check(force=True)
    pump(None, 0.5)
    assert _dialog(win) is None
    idle()


def test_ask_no_posts_no(win, srv):
    _ask_ready(win, srv)
    assert pump(lambda: _dialog(win) is not None, 4)
    d = _dialog(win)
    d.emit("response", "no")
    d.force_close()
    assert pump(lambda: srv.first_steps.get("state") == "no", 3)
    assert not srv.first_steps.get("page") and _fs_reqs(srv, "POST")[-1][1] == {"answer": "no"}
    assert win.route[0] != "page" or win.route[1] in (1, 2, 3, 10, 11, 12)


def test_escape_saves_nothing(win, srv):
    """Escape/Schliessen ist keine Antwort: kein POST, Zustand bleibt offen (wie Web, iOS, Android)"""
    _ask_ready(win, srv)
    assert pump(lambda: _dialog(win) is not None, 4)
    d = _dialog(win)
    n = len(_fs_reqs(srv, "POST"))
    d.emit("response", "close")
    d.force_close()
    pump(None, 0.8)
    assert len(_fs_reqs(srv, "POST")) == n and srv.first_steps.get("state") in (None, "")


def test_no_ask_when_disabled_older_or_answered(win, srv):
    from hlidskjalf.firststeps import first_steps
    for fs in ({"enabled": False}, None, {"state": "no"}, {"state": "yes", "ask": False}):
        srv.first_steps = fs
        first_steps.reset()
        win.first_steps_check(force=True)
        pump(lambda: not first_steps.busy, 3)
        pump(None, 0.3)
        assert _dialog(win) is None, fs
    assert first_steps.st is not None and first_steps.st["ask"] is False


def test_ask_waits_for_other_dialogs(win, srv):
    other = Adw.AlertDialog(heading="Anderer Dialog")
    other.add_response("ok", "OK")
    other.present(win)
    pump(None, 0.2)
    _ask_ready(win, srv)
    pump(None, 1.0)
    assert win.get_visible_dialog() is other               # nicht ueber anderen Dialogen
    other.force_close()
    assert pump(lambda: _dialog(win) is not None and _dialog(win).get_heading() == "Would you like a short introduction?", 5)
    _dialog(win).force_close()


def test_activation_is_throttled(win, srv):
    from hlidskjalf.firststeps import first_steps
    _ask_ready(win, srv, state="no")
    pump(lambda: not first_steps.busy, 3)
    n = len(_fs_reqs(srv, "GET"))
    for _ in range(3):                                     # Wechsel in den Vordergrund: hoechstens jede Minute
        win._activated()
        pump(None, 0.1)
    assert len(_fs_reqs(srv, "GET")) == n
    first_steps.last -= 61
    win._activated()
    assert pump(lambda: len(_fs_reqs(srv, "GET")) == n + 1, 3)


# --- Einstellungen > Info > Erste Schritte ----------------------------------------------------------------------------
def test_settings_section(win, srv):
    from hlidskjalf.firststeps import first_steps
    from hlidskjalf.settings import FirstStepsGroup, SettingsDialog
    srv.first_steps = {"state": "no"}
    first_steps.reset()
    d = SettingsDialog(win)
    d.present(win)
    d.show_page("info")
    g = next(w for w in _walk(d.pages["info"]) if isinstance(w, FirstStepsGroup))
    assert pump(lambda: g.get_visible(), 3)
    assert g.get_title() == "First steps"
    assert g.row.get_title().startswith("A page with a short checklist") and g.btn.get_label() == "Create first steps"
    g.btn.emit("clicked")                                  # legt die Seite an und oeffnet sie
    assert pump(lambda: srv.first_steps.get("page") and win.route == ("page", srv.first_steps["page"]), 5)
    assert win.get_visible_dialog() is None
    pid = srv.first_steps["page"]
    srv.first_steps["done"] = {"avatar", "task"}
    first_steps.reset()
    d = SettingsDialog(win)
    d.present(win)
    d.show_page("info")
    g = next(w for w in _walk(d.pages["info"]) if isinstance(w, FirstStepsGroup))
    assert pump(lambda: g.btn.get_label() == "Open first steps", 3)
    assert g.row.get_title() == "Your “First steps” page: 2 of 5 done."
    win.show_root(("today",))
    n = len(srv.notes)
    g.btn.emit("clicked")
    assert pump(lambda: win.route == ("page", pid), 5)
    assert n == len(srv.notes)                             # keine zweite Seite
    # abgeschaltet: Abschnitt unsichtbar
    srv.first_steps["enabled"] = False
    first_steps.reset()
    d = SettingsDialog(win)
    d.present(win)
    d.show_page("info")
    g = next(w for w in _walk(d.pages["info"]) if isinstance(w, FirstStepsGroup))
    pump(lambda: first_steps.st is not None, 3)
    assert not g.get_visible()
    d.force_close()
    # aelterer Server (404): ebenso
    srv.first_steps = None
    first_steps.reset()
    d = SettingsDialog(win)
    d.present(win)
    g = next(w for w in _walk(d.pages["info"]) if isinstance(w, FirstStepsGroup))
    pump(lambda: first_steps.supported is False, 3)
    assert not g.get_visible()
    d.force_close()


def test_settings_section_error_is_shown(win, srv):
    from hlidskjalf.firststeps import first_steps
    from hlidskjalf.settings import FirstStepsGroup, SettingsDialog
    srv.first_steps = {"state": "no"}
    first_steps.reset()
    d = SettingsDialog(win)
    d.present(win)
    d.show_page("info")
    g = next(w for w in _walk(d.pages["info"]) if isinstance(w, FirstStepsGroup))
    assert pump(lambda: g.get_visible(), 3)
    srv.fail["POST api/first-steps"] = 500
    g.btn.emit("clicked")
    assert pump(lambda: g.btn.get_sensitive() and not g.busy, 3)  # Fehler: Knopf wieder frei, Dialog bleibt
    assert win.get_visible_dialog() is d
    d.force_close()


# --- leere Zustaende ----------------------------------------------------------------------------------------------------
def test_tasks_empty_state_and_focus(win, srv):
    from hlidskjalf.store import store
    srv.tasks, srv.done_tasks = [], []
    store.load_today()
    win.show_root(("tasks",))
    tl = win.current.task_list
    assert pump(lambda: _empties(tl), 3)
    e = _empties(tl)[0]
    assert "Your tasks appear here" in _texts(e)[0] and e.button.get_label() == "Write down a task"
    assert e.button.measure(Gtk.Orientation.VERTICAL, -1)[0] >= 44     # Touch-Ziel (mindestens 44 px hoch)
    e.button.emit("clicked")
    assert pump(lambda: tl.entry.has_focus() or tl.entry.get_focus_child() is not None or tl.entry.is_focus(), 2)
    # nur erledigte: wie bisher "Alles erledigt"
    srv.done_tasks = [{"id": 9, "text": "Fertig", "done": True}]
    store.load_today()
    assert pump(lambda: not _empties(tl) and "All done 🎉" in _texts(tl), 3)


def test_today_empty_states(win, srv):
    from hlidskjalf.store import store
    srv.tasks, srv.done_tasks, srv.cal_events, srv.cal_accounts = [], [], [], []
    win.show_root(("today",))
    store.load_today()
    v = win.current
    assert pump(lambda: len(_empties(v)) == 2, 4)
    tasks_e, cal_e = _empties(v)
    assert tasks_e.button.get_label() == "Write down a task"
    assert cal_e.button.get_label() == "Connect a calendar" and cal_e.button.has_css_class("hk-empty-need")
    # GNOME HIG: nur eine hervorgehobene Aktion je Ansicht - die Voraussetzung ist eine ruhige Pille
    assert tasks_e.button.has_css_class("suggested-action") and not cal_e.button.has_css_class("suggested-action")
    assert cal_e.button.has_css_class("pill") and cal_e.button.measure(Gtk.Orientation.VERTICAL, -1)[0] >= 44
    assert "Nothing planned 🎉" not in _texts(v)
    tasks_e.button.emit("clicked")                          # zu Aufgaben, mit Zurueck nach Heute
    assert pump(lambda: win.route == ("tasks",), 2) and len(win.history) == 2
    win.go_back()
    v = win.current
    assert pump(lambda: len(_empties(v)) == 2, 3)
    _empties(v)[1].button.emit("clicked")                   # Einstellungen: Kalender
    from hlidskjalf.settings import SettingsDialog
    assert pump(lambda: isinstance(win.get_visible_dialog(), SettingsDialog), 2)
    d = win.get_visible_dialog()
    assert d.get_visible_page() is d.pages["settings"]
    assert pump(lambda: d.calendar.connect_btn is not None and d.calendar.connect_btn.has_focus(), 3)
    d.force_close()
    # Kalender verbunden, Aufgaben vorhanden: keine leeren Zustaende
    srv.cal_accounts = [{"id": 1, "label": "Arbeit", "calendars": []}]
    srv.tasks = [{"id": 1, "text": "Einkaufen", "prio": 2}]
    store.load_cal_accounts()
    store.load_today()
    assert pump(lambda: not _empties(win.current), 3)


def test_events_screen_without_calendar(win, srv):
    from hlidskjalf.store import store
    srv.cal_events, srv.cal_accounts = [], []
    win.show_root(("events",))
    el = next(w for w in _walk(win.current) if type(w).__name__ == "EventList")
    assert pump(lambda: _empties(el), 3)
    assert _empties(el)[0].button.get_label() == "Connect a calendar" and not el.add_btn.get_visible()
    assert "Events from your calendars appear here." in _texts(el)
    srv.cal_accounts = [{"id": 1, "label": "Arbeit", "calendars": []}]
    store.load_cal_accounts()
    assert pump(lambda: not _empties(el) and "No events" in _texts(el) and el.add_btn.get_visible(), 3)


def _dbview(win, nid):
    from hlidskjalf.database import DatabaseView
    win.show_root(("page", nid))
    v = win.current
    assert pump(lambda: any(isinstance(w, DatabaseView) and w.m.loaded for w in _walk(v)), 5)
    idle()
    return next(w for w in _walk(v) if isinstance(w, DatabaseView))


def test_database_empty_and_filtered(win, srv):
    del srv.notes[11], srv.notes[12]
    dv = _dbview(win, 10)
    assert pump(lambda: _empties(dv), 3)
    e = _empties(dv)[0]
    assert _texts(e)[0].startswith("No entries yet. Each entry is a page") and e.button.get_label() == "+ New entry"
    n = len(srv.notes)
    e.button.emit("clicked")
    assert pump(lambda: len(srv.notes) == n + 1, 3)
    idle()


def test_database_filtered_has_no_button(win, srv):
    srv.notes[10]["schema"]["views"][0]["filters"] = [{"pid": "title", "op": "is_empty"}]
    dv = _dbview(win, 10)
    assert pump(lambda: _empties(dv), 3)
    e = _empties(dv)[0]
    assert _texts(e) == ["No entry matches the filters of this view."] and e.button is None


def test_database_readonly_no_button(win, srv):
    from hlidskjalf.store import store
    sc = {"props": [{"id": "st", "name": "Status", "type": "text"}], "views": [{"id": "t", "name": "Tabelle", "type": "table"}]}
    srv.add_foreign(300, "Fremde DB", role="view", kind="database", schema=sc, body="")
    store.refresh()
    idle()
    dv = _dbview(win, 300)
    assert pump(lambda: _empties(dv), 3)
    assert _empties(dv)[0].button is None                    # nur lesen: kein "Neuer Eintrag"


def test_sidebar_first_page_button(win, srv):
    from hlidskjalf.store import store
    srv.notes.clear()
    store.refresh()
    sb = win.sidebar
    assert pump(lambda: [w for w in _walk(sb.box) if isinstance(w, Gtk.Button) and w.has_css_class("hk-first-page")], 4)
    b = next(w for w in _walk(sb.box) if isinstance(w, Gtk.Button) and w.has_css_class("hk-first-page"))
    b.emit("clicked")
    assert pump(lambda: any(m == "POST" and u.endswith("/api/notes") for m, u, bd, c in srv.log), 3)
    idle()


def test_mock_body_is_json(srv):
    """Seite "Erste Schritte" im Schein-Server ist ein gueltiges Block-Dokument (fuer die Bilder)"""
    from hlidskjalf.firststeps import FirstSteps
    srv.first_steps = {"state": None}
    res = []
    FirstSteps().answer(True, done=res.append)
    assert pump(lambda: res, 3)
    blocks = json.loads(srv.notes[res[0]["page"]]["body"])["blocks"]
    assert blocks[1]["data"]["style"] == "checklist" and len(blocks[1]["data"]["items"]) == 5


# --- Pruefer-Auflagen (Leck, veraltete Antworten) -----------------------------------------------------------------------
def test_settings_group_not_held_by_first_steps(win, srv, monkeypatch):
    """Die Gruppe "Erste Schritte" haengt nur waehrend die Seite Info sichtbar ist am globalen first_steps - danach
    haelt first_steps weder Gruppe noch Dialog fest und ruft kein fill() mehr auf.
    (Dass der geschlossene SettingsDialog insgesamt nicht freigegeben wird, war schon vor 2031 so - andere Gruppen,
    Signal-Kreise in PyGObject - und ist hier nicht Thema.)"""
    from gi.repository import GObject
    from hlidskjalf.firststeps import FirstSteps, first_steps
    from hlidskjalf.settings import FirstStepsGroup, SettingsDialog
    sig = GObject.signal_lookup("changed", FirstSteps)

    def pending():
        return GObject.signal_has_handler_pending(first_steps, sig, 0, False)
    srv.first_steps = {"state": "no"}
    first_steps.reset()
    assert not pending()
    d = SettingsDialog(win)
    d.present(win)
    assert not pending()                                   # Info noch nicht gezeigt: keine Verbindung
    d.show_page("info")
    g = next(w for w in _walk(d.pages["info"]) if isinstance(w, FirstStepsGroup))
    assert pump(lambda: g.get_visible() and g._h is not None and pending(), 3)
    d.show_page("account")                                 # Info nicht mehr sichtbar: Verbindung weg
    assert pump(lambda: g._h is None and not pending(), 2)
    d.show_page("info")
    assert pump(lambda: g._h is not None and pending(), 2)
    d.force_close()
    assert pump(lambda: g._h is None and not pending(), 2)
    calls = []
    monkeypatch.setattr(FirstStepsGroup, "fill", lambda self: calls.append(self))
    first_steps.emit("changed")
    assert not calls                                       # alte Gruppe bekommt nichts mehr


def test_answer_after_account_switch_is_dropped(win, srv):
    from hlidskjalf.firststeps import first_steps
    srv.first_steps = {"state": None}
    first_steps.reset()
    srv.delay["POST api/first-steps"] = 0.4
    done, errs = [], []
    first_steps.answer(True, done=done.append, error=errs.append)
    first_steps.reset()                                    # Kontowechsel, waehrend die Antwort unterwegs ist
    pump(None, 1.0)
    assert not done and not errs and first_steps.st is None     # keine Seite des alten Kontos oeffnen
    srv.first_steps["enabled"] = False
    first_steps.answer(True, done=done.append, error=errs.append)
    first_steps.reset()
    pump(None, 1.0)
    assert not errs                                        # auch kein Fehler des alten Kontos
    opened = []
    win.open_first_steps_page = opened.append
    srv.first_steps = {"state": None}
    win._first_steps_answer(True)
    first_steps.reset()
    pump(None, 1.0)
    assert not opened


def test_cal_accounts_stale_answer_dropped(win, srv):
    from hlidskjalf.store import store
    srv.cal_accounts = []
    srv.delay["GET api/settings/calendars"] = 0.4
    store.cal_accounts = None
    store.load_cal_accounts()
    store.forget_account_data()                            # anderes Konto am selben Server
    pump(None, 1.0)
    assert store.cal_accounts is None
    del srv.delay["GET api/settings/calendars"]
    store.load_cal_accounts()
    assert pump(lambda: store.cal_accounts == 0, 3)
