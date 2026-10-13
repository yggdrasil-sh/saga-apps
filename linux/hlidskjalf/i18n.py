"""Uebersetzungen (gettext). Quelltexte sind englisch; Kataloge: po/*.po -> <localedir>/<sprache>/LC_MESSAGES/<DOMAIN>.mo.
Gesucht wird: $HK_LOCALEDIR, linux/build/locale (Start aus dem Quellordner, erzeugt von po/build-mo.sh),
<prefix>/share/locale (RPM: /usr, Flatpak: /app). Ohne Katalog laeuft die App englisch weiter."""
import gettext
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


LOCALEDIR = next((d for d in _dirs() if gettext.find(DOMAIN, d)), None)
_t = gettext.translation(DOMAIN, LOCALEDIR, fallback=True)
_ = _t.gettext
ngettext = _t.ngettext
pgettext = _t.pgettext


def ui_language():
    """Sprache der Oberflaeche ('de', 'es', sonst 'en') - fuer Accept-Language an den Server"""
    info = _t.info() if hasattr(_t, "info") else {}
    lang = (info.get("language") or "").split("_")[0].split("-")[0]
    return lang or "en"
