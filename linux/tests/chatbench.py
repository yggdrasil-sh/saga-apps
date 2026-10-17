# Messung (2.1-c): Chat mit 300 Bildnachrichten (synthetische Fotos ~3,7 MB JPEG, 2400 x 1800) mit nachgebautem Server.
#   im Container: HK_OUT=<ordner> sh tests/run-container.sh app:chatbench.py
#     tests/chatbench.cfg (optional): HK_BENCH_TH=0 (Nachrichten ohne Vorschaubild, wie alte Clients), HK_BENCH_N, HK_BENCH_RSS_MAX (MB)
#   Ausgabe: Zeilen "BENCH name=wert" (auch in <HK_OUT>/chatbench.txt) - Zeit bis erster Bildschirm, Ruckler beim
#   Laden/Scrollen, Speicher (RSS).
#   Laeuft mit altem und neuem Stand (nur oeffentliche Wege: Route, Scrollen, "Aeltere laden").
#   Netz nachgestellt: je Anhang 10 ms + Groesse / 50 MB/s (LAN).
import io
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import v2shots as V  # noqa: E402

from gi.repository import GLib  # noqa: E402

# Einstellungen (xrun gibt keine eigenen Umgebungsvariablen weiter): tests/chatbench.cfg, Zeilen NAME=WERT
CFG = dict(os.environ)
try:
    with open(os.path.join(os.path.dirname(os.path.abspath(os.environ["HK_TEST"])), "chatbench.cfg")) as _f:
        CFG.update(x.strip().split("=", 1) for x in _f if "=" in x)
except OSError:
    pass
N = int(CFG.get("HK_BENCH_N", "300"))
WITH_TH = CFG.get("HK_BENCH_TH", "1") == "1"
RSS_MAX = int(CFG.get("HK_BENCH_RSS_MAX", "2500")) * 1024 * 1024
B = {}


def rep(k, v):
    print(f"BENCH {k}={v}", flush=True)
    try:
        with open(os.path.join(os.environ.get("HK_OUT") or "/tmp", "chatbench.txt"), "a") as f:
            f.write(f"{k}={v}\n")
    except OSError:
        pass


def rss():
    with open("/proc/self/status") as f:
        d = dict(line.split(":", 1) for line in f if ":" in line)
    return int(d["VmRSS"].split()[0]) * 1024, int(d["VmHWM"].split()[0]) * 1024


def mb(x):
    return f"{x / 1048576:.0f}MB"


def _photo(seed):
    from PIL import Image
    rnd = __import__("random").Random(seed)
    w, h = 2400, 1800
    small = Image.frombytes("RGB", (400, 300), rnd.randbytes(400 * 300 * 3)).resize((w, h), Image.BICUBIC)
    noise = Image.effect_noise((w, h), 40).convert("RGB")
    im = Image.blend(small, noise, 0.45)
    o = io.BytesIO()
    im.save(o, "JPEG", quality=96)
    return o.getvalue()


def _thumb(data):
    from PIL import Image
    im = Image.open(io.BytesIO(data))
    w, h = im.size
    im.draft("RGB", (640, 640))
    im = im.convert("RGB")
    im.thumbnail((320, 320), Image.LANCZOS)
    o = io.BytesIO()
    im.save(o, "JPEG", quality=70)
    return o.getvalue(), w, h, im.width, im.height


class Ticker:
    """Hauptschleife: Abstaende zwischen 10-ms-Ticks (Ruckler = lange Abstaende)"""
    def __init__(self):
        self.gaps, self.last, self.on = [], time.monotonic(), True
        GLib.timeout_add(10, self._tick)

    def _tick(self):
        t = time.monotonic()
        self.gaps.append(t - self.last)
        self.last = t
        return self.on

    def stop(self):
        self.on = False
        g = sorted(self.gaps) or [0]
        return {"max": g[-1] * 1000, "p95": g[int(len(g) * 0.95)] * 1000, "n_over_50ms": sum(1 for x in g if x > 0.05)}


def pump(cond, timeout, every=0.05):
    """Hauptschleife laufen lassen, bis cond() (alle every s geprueft - sonst verhungern Leerlauf-Rueckrufe hinter
    der teuren Pruefung) oder Zeitlimit"""
    ctx = GLib.MainContext.default()
    end = time.monotonic() + timeout
    nxt = 0
    while time.monotonic() < end:
        if time.monotonic() >= nxt:
            if cond():
                return True
            nxt = time.monotonic() + every
        ctx.iteration(False) or time.sleep(0.002)
    return cond()


