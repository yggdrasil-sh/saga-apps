"""Chat-Bilder (2.1-c): Vorschaubilder beim Senden, Laden nur fuer sichtbare Nachrichten, Zwischenspeicher.

Format (gemeinsam mit Web/Apple/Android, abwaertskompatibel): der files[]-Eintrag eines Bildes bekommt "w"/"h" (Original,
Pixel, so wie es angezeigt wird) und "th": {"id", "k", "m": "image/jpeg", "w", "h", "s"} - ein eigenes, wie jeder Anhang
verschluesseltes JPEG (laengste Seite 320 px, Qualitaet 70, ohne Metadaten). Im Verlauf wird nur das Vorschaubild geladen;
das Original erst beim Oeffnen. Fehlt "th" (alte Nachrichten), wird das Original geladen, hier verkleinert und das
Vorschaubild - mit dem Dateischluessel der Nachricht verschluesselt - zwischengespeichert.

Zwischenspeicher:
  * Speicher: entschluesselte, dekodierte Vorschaubilder (LRU, MEM_LIMIT Bytes Pixeldaten)
  * Platte: CACHE_DIR/chat-blobs - nur Ciphertext (wie vom Server), die Schluessel stehen nur in den verschluesselten
    Nachrichten -> "encrypted at rest"; LRU bis BLOB_LIMIT; geleert beim Abmelden, Serverwechsel und "Neu beginnen".
    Dateiname mit Chat-Kennung (<server>-c<chat>-<datei>), damit "Chat loeschen" (2.1) alles eines Chats findet
    (purge_chat); aeltere Eintraege ohne Kennung werden weiter gelesen.
Arbeit (Laden, Entschluesseln, Dekodieren) laeuft in eigenen Threads (begrenzte Parallelitaet, neueste Anfrage zuerst);
noch wartende Auftraege fuer weggescrollte Bilder werden verworfen."""
import collections
import hashlib
import io
import os
import re
import shutil
import threading
import urllib.parse

from gi.repository import Gdk, GLib

from . import chatcrypto as C
from . import config
from .api import client

THUMB_EDGE = 320
THUMB_Q = 70
BLOB_LIMIT = 200 * 1024 * 1024
MEM_LIMIT = 64 * 1024 * 1024
WORKERS = 3
_DIR = os.path.join(config.CACHE_DIR, "chat-blobs")

_mem = collections.OrderedDict()     # Datei-ID -> Gdk.Texture (Vorschaubild)
_mem_cost = {}
_mem_size = 0
_disk_lock = threading.Lock()
_disk_writes = 0
_gen = 0                             # erhoeht bei clear(): spaete Ergebnisse alter Auftraege verwerfen
_mem_cid = {}                        # Datei-ID -> Chat (Vorschaubilder im Speicher, fuer purge_chat)
_cgen = {}                           # Chat -> Zaehler, erhoeht bei purge_chat: spaete Ergebnisse dieses Chats verwerfen
REFS = {}                            # Chat -> chat_ref (2.3, Chats ueber Servergrenzen: Anhaenge mit AD saga-chat/v2/file)


def decrypt(cid, blob, key, v1=False):
    """Anhang entschluesseln: v1 mit der Chat-ID, v2 (Chat ueber Servergrenzen, Stufe 3) mit chat_ref. v1=True: Nachricht
    von vor der Umstellung des Chats (2.3 Seiten-Chats, 2.5 Stufe 3) - ihre Anhaenge haben weiter die Chat-ID als AD"""
    ref = None if v1 else REFS.get(cid)
    return C.decrypt_file_v2(ref, blob, key) if ref else C.decrypt_file(cid, blob, key)


def ad1(f):
    """Anhang einer Nachricht im alten Format (Markierung aus chat._decode)"""
    return isinstance(f, dict) and f.get("_ad") == 1


def encrypt(cid, data, key=None, v1=False):
    ref = None if v1 else REFS.get(cid)
    return C.encrypt_file_v2(ref, data, key=key) if ref else C.encrypt_file(cid, data, key=key)


def is_image(f):
    m = (f.get("m") or "") if isinstance(f, dict) else ""
    return m.startswith("image/") and m != "image/svg+xml"


def _dim(v):
    try:
        v = int(v)
    except (TypeError, ValueError):
        return 0
    return v if 0 < v <= 100000 else 0


def size_of(f):
    """angezeigte Groesse des Originals aus dem Eintrag (w/h, sonst die Masse des Vorschaubilds) -> (w, h) oder None"""
    w, h = _dim(f.get("w")), _dim(f.get("h"))
    if w and h:
        return w, h
    th = f.get("th")
    if isinstance(th, dict):
        w, h = _dim(th.get("w")), _dim(th.get("h"))
        if w and h:
            return w, h
    return None


