# KI-Persona und "KI im Chat" (Saga 2.1) - Bildschirmfotos gegen einen ECHTEN Testserver (nicht im normalen Testlauf).
# Vorher tests/realki.py laufen lassen (legt den Chat an und schreibt den Wiederherstellungsschluessel von A), dann:
#   HK_TEST=tests/realkishots.py mit Datei /w/realki-shots.json {"base", "users", "rk", "cid"} (Pfad: HK_REALKI_CFG)
# Dateien: linux-ki-NN-name[-es].png in HK_OUT. Zugangsdaten/Schluessel werden nie ausgegeben.
import http.cookies
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

from gi.repository import Adw

CFG = json.load(open(os.environ.get("HK_REALKI_CFG") or "/w/realki-shots.json", encoding="utf-8"))
P = os.environ.get("HK_SHOT_PREFIX", "")
S = {}


def shoot(g, name, widget=None):
    g["shot"](os.path.join(g["out"], f"linux-ki-{name}{'-es' if P.startswith('es') else ''}.png"), widget)


class _NoRedir(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def _login(base, user, pw):
    op = urllib.request.build_opener(_NoRedir)
    jar = {}

    def take(h):
        for raw in h.get_all("Set-Cookie") or []:
            c = http.cookies.SimpleCookie()
            c.load(raw)
            for k, m in c.items():
                jar[k] = m.value

    def hdr():
        return {"Cookie": "; ".join(f"{k}={v}" for k, v in jar.items()), "User-Agent": "realkishots"}
    r = op.open(urllib.request.Request(base + "/login", headers=hdr()), timeout=15)
    take(r.headers)
    csrf = re.search(r'name="csrf" value="([^"]+)"', r.read().decode()).group(1)
    data = urllib.parse.urlencode({"username": user, "password": pw, "csrf": csrf}).encode()
    try:
        r = op.open(urllib.request.Request(base + "/login", data=data, headers=hdr()), timeout=15)
        take(r.headers)
    except urllib.error.HTTPError as e:
        take(e.headers)
    return jar


def start(g):
    from hlidskjalf import config
    from hlidskjalf.api import client
    win = g["win"]
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_LIGHT)
    win.set_default_size(1200, 820)
    base = CFG["base"].rstrip("/")
    users = dict(x.strip().split("=", 1) for x in open(CFG["users"], encoding="utf-8") if "=" in x)
    user = sorted(users)[0]                                     # app-linux
    jar = _login(base, user, users[user])
    config.settings.server = base
    client.set_cookies(base, jar)
    g["store"].needs_login = False
    win.stack.set_visible_child_name("main")
    win._session_started()
    from hlidskjalf.util import bg
    title = "KI-Test: Gartenplan" if not P else "Prueba IA: plan del jardin"

    def made(j):
        S["pid"] = int(j["id"])
        g["store"].refresh()
    bg(client.json, "POST", "api/notes", {"title": title}, done=made, error=lambda e: print("FEHLER Seite", e, flush=True))


def unlock(g):
    from hlidskjalf.assistant import ai
    from hlidskjalf.chat import chat
    from hlidskjalf.util import bg
    ai.refresh(lambda: print("KI", {k: ai.status.get(k) for k in ("enabled", "chat")}, flush=True))
    from hlidskjalf.api import client
    rk = open(CFG["rk"], encoding="utf-8").read().strip()
    cid = int(CFG["cid"])

    def tidy(k):
        """Reste frueherer Laeufe (eigene Testnachrichten) entfernen, KI im Chat erlauben"""
        chat.keys = k
        j = client.get(f"api/chats/{cid}/messages?device={k['device']['id']}&limit=100")
        gone = set()
        for m in j.get("messages") or []:
            d = chat._decode(cid, m, j.get("devices"), j.get("idents"))
            if d["mine"] and (d["text"].startswith(("Treffen klappt.", "Nos vemos.")) or d.get("reply_to") in gone):
                client.request("DELETE", f"api/chats/{cid}/messages/{d['id']}")
                gone.add(d["id"])
        client.json("PUT", f"api/chats/{cid}/ai", {"allowed": True})
        return k

    def work():
        return tidy(chat.unlock(rk))
    bg(work, done=chat.adopt_keys, error=lambda e: print("FEHLER unlock", e, flush=True))


