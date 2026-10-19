"""Pruefung gegen DREI echte Saga-2.3-Server (kein pytest): freie Gruppen und Seiten-Chats ueber Servergrenzen
(docs/APP-API-FED.md Abschnitt 9, Foederation E5) mit dem Chat-Zustand der Linux-App (chat.ChatState). Anna und Carl auf A,
Bert und Cleo auf B, Dora auf C; A ist mit B und C verbunden, B und C kennen sich NICHT (Schluessel ueber das Heim):
  - Gruppe mit Handles anlegen (Heim A), Name als v2-Nachricht kind 'title' - bei B und C entschluesselt und geprueft
  - Einladung als Nachrichtenanfrage (invited_by), Annehmen; Senden/Empfangen v2 ueber drei Server, Echtheit (auch C<->B)
  - Systemzeilen members (Herkunft), Mitglied per Handle hinzufuegen, Verlassen
  - Seiten-Chat: vorher lokal (v1), nach dem Teilen mit B umgestellt (v2, gleiche ID): alte v1-Nachrichten weiter lesbar,
    Systemzeile fed, Bert oeffnet den Chat der fremden Seite (negative note_id), Nachrichten in beide Richtungen
    HK_FED_STACKS=/pfad/stacks3.json HK_FED_SERVER_TESTS=<Server-Repo>/tests python3 tests/realfed23b_chat.py"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import realfed as R  # noqa: E402 - Person, check, pump, wait (gleiche Stacks-Datei)

from hlidskjalf.chat import chat_home_hint, sys_text  # noqa: E402

ST = R.ST
check, pump = R.check, R.pump


def wait(fn, sec=20):
    end = time.monotonic() + sec
    while time.monotonic() < end:
        v = fn()
        if v:
            return v
        pump(None, 0.3)
    return fn()


def find(p, pred):
    p.load()
    return next((c for c in p.st.chats if pred(c)), None)


def sys_lines(p, cid):
    return [m.get("sys") or {} for m in p.fresh_msgs(cid) if m.get("state") == "sys"]


def main():
    anna, carl, bert, cleo, dora = (R.Person(k, u) for k, u in (("a", "fa-anna"), ("a", "fa-carl"), ("b", "fb-bert"), ("b", "fb-cleo"),
                                                                 ("c", "fc-dora")))
    check(all(p.st.my_handle for p in (anna, carl, bert, cleo, dora)), "Alle: Handle + Zertifikate v2")
    # --- Gruppe mit Personen anderer Server ---------------------------------------------------------------------------
    ch = anna.run(anna.st.new_chat, {"kind": "free", "members": ["fa-carl"], "handles": [bert.st.my_handle, dora.st.my_handle]})
    check(isinstance(ch, dict) and ch.get("v") == 2 and ch.get("home_self"), f"A: Gruppe ueber drei Server angelegt (v2, Heim A) ({str(ch)[:120]})")
    if not isinstance(ch, dict):
        return False
    cid = ch["id"]
    r = anna.call(anna.st.send, cid, {"v": 1, "type": "title", "title": "Gartenrunde"}, None, "title")
    check(not isinstance(r, Exception), f"A: Gruppenname als v2-Nachricht kind 'title' ({r})")
    anna.load()
    check(wait(lambda: (anna.st.chat(cid) or {}).get("_title") == "Gartenrunde", 15), "A: Name entschluesselt und geprueft")
    bc = wait(lambda: find(bert, lambda c: c.get("ref") == ch.get("ref")), 30)
    dc = wait(lambda: find(dora, lambda c: c.get("ref") == ch.get("ref")), 30)
    check(bc and bc.get("request") and (bc.get("invited_by") or {}).get("host") == ST["host"]["a"],
          f"B: Einladung als Nachrichtenanfrage (invited_by von A) ({(bc or {}).get('invited_by')})")
    check(dc and dc.get("request"), "C: Einladung als Nachrichtenanfrage")
    check(not chat_home_hint(bc or {}).startswith("This chat is on this server") and ST["host"]["a"] in chat_home_hint(bc or {}),
          "B: Hinweis 'Dieser Chat liegt auf A'")
    check(wait(lambda: (find(bert, lambda c: c.get("id") == bc["id"]) or {}).get("_title") == "Gartenrunde", 20),
          "B: Gruppenname v2 entschluesselt und geprueft")
    check(wait(lambda: (find(dora, lambda c: c.get("id") == dc["id"]) or {}).get("_title") == "Gartenrunde", 20),
          "C: Gruppenname v2 (Schluessel von A ueber das Heim)")
    for p, c in ((bert, bc), (dora, dc)):
        res = p.run(p.st.answer_request, c["id"], "accept")
        check(not isinstance(res, Exception), f"{p.user}: Anfrage angenommen")
    r = anna.send(cid, "Hallo Gartenrunde")
    check(isinstance(r, tuple) and not r[1], f"A: Nachricht an alle Mitglieder (ohne fehlende Schluessel) ({r})")
    check(wait(lambda: "Hallo Gartenrunde" in bert.texts(bc["id"]), 20), "B: Nachricht von A gelesen")
    check(wait(lambda: "Hallo Gartenrunde" in dora.texts(dc["id"]), 20), "C: Nachricht von A gelesen")
    m = next((x for x in dora.fresh_msgs(dc["id"]) if x.get("text") == "Hallo Gartenrunde"), {})
    check(m.get("state") == "ok", f"C: Nachricht von A echt ({m.get('state')})")
    r = dora.send(dc["id"], "Gruss von C")
    check(isinstance(r, tuple), f"C: Nachricht an die Gruppe ({r})")
    check(wait(lambda: "Gruss von C" in bert.texts(bc["id"]), 20), "B: Nachricht von C (Server ohne Verbindung) gelesen")
    m = next((x for x in bert.fresh_msgs(bc["id"]) if x.get("text") == "Gruss von C"), {})
    check(m.get("state") == "ok", f"B: Nachricht von C echt (Schluessel source 'home') ({m.get('state')})")
    check(wait(lambda: "Gruss von C" in carl.texts(cid), 20), "A: Carl liest die Nachricht von C")
    # Systemzeilen
    sl = sys_lines(bert, bc["id"])
    mem = [x for x in sl if x.get("type") == "members"]
    txt = [sys_text(x, bert.st.my_handle) for x in mem]
    check(mem and any("(" + ST["host"]["c"] + ")" in t for t in txt), f"B: Systemzeile Mitglieder mit Herkunft ({txt})")
    # Mitglied per Handle hinzufuegen, verlassen
    s = anna.call(lambda: R.client.json("POST", f"api/chats/{cid}/members", {"handle": cleo.st.my_handle}))
    check(not isinstance(s, Exception), f"A: Cleo (B) per Handle hinzugefuegt ({str(s)[:80]})")
    cc = wait(lambda: find(cleo, lambda c: c.get("ref") == ch.get("ref")), 30)
    check(cc and cc.get("request"), "B: Cleo bekommt die Einladung als Anfrage")
    sl = sys_lines(anna, cid)
    txt = [sys_text(x, anna.st.my_handle) for x in sl if x.get("type") == "members"]
    check(any(t.startswith("You added") and "Cleo" in t and ST["host"]["b"] in t for t in txt), f"A: 'Du hast Cleo (B) hinzugefuegt' ({txt})")
    me_b = next((x for x in bc.get("members") or [] if x.get("handle") == bert.st.my_handle), {})
    s = bert.call(lambda: R.client.request("DELETE", f"api/chats/{bc['id']}/members/" + R.urllib.parse.quote(me_b.get("username") or "", safe="")))
    check(not isinstance(s, Exception), f"B: Bert verlaesst die Gruppe ({str(s)[:80]})")
    check(wait(lambda: any(sys_text(x, anna.st.my_handle).startswith("Bert Gast (") and "left the group" in sys_text(x, anna.st.my_handle)
                           for x in sys_lines(anna, cid)), 20), "A: Systemzeile 'Bert (B) hat die Gruppe verlassen'")
    r = anna.send(cid, "Nach dem Verlassen")
    check(isinstance(r, tuple), f"A: Senden nach Mitgliederwechsel (aktuelle Liste vom Heim) ({r})")

    # --- Seiten-Chat: lokal (v1) -> ueber Servergrenzen (v2) ---------------------------------------------------------
    anna.use()
    P = R.client.json("POST", "api/notes", {"title": "Seite mit Chat"})["id"]
    R.client.json("PUT", f"api/notes/{P}/shares", {"type": "user", "target": "fa-carl", "role": "edit"})
    pc = anna.run(anna.st.new_chat, {"kind": "page", "note_id": P})
    check(isinstance(pc, dict) and pc.get("v") != 2, f"A: Seiten-Chat lokal (v1) ({str(pc)[:80]})")
    pcid = pc["id"]
    anna.load()
    r = anna.send(pcid, "Vor der Umstellung")
    check(not isinstance(r, Exception), f"A: v1-Nachricht im Seiten-Chat ({r})")
    anna.use()
    R.client.json("PUT", f"api/notes/{P}/shares", {"type": "remote", "target": "@bert@" + ST["host"]["b"], "role": "edit"})
    up = wait(lambda: (find(anna, lambda c: c.get("id") == pcid) or {}).get("v") == 2, 30)
    check(up, "A: Seiten-Chat nach dem Teilen mit B umgestellt (v2, gleiche ID)")
    msgs = anna.fresh_msgs(pcid)
    old = next((x for x in msgs if x.get("text") == "Vor der Umstellung"), {})
    check(old.get("state") == "ok" and not old.get("sender_handle"), f"A: alte v1-Nachricht weiter lesbar und echt ({old.get('state')})")
    check(any(x.get("type") == "fed" for x in sys_lines(anna, pcid)), "A: Systemzeile 'ab jetzt mit Personen anderer Server'")
    check(wait(lambda: "Vor der Umstellung" in carl.texts(pcid), 15), "A: Carl liest die alte v1-Nachricht weiter")
    r = anna.send(pcid, "Nach der Umstellung")
    check(isinstance(r, tuple), f"A: v2-Nachricht im umgestellten Seiten-Chat ({r})")
    # Bert oeffnet den Chat der fremden Seite
    bert.use()
    items = wait(lambda: [x for x in (R.client.get("api/fed/shared") or {}).get("items") or [] if x.get("title") == "Seite mit Chat"], 30)
    rid = items[0]["id"] if items else None
    check(rid is not None and rid < 0 and "chatgroups" in ((items[0].get("remote") or {}).get("caps") or []),
          f"B: fremde Seite mit Faehigkeit chatgroups ({rid})")
    bpc = bert.run(bert.st.new_chat, {"kind": "page", "note_id": rid}) if rid else None
    check(isinstance(bpc, dict) and bpc.get("v") == 2 and bpc.get("note_id") == rid and bpc.get("ref") == (anna.st.chat(pcid) or {}).get("ref"),
          f"B: Seiten-Chat der fremden Seite = derselbe Chat ({str(bpc)[:120]})")
    if isinstance(bpc, dict):
        bert.load()
        check(wait(lambda: "Nach der Umstellung" in bert.texts(bpc["id"]), 20), "B: v2-Nachricht im Seiten-Chat gelesen")
        r = bert.send(bpc["id"], "Antwort von B")
        check(isinstance(r, tuple), f"B: Antwort im Seiten-Chat ({r})")
        check(wait(lambda: "Antwort von B" in anna.texts(pcid), 20), "A: Antwort von B gelesen")
        m = next((x for x in anna.fresh_msgs(pcid) if x.get("text") == "Antwort von B"), {})
        check(m.get("state") == "ok", f"A: Antwort von B echt ({m.get('state')})")
    for p in (anna, carl, bert, cleo, dora):
        p.stop = True
    print(f"\nREALFED23B-CHAT {sum(R.RES)}/{len(R.RES)} OK – " + ("ALLES GRUEN" if all(R.RES) else "FEHLER"), flush=True)
    return all(R.RES)


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
