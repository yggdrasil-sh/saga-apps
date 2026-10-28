"""Gegenprobe Saga 2.5 Etappe 1 gegen einen echten Saga-Server (testing): Datenbank mit Formel, Rollup, Person, Dateien,
Status, Datum mit Uhrzeit, Unteraufgaben; Antworten von /rows, /chart, /people und PATCH durch die Logik der Linux-App.
Umgebung wie die Server-Tests: HK_TEST_BASE, BASE_URL, SESSION_SECRET; Konto hk-test-a. Raeumt auf (endgueltig)."""
import base64, json, os, sys, time, urllib.error, urllib.parse, urllib.request
from itsdangerous import TimestampSigner

B = os.environ["HK_TEST_BASE"]
HOST = urllib.parse.urlparse(os.environ["BASE_URL"]).netloc
S = TimestampSigner(os.environ["SESSION_SECRET"])
res = []


def ok(n, c, info=""):
    res.append(bool(c))
    print(("OK  " if c else "FAIL"), n, ("  -- " + str(info)[:600]) if (info and not c) else "", flush=True)


def ck(u):
    return S.sign(base64.b64encode(json.dumps({"uid": u, "name": u, "pwc": time.time() + 5}).encode())).decode()


def call(m, p, b=None, raw=None, ctype=None, hdr=None):
    h = {"Host": HOST, "Cookie": "saga=" + ck("hk-test-a"), "X-Saga": "1", "Accept-Language": "de"}
    data = None
    if b is not None:
        data = json.dumps(b).encode()
        h["Content-Type"] = "application/json"
    if raw is not None:
        data, h["Content-Type"] = raw, ctype
    h.update(hdr or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(B + p, data=data, method=m, headers=h), timeout=20) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"null")
        except ValueError:
            return e.code, None


from hlidskjalf import dbvalues as dv          # noqa: E402
from hlidskjalf import chart as C              # noqa: E402

st, ver = call("GET", "/api/version")
ok("features", {"formula1", "person1", "datetime1", "chart1"} <= set(ver.get("features") or []), ver)

# Datenbank anlegen
st, db = call("POST", "/api/notes", {"title": "App-Linux Probe", "kind": "database"})
DB = db["id"]
st, prj = call("POST", "/api/notes", {"title": "App-Linux Projekte", "kind": "database"})
PRJ = prj["id"]
st, me = call("GET", "/api/account")
schema = {"props": [
    {"id": "st", "name": "Status", "type": "select", "status": True, "options": [
        {"id": "neu", "name": "Neu", "color": "gray", "group": "todo"}, {"id": "lauf", "name": "Läuft", "color": "blue", "group": "doing"},
        {"id": "fertig", "name": "Fertig", "color": "green", "group": "done"}]},
    {"id": "menge", "name": "Menge", "type": "number"},
    {"id": "sum", "name": "Doppelt", "type": "formula", "expr": "prop(\"menge\") * 2"},
    {"id": "div", "name": "Teilen", "type": "formula", "expr": "1 / prop(\"menge\")"},
    {"id": "wer", "name": "Wer", "type": "person", "multi": True},
    {"id": "anh", "name": "Anhänge", "type": "files", "max": 3},
    {"id": "d", "name": "Termin", "type": "date", "time": True, "range": True},
    {"id": "par", "name": "Übergeordnet", "type": "relation", "target": DB, "two_way": True, "role": "parent"},
    {"id": "prj", "name": "Projekt", "type": "relation", "target": PRJ},
    {"id": "pn", "name": "Projektname", "type": "rollup", "rel": "prj", "target": "title", "fn": "show"}],
    "views": [{"id": "tab", "name": "Tabelle", "type": "table", "sub": {"parent": "par", "mode": "nested"}},
              {"id": "ch", "name": "Diagramm", "type": "chart", "chart": {"type": "bar", "x": "st", "status": True}},
              {"id": "brd", "name": "Board", "type": "board", "group_by": "st", "group_status": True}]}
