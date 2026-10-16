"""Kommentare an Bloecken (Server ab 1.3), dezent: am Block nur eine kleine Zahl am Rand (nur bei offenen Unterhaltungen),
die Unterhaltungen in einer Seitenleiste rechts (Adw.OverlaySplitView der Seite; schmal als Overlay).
Antworten, eigene bearbeiten/loeschen (⋯-Menue), "✓ Erledigt"/"Wieder öffnen", Schalter "Erledigte zeigen" am Listenende,
ungelesen = kleiner Punkt neben dem Autor, gesehen wird beim Anzeigen gemeldet. Rolle Lesen: lesen, nicht schreiben.
Wortlaut wie im Web (static/comments.js).
Schnittstelle: GET/POST api/notes/{id}/comments (?resolved=1), PATCH/DELETE api/comments/{id} (?all=1),
POST api/comments/{id}/resolve {resolved}, POST api/comments/{id}/seen.
Die Logik (Zustand -> Anzeige) steht ohne GTK in Thread-Funktionen und CommentState und ist so per pytest pruefbar."""
import re

from gi.repository import Adw, Gdk, GLib, Gtk, Pango

from .api import client
from .i18n import _, ngettext
from .sharing import RANK
from .util import bg, date_time_text, rel_time

COMMENT_MAX = 2000
COUNT_FROM = COMMENT_MAX - 200          # Zaehler "n von 2000" erst kurz vor der Grenze
URL_RE = re.compile(r"\bhttps?://[^\s<>\"']+")


# =====================================================================================================
# Logik ohne GTK
# =====================================================================================================
def linkify_markup(text):
    """Klartext -> Pango-Markup mit Links (nur http/https; Satzzeichen am Ende gehoeren nicht zum Link)"""
    out, last = [], 0
    text = text or ""
    for m in URL_RE.finditer(text):
        u = re.sub(r"[).,;:!?\]]+$", "", m.group(0))
        if not u:
            continue
        out.append(GLib.markup_escape_text(text[last:m.start()]))
        e = GLib.markup_escape_text(u)
        out.append(f'<a href="{e}">{e}</a>')
        last = m.start() + len(u)
    out.append(GLib.markup_escape_text(text[last:]))
    return "".join(out)


def author(cm):
    a = (cm or {}).get("author") or {}
    return _("You") if a.get("me") else (a.get("name") or "")


def open_threads(threads):
    return [t for t in threads or [] if not t.get("resolved_at")]


def block_counts(threads):
    """({block_id: offene Unterhaltungen}, {block_id mit ungelesenen}) - fuer die Zahl am Block"""
    per, unread = {}, set()
    for t in open_threads(threads):
        b = t.get("block_id")
        if not b:
            continue
        per[b] = per.get(b, 0) + 1
        if t.get("unread"):
            unread.add(b)
    return per, unread


def count_tooltip(n_open, n_unread):
    """Knopf in der Kopfleiste: "3 offene Unterhaltungen · 1 ungelesen" """
    s = ngettext("{n} open thread", "{n} open threads", n_open).format(n=n_open)
    if n_unread:
        s += " · " + ngettext("{n} unread", "{n} unread", n_unread).format(n=n_unread)
    return s


def bubble_tooltip(n):
    return ngettext("{n} thread on this block", "{n} threads on this block", n).format(n=n)


def resolved_text(t):
    """"✓ Erledigt von …" bzw. "✓ Erledigt" (Unterhaltung erledigt, ohne bekannte Person)"""
    by = t.get("resolved_by")
    if isinstance(by, dict):
        return "✓ " + _("Resolved by {name}").format(name=_("you") if by.get("me") else (by.get("name") or ""))
    return _("✓ Resolved")


def comments_count_text(n):
    return ngettext("{n} comment", "{n} comments", n).format(n=n)


def unread_index(t):
    """Index des letzten Kommentars von anderen in einer ungelesenen Unterhaltung (dort steht der Punkt) oder -1"""
    if not t.get("unread"):
        return -1
    idx = -1
    for i, cm in enumerate(t.get("comments") or []):
        if not cm.get("mine"):
            idx = i
    return idx


def others_replied(t, cm):
    """Gibt es Antworten von anderen (oder von geloeschten Konten) unter diesem ersten Kommentar?"""
    me = ((cm or {}).get("author") or {}).get("username")
    return any(not (x.get("author") or {}).get("username") or (x.get("author") or {}).get("username") != me
               for x in (t.get("comments") or [])[1:])


