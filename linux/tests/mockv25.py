"""Schein-Server: Saga 2.5 Etappe 1 der Apps (docs/APP-API.md) - Faehigkeiten in /api/version, Personen-Verzeichnis
(people) und Auswahl, Formeln/Rollups (fertige Werte), PATCH-Antwort mit calc/values, Diagramm, Gewohnheit abhaken.
Aktiv nur mit s.v25 = True (aeltere Tests sehen den alten Server)."""
import json
import time

FEATURES = ["formula1", "rollup1", "dbgroup1", "person1", "file1", "move1", "render1", "relfilter1", "datetime1", "status1",
            "subtasks1", "tableopts1", "weather1", "habit1", "upcoming1", "chart1", "subpages1"]


def init(s):
    s.v25 = False
    s.chatadm = False          # 2.5 Gruppeninfo (Feature chatadm1 nur mit extra_features)
    s.admin_log = []           # [(cid, who, body)]
    s.admin_fail = None        # (code, meldung) fuer PATCH .../members/{who}
    s.contacts_on = False      # 2.5 Kontakte (Feature contacts1)
    s.contacts = []            # [{ref, name, sub, avatar, remote, host?, ready, gone, last_at, hidden?}]
    s.contacts_log = []
    # 2.6 Adressbuch (Feature contacts2 nur mit extra_features; Bugs-DB 2027a): id -> Kontakt wie GET /api/contacts/{id}
    s.book_on = False
    s.book = {}
    s.book_cc = None
    s.book_log = []            # [(methode, pfad, body)]
    s.book_next = 100
    s.v25_people = {}          # Kennung -> {name, avatar, me, remote, gone?}
    s.v25_calc = {}            # Zeile -> {pid: wert} (Formel-/Rollup-Werte, "rechnet der Server")
    s.v25_fx_err = {}          # Zeile -> {pid: code}
    s.v25_row_extra = {}       # Zeile -> {"blocked": True, "depth": 1, ...}
    s.v25_chart = {}           # (db, view) -> Antwort von .../chart
    s.v25_habit = []           # [(seite, block, body)]
    s.v25_uploads = []         # [(note, name)]
    # 2.5 (P3): Zusammenfuehren - Basis je (Seite, rev) und eingereichte Fassung; einfache Regeln (genau: Server-Vektoren)
    s.merge_api = False        # PATCH mit merge: 1 -> merged statt 409
    s.merge_bases = {}         # (nid, rev) -> Bloecke (Server-Stand)
    s.merge_sent = {}          # (nid, rev) -> eingereichte Bloecke
    s.merge_gone = set()       # (nid, rev): Basis "nicht mehr da" -> 409
    s.merge_log = []           # [(nid, body)]
    s.since_log = []           # [(nid, since, delta?)]
    # 2.5 (Paket 7): Aufgabenquellen
    s.tasksrc = False          # features tasksrc1/taskdue1 + Antworten
    s.src_items = []           # Zeilen-Eintraege der Liste (kind row)
    s.src_sources = []
    s.src_people = {}
    s.src_log = []             # [(methode, pfad, body)]
    s.src_fail = {}            # zeile -> code
    s.src_prev = {}            # zeile -> prev (Antwort auf done)
    s.digest = {"on": False, "time": "07:30", "tz": ""}


def version(s):
    out = {"version": "2.5.0", "min_app": "1.0.0"}
    if s.v25:
        out["features"] = list(FEATURES) + (["tasksrc1", "taskdue1"] if getattr(s, "tasksrc", False) else []) \
            + list(getattr(s, "extra_features", []))
    return out


def _fx_pids(schema):
    return {p.get("id") for p in (schema or {}).get("props") or [] if p.get("type") in ("formula", "rollup")}


def _vals_pids(schema):
    return [p.get("id") for p in (schema or {}).get("props") or [] if p.get("type") in ("person", "files")]


def decorate_rows(s, nid, j):
    """GET .../rows: Formelwerte, fx_err, people, Zusatzfelder einsetzen"""
    if not s.v25 or not isinstance(j, dict):
        return j
    for r in j.get("rows") or []:
        r["props"] = dict(r.get("props") or {})
        r["props"].update(s.v25_calc.get(r["id"]) or {})
        if s.v25_fx_err.get(r["id"]):
            r["fx_err"] = dict(s.v25_fx_err[r["id"]])
        r.update(s.v25_row_extra.get(r["id"]) or {})
    if any(p.get("type") == "person" for p in (j.get("schema") or {}).get("props") or []):
        j["people"] = dict(s.v25_people)
    return j


