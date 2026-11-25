"""(2.6, Bugs-DB 2027a-7) Adressbuch: Einstellungen > Kontakte - Liste mit Suche, Detail (feste Saga-Zeile mit "Nachricht",
tel:/mailto: fuer Nummern und Mails), Bearbeiten (Name fuer mich, Anrede -/du/Sie, Grussform, Nummern/Mails mit Typ), Neuer
Kontakt, Loeschen mit Rueckfrage, Landesvorwahl. Dazu "Kontakt bearbeiten ..." im Kontaktmenue der Personensuche/Gruppeninfo.

Vertrag: docs/APP-API.md im Server "Kontakte (2.6, Bugs-DB 2027a, contacts2)", docs/APP-API-CHAT.md 7 (contact_id).
GET/POST /api/contacts, GET/PATCH/DELETE /api/contacts/{id} (PATCH mit ver, 409 {error, contact} = anderswo geaendert,
409 {error, contact_id} = Wert gibt es schon), GET/PUT /api/contacts/settings {cc}, Live contacts.changed {id, deleted?}.
Nur mit contacts2 (/api/version bzw. /api/chat/me -> features); aeltere Server: alles wie bisher. "Name fuer mich" steht
nur hier, nie in Chats. Nummern und Mails nie ins Protokoll (clientlog) oder in Fehlermeldungen der App."""
import os
import urllib.parse

from gi.repository import Adw, GLib, GObject, Gtk

from .api import APIError, client
from .i18n import _, pgettext
from .util import bg

TYPES = ("mobile", "home", "work", "other")
FORMS = (None, "informal", "formal")
MAX_NAME, MAX_SALUTATION, MAX_ADDRS = 120, 80, 10

# Landesvorwahl aus der Region des Rechners (Vorschlag; der Server prueft): ISO-3166-Kuerzel -> Vorwahl ohne "+"
REGION_CC = {
    "DE": "49", "AT": "43", "CH": "41", "LI": "423", "LU": "352", "BE": "32", "NL": "31", "FR": "33", "IT": "39", "ES": "34",
    "PT": "351", "GB": "44", "IE": "353", "DK": "45", "SE": "46", "NO": "47", "FI": "358", "IS": "354", "PL": "48", "CZ": "420",
    "SK": "421", "HU": "36", "SI": "386", "HR": "385", "RO": "40", "BG": "359", "GR": "30", "TR": "90", "RU": "7", "UA": "380",
    "BY": "375", "KZ": "7", "EE": "372", "LV": "371", "LT": "370", "US": "1", "CA": "1", "MX": "52", "BR": "55", "AR": "54",
    "CL": "56", "CO": "57", "PE": "51", "AU": "61", "NZ": "64", "JP": "81", "KR": "82", "CN": "86", "HK": "852", "TW": "886",
    "SG": "65", "MY": "60", "ID": "62", "PH": "63", "VN": "84", "TH": "66", "IN": "91", "IL": "972", "AE": "971", "ZA": "27",
    "EG": "20", "MA": "212",
}


# --- Regeln (ohne Netz und Oberflaeche) -------------------------------------------------------------------------------
def has_feature(me=None):
    """Server kann das Adressbuch (contacts2 in /api/version bzw. /api/chat/me -> features)"""
    from .chatstatus import features
    from .store import store
    if store.has("contacts2"):
        return True
    if me is None:
        from .chat import chat
        me = chat.me
    return "contacts2" in features(me)


def region_cc(loc=None):
    """Landesvorwahl aus der Region des Rechners ("de_DE.UTF-8" -> "49") oder None. loc: Locale-Text (Tests)"""
    if loc is None:
        loc = next((os.environ.get(k) for k in ("LC_ALL", "LC_TELEPHONE", "LC_ADDRESS", "LANG") if os.environ.get(k)), "")
    base = str(loc or "").split(".", 1)[0].split("@", 1)[0]
    reg = base.split("_", 1)[1].upper() if "_" in base else ""
    return REGION_CC.get(reg)


def cc_norm(v):
    """Eingabe "49", "+49", " 0049 " -> "49"; leer -> None; ungueltig -> False"""
    s = str(v if v is not None else "").strip().replace(" ", "")
    if not s:
        return None
    s = s[1:] if s.startswith("+") else (s[2:] if s.startswith("00") else s)
    return s if s.isdigit() and 1 <= len(s) <= 3 and not s.startswith("0") else False


def cc_text(cc):
    return "+" + str(cc) if cc else ""


def is_national(v):
    """Nummer ohne Landesvorwahl ("0170 ...", nicht "0049 ...")"""
    s = "".join(ch for ch in str(v or "") if ch.isdigit() or ch == "+")
    return s.startswith("0") and not s.startswith("00")


