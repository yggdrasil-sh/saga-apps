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
        GLib.set_application_name("Saga")

    def do_startup(self):
        Adw.Application.do_startup(self)
        prov = Gtk.CssProvider()
        prov.load_from_path(os.path.join(HERE, "style.css"))
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), prov, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        for d in (os.path.join(HERE, "icons"), os.path.join(HERE, "..", "data", "icons")):
            if os.path.isdir(d):
                Gtk.IconTheme.get_for_display(Gdk.Display.get_default()).add_search_path(d)
        q = Gio.SimpleAction.new("quit", None)
        q.connect("activate", lambda *_: self.win.close() if self.win else self.quit())
        self.add_action(q)
        self.set_accels_for_action("app.quit", ["<Control>q"])

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

    def do_open(self, files, n, hint):
        self.do_activate()
        for f in files:
            self.win.open_link(f.get_uri())

    def _run_test(self):
        """Nur fuer Tests: HK_TEST=skript.py wird mit Zugriff auf Fenster und Store ausgefuehrt"""
        from . import testing
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
