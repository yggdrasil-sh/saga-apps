"""Chat-Standard (Saga 2.5) - Oberflaeche: Gruppenbild (laden, setzen, entfernen), Beschreibung, Gruppenrechte, Melden,
Sperren, Medienuebersicht, Weiterleiten, Stummschalt-Menue. Logik in chatstd.py, Zustand in chat.py.
Referenz: static/chat.js im Server-Repo (grpInfoSection, grpHeadAvatar, reportDialog, mediaView, selForward)."""
import io

from gi.repository import Adw, Gdk, GLib, Gtk, Pango

from . import chatcrypto as C
from . import chatmedia, chatvideo
from . import chatstd as K
from .chat import chat
from .i18n import _, ngettext
from .util import bg


def _mk(text):
    return GLib.markup_escape_text(str(text or ""))


def _lbl(text="", css=(), xalign=0, wrap=True, **kw):
    lb = Gtk.Label(label=text, xalign=xalign, wrap=wrap, **kw)
    if wrap:
        lb.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
    for c in css:
        lb.add_css_class(c)
    return lb


# --- Gruppenbild (groupinfo2) --------------------------------------------------------------------------------------
_TEX = {}                      # Anhang-ID des Gruppenbilds -> Gdk.Texture (Sitzung)


def group_texture(c, cb):
    """Gruppenbild eines Chats (entschluesselt, Vorschaubild-Weg) -> cb(Gdk.Texture) im Hauptthread; ohne Bild nichts"""
    f = (c or {}).get("_avf")
    if not isinstance(f, dict) or not f.get("id"):
        return False
    tex = _TEX.get(f["id"])
    if tex is not None:
        cb(tex)
        return True

    def got(t, _wh):
        if t is not None:
            _TEX[f["id"]] = t
            cb(t)
    chatmedia.request(c["id"], f, got)
    return True


def attach_group(av, c):
    """Adw.Avatar bekommt das Gruppenbild (sobald geladen)"""
    group_texture(c, lambda t: av.set_custom_image(t))


def square_jpeg(data, edge=K.GRP_AV):
    """Bild -> quadratisch (Mitte) zugeschnittenes JPEG edge x edge, ohne Metadaten; None, wenn nicht lesbar"""
    try:
        from PIL import Image, ImageOps
        im = Image.open(io.BytesIO(data))
        im = ImageOps.exif_transpose(im)
        if im.mode != "RGB":
            im = im.convert("RGBA")
            bgc = Image.new("RGB", im.size, (255, 255, 255))
            bgc.paste(im, mask=im.split()[-1])
            im = bgc
        s = min(im.size)
        x, y = (im.size[0] - s) // 2, (im.size[1] - s) // 2
        im = im.crop((x, y, x + s, y + s)).resize((edge, edge), Image.LANCZOS)
        out = io.BytesIO()
        im.save(out, "JPEG", quality=85)
        return out.getvalue()
    except Exception:  # noqa: BLE001 - kein Bild
        return None


def set_group_avatar(cid, data, done, error):
    """Gruppenbild setzen: zuschneiden, wie einen Anhang verschluesselt hochladen, Steuer-Nachricht kind 'avatar'"""
    def work():
        jpeg = square_jpeg(data)
        if jpeg is None:
            raise ValueError(_("Image not readable."))
        e = chat.upload_file(cid, jpeg, "group.jpg", "image/jpeg")
        f = dict(e, w=K.GRP_AV, h=K.GRP_AV)
        chat.send(cid, {"v": 1, "type": "avatar", "file": f}, None, "avatar", [f["id"]])
        return f

    def fin(f):
        c = chat.chat(cid)
        if c is not None:
            c["_avf"] = f
        chat.load_chat(cid)
        chat.emit("changed")
        done(f)
    bg(work, done=fin, error=error)


def clear_group_avatar(cid, done, error):
    def fin(_r):
        c = chat.chat(cid)
        if c is not None:
            c["_avf"] = None
        chat.load_chat(cid)
        chat.emit("changed")
        done()
    bg(chat.send, cid, {"v": 1, "type": "avatar", "file": None}, None, "avatar", done=fin, error=error)


