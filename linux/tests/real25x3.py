"""Pruefung gegen einen echten Saga-2.5-Server mit Chat-Stufe 3 (kein pytest): Chat-Zustand der Linux-App (chat.ChatState)
mit zwei Konten auf demselben Server (Anna, Carl):
  - Kopfzeile X-Saga-Chat: 3, Geraetefaehigkeit chat3 (Umstellauftrag), features chat3/react/edit/voice
  - Umstellung v1 -> Stufe 3: alter Chat (v1, mit Anhang) bleibt lesbar (auch der Anhang, AD mit Chat-ID), Systemzeile
    {"type": "crypto", "level": 3}, Vertrauen "ueberprueft" uebernommen (gleicher Identitaetsschluessel, Hinweis "Neue
    Darstellung"), falsche Zuordnung Benutzer -> Handle wird NICHT uebernommen, Senden in den gerade umgestellten Chat (409)
  - neuer Direkt-Chat sofort Stufe 3 (v 3, x3_ok), members[].username = Handle (kein Anmeldename)
  - Reaktionen: setzen, ersetzen, zuruecknehmen; live (chat.react) und frisch geladen, Signatur/Schluessel geprueft
  - Bearbeiten: eigener Text (live chat.edited, "bearbeitet"), fremde Nachricht -> 403, nach dem Neuladen der neueste Text
  - Sprachnachricht: Aufnahme mit audiotestsrc (kein Mikrofon), Upload saga-file/v2, beim anderen dekodierbar, Dauer passt
    HK_FED_STACKS=/pfad/stacks.json HK_X3_SRVPY=/pfad/srvpy.sh python3 tests/real25x3.py
stacks.json: {"a": url, "cookies": {"a": {"lx-anna": "saga=...", "lx-carl": "saga=..."}}}; HK_X3_SRVPY: Befehl, der Python-
Code von stdin mit der Server-Umgebung ausfuehrt (Einstellung chat_v3 schalten, Umstellauftrag laufen lassen)."""
import os
import subprocess
import sys
import time

os.environ.setdefault("HK_NO_KEYRING", "1")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import realfed as R  # noqa: E402

from gi.repository import GLib  # noqa: E402

from hlidskjalf import chatmedia, chatvideo, voice  # noqa: E402
from hlidskjalf import chatcrypto as C  # noqa: E402
from hlidskjalf.api import APIError, client  # noqa: E402
from hlidskjalf.chat import can_edit, can_react, h2u_get, is_fed, sys_text, trust2_get, trust_get, trust_verified, x3_limits, x3_ok  # noqa: E402

check, pump, Person = R.check, R.pump, R.Person
SRVPY = os.environ.get("HK_X3_SRVPY")


def srv(code):
    """Python-Code mit der Server-Umgebung (Datenbank) -> Ausgabe"""
    r = subprocess.run([SRVPY], input=code, capture_output=True, text=True, timeout=120)
    if r.returncode:
        print(r.stderr[-2000:])
    return r.stdout.strip()


def set_v3(on):
    srv(f"import auth\nauth.save_setting('chat_v3', {bool(on)})\nprint('ok')")
    time.sleep(5.6)                                     # Einstellungen sind im Server 5 s zwischengespeichert


def convert():
    return srv("from saga import chat as CH, fed_chat\nprint(CH.convert_v3(limit=100000))")


def wait(fn, sec=15):
    end = time.monotonic() + sec
    while time.monotonic() < end:
        v = fn()
        if v:
            return v
        pump(None, 0.3)
    return fn()


def by_text(p, cid, text, fresh=True):
    ms = p.fresh_msgs(cid) if fresh else p.msgs(cid)
    return next((m for m in ms if m.get("text") == text or m.get("orig_text") == text), None)


def cached(p, cid, mid):
    return next((m for m in (p.st.cache.get(cid) or {}).get("msgs") or [] if m.get("id") == mid), None)


