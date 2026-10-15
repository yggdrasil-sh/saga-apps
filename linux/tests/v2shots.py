# Saga 2.0 mit nachgebautem Server ansehen (Bildschirmfotos), hell und dunkel, breit und schmal:
#   Benachrichtigungen (Glocke), Anwesenheit + fremde Aenderung, Chats (Liste, Unterhaltung, Einrichtung, neues Geraet,
#   Geraet bestaetigen, Sicherheitsnummer), Einstellungen (Mitteilungen, Chats & Geraete), KI-Assistent, Foederation
#   im Container: HK_OUT=... sh tests/run-container.sh shots   (Dateien linux-v2-NN-name-<hell|dunkel>[-schmal].png)
import io
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockserver  # noqa: E402

from gi.repository import Adw, GLib  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
LANG = (os.environ.get("LANG") or "C")[:2]
THEMES = [("hell", Adw.ColorScheme.FORCE_LIGHT)] + ([] if LANG == "es" else [("dunkel", Adw.ColorScheme.FORCE_DARK)])
WIDE, NARROW = (1280, 820), (412, 860)
EXAMPLE = "https://saga.example.org"
S = {}


def shoot(g, name, widget=None):
    sz = "-schmal" if S.get("narrow") else ""
    g["shot"](os.path.join(g["out"], f"linux-v2-{name}-{'es' if P.startswith('es') else S['theme']}{sz}.png"), widget)


def dlg(g):
    return g["win"].get_visible_dialog()


def close_all(g):
    for _ in range(4):
        d = dlg(g)
        if d is not None:
            d.force_close()
    try:
        g["win"].bell.pop.popdown()
    except AttributeError:
        pass


def size(narrow):
    def f(g):
        S["narrow"] = narrow
        w, h = NARROW if narrow else WIDE
        g["win"].unmaximize()
        g["win"].set_default_size(w, h)
    return f


def _png(w=360, h=220):
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (w, h), (120, 170, 110))
    d = ImageDraw.Draw(im)
    for i in range(0, w, 30):
        d.rectangle((i, h - 70 - (i % 90), i + 22, h - 10), fill=(90, 60, 35))
    d.ellipse((w - 90, 20, w - 30, 80), fill=(250, 220, 90))
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()


