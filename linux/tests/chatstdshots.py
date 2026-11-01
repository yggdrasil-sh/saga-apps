# Chat-Standard 2.5: Bildschirmfotos mit nachgebautem Server (tests/mockstd.py), ohne Netz.
#   HK_TEST=tests/chatstdshots.py HK_OUT=<ordner> python3 -m hlidskjalf.main   (Dateien linux-25-chatstd-NN-<name>.png)
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mockserver  # noqa: E402
from mockstd import ref_of  # noqa: E402

S = {}
FEATS = ["chatadm1", "contacts1", "pagechatmembers1", "groupinfo2", "moddel1", "msgpin1", "block2", "report1", "mute2", "chatpin1",
         "markunread1", "chathide1", "chatmention1", "chatfmt1", "unfurl1", "chatmedia1"]


def check(name, ok):
    print(f"{'OK  ' if ok else 'FEHLER'} {name}", flush=True)


def shoot(g, name, dialog=False):
    g["shot"](os.path.join(g["out"], f"linux-25-chatstd-{name}.png"))


def start(g):
    srv = S["srv"] = mockserver.install()
    srv.v25 = True
    srv.extra_features = list(FEATS)
    srv.chatadm = srv.contacts_on = srv.chatstd = True
    g["store"].logged_in()
    g["store"].check_version()
    g["win"].set_default_size(1100, 760)


def setup(g):
    from hlidskjalf import chatcrypto as C
    from hlidskjalf.chat import chat
    chat.refresh()
    S["rk"] = C.recovery_key()


def ready(g):
    from hlidskjalf.chat import chat
    k = chat.setup(S["rk"])
    chat.adopt_keys(k)


def data(g):
    srv = S["srv"]
    for u, n in (("anna", "Anna Beispiel"), ("bert", "Bert Beispiel"), ("cora", "Cora Muster")):
        srv.chat_peer(u, n)
    srv.groups["team"] = ["test", "anna", "bert"]
    g1 = S["g"] = srv.new_chat("free", ["test", "anna", "bert", "cora"], admin="test")
    srv.peer_send(g1, "anna", "Hallo zusammen! *Wichtig:* morgen um _10 Uhr_ im ~Raum 2~ Raum 3.")
    srv.peer_send(g1, "bert", f"<@{ref_of('test')}> kannst du die Liste machen?\n- Brot\n- `Kaese <500 g>`\n> Zitat aus der Mail")
    srv.peer_send(g1, "cora", "Nachricht von Cora")
    srv.peer_send(g1, "anna", "```\nprint('<b>kein Markup</b>')\n```")
    srv.chats[g1]["pins"] = [srv.chats[g1]["msgs"][0]["id"]]
    srv.blocks.append({"target": ref_of("cora"), "name": "Cora Muster", "local": True})
    S["dm"] = srv.new_chat("dm", ["test", "anna"])
    srv.peer_send(S["dm"], "anna", "Bis gleich!")
    S["gg"] = srv.new_chat("group", ["test", "anna", "bert"], group="team")
    S["pc"] = srv.new_chat("page", ["test", "anna", "bert"], note_id=1)
    srv.page_via[(S["pc"], "bert")] = "group"
    from mockstd import _p
    import time
    _p(srv.chats[S["dm"]], "test").update(pinned=True, pinned_at=time.time())
    _p(srv.chats[S["gg"]], "test").update(muted_until=int(time.time()) + 8 * 3600)
    _p(srv.chats[S["pc"]], "test").update(unread_mark=True)
    hid = srv.new_chat("group", ["test", "anna"], group="alt")
    _p(srv.chats[hid], "test").update(hidden=True)
    srv.unfurl["https://news.example/artikel"] = {"url": "https://news.example/artikel", "title": "Ein Artikel zum Test",
                                                  "desc": "Kurz und knapp erklaert, was neu ist.", "site": "Testblatt"}
    from hlidskjalf.chat import chat
    chat.load_chats()


def open_group(g):
    g["win"].show_route(("chat", S["g"]))


def check_group(g):
    conv = g["win"].current.conv
    check("Pins-Leiste sichtbar", conv.pin_bar.get_visible())
    check("eingeklappte Nachricht", any("blocked person" in (getattr(w, "get_label", lambda: "")() or "")
                                         for w in _walk(conv.msgs)))


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def shot_group(g):
    shoot(g, "01-unterhaltung")


def type_mention(g):
    conv = g["win"].current.conv
    conv.entry.get_buffer().set_text("Danke @An")


def shot_mention(g):
    shoot(g, "02-erwaehnen")


def type_link(g):
    conv = g["win"].current.conv
    if conv.people_items:
        conv._people_pick(conv.people_items[0])
    b = conv.entry.get_buffer()
    b.insert(b.get_end_iter(), "lies mal https://news.example/artikel")


def shot_link(g):
    check("Linkvorschau", g["win"].current.conv.unf_box.get_visible())
    shoot(g, "03-linkvorschau")


