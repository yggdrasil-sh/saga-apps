# Thai und Russisch (und jede andere Sprache aus LANG): Bildschirmfotos der wichtigsten Ansichten mit nachgebautem Server,
# um Schrift (Thai-Glyphen), Zeilenumbruch (Thai ohne Leerzeichen) und Textlaenge (Russisch) zu pruefen - breit und schmal.
#   im Container: sh tests/run-container.sh thrushots   (Dateien linux-thru-NN-name-<sprache>[-schmal].png)
# Prueft ausserdem, dass Thai-Texte mit einer Schrift gesetzt werden, die Thai kann (keine Ersatzkaestchen).
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import v2shots as V  # noqa: E402 - Aufbau (Server, Chats, Konten) wie bei den 2.0-Bildern

from gi.repository import Adw, Gtk, Pango  # noqa: E402

LANG = V.LANG
S = {"err": []}
TXT = {
    "th": {"title": "แผนโครงการบ้าน", "h": "แปลงผักยกพื้น",
           "p": "เราจะสร้างแปลงผักยกพื้นในเดือนพฤษภาคม งบประมาณประมาณ 300 ยูโร ไม้สนชนิดนี้ราคาถูกกว่าและใช้งานได้นานกว่า "
                "ต้องสั่งดินและปุ๋ยหมักล่วงหน้าอย่างน้อยหนึ่งสัปดาห์",
           "c1": "สั่งไม้", "c2": "สั่งดิน", "q": "สวนยังเหลืออะไรที่ต้องทำบ้าง",
           "ai": ["ตามแผน แปลงผักยกพื้นจะสร้างในเดือนพฤษภาคม [1] ", "งบประมาณประมาณ 300 ยูโร [2] ", "ที่ยังค้างอยู่คือการสั่งดิน"],
           "m1": "สวัสดี! เห็นแผนแปลงผักยกพื้นหรือยัง [[note:1]]", "m2": "เห็นแล้ว ดูดีมาก 👍", "m3": "พรุ่งนี้ฉันจะสั่งดิน",
           "m4": "เจอกันวันเสาร์ 10 โมงไหม", "m5": "ได้เลย"},
    "ru": {"title": "План проекта «Дом»", "h": "Приподнятая грядка",
           "p": "Грядку строим в мае, бюджет около 300 €. Лиственница дешевле и служит дольше. Землю и компост нужно заказать "
                "заранее – минимум за неделю до начала работ.",
           "c1": "Заказать древесину", "c2": "Заказать землю", "q": "Что ещё осталось сделать в саду?",
           "ai": ["По плану грядку строят в мае [1]; ", "бюджет – около 300 € [2]. ", "Открытым остаётся заказ земли."],
           "m1": "Привет! Видел план грядки? [[note:1]]", "m2": "Да, выглядит отлично 👍", "m3": "Землю закажу завтра.",
           "m4": "Встречаемся в субботу в 10?", "m5": "Мне подходит."},
}.get(LANG) or {"title": "House project plan", "h": "Raised bed", "p": "We build the raised bed in May; the budget is about 300 €.",
                "c1": "Order wood", "c2": "Order soil", "q": "What is still open for the garden?",
                "ai": ["According to the plan the raised bed is built in May [1]; ", "the budget is about €300 [2]. ",
                       "Ordering the soil is still open."],
                "m1": "Hi! Did you see the plan for the raised bed? [[note:1]]", "m2": "Yes, looks good 👍",
                "m3": "I'll order the soil tomorrow.", "m4": "Meet on Saturday at 10?", "m5": "Works for me."}


def shoot(g, name, widget=None):
    sz = "-schmal" if V.S.get("narrow") else ""
    g["shot"](os.path.join(g["out"], f"linux-thru-{name}-{LANG}{sz}.png"), widget)


def start(g):
    V.start(g)
    srv = V.S["srv"]
    srv.notes[1]["title"] = TXT["title"]
    srv.notes[1]["body"] = json.dumps({"blocks": [
        {"id": "t1", "type": "header", "data": {"text": TXT["h"], "level": 2}},
        {"id": "t2", "type": "paragraph", "data": {"text": TXT["p"]}},
        {"id": "t3", "type": "list", "data": {"style": "checklist", "meta": {}, "items": [
            {"content": TXT["c1"], "meta": {"checked": True}, "items": []},
            {"content": TXT["c2"], "meta": {"checked": False}, "items": []}]}}]})
    srv.ai_reply = TXT["ai"]
    srv.ai_sources = [{"n": 1, "id": 1, "title": TXT["title"]}, {"n": 2, "id": 3, "title": "Notizen"}]


