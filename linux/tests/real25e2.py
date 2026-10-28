"""Gegenprobe Saga 2.5 Etappe 2 gegen einen echten Saga-Server: App-Logik (PageDoc, Store, Warteschlange) und ein zweiter
Client (Python, urllib) schreiben gleichzeitig. Zusammenfuehren (merge: 1, base: sent, ?since-Delta, Konfliktblock),
Anwesenheit je Block, Aufgabenquellen (Liste, Abhaken mit prev, Rueckgaengig), Erwaehnung, synchronisierte Kopie (sync1)
inkl. Original + Kopie auf derselben Seite, Verschieben + Rueckgaengig, Zeitleiste (Props-PATCH).
Umgebung wie die Server-Tests: HK_TEST_BASE, BASE_URL, SESSION_SECRET; Konten hk-test-a/hk-test-b. Raeumt auf (endgueltig).
Start: dbus-run-session xvfb-run python3 tests/real25e2.py"""
import base64, copy, json, os, sys, tempfile, time, urllib.error, urllib.parse, urllib.request
from itsdangerous import TimestampSigner

_HOME = tempfile.mkdtemp(prefix="hk-real25e2-")
for k, sub in (("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache"), ("XDG_DATA_HOME", "data")):
    os.environ[k] = os.path.join(_HOME, sub)
os.environ["HK_NO_KEYRING"] = "1"
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

B = os.environ["HK_TEST_BASE"]
HOST = urllib.parse.urlparse(os.environ["BASE_URL"]).netloc
S = TimestampSigner(os.environ["SESSION_SECRET"])
res = []


def ok(n, c, info=""):
    res.append(bool(c))
    print(("OK  " if c else "FAIL"), n, ("  -- " + str(info)[:600]) if (info and not c) else "", flush=True)


def ck(u):
    return S.sign(base64.b64encode(json.dumps({"uid": u, "name": u, "pwc": time.time() + 5}).encode())).decode()


def call(m, p, b=None, user="hk-test-a", hdr=None):
    h = {"Host": HOST, "Cookie": "saga=" + ck(user), "X-Saga": "1", "Accept-Language": "de"}
    data = None
    if b is not None:
        data = json.dumps(b).encode()
        h["Content-Type"] = "application/json"
    h.update(hdr or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(B + p, data=data, method=m, headers=h), timeout=20) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"null")
        except ValueError:
            return e.code, None


import gi                                                        # noqa: E402
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Gtk                                    # noqa: E402,F401
from hk_helpers import idle, pump                                # noqa: E402
from hlidskjalf import config                                    # noqa: E402
from hlidskjalf.api import APIError, client                      # noqa: E402

config.settings.server = B
config.set_account("hk-test-a", B)
SENT = []


def app_request(method, path, body=None, raw=None, ctype=None, timeout=20, server=None, account=None, headers=None):
    """App-Client gegen den Testserver: Sitzungs-Cookie von hk-test-a (wie nach der Anmeldung)"""
    h = {"Host": HOST, "Cookie": "saga=" + ck("hk-test-a"), "X-Saga": "1", "Accept-Language": "de"}
    h.update(headers or {})
    data = raw
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    url = path if path.startswith("http") else B.rstrip("/") + "/" + path.lstrip("/")
    SENT.append((method, url.replace(B, ""), body, dict(headers or {})))
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=h), timeout=timeout) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        raw = e.read() or b""
        try:
            d = json.loads(raw)
        except ValueError:
            d = None
        raise APIError(e.code, (d or {}).get("error") if isinstance(d, dict) else str(e.code), d) from None


client.request = app_request
from hlidskjalf.store import store                               # noqa: E402
store.check_version()
pump(lambda: store.has("tasksrc1"), 6)
ok("Server-Faehigkeiten", {"tasksrc1", "mention1", "synced1", "move1", "timeline1"} <= store.features, sorted(store.features))
CLEAN = []


def P(i, t):
    return {"id": i, "type": "paragraph", "data": {"text": t}}


def texts(nid, user="hk-test-a"):
    st, n = call("GET", f"/api/notes/{nid}", user=user)
    return [b["data"].get("text") for b in json.loads(n["body"])["blocks"]], n


