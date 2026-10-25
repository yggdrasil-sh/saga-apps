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
