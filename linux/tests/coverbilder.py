# Titelbild-Galerie (2.5, Bugs-DB 2117): Bildschirmfotos mit nachgebautem Server, ohne Netz.
#   HK_TEST=tests/coverbilder.py HK_OUT=<ordner> python3 -m hlidskjalf.main
#   Dateien <HK_SHOT_PREFIX>linux-titelbild-NN-<name>[-dunkel].png: Menue "Aendern", Dialog "Titelbild-Galerie",
#   "Ueber dieses Bild", Menue "Titelbild hinzufuegen". Ohne coverdialog.py (aeltere App): nur die beiden Menues (vorher).
#   Aufnahme des ganzen X-Bildschirms (ImageMagick "import", sonst nur das Fenster) - so sind Menues und Dialoge drauf.
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mockserver  # noqa: E402

from gi.repository import Adw, Gtk  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
S = {"suffix": ""}
W, H = 1180, 800
try:
    from hlidskjalf import coverdialog  # noqa: F401
    NEW = True
except ImportError:
    NEW = False


def check(name, ok):
    print(f"{'OK  ' if ok else 'FEHLER'} {name}", flush=True)


def shoot(g, name):
    path = os.path.join(g["out"], f"{P}linux-titelbild-{name}{S['suffix']}.png")
    if shutil.which("import") and os.environ.get("DISPLAY"):
        subprocess.run(["import", "-window", "root", "-crop", f"{W}x{H}+0+0", "+repage", path], check=False)
        print("SHOT", os.path.basename(path), flush=True)
    else:
        g["shot"](path)


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def start(g):
    S["srv"] = srv = mockserver.install()
    srv.covers_api = True
    srv.notes[3]["cover"] = "/covers/met-wave.jpg"
    srv.notes[3]["cover_pos"] = 40
    srv.notes[3]["title"] = "Reiseplanung"
    g["store"].logged_in()
    g["win"].set_default_size(W, H)
    g["win"].show_page(3)


def _close_all(g):
    d = g["win"].get_visible_dialog()
    if d is not None:
        d.force_close()
    for w in _walk(g["win"]):
        if isinstance(w, Gtk.MenuButton):
            w.popdown()


def menu_open(g):
    _close_all(g)
    v = g["win"].current
    check("Titelbild da", v.cover is not None)
    mb = v.cover.bar.get_first_child()
    v.cover.bar.add_css_class("hk-on")
    mb.popup()


def menu_shot(g):
    shoot(g, "01-menue")
    if NEW:
        pop = g["win"].current.cover.bar.get_first_child().get_popover()
        labels = [x.get_label() for x in _walk(pop) if isinstance(x, Gtk.Label)] if pop else []
        check("Menue mit Galerie", "Galerie …" in labels or "Gallery …" in labels)


def gallery_open(g):
    _close_all(g)
    if NEW:
        coverdialog.CoverGalleryDialog(g["win"].current).present(g["win"])


def gallery_shot(g):
    if NEW:
        d = g["win"].get_visible_dialog()
        check("Galerie zeigt Liste", d is not None and d.stack.get_visible_child_name() == "list")
        shoot(g, "02-galerie")


def about_open(g):
    _close_all(g)
    if NEW:
        coverdialog.show_about(g["win"].current)


def about_shot(g):
    if NEW:
        shoot(g, "03-ueber")
    _close_all(g)
    g["win"].show_page(2)


def add_open(g):
    v = g["win"].current
    mb = [w for w in _walk(v) if isinstance(w, Gtk.MenuButton) and isinstance(w.get_child(), Gtk.Label)
          and "🖼" in (w.get_child().get_label() or "")]
    check("Titelbild hinzufuegen da", bool(mb))
    if mb:
        mb[0].get_parent().add_css_class("hk-on")
        mb[0].popup()


def add_shot(g):
    shoot(g, "04-hinzufuegen")
    _close_all(g)


def dark(g):
    _close_all(g)
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    S["suffix"] = "-dunkel"
    g["win"].show_page(3)


def done(g):
    srv = S["srv"]
    check("Vorschaubilder ueber thumb geladen", not NEW or any(p.startswith("covers/") and "w=384" in p for p in srv.file_log))


ROUND = [(1500, menu_open), (900, menu_shot), (300, gallery_open), (2500, gallery_shot), (300, about_open), (800, about_shot),
         (1500, add_open), (900, add_shot)]
steps = [(2500, start), (2000, lambda g: None)] + ROUND + [(500, dark), (1500, lambda g: None)] + ROUND + [(300, done)]
