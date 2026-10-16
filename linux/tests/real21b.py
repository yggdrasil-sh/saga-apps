"""Pruefung gegen einen echten Saga-2.1-Server (kein pytest), mit zwei WEGWERF-Konten (vorher anlegen, danach loeschen):
  - Konto A bekommt eine Authenticator-App (TOTP) ueber die Konto-API; danach Anmeldung WIE DIE APP: /app/login (PKCE) ->
    Passwort -> Zwischenschritt /login/2fa mit Code -> /app/handoff -> hlidskjalf://auth?code=... -> client.exchange
    (falscher Code wird abgewiesen); Geraete-Schluessel und /app/resume danach ohne zweiten Faktor
  - "Anmeldung & Sicherheit": Stand wie in den Einstellungen
  - Chat "Neu beginnen" in der App-Sitzung: reauth + reauth_methods, falscher Code -> 403, richtiger Code -> neu begonnen
  - Profilbild: Zuschnitt der App hochladen, Feld "avatar" in /api/account und beim Gegenueber im Direkt-Chat, Bild laden
    (Stufe 64), Speicher-/Platten-Zwischenspeicher, entfernen
  - Anrufe: B ruft A an (Angebot mit chatcrypto.seal_signal fuer A's Geraet versiegelt); A klingelt ueber den Live-Kanal
    (calls.Calls: GET /api/calls/<id>, nur ringing/callee), oeffnet und prueft das Signal (open/verify/Fingerabdruck);
    Ablehnen -> Chat-Eintrag "abgelehnt"; zweiter Anruf, B legt auf -> Klingeln endet, "Verpasster Anruf" im Chat und als
    Benachrichtigung; derselbe Anruf klingelt nicht noch einmal
    HK_REAL_BASE=http://host:port HK_REAL_USERS=/pfad python3 tests/real21b.py
Die Datei enthaelt Zeilen "benutzer=passwort" fuer genau zwei Konten (A zuerst). Passwoerter/Geheimnisse werden nie ausgegeben."""
import base64
import hashlib
import hmac
import http.cookies
import io
import json
import os
import queue
import re
import struct
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

os.environ.setdefault("HK_NO_KEYRING", "1")
os.environ["HK_NO_RING"] = "1"
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gi.repository import GLib  # noqa: E402

from hlidskjalf import avatars, config  # noqa: E402
from hlidskjalf import chatcrypto as C  # noqa: E402
from hlidskjalf.api import APIError, client, pkce, sse_events  # noqa: E402
from hlidskjalf.calls import Calls, entry_text, parse_entry  # noqa: E402
from hlidskjalf.chat import ChatError, ChatState  # noqa: E402
from hlidskjalf.notifications import item_text  # noqa: E402
from hlidskjalf.profile import crop_bytes  # noqa: E402
from hlidskjalf.security import status_lines  # noqa: E402

BASE = os.environ["HK_REAL_BASE"].rstrip("/")
_users = [x.strip().split("=", 1) for x in open(os.environ["HK_REAL_USERS"], encoding="utf-8") if "=" in x]
(A, PWA), (B, PWB) = _users[0], _users[1]
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