def addrs_of(c, fixed=None, kind=None):
    out = [a for a in (c or {}).get("addrs") or [] if isinstance(a, dict) and isinstance(a.get("value"), str)]
    if fixed is not None:
        out = [a for a in out if bool(a.get("fixed")) == fixed]
    if kind is not None:
        out = [a for a in out if a.get("kind") == kind]
    return out


def type_label(t):
    return {"mobile": pgettext("phone type", "Mobile"), "home": pgettext("phone type", "Home"),
            "work": pgettext("phone type", "Work"), "other": pgettext("phone type", "Other")}.get(t, "")


def form_label(f):
    return {"informal": pgettext("form of address", "Informal"), "formal": pgettext("form of address", "Formal")}.get(f, "—")


def addr_href(a):
    """tel:/mailto: fuer Nummer bzw. Mail, sonst None"""
    a = a if isinstance(a, dict) else {}
    v = a.get("value") if isinstance(a.get("value"), str) else ""
    if a.get("kind") == "e164" and v.startswith("+") and v[1:].isdigit():
        return "tel:" + v
    if a.get("kind") == "email" and v.count("@") == 1 and " " not in v:
        return "mailto:" + urllib.parse.quote(v, safe="@.+-_")
    return None


def saga_of(c):
    s = (c or {}).get("saga")
    return s if isinstance(s, dict) and isinstance(s.get("ref"), str) and s["ref"] else None


def display_name(c):
    c = c if isinstance(c, dict) else {}
    n = c.get("name") if isinstance(c.get("name"), str) and c.get("name").strip() else ""
    if n:
        return n
    first = addrs_of(c, fixed=False)
    return first[0]["value"] if first else "?"


def saga_sub(s):
    """Unterzeile der Saga-Adresse (wie Kontakte in Chats): fremd "Person auf host", weg "nicht erreichbar" """
    if not s:
        return ""
    host = s.get("host") or ""
    if s.get("gone"):
        return _("Server {host} not connected").format(host=host) if s.get("remote") else _("Account no longer active")
    if s.get("remote"):
        return _("Person on {host}").format(host=host)
    return " · ".join(x for x in (str(s.get("sub") or ""), "" if s.get("ready", True) else _("hasn't set up chats yet")) if x)


def list_sub(c):
    """Unterzeile in der Liste: Saga-Angabe bzw. erste Nummer/Mail"""
    s = saga_of(c)
    priv = addrs_of(c, fixed=False)
    rest = [a["value"] for a in priv if a["value"] != display_name(c)]
    if s:
        return saga_sub(s) or (rest[0] if rest else _("Saga"))
    return rest[0] if rest else ""


def contact_ok(c):
    return isinstance(c, dict) and isinstance(c.get("id"), int) and not isinstance(c.get("id"), bool)


def edit_body(name, form, salutation, rows, ver=None, cc=None, new=False):
    """Felder des Bearbeitens -> Anfrage (POST bzw. PATCH). rows: [(kind, value, type)] der privaten Adressen (leere fallen weg,
    doppelte zaehlen einmal); feste Adressen schickt die App nicht (bleiben immer)."""
    name = (name or "").strip()[:MAX_NAME]
    sal = (salutation or "").strip()[:MAX_SALUTATION]
    seen, addrs = set(), []
    for kind, value, typ in rows:
        v = (value or "").strip()
        if kind not in ("e164", "email") or not v or (kind, v.lower()) in seen:
            continue
        seen.add((kind, v.lower()))
        a = {"kind": kind, "value": v}
        if typ in TYPES:
            a["type"] = typ
        addrs.append(a)
    b = {"own_name": name or None, "form": form if form in ("informal", "formal") else None, "salutation": sal or None,
         "addrs": addrs}
    if new:
        for k in ("form", "salutation"):
            if b[k] is None:
                b.pop(k)
    elif ver is not None:
        b["ver"] = ver
    if cc and any(a["kind"] == "e164" and is_national(a["value"]) for a in addrs):
        b["cc"] = str(cc)
    return b


def edit_problem(body, manual):
    """Pruefung vor dem Senden (der Server prueft genau) -> Text oder None"""
    if manual and not body.get("own_name"):
        return _("Please enter a name.")
    if manual and not body.get("addrs"):
        return _("Please enter at least one phone number or email address.")
    for kind, msg in (("e164", _("At most 10 phone numbers per contact.")), ("email", _("At most 10 email addresses per contact."))):
        if len([a for a in body.get("addrs") or [] if a["kind"] == kind]) > MAX_ADDRS:
            return msg
    return None


def conflict_of(e):
    """409 eines PATCH -> ("changed", kontakt) | ("duplicate", contact_id) | None"""
    d = getattr(e, "data", None)
    if not isinstance(e, APIError) or e.code != 409 or not isinstance(d, dict):
        return None
    if contact_ok(d.get("contact")):
        return ("changed", d["contact"])
    cid = d.get("contact_id")
    if isinstance(cid, int) and not isinstance(cid, bool):
        return ("duplicate", cid)
    return None


