"""Seiten veroeffentlichen (Server ab 1.2): Dialog aus dem Seitenmenue, Hinweis "Oeffentlich" in der Kopfleiste,
oeffentlicher Name in den Einstellungen. Ablauf wie im Web (static/pages.js): ohne oeffentlichen Namen erst der
Namensschritt (Vorschlag, Pruefung beim Tippen), dann die Einstellungen der Seite.
Reihenfolge im Dialog (gleich auf allen Plattformen): Schalter "Oeffentlich" -> Link-Karte (Link, Kopieren, Oeffnen;
Profiladresse bei Profilseite; solange privat die kuenftige Adresse gedaempft) -> Adresse -> Optionen -> Beenden.
Fehler stehen am Feld (rot markiert, Text darunter), nicht in einer Meldung.
Schnittstelle: GET/PUT/DELETE api/notes/{id}/publish, GET/PUT api/account/public-name ({name, check}).
Die Logik (Zustand -> Anzeige) steht ohne GTK in view()/header_state() und ist so per pytest pruefbar."""
import os
import re

from gi.repository import Adw, GLib, Gtk, Pango

from .api import APIError, client
from .i18n import _, pgettext
from .store import store
from .util import bg, bg_serial, date_time_text, placeholder

CHECK_DELAY_MS = 300        # Pruefung des Namens erst, wenn das Tippen kurz ruht
MAIN_HEIGHT = 700           # Hoehe der Einstellungsseite; der Namensschritt bleibt natuerlich hoch
ICON_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icons")   # eigene Symbole (RPM, Flatpak)
GLOBE = "saga-globe-symbolic"                                                  # icons/saga-globe-symbolic.svg
ZWSP, WJ = "​", "⁠"


# =====================================================================================================
# Logik ohne GTK
# =====================================================================================================
def available(page):
    """Menuepunkt anbieten? Nur wenn der Server Veroeffentlichen kennt (Feld 'published' in der Seite; aeltere
    Server liefern es nicht) und die Seite keine Vorlage ist (Vorlagen lassen sich nicht veroeffentlichen)."""
    return isinstance(page, dict) and "published" in page and not page.get("template")


def norm(s):
    """Name bzw. Adresse wie der Server: ohne Leerraum am Rand, klein"""
    return str(s or "").strip().lower()


def display_url(u):
    """Adresse fuer die Anzeige: Umbruch nur nach "/" (U+200B dahinter), nie am Bindestrich (U+2060 dahinter).
    Nur fuer die Anzeige - Kopieren und Oeffnen nehmen immer die echte Adresse."""
    u = str(u or "").replace("-", "-" + WJ)
    return re.sub(r"/+", lambda m: m.group(0) + ZWSP, u)


def need_name(st):
    """Antwort von GET .../publish bzw. Fehler von PUT: zuerst einen oeffentlichen Namen waehlen?"""
    if isinstance(st, APIError):
        return bool(isinstance(st.data, dict) and st.data.get("need_name"))
    return isinstance(st, dict) and not st.get("name")


def suggested_name(st):
    if isinstance(st, APIError):
        st = st.data if isinstance(st.data, dict) else {}
    return norm((st or {}).get("suggested_name") or "")


def header_state(page):
    """Hinweis in der Kopfleiste fuer eine geladene Seite: None oder {label, tooltip, url, via}.
    Die Beschriftung ist immer nur "Oeffentlich"; "ueber <Elternseite>" steht im Tooltip.
    Oeffentlich ueber eine Elternseite ("Unterseiten mit veroeffentlichen"): published_via, nur wenn nicht selbst."""
    if not isinstance(page, dict):
        return None
    if page.get("published"):
        return {"label": _("Public"), "tooltip": _("This page is public – click for settings"),
                "url": page.get("public_url") or "", "via": None}
    via = page.get("published_via")
    if isinstance(via, dict) and via.get("url"):
        return {"label": _("Public"),
                "tooltip": _("Public as a subpage of “{title}” – click for details").format(title=str(via.get("title") or "")),
                "url": via.get("url") or "", "via": via}
    return None


def apply_state(page, st):
    """Antwort von .../publish in die geladene Seite uebernehmen (Kopfleiste, Menue) - wie GET api/notes/{id}"""
    if isinstance(page, dict) and isinstance(st, dict):
        page["published"] = bool(st.get("published"))
        page["public_url"] = st.get("url") if st.get("published") else None
        page["published_via"] = st.get("published_via")


def preview_url(st, slug):
    """Vorschau der Adresse: <server>/@name/<adresse> (profile_url ist <server>/@name)"""
    base = (st or {}).get("profile_url") or ""
    return f"{base}/{slug or '…'}" if base else ""


