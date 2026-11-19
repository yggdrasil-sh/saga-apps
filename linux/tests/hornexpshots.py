# 2.5 Gjallarhorn experimentell: Einstellungen -> Info -> Hilfe (Zeile mit Marke und Hinweis) - Bildschirmfotos mit
# nachgebautem Server (meldet clientlog1), ohne Netz.
#   HK_TEST=tests/hornexpshots.py HK_OUT=<ordner> python3 -m hlidskjalf.main   (Dateien <HK_SHOT_PREFIX>linux-hilfe[-dunkel].png)
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mockserver  # noqa: E402

from gi.repository import Adw, Gtk  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
S = {}


def check(name, ok):
    print(f"{'OK  ' if ok else 'FEHLER'} {name}", flush=True)


def start(g):
    S["srv"] = mockserver.install()
    g["store"].logged_in()
    g["store"].server_version = "2.5.0"
    g["store"].features = frozenset({"clientlog1"})
    g["win"].show_root(("today",))
    g["win"].set_default_size(1100, 820)


def open_info(g):
    g["store"].features = frozenset({"clientlog1"})          # nach der Versionsabfrage des Nachbaus erneut setzen
    g["win"].settings_dialog(page="account")


def info(g):
    g["win"].get_visible_dialog().show_page("info")


def scroll(g):
    d = g["win"].get_visible_dialog()

    def walk(w):
        c = w.get_first_child()
        while c:
            yield c
            yield from walk(c)
            c = c.get_next_sibling()
    for sw in [w for w in walk(d.pages["info"]) if isinstance(w, Gtk.ScrolledWindow)]:
        a = sw.get_vadjustment()
        a.set_value(a.get_upper() - a.get_page_size())


def shoot(g):
    d = g["win"].get_visible_dialog()
    g["shot"](os.path.join(g["out"], f"{P}linux-hilfe{S.get('suffix', '')}.png"))
    h = getattr(d.pages["info"].diag, "horn", None)
    check("Hilfe: Zeile Gjallarhorn sichtbar", bool(h and h.get_visible()))


def dark(g):
    g["win"].get_visible_dialog().force_close()
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    S["suffix"] = "-dunkel"


steps = [(300, start), (800, open_info), (800, info), (1200, scroll), (800, shoot), (300, dark),
         (300, open_info), (800, info), (1200, scroll), (800, shoot)]