def comment_actions(t, cm, first, role):
    """Eintraege im ⋯-Menue eines Kommentars: Teilmenge von ("edit", "delete", "delete_all")"""
    can = RANK.get(role, 0) >= RANK["comment"]
    mod = role == "owner"
    mine = bool(cm.get("mine")) and can and not cm.get("deleted")
    out = []
    if mine:
        out.append("edit")
    if not cm.get("deleted") and (mine or mod):
        out.append("delete")
    if first and mod and (others_replied(t, cm) or cm.get("deleted")):
        out.append("delete_all")
    return out


def delete_texts(t, cm, first, whole_thread=False):
    """Rueckfrage beim Loeschen -> (Titel, Text)"""
    if whole_thread:
        return _("Delete thread?"), _("The first comment and all replies will be deleted – including those from others.")
    others = first and others_replied(t, cm)
    if first and not others:
        return _("Delete thread?"), _("The first comment and all replies will be deleted.")
    if others:
        return _("Delete comment?"), _("The comment will be deleted; the replies remain.")
    return _("Delete comment?"), _("The comment will be deleted.")


class CommentState:
    """Unterhaltungen einer Seite + Ansicht (alle oder ein Block, erledigte zeigen) - ohne GTK"""

    def __init__(self, role="owner"):
        self.threads = []
        self.nres = 0
        self.loaded = False
        self.role = role
        self.resolved = False             # Schalter "Erledigte zeigen"
        self.block = None                 # Blockansicht: nur Unterhaltungen dieses Blocks
        self.expanded = set()             # aufgeklappte erledigte Unterhaltungen
        self.max = COMMENT_MAX

    @property
    def can(self):
        return RANK.get(self.role, 0) >= RANK["comment"]

    def apply(self, d):
        d = d or {}
        self.threads = [t for t in d.get("threads") or [] if isinstance(t, dict)]
        self.nres = int(d.get("resolved_count") or 0)
        if d.get("role") in RANK:
            self.role = d["role"]
        self.max = int(d.get("max") or COMMENT_MAX)
        self.loaded = True

    def open(self):
        return open_threads(self.threads)

    def unread_open(self):
        return sum(1 for t in self.open() if t.get("unread"))

    def shown(self):
        allt = [t for t in self.threads if self.resolved or not t.get("resolved_at")]
        return [t for t in allt if t.get("block_id") == self.block] if self.block else allt

    def show_switch(self):
        return bool(self.nres) or self.resolved

    def placeholder(self):
        return _("Comment on this block") if self.block else _("Comment on the whole page")

    def empty_text(self):
        if self.block:
            if not self.can:
                return _("No comments on this block.")
            if self.nres and not self.resolved:
                return _("No open comments on this block.")
            return _("No comments on this block yet.")
        if self.nres and not self.resolved:
            return ngettext("No open comments · {n} resolved", "No open comments · {n} resolved", self.nres).format(n=self.nres)
        if not self.can:
            return _("No comments yet.")
        return _("No comments yet. Comment on a paragraph with 💬 in the right margin, or write a comment on the whole page below.")

    def unseen(self):
        """sichtbare, ungelesene, offene Unterhaltungen (werden beim Zeichnen als gelesen gemeldet)"""
        return [t for t in self.shown() if t.get("unread") and not t.get("resolved_at")]


# =====================================================================================================
# GTK
# =====================================================================================================
def _label(text="", css=None, wrap=True, xalign=0):
    lb = Gtk.Label(label=text, xalign=xalign, wrap=wrap, wrap_mode=Pango.WrapMode.WORD_CHAR)
    for c in (css or "").split():
        lb.add_css_class(c)
    return lb


def _flat(label, cb, css=None, tooltip=None):
    b = Gtk.Button(label=label, tooltip_text=tooltip)
    b.add_css_class("flat")
    for c in (css or "").split():
        b.add_css_class(c)
    b.connect("clicked", lambda *_: cb())
    return b


