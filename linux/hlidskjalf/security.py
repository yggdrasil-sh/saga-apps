"""Einstellungen -> Konto: "Anmeldung & Sicherheit" (Server ab 2.1). Nur Stand (GET /api/account/security) und ein Knopf
"Im Browser verwalten" -> <server>/security im Standardbrowser - dort gehen Passkeys (WebAuthn), Authenticator-App und
Wiederherstellungscodes. Nicht nativ nachgebaut (Empfehlung der 2FA-Spezifikation). Aeltere Server (404): unsichtbar.
Kommt man aus dem Browser zurueck (Fenster wieder aktiv), wird der Stand neu geladen."""
from gi.repository import Adw, GLib, Gtk

from . import config
from .api import client
from .i18n import _, ngettext
from .util import bg

RECOVERY_WARN = 3


def status_lines(st):
    """Stand -> (Titel, Untertitel, Warnung|None) - in der Sprache der App (Glossar wie im Web)"""
    st = st if isinstance(st, dict) else {}
    if st.get("supported") is False:
        return _("Two-factor sign-in is handled by your sign-in service (SSO)."), "", None
    on = bool(st.get("enabled"))
    title = "✓ " + _("Second factor is on.") if on else _("Second factor is off.")
    parts = []
    n = len(st.get("passkeys") or [])
    if n:
        parts.append(ngettext("{n} passkey", "{n} passkeys", n).format(n=n))
    if st.get("totp"):
        parts.append(_("Authenticator app"))
    left = st.get("recovery_left")
    if on and isinstance(left, int):
        parts.append(ngettext("{n} recovery code left", "{n} recovery codes left", left).format(n=left))
    warn = None
    if st.get("required") and not on:
        warn = _("Your admin requires a second factor – please set it up in the browser.")
    elif on and isinstance(left, int) and left <= RECOVERY_WARN:
        warn = _("Only a few recovery codes left – create new ones in the browser.")
    elif st.get("passkey_problem") and n:
        warn = str(st["passkey_problem"])
    return title, " · ".join(parts) if parts else (_("No second factor set up.") if not on else ""), warn


class SecurityGroup(Adw.PreferencesGroup):
    def __init__(self, dlg):
        # Translators: the title is markup - write "&" as "&amp;"
        super().__init__(title=_("Sign-in &amp; security"),
                         description=_("Passkeys, authenticator app and recovery codes are managed in the browser."))
        self.dlg = dlg
        self.set_visible(False)
        self.state = Adw.ActionRow(title="", use_markup=False)
        self.state.set_subtitle_lines(2)
        self.add(self.state)
        self.warn = Adw.ActionRow(title="", use_markup=False, visible=False)
        self.warn.add_css_class("hk-warn-row")
        self.warn.add_prefix(Gtk.Image.new_from_icon_name("dialog-warning-symbolic"))
        self.add(self.warn)
        self.manage = Adw.ActionRow(title=_("Manage in browser"), activatable=True)
        self.manage.add_suffix(Gtk.Image.new_from_icon_name("web-browser-symbolic"))
        self.manage.connect("activated", lambda *_: self.open_browser())
        self.add(self.manage)
        self._h = None
        win = getattr(dlg, "win", None)
        if win is not None:
            self._h = win.connect("notify::is-active", lambda w, _p: w.is_active() and self.get_mapped() and self.load())
            dlg.connect("closed", lambda *_: self._drop(win))
        self.load()

    def _drop(self, win):
        if self._h:
            win.disconnect(self._h)
            self._h = None

    def load(self):
        bg(client.get, "api/account/security", done=self.fill, error=lambda e: None)

    def fill(self, st):
        if not isinstance(st, dict):
            return
        title, sub, warn = status_lines(st)
        self.state.set_title(title)
        self.state.set_subtitle(sub)
        self.warn.set_title(warn or "")
        self.warn.set_visible(bool(warn))
        self.manage.set_visible(st.get("supported") is not False)
        self.set_visible(True)

    def url(self):
        return config.settings.server.rstrip("/") + "/security"

    def open_browser(self):
        win = self.dlg.win
        win.open_uri(self.url())
        t = Adw.Toast(title=GLib.markup_escape_text(_("Opened in your browser – sign in there if asked.")))
        self.dlg.add_toast(t)
