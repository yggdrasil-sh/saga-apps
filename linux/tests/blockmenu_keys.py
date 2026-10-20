# N1 (Testrunde 2.4): Blockmenue per Tastatur (Menuetaste, Umschalt+F10) -> Loeschen/Duplizieren mit echter Hauptschleife.
# Frueher Absturz (SIGSEGV in gdk_surface_request_motion), weil das Menue am Textfeld des Blocks hing, das beim Neuaufbau
# verschwand. Erfolg: "TEST FERTIG" ohne "FEHLER" und ohne Absturz.
#   sh tests/run-container.sh app:blockmenu_keys.py
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mockserver  # noqa: E402

from gi.repository import Gdk, Gtk  # noqa: E402

S = {}


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def check(ok, what):
    print(("ok " if ok else "FEHLER ") + what, flush=True)


def start(g):
    S["srv"] = mockserver.install()
    g["store"].logged_in()
    g["win"].show_root(("page", 1))


def _key(w, kv, mods=0):
    """Taste wie vom Fenster zugestellt: Controller-Kette vom Fokus nach oben"""
    while w is not None:
        lm = w.observe_controllers()
        for i in range(lm.get_n_items()):
            c = lm.get_item(i)
            if isinstance(c, Gtk.EventControllerKey) and c.emit("key-pressed", kv, 0, Gdk.ModifierType(mods)):
                return True
        w = w.get_parent()
    return False


def _open(g, bid, kv, mods):
    from hlidskjalf import blocks
    v = g["win"].current
    made, orig = [], blocks.menu_popover

    def spy(parent, entries):
        made.append(orig(parent, entries))
        return made[-1]
    blocks.menu_popover = spy
    try:
        rt = v.kit.by_key[bid]
        rt.grab_focus()
        check(_key(rt, kv, mods), f"Menue-Taste in {bid} behandelt")
    finally:
        blocks.menu_popover = orig
    S["pop"] = made[0] if made else None
    check(S["pop"] is not None and S["pop"].get_visible(), "Blockmenue offen")


def _click(text):
    from hlidskjalf.i18n import _
    pop = S["pop"]
    b = next((b for b in walk(pop) if isinstance(b, Gtk.Button) and isinstance(b.get_child(), Gtk.Label)
              and b.get_child().get_label() == _(text)), None)
    check(b is not None, f"Eintrag {text}")
    if b is not None:
        b.emit("clicked")


def open_delete(g):
    v = g["win"].current
    S["n"] = len(v.doc.root)
    _open(g, v.doc.root[0]["id"], Gdk.KEY_Menu, 0)
    g["shot"](g["out"] + "/n1-blockmenue-tastatur.png")


def do_delete(g):
    _click("Delete")


def check_delete(g):
    v = g["win"].current
    check(len(v.doc.root) == S["n"] - 1 or (S["n"] == 1 and len(v.doc.root) == 1), "Block geloescht")
    check(S["pop"].get_parent() is None, "Menue abgehaengt")
    _open(g, v.doc.root[0]["id"], Gdk.KEY_F10, Gdk.ModifierType.SHIFT_MASK)


def do_dup(g):
    S["n"] = len(g["win"].current.doc.root)
    _click("Duplicate")


def check_dup(g):
    v = g["win"].current
    check(len(v.doc.root) == S["n"] + 1, "Block dupliziert")
    # noch einmal loeschen, direkt danach mit der Maus ueber die Seite (Zeigerbewegung nach dem Neuaufbau)
    _open(g, v.doc.root[-1]["id"], Gdk.KEY_Menu, 0)


def do_delete2(g):
    _click("Delete")


def end(g):
    g["shot"](g["out"] + "/n1-nach-loeschen.png")
    check(g["win"].current.doc.fetched, "Seite geladen")


steps = [(1500, start), (2000, open_delete), (600, do_delete), (40, lambda g: None), (900, check_delete), (600, do_dup), (900, check_dup),
         (600, do_delete2), (900, end)]
