"""Nextcloud-Dateien in Seiten (Server ab 2.3, docs/APP-API.md "Nextcloud-Dateien in Seiten"):
- Einstellungen -> Konto -> "Nextcloud": eigenes Konto verknuepfen (Login Flow v2: Link im Browser, die App fragt den
  Server ab) bzw. trennen
- Block "nextcloud": Karte (Typ-Symbol, Name, Groesse), Vorschau fuer Bilder und Ordnerinhalt ueber den Saga-Proxy
  (/api/notes/{id}/nc/...), "In Nextcloud oeffnen", "Herunterladen"; Schalter nur fuer die Person, die ihn eingefuegt hat
- Auswahl einer Datei aus der eigenen Nextcloud (Zuletzt, Ordner, Suche): als Verweis oder als Kopie einfuegen
author/sig setzt nur der Server; die App schreibt bestehende Bloecke unveraendert zurueck."""
import time
import urllib.parse

from gi.repository import Adw, GLib, Gtk, Pango

from .api import client
from .i18n import _
from .util import bg, date_short, human_size, placeholder

KINDS = ("file", "image", "folder")

# --- Kontostand (GET /api/settings/nextcloud), zwischengespeichert --------------------------------------------------
_info = {"t": 0.0, "v": None, "server": None}


def info(done, fresh=False):
    """done({enabled, connected, me, server, user, ...} | None) im Hauptthread; None: Server kann es nicht (404) / Fehler"""
    from .config import settings
    if not fresh and _info["v"] is not None and _info["server"] == settings.server and time.monotonic() - _info["t"] < 120:
        done(_info["v"])
        return

    def fin(j):
        ok = isinstance(j, dict) and "enabled" in j and "connected" in j      # sonst: Server ohne Nextcloud-Dateien
        if ok:
            _info.update(t=time.monotonic(), v=j, server=settings.server)
        done(j if ok else None)
    bg(client.get, "api/settings/nextcloud", done=fin, error=lambda e: done(None))


def forget_info():
    _info.update(t=0.0, v=None)


def me():
    """eigener Kontoname laut Server (fuer author) - None, solange unbekannt"""
    v = _info["v"]
    if isinstance(v, dict) and v.get("me"):
        return v["me"]
    from . import config
    return config.account()


def supported():
    """Server ab 2.3 (Nextcloud-Bloecke einfuegen)"""
    from . import version_tuple
    from .store import store
    return bool(store.server_version) and version_tuple(store.server_version) >= (2, 3, 0)


def size_text(n):
    return human_size(n) if isinstance(n, (int, float)) and not isinstance(n, bool) else ""


def icon_name(d):
    """Symbol nach Art/MIME-Typ (wie die Emojis im Web: Ordner, Bild, PDF, Video, Audio, Tabelle, Praesentation, Archiv)"""
    if d.get("dir") or d.get("kind") == "folder":
        return "folder-symbolic"
    m = str(d.get("mime") or "")
    if m.startswith("image/") or d.get("kind") == "image":
        return "image-x-generic-symbolic"
    if m == "application/pdf":
        return "x-office-document-symbolic"
    if m.startswith("video/"):
        return "video-x-generic-symbolic"
    if m.startswith("audio/"):
        return "audio-x-generic-symbolic"
    if any(x in m for x in ("spreadsheet", "excel", "csv")):
        return "x-office-spreadsheet-symbolic"
    if any(x in m for x in ("presentation", "powerpoint")):
        return "x-office-presentation-symbolic"
    if any(x in m for x in ("zip", "compressed", "tar")):
        return "package-x-generic-symbolic"
    return "text-x-generic-symbolic"


def block_key(d):
    return "|".join(str(d.get(k) or "") for k in ("author", "inst", "fileid"))


def walk_nc(blocks):
    """alle nextcloud-Bloecke (auch in Aufklappern und Spalten)"""
    out = []

    def walk(bs):
        for b in bs if isinstance(bs, list) else []:
            if not isinstance(b, dict):
                continue
            d = b.get("data") if isinstance(b.get("data"), dict) else {}
            if b.get("type") == "nextcloud":
                out.append(d)
            walk(d.get("blocks"))
            for c in d.get("cols") or []:
                if isinstance(c, dict):
                    walk(c.get("blocks"))
    walk(blocks)
    return out


