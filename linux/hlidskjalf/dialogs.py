"""Dialoge: Befehlspalette, Papierkorb, Verschieben, Versionen, Seitenauswahl, Immich, Schaltflaechen, Dienste."""
from gi.repository import Adw, Gdk, GLib, Gtk, Pango

from . import images
from .api import client
from .blocks import flat_button
from .store import is_db, label, store
from .util import bg, date_short, date_time_text, norm


def _row(icon_widget, title, subtitle=None, suffix=None):
    r = Gtk.Box(spacing=10, margin_top=4, margin_bottom=4, margin_start=6, margin_end=6)
    r.append(icon_widget)
    col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
    col.append(Gtk.Label(label=title, xalign=0, ellipsize=Pango.EllipsizeMode.END))
    if subtitle:
        s = Gtk.Label(label=subtitle, xalign=0, ellipsize=Pango.EllipsizeMode.END)
        s.add_css_class("caption")
        s.add_css_class("dim-label")
        col.append(s)
    r.append(col)
    if suffix:
        r.append(suffix)
    return r


def _page_icon(p):
    return images.page_icon((p or {}).get("icon"), fallback="🗃️" if is_db(p) else "📄")


class CommandPalette(Adw.Dialog):
    """Strg+K: Seiten nach Name und Inhalt, mit ">" nur Befehle"""

    def __init__(self, win, actions):
        super().__init__(content_width=600, content_height=460)
        self.win, self.actions = win, actions
        self.found = []
        self.sel = 0
        self.hits = []
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.entry = Gtk.SearchEntry(placeholder_text="Seite suchen … (> für Befehle)", margin_top=12, margin_bottom=8, margin_start=12, margin_end=12)
        self.entry.connect("search-changed", self._changed)
        self.entry.connect("activate", lambda *_: self._pick(self.sel))
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.entry.add_controller(kc)
        box.append(self.entry)
        box.append(Gtk.Separator())
        sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.list.add_css_class("navigation-sidebar")
        self.list.connect("row-activated", lambda lb, r: self._pick(r.get_index()))
        sw.set_child(self.list)
        box.append(sw)
        self.set_child(box)
        self.fill()
        GLib.idle_add(lambda: (self.entry.grab_focus(), False)[1])

    def _key(self, c, kv, code, st):
        if kv == Gdk.KEY_Down:
            self._select(self.sel + 1)
            return True
        if kv == Gdk.KEY_Up:
            self._select(self.sel - 1)
            return True
        return False

    def _select(self, i):
        if not self.hits:
            return
        self.sel = i % len(self.hits)
        self.list.select_row(self.list.get_row_at_index(self.sel))

    def _changed(self, e):
        t = e.get_text().strip()
        self.sel = 0
        if len(t) >= 2 and not t.startswith(">"):
            def got(r, q=t):
                if self.entry.get_text().strip() == q:
                    self.found = r or []
                    self.fill()
            store.search(t, got)
        else:
            self.found = []
        self.fill()

    def fill(self):
        while (c := self.list.get_first_child()):
            self.list.remove(c)
        t = self.entry.get_text().strip()
        hits = []
        if t.startswith(">"):
            a = norm(t[1:])
            hits = [("a", x) for x in self.actions if not a or a in norm(x[1])]
        else:
            n = norm(t)
            if not n:
                pages = [store.by_id[i] for i in store.recent if i in store.by_id][:8]
            else:
                by_name = [p for p in store.pages if n in norm(label(p))]
                ids = {p["id"] for p in by_name}
                pages = by_name + [f for f in self.found if f["id"] not in ids]
            hits = [("p", p) for p in pages[:12]]
            if n:
                hits += [("a", x) for x in self.actions if n in norm(x[1])][:3]
        self.hits = hits
        for kind, x in hits:
            if kind == "p":
                w = _row(_page_icon(x), label(x), store.path_text(x["id"]) or None)
            else:
                suf = Gtk.Label(label="Befehl")
                suf.add_css_class("dim-label")
                suf.add_css_class("caption")
                w = _row(Gtk.Image.new_from_icon_name(x[0]), x[1], None, suf)
            self.list.append(w)
        if not hits:
            lb = Gtk.Label(label="Nichts gefunden", margin_top=12)
            lb.add_css_class("dim-label")
            self.list.append(lb)
        self._select(self.sel)

    def _pick(self, i):
        if not (0 <= i < len(self.hits)):
            return
        kind, x = self.hits[i]
        self.close()
        if kind == "p":
            self.win.show_page(x["id"], root=True)
        else:
            GLib.idle_add(lambda: (x[2](), False)[1])


