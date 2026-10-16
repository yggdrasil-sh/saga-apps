"""Einstellungen -> Konto: eigenes Profilbild (Server ab 2.1). GET/PUT/DELETE/PATCH /api/account/avatar.
Hochladen: Bild waehlen -> quadratisch zuschneiden (Kreis-Vorschau, verschieben, vergroessern) -> 512-1024 px als JPEG
(PNG bei Transparenz) als Rohinhalt. Der Server schneidet sicherheitshalber noch einmal mittig zu, dreht nach EXIF und
entfernt alle Metadaten. Aeltere Server (404): Gruppe unsichtbar."""
import io

from gi.repository import Adw, Gdk, GdkPixbuf, Gio, GLib, Gtk

from . import avatars
from .api import APIError, client
from .i18n import _
from .util import bg

MIN_OUT, MAX_OUT = 512, 1024


def prepare(data):
    """Hintergrund: Bild oeffnen, nach EXIF drehen -> PIL-Bild (RGB/RGBA). ValueError: kein (lesbares) Bild."""
    from PIL import Image, ImageOps
    try:
        im = Image.open(io.BytesIO(data))
        im.load()
    except Exception as e:  # noqa: BLE001 - PIL wirft je nach Format verschiedene Fehler
        raise ValueError(_("This image can't be opened.")) from e
    im = ImageOps.exif_transpose(im)
    alpha = im.mode in ("RGBA", "LA", "PA") or (im.mode == "P" and "transparency" in im.info)
    return im.convert("RGBA" if alpha else "RGB")


def crop_bytes(im, box):
    """quadratischer Ausschnitt (links, oben, Kante in Bildpunkten) -> (Bytes, Content-Type)"""
    x, y, side = (int(round(v)) for v in box)
    side = max(1, min(side, im.width, im.height))
    x = max(0, min(x, im.width - side))
    y = max(0, min(y, im.height - side))
    out = im.crop((x, y, x + side, y + side))
    n = max(MIN_OUT, min(MAX_OUT, side))
    if n != side:
        from PIL import Image
        out = out.resize((n, n), Image.LANCZOS)
    buf = io.BytesIO()
    if out.mode == "RGBA" and out.getextrema()[3][0] < 255:
        out.save(buf, "PNG", optimize=True)
        return buf.getvalue(), "image/png"
    out.convert("RGB").save(buf, "JPEG", quality=90)
    return buf.getvalue(), "image/jpeg"


def _pixbuf(im, max_px=900):
    """Vorschau als GdkPixbuf (verkleinert)"""
    pv = im.copy()
    pv.thumbnail((max_px, max_px))
    buf = io.BytesIO()
    pv.save(buf, "PNG")
    ld = GdkPixbuf.PixbufLoader.new_with_type("png")
    ld.write(buf.getvalue())
    ld.close()
    return ld.get_pixbuf(), pv.width / im.width


class CropArea(Gtk.DrawingArea):
    """Bild mit kreisfoermigem Ausschnitt: ziehen verschiebt, Mausrad/Regler vergroessert bzw. verkleinert"""
    __gtype_name__ = "HKCropArea"

    def __init__(self, im):
        super().__init__(content_width=340, content_height=340, hexpand=True, vexpand=True)
        self.im = im
        self.pb, self.pscale = _pixbuf(im)
        self.full = min(im.width, im.height)
        self.side = self.full                                  # Kante in Bildpunkten des Originals
        self.cx, self.cy = im.width / 2, im.height / 2
        self.set_draw_func(self._draw)
        self.update_property([Gtk.AccessibleProperty.LABEL], [_("Crop profile picture")])
        self.set_focusable(True)
        dr = Gtk.GestureDrag()
        dr.connect("drag-begin", lambda g, x, y: setattr(self, "_start", (self.cx, self.cy)))
        dr.connect("drag-update", self._drag)
        self.add_controller(dr)
        sc = Gtk.EventControllerScroll.new(Gtk.EventControllerScrollFlags.VERTICAL)
        sc.connect("scroll", lambda c, dx, dy: (self.zoom(1.08 if dy > 0 else 1 / 1.08), True)[1])
        self.add_controller(sc)
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.add_controller(kc)
        self.on_change = None

    def _fit(self):
        w, h = self.get_width(), self.get_height()
        s = min(w / self.im.width, h / self.im.height) if self.im.width and self.im.height else 1
        return s, (w - self.im.width * s) / 2, (h - self.im.height * s) / 2

    def _clamp(self):
        self.side = max(min(64, self.full), min(self.side, self.full))
        r = self.side / 2
        self.cx = max(r, min(self.im.width - r, self.cx))
        self.cy = max(r, min(self.im.height - r, self.cy))
        self.queue_draw()
        if self.on_change:
            self.on_change()

    def zoom(self, f):
        self.side = self.side * f
        self._clamp()

    def set_fraction(self, v):
        self.side = self.full * v
        self._clamp()

    def _drag(self, g, dx, dy):
        s, _ox, _oy = self._fit()
        sx, sy = getattr(self, "_start", (self.cx, self.cy))
        self.cx, self.cy = sx + dx / s, sy + dy / s
        self._clamp()

    def _key(self, c, kv, code, state):
        step = self.side * 0.05
        moves = {Gdk.KEY_Left: (-step, 0), Gdk.KEY_Right: (step, 0), Gdk.KEY_Up: (0, -step), Gdk.KEY_Down: (0, step)}
        if kv in moves:
            self.cx += moves[kv][0]
            self.cy += moves[kv][1]
            self._clamp()
            return True
        if kv in (Gdk.KEY_plus, Gdk.KEY_KP_Add):
            self.zoom(1 / 1.08)
            return True
        if kv in (Gdk.KEY_minus, Gdk.KEY_KP_Subtract):
            self.zoom(1.08)
            return True
        return False

    def box(self):
        return (self.cx - self.side / 2, self.cy - self.side / 2, self.side)

    def _draw(self, area, cr, w, h):
        import math
        s, ox, oy = self._fit()
        cr.save()
        cr.translate(ox, oy)
        cr.scale(s / self.pscale, s / self.pscale)
        Gdk.cairo_set_source_pixbuf(cr, self.pb, 0, 0)
        cr.paint()
        cr.restore()
        x, y = ox + self.cx * s, oy + self.cy * s
        r = self.side * s / 2
        cr.set_fill_rule(1)                                    # cairo.FILL_RULE_EVEN_ODD: Kreis bleibt frei
        cr.rectangle(ox, oy, self.im.width * s, self.im.height * s)
        cr.arc(x, y, r, 0, 2 * math.pi)
        cr.set_source_rgba(0, 0, 0, 0.55)
        cr.fill()
        cr.arc(x, y, r, 0, 2 * math.pi)
        cr.set_source_rgba(1, 1, 1, 0.9)
        cr.set_line_width(2)
        cr.stroke()


