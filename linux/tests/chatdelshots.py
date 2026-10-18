# Saga 2.1 (Linux): Chats archivieren und loeschen - Bildschirmfotos mit nachgebautem Server, hell/dunkel, breit/schmal, es.
#   im Container: sh tests/run-container.sh chatdelshots   (Dateien linux-chatdel-NN-name-<hell|dunkel|es>[-schmal].png)
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockv2  # noqa: E402
import v2shots as V  # noqa: E402 - Aufbau (Server, Chats, Konten) wie bei den 2.0-Bildern

from gi.repository import Adw, Gtk  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
THEMES = [("hell", Adw.ColorScheme.FORCE_LIGHT)] + ([] if V.LANG == "es" else [("dunkel", Adw.ColorScheme.FORCE_DARK)])
TXT = {"de": ("Kommst du nachher vorbei?", "Neues Thema: Grillfest im Juni"),
       "es": ("¿Pasas luego?", "Nuevo tema: barbacoa en junio")}.get(V.LANG, ("Are you coming by later?", "New topic: barbecue in June"))


def shoot(g, name, widget=None):
    sz = "-schmal" if V.S.get("narrow") else ""
    g["shot"](os.path.join(g["out"], f"linux-chatdel-{name}-{'es' if P.startswith('es') else V.S['theme']}{sz}.png"), widget)


def start(g):
    V.start(g)
    srv = V.S["srv"]
    V.S["dm2"] = srv.new_chat("dm", ["test", "bert"])
    srv.saga_admins.add("bert")


def ready(g):
    from hlidskjalf.chatview import ChatList
    V.chat_ready(g)
    srv = V.S["srv"]
    if not srv.chats[V.S["dm2"]]["msgs"]:
        srv.peer_send(V.S["dm2"], "bert", TXT[0])
    for cid in (V.S["dm2"], V.S["free"]):                      # zwei Chats im Archiv
        mockv2._pref(srv.chats[cid], "test")["archived"] = 1.0
    ChatList.arch_open = True
    from hlidskjalf.chat import chat
    chat.load_chats()
    g["win"].show_route(("chat", V.S["dm"]))


def list_shot(g):
    shoot(g, "01-chatliste-archiviert")


def ctx_menu(g):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import _walk_rows
    scr = g["win"].current
    rows = [r for r in _walk_rows(scr.list.box) if getattr(r, "cid", None) == V.S["dm"]]
    V.S["pop"] = scr.list._menu(rows[0], chat.chat(V.S["dm"]), 120, 20)


def ctx_shot(g):
    shoot(g, "02-kontextmenue", V.S["pop"])
    V.S["pop"].popdown()


def del_dialog(cid, pick_all=False):
    def f(g):
        from hlidskjalf.chatview import DeleteChatDialog
        V.close_all(g)
        d = DeleteChatDialog(g["win"], V.S[cid])
        d.present(g["win"])
        if pick_all:
            d.r_all.set_active(True)                          # "Fuer alle" gewaehlt: Knopf "Fuer alle loeschen"
    return f


def del_shot(name):
    def f(g):
        shoot(g, name)
        V.close_all(g)
    return f


def cleared(g):
    """Bert (Saga-Admin) loescht den Verlauf des Gruppen-Chats fuer alle - live, mit Hinweis"""
    srv = V.S["srv"]
    team = V.S["team"]
    g["win"].show_route(("chat", team))
    if not [m for m in srv.chats[team]["msgs"] if m["kind"] == "msg"]:
        srv.peer_send(team, "anna", TXT[1])
    srv.me = "bert"
    mockv2.delete_chat(srv, srv.chats[team], "bert", "all")
    srv.me = "test"


def cleared_new(g):
    srv = V.S["srv"]
    m = srv.peer_send(V.S["team"], "anna", TXT[1])
    srv.push_event("chat.message", {"chat_id": V.S["team"], "seq": m["seq"], "id": m["id"], "sender": "anna"})


def cleared_shot(g):
    shoot(g, "06-verlauf-fuer-alle-geloescht")


def conv_menu(g):
    g["win"].show_route(("chat", V.S["dm"]))


def conv_menu_open(g):
    cur = g["win"].current
    if cur.conv is not None:
        cur.conv.menu(g["win"].wtitle)
        pops = [w for w in _walk(g["win"].wtitle) if isinstance(w, Gtk.Popover)]
        V.S["pop"] = pops[-1] if pops else None


def conv_menu_shot(g):
    if V.S.get("pop") is not None:
        shoot(g, "07-chat-menue", V.S["pop"])
        V.S["pop"].popdown()


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


steps = [(300, start)]
for _th, _sc in THEMES:
    steps += [(500, V.size(False)), (2500, V.reset(_th, _sc)), (1500, ready), (3000, list_shot),
              (500, ctx_menu), (1500, ctx_shot),
              (500, del_dialog("dm", pick_all=True)), (1500, del_shot("03-loeschen-direktchat-fuer-alle")),
              (500, del_dialog("team")), (1500, del_shot("04-loeschen-gruppe-fuer-alle-ausgegraut")),
              (500, del_dialog("free")), (1500, del_shot("05-loeschen-freie-gruppe-verlassen")),
              (500, cleared), (1200, cleared_new), (1300, cleared_shot),
              (500, conv_menu), (2000, conv_menu_open), (1500, conv_menu_shot)]
steps += [(500, V.size(True)), (2500, V.reset(THEMES[0][0], THEMES[0][1])), (1500, ready), (500, lambda g: g["win"].show_root(("chats",))),
          (2500, list_shot), (500, del_dialog("dm")), (1500, del_shot("03-loeschen-direktchat")),
          (500, cleared), (1200, cleared_new), (1300, cleared_shot)]
steps += [(500, lambda g: Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT))]
