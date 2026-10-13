# Offline: alle Blocktypen zeichnen (erfundene Seite, nichts wird gespeichert)
from hlidskjalf import editor as E
from hlidskjalf.api import client

client.on_need_login = None
PAGE = {"id": 999999, "title": "Blocktypen-Test", "icon": "🧪", "format": "blocks", "rev": 1, "tags": ["linux", "test"], "updated": 1791700000,
        "body": None}
BLOCKS = [
    {"id": "h1", "type": "header", "data": {"text": "Überschrift 1", "level": 1}},
    {"id": "p1", "type": "paragraph", "data": {"text": 'Text mit <b>fett</b>, <i>kursiv</i>, <u>unter</u>, <s>durch</s>, <mark>markiert</mark>, '
                                                       '<code>code</code>, <a href="https://example.org">Link</a>, <a href="/notes#1">Seite</a> und '
                                                       '<time datetime="2026-10-12" class="hk-date">Mo., 12. Okt.</time>.'}},
    {"id": "h2", "type": "header", "data": {"text": "Listen", "level": 2}},
    {"id": "l1", "type": "list", "data": {"style": "checklist", "items": [
        {"content": "Erledigt", "meta": {"checked": True}, "items": []},
        {"content": "Offen mit Unterpunkt", "meta": {"checked": False}, "items": [{"content": "Unterpunkt", "meta": {"checked": False}, "items": []}]}]}},
    {"id": "l2", "type": "list", "data": {"style": "ordered", "items": [{"content": "Eins", "meta": {}, "items": []}, {"content": "Zwei", "meta": {}, "items": []}]}},
    {"id": "q1", "type": "quote", "data": {"text": "Ein Zitat, kursiv mit Linie."}},
    {"id": "c1", "type": "callout", "data": {"emoji": "💡", "text": "Ein Hinweis in einem grauen Kasten."}},
    {"id": "t1", "type": "toggle", "data": {"title": "Aufklappbar", "open": True, "blocks": [{"id": "tp", "type": "paragraph", "data": {"text": "Innen"}}]}},
    {"id": "k1", "type": "code", "data": {"code": "sudo dnf install hlidskjalf\necho ok", "language": "Bash"}},
    {"id": "d1", "type": "delimiter", "data": {}},
    {"id": "tb", "type": "table", "data": {"withHeadings": True, "content": [["Name", "Wert"], ["Alpha", "1"], ["Beta", "2"]]}},
    {"id": "co", "type": "columns", "data": {"cols": [{"blocks": [{"id": "cl", "type": "paragraph", "data": {"text": "Linke Spalte"}}]},
                                                       {"blocks": [{"id": "cr", "type": "paragraph", "data": {"text": "Rechte Spalte"}}]}]}},
    {"id": "em", "type": "embed", "data": {"url": "https://www.youtube.com/watch?v=abc", "title": "Ein Video"}},
    {"id": "at", "type": "attaches", "data": {"file": {"url": "/files/x", "name": "Handbuch.pdf", "size": 1234567}, "title": "Handbuch.pdf"}},
    {"id": "bt", "type": "buttons", "data": {"buttons": [{"label": "Neue Notiz", "icon": "📝", "action": ""}, {"label": "Aufgabe", "icon": "✅", "action": "task"}]}},
    {"id": "pe", "type": "paragraph", "data": {"text": ""}},
]


def fake_load(self, force=False):
    import json
    p = dict(PAGE, body=json.dumps({"blocks": BLOCKS}))
    self._apply(p, None)
    if self.on_loaded:
        self.on_loaded(self)


E.PageDoc.load = fake_load
E.PageDoc.save = lambda self, done=None: done and done()


def open_page(g):
    w = g["win"]
    w.stack.set_visible_child_name("main")
    w.show_root(("page", 999999))


def shot1(g):
    v = g["win"].current
    print("Fokus-Felder:", len(v.kit.order), flush=True)
    for k, rt in v.kit.order:
        print("  RT", k, rt.get_width(), "x", rt.get_height(), repr(rt.text()[:30]), rt.get_visible(), rt.get_mapped(), flush=True)
    g["shot"](g["out"] + "/20-bloecke.png")
    adj = v.scroll.get_vadjustment()
    adj.set_value(adj.get_upper())


def shot2(g):
    g["shot"](g["out"] + "/21-bloecke-unten.png")


def hover(g):
    v = g["win"].current
    from hlidskjalf.blocks import BlockRow
    rows = []

    def walk(w):
        if isinstance(w, BlockRow):
            rows.append(w)
        c = w.get_first_child()
        while c is not None:
            walk(c)
            c = c.get_next_sibling()
    walk(v)
    for r in rows:
        if r.bid in ("p1", "cr"):
            r.show_handle(True)
    v.scroll.get_vadjustment().set_value(0)


def shot3(g):
    g["shot"](g["out"] + "/22-griffe.png")


steps = [(1500, open_page), (2000, shot1), (800, shot2), (300, hover), (800, shot3)]


def debug(g):
    from gi.repository import Gtk
    v = g["win"].current
    for key in ("l1/0", "l2/0"):
        rt = v.kit.by_key[key]
        print("MEASURE", key, rt.measure(Gtk.Orientation.VERTICAL, 707)[:2], flush=True)
        w = rt
        for _ in range(5):
            print("   ", type(w).__name__, w.get_width(), "x", w.get_height(), "valign", w.get_valign().value_nick, "vexp", w.get_vexpand(), flush=True)
            w = w.get_parent()


steps.append((300, debug))
