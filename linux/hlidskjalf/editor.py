"""Seite laden/speichern (PageDoc) und Block-Operationen (PageEditor) - wie NativePage.swift / Editor.swift."""
import copy
import json
import time

from gi.repository import GLib

from . import config, drafts
from .api import SWITCHED, APIError, client
from .sharing import RANK, doc_text, role_of
from .store import is_db, store
from .i18n import _
from .util import bg, bg_serial, esc, md_to_html, new_block_id

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
    (_("Nextcloud file"), "nextcloud", 0, "folder-remote-symbolic", "nextcloud cloud datei ordner file folder archivo nube"),
]
CONVERTIBLE = [it for it in SLASH_ITEMS if it[1] not in ("image", "immich_image", "page", "db_new", "db_link", "tasks", "calendar",
                                                          "table", "columns", "buttons", "embed", "file", "nextcloud")]


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


def _draft_path(pid, server=None, account=drafts.CURRENT):
    """Entwurf je Server, Konto und Seite (siehe drafts.py)"""
    return drafts.path(pid, server, account)


def read_draft(pid, server=None, account=drafts.CURRENT):
    return drafts.read(pid, server, account)


def drop_draft(pid, server=None, account=drafts.CURRENT):
    drafts.drop(pid, server, account)


def _blocks_key(blocks):
    try:
        return json.dumps(blocks, sort_keys=True, ensure_ascii=False)
    except (TypeError, ValueError):
        return None