def launch_uri(uri, parent=None):
    """tel:/mailto: im System oeffnen (Tests ersetzen das)"""
    Gtk.UriLauncher.new(uri).launch(parent, None, None)


# --- Stand und Server-Zugriff -------------------------------------------------------------------------------------------
class ContactBook(GObject.Object):
    """Signal "changed" (id, deleted): Live contacts.changed bzw. eigene Aenderung; id 0 = alles neu laden"""
    __gsignals__ = {"changed": (GObject.SignalFlags.RUN_FIRST, None, (GObject.TYPE_INT64, bool))}

    def __init__(self):
        super().__init__()
        self.cc = None               # Landesvorwahl des Kontos (GET /api/contacts/settings) oder None
        self.cc_known = False

    def available(self):
        return has_feature()

    def list(self, q, done, error=None, limit=200):
        p = f"api/contacts?limit={int(limit)}" + ("&q=" + urllib.parse.quote(q) if q else "")

        def fin(j):
            j = j if isinstance(j, dict) else {}
            done([c for c in j.get("contacts") or [] if contact_ok(c)], j.get("total"))
        bg(client.get, p, done=fin, error=error or (lambda e: done([], 0)))

    def get(self, cid, done, error=None):
        bg(client.get, f"api/contacts/{int(cid)}", done=lambda j: done(j if contact_ok(j) else None),
           error=error or (lambda e: done(None)))

    def create(self, body, done, error):
        def fin(j):
            if contact_ok(j):
                self.emit("changed", j["id"], False)
            done(j)
        bg(client.json, "POST", "api/contacts", body, done=fin, error=error)

    def update(self, cid, body, done, error):
        """PATCH mit ver; Fehler an error (409: conflict_of(e))"""
        def fin(j):
            if contact_ok(j):
                self.emit("changed", j["id"], False)
            done(j)
        bg(client.json, "PATCH", f"api/contacts/{int(cid)}", body, done=fin, error=error)

    def delete(self, cid, done, error):
        def fin(_j):
            self.emit("changed", int(cid), True)
            done()
        bg(client.json, "DELETE", f"api/contacts/{int(cid)}", None, done=fin, error=error)

    def load_settings(self, done=None):
        def fin(j):
            v = cc_norm((j or {}).get("cc")) if isinstance(j, dict) else None
            self.cc, self.cc_known = (v or None), True
            if done:
                done(self.cc)
        bg(client.get, "api/contacts/settings", done=fin, error=lambda e: done and done(self.cc))

    def put_cc(self, cc, done=None, error=None):
        def fin(j):
            v = cc_norm(j.get("cc")) if isinstance(j, dict) else cc
            self.cc = v or None
            self.cc_known = True
            if done:
                done(self.cc)
        bg(client.json, "PUT", "api/contacts/settings", {"cc": cc}, done=fin, error=error)

    def cc_suggest(self):
        """Landesvorwahl fuer nationale Nummern: die des Kontos, sonst die der Region des Rechners"""
        return self.cc or region_cc()

    def on_live(self, typ, d):
        d = d if isinstance(d, dict) else {}
        if typ == "contacts.changed":
            cid = d.get("id")
            self.emit("changed", cid if isinstance(cid, int) and not isinstance(cid, bool) else 0, bool(d.get("deleted")))
        elif typ in ("hello", "reset"):
            self.emit("changed", 0, False)


book = ContactBook()


# --- Oberflaeche -----------------------------------------------------------------------------------------------------------
def _toolbar(title, child, start=None, end=None):
    tv = Adw.ToolbarView()
    hb = Adw.HeaderBar()
    if start is not None:
        hb.pack_start(start)
        hb.set_show_start_title_buttons(False)
        hb.set_show_back_button(False)            # "Abbrechen" statt Zurueck-Pfeil
    if end is not None:
        hb.pack_end(end)
        hb.set_show_end_title_buttons(False)
    tv.add_top_bar(hb)
    tv.set_content(child)
    return tv


def _icon_btn(icon, tip, cb):
    b = Gtk.Button(icon_name=icon, tooltip_text=tip, valign=Gtk.Align.CENTER)
    b.add_css_class("flat")
    b.update_property([Gtk.AccessibleProperty.LABEL], [tip])
    b.connect("clicked", lambda *_: cb())
    return b


def _avatar(c, size):
    from . import avatars
    s = saga_of(c) or {}
    return avatars.person({"name": display_name(c), "avatar": s.get("avatar")}, size, text=display_name(c))


def _esc(s):
    return GLib.markup_escape_text(str(s or ""))


