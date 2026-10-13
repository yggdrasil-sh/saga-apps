# Datenbank-Test auf einer eigenen Test-Datenbank (wird am Ende endgueltig geloescht)
import datetime as dt

S = {}


def find_dv(g):
    from hlidskjalf.database import DatabaseView
    v = g["win"].current

    def walk(w):
        if isinstance(w, DatabaseView):
            return w
        c = w.get_first_child()
        while c is not None:
            r = walk(c)
            if r:
                return r
            c = c.get_next_sibling()
    return walk(v)


def create(g):
    def made(pid):
        S["db"] = pid
        print("Test-Datenbank:", pid, flush=True)
        g["win"].show_root(("page", pid))
    g["store"].new_page(None, done=made, kind="database", title="HK DB-Test")


def schema(g):
    dv = find_dv(g)
    S["dv"] = dv
    m = dv.m

    def ch(sc):
        sc["props"] = [
            {"id": "st", "name": "Status", "type": "select", "options": [{"id": "o1", "name": "Offen", "color": "red"},
                                                                        {"id": "o2", "name": "In Arbeit", "color": "yellow"},
                                                                        {"id": "o3", "name": "Fertig", "color": "green"}]},
            {"id": "pr", "name": "Punkte", "type": "number"},
            {"id": "du", "name": "Fällig", "type": "date"},
            {"id": "ok", "name": "Wichtig", "type": "checkbox"},
            {"id": "tg", "name": "Tags", "type": "multi_select", "options": [{"id": "t1", "name": "Linux", "color": "blue"},
                                                                             {"id": "t2", "name": "GTK", "color": "purple"}]},
        ]
        sc["views"] = [{"id": "tab", "name": "Tabelle", "type": "table", "calcs": {"pr": "sum", "title": "count"}},
                       {"id": "brd", "name": "Board", "type": "board", "group_by": "st"},
                       {"id": "cal", "name": "Kalender", "type": "calendar", "date_by": "du"}]
    m.edit_schema(ch)


def rows(g):
    m = S["dv"].m
    today = dt.date.today()
    data = [("Linux-App bauen", "o2", 8, today.isoformat(), True, ["t1", "t2"]), ("RPM testen", "o1", 3, (today + dt.timedelta(days=2)).isoformat(), False, ["t1"]),
            ("Commit", "o3", 1, (today + dt.timedelta(days=5)).isoformat(), False, [])]
    for t, st, pr, du, ok, tg in data:
        g["store"].run("POST", "api/notes", {"parent_id": m.db_id, "title": t, "props": {"st": st, "pr": pr, "du": du, "ok": ok, "tg": tg}})


def reload(g):
    S["dv"].m.load()


def shot_table(g):
    m = S["dv"].m
    print("Zeilen:", len(m.rows), "Spalten:", [p["name"] for p in m.props], flush=True)
    g["shot"](g["out"] + "/40-db-tabelle.png")


def set_value(g):
    m = S["dv"].m
    r = next(r for r in m.rows if r["title"] == "RPM testen")
    m.set(r["id"], "st", "o2")                      # Wert aendern wie im Board-Ziehen
    m.edit_view("tab", lambda v: v.update(sorts=[{"pid": "pr", "dir": "desc"}], filters=[{"pid": "st", "op": "is_not", "value": "o3"}]))


def shot_filtered(g):
    dv = S["dv"]
    print("Gefiltert+sortiert:", [r["title"] for r in dv.m.rows_for(dv.view)], flush=True)
    g["shot"](g["out"] + "/41-db-filter.png")
    dv.set_view("brd")


def shot_board(g):
    g["shot"](g["out"] + "/42-db-board.png")
    S["dv"].set_view("cal")


def shot_cal(g):
    g["shot"](g["out"] + "/43-db-kalender.png")


def check(g):
    from hlidskjalf.api import client
    j = client.get(f"api/notes/{S['db']}/rows")
    print("Server-Ansichten:", [(v["id"], v.get("sorts"), v.get("filters")) for v in j["schema"]["views"]][:1], flush=True)
    print("Server-Zeilen:", [(r["title"], r["props"].get("st")) for r in j["rows"]], flush=True)


def cleanup(g):
    from hlidskjalf.api import client
    db = S.get("db")
    if db:
        client.request("DELETE", f"api/notes/{db}")
        client.request("DELETE", f"api/trash/{db}")
        print("Test-Datenbank endgültig gelöscht", flush=True)


steps = [(2500, create), (2500, schema), (1500, rows), (2000, reload), (2500, shot_table), (300, set_value), (2000, shot_filtered),
         (1500, shot_board), (1500, shot_cal), (300, check), (300, cleanup)]
