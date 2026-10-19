"""Pruefung gegen ZWEI echte, verbundene Saga-2.3-Server (kein pytest): Chats und Anrufe ueber Servergrenzen
(docs/APP-API-FED.md, saga-chat/v2) mit dem Zustand der Linux-App (chat.ChatState) - Anna und Carl auf Server A, Bert auf
Server B, dazu "Rita" auf B mit der Server-Referenz (tests/chatlib.py aus dem Server-Repo) als zweite Umsetzung:
  - Kopfzeile X-Saga-Chat: 2, Zertifikate v2 + signierte Geraete-Liste (still), eigener Handle
  - Adressbuch-Suche (ab 1 Zeichen genaue Treffer, "Name · host", name@host), Direkt-Chat mit Handle beginnen
  - Nachrichtenanfrage (Bereich, Zaehler), Senden/Empfangen v2 in beide Richtungen, Echtheit, Anhang v2 mit Vorschaubild
  - Gegenprobe mit der Server-Referenz (beide Richtungen), Sicherheitsnummer v2 auf beiden Seiten gleich
  - TOFU: Schluesselwechsel -> Warnung (Senden gesperrt) -> "Verstanden"; ueberprueft + Wechsel -> Blockade; Live chat.keys.changed
  - Heim offline -> "wird zugestellt" (pending) -> zugestellt; Sperren (Heim weist ab -> "nicht zugestellt"), Sperrliste, Entsperren
  - Anrufe saga-call/v2: Anfrage klingelt nie, angenommener Chat klingelt (calls.CallState), Ablehnen
  - lokale Chats (v1) unveraendert daneben
    HK_FED_STACKS=/pfad/stacks.json python3 tests/realfed.py
stacks.json: {"a": url, "b": url, "cookies": {"a": {konto: "saga=..."}, "b": {...}}} (zwei Wegwerf-Instanzen wie in
tests/foederation2.py im Server-Repo, verbunden; Konten fa-anna, fa-carl, fb-bert, fb-anna2). Der Starter muss die
Steuerdateien stacks.pausea / stacks.resumea neben stacks.json beachten (Server A anhalten bzw. wieder starten).
HK_FED_SERVER_TESTS=<Server-Repo>/tests fuer die Referenz (chatlib.py), Standard: ../saga/tests neben saga-apps."""
import json
import os
import sys
import threading
import time
import urllib.request

os.environ.setdefault("HK_NO_KEYRING", "1")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gi.repository import GLib  # noqa: E402

from hlidskjalf import chatmedia, config  # noqa: E402
from hlidskjalf import chatcrypto as C  # noqa: E402
from hlidskjalf.api import APIError, client, sse_events  # noqa: E402
from hlidskjalf.chat import ChatState, SafetyChanged, trust2_get  # noqa: E402

ST = json.load(open(os.environ["HK_FED_STACKS"], encoding="utf-8"))
PW = "Fed-User-Pass-123"                                  # Passwort der Wegwerf-Konten aus tests/foederation.py (Server-Repo)
CTL = os.path.dirname(os.environ["HK_FED_STACKS"])        # Steuerdateien stacks.pausea/.resumea: Server A kurz anhalten
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


def wait(fn, sec=15):
    end = time.monotonic() + sec
    while time.monotonic() < end:
        v = fn()
        if v:
            return v
        time.sleep(0.3)
    return fn()


