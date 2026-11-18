"""Schein-Server: Saga-Siegel (Saga 2.5, wie saga/siegel.py, vereinfacht).
GET api/siegel liefert die Liste (nur wenn s.siegel_api True, sonst 404 wie aeltere Server); Feature "siegel1" setzt der
Test selbst (s.extra_features). Dateien /siegel/<farbe>/<motiv>.svg: mit ?w= PNG (64/128/256), ohne ?w= SVG-Text.
HK_SIEGEL_DIR (optional, fuer Bildschirmfotos): Ordner mit den echten PNGs <farbe>/<motiv>.png - sonst farbige Kreise."""
import hashlib
import io
import os
import re

FARBEN = {"weinrot": "Weinrot", "smaragd": "Smaragd", "elfenbein": "Elfenbein"}
MOTIVE = ("yggdrasil", "rabe", "drachenboot", "horn", "rune", "buch", "feder", "schriftrolle", "kerze", "laterne", "mond", "sonne",
          "nordlicht", "berg", "wald", "welle", "kompass", "karte", "schluessel", "schild", "hammer", "axt", "krone", "kristall",
          "trank", "feuer", "stern", "herz", "brief", "sanduhr", "zahnrad", "truhe", "server", "terminal", "katze", "teetasse")
NAMEN = {"rabe": "Rabe", "schluessel": "Schlüssel", "teetasse": "Teetasse", "drachenboot": "Drachenboot"}
PATH_RE = re.compile(r"siegel/(weinrot|smaragd|elfenbein)/([a-z]{2,20})\.(svg|png)")
RGB = {"weinrot": (128, 24, 40), "smaragd": (20, 110, 70), "elfenbein": (236, 226, 200)}


def init(s):
    s.siegel_api = True
    s.siegel_log = []                 # Abrufe der Liste (Query) - einmal je Server und Sprache


def listing():
    return {"colors": [{"id": k, "label": v} for k, v in FARBEN.items()],
            "motifs": [{"id": m, "label": NAMEN.get(m, m.capitalize())} for m in MOTIVE],
            "path": "/siegel/{color}/{motif}.svg", "sizes": [64, 128, 256]}


def handle(s, method, path, q):
    if path == "api/siegel" and method == "GET":
        s.siegel_log.append(q)
        if s.siegel_api is not True:
            return s._reply(404, {"error": "not found"})
        return s._reply(200, listing())
    m = PATH_RE.fullmatch(path)
    if m and method == "GET":
        s.file_log.append(path + ("?w=" + q["w"] if q.get("w") else ""))
        if m.group(2) not in MOTIVE:
            return s._reply(404, {"error": "not found"})
        if m.group(3) == "svg" and not q.get("w"):
            return s._reply(200, b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><circle cx="32" cy="32" r="30"/></svg>')
        w = int(q["w"]) if str(q.get("w") or "").isdigit() else 256
        return s._reply(200, image(m.group(1), m.group(2), next((x for x in (64, 128, 256) if w <= x), 256)))
    return None


def image(farbe, motiv, px):
    from PIL import Image, ImageDraw
    d = os.environ.get("HK_SIEGEL_DIR")
    fn = os.path.join(d, farbe, motiv + ".png") if d else ""
    if fn and os.path.isfile(fn):
        im = Image.open(fn).convert("RGBA")
        if im.size[0] != px:
            im = im.resize((px, px), Image.LANCZOS)
    else:
        h = hashlib.md5(motiv.encode()).digest()
        im = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        dr = ImageDraw.Draw(im)
        dr.ellipse((1, 1, px - 2, px - 2), fill=RGB[farbe], outline=(200, 160, 60), width=max(2, px // 20))
        k = px // 4
        dr.rectangle((k + h[0] % k, k + h[1] % k, px - k, px - k), fill=(230, 190, 90))
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()
