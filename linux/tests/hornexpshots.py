# Einstellungen -> Info -> Hilfe mit nachgebautem Server (meldet clientlog1), ohne Netz - Bildschirmfotos.
# 2.5 stand hier eine Zeile "Anrufe ueber den Anruf-Knoten" mit Marke "Experimentell" und Hinweis-Satz; seit 2.6 (2263-8)
# ist Gjallarhorn nicht mehr experimentell: die Zeile ist weg, "Diagnose senden" bleibt.
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


def walk(w):
    c = w.get_first_child()
    while c:
        yield c
        yield from walk(c)
        c = c.get_next_sibling()


def scroll(g):
    d = g["win"].get_visible_dialog()
    for sw in [w for w in walk(d.pages["info"]) if isinstance(w, Gtk.ScrolledWindow)]:
        a = sw.get_vadjustment()
        a.set_value(a.get_upper() - a.get_page_size())


def shoot(g):
    d = g["win"].get_visible_dialog()
    g["shot"](os.path.join(g["out"], f"{P}linux-hilfe{S.get('suffix', '')}.png"))
    diag = d.pages["info"].diag
    check("Hilfe: Diagnose senden bereit", diag.send_row.get_visible() and diag.btn.get_sensitive())
    check("Hilfe: keine Zeile Gjallarhorn mehr", getattr(diag, "horn", None) is None)
    texts = [w.get_label() for w in walk(diag) if isinstance(w, Gtk.Label)]
    check("Hilfe: kein Hinweis experimentell", not any("xperiment" in (t or "") for t in texts))


def dark(g):
    g["win"].get_visible_dialog().force_close()
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    S["suffix"] = "-dunkel"


steps = [(300, start), (800, open_info), (800, info), (1200, scroll), (800, shoot), (300, dark),
         (300, open_info), (800, info), (1200, scroll), (800, shoot)]