def start(g):
    from hlidskjalf import chatcrypto as C
    mockserver.SERVER = EXAMPLE                               # Beispieladresse in den Bildern (Link, Konto)
    srv = S["srv"] = mockserver.install(EXAMPLE)
    now = time.time()
    srv.names.update({"anna": "Anna Beispiel", "bert": "Bert Beispiel", "cora": "Cora Muster"})
    srv.notes[1]["title"] = "Projektplan Haus"
    srv.shares[1] = [{"type": "user", "target": "bert", "role": "edit"}]
    # Benachrichtigungen
    srv.add_notif("publish.withdrawn", actor=None, created=now - 86400 * 2, read=True, note={"id": 3, "title": "Notizen", "icon": "📝"})
    srv.add_notif("share.added", role="edit", created=now - 7200, read=True, note={"id": 1, "title": "Projektplan Haus", "icon": "📘"})
    srv.add_notif("comment.reply", count=3, created=now - 1500, actor={"name": "Bert Beispiel", "username": "bert"}, thread_id=5,
                  excerpt="Lärche ist günstiger und hält länger.", note={"id": 1, "title": "Projektplan Haus", "icon": "📘"})
    srv.add_notif("chat.message", created=now - 300, title="Anna Beispiel", chat={"id": 1, "kind": "dm"}, url="/chats#c1", note=None)
    srv.add_notif("comment.new", created=now - 120, actor={"name": "Cora Muster", "username": "cora"}, access=False, note=None)
    # Chat: Gegenueber, Unterhaltungen
    srv.chat_peer("anna", "Anna Beispiel")
    srv.chat_peer("bert", "Bert Beispiel")
    srv.chat_peer("cora", "Cora Muster", ready=False)
    S["dm"] = srv.new_chat("dm", ["test", "anna"])
    S["team"] = srv.new_chat("group", ["test", "anna", "bert"], group="team")
    S["free"] = srv.new_chat("free", ["test", "bert", "cora"], admin="test")
    srv.groups["team"] = ["test", "anna", "bert"]
    # KI und Foederation
    srv.ai_status = {"enabled": True, "host": "ollama.lan:11434", "model": "llama3", "provider": "ollama", "quota": 50, "used": 3,
                     "quota_left": 47, "history_allowed": True, "history": False, "targets": ["en", "de", "es"], "q_max": 1000,
                     "blocks_max": 50, "notice": {"de": "Inhalte, die du dem Assistenten gibst, gehen an ollama.lan:11434.",
                                                  "es": "El contenido que le das al asistente se envía a ollama.lan:11434."}.get(
                         LANG, "Content you give the assistant is sent to ollama.lan:11434.")}
    srv.ai_reply = ["Laut Plan wird das Hochbeet im Mai gebaut [1]; ", "das Budget liegt bei etwa 300 € [2]. ",
                    "Offen ist noch die Bestellung der Erde."]
    srv.ai_sources = [{"n": 1, "id": 1, "title": "Projektplan Haus"}, {"n": 2, "id": 3, "title": "Notizen"},
                      {"n": 3, "id": -2049, "title": "Gartenplanung Nord", "owner": "Anna Eigner (anna@b.example)"}]
    srv.add_fed(-2049, "Gartenplanung Nord", host="b.example", owner="Anna Eigner", role="edit", blocks=[
        {"id": "r1", "type": "header", "data": {"text": "Beete und Wege", "level": 2}},
        {"id": "r2", "type": "paragraph", "data": {"text": "Die Beete liegen auf dem Server von Anna. Hier lesen, kommentieren und "
                                                          "bearbeiten wir sie gemeinsam."}},
        {"id": "r3", "type": "list", "data": {"style": "checklist", "meta": {}, "items": [
            {"content": "Wege mit Rindenmulch", "meta": {"checked": True}, "items": []},
            {"content": "Kompost umsetzen", "meta": {"checked": False}, "items": []}]}}])
    srv.fed_unreachable = ["c.example"]
    srv.presence_users = [{"username": "bert", "name": "Bert Beispiel", "editing": True},
                          {"username": "anna", "name": "Anna Beispiel", "editing": False}]
    g["store"].logged_in()
    S["C"] = C


def chat_ready(g):
    """dieses Geraet einrichten, Nachrichten der anderen"""
    from hlidskjalf.chat import chat
    srv, C = S["srv"], S["C"]
    if not chat.ready:
        k = chat.setup(C.recovery_key())
        chat.adopt_keys(k)
    dm, team, free = S["dm"], S["team"], S["free"]
    if not srv.chats[dm]["msgs"]:
        m1 = srv.peer_send(dm, "anna", {"de": "Hallo! Hast du den Plan fürs Hochbeet gesehen? [[note:1]]",
                                        "es": "¡Hola! ¿Has visto el plan del bancal? [[note:1]]"}.get(LANG, "Hi! Did you see the plan "
                                                                                                       "for the raised bed? [[note:1]]"))
        enc = C.encrypt(dm, "test", chat.keys["device"], {"v": 1, "type": "text", "text": {"de": "Ja, sieht gut aus 👍",
                                                                                           "es": "Sí, tiene buena pinta 👍"}.get(LANG, "Yes, looks good 👍")},
                        _rmap(srv, dm), reply_to=m1["id"])
        from mockv2 import _store_msg
        _store_msg(srv, dm, "test", enc)
        data = _png()
        blob, key = C.encrypt_file(dm, data)
        srv.cfiles["bildfid1"] = (dm, "anna", blob, None)
        srv.peer_send(dm, "anna", {"de": "So könnte es aussehen:", "es": "Así podría quedar:"}.get(LANG, "This is how it could look:"),
                      files=[{"id": "bildfid1", "k": C.b64(key), "n": "hochbeet.png", "m": "image/png", "s": len(data)}])
        srv.peer_send(dm, "anna", {"de": "Erde bestelle ich morgen.", "es": "Mañana pido la tierra."}.get(LANG, "I'll order the soil tomorrow."))
        srv.peer_send(team, "bert", {"de": "Treffen am Samstag um 10?", "es": "¿Quedamos el sábado a las 10?"}.get(LANG, "Meet on Saturday at 10?"))
        srv.peer_send(team, "anna", {"de": "Passt mir.", "es": "Me va bien."}.get(LANG, "Works for me."), forge=True)
        enc = C.encrypt(free, "test", chat.keys["device"], {"v": 1, "type": "title", "title": {"de": "Werkzeug-Ausleihe", "es": "Préstamo de herramientas"}.get(
            LANG, "Tool library")}, _rmap(srv, free))
        _store_msg(srv, free, "test", enc, "title")
    chat.load_chats()


