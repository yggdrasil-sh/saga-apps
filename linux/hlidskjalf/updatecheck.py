"""Update-Hinweis (Einstellungen -> Info): fragt die Update-Info der CI ab (updatehint.py), merkt das Ergebnis in
settings.json ("update_hint") und liefert Statuszeile + die eine Schaltflaeche - wie ios/App/UpdateHintModel.swift.
Abruf im Hintergrund (util.bg), nur die Datei, ohne Cookies/Kennungen, 10 s Zeitlimit. Fehler bleiben still - sichtbar
nur nach "Nach Updates suchen". Schalter aus ("update_hint_on": false) -> keine Anfrage an GitHub."""
import time

from gi.repository import GObject

from . import update_label
from . import updatehint as U
from .config import settings
from .util import bg

CACHE_KEY = "update_hint"
ON_KEY = "update_hint_on"


class UpdateHintModel(GObject.Object):
    __gsignals__ = {"changed": (GObject.SignalFlags.RUN_FIRST, None, ())}

    def __init__(self):
        super().__init__()
        self.checking = False
        self.failed = False
        self.checked, self.release = U.decode(settings.get(CACHE_KEY))
        self.fetch = U.fetch                         # Tests: Ersatz ohne Netz
        self.label_fn = update_label                 # Tests: festes Label (Quellbaum = Entwickler-Build)

    # --- Zustand ---------------------------------------------------------------------------------------------------
    @property
    def label(self):
        return self.label_fn()

    @property
    def enabled(self):
        return settings.get(ON_KEY, True) is not False

    def set_enabled(self, on):
        if on == self.enabled:
            return
        settings.set(ON_KEY, None if on else False)
        if not on:
            self.failed = False
        self.emit("changed")
        if on:
            self.info_opened()

    @property
    def channel(self):
        return U.channel(self.label) if self.label else None

    @property
    def available(self):
        """Bekanntes Update im eigenen Kanal (None: keins, Hinweis aus oder Entwickler-Build)"""
        return U.available(self.release, self.label) if self.enabled and self.label else None

    def status(self):
        return U.status(self.available, self.failed, self.checked)

    def button(self):
        return U.button(self.checking, self.available)

    # --- Pruefen ---------------------------------------------------------------------------------------------------
    def app_started(self):
        """App-Start: hoechstens alle 12 h"""
        self._auto(U.START_INTERVAL)

    def info_opened(self):
        """Info geoeffnet: nur, wenn das letzte Ergebnis nicht ganz frisch ist"""
        self._auto(U.INFO_INTERVAL)

    def _auto(self, interval):
        if self.enabled and not self.checking and self.label and U.due(self.checked, time.time(), interval):
            self.check(manual=False)

    def check(self, manual=True):
        """"Nach Updates suchen": sofort, ohne Zwischenspeicher. Laeuft im Hintergrund; Ergebnis per "changed"."""
        if not self.enabled or self.checking:
            return
        lbl = self.label
        url, ch = (U.feed_url(lbl), U.channel(lbl)) if lbl else (None, None)
        if not url or not ch:
            if manual:
                self.failed = True
                self.emit("changed")
            return
        self.checking = True
        if manual:
            self.failed = False
        self.emit("changed")

        def done(r):
            self.checking = False
            self.release, self.checked, self.failed = r, time.time(), False
            settings.set(CACHE_KEY, U.encode(self.checked, r))
            self.emit("changed")

        def error(_e):
            self.checking = False
            if manual:
                self.failed = True                   # automatische Pruefung: still, alter Stand bleibt
            self.emit("changed")
        bg(self.fetch, url, ch, done=done, error=error)


hint = UpdateHintModel()
