"""Seiten veroeffentlichen (Server ab 1.2): Dialog aus dem Seitenmenue, Hinweis "Oeffentlich" in der Kopfleiste,
oeffentlicher Name in den Einstellungen. Ablauf wie im Web (static/pages.js): ohne oeffentlichen Namen erst der
Namensschritt (Vorschlag, Pruefung beim Tippen), dann die Einstellungen der Seite.
Schnittstelle: GET/PUT/DELETE api/notes/{id}/publish, GET/PUT api/account/public-name ({name, check}).
Die Logik (Zustand -> Anzeige) steht ohne GTK in view()/header_state() und ist so per pytest pruefbar."""
from gi.repository import Adw, GLib, Gtk

from .api import APIError, client
from .i18n import _
from .store import store
from .util import bg, bg_serial, date_time_text, placeholder

CHECK_DELAY_MS = 300        # Pruefung des Namens erst, wenn das Tippen kurz ruht


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


def need_name(st):
    """Antwort von GET .../publish bzw. Fehler von PUT: zuerst einen oeffentlichen Namen waehlen?"""
    if isinstance(st, APIError):
        return bool(isinstance(st.data, dict) and st.data.get("need_name"))
    return isinstance(st, dict) and not st.get("name")


def suggested_name(st):
    if isinstance(st, APIError):
        st = st.data if isinstance(st.data, dict) else {}
    return norm((st or {}).get("suggested_name") or "")


def _short(t, n=28):
    t = str(t or "")
    return t if len(t) <= n else t[:n - 1] + "…"