def decorate_note(s, nid, j):
    """GET api/notes/{zeile}: Formelwerte und people wie /rows"""
    if not s.v25 or not isinstance(j, dict):
        return j
    par = s.notes.get(j.get("parent_id"))
    if not par or par.get("kind") != "database":
        return j
    j["props"] = dict(j.get("props") or {})
    j["props"].update(s.v25_calc.get(nid) or {})
    if s.v25_fx_err.get(nid):
        j["fx_err"] = dict(s.v25_fx_err[nid])
    j.update(s.v25_row_extra.get(nid) or {})
    if any(p.get("type") == "person" for p in (par.get("schema") or {}).get("props") or []):
        j["people"] = dict(s.v25_people)
    return j


def patch_row(s, nid, body, out):
    """PATCH einer Zeile: berechnete Werte verwerfen (nie gespeichert), Antwort mit calc/fx_err/values/people"""
    if not s.v25:
        return out
    n = s.notes.get(nid)
    par = s.notes.get((n or {}).get("parent_id"))
    if not par or par.get("kind") != "database":
        return out
    sc = par.get("schema") or {}
    fx = _fx_pids(sc)
    props = n.get("props") or {}
    for k in list(props):
        if k in fx:
            props.pop(k)                                   # stillschweigend verworfen
    if fx:
        out["calc"] = {k: (s.v25_calc.get(nid) or {}).get(k) for k in fx}
        out["fx_err"] = dict(s.v25_fx_err.get(nid) or {})
    vp = _vals_pids(sc)
    if vp:
        out["values"] = {k: props.get(k) for k in vp}
        out["people"] = dict(s.v25_people)
    return out


# --- (2.6, contacts2) Adressbuch - einfache Regeln wie docs/APP-API.md "Kontakte" (genau: der echte Server) ---------------
def book_add(s, name=None, saga=None, addrs=(), last_at=None, **kw):
    """Kontakt im Schein-Server anlegen. saga: {ref, remote?, host?, sub?, ready?, gone?} (feste Adresse), addrs: [(kind, value, type)]"""
    s.book_next += 1
    cid = s.book_next
    now = time.time()
    al = []
    if saga:
        s.book_next += 1
        al.append({"id": s.book_next, "kind": "fed" if saga.get("remote") else "saga", "value": saga["ref"], "type": None, "fixed": True})
    for k, v, t in addrs:
        s.book_next += 1
        al.append({"id": s.book_next, "kind": k, "value": v, "type": t, "fixed": False})
    c = {"id": cid, "ver": 1, "origin": "chat" if saga else "manual", "own_name": kw.get("own_name", None if saga else name),
         "live_name": name, "saga": dict({"remote": False, "host": None, "sub": "", "avatar": None, "ready": True, "gone": False}, **saga) if saga else None,
         "form": kw.get("form"), "salutation": kw.get("salutation"), "addrs": al, "last_at": last_at, "created": now, "updated": now}
    s.book[cid] = c
    return cid


def _book_view(c):
    out = {k: v for k, v in c.items() if k != "live_name"}
    out["name"] = c.get("own_name") or c.get("live_name") or next((a["value"] for a in c["addrs"] if not a["fixed"]), "?")
    out["addrs"] = [dict(a) for a in c["addrs"]]
    return out


def _e164(v, cc):
    import re
    x = re.sub(r"[\s\-/.()]", "", v or "")
    if x.startswith("00"):
        x = "+" + x[2:]
    elif x.startswith("0"):
        if not cc:
            return None
        x = "+" + str(cc) + x[1:]
    return x if re.fullmatch(r"\+[1-9]\d{7,14}", x) else None