class Composer(Gtk.Box):
    """Mehrzeiliges Eingabefeld mit Platzhalter, Zaehler kurz vor der Grenze, Senden (Strg+Enter), optional Abbrechen (Esc)"""

    def __init__(self, placeholder, ok_label, on_ok, on_cancel=None, value="", maxlen=COMMENT_MAX):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.on_ok, self.on_cancel, self.maxlen = on_ok, on_cancel, maxlen
        self.busy = False
        self._trim_src = 0
        ov = Gtk.Overlay()
        sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True, max_content_height=220,
                                min_content_height=56)
        sw.add_css_class("hk-cm-input")
        self.tv = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, accepts_tab=False, top_margin=8, bottom_margin=8,
                               left_margin=10, right_margin=10)
        self.tv.update_property([Gtk.AccessibleProperty.LABEL], [placeholder])
        self.buf = self.tv.get_buffer()
        self.buf.set_text(value or "")
        sw.set_child(self.tv)
        ov.set_child(sw)
        self.ph = _label(placeholder, "hk-placeholder", wrap=False)
        self.ph.set_can_target(False)
        self.ph.set_valign(Gtk.Align.START)
        self.ph.set_margin_top(8)
        self.ph.set_margin_start(11)
        ov.add_overlay(self.ph)
        self.append(ov)
        bar = Gtk.Box(spacing=6)
        self.count = _label("", "dim-label caption", wrap=False)
        self.count.set_hexpand(True)
        bar.append(self.count)
        if on_cancel:
            bar.append(_flat(_("Cancel"), on_cancel))
        self.ok = Gtk.Button(label=ok_label)
        self.ok.add_css_class("suggested-action")
        self.ok.connect("clicked", lambda *_: self.send())
        bar.append(self.ok)
        self.append(bar)
        self.buf.connect("changed", lambda *_: self._update())
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.tv.add_controller(kc)
        self._update()

    def text(self):
        return self.buf.get_text(self.buf.get_start_iter(), self.buf.get_end_iter(), True)

    def _update(self):
        t = self.text()
        if len(t) > self.maxlen:                                # wie maxlength im Web
            # nicht im "changed"-Handler selbst kuerzen: GTK wuerde die Iteratoren des laufenden Einfuegens
            # verwerfen ("Invalid text buffer iterator", BUG-117) - gleich danach
            if not self._trim_src:
                self._trim_src = GLib.idle_add(self._trim)
            return
        n = len(t)
        self.count.set_label(_("{n} of {max}").format(n=n, max=self.maxlen) if n > self.maxlen - 200 else "")
        self.ph.set_visible(n == 0)
        self.ok.set_sensitive(bool(t.strip()) and not self.busy)

    def _trim(self):
        self._trim_src = 0
        t = self.text()
        if len(t) > self.maxlen:
            self.buf.set_text(t[:self.maxlen])
        return False

    def _key(self, c, kv, code, state):
        if kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter) and state & Gdk.ModifierType.CONTROL_MASK:
            self.send()
            return True
        if kv == Gdk.KEY_Escape and self.on_cancel:
            self.on_cancel()
            return True
        return False

    def send(self):
        t = self.text()[:self.maxlen].strip()
        if not t or self.busy:
            return
        self.busy = True
        self._update()

        def fin(ok):
            self.busy = False
            if ok:
                self.buf.set_text("")
            self._update()
        self.on_ok(t, fin)

    def focus(self):
        self.tv.grab_focus()
        self.buf.place_cursor(self.buf.get_end_iter())