class TrashDialog(Adw.Dialog):
    def __init__(self, win):
        super().__init__(title="Papierkorb", content_width=560, content_height=460)
        self.win = win
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.empty_btn = Gtk.Button(label="Papierkorb leeren")
        self.empty_btn.add_css_class("destructive-action")
        self.empty_btn.connect("clicked", lambda *_: self._confirm_empty())
        hb.pack_start(self.empty_btn)
        tv.add_top_bar(hb)
        sw = Gtk.ScrolledWindow(vexpand=True)
        self.box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_top=8, margin_bottom=8, margin_start=10, margin_end=10)
        sw.set_child(self.box)
        tv.set_content(sw)
        self.set_child(tv)
        self.load()

    def load(self):
        bg(client.get, "api/trash", done=self.fill, error=lambda e: self.fill([]))

    def fill(self, items):
        while (c := self.box.get_first_child()):
            self.box.remove(c)
        self.empty_btn.set_sensitive(bool(items))
        if not items:
            lb = Gtk.Label(label="Der Papierkorb ist leer.", margin_top=20)
            lb.add_css_class("dim-label")
            self.box.append(lb)
        for it in items or []:
            sub = it.get("subpages") or 0
            txt = (f"mit {sub} Unterseite{'' if sub == 1 else 'n'} · " if sub else "") + "wird gelöscht am " + date_short(it.get("purge_at"))
            suf = Gtk.Box(spacing=6)
            rb = Gtk.Button(label="Zurückholen")
            rb.connect("clicked", lambda *_, i=it["id"]: store.run("POST", f"api/trash/{i}/restore", {}, done=lambda _: (self.load(), store.refresh())))
            suf.append(rb)
            db = Gtk.Button(label="Endgültig löschen")
            db.add_css_class("destructive-action")
            db.connect("clicked", lambda *_, i=it["id"]: store.run("DELETE", f"api/trash/{i}", done=lambda _: self.load()))
            suf.append(db)
            self.box.append(_row(images.page_icon(it.get("icon")), it.get("title") or "Unbenannt", txt, suf))

    def _confirm_empty(self):
        d = Adw.AlertDialog(heading="Papierkorb leeren?", body="Alle Seiten im Papierkorb werden endgültig gelöscht.")
        d.add_response("cancel", "Abbrechen")
        d.add_response("ok", "Endgültig löschen")
        d.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE)
        d.connect("response", lambda dd, r: r == "ok" and store.run("POST", "api/trash/empty", {}, done=lambda _: self.load()))
        d.present(self.win)


