"""Schein-Server: Saga 2.5 Etappe 1 der Apps (docs/APP-API.md) - Faehigkeiten in /api/version, Personen-Verzeichnis
(people) und Auswahl, Formeln/Rollups (fertige Werte), PATCH-Antwort mit calc/values, Diagramm, Gewohnheit abhaken.
Aktiv nur mit s.v25 = True (aeltere Tests sehen den alten Server)."""
import json
import time

FEATURES = ["formula1", "rollup1", "dbgroup1", "person1", "file1", "move1", "render1", "relfilter1", "datetime1", "status1",
            "subtasks1", "tableopts1", "weather1", "habit1", "upcoming1", "chart1"]


def init(s):
    s.v25 = False
    s.v25_people = {}          # Kennung -> {name, avatar, me, remote, gone?}
    s.v25_calc = {}            # Zeile -> {pid: wert} (Formel-/Rollup-Werte, "rechnet der Server")
    s.v25_fx_err = {}          # Zeile -> {pid: code}
    s.v25_row_extra = {}       # Zeile -> {"blocked": True, "depth": 1, ...}
    s.v25_chart = {}           # (db, view) -> Antwort von .../chart
    s.v25_habit = []           # [(seite, block, body)]
    s.v25_uploads = []         # [(note, name)]


def version(s):
    out = {"version": "2.5.0", "min_app": "1.0.0"}
    if s.v25:
        out["features"] = list(FEATURES)
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


def handle(s, method, path, parts, q, body):
    if not s.v25:
        return None
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
    if path == "api/files" and method == "POST":
        s.v25_uploads.append((q.get("note"), len(getattr(s, "_raw", b"") or b"")))
        fid = "%024x" % (len(s.v25_uploads) + 0xabc)
        return s._reply(200, {"file": {"url": f"/files/{fid}", "name": "anhang.txt"}})
    return None


def as_of():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
