"""Seitenbaum-Abgleich (2.2b) gegen einen ECHTEN Saga-Server (Testinstanz, nie die Produktivinstanz):
Store.refresh/notes.tree-Weg anlegen/umbenennen/verschieben/Papierkorb/wiederherstellen/endgueltig loeschen/archivieren/
Datenbank-Eintraege - nach jeder Runde muss die angezeigte Liste UND der Zwischenspeicher genau GET api/notes (ganz)
entsprechen. Misst die Bytes (ganz vs. Abgleich). Legt Seiten nur im angegebenen Testkonto an und loescht sie am Ende.
  HK_REAL_BASE=http://127.0.0.1:8240 HK_REAL_COOKIE=<saga-Sitzungscookie> HK_REAL_USER=<konto> \\
  dbus-run-session -- xvfb-run -a python3 tests/realdelta.py"""
import gzip
import json
import os
import shutil
import sys
import tempfile
import urllib.request

_HOME = tempfile.mkdtemp(prefix="hk-realdelta-")
for k, sub in (("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache"), ("XDG_DATA_HOME", "data")):
    os.environ[k] = os.path.join(_HOME, sub)
os.environ["HK_NO_KEYRING"] = "1"
os.environ["HK_TEST"] = "realdelta"
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import gi  # noqa: E402

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from hk_helpers import pump  # noqa: E402

from hlidskjalf import config  # noqa: E402
from hlidskjalf.api import client  # noqa: E402
from hlidskjalf.store import store  # noqa: E402

BASE = os.environ["HK_REAL_BASE"].rstrip("/")
USER = os.environ["HK_REAL_USER"]
res = []


def ok(name, cond, detail=None):
    res.append(bool(cond))
    print(("OK  " if cond else "FAIL"), name, flush=True)
    if not cond and detail is not None:
        print("     ", str(detail)[:800], flush=True)


def wire(path):
    """Bytes auf der Leitung (gzip wie die App) und entpackt"""
    req = urllib.request.Request(BASE + "/" + path, headers={"X-Saga": "1", "Accept-Encoding": "gzip",
                                                             "Cookie": "saga=" + os.environ["HK_REAL_COOKIE"]})
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
        enc = r.headers.get("Content-Encoding")
    body = gzip.decompress(raw) if enc == "gzip" else raw
    return len(raw), len(body), json.loads(body)


def full_tree():
    return json.loads(client.request("GET", "api/notes"))


def refresh():
    store.refresh()
    pump(lambda: not store._refreshing, 20)
    pump(None, 0.05)


def check(name):
    full = full_tree()
    ok(f"{name}: Anzeige == ganzer Baum", store._all == full, (len(store._all), len(full)))
    ok(f"{name}: Grundlage == ganzer Baum", store._tree_base == full)
    with open(store._cache_path("api/notes"), "rb") as f:
        ok(f"{name}: Zwischenspeicher == ganzer Baum", json.loads(f.read()) == full)
    with open(store._cache_path(store.CURSOR_CACHE), "rb") as f:
        ok(f"{name}: Stand-Datei == Stand", json.loads(f.read()) == store._tree_cursor)


