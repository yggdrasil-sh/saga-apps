"""Videos im Chat (Saga 2.4, Etappe E2) gegen den nachgebauten Server (tests/mockvideo.py): Grenzen und Faehigkeiten,
fortsetzbarer Upload in Teilen (Netzabbruch, Neustart, Abbrechen), Range mit Medien-Token, Blockzuordnung, lokaler Proxy
(Range, 416, Token), Platten-Zwischenspeicher (LRU, Chat loeschen), Speichern als Strom, konstanter Speicher beim
Hoch- und Herunterladen, v1 unveraendert, Oberflaeche (nur Poster in der Liste, Player erst im Dialog)."""
import os
import tracemalloc
import urllib.error
import urllib.request

import pytest
from gi.repository import Gtk

from hk_helpers import idle, pump
from hlidskjalf import chatcrypto as C
from hlidskjalf import filev2 as F

MiB = 1024 * 1024
CS = F.CS


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _ready(srv):
    from hlidskjalf.chat import chat
    chat.refresh()
    pump(lambda: chat.status == "setup", 5)
    k = chat.setup(C.recovery_key())
    chat.adopt_keys(k)
    pump(lambda: chat.status == "ready" and chat.me and chat.me.get("identity"), 5)
    idle()


def _chat(srv, small=256 * 1024):
    from hlidskjalf.chat import chat
    srv.file_max = small
    _ready(srv)
    srv.chat_peer("anna", "Anna")
    cid = srv.new_chat("dm", ["test", "anna"])
    chat.load_chats()
    pump(lambda: chat.chat(cid) is not None, 4)
    chat.refresh()
    pump(lambda: (chat.limits or {}).get("file_max") == small, 4)
    return cid