def set_group_desc(cid, text, done, error):
    t = str(text or "")[:K.DESC_MAX]

    def fin(_r):
        c = chat.chat(cid)
        if c is not None:
            c["_desc"] = t
        chat.load_chat(cid)
        done()
    bg(chat.send, cid, {"v": 1, "type": "desc", "desc": t}, None, "desc", done=fin, error=error)


def pick_image(win, cb):
    """Bilddatei waehlen -> cb(bytes)"""
    fd = Gtk.FileDialog(title=_("Change group picture"))
    flt = Gtk.FileFilter(name=_("Images"))
    for m in ("image/png", "image/jpeg", "image/webp", "image/gif"):
        flt.add_mime_type(m)
    fd.set_default_filter(flt)

    def done(d, res):
        try:
            f = d.open_finish(res)
        except GLib.Error:
            return
        if f is None:
            return
        try:
            ok, data, _etag = f.load_contents(None)
        except GLib.Error:
            return
        if ok:
            cb(bytes(data))
    fd.open(win, None, done)


# --- Abschnitte der Gruppeninfo --------------------------------------------------------------------------------------
def desc_group(dlg, c, rights):
    """Beschreibung: Text (oder "Keine Beschreibung."), bei can_info2 bearbeitbar (hoechstens 500 Zeichen)"""
    g = Adw.PreferencesGroup(title=_("Description"))
    d = c.get("_desc") or ""
    if d:
        g.add(_lbl(d, ("hk-gi-desc",), selectable=True))
    elif rights["info2"]:
        g.add(_lbl(_("No description."), ("dim-label", "hk-gi-desc")))
    else:
        return None
    if rights["info2"]:
        btn = Gtk.Button(label=_("Edit description") if d else _("Add description"), halign=Gtk.Align.START, margin_top=6)
        btn.add_css_class("flat")
        btn.connect("clicked", lambda *_: desc_dialog(dlg, c))
        g.add(btn)
    return g


def desc_dialog(dlg, c):
    cid = c["id"]
    ad = Adw.AlertDialog(heading=_("Group description"))
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
    sw = Gtk.ScrolledWindow(min_content_height=110, hscrollbar_policy=Gtk.PolicyType.NEVER)
    sw.add_css_class("card")
    tv = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, top_margin=6, bottom_margin=6, left_margin=8, right_margin=8)
    tv.update_property([Gtk.AccessibleProperty.LABEL], [_("Group description")])
    buf = tv.get_buffer()
    buf.set_text(c.get("_desc") or "")
    sw.set_child(tv)
    box.append(sw)
    cnt = _lbl("", ("dim-label", "caption"), xalign=1)
    box.append(cnt)

    def upd(*_a):
        t = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
        if len(t) > K.DESC_MAX:
            buf.set_text(t[:K.DESC_MAX])
            return
        cnt.set_label(f"{len(t)} / {K.DESC_MAX}")
    buf.connect("changed", upd)
    upd()
    ad.set_extra_child(box)
    ad.add_response("cancel", _("Cancel"))
    ad.add_response("save", _("Save"))
    ad.set_response_appearance("save", Adw.ResponseAppearance.SUGGESTED)
    ad.set_close_response("cancel")

    def resp(_d, r):
        if r != "save":
            return
        t = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False).strip()
        set_group_desc(cid, t, done=lambda: dlg._toast(_("Saved.")), error=lambda e: dlg._toast(e))
    ad.connect("response", resp)
    ad.present(dlg)
    dlg.desc_dlg = ad
    return ad


def perms_group(dlg, c, rights):
    """Gruppenrechte: zwei Schalter (nur Admins aendern sie), sonst nur lesend"""
    if not rights["show_perms"]:
        return None
    g = Adw.PreferencesGroup(title=_("Group permissions"))
    if not rights["perms"]:
        g.set_description(_("Only admins change the permissions."))
    for key, title in (("perm_send", _("Only admins can send messages")),
                       ("perm_info", _("Only admins can change name, picture and description and pin messages"))):
        row = Adw.SwitchRow(title=title, active=c.get(key) == "admins", sensitive=rights["perms"])
        row.hk_key = key

        def changed(r, _p, key=key):
            want = "admins" if r.get_active() else "all"
            cc = chat.chat(c["id"]) or {}
            if cc.get(key, "all") == want:
                return
            r.set_sensitive(False)

            def err(e):
                dlg._toast(e)
                r.set_active(cc.get(key) == "admins")
                r.set_sensitive(True)
            chat.patch(c["id"], {key: want}, done=lambda _ch: r.set_sensitive(True), error=err)
        row.connect("notify::active", changed)
        g.add(row)
    return g