def view(st, slug_text=None):
    """Zustand (GET/PUT/DELETE .../publish) -> was der Dialog zeigt. slug_text: was gerade im Adressfeld steht."""
    st = st or {}
    published = bool(st.get("published"))
    enabled = st.get("enabled", True) is not False
    via = st.get("published_via") if not published and isinstance(st.get("published_via"), dict) else None
    slug = st.get("slug") or ""
    typed = norm(slug if slug_text is None else slug_text)
    changed = bool(published and typed and typed != slug)
    w = st.get("withdrawn_at") if not published else None
    admin = bool(w) and st.get("withdrawn_by") == "admin"
    if not w:
        withdrawn = None
    elif admin:
        withdrawn = _("Withdrawn by the admin on {when}. Address and settings are kept – you can publish the page again.").format(
            when=date_time_text(w))
    else:
        withdrawn = _("Stopped publishing on {when}. Address and settings are kept and apply again when you publish again.").format(
            when=date_time_text(w))
    po = st.get("profile_other") if isinstance(st.get("profile_other"), dict) else None
    own = via is None                   # ueber Elternseite: Adresse und Optionen erst mit "Zusaetzlich ..."
    return {
        "published": published,
        "enabled": enabled,
        "disabled_note": None if enabled else _("The admin has turned off publishing on this server – public addresses "
                                                "currently return “Not found”."),
        "switch_title": _("Also publish at its own address") if via else _("Public"),
        "switch_active": published,
        "switch_sensitive": enabled or published,
        "via": via,
        "via_text": _("This page is already public: “{title}” is published together with its subpages.").format(
            title=via.get("title") or "") if via else None,
        "withdrawn": withdrawn,
        "withdrawn_admin": admin,
        # Link-Karte: oeffentlich der Link (Kopieren, Oeffnen), privat die kuenftige Adresse gedaempft
        "link_visible": own,
        "link": st.get("url") if published else preview_url(st, typed),
        "link_active": published,
        "profile_link": st.get("profile_url") if published and st.get("profile") and st.get("profile_url") else None,
        "home": bool(published and st.get("home")),
        # Adresse: veroeffentlicht mit Uebernehmen-Knopf (erst bei Abweichung), privat gilt sie beim Veroeffentlichen
        "address_visible": own,
        "slug_apply": published,
        "address_changed": changed,
        "address_note": _("Takes effect only when you tap “Change address”.") if changed else None,
        "options_visible": own,
        "options_title": None if published else _("Applies once the page is public"),
        "stop_visible": published,
        "profile_hint": _("Replaces your current profile page “{title}”.").format(title=po.get("title") or "") if po
        else _("Shown at your profile address – one page at most."),
        "name_note": _("Public name: @{name} – change it in Settings → Account.").format(name=st.get("name") or ""),
    }


def saved_text(st, done=None):
    """Rueckmeldung nach dem Speichern (ersetzte Profilseite wird genannt)"""
    rp = (st or {}).get("replaced_profile")
    if isinstance(rp, dict):
        return _("✓ Saved – replaces your previous profile page “{title}”.").format(title=rp.get("title") or "")
    return done or _("✓ Saved")


def error_text(e):
    """Fehler fuer die Anzeige: Text vom Server (schon in der App-Sprache), sonst allgemein"""
    if isinstance(e, APIError) and e.code == 404:
        return _("This server doesn't support publishing yet.")
    return str(e)


def error_field(e, body, where):
    """An welchem Feld steht ein Fehler? Abgelehnte Adresse (400/409 bei mitgeschickter Adresse) -> "slug",
    sonst die Stelle, die die Anfrage ausgeloest hat (pub, opt, stop)."""
    if isinstance(e, APIError) and e.code in (400, 409) and isinstance(body, dict) and body.get("slug"):
        return "slug"
    return where


# =====================================================================================================
# Bausteine
# =====================================================================================================
def name_hints(row, maxlen):
    """Eingabe fuer Name/Adresse: klein, ohne Rechtschreibpruefung, hoechstens maxlen Zeichen"""
    row.set_input_hints(Gtk.InputHints.NO_SPELLCHECK | Gtk.InputHints.LOWERCASE | Gtk.InputHints.NO_EMOJI)
    try:
        row.get_delegate().set_max_length(maxlen)
    except (AttributeError, TypeError):
        pass


def status_label():
    """Rueckmeldung unter einem Feld (Pruefung, Fehler) - fuer Screenreader als Statusbereich angesagt"""
    try:
        lb = Gtk.Label(xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, accessible_role=Gtk.AccessibleRole.STATUS)
    except (AttributeError, TypeError):
        lb = Gtk.Label(xalign=0, wrap=True)
    lb.set_visible(False)
    return lb


