"""Seite laden/speichern (PageDoc) und Block-Operationen (PageEditor) - wie NativePage.swift / Editor.swift."""
import copy
import json
import time

from gi.repository import GLib

from .api import APIError, client
from .store import is_db, store
from .i18n import _
from .util import bg, esc, md_to_html, new_block_id

# "/"-Menue: (Name, Typ, Ebene, Symbol, Suchwoerter - deutsch, englisch, spanisch; der Name wird zusaetzlich durchsucht)
SLASH_ITEMS = [
    (_("Text"), "paragraph", 0, "format-justify-left-symbolic", "text absatz paragraph texto parrafo"),
    (_("Heading 1"), "header", 1, "format-text-bold-symbolic", "h1 ueberschrift heading titel titulo encabezado"),
    (_("Heading 2"), "header", 2, "format-text-bold-symbolic", "h2 ueberschrift heading encabezado"),
    (_("Heading 3"), "header", 3, "format-text-bold-symbolic", "h3 ueberschrift heading encabezado"),
    (_("Heading 4"), "header", 4, "format-text-bold-symbolic", "h4 ueberschrift heading encabezado"),
    (_("Checklist"), "checklist", 0, "checkbox-checked-symbolic", "todo checkliste aufgabe check checklist tarea verificacion"),
    (_("Bulleted list"), "unordered", 0, "view-list-bullet-symbolic", "liste aufzaehlung bullet punkt list lista vinetas"),
    (_("Numbered list"), "ordered", 0, "view-list-ordered-symbolic", "nummeriert liste zahl ordered numbered lista numerada"),
    (_("Quote"), "quote", 0, "format-indent-more-symbolic", "zitat quote cita"),
    (_("Callout"), "callout", 0, "dialog-information-symbolic", "hinweis callout info nota aviso"),
    (_("Toggle"), "toggle", 0, "pan-end-symbolic", "toggle aufklappen desplegable"),
    (_("Code"), "code", 0, "utilities-terminal-symbolic", "code codigo"),
    (_("Divider"), "delimiter", 0, "list-remove-symbolic", "trenner linie divider separador linea"),
    (_("Image"), "image", 0, "image-x-generic-symbolic", "bild foto image screenshot imagen"),
    (_("Image from Immich"), "immich_image", 0, "camera-photo-symbolic", "immich bild foto album image imagen"),
    (_("Page"), "page", 0, "document-new-symbolic", "seite unterseite page subpage pagina subpagina"),
    (_("Database"), "db_new", 0, "x-office-spreadsheet-symbolic", "datenbank database tabelle board galerie kalender inline base datos tabla"),
    (_("Linked database"), "db_link", 0, "insert-link-symbolic", "verknuepfte datenbank linked view ansicht vinculada vista"),
    (_("Tasks"), "tasks", 0, "object-select-symbolic", "aufgaben tasks todo tareas"),
    (_("Calendar"), "calendar", 0, "x-office-calendar-symbolic", "kalender termine calendar events calendario eventos"),
    (_("Table"), "table", 0, "view-grid-symbolic", "tabelle table raster zellen tabla celdas"),
    (_("Columns"), "columns", 0, "view-dual-symbolic", "spalten columns nebeneinander columnas"),
    (_("Button"), "buttons", 0, "input-mouse-symbolic", "schaltflaeche button knopf aktion boton accion"),
    (_("Embed"), "embed", 0, "video-x-generic-symbolic", "einbetten embed video youtube link karte insertar enlace"),
    (_("File"), "file", 0, "mail-attachment-symbolic", "datei anhang file pdf upload attachment archivo adjunto"),
]
CONVERTIBLE = [it for it in SLASH_ITEMS if it[1] not in ("image", "immich_image", "page", "db_new", "db_link", "tasks", "calendar",
                                                          "table", "columns", "buttons", "embed", "file")]