def thumb_ref(f):
    """gueltiger "th"-Eintrag oder None"""
    th = f.get("th")
    if isinstance(th, dict) and isinstance(th.get("id"), str) and th["id"] and isinstance(th.get("k"), str):
        return th
    return None


def box_size(wh, max_w=240, max_h=280):
    """Platz im Verlauf (gleiche Regel wie bisher: hoechstens 240 x 280, nie vergroessern)"""
    if not wh:
        return 200, 150
    w, h = wh
    sc = min(1.0, max_w / max(1, w), max_h / max(1, h))
    return max(48, int(w * sc)), max(36, int(h * sc))


# --- Vorschaubild erzeugen (Hintergrund) -------------------------------------------------------------------------------
def make_thumb(data, edge=THUMB_EDGE, quality=THUMB_Q):
    """Bild -> (JPEG-Bytes, (w, h) des Originals, (tw, th)) - ohne Metadaten. None, wenn kein lesbares Bild."""
    try:
        from PIL import Image, ImageOps
    except ImportError:
        return None
    try:
        im = Image.open(io.BytesIO(data))
        w, h = im.size
        try:
            orient = im.getexif().get(0x0112, 1)
        except Exception:  # noqa: BLE001 - kaputte EXIF-Daten
            orient = 1
        if orient in (5, 6, 7, 8):
            w, h = h, w
        im.draft("RGB", (edge * 2, edge * 2))              # JPEG: gleich verkleinert dekodieren (viel schneller)
        try:
            im = ImageOps.exif_transpose(im)
        except Exception:  # noqa: BLE001
            pass
        if im.mode in ("RGBA", "LA", "P", "PA"):
            im = im.convert("RGBA")
            bgc = Image.new("RGB", im.size, (255, 255, 255))
            bgc.paste(im, mask=im.split()[-1])
            im = bgc
        elif im.mode != "RGB":
            im = im.convert("RGB")
        im.thumbnail((edge, edge), Image.LANCZOS)
        out = io.BytesIO()
        im.save(out, "JPEG", quality=quality, optimize=True)
        return out.getvalue(), (w, h), im.size
    except Exception:  # noqa: BLE001 - kein Bild/kaputt: ohne Vorschaubild senden
        return None


# --- Platten-Zwischenspeicher (nur Ciphertext) -------------------------------------------------------------------------
def _srv():
    return hashlib.sha256((config.settings.server or "").encode()).hexdigest()[:10]


def _safe(fid):
    return re.sub(r"[^A-Za-z0-9_-]", "_", fid)[:120]


def _path(fid, suffix="", cid=None):
    """Platten-Datei; mit cid: <server>-c<chat>-<datei> (2.1), ohne: altes Format <server>-<datei>"""
    mid = "" if cid is None else f"c{int(cid)}-"
    return os.path.join(_DIR, _srv() + "-" + mid + _safe(fid) + suffix)


def _read_any(fid, suffix, cid):
    """neues Format zuerst, dann ein aelterer Eintrag ohne Chat-Kennung"""
    data = _read(_path(fid, suffix, cid)) if cid is not None else None
    return data if data is not None else _read(_path(fid, suffix))


def _read(p):
    try:
        with open(p, "rb") as f:
            data = f.read()
        os.utime(p)                                       # zuletzt benutzt (LRU), atime ist oft abgeschaltet
        return data
    except OSError:
        return None


def _write(p, blob):
    global _disk_writes
    try:
        os.makedirs(_DIR, mode=0o700, exist_ok=True)
        config.atomic_write(p, blob)
    except OSError:
        return
    _disk_writes += 1
    if _disk_writes % 16 == 0:
        prune()


def put_blob(fid, blob, cid=None):
    _write(_path(fid, "", cid), blob)


def get_blob(fid, keep=True, cid=None):
    """Hintergrund: Ciphertext aus dem Zwischenspeicher oder vom Server (keep: danach zwischenspeichern)"""
    data = _read_any(fid, "", cid)
    if data is not None:
        return data
    g = _cgen.get(cid, 0)
    data = client.request("GET", f"api/chat/files/{urllib.parse.quote(fid)}", timeout=180)
    if keep and _cgen.get(cid, 0) == g:                  # inzwischen geloescht: nicht wieder ablegen
        _write(_path(fid, "", cid), data)
    return data


