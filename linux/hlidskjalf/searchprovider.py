"""GNOME-Suche (Aktivitaeten): Seiten aus dem Zwischenspeicher finden - wie Spotlight in der Mac-App.
Wird per D-Bus gestartet (hlidskjalf --search-provider) und beendet sich nach einer Minute ohne Anfragen."""
import json
import os
import sys

from gi.repository import Gio, GLib

from . import APP_ID
from .config import CACHE_DIR
from .util import norm

BUS = APP_ID + ".SearchProvider"
PATH = "/" + APP_ID.replace(".", "/") + "/SearchProvider"             # /io/github/yggdrasil_sh/Saga/SearchProvider
XML = """<node><interface name="org.gnome.Shell.SearchProvider2">
<method name="GetInitialResultSet"><arg type="as" name="terms" direction="in"/><arg type="as" direction="out"/></method>
<method name="GetSubsearchResultSet"><arg type="as" name="prev" direction="in"/><arg type="as" name="terms" direction="in"/><arg type="as" direction="out"/></method>
<method name="GetResultMetas"><arg type="as" name="ids" direction="in"/><arg type="aa{sv}" direction="out"/></method>
<method name="ActivateResult"><arg type="s" name="id" direction="in"/><arg type="as" name="terms" direction="in"/><arg type="u" name="ts" direction="in"/></method>
<method name="LaunchSearch"><arg type="as" name="terms" direction="in"/><arg type="u" name="ts" direction="in"/></method>
</interface></node>"""


class Provider:
    def __init__(self):
        self.loop = GLib.MainLoop()
        self.pages = {}
        self._loaded_at = 0
        self._idle = 0

    def load(self):
        p = os.path.join(CACHE_DIR, "api_notes")
        try:
            m = os.path.getmtime(p)
            if m == self._loaded_at:
                return
            with open(p, encoding="utf-8") as f:
                lst = json.load(f)
            self.pages = {x["id"]: x for x in lst if not x.get("archived")}
            self._loaded_at = m
        except (OSError, ValueError):
            self.pages = {}

    def path(self, pid):
        out, cur, n = [], (self.pages.get(pid) or {}).get("parent_id"), 0
        while cur in self.pages and n < 10:
            out.insert(0, self.pages[cur].get("title") or "Unbenannt")
            cur, n = self.pages[cur].get("parent_id"), n + 1
        return " / ".join(out)

    def search(self, terms):
        self.load()
        ts = [norm(t) for t in terms if t.strip()]
        if not ts or sum(len(t) for t in ts) < 2:
            return []
        hits = []
        for p in self.pages.values():
            t = norm(p.get("title") or "")
            if all(x in t for x in ts):
                hits.append((0 if t.startswith(ts[0]) else 1, -(p.get("updated") or 0), str(p["id"])))
        hits.sort()
        return [h[2] for h in hits[:12]]

    def _tick(self):
        if self._idle:
            GLib.source_remove(self._idle)
        self._idle = GLib.timeout_add_seconds(60, lambda: (self.loop.quit(), False)[1])

    def call(self, conn, sender, path, iface, method, params, inv):
        self._tick()
        a = params.unpack()
        if method == "GetInitialResultSet":
            inv.return_value(GLib.Variant("(as)", (self.search(a[0]),)))
        elif method == "GetSubsearchResultSet":
            inv.return_value(GLib.Variant("(as)", (self.search(a[1]),)))
        elif method == "GetResultMetas":
            metas = []
            for i in a[0]:
                p = self.pages.get(int(i)) if i.isdigit() else None
                if not p:
                    continue
                ic = p.get("icon") or ""
                emoji = ic if ic and not ic.startswith("/") and len(ic) <= 4 else ("🗃️" if p.get("kind") == "database" else "📄")
                metas.append({"id": GLib.Variant("s", i), "name": GLib.Variant("s", f"{emoji} {p.get('title') or 'Unbenannt'}"),
                              "description": GLib.Variant("s", self.path(p["id"]) or "Saga"),
                              "gicon": GLib.Variant("s", APP_ID)})
            inv.return_value(GLib.Variant("(aa{sv})", (metas,)))
        elif method == "ActivateResult":
            self._launch(f"hlidskjalf://page/{a[0]}")
            inv.return_value(None)
        elif method == "LaunchSearch":
            self._launch("hlidskjalf://today")
            inv.return_value(None)

    @staticmethod
    def _launch(uri):
        if os.path.exists("/.flatpak-info"):
            # Flatpak: Kindprozesse enden mit diesem Dienst (Sandbox) -> nach der Antwort an die Shell
            # diesen Prozess durch die App ersetzen (laeuft sie schon, uebergibt sie den Link und endet)
            def run_app():
                Gio.bus_get_sync(Gio.BusType.SESSION, None).flush_sync(None)    # Antwort zuerst zustellen
                os.execv(sys.argv[0], [sys.argv[0], uri])
            GLib.idle_add(run_app)
            return
        app = Gio.DesktopAppInfo.new(APP_ID + ".desktop")
        if app:
            app.launch_uris([uri], None)

    def run(self):
        node = Gio.DBusNodeInfo.new_for_xml(XML)

        def acquired(conn, name):
            conn.register_object(PATH, node.interfaces[0], self.call, None, None)
        Gio.bus_own_name(Gio.BusType.SESSION, BUS, Gio.BusNameOwnerFlags.NONE, acquired, None, lambda *_: self.loop.quit())
        self._tick()
        self.loop.run()
        return 0