def make(btype, text="", level=2):
    if btype == "header":
        data = {"text": text, "level": level}
    elif btype in ("checklist", "unordered", "ordered"):
        data = {"style": btype, "meta": {}, "items": [{"content": text, "meta": {"checked": False} if btype == "checklist" else {}, "items": []}]}
    elif btype == "quote":
        data = {"text": text, "caption": "", "alignment": "left"}
    elif btype == "callout":
        data = {"emoji": "💡", "text": text}
    elif btype == "toggle":
        data = {"title": text, "open": True, "blocks": [make("paragraph")]}
    elif btype == "code":
        data = {"code": text}
    elif btype == "delimiter":
        data = {}
    else:
        data = {"text": text}
    return {"id": new_block_id(), "type": "list" if btype in ("checklist", "unordered", "ordered") else btype, "data": data}


def ensure_ids(arr):
    for b in arr:
        if not isinstance(b, dict):
            continue
        if not b.get("id"):
            b["id"] = new_block_id()
        d = b.get("data")
        if not isinstance(d, dict):
            d = b["data"] = {}
        if isinstance(d.get("blocks"), list):
            ensure_ids(d["blocks"])
        for c in d.get("cols") or []:
            if isinstance(c, dict) and isinstance(c.get("blocks"), list):
                ensure_ids(c["blocks"])


class PageDoc:
    """Eine Seite: Kopf (Titel, Icon, ...) + Bloecke. Speichern mit Revision (409 = woanders geaendert)."""

    def __init__(self, pid):
        self.id = pid
        self.page = {}
        self.root = []
        self.doc = {"blocks": self.root}
        self.rev = 0
        self.loaded = False
        self.fetched = False           # echte Daten vom Server/Zwischenspeicher - erst dann darf gespeichert werden
        self.dirty = False
        self.error = None
        self.parent_schema = None
        self.on_loaded = None          # (doc) -> None
        self.on_error = None           # (text) -> None
        self.on_saved = None
        self._saving = False
        self._again = False
        self.gen = 0                   # zaehlt Aenderungen: was waehrend des Speicherns dazukommt, geht nicht verloren

    def mark_dirty(self):
        self.dirty = True
        self.gen += 1

    @property
    def title_raw(self):
        return self.page.get("title") or ""

    @property
    def title(self):
        return self.title_raw or _("Untitled")

    @property
    def is_database(self):
        return self.page.get("kind") == "database"

    @property
    def props(self):
        return self.page.get("props") or {}

    def load(self, force=False):
        if not self.loaded:
            c = store.cached(f"api/notes/{self.id}")
            if isinstance(c, dict):
                self._apply(c, None)
                if self.on_loaded:
                    self.on_loaded(self)

        def work():
            p = store.get_cached(f"api/notes/{self.id}")
            schema = None
            par = p.get("parent_id")
            if par is not None and is_db(store.by_id.get(par)):
                try:
                    schema = store.get_cached(f"api/notes/{par}").get("schema")
                except APIError:
                    c = store.cached(f"api/notes/{par}")
                    schema = c.get("schema") if isinstance(c, dict) else None
            return p, schema

        def fin(r):
            if self.dirty and self.loaded and not force:
                return                                       # eigene Eingaben schuetzen
            self._apply(r[0], r[1])
            if self.on_loaded:
                self.on_loaded(self)

        def err(e):
            if not self.fetched:
                self.error = str(e)
                self.loaded = True
                if self.on_loaded:
                    self.on_loaded(self)
            elif self.on_error:
                self.on_error(str(e))
        bg(work, done=fin, error=err)

    def _apply(self, p, schema):
        self.page = p
        self.rev = p.get("rev") or 0
        body = p.get("body") or ""
        root = None
        if p.get("format") == "blocks":
            try:
                m = json.loads(body) if body else {"blocks": []}
                if isinstance(m, dict) and isinstance(m.get("blocks"), list):
                    self.doc, root = m, m["blocks"]
            except ValueError:
                root = None
        if root is None:
            root = [make("paragraph", md_to_html(line)) for line in body.split("\n") if line.strip()]
            self.doc = {"blocks": root}
        ensure_ids(root)
        if not root and not self.is_database:
            root.append(make("paragraph"))
        self.root = root
        if schema is not None:
            self.parent_schema = schema
        elif not is_db(store.by_id.get(p.get("parent_id"))):
            self.parent_schema = None
        self.error = None
        self.loaded = True
        self.fetched = True

    def save(self, done=None):
        # Nie eine Seite speichern, die nicht geladen werden konnte: sonst wuerde die leere Ansicht
        # ("Unbenannt", ohne Bloecke) den echten Inhalt auf dem Server ueberschreiben.
        if not self.loaded or not self.fetched:
            return
        if self._saving:
            self._again = True
            return
        self._saving = True
        self.doc["blocks"] = self.root
        self.doc["time"] = int(time.time() * 1000)
        b = {"title": self.title_raw, "rev": self.rev}
        if not self.is_database:
            b["body"] = json.dumps(self.doc, ensure_ascii=False)
            b["format"] = "blocks"
        title = self.title_raw
        sent = self.gen

        def fin(r):
            self._saving = False
            if isinstance(r, dict) and r.get("rev") is not None:
                self.rev = r["rev"]
            self.dirty = self.gen != sent             # inzwischen weiter getippt -> gleich noch einmal
            if self.dirty:
                self._again = True
            self.error = None
            store.update_title(self.id, title)
            store.drop_cache(f"api/notes/{self.id}")
            if self.parent_schema is not None:
                store.emit("data-changed")
            if self.on_saved:
                self.on_saved()
            if self._again:
                self._again = False
                self.save()
            if done:
                done()

        def err(e):
            self._saving = False
            if isinstance(e, APIError) and e.code == 409:
                if self.on_error:
                    self.on_error(_("The page was changed elsewhere in the meantime – reloaded."))
                self.dirty = False
                self.load(force=True)
            elif self.on_error:
                self.on_error(_("Not saved: {error}").format(error=e))
            if done:
                done()
        bg(client.json, "PATCH", f"api/notes/{self.id}", b, done=fin, error=err)