def _book_addrs(s, c, items, cc):
    """private Adressen pruefen -> (liste, fehler)"""
    out, seen = [], set()
    for it in items if isinstance(items, list) else []:
        if not isinstance(it, dict):
            return None, (400, {"error": "Ungültige Adresse."})
        k, v = it.get("kind"), it.get("value")
        if k in ("saga", "fed"):
            if any(a["fixed"] and a["value"] == v for a in (c or {}).get("addrs") or []):
                continue
            return None, (400, {"error": "Saga-Adressen entstehen nur aus Chats."})
        if k == "e164":
            v = _e164(v, cc or s.book_cc)
            if v is None:
                return None, (400, {"error": "Ungültige Telefonnummer."})
        elif k == "email":
            v = str(v or "").strip()
            if v.count("@") != 1 or " " in v or len(v) > 254:
                return None, (400, {"error": "Ungültige E-Mail-Adresse."})
            v = v.split("@")[0] + "@" + v.split("@")[1].lower()
        else:
            return None, (400, {"error": "Ungültige Adresse."})
        if (k, v) in seen:
            continue
        seen.add((k, v))
        dup = next((o for o in s.book.values() if o is not c and any(a["kind"] == k and a["value"] == v for a in o["addrs"])), None)
        if dup is not None:
            return None, (409, {"error": "Diese Nummer bzw. Adresse steht schon bei einem anderen Kontakt.", "contact_id": dup["id"]})
        old = next((a for a in (c or {}).get("addrs") or [] if a["kind"] == k and a["value"] == v), None)
        if old is None:
            s.book_next += 1
        out.append({"id": old["id"] if old else s.book_next, "kind": k, "value": v, "type": it.get("type") if it.get("type") in
                    ("mobile", "home", "work", "other") else None, "fixed": False})
    if len([a for a in out if a["kind"] == "e164"]) > 10 or len([a for a in out if a["kind"] == "email"]) > 10:
        return None, (400, {"error": "Zu viele Adressen."})
    return out, None


def _book(s, method, path, parts, q, body):
    if not getattr(s, "book_on", False) or parts[:2] != ["api", "contacts"]:
        return None
    s.book_log.append((method, path, dict(body) if isinstance(body, dict) else body))
    if path == "api/contacts/settings":
        if method == "PUT":
            v = str(body.get("cc") or "").lstrip("+") or None
            if v is not None and not (v.isdigit() and 1 <= len(v) <= 3):
                return s._reply(400, {"error": "Ungültige Landesvorwahl."})
            s.book_cc = v
        return s._reply(200, {"cc": s.book_cc})
    if path == "api/contacts" and method == "GET":
        qq = (q.get("q") or "").lower()
        cs = [_book_view(c) for c in s.book.values()]
        if qq:
            dg = "".join(ch for ch in qq if ch.isdigit())
            cs = [c for c in cs if qq in c["name"].lower() or any(qq in a["value"].lower() for a in c["addrs"] if a["kind"] == "email")
                  or (len(dg) >= 3 and any(dg in a["value"] for a in c["addrs"] if a["kind"] == "e164"))]
        cs.sort(key=lambda c: -(c.get("last_at") or c.get("updated") or 0))
        off, lim = int(q.get("offset") or 0), int(q.get("limit") or 200)
        return s._reply(200, {"contacts": cs[off:off + lim], "total": len(cs)})
    if path == "api/contacts" and method == "POST":
        if not str(body.get("own_name") or "").strip():
            return s._reply(400, {"error": "Bitte einen Namen eingeben."})
        if any(isinstance(a, dict) and a.get("kind") in ("saga", "fed") for a in body.get("addrs") or []):
            return s._reply(400, {"error": "Saga-Adressen entstehen nur aus Chats."})
        al, err = _book_addrs(s, None, body.get("addrs"), body.get("cc"))
        if err:
            return s._reply(*err)
        if not al:
            return s._reply(400, {"error": "Mindestens eine Nummer oder E-Mail-Adresse."})
        cid = book_add(s, body["own_name"].strip(), form=body.get("form"), salutation=body.get("salutation"))
        s.book[cid]["addrs"] = al
        s.push_event("contacts.changed", {"id": cid})
        return s._reply(201, _book_view(s.book[cid]))
    if len(parts) == 3 and parts[2].isdigit():
        c = s.book.get(int(parts[2]))
        if c is None:
            return s._reply(404, {"error": "not found"})
        if method == "GET":
            return s._reply(200, _book_view(c))
        if method == "DELETE":
            del s.book[c["id"]]
            s.push_event("contacts.changed", {"id": c["id"], "deleted": True})
            return s._reply(200, {"ok": True})
        if method == "PATCH":
            if body.get("ver") != c["ver"]:
                return s._reply(409, {"error": "Der Kontakt wurde inzwischen geändert.", "contact": _book_view(c)})
            new = dict(c)
            for k in ("own_name", "form", "salutation"):
                if k in body:
                    new[k] = (body[k] or None) if not isinstance(body[k], str) else (body[k].strip() or None)
            if "addrs" in body:
                al, err = _book_addrs(s, c, body["addrs"], body.get("cc"))
                if err:
                    return s._reply(*err)
                new["addrs"] = [a for a in c["addrs"] if a["fixed"]] + al
            if c["origin"] == "manual" and (not new.get("own_name") or not [a for a in new["addrs"] if not a["fixed"]]):
                return s._reply(400, {"error": "Name und mindestens eine Adresse sind Pflicht."})
            new["ver"] = c["ver"] + 1
            new["updated"] = time.time()
            s.book[c["id"]] = new
            s.push_event("contacts.changed", {"id": c["id"]})
            return s._reply(200, _book_view(new))
    return s._reply(405, {"error": "method"})


