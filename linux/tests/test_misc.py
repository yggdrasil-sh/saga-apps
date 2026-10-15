"""Kleinere Bausteine: Einstellungen aus mehreren Threads, Bilder-Zwischenspeicher, Entprellen, Ursprung von Adressen."""
import json
import os
import threading

from hk_helpers import pump


def test_settings_threadsafe(srv):
    """N2: gleichzeitige Schreiber (Hintergrund-Threads) zerstoeren settings.json nicht"""
    from hlidskjalf import config
    errs = []

    def writer(n):
        try:
            for i in range(50):
                config.settings.set(f"k{n}", i)
        except Exception as e:  # noqa: BLE001
            errs.append(e)
    ts = [threading.Thread(target=writer, args=(n,)) for n in range(6)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert not errs
    with open(os.path.join(config.CONFIG_DIR, "settings.json")) as f:
        d = json.load(f)
    assert all(d[f"k{n}"] == 49 for n in range(6))
    assert not [x for x in os.listdir(config.CONFIG_DIR) if x.startswith(".tmp-")]


def test_set_soon_writes_once(srv):
    """N10: beim Ziehen nur im Speicher, auf die Platte nach kurzer Ruhe"""
    from hlidskjalf import config
    path = os.path.join(config.CONFIG_DIR, "settings.json")
    before = os.stat(path).st_mtime_ns
    for w in range(300, 400, 5):
        config.settings.set_soon("peek_width", w)
    assert config.settings.get("peek_width") == 395
    assert os.stat(path).st_mtime_ns == before
    pump(lambda: os.stat(path).st_mtime_ns != before, 2)
    with open(path) as f:
        assert json.load(f)["peek_width"] == 395


def test_image_cache_limits(srv):
    """M14: Grenze je Eintrag und gesamt; Platten-Zwischenspeicher wird aufgeraeumt"""
    from hlidskjalf import config, images

    class D:
        def __init__(self, cost):
            self.cost = cost
    images._MEM.clear()
    images._mem_size = 0
    images._remember("riesig", D(images._ENTRY_LIMIT + 1))
    assert "riesig" not in images._MEM
    for i in range(10):
        images._remember(f"b{i}", D(images._MEM_LIMIT // 4))
    assert images._mem_size <= images._MEM_LIMIT
    d = os.path.join(config.CACHE_DIR, "files")
    for i in range(5):
        with open(os.path.join(d, f"f{i}"), "wb") as f:
            f.write(b"x" * 1000)
        os.utime(os.path.join(d, f"f{i}"), (1000 + i, 1000 + i))
    assert images.prune_disk_cache(limit=2500, max_age=10 ** 12) == 3
    assert sorted(os.listdir(d)) == ["f3", "f4"]


def test_clear_cache_keeps_0700(srv):
    """N4"""
    from hlidskjalf import config
    from hlidskjalf.store import store
    store.clear_cache()
    assert os.stat(config.CACHE_DIR).st_mode & 0o777 == 0o700


def test_debounce(srv):
    from hlidskjalf.util import Debounce
    n = []
    d = Debounce(50, lambda: n.append(1))
    for _ in range(10):
        d()
    pump(lambda: n, 1)
    pump(None, 0.15)
    assert n == [1]


def test_origin():
    from hlidskjalf.config import origin
    assert origin("https://Saga.Example.org/notes#1") == "https://saga.example.org"
    assert origin("https://saga.example.org:8443/") == "https://saga.example.org:8443"
    assert origin("hlidskjalf://pair") == ""
    assert origin("javascript:alert(1)") == ""


def test_unreachable_text_without_raw_errno():
    """BUG-113: keine rohe Systemmeldung ("[Errno 111] Connection refused") in der Oberflaeche; Zertifikatsfehler behalten
    ihre Einzelheit"""
    import ssl
    import urllib.error
    from hlidskjalf.api import _unreachable
    t = _unreachable(urllib.error.URLError(ConnectionRefusedError(111, "Connection refused")))
    assert t == "Server not reachable" and "Errno" not in t
    assert _unreachable(ConnectionResetError(104, "Connection reset by peer")) == "Server not reachable"
    c = _unreachable(urllib.error.URLError(ssl.SSLCertVerificationError("certificate verify failed")))
    assert c.startswith("Server not reachable: ") and "certificate" in c


def test_chats_entry_stays_while_offline(srv):
    """BUG-113: hatte der Server Chats, bleibt "Chats" in der Seitenleiste, solange der Status offline unbekannt ist"""
    from hlidskjalf import config
    from hlidskjalf.chat import chat
    chat.status = "ready"
    chat._remember_available(config.settings.server)
    chat.status = "unknown"
    assert chat.shown
    chat.status = "off"
    chat._remember_available(config.settings.server)
    chat.status = "unknown"
    assert not chat.shown