def set_status(lb, text, ok=None):
    lb.set_label(text or "")
    lb.set_visible(bool(text))
    for c, on in (("success", ok is True), ("error", ok is False)):
        (lb.add_css_class if on else lb.remove_css_class)(c)
    if text and hasattr(lb, "announce"):
        try:
            lb.announce(text, Gtk.AccessibleAnnouncementPriority.MEDIUM)
        except (AttributeError, TypeError):
            pass


def mark_error(w, on):
    """Feld rot markieren (Adwaita-Stil "error") bzw. die Markierung wegnehmen"""
    (w.add_css_class if on else w.remove_css_class)("error")


def note_label(css=None):
    lb = Gtk.Label(xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, selectable=True)
    for c in (css or "").split():
        lb.add_css_class(c)
    return lb


def confirm(parent, heading, body, label, ok, cancelled=None, destructive=True):
    """Rueckfrage (Adw.AlertDialog): Abbrechen ist Vorgabe und Esc"""
    d = Adw.AlertDialog(heading=heading, body=body)
    d.add_response("cancel", _("Cancel"))
    d.add_response("ok", label)
    d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE if destructive else Adw.ResponseAppearance.SUGGESTED)
    d.set_default_response("cancel")
    d.set_close_response("cancel")
    d.connect("response", lambda dd, r: ok() if r == "ok" else (cancelled and cancelled()))
    d.present(parent)
    return d


def button_row(title, cb, destructive=False):
    if hasattr(Adw, "ButtonRow"):
        r = Adw.ButtonRow(title=title)
    else:
        r = Adw.ActionRow(title=title, activatable=True)
    if destructive:
        r.add_css_class("destructive-action")
    r.connect("activated", lambda *_: cb())
    return r


def icon_button(icon, tooltip, cb):
    """flacher Symbol-Knopf mit Tooltip; der Tooltip ist auch der Name fuer Screenreader"""
    b = Gtk.Button(icon_name=icon, tooltip_text=tooltip, valign=Gtk.Align.CENTER)
    b.add_css_class("flat")
    b.update_property([Gtk.AccessibleProperty.LABEL], [tooltip])
    b.connect("clicked", lambda *_: cb())
    return b


class UrlRow(Adw.PreferencesRow):
    """Zeile "Ueberschrift + Adresse" ueber die volle Breite, Knoepfe als Symbole rechts. Die Adresse bricht nur nach
    "/" um (display_url) und ist nicht markierbar (beim Kopieren kaemen sonst die unsichtbaren Umbruchzeichen mit)."""

    def __init__(self, title):
        super().__init__(title=title, activatable=False)
        self.box = Gtk.Box(spacing=6, margin_start=12, margin_end=6, margin_top=8, margin_bottom=8)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True, valign=Gtk.Align.CENTER)
        self.head = Gtk.Label(label=title, xalign=0)
        self.head.add_css_class("caption")
        self.head.add_css_class("dim-label")
        self.url = Gtk.Label(xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR)
        col.append(self.head)
        col.append(self.url)
        self.box.append(col)
        self.set_child(self.box)
        self.raw = ""

    def add_button(self, b):
        self.box.append(b)
        return b

    def set_url(self, url, active=True):
        self.raw = url or ""
        self.url.set_label(display_url(url))
        (self.url.remove_css_class if active else self.url.add_css_class)("dim-label")
        self.update_property([Gtk.AccessibleProperty.LABEL], [f"{self.head.get_label()}: {self.raw}"])


class NameCheck:
    """Pruefung des oeffentlichen Namens beim Tippen (gueltig und frei: PUT api/account/public-name mit check).
    on_result(name, ok, text): ok None = nichts zu pruefen (leer bzw. unveraendert)."""

    def __init__(self, on_result):
        self.on_result = on_result
        self._tid = 0
        self._gen = 0

    def cancel(self):
        self._gen += 1
        if self._tid:
            GLib.source_remove(self._tid)
            self._tid = 0

    def changed(self, text, unchanged=None):
        self.cancel()
        name = norm(text)
        if not name or name == unchanged:
            self.on_result(name, None, "")
            return
        gen = self._gen
        self.on_result(name, None, "")

        def done(r):
            if gen == self._gen:
                ok = bool(isinstance(r, dict) and r.get("ok"))
                self.on_result(name, ok, _("✓ Available") if ok else (r or {}).get("error") or "")

        def err(e):
            if gen == self._gen:
                self.on_result(name, False, str(e))

        def go():
            self._tid = 0
            bg(client.json, "PUT", "api/account/public-name", {"name": name, "check": True}, done=done, error=err)
            return False
        self._tid = GLib.timeout_add(CHECK_DELAY_MS, go)