def _book_id(s, ref):
    return next((c["id"] for c in getattr(s, "book", {}).values() if c.get("saga") and c["saga"]["ref"] == ref), None)


def _chatadm(s, method, path, parts, q, body):
    """(2.5) Gruppeninfo: PATCH /api/chats/{cid}/members/{who} {admin}; Kontakte: GET/DELETE /api/chat/contacts"""
    import mockv2
    me = s.me
    if path == "api/chat/contacts" and method == "GET" and getattr(s, "contacts_on", False):
        s.contacts_log.append(("GET", dict(q)))
        lim = int(q.get("limit") or 50)
        out = [dict(k) for k in s.contacts if not k.get("hidden")]
        out.sort(key=lambda k: -k.get("last_at", 0))
        for k in out:
            k.pop("hidden", None)
            if getattr(s, "book_on", False):
                k["contact_id"] = _book_id(s, k["ref"])
        return s._reply(200, {"contacts": out[:lim]})
    if parts[:3] == ["api", "chat", "contacts"] and len(parts) == 4 and method == "DELETE" and getattr(s, "contacts_on", False):
        import urllib.parse
        ref = urllib.parse.unquote(parts[3])
        s.contacts_log.append(("DELETE", ref))
        k = next((x for x in s.contacts if x["ref"] == ref and not x.get("hidden")), None)
        if k is None:
            return s._reply(404, {"error": "not found"})
        k["hidden"] = True
        return s._reply(200, {"ok": True})
    if path == "api/chat/people" and getattr(s, "contacts_on", False):     # Kontakte vorne (contact: true), jede Laenge
        qq = (q.get("q") or "").lower()
        ks = [k for k in s.contacts if not k.get("hidden") and not k.get("gone") and qq and qq in k["name"].lower()]
        users = [{"username": k["ref"], "name": k["name"], "ready": True, "contact": True, "contact_id": _book_id(s, k["ref"])}
                 for k in ks if not k.get("remote")]
        remote = [{"handle": k["ref"], "name": k["name"], "host": k.get("host"), "contact": True, "contact_id": _book_id(s, k["ref"])}
                  for k in ks if k.get("remote")]
        have = {u["name"] for u in users}
        if len(qq) >= 3:
            users += [{"username": "~" + u, "name": n, "ready": u in s.idents} for u, n in s.names.items()
                      if u != me and (u.startswith(qq) or qq in n.lower()) and n not in have]
        return s._reply(200, {"users": users, "groups": [], "remote": remote})
    if (getattr(s, "chatadm", False) and parts[:2] == ["api", "chats"] and len(parts) == 5 and parts[2].isdigit()
            and parts[3] == "members" and method == "PATCH"):
        ch = s.chats.get(int(parts[2]))
        who = parts[4][1:] if parts[4].startswith("~") else parts[4]
        s.admin_log.append((int(parts[2]), parts[4], body))
        if ch is None or me not in ch["members"] or who not in ch["members"]:
            return s._reply(404, {"error": "not found"})
        if ch["kind"] != "free" or not isinstance(body.get("admin"), bool):
            return s._reply(400, {"error": "Nur in freien Gruppen."})
        if s.admin_fail:
            code, msg = s.admin_fail
            return s._reply(code, {"error": msg})
        adm = mockv2.admins(ch)
        if me not in adm:
            return s._reply(403, {"error": "Dafür fehlt dir die Berechtigung."})
        if not body["admin"] and adm == {who}:
            return s._reply(409, {"error": "Das ist der letzte Admin der Gruppe – erst jemand anderen zum Admin machen."})
        (adm.add if body["admin"] else adm.discard)(who)
        return s._reply(200, {"chat": mockv2._chat_dict(s, ch, me)})
    return None


