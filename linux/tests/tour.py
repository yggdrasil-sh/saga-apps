# Rundtour mit echten Daten (nur lesen): Startseite, Inhaltsseite, Datenbank-Ansichten, Peek, Palette, Einstellungen
import json
import os

S = {}


def find_pages(g):
    st = g["store"]
    print("Ansicht:", g["win"].stack.get_visible_child_name(), "Seiten:", len(st.pages), "Aufgaben:", len(st.tasks), flush=True)
    want = os.environ.get("HK_PAGE")
    content = None
    if want:
        content = int(want)
    else:
        for p in st.pages:
            if "dashboard" in (p.get("title") or "").lower() and p.get("kind") != "database":
                content = p["id"]
                break
        content = content or next((p["id"] for p in st.pages if p.get("parent_id") is None and p.get("kind") != "database"), None)
    dbs = [p for p in st.pages if p.get("kind") == "database"]
    S["content"] = content
    S["db"] = int(os.environ["HK_DB"]) if os.environ.get("HK_DB") else (dbs[0]["id"] if dbs else None)
    print("Inhaltsseite:", content, "Datenbank:", S["db"], [(d["id"], d.get("title")) for d in dbs[:12]], flush=True)
    g["shot"](g["out"] + "/01-heute.png")


def open_content(g):
    if S["content"]:
        g["win"].show_root(("page", S["content"]))


def shot_content(g):
    g["shot"](g["out"] + "/02-seite.png")
    v = g["win"].current
    print("Titelbild:", v.doc.page.get("cover"), "Symbol:", v.doc.page.get("icon"), flush=True)
    print("Bloecke:", len(v.doc.root), "Typen:", sorted({b.get("type") for b in v.kit.view.editor.flat_blocks()}), flush=True)
    adj = v.scroll.get_vadjustment()
    adj.set_value(min(adj.get_upper(), 700))


def shot_content2(g):
    g["shot"](g["out"] + "/03-seite-unten.png")


def open_db(g):
    if S["db"]:
        g["win"].show_root(("page", S["db"]))


def db_views(g):
    from hlidskjalf.database import DatabaseView
    v = g["win"].current

    def find(w):
        if isinstance(w, DatabaseView):
            return w
        c = w.get_first_child()
        while c is not None:
            r = find(c)
            if r:
                return r
            c = c.get_next_sibling()
        return None
    dv = find(v)
    S["dv"] = dv
    if dv:
        print("Ansichten:", [(x.get("id"), x.get("type")) for x in dv.views], "Zeilen:", len(dv.m.rows), flush=True)
    g["shot"](g["out"] + "/04-db.png")


def db_next_view(i):
    def f(g):
        dv = S.get("dv")
        if dv and len(dv.views) > i:
            dv.set_view(dv.views[i]["id"])
            g["GLib"].timeout_add(800, lambda: (g["shot"](g["out"] + f"/05-db-view{i}.png"), False)[1])
    return f


def peek(g):
    dv = S.get("dv")
    if dv and dv.m.rows:
        g["win"].open_entry(dv.m.rows[0]["id"], "side", [r["id"] for r in dv.m.rows])


def shot_peek(g):
    g["shot"](g["out"] + "/06-peek.png")
    g["win"].close_peek()


def palette(g):
    g["win"].palette()


def shot_palette(g):
    g["shot"](g["out"] + "/07-palette.png")
    dlg = g["win"].get_visible_dialog()
    if dlg:
        dlg.close()


def settings(g):
    g["win"].settings_dialog()


def shot_settings(g):
    g["shot"](g["out"] + "/08-einstellungen.png")


steps = [(3000, find_pages), (200, open_content), (3500, shot_content), (1500, shot_content2), (200, open_db), (3500, db_views),
         (100, db_next_view(1)), (1500, db_next_view(2)), (1500, db_next_view(3)), (1500, lambda g: None),
         (500, peek), (3000, shot_peek), (500, palette), (1200, shot_palette), (500, settings), (2500, shot_settings)]
