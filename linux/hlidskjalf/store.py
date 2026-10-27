"""Zentraler Zustand wie Store.swift: Seiten, Aufgaben, Termine, Zuletzt, Startseite. Signale fuer die Oberflaeche."""
import datetime as _dt
import json
import os
import re
import time
import weakref

from gi.repository import GLib, GObject

from . import config, notesync
from .api import APIError, client
from .i18n import _
from .util import Debounce, bg, bg_serial, iso_day, writes_pending

PAGE_FIELDS = ("id", "title", "icon", "parent_id", "kind", "pinned", "updated", "snippet", "archived", "template", "tags")


def label(p):
    t = (p or {}).get("title") or ""
    return t if t else _("Untitled")


def is_db(p):
    return (p or {}).get("kind") == "database"


class Store(GObject.Object):
    __gsignals__ = {
        "pages-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "today-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "data-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),     # Datenbank-Ansichten neu laden
        "row-changed": (GObject.SignalFlags.RUN_FIRST, None, (int,)),  # Eigenschaften eines Eintrags in der Tabelle gespeichert (N9)
        "version-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),  # Server-Version bekannt (Hinweis "App zu alt")
        "events-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "need-login": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "logged-in": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "error": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
        "undo": (GObject.SignalFlags.RUN_FIRST, None, (int, str)),      # Papierkorb: Rueckgaengig anbieten
        "task-deleted": (GObject.SignalFlags.RUN_FIRST, None, (int, str)),   # Aufgabe geloescht: Rueckgaengig anbieten
        "server-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
    }

    def __init__(self):
        super().__init__()
        self.pages = []
        self.archived = []
        self.by_id = {}
        self._all = []                    # alle eigenen Seiten wie GET api/notes (auch archivierte, Server-Reihenfolge)
        self._kids = {}                   # sichtbare Seiten je parent_id (linearer Baumaufbau)
        # Teilen (Server ab 1.3): fremde Seiten, die ich sehen darf (GET api/shared, flach; root=True = oberste geteilte
        # Seite). Getrennt von den eigenen (by_id), damit Favoriten, Zuletzt, Verschieben ... nur eigene Seiten kennen.
        self.shared = []
        self.shared_by_id = {}
        self.share_api = None             # Server kennt Teilen? None = noch unbekannt, False = aelterer Server (404)
        self.share_off = False            # Teilen abgeschaltet, obwohl mir etwas freigegeben ist -> Hinweis
        # Foederation (2.0): Seiten anderer Saga-Instanzen (negative IDs), getrennt geladen (GET api/fed/shared, langsam)
        self.fed_shared = []
        self.fed_unreachable = []         # Hosts, die gerade nicht erreichbar sind (dezenter Hinweis)
        self._fed_loading = False
        self.tasks = []
        self.done_tasks = []
        self.events = []
        self.loading = False
        self.needs_login = False
        self._refreshing = False
        self._refresh_waiters = []        # Rueckrufe der laufenden Auffrischung
        self._refresh_next = None         # Rueckrufe fuer eine weitere Auffrischung (danach angefragt)
        self._refreshed_at = 0.0
        self._task_deletes = {}           # Aufgabe -> (Aufgabe, Zeitgeber): Loeschen erst nach der Rueckgaengig-Frist
        self.docs = weakref.WeakSet()     # offene Seiten (PageDoc) - Schliessen wartet auf deren Speichern
        self.changed_data = Debounce(350, lambda: self.emit("data-changed"))   # Datenbank-Ansichten neu laden (gebuendelt)
        self.recent = list(config.settings.get("recent", []))
        self.server_home = False          # E2 (2.5): Server fuehrt die Startseite als Kontoeinstellung start_page
        self.fresh = {}                   # E4 (2.5): eben aus einer Vorlage angelegte Eintraege -> {"tok", "placeholder"}
        client.on_need_login = lambda: GLib.idle_add(self._need_login)
        # Seitenbaum-Abgleich (2.2): _tree_base = Liste genau wie der Server sie zum Stand _tree_cursor kennt (Grundlage
        # fuers Einarbeiten, nie lokal veraendert); die angezeigte Liste (_all, by_id ...) sind Kopien davon, die lokale
        # Aenderungen (neue Seite, Papierkorb ...) sofort zeigen und beim naechsten Abgleich ersetzt werden.
        self._tree_base = []
        self._tree_cursor = None
        self.tree_bytes = 0                # Bytes der Antworten von api/notes (Messung/Tests)
        c = self.cached("api/notes")
        if isinstance(c, list):
            c = [p for p in c if isinstance(p, dict) and p.get("id") is not None]
            self._tree_base = c
            self._set_pages([dict(p) for p in c])
            cur = self.cached(self.CURSOR_CACHE)
            self._tree_cursor = cur if isinstance(cur, dict) else None
        c = self.cached("api/fed/shared")
        if isinstance(c, dict):
            self.fed_shared = [p for p in c.get("items") or [] if isinstance(p, dict) and p.get("id") is not None]
        c = self.cached("api/shared")
        if isinstance(c, list):
            self._set_shared(c)

    # --- Zwischenspeicher --------------------------------------------------------------------
    @staticmethod
    def _cache_path(path):
        return os.path.join(config.CACHE_DIR, path.replace("/", "_").replace("?", "_").replace("&", "_"))

    def cached(self, path):
        try:
            with open(self._cache_path(path), "rb") as f:
                return json.loads(f.read())
        except (OSError, ValueError):
            return None

    def get_cached(self, path):
        """GET + im Zwischenspeicher ablegen (Hintergrund-Thread)"""
        srv = config.settings.server
        raw = client.request("GET", path)
        if srv == config.settings.server:                  # inzwischen anderer Server: nicht in dessen Zwischenspeicher
            try:
                config.atomic_write(self._cache_path(path), raw)
            except OSError:
                pass
        return json.loads(raw)

    CURSOR_CACHE = "api/notes.cursor"      # Stand des Abgleichs zur zwischengespeicherten Liste "api/notes"

    def drop_tree_cursor(self):
        """Stand verwerfen: der naechste Abgleich holt den ganzen Baum (Abmelden, Kontowechsel, Serverwechsel, Live
        "reset", notes.tree mit full: true)"""
        self._tree_cursor = None
        self.drop_cache(self.CURSOR_CACHE)

    def _write_tree_cache(self, base, cursor):
        """Zwischenspeicher: eingearbeitete Liste im bisherigen Format (wie die Antwort von GET api/notes) plus
        Stand-Datei. Erst den Stand loeschen, dann die Liste, dann den Stand - nie ein Stand zu einer fremden Liste."""
        try:
            os.remove(self._cache_path(self.CURSOR_CACHE))
        except OSError:
            pass
        try:
            if base is not None:
                config.atomic_write(self._cache_path("api/notes"), json.dumps(base, ensure_ascii=False, separators=(",", ":")))
            if cursor:
                config.atomic_write(self._cache_path(self.CURSOR_CACHE), json.dumps(cursor))
        except (OSError, TypeError, ValueError):
            pass

    def drop_cache(self, path):
        try:
            os.remove(self._cache_path(path))
        except OSError:
            pass

    def clear_cache(self):
        import shutil
        shutil.rmtree(config.CACHE_DIR, ignore_errors=True)
        from . import favicon                              # 2.5: direkt geladene Lesezeichen-Favicons (Speicher; Platte oben)
        favicon.forget_memory()
        for d in (config.CACHE_DIR, os.path.join(config.CACHE_DIR, "files")):
            os.makedirs(d, mode=0o700, exist_ok=True)
            os.chmod(d, 0o700)

    def switch_server(self, server):
        """Anderer Server: alles vom alten weg (Sitzung, Zwischenspeicher, Zuletzt, Startseite, aufgeklappte Seiten;
        Entwuerfe bleiben je Server und Konto getrennt liegen). Das Geraet wird beim alten Server abgemeldet
        (/app/forget mit dessen Geraete-Schluessel, wie iOS/Android); Fehler dabei (offline) blockieren nichts."""
        server = config.origin(server) or server
        old = config.origin(config.settings.server)
        if server == old:
            return False
        tok = config.get_device_token(old) if old else None
        if old:
            self.new_session(old)
        config.set_account(None, server)                   # Konto dort erst nach der Anmeldung bekannt
        client.set_cookies(server, {})
        config.settings.server = server
        if old:                                            # erst nach dem Umstellen (forget_server prueft den Server)
            bg(client.forget_server, old, tok, True, error=lambda e: None)
        self.clear_cache()
        self._tree_base, self._tree_cursor = [], None
        self.trashed_ids = set()
        self._set_pages([])
        self.tasks, self.done_tasks, self.events = [], [], []
        self.fed_shared, self.fed_unreachable = [], []
        self._set_shared([])
        self.share_api, self.share_off = None, False
        self.recent = []
        for k in ("recent", "home_page", "expanded"):
            config.settings.set(k, None)
        self.server_home, self.fresh = False, {}
        self.server_version = self.min_app = None
        self.emit("server-changed")
        self.emit("pages-changed")
        self.emit("today-changed")
        return True

    # --- Anmeldung ----------------------------------------------------------------------------
    def _need_login(self):
        if not self.needs_login:
            self.needs_login = True
            self.emit("need-login")
        return False

    def logged_in(self):
        self.needs_login = False
        self.emit("logged-in")
        self.refresh()

    # --- Konto: Entwuerfe gehoeren zu Server + Konto -------------------------------------------------
    session_gen = 0

    def new_session(self, server=None):
        """Neue Anmeldung (oder Abmeldung): Konto erst wieder bekannt, wenn /api/account geantwortet hat. Bis dahin
        speichern offene Seiten des bisherigen Kontos nur als Entwurf (nie unter einem anderen Konto)."""
        self.session_gen += 1
        self.tree_seen = False                             # Faehigkeit des Servers (notes.tree) neu lernen
        self.drop_tree_cursor()                            # Stand gilt je Konto: neu anmelden = ganz laden
        srv = config.origin(server or config.settings.server)
        for d in list(self.docs):
            if d.account is None and config.origin(d.server) == srv:
                d.account = ""                             # Konto des Vorgaengers unbekannt: nie dem neuen zuordnen
        config.set_account(None, srv)

    def set_account(self, name, server=None):
        """Konto an server bekannt: frueher (ohne Konto) abgelegte Entwuerfe uebernehmen, offene Seiten zuordnen
        und deren ungespeicherte Eingaben jetzt senden (falls sie zu diesem Konto gehoeren)."""
        from . import drafts
        srv = config.origin(server or config.settings.server)
        config.set_account(name, srv)
        drafts.migrate(srv, name)
        for d in list(self.docs):
            d.account_known()

    def forget_account_data(self):
        """Inhalte des bisherigen Kontos weg (Zwischenspeicher, Seitenliste, Zuletzt ...) - Abmelden und Anmelden mit
        einem (moeglicherweise anderen) Konto am selben Server (BUG-031). Entwuerfe bleiben je Konto getrennt liegen."""
        self.tree_seen = False
        self.clear_cache()
        self._tree_base, self._tree_cursor = [], None
        self.trashed_ids = set()
        self._set_pages([])
        self.tasks, self.done_tasks, self.events = [], [], []
        self.fed_shared, self.fed_unreachable = [], []
        self._set_shared([])
        self.share_api, self.share_off = None, False
        self.recent = []
        config.settings.set("recent", None)
        self.emit("pages-changed")
        self.emit("today-changed")

    def logout(self, done=None):
        self.new_session()                                 # Entwuerfe bleiben beim Konto liegen (naechste Anmeldung)

        def work():
            client.forget()

        def fin(_):
            self.forget_account_data()
            self.needs_login = True
            self.emit("need-login")
            if done:
                done()
        bg(work, done=fin, error=fin)

    # --- Seiten -------------------------------------------------------------------------------
    @staticmethod
    def split_pages(allp):
        """GET api/notes -> (alle, by_id, sichtbare, oberste archivierte, Kinder je Elternseite). Reine Funktion, laeuft
        beim Neuladen im Hintergrund-Thread; Kinder-Index = linearer Baumaufbau statt Suche ueber alle Seiten je Knoten."""
        allp = [p for p in allp if isinstance(p, dict) and p.get("id") is not None]
        by_id = {p["id"]: p for p in allp}
        pages = [p for p in allp if not p.get("archived")]
        arch = {p["id"] for p in allp if p.get("archived")}
        archived = [p for p in allp if p.get("archived") and not (p.get("parent_id") in arch)]
        kids = {}
        for p in pages:
            kids.setdefault(p.get("parent_id"), []).append(p)
        return allp, by_id, pages, archived, kids

    def _set_pages(self, allp, split=None):
        self._all, self.by_id, self.pages, self.archived, self._kids = split or self.split_pages(allp)
        if self.trashed_ids:                                  # wieder da (zurueckgeholt, auch auf einem anderen Geraet)
            self.trashed_ids -= set(self.by_id)

    trashed_ids = set()      # eigene Seiten, die hier in den Papierkorb gingen (samt Unterseiten) - nicht mehr bearbeiten

    _local_epoch = 0

    def _local_tree_change(self):
        """Seitenliste lokal geaendert (neue Seite, Papierkorb, Archiv, Verschieben): eine gerade laufende Auffrischung
        hat davon evtl. noch nichts gewusst -> ihre Seitenliste nicht uebernehmen, sondern noch einmal laden (refresh)."""
        self._local_epoch += 1

    @staticmethod
    def meta_from_detail(d):
        """Eintrag der Seitenliste aus GET api/notes/{id} einer EIGENEN Seite - dieselben Felder und Werte wie in
        GET api/notes (unread = ungelesene Kommentare von anderen)."""
        keys = ("id", "title", "icon", "parent_id", "updated", "tags", "pinned", "template", "kind", "archived", "locked", "shared")
        m = {k: d.get(k) for k in keys if k in d}
        m["unread"] = bool(d.get("comments_unread"))
        return m

    def insert_page(self, meta):
        """Neue eigene Seite lokal einfuegen (statt die ganze Liste neu zu laden). Neue Seiten bekommen auf dem Server
        sort = Anlegezeit und stehen damit wie nach einem Neuladen am Ende."""
        pid = meta["id"]
        if pid in self.by_id:
            return
        self._local_tree_change()
        self._all = list(getattr(self, "_all", self.pages)) + [meta]
        self.by_id = dict(self.by_id)
        self.by_id[pid] = meta
        if not meta.get("archived"):
            self.pages = self.pages + [meta]
            kids = dict(getattr(self, "_kids", None) or {})
            kids[meta.get("parent_id")] = list(kids.get(meta.get("parent_id"), [])) + [meta]
            self._kids = kids
        self.emit("pages-changed")

    def _subtree_all(self, pid):
        """pid und alle Nachfahren (auch archivierte) - wie der Server beim Papierkorb/Archivieren"""
        kids = {}
        for p in getattr(self, "_all", self.pages):
            kids.setdefault(p.get("parent_id"), []).append(p["id"])
        out, stack = set(), [pid]
        while stack:
            n = stack.pop()
            if n not in out:
                out.add(n)
                stack.extend(kids.get(n, ()))
        return out

    def remove_subtree_local(self, pid, archived=False):
        """Papierkorb (archived=False) bzw. Archiv (True) lokal nachziehen: der Server nimmt die Seite samt aktiver
        Unterseiten mit. Danach dieselbe Liste wie nach einem Neuladen (Reihenfolge bleibt)."""
        gone = self._subtree_all(pid)
        if not archived:
            self.trashed_ids = self.trashed_ids | set(gone)
        self._local_tree_change()
        allp = getattr(self, "_all", self.pages)
        if archived:
            for p in allp:
                if p["id"] in gone and not p.get("archived"):
                    p["archived"] = True
            new = list(allp)
        else:
            new = [p for p in allp if p["id"] not in gone]
        self._set_pages(new)
        self.emit("pages-changed")

    def _move_local(self, pid, parent):
        """Verschieben an Position 0 unter parent sofort zeigen (die anschliessende Auffrischung bringt die genaue
        Reihenfolge des Servers)"""
        p = self.by_id.get(pid)
        if p is None:
            return
        self._local_tree_change()
        p["parent_id"] = parent
        allp = [x for x in getattr(self, "_all", self.pages) if x is not p]
        allp.insert(0, p)
        self._set_pages(allp)
        self.emit("pages-changed")

    # --- Live: notes.tree (Server 2.2) ------------------------------------------------------------
    # In dieser Anmeldung schon ein notes.tree empfangen? Erst dann reicht mit Live-Kanal der 5-min-Takt - die
    # Serverversion allein sagt das nicht sicher (testing-Server melden schon 2.2.0, schicken aber evtl. noch kein
    # notes.tree). Abmelden, Kontowechsel, anderer Server: vergessen (new_session, forget_account_data).
    tree_seen = False

    TREE_DEBOUNCE_MS = 400
    TREE_MAX_TARGETED = 20
    TREE_TARGETED = frozenset(("created", "updated"))
    TREE_FULL = "*full"                                    # Pseudo-Art: full: true -> ganzen Baum neu laden
    _tree_src = 0

    @classmethod
    def tree_plan(cls, ids, ops):
        """-> IDs zum gezielten Nachladen (GET api/notes/{id}) oder None = voller Abgleich (full, moved, trashed,
        restored, archived, deleted, shared, unbekannte Art, keine oder viele IDs)"""
        ids = set(ids)
        ops = set(ops)
        if not ids or not ops or len(ids) > cls.TREE_MAX_TARGETED or any(str(o).lower() not in cls.TREE_TARGETED for o in ops):
            return None
        return ids

    @classmethod
    def tree_parse(cls, d):
        """notes.tree {ids: [int], op: str, ops: [str], full: bool} (docs/APP-API-LIVE.md) -> (ids, Arten, full).
        Arten = ops plus op (ausser "mixed", das nur auf ops verweist); full: true -> Arten enthalten TREE_FULL.
        Tolerant: Zahlen als Text, Zusatzfelder, fehlende Felder (-> Art "" = unbekannt = voller Abgleich)."""
        d = d if isinstance(d, dict) else {}
        ids = []
        for x in d.get("ids") if isinstance(d.get("ids"), list) else []:
            try:
                ids.append(int(float(x)))
            except (TypeError, ValueError, OverflowError):
                pass
        op = str(d.get("op") or "")
        lst = [str(o) for o in d.get("ops") or [] if isinstance(o, str)] if isinstance(d.get("ops"), list) else []
        full = d.get("full") is True
        ops = set(lst)
        if op != "mixed" or not lst:
            ops.add(op)
        if full:
            ops.add(cls.TREE_FULL)
        return ids, ops, full

    def on_tree_event(self, d):
        """Live-Ereignis notes.tree: Seiten anderswo (Web, anderes Geraet, Freigabe) angelegt/geaendert/verschoben/
        geloescht. Kurz sammeln (entprellt), dann gezielt nachladen und einpflegen bzw. voll abgleichen."""
        self.tree_seen = True                              # Server meldet Baum-Aenderungen: seltener Takt reicht
        ids, ops, _full = self.tree_parse(d)
        if not hasattr(self, "_tree_ids"):
            self._tree_ids, self._tree_ops = set(), set()
        self._tree_ids.update(ids)
        self._tree_ops.update(ops if ids else ops | {""})
        if not self._tree_src:
            self._tree_src = GLib.timeout_add(self.TREE_DEBOUNCE_MS, self._tree_apply)

    def _tree_apply(self):
        self._tree_src = 0
        ids, ops = set(self._tree_ids), set(self._tree_ops)
        self._tree_ids.clear()
        self._tree_ops.clear()
        if self.needs_login:
            return False
        plan = self.tree_plan(ids, ops)
        if plan is None:
            if self.TREE_FULL in ops:                      # full: true -> ganzen Baum neu (Stand verwerfen)
                self.drop_tree_cursor()
            self._local_tree_change()                      # laufende Auffrischung kennt die Aenderung evtl. noch nicht
            self.refresh()
            return False
        if self._tree_cursor:
            # Server mit Abgleich: ein kleines GET api/notes?since= statt je Seite GET api/notes/{id} (mit Inhalt)
            self._local_tree_change()
            self.refresh(tree_only=plan)
            return False
        srv = config.settings.server

        def work():
            out = []
            for i in sorted(plan):
                try:
                    out.append((i, self.get_cached(f"api/notes/{i}"), None))
                except APIError as e:
                    out.append((i, None, e.code))
            return out

        def fin(res):
            if srv != config.settings.server:
                return
            full = False
            for i, d, code in res:
                if isinstance(d, dict):
                    if d.get("role") in (None, "owner") and i > 0:
                        self.upsert_page(self.meta_from_detail(d))
                    else:
                        full = True                        # fremde (geteilte) Seite: "Mit mir geteilt" vom Server
                elif code in (403, 404) and i in self.by_id:
                    self.remove_subtree_local(i)
                else:
                    full = True
            if full:
                self._local_tree_change()
                self.refresh()
            else:
                self.load_today()

        bg(work, done=fin, error=lambda e: self.refresh())
        return False

    def upsert_page(self, meta):
        """Eigene Seite (Eintrag wie in GET api/notes) einpflegen: ersetzen (Stelle bleibt) bzw. neu ans Ende"""
        allp = list(getattr(self, "_all", self.pages))
        i = next((k for k, p in enumerate(allp) if p["id"] == meta["id"]), None)
        if i is None:
            allp.append(meta)
        else:
            old = allp[i]
            old.clear()                                    # dasselbe Objekt behalten (offene Ansichten halten es)
            old.update(meta)
        self._local_tree_change()
        self._set_pages(allp)
        self.emit("pages-changed")

    def _set_shared(self, lst):
        lst = [p for p in (lst or []) if isinstance(p, dict) and p.get("id") is not None]
        self._local_shared = lst
        have = {x["id"] for x in lst}
        lst = lst + [p for p in self.fed_shared if p["id"] not in have]
        self.shared = lst
        self.shared_by_id = {p["id"]: p for p in lst}

    def load_fed_shared(self):
        """Foederation: "Mit mir geteilt" von anderen Saga-Instanzen (kann je Server ~4 s dauern - eigene Anfrage,
        die Seitenleiste wartet nicht darauf). Aeltere Server/ohne Foederation: 404 bzw. leer."""
        if self._fed_loading:
            self._fed_again = True                           # z. B. Unterseite angelegt (2.3): danach noch einmal laden
            return
        self._fed_loading = True
        self._fed_again = False
        srv = config.settings.server

        def again():
            if getattr(self, "_fed_again", False) and srv == config.settings.server:
                self.load_fed_shared()

        def fin(j):
            self._fed_loading = False
            GLib.idle_add(lambda: (again(), False)[1])
            if srv != config.settings.server:
                return
            j = j if isinstance(j, dict) else {}
            items = [p for p in j.get("items") or [] if isinstance(p, dict) and p.get("id") is not None]
            unr = [h for h in j.get("unreachable") or [] if isinstance(h, str)]
            if items != self.fed_shared or unr != self.fed_unreachable:
                keep = {p["id"] for p in items}
                for p in self.fed_shared:
                    if p["id"] not in keep:
                        self.drop_cache(f"api/notes/{p['id']}")
                self.fed_shared, self.fed_unreachable = items, unr
                self._set_shared(getattr(self, "_local_shared", []))
                self.emit("pages-changed")

        def err(e):
            self._fed_loading = False
            self._fed_again = False
            if isinstance(e, APIError) and e.code == 404 and self.fed_shared:
                self.fed_shared, self.fed_unreachable = [], []
                self.drop_cache("api/fed/shared")
                self._set_shared(getattr(self, "_local_shared", []))
                self.emit("pages-changed")
        bg(self.get_cached, "api/fed/shared", done=fin, error=err)

    def meta(self, pid):
        """Seiten-Eintrag (eigene oder mit mir geteilte Seite) oder None"""
        return self.by_id.get(pid) or self.shared_by_id.get(pid)

    def is_foreign(self, pid):
        """Seite eines anderen Kontos, die mit mir geteilt ist?"""
        return pid not in self.by_id and pid in self.shared_by_id

    def visible(self, pid):
        return pid in self.by_id or pid in self.shared_by_id

    def shared_roots(self):
        return [p for p in self.shared if p.get("root")]

    def shared_children(self, pid):
        par = self.shared_by_id.get(pid)
        if is_db(par):
            return []
        return [p for p in self.shared if p.get("parent_id") == pid and not p.get("root")]

    def shared_unread(self):
        """Anzahl mit mir geteilter Seiten mit neuen Kommentaren"""
        return sum(1 for p in self.shared if p.get("unread"))

    def set_unread(self, pid, on):
        p = self.meta(pid)
        if p is not None and bool(p.get("unread")) != bool(on):
            p["unread"] = bool(on)
            self.emit("pages-changed")

    def page(self, pid):
        p = self.by_id.get(pid)
        return p if p and not p.get("archived") else p

    def children(self, parent):
        par = self.by_id.get(parent) if parent is not None else None
        if par is not None and is_db(par):
            return []
        return list(self._kids.get(parent, ()))

    def has_children(self, pid):
        p = self.by_id.get(pid)
        if is_db(p):
            return False
        return bool(self._kids.get(pid))

    @property
    def favorites(self):
        return [p for p in self.pages if p.get("pinned")]

    def path(self, pid):
        out, cur, n = [], (self.meta(pid) or {}).get("parent_id"), 0
        while cur is not None and self.meta(cur) is not None and n < 20:
            p = self.meta(cur)
            out.insert(0, p)
            cur, n = p.get("parent_id"), n + 1
        return out

    def path_text(self, pid):
        return " / ".join(label(p) for p in self.path(pid))

    def descendants(self, pid):
        """pid und alle sichtbaren Nachfahren (ueber den Kinder-Index, linear)"""
        s, stack = {pid}, [pid]
        while stack:
            for p in self._kids.get(stack.pop(), ()):
                if p["id"] not in s:
                    s.add(p["id"])
                    stack.append(p["id"])
        return s

    def databases(self):
        return [p for p in self.pages if is_db(p)]

    def templates(self):
        return [p for p in self.pages if p.get("template")]

    def all_tags(self):
        return sorted({t for p in self.pages for t in (p.get("tags") or [])})

    def _fetch_tree(self, base, cur, srv, account):
        """Hintergrund: Seitenbaum per Abgleich holen -> (neue Liste | None = unveraendert, neuer Stand | None, voll?).
        Mit gueltigem Stand nur Geaendertes (?since=), sonst den ganzen Baum mit Stand (?delta=1). Aeltere Server
        liefern wie bisher die Liste (Array) - dann kein Stand. Passt ein Abgleich nicht (unbekanntes "after") oder
        lehnt der Server den Stand ab, gleich den ganzen Baum holen."""
        origin = config.origin(srv)
        use = cur.get("value") if notesync.cursor_usable(cur, origin, account) else None
        for attempt in range(3):
            try:
                raw = client.request("GET", notesync.path(use))
            except APIError as e:
                if use and e.code in (400, 410, 422):
                    use = None
                    continue
                raise
            self.tree_bytes += len(raw)
            o = notesync.outcome(notesync.parse(raw), base, bool(use))
            if o["refetch"] and use:
                use = None
                continue
            break
        ncur = None
        if o["cursor"] and account:
            full_at = time.time() if o["full"] else float((cur or {}).get("full_at") or 0)
            ncur = {"value": o["cursor"], "server": origin, "account": account, "full_at": full_at}
        return o["pages"], ncur, o["full"]

    def refresh(self, done=None, tree_only=None):
        """Seitenliste und Aufgaben neu laden. Laeuft schon eine Auffrischung, folgt danach eine weitere
        (sie koennte begonnen haben, bevor z. B. eine neue Seite angelegt war) - kein Rueckruf geht verloren.
        tree_only (IDs aus notes.tree): nur den Seitenbaum abgleichen, danach Heute; sind die IDs danach nicht unter
        den eigenen Seiten (geteilte Seite, gerade geloescht), wie bisher alles neu laden."""
        if self._refreshing:
            if self._refresh_next is None:
                self._refresh_next = []
            if done:
                self._refresh_next.append(done)
            return
        self._refreshing = True
        self.loading = True
        waiters = [done] if done else []
        srv = config.settings.server
        session = self.session_gen
        epoch = self._local_epoch
        base, cur = self._tree_base, self._tree_cursor     # Grundlage und Stand gehoeren zusammen (nur hier ersetzt)

        def work():
            acct = None
            if not tree_only and config.account(srv) is None:   # Konto noch unbekannt (neu angemeldet, App-Update)
                try:
                    acct = (client.get("api/account") or {}).get("username")
                except APIError as e:
                    if e.code == 401:
                        raise
            account = config.account(srv) or (acct if isinstance(acct, str) else None)
            pages, ncur, _full = self._fetch_tree(base, cur, srv, account)
            nbase = base if pages is None else pages
            if srv == config.settings.server and session == self.session_gen and (pages is not None or ncur != cur):
                self._write_tree_cache(pages, ncur)
            split = self.split_pages([dict(p) for p in nbase])   # Kopien anzeigen; Baum im Hintergrund aufbereiten
            if tree_only:
                return nbase, ncur, split, None, None, None
            t = client.get("api/tasks")
            return nbase, ncur, split, t, acct, self._fetch_shared()

        def finish(ok, r):
            self._refreshing = False
            self.loading = False
            stale = srv != config.settings.server            # Server gewechselt: Ergebnis gehoert zum alten
            if ok and not stale and tree_only:
                nbase, ncur, split = r[:3]
                if session == self.session_gen:
                    self._tree_base, self._tree_cursor = nbase, ncur
                if epoch == self._local_epoch:
                    self._set_pages(None, split)
                    self.emit("pages-changed")
                    if any(i not in self.by_id for i in tree_only):
                        if self._refresh_next is None:       # fremde (geteilte) oder geloeschte Seite: alles neu
                            self._refresh_next = []
                    else:
                        self.load_today()
                elif self._refresh_next is None:
                    self._refresh_next = []
            elif ok and not stale:
                nbase, ncur, split, t, acct, (sh, sh_api, sh_off) = r
                if acct and isinstance(acct, str) and session == self.session_gen:
                    self.set_account(acct, srv)
                if session == self.session_gen:              # Server-Stand uebernehmen (auch bei lokaler Aenderung)
                    self._tree_base, self._tree_cursor = nbase, ncur
                if epoch == self._local_epoch:
                    self._set_pages(None, split)
                elif self._refresh_next is None:             # waehrend des Ladens lokal geaendert: Liste evtl. veraltet
                    self._refresh_next = []
                self._apply_shared(sh, sh_api, sh_off)
                self.tasks, self.done_tasks = t.get("open", []), t.get("done", []) or []
                self._drop_pending_deleted()
                self.needs_login = False
                self._refreshed_at = time.monotonic()
                self.emit("pages-changed")
                self.emit("today-changed")
                self.load_events()
                if sh_api is not False:
                    self.load_fed_shared()
                bg(client.ensure_device_token, error=lambda e: None)
                if self.server_version is None:
                    self.check_version()
            elif not ok and not stale and tree_only:
                if self._refresh_next is None:               # wie bisher beim gezielten Nachladen: alles neu laden
                    self._refresh_next = []
            elif not ok and not stale and not (isinstance(r, APIError) and r.code == 401):
                self.emit("error", str(r))
            nxt, self._refresh_next = self._refresh_next, None
            for cb in waiters:
                cb()
            if nxt is not None:
                self.refresh(done=(lambda: [cb() for cb in nxt]) if nxt else None)

        bg(work, done=lambda r: finish(True, r), error=lambda e: finish(False, e))

    def _apply_shared(self, sh, sh_api, sh_off):
        """Ergebnis von _fetch_shared uebernehmen (None = unveraendert lassen)"""
        if sh is None:
            return
        # nicht mehr mit mir geteilt: zwischengespeicherte Inhalte verwerfen (offline nicht mehr lesbar)
        keep = {p.get("id") for p in sh if isinstance(p, dict)} | set(self.by_id) | {p["id"] for p in self.fed_shared}
        for gid in set(self.shared_by_id) - keep:
            self.drop_cache(f"api/notes/{gid}")
            self.drop_cache(f"api/notes/{gid}/rows")
        self._set_shared(sh)
        self.share_api, self.share_off = sh_api, sh_off

    def refresh_light(self):
        """Kleiner Abgleich im Minutentakt, solange der Seitenbaum seltener abgeglichen wird (Live-Kanal + notes.tree):
        Mit mir geteilt (Gruppenwechsel und Verschieben aus geteilten Baeumen loesen serverseitig nicht immer Ereignisse
        aus), andere Server, Heute (Aufgaben/Termine). Glocke und Chats fragt der Fenster-Takt selbst ab."""
        if self.needs_login or self._refreshing:
            return
        srv = config.settings.server

        def fin(r):
            if srv != config.settings.server or self._refreshing:
                return
            sh, sh_api, sh_off = r
            if sh is not None and sh != getattr(self, "_local_shared", None):
                self._apply_shared(sh, sh_api, sh_off)
                self.emit("pages-changed")
            else:
                self.share_api, self.share_off = sh_api, sh_off
            if sh_api is not False:
                self.load_fed_shared()

        bg(self._fetch_shared, done=fin, error=lambda e: None)
        self.load_today()

    def _fetch_shared(self):
        """Hintergrund: "Mit mir geteilt" -> (liste | None = unveraendert lassen, Server kennt Teilen?, Hinweis "abgeschaltet"?).
        Aeltere Server (404): leer, kein Teilen. Ist nichts geteilt, fragt api/share/info, ob Teilen abgeschaltet ist,
        obwohl mir etwas freigegeben wurde (dann zeigt die Seitenleiste einen Hinweis statt still nichts)."""
        try:
            lst = self.get_cached("api/shared")
        except APIError as e:
            if e.code == 401:
                raise
            if e.code == 404:
                self.drop_cache("api/shared")
                return [], False, False
            return None, self.share_api, self.share_off
        lst = lst if isinstance(lst, list) else []
        off = False
        if not lst:
            try:
                info = client.get("api/share/info") or {}
                off = info.get("enabled", True) is False and int(info.get("received") or 0) > 0
            except (APIError, TypeError, ValueError):
                off = self.share_off
        return lst, True, off

    def refresh_if_stale(self, seconds=20):
        """Fokuswechsel: nur auffrischen, wenn die letzte Auffrischung laenger her ist"""
        if not self._refreshing and time.monotonic() - self._refreshed_at > seconds:
            self.refresh()

    POLL_S = 60          # Takt ohne Live-Kanal bzw. solange kein notes.tree kam (wie bisher)
    POLL_LIVE_S = 300    # Takt mit Live-Kanal und schon empfangenem notes.tree (Sicherheitsnetz)

    def refresh_due(self, live_connected):
        """Takt (Fenster aktiv, alle 60 s geprueft): ohne Live-Kanal jedes Mal; mit Live-Kanal erst alle 5 min, wenn in
        dieser Anmeldung schon ein notes.tree kam (tree_seen), sonst weiter jede Minute. Halbe Minute Toleranz, damit
        der 60-s-Zeitgeber nicht eine Runde verpasst. Nicht faellig: kleine Listen trotzdem (refresh_light)."""
        every = self.POLL_LIVE_S if live_connected and self.tree_seen else self.POLL_S
        return time.monotonic() - self._refreshed_at >= every - 30

    server_version = None
    min_app = None
    graph_api = None                    # 2.5: False = Server kennt api/graph nicht (404) -> Einstieg "Graph" ausblenden

    def check_version(self):
        """Server-Version + aelteste App-Version, die er voll unterstuetzt (/api/version, ohne Anmeldung)"""
        def fin(j):
            if isinstance(j, dict):
                self.server_version, self.min_app = j.get("version"), j.get("min_app")
                self.emit("version-changed")
        bg(client.get, "api/version", done=fin, error=lambda e: None)

    @property
    def app_too_old(self):
        from . import VERSION, version_tuple
        return bool(self.min_app) and version_tuple(VERSION) < version_tuple(self.min_app)

    def run(self, method, path, body=None, done=None, error=None, refresh=False, data_changed=False, key=None, coalesce=None):
        """Anfrage im Hintergrund; danach optional Seitenliste/Datenbanken auffrischen.
        key: Schreib-Warteschlange (z. B. ("note", 12)) - Zugriffe mit demselben key laufen nacheinander."""
        if self.fresh and method != "GET":                 # E4: Eintrag wird geaendert (Eigenschaft, Symbol ...) -> bleibt
            m = re.match(r"api/notes/(-?\d+)(/(\w+))?$", path or "")
            if m and m.group(3) != "discard":
                self.fresh.pop(int(m.group(1)), None)
        def fin(r):
            if data_changed:
                self.changed_data()
            if refresh:
                self.refresh()
            if done:
                done(r)

        def err(e):
            if error:
                error(e)
            elif not (isinstance(e, APIError) and e.code == 401):
                self.emit("error", str(e))
        if key is not None:
            bg_serial(key, client.json, method, path, body, done=fin, error=err, coalesce=coalesce)
        else:
            bg(client.json, method, path, body, done=fin, error=err)

    def patch(self, pid, fields, done=None, refresh=False):
        p = self.by_id.get(pid)
        if p:
            for k in ("icon", "pinned", "title", "tags", "template"):
                if k in fields:
                    p[k] = fields[k]
        self.drop_cache(f"api/notes/{pid}")
        self.run("PATCH", f"api/notes/{pid}", fields, done=done, refresh=refresh, key=("note", pid))
        if any(k in fields for k in ("icon", "pinned", "title")):
            self.emit("pages-changed")

    def open_doc(self, pid):
        """offene Seite (PageDoc) mit dieser ID, falls gerade eine angezeigt wird"""
        return next((d for d in list(self.docs) if d.id == pid and d.fetched), None)

    def rename(self, pid, title, done=None):
        """Titel aendern ohne fremde Aenderungen zu ueberschreiben: ist die Seite offen, speichert ihr PageDoc (mit
        Revision); sonst erst die aktuelle Revision holen und mit ihr speichern (409: noch einmal)."""
        doc = self.open_doc(pid)
        if doc is not None:
            doc.set_title(title)
            if done:
                doc.save(lambda: done(None))
            else:
                doc.save()
            return
        p = self.by_id.get(pid)
        if p:
            p["title"] = title
            self.emit("pages-changed")

        def work():
            for _attempt in range(3):
                rev = client.get(f"api/notes/{pid}").get("rev")
                try:
                    return client.json("PATCH", f"api/notes/{pid}", {"title": title, "rev": rev})
                except APIError as e:
                    if e.code != 409:
                        raise
            raise APIError(409, _("The page was changed elsewhere in the meantime."))

        def fin(r):
            self.drop_cache(f"api/notes/{pid}")
            self.changed_data()
            if done:
                done(r)
        bg_serial(("note", pid), work, done=fin, error=lambda e: self.emit("error", str(e)))

    def pending_work(self):
        """Wird gerade noch gespeichert? Das Fenster wartet beim Schliessen darauf."""
        return writes_pending() > 0 or any(d.saving for d in list(self.docs))

    def unsaved_docs(self):
        """offene Seiten mit Eingaben, die (noch) nicht auf dem Server sind (offline, Konflikt)"""
        return [d for d in list(self.docs) if d.dirty or d.conflict is not None]

    def update_title(self, pid, title):
        p = self.by_id.get(pid)
        if p and p.get("title") != title:
            p["title"] = title
            self.emit("pages-changed")

    def new_page(self, parent=None, done=None, kind=None, title=""):
        b = {"title": title}
        if parent is not None:
            b["parent_id"] = parent
        if kind:
            b["kind"] = kind
            b["icon"] = "🗃️"
        def fin(r):
            self.page_added(r["id"], parent, done=lambda: done and done(r["id"]))
        self.run("POST", "api/notes", b, done=fin)

    def page_added(self, nid, parent, done=None):
        """Nach dem Anlegen: eigene Seite gezielt holen (GET api/notes/{id}, wenige KB) und lokal einfuegen, statt die
        ganze Seitenliste neu zu laden. Unter einer fremden (geteilten) Elternseite gehoert die Seite deren Eigentuemer
        ("Mit mir geteilt") -> wie bisher vollstaendig auffrischen; ebenso, wenn das gezielte Laden scheitert."""
        if parent is not None and parent not in self.by_id:
            self.refresh(done=done)
            return
        srv = config.settings.server

        def fin(d):
            if srv != config.settings.server:
                return
            if isinstance(d, dict) and d.get("id") == nid:
                self.insert_page(self.meta_from_detail(d))
                self.load_today()                            # Aufgaben/Termine (klein) wie bisher mit auffrischen
                if done:
                    done()
            else:
                self.refresh(done=done)
        bg(self.get_cached, f"api/notes/{nid}", done=fin, error=lambda e: self.refresh(done=done))

    def trash(self, pid, done=None):
        t = label(self.by_id.get(pid))
        own = pid in self.by_id

        def fin(_):
            self.emit("undo", pid, t)
            if own:
                self.remove_subtree_local(pid)               # Seite samt Unterseiten weg - wie nach einem Neuladen
                self.load_today()                            # Aufgaben/Termine aus der Seite
            else:
                self.refresh()
            self.changed_data()
            if done:
                done()
        self.run("DELETE", f"api/notes/{pid}", done=fin, key=("note", pid))

    def restore(self, pid, done=None, error=None):
        self.run("POST", f"api/trash/{pid}/restore", {}, refresh=True, data_changed=True, done=done, error=error)

    def move(self, pid, parent, done=None, error=None):
        def fin(r):
            if pid in self.by_id:
                self._move_local(pid, parent)                # sofort zeigen; die Auffrischung bringt die Reihenfolge
            self.refresh()
            if done:
                done()
        self.run("POST", f"api/notes/{pid}/move", {"parent_id": parent, "index": 0}, done=fin, error=error)

    def duplicate(self, pid, done=None):
        self.run("POST", f"api/notes/{pid}/duplicate", {}, done=lambda r: self.refresh(done=lambda: done and done(r["id"])), data_changed=True)

    def set_archived(self, pid, on):
        if not on or pid not in self.by_id:                  # Zurueckholen: Daten nur vom Server
            self.run("POST", f"api/notes/{pid}/archive", {"archived": on}, refresh=True, data_changed=True)
            return

        def fin(_):
            self.remove_subtree_local(pid, archived=True)    # samt aktiver Unterseiten ins Archiv - wie nach einem Neuladen
            self.load_today()
        self.run("POST", f"api/notes/{pid}/archive", {"archived": True}, done=fin, data_changed=True)

    def search(self, q, done):
        if len(q.strip()) < 2:
            done([])
            return
        bg(client.json, "POST", "api/notes/search", {"q": q}, done=done, error=lambda e: done([]))

    def visited(self, pid):
        self.recent = [pid] + [x for x in self.recent if x != pid]
        self.recent = self.recent[:8]
        config.settings.set("recent", self.recent)

    @property
    def home_page(self):
        return config.settings.get("home_page")

    def set_home(self, pid, local_only=False):
        """Startseite setzen (None = zuruecksetzen). Server mit start_page (E2): Kontoeinstellung, sonst nur dieses Geraet.
        Lokal gemerkt wird sie immer (Kaltstart ohne Netz oeffnet sie sofort)."""
        old = config.settings.get("home_page")
        config.settings.set("home_page", pid)
        if local_only or not self.server_home:
            return

        def err(e):
            if config.settings.get("home_page") == pid:
                config.settings.set("home_page", old)
            if not (isinstance(e, APIError) and e.code == 401):
                self.emit("error", str(e))
        self.run("PATCH", "api/account", {"start_page": pid}, error=err)

    def account_loaded(self, acc):
        """GET api/account (Fensterkopf): Startseite vom Server uebernehmen, wenn er sie kennt -> (bekannt, Seiten-ID)"""
        from .entries import account_start
        known, start = account_start(acc)
        self.server_home = known
        if known:
            config.settings.set("home_page", start)
        return known, start

    # --- E4: Eintraege aus Vorlagen, die unveraendert wieder geschlossen werden ------------------------------------------
    def note_fresh(self, nid, resp):
        """Antwort von duplicate merken (fresh + placeholder), sofern der Server sie liefert"""
        from .entries import fresh_info
        f = fresh_info(resp)
        if f and isinstance(nid, int):
            self.fresh[nid] = f
        return f

    def discard_fresh(self, nid, done=None):
        """eben angelegten, unveraenderten Eintrag endgueltig loeschen (POST api/notes/{id}/discard). 409 (geaendert,
        abgelaufen) und andere Fehler: Eintrag bleibt, ohne Meldung."""
        f = self.fresh.pop(nid, None)
        if not f:
            return False

        def fin(_r):
            self.refresh()
            self.changed_data()
            if done:
                done(True)
        self.run("POST", f"api/notes/{nid}/discard", {"fresh": f["tok"]}, done=fin, error=lambda e: done and done(False))
        return True

    # --- Aufgaben -------------------------------------------------------------------------------
    def load_today(self):
        def fin(t):
            self.tasks, self.done_tasks = t.get("open", []), t.get("done", []) or []
            self._drop_pending_deleted()
            self.emit("today-changed")
        bg(client.get, "api/tasks", done=fin, error=lambda e: None)
        self.load_events()

    def _drop_pending_deleted(self):
        if self._task_deletes:
            self.tasks = [x for x in self.tasks if x["id"] not in self._task_deletes]
            self.done_tasks = [x for x in self.done_tasks if x["id"] not in self._task_deletes]

    def add_task(self, text, due=None, prio=2, rep=None):
        b = {"text": text, "prio": prio, "today": iso_day()}
        if due:
            b["due"] = due
        if rep:
            b["repeat"] = rep
        self.run("POST", "api/tasks", b, done=lambda r: self.load_today())

    def update_task(self, tid, fields):
        self.run("PATCH", f"api/tasks/{tid}", fields, done=lambda r: self.load_today(), key=("task", tid))

    def complete_task(self, t):
        self.tasks = [x for x in self.tasks if x["id"] != t["id"]]
        self.emit("today-changed")
        self.update_task(t["id"], {"done": True})

    UNDO_SECONDS = 6

    def delete_task(self, tid):
        """Aufgabe sofort ausblenden, auf dem Server erst nach der Rueckgaengig-Frist loeschen (Toast "Rueckgaengig")"""
        t = next((x for x in self.tasks + self.done_tasks if x["id"] == tid), {"id": tid})
        self.tasks = [x for x in self.tasks if x["id"] != tid]
        self.done_tasks = [x for x in self.done_tasks if x["id"] != tid]
        if tid in self._task_deletes:
            return
        src = GLib.timeout_add_seconds(self.UNDO_SECONDS, self._delete_task_now, tid)
        self._task_deletes[tid] = (t, src)
        self.emit("today-changed")
        self.emit("task-deleted", tid, t.get("text") or "")

    def undo_delete_task(self, tid):
        t, src = self._task_deletes.pop(tid, (None, 0))
        if src:
            GLib.source_remove(src)
        if t is not None:
            (self.done_tasks if t.get("done") else self.tasks).append(t)
            self.emit("today-changed")
            self.load_today()

    def _delete_task_now(self, tid):
        t, src = self._task_deletes.pop(tid, (None, 0))
        if t is not None:
            self.run("DELETE", f"api/tasks/{tid}", done=lambda r: self.load_today(), key=("task", tid))
        return False

    def flush_task_deletes(self):
        """Beim Schliessen: vorgemerkte Loeschungen jetzt senden"""
        for tid, (t, src) in list(self._task_deletes.items()):
            GLib.source_remove(src)
            self._delete_task_now(tid)

    # --- Termine --------------------------------------------------------------------------------
    @staticmethod
    def ev_path(days):
        t0 = _dt.date.today()
        return f"api/calendar/events?start={t0.isoformat()}&end={(t0 + _dt.timedelta(days=days)).isoformat()}"

    def cached_events(self, days):
        c = self.cached(self.ev_path(days))
        return (c or {}).get("events") if isinstance(c, dict) else None

    def fetch_events(self, days, done, fresh=False):
        base = self.ev_path(days)

        def work():
            j = client.get(base + ("&fresh=1" if fresh else ""))
            try:
                config.atomic_write(self._cache_path(base), json.dumps(j))
            except OSError:
                pass
            return j.get("events", [])
        bg(work, done=done, error=lambda e: None)

    def load_events(self):
        if not self.events:
            c = self.cached_events(1)
            if c:
                self.events = c
                self.emit("today-changed")

        def fin(ev):
            self.events = ev
            self.emit("today-changed")
        self.fetch_events(1, fin)

    def cal_targets(self, done):
        def work():
            j = client.get("api/settings/calendars")
            out = []
            for a in j.get("accounts", []):
                for c in a.get("calendars", []):
                    if c.get("show") is False or c.get("writable") is False or not c.get("href"):
                        continue
                    name = (a.get("label") or a.get("username") or "") + " · " + (c.get("name") or "")
                    out.append({"account": a.get("id"), "cal": c["href"], "name": name, "color": c.get("color")})
            return out
        bg(work, done=done, error=lambda e: done([]))

    def page_url(self, pid):
        return f"{config.settings.server}/notes#{pid}"


store = Store()


def watch(widget, signal, cb):
    """Store-Signal nur verbunden, solange das Widget sichtbar ist (GTK4 raeumt sonst nicht auf)"""
    state = {"h": None}

    def on_map(*_):
        if state["h"] is None:
            state["h"] = store.connect(signal, lambda *a: cb())

    def on_unmap(*_):
        if state["h"] is not None:
            store.disconnect(state["h"])
            state["h"] = None
    widget.connect("map", on_map)
    widget.connect("unmap", on_unmap)
