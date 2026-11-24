# 2.6 (Bugs-DB 2031): leere Zustaende und "Erste Schritte" - Bildschirmfotos mit nachgebautem Server, ohne Netz.
#   HK_TEST=tests/firststeps2031shots.py HK_OUT=<ordner> HK_SHOT_PREFIX=nachher- python3 -m hlidskjalf.main
# Dateien <prefix>heute-leer, aufgaben-leer, datenbank-leer, frage, einstellungen-hilfe (.png). Laeuft auch mit aelteren
# Staenden der App (Vorher-Bilder): fehlt etwas, entsteht das Bild trotzdem.
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockserver  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
S = {}


def check(name, ok):
    print(f"{'OK  ' if ok else 'FEHLER'} {name}", flush=True)


def shoot(g, name):
    g["shot"](os.path.join(g["out"], f"{P}{name}.png"))


def _walk(w):
    c = w.get_first_child()
    while c:
        yield c
        yield from _walk(c)
        c = c.get_next_sibling()


def _empties(w):
    return [x for x in _walk(w) if x.has_css_class("hk-emptystate")]


def start(g):
    s = S["srv"] = mockserver.install()
    s.tasks, s.done_tasks = [], []
    s.cal_events, s.cal_accounts = [], []                  # kein Kalender verbunden
    s.first_steps = None                                   # Frage erst fuer ihr eigenes Bild
    g["win"].set_default_size(1100, 760)
    g["store"].logged_in()
    g["win"].show_root(("today",))


def today(g):
    shoot(g, "heute-leer")
    if P.startswith("nachher"):
        check("Heute: zwei leere Zustaende", len(_empties(g["win"].current)) == 2)
    g["win"].show_root(("tasks",))


def tasks(g):
    shoot(g, "aufgaben-leer")
    if P.startswith("nachher"):
        check("Aufgaben: leerer Zustand", len(_empties(g["win"].current)) == 1)
    s = S["srv"]
    s.notes.pop(11, None)
    s.notes.pop(12, None)
    g["win"].show_root(("page", 10))


def database(g):
    shoot(g, "datenbank-leer")
    if P.startswith("nachher"):
        check("Datenbank: leerer Zustand", len(_empties(g["win"].current)) >= 1)
    g["win"].show_root(("today",))
    S["srv"].first_steps = {"state": None}
    try:
        from hlidskjalf.firststeps import first_steps
    except ImportError:
        return
    first_steps.reset()
    g["win"].first_steps_check(force=True)


def question(g):
    shoot(g, "frage")
    d = g["win"].get_visible_dialog()
    if P.startswith("nachher"):
        check("Frage sichtbar", d is not None and d.get_heading() == "Möchtest du eine kurze Einführung?"
              if os.environ.get("LANG", "").startswith("de") else d is not None)
    if d is not None:
        d.force_close()
    s = S["srv"]                                           # Seite angelegt, zwei Punkte erledigt
    s._first_steps("POST", {"answer": "yes"})
    s.first_steps["done"] = {"avatar", "page"}
    try:
        from hlidskjalf.firststeps import first_steps
        first_steps.reset()
    except ImportError:
        pass
    g["win"].settings_dialog(page="info")


def scroll_info(g):
    from gi.repository import Gtk
    d = g["win"].get_visible_dialog()
    first = getattr(d.pages["info"], "first", None)
    for sw in [w for w in _walk(d.pages["info"]) if isinstance(w, Gtk.ScrolledWindow)]:
        a = sw.get_vadjustment()
        if first is not None and first.get_visible():     # Abschnitt "Erste Schritte" oben ins Bild
            ok, r = first.compute_bounds(sw.get_child())
            a.set_value(max(0, r.get_y() - 120) if ok else a.get_upper())
        else:
            a.set_value(a.get_upper() - a.get_page_size())


def settings(g):
    shoot(g, "einstellungen-hilfe")
    d = g["win"].get_visible_dialog()
    first = getattr(d.pages["info"], "first", None)
    if P.startswith("nachher"):
        check("Einstellungen: Erste Schritte sichtbar", first is not None and first.get_visible())


steps = [(300, start), (2000, today), (1200, tasks), (1800, database), (1500, question), (1500, scroll_info), (700, settings)]
