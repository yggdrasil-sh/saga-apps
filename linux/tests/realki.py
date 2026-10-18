"""Pruefung gegen einen echten Saga-2.1-Server (kein pytest): KI-Persona und "KI im Chat - nur auf Zuruf"
(docs/APP-API-KI.md im Saga-Repo). Voraussetzung: Assistent und "KI in Chats erlauben" sind auf dem Server an (Testserver:
Fake-Modell). Zwei Testkonten (app-linux, app-linux2):
  - /api/ai/status (persona, chat) und /api/ai/persona, Profilbild /api/ai/avatar (ETag -> 304, Zwischenspeicher der App)
  - Direkt-Chat A <-> B, KI erlauben (PUT /api/chats/{cid}/ai, Feld "ai" in der Chat-Liste)
  - A fragt mit "@Name": Frage + Verlauf (12) -> Strom -> Antwort verschluesselt mit ai_reply; B sieht sie als bestaetigte
    KI-Antwort (Beleg + SHA-256), Beleg nur einmal verwendbar (409), Faelschung ohne Beleg -> Warnung
  - Fehler: ohne @Name 400, zu langer Verlauf 400, KI im Chat aus -> 403
    HK_REAL_BASE=http://host:port HK_REAL_USERS=/pfad python3 tests/realki.py [rk-datei]
Die Datei enthaelt Zeilen "benutzer=passwort". Passwoerter werden nie ausgegeben. Optional wird der
Wiederherstellungsschluessel von A in rk-datei geschrieben (fuer Bildschirmfotos mit derselben Identitaet)."""
import os
import sys
import time
import urllib.request

os.environ.setdefault("HK_NO_KEYRING", "1")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gi.repository import GLib  # noqa: E402

import realv2  # noqa: E402
from hlidskjalf import aichat, avatars, config  # noqa: E402
from hlidskjalf.api import APIError, client  # noqa: E402
from hlidskjalf.assistant import ai, ai_name  # noqa: E402

BASE = realv2.BASE
A, B = realv2.A, realv2.B
RES = realv2.RES
check, use, state, ready, login = realv2.check, realv2.use, realv2.state, realv2.ready, realv2.login


def pump(cond, timeout=10.0):
    ctx = GLib.MainContext.default()
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        while ctx.pending():
            ctx.iteration(False)
        if cond():
            return True
        time.sleep(0.02)
    return bool(cond())


def msgs(st, cid):
    j = client.get(f"api/chats/{cid}/messages?device={st.device_id}&limit=50")
    return [st._decode(cid, m, j.get("devices"), j.get("idents")) for m in j["messages"]]


def raw_ai(cid, body):
    """POST /api/chats/{cid}/ai direkt -> Status (Strom wird nicht gelesen)"""
    try:
        r = client.stream("POST", f"api/chats/{cid}/ai", body, timeout=20)
        r.close()
        return 200
    except APIError as e:
        return e.code


