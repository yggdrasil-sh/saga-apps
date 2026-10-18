# Einstellungen -> Allgemein -> Sprache: Auswahl Automatisch/Deutsch/English/Español, Neustart-Hinweis, Speichern in settings.json
#   im Container: sh tests/run-container.sh lang   (Bildschirmfotos im Teil "shots")
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mockserver  # noqa: E402

from hlidskjalf import i18n  # noqa: E402
from hlidskjalf.config import settings  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
LANG = (os.environ.get("LANG") or "C")[:2]
S = {"err": []}


def check(name, got, want):
    ok = got == want
    print(f"{'OK  ' if ok else 'FEHLER'} {name}: {got!r} (erwartet {want!r})", flush=True)
    if not ok:
        S["err"].append(name)


def shoot(g, name):
    g["shot"](os.path.join(g["out"], f"{P}{name}.png"))


def start(g):
    S["srv"] = mockserver.install()
    g["store"].logged_in()
    g["win"].show_root(("today",))
    check("Ausgangslage (keine Wahl)", settings.get("language"), None)
    g["win"].settings_dialog()


def opened(g):
    d = g["win"].get_visible_dialog()
    S["d"] = d
    check("Zeile Sprache vorhanden", hasattr(d, "lang"), True)
    check("Auswahl Automatisch", d.lang.get_selected(), 0)
    check("Titel", d.lang.get_title(), i18n._("App language"))
    if LANG == "de":
        check("Titel uebersetzt", d.lang.get_title(), "App-Sprache")
    shoot(g, "60-sprache-einstellung")
    d.lang.set_selected(3)                                   # Español


def chosen(g):
    d = S["d"]
    check("gespeichert", settings.get("language"), "es")
    check("beim naechsten Start gelesen", i18n.chosen_language(), "es")
    check("Hinweis in der Zeile", d.lang.get_subtitle(), i18n._("Takes effect after restarting Saga"))
    ld = getattr(d, "lang_dialog", None)
    check("Neustart-Rueckfrage", ld is not None, True)
    if ld is not None:
        check("Rueckfrage Titel", ld.get_heading(), i18n._("Restart Saga to change the language"))
        if LANG == "de":
            check("Rueckfrage uebersetzt", ld.get_heading(), "Saga neu starten, um die Sprache zu wechseln")
    shoot(g, "61-sprache-neustart")
    if ld is not None:
        ld.emit("response", "later")                         # "Spaeter": Einstellung bleibt, App laeuft weiter
        ld.force_close()


def later(g):
    d = S["d"]
    check("Einstellungen noch offen", g["win"].get_visible_dialog() is d, True)
    shoot(g, "62-sprache-spaeter")
    d.lang.set_selected(0)                                   # zurueck auf Automatisch: laufende Sprache, keine Rueckfrage
    S["ld2"] = d.lang_dialog


def back(g):
    d = S["d"]
    check("Automatisch: Eintrag entfernt", settings.get("language"), None)
    check("keine neue Rueckfrage", d.lang_dialog is S["ld2"], True)
    d.lang.set_selected(2)                                   # English -> Rueckfrage -> "Jetzt neu starten" (Test: nur Meldung)
    d.lang_dialog.emit("response", "restart")
    d.lang_dialog.force_close()


def done(g):
    check("Neustart im Test unterdrueckt", g["win"].get_application().restart_requested, False)
    check("English gespeichert", settings.get("language"), "en")
    settings.set("language", None)                           # aufraeumen
    print("SPRACHWAHL", "OK" if not S["err"] else "FEHLER " + ", ".join(S["err"]), flush=True)


steps = [(1500, start), (1200, opened), (800, chosen), (800, later), (600, back), (800, done)]
