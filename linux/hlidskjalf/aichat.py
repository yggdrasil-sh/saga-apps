"""KI im Chat (Server ab 2.1) - "nur auf Zuruf", reine Logik ohne Oberflaeche (Vertrag: docs/APP-API-KI.md im Saga-Repo).

Chats bleiben Ende-zu-Ende-verschluesselt. Die KI liest nur Nachrichten, in denen sie mit @Name erwaehnt wird: die App
des Fragenden schickt diese Nachricht und hoechstens CTX_MAX Nachrichten davor im Klartext an POST /api/chats/{cid}/ai,
bekommt die Antwort als Strom und verschluesselt sie wie jede Nachricht fuer alle Mitglieder (mit ai_reply = Beleg).
Andere zeigen eine Nachricht nur dann als KI-Antwort, wenn Beleg-Kennung und SHA-256 des Textes zum Beleg passen."""
import hashlib
import re

CTX_MAX = 12            # Verlaufsnachrichten je Frage (mehr -> 400)
LINE_MAX = 600          # Zeichen je Verlaufsnachricht
Q_MAX = 1000            # Zeichen der Frage
_PREFIX_RX = re.compile(r"(?:^|(?<=[\s(\[\"']))@([^\s@]{0,30})$")


def mention_rx(name):
    """@Name: Gross-/Kleinschreibung egal, davor Anfang, Leerraum oder ( [ " ', danach kein Buchstabe/keine Ziffer"""
    return re.compile(r"(?:^|(?<=[\s(\[\"']))@" + re.escape(name) + r"(?!\w)", re.I)


def mentions(text, name):
    """Erwaehnt der Text die KI mit @Name?"""
    if not text or not name:
        return False
    return mention_rx(name).search(text) is not None


def typed_prefix(before_cursor):
    """Text bis zur Schreibmarke -> (Anfang des "@...", eingegebener Teil) oder None (kein @-Wort an der Schreibmarke)"""
    m = _PREFIX_RX.search(before_cursor or "")
    if m is None:
        return None
    return m.start(1) - 1, m.group(1)


def suggest(prefix, name):
    """Passt der Name zum eingegebenen Teil nach @ (Anfang, Gross-/Kleinschreibung egal)?"""
    return bool(name) and prefix is not None and name.lower().startswith(prefix.lower()) and prefix.lower() != name.lower()


def reply_sha(text):
    """Beleg wie beim Server: SHA-256 (hex) ueber die UTF-8-Bytes des Textes ohne Leerraum am Anfang/Ende"""
    return hashlib.sha256(str(text).strip().encode("utf-8")).hexdigest()


def check_reply(body, meta):
    """Entschluesselter Inhalt + Feld "ai" der Nachrichtenliste -> None (keine KI-Antwort), "ok" (bestaetigt) oder "bad"
    (gibt sich als KI-Antwort aus, Beleg fehlt oder passt nicht)"""
    if not isinstance(body, dict) or not isinstance(body.get("ai"), dict):
        return None
    if isinstance(meta, str):                         # v2 (2.3): Hash des Antworttexts, mitsigniert (kein Server-Beleg)
        return "ok" if isinstance(body.get("text"), str) and reply_sha(body["text"]) == meta.strip().lower() else "bad"
    if not isinstance(meta, dict) or not isinstance(body.get("text"), str):
        return "bad"
    ref = meta.get("ref")
    if not isinstance(ref, str) or not ref or body["ai"].get("reply") != ref:
        return "bad"
    want = meta.get("sha256")
    if not isinstance(want, str) or reply_sha(body["text"]) != want.strip().lower():
        return "bad"
    return "ok"


def history(msgs, qid, ai_name, limit=CTX_MAX):
    """Verlauf fuer die Frage: hoechstens limit Text-Nachrichten VOR der Frage (aelteste zuerst), je LINE_MAX Zeichen;
    bestaetigte KI-Antworten mit dem Namen der KI. Ohne Anhaenge, Anruf-Eintraege und Nicht-Entschluesselbares."""
    lst = list(msgs or [])
    ids = [m.get("id") for m in lst]
    if qid in ids:
        lst = lst[:ids.index(qid)]
    out = []
    for m in reversed(lst):
        if len(out) >= min(limit, CTX_MAX):
            break
        if m.get("state") not in ("ok", "unverified") or m.get("id") == qid:
            continue
        text = str(m.get("text") or "").strip()
        if not text:
            continue
        if m.get("ai_state") == "ok":
            name = ai_name
        else:
            name = (m.get("sender") or {}).get("name") or (m.get("sender") or {}).get("username") or "?"
        out.append({"name": str(name)[:80], "text": text[:LINE_MAX]})
    out.reverse()
    return out


def question(text):
    """Frage fuer den Server (hoechstens Q_MAX Zeichen)"""
    return str(text or "").strip()[:Q_MAX]


def reply_body(text, reply_id, name):
    """Inhalt der Antwort-Nachricht (wird wie jede Nachricht verschluesselt)"""
    return {"v": 1, "type": "text", "text": text, "ai": {"reply": reply_id, "name": name}}


def may_toggle(c):
    """Eintrag "KI erlauben" anbieten? Der Server prueft selbst (sonst 403): freie Gruppe - Chat-Admins; Direkt-Chat -
    beide; Gruppen-Chat - Saga-Admins; Seiten-Chat - Eigentuemer der Seite. Hier nur, was die App sicher weiss."""
    k = (c or {}).get("kind")
    if k == "free":
        return (c or {}).get("role") == "admin"
    return k in ("dm", "group", "page")