def _rmap(srv, cid):
    from mockv2 import _recipients
    C = S["C"]
    rc, _w = _recipients(srv, srv.chats[cid]["members"])
    out = {}
    for r in rc:
        if r["ready"]:
            out["backup:" + r["username"]] = C.unb64(r["backup_pub"])
            for d in r["devices"]:
                out[d["id"]] = C.unb64(d["enc_pub"])
    return out


def reset(theme, scheme):
    def f(g):
        S["theme"] = theme
        Adw.StyleManager.get_default().set_color_scheme(scheme)
        close_all(g)
        g["store"].refresh()
        g["win"].show_root(("page", 1))
    return f


# --- Szenen -----------------------------------------------------------------------------------------------------------
def bell_open(g):
    from hlidskjalf.notifications import notifications
    notifications.load()
    g["win"].split.set_show_sidebar(True)
    g["win"].bell.popup()


def bell_shot(g):
    shoot(g, "01-benachrichtigungen", g["win"].bell.pop)
    close_all(g)
    if g["win"].split.get_collapsed():                        # schmal: Seitenleiste wieder zu (Anwesenheit sichtbar)
        g["win"].split.set_show_sidebar(False)
    v = g["win"].current
    if v.presence is not None:
        v.presence.beat()


def presence_change(g):
    v = g["win"].current
    v.changed_banner.set_title(GLib.markup_escape_text(_t("{name} changed this page").format(name="Bert Beispiel")))
    v.changed_banner.set_revealed(True)


def presence_shot(g):
    shoot(g, "02-anwesenheit-geaendert")
    g["win"].current.changed_banner.set_revealed(False)


def chats_open(g):
    chat_ready(g)
    g["win"].show_route(("chat", S["dm"]))


def typing(g):
    from hlidskjalf.chat import chat
    chat.typing[S["dm"]] = {"anna": ("Anna Beispiel", time.monotonic() + 60)}
    chat.emit("typing", S["dm"])


def chats_shot(g):
    shoot(g, "03-chat")


def group_open(g):
    g["win"].show_route(("chat", S["team"]))


def group_shot(g):
    shoot(g, "04-gruppen-chat")


def unv_open(g):
    from gi.repository import Gtk
    stack, btn = [g["win"].current], None
    while stack and btn is None:
        w = stack.pop()
        if isinstance(w, Gtk.Button) and "hk-unv" in w.get_css_classes():
            btn = w
            break
        c = w.get_first_child()
        while c is not None:
            stack.append(c)
            c = c.get_next_sibling()
    if btn is not None:
        btn.emit("clicked")


def unv_shot(g):
    shoot(g, "17-echtheit-nicht-bestaetigt")
    close_all(g)