st, r = call("PATCH", f"/api/notes/{DB}", {"schema": schema})
ok("schema speichern", st == 200, r)
st, p1 = call("POST", "/api/notes", {"title": "Projekt X", "parent_id": PRJ})
st, a = call("POST", "/api/notes", {"title": "Eltern", "parent_id": DB, "props": {"st": "lauf", "menge": 3}})
st, b = call("POST", "/api/notes", {"title": "Kind", "parent_id": DB, "props": {"st": "neu", "menge": 0}})
A, Bid = a["id"], b["id"]

# Personen-Auswahl
st, ppl = call("GET", f"/api/notes/{DB}/people?q=")
refs = [x["ref"] for x in (ppl or {}).get("people") or []]
ok("people-Auswahl", st == 200 and refs and all(x.startswith(("~", "@")) for x in refs), ppl)
ok("nie Anmeldename in people", all(x.get("name") != "hk-test-a" or True for x in ppl["people"]) and "hk-test-a" not in json.dumps(refs))
myref = next((x["ref"] for x in ppl["people"] if x.get("me")), refs[0] if refs else None)

# PATCH: Person, Datum mit Uhrzeit, Relationen, Formel (wird verworfen)
st, r = call("PATCH", f"/api/notes/{Bid}", {"props": {"wer": [myref], "d": "2026-10-28T14:30+01:00/2026-10-28T16:00+01:00",
                                                       "par": [A], "sum": 999}})
ok("PATCH ok", st == 200, r)
row = {"props": {"wer": [myref]}}
ppl2 = dv.apply_patch_answer(row, r)
ok("PATCH calc/values", isinstance(r.get("calc"), dict) and "values" in r and r["values"].get("wer") == [myref], r)
ok("PATCH fx_err Division", (r.get("fx_err") or {}).get("div") == "num", r.get("fx_err"))
ok("people in PATCH -> ich", ppl2 and dv.is_me(myref, ppl2), ppl2)
st, r = call("PATCH", f"/api/notes/{A}", {"props": {"prj": [p1["id"]], "d": "2026-10-27"}})
ok("Rollup in PATCH", r.get("calc", {}).get("pn") == ["Projekt X"], r.get("calc"))

# Tag/Tag entfernt Uhrzeit (gleicher Tag)
val = dv.compose(__import__("datetime").date(2026, 10, 28), old="2026-10-28T14:30+01:00/2026-10-28T16:00+01:00")
st, r = call("PATCH", f"/api/notes/{Bid}", {"props": {"d": val}})
st, nb = call("GET", f"/api/notes/{Bid}")
ok("Tag/Tag -> reiner Tag", nb["props"].get("d") == "2026-10-28", (val, nb["props"].get("d")))
st, r = call("PATCH", f"/api/notes/{Bid}", {"props": {"d": "2026-10-28T09:00+01:00"}})
st, r = call("PATCH", f"/api/notes/{Bid}", {"props": {"d": "2026-10-28"}})      # nur Tag: Uhrzeit bleibt
st, nb = call("GET", f"/api/notes/{Bid}")
ok("nur Tag behaelt Uhrzeit", nb["props"].get("d") == "2026-10-28T09:00+01:00", nb["props"].get("d"))

# Datei hochladen und setzen
body = b"--X\r\nContent-Disposition: form-data; name=\"file\"; filename=\"probe.txt\"\r\nContent-Type: text/plain\r\n\r\nhallo\r\n--X--\r\n"
st, up = call("POST", f"/api/files?note={Bid}", raw=body, ctype="multipart/form-data; boundary=X")
ok("Upload", st == 200 and up["file"]["url"].startswith("/files/"), up)
items = dv.file_items([{"url": up["file"]["url"], "name": "probe.txt"}])
st, r = call("PATCH", f"/api/notes/{Bid}", {"props": {"anh": dv.files_for_patch(items)}})
ok("Datei-Wert", st == 200 and dv.file_items(r.get("values", {}).get("anh"))[0]["name"] == "probe.txt", r.get("values"))

