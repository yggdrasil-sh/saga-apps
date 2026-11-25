"""Gelesen-Bestaetigungen und Online-Status in Chats (Server 2.4) - Regeln ohne Oberflaeche und ohne Netz.

"Wie WhatsApp": standardmaessig an, je Konto getrennt abschaltbar (GET/PUT /api/chat/privacy), gegenseitig - wer etwas
abschaltet, sendet es nicht und sieht es bei anderen auch nicht. Server ohne "features" in GET /api/chat/me (aelter als 2.4):
alles bleibt ausgeblendet. Vertrag: docs/APP-API.md im Server ("Gelesen-Bestaetigungen und Online-Status"), Web
static/chat.js (receiptWant, readersOf, tickEl, presenceChip).

Haken an eigenen Nachrichten: ⏳ wird zugestellt (fstate "pending") -> ✓ zugestellt -> ✓✓ gelesen (Position n der
Bestaetigung >= Position der Nachricht; Position = seq lokal bzw. fseq ueber Servergrenzen). Gruppen: ✓✓ mit Zaehler, bis
alle anderen bestaetigt haben."""
import time

from .i18n import _, ngettext, pgettext
from .util import rel_time

DEFAULT_GAP = 2.0          # hoechstens eine Bestaetigung je Chat und so viele Sekunden (status_limits.receipt_gap)
PRESENCE_RENEW_S = 60      # Abo erneuern, solange sichtbar (ttl 90 s beim Server)
PRESENCE_MAX = 50          # Personen je Abfrage
PRESENCE_LIST = 30         # Direkt-Chats der Liste, die abonniert werden


def _fed(c):
    """Format saga-chat/v2 (2.3 ueber Servergrenzen, 2.5 Stufe 3 auch lokal): Handles, fseq, Bestaetigungen v2"""
    return isinstance(c, dict) and c.get("v") in (2, 3)


def features(me):
    f = (me or {}).get("features")
    return set(x for x in f if isinstance(x, str)) if isinstance(f, list) else set()


def privacy(me):
    p = (me or {}).get("privacy")
    p = p if isinstance(p, dict) else {}
    return {"receipts": p.get("receipts") is not False, "online": p.get("online") is not False}


def gap(me):
    g = ((me or {}).get("status_limits") or {}).get("receipt_gap")
    return float(g) if isinstance(g, (int, float)) and 0 < g <= 60 else DEFAULT_GAP


def receipts_on(me):
    """Server kann es und die eigene Einstellung ist an (gegenseitig: aus = auch keine fremden anzeigen)"""
    return "receipts" in features(me) and privacy(me)["receipts"]


def online_on(me):
    return "online" in features(me) and privacy(me)["online"]


def presence_on(me):
    """Online-Status-Abo laufen lassen? Eigener Online-Status an - oder (2.6, userstatus1) der Server liefert den eigenen
    Status der Personen auch bei abgeschaltetem Online-Status (dann nur status, keine users)"""
    return "online" in features(me) and (privacy(me)["online"] or "userstatus1" in features(me))


def rc_ok(me, c):
    """Bestaetigungen in diesem Chat senden/anzeigen? Nicht bei Nachrichtenanfragen, schreibgeschuetzt oder wenn der andere
    Server (Saga 2.3) es nicht kann (receipts_ok false)"""
    if not receipts_on(me) or not isinstance(c, dict):
        return False
    if c.get("request") or c.get("readonly"):
        return False
    return not (_fed(c) and c.get("receipts_ok") is False)


def presence_user(me, c):
    """Direkt-Chat -> Benutzername bzw. Prinzipal des Gegenuebers fuer das Online-Abo, sonst None"""
    if not presence_on(me) or not isinstance(c, dict) or c.get("kind") != "dm" or c.get("request"):
        return None
    if _fed(c) and c.get("online_ok") is False:
        return None
    p = c.get("peer") if isinstance(c.get("peer"), dict) else {}
    u = p.get("username")
    return u if isinstance(u, str) and u else None


def ord_of(c, m):
    """Position einer Nachricht, die alle Teilnehmer gleich sehen: seq lokal, fseq (Reihenfolge des Heims) v2"""
    n = (m or {}).get("fseq") if _fed(c) else (m or {}).get("seq")
    return n if isinstance(n, int) and not isinstance(n, bool) and n > 0 else 0


def own(m):
    """eigene Nachricht (eine KI-Antwort auf meine Frage zaehlt nicht als meine)"""
    return bool((m or {}).get("mine")) and (m or {}).get("ai_state") != "ok"


def want(me, c, msgs, sent_n):
    """zu bestaetigende Nachricht: die neueste mit Position, die nicht von mir ist, falls n > zuletzt bestaetigt
    -> {"id", "n"} oder None"""
    if not rc_ok(me, c):
        return None
    for m in reversed(msgs or []):
        n = ord_of(c, m)
        if not n or own(m):
            continue
        return {"id": m.get("id"), "n": n} if n > (sent_n or 0) and m.get("id") else None
    return None