def head_avatar(dlg, c, rights, size=64):
    """Kopf der Gruppeninfo: Gruppenbild (bei can_info2 mit "Gruppenbild aendern")"""
    from .sharing import people_icon
    from .chat import chat as _c
    av = Adw.Avatar(size=size, text=_c.title(c), show_initials=c.get("kind") == "free")
    if c.get("kind") != "free":
        av.set_icon_name(people_icon())
    attach_group(av, c)
    if not rights["info2"]:
        return av
    b = Gtk.Button(child=av, tooltip_text=_("Change group picture"), halign=Gtk.Align.CENTER)
    b.add_css_class("flat")
    b.add_css_class("circular")
    b.add_css_class("hk-gi-avb")
    b.update_property([Gtk.AccessibleProperty.LABEL], [_("Change group picture")])
    cid = c["id"]

    def choose():
        pick_image(dlg.win, lambda data: set_group_avatar(cid, data, done=lambda _f: dlg._toast(_("Group picture saved.")),
                                                          error=lambda e: dlg._toast(e)))

    def clicked(*_a):
        if not (chat.chat(cid) or {}).get("_avf"):
            choose()
            return
        from .blocks import menu_popover
        menu_popover(b, [(_("Choose new picture …"), choose),
                         (_("Remove picture"), lambda: clear_group_avatar(cid, done=lambda: dlg._toast(_("Group picture removed.")),
                                                                         error=lambda e: dlg._toast(e)), True)]).popup()
    b.connect("clicked", clicked)
    return b


# --- Stummschalten (mute2) -------------------------------------------------------------------------------------------
def mute_entries(win, c, toast=None):
    """Menue-Eintraege: Untermenue Stummschalten (Dauer) + Mitteilungen (notify); ohne mute2 der alte Schalter"""
    toast = toast or (lambda t: win.toast(str(t)))
    cid = c["id"]
    if not K.has(K.MUTE2):
        return [(_("Unmute") if c.get("muted") else _("Mute"),
                 lambda: chat.patch(cid, {"muted": not c.get("muted")}, done=lambda _c: chat.load_chats(), error=toast))]

    def go(b):
        chat.patch(cid, b, done=lambda _c: chat.load_chats(), error=toast)
    sub = []
    if K.muted_now(c):
        sub.append((_("Unmute"), lambda: go({"muted_until": 0})))
    for t, v in K.mute_choices():
        on = v == -1 and c.get("muted_until") == -1
        sub.append((("✓ " if on else "") + t, lambda v=v: go({"muted_until": v})))
    sub.append(None)
    cur = c.get("notify") or "all"
    for k, t in K.notify_choices():
        sub.append((("✓ " if k == cur else "") + _("Notifications: {mode}").format(mode=t), lambda k=k: go({"notify": k})))
    return [(K.mute_label(c), sub)]


def list_entries(win, c, screen=None):
    """(9) Anheften, als ungelesen markieren, ausblenden - nur mit den Features"""
    cid = c["id"]
    out = []

    def go(b, after=None):
        def done(_ch):
            chat.load_chats()
            if after:
                after()
        chat.patch(cid, b, done=done, error=lambda e: win.toast(str(e)))
    if K.has(K.CHATPIN):
        out.append((_("Unpin") if c.get("pinned") else _("Pin"), lambda: go({"pinned": not c.get("pinned")})))
    if K.has(K.MARKUNREAD) and not c.get("unread") and not c.get("unread_mark"):
        def unread():
            if screen is not None and screen.cid == cid:
                win.show_root(("chats",))
            go({"unread_mark": True})
        out.append((_("Mark as unread"), unread))
    if K.has(K.CHATHIDE) and c.get("can_hide"):
        def hide():
            def after():
                if not c.get("hidden"):
                    win.toast(_("Hidden – the chat comes back when someone mentions you."))
                    if screen is not None and screen.cid == cid:
                        win.show_root(("chats",))
            go({"hidden": not c.get("hidden")}, after)
        out.append((_("Show again") if c.get("hidden") else _("Hide for me"), hide))
    return out


