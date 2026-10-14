# Rueckfragen und Menues mit nachgebautem Server ansehen (Bildschirmfotos): Kopplung, Konflikt, Schliessen, Menues, Kuerzel
#   im Container: HK_OUT=... sh tests/run-container.sh shots   (laeuft dort nach shots.py)
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mockserver  # noqa: E402

from gi.repository import Adw, Gdk, Gtk  # noqa: E402

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


# --- Veroeffentlichen: Namensschritt, Einstellungen, Kopfleiste, Unterseite, Rueckfrage, Einstellungen -> Konto ---
def pub_open(g):
    from hlidskjalf.publish import open_publish
    open_publish(g["win"], 1)


def pub_name_shot(g):
    shoot(g, "30-veroeffentlichen-name")
    d = g["win"].get_visible_dialog()
    d.name_row.set_text("anna")


def pub_name_continue(g):
    g["win"].get_visible_dialog()._name_continue()


def pub_publish(g):
    d = g["win"].get_visible_dialog()
    d.sw_children.set_active(True)
    d.sw.set_active(True)


def pub_main_shot(g):
    shoot(g, "31-veroeffentlichen-oeffentlich")
    g["win"].get_visible_dialog()._stop()                  # Rueckfrage "Veroeffentlichung beenden?"


def pub_stop_shot(g):
    shoot(g, "32-veroeffentlichen-beenden-rueckfrage")
    close_dialog(g)                                          # Rueckfrage weg (= Abbrechen)
    close_dialog(g)


def pub_header_shot(g):
    shoot(g, "33-kopfleiste-oeffentlich")
    g["win"].show_root(("page", 2))


def pub_via_header(g):
    shoot(g, "34-unterseite-oeffentlich-ueber")
    from hlidskjalf.publish import open_publish
    open_publish(g["win"], 2)


def pub_via_shot(g):
    shoot(g, "35-veroeffentlichen-ueber-elternseite")
    close_dialog(g)
    g["win"].settings_dialog()


def pub_settings(g):
    d = g["win"].get_visible_dialog()
    from hlidskjalf.publish import PublicNameGroup
    grp = next(w for w in walk(d) if isinstance(w, PublicNameGroup))
    page = grp.get_ancestor(Adw.PreferencesPage)
    d.set_visible_page(page)
    grp.row.set_text("anna-neu")
    grp.row.grab_focus()


def pub_settings_shot(g):
    shoot(g, "36-einstellungen-oeffentlicher-name")
    close_dialog(g)
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    g["win"].show_root(("page", 1))
    from hlidskjalf.publish import open_publish
    open_publish(g["win"], 1)


def pub_dark_shot(g):
    shoot(g, "37-veroeffentlichen-dunkel")
    close_dialog(g)
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT)


steps = [(300, start), (2500, pair), (800, pair_shot), (800, conflict_shot), (800, conflict_later_shot), (800, draft_banner_shot),
         (800, block_menu_shot), (800, sidebar_menu_shot),
         (1000, shortcuts_shot), (1500, tasks_shot), (800, close_shot),
         (1500, pub_open), (1500, pub_name_shot), (1200, pub_name_continue), (1200, pub_publish), (1500, pub_main_shot),
         (1000, pub_stop_shot), (1000, pub_header_shot), (1500, pub_via_header), (1500, pub_via_shot), (1500, pub_settings),
         (1500, pub_settings_shot), (2000, pub_dark_shot)]