def others(c):
    return [x for x in (c or {}).get("members") or [] if isinstance(x, dict) and not x.get("me") and x.get("username")]


def readers(c, m, rc):
    """Wer hat Nachricht m gelesen? rc: {von: {n, ts}} -> [{"user", "name", "ts"}] (ohne mich)"""
    n = ord_of(c, m)
    if not n:
        return []
    out = []
    for x in others(c):
        r = (rc or {}).get(x["username"])
        if r and r.get("n", 0) >= n:
            out.append({"user": x["username"], "name": x.get("name") or x["username"], "ts": r.get("ts")})
    return out


def tick(me, c, m, rc):
    """Haken einer Nachricht -> None (keiner: fremd, Anruf, Systemzeile, Server ohne 2.4) | "pending" | "failed" |
    ("sent" | "read", gelesen_von, alle_gelesen)"""
    if not own(m) or (m or {}).get("state") in ("call", "sys"):
        return None
    if m.get("fstate") in ("pending", "failed"):
        return m["fstate"]
    if "receipts" not in features(me):
        return None
    rd = readers(c, m, rc) if rc_ok(me, c) else []
    oth = len(others(c))
    return ("read" if rd else "sent", len(rd), oth > 0 and len(rd) >= oth)


def tick_text(t, group):
    """sichtbares Zeichen und Beschreibung (Tooltip, Screenreader) eines Haken-Zustands"""
    state, n, everyone = t
    if state == "sent":
        return "✓", _("Delivered")
    if not group:
        return "✓✓", _("Read")
    if everyone:
        return "✓✓", _("Read by everyone")
    return "✓✓", ngettext("Read by {n} person", "Read by {n} people", n).format(n=n)


def presence_text(p, now=None):
    """{"online", "last"} -> "online" | "last online 5 minutes ago" | "" """
    if not isinstance(p, dict):
        return ""
    if p.get("online"):
        return _("online")
    last = p.get("last")
    if isinstance(last, (int, float)) and not isinstance(last, bool) and last > 0:
        now = time.time() if now is None else now
        return _("last online {when}").format(when=rel_time(min(float(last), now - 60), now))   # 5-Minuten-Raster: nie "jetzt"
    return ""


# --- (2.6, Bugs-DB 2029-8, userstatus1) Eigener Status: Verfuegbar / Beschaeftigt / Nicht stoeren -----------------------
# Vertrag: docs/APP-API.md im Server ("Eigener Status" bis "Anrufe und eigener Status"), Web static/userstatus.js
# (shown, peerText, digest). Ansicht des Servers: {state, until, text, source, ver, effective, why, quiet, in_call, prefs,
# exceptions, limits, now}. Status anderer (Presence-Abo, Live chat.status): {s, until?, text?}.
STATES = ("available", "busy", "dnd")
PEER_STATES = STATES + ("in_call",)
TEXT_MAX = 80
EXC_MAX = 100
REPEAT_S = (30, 3600)


def has_userstatus(me):
    """Server kann den eigenen Status (Feature in GET /api/chat/me) - ohne: nichts zeigen"""
    return "userstatus1" in features(me)


def state_name(s):
    return {"available": pgettext("user status", "Available"), "busy": pgettext("user status", "Busy"),
            "dnd": pgettext("user status", "Do not disturb"), "in_call": pgettext("user status", "On a call")}.get(s, "")


def _ts(x):
    return float(x) if isinstance(x, (int, float)) and not isinstance(x, bool) and x > 0 else None


def view_ok(v):
    return isinstance(v, dict) and isinstance(v.get("ver"), int) and not isinstance(v.get("ver"), bool) and v.get("state") in STATES


def take_view(old, new):
    """neue Ansicht uebernehmen? Nur gueltige und nicht aeltere (Antworten koennen sich ueberholen)"""
    if not view_ok(new):
        return False
    return not view_ok(old) or new["ver"] >= old["ver"]


def when_text(ts, now=None):
    """Ende als kurzer Text wie im Web: heute "14:30", morgen "morgen 14:30", diese Woche "Fr 14:30", sonst "24. Dez., 14:30" """
    import datetime as _dt
    from .util import fmt_date, time_text
    d = _dt.datetime.fromtimestamp(float(ts))
    n = _dt.datetime.fromtimestamp(time.time() if now is None else now)
    dd = (d.date() - n.date()).days
    t = time_text(d)
    if dd <= 0:
        return t
    if dd == 1:
        return _("tomorrow {time}").format(time=t)
    if dd < 7:
        return fmt_date(d, "%a") + " " + t
    # Translators: short date before a time (GLib.DateTime.format), e.g. "Oct 12"
    return fmt_date(d, _("%b %-d")) + ", " + t


def until_text(ts, now=None):
    ts = _ts(ts)
    return _("until {when}").format(when=when_text(ts, now)) if ts else ""