def handle(s, method, path, parts, q, body):
    r = _book(s, method, path, parts, q, body)
    if r is not None:
        return r
    r = _chatadm(s, method, path, parts, q, body)
    if r is not None:
        return r
    if not s.v25:
        return None
    r = _tasksrc(s, method, path, parts, q, body)
    if r is not None:
        return r
    if len(parts) >= 4 and parts[:2] == ["api", "notes"] and parts[2].isdigit():
        nid = int(parts[2])
        rest = parts[3:]
        if rest == ["people"] and method == "GET":
            qq = (q.get("q") or "").lower()
            ppl = [{"ref": k, "name": v.get("name"), "avatar": v.get("avatar"), "me": bool(v.get("me")), "remote": bool(v.get("remote"))}
                   for k, v in s.v25_people.items() if not v.get("gone") and qq in (v.get("name") or "").lower()]
            return s._reply(200, {"people": ppl[:20], "more": len(ppl) > 20})
        if rest == ["chart"] and method == "GET":
            r = s.v25_chart.get((nid, q.get("view")))
            if r is None:
                return s._reply(200, {"chart": None, "view": q.get("view"), "error": "bad_cfg"})
            return s._reply(200, r)
        if len(rest) == 3 and rest[0] == "widgets" and rest[2] == "habit" and method == "POST":
            s.v25_habit.append((nid, rest[1], body))
            w = dict(s.widget_values.get(rest[1]) or {})
            d = json.loads(json.dumps(w.get("data") or {}))
            for h in d.get("habits") or []:
                if h.get("pid") == body.get("pid") and body.get("day") in (d.get("days") or []):
                    h["done"][d["days"].index(body["day"])] = bool(body.get("on"))
            w["data"] = d
            s.widget_values[rest[1]] = w
            return s._reply(200, {"widget": w})
    if len(parts) == 5 and parts[:2] == ["api", "notes"] and parts[3:] == ["blocks", "move"] and method == "POST":
        return _move(s, int(parts[2]), body)
    if path == "api/account/api-tokens" and method == "GET":
        return s._reply(200, {"enabled": True, "tokens": getattr(s, "api_tokens", []), "max": 10})
    if parts[:3] == ["api", "account", "api-tokens"] and len(parts) == 4 and method == "DELETE":
        tid = int(parts[3])
        for t in getattr(s, "api_tokens", []):
            if t["id"] == tid:
                t["revoked"] = 1.0
                return s._reply(200, {"ok": True})
        return s._reply(404, {"error": "not found"})
    if path == "api/files" and method == "POST":
        s.v25_uploads.append((q.get("note"), len(getattr(s, "_raw", b"") or b"")))
        fid = "%024x" % (len(s.v25_uploads) + 0xabc)
        return s._reply(200, {"file": {"url": f"/files/{fid}", "name": "anhang.txt"}})
    return None


def as_of():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


# --- 2.5 (P3): Zusammenfuehren (vereinfachte Regeln wie saga/merge.py fuer die App-Tests) -------------------------------------
def _blocks(body):
    try:
        return json.loads(body or "{}").get("blocks") or []
    except ValueError:
        return []


def keep_base(s, nid, n):
    if getattr(s, "merge_api", False) and (n.get("format") or "blocks") == "blocks":
        s.merge_bases[(nid, n["rev"])] = json.loads(json.dumps(_blocks(n.get("body"))))


def _merge(base, mine, theirs, by):
    bb = {b["id"]: b for b in base}
    mm = {b["id"]: b for b in mine}
    tt = {b["id"]: b for b in theirs}
    info = {"conflicts": [], "restored": [], "lost": []}
    out = []
    for b in theirs:
        i = b["id"]
        if i in mm:
            mc = mm[i] != bb.get(i)
            tc = b != bb.get(i)
            if mc and tc and mm[i] != b:
                out.append(b)                              # Ueberschneidung: Server-Fassung bleibt, eigene als Konfliktblock
                cb = json.loads(json.dumps(mm[i]))
                cb["id"] = i + "-k"
                cb.setdefault("tunes", {})["hkconflict"] = dict(by, at=1, of=i)
                out.append(cb)
                info["conflicts"].append(cb["id"])
            else:
                out.append(mm[i] if mc else b)
        elif i in bb:
            if b != bb[i]:
                out.append(b)                              # geloescht gegen geaendert: Aenderung gewinnt
                info["restored"].append(i)
        else:
            out.append(b)                                  # fremd neu
    prev = None
    for b in mine:
        if b["id"] not in bb and b["id"] not in tt:        # eigen neu: hinter dem Vorgaenger
            k = next((j + 1 for j, x in enumerate(out) if x["id"] == prev), len(out) if prev else 0)
            out.insert(k, b)
        prev = b["id"]
    return out, info


