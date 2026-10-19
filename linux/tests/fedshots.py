# Saga 2.3 (Linux): Chats ueber Servergrenzen - Bildschirmfotos mit nachgebautem Server (mockfed.py), hell/dunkel, schmal, es.
#   im Container: sh tests/run-container.sh app:fedshots.py   (Dateien linux-fed-NN-name-<hell|dunkel|es>[-schmal].png)
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockfed  # noqa: E402
import v2shots as V  # noqa: E402 - Aufbau (Server, Chats, Konten) wie bei den 2.0-Bildern

from gi.repository import Adw, Gtk  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
THEMES = [("hell", Adw.ColorScheme.FORCE_LIGHT)] + ([] if V.LANG == "es" else [("dunkel", Adw.ColorScheme.FORCE_DARK)])
TXT = {"de": ("Hallo! Wir kennen uns vom Gartenprojekt – darf ich dir schreiben?", "Gern! Schön, dass es über die Server hinweg klappt.",
              "Die Pläne schicke ich dir gleich.", "Bis morgen dann 👋"),
       "es": ("¡Hola! Nos conocemos del proyecto del huerto, ¿puedo escribirte?", "¡Claro! Qué bien que funcione entre servidores.",
              "Te envío los planos enseguida.", "Hasta mañana 👋")}.get(
    V.LANG, ("Hi! We know each other from the garden project – may I write to you?", "Sure! Nice that it works across servers.",
             "I'll send you the plans in a moment.", "See you tomorrow 👋"))


def shoot(g, name, widget=None):
    sz = "-schmal" if V.S.get("narrow") else ""
    g["shot"](os.path.join(g["out"], f"linux-fed-{name}-{'es' if P.startswith('es') else V.S['theme']}{sz}.png"), widget)


def start(g):
    V.start(g)
    srv = V.S["srv"]
    mockfed.enable(srv)
    mockfed.HOST = "saga.example.org"
    srv.fed["me"] = srv.fed["me"].split("@")[0] + "@saga.example.org"
    g["store"].server_version = "2.3.0"
    V.S["h_bert"] = mockfed.remote_person(srv, "Bert Gast", "saga.verein.example", public="bert")
    V.S["h_cleo"] = mockfed.remote_person(srv, "Cleo Team", "cloud.firma.example")
    V.S["h_dora"] = mockfed.remote_person(srv, "Dora Fern", "saga.verein.example")
    mockfed.remote_person(srv, "Anna Berg", "saga.verein.example", public="annab")
    mockfed.remote_person(srv, "Anna Berg", "cloud.firma.example")
    V.S["req"] = mockfed.new_fed_chat(srv, V.S["h_dora"], request=True, home_self=False)
    V.S["fdm"] = mockfed.new_fed_chat(srv, V.S["h_bert"])
    V.S["warn"] = mockfed.new_fed_chat(srv, V.S["h_cleo"])


def ready(g):
    from hlidskjalf.chat import chat
    V.chat_ready(g)
    srv = V.S["srv"]
    if srv.fed["certs"]["dset"] is None:
        chat.fed_certs()                                  # Zertifikate v2 (sonst gaebe es keine Schluessel fuer dieses Geraet)
    if not srv.chats[V.S["req"]]["msgs"]:
        mockfed.peer_send_v2(srv, V.S["req"], TXT[0])
        m = mockfed.peer_send_v2(srv, V.S["fdm"], TXT[1])
        mockfed.add_sys(srv, V.S["fdm"], {"type": "ai", "on": False, "by": V.S["h_bert"], "name": "Bert Gast"})
        V.S["m_bert"] = m
        mockfed.peer_send_v2(srv, V.S["warn"], TXT[3])
    chat.refresh()


def own_msgs(g):
    """eigene Nachrichten im Chat mit Bert: eine zugestellt, eine wartet (Server von Bert gerade nicht erreichbar)"""
    from hlidskjalf.chat import chat
    srv = V.S["srv"]
    cid = V.S["fdm"]
    if any(m.get("sender") == srv.me and m.get("kind") == "msg" for m in srv.chats[cid]["msgs"]):
        return
    chat.send(cid, {"v": 1, "type": "text", "text": TXT[2]}, reply_to=V.S["m_bert"]["id"])
    srv.fed["offline"] = True
    chat.send(cid, {"v": 1, "type": "text", "text": "📎 " + {"de": "Pläne kommen gleich", "es": "Los planos ya van"}.get(V.LANG, "Plans coming")})
    srv.fed["offline"] = False