def prune(limit=None):
    """Platten-Zwischenspeicher bis unter die Grenze kuerzen (am laengsten ungenutzte zuerst)"""
    limit = BLOB_LIMIT if limit is None else limit
    with _disk_lock:
        try:
            ents = [e for e in os.scandir(_DIR) if e.is_file(follow_symlinks=False)]
        except OSError:
            return 0
        files = []
        for e in ents:
            try:
                st = e.stat(follow_symlinks=False)
            except OSError:
                continue
            files.append((st.st_mtime, st.st_size, e.path))
        files.sort()
        total, n = sum(f[1] for f in files), 0
        for _t, size, path in files:
            if total <= limit:
                break
            try:
                os.remove(path)
                total -= size
                n += 1
            except OSError:
                pass
        return n


def disk_usage():
    try:
        return sum(e.stat().st_size for e in os.scandir(_DIR) if e.is_file(follow_symlinks=False))
    except OSError:
        return 0


def clear():
    """Abmelden, Serverwechsel, "Neu beginnen": Speicher und Platte leeren"""
    global _mem_size, _gen
    _gen += 1
    _mem.clear()
    _mem_cost.clear()
    _mem_cid.clear()
    REFS.clear()
    _mem_size = 0
    with _jobs_lock:
        _jobs.clear()
    with _disk_lock:
        shutil.rmtree(_DIR, ignore_errors=True)


def purge_chat(cid, fids=(), keep=()):
    """Chat geloescht/geleert (2.1, Hauptthread): Vorschaubilder im Speicher, wartende Auftraege und den
    Platten-Zwischenspeicher dieses Chats entfernen - ausser den Datei-IDs in keep (Nachrichten nach der Grenze).
    fids: zusaetzlich bekannte Datei-IDs (aeltere Platten-Eintraege ohne Chat-Kennung). -> Anzahl entfernter Dateien"""
    global _mem_size
    keep = set(keep)
    fids = set(fids) - keep
    _cgen[cid] = _cgen.get(cid, 0) + 1                    # laufende Auftraege dieses Chats: Ergebnis verwerfen
    for fid in [f for f in _mem if (_mem_cid.get(f) == cid or f in fids) and f not in keep]:
        _mem.pop(fid, None)
        _mem_size -= _mem_cost.pop(fid, 0)
        _mem_cid.pop(fid, None)
    with _jobs_lock:
        for fid in [f for f, j in _jobs.items() if j["cid"] == cid and f not in keep]:
            del _jobs[fid]
    srv = _srv()
    pre = f"{srv}-c{int(cid)}-"
    legacy = {srv + "-" + _safe(f) + s for f in fids for s in ("", ".th")}
    safe_keep = {_safe(f) for f in keep}
    n = 0
    with _disk_lock:
        try:
            ents = [e for e in os.scandir(_DIR) if e.is_file(follow_symlinks=False)]
        except OSError:
            ents = []
        for e in ents:
            name = e.name
            if name.startswith(pre):
                rest = name[len(pre):]
                if (rest[:-3] if rest.endswith(".th") else rest) in safe_keep:
                    continue
            elif name not in legacy:
                continue
            try:
                os.remove(e.path)
                n += 1
            except OSError:
                pass
    return n


# --- Speicher-LRU (Hauptthread) ----------------------------------------------------------------------------------------
def cached(fid):
    t = _mem.get(fid)
    if t is not None:
        _mem.move_to_end(fid)
    return t


def remember(fid, tex):
    global _mem_size
    cost = tex.get_width() * tex.get_height() * 4
    if fid in _mem:
        _mem_size -= _mem_cost.get(fid, 0)
    _mem[fid] = tex
    _mem.move_to_end(fid)
    _mem_cost[fid] = cost
    _mem_size += cost
    while _mem_size > MEM_LIMIT and len(_mem) > 1:
        old, _t = _mem.popitem(last=False)
        _mem_size -= _mem_cost.pop(old, 0)
        _mem_cid.pop(old, None)


def mem_usage():
    return _mem_size, len(_mem)


def _texture(raw):
    rgba, w, h = raw
    return Gdk.MemoryTexture.new(w, h, Gdk.MemoryFormat.R8G8B8A8, GLib.Bytes.new(rgba), w * 4)


def _decode(data):
    """Hintergrund: JPEG/PNG -> rohe RGBA-Pixel (Vorschaubilder sind klein)"""
    from PIL import Image
    im = Image.open(io.BytesIO(data))
    im.draft("RGB", (THUMB_EDGE * 2, THUMB_EDGE * 2))
    fr = im.convert("RGBA")
    if max(fr.size) > THUMB_EDGE * 2:
        fr.thumbnail((THUMB_EDGE * 2, THUMB_EDGE * 2))
    return fr.tobytes(), fr.width, fr.height


