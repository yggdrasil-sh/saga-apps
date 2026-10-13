# Editor-Test auf einer eigenen Testseite (wird am Ende endgueltig geloescht)
import json

from gi.repository import Gdk

S = {}


def create(g):
    st = g["store"]

    def made(pid):
        S["pid"] = pid
        print("Testseite:", pid, flush=True)
        g["win"].show_root(("page", pid))
    st.new_page(None, done=made, title="")


def view(g):
    return g["win"].current


def rt_of(g, key=None):
    v = view(g)
    if key is None:
        return v.kit.order[0][1]
    return v.kit.by_key.get(key)


def type_title(g):
    v = view(g)
    old = v.doc.on_error
    v.doc.on_error = lambda m: (print("DOC-FEHLER:", m, flush=True), old(m))
    import hlidskjalf.editor as E
    orig = E.PageDoc.save

    def traced(self, done=None):
        import json as _j, time as _t
        txt = _j.dumps(self.root, ensure_ascii=False)
        print("SAVE", round(_t.time() % 100, 2), "rev", self.rev, "saving", self._saving, "dirty", self.dirty, "time-tag" if "<time" in txt else ("@morgen" if "@morgen" in txt else "-"), flush=True)
        return orig(self, done)
    E.PageDoc.save = traced
    v.title_rt.get_buffer().set_text("HK Linux-Test")
    rt = v.kit.order[0][1]
    rt.focus_at("end")
    rt.get_buffer().insert_at_cursor("Hallo Welt, das ist fett")
    b = rt.get_buffer()
    s = b.get_iter_at_offset(20)
    e = b.get_end_iter()
    b.select_range(s, e)
    rt.toggle_format("b")
    b.place_cursor(b.get_end_iter())
    v.kit.on_key(rt, Gdk.KEY_Return, 0)          # Enter: neuer Absatz


def type_md(g):
    v = view(g)
    rt = v.kit.by_key.get(v.editor.focus) or v.kit.order[-1][1]
    rt.get_buffer().insert_at_cursor("## ")       # Markdown-Kuerzel -> Ueberschrift 2


def type_heading(g):
    v = view(g)
    rt = v.kit.by_key.get(v.editor.focus)
    print("Fokus nach ##:", v.editor.focus, rt is not None, flush=True)
    if rt:
        rt.get_buffer().insert_at_cursor("Abschnitt")
        v.kit.on_key(rt, Gdk.KEY_Return, 0)


def type_list(g):
    v = view(g)
    rt = v.kit.by_key.get(v.editor.focus)
    rt.get_buffer().insert_at_cursor("[] ")       # Checkliste


def list_items(g):
    v = view(g)
    rt = v.kit.by_key.get(v.editor.focus)
    print("Fokus Liste:", v.editor.focus, flush=True)
    rt.get_buffer().insert_at_cursor("Erster Punkt")
    v.kit.on_key(rt, Gdk.KEY_Return, 0)


def list2(g):
    v = view(g)
    rt = v.kit.by_key.get(v.editor.focus)
    rt.get_buffer().insert_at_cursor("Zweiter Punkt (eingerückt)")
    v.kit.on_key(rt, Gdk.KEY_Tab, 0)


def list_end(g):
    v = view(g)
    rt = v.kit.by_key.get(v.editor.focus)
    v.kit.on_key(rt, Gdk.KEY_Return, 0)
    rt = v.kit.by_key.get(v.editor.focus)
    v.kit.on_key(rt, Gdk.KEY_Return, 0)           # leerer Punkt -> ausruecken
    rt = v.kit.by_key.get(v.editor.focus)
    v.kit.on_key(rt, Gdk.KEY_Return, 0)           # leerer Punkt -> Liste beenden


def slash(g):
    v = view(g)
    rt = v.kit.by_key.get(v.editor.focus)
    print("Fokus nach Liste:", v.editor.focus, flush=True)
    rt.get_buffer().insert_at_cursor("/tog")


def slash_shot(g):
    print("Slash-Menue sichtbar:", view(g).suggest.visible, len(view(g).suggest.items), flush=True)
    g["shot"](g["out"] + "/10-slash.png")
    view(g).suggest.pick_current()


def toggle_text(g):
    v = view(g)
    rt = v.kit.by_key.get(v.editor.focus)
    rt.get_buffer().insert_at_cursor("Mehr Details")
    v.kit.on_key(rt, Gdk.KEY_Return, 0)


def inner(g):
    v = view(g)
    rt = v.kit.by_key.get(v.editor.focus)
    rt.get_buffer().insert_at_cursor("Im Toggle @morgen")


def mention_shot(g):
    v = view(g)
    sg = v.suggest
    print("@-Menue:", [i[1] for i in sg.items][:4], "rt aktuell:", sg.rt is v.kit.by_key.get(v.editor.focus), "Cursor:", sg.rt.cursor_offset(), len(sg.rt.text()), flush=True)
    import time as _t
    print("PICK", round(_t.time() % 100, 2), flush=True)
    sg.pick_current()
    print("nach Auswahl:", repr(sg.rt.text() if sg.rt else v.kit.by_key.get(v.editor.focus).text()), flush=True)
    rt = v.kit.by_key.get(v.editor.focus)
    print("Feld jetzt:", repr(rt.text()), rt.html(), flush=True)
    b = v.editor.block(rt.hk_block["id"])
    print("Daten im Block:", rt.hk_block.get("data"), "gleich:", b is rt.hk_block, "dirty:", v.doc.dirty, "src:", v.editor._save_src, flush=True)


def flush(g):
    view(g).editor.flush()


def check(g):
    v = view(g)
    g["shot"](g["out"] + "/12-editor.png")
    from hlidskjalf.api import client
    print("Zustand:", v.doc.rev, v.doc.dirty, v.doc._saving, v.doc._again, flush=True)
    p = client.get(f"api/notes/{S['pid']}")
    body = json.loads(p.get("body") or "{}")
    print("Titel gespeichert:", p.get("title"), flush=True)
    for b in body.get("blocks", []):
        print("  BLOCK", b["type"], json.dumps(b.get("data"), ensure_ascii=False)[:160], flush=True)


def cleanup(g):
    from hlidskjalf.api import client
    pid = S.get("pid")
    if pid:
        client.request("DELETE", f"api/notes/{pid}")
        client.request("DELETE", f"api/trash/{pid}")
        print("Testseite endgültig gelöscht", flush=True)


steps = [(2500, create), (2500, type_title), (600, type_md), (600, type_heading), (600, type_list), (600, list_items), (500, list2),
         (500, list_end), (600, slash), (700, slash_shot), (700, toggle_text), (600, inner), (700, mention_shot), (300, flush),
         (2500, check), (300, cleanup)]
