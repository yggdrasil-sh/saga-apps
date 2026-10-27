"""Kachel-Symbole (Server 2.5): icon_url aus /api/services bevorzugt (auch automatische Symbole), sonst wie bisher
/api/services/icon/<name>, sonst Anfangsbuchstabe"""
from gi.repository import Gtk

from hk_helpers import pump


def walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += walk(c)
        c = c.get_next_sibling()
    return out


def test_icon_path_rules():
    from hlidskjalf.dialogs import service_icon_path
    assert service_icon_path("", "/api/services/favicon/bm3?h=ab12") == "/api/services/favicon/bm3?h=ab12"
    assert service_icon_path("nextcloud", "/api/services/icon/nextcloud") == "/api/services/icon/nextcloud"
    assert service_icon_path("nextcloud") == "/api/services/icon/nextcloud"          # aelterer Server ohne icon_url
    assert service_icon_path("", "") is None and service_icon_path(None) is None
    assert service_icon_path("", "https://evil.example/x.png") is None                 # nur der eigene Server
    assert service_icon_path("../x") is None


def test_services_load_icon_url(win, srv):
    from hlidskjalf.dialogs import services_screen
    from hlidskjalf.images import ImageView
    srv.services = [{"name": "Video", "host": "youtube.com", "url": "https://youtube.com/", "icon": "", "category": "Medien",
                     "own": True, "key": "bm7", "icon_url": "/api/services/favicon/bm7?h=0123456789"},
                    {"name": "Wolke", "host": "cloud.example.org", "url": "https://cloud.example.org", "icon": "nextcloud",
                     "category": "Medien"}]
    srv.log.clear()
    sw = services_screen(win)
    got = lambda: [u for (_m, u, _b, _c) in srv.log if "/api/services/" in u]   # noqa: E731
    assert pump(lambda: any("favicon/bm7" in u for u in got()) and any("icon/nextcloud" in u for u in got()), 5), got()
    assert pump(lambda: sum(1 for w in walk(sw) if isinstance(w, ImageView)) >= 2, 5)
    assert not any(isinstance(w, Gtk.Label) and w.get_label() == "V" for w in walk(sw))  # Bild statt Anfangsbuchstabe


# --- 2.5: Lesezeichen-Favicons, Rueckfall direkt vom Geraet (icon_direct) ----------------------------------------------
BM = "https://shop.example.org/start?x=1"
DIRECT = ["https://shop.example.org/apple-touch-icon.png", "https://shop.example.org/favicon.ico"]
PNG8 = b"\x89PNG\r\n\x1a\n"


def _ico():
    import io
    from PIL import Image
    out = io.BytesIO()
    Image.new("RGBA", (32, 32), (20, 120, 200, 255)).save(out, "ICO")
    return out.getvalue()


def test_direct_candidates():
    from hlidskjalf.favicon import direct_candidates as c
    assert c(DIRECT, BM) == DIRECT                                                      # Reihenfolge wie geliefert
    assert c(None, BM) == [] and c([], BM) == []                                        # aelterer Server: wie bisher
    assert c(["https://andere.example.org/favicon.ico"], BM) == []                      # nur Host des Lesezeichens
    assert len(c(["https://SHOP.example.org/favicon.ico"], BM)) == 1
    assert c(["http://shop.example.org/favicon.ico"], BM) == []                         # https-Lesezeichen: kein http
    assert len(c(["http://shop.example.org/f.ico", "https://shop.example.org/f.ico"], "http://shop.example.org/")) == 2
    assert c(["ftp://shop.example.org/f.ico", "data:image/png;base64,AA", "javascript:alert(1)", "/favicon.ico", 7], BM) == []
    assert c(["https://u:p@shop.example.org/favicon.ico"], BM) == []                    # keine Anmeldedaten
    assert c(["https://shop.example.org:8443/favicon.ico"], BM) == []
    assert len(c(["https://shop.example.org:443/favicon.ico"], BM)) == 1
    assert c(DIRECT, "mailto:a@example.org") == [] and c(DIRECT, None) == []
    assert len(c([f"https://shop.example.org/i{i}.png" for i in range(5)], BM)) == 3    # hoechstens 3
    assert len(c([DIRECT[1], DIRECT[1]], BM)) == 1