# --- Auftraege: eigene Threads, neueste zuerst, Wegscrollen verwirft -----------------------------------------------------
_jobs = collections.OrderedDict()    # Datei-ID -> Auftrag (wartend)
_jobs_lock = threading.Condition()
_running = {}                        # Datei-ID -> Auftrag (laeuft)
_threads = []
stats = {"fetched": 0, "decoded": 0, "cancelled": 0, "generated": 0}


def _worker():
    while True:
        with _jobs_lock:
            while not _jobs:
                _jobs_lock.wait()
            fid, job = _jobs.popitem(last=True)           # LIFO: zuletzt sichtbar gewordene Bilder zuerst
            _running[fid] = job                           # weitere Interessenten haengen sich an
        try:
            res = _run(job)
            err = None
        except Exception as e:  # noqa: BLE001
            res, err = None, e
        GLib.idle_add(_finish, fid, job, res, err)


def _run(job):
    cid, f, gen = job["cid"], job["f"], job["gen"]
    th = thumb_ref(f)
    if th:
        blob = get_blob(th["id"], cid=cid)
        stats["fetched"] += 1
        data = decrypt(cid, blob, C.unb64(th["k"], 32), ad1(f))
        return th["id"], _decode(data), None
    # alte Nachricht ohne Vorschaubild: eigenes erzeugen und verschluesselt zwischenspeichern
    key = C.unb64(f.get("k"), 32)
    blob = _read_any(f["id"], ".th", cid)
    if blob is not None:
        try:
            data = decrypt(cid, blob, key, ad1(f))
            return f["id"], _decode(data), None
        except C.CryptoError:
            pass
    orig = decrypt(cid, get_blob(f["id"], keep=False, cid=cid), key, ad1(f))
    stats["fetched"] += 1
    t = make_thumb(orig)
    if t is None:
        raise ValueError("no image")
    stats["generated"] += 1
    if gen == _gen and job.get("cgen", 0) == _cgen.get(cid, 0):
        _write(_path(f["id"], ".th", cid), encrypt(cid, t[0], key=key, v1=ad1(f))[0])
    return f["id"], _decode(t[0]), t[1]


def _finish(fid, job, res, err):
    with _jobs_lock:
        _running.pop(fid, None)
    if job["gen"] != _gen:
        return False
    if job.get("cgen", 0) != _cgen.get(job["cid"], 0):        # Chat inzwischen geloescht: nichts anzeigen/merken
        res = None
    tex, wh = None, None
    if res is not None:
        _key, raw, wh = res
        tex = _texture(raw)
        stats["decoded"] += 1
        remember(fid, tex)
        _mem_cid[fid] = job["cid"]
    for cb in job["cbs"]:
        cb(tex, wh)
    return False


def request(cid, f, cb):
    """Vorschaubild fuer den Verlauf: cb(Gdk.Texture|None, (w, h)|None) im Hauptthread (sofort, wenn im Speicher).
    -> Schluessel fuer cancel()"""
    fid = f["id"]
    tex = cached(fid)
    if tex is not None:
        cb(tex, None)
        return fid
    with _jobs_lock:
        if fid in _running:
            _running[fid]["cbs"].append(cb)
            return fid
        job = _jobs.pop(fid, None)
        if job is None:
            job = {"cid": cid, "f": f, "gen": _gen, "cgen": _cgen.get(cid, 0), "cbs": []}
        job["cbs"].append(cb)
        _jobs[fid] = job                                   # (wieder) ans Ende: zuerst dran
        _jobs_lock.notify()
    if not _threads:
        for i in range(WORKERS):
            t = threading.Thread(target=_worker, name=f"hk-chatimg-{i}", daemon=True)
            t.start()
            _threads.append(t)
    return fid


def bump(fid):
    """wartenden Auftrag nach vorn holen (Bild ist jetzt im sichtbaren Bereich)"""
    with _jobs_lock:
        if fid in _jobs:
            _jobs.move_to_end(fid)


def cancel(fid, cb):
    """Bild nicht mehr sichtbar: wartenden Auftrag (ohne andere Interessenten) verwerfen"""
    with _jobs_lock:
        job = _jobs.get(fid)
        if job is None:
            return
        job["cbs"] = [c for c in job["cbs"] if c is not cb]
        if not job["cbs"]:
            del _jobs[fid]
            stats["cancelled"] += 1


def original(cid, f):
    """Hintergrund: Original laden (Ciphertext wird zwischengespeichert) und entschluesseln -> Bytes"""
    return decrypt(cid, get_blob(f["id"], cid=cid), C.unb64(f.get("k"), 32), ad1(f))
