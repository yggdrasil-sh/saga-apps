"""pytest: Linux-App mit nachgebautem Server (tests/mockserver.py) - ohne echten Server, ohne Testrechner.
Laeuft mit Xvfb und eigenem D-Bus: sh tests/run-container.sh unit  (oder: dbus-run-session xvfb-run pytest tests/)"""
import os
import shutil
import sys
import tempfile
import weakref

# Vor dem ersten Import der App: eigene Ordner (Einstellungen, Zwischenspeicher, Schluessel), nie der Schluesselbund
_HOME = tempfile.mkdtemp(prefix="hk-pytest-")
for k, sub in (("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache"), ("XDG_DATA_HOME", "data")):
    os.environ[k] = os.path.join(_HOME, sub)
os.environ["HK_NO_KEYRING"] = "1"
os.environ["HK_TEST"] = "pytest"                  # eigene App-Instanz (NON_UNIQUE)
os.environ.setdefault("GSK_RENDERER", "cairo")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import gi  # noqa: E402

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
import pytest  # noqa: E402

import mockserver  # noqa: E402


from hk_helpers import idle, pump  # noqa: E402


@pytest.fixture
def srv():
    """frischer Zustand + Schein-Server https://saga.test (angemeldet)"""
    from hlidskjalf import config
    from hlidskjalf.api import client
    from hlidskjalf.store import store
    idle()
    with config.settings.lock:
        config.settings.d.clear()
        config.settings._write()
    shutil.rmtree(config.DATA_DIR, ignore_errors=True)
    os.makedirs(config.DATA_DIR, mode=0o700, exist_ok=True)
    store.clear_cache()
    store.pages, store.archived, store.by_id, store.tasks, store.done_tasks, store.events = [], [], {}, [], [], []
    store.recent = []
    store.needs_login = False
    store._refresh_next = None
    store._task_deletes = {}
    store.docs = weakref.WeakSet()
    store.server_version = None
    store.fed_shared, store.fed_unreachable = [], []
    store._set_shared([])
    from hlidskjalf.assistant import ai
    from hlidskjalf.chat import _mem, chat
    from hlidskjalf.live import live
    from hlidskjalf.notifications import notifications
    live.reset()
    chat.clear()
    _mem.clear()
    notifications.reset()
    ai.status, ai.known = {}, False
    client.set_cookies("", {})
    s = mockserver.install()
    yield s
    live.reset()
    idle()


@pytest.fixture(scope="session")
def app():
    from hlidskjalf.main import App
    a = App()
    a.register(None)                               # startup (CSS, Aktionen) ohne Hauptschleife
    return a


@pytest.fixture
def win(app, srv):
    from hlidskjalf.window import MainWindow
    w = MainWindow(app)
    w.present()
    pump(None, 0.3)
    idle()
    yield w
    w._close_ok = True
    d = w.get_visible_dialog() if hasattr(w, "get_visible_dialog") else None
    if d is not None:
        d.force_close()
    w.destroy()
    pump(None, 0.1)