def shown(v, quiet_to=None, now=None):
    """Was gerade wirkt (ehrlich) -> {"state": effective, "hint": Zusatz} oder None. quiet_to: Ende der Ruhezeit "07:00"."""
    if not view_ok(v):
        return None
    eff = v.get("effective") if v.get("effective") in PEER_STATES else v["state"]
    hint = ""
    if eff == "dnd" and v.get("why") == "quiet":
        hint = _("Quiet hours until {time} – counts as Do not disturb").format(time=quiet_to) if quiet_to \
            else _("Quiet hours – counts as Do not disturb")
    elif eff == "in_call":
        hint = _("underneath: {state}").format(state=state_name(v["state"])) if v["state"] != "available" else ""
    elif _ts(v.get("until")) and v["state"] == eff:
        hint = until_text(v["until"], now)
    if v.get("source") == "focus" and v["state"] != "available" and v.get("why") != "quiet":
        hint = " · ".join(x for x in (hint, _("from the device (Focus)")) if x)
    return {"state": eff, "hint": hint}


def shown_text(v, quiet_to=None, now=None):
    s = shown(v, quiet_to, now)
    return (state_name(s["state"]) + (" · " + s["hint"] if s["hint"] else "")) if s else ""


def peer_status(st):
    """{s, until?, text?} eines Chat-Partners -> bereinigt oder None (Verfuegbar zeigt nichts, Unbekanntes auch nicht)"""
    if not isinstance(st, dict) or st.get("s") not in PEER_STATES or st.get("s") == "available":
        return None
    out = {"s": st["s"]}
    if _ts(st.get("until")) and st["s"] != "in_call":
        out["until"] = _ts(st["until"])
    t = st.get("text")
    if isinstance(t, str) and t.strip():
        out["text"] = " ".join(t.split())[:TEXT_MAX]
    return out


def peer_text(st, with_text=False, now=None):
    """"Nicht stören bis 14:30" (mit Text: "… · Im Kundentermin"); Verfuegbar/unbekannt -> "" """
    st = peer_status(st)
    if not st:
        return ""
    s = state_name(st["s"]) + (" " + until_text(st["until"], now) if st.get("until") else "")
    return s + " · " + st["text"] if with_text and st.get("text") else s


def tomorrow7(now=None):
    """Ende fuer "Bis morgen 7:00" (vor 5 Uhr: heute 7:00) -> (ts, Beschriftung)"""
    import datetime as _dt
    n = _dt.datetime.fromtimestamp(time.time() if now is None else now)
    early = n.hour < 5
    d = _dt.datetime(n.year, n.month, n.day, 7, 0) + _dt.timedelta(days=0 if early else 1)
    return d.timestamp(), (_("Until 7:00") if early else _("Until tomorrow 7:00"))


def status_body(state, dur, text, v, now=None):
    """PUT /api/account/status: state, Dauer ("h1" | "t7" | "keep" | "off"), Text -> Body (ver aus der Ansicht, Text <= 80)"""
    t = " ".join((text or "").split())[:TEXT_MAX] or None
    b = {"state": state, "text": t, "source": "manual"}
    if state != "available":
        if dur == "h1":
            b["dur"] = 3600
        elif dur == "t7":
            b["until"] = tomorrow7(now)[0]
        elif dur == "keep" and _ts((v or {}).get("until")):
            b["until"] = v["until"]
    if view_ok(v):
        b["ver"] = v["ver"]
    return b


def keep_until(v, now=None):
    """Dauer "Bis {when} (wie bisher)" anbieten? (gesetztes Ende, nicht Verfuegbar, mehr als eine Minute entfernt)"""
    u = _ts((v or {}).get("until"))
    now = time.time() if now is None else now
    return bool(u and (v or {}).get("state") != "available" and u > now + 60)


def digest_text(d):
    """Sammelmeldung status.digest {messages, calls, other} -> Satz (nur Zahlen, nie Namen) oder "" """
    if not isinstance(d, dict):
        return ""
    src = d.get("digest") if isinstance(d.get("digest"), dict) else d

    def n(k):
        x = src.get(k)
        return x if isinstance(x, int) and not isinstance(x, bool) and x > 0 else 0
    parts = []
    if n("messages"):
        parts.append(ngettext("{n} message", "{n} messages", n("messages")).format(n=n("messages")))
    if n("calls"):
        parts.append(ngettext("{n} missed call", "{n} missed calls", n("calls")).format(n=n("calls")))
    if n("other"):
        parts.append(ngettext("{n} other notification", "{n} other notifications", n("other")).format(n=n("other")))
    if not parts:
        return ""
    s = parts[0]
    for p in parts[1:]:
        # Translators: joins two items of a list, e.g. "4 messages, 1 missed call"
        s = _("{a}, {b}").format(a=s, b=p)
    return _("While “Do not disturb” was on: {items}").format(items=s)


def exc_same(a, b):
    if not isinstance(a, dict) or not isinstance(b, dict) or a.get("type") != b.get("type"):
        return False
    return a.get("name") == b.get("name") if a.get("type") == "group" else a.get("ref") == b.get("ref")


def exc_out(x):
    """Ausnahme fuer PUT .../prefs (ohne Anzeigename)"""
    return {"type": "group", "name": x.get("name")} if x.get("type") == "group" else {"type": "user", "ref": x.get("ref")}
