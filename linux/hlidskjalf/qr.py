"""Kleiner QR-Code-Erzeuger (ISO/IEC 18004) fuer die Sicherheitsnummer im Chat ("saga-safety:1:<60 Ziffern>"):
Byte-Modus, Fehlerkorrektur M, Versionen 1-10, Maske nach Strafpunkten. Keine Kryptografie - nur zum Abscannen mit
einem anderen Geraet. Zeichnen: QRCode-Widget (Gtk.DrawingArea, schwarz auf weiss mit Ruhezone, auch im Dunkelmodus)."""
import struct
import zlib

# Version -> (EC-Codewoerter je Block, [(Bloecke, Daten-Codewoerter je Block), ...]) fuer Stufe M
_M = {1: (10, [(1, 16)]), 2: (16, [(1, 28)]), 3: (26, [(1, 44)]), 4: (18, [(2, 32)]), 5: (24, [(2, 43)]),
      6: (16, [(4, 27)]), 7: (18, [(4, 31)]), 8: (22, [(2, 38), (2, 39)]), 9: (22, [(3, 36), (2, 37)]),
      10: (26, [(4, 43), (1, 44)])}
_ALIGN = {1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42],
          9: [6, 26, 46], 10: [6, 28, 50]}

# GF(256), Primitivpolynom 0x11D
_EXP, _LOG = [0] * 512, [0] * 256
_x = 1
for _i in range(255):
    _EXP[_i] = _x
    _LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    _EXP[_i] = _EXP[_i - 255]


def _mul(a, b):
    return 0 if a == 0 or b == 0 else _EXP[_LOG[a] + _LOG[b]]


def _rs_gen(n):
    g = [1]
    for i in range(n):
        ng = [0] * (len(g) + 1)
        for j, c in enumerate(g):
            ng[j] ^= c
            ng[j + 1] ^= _mul(c, _EXP[i])
        g = ng
    return g


def _rs(data, n):
    gen = _rs_gen(n)
    rem = list(data) + [0] * n
    for i in range(len(data)):
        f = rem[i]
        if f:
            for j in range(1, len(gen)):
                rem[i + j] ^= _mul(gen[j], f)
    return rem[len(data):]


def _bits_len(v):
    return 8 if v < 10 else 16


def _choose(nbytes):
    for v in range(1, 11):
        _ec, groups = _M[v]
        cap = sum(b * d for b, d in groups)
        if 4 + _bits_len(v) + 8 * nbytes <= cap * 8:
            return v
    raise ValueError("text too long for QR versions 1-10")


def _codewords(data, v):
    ec, groups = _M[v]
    cap = sum(b * d for b, d in groups)
    bits = [0, 1, 0, 0]                                       # Byte-Modus
    n = len(data)
    bits += [(n >> i) & 1 for i in range(_bits_len(v) - 1, -1, -1)]
    for b in data:
        bits += [(b >> i) & 1 for i in range(7, -1, -1)]
    bits += [0] * min(4, cap * 8 - len(bits))                 # Terminator
    while len(bits) % 8:
        bits.append(0)
    cw = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = [0xEC, 0x11]
    i = 0
    while len(cw) < cap:
        cw.append(pad[i % 2])
        i += 1
    blocks, pos = [], 0
    for nb, d in groups:
        for _ in range(nb):
            blocks.append(cw[pos:pos + d])
            pos += d
    ecs = [_rs(b, ec) for b in blocks]
    out = []
    for i in range(max(len(b) for b in blocks)):
        out += [b[i] for b in blocks if i < len(b)]
    for i in range(ec):
        out += [e[i] for e in ecs]
    return out


def _bch(value, poly, bits_total):
    deg = poly.bit_length() - 1
    v = value << deg
    while v.bit_length() > deg:
        v ^= poly << (v.bit_length() - poly.bit_length())
    return (value << deg) | v


def _format_bits(mask):
    return _bch((0b00 << 3) | mask, 0x537, 15) ^ 0x5412      # Stufe M = 00


def _version_bits(v):
    return _bch(v, 0x1F25, 18)


