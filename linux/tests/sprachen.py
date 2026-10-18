# Sprachen: die App zeigt die Sprache aus LANG (de, es, ru, th, sonst Englisch). Einmal je Sprache laufen lassen:
#   for l in en_US de_DE es_ES ru_RU th_TH; do HK_LANG=$l.UTF-8 sh tools/linux-test.sh linux/tests/sprachen.py; done
# Stichprobe: Anmeldebildschirm (Titel, Knopf, Feld, Hinweise), Seitenleiste, Fenstertitel, Datum und Zahl.
import os

from hlidskjalf import i18n
from hlidskjalf.util import day_text, num_text, weekday_names

# HK_APP_LANGUAGE: in den App-Einstellungen gewaehlte Sprache (run-container.sh legt settings.json an) - hat Vorrang vor LANG
APP_LANG = os.environ.get("HK_APP_LANGUAGE") or ""
LANG = APP_LANG or (os.environ.get("LANG") or "C")[:2]
TAG = f"{LANG}-app" if APP_LANG else LANG
EXPECT = {
    "en": {"button": "Sign in", "server": "Server address", "today": "Today", "desc": "Sign in with a passkey", "search": "Search pages"},
    "de": {"button": "Anmelden", "server": "Server-Adresse", "today": "Heute", "desc": "Anmelden mit Passkey", "search": "Seiten durchsuchen"},
    "es": {"button": "Iniciar sesión", "server": "Dirección del servidor", "today": "Hoy", "desc": "Inicia sesión con una llave",
           "search": "Buscar páginas"},
    "ru": {"button": "Войти", "server": "Адрес сервера", "today": "Сегодня", "desc": "Войди с помощью ключа доступа",
           "search": "Поиск страниц"},
    "th": {"button": "เข้าสู่ระบบ", "server": "ที่อยู่เซิร์ฟเวอร์", "today": "วันนี้", "desc": "เข้าสู่ระบบด้วยพาสคีย์",
           "search": "ค้นหาหน้า"},
}.get(LANG) or {}
BUTTONS = {"en": "Sign in", "de": "Anmelden", "es": "Iniciar sesión", "ru": "Войти", "th": "เข้าสู่ระบบ"}
WRONG = tuple(v for k, v in BUTTONS.items() if k != LANG) if LANG in BUTTONS else ()
CATALOGS = ("de", "es", "ru", "th")                  # Sprachen mit Katalog (po/LINGUAS)
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
    print("LANG:", os.environ.get("LANG"), "App-Sprache:", APP_LANG or "-", "LANGUAGE:", os.environ.get("LANGUAGE"),
          "Katalog:", i18n.LOCALEDIR, "Sprache:", i18n.ui_language(), flush=True)
    from hlidskjalf import api
    check("Accept-Language", api.ACCEPT_LANGUAGE, LANG if LANG in CATALOGS else "en")
    if APP_LANG:
        check("LANGUAGE (GTK)", os.environ.get("LANGUAGE"), APP_LANG)
        check("Einstellung", i18n.chosen_language(), APP_LANG)
    if LANG in CATALOGS and not i18n.LOCALEDIR:
        S["err"].append("kein Katalog gefunden")
        print("FEHLER kein Katalog (.mo) gefunden", flush=True)
    win.stack.set_visible_child_name("login")
    login = getattr(win, "login_status", None) or win.stack.get_child_by_name("login")
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
    g["shot"](g["out"] + f"/sprache-{TAG}-1-anmeldung.png")


def s2(g):
    win = g["win"]
    win.stack.set_visible_child_name("main")
    win.show_root(("today",))
    check("Titel Heute", win.wtitle.get_title(), EXPECT.get("today"))
    check("Suche Seitenleiste", win.sidebar.search.get_placeholder_text(), EXPECT.get("search"))
    print("Datum 2026-10-12:", day_text("2026-10-12"), "· Wochentage:", weekday_names(), "· Zahl:", num_text(1234.5),
          "· Prozent:", num_text(42, "percent"), "· Euro:", num_text(12.5, "euro"), flush=True)
    # Datum mit Jahr: immer gregorianisch (Thai: 2025, nicht 2568 nach buddhistischer Zeitrechnung)
    d = day_text("2025-10-12")
    check("Jahr gregorianisch", "2025" in d and "2568" not in d, True)


def s3(g):
    g["shot"](g["out"] + f"/sprache-{TAG}-2-heute.png")
    print("SPRACHE", LANG, "OK" if not S["err"] else "FEHLER " + ", ".join(S["err"]), flush=True)


steps = [(3000, s1), (800, s2), (1500, s3)]