def merge_patch(s, nid, n, body):
    """PATCH mit merge: 1 und veralteter rev (bzw. base: sent) -> 200 mit merged; Basis fehlt -> None (409 wie bisher)"""
    if not getattr(s, "merge_api", False) or not body.get("merge") or body.get("rev") is None or "body" not in body:
        return None
    want = body["rev"]
    sent = body.get("base") == "sent"
    if want == n["rev"] and not sent:
        s.merge_sent[(nid, want + 1)] = _blocks(body["body"])
        return None                                        # ganz normal speichern (keep_base danach)
    s.merge_log.append((nid, dict(body)))
    if (nid, want) in s.merge_gone:
        return None
    base = s.merge_sent.get((nid, want)) if sent else s.merge_bases.get((nid, want))
    if base is None:
        return None
    mine = _blocks(body["body"])
    doc, info = _merge(base, mine, _blocks(n.get("body")), {"by": "~testtesttesttest", "name": "Test"})
    n["body"] = json.dumps({"blocks": doc})
    if "title" in body and body["title"] != n.get("title"):
        info["title_lost"] = True                          # eigene Fassung gilt
        n["title"] = body["title"]
    n["rev"] += 1
    n["updated"] = time.time()
    n["updated_by"] = s.me
    s.merge_sent[(nid, n["rev"])] = mine
    keep_base(s, nid, n)
    m = dict(info, **{"from": want})
    if not m.get("title_lost"):
        m.pop("title_lost", None)
    return s._reply(200, {"ok": True, "rev": n["rev"], "updated": n["updated"], "body": n["body"], "format": "blocks",
                          "title": n.get("title") or "", "merged": m})


def since_delta(s, nid, out, q):
    """GET ?since=<rev>: delta {from, blocks} statt body (unveraenderte Bloecke nur als {"id"})"""
    if not getattr(s, "merge_api", False) or "since" not in q:
        return out
    try:
        since = int(q["since"])
    except ValueError:
        return out
    base = s.merge_bases.get((nid, since))
    s.since_log.append((nid, since, base is not None))
    if base is None or (out.get("format") or "blocks") != "blocks":
        return out
    bb = {b["id"]: b for b in base}
    blocks = [{"id": b["id"]} if bb.get(b["id"]) == b else b for b in _blocks(out.get("body"))]
    out = dict(out)
    out.pop("body", None)
    out["delta"] = {"from": since, "blocks": blocks}
    return out


def enable_merge(s):
    """Schein-Server kann zusammenfuehren (Antwortform merged); aktuelle Staende als Basis merken"""
    s.merge_api = True
    for nid, n in s.notes.items():
        keep_base(s, nid, n)


