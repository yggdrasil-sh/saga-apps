"""Pruefung gegen einen echten Saga-2.0-Server (kein pytest): zwei Testkonten, Chat-Krypto der Linux-App gegen den Server
(Einrichtung bzw. Neu beginnen mit Passwort, Direkt-Chat, Senden/Empfangen beider Seiten, Sicherheitsnummer auf beiden
Seiten gleich, Wiederherstellung auf einem "neuen Geraet", Kopplungs-Code), Live-Kanal (chat.message kommt an),
Benachrichtigungen, Mitteilungen (Einstellungen), KI-Status, Foederation ("Mit mir geteilt").
    HK_REAL_BASE=http://host:port HK_REAL_USERS=/pfad/zur/datei python3 tests/realv2.py
Die Datei enthaelt Zeilen "benutzer=passwort"; benutzt werden app-linux und app-linux2. Passwoerter werden nie ausgegeben."""
import http.cookies
import os
import re
import sys
import threading
import time
import urllib.parse
import urllib.request

os.environ.setdefault("HK_NO_KEYRING", "1")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from hlidskjalf import chatcrypto as C  # noqa: E402
from hlidskjalf import config  # noqa: E402
from hlidskjalf.api import APIError, client, sse_events  # noqa: E402
from hlidskjalf.chat import ChatState, _mem, trust_get  # noqa: E402

BASE = os.environ["HK_REAL_BASE"].rstrip("/")
PW = dict(x.strip().split("=", 1) for x in open(os.environ["HK_REAL_USERS"], encoding="utf-8") if "=" in x)
A, B = "app-linux", "app-linux2"
RES = []


def check(ok, what):
    RES.append(bool(ok))
    print(("OK     " if ok else "FEHLER ") + what, flush=True)


