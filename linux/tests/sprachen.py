# Sprachen: die App zeigt die Sprache aus LANG (de, es, sonst Englisch). Einmal je Sprache laufen lassen:
#   for l in en_US de_DE es_ES; do HK_LANG=$l.UTF-8 sh tools/linux-test.sh linux/tests/sprachen.py; done
# Stichprobe: Anmeldebildschirm (Titel, Knopf, Feld, Hinweise), Seitenleiste, Fenstertitel, Datum und Zahl.
import os

from hlidskjalf import i18n
from hlidskjalf.util import day_text, num_text, weekday_names

LANG = (os.environ.get("LANG") or "C")[:2]
EXPECT = {
    "en": {"button": "Sign in", "server": "Server address", "today": "Today", "desc": "Sign in with a passkey", "search": "Search pages"},
    "de": {"button": "Anmelden", "server": "Server-Adresse", "today": "Heute", "desc": "Anmelden mit Passkey", "search": "Seiten durchsuchen"},
    "es": {"button": "Iniciar sesión", "server": "Dirección del servidor", "today": "Hoy", "desc": "Inicia sesión con una llave",
           "search": "Buscar páginas"},
}.get(LANG) or {}
WRONG = {"en": ("Anmelden", "Iniciar sesión"), "de": ("Sign in", "Iniciar sesión"), "es": ("Sign in", "Anmelden")}.get(LANG, ())
S = {"err": []}


def _children(w):
    out, c = [], w.get_first_child()
    while c:
        out.append(c)
        out += _children(c)
        c = c.get_next_sibling()
    return out


def check(name, got, want):
    ok = want is not None and (got == want or (isinstance(got, str) and got.startswith(want)))
    print(f"{'OK  ' if ok else 'FEHLER'} {name}: {got!r} (erwartet {want!r})", flush=True)
    if not ok:
        S["err"].append(name)


def s1(g):
    win = g["win"]
    print("LANG:", os.environ.get("LANG"), "Katalog:", i18n.LOCALEDIR, "Sprache:", i18n.ui_language(), flush=True)
    if LANG in ("de", "es") and not i18n.LOCALEDIR:
        S["err"].append("kein Katalog gefunden")
        print("FEHLER kein Katalog (.mo) gefunden", flush=True)
    win.stack.set_visible_child_name("login")
    login = win.stack.get_child_by_name("login")
    kids = [login] + _children(login)
    btns = [c.get_label() for c in kids if c.__class__.__name__ == "Button" and c.get_label()]
    check("Knopf Anmelden", btns[0] if btns else None, EXPECT.get("button"))
    check("Feld Server", win.login_server.get_title(), EXPECT.get("server"))
    check("Beschreibung", login.get_description(), EXPECT.get("desc"))
    labels = [c.get_label() for c in kids if c.__class__.__name__ in ("Label", "Expander") and c.get_label()]
    print("Texte Anmeldung:", labels, flush=True)
    bad = [t for t in btns + labels if t in WRONG]
    if bad:
        S["err"].append("falsche Sprache")
        print("FEHLER falsche Sprache:", bad, flush=True)
    g["shot"](g["out"] + f"/sprache-{LANG}-1-anmeldung.png")


def s2(g):
    win = g["win"]
    win.stack.set_visible_child_name("main")
    win.show_root(("today",))
    check("Titel Heute", win.wtitle.get_title(), EXPECT.get("today"))
    check("Suche Seitenleiste", win.sidebar.search.get_placeholder_text(), EXPECT.get("search"))
    print("Datum 2026-10-12:", day_text("2026-10-12"), "· Wochentage:", weekday_names(), "· Zahl:", num_text(1234.5),
          "· Prozent:", num_text(42, "percent"), "· Euro:", num_text(12.5, "euro"), flush=True)


def s3(g):
    g["shot"](g["out"] + f"/sprache-{LANG}-2-heute.png")
    print("SPRACHE", LANG, "OK" if not S["err"] else "FEHLER " + ", ".join(S["err"]), flush=True)


steps = [(3000, s1), (800, s2), (1500, s3)]
