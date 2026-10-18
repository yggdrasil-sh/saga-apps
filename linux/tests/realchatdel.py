"""Pruefung gegen einen echten Saga-2.1-Server (kein pytest): Chats archivieren und loeschen (docs/APP-API-CHAT.md) mit
dem Zustand der Linux-App (chat.ChatState) und zwei WEGWERF-Konten (vorher anlegen, danach loeschen; B ist Saga-Admin,
beide in derselben Saga-Gruppe):
  - neue Felder (archived, archived_at, cleared_seq, delete_for_all), Archivieren per PATCH, Live chat.changed,
    Ungelesen ohne Archiv, neue Nachricht holt aus dem Archiv
  - "Nur fuer mich" (Direkt-Chat): Server-Antwort, lokaler Zwischenspeicher (Nachrichten, Anhang auf der Platte) leer,
    Liste ohne den Chat, Live chat.cleared {scope: me}, Anhang -> 404, B behaelt alles, neue Nachricht -> wieder da
  - freie Gruppe: B ohne Recht -> 403, "Gruppe ausserdem verlassen", A loescht fuer alle -> B bekommt chat.removed mit
    Namen; Gruppen-Chat: B (Saga-Admin) loescht den Verlauf fuer alle -> A bekommt chat.cleared {scope: all}
    HK_REAL_BASE=http://host:port HK_REAL_USERS=/pfad HK_REAL_GROUP=gruppe python3 tests/realchatdel.py
Die Datei enthaelt Zeilen "benutzer=passwort" fuer genau zwei Konten (A zuerst). Passwoerter werden nie ausgegeben."""
import os
import sys
import threading
import time

os.environ.setdefault("HK_NO_KEYRING", "1")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gi.repository import GLib  # noqa: E402

from hlidskjalf import chatmedia, config  # noqa: E402
from hlidskjalf import chatcrypto as C  # noqa: E402
from hlidskjalf.api import APIError, client, sse_events  # noqa: E402
from hlidskjalf.chat import ChatState  # noqa: E402

BASE = os.environ["HK_REAL_BASE"].rstrip("/")
_users = [x.strip().split("=", 1) for x in open(os.environ["HK_REAL_USERS"], encoding="utf-8") if "=" in x]
(A, PWA), (B, PWB) = _users[0], _users[1]
GROUP = os.environ.get("HK_REAL_GROUP", "")
RES = []


def check(ok, what):
    RES.append(bool(ok))
    print(("OK     " if ok else "FEHLER ") + what, flush=True)


def pump(cond=None, timeout=6.0):
    ctx = GLib.MainContext.default()
    end = time.monotonic() + timeout
    while True:
        while ctx.pending():
            ctx.iteration(False)
        if cond is not None and cond():
            return True
        if time.monotonic() > end:
            return bool(cond()) if cond is not None else True
        time.sleep(0.02)


def login(user, pw):
    import realv2
    realv2.BASE, realv2.PW = BASE, {user: pw}
    return realv2.login(user)


