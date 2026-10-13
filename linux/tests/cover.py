import os
import time
from hlidskjalf import images

# Bild-Datei auf dem eigenen Server (z. B. ein Titelbild): HK_FILE=/files/<datei-id>
FILE = os.environ.get("HK_FILE", "/files/DATEI-ID")

T = {}


def go(g):
    T["t"] = time.time()
    raw = images.fetch_bytes(FILE + "?w=2000")
    print("Bytes:", len(raw), raw[:6], flush=True)
    from PIL import Image
    import io
    im = Image.open(io.BytesIO(raw))
    print("Format:", im.format, im.size, "Bilder:", getattr(im, "n_frames", 1), flush=True)

    def done(d, n=[0]):
        n[0] += 1
        print("Dekodiert nach", round(time.time() - T["t"], 2), "s:", d and (d.w, d.h, len(d.textures), round(d.cost / 1e6), "MB"), flush=True)
    images.load(FILE, done, w=2000)
    g["win"].show_root(("page", 4))


def take(g):
    g["shot"](g["out"] + "/30-cover.png")


steps = [(2500, go), (9000, take)]