class CropDialog(Adw.Dialog):
    def __init__(self, im, on_done):
        super().__init__(title=_("Crop profile picture"), content_width=440, content_height=560)
        self.on_done = on_done
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar(show_end_title_buttons=False, show_start_title_buttons=False)
        cancel = Gtk.Button(label=_("Cancel"))
        cancel.connect("clicked", lambda *_: self.close())
        hb.pack_start(cancel)
        self.ok = Gtk.Button(label=_("Upload"))
        self.ok.add_css_class("suggested-action")
        self.ok.connect("clicked", lambda *_: self._ok())
        hb.pack_end(self.ok)
        tv.add_top_bar(hb)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)
        self.area = CropArea(im)
        fr = Gtk.Frame(child=self.area)
        box.append(fr)
        row = Gtk.Box(spacing=8)
        row.append(Gtk.Image.new_from_icon_name("zoom-out-symbolic"))
        self.scale = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0.15, 1.0, 0.01)
        self.scale.set_value(1.0)
        self.scale.set_inverted(True)                          # nach rechts = naeher heran (kleinerer Ausschnitt)
        self.scale.set_hexpand(True)
        self.scale.set_draw_value(False)
        self.scale.update_property([Gtk.AccessibleProperty.LABEL], [_("Zoom")])
        self.scale.connect("value-changed", lambda s: self.area.set_fraction(s.get_value()))
        row.append(self.scale)
        row.append(Gtk.Image.new_from_icon_name("zoom-in-symbolic"))
        box.append(row)
        self.area.on_change = lambda: abs(self.scale.get_value() - self.area.side / self.area.full) > 0.005 and \
            self.scale.set_value(self.area.side / self.area.full)
        hint = Gtk.Label(label=_("Drag to move the circle; scroll or use the slider to zoom."), wrap=True)
        hint.add_css_class("dim-label")
        hint.add_css_class("caption")
        box.append(hint)
        self.msg = Gtk.Label(wrap=True, visible=False)
        self.msg.add_css_class("error")
        box.append(self.msg)
        tv.set_content(box)
        self.set_child(tv)

    def failed(self, text):
        self.ok.set_sensitive(True)
        self.msg.set_label(text)
        self.msg.set_visible(True)

    def _ok(self):
        self.ok.set_sensitive(False)
        self.msg.set_visible(False)
        self.on_done(self, self.area.im, self.area.box())


def _source_text(info):
    src = (info or {}).get("source")
    if src == "upload":
        return _("Shown: your own picture")
    if src == "sso":
        return _("Shown: picture from your identity provider")
    if src == "gravatar":
        return _("Shown: Gravatar")
    return _("No picture – your initials are shown.")