def keys_of(blocks):
    return {block_key(d) for d in walk_nc(blocks)}


def foreign_new(blocks, known, mine):
    """nextcloud-Bloecke einer anderen Person, die nicht schon in dieser Seite stehen (known: Schluessel des
    gespeicherten Stands) - der Server lehnt das Speichern damit ab"""
    return [d for d in walk_nc(blocks) if d.get("author") and d.get("author") != mine and block_key(d) not in known]


def strip_pasted(blocks, known, mine, remote=False):
    """Eingefuegte Bloecke (Zwischenablage): nextcloud-Bloecke anderer Personen (bzw. auf Seiten anderer Saga-Server alle),
    die nicht schon in dieser Seite stehen, herausnehmen - der Server wuerde das Speichern sonst ablehnen (403).
    -> (verbleibende Bloecke, Anzahl entfernt)"""
    n = [0]

    def bad(b):
        if b.get("type") != "nextcloud":
            return False
        d = b.get("data") if isinstance(b.get("data"), dict) else {}
        if block_key(d) in known:
            return False
        if remote:
            return True
        return bool(d.get("author")) and d.get("author") != mine

    def walk(bs):
        out = []
        for b in bs:
            if not isinstance(b, dict):
                continue
            if bad(b):
                n[0] += 1
                continue
            d = b.get("data") if isinstance(b.get("data"), dict) else {}
            if isinstance(d.get("blocks"), list):
                d["blocks"] = walk(d["blocks"])
            for c in d.get("cols") or []:
                if isinstance(c, dict) and isinstance(c.get("blocks"), list):
                    c["blocks"] = walk(c["blocks"])
            out.append(b)
        return out
    rest = walk(list(blocks))
    return rest, n[0]


def proxy_path(nid, d, **q):
    qs = {"a": d.get("author") or ""}
    qs.update({k: v for k, v in q.items() if v is not None})
    return f"api/notes/{int(nid)}/nc/{urllib.parse.quote(str(d.get('fileid') or ''), safe='')}?" + urllib.parse.urlencode(qs)


def open_url(d):
    inst = str(d.get("inst") or "").rstrip("/")
    if not inst.startswith(("https://", "http://")) or any(c in inst for c in " \"<>") or not d.get("fileid"):
        return None
    return inst + "/f/" + urllib.parse.quote(str(d["fileid"]), safe="")


# --- Status der Bloecke einer Seite (GET /api/notes/{id}/nc), je Seite 60 s ---------------------------------------
_status = {}            # nid -> {"t", "items" | None, "cbs": [...]}


def page_status(nid, done, fresh=False):
    """done({items, enabled} | {"items": [], "failed": True}) im Hauptthread"""
    st = _status.get(nid)
    if st and not fresh and (st.get("cbs") is not None or time.monotonic() - st["t"] < 60):
        if st.get("cbs") is not None:
            st["cbs"].append(done)
        else:
            done(st["v"])
        return
    st = _status[nid] = {"t": time.monotonic(), "v": None, "cbs": [done]}

    def fin(j):
        st["v"] = j if isinstance(j, dict) else {"items": [], "failed": True}
        cbs, st["cbs"] = st["cbs"], None
        for c in cbs:
            c(st["v"])
    bg(client.get, f"api/notes/{int(nid)}/nc", done=fin, error=lambda e: fin(None))


def drop_status(nid=None):
    if nid is None:
        _status.clear()
    else:
        _status.pop(nid, None)


# --- Block ------------------------------------------------------------------------------------------------------------
def block_widget(kit, b, d):
    """Karte fuer einen nextcloud-Block (BlockKit.build)"""
    return NcCard(kit, b, d)