class CommentsPanel(Gtk.Box):
    """Seitenleiste "Kommentare" einer Seite (PageView). view: show_block(bid), block_snippet(bid), update_bubbles(),
    comments_changed(), win (toast), id, doc."""
    __gtype_name__ = "HKCommentsPanel"

    def __init__(self, view):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.view = view
        self.add_css_class("hk-cm-panel")
        self.s = CommentState()
        self.load_gen = 0
        self._done = []                    # Rueckrufe ausstehender reload()-Aufrufe
        self._foot_key = None              # Eingabefeld unten nur neu bauen, wenn sich Rolle/Block aendern (Entwurf bleibt)
        self.reply_to = None
        self.editing = None
        self.is_open = False
        head = Gtk.Box(spacing=6, margin_start=14, margin_end=8, margin_top=8, margin_bottom=8)
        self.title = _label(_("Comments"), "heading", wrap=False)
        head.append(self.title)
        self.count = _label("", "hk-badge", wrap=False)
        self.count.set_valign(Gtk.Align.CENTER)
        head.append(self.count)
        head.append(Gtk.Box(hexpand=True))
        close = Gtk.Button(icon_name="window-close-symbolic", tooltip_text=_("Close comments"), valign=Gtk.Align.CENTER)
        close.add_css_class("flat")
        close.update_property([Gtk.AccessibleProperty.LABEL], [_("Close comments")])
        close.connect("clicked", lambda *_: self.view.hide_comments())
        head.append(close)
        self.append(head)
        self.ctx = Gtk.Box(margin_start=8, margin_end=8)
        self.ctx.append(_flat("← " + _("All threads"), self._all, "hk-small"))
        self.append(self.ctx)
        self.append(Gtk.Separator())
        sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.list = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_top=10, margin_bottom=10, margin_start=10, margin_end=10)
        sw.set_child(self.list)
        self.scroll = sw
        self.append(sw)
        self.append(Gtk.Separator())
        self.foot = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=8, margin_bottom=10, margin_start=10, margin_end=10)
        self.append(self.foot)
        self.composer = None
        try:
            self.set_accessible_role(Gtk.AccessibleRole.COMPLEMENTARY)
        except (AttributeError, TypeError):
            pass
        self.update_property([Gtk.AccessibleProperty.LABEL], [_("Comments")])
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", lambda c, kv, code, st: (self.view.hide_comments(), True)[1]
                   if kv == Gdk.KEY_Escape and self.reply_to is None and self.editing is None else False)
        self.add_controller(kc)

    @property
    def win(self):
        return self.view.win

    @property
    def nid(self):
        return self.view.id

    def toast(self, text):
        self.win.toast(text)

    # --- Daten -----------------------------------------------------------------------------------------
    def reload(self, done=None):
        """Kommentare laden; done() nach dem Laden. Ueberholt ein neueres Laden ein aelteres (z. B. Live-Ereignis direkt
        nach dem eigenen Senden), laufen die Rueckrufe des aelteren mit dem neuesten Stand - keiner geht verloren (BUG-029)."""
        gen = self.load_gen = self.load_gen + 1
        nid = self.nid
        if done is not None and done not in self._done:
            self._done.append(done)

        def fin(d):
            if gen != self.load_gen:
                return
            self.s.apply(d)
            self.view.comments_changed(self.s)
            ds, self._done = self._done, []
            for cb in ds:
                cb()

        def err(e):
            if gen == self.load_gen and self.is_open:
                self.toast(_("Comments not loaded: {err}").format(err=e))
        bg(client.get, f"api/notes/{nid}/comments" + ("?resolved=1" if self.s.resolved else ""), done=fin, error=err)

    def open(self, block=None):
        self.s.block = block or None
        self.reply_to = self.editing = None
        self.is_open = True
        self.render()
        self.reload(self.render)
        if block is not None and self.composer is not None and self.s.can:
            GLib.idle_add(lambda: (self.composer is not None and self.composer.focus(), False)[1])

    def live_render(self):
        """Live (comments.changed, auch fremde Kommentare): neu zeichnen, solange keine Antwort/Bearbeitung offen ist -
        deren Eingabefeld wuerde sonst neu gebaut (Entwurf weg). Das Feld unten bleibt stehen (render)."""
        if self.is_open and self.reply_to is None and self.editing is None:
            self.render()

    def closed(self):
        self.is_open = False
        self.s.block = None
        self.reply_to = self.editing = None

    def _all(self):
        self.s.block = None
        self.view.mark_comment_block(None)
        self.render()

    # --- Zeichnen --------------------------------------------------------------------------------------
    def render(self):
        s = self.s
        n = len(s.open())
        self.count.set_label(str(n))
        self.count.set_visible(bool(n))
        self.ctx.set_visible(bool(s.block))
        while (c := self.list.get_first_child()):
            self.list.remove(c)
        shown = s.shown()
        for t in shown:
            self.list.append(self._thread(t))
        if not shown:
            self.list.append(_label(s.empty_text() if s.loaded else "", "dim-label"))
        if s.show_switch():
            row = Gtk.Box(spacing=8, halign=Gtk.Align.END, margin_top=4)
            lb = _label(_("Show resolved"), wrap=False)
            sw = Gtk.Switch(active=s.resolved, valign=Gtk.Align.CENTER)
            sw.update_property([Gtk.AccessibleProperty.LABEL], [_("Show resolved")])
            sw.set_tooltip_text(ngettext("{n} resolved", "{n} resolved", s.nres).format(n=s.nres))
            sw.connect("notify::active", lambda w, _p: self._toggle_resolved(w.get_active()))
            row.append(lb)
            row.append(sw)
            self.list.append(row)
        self._foot()
        self._seen()

    def _foot(self):
        s = self.s
        key = (s.can, s.block or "", s.placeholder(), s.max)
        if key == self._foot_key and self.composer is not None:
            return                                         # Entwurf und Fokus im Eingabefeld bleiben
        self._foot_key = key
        while (c := self.foot.get_first_child()):
            self.foot.remove(c)
        self.composer = None
        if not s.can:
            self.foot.append(_label(_("You can read comments but not write them."), "dim-label"))
            return
        bid = s.block or ""
        nid = self.nid

        def send(text, fin):
            def ok(_r):
                fin(True)
                self.reload(self.render)

            def err(e):
                fin(False)
                self.toast(_("Not sent: {err}").format(err=e))
            bg(client.json, "POST", f"api/notes/{nid}/comments", {"block_id": bid, "body": text}, done=ok, error=err)
        self.composer = Composer(s.placeholder(), _("Comment"), send, maxlen=s.max)
        self.foot.append(self.composer)

    def _toggle_resolved(self, on):
        if on != self.s.resolved:
            self.s.resolved = on
            self.reload(self.render)

    def _seen(self):
        for t in self.s.unseen():
            def ok(_r, t=t):
                t["unread"] = False
                self.view.comments_changed(self.s)
            bg(client.json, "POST", f"api/comments/{t['id']}/seen", {}, done=ok, error=lambda e: None)

    def _thread(self, t):
        s = self.s
        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        card.add_css_class("card")
        card.add_css_class("hk-cm-thread")
        if t.get("unread"):
            card.add_css_class("hk-unread")
        inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=8, margin_bottom=6, margin_start=10, margin_end=6)
        card.append(inner)
        if not s.block:                                    # Blockansicht: der Block ist schon hervorgehoben
            bid = t.get("block_id")
            sn = self.view.block_snippet(bid) if bid else _("Full page")
            gone = bool(bid) and (not t.get("block_exists", True) or sn is None)
            w = Gtk.Button()
            w.add_css_class("flat")
            w.add_css_class("hk-cm-where")
            lb = _label(_("Block removed") if gone else sn, "dim-label caption", wrap=False)
            lb.set_ellipsize(Pango.EllipsizeMode.END)
            if gone:
                lb.add_css_class("hk-italic")
            w.set_child(lb)
            if bid and not gone:
                w.set_tooltip_text(_("Go to block"))
                w.connect("clicked", lambda *_: self.view.show_block(bid))
            else:
                w.set_can_target(False)
                w.set_focusable(False)
            inner.append(w)
        resolved = bool(t.get("resolved_at"))
        if resolved and t["id"] not in s.expanded:         # erledigt: zugeklappt
            b = Gtk.Button()
            b.add_css_class("flat")
            bx = Gtk.Box(spacing=8)
            bx.append(_label(resolved_text(t), wrap=False))
            bx.append(_label(comments_count_text(len(t.get("comments") or [])), "dim-label caption", wrap=False))
            b.set_child(bx)
            b.connect("clicked", lambda *_: (s.expanded.add(t["id"]), self.render()))
            inner.append(b)
            return card
        if resolved:
            inner.append(_label(resolved_text(t), "dim-label caption"))
        dot = unread_index(t)
        cms = t.get("comments") or []
        for i, cm in enumerate(cms):
            if i:
                inner.append(Gtk.Separator())
            inner.append(self._comment(t, cm, i == 0, i == dot))
        if s.can:
            if self.reply_to == t["id"]:
                def reply(text, fin, tid=t["id"]):
                    def ok(_r):
                        fin(True)
                        self.reply_to = None
                        self.reload(self.render)

                    def err(e):
                        fin(False)
                        self.toast(_("Not sent: {err}").format(err=e))
                    bg(client.json, "POST", f"api/notes/{self.nid}/comments", {"thread_id": tid, "body": text}, done=ok, error=err)
                ed = Composer(_("Reply"), _("Reply"), reply, on_cancel=lambda: (setattr(self, "reply_to", None), self.render()), maxlen=s.max)
                inner.append(ed)
                GLib.idle_add(lambda: (ed.focus(), False)[1])
            else:
                bar = Gtk.Box(spacing=4)
                bar.append(_flat(_("Reply"), lambda: self._reply(t["id"]), "hk-small"))
                bar.append(Gtk.Box(hexpand=True))
                rb = _flat(_("Reopen") if resolved else _("✓ Resolve"), lambda: self._resolve(t, not resolved), "hk-small",
                           tooltip=_("Reopen thread") if resolved else _("Mark as resolved – the thread is collapsed"))
                bar.append(rb)
                inner.append(bar)
        return card

    def _comment(self, t, cm, first, dot):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        h = Gtk.Box(spacing=6)
        from . import avatars
        av = avatars.person((cm or {}).get("author") or {}, 24, text=author(cm) or "?", valign=Gtk.Align.CENTER)
        h.append(av)                                               # Profilbild (2.1) bzw. Initialen
        a = _label(author(cm), "heading", wrap=False)
        a.set_ellipsize(Pango.EllipsizeMode.END)
        h.append(a)
        if dot:
            d = _label("●", "hk-unread-dot", wrap=False)
            d.set_tooltip_text(_("New"))
            d.update_property([Gtk.AccessibleProperty.LABEL], [_("New")])
            h.append(d)
        tm = _label(rel_time(cm.get("created")), "dim-label caption", wrap=False)
        tm.set_tooltip_text(date_time_text(cm.get("created")))
        h.append(tm)
        if cm.get("edited") and not cm.get("deleted"):
            h.append(_label(_("edited"), "dim-label caption", wrap=False))
        h.append(Gtk.Box(hexpand=True))
        acts = comment_actions(t, cm, first, self.s.role)
        if acts and self.editing != cm["id"]:
            mb = Gtk.MenuButton(icon_name="view-more-symbolic", valign=Gtk.Align.CENTER, tooltip_text=_("Actions"))
            mb.add_css_class("flat")
            mb.add_css_class("hk-tiny")
            mb.update_property([Gtk.AccessibleProperty.LABEL], [_("Actions for this comment")])
            from .blocks import menu_popover
            ents = []
            if "edit" in acts:
                ents.append((_("Edit"), lambda: self._edit(cm["id"])))
            if "delete" in acts:
                ents.append((_("Delete"), lambda: self._ask_delete(t, cm, first, False), True))
            if "delete_all" in acts:
                ents.append((_("Delete conversation"), lambda: self._ask_delete(t, cm, first, True), True))
            mb.set_create_popup_func(lambda m: m.set_popover(menu_popover(None, ents)))
            h.append(mb)
        box.append(h)
        if self.editing == cm["id"]:
            def save(text, fin, cid=cm["id"]):
                def ok(_r):
                    fin(True)
                    self.editing = None
                    self.reload(self.render)

                def err(e):
                    fin(False)
                    self.toast(_("Not saved: {err}").format(err=e))
                bg(client.json, "PATCH", f"api/comments/{cid}", {"body": text}, done=ok, error=err)
            ed = Composer(_("Edit comment"), _("Save"), save, on_cancel=lambda: (setattr(self, "editing", None), self.render()),
                          value=cm.get("body") or "", maxlen=self.s.max)
            box.append(ed)
            GLib.idle_add(lambda: (ed.focus(), False)[1])
            return box
        if cm.get("deleted"):
            box.append(_label(_("Comment deleted"), "dim-label hk-italic"))
            return box
        body = _label("", "hk-cm-text")
        body.set_markup(linkify_markup(cm.get("body") or ""))
        body.set_selectable(True)
        body.connect("activate-link", lambda lb, uri: (self.win.open_uri(uri), True)[1])
        box.append(body)
        return box

    # --- Aktionen --------------------------------------------------------------------------------------
    def _reply(self, tid):
        self.reply_to, self.editing = tid, None
        self.render()

    def _edit(self, cid):
        self.editing, self.reply_to = cid, None
        self.render()

    def _resolve(self, t, on):
        def ok(_r):
            if on:
                t["resolved_at"] = 1
                self.view.comments_changed(self.s)            # Zahl am Block sofort weg
                tt = Adw.Toast(title=GLib.markup_escape_text(_("Thread resolved")), button_label=_("Undo"), timeout=6)
                tt.connect("button-clicked", lambda *_: bg(client.json, "POST", f"api/comments/{t['id']}/resolve", {"resolved": False},
                                                           done=lambda r: self.reload(self.render), error=lambda e: None))
                self.win.toasts.add_toast(tt)
            self.s.expanded.discard(t["id"])
            self.reload(self.render)
        bg(client.json, "POST", f"api/comments/{t['id']}/resolve", {"resolved": on}, done=ok,
           error=lambda e: self.toast(_("Not saved: {err}").format(err=e)))

    def _ask_delete(self, t, cm, first, whole):
        from .publish import confirm
        title, body = delete_texts(t, cm, first, whole)
        path = f"api/comments/{cm['id']}" + ("?all=1" if whole else "")
        confirm(self.win, title, body, _("Delete"), lambda: bg(
            client.json, "DELETE", path, done=lambda r: self.reload(self.render),
            error=lambda e: self.toast(_("Not deleted: {err}").format(err=e))))