# --- 2.5 (Paket 7): Aufgabenquellen ------------------------------------------------------------------------------------
def _tasksrc(s, method, path, parts, q, body):
    if not getattr(s, "tasksrc", False):
        return None
    if path == "api/tasks" and method == "GET" and (q.get("with_sources") or q.get("sources")):
        s.src_log.append((method, path, dict(q)))
        inbox = [dict(t, key=f"t:{t['id']}", kind="task", done=False, overdue=bool(t.get("due") and t["due"] < q.get("today", "9")))
                 for t in s.tasks]
        rows = [dict(r) for r in s.src_items if not r.get("done")]
        return s._reply(200, {"open": s.tasks, "done": s.done_tasks, "items": rows[:1] + inbox + rows[1:],
                              "sources": s.src_sources, "people": s.src_people, "limits": {"sources": 20, "rows": 500}})
    if path == "api/tasks" and method == "POST" and body.get("src") is not None:
        s.src_log.append((method, path, dict(body)))
        rid = s.next_id
        s.next_id += 1
        s.src_items.append({"key": f"r:{rid}", "kind": "row", "id": rid, "src": body["src"], "text": body.get("text"), "done": False,
                            "overdue": False, "edit": True})
        return s._reply(200, {"id": rid, "kind": "row", "src": body["src"]})
    if parts[:3] == ["api", "tasks", "src"] and len(parts) == 4 and method == "PATCH":
        rid = int(parts[3])
        s.src_log.append((method, path, dict(body)))
        if rid in s.src_fail:
            return s._reply(s.src_fail[rid], {"error": "mock"})
        it = next((r for r in s.src_items if r["id"] == rid), None)
        if it is None:
            return s._reply(404, {"error": "not found"})
        out = {"ok": True, "src": it["src"]}
        if "done" in body:
            it["done"] = bool(body["done"])
            out["done"] = it["done"]
            if body["done"]:
                out["prev"] = s.src_prev.get(rid, "opt-open")
            it["value"] = body.get("value") if not body["done"] else "opt-done"
        return s._reply(200, out)
    if path == "api/tasks/sources" and method == "GET":
        return s._reply(200, {"sources": s.src_sources, "max": 20})
    if len(parts) == 4 and parts[:2] == ["api", "notes"] and parts[3] == "tasksource":
        db = int(parts[2])
        s.src_log.append((method, path, dict(body)))
        src = next((x for x in s.src_sources if x["db"] == db), None)
        if method == "GET":
            mp = (s.notes.get(db, {}).get("schema") or {}).get("tasks")
            return s._reply(200, {"db": db, "map": (src or {}).get("map") or mp, "scope": (src or {}).get("scope"), "count": len(s.src_sources), "max": 20})
        if method == "DELETE":
            s.src_sources = [x for x in s.src_sources if x["db"] != db]
            return s._reply(200, {"ok": True}) if src else s._reply(404, {"error": "not found"})
        if method == "PUT":
            if src is None:
                n = s.notes.get(db) or {}
                src = {"db": db, "title": n.get("title"), "icon": "", "role": "owner", "scope": "all", "own": True, "count": 0,
                       "map": (n.get("schema") or {}).get("tasks")}
                s.src_sources.append(src)
            if body.get("scope") in ("all", "assigned"):
                src["scope"] = body["scope"]
            return s._reply(200, dict(src))
    if path == "api/tasks/digest":
        if method == "PUT":
            s.digest.update({k: body[k] for k in ("on", "time", "tz") if k in body})
        return s._reply(200, dict(s.digest))
    return None


# --- 2.5 (P6-4): Bloecke verschieben ------------------------------------------------------------------------------------
def _move(s, a, b):
    s.move_log = getattr(s, "move_log", [])
    s.move_log.append((a, dict(b)))
    to = b.get("to")
    if isinstance(to, int) and to < 0:
        return s._reply(400, {"error": "federated", "federated": True})
    na, nb = s.notes.get(a), s.notes.get(to)
    if na is None or nb is None:
        return s._reply(404, {"error": "not found"})
    if getattr(s, "move_fail", None):
        return s._reply(s.move_fail, {"error": "mock", "quota": s.move_fail == 507})
    if b.get("rev_a") != na["rev"]:
        return s._reply(409, {"error": "conflict", "rev_a": na["rev"], "rev_b": nb["rev"]})
    ba, bb = _blocks(na.get("body")), _blocks(nb.get("body"))
    ids = b.get("ids") or []
    moved = [x for x in ba if x.get("id") in ids]
    if not moved:
        return s._reply(404, {"error": "not found"})
    ba = [x for x in ba if x.get("id") not in ids]
    if b.get("after"):
        k = next((i + 1 for i, x in enumerate(bb) if x.get("id") == b["after"]), None)
        if k is None:
            return s._reply(404, {"error": "not found"})
    else:
        k = 0 if b.get("at") == "start" else len(bb)
    bb[k:k] = moved
    na["body"], nb["body"] = json.dumps({"blocks": ba}), json.dumps({"blocks": bb})
    na["rev"] += 1
    nb["rev"] += 1
    return s._reply(200, {"ok": True, "rev_a": na["rev"], "rev_b": nb["rev"], "ids": [x["id"] for x in moved], "renamed": {},
                          "files_copied": 0, "unsigned": [], "comments": 0, "updated": time.time()})