# --- 1. Zusammenfuehren ---------------------------------------------------------------------------------------------
st, pg = call("POST", "/api/notes", {"title": "E2 Linux Merge"})
NID = pg["id"]
CLEAN.append(NID)
st, _r = call("PATCH", f"/api/notes/{NID}", {"body": json.dumps({"blocks": [P("a1", "Eins"), P("a2", "Zwei"), P("a3", "Drei")]}),
                                             "format": "blocks"})
st, sh = call("PUT", f"/api/notes/{NID}/shares", {"type": "user", "target": "hk-test-b", "role": "edit"})
ok("geteilt mit B (edit)", st == 200, sh)

from hlidskjalf.editor import PageDoc                           # noqa: E402
d = PageDoc(NID)
seen = []
d.on_conflict = lambda doc, p: seen.append(p)
merged = []
d.on_merged = merged.append
d.load()
pump(lambda: d.fetched, 8)
ok("App laedt Seite", d.fetched and len(d.root) == 3, d.root)
rev0 = d.rev
# B schreibt Block a3, App schreibt Block a1 (veraltete rev) -> merged, keine Konfliktfrage
_t, nb = texts(NID, "hk-test-b")
bb = json.loads(nb["body"])["blocks"]
bb[2]["data"]["text"] = "Drei von B"
st, r = call("PATCH", f"/api/notes/{NID}", {"body": json.dumps({"blocks": bb}), "format": "blocks", "rev": nb["rev"], "merge": 1},
             user="hk-test-b")
ok("B speichert", st == 200 and r.get("rev") == rev0 + 1, r)
d.root[0]["data"]["text"] = "Eins von App"
d.mark_dirty()
d.save()
idle()
t, n = texts(NID)
ok("merge: 1 gesendet + Kopf merge1", any(m == "PATCH" and b and b.get("merge") == 1 and "merge1" in h.get("X-Saga-Caps", "")
                                           for m, u, b, h in SENT if u.endswith(f"/api/notes/{NID}")), SENT[-1])
ok("zusammengefuehrt (beide Aenderungen)", t == ["Eins von App", "Zwei", "Drei von B"], t)
ok("App hat fremden Block uebernommen, kein Dialog", not seen and [x["data"]["text"] for x in d.root] == t and d.rev == n["rev"],
   [x["data"]["text"] for x in d.root])
ok("merged-Hinweis gemeldet", merged and isinstance(merged[-1], dict), merged)
# Weiterschreiben waehrend der Antwort: base: "sent"
_t, nb = texts(NID, "hk-test-b")
bb = json.loads(nb["body"])["blocks"]
bb[1]["data"]["text"] = "Zwei von B"
call("PATCH", f"/api/notes/{NID}", {"body": json.dumps({"blocks": bb}), "format": "blocks", "rev": nb["rev"], "merge": 1}, user="hk-test-b")
orig_req = client.request


def slow(*a, **k):
    out = orig_req(*a, **k)
    if a[0] == "PATCH":
        time.sleep(0.4)
    return out


client.request = slow
d.root[0]["data"]["text"] = "Eins v2"
d.mark_dirty()
d.save()
pump(None, 0.2)
d.root[0]["data"]["text"] = "Eins v3 weiter"                     # waehrend die Antwort unterwegs ist
d.mark_dirty()
pump(lambda: not d.saving and not d.dirty, 10)
client.request = orig_req
idle()
t, n = texts(NID)
ok("base: sent gesendet", any(b and b.get("base") == "sent" for m, u, b, h in SENT if m == "PATCH" and u.endswith(f"/api/notes/{NID}")))
ok("weitergeschrieben + fremd zusammen", t == ["Eins v3 weiter", "Zwei von B", "Drei von B"], t)
ok("App-Stand = Server", [x["data"]["text"] for x in d.root] == t and not d.dirty, [x["data"]["text"] for x in d.root])
# Konflikt im selben Absatz -> Konfliktblock, Uebernehmen
_t, nb = texts(NID, "hk-test-b")
bb = json.loads(nb["body"])["blocks"]
bb[1]["data"]["text"] = "Zwei B sagt"
call("PATCH", f"/api/notes/{NID}", {"body": json.dumps({"blocks": bb}), "format": "blocks", "rev": nb["rev"], "merge": 1}, user="hk-test-b")
d.root[1]["data"]["text"] = "Zwei App sagt"
d.mark_dirty()
d.save()
idle()
from hlidskjalf import rebase                                    # noqa: E402
cf = [b for b in d.root if rebase.conflict_info(b)]
ok("Konfliktblock in der App", len(cf) == 1 and cf[0]["data"]["text"] == "Zwei App sagt" and cf[0]["tunes"]["hkconflict"].get("of") == "a2",
   d.root)
