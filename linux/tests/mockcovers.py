"""Schein-Server: Titelbild-Galerie (Saga 2.5, GET api/covers wie saga/covers.py, vereinfacht).
s.covers_api: True = Liste, sonst HTTP-Code (Standard 404 = aelterer Server, 403 = abgeschaltet).
Bilder unter covers/<id>.jpg liefert der Schein-Server als farbige Flaeche (je ID eine Farbe)."""
import hashlib
import io


def init(s):
    s.covers_api = 404
    s.covers_log = []                 # Accept-Language der Listen-Anfragen (hier: Zahl der Anfragen)
    s.covers = [
        {"id": "met-wave", "url": "/covers/met-wave.jpg", "thumb": "/covers/met-wave.jpg?w=384", "title": "Under the Wave off Kanagawa",
         "artist": "Katsushika Hokusai", "date": "ca. 1830–32", "source_url": "https://www.metmuseum.org/art/collection/search/45434",
         "source": "The Metropolitan Museum of Art", "group": "japan", "pos": 40, "builtin": True},
        {"id": "met-fuji", "url": "/covers/met-fuji.jpg", "thumb": "/covers/met-fuji.jpg?w=384", "title": "Fine Wind, Clear Morning",
         "artist": "Katsushika Hokusai", "date": "ca. 1830–32", "source_url": "https://www.metmuseum.org/art/collection/search/36491",
         "source": "The Metropolitan Museum of Art", "group": "japan", "pos": 60, "builtin": True},
        {"id": "saga-fjord", "url": "/covers/saga-fjord.jpg", "thumb": "/covers/saga-fjord.jpg?w=384", "title": "Fjord im Nebel",
         "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "nordic", "pos": 50, "builtin": True},
        {"id": "saga-aurora", "url": "/covers/saga-aurora.jpg", "thumb": "/covers/saga-aurora.jpg?w=384", "title": "Nordlicht",
         "artist": "", "date": "", "source_url": "", "source": "KI-generiert für Saga", "group": "nordic", "pos": 30, "builtin": True},
        {"id": "eigen-1", "url": "/covers/eigen-1.jpg", "thumb": "/covers/eigen-1.jpg?w=384", "title": "Hausbild",
         "artist": "Admin", "date": "2026", "source_url": "javascript:alert(1)", "source": "", "group": "", "pos": 50, "builtin": False},
        {"id": "kaputt", "url": "/files/kaputt.jpg", "title": "Kein Galerie-Pfad"},
    ]
    s.cover_groups = [{"id": "nordic", "label": "Nordisch"}, {"id": "japan", "label": "Japan"}]


def handle(s, method, path, q):
    if path == "api/covers" and method == "GET":
        s.covers_log.append(q)
        if s.covers_api is not True:
            return s._reply(s.covers_api, {"error": "not found" if s.covers_api == 404 else "forbidden"})
        return s._reply(200, {"covers": s.covers, "groups": s.cover_groups, "license": "CC0 / public domain",
                              "source": "The Metropolitan Museum of Art (Open Access)", "random_cover": True})
    return None


def image(cid, w=None):
    """JPEG je ID in eigener Farbe (oben hell, unten dunkel - damit der Ausschnitt nach pos sichtbar ist)"""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return b""
    h = hashlib.md5(cid.encode()).digest()
    base = (60 + h[0] % 160, 60 + h[1] % 160, 60 + h[2] % 160)
    W = int(w) if w and str(w).isdigit() else 1200
    H = W * 4 // 7
    im = Image.new("RGB", (W, H), base)
    d = ImageDraw.Draw(im)
    for y in range(0, H, max(1, H // 24)):
        f = y / H
        c = tuple(int(v * (1.25 - f)) if v * (1.25 - f) < 255 else 255 for v in base)
        d.rectangle((0, y, W, y + H // 24), fill=c)
    d.ellipse((W * 0.6, H * 0.15, W * 0.8, H * 0.15 + W * 0.2), fill=(250, 240, 210))
    out = io.BytesIO()
    im.save(out, "JPEG", quality=80)
    return out.getvalue()