def test_order_and_limits():
    from hlidskjalf import favicon as f
    p = f.plan("/api/services/favicon/k", DIRECT)
    assert p == [("server", "/api/services/favicon/k"), ("direct", DIRECT[0]), ("direct", DIRECT[1])]
    assert f.plan(None, []) == []
    tried = []
    r = f.first(p, lambda s: (tried.append(s), PNG8 if s == ("direct", DIRECT[1]) else None)[1])
    assert r == (("direct", DIRECT[1]), PNG8) and len(tried) == 3
    tried.clear()
    f.first(p, lambda s: (tried.append(s), PNG8)[1])
    assert tried == [p[0]]                                                              # Server klappt -> kein Geraete-Abruf
    assert f.first(p, lambda s: None) is None
    for d in (PNG8, b"\x00\x00\x01\x00\x01\x00", b"\xff\xd8\xff\xe0", b"GIF89a", b"RIFF\0\0\0\0WEBPVP8 "):
        assert f.is_raster(d)
    for d in (b'<svg xmlns="http://www.w3.org/2000/svg"/>', b"<!doctype html>", b"RIFF\0\0\0\0WAVE", b"\x89", None):
        assert not f.is_raster(d)
    assert f.TIMEOUT <= 5 and f.MAX_BYTES == 524288
    assert f.accept(200, "application/octet-stream", None, PNG8)
    assert not f.accept(503, "image/png", None, PNG8)
    assert not f.accept(200, "image/svg+xml", None, PNG8)
    assert not f.accept(200, "text/html", None, PNG8)
    assert not f.accept(200, "image/png", None, PNG8 + bytes(f.MAX_BYTES))
    assert f.accept(200, "image/png", None, PNG8 + bytes(f.MAX_BYTES - len(PNG8)))
    assert not f.accept(200, "image/png", f.MAX_BYTES + 1, PNG8)
    assert f.reject_early(200, "image/png", 10_000_000) and f.reject_early(404, "image/png", None)
    assert not f.reject_early(200, None, None)
    assert f.redirect_allowed("https://a.example/x", "https://cdn.example/y")
    assert not f.redirect_allowed("https://a.example/x", "http://a.example/x")
    assert f.redirect_allowed("http://a.example/x", "https://a.example/x")
    assert not f.redirect_allowed("https://a.example/x", "file:///etc/passwd")


def test_cache_decision():
    from hlidskjalf.favicon import cache_decision as d, cache_key
    t0 = 1_800_000_000
    assert d(True, t0, t0 + 6 * 86400) == "use"
    assert d(True, t0, t0 + 7 * 86400 + 1) == "reload"
    assert d(False, t0, t0 + 59 * 60) == "missing"
    assert d(False, t0, t0 + 3601) == "reload"
    assert d(True, t0, t0 - 10) == "reload"                                             # Uhr verstellt
    assert cache_key(DIRECT) == cache_key(DIRECT) != cache_key(DIRECT[::-1])
    assert cache_key([]) == "cbf29ce484222325"                                          # wie iOS/Android (FNV-1a 64)


def _shop(icon_direct=DIRECT, key="bm9"):
    b = {"name": "Shop", "host": "shop.example.org", "url": BM, "icon": "", "category": "Lesezeichen", "own": True,
         "key": key, "icon_url": f"/api/services/favicon/{key}?h=0123456789"}
    if icon_direct is not None:
        b["icon_direct"] = icon_direct
    return b


def _shown(sw):
    from hlidskjalf.images import ImageView
    return sum(1 for w in walk(sw) if isinstance(w, ImageView))


def test_bookmark_falls_back_to_device(win, srv):
    """Server 503 (Bot-Schutz) -> apple-touch-icon 404 -> favicon.ico vom Geraet; Dienst ohne icon_direct unberuehrt"""
    from hlidskjalf.dialogs import services_screen
    srv.fail["GET api/services/favicon/bm6"] = 503
    srv.sites = {DIRECT[0]: (404, "text/html", b"<html>nope</html>"), DIRECT[1]: (200, "image/x-icon", _ico())}
    srv.services = [_shop(key="bm6"), {"name": "Wolke", "host": "cloud.example.org", "url": "https://cloud.example.org",
                              "icon": "", "category": "Lesezeichen"}]
    sw = services_screen(win)
    assert pump(lambda: _shown(sw) >= 1, 5)
    assert srv.direct_log == DIRECT                                                    # der Reihe nach, nur das Lesezeichen
    assert any(isinstance(w, Gtk.Label) and w.get_label() == "W" for w in walk(sw))  # Dienst: Buchstabe
    assert not any(isinstance(w, Gtk.Label) and w.get_label() == "S" for w in walk(sw))


def test_server_icon_wins_no_device_fetch(win, srv):
    from hlidskjalf.dialogs import services_screen
    srv.sites = {DIRECT[1]: (200, "image/x-icon", _ico())}
    srv.services = [_shop()]
    sw = services_screen(win)
    assert pump(lambda: _shown(sw) >= 1, 5)
    pump(None, 0.3)
    assert srv.direct_log == []


