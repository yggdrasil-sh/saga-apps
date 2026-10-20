"""Gelesen-Bestaetigungen und Online-Status in Chats (Server 2.4) - Regeln ohne Oberflaeche und ohne Netz.

"Wie WhatsApp": standardmaessig an, je Konto getrennt abschaltbar (GET/PUT /api/chat/privacy), gegenseitig - wer etwas
abschaltet, sendet es nicht und sieht es bei anderen auch nicht. Server ohne "features" in GET /api/chat/me (aelter als 2.4):
alles bleibt ausgeblendet. Vertrag: docs/APP-API.md im Server ("Gelesen-Bestaetigungen und Online-Status"), Web
static/chat.js (receiptWant, readersOf, tickEl, presenceChip).

Haken an eigenen Nachrichten: ⏳ wird zugestellt (fstate "pending") -> ✓ zugestellt -> ✓✓ gelesen (Position n der
Bestaetigung >= Position der Nachricht; Position = seq lokal bzw. fseq ueber Servergrenzen). Gruppen: ✓✓ mit Zaehler, bis
alle anderen bestaetigt haben."""
import time

from .i18n import _, ngettext
from .util import rel_time

DEFAULT_GAP = 2.0          # hoechstens eine Bestaetigung je Chat und so viele Sekunden (status_limits.receipt_gap)
PRESENCE_RENEW_S = 60      # Abo erneuern, solange sichtbar (ttl 90 s beim Server)
PRESENCE_MAX = 50          # Personen je Abfrage
PRESENCE_LIST = 30         # Direkt-Chats der Liste, die abonniert werden


def _fed(c):
    return isinstance(c, dict) and c.get("v") == 2


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
    if not online_on(me) or not isinstance(c, dict) or c.get("kind") != "dm" or c.get("request"):
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
