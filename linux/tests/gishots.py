# Saga 2.5 (Linux): Gruppeninfo und Kontakte - Bildschirmfotos mit nachgebautem Server, hell/dunkel (es: nur hell).
#   im Container: sh tests/run-container.sh app:gishots.py   (Dateien linux-gi-NN-name-<hell|dunkel|es>.png in HK_OUT)
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockv2  # noqa: E402
import v2shots as V  # noqa: E402 - Aufbau (Server, Chats, Konten) wie bei den 2.0-Bildern

from gi.repository import Adw  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
THEMES = [("hell", Adw.ColorScheme.FORCE_LIGHT)] + ([] if V.LANG == "es" else [("dunkel", Adw.ColorScheme.FORCE_DARK)])


def shoot(g, name, widget=None):
    g["shot"](os.path.join(g["out"], f"linux-gi-{name}-{'es' if P.startswith('es') else V.S['theme']}.png"), widget)


def start(g):
    V.start(g)
    srv = V.S["srv"]
    srv.v25 = True
    srv.extra_features = ["chatadm1", "contacts1"]
    srv.chatadm = srv.contacts_on = True
    srv.chat_peer("dora", "Dora Klein")
    srv.names["dora"] = "Dora Klein"
    srv.chats[V.S["free"]]["members"].append("anna")
    mockv2.admins(srv.chats[V.S["free"]]).add("bert")
    srv.contacts = [
        {"ref": "~anna", "name": "Anna Beispiel", "sub": "", "avatar": None, "remote": False, "ready": True, "gone": False, "last_at": 50},
        {"ref": "~dora", "name": "Dora Klein", "sub": "", "avatar": None, "remote": False, "ready": True, "gone": False, "last_at": 40},
        {"ref": "~emil@b.example", "name": "Emil Gast", "sub": "b.example", "host": "b.example", "avatar": None, "remote": True,
         "ready": True, "gone": False, "last_at": 30},
        {"ref": "~fritz", "name": "Fritz Alt", "sub": "", "avatar": None, "remote": False, "ready": True, "gone": True, "last_at": 20}]
    g["store"].check_version()


def ready(g):
    V.chat_ready(g)
    from hlidskjalf.chat import chat
    chat.load_chats()
    g["win"].show_route(("chat", V.S["free"]))


def info_open(g):
    V.close_all(g)
    g["win"].current.title_clicked()                      # Klick auf den Chat-Kopf


def info_shot(g):
    shoot(g, "01-gruppeninfo")


def info_add(g):
    d = V.dlg(g)
    if d is not None and hasattr(d, "_toggle_add"):
        d._toggle_add()


def info_scroll(g):
    from gi.repository import Gtk
    d = V.dlg(g)
    sw = [w for w in _walk(d) if isinstance(w, Gtk.ScrolledWindow)] if d is not None else []
    if sw:
        adj = sw[0].get_vadjustment()
        adj.set_value(adj.get_upper())


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def info_add_shot(g):
    shoot(g, "02-mitglied-hinzufuegen-kontakte")
    V.close_all(g)


def new_chat(g):
    from hlidskjalf.chatview import NewChatDialog
    V.close_all(g)
    NewChatDialog(g["win"]).present(g["win"])


def new_chat_shot(g):
    shoot(g, "03-neue-unterhaltung-kontakte")
    V.close_all(g)


def readonly(g):
    from hlidskjalf.chatview import GroupInfoDialog
    V.close_all(g)
    GroupInfoDialog(g["win"], V.S["team"]).present(g["win"])


def readonly_shot(g):
    shoot(g, "04-gruppen-chat-nur-lesend")
    V.close_all(g)


steps = [(300, start)]
for _th, _sc in THEMES:
    steps += [(500, V.size(False)), (2500, V.reset(_th, _sc)), (1500, ready), (2500, info_open), (1500, info_shot),
              (300, info_add), (1200, info_scroll), (500, info_add_shot), (500, new_chat), (1800, new_chat_shot), (500, readonly), (1500, readonly_shot)]
steps += [(500, lambda g: Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT))]