class Person:
    """ein Konto mit eigenem Chat-Zustand, eigenen Cookies und eigenem Live-Kanal"""

    def __init__(self, user, pw):
        self.user = user
        self.jar = login(user, pw)
        self.use()
        self.st = ChatState()
        self.st.me = client.get("api/chat/me")
        rk = C.recovery_key()
        k = self.st.setup(rk, pw, reset=bool(self.st.me.get("identity")))
        self.st.keys, self.st.status = k, "ready"
        self.st.me = client.get("api/chat/me?device=" + k["device"]["id"])
        self.events, self.gone = [], []
        self.st.connect("chat-gone", lambda o, cid, how, name: self.gone.append((cid, how, name)))
        self.stop = False
        resp = client.stream("GET", "api/events", timeout=60)

        def reader():
            try:
                for ev, data, _eid in sse_events(resp, stop=lambda: self.stop):
                    self.events.append((ev, data))
            except Exception:  # noqa: BLE001 - am Ende geschlossen
                pass
        threading.Thread(target=reader, daemon=True).start()

    def use(self):
        client.set_cookies(BASE, self.jar)

    def run(self, fn, *a, timeout=8, **kw):
        """ChatState-Aufruf mit done/error (laeuft im Hintergrund) -> Ergebnis"""
        self.use()
        box = {}
        fn(*a, done=lambda r=None: box.setdefault("r", r), error=lambda e: box.setdefault("e", e), **kw)
        pump(lambda: box, timeout)
        pump(None, 1.0)                                  # Folgeanfragen (Liste neu) noch mit diesen Cookies
        if "e" in box:
            return box["e"]
        return box.get("r")

    def load(self):
        self.use()
        box = {}
        self.st.load_chats(done=lambda: box.setdefault("ok", 1))
        pump(lambda: box, 8)
        pump(None, 0.5)

    def wait_event(self, typ, cid, timeout=6, key=None):
        """Live-Ereignis abwarten (key: nur mit diesem Feld) und wie die App verarbeiten -> Daten"""
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for i, (ev, d) in enumerate(self.events):
                if ev == typ and isinstance(d, dict) and d.get("chat_id") == cid and (key is None or key in d):
                    self.events.pop(i)
                    self.use()
                    self.st.on_live(ev, d)
                    pump(None, 1.5)
                    return d
            time.sleep(0.1)
        return None

    def send(self, cid, text, files=None):
        self.use()
        body = {"v": 1, "type": "text", "text": text}
        if files:
            body["files"] = files
        r, _m = self.st.send(cid, body, file_ids=[f["id"] for f in files or []] or None)
        return r