def _file(tmp_path, n, name="clip.mp4"):
    p = tmp_path / name
    with open(p, "wb") as f:                                # deterministisch, ohne alles im Speicher zu halten
        k = 0
        while k < n:
            m = min(MiB, n - k)
            f.write(bytes((k + i) % 251 for i in range(min(m, 251))) * (m // 251) + bytes((k + i) % 251 for i in range(m % 251)))
            k += m
    return str(p)


def _mp4(tmp_path, secs=2):
    """echtes kleines MP4 (GStreamer) oder None"""
    from hlidskjalf import videotools as V
    Gst = V.gst()
    enc = V.h264_encoder()
    if not Gst or not enc or not V.has("videotestsrc", "mp4mux", "h264parse"):
        return None
    p = str(tmp_path / "echt.mp4")
    pipe = Gst.parse_launch(f"videotestsrc num-buffers={secs * 25} pattern=ball ! video/x-raw,width=640,height=360,framerate=25/1 ! "
                            f"{enc} ! h264parse ! mp4mux ! filesink location={p}")
    pipe.set_state(Gst.State.PLAYING)
    pipe.get_bus().timed_pop_filtered(20 * Gst.SECOND, Gst.MessageType.EOS | Gst.MessageType.ERROR)
    pipe.set_state(Gst.State.NULL)
    return p if os.path.getsize(p) > 1000 else None


# --- Grenzen -------------------------------------------------------------------------------------------------------------
def test_plan_limits():
    from hlidskjalf import chatvideo as V
    lim = {"file_max": 10 * MiB, "file2": True, "video_max": 500 * MiB}
    assert V.plan("a.mp4", 10 * MiB, lim) == "v1"
    assert V.plan("a.mp4", 10 * MiB + 1, lim) == "v2"
    with pytest.raises(V.VideoError, match="500"):
        V.plan("a.mp4", 500 * MiB + 1, lim)
    with pytest.raises(V.VideoError, match="10 MB"):                       # Server 2.3: kein file2
        V.plan("a.mp4", 11 * MiB, {"file_max": 10 * MiB})
    fed = {"v": 2, "big_files": False}
    with pytest.raises(V.VideoError, match="10 MB"):                       # Gegenueber ohne chatfile2
        V.plan("a.mp4", 11 * MiB, lim, fed, True)
    assert V.plan("a.mp4", 11 * MiB, lim, {"v": 2, "big_files": True}, True) == "v2"
    assert V.max_video(lim) == 500 * MiB and V.max_video({"file_max": 10 * MiB}) == 10 * MiB
    assert V.max_video(lim, fed, True) == 10 * MiB
    assert V.fmt({}) == 1 and V.fmt({"x": 2}) == 2 and V.fmt({"x": 3}) == 3 and V.fmt({"x": "?"}) == 99
    assert V.dur_text(83.4) == "1:23" and V.dur_text(3725) == "1:02:05"
    assert V.display_size({"w": 1280, "h": 720, "rot": 90}) == (720, 1280)


def test_proxy_range_parse():
    from hlidskjalf.chatvideo import parse_range
    assert parse_range(None, 100) is None
    assert parse_range("bytes=0-", 100) == (0, 99)
    assert parse_range("bytes=10-19", 100) == (10, 19)
    assert parse_range("bytes=90-500", 100) == (90, 99)
    assert parse_range("bytes=-30", 100) == (70, 99)
    assert parse_range("bytes=-300", 100) == (0, 99)
    assert parse_range("bytes=5-9,20-30", 100) == (5, 9)                 # mehrere Bereiche: der erste
    for bad in ("bytes=100-", "bytes=20-10", "bytes=-0", "items=0-1", "bytes=-", "bytes=a-b"):
        assert parse_range(bad, 100) == "bad", bad
    assert parse_range("bytes=0-", 0) == "bad"


# --- Upload ----------------------------------------------------------------------------------------------------------------
def test_upload_parts_resume_after_drop(win, srv, tmp_path):
    from hlidskjalf import chatvideo as V
    from hlidskjalf.chat import chat
    cid = _chat(srv)
    srv.upload_part = 300 * 1024                         # mehrere Teile, Grenzen mitten im Block
    path = _file(tmp_path, 3 * CS + 777)
    total = F.cipher_size(os.path.getsize(path))
    srv.put_fail = [None, ("drop", 1000), ("code", 503)]  # 2. Teil bricht nach 1000 Bytes ab, 3. Teil 503
    V.RETRIES, old = 3, V.RETRIES
    sleeps = []
    V._sleep, old_sleep = (lambda sec, c: sleeps.append(sec)), V._sleep
    seen = []
    try:
        r = V.upload(cid, path, progress=lambda a, t: seen.append((a, t)), limits=chat.limits)
    finally:
        V.RETRIES, V._sleep = old, old_sleep
    assert len(sleeps) == 2                               # zwei Wiederholungen (mit Warten), danach HEAD und weiter
    assert seen[-1] == (total, total)
    blob = open(srv.cfiles[r["id"]][2].path, "rb").read()
    assert len(blob) == total == srv.put_bytes            # kein Byte doppelt (Fortsetzen ab Upload-Offset, auch mitten im Block)
    assert F.decrypt_blob(C.unb64(r["k"], 32), blob, os.path.getsize(path), CS) == open(path, "rb").read()
    heads = [x for x in srv.media_log if x[0] == "HEAD"]
    assert len(heads) == 2
    puts = [x for x in srv.media_log if x[0] == "PUT"]
    assert all(int(p[2]["content-length"]) <= srv.upload_part for p in puts)
    assert r["pending"] in V._pending_all()               # bis zum Senden gemerkt (Schluessel geschuetzt, Datei 0600)
    assert oct(os.stat(V._PFILE).st_mode & 0o777) == "0o600"
    V.pending_drop(r["pending"])
    assert not V._pending_all()


def test_upload_resume_after_restart_and_cancel(win, srv, tmp_path):
    from hlidskjalf import chatvideo as V
    from hlidskjalf.chat import chat
    cid = _chat(srv)
    srv.upload_part = 256 * 1024
    path = _file(tmp_path, 4 * CS + 5)
    total = F.cipher_size(os.path.getsize(path))

    class Crash(Exception):
        pass

    def crash(a, t):
        if a > 300 * 1024:
            raise Crash()                                 # App beendet mitten im Upload
    with pytest.raises(Crash):
        V.upload(cid, path, progress=crash, limits=chat.limits)
    sent = srv.put_bytes
    assert 0 < sent < total and len(V._pending_all()) == 1
    # "Neustart": dieselbe Datei erneut senden -> HEAD, weiter ab Upload-Offset, gleicher Schluessel
    n_post = sum(1 for m, u, b, k in srv.log if m == "POST" and u.endswith(f"/api/chats/{cid}/uploads"))
    r = V.upload(cid, path, limits=chat.limits)
    assert sum(1 for m, u, b, k in srv.log if m == "POST" and u.endswith(f"/api/chats/{cid}/uploads")) == n_post
    assert srv.put_bytes == total
    blob = open(srv.cfiles[r["id"]][2].path, "rb").read()
    assert F.decrypt_blob(C.unb64(r["k"], 32), blob) == open(path, "rb").read()
    # fertig, aber Antwort verpasst: HEAD meldet Upload-Done -> gleiche Kennung
    r2 = V.upload(cid, path, limits=chat.limits)
    assert r2["id"] == r["id"] and srv.put_bytes == total
    V.pending_drop(r["id"])
    # Abbrechen: DELETE, nichts gemerkt
    path2 = _file(tmp_path, 3 * CS, "b.mp4")
    calls = []
    with pytest.raises(V.Cancelled):
        V.upload(cid, path2, progress=lambda a, t: calls.append(a), cancelled=lambda: len(calls) >= 1, limits=chat.limits)
    assert not srv.uploads and not V._pending_all()
    assert any(m == "DELETE" for m, p, h in srv.media_log)


def test_send_video_message_v1_v2_and_old_server(win, srv, tmp_path):
    from hlidskjalf import chatvideo as V
    from hlidskjalf.chat import chat
    cid = _chat(srv)
    real = _mp4(tmp_path)
    big = _file(tmp_path, 2 * CS + 10, "gross.mp4")
    small = _file(tmp_path, 1000, "klein.mp4")
    files = [("gross.mp4", "video/mp4", V.LocalVideo(big, os.path.getsize(big))),
             ("klein.mp4", "video/mp4", V.LocalVideo(small, 1000))]
    if real:
        files.append(("echt.mp4", "video/mp4", V.LocalVideo(real, os.path.getsize(real))))
    pending, prog = [], []
    out, ids = chat.upload_attachments(cid, files, progress=lambda i, t: prog.append((i, t)), pending=pending)
    g, k = out[0], out[1]
    assert g["x"] == 2 and g["cs"] == CS and g["s"] == os.path.getsize(big) and isinstance(srv.cfiles[g["id"]][2], object)
    assert "x" not in k and srv.cfiles[k["id"]][2][:24] != open(small, "rb").read()[:24]        # v1 wie bisher
    assert chat.fetch_file(cid, k) == open(small, "rb").read()
    assert pending == [g["id"]] and any("%" in t for i, t in prog if i == 0)
    if real:
        e = out[2]
        assert e["m"] == "video/mp4" and e["w"] == 640 and e["h"] == 360 and 1.5 <= e["dur"] <= 2.5 and e.get("vc") == "avc1"
        th = e["th"]
        assert th["m"] == "image/jpeg" and th["w"] == 320 and th["h"] == 180 and th["s"] <= 30 * 1024 and th["id"] in ids
        assert "x" not in e                               # klein genug: v1, mit Poster
    resp, _m = chat.send(cid, {"v": 1, "type": "text", "text": "", "files": out}, file_ids=ids)
    assert resp["ok"]
    for p in pending:
        V.pending_drop(p)
    # Speichern als Strom (v2) und v1
    buf = bytearray()
    assert chat.save_file(cid, g, buf.extend) == g["s"] and bytes(buf) == open(big, "rb").read()
    buf = bytearray()
    chat.save_file(cid, k, buf.extend)
    assert bytes(buf) == open(small, "rb").read()
    with pytest.raises(Exception):
        chat.fetch_file(cid, g)                           # v2 nie ganz in den Speicher
    with pytest.raises(Exception):
        chat.save_file(cid, dict(g, x=3), buf.extend)     # neueres Format: Hinweis statt Unsinn
    # Server 2.3 (ohne file2): grosse Videos mit Hinweis abgelehnt, kleine wie bisher
    srv.file2 = False
    chat.refresh()
    pump(lambda: not (chat.limits or {}).get("file2"), 4)
    with pytest.raises(V.VideoError, match="larger videos|groessere|größere"):
        chat.upload_attachments(cid, files[:1])
    out2, _ids = chat.upload_attachments(cid, files[1:2])
    assert "x" not in out2[0]


def test_range_token_blocks_and_cache(win, srv, tmp_path):
    from hlidskjalf import chatvideo as V
    from hlidskjalf.chat import chat
    cid = _chat(srv)
    path = _file(tmp_path, 40 * CS + 123)                 # 3 Teile im Zwischenspeicher (16 Bloecke je Teil)
    plain = open(path, "rb").read()
    r = V.upload(cid, path, limits=chat.limits)
    f = {"id": r["id"], "k": r["k"], "s": len(plain), "x": 2, "cs": CS, "m": "video/mp4"}
    V.cache_clear()
    srv.media_log.clear()
    src = V.VideoSource(cid, f)
    # Bereich ueber Blockgrenzen
    a, b = CS - 10, 2 * CS + 10
    assert b"".join(src.iter_plain(a, b)) == plain[a:b + 1]
    gets = [x for x in srv.media_log if x[0] == "GET"]
    assert gets and all(x[2].get("x-saga-media-token", "").startswith("m1.") and "cookie" not in x[2] for x in gets)
    assert gets[0][2]["range"] == "bytes=0-47"            # Kopf einmal
    assert gets[1][2]["range"] == f"bytes=48-{F.block_off(16) - 1}"     # erster Teil (16 Bloecke)
    # gleicher Bereich noch einmal: aus dem Speicher/Platten-Zwischenspeicher, kein Abruf
    n = len(gets)
    src2 = V.VideoSource(cid, f)
    assert b"".join(src2.iter_plain(a, b)) == plain[a:b + 1]
    assert len([x for x in srv.media_log if x[0] == "GET"]) == n
    # Ende (letzter, kurzer Block) und Spulen ans Ende
    assert b"".join(src2.iter_plain(len(plain) - 5, len(plain) - 1)) == plain[-5:]
    # Token abgelaufen -> 401 -> neues Token, weiter
    srv.media_tokens.clear()
    src3 = V.VideoSource(cid, f, cache=False)
    src3._prefix = src2.prefix()
    V.cache_clear()
    assert b"".join(src3.iter_plain(17 * CS, 17 * CS + 99)) == plain[17 * CS:17 * CS + 100]
    # ohne Zwischenspeicher (Speichern): je Teil (16 Bloecke) genau ein Abruf
    V.cache_clear()
    n0 = len([x for x in srv.media_log if x[0] == "GET"])
    src5 = V.VideoSource(cid, f, cache=False)
    assert b"".join(src5.iter_plain(0, len(plain) - 1)) == plain
    assert len([x for x in srv.media_log if x[0] == "GET"]) - n0 == 1 + 3 and V.cache_usage() == 0
    # Server liefert kuerzere Antworten (Foederation ohne saga-media): Rest wird nachgeholt
    srv.range_max = 100000
    V.cache_clear()
    src4 = V.VideoSource(cid, f)
    assert b"".join(src4.iter_plain(0, 3 * CS)) == plain[:3 * CS + 1]
    srv.range_max = None
    # falscher Block vom Server (z. B. vertauscht): Fehler statt falscher Bytes
    V.cache_clear()
    bad = dict(f, k=C.b64(os.urandom(32)))
    with pytest.raises(C.CryptoError):
        b"".join(V.VideoSource(cid, bad).iter_plain(0, 10))
    with pytest.raises(C.CryptoError):                    # Kopf passt nicht zur Nachricht
        V.VideoSource(cid, dict(f, s=len(plain) - 1)).prefix()
    # LRU: Grenze unterschreiten -> zuletzt benutzte bleiben
    V.cache_clear()
    s5 = V.VideoSource(cid, f)
    for i in (0, 16, 32):
        s5.block(i)
        s5._mem.clear()
    old = os.path.getmtime(V._cpath(cid, f["id"], 0))
    os.utime(V._cpath(cid, f["id"], 0), (old - 100, old - 100))
    os.utime(V._cpath(cid, f["id"], 1), (old - 50, old - 50))
    V.cache_read(cid, f["id"], 0, 0, 10)                  # Teil 0 gerade benutzt -> juengster
    usage = V.cache_usage()
    n = V.cache_prune(usage - 1)
    assert n == 1 and not os.path.exists(V._cpath(cid, f["id"], 1)) and os.path.exists(V._cpath(cid, f["id"], 0))
    assert V.cache_limit() == 512 * MiB
    # Chat geloescht: Zwischenspeicher dieses Chats weg
    assert V.purge_chat(cid) >= 2 and V.cache_usage() == 0


def test_local_proxy(win, srv, tmp_path):
    from hlidskjalf import chatvideo as V
    from hlidskjalf.chat import chat
    cid = _chat(srv)
    path = _file(tmp_path, 5 * CS + 3)
    plain = open(path, "rb").read()
    r = V.upload(cid, path, limits=chat.limits)
    src = V.VideoSource(cid, {"id": r["id"], "k": r["k"], "s": len(plain), "x": 2, "cs": CS, "m": "video/mp4"})
    url, tok = V.proxy.register(src)
    assert url.startswith("http://127.0.0.1:") and len(tok) >= 22
    assert V.proxy.srv.server_address[0] == "127.0.0.1"

    def get(u, rg=None, method="GET"):
        req = urllib.request.Request(u, method=method, headers={"Range": rg} if rg else {})
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()
    st, h, body = get(url, "bytes=1000-300000")
    assert st == 206 and body == plain[1000:300001] and h["Content-Range"] == f"bytes 1000-300000/{len(plain)}"
    assert h["Content-Type"] == "video/mp4" and h["Accept-Ranges"] == "bytes" and h["Cache-Control"] == "no-store"
    st, h, body = get(url, "bytes=-7")
    assert st == 206 and body == plain[-7:]
    st, h, body = get(url)
    assert st == 200 and body == plain
    st, h, body = get(url, method="HEAD")
    assert st == 200 and int(h["Content-Length"]) == len(plain)
    st, h, _b = get(url, f"bytes={len(plain)}-")
    assert st == 416 and h["Content-Range"] == f"bytes */{len(plain)}"
    st, _h, _b = get(url.replace(tok, "x" * 22))           # fremdes Token
    assert st == 404
    V.proxy.unregister(tok)
    st, _h, _b = get(url)
    assert st == 404


def test_stream_constant_memory(win, srv, tmp_path):
    """Hochladen und Speichern brauchen gleich viel Speicher - egal wie gross das Video ist"""
    from hlidskjalf import chatvideo as V
    from hlidskjalf.chat import chat
    cid = _chat(srv)
    peaks = {}
    for n in (12, 48):
        path = _file(tmp_path, n * MiB, f"v{n}.mp4")
        tracemalloc.start()
        r = V.upload(cid, path, limits=chat.limits)
        up = tracemalloc.get_traced_memory()[1]
        tracemalloc.stop()
        V.pending_drop(r["pending"])
        f = {"id": r["id"], "k": r["k"], "s": n * MiB, "x": 2, "cs": CS, "m": "video/mp4"}
        out = tmp_path / f"out{n}.mp4"
        with open(out, "wb") as fh:
            tracemalloc.start()
            chat.save_file(cid, f, fh.write)
            down = tracemalloc.get_traced_memory()[1]
            tracemalloc.stop()
        assert os.path.getsize(out) == n * MiB and open(out, "rb").read(MiB) == open(path, "rb").read(MiB)
        peaks[n] = (up, down)
        os.remove(path)
        os.remove(out)
    for n, (up, down) in peaks.items():
        assert up < 3 * MiB, (n, up)                      # je PUT Block fuer Block (nie ein ganzer Teil im Speicher)
        assert down < 12 * MiB, (n, down)                 # ein Abruf (4 MiB) plus Blockpuffer
    assert abs(peaks[48][0] - peaks[12][0]) < MiB and abs(peaks[48][1] - peaks[12][1]) < 2 * MiB


def test_resend_v2_copies_stream(win, srv, tmp_path):
    from hlidskjalf import chatvideo as V
    from hlidskjalf.chat import chat
    cid = _chat(srv)
    path = _file(tmp_path, 3 * CS + 1)
    r = V.upload(cid, path, limits=chat.limits)
    f = {"id": r["id"], "k": r["k"], "s": os.path.getsize(path), "x": 2, "cs": CS, "m": "video/mp4"}
    nid = V.copy_v2(cid, f)
    assert nid != r["id"]
    assert open(srv.cfiles[nid][2].path, "rb").read() == open(srv.cfiles[r["id"]][2].path, "rb").read()


# --- Oberflaeche -------------------------------------------------------------------------------------------------------------
def test_ui_poster_only_in_list_player_in_dialog(win, srv, tmp_path):
    from hlidskjalf import chatvideo as V
    from hlidskjalf import chatview as CV
    from hlidskjalf.chat import chat
    cid = _chat(srv, small=16 * 1024)                     # auch das echte (kleine) Video geht als saga-file/v2
    real = _mp4(tmp_path)
    if real is None:
        pytest.skip("kein H.264-Encoder")
    big = _file(tmp_path, 2 * CS + 10, "gross.mp4")
    files = [("echt.mp4", "video/mp4", V.LocalVideo(real, os.path.getsize(real))),
             ("gross.mp4", "video/mp4", V.LocalVideo(big, os.path.getsize(big))),
             ("neu.bin", "application/octet-stream", b"x")]
    out, ids = chat.upload_attachments(cid, files)
    assert out[0]["x"] == 2 and out[0]["th"]
    out[2]["x"] = 3                                       # Anhang in einem neueren Format
    chat.send(cid, {"v": 1, "type": "text", "text": "Videos", "files": out}, file_ids=ids)
    win.show_route(("chat", cid))
    conv = win.current.conv
    pump(lambda: conv.loaded, 6)
    pump(lambda: any(isinstance(w, CV.ChatImage) and V.is_video(w.f) for w in _walk(conv)), 6)
    ws = _walk(conv)
    vids = [w for w in ws if isinstance(w, CV.ChatImage) and V.is_video(w.f)]
    assert len(vids) == 2
    assert not any(isinstance(w, Gtk.Video) for w in ws)                  # nie ein Player in der Liste
    labels = [w.get_label() for w in ws if isinstance(w, Gtk.Label)]
    assert "0:02" in labels and any("newer Saga" in (x or "") or "neueren Saga" in (x or "") for x in labels)
    pump(lambda: vids[0].state == "ok", 6)                # Poster (sichtbar) geladen
    assert vids[1].state == "none"                        # ohne Poster: nichts geladen, kein Original
    assert not any(m == "GET" and (out[0]["id"] in p or out[1]["id"] in p) for m, p, h in srv.media_log)   # Liste: nie das Video selbst
    # Antippen: Player im Dialog (GStreamer ueber den lokalen Proxy, Range), Schliessen beendet die Wiedergabe
    from hlidskjalf import videotools
    dlg = CV.VideoViewer(conv, out[0])
    dlg.present(win)
    pump(lambda: dlg.stack.get_visible_child_name() in ("play", "err"), 6)
    if videotools.can_play():
        assert dlg.stack.get_visible_child_name() == "play" and len(V.proxy.sources) == 1
        pump(lambda: dlg.stream and dlg.stream.is_prepared(), 8)
        assert dlg.stream.is_prepared() and dlg.stream.has_video() and 1_500_000 <= dlg.stream.get_duration() <= 2_500_000
        assert any(m == "GET" and out[0]["id"] in p for m, p, h in srv.media_log)
    dlg.close()
    pump(None, 0.3)
    assert not V.proxy.sources
    # kein abspielbares Video (bzw. Codec fehlt): Hinweis "Herunterladen und extern abspielen" mit Speichern
    dlg = CV.VideoViewer(conv, out[1])
    dlg.present(win)
    pump(lambda: dlg.stack.get_visible_child_name() == "err", 8)
    assert dlg.stack.get_visible_child_name() == "err" and dlg.err.get_description()
    assert not V.proxy.sources
    dlg.close()
    pump(None, 0.2)
