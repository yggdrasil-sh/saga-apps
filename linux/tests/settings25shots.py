# Einstellungen 2.5: Konto · Einstellungen · Info (mit Update-Hinweis) - Bildschirmfotos mit nachgebautem Server, ohne Netz.
#   HK_TEST=tests/settings25shots.py HK_OUT=<ordner> python3 -m hlidskjalf.main   (Dateien linux-25-einst-NN-<name>[-dunkel].png)
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mockserver  # noqa: E402

from gi.repository import Adw  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
S = {}
PAGE = "https://github.com/yggdrasil-sh/saga-apps/releases/tag/v2.5.0-rc.140"


def check(name, ok):
    print(f"{'OK  ' if ok else 'FEHLER'} {name}", flush=True)


def shoot(g, name):
    d = g["win"].get_visible_dialog()
    g["shot"](os.path.join(g["out"], f"{P}linux-25-einst-{name}{S.get('suffix', '')}.png"))
    return d


def start(g):
    S["srv"] = mockserver.install()
    g["store"].logged_in()
    g["store"].server_version = "2.5.0"
    from hlidskjalf import updatecheck, updatehint as U
    h = updatecheck.hint
    h.label_fn = lambda: "2.5.0-rc.130"                       # wie ein Paket aus dem testing-Kanal
    h.fetch = lambda url, ch: (time.sleep(0.3), U.parse(json.dumps({"format": 1, "channel": "testing", "version": "2.5.0-rc.140",
                                                                     "page": PAGE, "assets": {}}), ch))[1]
    g["win"].show_root(("today",))
    g["win"].set_default_size(1100, 820)


def open_account(g):
    g["win"].settings_dialog(page="account")


def shot_account(g):
    shoot(g, "01-konto")


def open_settings(g):
    g["win"].get_visible_dialog().show_page("settings")


def shot_settings(g):
    shoot(g, "02-einstellungen")


def scroll_settings(g):
    from gi.repository import Gtk
    d = g["win"].get_visible_dialog()

    def walk(w):
        c = w.get_first_child()
        while c:
            yield c
            yield from walk(c)
            c = c.get_next_sibling()
    for sw in [w for w in walk(d.pages["settings"]) if isinstance(w, Gtk.ScrolledWindow)]:
        a = sw.get_vadjustment()
        a.set_value(a.get_upper() - a.get_page_size())


def shot_settings2(g):
    shoot(g, "02b-einstellungen-unten")


def open_info(g):
    g["win"].get_visible_dialog().show_page("info")


def shot_info(g):
    d = shoot(g, "03-info-update")
    check("Info: Schaltflaeche Update", d.pages["info"].btn.get_label() == "Update")


def uptodate(g):
    from hlidskjalf import updatecheck, updatehint as U
    h = updatecheck.hint
    h.release = U.parse(json.dumps({"format": 1, "version": "2.5.0-rc.130", "page": PAGE}), "testing")
    h.emit("changed")


def shot_uptodate(g):
    shoot(g, "04-info-aktuell")


def open_sub(g):
    g["win"].get_visible_dialog().show_page("push")


def shot_sub(g):
    shoot(g, "05-unterseite-mitteilungen")
    g["win"].get_visible_dialog().force_close()
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    S["suffix"] = "-dunkel"
    from hlidskjalf import updatecheck
    updatecheck.hint.release = None
    updatecheck.hint.checked = None


steps = [(300, start), (800, open_account), (1500, shot_account), (300, open_settings), (1200, shot_settings), (200, scroll_settings), (600, shot_settings2),
         (300, open_info), (1500, shot_info), (200, uptodate), (500, shot_uptodate), (300, open_sub), (1200, shot_sub),
         (300, open_account), (1500, shot_account), (300, open_info), (1500, shot_info)]