def _walk(w):
    o, c = [w], w.get_first_child()
    while c:
        o += _walk(c)
        c = c.get_next_sibling()
    return o


def view_state(conv):
    """Bilder im sichtbaren Bereich: (geladen, wartend)"""
    sc = conv.scroll
    page = sc.get_vadjustment().get_page_size()
    loaded = waiting = 0
    for w in _walk(conv.msgs):
        wait, btn = w.has_css_class("hk-img-wait"), w.has_css_class("hk-img-btn")
        if not (wait or btn):
            continue
        ok, r = w.compute_bounds(sc)
        if not ok or r.get_y() + r.get_height() < 0 or r.get_y() > page:
            continue
        if wait:
            waiting += 1
        elif btn and not any(x.has_css_class("hk-img-wait") for x in _walk(w)):
            loaded += 1
    return loaded, waiting


def start(g):
    from hlidskjalf import chatcrypto as C
    from hlidskjalf.chat import chat
    V.start(g)
    srv = V.S["srv"]
    chat.refresh()
    pump(lambda: chat.me and chat.status in ("setup", "ready"), 10)
    if not chat.ready:
        chat.adopt_keys(chat.setup(C.recovery_key()))
    cid = srv.new_chat("free", ["test", "anna"], admin="test")
    t = time.monotonic()
    photos = [_photo(i) for i in range(8)]
    sizes = [len(p) for p in photos]
    rep("fotos", f"{len(photos)}x {min(sizes) / 1e6:.1f}-{max(sizes) / 1e6:.1f}MB (2400x1800)")
    blobs = []
    for i, p in enumerate(photos):
        b, k = C.encrypt_file(cid, p)
        tb, w, h, tw, th = _thumb(p)
        tblob, tk = C.encrypt_file(cid, tb)
        blobs.append((b, k, tblob, tk, len(p), w, h, tw, th, len(tb)))
    for i in range(N):
        b, k, tblob, tk, s, w, h, tw, th, ts = blobs[i % len(blobs)]
        fid = f"bench{i:04d}"
        srv.cfiles[fid] = (cid, "anna", b, None)
        f = {"id": fid, "k": C.b64(k), "n": f"foto-{i}.jpg", "m": "image/jpeg", "s": s}
        if WITH_TH:
            srv.cfiles[fid + "t"] = (cid, "anna", tblob, None)
            f.update({"w": w, "h": h, "th": {"id": fid + "t", "k": C.b64(tk), "m": "image/jpeg", "w": tw, "h": th, "s": ts}})
        srv.peer_send(cid, "anna", f"Bild {i}" if i % 5 == 0 else "", files=[f])
    rep("aufbau_s", f"{time.monotonic() - t:.1f}")
    rep("format", "mit th" if WITH_TH else "ohne th (alt)")
    # Netz: je Anhang 10 ms + 50 MB/s
    orig = srv.handle
    B["dl"] = [0, 0]

    def handle(method, url, data, cookie):
        r = orig(method, url, data, cookie)
        if method == "GET" and "/api/chat/files/" in url:
            n = len(r[1])
            B["dl"][0] += 1
            B["dl"][1] += n
            time.sleep(0.010 + n / 50e6)
        return r
    srv.handle = handle
    B["cid"] = cid
    g["win"].unmaximize()
    g["win"].set_default_size(1280, 820)
    chat.load_chats()
    rep("rss_start", mb(rss()[0]))


def first_screen(g):
    win = g["win"]
    tk = Ticker()
    t0 = time.monotonic()
    win.show_route(("chat", B["cid"]))
    conv = None

    def ready():
        nonlocal conv
        conv = getattr(win.current, "conv", None)
        if conv is None or not conv.loaded:
            return False
        lo, wa = view_state(conv)
        return lo > 0 and wa == 0
    ok = pump(ready, 120)
    t1 = time.monotonic() - t0
    st = tk.stop()
    rep("erster_bildschirm_s", f"{t1:.2f}" + ("" if ok else " (Zeitlimit)"))
    rep("erster_bildschirm_ruckler_ms", f"max {st['max']:.0f}, p95 {st['p95']:.0f}, >50ms: {st['n_over_50ms']}")
    rep("downloads_bis_erster_bildschirm", f"{B['dl'][0]} ({B['dl'][1] / 1e6:.1f}MB)")
    if not ok:
        debug(conv)
    B["conv"] = conv
    g["shot"](os.path.join(g["out"], "linux-bench-erster-bildschirm.png"))