ok("Konfliktblock hat Namen", bool((cf[0]["tunes"]["hkconflict"] if cf else {}).get("name")), cf)
rebase.conflict_take(d.root, cf[0]["id"])
d.mark_dirty()
d.save()
idle()
st, n = call("GET", f"/api/notes/{NID}")
bl = json.loads(n["body"])["blocks"]
ok("Uebernehmen gespeichert", [b["data"]["text"] for b in bl] == ["Eins v3 weiter", "Zwei App sagt", "Drei von B"]
   and not any("hkconflict" in (b.get("tunes") or {}) for b in bl), bl)
# Delta: B aendert, App holt per ?since
_t, nb = texts(NID, "hk-test-b")
bb = json.loads(nb["body"])["blocks"]
bb[2]["data"]["text"] = "Drei Delta"
call("PATCH", f"/api/notes/{NID}", {"body": json.dumps({"blocks": bb}), "format": "blocks", "rev": nb["rev"], "merge": 1}, user="hk-test-b")
keep = d.root[0]
pulled = []
d.pull(pulled.append)
pump(lambda: pulled, 8)
idle()
ok("?since gesendet", any(m == "GET" and "since=" in u for m, u, b, h in SENT), [u for m, u, b, h in SENT if m == "GET"][-3:])
ok("Delta uebernommen (unveraendert = dasselbe Objekt)", pulled == [True] and d.root[0] is keep and d.root[2]["data"]["text"] == "Drei Delta",
   [x["data"]["text"] for x in d.root])

# --- 2. Anwesenheit je Block -----------------------------------------------------------------------------------------
from hlidskjalf.live import Presence                             # noqa: E402
got = []
pr = Presence(NID, lambda: True, lambda: True, got.append, lambda r, b: None, lambda: d.rev)
pr.block = "a2"
pr.typed()
pump(lambda: got, 6)
st, r = call("POST", "/api/presence", {"note_id": NID, "editing": True}, user="hk-test-b")
users = r.get("users") or []
a = next((u for u in users if not u.get("me")), {})
ok("Server meldet Block + Farbe der App-Person", a.get("block") == "a2" and a.get("color"), users)
pr.stop()

# --- 3. Aufgabenquellen ----------------------------------------------------------------------------------------------
st, db = call("POST", "/api/notes", {"title": "E2 Linux Aufgaben", "kind": "database"})
DB = db["id"]
CLEAN.append(DB)
schema = {"props": [{"id": "st", "name": "Status", "type": "select", "status": True, "options": [
    {"id": "neu", "name": "Neu", "group": "todo"}, {"id": "lauf", "name": "Läuft", "group": "doing"},
    {"id": "fertig", "name": "Fertig", "group": "done"}]}, {"id": "due", "name": "Fällig", "type": "date"}],
    "views": [{"id": "t", "name": "Tabelle", "type": "table"}]}
st, r = call("PATCH", f"/api/notes/{DB}", {"schema": schema})
st, r = call("PUT", f"/api/notes/{DB}/tasksource", {"map": {"done": "st", "open_value": "neu", "due": "due"}, "subscribe": True})
ok("Quelle eingerichtet + abonniert", st == 200 and r.get("map"), r)
st, row = call("POST", "/api/notes", {"title": "Zeile läuft", "parent_id": DB, "props": {"st": "lauf", "due": time.strftime("%Y-%m-%d")}})
ROW = row["id"]
store.load_today()
pump(lambda: store.task_items and any(x.get("id") == ROW for x in store.task_items), 8)
it = next((x for x in store.task_items or [] if x.get("id") == ROW), None)
ok("Zeile in der App-Liste (mit Quelle)", it is not None and it.get("kind") == "row" and it.get("src") == DB
   and any(s.get("db") == DB for s in store.task_sources), store.task_items)