# --- Sperren / Melden (block2, report1) ------------------------------------------------------------------------------
def block_person(parent, win, target, name, toast=None):
    from .publish import confirm
    toast = toast or (lambda t: win.toast(str(t)))
    confirm(parent, _("Block {name}?").format(name=name),
            _("{name} can no longer send you direct messages or call you. In groups, their messages are collapsed. {name} won't be "
              "told.").format(name=name), _("Block"),
            lambda: chat.block(target, done=lambda _j: toast(_("{name} is blocked.").format(name=name)), error=toast))


def unblock_person(win, target, toast=None):
    toast = toast or (lambda t: win.toast(str(t)))

    def fin(_r):
        toast(_("Unblocked."))
        chat.load_chats()
        if chat.open_chat is not None:
            chat.load_chat(chat.open_chat, error=lambda e: None)
        chat.emit("blocks")
    chat.unblock(target, done=fin, error=toast)


class ReportDialog(Adw.Dialog):
    """Melden: Grund + optional mitgeschickter Klartext (vorbelegt, aenderbar) -> POST /api/chat/report"""
    __gtype_name__ = "HKReportDialog"

    def __init__(self, win, c, msgs):
        super().__init__(title=_("Report"), content_width=460, content_height=520)
        self.win, self.cid, self.msgs = win, c["id"], list(msgs)[:K.REPORT_MSGS_MAX]
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar(show_end_title_buttons=False, show_start_title_buttons=False)
        cancel = Gtk.Button(label=_("Cancel"))
        cancel.connect("clicked", lambda *_: self.close())
        hb.pack_start(cancel)
        self.go_btn = Gtk.Button(label=_("Report"))
        self.go_btn.add_css_class("destructive-action")
        self.go_btn.connect("clicked", lambda *_: self._go())
        hb.pack_end(self.go_btn)
        tv.add_top_bar(hb)
        page = Adw.PreferencesPage()
        g = Adw.PreferencesGroup(description=_("The report goes to the admins of this Saga server. Chats are end-to-end encrypted – "
                                               "the admins only see what you send along here."))
        labels = K.report_reason_labels()
        self.reason = Adw.ComboRow(title=_("Reason"), model=Gtk.StringList.new([labels[r] for r in K.REPORT_REASONS]))
        g.add(self.reason)
        self.inc = Adw.SwitchRow(title=_("Include the text of the reported messages"), active=True)
        g.add(self.inc)
        page.add(g)
        g2 = Adw.PreferencesGroup(title=_("Text sent along"), description=_("You can edit or shorten the text before sending."))
        sw = Gtk.ScrolledWindow(min_content_height=140, hscrollbar_policy=Gtk.PolicyType.NEVER)
        sw.add_css_class("card")
        self.tv = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, top_margin=6, bottom_margin=6, left_margin=8, right_margin=8)
        self.tv.update_property([Gtk.AccessibleProperty.LABEL], [_("Text sent along")])
        name = (chat.me or {}).get("name")
        self.tv.get_buffer().set_text(K.report_text(self.msgs, c, name))
        sw.set_child(self.tv)
        g2.add(sw)
        self.inc.connect("notify::active", lambda r, _p: g2.set_visible(r.get_active()))
        page.add(g2)
        tv.set_content(page)
        self.toasts = Adw.ToastOverlay(child=tv)
        self.set_child(self.toasts)

    def _go(self):
        b = self.tv.get_buffer()
        text = b.get_text(b.get_start_iter(), b.get_end_iter(), False) if self.inc.get_active() else ""
        body = K.report_body(self.cid, self.msgs, K.REPORT_REASONS[self.reason.get_selected()], text)
        self.go_btn.set_sensitive(False)

        def done(_j):
            self.close()
            self.win.toast(_("Thanks – the admins have received the report."))

        def err(e):
            self.go_btn.set_sensitive(True)
            self.toasts.add_toast(Adw.Toast(title=_mk(e)))
        chat.report(body, done=done, error=err)
        self.last_body = body