class AvatarGroup(Adw.PreferencesGroup):
    """Profilbild: Vorschau, Herkunft, Hochladen/Entfernen, Gravatar, auf veroeffentlichten Seiten"""

    def __init__(self, dlg):
        super().__init__(title=_("Profile picture"))
        self.dlg = dlg
        self.info = None
        self.set_visible(False)
        self._busy = False
        head = Gtk.Box(spacing=16, margin_top=6, margin_bottom=6)
        self.av = Adw.Avatar(size=72, text="?", show_initials=True)
        head.append(self.av)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, valign=Gtk.Align.CENTER, hexpand=True)
        self.src = Gtk.Label(xalign=0, wrap=True)
        col.append(self.src)
        # untereinander statt nebeneinander: passt auch bei Telefon-Breite (360 px)
        btns = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, halign=Gtk.Align.START)
        self.up = Gtk.Button(label=_("Upload picture …"))
        self.up.connect("clicked", lambda *_: self._pick())
        btns.append(self.up)
        self.rm = Gtk.Button(label=_("Remove my picture"))
        self.rm.add_css_class("destructive-action")
        self.rm.connect("clicked", lambda *_: self._remove())
        btns.append(self.rm)
        col.append(btns)
        self.err = Gtk.Label(xalign=0, wrap=True, visible=False)
        self.err.add_css_class("error")
        col.append(self.err)
        head.append(col)
        head.set_margin_start(12)
        head.set_margin_end(12)
        hr = Adw.PreferencesRow(activatable=False, focusable=False, child=head)    # in der Liste, ueber den Schaltern
        hr.set_title(_("Profile picture"))
        self.add(hr)
        self.grav = Adw.SwitchRow(title=_("Use Gravatar if I have no picture of my own"),
                                  subtitle=_("The server fetches and caches Gravatar pictures – your browser and the apps never contact "
                                             "Gravatar directly."))
        self.grav.connect("notify::active", lambda r, _p: self._patch("gravatar", r))
        self.add(self.grav)
        self.pub = Adw.SwitchRow(title=_("Also show on my published pages"))
        self.pub.connect("notify::active", lambda r, _p: self._patch("public", r))
        self.add(self.pub)
        self.load()

    def load(self):
        bg(client.get, "api/account/avatar", done=self.fill, error=lambda e: None)

    def fill(self, info):
        if not isinstance(info, dict):
            return
        self.info = info
        self._busy = True
        name = (self.dlg.win.account_name() if hasattr(self.dlg.win, "account_name") else "") or "?"
        self.av.set_text(name)
        avatars.forget(info.get("url"))
        avatars.attach(self.av, info.get("url"), 72)
        self.src.set_label(_source_text(info))
        self.rm.set_visible(bool(info.get("upload")))
        self.grav.set_visible(bool(info.get("gravatar_enabled")))
        self.grav.set_active(bool(info.get("gravatar")))
        self.grav.set_sensitive(bool(info.get("has_email")))
        self.pub.set_active(bool(info.get("public")))
        self._busy = False
        self.set_visible(True)

    def _error(self, e):
        self.up.set_sensitive(True)
        self.rm.set_sensitive(True)
        if isinstance(e, APIError) and e.code == 413:
            mb = (self.info or {}).get("max_mb") or 5
            text = e.msg or _("The picture is too large (at most {n} MB).").format(n=mb)
        elif isinstance(e, APIError) and e.code == 429:
            text = e.msg or _("Too many uploads – please try again later.")
        else:
            text = str(e)
        self.err.set_label(text)
        self.err.set_visible(True)
        return text

    def _patch(self, key, row):
        if self._busy or self.info is None:
            return
        on = row.get_active()

        def err(e):
            self._busy = True
            row.set_active(not on)
            self._busy = False
            self._error(e)
        bg(client.json, "PATCH", "api/account/avatar", {key: on}, done=self._changed, error=err)

    def _changed(self, info):
        self.err.set_visible(False)
        self.up.set_sensitive(True)
        self.rm.set_sensitive(True)
        old = (self.info or {}).get("url")
        if old:
            avatars.forget(old)
        self.fill(info)
        win = self.dlg.win
        if hasattr(win, "account_changed"):
            win.account_changed()

    def _pick(self):
        fd = Gtk.FileDialog(title=_("Upload picture …"))
        flt = Gtk.FileFilter(name=_("Images"))
        for m in ("image/png", "image/jpeg", "image/gif", "image/webp", "image/heic", "image/heif"):
            flt.add_mime_type(m)
        ls = Gio.ListStore.new(Gtk.FileFilter)
        ls.append(flt)
        fd.set_filters(ls)

        def chosen(d, res):
            try:
                f = d.open_finish(res)
            except GLib.Error:
                return
            self.err.set_visible(False)

            def work():
                ok, data, _etag = f.load_contents(None)
                return prepare(data)
            bg(work, done=self._crop, error=self._error)
        fd.open(self.dlg.win, None, chosen)

    def _crop(self, im):
        CropDialog(im, self._upload).present(self.dlg)

    def _upload(self, cd, im, box):
        def work():
            data, ctype = crop_bytes(im, box)
            return client.json("PUT", "api/account/avatar", raw=data, ctype=ctype, timeout=120)

        def fin(info):
            cd.close()
            self._changed(info)
        bg(work, done=fin, error=lambda e: cd.failed(self._error(e)))

    def _remove(self):
        self.rm.set_sensitive(False)
        bg(client.json, "DELETE", "api/account/avatar", done=lambda info: self._changed(info) if isinstance(info, dict) else self.load(),
           error=self._error)