class PageDoc:
    """Eine Seite: Kopf (Titel, Icon, ...) + Bloecke. Speichern mit Revision; nichts geht verloren:
    - 409 (woanders geaendert): Server-Fassung holen, eigene behalten, on_conflict fragt (sonst Konfliktkopie)
    - jeder Stand wird vor dem Senden als Entwurf auf der Platte abgelegt und erst nach Erfolg geloescht
      (Absturz, offline, Schliessen) - beim naechsten Oeffnen wird er wiederhergestellt
    - save(done)/flush(done): alle Rueckrufe laufen, wenn der letzte Stand gespeichert ist"""

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
        self.last_error = None         # letzter Fehler beim Speichern (offline ...)
        self.parent_schema = None
        self.conflict = None           # Server-Fassung, solange ein Konflikt offen ist
        self.on_loaded = None          # (doc) -> None
        self.on_error = None           # (text) -> None
        self.on_saved = None
        self.on_conflict = None        # (doc, server_page) -> None; ohne: automatische Konfliktkopie
        self.on_notice = None          # (text) -> None: Hinweis (Entwurf wiederhergestellt, Konfliktkopie angelegt)
        self.on_title = None           # (title) -> None: Titel von aussen geaendert (Tabelle)
        self._saving = False
        self._waiters = []
        self._draft_checked = False
        self._resolving = False
        self._retry_src = 0
        self.gen = 0                   # zaehlt Aenderungen: was waehrend des Speicherns dazukommt, geht nicht verloren
        self.server = config.settings.server       # die Seite gehoert zu diesem Server - nie an einen anderen speichern
        self.account = config.account(self.server)  # ... und zu diesem Konto (None: noch unbekannt, wird zugeordnet)
        self.on_copies = None          # () -> None: lokale Kopien geaendert (Banner)
        self._offline = False
        self.too_large = False         # 413 beim Speichern: Seite ueber der Servergrenze (bis ein Speichern klappt)
        # Teilen (1.3): fremde Seiten
        self.error_code = None         # HTTP-Code, wenn das Laden fehlschlug (404: kein Zugriff)
        self.conflict_by = None        # Name der Person, die zuletzt gespeichert hat (409 'by' bzw. updated_by)
        self.lost = None               # 403/404 beim Speichern einer fremden Seite: danach nicht mehr speichern
        self.on_lost = None            # (doc, code, letzter Text) -> None
        self.nc_known = set()          # Nextcloud-Bloecke (author|inst|fileid) im zuletzt geladenen/gespeicherten Stand (2.3)
        self.nc_error = None           # zuletzt gemeldete Ablehnung eines neuen Nextcloud-Blocks
        self._overwrite = False        # "Meine Version behalten": einmal mit overwrite speichern (andere Fassung -> Versionen)
        store.docs.add(self)

    def mark_dirty(self):
        self.dirty = True
        self.gen += 1

    # --- Server + Konto ----------------------------------------------------------------------
    def _acct(self):
        """Konto, zu dem diese Seite gehoert. Unbekannt: das jetzt angemeldete Konto (sobald bekannt) uebernehmen."""
        if self.account is None and config.origin(config.settings.server) == config.origin(self.server):
            self.account = config.account(self.server)
        return self.account

    def same_session(self):
        """Darf jetzt gesendet werden? Nur an den eigenen Server und mit dem eigenen Konto - nach einem Server- oder
        Kontowechsel (auch waehrend das neue Konto noch nicht bekannt ist) bleibt alles als Entwurf liegen."""
        if config.origin(config.settings.server) != config.origin(self.server):
            return False
        mine = self._acct()
        return mine is None or config.account(self.server) == mine

    def account_known(self):
        """Store: Konto am Server jetzt bekannt -> zuordnen, ungespeicherte Eingaben senden (gleiches Konto)"""
        self._acct()
        if self.dirty and not self._saving and self.conflict is None and self.same_session():
            self.save()

    @property
    def saving(self):
        return self._saving

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

    @property
    def role(self):
        """'owner' | 'edit' | 'comment' | 'view' (eigene Seite bzw. aeltere Server: 'owner')"""
        return role_of(self.page)

    @property
    def foreign(self):
        return self.fetched and self.role != "owner"

    @property
    def read_only(self):
        """Lesen/Kommentieren oder Zugriff verloren: nichts speichern"""
        return self.fetched and (RANK[self.role] < RANK["edit"] or self.lost is not None)

    def text_now(self):
        """eigener Text (Titel + Bloecke) als Klartext - zum Kopieren"""
        return doc_text(self.title_raw, None if self.is_database else self.root)

    def set_title(self, title):
        self.page["title"] = title
        self.mark_dirty()
        if self.on_title:
            self.on_title(title)

    # --- Laden ---------------------------------------------------------------------------------
    def load(self, force=False):
        if not self.loaded:
            c = store.cached(f"api/notes/{self.id}")
            if isinstance(c, dict):
                self._apply(c, None)
                self._restore_draft(c)
                if self.on_loaded:
                    self.on_loaded(self)

        def work():
            p = store.get_cached(f"api/notes/{self.id}")
            schema = None
            par = p.get("parent_id")
            if par is not None and is_db(store.meta(par)):
                try:
                    schema = store.get_cached(f"api/notes/{par}").get("schema")
                except APIError:
                    c = store.cached(f"api/notes/{par}")
                    schema = c.get("schema") if isinstance(c, dict) else None
            return p, schema

        def fin(r):
            p, schema = r
            if self._saving:
                return                                       # das laufende Speichern entscheidet (409 -> Konflikt)
            if (self.dirty or self.conflict is not None) and self.fetched:
                known = self.conflict.get("rev") if self.conflict is not None else self.rev
                if (p.get("rev") or 0) != known:
                    self._conflict(p)                        # eigene Eingaben + inzwischen anderer Stand: sofort melden
                if schema is not None:
                    self.parent_schema = schema
                return                                       # eigene Eingaben schuetzen
            self._apply(p, schema)
            self._restore_draft(p)
            if self.on_loaded:
                self.on_loaded(self)

        def err(e):
            code = e.code if isinstance(e, APIError) else None
            if code == 404 and self.fetched and self.foreign:
                # Freigabe entzogen (oder Teilen abgeschaltet): eigene Eingaben zum Kopieren anbieten, dann "Kein Zugriff"
                if self.dirty or self.conflict is not None:
                    self._lose(404)
                    return
                self.fetched = False
                store.drop_cache(f"api/notes/{self.id}")
            if not self.fetched:
                self.error = str(e)
                self.error_code = code
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
        elif not is_db(store.meta(p.get("parent_id"))):
            self.parent_schema = None
        self.error = None
        self.error_code = None
        self.lost = None                                     # frischer Stand: die Rolle darin entscheidet
        self.loaded = True
        self.fetched = True
        from .nextcloud import keys_of
        self.nc_known = keys_of(root)                        # Nextcloud-Bloecke des Server-Stands (fuer abgelehnte Kopien)

    # --- Entwuerfe -------------------------------------------------------------------------------
    def _snapshot(self, deep=False):
        d = {"id": self.id, "rev": self.rev, "title": self.title_raw, "time": time.time()}
        if not self.is_database:
            self.doc["blocks"] = self.root
            # deep: unabhaengige Kopie (spaetere Eingaben aendern sie nicht)
            d["doc"] = json.loads(json.dumps(self.doc, ensure_ascii=False)) if deep else self.doc
        return d

    def _write_draft(self):
        if not self.fetched:
            return
        try:
            drafts.write(self._snapshot(), self.server, self._acct())
        except (TypeError, ValueError):
            pass

    def _same_content(self, d):
        """Entwurf/Kopie d entspricht dem angezeigten Stand (Titel + Bloecke)?"""
        doc = d.get("doc") if isinstance(d.get("doc"), dict) and isinstance(d["doc"].get("blocks"), list) else None
        same_title = (d.get("title") or "") == self.title_raw
        return same_title and (doc is None or self.is_database or _blocks_key(doc.get("blocks")) == _blocks_key(self.root))

    def _restore_draft(self, p):
        """Ungespeicherter Stand vom letzten Mal (Absturz, offline, Schliessen) -> uebernehmen und speichern"""
        if self._draft_checked or not self.fetched:
            return
        self._draft_checked = True
        d = drafts.read(self.id, self.server, self._acct())
        if not d:
            return
        if self._same_content(d):
            drafts.drop(self.id, self.server, self._acct())               # nichts Neues (war schon gespeichert)
            return
        if self.read_only:                       # inzwischen nur noch lesen: Text zum Kopieren anbieten, nicht speichern
            from .sharing import snapshot_text
            drafts.drop(self.id, self.server, self._acct())
            text = snapshot_text(d)
            if self.on_lost:
                GLib.idle_add(lambda: (self.on_lost(self, 403, text), False)[1])
            return
        doc = d.get("doc") if isinstance(d.get("doc"), dict) and isinstance(d["doc"].get("blocks"), list) else None
        self.page = dict(self.page, title=d.get("title") or "")
        if doc is not None and not self.is_database:
            ensure_ids(doc["blocks"])
            self.doc, self.root = doc, doc["blocks"]
        self.rev = d.get("rev") if isinstance(d.get("rev"), int) else self.rev
        self.mark_dirty()
        if self.on_notice:
            self.on_notice(_("Unsaved changes from last time were restored."))
        GLib.idle_add(lambda: (self.save(), False)[1])

    # --- Lokale Kopien (Konflikt: "Server-Fassung laden" sichert vorher die eigene Fassung) -------------------------
    def save_local_copy(self):
        """eigene Fassung (wie gerade angezeigt) als lokale Kopie ablegen -> True, wenn sicher auf der Platte"""
        if not self.fetched:
            return False
        try:
            ok = drafts.write_copy(self._snapshot(deep=True), self.server, self._acct()) is not None
        except (TypeError, ValueError):
            ok = False
        if ok and self.on_copies:
            self.on_copies()
        return ok

    def local_copies(self):
        """lokale Kopien, die sich vom angezeigten Stand unterscheiden (neueste zuerst). Gleicht eine dem gespeicherten
        Stand, ist sie ueberfluessig und wird geloescht (nicht, solange der angezeigte Stand ungespeichert ist)."""
        if not self.fetched:
            return []
        out = []
        for p, d in drafts.copies(self.id, self.server, self._acct()):
            if not self._same_content(d):
                out.append((p, d))
            elif not self.dirty and self.conflict is None and not self._saving:
                drafts.drop_copy(p)
        return out

    def restore_copy(self, path):
        """Kopie wiederherstellen: ersetzt den angezeigten Stand und speichert ihn (bewusste Entscheidung - auch ueber
        die Server-Fassung hinweg). Die Kopie wird erst geloescht, wenn ihr Inhalt als Entwurf sicher liegt."""
        d = next((d for p, d in drafts.copies(self.id, self.server, self._acct()) if p == path), None)
        if d is None or not self.fetched:
            return False
        if (self.dirty or self.conflict is not None) and not self._same_content(d):
            self.save_local_copy()                           # angezeigte, ungespeicherte Eingaben nicht verwerfen
        if self.conflict is not None:                        # offener Konflikt: Wiederherstellen = meine Fassung
            self.rev = self.conflict.get("rev") or self.rev
            self.conflict = None
        self.page = dict(self.page, title=d.get("title") or "")
        doc = d.get("doc") if isinstance(d.get("doc"), dict) and isinstance(d["doc"].get("blocks"), list) else None
        if doc is not None and not self.is_database:
            ensure_ids(doc["blocks"])
            self.doc, self.root = doc, doc["blocks"]
        self.mark_dirty()
        self._write_draft()
        drafts.drop_copy(path)
        store.update_title(self.id, self.title_raw)
        if self.on_loaded:
            self.on_loaded(self)
        self.save()
        return True

    def discard_copy(self, path):
        drafts.drop_copy(path)
        if self.on_copies:
            self.on_copies()

    # --- Speichern -----------------------------------------------------------------------------
    def _release(self):
        ws, self._waiters = self._waiters, []
        for cb in ws:
            try:
                cb()
            except Exception:  # noqa: BLE001
                import traceback
                traceback.print_exc()

    def save(self, done=None):
        # Nie eine Seite speichern, die nicht geladen werden konnte: sonst wuerde die leere Ansicht
        # ("Unbenannt", ohne Bloecke) den echten Inhalt auf dem Server ueberschreiben.
        if done is not None:
            self._waiters.append(done)
        if not self.loaded or not self.fetched:
            self._release()
            return
        if self.read_only:                                   # Lesen/Kommentieren, Zugriff verloren: nie speichern
            self.dirty = False
            self._release()
            return
        if not self.same_session():
            self._write_draft()                              # Server/Konto gewechselt: nur als Entwurf (dort) behalten
            self._release()
            return
        if self.conflict is not None:                        # erst entscheiden (Dialog), dann weiter
            self._write_draft()
            return
        if self._saving:
            return                                           # fin() speichert danach den neuesten Stand
        if not self.dirty:
            self._release()
            return
        if self._retry_src:
            GLib.source_remove(self._retry_src)
            self._retry_src = 0
        self._saving = True
        self.doc["blocks"] = self.root
        self.doc["time"] = int(time.time() * 1000)
        self._write_draft()
        b = {"title": self.title_raw, "rev": self.rev}
        if not self.is_database:
            b["body"] = json.dumps(self.doc, ensure_ascii=False)
            b["format"] = "blocks"
        if self._overwrite:                                  # "Meine Version behalten": andere Fassung als Version sichern
            b["overwrite"] = True
            self._overwrite = False
        title = self.title_raw
        sent = self.gen
        acct = self._acct()
        from .nextcloud import keys_of
        sent_nc = keys_of(self.root) if not self.is_database else set()

        def fin(r):
            self._saving = False
            self.nc_known = sent_nc
            self.nc_error = None
            if isinstance(r, dict) and r.get("rev") is not None:
                self.rev = r["rev"]
            self.dirty = self.gen != sent             # inzwischen weiter getippt -> gleich noch einmal
            self.error = self.last_error = None
            self.conflict_by = None
            self._offline = False
            self.too_large = False
            if isinstance(self.page.get("updated_by"), dict):  # "Zuletzt geaendert von": jetzt ich
                self.page["updated_by"] = {"me": True, "name": "", "username": None}
            store.update_title(self.id, title)
            store.drop_cache(f"api/notes/{self.id}")
            if self.parent_schema is not None:
                store.changed_data()
            if not self.dirty:
                drafts.drop(self.id, self.server, acct)
            if self.on_saved:
                self.on_saved()
            if self.dirty:
                self.save()
            else:
                self._release()

        def err(e):
            self._saving = False
            if self._nc_rejected(e, sent_nc):                   # neuer Nextcloud-Block abgelehnt (fremd, nicht verbunden ...)
                return
            if isinstance(e, APIError) and e.code == 409:
                by = e.data.get("by") if isinstance(e.data, dict) else None
                self.conflict_by = by if isinstance(by, str) and by else None
                self._fetch_conflict()
                return
            if isinstance(e, APIError) and e.code in (403, 404) and self.foreign:
                self._lose(e.code)                           # Freigabe entzogen bzw. nur noch Lesen
                return
            self._write_draft()
            if isinstance(e, APIError) and e.code == SWITCHED:   # Server/Konto gewechselt: bleibt als Entwurf
                self._release()
                return
            self.last_error = e
            self._report(e)
            if not (isinstance(e, APIError) and e.code in (401, 403, 404, 413, 423)):
                self._retry_src = GLib.timeout_add_seconds(20, self._retry)    # offline: spaeter noch einmal
            self._release()
        srv = self.server

        def send():
            return client.json("PATCH", f"api/notes/{self.id}", b, server=srv, account=acct)
        bg_serial(("note", self.id), send, done=fin, error=err)

    def _nc_rejected(self, e, sent_nc):
        """Der Server lehnt das Speichern wegen eines NEUEN Nextcloud-Blocks ab (403: Datei einer anderen Person, 400: nicht
        aus der eigenen Nextcloud, 409 ohne Revision: nicht verbunden). Kein Verlust der Freigabe, kein Konflikt: verstaendlich
        melden, Stand als Entwurf behalten; jede weitere Aenderung versucht es erneut (z. B. nach Entfernen des Blocks)."""
        if not isinstance(e, APIError) or e.code not in (400, 403, 409):
            return False
        if e.code == 409 and isinstance(e.data, dict) and "rev" in e.data:
            return False                                     # echter Konflikt
        if not (sent_nc - getattr(self, "nc_known", set())):
            return False
        self._write_draft()
        self.last_error = e
        first = getattr(self, "nc_error", None) != str(e)
        self.nc_error = str(e)
        if first and self.on_error:
            self.on_error(_("Not saved: {error}").format(error=e) + " " +
                          _("Remove the Nextcloud file or insert it from your own Nextcloud (Settings → Account → Nextcloud)."))
        self._release()
        return True

    def _report(self, e):
        """Fehler beim Speichern melden. Offline (Server nicht erreichbar): einmal der Offline-Hinweis, nicht bei
        jedem weiteren Versuch. 413 (Seite zu gross): einmal erklaeren, was zu tun ist; die Seite zeigt den Hinweis
        dauerhaft (too_large), jede weitere Eingabe versucht es erneut (gekuerzt klappt es wieder) - BUG-081."""
        if isinstance(e, APIError) and e.code == 413:
            first = not self.too_large
            self.too_large = True
            if first and self.on_error:
                self.on_error(_("This page is too large to save. Shorten it or split it into several pages – until then "
                                "your changes are only kept on this device."))
            if self.on_copies:
                self.on_copies()                             # Hinweis oben an der Seite aktualisieren
            return
        if isinstance(e, APIError) and e.code == 0:
            if not self._offline and self.on_error:
                self.on_error(_("Offline – changes will be saved as soon as the server is reachable."))
            self._offline = True
            return
        self._offline = False
        if self.on_error:
            self.on_error(_("Not saved: {error}").format(error=e))

    def _retry(self):
        self._retry_src = 0
        if self.dirty and not self._saving:
            self.save()
        return False

    # --- Konflikte -----------------------------------------------------------------------------
    def _fetch_conflict(self):
        def fin(p):
            if self.dirty or self.conflict is not None:     # inzwischen schon geloest (z. B. Server-Fassung uebernommen)?
                self._conflict(p)

        def err(e):
            self.last_error = e
            self._write_draft()
            self._report(e)
            self._release()
        bg(client.get, f"api/notes/{self.id}", done=fin, error=err)

    def _lose(self, code):
        """Speichern einer fremden Seite abgelehnt (403: nur noch lesen, 404: Freigabe weg): nicht mehr speichern,
        den letzten eigenen Text zum Kopieren anbieten (on_lost), Entwurf verwerfen (er liesse sich nie speichern)."""
        text = self.text_now()
        self.lost = code
        self.dirty = False
        self.conflict = None
        if self._retry_src:
            GLib.source_remove(self._retry_src)
            self._retry_src = 0
        drafts.drop(self.id, self.server, self._acct())
        self._release()
        if self.on_lost:
            self.on_lost(self, code, text)
        elif self.on_error:
            from .sharing import lost_texts
            self.on_error(lost_texts(code)[0])

    def _conflict(self, p):
        already = self.conflict is not None
        if not self.conflict_by:                             # beim Laden bemerkt: wer zuletzt gespeichert hat
            ub = p.get("updated_by") if isinstance(p, dict) else None
            if isinstance(ub, dict) and not ub.get("me") and ub.get("name"):
                self.conflict_by = ub["name"]
        self.conflict = p                                    # immer die neueste Server-Fassung merken
        self._write_draft()
        if already:
            return                                           # Frage laeuft schon
        if self.on_conflict:
            self.on_conflict(self, p)
        else:
            self.resolve("copy")

    def resolve(self, choice):
        """Konflikt loesen:
        'mine'   meine Fassung behalten (speichern, ueberschreibt die andere Aenderung)
        'theirs' Server-Fassung laden - meine Fassung wird IMMER vorher als lokale Kopie gesichert (Banner bietet sie an)
        'later'  noch nicht entscheiden (Dialog geschlossen, Esc): nichts ueberschreiben, Hinweis bleibt an der Seite
        'copy'   ohne Dialog (Seite nicht sichtbar): meine Fassung als Kopie-Seite auf dem Server, hier die Server-Fassung;
                 was waehrenddessen weiter getippt wurde, bleibt als lokale Kopie"""
        p = self.conflict
        if p is None or self._resolving or choice == "later":
            return
        if choice == "copy" and self.is_database:
            choice = "theirs"                                # Datenbank: nur der Titel - lokale Kopie genuegt
        if choice == "mine":
            self.conflict = None
            self.conflict_by = None
            self.rev = p.get("rev") or 0
            self._overwrite = True                           # Server sichert die andere Fassung als Version
            self.mark_dirty()
            self.save()
            return
        if choice == "theirs":
            if self.save_local_copy():                       # gleicht sie der Server-Fassung, raeumt der Banner sie weg
                self._take_theirs(p)
            elif self.on_error:                              # Kopie nicht sicher abgelegt: nichts verwerfen
                self.on_error(_("Your version could not be saved as a copy. Nothing was discarded."))
            return
        self._resolving = True                               # Konflikt bleibt offen, bis die Kopie sicher angelegt ist
        self.doc["blocks"] = self.root
        title = (self.title_raw or _("Untitled")) + " " + _("(conflict copy)")
        body = json.dumps(self.doc, ensure_ascii=False)
        parent = p.get("parent_id")
        sent = self.gen
        srv, acct = self.server, self._acct()

        def work():
            b = {"title": title}
            if parent is not None:
                b["parent_id"] = parent
            r = client.json("POST", "api/notes", b, server=srv, account=acct)
            client.json("PATCH", f"api/notes/{r['id']}", {"body": body, "format": "blocks"}, server=srv, account=acct)
            return r["id"]

        def fin(nid):
            self._resolving = False
            if self.gen != sent and not self.save_local_copy():    # inzwischen weiter getippt: das nicht verwerfen
                self._write_draft()
                if self.on_error:
                    self.on_error(_("Your version could not be saved as a copy. Nothing was discarded."))
                store.refresh()
                return
            self._take_theirs(self.conflict or p)
            if self.on_notice:
                self.on_notice(_("Your version was saved as “{title}”.").format(title=title))
            store.refresh()

        def err(e):
            self._resolving = False                          # nichts verwerfen: Konflikt bleibt offen
            self._write_draft()
            if self.on_error and not (isinstance(e, APIError) and e.code == SWITCHED):
                self.on_error(_("Conflict copy not created: {error}").format(error=e))
        bg(work, done=fin, error=err)

    def _take_theirs(self, p):
        self.conflict = None
        self.conflict_by = None
        drafts.drop(self.id, self.server, self._acct())
        self.dirty = False
        self.gen += 1
        self._apply(p, None)
        store.update_title(self.id, self.title_raw)
        if self.on_loaded:
            self.on_loaded(self)
        self._release()


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
        """Struktur geaendert: betroffene Bloecke neu zeichnen (unveraenderte bleiben stehen) + speichern"""
        self.view.rebuild_blocks()
        self.text_changed()

    def text_changed(self):
        self.doc.mark_dirty()
        typed = getattr(self.view, "on_typed", None)            # Anwesenheit: "bearbeitet gerade"
        if typed:
            typed()
        if self._save_src:
            GLib.source_remove(self._save_src)
        self._save_src = GLib.timeout_add(700, self._save_now)

    def _save_now(self):
        self._save_src = 0
        self.doc.save()
        return False

    def flush(self, done=None):
        """Sofort speichern; done() erst, wenn alles gespeichert ist (auch wenn gerade schon gespeichert wird)"""
        if self._save_src:
            GLib.source_remove(self._save_src)
            self._save_src = 0
        if self.doc.dirty or self.doc.saving or self.doc.conflict is not None:
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
        if btype == "nextcloud":
            self.changed()
            v.pick_nextcloud(bid)
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
                self.flush(lambda: store.page_added(res["id"], self.doc.id, done=lambda: btype == "page" and v.open_page(res["id"])))
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
        return b["id"]