def main():
    config.settings.server = BASE
    ja, jb = login(A), login(B)
    # --- Persona -----------------------------------------------------------------------------------------------
    use(ja)
    ai.refresh()
    pump(lambda: ai.known)
    p = ai.persona()
    check(ai.enabled and p and p.get("name"), f"/api/ai/status: Persona ({ai_name()}), Assistent an")
    check(ai.chat_allowed, "/api/ai/status: chat = true (KI in Chats erlaubt)")
    pj = client.get("api/ai/persona")
    check(pj.get("name") == p.get("name") and pj.get("enabled") is True and "custom_name" in pj, "/api/ai/persona")
    if p.get("avatar"):
        req = urllib.request.Request(BASE + p["avatar"] + "&s=64", headers={"X-Saga": "1", "Cookie": client.cookie_header_for(BASE)})
        with urllib.request.urlopen(req, timeout=15) as r:
            et, n = r.headers.get("ETag"), len(r.read())
        check(n > 100 and et, f"/api/ai/avatar: Bild ({n} Bytes, ETag)")
        req.add_header("If-None-Match", et)
        try:
            urllib.request.urlopen(req, timeout=15)
            code = 200
        except urllib.error.HTTPError as e:
            code = e.code
        check(code == 304, "/api/ai/avatar: If-None-Match -> 304")
        data = avatars.fetch(config.origin(BASE), p["avatar"], 64, p.get("avatar_etag"))
        f = avatars._disk(config.origin(BASE), p["avatar"], 64, p.get("avatar_etag"))
        check(data and os.path.exists(f), "App: Bild geladen und nach avatar_etag zwischengespeichert")
    else:
        check(True, "/api/ai/avatar: kein Bild gesetzt (Symbol)")
    name = ai_name()
    # --- Chat einrichten -------------------------------------------------------------------------------------
    sa, sb = state(ja), state(jb)
    rka = ready(sa, ja, A)
    ready(sb, jb, B)
    if len(sys.argv) > 1:
        with open(sys.argv[1], "w", encoding="utf-8") as fh:
            fh.write(rka)
    use(ja)
    ch = client.json("POST", "api/chats", {"kind": "dm", "user": B})["chat"]
    cid = ch["id"]
    check("ai" in ch, f"Chat {cid}: Feld ai vorhanden")
    r = client.json("PUT", f"api/chats/{cid}/ai", {"allowed": False})
    check(r.get("ai") is False, "PUT /api/chats/{cid}/ai false")
    check(raw_ai(cid, {"q": f"@{name} hallo", "history": []}) == 403, "Frage bei ausgeschalteter KI im Chat -> 403")
    r = client.json("PUT", f"api/chats/{cid}/ai", {"allowed": True})
    check(r.get("ai") is True, "PUT /api/chats/{cid}/ai true (Direkt-Chat: beide duerfen)")
    use(jb)
    check(client.get(f"api/chats/{cid}")["chat"].get("ai") is True, "B sieht ai = true")
    # Verlauf: 14 Nachrichten (B und A abwechselnd)
    for i in range(14):
        st_, jar = (sb, jb) if i % 2 == 0 else (sa, ja)
        use(jar)
        st_.send(cid, {"v": 1, "type": "text", "text": f"Verlauf {i}: Treffen am Bahnhof um {8 + i % 4} Uhr"})
    # --- Frage von A ---------------------------------------------------------------------------------------------
    use(ja)
    sa.chats = [client.get(f"api/chats/{cid}")["chat"]]
    sa.cache[cid] = {"msgs": msgs(sa, cid), "more": False, "last_seq": 0, "loaded": True}
    check(len(sa.cache[cid]["msgs"]) >= 14, "A hat den Verlauf geladen")
    check(raw_ai(cid, {"q": "ohne Erwaehnung", "history": []}) == 400, "ohne @Name -> 400")
    check(raw_ai(cid, {"q": f"@{name} hallo", "history": [{"name": "x", "text": "y"}] * 13}) == 400, "13 Verlaufsnachrichten -> 400")
    text = f"@{name} wann treffen wir uns am Bahnhof?"
    resp, _m = sa.send(cid, {"v": 1, "type": "text", "text": text})
    qid = resp["id"]
    hist = aichat.history(sa.cache[cid]["msgs"], qid, name)
    check(len(hist) == 12 and hist[-1]["text"].startswith("Verlauf 13"), "Verlauf: 12 Nachrichten, neueste zuletzt")
    errs = []
    sa.load_messages = lambda *a, **k: (k.get("done") or (lambda: None))()      # ohne Hauptfenster: nicht neu laden
    sa._list_soon = lambda: None
    started = sa.ask_ai(cid, text, qid, on_error=errs.append)
    check(started, "Frage an die KI gestartet")
    got_delta = {"x": False}
    h = sa.connect("ai-pending", lambda o, c: (sa.ai_pending.get(cid) or {}).get("text") and got_delta.update(x=True))
    pump(lambda: cid not in sa.ai_pending, 40)
    sa.disconnect(h)
    check(not errs, "Antwort gestreamt und gesendet" + (f" (Fehler: {errs[0]})" if errs else ""))
    check(got_delta["x"], "Antwort lief sichtbar mit (delta)")
    use(jb)
    mb = msgs(sb, cid)
    last = mb[-1]
    check(last.get("ai_state") == "ok" and last["body"]["ai"]["name"] == name and last.get("reply_to") == qid,
          f"B sieht bestaetigte KI-Antwort von {name} (Beleg + SHA-256): {last['text'][:60]!r}")
    check(last.get("ai_by") == A, "Feld ai.by = Fragende Person")
    ref = last["body"]["ai"]["reply"]
    # Beleg nur einmal - und nur fuer die Fragende Person
    try:
        sb.send(cid, {"v": 1, "type": "text", "text": last["text"], "ai": {"reply": ref, "name": name}}, extra={"ai_reply": ref})
        check(False, "Beleg erneut verwenden -> 409")
    except Exception as e:  # noqa: BLE001
        check(getattr(e, "code", None) == 409, "Beleg erneut verwenden (anderes Konto) -> 409")
    # Faelschung: gibt sich als KI-Antwort aus, ohne Beleg
    sb.send(cid, {"v": 1, "type": "text", "text": "Ich bin wirklich die KI.", "ai": {"reply": ref, "name": name}})
    use(ja)
    ma = msgs(sa, cid)
    check(ma[-1].get("ai_state") == "bad", "Faelschung ohne Beleg: als nicht bestaetigt erkannt")
    check(ma[-2].get("ai_state") == "ok" and ma[-2]["mine"], "A sieht die eigene KI-Antwort bestaetigt")
    print("CHAT", cid)
    ok = all(RES)
    print(("ALLES OK" if ok else "FEHLER") + f" ({sum(RES)}/{len(RES)})", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
