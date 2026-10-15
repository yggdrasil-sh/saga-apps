# Teilen + Kommentare mit nachgebautem Server ansehen (Bildschirmfotos), hell und dunkel; auf Spanisch nur hell:
#   Mit mir geteilt + fremde Seite (Bearbeiten), fremde Seite (Lesen), Teilen-Dialog (Freigaben, Suche), Kommentare
#   (alle, ein Block), Konflikt mit einer anderen Person (+ Entwurf), Zugriff verloren (nur noch lesen, kein Zugriff mehr),
#   Ansicht "Kein Zugriff auf diese Seite", gesperrte Seite mit Datenbank-Block
#   im Container: sh tests/run-container.sh shots   (Dateien linux-NN-*-<hell|dunkel|es>.png)
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockserver  # noqa: E402

from gi.repository import Adw  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
LANG = (os.environ.get("LANG") or "C")[:2]
THEMES = [("hell", Adw.ColorScheme.FORCE_LIGHT)] + ([] if LANG == "es" else [("dunkel", Adw.ColorScheme.FORCE_DARK)])
NA = {"de": "Seite ohne Zugriff", "es": "Página sin acceso"}.get(LANG, "Page without access")
S = {}


def shoot(g, name):
    # wie die anderen Apps: linux-NN-name-<hell|dunkel|es>.png (Spanisch nur hell)
    g["shot"](os.path.join(g["out"], f"linux-{name}-{'es' if P.startswith('es') else S['theme']}.png"))


def dlg(g):
    return g["win"].get_visible_dialog()


def close_all(g):
    for _ in range(4):
        d = dlg(g)
        if d is not None:
            d.force_close()


def garden_blocks():
    return [
        {"id": "g1", "type": "header", "data": {"text": "Ziele für den Sommer", "level": 2}},
        {"id": "g2", "type": "paragraph", "data": {"text": 'Wir planen gemeinsam die Beete für dieses Jahr. Details stehen unter '
                                                           '<a href="/notes#201">🏁 Meilensteine</a>, Notizen in '
                                                           f'<a href="/notes#999">{NA}</a>.'}},
        {"id": "g3", "type": "list", "data": {"style": "checklist", "meta": {}, "items": [
            {"content": "Hochbeet bauen", "meta": {"checked": True}, "items": []},
            {"content": "Tomaten vorziehen", "meta": {"checked": False}, "items": []}]}},
        {"id": "g4", "type": "callout", "data": {"emoji": "💶", "text": "Budget: etwa 300 € für Holz und Erde."}},
        {"id": "g5", "type": "tasks", "data": {}},
        {"id": "g6", "type": "paragraph", "data": {"text": "Gießplan folgt, sobald die Beete stehen."}},
    ]


def start(g):
    srv = S["srv"] = mockserver.install()
    now = time.time()
    srv.notes[1]["title"] = "Projektplan Haus"
    srv.shares[1] = [{"type": "user", "target": "bert", "role": "edit"}, {"type": "user", "target": "cora", "role": "comment"},
                     {"type": "group", "target": "team", "role": "view"}]
    srv.add_foreign(200, "Projektplan Garten", role="edit", icon="🌱", blocks=garden_blocks(), updated_by="anna")
    srv.foreign[200]["updated"] = now - 900
    srv.add_foreign(201, "Meilensteine", parent_id=200, icon="🏁")
    srv.add_foreign(210, "Leseliste Herbst", owner="cora", role="view", icon="📚", blocks=[
        {"id": "l1", "type": "paragraph", "data": {"text": "Bücher für die langen Abende – Vorschläge gern als Kommentar."}},
        {"id": "l2", "type": "list", "data": {"style": "unordered", "meta": {}, "items": [
            {"content": "Ein Roman über Gärten", "meta": {}, "items": []},
            {"content": "Ein Sachbuch über Holz", "meta": {}, "items": []}]}},
        {"id": "l4", "type": "list", "data": {"style": "checklist", "meta": {}, "items": [
            {"content": "Ausgelesen: Der Gartenroman", "meta": {"checked": True}, "items": []},
            {"content": "Noch offen: Holzbuch", "meta": {"checked": False}, "items": []}]}},
        {"id": "l3", "type": "calendar", "data": {}}])
    # eigene, gesperrte Seite mit Datenbank-Block (Nachtrag: kein "Neu", keine Ansichts-Einstellungen, kein "+")
    srv.notes[3].update(title="Wochenplan", icon="🗓️", locked=True, body=json.dumps({"blocks": [
        {"id": "w1", "type": "paragraph", "data": {"text": "Die Aufgaben dieser Woche – die Seite ist gesperrt."}},
        {"id": "w2", "type": "database", "data": {"db": 10, "view": "tabelle"}}]}))
    t = srv.add_comment(200, "Reicht das Budget? Holzpreise siehe https://example.org/holz", uid="anna", block_id="g4", created=now - 3600)
    srv.add_comment(200, "Ja, wenn wir Lärche statt Eiche nehmen.", uid="bert", thread_id=t, created=now - 1800)
    srv.add_comment(200, "Tomaten erst nach den Eisheiligen.", uid="cora", block_id="g3", created=now - 7200, resolved=True)
    srv.add_comment(200, "Schöne Ziele!", uid="test", block_id="g1", created=now - 600)
    srv.add_comment(210, "Gute Auswahl.", uid="cora", block_id="l1", created=now - 4000)
    g["store"].logged_in()