class Host:
    """Wo Detail und Bearbeiten erscheinen: in den Einstellungen (Unterseiten) oder als eigener Dialog (Kontaktmenue)"""

    def __init__(self, dlg, win, toast, toast_after=None):
        self.dlg, self.win, self._toast = dlg, win, toast
        self._after = toast_after or toast

    def toast(self, t):
        if t:
            self._toast(str(t))

    def toast_after(self, t):
        """Meldung nach dem Schliessen (eigener Dialog: im Fenster)"""
        if t:
            self._after(str(t))

    _n = 0

    def push(self, title, child, tag):
        Host._n += 1                               # Tags muessen eindeutig sein (auch eine gerade geschlossene Seite zaehlt)
        nav = Adw.NavigationPage(title=title, child=child, tag=f"{tag}-{Host._n}")
        self.dlg.push_subpage(nav)
        return nav

    def pop(self):
        self.dlg.pop_subpage()

    def close_all(self):
        self.dlg.close()


class ContactsPage(Adw.PreferencesPage):
    """Einstellungen > Kontakte: Suche, "Neuer Kontakt", Liste (zuletzt zuerst), Landesvorwahl. Unsichtbar ohne contacts2."""

    def __init__(self, dlg):
        super().__init__(title=_("Contacts"), visible=book.available())
        self.dlg = dlg
        self.host = Host(dlg, getattr(dlg, "win", None), lambda t: dlg.add_toast(Adw.Toast(title=_esc(t), timeout=4)))
        self.rows = []
        self.gen = 0
        self._t = 0
        self.loaded = False
        self.items = []
        g0 = Adw.PreferencesGroup(description=_("People from your chats and contacts you added yourself – with your own name for "
                                                "them, phone numbers and email addresses. Only you can see them."))
        self.new_btn = Gtk.Button(label=_("New contact"), valign=Gtk.Align.CENTER)
        self.new_btn.add_css_class("suggested-action")
        self.new_btn.connect("clicked", lambda *_: self.new_contact())
        g0.set_header_suffix(self.new_btn)
        self.search = Gtk.SearchEntry(placeholder_text=_("Search contacts"), hexpand=True)
        self.search.update_property([Gtk.AccessibleProperty.LABEL], [_("Search contacts")])
        self.search.connect("search-changed", lambda *_: self._soon())
        g0.add(self.search)
        self.add(g0)
        self.g_list = Adw.PreferencesGroup()
        self.add(self.g_list)
        self.g_cc = Adw.PreferencesGroup(title=_("Country code"),
                                         description=_("Used for phone numbers you enter without a country code, e.g. 0170 …"))
        self.cc_row = Adw.EntryRow(title=_("Country code for national numbers"), show_apply_button=True)
        self.cc_row.set_input_purpose(Gtk.InputPurpose.PHONE)
        self.cc_row.connect("apply", lambda *_: self._cc_apply(self.cc_row.get_text()))
        self.g_cc.add(self.cc_row)
        self.cc_hint = Adw.ActionRow(title="", visible=False)
        self.cc_use = Gtk.Button(valign=Gtk.Align.CENTER)
        self.cc_use.connect("clicked", lambda *_: self._cc_apply(region_cc()))
        self.cc_hint.add_suffix(self.cc_use)
        self.g_cc.add(self.cc_hint)
        self.add(self.g_cc)
        from .store import store
        self._hs = [(store, store.connect("version-changed", lambda *_: self._feature())),
                    (book, book.connect("changed", lambda _b, cid, dl: self._live(cid, dl)))]
        self.connect("destroy", lambda *_: [o.disconnect(h) for o, h in self._hs])
        self.connect("map", lambda *_: self.load())

    def _feature(self):
        on = book.available()
        if on != self.get_visible():
            self.set_visible(on)

    def _live(self, cid, deleted):
        if self.loaded and self.get_mapped():
            self._soon(400)
        elif self.loaded:
            self.loaded = False                   # beim naechsten Zeigen neu laden

    def _soon(self, ms=250):
        if self._t:
            GLib.source_remove(self._t)

        def go():
            self._t = 0
            self.load()
            return False
        self._t = GLib.timeout_add(ms, go)

    def load(self):
        if not book.available():
            return
        self.gen += 1
        gen = self.gen
        q = self.search.get_text().strip()

        def fin(cs, total):
            if gen != self.gen:
                return
            self.loaded = True
            self.fill(cs, q)
        book.list(q, fin, error=lambda e: gen == self.gen and self.fill([], q, err=str(e)))
        if not book.cc_known:
            book.load_settings(lambda _cc: self._cc_fill())
        else:
            self._cc_fill()

    def fill(self, cs, q="", err=None):
        for r in self.rows:
            self.g_list.remove(r)
        self.rows = []
        self.items = list(cs)
        if err or not cs:
            t = err or (_("No matches.") if q else _("No contacts yet. People you chat with appear here; you can also add "
                                                    "contacts with a phone number or email address."))
            r = Adw.ActionRow(title=_esc(t), activatable=False)
            r.add_css_class("dim-label")
            self.rows.append(r)
            self.g_list.add(r)
            return
        for c in cs:
            r = Adw.ActionRow(title=_esc(display_name(c)), subtitle=_esc(list_sub(c)), activatable=True)
            r.add_prefix(_avatar(c, 32))
            r.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
            s = saga_of(c)
            if s and s.get("gone"):
                r.add_css_class("hk-gone")
            r.connect("activated", lambda *_, c=c: self.open(c["id"], c))
            r.hk_contact = c
            self.rows.append(r)
            self.g_list.add(r)

    def _cc_fill(self):
        self.cc_row.set_text(cc_text(book.cc))
        sug = region_cc()
        show = not book.cc and bool(sug)
        self.cc_hint.set_visible(show)
        if show:
            self.cc_hint.set_title(_esc(_("Suggested for this computer’s region: {cc}").format(cc=cc_text(sug))))
            self.cc_use.set_label(_("Use {cc}").format(cc=cc_text(sug)))

    def _cc_apply(self, text):
        v = cc_norm(text)
        if v is False:
            self.host.toast(_("Please enter a country code with 1 to 3 digits, e.g. +49."))
            return
        book.put_cc(v, done=lambda _cc: (self._cc_fill(), self.host.toast(_("Country code saved."))),
                    error=lambda e: self.host.toast(e))

    # Detail / Bearbeiten
    def open(self, cid, c=None):
        return ContactDetail(self.host, cid, c).show()

    def new_contact(self):
        return ContactEditor(self.host, None).show()