def test_older_server_without_icon_direct(win, srv):
    from hlidskjalf.dialogs import services_screen
    srv.fail["GET api/services/favicon/bm8"] = 503        # eigener Schluessel: bm9 liegt schon im Bild-Speicher
    srv.sites = {DIRECT[1]: (200, "image/x-icon", _ico())}
    srv.services = [_shop(icon_direct=None, key="bm8")]
    sw = services_screen(win)
    assert pump(lambda: any(u for (_m, u, _b, _c) in srv.log if "favicon/bm8" in u), 5)
    pump(None, 0.5)
    assert srv.direct_log == [] and _shown(sw) == 0
    assert any(isinstance(w, Gtk.Label) and w.get_label() == "S" for w in walk(sw))


def test_rejected_answers_and_cache(srv):
    """SVG, HTML, Uebergroesse -> Fehlversuch (1 h gemerkt, kein neuer Abruf); Treffer kommt danach von der Platte"""
    import os
    from hlidskjalf import config, favicon
    opens = lambda d: True                                                              # noqa: E731
    srv.sites = {DIRECT[0]: (200, "image/svg+xml", b"<svg/>"), DIRECT[1]: (200, "image/png", PNG8 + bytes(favicon.MAX_BYTES))}
    assert favicon.load_blocking(DIRECT, opens) is None
    assert srv.direct_log == DIRECT
    srv.sites[DIRECT[1]] = (200, "image/png", PNG8)
    assert favicon.load_blocking(DIRECT, opens) is None and len(srv.direct_log) == 2   # Fehlversuch gemerkt
    later = __import__("time").time() + 3601
    assert favicon.load_blocking(DIRECT, opens, now=later) == PNG8 and len(srv.direct_log) == 4
    favicon.forget_memory()
    srv.sites.clear()
    assert favicon.load_blocking(DIRECT, opens) == PNG8 and len(srv.direct_log) == 4   # von der Platte, ohne Netz
    key = favicon.cache_key(DIRECT)
    assert os.path.exists(os.path.join(config.CACHE_DIR, "favicons", key + ".img"))
    favicon.forget_memory()
    assert favicon.load_blocking(DIRECT, opens, now=__import__("time").time() + 8 * 86400) is None   # nach 7 Tagen neu
    assert len(srv.direct_log) == 6
    favicon.forget_memory()
    assert favicon.load_blocking(DIRECT, lambda d: False, now=__import__("time").time() + 8 * 86400 + 3601) is None


def test_http_get_real_socket():
    """Echter Abruf gegen einen lokalen Testserver: keine Cookies/Anmeldung, Groessen- und Zeitlimit, Weiterleitung"""
    import http.server
    import threading
    import time
    from hlidskjalf import favicon
    seen = []

    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            seen.append((self.path, self.headers.get("Cookie"), self.headers.get("Authorization")))
            if self.path == "/big.png":
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.end_headers()                                                     # ohne Content-Length
                self.wfile.write(PNG8 + bytes(favicon.MAX_BYTES + 10))
            elif self.path == "/slow.png":
                time.sleep(2)
                self.send_response(200)
                self.end_headers()
            elif self.path == "/moved":
                self.send_response(302)
                self.send_header("Location", "/favicon.ico")
                self.send_header("Set-Cookie", "track=1")
                self.end_headers()
            elif self.path == "/auth":
                self.send_response(401)
                self.send_header("WWW-Authenticate", 'Basic realm="x"')
                self.end_headers()
            else:
                ico = _ico()
                self.send_response(200)
                self.send_header("Content-Type", "image/x-icon")
                self.send_header("Content-Length", str(len(ico)))
                self.end_headers()
                self.wfile.write(ico)

    s = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=s.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{s.server_address[1]}"
    try:
        st, ct, ln, data = favicon.http_get(base + "/moved")
        assert st == 200 and favicon.accept(st, ct, ln, data) and favicon.is_raster(data)
        assert [p for p, _c, _a in seen] == ["/moved", "/favicon.ico"]
        assert all(c is None and a is None for _p, c, a in seen)                        # kein Cookie aus Set-Cookie, keine Anmeldung
        st, ct, ln, data = favicon.http_get(base + "/big.png")
        assert len(data) == favicon.MAX_BYTES + 1 and not favicon.accept(st, ct, ln, data)   # nur bis Grenze gelesen
        assert favicon.http_get(base + "/auth")[0] == 401
        t = time.monotonic()
        assert favicon.http_get(base + "/slow.png", timeout=0.5)[0] == 0
        assert time.monotonic() - t < 1.5
        assert favicon.http_get("http://127.0.0.1:1/favicon.ico")[0] == 0               # nicht erreichbar
    finally:
        s.shutdown()