class Person:
    """ein Konto mit eigenem Chat-Zustand (Linux-App), eigener Sitzung und eigenem Live-Kanal"""

    def __init__(self, k, user):
        self.k, self.user, self.base = k, user, ST[k]
        self.jar = {"saga": ST["cookies"][k][user].split("=", 1)[1]}
        self.use()
        self.st = ChatState()
        self.st.me = client.get("api/chat/me")
        k2 = self.st.setup(C.recovery_key(), PW, reset=bool(self.st.me.get("identity")))
        self.st.keys, self.st.status = k2, "ready"
        self.st.me = client.get("api/chat/me?device=" + k2["device"]["id"])
        self.certs = self.st.fed_certs()
        self.events = []
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
        config.settings.server = self.base
        client.set_cookies(self.base, self.jar)

    def run(self, fn, *a, timeout=10, **kw):
        """ChatState-Aufruf mit done/error (laeuft im Hintergrund) -> Ergebnis bzw. Fehler"""
        self.use()
        box = {}
        fn(*a, done=lambda r=None: box.setdefault("r", r), error=lambda e: box.setdefault("e", e), **kw)
        pump(lambda: box, timeout)
        pump(None, 1.0)
        return box["e"] if "e" in box else box.get("r")

    def call(self, fn, *a, **kw):
        """Hintergrund-Funktion des Zustands direkt (wie bg) -> Ergebnis oder Ausnahme als Wert"""
        self.use()
        try:
            return fn(*a, **kw)
        except Exception as e:  # noqa: BLE001
            return e

    def load(self):
        self.use()
        box = {}
        self.st.load_chats(done=lambda: box.setdefault("ok", 1))
        pump(lambda: box, 10)
        pump(None, 0.5)

    def msgs(self, cid):
        self.use()
        box = {}
        self.st.load_messages(cid, done=lambda: box.setdefault("ok", 1))
        pump(lambda: box, 15)
        return (self.st.cache.get(cid) or {}).get("msgs") or []

    def fresh_msgs(self, cid):
        self.st.cache.pop(cid, None)
        return self.msgs(cid)

    def texts(self, cid):
        return [m.get("text") for m in self.fresh_msgs(cid) if m.get("state") in ("ok", "unverified")]

    def send(self, cid, text, files=None, reply_to=None):
        self.use()
        body = {"v": 1, "type": "text", "text": text}
        fobjs, fids = [], None
        if files:
            fobjs, fids = self.st.upload_attachments(cid, files)
            body["files"] = fobjs
        return self.st.send(cid, body, reply_to=reply_to, file_ids=fids)

    def event(self, typ, pred=lambda d: True, timeout=10):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for i, (ev, d) in enumerate(self.events):
                if ev == typ and isinstance(d, dict) and pred(d):
                    self.events.pop(i)
                    return d
            time.sleep(0.1)
        return None


