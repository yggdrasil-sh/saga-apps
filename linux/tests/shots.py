# Bildschirmfotos mit nachgebautem Server: Seite, Dienste, Heute - hell, dunkel und schmal (HK_OUT, Standard /tmp)
#   im Container: sh tests/run-container.sh shots
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockserver  # noqa: E402

from gi.repository import Adw  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
S = {}


def shoot(g, name):
    g["shot"](os.path.join(g["out"], f"{P}{name}.png"))


def start(g):
    S["srv"] = mockserver.install()
    g["store"].logged_in()
    g["win"].show_root(("page", 1))


def light_page(g):
    shoot(g, "01-seite-hell")
    adj = g["win"].current.scroll.get_vadjustment()
    adj.set_value(adj.get_upper())


def light_db(g):
    shoot(g, "01b-seite-datenbank-hell")
    g["win"].show_root(("services",))


def light_services(g):
    shoot(g, "02-dienste-hell")
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)


def dark_services(g):
    shoot(g, "03-dienste-dunkel")
    g["win"].show_root(("page", 1))


def dark_page(g):
    shoot(g, "04-seite-dunkel")
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT)
    g["win"].set_default_size(900, 700)


def narrow_page(g):
    shoot(g, "05-seite-schmal-900")
    g["win"].show_root(("services",))


def narrow_services(g):
    shoot(g, "06-dienste-schmal-900")
    g["win"].set_default_size(1100, 760)
    g["win"].show_root(("today",))


def mid_today(g):
    shoot(g, "07-heute-1100")
    g["win"].show_root(("page", 1))


def mid_page(g):
    shoot(g, "08-seite-1100")
    g["win"].set_default_size(600, 760)


def phone_page(g):
    shoot(g, "09-seite-600")
    g["win"].stack.set_visible_child_name("login")


def login(g):
    shoot(g, "10-anmeldung-600")


steps = [(300, start), (2500, light_page), (1200, light_db), (1500, light_services), (1200, dark_services), (2500, dark_page),
         (2000, narrow_page), (1500, narrow_services), (2000, mid_today), (2000, mid_page), (2000, phone_page), (1200, login)]