# --- Medienuebersicht (chatmedia1) -----------------------------------------------------------------------------------
class MediaDialog(Adw.Dialog):
    """Medien, Dateien, Links: GET .../messages?has=file (entschluesselt), Links aus geladenen und nachgeladenen Seiten"""
    __gtype_name__ = "HKMediaDialog"

    def __init__(self, conv_or_win, cid):
        super().__init__(title=_("Media, files, links"), content_width=640, content_height=600)
        self.win = getattr(conv_or_win, "win", conv_or_win)
        self.conv = conv_or_win if hasattr(conv_or_win, "_save_file") else None
        self.cid = cid
        self.msgs, self.before, self.done_ = {}, None, False
        self.lmsgs, self.lbefore, self.ldone = {}, None, False
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.stack = Adw.ViewStack()
        sw = Adw.ViewSwitcher(stack=self.stack, policy=Adw.ViewSwitcherPolicy.WIDE)
        hb.set_title_widget(sw)
        tv.add_top_bar(hb)
        self.views = {}
        for key, title, icon in (("media", _("Media"), "image-x-generic-symbolic"), ("files", _("Files"), "text-x-generic-symbolic"),
                                 ("links", _("Links"), "insert-link-symbolic")):
            sc = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_start=12, margin_end=12, margin_top=12, margin_bottom=12)
            sc.set_child(box)
            self.stack.add_titled_with_icon(sc, key, title, icon)
            self.views[key] = box
        self.stack.connect("notify::visible-child-name", lambda *_: self._draw())
        tv.set_content(self.stack)
        self.more = Gtk.Button(label=_("Load older"), halign=Gtk.Align.CENTER, margin_top=6, margin_bottom=10)
        self.more.connect("clicked", lambda *_: self._load(self.stack.get_visible_child_name() == "links"))
        tv.add_bottom_bar(self.more)
        self.toasts = Adw.ToastOverlay(child=tv)
        self.set_child(self.toasts)
        self.loading = 0
        self._load(False)
        self._load(True)

    def _load(self, links):
        self.loading += 1
        self.more.set_sensitive(False)
        before = self.lbefore if links else self.before

        def fin(r):
            msgs, more, low = r
            tgt = self.lmsgs if links else self.msgs
            for m in msgs:
                tgt[m["id"]] = m
            if links:
                self.lbefore, self.ldone = (low or self.lbefore), not more
            else:
                self.before, self.done_ = (low or self.before), not more
            self.loading -= 1
            self._draw()

        def err(e):
            self.loading -= 1
            self.toasts.add_toast(Adw.Toast(title=_mk(e)))
            self._draw()
        bg(chat.media_page, self.cid, before, not links, done=fin, error=err)

    def items(self):
        med, fil, _l = K.media_split(sorted(self.msgs.values(), key=lambda m: -(m.get("seq") or 0)))
        _m, _f, links = K.media_split(sorted(self.lmsgs.values(), key=lambda m: -(m.get("seq") or 0)))
        return {"media": med, "files": fil, "links": links}

    def _draw(self):
        tab = self.stack.get_visible_child_name() or "media"
        box = self.views[tab]
        while (w := box.get_first_child()):
            box.remove(w)
        its = self.items()[tab]
        if not its and not self.loading:
            box.append(_lbl(_("Nothing found (in the loaded messages)."), ("dim-label",), xalign=0.5))
        if tab == "media" and its:
            fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=6, min_children_per_line=2,
                             row_spacing=6, column_spacing=6, homogeneous=True)
            for f, _m in its:
                fb.append(self._tile(f))
            box.append(fb)
        elif tab == "files" and its:
            lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
            lb.add_css_class("boxed-list")
            from .util import human_size
            for f, m in its:
                r = Adw.ActionRow(title=_mk(f.get("n") or _("File")),
                                  subtitle=_mk(" · ".join(x for x in (human_size(f.get("s") or 0) if f.get("s") else "",
                                                                       (m.get("sender") or {}).get("name") or "") if x)),
                                  activatable=self.conv is not None)
                r.add_prefix(Gtk.Image.new_from_icon_name("text-x-generic-symbolic"))
                if self.conv is not None:
                    r.connect("activated", lambda *_, f=f: self.conv._save_file(f))
                lb.append(r)
            box.append(lb)
        elif tab == "links" and its:
            lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
            lb.add_css_class("boxed-list")
            for u, _m in its:
                r = Adw.ActionRow(title=_mk(u), activatable=True)
                r.add_prefix(Gtk.Image.new_from_icon_name("insert-link-symbolic"))
                r.connect("activated", lambda *_, u=u: self.win.open_uri(u))
                lb.append(r)
            box.append(lb)
        done = self.ldone if tab == "links" else self.done_
        self.more.set_visible(not done)
        self.more.set_sensitive(not self.loading)

    def _tile(self, f):
        b = Gtk.Button(tooltip_text=f.get("n") or _("File"))
        b.add_css_class("flat")
        b.add_css_class("hk-md-tile")
        b.update_property([Gtk.AccessibleProperty.LABEL], [f.get("n") or _("File")])
        pic = Gtk.Picture(content_fit=Gtk.ContentFit.COVER, width_request=110, height_request=110)
        ov = Gtk.Overlay(child=pic)
        if chatvideo.is_video(f):
            ov.add_overlay(_lbl("▶", ("title-2",), xalign=0.5, halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER))
        b.set_child(ov)
        if chatmedia.is_image(f) or chatmedia.thumb_ref(f):
            chatmedia.request(self.cid, f, lambda t, _wh: t is not None and pic.set_paintable(t))
        if self.conv is not None:
            from .chatview import ImageViewer, VideoViewer
            b.connect("clicked", lambda *_: (VideoViewer if chatvideo.is_video(f) else ImageViewer)(self.conv, f).present(self))
        return b