class NcCard(Gtk.Box):
    __gtype_name__ = "HKNcCard"

    def __init__(self, kit, b, d):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.kit, self.b, self.d = kit, b, d
        self.view = kit.view
        self.tries = 0
        self.add_css_class("hk-card-box")
        self.add_css_class("hk-nc-card")
        self.nid = getattr(getattr(kit.editor, "doc", None), "id", 0) or 0
        self.remote = bool(getattr(self.view, "remote", False))
        self.draw()

    def _clear(self):
        while (c := self.get_first_child()):
            self.remove(c)

    def draw(self):
        self._clear()
        d = self.d
        if not d.get("fileid"):
            lb = Gtk.Label(label="☁ " + _("Nextcloud file") + " · " + _("No file selected"), xalign=0)
            lb.add_css_class("dim-label")
            self.append(lb)
            return
        from .util import decode_entities
        name = decode_entities(str(d.get("name") or "")) or _("Nextcloud file")
        top = Gtk.Box(spacing=10)
        ic = Gtk.Image.new_from_icon_name(icon_name(d))
        ic.set_pixel_size(24)
        ic.set_valign(Gtk.Align.CENTER)
        top.append(ic)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
        self.name_lb = Gtk.Label(label=name, xalign=0, ellipsize=Pango.EllipsizeMode.MIDDLE)
        self.name_lb.add_css_class("heading")
        col.append(self.name_lb)
        self.meta_lb = Gtk.Label(label=" · ".join(x for x in (_("Folder") if d.get("kind") == "folder" else size_text(d.get("size")),
                                                               "Nextcloud") if x), xalign=0)
        self.meta_lb.add_css_class("caption")
        self.meta_lb.add_css_class("dim-label")
        col.append(self.meta_lb)
        top.append(col)
        self.acts = Gtk.Box(spacing=4, valign=Gtk.Align.CENTER)
        top.append(self.acts)
        self.append(top)
        self.dl_btn = None
        if d.get("kind") != "folder":
            self.dl_btn = Gtk.Button(icon_name="folder-download-symbolic", tooltip_text=_("Download"))
            self.dl_btn.add_css_class("flat")
            self.dl_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Download")])
            self.dl_btn.connect("clicked", lambda *_: self.view.win.download_open("/" + proxy_path(self.nid, d, dl="1"), name))
            self.acts.append(self.dl_btn)
        url = open_url(d)
        if url and not self.remote:
            ob = Gtk.Button(icon_name="adw-external-link-symbolic", tooltip_text=_("Open in Nextcloud"))
            ob.add_css_class("flat")
            ob.update_property([Gtk.AccessibleProperty.LABEL], [_("Open in Nextcloud")])
            ob.connect("clicked", lambda *_: self.view.win.open_uri(url))
            self.acts.append(ob)
        self.media = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        self.append(self.media)
        self.note = Gtk.Label(xalign=0, wrap=True, visible=False)
        self.note.add_css_class("dim-label")
        self.note.add_css_class("caption")
        self.append(self.note)
        self.owner_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.append(self.owner_box)
        if self.nid:
            page_status(self.nid, self._status)
        if me() is None and not self.remote and not self.kit.editor.locked:
            info(lambda _s: self._owner_part())             # eigener Kontoname noch unbekannt: erst fragen
        else:
            self._owner_part()

    def _fresh(self):
        """eben eingefuegt (noch ohne Signatur): der Server kennt den Block erst nach dem Speichern"""
        return not self.d.get("sig") and not self.remote

    def _retry(self):
        if not self._fresh() or self.tries >= 8 or self.get_root() is None:
            return False
        self.tries += 1
        self.note.set_label(_("Loading …"))
        self.note.set_visible(True)

        def again():
            if self.get_root() is not None:
                drop_status(self.nid)
                page_status(self.nid, self._status)
            return False
        GLib.timeout_add(1500, again)
        return True

    def _unavailable(self, txt):
        if self._retry():
            return
        while (c := self.media.get_first_child()):
            self.media.remove(c)
        self.note.set_label(txt)
        self.note.set_visible(True)
        self.add_css_class("hk-nc-off")
        if self.dl_btn is not None:
            self.dl_btn.set_visible(False)

    def _status(self, st):
        if self.get_root() is None and self.get_parent() is None:
            return
        d = self.d
        it = next((x for x in (st or {}).get("items") or [] if isinstance(x, dict) and str(x.get("fileid")) == str(d.get("fileid"))
                   and (x.get("author") == d.get("author") or self.remote)), None)
        far = _("Only visible on the page’s server (sharing with connected servers is off).")
        if (st or {}).get("failed") and not it:
            if self.remote:
                self._unavailable(far)
            return
        if not it or not it.get("ok"):
            self._unavailable(far if self.remote else _("Not available – link removed, no access anymore or file deleted."))
            return
        self.note.set_visible(False)
        self.remove_css_class("hk-nc-off")
        if self.dl_btn is not None:
            self.dl_btn.set_visible(True)
        if it.get("name"):
            self.name_lb.set_label(str(it["name"]))
        self.meta_lb.set_label(" · ".join(x for x in (_("Folder") if it.get("dir") else size_text(it.get("size")),
                                                       date_short(it.get("mtime")) if it.get("mtime") else "", "Nextcloud") if x))
        while (c := self.media.get_first_child()):
            self.media.remove(c)
        if it.get("dir"):
            ents = [e for e in it.get("entries") or [] if isinstance(e, dict)]
            for e in ents:
                row = Gtk.Box(spacing=6)
                im = Gtk.Image.new_from_icon_name("folder-symbolic" if e.get("dir") else "text-x-generic-symbolic")
                row.append(im)
                row.append(Gtk.Label(label=str(e.get("name") or ""), xalign=0, ellipsize=Pango.EllipsizeMode.MIDDLE, hexpand=True))
                if not e.get("dir") and e.get("size") is not None:
                    sz = Gtk.Label(label=size_text(e.get("size")))
                    sz.add_css_class("dim-label")
                    sz.add_css_class("caption")
                    row.append(sz)
                self.media.append(row)
            if not ents:
                lb = Gtk.Label(label=_("Empty folder"), xalign=0)
                lb.add_css_class("dim-label")
                self.media.append(lb)
        elif d.get("kind") == "image" or it.get("preview"):
            from . import images
            big = d.get("kind") == "image"
            # v: Stand der Datei (neue Fassung in Nextcloud -> neue Vorschau statt der zwischengespeicherten)
            img = images.ImageView("/" + proxy_path(self.nid, d, w=1280 if big else 320, v=it.get("mtime") or d.get("mtime")),
                                   mode="fit", max_height=600 if big else 200, radius=8)
            if not big:
                img.set_size_request(160, -1)
            holder = Gtk.Box(halign=Gtk.Align.START)    # waagrecht: Hoehe passt zur echten Breite (kleine Bilder ohne Leerraum)
            holder.append(img)
            self.media.append(holder)

    def _owner_part(self):
        """Nur die Person, die den Block eingefuegt hat: Schalter + "Als Kopie uebernehmen"; andere: Hinweis"""
        ed = self.kit.editor
        while (c := self.owner_box.get_first_child()):
            self.owner_box.remove(c)
        if ed.locked or self.remote:
            return
        d = self.d
        mine = me()
        if d.get("author") and mine and d["author"] != mine:
            lb = Gtk.Label(label=_("Inserted from another person’s Nextcloud – only they can change the sharing settings."),
                           xalign=0, wrap=True)
            lb.add_css_class("dim-label")
            lb.add_css_class("caption")
            self.owner_box.append(lb)
            return
        if not d.get("author") or not mine:
            return
        opts = Gtk.Box(spacing=12)
        for label, key, tip in ((_("Show on published page"), "public",
                                 _("Visitors of the published page see preview and download (via Saga, not your Nextcloud address).")),
                                (_("Show via connected servers"), "fed",
                                 _("People on other Saga servers the page is shared with see preview and download."))):
            cb = Gtk.CheckButton(label=label, active=d.get(key) is True, tooltip_text=tip)

            def toggled(c, key=key):
                d[key] = c.get_active()
                d.pop("sig", None)                      # Schalter geaendert: der Server signiert neu
                self.kit.synced(self)
                ed.text_changed()
            cb.connect("toggled", toggled)
            opts.append(cb)
        if d.get("kind") != "folder":
            cp = Gtk.Button(label=_("Insert as copy"), tooltip_text=_("The file is copied into Saga and then follows Saga’s permissions "
                                                                   "(also offline, in backups and exports)."))
            cp.add_css_class("flat")
            cp.connect("clicked", lambda *_: self._copy(cp))
            opts.append(cp)
        self.owner_box.append(opts)

    def _copy(self, btn):
        btn.set_sensitive(False)
        body = {"fileid": self.d.get("fileid")}
        if getattr(self.view, "upload_note", None):
            body["note"] = self.view.upload_note

        def fin(r):
            f = (r or {}).get("file") if isinstance(r, dict) else None
            bid = self.b.get("id")
            if not f or not f.get("url"):
                btn.set_sensitive(True)
                return
            replace_with_copy(self.kit.editor, bid, f)

        def err(e):
            btn.set_sensitive(True)
            self.view.win.toast(_("Not copied: {error}").format(error=e))
        bg(client.json, "POST", "api/nextcloud/import", body, done=fin, error=err)


