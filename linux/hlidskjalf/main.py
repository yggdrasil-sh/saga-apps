"""Start: Adw.Application (eine Instanz), hlidskjalf://-Links (Anmeldung, Seiten), Testmodus fuer Bildschirmfotos."""
import os
import sys

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

from . import APP_ID  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


class App(Adw.Application):
    def __init__(self):
        flags = Gio.ApplicationFlags.HANDLES_OPEN
        if os.environ.get("HK_TEST"):              # Test: eigene Instanz neben einer laufenden App
            flags |= Gio.ApplicationFlags.NON_UNIQUE
        super().__init__(application_id=APP_ID, flags=flags)
        self.win = None
        self._pending = None
        GLib.set_application_name("Saga")
        # Dock-Aktionen der Desktop-Datei (statt hlidskjalf://new, das eine Webseite ohne Rueckfrage ausloesen koennte)
        self.add_main_option("new-page", 0, GLib.OptionFlags.NONE, GLib.OptionArg.NONE, "New page", None)
        self.add_main_option("new-task", 0, GLib.OptionFlags.NONE, GLib.OptionArg.NONE, "New task", None)

    def do_handle_local_options(self, options):
        d = options.end().unpack()
        act = next((a for a in ("new-page", "new-task") if d.get(a)), None)
        if act is None:
            return -1
        try:
            self.register(None)
        except GLib.Error:
            return 1
        if self.get_is_remote():                              # laeuft schon: dort ausfuehren, hier beenden
            self.activate_action(act, None)
            return 0
        self._pending = act                                   # erste Instanz: nach dem Start ausfuehren
        return -1

    def do_startup(self):
        Adw.Application.do_startup(self)
        prov = Gtk.CssProvider()
        prov.load_from_path(os.path.join(HERE, "style.css"))
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), prov, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        for d in (os.path.join(HERE, "icons"), os.path.join(HERE, "..", "data", "icons")):
            if os.path.isdir(d):
                Gtk.IconTheme.get_for_display(Gdk.Display.get_default()).add_search_path(d)
        q = Gio.SimpleAction.new("quit", None)
        q.connect("activate", lambda *_: self.win.close() if self.win else self.quit())   # close: wartet aufs Speichern
        self.add_action(q)
        self.set_accels_for_action("app.quit", ["<Control>q"])
        for name, fn in (("new-page", lambda: self.win and self.win.new_page()),
                         ("new-task", lambda: self.win and self.win.open_link("hlidskjalf://new-task"))):
            a = Gio.SimpleAction.new(name, None)
            a.connect("activate", lambda *_, fn=fn: (self.do_activate(), fn()))
            self.add_action(a)

    def do_activate(self):
        if self.win is None:
            from .window import MainWindow
            try:
                self.win = MainWindow(self)
            except Exception:                             # Test: nicht haengen bleiben
                if os.environ.get("HK_TEST"):
                    import traceback
                    traceback.print_exc()
                    self.quit()
                    return
                raise
            if os.environ.get("HK_TEST"):
                GLib.timeout_add(1500, self._run_test)
        self.win.present()
        if self._pending:
            act, self._pending = self._pending, None
            GLib.idle_add(lambda: (self.activate_action(act, None), False)[1])

    def do_open(self, files, n, hint):
        self.do_activate()
        for f in files:
            self.win.open_link(f.get_uri())

    def _run_test(self):
        """Nur fuer Tests: HK_TEST=skript.py wird mit Zugriff auf Fenster und Store ausgefuehrt (testing.py liegt nur
        im Quellordner, nicht im RPM/Flatpak)"""
        try:
            from . import testing
        except ImportError:
            print("HK_TEST: testing.py fehlt (nur im Quellordner vorhanden)", file=sys.stderr)
            self.quit()
            return False
        testing.run(self.win, os.environ["HK_TEST"])
        return False


def main():
    if "--search-provider" in sys.argv:                  # GNOME-Suche (per D-Bus gestartet)
        from .searchprovider import Provider
        return Provider().run()
    app = App()
    return app.run(sys.argv)


if __name__ == "__main__":
    sys.exit(main())