class PageEditor:
    """Block-Operationen. Die Ansicht (PageView) zeichnet nach changed() neu und setzt den Fokus."""

    def __init__(self, doc, view):
        self.doc = doc
        self.view = view
        self.locked = False
        self.focus = None              # "blockID" oder "blockID/0/2" (Listenpunkt)
        self.focus_where = "end"       # "start" | "end" | Zeichen-Offset
        self._save_src = 0

    # --- Speichern ------------------------------------------------------------------------------
    def changed(self):
        """Struktur geaendert: neu zeichnen + speichern"""
        self.view.rebuild()
        self.text_changed()

    def text_changed(self):
        self.doc.mark_dirty()
        if self._save_src:
            GLib.source_remove(self._save_src)
        self._save_src = GLib.timeout_add(700, self._save_now)

    def _save_now(self):
        self._save_src = 0
        self.doc.save()
        return False

    def flush(self, done=None):
        if self._save_src:
            GLib.source_remove(self._save_src)
            self._save_src = 0
        if self.doc.dirty:
            self.doc.save(done)
        elif done:
            done()

    def set_focus(self, key, where="end"):
        self.focus, self.focus_where = key, where

    @property
    def focused_block(self):
        return self.focus.split("/")[0] if self.focus else None

    # --- Suchen ---------------------------------------------------------------------------------
    def locate(self, bid, arr=None):
        a = self.doc.root if arr is None else arr
        for i, b in enumerate(a):
            if not isinstance(b, dict):
                continue
            if b.get("id") == bid:
                return a, i
            d = b.get("data") or {}
            if isinstance(d.get("blocks"), list):
                r = self.locate(bid, d["blocks"])
                if r:
                    return r
            for c in d.get("cols") or []:
                if isinstance(c, dict) and isinstance(c.get("blocks"), list):
                    r = self.locate(bid, c["blocks"])
                    if r:
                        return r
        return None

    def block(self, bid):
        r = self.locate(bid)
        return r[0][r[1]] if r else None

    def flat_blocks(self):
        out = []

        def walk(a):
            for b in a:
                out.append(b)
                d = b.get("data") or {}
                if isinstance(d.get("blocks"), list):
                    walk(d["blocks"])
                for c in d.get("cols") or []:
                    walk(c.get("blocks") or [])
        walk(self.doc.root)
        return out

    # --- Einfuegen / Loeschen / Verschieben ------------------------------------------------------------
    def insert(self, block, after=None, arr=None):
        if after and self.locate(after):
            a, i = self.locate(after)
            a.insert(i + 1, block)
        else:
            (self.doc.root if arr is None else arr).append(block)
        if block["type"] == "list":
            self.set_focus(block["id"] + "/0", "start")
        elif block["type"] != "delimiter":
            self.set_focus(block["id"], "start")
        self.changed()
        return block["id"]

    def delete(self, bid):
        r = self.locate(bid)
        if not r:
            return
        a, i = r
        prev = a[i - 1].get("id") if i > 0 else None
        a.pop(i)
        if a is self.doc.root and not a:
            a.append(make("paragraph"))
        self.set_focus(prev, "end")
        self.changed()

    def move(self, bid, d):
        r = self.locate(bid)
        if not r:
            return
        a, i = r
        if 0 <= i + d < len(a):
            a.insert(i + d, a.pop(i))
            self.changed()

    def move_to(self, bid, target, before):
        """Ziehen & Ablegen: Block vor/hinter einen anderen Block"""
        if bid == target:
            return
        src = self.locate(bid)
        if not src:
            return
        b = src[0][src[1]]
        if target in self._ids_inside(b):
            return                                            # nicht in sich selbst
        src[0].pop(src[1])
        r = self.locate(target)
        if not r:
            src[0].insert(src[1], b)
            return
        a, i = r
        a.insert(i if before else i + 1, b)
        self.changed()

    def _ids_inside(self, b):
        out = set()
        d = b.get("data") or {}
        for x in d.get("blocks") or []:
            out.add(x.get("id"))
            out |= self._ids_inside(x)
        for c in d.get("cols") or []:
            for x in c.get("blocks") or []:
                out.add(x.get("id"))
                out |= self._ids_inside(x)
        return out

    def duplicate(self, bid):
        r = self.locate(bid)
        if not r:
            return
        a, i = r
        cp = copy.deepcopy(a[i])

        def renew(b):
            b["id"] = new_block_id()
            d = b.get("data") or {}
            for x in d.get("blocks") or []:
                renew(x)
            for c in d.get("cols") or []:
                for x in c.get("blocks") or []:
                    renew(x)
        renew(cp)
        a.insert(i + 1, cp)
        self.changed()

    def convert(self, bid, btype, level=2):
        r = self.locate(bid)
        if not r:
            return
        a, i = r
        b = a[i]
        d = b.get("data") or {}
        if b.get("type") == "list":
            texts = [it.get("content", "") for it in d.get("items") or [] if isinstance(it, dict)]
        else:
            texts = [d.get("text") if d.get("text") is not None else d.get("title", "") or ""]
        texts = texts or [""]
        if btype in ("checklist", "unordered", "ordered"):
            nb = make(btype)
            nb["data"]["items"] = [{"content": t, "meta": {"checked": False} if btype == "checklist" else {}, "items": []} for t in texts]
            nb["id"] = bid
            a[i] = nb
            self.set_focus(bid + "/0", "end")
        else:
            nb = make(btype, texts[0], level)
            nb["id"] = bid
            a[i] = nb
            for k, t in enumerate(texts[1:]):
                a.insert(i + 1 + k, make(btype, t, level))
            self.set_focus(bid, "end")
        self.changed()

    def continue_writing(self, arr=None):
        """Klick unter den letzten Block: leeren Absatz am Ende fokussieren oder anlegen"""
        a = self.doc.root if arr is None else arr
        if a and a[-1].get("type") == "paragraph" and not (a[-1].get("data") or {}).get("text"):
            self.set_focus(a[-1]["id"], "end")
            self.view.apply_focus()
            return
        p = make("paragraph")
        a.append(p)
        self.set_focus(p["id"], "start")
        self.changed()

    # --- Spalten ---------------------------------------------------------------------------------
    def column_of(self, bid, arr=None):
        a = self.doc.root if arr is None else arr
        for i, b in enumerate(a):
            d = b.get("data") or {}
            if b.get("type") == "columns":
                for ci, c in enumerate(d.get("cols") or []):
                    inner = c.get("blocks") or []
                    if any(x.get("id") == bid for x in inner):
                        return b, a, i, ci
                    r = self.column_of(bid, inner)
                    if r:
                        return r
            if isinstance(d.get("blocks"), list):
                r = self.column_of(bid, d["blocks"])
                if r:
                    return r
        return None

    @staticmethod
    def col_count(cols_block):
        return len((cols_block.get("data") or {}).get("cols") or [])

    @staticmethod
    def col_array(cols_block, ci):
        cols = (cols_block.get("data") or {}).get("cols") or []
        if 0 <= ci < len(cols):
            return cols[ci].setdefault("blocks", [])
        return None

    def columns_above(self, bid):
        r = self.locate(bid)
        if r and r[1] > 0 and r[0][r[1] - 1].get("type") == "columns":
            return r[0][r[1] - 1]
        return None

    def columns_below(self, bid):
        r = self.locate(bid)
        if r and r[1] + 1 < len(r[0]) and r[0][r[1] + 1].get("type") == "columns":
            return r[0][r[1] + 1]
        return None

    def is_columns(self, bid):
        b = self.block(bid)
        return bool(b and b.get("type") == "columns")

    def move_into_column(self, bid, cols, ci):
        if self.is_columns(bid):
            return
        r = self.locate(bid)
        tgt = self.col_array(cols, ci)
        if not r or tgt is None:
            return
        b = r[0].pop(r[1])
        tgt.append(b)
        self.changed()

    def move_out_of_columns(self, bid):
        c = self.column_of(bid)
        r = self.locate(bid)
        if not c or not r:
            return
        b = r[0].pop(r[1])
        parent = c[1]
        idx = next((k for k, x in enumerate(parent) if x is c[0]), len(parent) - 1)
        parent.insert(idx + 1, b)
        self.changed()

    def merge_columns(self, bid):
        """Spalten-Block mit dem Spalten-Block darunter zusammenfuehren"""
        r = self.locate(bid)
        below = self.columns_below(bid)
        if not r or below is None:
            return
        a, i = r
        top = a[i]
        n = max(self.col_count(top), self.col_count(below))
        merged = []
        for ci in range(min(4, n)):
            blocks = list(self.col_array(top, ci) or []) + list(self.col_array(below, ci) or [])
            merged.append({"blocks": blocks})
        top.setdefault("data", {})["cols"] = merged
        a.pop(i + 1)
        self.changed()

    def merge_into_above(self, bid):
        above = self.columns_above(bid)
        if above is not None:
            self.merge_columns(above["id"])

    def continue_in_column(self, blocks):
        if blocks and blocks[-1].get("type") == "paragraph" and not (blocks[-1].get("data") or {}).get("text"):
            self.set_focus(blocks[-1]["id"], "end")
            self.view.apply_focus()
            return
        p = make("paragraph")
        blocks.append(p)
        self.set_focus(p["id"], "start")
        self.changed()

    # --- Listen ----------------------------------------------------------------------------------
    @staticmethod
    def item_at(block, path):
        items = (block.get("data") or {}).get("items") or []
        it = None
        for k in path:
            if k >= len(items):
                return None, None, None
            it = items[k]
            parent_items = items
            items = it.setdefault("items", [])
        return it, parent_items, path[-1]

    # --- "/"-Auswahl -------------------------------------------------------------------------------
    def slash_apply(self, bid, btype, level):
        r = self.locate(bid)
        if not r:
            return
        a, i = r
        b = a[i]
        (b.get("data") or {})["text"] = ""
        v = self.view
        if btype == "paragraph":
            self.set_focus(bid, "start")
            self.changed()
            return
        if btype == "image":
            self.set_focus(bid, "start")
            self.changed()
            v.pick_image(after=bid)
            return
        if btype == "immich_image":
            self.changed()
            v.pick_immich(after=bid)
            return
        if btype == "table":
            a[i] = {"id": bid, "type": "table", "data": {"withHeadings": True, "content": [["", ""], ["", ""]]}}
            self.set_focus(None)
            self.changed()
            return
        if btype == "columns":
            a[i] = {"id": bid, "type": "columns", "data": {"cols": [{"blocks": [make("paragraph")]}, {"blocks": [make("paragraph")]}]}}
            self.set_focus(a[i]["data"]["cols"][0]["blocks"][0]["id"], "start")
            self.changed()
            return
        if btype == "buttons":
            a[i] = {"id": bid, "type": "buttons", "data": {"buttons": [{"label": _("New"), "icon": "➕", "action": ""}]}}
            self.set_focus(None)
            self.changed()
            v.config_buttons(bid)
            return
        if btype == "embed":
            self.changed()
            v.ask_embed(bid)
            return
        if btype == "file":
            self.changed()
            v.pick_file(bid)
            return
        if btype in ("tasks", "calendar"):
            a[i] = {"id": bid, "type": btype, "data": {}}
            self.set_focus(None)
            self.changed()
            return
        if btype == "db_link":
            self.changed()
            v.link_database(bid)
            return
        if btype in ("db_new", "page"):
            self.set_focus(None)
            body = ({"kind": "database", "title": _("New database"), "icon": "🗃️", "parent_id": self.doc.id} if btype == "db_new"
                    else {"title": "", "parent_id": self.doc.id})

            def fin(res):
                rr = self.locate(bid)
                if not rr:
                    return
                aa, j = rr
                if btype == "db_new":
                    aa[j] = {"id": bid, "type": "database", "data": {"db": res["id"]}}
                else:
                    aa[j]["data"] = {"text": f'📄 <a href="/notes#{res["id"]}">{esc(_("Untitled"))}</a>'}
                self.changed()
                self.flush(lambda: store.refresh(done=lambda: btype == "page" and v.open_page(res["id"])))
            store.run("POST", "api/notes", body, done=fin)
            return
        if btype == "delimiter":
            a[i] = make("delimiter")
            p = make("paragraph")
            a.insert(i + 1, p)
            self.set_focus(p["id"], "start")
            self.changed()
            return
        self.convert(bid, btype, level or 2)

    def link_database(self, db, bid):
        r = self.locate(bid)
        if r:
            r[0][r[1]] = {"id": bid, "type": "database", "data": {"db": db}}
            self.changed()

    def insert_image_url(self, url, after=None):
        b = {"id": new_block_id(), "type": "image",
             "data": {"file": {"url": url}, "caption": "", "withBorder": False, "stretched": False, "withBackground": False}}
        r = self.locate(after) if after else None
        if r and r[0][r[1]].get("type") == "paragraph" and not (r[0][r[1]].get("data") or {}).get("text"):
            r[0][r[1]] = b                                    # leerer Absatz (vom "/") wird zum Bild
            self.set_focus(None)
            self.changed()
        else:
            self.insert(b, after)
            self.set_focus(None)