class ContactDetail:
    """Detail eines Kontakts: Kopf (Bild, Name), feste Saga-Zeile mit "Nachricht", Nummern (tel:) und Mails (mailto:), Anrede,
    Grussform; "Bearbeiten" im Kopf, "Kontakt loeschen" mit Rueckfrage."""

    def __init__(self, host, cid, c=None):
        self.host, self.cid, self.c = host, int(cid), c if contact_ok(c) else None
        self.nav = None
        self.page = Adw.PreferencesPage()
        self.groups = []
        self.edit_btn = Gtk.Button(label=_("Edit"), valign=Gtk.Align.CENTER, sensitive=False)
        self.edit_btn.connect("clicked", lambda *_: self.edit())
        self._h = book.connect("changed", lambda _b, cid, dl: self._live(cid, dl))
        self.page.connect("destroy", lambda *_: book.disconnect(self._h))

    def show(self):
        self.nav = self.host.push(_("Contact"), _toolbar(_("Contact"), self.page, end=self.edit_btn), "contact")
        self.nav.hk_detail = self
        if self.c:
            self.fill(self.c)
        self.reload()
        return self

    def reload(self):
        book.get(self.cid, self._got)

    def _got(self, c):
        if c is None:
            self.fill(None)
            return
        self.c = c
        self.fill(c)

    def _live(self, cid, deleted):
        if cid not in (0, self.cid):
            return
        if deleted:
            self.fill(None)
            return
        self.reload()

    def fill(self, c):
        for g in self.groups:
            self.page.remove(g)
        self.groups = []
        self.edit_btn.set_sensitive(c is not None)
        if c is None:
            g = Adw.PreferencesGroup()
            g.add(Adw.StatusPage(icon_name="avatar-default-symbolic", title=_esc(_("Contact not found")),
                                 description=_esc(_("It may have been deleted on another device."))))
            self._add(g)
            return
        name = display_name(c)
        head = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, halign=Gtk.Align.CENTER, margin_bottom=6)
        head.append(_avatar(c, 80))
        t = Gtk.Label(label=name, wrap=True, justify=Gtk.Justification.CENTER, selectable=True)
        t.add_css_class("title-2")
        head.append(t)
        self.title_lb = t
        gh = Adw.PreferencesGroup()
        gh.add(head)
        self._add(gh)
        s = saga_of(c)
        if s:
            g = Adw.PreferencesGroup(title=_("Saga"))
            r = Adw.ActionRow(title=_esc(_("Saga address")), subtitle=_esc(saga_sub(s) or _("From a shared chat")))
            r.add_prefix(Gtk.Image.new_from_icon_name("system-users-symbolic"))
            msg = Gtk.Button(label=_("Message"), valign=Gtk.Align.CENTER, sensitive=not s.get("gone"))
            msg.add_css_class("suggested-action")
            msg.connect("clicked", lambda *_: self.message())
            r.add_suffix(msg)
            self.msg_btn = msg
            if s.get("gone"):
                r.set_tooltip_text(_("Currently unreachable"))
            g.add(r)
            self._add(g)
        for kind, title, icon, act in (("e164", _("Phone numbers"), "call-start-symbolic", _("Call")),
                                       ("email", _("Email addresses"), "mail-unread-symbolic", _("Write email"))):
            al = addrs_of(c, fixed=False, kind=kind)
            if not al:
                continue
            g = Adw.PreferencesGroup(title=title)
            for a in al:
                href = addr_href(a)
                r = Adw.ActionRow(title=_esc(a["value"]), subtitle=_esc(type_label(a.get("type"))), activatable=bool(href))
                if hasattr(r, "set_subtitle_selectable"):
                    r.set_subtitle_selectable(True)
                if href:
                    r.add_suffix(_icon_btn(icon, act, lambda h=href: self.launch(h)))
                    r.connect("activated", lambda *_, h=href: self.launch(h))
                r.add_suffix(_icon_btn("edit-copy-symbolic", _("Copy"), lambda v=a["value"]: self.copy(v)))
                r.hk_href = href
                g.add(r)
            self._add(g)
        if c.get("form") in ("informal", "formal") or c.get("salutation"):
            g = Adw.PreferencesGroup()
            if c.get("form") in ("informal", "formal"):
                g.add(Adw.ActionRow(title=_esc(_("Form of address")), subtitle=_esc(form_label(c["form"]))))
            if c.get("salutation"):
                g.add(Adw.ActionRow(title=_esc(_("Greeting")), subtitle=_esc(c["salutation"])))
            self._add(g)
        g = Adw.PreferencesGroup()
        if c.get("own_name") and s:
            g.set_description(_("“{name}” is your own name for this person – only you see it, here in your contacts.").format(name=c["own_name"]))
        dr = Adw.ButtonRow(title=_("Delete contact"))
        dr.add_css_class("destructive-action")
        dr.connect("activated", lambda *_: self.ask_delete())
        g.add(dr)
        self._add(g)

    def _add(self, g):
        self.page.add(g)
        self.groups.append(g)

    def launch(self, href):
        launch_uri(href, self.page.get_root())

    def copy(self, v):
        self.page.get_clipboard().set(v)
        self.host.toast(_("Copied"))

    def edit(self):
        if self.c:
            ContactEditor(self.host, self.c, on_saved=self._saved).show()

    def _saved(self, c):
        self.c = c
        self.fill(c)

    def message(self):
        """Direkt-Chat mit der Saga-Adresse: lokal {user: ~x}, fremd {handle: ~x@host}"""
        from .chat import chat
        s = saga_of(self.c)
        win = self.host.win
        if not s or win is None:
            return
        if not chat.ready:
            self.host.close_all()
            win.show_root(("chats",))
            return
        body = {"kind": "dm", "handle": s["ref"]} if s.get("remote") else {"kind": "dm", "user": s["ref"]}

        def done(ch):
            self.host.close_all()
            if (ch or {}).get("id") is not None:
                win.show_route(("chat", ch["id"]))
        chat.new_chat(body, done, lambda e: self.host.toast(e))

    def ask_delete(self):
        name = display_name(self.c)
        body = _("{name} is removed from your contacts together with all phone numbers and email addresses.").format(name=name)
        if saga_of(self.c):
            body += " " + _("Until you chat again, {name} also no longer appears in your chat suggestions.").format(name=name)
        d = Adw.AlertDialog(heading=_("Delete contact?"), body=body)
        d.add_response("cancel", _("Cancel"))
        d.add_response("delete", _("Delete"))
        d.set_response_appearance("delete", Adw.ResponseAppearance.DESTRUCTIVE)
        d.set_default_response("cancel")
        d.set_close_response("cancel")
        d.connect("response", lambda _d, r: r == "delete" and self.delete())
        d.present(self.page)
        self.alert = d
        return d

    def delete(self):
        name = display_name(self.c)

        def done():
            self.host.toast(_("{name} deleted.").format(name=name))
            if self.nav is not None and self.nav.get_parent() is not None:
                self.host.pop()
        book.delete(self.cid, done, lambda e: self.host.toast(e))


