"""Uebersetzungen (gettext). Quelltexte sind englisch; Kataloge: po/*.po -> <localedir>/<sprache>/LC_MESSAGES/<DOMAIN>.mo.
Gesucht wird: $HK_LOCALEDIR, linux/build/locale (Start aus dem Quellordner, erzeugt von po/build-mo.sh),
<prefix>/share/locale (RPM: /usr, Flatpak: /app). Ohne Katalog laeuft die App englisch weiter."""
import gettext
import json
import locale
import os

from . import APP_ID

DOMAIN = APP_ID
HERE = os.path.dirname(os.path.abspath(__file__))

try:                                    # Datums- und Zahlenformate der Systemsprache (GLib.DateTime.format, locale)
    locale.setlocale(locale.LC_ALL, "")
except locale.Error:
    pass


def _dirs():
    env = os.environ.get("HK_LOCALEDIR")
    if env:
        yield env
    yield os.path.normpath(os.path.join(HERE, "..", "build", "locale"))     # Quellordner: linux/build/locale
    yield os.path.normpath(os.path.join(HERE, "..", "..", "locale"))        # <prefix>/share/hlidskjalf/hlidskjalf -> <prefix>/share/locale
    yield None                                                              # Python-Standard (sys.base_prefix/share/locale)


LANGUAGES = ("de", "en", "es")          # waehlbar in Einstellungen -> Allgemein -> Sprache (sonst: Systemsprache)


def chosen_language():
    """In der App gewaehlte Sprache ('de'/'en'/'es') oder '' (= Systemsprache). Liest settings.json direkt, weil
    config.py erst nach den Uebersetzungen geladen wird (gleicher Ordner wie GLib.get_user_config_dir())."""
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.join(os.path.expanduser("~"), ".config")
    try:
        with open(os.path.join(base, "hlidskjalf", "settings.json"), encoding="utf-8") as f:
            v = json.load(f).get("language")
    except (OSError, ValueError, AttributeError):
        return ""
    return v if v in LANGUAGES else ""


CHOSEN = chosen_language()
if CHOSEN:                              # gilt auch fuer GTK/libadwaita (glibc-gettext liest LANGUAGE bei jedem Aufruf)
    os.environ["LANGUAGE"] = CHOSEN

LOCALEDIR = next((d for d in _dirs() if gettext.find(DOMAIN, d, languages=[CHOSEN] if CHOSEN in ("de", "es") else None)), None)
if CHOSEN == "en":                      # Quelltexte sind englisch: kein Katalog
    _t = gettext.NullTranslations()
else:
    _t = gettext.translation(DOMAIN, LOCALEDIR, languages=[CHOSEN] if CHOSEN else None, fallback=True)
_ = _t.gettext
ngettext = _t.ngettext
pgettext = _t.pgettext


def ui_language():
    """Sprache der Oberflaeche ('de', 'es', sonst 'en') - fuer Accept-Language an den Server"""
    if CHOSEN:
        return CHOSEN
    info = _t.info() if hasattr(_t, "info") else {}
    lang = (info.get("language") or "").split("_")[0].split("-")[0]
    return lang or "en"
