# Saga 2.1 (Linux): Immich im Chat und Einfuegen aus der Zwischenablage - Bildschirmfotos mit nachgebautem Server
#   im Container: sh tests/run-container.sh shots   (Dateien linux-v21-NN-name-<hell|dunkel|es>.png)
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockv2  # noqa: E402
import v2shots as V  # noqa: E402 - Aufbau (Server, Chats, Konten) wie bei den 2.0-Bildern

from gi.repository import Adw, Gdk, GLib, GObject, Gtk  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
THEMES = [("hell", Adw.ColorScheme.FORCE_LIGHT)] + ([] if V.LANG == "es" else [("dunkel", Adw.ColorScheme.FORCE_DARK)])


def shoot(g, name, widget=None):
    sz = "-schmal" if V.S.get("narrow") else ""
    g["shot"](os.path.join(g["out"], f"linux-v21-{name}-{'es' if P.startswith('es') else V.S['theme']}{sz}.png"), widget)


def _photo(sky, ground, sun=True, w=480, h=320):
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (w, h), sky)
    d = ImageDraw.Draw(im)
    d.polygon([(0, h), (0, h * 0.62), (w * 0.35, h * 0.45), (w * 0.7, h * 0.6), (w, h * 0.5), (w, h)], fill=ground)
    if sun:
        d.ellipse((w - 120, 30, w - 50, 100), fill=(250, 214, 90))
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()


PHOTOS = [("strand.png", (135, 190, 230), (230, 205, 150)), ("berge.png", (160, 190, 220), (90, 110, 95)),
          ("wiese.png", (170, 210, 235), (110, 170, 90)), ("abend.png", (240, 160, 110), (80, 70, 90)),
          ("see.png", (150, 200, 225), (60, 120, 160)), ("wald.png", (200, 220, 230), (40, 90, 60))]


def start(g):
    V.start(g)
    srv = V.S["srv"]
    for i, (n, sky, gr) in enumerate(PHOTOS):
        data = _photo(sky, gr, sun=i % 2 == 0)
        mockv2.immich_add(srv, f"a{i}", n, "image/png", data, data, _photo(sky, gr, sun=i % 2 == 0, w=240, h=160))


def chat_open(g):
    V.chat_ready(g)
    g["win"].show_route(("chat", V.S["dm"]))


def conv(g):
    return g["win"].current.conv


def menu_open(g):
    conv(g)._attach_menu()


def menu_shot(g):
    c = conv(g)
    pop = next((w for w in _walk(c.att_btn) if isinstance(w, Gtk.Popover)), None)
    shoot(g, "01-chat-anhang-menue")
    if pop is not None:
        shoot(g, "01b-chat-anhang-menue-nah", pop)
        pop.popdown()


def picker_open(g):
    conv(g)._pick_immich()


def picker_shot(g):
    shoot(g, "02-chat-immich-auswahl")
    d = g["win"].get_visible_dialog()
    d._choose({"id": "a0", "name": "strand.png", "mime": "image/png"})


def paste_image(g):
    c = conv(g)
    cb = c.entry.get_clipboard()
    tex = Gdk.Texture.new_from_bytes(GLib.Bytes.new(_photo((200, 200, 205), (70, 75, 90), sun=False, w=640, h=400)))
    cb.set_content(Gdk.ContentProvider.new_for_value(GObject.Value(Gdk.Texture, tex)))
    c.paste(cb)
    c.entry.get_buffer().set_text({"de": "Zwei Fotos vom Wochenende", "es": "Dos fotos del fin de semana"}.get(V.LANG, "Two photos from the weekend"))


def attach_shot(g):
    shoot(g, "03-chat-anhaenge-vorschau")
    conv(g).send()


def sent_shot(g):
    shoot(g, "04-chat-gesendet")
    g["win"].show_root(("page", 3))


def page_paste(g):
    v = g["win"].current
    v.editor.set_focus(v.doc.root[0]["id"])
    cb = v.get_clipboard()
    tex = Gdk.Texture.new_from_bytes(GLib.Bytes.new(_photo((150, 200, 225), (60, 120, 160), w=640, h=360)))
    cb.set_content(Gdk.ContentProvider.new_for_value(GObject.Value(Gdk.Texture, tex)))
    v.paste(cb)


def page_shot(g):
    shoot(g, "05-seite-bild-eingefuegt")


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


steps = [(300, start)]
for _th, _sc in THEMES:
    steps += [(500, V.size(False)), (2500, V.reset(_th, _sc)), (1500, chat_open), (2500, menu_open), (1500, menu_shot),
              (500, picker_open), (3000, picker_shot), (2500, paste_image), (2000, attach_shot), (3000, sent_shot),
              (3000, page_paste), (3000, page_shot)]
steps += [(500, V.size(True)), (2500, V.reset(THEMES[0][0], THEMES[0][1])), (1500, chat_open), (2000, paste_image),
          (2000, attach_shot)]
steps += [(500, lambda g: Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT))]
