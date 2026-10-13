# Server-Adresse und Geraete-Schluessel (auch Uebernahme aus alter App-ID/Adresse) + Anmeldeseite mit Server und Kopplungslink
from hlidskjalf.config import settings, get_device_token


def s1(g):
    win = g["win"]
    print("Server:", settings.server, "Schluessel:", bool(get_device_token()), flush=True)
    print("Ansicht:", win.stack.get_visible_child_name(), "Seiten:", len(g["store"].pages), flush=True)
    g["shot"](g["out"] + "/01-start.png")


def s2(g):
    win = g["win"]
    win.stack.set_visible_child_name("login")
    print("Login-Feld:", win.login_server.get_text(), flush=True)


def s3(g):
    g["shot"](g["out"] + "/02-login.png")
    win = g["win"]
    win.pair_link((settings.server or "https://saga.example.org") + "/app/pair?c=" + "x" * 32)


def s4(g):
    print("Meldung nach falschem Code:", g["win"].login_msg.get_label(), flush=True)
    g["win"].stack.set_visible_child_name("main")


steps = [(4000, s1), (500, s2), (800, s3), (2500, s4)]