# --- TOTP (RFC 6238, SHA1, 30 s, 6 Ziffern) - wie eine Authenticator-App -------------------------------------------
class Totp:
    def __init__(self, secret):
        s = re.sub(r"[\s-]", "", secret).upper()
        self.key = base64.b32decode(s + "=" * (-len(s) % 8))
        self.last = 0

    def code(self, step):
        h = hmac.new(self.key, struct.pack(">Q", step), hashlib.sha1).digest()
        o = h[-1] & 15
        return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)

    def next(self):
        """naechster noch nicht benutzter Code (der Server nimmt +-1 Schritt, jeden nur einmal)"""
        while True:
            now = int(time.time() // 30)
            st = max(now, self.last + 1)
            if st <= now + 1:
                self.last = st
                return self.code(st)
            time.sleep(1)

    def wrong(self):
        c = self.code(int(time.time() // 30))
        return "%06d" % ((int(c) + 500000) % 1000000)


# --- Web wie ein Browser (eigene Cookies, keine automatischen Weiterleitungen) ---------------------------------------
class _NoRedir(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


class Browser:
    def __init__(self):
        self.jar = {}
        self.op = urllib.request.build_opener(_NoRedir)

    def _take(self, h):
        for raw in h.get_all("Set-Cookie") or []:
            c = http.cookies.SimpleCookie()
            c.load(raw)
            for k, m in c.items():
                if m["max-age"] in ("0",) or not m.value:
                    self.jar.pop(k, None)
                else:
                    self.jar[k] = m.value

    def req(self, path, form=None):
        """-> (status, Location, Text)"""
        url = path if path.startswith("http") else BASE + path
        data = urllib.parse.urlencode(form).encode() if form is not None else None
        hd = {"Cookie": "; ".join(f"{k}={v}" for k, v in self.jar.items()), "User-Agent": "real21b"}
        try:
            r = self.op.open(urllib.request.Request(url, data=data, headers=hd), timeout=20)
            self._take(r.headers)
            return r.status, r.headers.get("Location"), r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            self._take(e.headers)
            return e.code, e.headers.get("Location"), (e.read() or b"").decode("utf-8", "replace")

    def follow(self, path, form=None, limit=8):
        st, loc, txt = self.req(path, form)
        while st in (301, 302, 303, 307) and loc and limit > 0:
            if loc.startswith("hlidskjalf://"):
                return st, loc, txt
            st, loc, txt = self.req(loc)
            limit -= 1
        return st, loc, txt


def csrf(txt):
    m = re.search(r'name="csrf" value="([^"]+)"', txt)
    return m.group(1) if m else ""


def web_login(user, pw):
    b = Browser()
    st, loc, txt = b.follow("/login")
    st, loc, txt = b.req("/login", {"username": user, "password": pw, "csrf": csrf(txt)})
    return b, st, loc


def use(jar):
    client.set_cookies(BASE, jar)


# --- Ablauf -------------------------------------------------------------------------------------------------------------
def main():
    config.settings.server = BASE
    # 1) A: Authenticator-App einrichten (frische Web-Anmeldung, nur Passwort)
    ba, st, loc = web_login(A, PWA)
    check(st == 303 and loc in ("/", "/notes"), f"A: Web-Anmeldung nur mit Passwort (noch ohne 2FA) -> {loc}")
    use(ba.jar)
    sec = client.get("api/account/security")
    check(sec.get("supported") is True and sec.get("enabled") is False, "A: /api/account/security (unterstuetzt, aus)")
    s = client.json("POST", "api/account/totp/setup", {})
    check(s.get("secret") and str(s.get("uri", "")).startswith("otpauth://"), "A: TOTP-Einrichtung (Geheimnis + otpauth-Adresse)")
    totp = Totp(s["secret"])
    r = client.json("POST", "api/account/totp/confirm", {"code": totp.next()})
    codes = (r or {}).get("recovery") or (r or {}).get("codes") or []
    sec = client.get("api/account/security")
    check(sec.get("enabled") and sec.get("totp"), f"A: Authenticator-App bestaetigt ({len(codes)} Wiederherstellungscodes)")
    title, sub, warn = status_lines(sec)
    check(title.startswith("✓") and "Authenticator" in sub, f"Stand wie in den Einstellungen: {title} / {sub}")

    # 2) Anmeldung wie die App: /app/login (PKCE) -> Passwort -> /login/2fa -> /app/handoff -> hlidskjalf://auth?code
    verifier, challenge = pkce()
    br = Browser()
    st, loc, txt = br.follow(f"/app/login?challenge={challenge}")
    check(st == 200 and 'name="password"' in txt, "App-Anmeldung: Browser zeigt die Anmeldeseite")
    st, loc, txt = br.req("/login", {"username": A, "password": PWA, "csrf": csrf(txt)})
    check(st == 303 and loc == "/login/2fa", "nach dem Passwort: Zwischenschritt /login/2fa")
    st, loc, txt = br.req("/login/2fa")
    check(st == 200 and 'name="code"' in txt, "2FA-Seite fragt nach dem Code")
    st, loc, txt2 = br.req("/login/2fa", {"code": totp.wrong(), "csrf": csrf(txt)})
    check(st == 401, "falscher Code wird abgewiesen (401)")
    st, loc, txt = br.req("/login/2fa", {"code": totp.next(), "csrf": csrf(txt2) or csrf(txt)})
    check(st == 303 and loc == "/app/handoff", "richtiger Code -> weiter zu /app/handoff")
    st, loc, txt = br.follow(loc)
    m = re.search(r"hlidskjalf://auth\?code=([A-Za-z0-9_\-]+)", (loc or "") + txt)
    check(m is not None, "Rueckkehr in die App: hlidskjalf://auth?code=…")
    j, jar = client.exchange(m.group(1), verifier, BASE)
    client.adopt_login(BASE, j, jar)
    check(str(j.get("device_token", "")).startswith("hkd_"), "client.exchange: Sitzung + Geraete-Schluessel")
    check(client.get("api/account").get("username") == A, "App-Sitzung gehoert zu A")
    client.clear_cookies()
    check(client.resume() == "ok" and client.get("api/account").get("username") == A, "/app/resume ohne zweiten Faktor")
    app_jar = dict(client.cookies)

    # 3) Chat einrichten (B, A), dann in der App-Sitzung "Neu beginnen" mit Code
    bb, st, loc = web_login(B, PWB)
    use(bb.jar)
    sb = ChatState()
    sb.me = client.get("api/chat/me")
    kb = sb.setup(C.recovery_key())
    sb.keys, sb.status = kb, "ready"
    sb.me = client.get("api/chat/me?device=" + kb["device"]["id"])
    use(app_jar)
    sa = ChatState()
    sa.me = client.get("api/chat/me")
    ka = sa.setup(C.recovery_key())
    sa.keys, sa.status = ka, "ready"
    me = client.get("api/chat/me?device=" + ka["device"]["id"])
    sa.me = me
    check(me.get("reauth") == "password" and "totp" in (me.get("reauth_methods") or []),
          f"App-Sitzung: reauth={me.get('reauth')}, reauth_methods={me.get('reauth_methods')}")
    try:
        sa.setup(C.recovery_key(), None, True, totp.wrong())
        check(False, "Neu beginnen mit falschem Code muss scheitern")
    except ChatError as e:
        check(e.code == 403 and "totp" in ((e.data or {}).get("reauth_methods") or []), "Neu beginnen: falscher Code -> 403 + reauth_methods")
    old = client.get("api/chat/me")["identity"]["ident_pub"]
    ka = sa.setup(C.recovery_key(), None, True, totp.next())
    sa.keys = ka
    sa.me = client.get("api/chat/me?device=" + ka["device"]["id"])
    check(sa.me["identity"]["ident_pub"] == C.b64(ka["ident_pk"]) != old, "Neu beginnen mit Code der Authenticator-App: neue Schluessel")

    # 4) Profilbild
    from PIL import Image
    im = Image.new("RGB", (900, 700), (90, 140, 200))
    data, ctype = crop_bytes(im, (150, 50, 600))
    info = client.json("PUT", "api/account/avatar", raw=data, ctype=ctype)
    check(info.get("source") == "upload" and info.get("upload") and str(info.get("url", "")).startswith("/api/avatar/"),
          f"Profilbild hochgeladen ({ctype}, {len(data)} Bytes)")
    url = client.get("api/account").get("avatar")
    check(url == info["url"] and "v=" in url, "GET /api/account: Feld avatar (mit Version)")
    use(bb.jar)
    ch = client.json("POST", "api/chats", {"kind": "dm", "user": A})["chat"]
    cid = ch["id"]
    check(ch["peer"]["username"] == A and ch["peer"].get("avatar") == url, "B sieht A's Bild im Direkt-Chat (peer.avatar)")
    use(app_jar)
    avatars.reset()
    raw = avatars.fetch(config.origin(BASE), url, 64)
    pic = Image.open(io.BytesIO(raw))
    check(pic.size[0] == pic.size[1] and pic.size[0] >= 64, f"Bild geladen (Stufe 64 -> {pic.size[0]} px, {pic.format})")
    t0 = time.time()
    raw2 = avatars.fetch(config.origin(BASE), url, 64)
    check(raw2 == raw and time.time() - t0 < 0.05, "zweites Laden aus dem Platten-Zwischenspeicher")
    me_b = client.get(f"api/chats/{cid}?device={ka['device']['id']}")["chat"].get("peer") or {}
    if me_b.get("avatar"):
        try:
            avatars.fetch(config.origin(BASE), me_b["avatar"], 64)
            check(True, "Bild von B (Gravatar/Standard) geladen")
        except APIError as e:
            check(e.code == 404, "B ohne Bild: 404 -> Initialen")
    else:
        check(me_b.get("avatar") is None, "B ohne Bild: avatar = null -> Initialen")

    # 5) Anrufe: Live-Kanal von A (App) mitlesen
    events = queue.Queue()
    use(app_jar)
    resp = client.stream("GET", "api/events", timeout=40)
    stop = {"x": False}

    def reader():
        try:
            for ev, d, _eid in sse_events(resp, stop=lambda: stop["x"]):
                events.put((ev, d))
        except Exception:  # noqa: BLE001
            pass
    threading.Thread(target=reader, daemon=True).start()
    calls = Calls()
    cfg = client.get("api/calls/config")
    check(cfg.get("enabled") and cfg.get("format") == "saga-call/v1", f"GET /api/calls/config (enabled, {len(cfg.get('ice_servers') or [])} ICE-Server)")

    def dispatch(secs):
        end = time.time() + secs
        while time.time() < end:
            try:
                ev, d = events.get(timeout=0.1)
            except queue.Empty:
                pump(None, 0.05)
                continue
            if ev.startswith("call.") or ev == "hello":
                use(app_jar)
                calls.on_live(ev, d or {})
            pump(None, 0.05)

    def call_from_b(media):
        use(bb.jar)
        keys = client.get("api/chat/keys?users=" + A)["users"][A]
        devs = [d for d in keys["devices"] if not d.get("revoked")]
        call_id = C.hexid()
        sdp = ("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0 1\r\n"
               "a=fingerprint:sha-256 " + ":".join("%02X" % x for x in os.urandom(32)) + "\r\n")
        body = json.dumps({"t": "offer", "sdp": sdp, "media": media, "fp": C.sdp_fingerprints(sdp)}, separators=(",", ":"))
        sig = {d["id"]: C.seal_signal(call_id, B, kb["device"], A, d["id"], C.unb64(d["enc_pub"], 32), body) for d in devs}
        r = client.json("POST", "api/calls", {"id": call_id, "chat_id": cid, "media": media, "device": kb["device"]["id"], "signals": sig})
        return call_id, r, body

    c1, r1, body1 = call_from_b("video")
    check(r1.get("ok") and r1["call"]["state"] == "ringing", "B ruft A an (Angebot fuer A's Geraet versiegelt)")
    dispatch(4)
    check(calls.ringing is not None and calls.ringing["id"] == c1 and calls.ringing["media"] == "video",
          "A klingelt (Live call.ring -> GET /api/calls/<id>: ringing, Rolle callee)")
    check((calls.ringing or {}).get("peer", {}).get("username") == B, "Anrufer im Klingeln: B (mit Name/Bild-Feld)")
    use(app_jar)
    g = client.get(f"api/calls/{c1}?device={ka['device']['id']}")
    sg = (g.get("signals") or [{}])[0]
    bd, sgn = C.open_signal(sg.get("sealed"), ka["device"]["enc_pk"], ka["device"]["enc_sk"])
    ok_sig = C.verify_signal(c1, B, sg["from_device"], A, ka["device"]["id"], bd, sgn, kb["device"]["sign_pk"])
    fp_ok = json.loads(bd)["fp"] == C.sdp_fingerprints(json.loads(bd)["sdp"])
    check(bd == body1 and ok_sig and fp_ok, "A oeffnet das Angebot: Signatur (Ziel = eigenes Geraet) + Fingerabdruck stimmen")
    check(not C.verify_signal(c1, B, sg["from_device"], A, "f" * 32, bd, sgn, kb["device"]["sign_pk"]), "fuer anderes Zielgeraet: abgelehnt")
    calls.decline(c1)
    pump(None, 0.5)
    dispatch(2)
    check(calls.ringing is None and c1 in calls.done, "A lehnt ab: Klingeln aus")
    use(bb.jar)
    check(client.get(f"api/calls/{c1}")["call"]["state"] == "ended", "Server: Anruf beendet (abgelehnt)")
    # derselbe Anruf noch einmal (z. B. nachgeholtes Ereignis): kein Klingeln
    use(app_jar)
    calls.on_live("call.ring", {"call_id": c1})
    pump(None, 1.0)
    check(calls.ringing is None, "nachgeholtes call.ring desselben Anrufs klingelt nicht wieder")
    # zweiter Anruf: B legt auf, bevor A annimmt -> verpasst
    c2, r2, _b = call_from_b("audio")
    dispatch(4)
    check(calls.ringing is not None and calls.ringing["id"] == c2, "zweiter Anruf klingelt")
    use(bb.jar)
    client.json("POST", f"api/calls/{c2}/end", {"reason": "hangup", "connected": False})
    dispatch(4)
    check(calls.ringing is None, "B legt auf: Klingeln endet (call.state ended)")
    # Chat-Eintraege und Benachrichtigung
    use(app_jar)
    j = client.get(f"api/chats/{cid}/messages?device={ka['device']['id']}&limit=50&calls=1")
    ents = [parse_entry(m) for m in j.get("messages") or [] if m.get("kind") == "call"]
    texts = [entry_text(k, A) for k in ents if k]
    check(len(ents) >= 2 and all(ents), f"Chat-Eintraege (calls=1): {texts}")
    check(any(k["status"] == "declined" for k in ents) and any(k["status"] == "missed" for k in ents),
          "Eintraege: abgelehnt + verpasst")
    j0 = client.get(f"api/chats/{cid}/messages?device={ka['device']['id']}&limit=50")
    check(not any(m.get("kind") == "call" for m in j0.get("messages") or []), "ohne calls=1 keine Anruf-Eintraege (alte Apps)")
    n = client.get("api/notifications?limit=30")
    miss = [x for x in n.get("items") or [] if x.get("kind") == "call.missed"]
    check(miss and item_text(miss[0]).startswith("Missed call from"), f"Benachrichtigung: {item_text(miss[0]) if miss else '-'}")
    # Aufraeumen (Konten loescht der Aufrufer)
    stop["x"] = True
    try:
        resp.close()
    except OSError:
        pass
    client.json("DELETE", "api/account/avatar")
    print(f"ECHTSERVER 2.1-b {sum(RES)}/{len(RES)}", "- ALLES GRUEN" if all(RES) else "- FEHLER", flush=True)
    return 0 if all(RES) else 1


if __name__ == "__main__":
    sys.exit(main())