# --- Weiterleiten (Mehrfachauswahl) ----------------------------------------------------------------------------------
class ForwardDialog(Adw.Dialog):
    """Weiterleiten an …: Chat waehlen -> Texte neu verschluesselt (normaler Sendeweg), v1-Anhaenge entschluesselt und im
    Ziel neu hochgeladen; saga-file/v2 (grosse Dateien, Videos, Sprachnachrichten) als Strom von Server zu Server kopiert
    (gleicher Schluessel), Vorschaubild/Poster neu verschluesselt"""
    __gtype_name__ = "HKForwardDialog"

    def __init__(self, conv, msgs, done=None):
        super().__init__(title=_("Forward to …"), content_width=420, content_height=520)
        self.conv, self.win, self.msgs, self.on_done = conv, conv.win, list(msgs), done
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_start=12, margin_end=12, margin_top=8, margin_bottom=12)
        self.q = Gtk.SearchEntry(placeholder_text=_("Search chats"))
        self.q.connect("search-changed", lambda *_: self._fill())
        box.append(self.q)
        sc = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.lb.add_css_class("boxed-list")
        self.lb.update_property([Gtk.AccessibleProperty.LABEL], [_("Chats")])
        sc.set_child(self.lb)
        box.append(sc)
        tv.set_content(box)
        self.toasts = Adw.ToastOverlay(child=tv)
        self.set_child(self.toasts)
        self._fill()

    def _fill(self):
        self.lb.remove_all()
        cand = K.fwd_candidates(chat.chats, self.q.get_text(), chat.title)
        if not cand:
            self.lb.append(Gtk.ListBoxRow(activatable=False, child=_lbl(_("No matching chat."), ("dim-label",), margin_top=8,
                                                                         margin_bottom=8, margin_start=8)))
        for c in cand:
            r = Adw.ActionRow(title=_mk(chat.title(c)), subtitle=_mk(chat.kind_label(c)), activatable=True)
            r.hk_cid = c["id"]
            r.connect("activated", lambda *_, c=c: self._ask(c))
            self.lb.append(r)

    def _ask(self, target):
        from .publish import confirm
        n = len(self.msgs)
        confirm(self, _("Forward?"), ngettext("Forward {n} message to “{chat}”?", "Forward {n} messages to “{chat}”?", n).format(
            n=n, chat=chat.title(target)), _("Forward"), lambda: self.go(target), destructive=False)

    def go(self, target):
        src, tid, msgs = self.conv.cid, target["id"], self.msgs
        win, on_done = self.win, self.on_done
        self.close()
        win.toast(_("Forwarding …"))

        def work():
            n = skipped = 0
            for m in msgs:
                ok, sk = K.fwd_files(m)
                fobjs, fids = [], []
                for f in ok:
                    try:
                        if K.fwd_fmt(f) == 2:
                            e, ids = fwd_v2(src, tid, f)
                        else:
                            data = chatmedia.original(src, f)
                            up = [(str(f.get("n") or _("File"))[:200], f.get("m") or "application/octet-stream", data)]
                            out, ids = chat.upload_attachments(tid, up)
                            e = out[0]
                    except Exception:  # noqa: BLE001 - nicht ladbar/kopierbar: Hinweis statt Anhang
                        sk += 1
                        continue
                    fobjs.append(e)
                    fids += ids
                chat.send(tid, K.fwd_body(m, fobjs, sk), file_ids=fids)
                n += 1
                skipped += sk
            return n, skipped

        def fin(r):
            n, sk = r
            win.toast(ngettext("{n} message forwarded.", "{n} messages forwarded.", n).format(n=n) +
                      (" " + ngettext("{n} attachment not forwarded.", "{n} attachments not forwarded.", sk).format(n=sk) if sk else ""))
            if (chat.cache.get(tid) or {}).get("loaded"):
                chat.load_messages(tid)
            chat._list_soon()
            if on_done:
                on_done()

        def err(e):
            win.toast(_("Forwarding stopped: {err}").format(err=e))
        bg(work, done=fin, error=err)