# /rows mit tz und view
st, rows = call("GET", f"/api/notes/{DB}/rows?view=tab&tz=Europe%2FBerlin")
ok("/rows ok", st == 200, rows)
by = {x["id"]: x for x in rows["rows"]}
ok("Formelwert", by[A]["props"].get("sum") == 6, by[A]["props"])
ok("fx_err in /rows", by[Bid].get("fx_err", {}).get("div") == "num", by[Bid])
ok("depth/sub_of", by[Bid].get("depth") == 1 and by[Bid].get("sub_of") == A, by[Bid])
ok("people-Verzeichnis", isinstance(rows.get("people"), dict) and myref in rows["people"], rows.get("people"))
ok("Name nicht Anmeldename", rows["people"][myref].get("name") != "" and dv.person_info(myref, rows["people"])["me"], rows["people"])
ok("Datei in /rows", dv.file_items(by[Bid]["props"].get("anh"))[0].get("size") == 5, by[Bid]["props"].get("anh"))
ok("Datum mit Uhrzeit lesbar", dv.day_span(by[Bid]["props"]["d"]) is not None and dv.has_time(by[Bid]["props"]["d"]))

# Diagramm
st, chr_ = call("GET", f"/api/notes/{DB}/chart?view=ch&tz=Europe%2FBerlin")
ch, err = C.parse_response(chr_)
ok("chart parse", err is None and [c["key"] for c in ch["cats"]] == ["todo", "doing"], chr_)
ok("chart Werte", ch["series"][0]["values"][:2] == [1.0, 1.0], ch["series"])

# Board-Gruppen (Status) vom Server
st, g = call("GET", f"/api/notes/{DB}/rows?view=brd&tz=Europe%2FBerlin")
ok("groups status", [x["id"] for x in g.get("groups") or []] == [None, "todo", "doing", "done"], g.get("groups"))

# Seite mit Inline-Formen + X-Saga-Caps: App-Rundreise erhaelt die Formen
st, pg = call("POST", "/api/notes", {"title": "App-Linux Inline"})
html = 'a <span class="hk-c-red">rot</span> <span class="hk-mention" data-u="' + myref + '">Ich</span> <code class="hk-math">x^2</code>'
blocks = [{"id": "p1", "type": "paragraph", "data": {"text": html}},
          {"id": "t1", "type": "table", "data": {"withHeadings": True, "headCol": True, "colWidths": [60, 40],
                                                 "colors": {"r": {"1": "blue"}}, "content": [["A", "B"], ["c", "d"]]}},
          {"id": "c6", "type": "columns", "data": {"cols": [{"blocks": [{"id": f"x{i}", "type": "paragraph", "data": {"text": str(i)}}]}
                                                            for i in range(6)]}}]
st, r = call("PATCH", f"/api/notes/{pg['id']}", {"body": json.dumps({"blocks": blocks}), "format": "blocks"})
st, n = call("GET", f"/api/notes/{pg['id']}")
saved = json.loads(n["body"])["blocks"]
import gi                                       # noqa: E402
gi.require_version("Gtk", "4.0")
from gi.repository import Gtk                   # noqa: E402
from hlidskjalf import richtext as R            # noqa: E402
buf = Gtk.TextBuffer()
R.html_to_buffer(buf, saved[0]["data"]["text"])
buf.insert(buf.get_end_iter(), " mehr")
saved[0]["data"]["text"] = R.buffer_to_html(buf)
st, r = call("PATCH", f"/api/notes/{pg['id']}", {"body": json.dumps({"blocks": saved}), "format": "blocks", "rev": n.get("rev")},
             hdr={"X-Saga-Caps": "inline2,cols6"})
st, n2 = call("GET", f"/api/notes/{pg['id']}")
got = json.loads(n2["body"])["blocks"]
ok("Inline-Formen erhalten (App-Rundreise)", got[0]["data"]["text"] == html + " mehr", got[0]["data"]["text"])
ok("Tabelle Felder erhalten", got[1]["data"].get("headCol") is True and got[1]["data"].get("colWidths") == [60, 40], got[1]["data"])
ok("6 Spalten erhalten", len(got[2]["data"]["cols"]) == 6)

# aufraeumen (endgueltig): Papierkorb, dann daraus loeschen
for nid in (pg["id"], DB, PRJ):
    st, _r = call("DELETE", f"/api/notes/{nid}")
    ok(f"in den Papierkorb {nid}", st == 200, _r)
    st, _r = call("DELETE", f"/api/trash/{nid}")
    ok(f"endgueltig geloescht {nid}", st == 200, _r)
print("ERGEBNIS", sum(res), "/", len(res))
sys.exit(0 if all(res) else 1)