def open_chat(g):
    from hlidskjalf.chat import chat
    print("Chat-Status", chat.status, flush=True)
    g["win"].show_route(("chat", int(CFG["cid"])))


def shot_chat(g):
    shoot(g, "01-chat-hinweis-antwort")


def type_at(g):
    conv = g["win"].current.conv
    S["conv"] = conv
    buf = conv.entry.get_buffer()
    buf.set_text("Treffen klappt. @Ma" if not P else "Nos vemos. @Ma")
    buf.place_cursor(buf.get_end_iter())


def shot_suggest(g):
    shoot(g, "02-vorschlag-at")
    S["conv"]._ai_apply()
    S["conv"].entry.get_buffer().insert_at_cursor("welcher Zug passt am besten?" if not P else "¿qué tren nos viene mejor?")


def shot_hint(g):
    shoot(g, "03-hinweis-geht-an")
    S["conv"].send()


def shot_pending(g):
    shoot(g, "04-antwort-laeuft")


def fake_thinking(g):
    """Darstellung "Marin denkt nach …" (Platzhalter wie waehrend einer laufenden Frage; das Testmodell antwortet zu schnell)"""
    from hlidskjalf.chat import chat
    cid = int(CFG["cid"])
    chat.ai_pending[cid] = {"text": "", "q_msg": None, "stream": None}
    chat.emit("ai-pending", cid)


def shot_thinking(g):
    from hlidskjalf.chat import chat
    shoot(g, "04b-denkt-nach")
    cid = int(CFG["cid"])
    chat.ai_pending.pop(cid, None)
    chat.emit("ai-pending", cid)


def shot_reply(g):
    shoot(g, "05-antwort-gefragt-von-dir")


def toggle_off(g):
    S["conv"]._ai_toggle()                                     # aus (ohne Rueckfrage)


def shot_off(g):
    shoot(g, "06-ki-aus")
    S["conv"]._ai_toggle()                                     # wieder an: Rueckfrage


def shot_confirm(g):
    shoot(g, "07-erlauben-rueckfrage")
    d = g["win"].get_visible_dialog()
    if d is not None:
        d.emit("response", "ok")
        d.force_close()


def open_page(g):
    if S.get("pid"):
        g["win"].show_page(S["pid"])


def shot_page(g):
    shoot(g, "08-seite-frage-marin")
    g["win"].assistant()


def ask(g):
    d = g["win"].get_visible_dialog()
    S["dlg"] = d
    if d is not None and hasattr(d, "q"):
        d.q.get_buffer().set_text("Was steht auf meinen Seiten zum Garten?" if not P else "¿Qué dicen mis páginas sobre el jardín?")
        d.ask()


def shot_assistant(g):
    shoot(g, "09-assistent-antwort-mit-bild")
    d = g["win"].get_visible_dialog()
    if d is not None:
        d.force_close()


def cleanup(g):
    """Testseite endgueltig loeschen (Papierkorb, dann endgueltig)"""
    from hlidskjalf.api import client
    pid = S.get("pid")
    if not pid:
        return
    g["win"].show_root(("today",))
    try:
        client.request("DELETE", f"api/notes/{pid}")
        client.request("DELETE", f"api/trash/{pid}")
        print("Testseite geloescht", flush=True)
    except Exception as e:  # noqa: BLE001
        print("FEHLER Testseite nicht geloescht:", e, flush=True)


steps = [(500, start), (4000, unlock), (4000, open_chat), (3500, shot_chat), (300, type_at), (600, shot_suggest),
         (600, shot_hint), (1500, shot_pending), (14000, shot_reply),
         (300, fake_thinking), (400, shot_thinking), (300, toggle_off), (2500, shot_off), (1200, shot_confirm),
         (2000, open_page), (3500, shot_page), (1200, ask), (14000, shot_assistant), (500, cleanup)]