def main():
    config.settings.server = BASE
    client.set_cookies(BASE, {"saga": os.environ["HK_REAL_COOKIE"]})
    config.set_account(USER, BASE)
    made = []

    def new(title, parent=None, kind=None):
        b = {"title": title}
        if parent is not None:
            b["parent_id"] = parent
        if kind:
            b["kind"] = kind
        nid = client.json("POST", "api/notes", b)["id"]
        made.append(nid)
        return nid

    # Grundbestand: 300 Seiten in einem Baum + eine Datenbank mit 40 Eintraegen (wie ein mittleres Konto)
    roots = [new(f"dal Wurzel {i}") for i in range(10)]
    for i in range(290):
        new(f"dal Seite {i}", roots[i % 10] if i % 3 else made[-1])
    db = new("dal Datenbank", roots[0], kind="database")
    rows = [new(f"dal Eintrag {i}", db) for i in range(40)]

    w_full, b_full, _j = wire("api/notes")
    w_d1, b_d1, _j = wire("api/notes?delta=1")
    print(f"Messung: GET api/notes ganz = {b_full} B ({w_full} B gzip), ?delta=1 = {b_d1} B ({w_d1} B gzip), {len(_j['notes'])} Seiten")

    store.tree_bytes = 0
    refresh()
    ok("Start ohne Stand: delta=1, Stand gesetzt", store._tree_cursor and store._tree_cursor["account"] == USER, store._tree_cursor)
    first = store.tree_bytes
    check("Start")

    deltas = []

    def round_(name, fn):
        fn()
        store.tree_bytes = 0
        refresh()
        deltas.append((name, store.tree_bytes))
        check(name)

    round_("nichts geaendert", lambda: None)
    round_("anlegen", lambda: [new("dal neu A", roots[2]), new("dal neu B")])

    def rename():
        for pid in (roots[3], made[50]):
            rev = client.get(f"api/notes/{pid}").get("rev")
            client.json("PATCH", f"api/notes/{pid}", {"title": f"dal umbenannt {pid}", "rev": rev})
    round_("umbenennen", rename)
    round_("verschieben", lambda: [client.json("POST", f"api/notes/{made[60]}/move", {"parent_id": roots[5], "index": 0}),
                                   client.json("POST", f"api/notes/{roots[7]}/move", {"parent_id": None, "index": 0})])
    round_("Papierkorb", lambda: client.request("DELETE", f"api/notes/{roots[4]}"))
    round_("wiederherstellen", lambda: client.json("POST", f"api/trash/{roots[4]}/restore", {}))
    round_("archivieren", lambda: client.json("POST", f"api/notes/{roots[6]}/archive", {"archived": True}))
    round_("aus dem Archiv", lambda: client.json("POST", f"api/notes/{roots[6]}/archive", {"archived": False}))

    def db_rows():
        new("dal Eintrag neu", db)
        rev = client.get(f"api/notes/{rows[3]}").get("rev")
        client.json("PATCH", f"api/notes/{rows[3]}", {"title": "dal Eintrag geaendert", "rev": rev})
        client.request("DELETE", f"api/notes/{rows[5]}")
    round_("Datenbank-Eintraege", db_rows)

    def purge():
        client.request("DELETE", f"api/notes/{made[100]}")
        client.request("DELETE", f"api/trash/{made[100]}")
    round_("endgueltig loeschen", purge)

    # notes.tree (Live) gezielt: anderswo umbenannt -> ein ?since= statt GET api/notes/{id}
    rev = client.get(f"api/notes/{made[70]}").get("rev")
    client.json("PATCH", f"api/notes/{made[70]}", {"title": "dal live", "rev": rev})
    store.tree_bytes = 0
    store.on_tree_event({"ids": [made[70]], "op": "updated"})
    pump(lambda: store.by_id.get(made[70], {}).get("title") == "dal live", 5)
    pump(lambda: not store._refreshing, 10)
    deltas.append(("notes.tree updated", store.tree_bytes))
    check("notes.tree")

    # Neustart: Zwischenspeicher + Stand -> erster Abgleich mit since
    from hlidskjalf.store import Store
    s2 = Store()
    ok("Neustart: Liste + Stand aus dem Zwischenspeicher", s2._all == store._all and s2._tree_cursor == store._tree_cursor)
    s2.tree_bytes = 0
    s2.refresh()
    pump(lambda: not s2._refreshing, 20)
    ok("Neustart: Abgleich statt ganzer Liste", s2.tree_bytes < first / 5 and s2._all == full_tree(), s2.tree_bytes)
    deltas.append(("Neustart", s2.tree_bytes))

    print(f"Messung: Start (delta=1) = {first} B")
    for n, b in deltas:
        print(f"Messung: {n} = {b} B")
    ok("Abgleich deutlich kleiner als der ganze Baum", max(b for _n, b in deltas) < b_full / 5)

    # Aufraeumen: alle angelegten Seiten endgueltig loeschen
    for pid in reversed(made):
        try:
            client.request("DELETE", f"api/notes/{pid}")
        except Exception:  # noqa: BLE001
            pass
    for pid in made:
        try:
            client.request("DELETE", f"api/trash/{pid}")
        except Exception:  # noqa: BLE001
            pass
    left = [p for p in full_tree() if p["id"] in set(made)]
    ok("aufgeraeumt", not left, len(left))
    shutil.rmtree(_HOME, ignore_errors=True)
    print(f"{sum(res)}/{len(res)} OK", flush=True)
    return 0 if all(res) else 1


if __name__ == "__main__":
    sys.exit(main())