class PagePicker(Adw.Dialog):
    """Seite auswaehlen (Verschieben, Datenbank verknuepfen, ...)"""

    def __init__(self, win, title, pages, on_pick, top_level=None):
        super().__init__(title=title, content_width=440, content_height=520)
        self.pages, self.on_pick, self.top_level = pages, on_pick, top_level
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=8, margin_start=10, margin_end=10, margin_bottom=8)
        self.entry = Gtk.SearchEntry(placeholder_text="Seite suchen")
        self.entry.connect("search-changed", lambda *_: self.fill())
        box.append(self.entry)
        sw = Gtk.ScrolledWindow(vexpand=True)
        self.list = Gtk.ListBox()
        self.list.add_css_class("navigation-sidebar")
        self.list.connect("row-activated", self._act)
        sw.set_child(self.list)
        box.append(sw)
        self.err = Gtk.Label(xalign=0, wrap=True)
        self.err.add_css_class("error")
        box.append(self.err)
        tv.set_content(box)
        self.set_child(tv)
        self.shown = []
        self.fill()
        GLib.idle_add(lambda: (self.entry.grab_focus(), False)[1])

    def fill(self):
        while (c := self.list.get_first_child()):
            self.list.remove(c)
        q = norm(self.entry.get_text())
        self.shown = []
        if self.top_level and not q:
            self.shown.append(None)
            self.list.append(_row(Gtk.Image.new_from_icon_name("go-top-symbolic"), self.top_level))
        for p in self.pages:
            if q and q not in norm(label(p)):
                continue
            self.shown.append(p)
            self.list.append(_row(_page_icon(p), label(p), store.path_text(p["id"]) or None))
            if len(self.shown) > 200:
                break

    def _act(self, lb, row):
        p = self.shown[row.get_index()]
        self.on_pick(None if p is None else p["id"], self) if self.top_level else self.on_pick(p["id"])
        if not self.top_level:
            self.close()


def move_dialog(win, pid):
    blocked = store.descendants(pid)
    cur = (store.by_id.get(pid) or {}).get("parent_id")
    targets = [p for p in store.pages if p["id"] not in blocked and not is_db(p) and p["id"] != cur
               and not is_db(store.by_id.get(p.get("parent_id")))]

    def go(parent, dlg):
        store.move(pid, parent, done=dlg.close, error=lambda e: dlg.err.set_label(str(e)))
    PagePicker(win, "Verschieben nach", targets, go, top_level="Oberste Ebene" if cur is not None else None).present(win)


class VersionsDialog(Adw.Dialog):
    def __init__(self, win, pid, restored):
        super().__init__(title="Versionsverlauf", content_width=760, content_height=500)
        self.pid, self.restored = pid, restored
        self.sel = None
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.restore_btn = Gtk.Button(label="Diese Version wiederherstellen", sensitive=False)
        self.restore_btn.add_css_class("suggested-action")
        self.restore_btn.connect("clicked", lambda *_: self._restore())
        hb.pack_end(self.restore_btn)
        tv.add_top_bar(hb)
        pane = Gtk.Paned(position=240)
        sw = Gtk.ScrolledWindow()
        self.list = Gtk.ListBox()
        self.list.add_css_class("navigation-sidebar")
        self.list.connect("row-selected", self._selected)
        sw.set_child(self.list)
        pane.set_start_child(sw)
        sw2 = Gtk.ScrolledWindow()
        self.prev = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_top=16, margin_bottom=16, margin_start=16, margin_end=16)
        sw2.set_child(self.prev)
        pane.set_end_child(sw2)
        tv.set_content(pane)
        self.set_child(tv)
        self.versions = []
        self._msg("Version auswählen, um sie anzusehen.")
        bg(client.get, f"api/notes/{pid}/versions", done=self._fill, error=lambda e: self._msg(str(e)))

    def _msg(self, t):
        while (c := self.prev.get_first_child()):
            self.prev.remove(c)
        lb = Gtk.Label(label=t, wrap=True, xalign=0)
        lb.add_css_class("dim-label")
        self.prev.append(lb)

    def _fill(self, vs):
        self.versions = vs or []
        if not self.versions:
            self._msg("Noch keine früheren Versionen. Beim Bearbeiten wird höchstens alle 10 Minuten ein Stand gesichert (bis zu 50 pro Seite).")
        for v in self.versions:
            self.list.append(_row(Gtk.Image.new_from_icon_name("document-open-recent-symbolic"), date_time_text(v.get("created")), v.get("title") or "Unbenannt"))

    def _selected(self, lb, row):
        if row is None:
            return
        v = self.versions[row.get_index()]
        self.sel = v["id"]
        self.restore_btn.set_sensitive(True)

        def show(p):
            while (c := self.prev.get_first_child()):
                self.prev.remove(c)
            h = Gtk.Label(label=p.get("title") or "Unbenannt", xalign=0, wrap=True)
            h.add_css_class("title-3")
            self.prev.append(h)
            t = Gtk.Label(label=p.get("text") or "(leer)", xalign=0, wrap=True, selectable=True)
            self.prev.append(t)
        bg(client.get, f"api/notes/{self.pid}/versions/{v['id']}", done=show, error=lambda e: self._msg(str(e)))

    def _restore(self):
        if self.sel:
            store.run("POST", f"api/notes/{self.pid}/versions/{self.sel}/restore", {}, done=lambda _: (self.restored(), self.close()))