def header_state(page):
    """Hinweis in der Kopfleiste fuer eine geladene Seite: None oder {label, tooltip, url, via}.
    Oeffentlich ueber eine Elternseite ("Unterseiten mit veroeffentlichen"): published_via, nur wenn nicht selbst."""
    if not isinstance(page, dict):
        return None
    if page.get("published"):
        return {"label": _("Public"), "tooltip": _("This page is public – click for settings"),
                "url": page.get("public_url") or "", "via": None}
    via = page.get("published_via")
    if isinstance(via, dict) and via.get("url"):
        t = str(via.get("title") or "")
        return {"label": _("Public (via “{title}”)").format(title=_short(t)),
                "tooltip": _("Public as a subpage of “{title}” – click for details").format(title=t),
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
    changed = published and typed != slug
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
        "slug_button_visible": published,
        "slug_button_sensitive": changed and bool(typed),
        "address_note": _("Only applied with “Change address”.") if changed else preview_url(st, typed),
        "address_changed": changed,
        "link": st.get("url") if published else None,
        "stop_visible": published,
        "profile_hint": _("Replaces your current profile page “{title}”.").format(title=po.get("title") or "") if po
        else _("Shown at your profile address – one page at most."),
        "home": bool(published and st.get("home")),
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
        lb = Gtk.Label(xalign=0, wrap=True, accessible_role=Gtk.AccessibleRole.STATUS)
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


def note_label(css=None):
    lb = Gtk.Label(xalign=0, wrap=True, selectable=True)
    for c in (css or "").split():
        lb.add_css_class(c)
    return lb


def confirm(parent, heading, body, label, ok, cancelled=None):
    """Rueckfrage (Adw.AlertDialog): Abbrechen ist Vorgabe und Esc"""
    d = Adw.AlertDialog(heading=heading, body=body)
    d.add_response("cancel", _("Cancel"))
    d.add_response("ok", label)
    d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
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


def globe_icon():
    """Symbol fuer "oeffentlich": globe-symbolic (neuere Adwaita-Symbole), sonst web-browser-symbolic"""
    try:
        from gi.repository import Gdk
        theme = Gtk.IconTheme.get_for_display(Gdk.Display.get_default())
        if theme.has_icon("globe-symbolic"):
            return "globe-symbolic"
    except (AttributeError, TypeError):
        pass
    return "web-browser-symbolic"


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
        super().__init__(title=_("Publish"), content_width=540, content_height=700)
        self.win, self.pid, self.st = win, pid, st or {}
        self.toasts = Adw.ToastOverlay()
        self.nav = Adw.NavigationView()
        self.toasts.set_child(self.nav)
        self.set_child(self.toasts)
        self.check = NameCheck(self._name_result)
        self.name_row = self.go_btn = self.main = None
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
        pg = Adw.PreferencesPage()
        g = Adw.PreferencesGroup(description=_("First choose your public name. It appears in every address of your public "
                                               "pages and is visible to everyone."))
        self.name_row = Adw.EntryRow(title=_("Public name"), text=suggestion or "")
        name_hints(self.name_row, 32)
        g.add(self.name_row)
        self.name_url = note_label("dim-label caption")
        self.name_status = status_label()
        g.add(self.name_url)
        g.add(self.name_status)
        hint = note_label("dim-label")
        hint.set_label(_("Not your username: pick something everyone may see. 2–32 characters, a–z, 0–9, - and _."))
        g.add(hint)
        for w in (self.name_url, self.name_status, hint):
            w.set_margin_top(6)
        pg.add(g)
        self.go_btn = Gtk.Button(label=_("Continue"), sensitive=False)
        self.go_btn.add_css_class("suggested-action")
        self.go_btn.connect("clicked", lambda *_: self._name_continue())
        origin = config.origin(config.settings.server)

        def changed(*_):
            v = norm(self.name_row.get_text())
            self.name_url.set_label(f"{origin}/@{v or '…'}")
            self.go_btn.set_sensitive(False)
            self.check.changed(v)
        self.name_row.connect("changed", changed)
        self.name_row.connect("entry-activated", lambda *_: self._name_continue())
        page = self._page(_("Public name"), pg, "name", self.go_btn)
        self.nav.replace([page])
        changed()
        GLib.idle_add(lambda: (self.name_row is not None and self.name_row.grab_focus(), False)[1])

    def _name_result(self, name, ok, text):
        if self.name_row is None or norm(self.name_row.get_text()) != name:
            return
        set_status(self.name_status, text, ok)
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
            set_status(self.name_status, str(e), False)
            self.go_btn.set_sensitive(True)
        bg(work, done=self.show_main, error=err)

    # --- Schritt 2: Einstellungen der Seite ----------------------------------------------------------------
    def show_main(self, st):
        self.st = st or {}
        self.check.cancel()
        self.name_row = None
        pg = Adw.PreferencesPage()
        # Hinweise: abgeschaltet, beendet/zurueckgezogen
        self.g_notes = Adw.PreferencesGroup()
        self.l_disabled = note_label("warning")
        self.l_withdrawn = note_label()
        self.g_notes.add(self.l_disabled)
        self.g_notes.add(self.l_withdrawn)
        self.l_via = note_label()                            # Text zu "oeffentlich ueber ..." (Zeile mit Link darunter)
        self.g_notes.add(self.l_via)
        for w in (self.l_withdrawn, self.l_via):
            w.set_margin_top(6)
        pg.add(self.g_notes)
        # Oeffentlich ueber eine Elternseite
        self.g_via = Adw.PreferencesGroup()
        self.via_row = Adw.ActionRow(title=_("Link"), subtitle_selectable=True)
        self.via_row.add_css_class("property")
        cp = Gtk.Button(label=_("Copy link"), valign=Gtk.Align.CENTER)
        cp.connect("clicked", lambda *_: self._copy((self.st.get("published_via") or {}).get("url")))
        self.via_open = Gtk.Button(valign=Gtk.Align.CENTER)
        self.via_open.connect("clicked", lambda *_: self._open_via())
        self.via_row.add_suffix(cp)
        self.via_row.add_suffix(self.via_open)
        self.g_via.add(self.via_row)
        vh = note_label("dim-label")
        vh.set_label(_("It only becomes private when “Also publish subpages” is turned off there or the page is moved."))
        vh.set_margin_top(6)
        self.g_via.add(vh)
        pg.add(self.g_via)
        # Schalter "Oeffentlich" und Adresse
        g = Adw.PreferencesGroup()
        self.sw = Adw.SwitchRow(title=_("Public"), subtitle=_("Anyone with the link can read this page without signing in."))
        self.sw.connect("notify::active", self._public_toggled)
        g.add(self.sw)
        self.slug = Adw.EntryRow(title=_("Address"), text=self.st.get("slug") or "")
        name_hints(self.slug, 60)
        self.slug_btn = Gtk.Button(label=_("Change address"), valign=Gtk.Align.CENTER)
        self.slug_btn.connect("clicked", lambda *_: self._change_slug())
        self.slug.add_suffix(self.slug_btn)
        self.slug.connect("changed", lambda *_: self.draw())
        self.slug.connect("entry-activated", lambda *_: self._change_slug())
        g.add(self.slug)
        self.l_addr = note_label("dim-label caption")
        self.l_addr.set_margin_top(6)
        g.add(self.l_addr)
        pg.add(g)
        # Optionen (vor dem Veroeffentlichen nur gemerkt, danach sofort gespeichert)
        g2 = Adw.PreferencesGroup()
        self.sw_children = Adw.SwitchRow(title=_("Also publish subpages"),
                                         subtitle=_("Subpages (and database entries) of this page will then be public too."))
        self.sw_index = Adw.SwitchRow(title=_("Allow search engines"),
                                      subtitle=_("Otherwise search engines are asked not to index the page."))
        self.sw_profile = Adw.SwitchRow(title=_("As profile page (/@name)"))
        for key, row in (("children", self.sw_children), ("indexable", self.sw_index), ("profile", self.sw_profile)):
            row.connect("notify::active", lambda r, _p, key=key: self._option_toggled(key, r))
            g2.add(row)
        pg.add(g2)
        # Link der oeffentlichen Seite
        self.g_link = Adw.PreferencesGroup()
        self.link_row = Adw.ActionRow(title=_("Link"), subtitle_selectable=True)
        self.link_row.add_css_class("property")
        cp2 = Gtk.Button(label=_("Copy link"), valign=Gtk.Align.CENTER)
        cp2.connect("clicked", lambda *_: self._copy(self.st.get("url")))
        op = Gtk.Button(label=_("Open"), valign=Gtk.Align.CENTER)
        op.connect("clicked", lambda *_: self.st.get("url") and self.win.open_uri(self.st["url"]))
        self.link_row.add_suffix(cp2)
        self.link_row.add_suffix(op)
        self.g_link.add(self.link_row)
        self.l_home = note_label("dim-label")
        self.l_home.set_label(_("This page is the home page for visitors."))
        self.l_home.set_margin_top(6)
        self.g_link.add(self.l_home)
        pg.add(self.g_link)
        # Beenden + Hinweis auf den oeffentlichen Namen
        self.g_end = Adw.PreferencesGroup()
        self.stop_row = button_row(_("Stop publishing"), self._stop, destructive=True)
        self.g_end.add(self.stop_row)
        pg.add(self.g_end)
        self.g_name = Adw.PreferencesGroup()
        self.l_name = note_label("dim-label")
        self.g_name.add(self.l_name)
        pg.add(self.g_name)
        self.main = pg
        self.nav.replace([self._page(_("Publish"), pg, "main")])
        self.draw(opts=True)
        GLib.idle_add(lambda: (self.sw.grab_focus(), False)[1])

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
                self.via_row.set_subtitle(GLib.markup_escape_text(via.get("url") or ""))
                self.via_open.set_label(_("Open “{title}”").format(title=_short(via.get("title"))))
            self.sw.set_title(v["switch_title"])
            self.sw.set_active(v["switch_active"])
            self.sw.set_sensitive(v["switch_sensitive"])
            self.slug_btn.set_visible(v["slug_button_visible"])
            self.slug_btn.set_sensitive(v["slug_button_sensitive"])
            self.l_addr.set_label(v["address_note"])
            self.l_addr.set_visible(bool(v["address_note"]))
            self.sw_profile.set_subtitle(v["profile_hint"])
            if opts:
                self.sw_children.set_active(bool(st.get("children")))
                self.sw_index.set_active(bool(st.get("indexable")))
                self.sw_profile.set_active(bool(st.get("profile")))
            self.g_link.set_visible(v["published"])
            self.link_row.set_subtitle(GLib.markup_escape_text(v["link"] or ""))
            self.l_home.set_visible(v["home"])
            self.g_end.set_visible(v["stop_visible"])
            self.l_name.set_label(v["name_note"])
        finally:
            self._drawing = False

    # --- Aktionen -------------------------------------------------------------------------------------------
    def _sync(self, ns, text=None):
        """Neuer Zustand vom Server: Dialog, offene Seiten (Kopfleiste, Menue) und Zwischenspeicher"""
        self.st = ns or {}
        for d in list(store.docs):
            if d.id == self.pid and d.fetched:
                apply_state(d.page, self.st)
        store.drop_cache(f"api/notes/{self.pid}")
        self.win.update_header()
        self.draw(opts=True)
        self.toast(text)

    def _failed(self, e):
        if need_name(e):                                  # Name inzwischen weg (anderes Geraet): Namensschritt
            self.show_name_step(suggested_name(e))
            return
        self.toast(str(e))
        self.draw(opts=True)

    def _send(self, method, body=None, text=None):
        # der Reihe nach (eine Warteschlange je Seite): schnelles Umschalten bleibt in Ordnung
        bg_serial(("publish", self.pid), client.json, method, self.path, body,
                  done=lambda ns: self._sync(ns, text(ns) if callable(text) else text), error=self._failed)

    def _put(self, body, done_text=None):
        if self.st.get("enabled", True) is False:
            self.toast(_("Publishing is turned off on this server."))
            self.draw(opts=True)
            return
        self._send("PUT", body, lambda ns: saved_text(ns, done_text))

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
            self._stop()

    def _option_toggled(self, key, row):
        if self._drawing or not self.st.get("published"):
            return                                          # noch nicht oeffentlich: gilt beim Veroeffentlichen
        self._put({key: row.get_active()})

    def _stop(self):
        if not self.st.get("published"):
            self.draw()
            return
        confirm(self, _("Stop publishing?"), _("The link will no longer work."), _("Stop publishing"),
                lambda: self._send("DELETE", None, _("No longer published.")), lambda: self.draw())

    def _change_slug(self):
        v = norm(self.slug.get_text())
        if not self.st.get("published") or not v or v == self.st.get("slug"):
            return
        confirm(self, _("Change address?"), _("The current link {url} will stop working.").format(url=self.st.get("url") or ""),
                _("Change address"), lambda: self._put({"slug": v}, _("✓ New address saved")))

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
class PublicNameGroup(Adw.PreferencesGroup):
    """Eigener Block mit eigenem Knopf; Pruefung beim Tippen; Rueckfrage beim Aendern, wenn schon Seiten oeffentlich
    sind (alte Links gehen dann nicht mehr). Aeltere Server (ohne api/account/public-name): Block bleibt unsichtbar."""

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
        self.check = NameCheck(self._result)
        self.row.connect("changed", lambda *_: (self.btn.set_sensitive(False), self.check.changed(self.row.get_text(), self.p.get("name"))))
        self.row.connect("entry-activated", lambda *_: self.save())
        self.connect("unrealize", lambda *_: self.check.cancel())
        bg(client.get, "api/account/public-name", done=self.fill, error=lambda e: None)

    def fill(self, p):
        self.p = p if isinstance(p, dict) else {}
        self.row.set_text(self.p.get("name") or "")
        placeholder(self.row, self.p.get("suggested") or "")
        self._hint()
        set_status(self.status, "")
        self.btn.set_sensitive(False)
        self.set_visible(True)

    def _hint(self):
        if self.p.get("name"):
            self.set_description(_("Appears in the addresses of your public pages ({url}). If you change it, old links stop working.").format(
                url=self.p.get("url") or ""))
        else:
            self.set_description(_("Appears in the addresses of your public pages (…/@name/…) and is visible to everyone – "
                                   "not your username. Set when you first publish."))

    def _result(self, name, ok, text):
        if norm(self.row.get_text()) != name:
            return
        set_status(self.status, text, ok)
        self.btn.set_sensitive(bool(ok))

    def save(self):
        v = norm(self.row.get_text())
        if not v or not self.btn.get_sensitive():
            return
        if self.p.get("name") and v != self.p["name"] and self.p.get("published"):
            confirm(self.dlg, _("Change your public name?"), _("Old links to your public pages will stop working."),
                    _("Save name"), lambda: self._save(v))
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
            set_status(self.status, _("✓ Saved"), True)

        def err(e):
            set_status(self.status, str(e), False)
            self.btn.set_sensitive(True)
        bg(client.json, "PUT", "api/account/public-name", {"name": v}, done=fin, error=err)