def fwd_v2(src, tid, f):
    """Hintergrund: saga-file/v2-Anhang in den Ziel-Chat weiterleiten -> (Eintrag, Datei-Kennungen). Passt er fuer das Ziel
    in v1 (chatfiles.send_plan "v1"): entschluesselt als v1 neu hochgeladen; sonst ("v2") der Ciphertext kopiert.
    Grosse Dateien nur, wenn das Ziel sie annimmt (Gegenueber ohne big_files: nein); Sprachnachrichten immer als v2."""
    from . import chatfiles
    from .chat import ChatError, is_fed
    ch = chat.chat(tid) or {}
    how = "v2"
    if not f.get("voice"):
        how, _n = chatfiles.send_plan(int(f.get("s") or 0), chat.limits, is_fed(ch), ch.get("big_files"))
        if how in ("peer", "server", "max", "empty"):
            raise ChatError(how)
    elif not (chat.limits or {}).get("file2") or (is_fed(ch) and ch.get("big_files") is False):
        # Sprachnachricht (immer v2): Server ohne file2 bzw. Gegenueber ohne saga-file/v2 koennte sie nicht lesen
        raise ChatError("peer")
    if how == "v1":
        # klein genug fuer v1: entschluesseln und als normaler v1-Anhang senden (auch Gegenueber ohne saga-file/v2
        # bzw. aeltere Server koennen ihn lesen); hoechstens die v1-Grenze im Speicher
        buf = bytearray()
        chat.save_file(src, f, buf.extend)
        e = chat.upload_file(tid, bytes(buf), str(f.get("n") or _("File"))[:200], f.get("m") or "application/octet-stream")
        nid = e["id"]
    else:
        nid = chatvideo.copy_v2(tid, f)
    ids, th = [nid], None
    t = chatmedia.thumb_ref(f)
    if t:
        try:
            plain = chatmedia.decrypt(src, chatmedia.get_blob(t["id"], cid=src), C.unb64(t["k"], 32), chatmedia.ad1(f))
            te = chat.upload_file(tid, plain, "thumb.jpg", t.get("m") or "image/jpeg")
            th = {k: t[k] for k in ("m", "w", "h") if k in t}
            th.update({"id": te["id"], "k": te["k"], "s": len(plain)})
            ids.append(te["id"])
        except Exception:  # noqa: BLE001 - ohne Vorschaubild weiter (Empfaenger zeigen dann den Platzhalter)
            th = None
    if how == "v1":
        out = K.fwd_entry(f, nid, th)
        out.pop("x", None)
        out.pop("cs", None)
        out["k"], out["s"] = e["k"], e["s"]
        return out, ids
    return K.fwd_entry(f, nid, th), ids


def key_is_menu(kv, state):
    return kv == Gdk.KEY_Menu or (kv == Gdk.KEY_F10 and state & Gdk.ModifierType.SHIFT_MASK)