_MASKS = [lambda r, c: (r + c) % 2 == 0, lambda r, c: r % 2 == 0, lambda r, c: c % 3 == 0,
          lambda r, c: (r + c) % 3 == 0, lambda r, c: (r // 2 + c // 3) % 2 == 0,
          lambda r, c: (r * c) % 2 + (r * c) % 3 == 0, lambda r, c: ((r * c) % 2 + (r * c) % 3) % 2 == 0,
          lambda r, c: ((r + c) % 2 + (r * c) % 3) % 2 == 0]


def _base(v):
    size = 17 + 4 * v
    m = [[None] * size for _ in range(size)]

    def finder(r0, c0):
        for r in range(-1, 8):
            for c in range(-1, 8):
                rr, cc = r0 + r, c0 + c
                if 0 <= rr < size and 0 <= cc < size:
                    on = 0 <= r <= 6 and 0 <= c <= 6 and (r in (0, 6) or c in (0, 6) or (2 <= r <= 4 and 2 <= c <= 4))
                    m[rr][cc] = on
    finder(0, 0)
    finder(0, size - 7)
    finder(size - 7, 0)
    for i in range(8, size - 8):                              # Taktlinien
        m[6][i] = m[i][6] = i % 2 == 0
    al = _ALIGN[v]
    for r in al:
        for c in al:
            if (r, c) in ((al[0], al[0]), (al[0], al[-1]), (al[-1], al[0])):     # Ecken mit Findern
                continue
            for dr in range(-2, 3):
                for dc in range(-2, 3):
                    m[r + dr][c + dc] = max(abs(dr), abs(dc)) != 1
    m[size - 8][8] = True                                     # dunkles Modul
    for i in range(9):                                        # Format-Bereiche reservieren
        if m[8][i] is None:
            m[8][i] = False
        if m[i][8] is None:
            m[i][8] = False
    for i in range(8):
        if m[8][size - 1 - i] is None:
            m[8][size - 1 - i] = False
        if m[size - 1 - i][8] is None:
            m[size - 1 - i][8] = False
    if v >= 7:
        for i in range(6):
            for j in range(3):
                m[i][size - 11 + j] = False
                m[size - 11 + j][i] = False
    return m


def _place(m, cw):
    size = len(m)
    bits = [(b >> i) & 1 for b in cw for i in range(7, -1, -1)]
    fixed = [[x is not None for x in row] for row in m]
    i = 0
    c = size - 1
    up = True
    while c > 0:
        if c == 6:
            c -= 1
        rows = range(size - 1, -1, -1) if up else range(size)
        for r in rows:
            for cc in (c, c - 1):
                if not fixed[r][cc]:
                    m[r][cc] = bool(bits[i]) if i < len(bits) else False
                    i += 1
        up = not up
        c -= 2
    return fixed


def _apply(m, fixed, mask, v):
    size = len(m)
    out = [row[:] for row in m]
    f = _MASKS[mask]
    for r in range(size):
        for c in range(size):
            if not fixed[r][c] and f(r, c):
                out[r][c] = not out[r][c]
    fb = _format_bits(mask)
    bit = [bool((fb >> i) & 1) for i in range(15)]
    for i in range(6):                                        # erste Kopie (um den Finder oben links)
        out[i][8] = bit[i]
    out[7][8] = bit[6]
    out[8][8] = bit[7]
    out[8][7] = bit[8]
    for i in range(9, 15):
        out[8][14 - i] = bit[i]
    for i in range(8):                                        # zweite Kopie (oben rechts / unten links)
        out[8][size - 1 - i] = bit[i]
    for i in range(8, 15):
        out[size - 15 + i][8] = bit[i]
    out[size - 8][8] = True
    if v >= 7:
        vb = _version_bits(v)
        for i in range(18):
            bit = bool((vb >> i) & 1)
            a, b = i // 3, size - 11 + i % 3
            out[a][b] = bit
            out[b][a] = bit
    return out


def _penalty(m):
    size = len(m)
    p = 0
    for line in list(m) + [list(col) for col in zip(*m)]:
        run, prev = 0, None
        for x in line:
            if x == prev:
                run += 1
            else:
                if run >= 5:
                    p += 3 + run - 5
                run, prev = 1, x
        if run >= 5:
            p += 3 + run - 5
    for r in range(size - 1):
        for c in range(size - 1):
            x = m[r][c]
            if x == m[r][c + 1] == m[r + 1][c] == m[r + 1][c + 1]:
                p += 3
    pat1 = [True, False, True, True, True, False, True, False, False, False, False]
    pat2 = pat1[::-1]
    for line in list(m) + [list(col) for col in zip(*m)]:
        for i in range(len(line) - 10):
            seg = line[i:i + 11]
            if seg == pat1 or seg == pat2:
                p += 40
    dark = sum(sum(1 for x in row if x) for row in m)
    k = abs(dark * 20 - size * size * 10) // (size * size)
    p += k * 10
    return p


def matrix(text):
    """Text -> Liste von Zeilen (True = dunkel), ohne Ruhezone"""
    data = text.encode("utf-8")
    v = _choose(len(data))
    cw = _codewords(data, v)
    base = _base(v)
    fixed = _place(base, cw)
    best = None
    for mask in range(8):
        cand = _apply(base, fixed, mask, v)
        pen = _penalty(cand)
        if best is None or pen < best[0]:
            best = (pen, cand)
    return best[1]


def png(text, scale=8, border=4):
    """PNG-Bytes (schwarz auf weiss) - zum Speichern bzw. fuer Tests"""
    m = matrix(text)
    n = len(m) + 2 * border
    rows = []
    for y in range(n * scale):
        r = y // scale - border
        line = bytearray([0])
        for x in range(n * scale):
            c = x // scale - border
            dark = 0 <= r < len(m) and 0 <= c < len(m) and m[r][c]
            line.append(0 if dark else 255)
        rows.append(bytes(line))
    raw = b"".join(rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    hdr = struct.pack(">IIBBBBB", n * scale, n * scale, 8, 0, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", hdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def widget(text, size=200):
    """Gtk.DrawingArea mit dem Code (weisser Grund, Ruhezone)"""
    from gi.repository import Gtk
    m = matrix(text)
    da = Gtk.DrawingArea(content_width=size, content_height=size, halign=Gtk.Align.CENTER)

    def draw(area, cr, w, h):
        n = len(m) + 8
        s = min(w, h) / n
        ox, oy = (w - s * n) / 2, (h - s * n) / 2
        cr.set_source_rgb(1, 1, 1)
        cr.rectangle(ox, oy, s * n, s * n)
        cr.fill()
        cr.set_source_rgb(0, 0, 0)
        for r, row in enumerate(m):
            for c, x in enumerate(row):
                if x:
                    cr.rectangle(ox + (c + 4) * s, oy + (r + 4) * s, s + 0.3, s + 0.3)
        cr.fill()
    da.set_draw_func(draw)
    return da