prevs = []
store.set_item_done(it, True, undo=prevs.append)
pump(lambda: prevs, 8)
idle()
st, rn = call("GET", f"/api/notes/{ROW}")
ok("abgehakt -> Fertig, prev = lauf", rn["props"].get("st") == "fertig" and prevs == ["lauf"], (rn.get("props"), prevs))
store.set_item_done(dict(it, done=True), False, prevs[0])
pump(lambda: call("GET", f"/api/notes/{ROW}")[1]["props"].get("st") == "lauf", 8)
idle()
st, rn = call("GET", f"/api/notes/{ROW}")
ok("Rueckgaengig -> wieder Laeuft (nicht Neu)", rn["props"].get("st") == "lauf", rn.get("props"))
ok("Warteschlange leer", len(store.tqueue) == 0, store.tqueue.d)
store.add_task_to("Neu aus der App", DB)
pump(lambda: any(x.get("text") == "Neu aus der App" for x in store.task_items or []), 8)
ok("neue Aufgabe in der Quelle", any(x.get("text") == "Neu aus der App" and x.get("src") == DB for x in store.task_items or []))
# Warteschlange offline: Netzfehler behalten, danach nachholen
it2 = next(x for x in store.task_items if x.get("text") == "Neu aus der App")
real = client.request


def offline(m, p, *a, **k):
    if "tasks/src" in p:
        raise APIError(0, "offline")
    return real(m, p, *a, **k)


client.request = offline
store.set_item_done(it2, True)
idle()
ok("offline: in der Warteschlange", "r:%d" % it2["id"] in store.tqueue.d, store.tqueue.d)
client.request = real
store.flush_task_queue()
pump(lambda: len(store.tqueue) == 0, 8)
idle()
st, rn = call("GET", f"/api/notes/{it2['id']}")
ok("nachgeholt -> erledigt", rn["props"].get("st") == "fertig", rn.get("props"))
st, dg = call("GET", "/api/tasks/digest")
ok("Zusammenfassung lesbar", st == 200 and "on" in dg, dg)

# --- 4. Erwaehnung + synchronisierte Bloecke ---------------------------------------------------------------------------
st, ppl = call("GET", f"/api/notes/{NID}/people?q=")
b_ref = next((p["ref"] for p in ppl.get("people") or [] if not p.get("me")), None)
ok("Person mit Zugriff (Handle)", isinstance(b_ref, str) and b_ref.startswith("~"), ppl)
from hlidskjalf.richtext import RichText                         # noqa: E402
rt = RichText("Hallo ")
rt.get_buffer().place_cursor(rt.get_buffer().get_end_iter())
rt.insert_mention(b_ref, "B")
d.load(force=True)
pump(lambda: not d.dirty, 4)
idle()
d.root[0]["data"]["text"] = rt.html()
d.mark_dirty()
d.save()
idle()
st, n = call("GET", f"/api/notes/{NID}")
bl = json.loads(n["body"])["blocks"]
ok("Erwaehnung gespeichert (data-u = Handle)", f'data-u="{b_ref}"' in bl[0]["data"]["text"] and b_ref in (n.get("people") or {}),
   (bl[0]["data"]["text"], n.get("people")))
# Original auf Seite A, Kopie auf Seite NID; Original UND Kopie zusaetzlich auf derselben Seite A
st, pa = call("POST", "/api/notes", {"title": "E2 Linux Original"})
A = pa["id"]
CLEAN.append(A)
SID = "LinuxE2SyncTest1"
st, r = call("PATCH", f"/api/notes/{A}", {"body": json.dumps({"blocks": [
    {"id": "sk", "type": "synced", "data": {"sid": SID}},               # Kopie VOR dem Original auf derselben Seite
    {"id": "so", "type": "synced", "data": {"sid": SID, "blocks": [P("si", "Synchron Inhalt")]}}, P("ax", "danach")]}),
    "format": "blocks"}, hdr={"X-Saga-Caps": "inline2,cols6,merge1,sync1"})
st, na = call("GET", f"/api/notes/{A}")
ba = json.loads(na["body"])["blocks"]
ok("Original + Kopie auf derselben Seite: Original bleibt", any(b.get("id") == "so" and (b["data"].get("blocks") or [{}])[0].get("data", {}).get("text") == "Synchron Inhalt"
                                                                 for b in ba), ba)
