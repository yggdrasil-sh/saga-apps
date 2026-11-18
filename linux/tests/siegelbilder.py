# Saga-Siegel (2.5, Bugs-DB 2156) und "Anruf voll" (Bugs-DB 2154): Bildschirmfotos mit nachgebautem Server, ohne Netz.
#   HK_TEST=tests/siegelbilder.py HK_OUT=<ordner> [HK_SIEGEL_DIR=<ordner mit <farbe>/<motiv>.png>] python3 -m hlidskjalf.main
#   Dateien <HK_SHOT_PREFIX>linux-siegel-NN-<name>[-dunkel].png: Seite mit Siegel (Kopf + Baum), Datenbank, Symbol-Dialog
#   (Bereich Saga-Siegel bzw. Emoji), Leiste "Anruf laeuft" bei vollem Anruf. Ohne siegel.py (aeltere App): wie vorher.
#   Aufnahme des ganzen X-Bildschirms (ImageMagick "import", sonst nur das Fenster) - so sind Dialoge drauf.
import importlib.util
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mockserver  # noqa: E402

from gi.repository import Adw  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
S = {"suffix": ""}
W, H = 1180, 800
NEW = importlib.util.find_spec("hlidskjalf.siegel") is not None      # neue App (sonst: Bilder "vorher")


def check(name, ok):
    print(f"{'OK  ' if ok else 'FEHLER'} {name}", flush=True)


def shoot(g, name):
    path = os.path.join(g["out"], f"{P}linux-siegel-{name}{S['suffix']}.png")
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
    srv.v25 = True
    srv.extra_features = ["siegel1"]
    n = srv.notes
    n[1]["icon"] = "/siegel/weinrot/yggdrasil.svg"
    n[2]["icon"] = "/siegel/smaragd/feder.svg"
    n[3].update(icon="/siegel/weinrot/rabe.svg", title="Reiseplanung")
    n[10]["icon"] = "/siegel/elfenbein/truhe.svg"
    for i, (t, ic) in enumerate((("Packliste", "/siegel/smaragd/kompass.svg"), ("Unterkunft", "/siegel/weinrot/laterne.svg"),
                                 ("Route", "/siegel/elfenbein/karte.svg"), ("Ohne Siegel", "🧭"))):
        rid = 900 + i
        n[rid] = {"id": rid, "title": t, "format": "blocks", "body": "", "rev": 1, "parent_id": 10, "icon": ic,
                  "updated": n[3]["updated"], "created": n[3]["created"], "props": {}}
    g["store"].logged_in()
    g["win"].set_default_size(W, H)
    g["win"].show_page(3)


def _close_all(g):
    d = g["win"].get_visible_dialog()
    if d is not None:
        d.force_close()
    g["win"].runbar.show([])


def page_shot(g):
    v = g["win"].current
    check("Seite 3 offen", getattr(v, "id", None) == 3)
    from hlidskjalf import images
    n = sum(1 for w in _walk(g["win"]) if isinstance(w, images.ImageView) and isinstance(w.path, str) and w.path.startswith("/siegel/"))
    check(f"Siegel als Bild ({n})", not NEW or n >= 3)
    shoot(g, "01-seite")


def picker_open(g):
    from hlidskjalf.page import IconPicker
    IconPicker(g["win"].current).present(g["win"])


def picker_shot(g):
    d = g["win"].get_visible_dialog()
    if NEW:
        check("Bereich Saga-Siegel", d is not None and d.stack is not None and d.stack.get_visible_child_name() == "siegel")
        pane = d.stack.get_child_by_name("siegel")
        check("36 Motive", len(pane.tiles) == 36)
    shoot(g, "02-auswahl")
    if NEW and d is not None:
        d.stack.set_visible_child_name("emoji")


def emoji_shot(g):
    if NEW:
        shoot(g, "03-auswahl-emoji")
    _close_all(g)
    g["win"].show_page(10)


def db_shot(g):
    shoot(g, "04-datenbank")
    g["win"].show_page(3)


def full_call(g):
    cid = "c" * 32
    c = {"id": cid, "chat_id": 1, "media": "audio", "state": "active", "kind": "group", "mine": [], "joinable": False,
         "join_block": "full", "limit": 4, "legs": [{"device": f"{i:032x}", "who": "x", "role": "member"} for i in range(4)],
         "from": {"username": "anna", "name": "Anna", "chat": "Team"}}
    S["call"] = c
    g["win"].runbar.show([c])


def full_shot(g):
    check("Leiste voll", g["win"].runbar.get_reveal_child())
    print("NOTE", g["win"].runbar.note.get_label(), flush=True)
    shoot(g, "05-anruf-voll")


def full_mine(g):
    c = dict(S["call"], mine=["0" * 32], takeover=True)
    c["legs"] = [dict(c["legs"][0], device="0" * 32, dev_name="Telefon", who="test")] + c["legs"][1:]
    g["win"].runbar.show([c])


def full_mine_shot(g):
    print("NOTE", g["win"].runbar.note.get_label(), flush=True)
    shoot(g, "06-anruf-voll-eigenes")
    _close_all(g)


def dark(g):
    _close_all(g)
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    S["suffix"] = "-dunkel"
    g["win"].show_page(3)


def done(g):
    srv = S["srv"]
    check("Siegel nur als PNG geladen", all("?w=" in p for p in srv.file_log if p.startswith("siegel/")))


ROUND = [(1800, page_shot), (300, picker_open), (2200, picker_shot), (900, emoji_shot), (2200, db_shot),
         (1200, full_call), (900, full_shot), (300, full_mine), (900, full_mine_shot)]
steps = [(2500, start), (2500, lambda g: None)] + ROUND + [(500, dark), (2000, lambda g: None)] + ROUND + [(300, done)]