class ImmichThumb(Gtk.Overlay):
    def __init__(self, aid, size=110):
        super().__init__()
        self.img = images.ImageView(f"/api/immich/thumb/{aid}" if aid else None, mode="cover", radius=6)
        self.img.set_size_request(size, size)
        self.set_child(self.img)


class ImmichPicker(Adw.Dialog):
    """Bilder aus Immich: Zuletzt, Alben, Suche nach Bildinhalt; der Server uebernimmt das Foto als eigene Datei"""

    def __init__(self, win, on_pick):
        super().__init__(title="Immich", content_width=620, content_height=600)
        self.win, self.on_pick = win, on_pick
        self.album = None
        self.next = None
        self.busy = False
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.back = flat_button(icon="go-previous-symbolic", label="Alben", cb=self._back)
        self.back.set_visible(False)
        hb.pack_start(self.back)
        tv.add_top_bar(hb)
        self.stack = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=8, margin_bottom=8, margin_start=10, margin_end=10)
        tv.set_content(self.stack)
        self.set_child(tv)
        self.info = Gtk.Label(xalign=0, wrap=True)
        self.info.add_css_class("dim-label")
        bg(client.get, "api/settings/immich", done=self._status, error=lambda e: self._message("Nicht erreichbar: " + str(e)))

    def _clear(self):
        while (c := self.stack.get_first_child()):
            self.stack.remove(c)

    def _message(self, t):
        self._clear()
        lb = Gtk.Label(label=t, wrap=True, vexpand=True)
        lb.add_css_class("dim-label")
        self.stack.append(lb)

    def _status(self, j):
        if j.get("available") is False:
            self._message("Immich ist auf diesem Server nicht eingerichtet.")
        elif not j.get("connected"):
            self._connect_form(j.get("suggest") or "")
        else:
            self._content()

    def _connect_form(self, suggest):
        self._clear()
        t = Gtk.Label(label="Adresse deines Immich und einen API-Schlüssel eintragen. In Immich unter Kontoeinstellungen → API-Schlüssel einen neuen "
                            "Schlüssel anlegen (Rechte: album.read, asset.read, asset.view, asset.download, user.read).", wrap=True, xalign=0)
        t.add_css_class("dim-label")
        self.stack.append(t)
        url = Gtk.Entry(text=suggest, placeholder_text="Immich-Adresse (https://…)")
        key = Gtk.PasswordEntry(placeholder_text="API-Schlüssel", show_peek_icon=True)
        self.stack.append(url)
        self.stack.append(key)
        b = Gtk.Button(label="Verbinden", halign=Gtk.Align.START)
        b.add_css_class("suggested-action")
        msg = Gtk.Label(xalign=0, wrap=True)
        msg.add_css_class("error")

        def go(*_):
            msg.set_label("Prüfe …")
            store.run("POST", "api/settings/immich", {"url": url.get_text().strip(), "apikey": key.get_text().strip()},
                      done=lambda _: bg(client.get, "api/settings/immich", done=self._status), error=lambda e: msg.set_label(str(e)))
        b.connect("clicked", go)
        self.stack.append(b)
        self.stack.append(msg)

    def _content(self):
        self._clear()
        if self.album is None:
            sw = Gtk.StackSwitcher()
            self.tabs = Gtk.Stack()
            self.tabs.add_titled(Gtk.Box(), "recent", "Zuletzt")
            self.tabs.add_titled(Gtk.Box(), "albums", "Alben")
            sw.set_stack(self.tabs)
            sw.set_halign(Gtk.Align.CENTER)
            self.tabs.connect("notify::visible-child-name", lambda *_: self._tab())
            self.stack.append(sw)
        self.search = Gtk.SearchEntry(placeholder_text="Suchen … (z. B. „Strand“, „Katze“)")
        self.search.connect("activate", lambda *_: self._load(True))
        self.stack.append(self.search)
        self.stack.append(self.info)
        sw2 = Gtk.ScrolledWindow(vexpand=True)
        self.grid = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, row_spacing=4, column_spacing=4, homogeneous=True, max_children_per_line=10)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        col.append(self.grid)
        self.more = Gtk.Button(label="Mehr laden", halign=Gtk.Align.CENTER, margin_top=8, visible=False)
        self.more.connect("clicked", lambda *_: self._load(False))
        col.append(self.more)
        sw2.set_child(col)
        self.stack.append(sw2)
        self._load(True)

    def _tab(self):
        if self.tabs.get_visible_child_name() == "albums":
            self.search.set_visible(False)
            self._albums()
        else:
            self.search.set_visible(True)
            self._load(True)

    def _back(self):
        self.album = None
        self.back.set_visible(False)
        self.set_title("Immich")
        self._content()
        self.tabs.set_visible_child_name("albums")

    def _albums(self):
        self.info.set_label("Lade …")
        self.more.set_visible(False)

        def fin(j):
            while (c := self.grid.get_first_child()):
                self.grid.remove(c)
            al = j.get("albums") or []
            self.info.set_label("" if al else "Keine Alben.")
            for a in al:
                ov = Gtk.Overlay()
                th = images.ImageView(f"/api/immich/thumb/{a['thumb']}" if a.get("thumb") else None, mode="cover", radius=8)
                th.set_size_request(170, 128)
                ov.set_child(th)
                cap = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, valign=Gtk.Align.END)
                cap.add_css_class("hk-album-cap")
                cap.append(Gtk.Label(label=a.get("name") or "", xalign=0, ellipsize=Pango.EllipsizeMode.END, css_classes=["heading"]))
                cap.append(Gtk.Label(label=f"{a.get('count') or 0} Fotos", xalign=0, css_classes=["caption"]))
                ov.add_overlay(cap)
                b = Gtk.Button(child=ov)
                b.add_css_class("flat")
                b.connect("clicked", lambda *_, a=a: self._open_album(a))
                self.grid.append(b)
        bg(client.get, "api/immich/albums", done=fin, error=lambda e: self.info.set_label("Alben nicht lesbar"))

    def _open_album(self, a):
        self.album = a
        self.set_title(a.get("name") or "Album")
        self.back.set_visible(True)
        self._content()

    def _load(self, reset):
        if reset:
            self.next = None
            while (c := self.grid.get_first_child()):
                self.grid.remove(c)
        b = {"page": 1 if reset else (self.next or 1)}
        if self.album:
            b["album"] = self.album["id"]
        q = self.search.get_text().strip()
        if q:
            b["q"] = q
        self.info.set_label("Lade …")

        def fin(j):
            items = j.get("items") or []
            for it in items:
                ov = ImmichThumb(it.get("id"))
                if it.get("mime") == "image/gif":
                    g = Gtk.Label(label="GIF", halign=Gtk.Align.END, valign=Gtk.Align.END, margin_end=4, margin_bottom=4)
                    g.add_css_class("hk-gif-badge")
                    ov.add_overlay(g)
                bt = Gtk.Button(child=ov)
                bt.add_css_class("flat")
                bt.connect("clicked", lambda *_, it=it: self._choose(it))
                self.grid.append(bt)
            self.next = j.get("next")
            self.more.set_visible(bool(self.next))
            self.info.set_label("" if self.grid.get_first_child() else "Keine Fotos gefunden.")
        bg(client.json, "POST", "api/immich/assets", b, done=fin, error=lambda e: self.info.set_label("Immich: Suche fehlgeschlagen"))

    def _choose(self, it):
        if self.busy:
            return
        self.busy = True
        self.info.set_label(f"Übernehme „{it.get('name') or ''}“ …")

        def fin(r):
            self.close()
            self.on_pick(r["url"])

        def err(e):
            self.busy = False
            self.info.set_label("Nicht übernommen: " + str(e))
        bg(client.json, "POST", "api/immich/import", {"id": it["id"]}, done=fin, error=err)