def reset(theme, scheme):
    def f(g):
        S["theme"] = theme
        Adw.StyleManager.get_default().set_color_scheme(scheme)
        close_all(g)
        from hlidskjalf import drafts
        for p, _d in drafts.copies(200):                         # lokale Kopie aus dem vorigen Durchgang weg
            drafts.drop_copy(p)
        srv = S["srv"]
        srv.grants[200] = "edit"
        srv.foreign[200]["rev"] = 1
        srv.foreign[200]["body"] = json.dumps({"blocks": garden_blocks()})
        srv.foreign[200]["updated_by"] = "anna"
        g["win"].sidebar.expanded.add(200)
        g["store"].refresh()
        g["win"].show_root(("page", 200))
    return f


def shared_shot(g):
    shoot(g, "01-mit-mir-geteilt-bearbeiten")
    g["win"].show_root(("page", 210))


def view_shot(g):
    shoot(g, "02-fremde-seite-lesen")
    g["win"].show_root(("page", 1))


def share_open(g):
    g["win"].share_dialog(1)


def share_shot(g):
    shoot(g, "03-teilen-dialog")
    d = dlg(g)
    d.role.set_selected(1)
    d.q.set_text("cor")


def search_shot(g):
    shoot(g, "04-teilen-suche")
    close_all(g)
    g["win"].show_root(("page", 200))


def comments_open(g):
    g["win"].current.show_comments()


def comments_shot(g):
    shoot(g, "05-kommentare")
    g["win"].current.show_comments("g4")


def block_shot(g):
    shoot(g, "06-kommentare-block")
    v = g["win"].current
    v.hide_comments()
    srv = S["srv"]
    n = srv.foreign[200]
    n["rev"] += 1
    n["updated_by"] = "bert"
    b = garden_blocks()
    b[3]["data"]["text"] = "Budget: etwa 350 € für Holz, Erde und Schrauben."
    n["body"] = json.dumps({"blocks": b})
    v.doc.root[0]["data"]["text"] = "Ziele für den Sommer 2026"
    v.editor.text_changed()
    v.editor.flush()


def conflict_shot(g):
    shoot(g, "07-konflikt")
    d = dlg(g)
    d.emit("response", "reload")
    d.force_close()


def draft_shot(g):
    shoot(g, "08-konflikt-entwurf")
    close_all(g)
    v = g["win"].current
    S["srv"].grants[200] = "view"                                 # Freigabe auf Lesen gesenkt
    v.doc.root[0]["data"]["text"] = "Ziele für den Sommer – ergänzt"
    v.editor.text_changed()
    v.editor.flush()


def lost_shot(g):
    shoot(g, "09-nur-noch-lesen")
    close_all(g)


def gone_edit(g):
    v = g["win"].current
    S["srv"].grants[200] = "edit"                                  # noch einmal bearbeiten duerfen ...
    v.doc.load(force=True)


def gone_save(g):
    v = g["win"].current
    del S["srv"].grants[200]                                       # ... und beim Speichern ist die Freigabe weg
    v.doc.root[0]["data"]["text"] = "Ziele – nach dem Entzug"
    v.editor.text_changed()
    v.editor.flush()


def gone_shot(g):
    shoot(g, "10-kein-zugriff-mehr")
    close_all(g)


def noaccess_shot(g):
    shoot(g, "11-kein-zugriff")
    g["win"].show_root(("page", 3))


def locked_db_shot(g):
    shoot(g, "12-gesperrt-datenbank")


steps = [(300, start)]
for _t, _s in THEMES:
    steps += [(2500, reset(_t, _s)), (2500, shared_shot), (2000, view_shot), (1500, share_open), (1500, share_shot),
              (1500, search_shot), (1500, comments_open), (1500, comments_shot), (1500, block_shot), (3500, conflict_shot),
              (1500, draft_shot), (2000, lost_shot), (2000, gone_edit), (2000, gone_save), (2000, gone_shot),
              (1500, noaccess_shot), (2500, locked_db_shot)]
steps += [(500, lambda g: Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT))]