def replace_with_copy(ed, bid, f):
    """Block bid durch einen Bild- bzw. Dateiblock mit der kopierten Datei ersetzen"""
    rr = ed.locate(bid)
    if not rr:
        return
    if str(f.get("mime") or "").startswith("image/"):
        nb = {"id": bid, "type": "image", "data": {"file": {"url": f["url"]}, "caption": "", "withBorder": False, "stretched": False,
                                                    "withBackground": False}}
    else:
        nb = {"id": bid, "type": "attaches", "data": {"file": f, "title": f.get("name") or ""}}
    rr[0][rr[1]] = nb
    ed.set_focus(None)
    ed.changed()


def new_block_data(it, author):
    """Eintrag der Auswahl -> Daten eines neuen Blocks (author: eigenes Konto; sig setzt der Server)"""
    return {"inst": it.get("inst"), "fileid": str(it.get("fileid") or ""), "kind": it.get("kind") if it.get("kind") in KINDS else "file",
            "name": it.get("name") or "", "mime": it.get("mime") or "", "size": it.get("size"), "mtime": it.get("mtime"),
            "author": author, "public": False, "fed": False}


# --- Auswahl ------------------------------------------------------------------------------------------------------------
class NcPicker(Adw.Dialog):
    """Datei aus der eigenen Nextcloud: Zuletzt (Suche) bzw. Ordner. on_link(item) / on_copy(file-info).
    link=False: nur "Als Kopie" (Seite einer anderen Saga-Instanz). note: fremde Seite (Kopie gehoert deren Eigentuemer)."""

    def __init__(self, win, on_link, on_copy, link=True, note=None):
        super().__init__(title=_("File from Nextcloud"), content_width=600, content_height=620)
        self.win, self.on_link, self.on_copy, self.link, self.note = win, on_link, on_copy, link, note
        self.mode = "recent"
        self.path = []                      # [(name, item)] - Ordnerkette (item fuer "Diesen Ordner einfuegen")
        self.gen = 0
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.back = Gtk.Button(icon_name="go-previous-symbolic", tooltip_text=_("Back"), visible=False)
        self.back.connect("clicked", lambda *_: self._up())
        hb.pack_start(self.back)
        tv.add_top_bar(hb)
        self.box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=8, margin_bottom=8, margin_start=10, margin_end=10)
        tv.set_content(self.box)
        self.set_child(tv)
        self.info = Gtk.Label(xalign=0, wrap=True)
        self.info.add_css_class("dim-label")
        self.box.append(self.info)
        self.info.set_label(_("Loading …"))
        info(self._state, fresh=True)

    def _message(self, txt, settings_btn=False):
        while (c := self.box.get_first_child()):
            self.box.remove(c)
        lb = Gtk.Label(label=txt, wrap=True, vexpand=not settings_btn)
        lb.add_css_class("dim-label")
        self.box.append(lb)
        if settings_btn:
            b = Gtk.Button(label=_("Open settings"), halign=Gtk.Align.CENTER, vexpand=True, valign=Gtk.Align.START)
            b.connect("clicked", lambda *_: (self.close(), self.win.settings_dialog(page="settings")))
            self.box.append(b)

    def _state(self, s):
        if s is None:
            self._message(_("This server doesn’t support Nextcloud files."))
        elif not s.get("enabled"):
            self._message(_("Nextcloud files are turned off on this server."))
        elif not s.get("connected"):
            self._message(_("Not connected to Nextcloud yet. Connect your Nextcloud in the settings under Account → Nextcloud."),
                          settings_btn=True)
        else:
            self._content()

    def _content(self):
        while (c := self.box.get_first_child()):
            self.box.remove(c)
        sw = Gtk.StackSwitcher(halign=Gtk.Align.CENTER)
        self.tabs = Gtk.Stack()
        self.tabs.add_titled(Gtk.Box(), "recent", _("Recent"))
        self.tabs.add_titled(Gtk.Box(), "dirs", _("Folders"))
        sw.set_stack(self.tabs)
        self.tabs.connect("notify::visible-child-name", lambda *_: self._tab())
        self.box.append(sw)
        self.search = Gtk.SearchEntry(placeholder_text=_("Search file name …"))
        self.search.connect("activate", lambda *_: self._load())
        self.search.connect("stop-search", lambda *_: self.close())
        self.box.append(self.search)
        self.crumb = Gtk.Box(spacing=6, visible=False)
        self.crumb_lb = Gtk.Label(xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.START)
        self.crumb.append(self.crumb_lb)
        self.here = Gtk.Button(label=_("Insert this folder"))
        self.here.add_css_class("flat")
        self.here.connect("clicked", lambda *_: self.path and self._choose(self.path[-1][1]))
        self.crumb.append(self.here)
        self.box.append(self.crumb)
        self.box.append(self.info)
        sc = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.list.add_css_class("boxed-list")
        self.list.update_property([Gtk.AccessibleProperty.LABEL], [_("Files")])
        self.list.connect("row-activated", lambda lb, row: self._activate(row.item))
        sc.set_child(self.list)
        self.box.append(sc)
        self.foot = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.box.append(self.foot)
        self._load()
        GLib.idle_add(lambda: (self.search.grab_focus(), False)[1])

    def _tab(self):
        self.mode = self.tabs.get_visible_child_name() or "recent"
        self.path = []
        self.search.set_visible(self.mode == "recent")
        self._load()

    def _up(self):
        if self.path:
            self.path.pop()
        self._load()

    def _load(self):
        self.gen += 1
        gen = self.gen
        self.info.set_label(_("Loading …"))
        self.list.remove_all()
        self._clear_foot()
        dirs = self.mode == "dirs"
        self.back.set_visible(dirs and bool(self.path))
        self.crumb.set_visible(dirs)
        if dirs:
            p = "/" + "/".join(n for n, _it in self.path)
            self.crumb_lb.set_label("☁ " + p)
            self.here.set_visible(bool(self.path) and self.link)
            path = "api/nextcloud/list?" + urllib.parse.urlencode({"path": p})
        else:
            path = "api/nextcloud/search?" + urllib.parse.urlencode({"q": self.search.get_text().strip()})

        def fin(j):
            if gen != self.gen:
                return
            items = [x for x in (j or {}).get("items") or [] if isinstance(x, dict)]
            self.info.set_label("" if items else _("Nothing found."))
            self.info.set_visible(not items)
            for it in items:
                self.list.append(self._row(it))

        def err(e):
            if gen == self.gen:
                self.info.set_visible(True)
                self.info.set_label(str(e))
        bg(client.get, path, done=fin, error=err)

    def _row(self, it):
        row = Gtk.ListBoxRow()
        row.item = it
        box = Gtk.Box(spacing=10, margin_top=6, margin_bottom=6, margin_start=8, margin_end=8)
        if it.get("preview") and not it.get("dir") and it.get("fileid"):
            from . import images
            th = images.ImageView(f"/api/nextcloud/thumb/{urllib.parse.quote(str(it['fileid']), safe='')}?w=96", mode="cover", radius=4)
            th.set_size_request(36, 36)
            th.set_valign(Gtk.Align.CENTER)
            box.append(th)
        else:
            ic = Gtk.Image.new_from_icon_name(icon_name(it))
            ic.set_pixel_size(24)
            box.append(ic)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
        col.append(Gtk.Label(label=str(it.get("name") or ""), xalign=0, ellipsize=Pango.EllipsizeMode.MIDDLE))
        meta = _("Folder") if it.get("dir") else " · ".join(x for x in (size_text(it.get("size")), date_short(it.get("mtime"))
                                                                         if it.get("mtime") else "") if x)
        if self.mode != "dirs" and it.get("path"):
            par = str(it["path"]).rsplit("/", 1)[0] or "/"
            meta = " · ".join(x for x in (meta, par) if x)
        m = Gtk.Label(label=meta, xalign=0, ellipsize=Pango.EllipsizeMode.START)
        m.add_css_class("caption")
        m.add_css_class("dim-label")
        col.append(m)
        box.append(col)
        if it.get("dir") and self.mode == "dirs":
            box.append(Gtk.Image.new_from_icon_name("go-next-symbolic"))
        row.set_child(box)
        row.update_property([Gtk.AccessibleProperty.LABEL], [str(it.get("name") or "") + (" – " + meta if meta else "")])
        return row

    def _activate(self, it):
        if it.get("dir") and self.mode == "dirs":
            self.path.append((str(it.get("name") or ""), it))
            self._load()
            return
        self._choose(it)

    def _clear_foot(self):
        while (c := self.foot.get_first_child()):
            self.foot.remove(c)

    def _choose(self, it):
        self._clear_foot()
        t = Gtk.Label(label=str(it.get("name") or ""), xalign=0, ellipsize=Pango.EllipsizeMode.MIDDLE)
        t.add_css_class("heading")
        self.foot.append(t)
        warn = Gtk.Label(label=_("Everyone who can see this page can also see this file.") + " " +
                         _("Preview and download go through your Nextcloud account as long as it is connected.")
                         if self.link else _("On pages from other Saga servers, Nextcloud files can only be inserted as a copy."),
                         xalign=0, wrap=True)
        warn.add_css_class("caption")
        warn.add_css_class("warning")
        self.foot.append(warn)
        bar = Gtk.Box(spacing=6)
        msg = Gtk.Label(xalign=0, wrap=True)
        msg.add_css_class("caption")
        lk = None
        if self.link:
            lk = Gtk.Button(label=_("Insert as link"))
            lk.add_css_class("suggested-action")
            lk.connect("clicked", lambda *_: (self.close(), self.on_link(it)))
            bar.append(lk)
        if not it.get("dir"):
            cp = Gtk.Button(label=_("Insert as copy"), tooltip_text=_("The file is copied into Saga and then follows Saga’s "
                                                                   "permissions (also offline, in backups and exports)."))
            if not self.link:
                cp.add_css_class("suggested-action")

            def copy(*_a):
                cp.set_sensitive(False)
                if lk is not None:
                    lk.set_sensitive(False)
                msg.set_label(_("Copying “{name}” …").format(name=it.get("name") or ""))
                body = {"fileid": it.get("fileid")}
                if self.note:
                    body["note"] = self.note

                def fin(r):
                    f = (r or {}).get("file") if isinstance(r, dict) else None
                    if not f or not f.get("url"):
                        msg.set_label(_("Not copied: {error}").format(error="?"))
                        cp.set_sensitive(True)
                        return
                    self.close()
                    self.on_copy(f)

                def err(e):
                    msg.set_label(_("Not copied: {error}").format(error=e))
                    cp.set_sensitive(True)
                    if lk is not None:
                        lk.set_sensitive(True)
                bg(client.json, "POST", "api/nextcloud/import", body, done=fin, error=err)
            cp.connect("clicked", copy)
            bar.append(cp)
        self.foot.append(bar)
        self.foot.append(msg)