class ButtonsConfig(Adw.Dialog):
    """Schaltflaechen-Block einrichten (wie ⚙ im Web)"""

    def __init__(self, data, changed):
        super().__init__(title="Schaltflächen", content_width=460, content_height=560)
        self.data, self.changed = data, changed
        self.buttons = [dict(b) for b in data.get("buttons") or []]
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar(show_end_title_buttons=False, show_start_title_buttons=False)
        c = Gtk.Button(label="Abbrechen")
        c.connect("clicked", lambda *_: self.close())
        hb.pack_start(c)
        ok = Gtk.Button(label="Fertig")
        ok.add_css_class("suggested-action")
        ok.connect("clicked", lambda *_: self._done())
        hb.pack_end(ok)
        tv.add_top_bar(hb)
        self.page = Adw.PreferencesPage()
        tv.set_content(self.page)
        self.set_child(tv)
        self.groups = []
        self.fill()

    def fill(self):
        for g in self.groups:
            self.page.remove(g)
        self.groups = []
        dbs = store.databases()
        tpls = store.templates()
        for i, b in enumerate(self.buttons):
            g = Adw.PreferencesGroup(title=f"Schaltfläche {i + 1}")
            lab = Adw.EntryRow(title="Beschriftung", text=b.get("label") or "")
            lab.connect("changed", lambda e, b=b: b.__setitem__("label", e.get_text()))
            g.add(lab)
            ic = Adw.EntryRow(title="Symbol (Emoji)", text=b.get("icon") or "")
            ic.connect("changed", lambda e, b=b: b.__setitem__("icon", e.get_text()))
            g.add(ic)
            acts = [("", "keine"), ("db", "Neuer Eintrag in Datenbank"), ("task", "Neue Aufgabe"), ("page", "Seite öffnen")]
            g.add(self._combo("Aktion", acts, b.get("action") or "", lambda v, b=b: (b.__setitem__("action", v), self.fill())))
            if b.get("action") == "db":
                g.add(self._combo("Datenbank", [(0, "– wählen –")] + [(d["id"], label(d)) for d in dbs], b.get("target") or 0,
                                  lambda v, b=b: _seti(b, "target", v)))
                g.add(self._combo("Vorlage", [(0, "keine (leere Seite)")] + [(t["id"], label(t)) for t in tpls], b.get("tpl") or 0,
                                  lambda v, b=b: _seti(b, "tpl", v)))
                tt = Adw.EntryRow(title="Titel ({datum} = heute)", text=b.get("title") or "")
                tt.connect("changed", lambda e, b=b: b.__setitem__("title", e.get_text()))
                g.add(tt)
            if b.get("action") == "task":
                tt = Adw.EntryRow(title="Text der Aufgabe", text=b.get("title") or "")
                tt.connect("changed", lambda e, b=b: b.__setitem__("title", e.get_text()))
                g.add(tt)
            if b.get("action") == "page":
                g.add(self._combo("Seite", [(0, "– wählen –")] + [(p["id"], label(p)) for p in store.pages[:500]], b.get("target") or 0,
                                  lambda v, b=b: _seti(b, "target", v)))
            if b.get("action") in ("db", "page"):
                g.add(self._combo("Öffnen als", [("", "Seite"), ("side", "Seitenansicht"), ("center", "Mitte")], b.get("open") or "",
                                  lambda v, b=b: b.__setitem__("open", v)))
            rm = Gtk.Button(label="Schaltfläche entfernen", margin_top=6, halign=Gtk.Align.START)
            rm.add_css_class("destructive-action")
            rm.connect("clicked", lambda *_, i=i: (self.buttons.pop(i), self.fill()))
            g.add(rm)
            self.page.add(g)
            self.groups.append(g)
        g = Adw.PreferencesGroup()
        add = Gtk.Button(label="Schaltfläche hinzufügen", halign=Gtk.Align.START)
        add.connect("clicked", lambda *_: (self.buttons.append({"label": "Neu", "icon": "➕", "action": ""}), self.fill()))
        g.add(add)
        self.page.add(g)
        self.groups.append(g)

    @staticmethod
    def _combo(title, pairs, cur, on):
        row = Adw.ComboRow(title=title, model=Gtk.StringList.new([t for _, t in pairs]))
        vals = [v for v, _ in pairs]
        if cur in vals:
            row.set_selected(vals.index(cur))
        row.connect("notify::selected", lambda r, _: on(vals[r.get_selected()]))
        return row

    def _done(self):
        self.data["buttons"] = self.buttons
        self.changed()
        self.close()


