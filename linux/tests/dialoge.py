# Rueckfragen und Menues mit nachgebautem Server ansehen (Bildschirmfotos): Kopplung, Konflikt, Schliessen, Menues, Kuerzel
# (Veroeffentlichen: pubshots.py)
#   im Container: HK_OUT=... sh tests/run-container.sh shots   (laeuft dort nach shots.py)
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mockserver  # noqa: E402

from gi.repository import Gdk, Gtk  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
S = {}


def shoot(g, name):
    g["shot"](os.path.join(g["out"], f"{P}{name}.png"))


def close_dialog(g):
    d = g["win"].get_visible_dialog()
    if d is not None:
        d.force_close()


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def start(g):
    S["srv"] = mockserver.install()
    g["store"].logged_in()
    g["win"].show_root(("page", 1))


def pair(g):
    g["win"].open_link("hlidskjalf://pair?u=https://fremd.example.org&c=" + "x" * 24)


def pair_shot(g):
    shoot(g, "20-kopplung-rueckfrage")
    close_dialog(g)
    v = g["win"].current
    v.doc.conflict = None
    v.doc._conflict(dict(v.doc.page, rev=99))               # Konflikt-Dialog zeigen


def conflict_shot(g):
    shoot(g, "21-konflikt")
    close_dialog(g)                                          # wie Esc: Entscheidung vertagt, Hinweis oben


def conflict_later_shot(g):
    shoot(g, "21b-konflikt-vertagt")
    v = g["win"].current
    v.doc.page["title"] = "Projekt (hier geändert)"
    v.doc.resolve("theirs")                                  # Server-Fassung laden: meine als lokale Kopie -> Entwurf-Banner


def draft_banner_shot(g):
    shoot(g, "21c-entwurf-banner")
    v = g["win"].current
    for p, _ in v.doc.local_copies():
        v.doc.discard_copy(p)
    v.update_banners()
    v.kit.on_key(v.kit.by_key["p1"], Gdk.KEY_Menu, 0)        # Blockmenue per Tastatur


def block_menu_shot(g):
    shoot(g, "22-blockmenue-tastatur")
    for p in [w for w in walk(g["win"]) if isinstance(w, Gtk.Popover) and w.get_visible()]:
        p.popdown()
    sb = g["win"].sidebar
    from hlidskjalf.window import SidebarRow
    row = next(r for r in walk(sb.box) if isinstance(r, SidebarRow) and r.page and r.kids)
    row.grab_focus()
    g["win"].page_actions(row, row.page)


def sidebar_menu_shot(g):
    shoot(g, "23-seitenleiste-menue")
    for p in [w for w in walk(g["win"]) if isinstance(w, Gtk.Popover) and w.get_visible()]:
        p.popdown()
    g["win"].shortcuts()


def shortcuts_shot(g):
    shoot(g, "24-kuerzel")
    close_dialog(g)
    g["win"].show_root(("tasks",))


def tasks_shot(g):
    shoot(g, "25-aufgaben-mit-menueknopf")
    g["win"]._ask_close_unsaved()


def close_shot(g):
    shoot(g, "26-schliessen-nicht-gespeichert")
    close_dialog(g)
    g["win"].show_root(("page", 1))


steps = [(300, start), (2500, pair), (800, pair_shot), (800, conflict_shot), (800, conflict_later_shot), (800, draft_banner_shot),
         (800, block_menu_shot), (800, sidebar_menu_shot),
         (1000, shortcuts_shot), (1500, tasks_shot), (800, close_shot)]
# Veroeffentlichen: eigene Bildschirmfotos in pubshots.py (hell, dunkel, Spanisch)