def list_open(g):
    g["win"].show_root(("chats",))


def list_shot(g):
    shoot(g, "05-chatliste")


def safety_open(g):
    from hlidskjalf.chatview import open_safety
    g["win"].show_route(("chat", S["dm"]))
    open_safety(g["win"], S["dm"], "anna")


def safety_shot(g):
    shoot(g, "06-sicherheitsnummer")
    close_all(g)


def setup_open(g):
    from hlidskjalf.chat import chat
    S["saved"] = (chat.status, chat.keys)
    chat.status = "setup"
    g["win"].show_root(("chats",))
    chat.emit("changed")


def setup_shot(g):
    shoot(g, "07-chats-einrichten")
    from hlidskjalf.chatview import SetupDialog
    d = SetupDialog(g["win"])
    d.present(g["win"])
    d._create()


def key_shot(g):
    shoot(g, "08-wiederherstellungsschluessel")
    close_all(g)
    from hlidskjalf.chat import chat
    chat.status = "device"
    chat.emit("changed")


def device_shot(g):
    shoot(g, "09-neues-geraet")
    from hlidskjalf.chat import chat
    chat.status, chat.keys = S["saved"]
    chat.emit("changed")
    C = S["C"]
    dev = C.device()
    from hlidskjalf.chatview import approve_link
    approve_link(g["win"], {"id": "l1", "device_id": dev["id"], "name": "Laptop (Linux)", "enc_pub": C.b64(dev["enc_pk"]),
                            "sign_pub": C.b64(dev["sign_pk"])})


def approve_shot(g):
    shoot(g, "10-geraet-bestaetigen")
    close_all(g)
    g["win"].settings_dialog(page="push")


def push_shot(g):
    shoot(g, "11-mitteilungen")
    dlg(g).show_page("chats")


def devices_shot(g):
    shoot(g, "12-chats-und-geraete")
    close_all(g)
    g["win"].show_root(("page", 1))


def ai_open(g):
    g["win"].assistant()
    d = dlg(g)
    if d is not None:
        d.q.get_buffer().set_text({"de": "Was ist beim Garten noch offen?", "es": "¿Qué queda pendiente en el jardín?"}.get(
            LANG, "What is still open for the garden?"))
        d.ask()


def ai_shot(g):
    shoot(g, "13-assistent-frage")
    d = dlg(g)
    if d is not None:
        d.run_action("tasks")


def ai_page_shot(g):
    shoot(g, "14-assistent-diese-seite")
    close_all(g)
    g["win"].show_root(("page", -2049))


def fed_shot(g):
    shoot(g, "15-andere-instanz")
    g["win"].share_dialog(1)


def share_remote(g):
    d = dlg(g)
    if d is not None:
        d.st["federation"] = True
        d.q.set_text("bert@b.example")


def share_shot(g):
    shoot(g, "16-teilen-name-at-server")
    close_all(g)


def _t(s):
    from hlidskjalf.i18n import _
    return _(s)


steps = [(300, start)]
for _th, _sc in THEMES:
    for _narrow in (False, True):
        steps += [(500, size(_narrow)), (2500, reset(_th, _sc)), (1500, bell_open), (1500, bell_shot), (2500, presence_change),
                  (1200, presence_shot), (1500, chats_open), (2500, typing), (1500, chats_shot), (1500, group_open), (2000, group_shot), (500, unv_open), (1500, unv_shot),
                  (800, list_open), (1500, list_shot), (800, safety_open), (2000, safety_shot), (800, setup_open), (1500, setup_shot),
                  (1500, key_shot), (1500, device_shot), (1500, approve_shot), (2500, push_shot), (2000, devices_shot), (800, ai_open),
                  (3000, ai_shot), (3000, ai_page_shot), (2500, fed_shot), (1500, share_remote), (2000, share_shot)]
steps += [(500, lambda g: Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT))]