def record(sec=2.0):
    box = {}
    r = voice.Recorder(src="audiotestsrc is-live=true wave=sine freq=523 volume=0.5")
    r.start()
    GLib.timeout_add(int(sec * 1000), lambda: (r.stop(lambda x: box.setdefault("r", x), lambda e: box.setdefault("e", e)), False)[1])
    pump(lambda: box, sec + 8)
    return box.get("r")


def main():
    if SRVPY:
        set_v3(False)                                   # erst ein Chat v1 (wie vor 2.5)
    anna, carl = Person("a", "lx-anna"), Person("a", "lx-carl")
    for p in (anna, carl):
        check(p.st.my_handle and not p.certs.get("need"), f"{p.user}: Handle-Zertifikat v2, Handle {p.st.my_handle}")

    def features():
        for p in (anna, carl):
            p.use()
            me = client.get("api/chat/me?device=" + p.st.device_id)
            check({"chat3", "react", "edit", "voice"} <= set(me.get("features") or []), f"{p.user}: features {me.get('features')}")
            p.st.me = me
        if SRVPY:
            caps = srv("from db import connect\nwith connect() as c:\n print(sorted(r[0] for r in c.execute(\"SELECT caps FROM chat_device\").fetchall()))")
            check(caps.count("chat3") >= 2, f"Geraetefaehigkeit chat3 beim Server gemerkt (X-Saga-Chat: 3 mit device) ({caps})")
    if not SRVPY:
        features()
    # --- v1-Chat, dann Umstellung ----------------------------------------------------------------------------------------
    if SRVPY:
        ch = anna.run(anna.st.new_chat, {"kind": "dm", "user": "lx-carl"})
        check(isinstance(ch, dict) and ch.get("v", 1) == 1 and not ch.get("ref"), f"Chat v1 (Stufe 3 aus) ({(ch or {}).get('v')})")
        cid = ch["id"]
        r = anna.call(anna.send, cid, "Alte Nachricht v1", files=[("alt.txt", "text/plain", "Anhang v1 äöü".encode())])
        check(isinstance(r, tuple), f"Anna sendet v1 mit Anhang ({r if not isinstance(r, tuple) else 'ok'})")
        carl.load()
        cc = carl.st.chat(cid)
        mem = next((m for m in cc.get("members") or [] if not m.get("me")), {})
        check(mem.get("handle") == anna.st.my_handle and mem.get("username") == "lx-anna",
              f"v1: members[].handle fuer Clients der Stufe 3 ({mem.get('handle')})")
        check(h2u_get(carl.st.uid, anna.st.my_handle) == "lx-anna", "Carl merkt sich Handle -> Benutzer (chat-h2u.json)")
        m0 = by_text(carl, cid, "Alte Nachricht v1")
        check(m0 and m0.get("state") == "ok", "Carl liest v1 (echt)")
        tv1 = trust_get(carl.st.uid, "lx-anna")
        trust_verified(carl.st.uid, "lx-anna", tv1.get("ident_pub"))           # Carl hat Annas Nummer verglichen
        anna.load()
        trust_verified(anna.st.uid, "lx-carl", "AAAA" + (trust_get(anna.st.uid, "lx-carl").get("ident_pub") or "")[4:])  # falscher Stand
        set_v3(True)
        features()                                      # Anfragen mit device: der Server merkt sich chat3 am Geraet
        n = convert().splitlines()[-1:]
        check(n and n[0].isdigit() and int(n[0]) >= 1, f"Umstellauftrag: {n} Chat(s) auf Stufe 3")
        carl.load()
        cc = carl.st.chat(cid)
        check(cc and cc.get("v") == 3 and cc.get("ref") and isinstance(cc.get("v3"), dict), f"Carl: Chat jetzt v 3 ({(cc or {}).get('v')}, v3 {cc.get('v3')})")
        ms = carl.fresh_msgs(cid)
        m0 = next((m for m in ms if m.get("text") == "Alte Nachricht v1"), None)
        check(m0 and m0.get("state") == "ok" and not m0.get("sender_handle"), "Altbestand: v1-Nachricht weiter echt (v1 geprueft)")
        f0 = (m0 or {}).get("files") or []
        got = carl.call(carl.st.fetch_file, cid, f0[0]) if f0 else None
        check(got == "Anhang v1 äöü".encode(), f"Altbestand: Anhang v1 (AD mit Chat-ID) lesbar ({str(got)[:60]})")
        sl = next((m for m in ms if m.get("state") == "sys"), None)
        check(sl and (sl.get("sys") or {}).get("type") == "crypto" and "stable Saga addresses" in sys_text(sl["sys"], cc.get("my_handle")),
              f"Systemzeile Umstellung ({(sl or {}).get('sys')})")
        carl.use()
        fk = carl.st.fed_load(cc)
        fa = fk.get(anna.st.my_handle) or {}
        check(fa.get("trust") == "trust" and (fa.get("stored") or {}).get("verified") and (fa.get("stored") or {}).get("migrated")
              and carl.st.fed_verified(anna.st.my_handle), f"Carl: 'ueberprueft' uebernommen (gleicher Schluessel) ({fa.get('trust')}, {fa.get('stored')})")
        anna.load()
        anna.use()
        fk = anna.st.fed_load(anna.st.chat(cid))
        fc = fk.get(carl.st.my_handle) or {}
        check(fc.get("trust") == "block" and not trust2_get(anna.st.uid, carl.st.my_handle),
              f"Anna: anderer Schluessel als im v1-Stand (ueberprueft) -> gesperrt, nichts uebernommen ({fc.get('trust')})")
        anna.st.fed_verify(carl.st.my_handle, fc["keys"]["ident_pub"], True)             # Anna vergleicht neu
        # Senden v1 in den gerade umgestellten Chat: 409 chat_changed -> v2
        carl.use()
        old = dict(cc, v=1, ref=None)
        carl.st.chats = [old if c.get("id") == cid else c for c in carl.st.chats]
        r = carl.call(carl.send, cid, "Nach der Umstellung")
        check(isinstance(r, tuple), f"Carl sendet mit veraltetem Chat (v1): 409 chat_changed -> neu als v2 ({r if not isinstance(r, tuple) else 'ok'})")
        pump(None, 1.0)
        m1 = by_text(anna, cid, "Nach der Umstellung")
        check(m1 and m1.get("state") == "ok" and m1.get("sender_handle") == carl.st.my_handle, "Anna: neue Nachricht im Format v2, echt")
    # --- neuer Chat: sofort Stufe 3 ---------------------------------------------------------------------------------------
    anna.use()
    # vorhandenen Direkt-Chat (umgestellt) nutzen bzw. neu anlegen
    ch = anna.run(anna.st.new_chat, {"kind": "dm", "user": "lx-carl"})
    check(isinstance(ch, dict) and ch.get("v") == 3 and ch.get("x3_ok") is True, f"Direkt-Chat Stufe 3 mit x3_ok ({(ch or {}).get('v')}, {(ch or {}).get('x3_ok')})")
    cid = ch["id"]
    peer = next((m for m in ch.get("members") or [] if not m.get("me")), {})
    check(peer.get("username") == carl.st.my_handle and "lx-carl" not in str(ch), "members[].username = Handle, kein Anmeldename im Chat-Objekt")
    r = anna.call(anna.send, cid, "Reagierst du?")
    check(isinstance(r, tuple), "Anna sendet (v2-Format in Stufe 3)")
    carl.load()
    m = by_text(carl, cid, "Reagierst du?")
    check(m and m.get("state") == "ok" and m.get("_k") and can_react(carl.st.chat(cid), m), "Carl: echt, Schluessel K bekannt, Reagieren moeglich")
    # --- Reaktionen -------------------------------------------------------------------------------------------------------
    carl.use()
    ok = carl.st.react(cid, m["id"], "👍", error=lambda t: print("  Fehler", t))
    check(ok and cached(carl, cid, m["id"])["rx"] == [{"by": carl.st.my_handle, "e": "👍", "ts": cached(carl, cid, m["id"])["rx"][0]["ts"]}],
          "Carl: 👍 sofort angezeigt")
    pump(lambda: (cached(carl, cid, m["id"]) or {}).get("rx_raw"), 6)
    ev = anna.event("chat.react", lambda d: d.get("chat_id") == cid, timeout=8)
    check(ev and (ev.get("items") or [{}])[0].get("by") == carl.st.my_handle and "e" not in ev["items"][0], f"Anna: Live chat.react (ohne Inhalt) ({ev})")
    am = by_text(anna, cid, "Reagierst du?")
    check(am and am.get("rx") == [{"by": carl.st.my_handle, "e": "👍", "ts": am["rx"][0]["ts"]}] if am and am.get("rx") else False,
          f"Anna: Reaktion geprueft und geoeffnet ({(am or {}).get('rx')})")
    # Live-Weg in einen geladenen Chat
    anna.use()
    anna.st.on_live("chat.react", ev)
    pump(None, 1.5)
    carl.use()
    carl.st.react(cid, m["id"], "❤️")
    pump(None, 0.2)
    carl.st.react(cid, m["id"], "😂")                    # innerhalb von 500 ms: nur die letzte Wahl geht raus
    pump(lambda: (cached(carl, cid, m["id"]) or {}).get("rx_raw") and not carl.st._rx_timers, 6)
    pump(None, 1.0)
    am = by_text(anna, cid, "Reagierst du?")
    check(am and [x["e"] for x in am.get("rx") or []] == ["😂"], f"ersetzt (gebuendelt, nur die letzte Wahl): {(am or {}).get('rx')}")
    anna.use()
    anna.st.react(cid, am["id"], "🙏")
    pump(lambda: not anna.st._rx_timers, 3)
    pump(None, 1.5)
    cm = by_text(carl, cid, "Reagierst du?")
    check(cm and sorted(x["e"] for x in cm.get("rx") or []) == ["😂", "🙏"], f"Carl sieht beide (je Person eine): {(cm or {}).get('rx')}")
    carl.use()
    carl.st.react(cid, m["id"], "")
    pump(lambda: not carl.st._rx_timers, 3)
    pump(None, 1.5)
    am = by_text(anna, cid, "Reagierst du?")
    check(am and [x["e"] for x in am.get("rx") or []] == ["🙏"], f"zurueckgenommen: {(am or {}).get('rx')}")
    # gefaelschte Reaktion (Signatur eines anderen Geraets) wird nicht angezeigt
    carl.use()
    bad = C.encrypt_reaction(m["_k"], ch["ref"], m["id"], carl.st.my_handle, dict(carl.st.keys["device"], sign_sk=C.device()["sign_sk"]), "💩")
    try:
        client.json("PUT", f"api/chats/{cid}/messages/{m['id']}/reaction", {k: bad[k] for k in ("dev", "ts", "ct", "sig")})
        srv_ok = True
    except APIError as e:
        srv_ok = e.code
    check(srv_ok == 400, f"Server prueft die Signatur: falsch signierte Reaktion -> 400 ({srv_ok})")
    # --- Bearbeiten -------------------------------------------------------------------------------------------------------
    anna.use()
    am = by_text(anna, cid, "Reagierst du?")
    check(can_edit(anna.st.chat(cid), am, lim=x3_limits(anna.st.me)), "Anna darf ihre Nachricht bearbeiten")
    res = anna.run(anna.st.send_edit, cid, am["id"], "Reagierst du? (bearbeitet)")
    check(not isinstance(res, Exception) and cached(anna, cid, am["id"]).get("text") == "Reagierst du? (bearbeitet)"
          and cached(anna, cid, am["id"]).get("edited_at"), f"Anna bearbeitet (sofort sichtbar) ({res})")
    ev = carl.event("chat.edited", lambda d: d.get("id") == am["id"], timeout=8)
    check(ev and ev.get("chat_id") == cid, f"Carl: Live chat.edited ({ev})")
    carl.use()
    carl.msgs(cid)
    carl.st.on_live("chat.edited", ev or {})
    pump(lambda: (cached(carl, cid, am["id"]) or {}).get("edited_at"), 6)
    cm = cached(carl, cid, am["id"])
    check(cm and cm.get("text") == "Reagierst du? (bearbeitet)" and cm.get("orig_text") == "Reagierst du?" and cm.get("edit_n") == 1,
          f"Carl: neuester Text nach dem Live-Ereignis ({(cm or {}).get('text')}, n {(cm or {}).get('edit_n')})")
    cm = by_text(carl, cid, "Reagierst du? (bearbeitet)")
    check(cm and cm.get("edited_at") and cm.get("state") == "ok" and [x["e"] for x in cm.get("rx") or []] == ["🙏"],
          "frisch geladen: bearbeitet, Reaktion bleibt")
    check(not can_edit(carl.st.chat(cid), cm), "Carl darf Annas Nachricht nicht bearbeiten")
    carl.use()
    try:
        carl.st.send_v2(carl.st.chat(cid), {"edit": cm["id"], "t": "untergeschoben"}, kind="edit", extra={"edit_of": cm["id"]})
        e403 = None
    except Exception as e:  # noqa: BLE001
        e403 = getattr(e, "code", e)
    check(e403 == 403, f"fremde Nachricht bearbeiten -> 403 ({e403})")
    # --- Sprachnachricht --------------------------------------------------------------------------------------------------
    res = record(2.2)
    check(res and voice.ogg_opus_info(res["data"]) and 1.8 < res["dur"] < 2.8 and len(res["wf"]) == 64,
          f"Aufnahme mit audiotestsrc: Ogg/Opus, {res and round(res['dur'], 2)} s, {res and len(res['data'])} B")
    anna.use()
    r = anna.call(anna.st.send_voice, cid, res["data"], res["dur"], res["wf"])
    check(isinstance(r, tuple), f"Sprachnachricht gesendet (saga-file/v2) ({r if not isinstance(r, tuple) else 'ok'})")
    carl.use()
    ms = carl.fresh_msgs(cid)
    vm = next((x for x in ms if any(f.get("voice") for f in x.get("files") or [])), None)
    vf = next((f for f in (vm or {}).get("files") or [] if f.get("voice")), {})
    check(vf.get("x") == 2 and vf.get("m") == "audio/ogg; codecs=opus" and abs(float(vf.get("dur") or 0) - res["dur"]) < 0.11
          and len(C.unb64(vf.get("wf") or "")) == 64, f"Carl: Anhang voice/dur/wf ({ {k: vf.get(k) for k in ('n', 'm', 's', 'dur', 'x')} })")
    src = chatvideo.VideoSource(cid, vf)
    data = b"".join(src.iter_plain(0, src.size - 1))
    check(data == res["data"], "Carl: Datei Block fuer Block entschluesselt = Aufnahme")
    d = voice.probe_duration(data)
    check(d and abs(d - float(vf["dur"])) < 0.15, f"Carl: mit GStreamer dekodiert, Dauer {d} ~ dur {vf.get('dur')}")
    check(not can_edit(anna.st.chat(cid), next((x for x in anna.fresh_msgs(cid) if x.get("id") == vm.get("id")), {})),
          "Sprachnachricht nicht bearbeitbar")
    anna.stop = carl.stop = True
    n = len(R.RES)
    ok = sum(R.RES)
    print(f"\nREAL25X3 {ok}/{n} - {'ALLES GRUEN' if ok == n else 'FEHLER'}")
    _ = (chatmedia, is_fed, x3_ok)
    return ok == n


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