def main():
    config.settings.server = BASE
    a, b = Person(A, PWA), Person(B, PWB)
    time.sleep(1)
    check(a.events and a.events[0][0] == "hello" and b.events and b.events[0][0] == "hello", "Live-Kanal beider Konten")
    # --- Direkt-Chat, Felder, Archiv -------------------------------------------------------------------------------------
    b.use()
    ch = client.json("POST", "api/chats", {"kind": "dm", "user": A, "device": b.st.device_id})["chat"]
    if ch.get("last_seq"):                                   # von einem frueheren Lauf: frisch beginnen (neue Kennung)
        client.json("DELETE", f"api/chats/{ch['id']}?scope=all")
        ch = client.json("POST", "api/chats", {"kind": "dm", "user": A, "device": b.st.device_id})["chat"]
    cid = ch["id"]
    b.send(cid, "Erste Nachricht von B")
    f = b.st.upload_file(cid, b"Anhang fuer den Loeschtest", "notiz.txt", "text/plain")
    b.send(cid, "mit Anhang", files=[f])
    a.load()
    c = a.st.chat(cid)
    check(c is not None and c.get("archived") is False and c.get("cleared_seq") == 0 and c.get("delete_for_all") == "chat"
          and "archived_at" in c, "Neue Felder: archived=false, cleared_seq=0, delete_for_all=chat")
    check(a.st.can_archive(c) and a.st.can_delete(c), "App erkennt den neuen Server (Archiv + Loeschen sichtbar)")
    check(a.st.unread == 2 and c.get("unread") == 2, "Ungelesen 2")
    r = a.run(a.st.set_archived, cid, True)
    check(isinstance(r, dict) and r.get("archived") is True and a.st.chat(cid).get("archived"), "Archivieren (PATCH archived=true)")
    a.load()
    check(a.st.unread == 0 and a.st.chat(cid).get("unread") == 2, "Ungelesen-Summe ohne Archiv, Zaehler je Chat bleibt")
    a.use()
    check(client.get("api/chats/unread").get("unread") == 0, "GET /api/chats/unread ohne Archiv")
    d = a.wait_event("chat.changed", cid, key="archived")
    check(d is not None and d.get("archived") is True, "Live: chat.changed {archived: true}")
    b.send(cid, "Neue Nachricht holt aus dem Archiv")
    a.load()
    check(a.st.chat(cid).get("archived") is False and a.st.unread == 3, "Neue Nachricht holt den Chat aus dem Archiv")
    a.use()
    try:
        client.json("PATCH", f"api/chats/{cid}", {"archived": "ja"})
        check(False, "archived kein bool -> 400")
    except APIError as e:
        check(e.code == 400, "archived kein bool -> 400")
    # --- Nur fuer mich (Direkt-Chat) -----------------------------------------------------------------------------------------
    a.use()
    box = {}
    a.st.load_messages(cid, done=lambda: box.setdefault("ok", 1))
    pump(lambda: box, 8)
    msgs = a.st.cache[cid]["msgs"]
    fa = next((x for m in msgs for x in m["files"]), None)
    data = a.st.fetch_file(cid, fa) if fa else None
    blob_path = chatmedia._path(fa["id"], "", cid) if fa else ""
    check(len(msgs) == 3 and data == b"Anhang fuer den Loeschtest" and os.path.exists(blob_path),
          "A: Verlauf entschluesselt, Anhang (Ciphertext) auf der Platte mit Chat-Kennung")
    top = max(m["seq"] for m in msgs)
    r = a.run(a.st.delete_chat, cid, "me")
    check(isinstance(r, dict) and r.get("scope") == "me" and r.get("hidden") is True and r.get("archived") is False
          and r.get("cleared_seq", 0) >= top, f"DELETE ?scope=me -> hidden, cleared_seq={r.get('cleared_seq') if isinstance(r, dict) else r}")
    check(cid not in a.st.cache and a.st.chat(cid) is None and not os.path.exists(blob_path) and all(not m for m in msgs),
          "A: Zwischenspeicher leer (Nachrichten, Klartext, Anhang auf der Platte), Chat aus der Liste")
    check(any(g[0] == cid and g[1] == "hidden" for g in a.gone), "Signal chat-gone (hidden) - offene Unterhaltung schliesst")
    d = a.wait_event("chat.cleared", cid)
    check(d is not None and d.get("scope") == "me" and d.get("cleared_seq") == r.get("cleared_seq"), "Live: chat.cleared {scope: me, cleared_seq}")
    a.load()
    check(a.st.chat(cid) is None, "GET /api/chats ohne den fuer mich geloeschten Direkt-Chat")
    a.use()
    j = client.get(f"api/chats/{cid}")
    check(j["chat"]["cleared_seq"] >= top, "GET /api/chats/{cid} geht weiter (Grenze cleared_seq)")
    j = client.get(f"api/chats/{cid}/messages?device={a.st.device_id}&limit=50")
    check(not j.get("messages"), "Nachrichten bis zur Grenze kommen nicht mehr")
    try:
        chatmedia.get_blob(fa["id"], keep=False, cid=cid)
        check(False, "Anhang fuer A -> 404")
    except APIError as e:
        check(e.code == 404, "Anhang fuer A -> 404")
    b.use()
    j = client.get(f"api/chats/{cid}/messages?device={b.st.device_id}&limit=50")
    check(len(j.get("messages") or []) == 3 and b.st.fetch_file(cid, f) == b"Anhang fuer den Loeschtest", "B behaelt Verlauf und Anhang")
    b.send(cid, "Bist du noch da?")
    a.load()
    box = {}
    a.use()
    a.st.load_messages(cid, done=lambda: box.setdefault("ok", 1))
    pump(lambda: box, 8)
    check(a.st.chat(cid) is not None and [m["text"] for m in a.st.cache[cid]["msgs"]] == ["Bist du noch da?"],
          "Neue Nachricht: Direkt-Chat wieder da, leer bis auf das Neue")
    # --- freie Gruppe: 403, verlassen, fuer alle ---------------------------------------------------------------------
    a.use()
    g1 = client.json("POST", "api/chats", {"kind": "free", "members": [B], "device": a.st.device_id})["chat"]["id"]
    a.send(g1, "Gruppe 1")
    b.load()
    check(b.st.chat(g1) and b.st.chat(g1).get("delete_for_all") is None, "freie Gruppe: B (kein Chat-Admin) delete_for_all=null")
    r = b.run(b.st.delete_chat, g1, "all")
    check(isinstance(r, APIError) and r.code == 403, "B: ?scope=all ohne Recht -> 403")
    r = a.run(a.st.delete_chat, g1, "all")
    check(isinstance(r, dict) and r.get("removed") is True and a.st.chat(g1) is None, "A (Admin): freie Gruppe fuer alle geloescht")
    d = b.wait_event("chat.removed", g1)
    check(d is not None and (d.get("by") or {}).get("username") == A and b.st.chat(g1) is None and g1 not in b.st.cache,
          "B: Live chat.removed mit 'by' - Chat weg, Zwischenspeicher leer")
    check(any(g[0] == g1 and g[1] == "removed" and g[2] for g in b.gone), "B: Hinweis '<Name> hat den Chat geloescht.' (Name gesetzt)")
    a.wait_event("chat.removed", g1)
    check(not [g for g in a.gone if g[0] == g1 and g[2]], "A (selbst geloescht): kein Hinweis mit Namen")
    b.use()
    g2 = client.json("POST", "api/chats", {"kind": "free", "members": [A], "device": b.st.device_id})["chat"]["id"]
    b.send(g2, "Gruppe 2")
    a.load()
    r = a.run(a.st.delete_chat, g2, "me", leave=True)
    a.use()
    try:
        client.get(f"api/chats/{g2}")
        left = False
    except APIError as e:
        left = e.code == 404
    check(isinstance(r, dict) and r.get("left") and left and a.st.chat(g2) is None, "Nur fuer mich + Gruppe verlassen")
    # --- Gruppen-Chat: Verlauf fuer alle (B ist Saga-Admin) ---------------------------------------------------------------
    if GROUP:
        a.use()
        gc = client.json("POST", "api/chats", {"kind": "group", "group": GROUP, "device": a.st.device_id})["chat"]["id"]
        a.send(gc, "Team-Thema")
        a.load()
        b.load()
        check(a.st.chat(gc).get("delete_for_all") is None and b.st.chat(gc).get("delete_for_all") == "history",
              "Gruppen-Chat: delete_for_all A=null, B (Saga-Admin)=history")
        box = {}
        a.use()
        a.st.load_messages(gc, done=lambda: box.setdefault("ok", 1))
        pump(lambda: box, 8)
        r = b.run(b.st.delete_chat, gc, "all")
        check(isinstance(r, dict) and r.get("removed") is False and r.get("deleted", 0) >= 1, "B: Verlauf fuer alle geloescht")
        d = a.wait_event("chat.cleared", gc)
        check(d is not None and d.get("scope") == "all" and not a.st.cache[gc]["msgs"] and a.st.chat(gc) is not None,
              "A: Live chat.cleared {scope: all} - Verlauf leer, Chat bleibt")
        check(any(g[0] == gc and g[1] == "cleared" and g[2] for g in a.gone), "A: Hinweis '<Name> hat den Verlauf geloescht.'")
        pump(lambda: int((a.st.chat(gc) or {}).get("cleared_seq") or 0) > 0, 5)
        check(int(a.st.chat(gc).get("cleared_seq") or 0) > 0, "A: cleared_seq > 0 -> 'Der bisherige Verlauf wurde geloescht.'")
    a.stop = b.stop = True
    print(f"\n{sum(RES)}/{len(RES)} OK", flush=True)
    sys.exit(0 if all(RES) else 1)


if __name__ == "__main__":
    main()