dA = PageDoc(A)
dA.load()
pump(lambda: dA.fetched, 8)
idle()
dA.root[-1]["data"]["text"] = "danach geaendert"                 # nur ein anderer Block
dA.mark_dirty()
dA.save()
idle()
st, na = call("GET", f"/api/notes/{A}")
ba = json.loads(na["body"])["blocks"]
so = next((b for b in ba if b.get("id") == "so"), None)
ok("App speichert Seite mit Original+Kopie: Original-Inhalt erhalten", so is not None and so["data"]["blocks"][0]["data"]["text"] == "Synchron Inhalt"
   and ba[-1]["data"]["text"] == "danach geaendert", ba)
# Kopie auf NID bearbeiten (sync1)
d.load(force=True)
pump(lambda: not d.dirty, 4)
idle()
d.root.append({"id": "kop", "type": "synced", "data": {"sid": SID}})
d.mark_dirty()
d.save()
idle()
d.load(force=True)
pump(lambda: any(b.get("id") == "kop" and b["data"].get("src") for b in d.root), 8)
idle()
kop = next(b for b in d.root if b.get("id") == "kop")
from hlidskjalf.editor import synced_editable                   # noqa: E402
ok("Kopie mit Inhalt + src (role owner)", synced_editable(kop) and kop["data"]["src"].get("note") == A, kop)
rev_a = kop["data"]["src"]["rev"]
kop["data"]["blocks"][0]["data"]["text"] = "Synchron aus der Kopie"
d.mark_dirty()
d.save()
idle()
st, na = call("GET", f"/api/notes/{A}")
so = next((b for b in json.loads(na["body"])["blocks"] if b.get("id") == "so"), {})
ok("in das Original geschrieben", (so.get("data", {}).get("blocks") or [{}])[0].get("data", {}).get("text") == "Synchron aus der Kopie", so)
ok("Kopf sync1 gesendet", any("sync1" in h.get("X-Saga-Caps", "") for m, u, b, h in SENT if m == "PATCH" and u.endswith(f"/api/notes/{NID}")))
kop = next(b for b in d.root if b.get("id") == "kop")
ok("src.rev aus synced_rev uebernommen", kop["data"]["src"]["rev"] > rev_a, kop["data"]["src"])

# --- 6. Verschieben -------------------------------------------------------------------------------------------------------
t0, n0 = texts(NID)
ids0 = [b["id"] for b in json.loads(n0["body"])["blocks"]]
prev = ids0[ids0.index("a3") - 1]                                # wie die App: Vorgaenger merken
st, r = call("POST", f"/api/notes/{NID}/blocks/move", {"ids": ["a3"], "to": A, "rev_a": n0["rev"], "at": "end"})
ok("verschoben (Server)", st == 200 and r.get("ids") == ["a3"], r)
st, r2 = call("POST", f"/api/notes/{A}/blocks/move", {"ids": ["a3"], "to": NID, "rev_a": r["rev_b"], "after": prev})
ok("Rueckgaengig = Gegenrichtung (wie die App)", st == 200, r2)
t, n = texts(NID)
ok("wieder an alter Stelle", t == t0, (t, t0))

# --- 7. Zeitleiste: Props-PATCH wie die App -----------------------------------------------------------------------------
from hlidskjalf import dbvalues as dv                            # noqa: E402
patch = dv.timeline_patch({"id": "due", "type": "date"}, None, "2026-10-28/2026-10-30", None, 2)
st, r = call("PATCH", f"/api/notes/{ROW}", {"props": patch})
st, rn = call("GET", f"/api/notes/{ROW}")
ok("Zeitraum gemeinsam verschoben", rn["props"].get("due") == "2026-10-30/2026-11-01", rn.get("props"))

# --- aufraeumen ----------------------------------------------------------------------------------------------------------
call("DELETE", f"/api/notes/{DB}/tasksource")
for nid in CLEAN:
    call("DELETE", f"/api/notes/{nid}")
    call("DELETE", f"/api/trash/{nid}")
print("ERGEBNIS", sum(res), "/", len(res))
sys.exit(0 if all(res) else 1)