def show(route):
    def f(g):
        V.close_all(g)
        g["win"].show_route(route)
    return f


def list_shot(g):
    shoot(g, "01-liste-anfrage")


def req_open(g):
    g["win"].show_route(("chat", V.S["req"]))


def req_shot(g):
    shoot(g, "02-nachrichtenanfrage")


def block_menu(g):
    conv = g["win"].current.conv
    btn = [w for w in _walk(conv.state_box) if isinstance(w, Gtk.Button) and w.has_css_class("destructive-action")]
    if btn:
        btn[0].emit("clicked")
        pops = [w for w in _walk(conv.state_box) if isinstance(w, Gtk.Popover)]
        V.S["pop"] = pops[-1] if pops else None


def block_shot(g):
    if V.S.get("pop") is not None:
        shoot(g, "03-sperren-menue", V.S["pop"])
        V.S["pop"].popdown()


def fdm_shot(g):
    shoot(g, "04-chat-ueber-servergrenzen")


def tofu(g):
    """Cleo hat neue Schluessel (z. B. "Neu beginnen") -> Warnung im Chat"""
    from hlidskjalf.chat import chat
    srv = V.S["srv"]
    if not V.S.get("cleo_new"):
        chat.fed_keys([V.S["h_cleo"]], True)
        mockfed.new_keys(srv, V.S["h_cleo"])
        V.S["cleo_new"] = True
    chat.on_live("chat.keys.changed", {"handle": V.S["h_cleo"]})
    g["win"].show_route(("chat", V.S["warn"]))


def tofu_shot(g):
    shoot(g, "05-sicherheitsnummer-geaendert")


def safety(g):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import open_safety_v2
    V.close_all(g)
    open_safety_v2(g["win"], V.S["fdm"], (chat.chat(V.S["fdm"]) or {}).get("peer") or {})


def safety_shot(g):
    shoot(g, "06-sicherheitsnummer-v2")
    V.close_all(g)


def new_chat(g):
    from hlidskjalf.chatview import NewChatDialog
    V.close_all(g)
    d = NewChatDialog(g["win"])
    d.present(g["win"])
    d.search.set_text("Anna Berg")


def new_chat_shot(g):
    shoot(g, "07-suche-andere-server")
    V.close_all(g)


def settings(g):
    from hlidskjalf.settings import SettingsDialog
    V.close_all(g)
    V.S["srv"].fed["blocks"] = [{"target": "spam.example", "kind": "instance", "name": "", "host": "spam.example", "label": "spam.example",
                                 "created": 0},
                                {"target": "~x@saga.verein.example", "kind": "person", "name": "Max Laut", "host": "saga.verein.example",
                                 "label": "Max Laut · saga.verein.example", "created": 0}]
    d = SettingsDialog(g["win"])
    d.present(g["win"])
    try:
        d.show_page("chats")
    except Exception:  # noqa: BLE001 - Seite heisst anders: Liste der Seiten durchgehen
        pass
    V.S["sd"] = d


def settings_scroll(g):
    d = V.S.get("sd")
    if d is None:
        return
    for w in _walk(d):
        if isinstance(w, Gtk.ScrolledWindow) and w.get_mapped():
            adj = w.get_vadjustment()
            adj.set_value(adj.get_upper())


def settings_shot(g):
    shoot(g, "08-einstellungen-gesperrt")
    V.close_all(g)


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


steps = [(300, start)]
for _th, _sc in THEMES:
    steps += [(500, V.size(False)), (2500, V.reset(_th, _sc)), (1500, ready), (1000, own_msgs), (500, show(("chats",))), (2500, list_shot),
              (500, req_open), (2500, req_shot), (500, block_menu), (1200, block_shot),
              (500, lambda g: (V.close_all(g), g["win"].show_route(("chat", V.S["fdm"])))), (2500, fdm_shot),
              (500, tofu), (2500, tofu_shot), (500, safety), (1500, safety_shot), (500, new_chat), (2000, new_chat_shot),
              (500, settings), (1500, settings_scroll), (1200, settings_shot)]
steps += [(500, V.size(True)), (2500, V.reset(THEMES[0][0], THEMES[0][1])), (1500, ready),
          (500, lambda g: g["win"].show_route(("chat", V.S["req"]))), (2500, req_shot),
          (500, lambda g: g["win"].show_route(("chat", V.S["fdm"]))), (2500, fdm_shot)]
steps += [(500, lambda g: Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT))]