class ContactEditor:
    """Bearbeiten bzw. Neuer Kontakt: Name fuer mich, Anrede (-/du/Sie), Grussform, Nummern und Mails mit Typ. Speichern mit ver;
    409 anderswo geaendert -> neuen Stand (ver, feste Adressen) uebernehmen, Eingabe bleibt; 409 doppelt -> Hinweis mit
    "Kontakt zeigen"."""

    def __init__(self, host, c, on_saved=None):
        self.host = host
        self.c = c if contact_ok(c) else None
        self.on_saved = on_saved
        self.manual = self.c is None or self.c.get("origin") == "manual" or saga_of(self.c) is None
        self.nav = None
        self.busy = False
        self.addr_rows = {"e164": [], "email": []}
        self.page = Adw.PreferencesPage()
        self.cancel_btn = Gtk.Button(label=_("Cancel"))
        self.cancel_btn.connect("clicked", lambda *_: self.close())
        self.save_btn = Gtk.Button(label=_("Save"))
        self.save_btn.add_css_class("suggested-action")
        self.save_btn.connect("clicked", lambda *_: self.save())
        # Hinweis bei Fehlern (409, 400) oben
        self.g_msg = Adw.PreferencesGroup(visible=False)
        self.msg = Gtk.Label(xalign=0, wrap=True)
        self.msg.add_css_class("error")
        bx = Gtk.Box(spacing=12)
        bx.append(self.msg)
        self.msg.set_hexpand(True)
        self.jump = Gtk.Button(label=_("Show contact"), valign=Gtk.Align.CENTER, visible=False)
        self.jump.connect("clicked", lambda *_: self._jump())
        bx.append(self.jump)
        self.g_msg.add(bx)
        self.page.add(self.g_msg)
        self.jump_id = None
        # Name, Anrede, Grussform
        g = Adw.PreferencesGroup(description=_("Only you see this name – in your contacts, not in chats."))
        self.name = Adw.EntryRow(title=_("Name") if self.manual else _("Name for me"))
        g.add(self.name)
        self.form = Adw.ComboRow(title=_("Form of address"),
                                 model=Gtk.StringList.new(["—", form_label("informal"), form_label("formal")]))
        g.add(self.form)
        self.sal = Adw.EntryRow(title=_("Greeting"))
        from .util import placeholder
        placeholder(self.sal, _("e.g. “Dear Anna”"))
        g.add(self.sal)
        self.page.add(g)
        # feste Saga-Adresse (nur Anzeige)
        self.g_saga = Adw.PreferencesGroup(title=_("Saga"), visible=False)
        self.saga_row = Adw.ActionRow(title=_esc(_("Saga address")), subtitle=_esc(_("From a shared chat – can’t be changed")))
        self.saga_row.add_prefix(Gtk.Image.new_from_icon_name("system-users-symbolic"))
        self.g_saga.add(self.saga_row)
        self.page.add(self.g_saga)
        # Nummern, Mails
        self.g_addr = {}
        for kind, title, add in (("e164", _("Phone numbers"), _("Add phone number")), ("email", _("Email addresses"), _("Add email address"))):
            ga = Adw.PreferencesGroup(title=title)
            b = Adw.ButtonRow(title=add, start_icon_name="list-add-symbolic")
            b.connect("activated", lambda *_, k=kind: self.add_addr(k, "", "mobile" if k == "e164" else "other", focus=True))
            ga.add(b)
            ga.hk_add = b
            self.g_addr[kind] = ga
            self.page.add(ga)
        self.cc = book.cc_suggest()
        self._cc_desc()
        if self.c:
            self.take(self.c, fields=True)
        else:
            self.add_addr("e164", "", "mobile")

    def _cc_desc(self):
        self.g_addr["e164"].set_description(
            _("Numbers without a country code get {cc}.").format(cc=cc_text(self.cc)) if self.cc
            else _("Please enter numbers with the country code, e.g. +49 170 1234567."))

    @property
    def title(self):
        return _("New contact") if self.c is None else _("Edit contact")

    def show(self):
        self.nav = self.host.push(self.title, _toolbar(self.title, self.page, start=self.cancel_btn, end=self.save_btn), "contact-edit")
        self.nav.hk_editor = self
        GLib.idle_add(lambda: self.name.grab_focus() and False)
        if not book.cc_known:
            book.load_settings(lambda _cc: (setattr(self, "cc", book.cc_suggest()), self._cc_desc()))
        return self

    def close(self):
        if self.nav is not None and self.nav.get_parent() is not None:
            self.host.pop()

    # Felder
    def take(self, c, fields):
        """Kontakt uebernehmen; fields=False: nur ver und feste Adressen (409 - die Eingabe bleibt)"""
        self.c = c
        s = saga_of(c)
        self.g_saga.set_visible(bool(s))
        if s:
            self.saga_row.set_subtitle(_esc(saga_sub(s) or _("From a shared chat – can’t be changed")))
        if not fields:
            return
        self.name.set_text(c.get("own_name") or "")
        self.form.set_selected(FORMS.index(c.get("form")) if c.get("form") in FORMS else 0)
        self.sal.set_text(c.get("salutation") or "")
        for kind in self.addr_rows:
            for r in list(self.addr_rows[kind]):
                self._drop_addr(kind, r)
        for a in addrs_of(c, fixed=False):
            if a.get("kind") in self.addr_rows:
                self.add_addr(a["kind"], a["value"], a.get("type"))

    def add_addr(self, kind, value, typ, focus=False):
        ga = self.g_addr[kind]
        if len(self.addr_rows[kind]) >= MAX_ADDRS:
            self.show_msg(_("At most 10 phone numbers per contact.") if kind == "e164" else _("At most 10 email addresses per contact."))
            return None
        r = Adw.EntryRow(title=_("Phone number") if kind == "e164" else _("Email address"), text=value or "")
        r.set_input_purpose(Gtk.InputPurpose.PHONE if kind == "e164" else Gtk.InputPurpose.EMAIL)
        dd = Gtk.DropDown(model=Gtk.StringList.new([type_label(t) for t in TYPES]), valign=Gtk.Align.CENTER)
        dd.set_selected(TYPES.index(typ) if typ in TYPES else TYPES.index("other"))
        dd.update_property([Gtk.AccessibleProperty.LABEL], [_("Type")])
        dd.add_css_class("flat")
        r.add_suffix(dd)
        rm = _icon_btn("user-trash-symbolic", _("Remove"), lambda: self._drop_addr(kind, r))
        r.add_suffix(rm)
        r.hk_type = dd
        r.hk_kind = kind
        ga.remove(ga.hk_add)
        ga.add(r)
        ga.add(ga.hk_add)
        self.addr_rows[kind].append(r)
        if focus:
            GLib.idle_add(lambda: r.grab_focus() and False)
        return r

    def _drop_addr(self, kind, r):
        if r in self.addr_rows[kind]:
            self.addr_rows[kind].remove(r)
            self.g_addr[kind].remove(r)

    def rows(self):
        return [(k, r.get_text(), TYPES[r.hk_type.get_selected()] if r.hk_type.get_selected() < len(TYPES) else None)
                for k in ("e164", "email") for r in self.addr_rows[k]]

    def body(self):
        new = self.c is None
        return edit_body(self.name.get_text(), FORMS[self.form.get_selected()] if self.form.get_selected() < 3 else None,
                         self.sal.get_text(), self.rows(), ver=None if new else self.c.get("ver"), cc=self.cc, new=new)

    def show_msg(self, t, jump=None):
        self.jump_id = jump
        self.jump.set_visible(jump is not None)
        self.msg.set_label(str(t or ""))
        self.g_msg.set_visible(bool(t))

    # Speichern
    def save(self):
        if self.busy:
            return
        b = self.body()
        p = edit_problem(b, self.manual)
        if p:
            self.show_msg(p)
            return
        self.show_msg(None)
        self.busy = True
        self.save_btn.set_sensitive(False)
        if "cc" in b and not book.cc and self.cc:
            book.put_cc(self.cc, error=lambda e: None)          # Vorschlag aus der Region einmal fuer das Konto merken
        if self.c is None:
            book.create(b, self._done, self._err)
        else:
            book.update(self.c["id"], b, self._done, self._err)

    def _done(self, c):
        self.busy = False
        self.save_btn.set_sensitive(True)
        new = self.c is None
        self.c = c if contact_ok(c) else self.c
        self.close()
        self.host.toast_after(_("Contact saved."))
        if self.on_saved and contact_ok(c):
            self.on_saved(c)
        elif new and contact_ok(c) and isinstance(self.host.dlg, Adw.PreferencesDialog):
            ContactDetail(self.host, c["id"], c).show()

    def _err(self, e):
        self.busy = False
        self.save_btn.set_sensitive(True)
        k = conflict_of(e)
        if k and k[0] == "changed":
            self.take(k[1], fields=False)
            if self.on_saved:
                self.on_saved(k[1])
            self.show_msg(_("This contact was changed on another device. The current version has been loaded – check your "
                            "entries and save again."))
            return
        if k and k[0] == "duplicate":
            self.show_msg(_("This phone number or email address is already saved in another contact."), jump=k[1])
            return
        self.show_msg(str(e))

    def _jump(self):
        if self.jump_id is not None:
            ContactDetail(self.host, self.jump_id).show()