def _theme():
    from gi.repository import Gdk
    disp = Gdk.Display.get_default()
    if disp is None:
        return None
    th = Gtk.IconTheme.get_for_display(disp)
    # eigene Symbole: main.py traegt den Ordner beim Start ein; hier zur Sicherheit (Tests, Suchanbieter)
    if os.path.isdir(ICON_DIR) and ICON_DIR not in (th.get_search_path() or []):
        th.add_search_path(ICON_DIR)
    return th


def _icon(*names):
    try:
        th = _theme()
        for n in names[:-1]:
            if th is not None and th.has_icon(n):
                return n
    except (AttributeError, TypeError):
        pass
    return names[-1]


def globe_icon():
    """Symbol fuer "oeffentlich": eigenes saga-globe-symbolic (hlidskjalf/icons), sonst web-browser-symbolic"""
    return _icon(GLOBE, "web-browser-symbolic")


def open_icon():
    """Symbol fuer "Im Browser oeffnen" (libadwaita bringt adw-external-link-symbolic mit)"""
    return _icon("adw-external-link-symbolic", "external-link-symbolic", "web-browser-symbolic")


# =====================================================================================================
# Dialog
# =====================================================================================================
def open_publish(win, pid):
    """Seitenmenue "Veroeffentlichen …": Zustand holen, dann den Dialog zeigen"""
    bg(client.get, f"api/notes/{pid}/publish", done=lambda st: PublishDialog(win, pid, st).present(win),
       error=lambda e: win.toast(error_text(e)))