# --- Einstellungen -> Konto -> Nextcloud -----------------------------------------------------------------------------
class NextcloudGroup(Adw.PreferencesGroup):
    """Eigenes Nextcloud-Konto verknuepfen (Login Flow v2) bzw. trennen; unsichtbar, wenn der Server es nicht kann"""

    def __init__(self, dlg):
        super().__init__(title="Nextcloud",
                         description=_("Link your own Nextcloud to insert files from it into pages (“/” menu → “Nextcloud file”). "
                                       "Saga gets its own app password – Saga never sees your password."))
        self.dlg = dlg
        self.rows = []
        self.timer = 0
        self.set_visible(False)
        self.connect("unrealize", lambda *_: self._stop())
        self.load()

    def _stop(self):
        if self.timer:
            GLib.source_remove(self.timer)
            self.timer = 0

    def _poll(self, ms=2500):
        self._stop()

        def tick():
            self.timer = 0
            if self.get_root() is not None:
                self.load()
            return False
        self.timer = GLib.timeout_add(ms, tick)

    def load(self):
        info(self.fill, fresh=True)

    def _add(self, w):
        self.add(w)
        self.rows.append(w)

    def fill(self, s):
        for r in self.rows:
            self.remove(r)
        self.rows = []
        if s is None:                                   # Server ohne Nextcloud-Dateien (vor 2.3) oder Fehler
            self.set_visible(False)
            return
        self.set_visible(True)
        msg = Gtk.Label(xalign=0, wrap=True, visible=False)
        msg.add_css_class("error")
        if not s.get("enabled"):
            self._add(Adw.ActionRow(title=_("Nextcloud files are turned off on this server.")))
        fl = s.get("flow") if isinstance(s.get("flow"), dict) else {}
        if fl.get("state") == "wait" and fl.get("login"):
            r = Adw.ActionRow(title=_("Nextcloud found"),
                              subtitle=_("Sign in there and allow access – Saga gets its own app password (Saga never sees your password)."))
            r.set_subtitle_lines(0)
            r.add_suffix(_btn(_("Sign in to Nextcloud"), "suggested-action", lambda: self.dlg.win.open_uri(fl["login"])))
            r.add_suffix(_btn(_("Cancel"), None, lambda: bg(client.request, "POST", "api/settings/nextcloud/cancel",
                                                          done=lambda _: self.load(), error=lambda e: self.load())))
            self._add(r)
            self._poll()
            return
        err_row = None
        if fl.get("message"):
            err_row = Adw.ActionRow(title=GLib.markup_escape_text(str(fl["message"])))
            err_row.add_css_class("error")
        if s.get("connected"):
            from .util import date_time_text
            host = str(s.get("server") or "").replace("https://", "").replace("http://", "")
            r = Adw.ActionRow(title="✅ " + GLib.markup_escape_text(_("Connected as {name}").format(name=s.get("user") or "")) +
                              f" ({GLib.markup_escape_text(host)})",
                              subtitle=GLib.markup_escape_text(_("Linked since {date} · last used {last}").format(
                                  date=date_time_text(s.get("since")) or "–", last=date_time_text(s.get("used")) or "–")))
            r.add_suffix(_btn(_("Disconnect"), "destructive-action", self._disconnect))
            self._add(r)
            hint = Adw.ActionRow(title=_("Everyone who can see a page also sees the Nextcloud files you inserted there. If you "
                                         "disconnect, your inserted files only show a card without content."))
            hint.set_title_lines(0)
            hint.add_css_class("dim-label")
            self._add(hint)
            if err_row is not None:
                self._add(err_row)
            return
        if err_row is not None:
            self._add(err_row)
        if not s.get("enabled"):
            return
        url = Adw.EntryRow(title=_("Nextcloud address"), text=s.get("suggest") or "")
        placeholder(url, "https://cloud.example.org")
        st = Adw.ActionRow(title=_("Connect"))

        def go():
            st.set_subtitle(_("Checking …"))

            def fin(j):
                forget_info()
                if isinstance(j, dict) and j.get("flow"):
                    self.dlg.win.open_uri(j["flow"])
                self.load()
            bg(client.json, "POST", "api/settings/nextcloud", {"url": url.get_text().strip()}, done=fin,
               error=lambda e: st.set_subtitle(GLib.markup_escape_text(str(e))))
        url.connect("entry-activated", lambda *_: go())
        st.add_suffix(_btn(_("Connect"), "suggested-action", go))
        self._add(url)
        self._add(st)

    def _disconnect(self):
        d = Adw.AlertDialog(heading=_("Disconnect Nextcloud?"),
                            body=_("Saga revokes the app password; your inserted files then no longer show any content."))
        d.add_response("cancel", _("Cancel"))
        d.add_response("ok", _("Disconnect"))
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        d.set_close_response("cancel")

        def resp(dd, r):
            if r == "ok":
                def fin(_j):
                    forget_info()
                    drop_status()
                    self.load()
                bg(client.request, "DELETE", "api/settings/nextcloud", done=fin,
                   error=lambda e: self.dlg.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))))
        d.connect("response", resp)
        d.present(self.dlg)
        self.ask = d                                     # fuer Tests


def _btn(label, css=None, cb=None):
    b = Gtk.Button(label=label, valign=Gtk.Align.CENTER)
    if css:
        b.add_css_class(css)
    if cb:
        b.connect("clicked", lambda *_: cb())
    return b