def _seti(b, k, v):
    if v:
        b[k] = v
    else:
        b.pop(k, None)


def service_icon(icon, name, size=30):
    """Symbol eines Dienstes ueber den eigenen Server (selfh.st wie im Web), sonst Anfangsbuchstabe"""
    holder = Gtk.Box(valign=Gtk.Align.CENTER, halign=Gtk.Align.START, hexpand=False)
    holder.set_size_request(size, size)
    letter = Gtk.Label(label=(name[:1] or "?").upper(), hexpand=True, vexpand=True)
    letter.add_css_class("hk-svc-letter")
    holder.append(letter)
    if icon:
        def got(dec):
            if dec is None:
                return
            v = images.ImageView(mode="fit", natural=size, max_height=size)
            v.set_decoded(dec)
            v.set_size_request(size, size)
            v.set_halign(Gtk.Align.CENTER)
            v.set_valign(Gtk.Align.CENTER)
            v.set_hexpand(False)
            while (c := holder.get_first_child()):
                holder.remove(c)
            holder.append(v)
        images.load(f"/api/services/icon/{icon}", got, max_px=128)
    return holder


def services_screen(win):
    sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True)
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=18, margin_bottom=24, margin_start=24, margin_end=24)
    h = Gtk.Label(label="Dienste", xalign=0)
    h.add_css_class("title-1")
    box.append(h)
    se = Gtk.SearchEntry(placeholder_text="Dienst suchen", halign=Gtk.Align.START, width_chars=30)
    box.append(se)
    holder = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14)
    box.append(holder)
    sw.set_child(box)
    data = {"list": []}

    def fill(*_):
        while (c := holder.get_first_child()):
            holder.remove(c)
        q = norm(se.get_text())
        shown = [s for s in data["list"] if not q or q in norm(f"{s.get('name')} {s.get('host')} {s.get('category')}")]
        for cat in sorted({s.get("category") or "" for s in shown}):
            hl = Gtk.Label(label=cat, xalign=0)
            hl.add_css_class("heading")
            holder.append(hl)
            fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, row_spacing=10, column_spacing=10, homogeneous=True, min_children_per_line=1, max_children_per_line=5)
            for s in [x for x in shown if (x.get("category") or "") == cat]:
                r = Gtk.Box(spacing=10)
                r.append(service_icon(s.get("icon"), s.get("name") or ""))
                col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
                col.append(Gtk.Label(label=s.get("name") or "", xalign=0, css_classes=["heading"]))
                col.append(Gtk.Label(label=s.get("host") or "", xalign=0, ellipsize=Pango.EllipsizeMode.END, css_classes=["caption", "dim-label"]))
                r.append(col)
                b = Gtk.Button(child=r)
                b.add_css_class("hk-card-box")
                b.add_css_class("flat")
                b.set_size_request(200, -1)
                b.connect("clicked", lambda *_, u=s.get("url"): win.open_uri(u))
                fb.append(b)
            holder.append(fb)
    se.connect("search-changed", fill)

    def got(lst):
        data["list"] = lst or []
        fill()
    bg(client.get, "api/services", done=got, error=lambda e: holder.append(Gtk.Label(label=str(e), css_classes=["error"])))
    return sw