class PublishDialog(Adw.Dialog):
    """Adw.Dialog mit Adw.NavigationView: Namensschritt (nur ohne oeffentlichen Namen), dann die Einstellungen."""

    def __init__(self, win, pid, st):
        super().__init__(title=_("Publish"), content_width=540)
        self.win, self.pid, self.st = win, pid, st or {}
        self.toasts = Adw.ToastOverlay()
        self.nav = Adw.NavigationView()
        self.toasts.set_child(self.nav)
        self.set_child(self.toasts)
        self.check = NameCheck(self._name_result)
        self.name_row = self.go_btn = self.main = None
        self.errs = {}
        self.slug_err = False
        self._drawing = False
        self.connect("closed", lambda *_: self.check.cancel())
        if need_name(self.st):
            self.show_name_step(suggested_name(self.st))
        else:
            self.show_main(self.st)

    @property
    def path(self):
        return f"api/notes/{self.pid}/publish"

    def toast(self, text):
        if text:
            self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(text)), timeout=4))

    @staticmethod
    def _page(title, content, tag, end=None):
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        if end is not None:
            hb.pack_end(end)
        tv.add_top_bar(hb)
        tv.set_content(content)
        return Adw.NavigationPage(title=title, child=tv, tag=tag)

    # --- Schritt 1: oeffentlicher Name ----------------------------------------------------------------------
    def show_name_step(self, suggestion):
        from . import config
        self.main = None
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, margin_top=24, margin_bottom=24, margin_start=12, margin_end=12)
        intro = Gtk.Label(label=_("First choose your public name. It appears in every address of your public "
                                  "pages and is visible to everyone."), xalign=0, wrap=True)   # nicht abgedunkelt
        intro.set_margin_bottom(12)
        box.append(intro)
        g = Adw.PreferencesGroup()
        self.name_row = Adw.EntryRow(title=_("Public name"), text=suggestion or "")
        name_hints(self.name_row, 32)
        g.add(self.name_row)
        self.name_url = note_label("dim-label caption")
        self.name_status = status_label()
        self.name_hint = note_label("dim-label")
        self.name_hint.set_label(_("Not your username: pick something everyone may see. 2–32 characters, a–z, 0–9, - and _."))
        for w in (self.name_url, self.name_status, self.name_hint):
            w.set_margin_top(6)
            g.add(w)
        box.append(g)
        sc = Adw.Clamp(child=box)                       # ohne Rollbereich: die Hoehe ergibt sich aus dem Inhalt
        self.go_btn = Gtk.Button(label=_("Continue"), sensitive=False)
        self.go_btn.add_css_class("suggested-action")
        self.go_btn.connect("clicked", lambda *_: self._name_continue())
        origin = config.origin(config.settings.server)

        def changed(*_):
            v = norm(self.name_row.get_text())
            self.name_url.set_label(display_url(f"{origin}/@{v or '…'}"))
            self.go_btn.set_sensitive(False)
            self.check.changed(v)
        self.name_row.connect("changed", changed)
        self.name_row.connect("entry-activated", lambda *_: self._name_continue())
        page = self._page(_("Public name"), sc, "name", self.go_btn)
        self.nav.replace([page])
        # natuerlich hoch (feste Hoehe nur fuer die Einstellungen); erst nach dem Austausch der Seite setzen, sonst
        # misst libadwaita noch den alten Inhalt
        self.set_content_height(-1)
        changed()
        GLib.idle_add(lambda: (self.name_row is not None and self.name_row.grab_focus(), False)[1])

    def _name_status(self, text, ok):
        """Pruefergebnis unter dem Feld; ein Fehler markiert das Feld und ersetzt den Hilfetext"""
        bad = ok is False and bool(text)
        set_status(self.name_status, text, ok)
        self.name_hint.set_visible(not bad)
        mark_error(self.name_row, bad)

    def _name_result(self, name, ok, text):
        if self.name_row is None or norm(self.name_row.get_text()) != name:
            return
        self._name_status(text, ok)
        self.go_btn.set_sensitive(bool(ok))

    def _name_continue(self):
        v = norm(self.name_row.get_text())
        if not v or not self.go_btn.get_sensitive():
            return
        self.go_btn.set_sensitive(False)

        def work():
            client.json("PUT", "api/account/public-name", {"name": v})
            return client.get(self.path)

        def err(e):
            if self.name_row is not None:
                self._name_status(str(e), False)
                self.go_btn.set_sensitive(True)
        bg(work, done=self.show_main, error=err)

    # --- Schritt 2: Einstellungen der Seite ----------------------------------------------------------------
    def _err_label(self, key, group):
        lb = status_label()
        lb.set_margin_top(6)
        group.add(lb)
        self.errs[key] = lb
        return lb

    def show_main(self, st):
        self.st = st or {}
        self.check.cancel()
        self.name_row = None
        self.errs = {}
        self.slug_err = False
        self.set_content_height(MAIN_HEIGHT)
        pg = Adw.PreferencesPage()
        # Hinweise: abgeschaltet, beendet/zurueckgezogen, oeffentlich ueber eine Elternseite
        self.g_notes = Adw.PreferencesGroup()
        self.l_disabled = note_label("warning")
        self.l_withdrawn = note_label()
        self.l_via = note_label()
        for w in (self.l_disabled, self.l_withdrawn, self.l_via):
            self.g_notes.add(w)
        for w in (self.l_withdrawn, self.l_via):
            w.set_margin_top(6)
        pg.add(self.g_notes)
        # 1 Schalter "Oeffentlich" (bzw. "Zusaetzlich unter eigener Adresse ...")
        g = Adw.PreferencesGroup()
        self.sw = Adw.SwitchRow(title=_("Public"), subtitle=_("Anyone with the link can read this page without signing in."))
        self.sw.connect("notify::active", self._public_toggled)
        g.add(self.sw)
        self._err_label("pub", g)
        pg.add(g)
        # 2a Oeffentlich ueber eine Elternseite: Link als eigene Zeile, Kopieren als Symbol, Oeffnen als eigene Zeile
        self.g_via = Adw.PreferencesGroup()
        self.via_link = UrlRow(_("Link"))
        self.via_link.add_button(icon_button("edit-copy-symbolic", _("Copy link"),
                                             lambda: self._copy((self.st.get("published_via") or {}).get("url"))))
        self.g_via.add(self.via_link)
        self.via_open = Adw.ActionRow(activatable=True)
        self.via_open.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
        self.via_open.connect("activated", lambda *_: self._open_via())
        self.g_via.add(self.via_open)
        vh = note_label("dim-label")
        vh.set_label(_("It only becomes private when “Also publish subpages” is turned off there or the page is moved."))
        vh.set_margin_top(6)
        self.g_via.add(vh)
        pg.add(self.g_via)
        # 2 Link-Karte: Link (Kopieren, Oeffnen), Profiladresse; privat die kuenftige Adresse gedaempft
        self.g_link = Adw.PreferencesGroup()
        self.link_row = UrlRow(_("Link"))
        self.b_copy = self.link_row.add_button(icon_button("edit-copy-symbolic", _("Copy link"), lambda: self._copy(self.st.get("url"))))
        self.b_open = self.link_row.add_button(icon_button(open_icon(), _("Open"),
                                                           lambda: self.st.get("url") and self.win.open_uri(self.st["url"])))
        self.g_link.add(self.link_row)
        self.prof_row = UrlRow(_("Profile address"))
        self.prof_row.add_button(icon_button("edit-copy-symbolic", _("Copy link"), lambda: self._copy(self.st.get("profile_url"))))
        self.g_link.add(self.prof_row)
        self.l_home = note_label("dim-label")
        self.l_home.set_label(_("This page is the home page for visitors."))
        self.l_home.set_margin_top(6)
        self.g_link.add(self.l_home)
        pg.add(self.g_link)
        # 3 Adresse: veroeffentlicht mit Uebernehmen-Knopf (erscheint erst, wenn die Eingabe abweicht)
        self.g_addr = Adw.PreferencesGroup()
        self.slug = Adw.EntryRow(title=_("Address"), text=self.st.get("slug") or "", show_apply_button=False)
        name_hints(self.slug, 60)
        self.slug.connect("changed", self._slug_changed)
        self.slug.connect("apply", lambda *_: self._change_slug())
        self.g_addr.add(self.slug)
        self.l_addr = note_label("dim-label caption")
        self.l_addr.set_margin_top(6)
        self.g_addr.add(self.l_addr)
        self._err_label("slug", self.g_addr)
        pg.add(self.g_addr)
        # 4 Optionen (vor dem Veroeffentlichen nur gemerkt, danach sofort gespeichert)
        self.g_opts = Adw.PreferencesGroup()
        self.sw_children = Adw.SwitchRow(title=_("Also publish subpages"),
                                         subtitle=_("Subpages (and database entries) of this page will then be public too."))
        self.sw_index = Adw.SwitchRow(title=_("Allow search engines"),
                                      subtitle=_("Otherwise search engines are asked not to index the page."))
        self.sw_profile = Adw.SwitchRow(title=_("As profile page (/@name)"))
        for key, row in (("children", self.sw_children), ("indexable", self.sw_index), ("profile", self.sw_profile)):
            row.connect("notify::active", lambda r, _p, key=key: self._option_toggled(key, r))
            self.g_opts.add(row)
        self._err_label("opt", self.g_opts)
        pg.add(self.g_opts)
        # 5 Beenden + Hinweis auf den oeffentlichen Namen
        self.g_end = Adw.PreferencesGroup()
        self.stop_row = button_row(_("Stop publishing"), lambda: self._stop("stop"), destructive=True)
        self.g_end.add(self.stop_row)
        self._err_label("stop", self.g_end)
        pg.add(self.g_end)
        self.g_name = Adw.PreferencesGroup()
        self.l_name = note_label("dim-label")
        self.g_name.add(self.l_name)
        pg.add(self.g_name)
        self.main = pg
        self.nav.replace([self._page(_("Publish"), pg, "main")])
        self.draw(opts=True)
        GLib.idle_add(lambda: (self.sw.grab_focus(), False)[1])

    def _apply_button(self, on):
        """Uebernehmen-Knopf im Adressfeld: Aus- und wieder Einschalten versteckt ihn (libadwaita zeigt ihn beim
        Tippen bzw. set_text); Tooltip "Adresse aendern" wie die Rueckfrage und der Hinweis darunter"""
        self.slug.set_show_apply_button(False)
        if not on:
            return
        self.slug.set_show_apply_button(True)
        c, todo = None, [self.slug]
        while todo:
            c = todo.pop()
            if isinstance(c, Gtk.Button) and c.get_icon_name() == "adw-entry-apply-symbolic":
                c.set_tooltip_text(_("Change address"))
                c.update_property([Gtk.AccessibleProperty.LABEL], [_("Change address")])
            k = c.get_first_child()
            while k is not None:
                todo.append(k)
                k = k.get_next_sibling()

    def draw(self, opts=False):
        """Anzeige aus self.st (und dem Adressfeld). opts: Schalter der Optionen aus dem Zustand des Servers setzen
        (nach Antworten und Fehlern) - sonst bleiben vor dem Veroeffentlichen gewaehlte Optionen stehen."""
        if self.main is None:
            return
        st = self.st
        v = view(st, self.slug.get_text())
        self._drawing = True
        try:
            self.l_disabled.set_label(v["disabled_note"] or "")
            self.l_disabled.set_visible(bool(v["disabled_note"]))
            self.l_withdrawn.set_label(v["withdrawn"] or "")
            self.l_withdrawn.set_visible(bool(v["withdrawn"]))
            (self.l_withdrawn.add_css_class if v["withdrawn_admin"] else self.l_withdrawn.remove_css_class)("warning")
            via = v["via"]
            self.l_via.set_label(v["via_text"] or "")
            self.l_via.set_visible(bool(via))
            self.g_notes.set_visible(bool(v["disabled_note"] or v["withdrawn"] or via))
            self.g_via.set_visible(bool(via))
            if via:
                self.via_link.set_url(via.get("url") or "")
                self.via_open.set_title(GLib.markup_escape_text(_("Open “{title}”").format(title=via.get("title") or "")))
            self.sw.set_title(v["switch_title"])
            self.sw.set_active(v["switch_active"])
            self.sw.set_sensitive(v["switch_sensitive"])
            self.g_link.set_visible(v["link_visible"])
            self.link_row.set_url(v["link"], v["link_active"])
            self.b_copy.set_visible(v["link_active"])
            self.b_open.set_visible(v["link_active"])
            self.prof_row.set_visible(bool(v["profile_link"]))
            self.prof_row.set_url(v["profile_link"] or "")
            self.l_home.set_visible(v["home"])
            self.g_addr.set_visible(v["address_visible"])
            if self.slug.get_show_apply_button() != v["slug_apply"] or not v["address_changed"]:
                self._apply_button(v["slug_apply"])
            self.l_addr.set_label(v["address_note"] or "")
            self.l_addr.set_visible(bool(v["address_note"]) and not self.slug_err)
            self.g_opts.set_visible(v["options_visible"])
            self.g_opts.set_title(v["options_title"] or "")
            self.sw_profile.set_subtitle(GLib.markup_escape_text(v["profile_hint"]))
            if opts:
                self.sw_children.set_active(bool(st.get("children")))
                self.sw_index.set_active(bool(st.get("indexable")))
                self.sw_profile.set_active(bool(st.get("profile")))
            self.g_end.set_visible(v["stop_visible"])
            self.l_name.set_label(v["name_note"])
        finally:
            self._drawing = False

    def show_error(self, where=None, text=""):
        """Fehler an der Stelle, die ihn ausgeloest hat (pub, slug, opt, stop); ohne Angabe: alle Fehler weg"""
        for k, lb in self.errs.items():
            set_status(lb, text if k == where else "", False)
        self.slug_err = where == "slug" and bool(text)
        if self.main is not None:
            mark_error(self.slug, self.slug_err)
            self.l_addr.set_visible(bool(self.l_addr.get_label()) and not self.slug_err)

    # --- Aktionen -------------------------------------------------------------------------------------------
    def _sync(self, ns, text=None, slug_sent=False):
        """Neuer Zustand vom Server: Dialog, offene Seiten (Kopfleiste, Menue) und Zwischenspeicher"""
        self.st = ns or {}
        for d in list(store.docs):
            if d.id == self.pid and d.fetched:
                apply_state(d.page, self.st)
        store.drop_cache(f"api/notes/{self.pid}")
        self.win.update_header()
        if self.main is not None:
            self.show_error()
            if slug_sent or not norm(self.slug.get_text()):      # Adresse vom Server (sonst bleibt die Eingabe stehen)
                self.slug.set_text(self.st.get("slug") or "")
        self.draw(opts=True)
        self.toast(text)

    def _failed(self, e, where):
        if need_name(e):                                  # Name inzwischen weg (anderes Geraet): Namensschritt
            self.show_name_step(suggested_name(e))
            return
        self.draw(opts=True)
        self.show_error(where, error_text(e))

    def _send(self, method, body=None, text=None, where="pub"):
        # der Reihe nach (eine Warteschlange je Seite): schnelles Umschalten bleibt in Ordnung
        sent = bool(isinstance(body, dict) and body.get("slug"))
        bg_serial(("publish", self.pid), client.json, method, self.path, body,
                  done=lambda ns: self._sync(ns, text(ns) if callable(text) else text, sent),
                  error=lambda e: self._failed(e, error_field(e, body, where)))

    def _put(self, body, done_text=None, where="pub"):
        if self.st.get("enabled", True) is False:
            self.draw(opts=True)
            self.show_error(where, _("Publishing is turned off on this server."))
            return
        self.show_error()
        self._send("PUT", body, lambda ns: saved_text(ns, done_text), where)

    def _public_toggled(self, row, _p):
        if self._drawing:
            return
        if row.get_active():
            body = {"children": self.sw_children.get_active(), "indexable": self.sw_index.get_active(),
                    "profile": self.sw_profile.get_active()}
            slug = norm(self.slug.get_text())
            if slug:
                body["slug"] = slug
            self._put(body)
        else:
            self._stop("pub")

    def _option_toggled(self, key, row):
        if self._drawing or not self.st.get("published"):
            return                                          # noch nicht oeffentlich: gilt beim Veroeffentlichen
        self._put({key: row.get_active()}, where="opt")

    def _stop(self, where="stop"):
        if not self.st.get("published"):
            self.draw()
            return
        # danach steht "Veroeffentlichung beendet am ..." im Dialog - keine zusaetzliche Meldung
        confirm(self, _("Stop publishing?"), _("The link will stop working."), pgettext("stop publishing", "Stop"),
                lambda: self._send("DELETE", None, None, where), lambda: self.draw())

    def _slug_changed(self, *_):
        if self.slug_err:
            self.show_error()
        self.draw()

    def _change_slug(self):
        v = norm(self.slug.get_text())
        if not self.st.get("published") or not v or v == self.st.get("slug"):
            return
        confirm(self, _("Change address?"),
                _("The current link {url} will stop working.").format(url=display_url(self.st.get("url") or "")),
                _("Change address"), lambda: self._put({"slug": v}, _("✓ New address saved"), "slug"),
                lambda: self.slug.set_text(self.st.get("slug") or ""), destructive=False)

    def _copy(self, url):
        if url:
            self.win.copy_text(url)
            self.toast(_("Link copied"))

    def _open_via(self):
        via = self.st.get("published_via") or {}
        if via.get("note_id"):
            self.close()
            self.win.show_page(via["note_id"])


