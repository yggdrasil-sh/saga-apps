# Veroeffentlichen mit nachgebautem Server ansehen (Bildschirmfotos), hell und dunkel; auf Spanisch nur hell:
#   Namensschritt, Dialog privat, oeffentlich (mit Profiladresse), Adresse geaendert (Hinweis), Rueckfrage Adresse,
#   Fehler am Feld, Rueckfrage Beenden, Kopfleiste, ueber Elternseite, Einstellungen -> Konto (oeffentlicher Name)
#   im Container: sh tests/run-container.sh shots   (Dateien pub-<hell|dunkel>-NN-*.png, Spanisch mit Vorsilbe es-)
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockserver  # noqa: E402

from gi.repository import Adw, Gtk  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
LANG = (os.environ.get("LANG") or "C")[:2]
THEMES = [("hell", Adw.ColorScheme.FORCE_LIGHT)] + ([] if LANG == "es" else [("dunkel", Adw.ColorScheme.FORCE_DARK)])
S = {}


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def shoot(g, name):
    g["shot"](os.path.join(g["out"], f"{P}pub-{S['theme']}-{name}.png"))


def dlg(g):
    return g["win"].get_visible_dialog()


def close_all(g):
    for _ in range(3):
        d = dlg(g)
        if d is not None:
            d.force_close()


def start(g):
    S["srv"] = mockserver.install()
    g["store"].logged_in()


def reset(theme, scheme):
    def f(g):
        S["theme"] = theme
        Adw.StyleManager.get_default().set_color_scheme(scheme)
        srv = S["srv"]
        srv.public_name = None
        srv.pubs.clear()
        g["win"].show_root(("page", 1))
        from hlidskjalf.publish import open_publish
        open_publish(g["win"], 1)
    return f


def name_shot(g):
    shoot(g, "01-name-schritt")
    dlg(g).name_row.set_text("anna")


def name_continue(g):
    dlg(g)._name_continue()


def private_shot(g):
    shoot(g, "02-dialog-privat")
    d = dlg(g)
    d.toast = lambda t: None                                  # Bestaetigungen nicht ueber dem Inhalt
    d.sw_children.set_active(True)
    d.sw_profile.set_active(True)
    d.sw.set_active(True)


def public_shot(g):
    shoot(g, "03-dialog-oeffentlich")
    d = dlg(g)
    d.slug.grab_focus()
    d.slug.set_text("projekt-neu")


def address_hint_shot(g):
    shoot(g, "04-adresse-geaendert-hinweis")
    dlg(g)._change_slug()                                     # Rueckfrage "Adresse aendern?"


def address_confirm_shot(g):
    shoot(g, "05-rueckfrage-adresse")
    dlg(g).force_close()                                      # Rueckfrage weg (= Abbrechen)
    S["srv"].pubs[3] = {"slug": "notizen", "children": False, "indexable": False, "profile": False, "active": True,
                        "withdrawn_at": None, "withdrawn_by": None}
    d = dlg(g)
    d.slug.set_text("notizen")
    d._apply_button(True)                                     # wie nach dem Klick auf den Uebernehmen-Knopf
    d._put({"slug": "notizen"}, None, "slug")                 # wie nach "Adresse aendern": belegt -> Fehler am Feld


def field_error_shot(g):
    shoot(g, "06-fehler-am-feld")
    d = dlg(g)
    d.slug.set_text(d.st.get("slug") or "")
    d._stop("stop")                                           # Rueckfrage "Veroeffentlichung beenden?"


def stop_confirm_shot(g):
    shoot(g, "07-rueckfrage-beenden")
    close_all(g)


def header_shot(g):
    shoot(g, "08-kopfleiste-oeffentlich")
    g["win"].show_root(("page", 2))


def via_open(g):
    from hlidskjalf.publish import open_publish
    open_publish(g["win"], 2)


def via_shot(g):
    shoot(g, "09-dialog-ueber-elternseite")
    close_all(g)
    g["win"].settings_dialog()


def account(g):
    d = dlg(g)
    from hlidskjalf.publish import PublicNameGroup
    grp = next(w for w in walk(d) if isinstance(w, PublicNameGroup))
    d.set_visible_page(grp.get_ancestor(Adw.PreferencesPage))
    grp.row.grab_focus()
    grp.row.set_text("anna-neu")
    grp.row.get_delegate().set_position(-1)


def account_shot(g):
    shoot(g, "10-konto-oeffentlicher-name")
    close_all(g)
    for p in [w for w in walk(g["win"]) if isinstance(w, Gtk.Popover) and w.get_visible()]:
        p.popdown()


steps = [(300, start)]
for _t, _s in THEMES:
    steps += [(2500, reset(_t, _s)), (1500, name_shot), (1200, name_continue), (1500, private_shot), (1500, public_shot),
              (1000, address_hint_shot), (1000, address_confirm_shot), (1500, field_error_shot), (1000, stop_confirm_shot),
              (1000, header_shot), (1500, via_open), (1500, via_shot), (1500, account), (1500, account_shot)]
steps += [(500, lambda g: Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT))]