def send_it(g):
    g["win"].current.conv.send()


def shot_sent(g):
    shoot(g, "04-gesendet")


def open_info(g):
    from hlidskjalf.chatview import GroupInfoDialog
    from hlidskjalf.chat import chat
    from hlidskjalf import chatstdview as SV
    from PIL import Image
    b = io.BytesIO()
    Image.new("RGB", (300, 300), (53, 132, 228)).save(b, "PNG")
    SV.set_group_desc(S["g"], "Planung fuer das Sommerfest – Ideen, Termine und Einkaeufe.", done=lambda: None, error=print)
    SV.set_group_avatar(S["g"], b.getvalue(), done=lambda f: None, error=print)
    chat.load_chat(S["g"])
    S["gi"] = GroupInfoDialog(g["win"], S["g"])
    S["gi"].present(g["win"])


def shot_info(g):
    shoot(g, "05-gruppeninfo")


def scroll_info(g):
    from gi.repository import Gtk
    for w in _walk(S["gi"]):
        if isinstance(w, Gtk.ScrolledWindow):
            adj = w.get_vadjustment()
            adj.set_value(adj.get_upper())


def shot_info2(g):
    shoot(g, "06-gruppeninfo-mitglieder")


def open_page(g):
    from hlidskjalf.chatview import GroupInfoDialog
    S["gi"].force_close()
    S["pi"] = GroupInfoDialog(g["win"], S["pc"])
    S["pi"].present(g["win"])
    S["pi"]._toggle_add()


def shot_page(g):
    shoot(g, "07-seitenchat-mitglieder")


def open_list(g):
    from hlidskjalf.chatview import ChatList
    S["pi"].force_close()
    ChatList.hid_open = True
    g["win"].show_route(("chats",))


def shot_list(g):
    shoot(g, "08-chatliste")


def open_media(g):
    g["win"].show_route(("chat", S["g"]))


def open_media2(g):
    from hlidskjalf.chatstdview import MediaDialog
    S["md"] = MediaDialog(g["win"].current.conv, S["g"])
    S["md"].present(g["win"])
    S["md"].stack.set_visible_child_name("links")


def shot_media(g):
    shoot(g, "09-medien-links")


def select(g):
    S["md"].force_close()
    from hlidskjalf.chat import chat
    conv = g["win"].current.conv
    ms = [m for m in chat.cache[S["g"]]["msgs"] if m.get("state") == "ok"]
    conv.sel_start(ms[0]["id"])
    conv._sel_toggle(ms[1]["id"], True)
    conv.render()


def shot_select(g):
    shoot(g, "10-auswahl")


def report(g):
    from hlidskjalf.chat import chat
    from hlidskjalf.chatstdview import ReportDialog
    conv = g["win"].current.conv
    conv.sel_end()
    m = [x for x in chat.cache[S["g"]]["msgs"] if x.get("state") == "ok" and not x.get("mine")][0]
    S["rd"] = ReportDialog(g["win"], chat.chat(S["g"]), [m])
    S["rd"].present(g["win"])


def shot_report(g):
    shoot(g, "11-melden")


def admin_only(g):
    S["rd"].force_close()
    srv = S["srv"]
    srv.chats[S["g"]]["perm_send"] = "admins"
    mockserver_admins = __import__("mockv2").admins(srv.chats[S["g"]])
    mockserver_admins.discard("test")
    mockserver_admins.add("anna")
    from hlidskjalf.chat import chat
    chat.load_chat(S["g"])


def shot_admin_only(g):
    shoot(g, "12-nur-admins-schreiben")


def narrow(g):
    g["win"].set_default_size(380, 760)
    g["win"].show_route(("chat", S["g"]))


def shot_narrow(g):
    shoot(g, "13-schmal")


def dark(g):
    from gi.repository import Adw
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    g["win"].set_default_size(1100, 760)
    S["srv"].chats[S["g"]]["perm_send"] = "all"
    from hlidskjalf.chat import chat
    chat.load_chat(S["g"])


def shot_dark(g):
    shoot(g, "14-dunkel")


def done(g):
    g["win"].get_application().quit()


steps = [(300, start), (600, setup), (800, ready), (800, data), (1200, open_group), (1500, check_group), (200, shot_group),
         (200, type_mention), (700, shot_mention), (200, type_link), (1500, shot_link), (200, send_it), (1800, shot_sent),
         (300, open_info), (2500, shot_info), (300, scroll_info), (600, shot_info2), (300, open_page), (1200, shot_page),
         (300, open_list), (1500, shot_list), (300, open_media), (1200, open_media2), (2000, shot_media), (200, select),
         (1000, shot_select), (200, report), (1000, shot_report), (300, admin_only), (1500, shot_admin_only), (300, narrow),
         (1500, shot_narrow), (300, dark), (1500, shot_dark), (300, done)]