# =====================================================================================================
# Einstellungen -> Konto: oeffentlicher Name
# =====================================================================================================
def new_address_text(origin, name, old_url):
    """Live beim Tippen: "Neue Adresse: …/@neu (bisher …/@alt)"; ohne bisherigen Namen nur die neue Adresse"""
    url = f"{origin}/@{name}"
    if old_url:
        return _("New address: {url} (was {old})").format(url=display_url(url), old=display_url(old_url))
    return display_url(url)


class PublicNameGroup(Adw.PreferencesGroup):
    """Eigener Block mit eigenem Knopf; Pruefung beim Tippen (Ergebnis direkt unter dem Feld, darunter die neue
    Adresse); Rueckfrage beim Aendern, wenn schon Seiten oeffentlich sind (alte Links gehen dann nicht mehr).
    Aeltere Server (ohne api/account/public-name): Block bleibt unsichtbar."""

    def __init__(self, dlg):
        super().__init__(title=_("Public name"))
        self.dlg = dlg
        self.p = {}
        self.set_visible(False)
        self.row = Adw.EntryRow(title=_("Name"))
        name_hints(self.row, 32)
        self.btn = Gtk.Button(label=_("Save name"), valign=Gtk.Align.CENTER, sensitive=False)
        self.btn.connect("clicked", lambda *_: self.save())
        self.row.add_suffix(self.btn)
        self.add(self.row)
        self.status = status_label()
        self.status.set_margin_top(6)
        self.add(self.status)
        self.newaddr = note_label("dim-label caption")
        self.newaddr.set_selectable(False)
        self.newaddr.set_margin_top(6)
        self.newaddr.set_visible(False)
        self.add(self.newaddr)
        self.check = NameCheck(self._result)
        self.row.connect("changed", lambda *_: self._changed())
        self.row.connect("entry-activated", lambda *_: self.save())
        self.connect("unrealize", lambda *_: self.check.cancel())
        bg(client.get, "api/account/public-name", done=self.fill, error=lambda e: None)

    def _changed(self):
        from . import config
        self.btn.set_sensitive(False)
        mark_error(self.row, False)
        v, old = norm(self.row.get_text()), self.p.get("name")
        txt = new_address_text(config.origin(config.settings.server), v, self.p.get("url") if old else None) if v and v != old else ""
        self.newaddr.set_label(txt)
        self.newaddr.set_visible(bool(txt))
        self.check.changed(v, old)

    def fill(self, p):
        self.p = p if isinstance(p, dict) else {}
        self.row.set_text(self.p.get("name") or "")
        placeholder(self.row, self.p.get("suggested") or "")
        self._hint()
        set_status(self.status, "")
        self.newaddr.set_visible(False)
        self.btn.set_sensitive(False)
        self.set_visible(True)

    def _hint(self):
        if self.p.get("name"):
            self.set_description(_("Appears in the addresses of your public pages ({url}). If you change it, old links stop working.").format(
                url=display_url(self.p.get("url") or "")))
        else:
            self.set_description(_("Appears in the addresses of your public pages (…/@name/…) and is visible to everyone – "
                                   "not your username. Set when you first publish."))

    def _result(self, name, ok, text):
        if norm(self.row.get_text()) != name:
            return
        set_status(self.status, text, ok)
        mark_error(self.row, ok is False and bool(text))
        self.btn.set_sensitive(bool(ok))

    def save(self):
        v = norm(self.row.get_text())
        if not v or not self.btn.get_sensitive():
            return
        if self.p.get("name") and v != self.p["name"] and self.p.get("published"):
            confirm(self.dlg, _("Change public name?"), _("Links to your published pages will change. Old links will stop working."),
                    _("Change"), lambda: self._save(v), destructive=False)
        else:
            self._save(v)

    def _save(self, v):
        self.btn.set_sensitive(False)

        def fin(d):
            self.p.update(name=d.get("name") or v, url=d.get("url"))
            self.check.cancel()
            self.row.set_text(self.p["name"])
            self._hint()
            self.btn.set_sensitive(False)
            self.newaddr.set_visible(False)
            mark_error(self.row, False)
            set_status(self.status, _("✓ Saved"), True)

        def err(e):
            set_status(self.status, str(e), False)
            mark_error(self.row, True)
            self.btn.set_sensitive(True)
        bg(client.json, "PUT", "api/account/public-name", {"name": v}, done=fin, error=err)