class Ref:
    """Gegenprobe mit der Server-Referenz (tests/chatlib.py): eigenes Geraet, Zertifikate v2, Senden/Lesen v2"""

    def __init__(self, k, user):
        sys.path.insert(0, os.environ.get("HK_FED_SERVER_TESTS") or os.path.join(os.path.dirname(__file__), "..", "..", "..", "saga", "tests"))
        import chatlib
        self.L = L = chatlib
        self.k, self.user, self.base, self.cookie = k, user, ST[k], ST["cookies"][k][user]
        self.ident, self.dev = L.identity(), L.device()
        self.api("PUT", "/api/chat/identity", L.setup_body(user, self.ident, self.dev, ops=1, mem=8 << 20))
        me = self.api("GET", "/api/chat/v2/me")
        self.handle = me["handle"]
        me = self.api("PUT", "/api/chat/v2/cert", {"handle_sig": L.b64(L.handle_sig(self.handle, self.ident)),
                                                   "devices": {self.dev["id"]: L.b64(L.device_sig_v2(self.handle, self.ident, self.dev))}})
        ver = ((me.get("device_set") or {}).get("ver") or 0) + 1
        self.api("PUT", "/api/chat/v2/cert", {"device_set": L.devices_sig_v2(self.handle, self.ident, ver, me["device_set_want"])})

    def api(self, m, p, body=None):
        req = urllib.request.Request(self.base + p, data=json.dumps(body).encode() if body is not None else None, method=m,
                                     headers={"X-Saga": "1", "X-Saga-Chat": "2", "Cookie": self.cookie, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read() or b"null")

    def chat(self, ref):
        return next((c for c in self.api("GET", "/api/chats?device=" + self.dev["id"])["chats"] if c.get("ref") == ref), None)

    def send(self, ch, text):
        L = self.L
        ents = self.api("GET", "/api/chat/v2/keys?handles=" + ",".join(m["handle"] for m in ch["members"]))["keys"]
        m = L.encrypt_v2(ch["ref"], self.handle, self.dev, L.recipients_v2([e for e in ents.values() if e]),
                         json.dumps({"v": 1, "type": "text", "text": text}))
        m["v"] = 2
        return self.api("POST", f"/api/chats/{ch['id']}/messages", m), m

    def texts(self, ch):
        L, out = self.L, []
        for m in self.api("GET", f"/api/chats/{ch['id']}/messages?calls=1&device={self.dev['id']}")["messages"]:
            if m.get("kind") == "msg" and m.get("key"):
                pk, sk = (self.dev["enc_pk"], self.dev["enc_sk"]) if m["key_for"] == "device" else (self.ident["backup_pk"], self.ident["backup_sk"])
                out.append(json.loads(L.decrypt_v2(ch["ref"], m, m["key"], pk, sk))["text"])
        return out


def png(w=900, h=600):
    from PIL import Image
    import io
    b = io.BytesIO()
    Image.new("RGB", (w, h), (40, 120, 200)).save(b, "PNG")
    return b.getvalue()


def main():
    anna, bert, carl = Person("a", "fa-anna"), Person("b", "fb-bert"), Person("a", "fa-carl")
    for p in (anna, bert, carl):
        check(p.st.my_handle and p.st.my_handle.endswith("@" + config.origin(p.base).split("//", 1)[1]) and p.certs
              and not p.certs.get("need") and p.certs.get("device_set_ok"),
              f"{p.user}: Zertifikate v2 + signierte Geraete-Liste still hochgeladen, Handle {p.st.my_handle}")
    time.sleep(1)
    check(all(p.events and p.events[0][0] == "hello" for p in (anna, bert, carl)), "Live-Kanal aller Konten")
    # --- Adressbuch-Suche --------------------------------------------------------------------------------------------
    anna.use()
    r = client.get("api/chat/people?q=bert")
    hit = next((x for x in r.get("remote") or [] if x.get("handle") == bert.st.my_handle), None)
    check(hit and hit.get("label", "").startswith("Bert Gast · ") and hit.get("host"), f"Suche 'bert': Bert mit Label '{(hit or {}).get('label')}'")
    r1 = client.get("api/chat/people?q=" + urllib.request.quote("Bert Gast"))
    check(any(x.get("handle") == bert.st.my_handle for x in r1.get("remote") or []), "Suche nach vollem Anzeigenamen")
    r2 = client.get("api/chat/people?q=Be")
    check(not any(x.get("handle") == bert.st.my_handle for x in r2.get("remote") or []), "1-2 Zeichen: nur genaue Treffer (Be findet Bert nicht)")
    host_b = config.origin(ST["b"]).split("//", 1)[1]
    r3 = client.get("api/chat/people?q=" + urllib.request.quote("bert@" + host_b))
    check(any(x.get("handle") == bert.st.my_handle for x in r3.get("remote") or []), "name@host findet genau diese Person")
    r4 = client.get("api/chat/people?q=" + urllib.request.quote("Anna Berg"))
    labels = sorted(x.get("label") for x in r4.get("remote") or [])
    check(len(labels) == 2 and len(set(labels)) == 2, f"gleiche Namen unterscheidbar: {labels}")
    st = client.get("api/share/targets?q=bert")
    check(any((x.get("label") or "").startswith("Bert Gast") for x in st.get("remote") or []), "Teilen-Suche: Adressbuch-Treffer mit Label")
    # --- Chat beginnen, Anfrage ----------------------------------------------------------------------------------------
    ch = anna.run(anna.st.new_chat, {"kind": "dm", "handle": bert.st.my_handle})
    check(isinstance(ch, dict) and ch.get("v") == 2 and ch.get("ref") and ch.get("home_self") and chatmedia.REFS.get(ch["id"]) == ch["ref"],
          "Anna beginnt Direkt-Chat mit Bert (v2, Heim A, chat_ref gemerkt)")
    cid_a = ch["id"]
    files = [("bild.png", "image/png", png()), ("notiz.txt", "text/plain", "Anhang über Servergrenzen äöü".encode())]
    r = anna.call(anna.send, cid_a, "Hallo Bert – über Servergrenzen!", files=files)
    check(isinstance(r, tuple) and r[0].get("fseq") == 1 and not r[0].get("pending"), f"Anna sendet v2 mit Bild + Datei ({r if not isinstance(r, tuple) else r[0]})")
    bert.load()
    bc = next((c for c in bert.st.chats if c.get("ref") == ch["ref"]), None)
    check(bc and bc.get("request") and bert.st.requests == 1, "Bert: Nachrichtenanfrage (eigener Zaehler)")
    check(bert.event("chat.request", timeout=5) is not None, "Bert: Live chat.request")
    cid_b = bc["id"]
    ms = bert.fresh_msgs(cid_b)
    m1 = next((m for m in ms if m.get("text")), {})
    check(m1.get("state") == "ok" and m1.get("text") == "Hallo Bert – über Servergrenzen!" and m1.get("fseq") == 1
          and m1.get("sender_handle") == anna.st.my_handle, f"Bert liest die Anfrage: entschluesselt + echt ({m1.get('state')})")
    fl = m1.get("files") or []
    bert.use()
    got = [bert.call(bert.st.fetch_file, cid_b, f) for f in fl]
    check(len(fl) == 2 and got[0] == files[0][2] and got[1] == files[1][2], "Anhaenge v2 (beim Absender-Server) geladen + entschluesselt")
    th = chatmedia.thumb_ref(fl[0]) if fl else None
    check(th and bert.call(chatmedia.get_blob, th["id"], cid=cid_b) and fl[0].get("w") == 900, "Vorschaubild v2 vorhanden")
    res = bert.run(bert.st.answer_request, cid_b, "accept")
    bert.load()
    bc = bert.st.chat(cid_b)
    check(not isinstance(res, Exception) and bc and not bc.get("request"), "Bert nimmt die Anfrage an")
    r = bert.call(bert.send, cid_b, "Hallo Anna, Grüße von B", reply_to=m1.get("id"))
    check(isinstance(r, tuple) and r[0].get("fseq") == 2, f"Bert antwortet (fseq {r[0].get('fseq') if isinstance(r, tuple) else r})")
    am = wait(lambda: [m for m in anna.fresh_msgs(cid_a) if m.get("text") == "Hallo Anna, Grüße von B"], 15)
    check(am and am[0]["state"] == "ok" and am[0]["reply_to"] == m1.get("id") and not am[0]["mine"], "Anna liest Berts Antwort (echt, Antwort auf)")
    own = [m for m in anna.fresh_msgs(cid_a) if m.get("mine")]
    check(own and own[0]["state"] == "ok", "Anna sieht die eigene Nachricht (Backup/Geraet, echt)")
    # --- Sicherheitsnummer v2 ------------------------------------------------------------------------------------------
    fa = anna.call(anna.st.fed_keys, [bert.st.my_handle], True)[bert.st.my_handle]
    fb = bert.call(bert.st.fed_keys, [anna.st.my_handle], True)[anna.st.my_handle]
    sa = C.safety_number_v2(anna.st.my_handle, anna.st.keys["ident_pk"], bert.st.my_handle, fa["keys"]["ident_pk"])
    sb = C.safety_number_v2(bert.st.my_handle, bert.st.keys["ident_pk"], anna.st.my_handle, fb["keys"]["ident_pk"])
    check(sa == sb and len(sa.split()) == 12 and fa["trust"] == "trust" and fb["trust"] == "trust", "Sicherheitsnummer v2 auf beiden Seiten gleich")
    # --- Gegenprobe mit der Server-Referenz (Rita auf B) -----------------------------------------------------------------
    rita = Ref("b", "fb-anna2")
    rc = anna.run(anna.st.new_chat, {"kind": "dm", "handle": rita.handle})
    rch = wait(lambda: rita.chat(rc["ref"]), 10)
    r = anna.call(anna.send, rc["id"], "Von Linux an die Referenz")
    check(isinstance(r, tuple) and rch and wait(lambda: rita.texts(rch) == ["Von Linux an die Referenz"], 10), "Referenz liest Linux-Nachricht v2")
    rita.api("POST", f"/api/chats/{rch['id']}/request", {"action": "accept"})
    rita.send(rch, "Von der Referenz an Linux")
    rm = wait(lambda: [m for m in anna.fresh_msgs(rc["id"]) if m.get("text") == "Von der Referenz an Linux"], 10)
    check(rm and rm[0]["state"] == "ok", "Linux liest Referenz-Nachricht v2 (echt)")
    # --- TOFU: Bert beginnt neu ----------------------------------------------------------------------------------------
    anna.events.clear()
    bert.use()
    k2 = bert.st.setup(C.recovery_key(), PW, reset=True)
    bert.st.keys = k2
    bert.st.me = client.get("api/chat/me?device=" + k2["device"]["id"])
    bert.st.fed_certs()
    ev = anna.event("chat.keys.changed", lambda d: d.get("handle") == bert.st.my_handle, timeout=15)
    check(ev is not None, "Anna: Live chat.keys.changed fuer Bert")
    anna.use()
    anna.st.on_live("chat.keys.changed", ev or {"handle": bert.st.my_handle})
    pump(lambda: (anna.st.fk.get(bert.st.my_handle) or {}).get("trust") == "warn", 10)
    check((anna.st.fk.get(bert.st.my_handle) or {}).get("trust") == "warn", "TOFU: Schluesselwechsel -> Warnung")
    anna.load()
    r = anna.call(anna.send, cid_a, "darf nicht raus")
    check(isinstance(r, SafetyChanged), f"Warnung: Senden gesperrt ({r})")
    check(anna.st.fed_problems(anna.st.chat(cid_a)) and anna.st.fed_problems(anna.st.chat(cid_a))[0][1] == "warn", "Chat zeigt die Warnung (fed_problems)")
    nk = anna.st.fk[bert.st.my_handle]["keys"]
    anna.call(anna.st.fed_verify, bert.st.my_handle, nk["ident_pub"], False)                  # "Verstanden"
    r = anna.call(anna.send, cid_a, "nach Verstanden")
    check(isinstance(r, tuple) and r[0].get("fseq"), "nach \"Verstanden\" wieder senden")
    check(wait(lambda: "nach Verstanden" in bert.texts(cid_b), 10), "Bert (neue Schluessel) liest die neue Nachricht")
    anna.call(anna.st.fed_verify, bert.st.my_handle, nk["ident_pub"], True)                   # ueberprueft
    anna.use()
    check(anna.st.fed_verified(bert.st.my_handle) and trust2_get(anna.st.uid, bert.st.my_handle).get("verified"), "als ueberprueft markiert")
    bert.use()
    k3 = bert.st.setup(C.recovery_key(), PW, reset=True)
    bert.st.keys = k3
    bert.st.me = client.get("api/chat/me?device=" + k3["device"]["id"])
    bert.st.fed_certs()
    time.sleep(1)
    f = anna.call(anna.st.fed_keys, [bert.st.my_handle], True)[bert.st.my_handle]
    check(f["trust"] == "block", "ueberprueft + erneuter Wechsel -> Blockade")
    r = anna.call(anna.send, cid_a, "gesperrt")
    check(isinstance(r, Exception) and not isinstance(r, SafetyChanged), f"Blockade: Senden gesperrt ({r})")
    anna.call(anna.st.fed_verify, bert.st.my_handle, f["keys"]["ident_pub"], True)            # Nummer neu verglichen
    r = anna.call(anna.send, cid_a, "neu verglichen")
    check(isinstance(r, tuple), "nach erneutem Vergleich wieder senden")
    # --- Heim offline -> wartet beim eigenen Server (Bert sendet, Heim A weg) -------------------------------------------
    open(os.path.join(CTL, "stacks.pausea"), "w").close()
    time.sleep(4)
    bert.load()
    r = bert.call(bert.send, cid_b, "waehrend A weg ist")
    check(isinstance(r, tuple) and r[0].get("pending"), f"Heim nicht erreichbar -> pending ({r if not isinstance(r, tuple) else r[0]})")
    pend = [m for m in bert.fresh_msgs(cid_b) if m.get("text") == "waehrend A weg ist"]
    check(pend and pend[0].get("fstate") == "pending", "eigene Nachricht: 'wird zugestellt' (fstate pending)")
    open(os.path.join(CTL, "stacks.resumea"), "w").close()
    ok = wait(lambda: [m for m in bert.fresh_msgs(cid_b) if m.get("text") == "waehrend A weg ist" and not m.get("fstate")], 60)
    check(ok, "nach Rueckkehr von A zugestellt (fstate leer)")
    check(wait(lambda: "waehrend A weg ist" in anna.texts(cid_a), 30), "Anna hat sie")
    # --- Sperren: Carl (Heim A) beginnt, Rita nimmt an, Carl sperrt Rita -> Rita sieht "nicht zugestellt" ---------------
    cc = carl.run(carl.st.new_chat, {"kind": "dm", "handle": rita.handle})
    rcc = wait(lambda: rita.chat(cc["ref"]), 10)
    rita.api("POST", f"/api/chats/{rcc['id']}/request", {"action": "accept"})
    res = carl.run(carl.st.answer_request, cc["id"], "block", "person")
    blocks = carl.run(carl.st.load_blocks)
    check(not isinstance(res, Exception) and isinstance(blocks, list) and len(blocks) == 1 and blocks[0].get("kind") == "person",
          f"Carl sperrt Rita (Sperrliste {[(b.get('label'), b.get('kind')) for b in blocks or []] if isinstance(blocks, list) else blocks})")
    try:
        rita.send(rcc, "nach der Sperre")
        rej = None
    except urllib.error.HTTPError as e:
        rej = e.code
    check(rej == 409, f"Heim weist Nachrichten der gesperrten Person ab (HTTP {rej})")
    res = carl.run(carl.st.unblock, blocks[0]["target"]) if isinstance(blocks, list) and blocks else "keine"
    check(not isinstance(res, Exception) and carl.run(carl.st.load_blocks) == [], "Entsperren")
    # --- Anrufe saga-call/v2 -------------------------------------------------------------------------------------------
    from hlidskjalf.calls import Calls
    cs = Calls()
    rang = []
    cs.connect("ring", lambda _o, r: rang.append(r))
    # Anfrage klingelt nie: Carl (A) beginnt Chat mit Bert, ruft an -> 409
    c2 = carl.run(carl.st.new_chat, {"kind": "dm", "handle": bert.st.my_handle})
    carl.use()
    eb = carl.call(carl.st.fed_keys, [bert.st.my_handle], True)[bert.st.my_handle]
    sig = _signals_for(carl, eb, "audio")
    try:
        client.json("POST", "api/calls", {"id": sig[0], "chat_id": c2["id"], "media": "audio", "device": carl.st.device_id, "signals": sig[1]})
        code = 200
    except APIError as e:
        code = e.code
    check(code == 409, f"Anruf an eine Nachrichtenanfrage: abgewiesen (HTTP {code})")
    # angenommener Chat: Anna ruft Bert an -> klingelt bei Bert (Linux: CallState prueft GET /api/calls/<id>)
    anna.use()
    eb = anna.call(anna.st.fed_keys, [bert.st.my_handle], True)[bert.st.my_handle]
    sig = _signals_for(anna, eb, "video")
    r = client.json("POST", "api/calls", {"id": sig[0], "chat_id": cid_a, "media": "video", "device": anna.st.device_id, "signals": sig[1]})
    check((r.get("call") or {}).get("state") == "ringing" and (r.get("call") or {}).get("v") == 2, "Anna ruft Bert an (saga-call/v2)")
    ring = bert.event("call.ring", lambda d: d.get("call_id") == sig[0], timeout=10)
    check(ring is not None, "Bert: Live call.ring")
    bert.use()
    cs.on_live("call.ring", ring or {})
    pump(lambda: rang, 8)
    check(rang and rang[0]["id"] == sig[0] and rang[0]["media"] == "video" and (rang[0]["peer"] or {}).get("name") == "Anna Berg",
          "Linux-App klingelt (Calls.check -> ring, Name der Anruferin)")
    d = client.get(f"api/calls/{sig[0]}?device={bert.st.device_id}")
    body, sg = C.open_signal_v2(d["signals"][0]["sealed"], bert.st.keys["device"]["enc_pk"], bert.st.keys["device"]["enc_sk"])
    check(d["call"]["state"] == "ringing" and d["call"].get("role") == "callee" and C.verify_signal_v2(
        sig[0], anna.st.my_handle, anna.st.device_id, bert.st.my_handle, bert.st.device_id, body, sg, anna.st.keys["device"]["sign_pk"]),
        "Bert: Angebot mit Bert-Linux-Schluessel geoeffnet, Signatur (Handles) gueltig")
    check(d["call"]["peer"].get("name") == "Anna Berg", "Anrufer-Name fuer die Klingel-Leiste")
    bert.use()
    cs.decline(sig[0])
    pump(None, 2)
    anna.use()
    check(wait(lambda: client.get(f"api/calls/{sig[0]}")["call"]["state"] == "ended", 10), "Bert lehnt ab -> bei Anna beendet")
    ce = wait(lambda: [m for m in anna.fresh_msgs(cid_a) if m.get("state") == "call"], 10)
    check(ce and ce[-1]["call"]["from"] == anna.st.uid, "Anruf-Eintrag im Chat: eigener Anruf als 'von mir' erkannt")
    # --- Lokaler Chat (v1) daneben unveraendert -------------------------------------------------------------------------
    lc = anna.run(anna.st.new_chat, {"kind": "dm", "user": "fa-carl"})
    r = anna.call(anna.send, lc["id"], "lokal v1")
    check(lc.get("v") != 2 and isinstance(r, tuple) and wait(lambda: carl.texts(lc["id"]) == ["lokal v1"], 10), "lokaler Chat v1 wie bisher")
    for p in (anna, bert, carl):
        p.stop = True
    print(f"\nREALFED {sum(RES)}/{len(RES)} OK – " + ("ALLES GRUEN" if all(RES) else "FEHLER"), flush=True)
    return all(RES)


def _signals_for(p, entry, media):
    cid = C.hexid()
    body = json.dumps({"t": "offer", "sdp": "v=0", "media": media, "fp": []})
    k = entry["keys"]                                     # gepruefte Schluessel aus ChatState.fed_keys
    sig = {d["id"]: C.seal_signal_v2(cid, p.st.my_handle, p.st.keys["device"], k["handle"], d["id"], C.unb64(d["enc_pub"], 32), body)
           for d in k["devices"] if not d["revoked"]}
    return cid, sig


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