def debug(conv):
    """Fehlersuche (neuer Stand): Zustaende der Bilder, Auftraege"""
    if conv is None or not hasattr(conv, "img_widgets"):
        return
    from hlidskjalf import chatmedia
    sts = {}
    for w in conv.img_widgets:
        sts[w.state] = sts.get(w.state, 0) + 1
    adj = conv.scroll.get_vadjustment()
    inv = []
    for w in conv.img_widgets:
        ok, r = w.compute_bounds(conv.clamp)
        if ok and r.get_y() + r.get_height() >= adj.get_value() and r.get_y() <= adj.get_value() + adj.get_page_size():
            inv.append((w.f["id"], w.state, int(r.get_y()), w.get_height()))
    rep("debug", f"view={view_state(conv)} states={sts} stats={chatmedia.stats} pinned={conv._pinned} "
                 f"jobs={len(chatmedia._jobs)} running={len(chatmedia._running)} adj={int(adj.get_value())}/"
                 f"{int(adj.get_page_size())}/{int(adj.get_upper())} inview={inv[:6]}")


def settle(g):
    pump(lambda: False, 8)                                  # Hintergrund-Laden (alter Stand: alle 50 Originale) abwarten
    r, hwm = rss()
    rep("nach_8s_rss", mb(r))
    rep("nach_8s_downloads", f"{B['dl'][0]} ({B['dl'][1] / 1e6:.1f}MB)")


def load_all(g):
    from hlidskjalf.chat import chat
    conv = B["conv"]
    tk = Ticker()
    t0 = time.monotonic()
    aborted = ""
    while (chat.cache.get(B["cid"]) or {}).get("more"):
        n = len(chat.cache[B["cid"]]["msgs"])
        conv._older()
        pump(lambda: len(chat.cache[B["cid"]]["msgs"]) > n, 30)
        pump(lambda: False, 1.5)
        if rss()[0] > RSS_MAX:
            aborted = f" (abgebrochen bei {len(chat.cache[B['cid']]['msgs'])} Nachrichten: RSS {mb(rss()[0])})"
            break
    st = tk.stop()
    rep("alle_laden_s", f"{time.monotonic() - t0:.1f}{aborted}")
    rep("alle_laden_ruckler_ms", f"max {st['max']:.0f}, p95 {st['p95']:.0f}, >50ms: {st['n_over_50ms']}")
    rep("nachrichten", len(chat.cache[B["cid"]]["msgs"]))
    rep("nach_laden_rss", mb(rss()[0]))
    B["aborted"] = bool(aborted)


def scroll(g):
    conv = B["conv"]
    adj = conv.scroll.get_vadjustment()
    adj.set_value(adj.get_upper())
    pump(lambda: False, 0.5)
    tk = Ticker()
    t0 = time.monotonic()
    steps = 0
    while adj.get_value() > 0:                                # ganz nach oben, je Schritt eine halbe Seite, ~60 Bilder/s
        adj.set_value(max(0, adj.get_value() - adj.get_page_size() / 2))
        pump(lambda: False, 0.016)
        steps += 1
    t_scroll = time.monotonic() - t0
    ok = pump(lambda: (lambda s: s[0] > 0 and s[1] == 0)(view_state(conv)), 60)
    st = tk.stop()
    if not ok:
        debug(conv)
    rep("scrollen_s", f"{t_scroll:.2f} ({steps} Schritte)")
    rep("scrollen_ruckler_ms", f"max {st['max']:.0f}, p95 {st['p95']:.0f}, >50ms: {st['n_over_50ms']}")
    rep("oben_bilder_da_s", f"{time.monotonic() - t0 - t_scroll:.2f}" + ("" if ok else " (Zeitlimit)"))
    r, hwm = rss()
    rep("ende_rss", mb(r))
    rep("spitze_rss", mb(hwm))
    rep("downloads_gesamt", f"{B['dl'][0]} ({B['dl'][1] / 1e6:.1f}MB)")
    g["shot"](os.path.join(g["out"], "linux-bench-oben.png"))


steps = [(300, start), (500, first_screen), (100, settle), (100, load_all), (100, scroll)]
