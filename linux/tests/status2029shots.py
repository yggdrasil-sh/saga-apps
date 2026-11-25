# 2.6 (Bugs-DB 2029-8): eigener Status - Bildschirmfotos mit nachgebautem Server (tests/mockstatus.py), ohne Netz.
#   HK_TEST=tests/status2029shots.py HK_OUT=<ordner> HK_SHOT_PREFIX=nachher- python3 -m hlidskjalf.main
# Laeuft auch mit dem Stand vor 2029-8 (Vorher-Bilder): fehlt etwas, entsteht das Bild trotzdem. Nur Demo-Daten.
import json
import os
import secrets
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockserver  # noqa: E402
import mockv21  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
AFTER = P.startswith("nachher")
DE = os.environ.get("LANG", "de").startswith("de")       # Text-Pruefungen nur auf Deutsch
S = {"opened": []}


def check(name, ok):
    print(f"{'OK  ' if ok else 'FEHLER'} {name}", flush=True)


def shoot(g, name):
    g["shot"](os.path.join(g["out"], f"{P}{name}.png"))


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    from gi.repository import Gtk
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label) and x.get_visible() and x.get_label()]


def _dark(on):
    from gi.repository import Adw
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK if on else Adw.ColorScheme.FORCE_LIGHT)


def _close_dialog(g):
    d = g["win"].get_visible_dialog()
    if d is not None:
        d.force_close()


def start(g):
    srv = S["srv"] = mockserver.install()
    srv.v25 = True
    srv.extra_features = ["userstatus1"]
    srv.ustatus_api = True
    srv.calls_waiting = True
    srv.calls_cfg = dict(srv.calls_cfg, features=["reneg", "multi", "waiting1"])
    from hlidskjalf import calls as CL
    CL.open_in_browser = lambda uri, parent=None: S["opened"].append(uri)    # nie einen Browser starten
    _dark(False)
    g["store"].logged_in()
    g["store"].check_version()
    g["win"].set_default_size(1100, 720)


def setup(g):
    from hlidskjalf import chatcrypto as C
    from hlidskjalf.chat import chat
    chat.refresh()
    S["rk"] = C.recovery_key()


def ready(g):
    from hlidskjalf.chat import chat
    chat.adopt_keys(chat.setup(S["rk"]))


def data(g):
    srv = S["srv"]
    for u, n in (("alex", "Alex Beispiel"), ("bruno", "Bruno Beispiel"), ("cora", "Cora Muster"), ("dora", "Dora Beispiel")):
        srv.chat_peer(u, n)
    S["alex"] = srv.new_chat("dm", ["test", "alex"])
    srv.peer_send(S["alex"], "alex", "Bin gleich im Kundentermin – melde mich danach.")
    S["bruno"] = srv.new_chat("dm", ["test", "bruno"])
    srv.peer_send(S["bruno"], "bruno", "Schickst du mir die Liste?")
    S["cora"] = srv.new_chat("dm", ["test", "cora"])
    srv.peer_send(S["cora"], "cora", "Ich ruf dich gleich zurück.")
    S["dora"] = srv.new_chat("dm", ["test", "dora"])
    srv.peer_send(S["dora"], "dora", "Danke dir!")
    end = (int(time.time() / 300) + 13) * 300.0                              # 5-Minuten-Raster wie der Server
    srv.peer_status.update({"alex": {"s": "dnd", "until": end, "text": "Im Kundentermin"}, "bruno": {"s": "busy"},
                            "cora": {"s": "in_call"}, "dora": {"s": "available"}})
    srv.online.update({"bruno": {"online": True, "last": None}, "cora": {"online": True, "last": None}})
    # verpasster Anruf waehrend "Nicht stoeren" (hold nur fuer die angerufene Person)
    m = mockv21.call_entry(srv, S["dora"], "missed", frm="dora", to="test", ago=900)
    d = json.loads(m["body_ct"])
    d["hold"] = "dnd"
    m["body_ct"] = json.dumps(d, separators=(",", ":"))
    srv.chats[S["dora"]]["msgs"][-1]["body_ct"] = m["body_ct"]
    from hlidskjalf.chat import chat
    chat.load_chats()
    g["win"].show_route(("chats",))


def shot_list(g):
    shoot(g, "01-chatliste-hell")
    if AFTER:
        from hlidskjalf.chat import chat
        check("Status der Partner geladen", (chat.ustat.get("alex") or {}).get("s") == "dnd")
    _dark(True)


def shot_list_dark(g):
    shoot(g, "02-chatliste-dunkel")
    _dark(False)
    g["win"].show_route(("chat", S["alex"]))


def shot_head(g):
    shoot(g, "03-chatkopf-hell")
    if AFTER and DE:
        check("Chip im Chat-Kopf", "Nicht stören" in (g["win"].wtitle.get_subtitle() or "") or
              any("Nicht stören" in t for t in _labels(g["win"])))
    _dark(True)


def shot_head_dark(g):
    shoot(g, "04-chatkopf-dunkel")
    _dark(False)
    g["win"].call_current()                                  # Anrufen bei "Nicht stoeren": Rueckfrage