def chat_fill(g):
    """dieses Geraet einrichten, Nachrichten in der Sprache des Tests"""
    from hlidskjalf.chat import chat
    from mockv2 import _store_msg
    srv, C = V.S["srv"], V.S["C"]
    if not chat.ready:
        chat.adopt_keys(chat.setup(C.recovery_key()))
    dm, team = V.S["dm"], V.S["team"]
    if not srv.chats[dm]["msgs"]:
        m1 = srv.peer_send(dm, "anna", TXT["m1"])
        enc = C.encrypt(dm, "test", chat.keys["device"], {"v": 1, "type": "text", "text": TXT["m2"]}, V._rmap(srv, dm),
                        reply_to=m1["id"])
        _store_msg(srv, dm, "test", enc)
        srv.peer_send(dm, "anna", TXT["m3"])
        srv.peer_send(team, "bert", TXT["m4"])
        srv.peer_send(team, "anna", TXT["m5"])
    chat.load_chats()


def reset(g):
    V.S["theme"] = "hell"
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT)
    V.close_all(g)
    g["store"].refresh()
    g["win"].stack.set_visible_child_name("login")


def login_shot(g):
    shoot(g, "01-anmelden")
    g["win"].stack.set_visible_child_name("main")
    g["win"].show_root(("page", 1))


def editor_shot(g):
    shoot(g, "02-editor")
    if LANG == "th":
        check_thai_font(g["win"].current)
    g["win"].settings_dialog()


def settings_shot(g):
    shoot(g, "03-einstellungen")
    V.close_all(g)
    chat_fill(g)
    g["win"].show_root(("chats",))


def list_shot(g):
    shoot(g, "04-chatliste")
    g["win"].show_route(("chat", V.S["dm"]))


def chat_shot(g):
    shoot(g, "05-chat")
    g["win"].show_root(("page", 1))
    g["win"].assistant()
    d = V.dlg(g)
    if d is not None:
        d.q.get_buffer().set_text(TXT["q"])
        d.ask()


def ai_shot(g):
    shoot(g, "06-ki")
    V.close_all(g)
    from hlidskjalf.chatview import DeleteChatDialog
    g["win"].show_route(("chat", V.S["dm"]))
    d = DeleteChatDialog(g["win"], V.S["dm"])
    d.present(g["win"])
    d.r_all.set_active(True)


def del_shot(g):
    shoot(g, "07-loeschen")
    V.close_all(g)


def check_thai_font(view):
    """Thai-Text muss ohne unbekannte Glyphen gesetzt werden (Pango: unknown_glyphs_count == 0)"""
    stack, n = [view], 0
    while stack:
        w = stack.pop()
        if isinstance(w, Gtk.TextView):
            buf = w.get_buffer()
            txt = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
            if any("฀" <= c <= "๿" for c in txt):
                lay = w.create_pango_layout(txt)
                n += 1
                if lay.get_unknown_glyphs_count():
                    S["err"].append("Thai-Glyphen fehlen")
                    print(f"FEHLER Thai ohne Schrift: {lay.get_unknown_glyphs_count()} unbekannte Glyphen", flush=True)
        c = w.get_first_child()
        while c is not None:
            stack.append(c)
            c = c.get_next_sibling()
    lay = Pango.Layout.new(view.get_pango_context())
    lay.set_text(TXT["p"], -1)
    lay.set_width(200 * Pango.SCALE)
    lay.set_wrap(Pango.WrapMode.WORD_CHAR)
    print(f"Thai: {n} Textfelder geprueft, Umbruch auf 200 px: {lay.get_line_count()} Zeilen,",
          f"unbekannte Glyphen: {lay.get_unknown_glyphs_count()}", flush=True)
    if lay.get_unknown_glyphs_count():
        S["err"].append("Thai-Glyphen fehlen")
        print("FEHLER Thai-Schrift fehlt", flush=True)


def done(g):
    print("THRU", LANG, "OK" if not S["err"] else "FEHLER " + ", ".join(S["err"]), flush=True)


steps = [(300, start)]
for _narrow in (False, True):
    steps += [(500, V.size(_narrow)), (2500, reset), (1500, login_shot), (3000, editor_shot), (2000, settings_shot),
              (2500, list_shot), (2500, chat_shot), (4000, ai_shot), (1500, del_shot)]
steps += [(300, done)]