class _NoRedir(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def login(user):
    """Web-Anmeldung mit Passwort -> Sitzungs-Cookies (wie ein frisch angemeldetes Geraet)"""
    op = urllib.request.build_opener(_NoRedir)
    jar = {}

    def take(h):
        for raw in h.get_all("Set-Cookie") or []:
            c = http.cookies.SimpleCookie()
            c.load(raw)
            for k, m in c.items():
                jar[k] = m.value

    def hdr():
        return {"Cookie": "; ".join(f"{k}={v}" for k, v in jar.items()), "User-Agent": "realv2"}
    r = op.open(urllib.request.Request(BASE + "/login", headers=hdr()), timeout=15)
    take(r.headers)
    csrf = re.search(r'name="csrf" value="([^"]+)"', r.read().decode()).group(1)
    data = urllib.parse.urlencode({"username": user, "password": PW[user], "csrf": csrf}).encode()
    try:
        r = op.open(urllib.request.Request(BASE + "/login", data=data, headers=hdr()), timeout=15)
        take(r.headers)
    except urllib.error.HTTPError as e:
        take(e.headers)
        if e.code not in (302, 303):
            raise
    return jar


def use(jar):
    client.set_cookies(BASE, jar)


def state(jar):
    use(jar)
    st = ChatState()
    st.me = client.get("api/chat/me")
    st.limits = st.me.get("limits") or {}
    return st


def ready(st, jar, user):
    """Chats einrichten (oder neu beginnen: das Passwort steht im Feld password) -> Wiederherstellungsschluessel"""
    use(jar)
    rk = C.recovery_key()
    k = st.setup(rk, PW[user], reset=bool(st.me.get("identity")))
    st.keys, st.status = k, "ready"
    st.me = client.get("api/chat/me?device=" + k["device"]["id"])
    return rk


def main():
    config.settings.server = BASE
    ja, jb = login(A), login(B)
    use(ja)
    check(client.get("api/account").get("username") == A, "Anmeldung " + A)
    use(jb)
    check(client.get("api/account").get("username") == B, "Anmeldung " + B)
    sa, sb = state(ja), state(jb)
    check(sa.me.get("enabled") is True and sa.me.get("uid") == A, "GET /api/chat/me (Chats eingeschaltet)")
    rka = ready(sa, ja, A)
    ready(sb, jb, B)
    check(sa.me["identity"]["ident_pub"] == C.b64(sa.keys["ident_pk"]), "Identitaet A beim Server = lokal")
    check(any(d["id"] == sa.device_id and d["current"] for d in sa.me["devices"]), "Geraet A angemeldet (current)")
    # Live-Kanal von B mitlesen
    events = []
    use(jb)
    resp = client.stream("GET", "api/events", timeout=40)
    stop = {"x": False}

    def reader():
        try:
            for ev, data, _eid in sse_events(resp, stop=lambda: stop["x"]):
                events.append((ev, data))
        except Exception:  # noqa: BLE001 - Verbindung am Ende geschlossen
            pass
    threading.Thread(target=reader, daemon=True).start()
    time.sleep(1.0)
    check(events and events[0][0] == "hello", "Live-Kanal: hello")
    # Direkt-Chat A -> B
    use(ja)
    ch = client.json("POST", "api/chats", {"kind": "dm", "user": B})["chat"]
    cid = ch["id"]
    check(ch["kind"] == "dm" and ch["peer"]["username"] == B, f"Direkt-Chat angelegt ({cid})")
    resp1, missing = sa.send(cid, {"v": 1, "type": "text", "text": "Hallo von der Linux-App 👋 [[note:1]]", "notes": [1]})
    check(resp1.get("ok") and not missing, "A sendet (Signatur + Empfaenger vom Server akzeptiert)")
    t0 = time.time()
    while time.time() - t0 < 6 and not any(e[0] == "chat.message" and e[1].get("chat_id") == cid for e in events):
        time.sleep(0.2)
    check(any(e[0] == "chat.message" and e[1].get("chat_id") == cid for e in events), "Live: chat.message bei B angekommen")
    use(jb)
    j = client.get(f"api/chats/{cid}/messages?device={sb.device_id}&limit=50")
    msgs = [sb._decode(cid, m, j.get("devices"), j.get("idents")) for m in j["messages"]]
    check(msgs and msgs[-1]["text"].startswith("Hallo von der Linux-App") and msgs[-1]["state"] == "ok",
          "B entschluesselt und prueft die Nachricht (Echtheit bestaetigt)")
    resp2, _m = sb.send(cid, {"v": 1, "type": "text", "text": "Antwort von B"}, reply_to=msgs[-1]["id"])
    check(resp2.get("ok"), "B antwortet (mit Zitat)")
    use(ja)
    j = client.get(f"api/chats/{cid}/messages?device={sa.device_id}&limit=50")
    ma = [sa._decode(cid, m, j.get("devices"), j.get("idents")) for m in j["messages"]]
    check(ma[-1]["text"] == "Antwort von B" and ma[-1]["state"] == "ok" and ma[-1]["reply_to"] == msgs[-1]["id"], "A liest die Antwort")
    # Sicherheitsnummer beidseitig gleich
    ra = {r["username"]: r for r in sa.recipients(cid, True)}
    use(jb)
    rb = {r["username"]: r for r in sb.recipients(cid, True)}
    na = C.safety_number(A, sa.keys["ident_pk"], B, C.unb64(ra[B]["ident_pub"]))
    nb = C.safety_number(B, sb.keys["ident_pk"], A, C.unb64(rb[A]["ident_pub"]))
    check(na == nb and len(na.replace(" ", "")) == 60, "Sicherheitsnummer auf beiden Seiten gleich")
    check(trust_get(B, A).get("ident_pub") == C.b64(sa.keys["ident_pk"]), "TOFU: B hat A's Identitaet gemerkt")
    # Anhang
    use(ja)
    f = sa.upload_file(cid, b"Anhang-Test \xc3\xa4\xc3\xb6\xc3\xbc", "test.txt", "text/plain")
    r3, _m = sa.send(cid, {"v": 1, "type": "text", "text": "", "files": [f]}, file_ids=[f["id"]])
    use(jb)
    check(r3.get("ok") and sb.fetch_file(cid, f) == "Anhang-Test äöü".encode(), "Anhang verschluesselt hochgeladen, B entschluesselt")
    # neues Geraet A: Wiederherstellung mit dem Schluessel, alte Nachricht ueber den Backup-Schluessel
    use(ja)
    _mem.clear()
    sa2 = state(ja)
    k2 = sa2.unlock(rka)
    sa2.keys, sa2.status = k2, "ready"
    j = client.get(f"api/chats/{cid}/messages?device={k2['device']['id']}&limit=50")
    m2 = [sa2._decode(cid, m, j.get("devices"), j.get("idents")) for m in j["messages"]]
    check(m2 and m2[0]["text"].startswith("Hallo von der Linux-App") and m2[0]["state"] == "ok",
          "Wiederherstellung: neues Geraet liest alte Nachrichten (Backup-Schluessel)")
    # Kopplung: neues Geraet bittet A2 um die Schluessel
    sa3 = ChatState()
    sa3.me = client.get("api/chat/me")
    lw = sa3.link_start()
    links = client.get("api/chat/link")["links"]
    ln = next(x for x in links if x["id"] == lw["id"])
    check(sa2.link_code_for(ln) == lw["code"], "Kopplungs-Code auf beiden Geraeten gleich (" + lw["code"] + ")")
    sa2.link_approve(ln)
    st, k3 = sa3.link_poll(lw)
    check(st == "approved" and k3["ident_pk"] == sa2.keys["ident_pk"], "Kopplung bestaetigt, Schluessel uebertragen")
    # neuer Wiederherstellungsschluessel
    rk2 = C.recovery_key()
    sa2.new_recovery(rk2)
    b = client.get("api/chat/me")["identity"]["bundle"]
    check(C.open_bundle(A, rk2, b)["ident_pk"] == sa2.keys["ident_pk"], "Neuer Wiederherstellungsschluessel gilt")
    # Geraete aufraeumen: das zuerst entfernte Geraet (A1) und das gekoppelte
    for d in (sa.device_id, k3["device"]["id"]):
        client.request("DELETE", f"api/chat/devices/{d}")
    check(not any(d["id"] == sa.device_id and not d["revoked"] for d in client.get("api/chat/me")["devices"]), "Geraet entfernt")
    # Benachrichtigungen, Mitteilungen, KI, Foederation (nur lesend bzw. eigene Einstellungen)
    use(jb)
    n = client.get("api/notifications?limit=30")
    check(isinstance(n.get("items"), list) and any(x.get("kind") == "chat.message" for x in n["items"]), "Benachrichtigung chat.message bei B")
    client.json("POST", f"api/chats/{cid}/read", {"seq": max(m["seq"] or 0 for m in msgs)})
    p = client.get("api/push")
    sub = None
    check("server" in p and "settings" in p and isinstance(p.get("devices"), list), "GET /api/push (Mitteilungen)")
    if p["server"].get("enabled") and p["server"].get("unifiedpush"):
        # wie der UnifiedPush-Connector: Adresse vom Verteiler anmelden (ohne Web-Push-Schluessel), danach wieder weg
        try:
            sub = client.json("POST", "api/push/subscriptions", {"type": "unifiedpush", "name": "realv2 (Linux)",
                                                                 "endpoint": "https://push.example.org/upSagaLinuxTest1"})
        except APIError as e:
            # der Server prueft, ob die Adresse erreichbar und erlaubt ist - eine erfundene ist es nicht
            check(e.code in (400, 422), f"UnifiedPush-Abo: Server lehnt unerreichbare Adresse ab ({e.code})")
            sub = None
    if sub is not None:
        devs = client.get("api/push")["devices"]
        mine = next((x for x in devs if x.get("id") == sub.get("id")), {})
        check(sub.get("id") and mine.get("encrypted") is False, "UnifiedPush-Abo angemeldet (ohne Schluessel: nie Namen)")
        client.request("DELETE", f"api/push/subscriptions/{sub['id']}")
        check(not any(x.get("id") == sub["id"] for x in client.get("api/push")["devices"]), "UnifiedPush-Abo wieder entfernt")
    a = client.get("api/ai/status")
    check("enabled" in a, f"GET /api/ai/status (enabled={a.get('enabled')})")
    fs = client.get("api/fed/shared")
    check(isinstance(fs.get("items"), list) and isinstance(fs.get("unreachable"), list), "GET /api/fed/shared")
    try:
        client.get("api/notes/-1025")
        check(False, "fremde Seite ohne Verbindung muss 404 liefern")
    except APIError as e:
        check(e.code == 404, "negative ID ohne Freigabe: 404")
    stop["x"] = True
    try:
        resp.close()
    except OSError:
        pass
    print(f"ECHTSERVER {sum(RES)}/{len(RES)}", "- ALLES GRUEN" if all(RES) else "- FEHLER", flush=True)
    return 0 if all(RES) else 1


if __name__ == "__main__":
    sys.exit(main())