def shot_ask(g):
    shoot(g, "05-rueckfrage-anruf")
    d = g["win"].get_visible_dialog()
    if AFTER:
        check("Rueckfrage vor dem Anruf", d is not None and not S["opened"])
    _close_dialog(g)
    S["srv"].uview.update(state="dnd", until=time.time() + 3600, text="Im Kundentermin", source="manual")
    S["srv"].uview["ver"] += 1
    try:
        from hlidskjalf.userstatus import ustatus
    except ImportError:
        return
    ustatus.load()


def open_menu(g):
    g["win"].acct_btn.emit("clicked")


def shot_menu(g):
    shoot(g, "06-status-menue")
    pop = getattr(g["win"], "acct_pop", None)
    if pop is not None and pop.get_visible():
        g["shot"](os.path.join(g["out"], f"{P}06b-status-menue-aufgeklappt.png"), pop)
    try:
        from hlidskjalf.statusview import StatusDialog
        del StatusDialog
    except ImportError:
        _close_dialog(g)
        return
    pop = getattr(g["win"], "acct_pop", None)
    if pop is not None:
        pop.popdown()


def open_status(g):
    try:
        from hlidskjalf.statusview import StatusDialog
    except ImportError:
        return
    StatusDialog(g["win"]).present(g["win"])


def shot_status(g):
    shoot(g, "07-status-setzen-hell")
    _dark(True)


def shot_status_dark(g):
    shoot(g, "08-status-setzen-dunkel")
    _dark(False)
    _close_dialog(g)
    g["win"].settings_dialog(page="reach" if AFTER else "push")


def shot_reach(g):
    shoot(g, "09-erreichbarkeit")
    _close_dialog(g)
    g["win"].show_route(("chat", S["dora"]))


def shot_missed(g):
    shoot(g, "10-verpasst-nicht-stoeren")
    if AFTER and DE:
        check("Verpasster Anruf (Nicht stoeren)", any("Nicht stören" in t for t in _labels(g["win"].current)))
    srv = S["srv"]
    # eigenes Gespraech laeuft (im Browser); Bruno klopft an
    run = secrets.token_hex(16)
    srv.calls[run] = {"id": run, "chat_id": S["cora"], "media": "audio", "state": "active", "reason": None, "role": "caller",
                      "peer": {"username": "cora", "name": "Cora Muster"}, "mine": ["b" * 32], "created": time.time() - 300,
                      "answered": time.time() - 290, "kind": "dm"}
    S["run"] = run
    cid = mockv21.ring(srv, "bruno", S["bruno"], push=False)
    srv.calls[cid]["waiting"] = True
    S["knock"] = cid
    srv.push_event("call.ring", {"call_id": cid, "chat_id": S["bruno"], "media": "audio", "from_device": "a" * 32, "ring_s": 45,
                                 "waiting": True, "from": srv.calls[cid]["peer"]})


def shot_knock(g):
    shoot(g, "11-anklopfen-hell")
    if AFTER:
        from hlidskjalf.calls import calls
        check("Anklopfen klingelt nicht", calls.ringing is None and S["knock"] in calls.holds)
    _dark(True)


def shot_knock_dark(g):
    shoot(g, "12-anklopfen-dunkel")
    _dark(False)
    srv = S["srv"]
    from hlidskjalf.calls import calls
    if getattr(calls, "holds", None) is not None:
        calls.hold_dismiss(S["knock"])
    else:
        calls._done(S["knock"])
    srv.calls[S["knock"]]["state"] = "ended"
    # zurueckgehaltener Anruf (eigenes Nicht stoeren), waehrend das eigene Gespraech woanders laeuft
    cid = mockv21.ring(srv, "alex", S["alex"], push=False)
    srv.calls[cid]["quiet"] = True
    S["quiet"] = cid
    srv.push_event("call.quiet", {"call_id": cid, "chat_id": S["alex"], "kind": "dm", "media": "audio", "from": srv.calls[cid]["peer"],
                                  "from_device": "a" * 32, "ring_s": 45, "why": "dnd"})


def shot_quiet(g):
    shoot(g, "13-stille-leiste")
    if AFTER:
        from hlidskjalf.calls import calls
        h = calls.holds.get(S["quiet"]) or {}
        check("stille Leiste ohne Annehmen (Gespraech woanders)", h.get("mode") == "quiet" and h.get("elsewhere") is True)
    srv = S["srv"]
    srv.calls[S["run"]]["state"] = "ended"
    srv.push_event("status.digest", {"messages": 4, "calls": 1, "other": 0, "since": time.time() - 3600, "until": time.time()})


def shot_digest(g):
    shoot(g, "14-sammelmeldung")


steps = [(300, start), (1500, setup), (1500, ready), (1500, data), (2500, shot_list), (900, shot_list_dark), (1800, shot_head),
         (900, shot_head_dark), (1200, shot_ask), (1200, open_menu), (1200, shot_menu), (1500, open_status), (1200, shot_status),
         (900, shot_status_dark), (2000, shot_reach), (2500, shot_missed), (2500, shot_knock), (900, shot_knock_dark),
         (2500, shot_quiet), (1500, shot_digest)]
