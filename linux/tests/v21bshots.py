# Saga 2.1 (Linux, Welle b): Profilbilder, "Anmeldung & Sicherheit", Neu beginnen mit Code, Anrufe - Bildschirmfotos mit
# nachgebautem Server, hell/dunkel, breit/schmal, es.
#   im Container: sh tests/run-container.sh shots   (Dateien linux-v21b-NN-name-<hell|dunkel|es>[-schmal].png)
import io
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
os.environ["HK_NO_RING"] = "1"                                   # im Container kein Klingelton
import mockv21  # noqa: E402
import v2shots as V  # noqa: E402 - Aufbau (Server, Chats, Konten) wie bei den 2.0-Bildern

from gi.repository import Adw  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
THEMES = [("hell", Adw.ColorScheme.FORCE_LIGHT)] + ([] if V.LANG == "es" else [("dunkel", Adw.ColorScheme.FORCE_DARK)])
def shoot(g, name, widget=None):
    sz = "-schmal" if V.S.get("narrow") else ""
    g["shot"](os.path.join(g["out"], f"linux-v21b-{name}-{'es' if P.startswith('es') else V.S['theme']}{sz}.png"), widget)


def _photo(w=900, h=640):
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (w, h), (150, 190, 220))
    d = ImageDraw.Draw(im)
    d.rectangle((0, h * 0.7, w, h), fill=(90, 140, 80))
    d.ellipse((w * 0.36, h * 0.18, w * 0.62, h * 0.52), fill=(235, 200, 170))          # Kopf
    d.ellipse((w * 0.26, h * 0.5, w * 0.72, h * 1.05), fill=(70, 100, 160))            # Schultern
    d.ellipse((w * 0.36, h * 0.12, w * 0.62, h * 0.3), fill=(90, 60, 40))              # Haare
    return im


def start(g):
    V.start(g)
    srv = V.S["srv"]
    for uid, col in (("anna", (210, 120, 80)), ("bert", (90, 130, 200)), ("test", (120, 170, 110))):
        mockv21.set_avatar(srv, uid, mockv21.face(col))
    srv.security = {"supported": True, "enabled": True, "required": False, "totp": True, "recovery_left": 2, "recovery_total": 10,
                    "passkeys": [{"id": 1, "name": "Laptop"}, {"id": 2, "name": "Telefon"}], "methods": ["password", "passkey", "totp"]}
    srv.reauth_methods = ["password", "passkey", "totp"]
    srv.reauth_need = "password"
    srv.users[V.EXAMPLE] = "test"
    dm = V.S["dm"]
    srv.add_notif("call.missed", created=time.time() - 60, actor={"name": "Anna Beispiel", "username": "anna"}, title="Anna Beispiel",
                  chat={"id": dm, "kind": "dm"}, url=f"/chats#c{dm}", note=None)


def account(g):
    from hlidskjalf.calls import calls
    calls.load_config(lambda on: g["win"].update_header())
    g["win"].load_account()
    g["win"].settings_dialog(page="account")


def account_shot(g):
    shoot(g, "01-konto-profilbild-sicherheit")
    from gi.repository import Gtk
    d = V.dlg(g)
    for sw in [w for w in _walk(d) if isinstance(w, Gtk.ScrolledWindow) and w.get_mapped()] if d is not None else []:
        adj = sw.get_vadjustment()
        adj.set_value(min(adj.get_upper() - adj.get_page_size(), 330))      # bis "Anmeldung & Sicherheit" ganz sichtbar


def account_shot2(g):
    shoot(g, "01b-konto-anmeldung-sicherheit")
    V.close_all(g)


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def crop_open(g):
    from hlidskjalf.profile import CropDialog, prepare
    buf = io.BytesIO()
    _photo().save(buf, "JPEG")
    CropDialog(prepare(buf.getvalue()), lambda d, im, box: d.close()).present(g["win"])


def crop_zoom(g):
    d = V.dlg(g)
    d.scale.set_value(0.7)
    d.area.cx -= 40
    d.area._clamp()


def crop_shot(g):
    shoot(g, "02-profilbild-zuschneiden")
    V.close_all(g)


def chat_open(g):
    V.chat_ready(g)                                             # Nachrichten zuerst (nur wenn der Chat noch leer ist) ...
    if not V.S.get("calls_added"):                              # ... dann die Anruf-Eintraege dahinter
        V.S["calls_added"] = True
        srv, dm = V.S["srv"], V.S["dm"]
        mockv21.call_entry(srv, dm, "missed", "audio", frm="anna", to="test", ago=0)
        mockv21.call_entry(srv, dm, "ended", "video", frm="test", to="anna", dur=754, ago=0)
    g["win"].show_route(("chat", V.S["dm"]))


def chat_shot(g):
    shoot(g, "03-chat-profilbilder-anrufe")


def list_shot(g):
    g["win"].show_route(("chats",))


def list_shot2(g):
    shoot(g, "04-chatliste-profilbilder")
    g["win"].show_route(("chat", V.S["dm"]))


def ring(g):
    from hlidskjalf.calls import calls
    srv = V.S["srv"]
    V.S["call"] = mockv21.ring(srv, "anna", V.S["dm"], "video", push=False)
    calls.on_live("call.ring", {"call_id": V.S["call"]})


def ring_shot(g):
    shoot(g, "05-eingehender-anruf")
    from hlidskjalf.calls import calls
    calls.decline(V.S["call"])


def start_over(g):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatview import SetupDialog
    chat.refresh(lambda: SetupDialog(g["win"], reset=True).present(g["win"]))


def start_over_shot(g):
    shoot(g, "06-neu-beginnen-mit-code")
    V.close_all(g)


def bell_open(g):
    V.bell_open(g)


def bell_shot(g):
    shoot(g, "07-benachrichtigung-verpasster-anruf", g["win"].bell.pop)
    V.close_all(g)


def presence(g):
    g["win"].show_root(("page", 1))


def presence_shot(g):
    win = g["win"]
    srv = V.S["srv"]
    win.set_presence([{"username": u, "name": srv.names[u], "avatar": mockv21.av_url(srv, u), "editing": u == "bert"}
                      for u in ("bert", "anna")])


def presence_shot2(g):
    shoot(g, "08-anwesenheit-profilbilder", g["win"].chb)


def login_page(g):
    g["win"].stack.set_visible_child_name("login")


def login_shot(g):
    shoot(g, "09-anmeldung-zweiter-faktor")
    g["win"].stack.set_visible_child_name("main")


steps = [(300, start)]
for _th, _sc in THEMES:
    steps += [(500, V.size(False)), (2500, V.reset(_th, _sc)), (1500, account), (2500, account_shot), (1000, account_shot2),
              (500, crop_open), (800, crop_zoom), (1200, crop_shot), (500, chat_open), (3000, chat_shot),
              (500, list_shot), (2000, list_shot2), (1500, ring), (2000, ring_shot), (1500, start_over), (2500, start_over_shot),
              (500, bell_open), (2500, bell_shot), (500, presence), (2000, presence_shot), (1500, presence_shot2),
              (500, login_page), (1500, login_shot)]
steps += [(500, V.size(True)), (2500, V.reset(THEMES[0][0], THEMES[0][1])), (1500, chat_open), (2500, ring), (2500, ring_shot),
          (1500, account), (2500, account_shot), (1000, account_shot2)]
steps += [(500, lambda g: Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT))]