# --- Einstieg aus dem Kontaktmenue (Personensuche, Gruppeninfo) ---------------------------------------------------------
class ContactDialog(Adw.Dialog):
    """Eigener Dialog fuer "Kontakt bearbeiten ..." ueber einem anderen Dialog: Navigation mit Bearbeiten (und Detail)"""

    def __init__(self, win):
        super().__init__(title=_("Edit contact"), content_width=520, content_height=680)
        self.toasts = Adw.ToastOverlay()
        self.navv = Adw.NavigationView()
        self.toasts.set_child(self.navv)
        self.set_child(self.toasts)
        self.win = win
        self.host = Host(self, win, lambda t: self.toasts.add_toast(Adw.Toast(title=_esc(t), timeout=4)),
                         toast_after=lambda t: win.toast(t) if hasattr(win, "toast") else None)

    def push_subpage(self, nav):
        self.navv.push(nav)

    def pop_subpage(self):
        if self.navv.get_navigation_stack().get_n_items() > 1:
            self.navv.pop()
        else:
            self.close()


def edit_contact(widget, cid):
    """"Kontakt bearbeiten ..." -> Kontakt laden, Bearbeiten im eigenen Dialog (ueber dem aktuellen)"""
    root = widget.get_root() if widget is not None else None
    win = root if isinstance(root, Gtk.Window) else None
    d = ContactDialog(win)
    d.present(widget)

    def got(c):
        if c is None:
            d.host.toast(_("Contact not found"))
            d.close()
            return
        ContactEditor(d.host, c).show()
    book.get(cid, got)
    return d
