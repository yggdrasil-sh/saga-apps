"""2.5 Fehlerberichte und "Diagnose senden" (nur wenn /api/version features "clientlog1" meldet; Vertrag docs/APP-API.md
"Fehlerberichte und Diagnose", Server-Bereinigung saga/clientlog.py).

Reine Logik (ohne GTK, getestet in tests/test_clientlog.py):
- scrub: Fehlertexte ohne Inhalte (wie saga/clientlog.py) - URLs, E-Mail, Schluessel, Zitate, Hosts, Dateien, Pfade,
  Handles, UUIDs, Hex, Zufallsfolgen, Zahlen -> Platzhalter; Woerter, die nicht nach Programm aussehen -> <w>
- endpoint: Endpunkt-Muster (/api/notes/:id/comments) - nur bekannte feste Pfadwoerter bleiben stehen
- Reporter: Eintraege sammeln, gleiche zaehlen (n), hoechstens 20 je Bericht, 1 Bericht je 30 s, 404/429/401 beachten
- Ring: die letzten 200 Ereignisse fuer "Diagnose senden" (nur im Speicher)
- Stack: nur Datei:Zeile + Modul.Funktion, Pfade auf Dateinamen gekuerzt; Absturz-Notiz (faulthandler bzw. Ausnahme
  ausserhalb der Hauptschleife) -> beim naechsten Start ein Bericht {k: "crash"}
- JSON-Form von Kopf, Bericht und Diagnose samt Groessengrenzen

Laufzeit (ClientLog, Instanz `log`): sys.excepthook/threading.excepthook -> {k: "error"}, API-Client (5xx, unlesbares
JSON), Speichern (Konflikt/offline/Fehler), Ansicht, Live-Kanal. Ohne Feature wird nichts gesendet und nichts fuer
einen Bericht gesammelt; der Ringpuffer bleibt im Speicher und geht nur ueber "Diagnose senden" hinaus."""
import collections
import json
import os
import re
import secrets
import sys
import threading
import time

FEATURE = "clientlog1"
LOG_PATH, DIAG_PATH = "api/client/log", "api/client/diag"
RATE_S = 30.0                       # hoechstens ein Bericht je 30 s
ITEMS_MAX, RING_MAX, REPORTS_MAX = 20, 200, 40
LOG_BYTES, DIAG_BYTES = 15000, 60000            # Server: 16 KB bzw. 64 KB (etwas Luft)
MSG_MAX, NOTE_MAX, STACK_MAX, STACK_LINES, LINE_MAX = 300, 1000, 2000, 20, 200
T_MAX = 7 * 24 * 3600 * 1000
APPS = ("web", "ios", "mac", "android", "linux")
KINDS = ("error", "unhandled", "crash", "anr", "warn")
CONN_CH = ("sse", "poll", "ws", "collab", "push", "call")
CONN_ST = ("open", "closed", "error", "poll", "retry")
SAVE_ST = ("ok", "conflict", "offline", "error", "merged")
LIFE_ST = ("start", "hidden", "visible", "online", "offline", "resume")
METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
DIAG_ALPHA = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
_SCREEN = re.compile(r"[a-z][a-z0-9\-]{0,23}")
_DIAG_ID = re.compile("D-[" + DIAG_ALPHA + "]{5}")

# --- Bereinigung (wie saga/clientlog.py scrub, ohne Saga-Kataloge) ---------------------------------------------------
_CTRL = re.compile("[\\x00-\\x1f\\x7f-\\x9f\\u200b-\\u200f\\u2028\\u2029\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")
_URL = re.compile(r"\b[a-z][a-z0-9+.\-]{1,15}://[^\s\"'<>()]*|\bwww\.[^\s\"'<>()]+|\b(?:blob|data|javascript|mailto|tel):[^\s\"'<>()]*", re.I)
_EMAIL = re.compile(r"[\w.+\-]+@[\w\-]+(?:\.[\w\-]+)+")
_SECRET = re.compile(r"\b(?:hkd?|sga|hk_test)_[A-Za-z0-9_\-]{6,}|\bBearer\s+\S+", re.I)
_TLD = ("com|org|net|de|at|ch|eu|io|info|biz|lan|local|localdomain|home|internal|intranet|intra|corp|arpa|cloud|online|"
        "site|xyz|co|me|tv|uk|fr|nl|be|it|es|pl|ru|th|jp|cn|us|ca|au|"
        "box|test|example|invalid|lab|localhost|onion|gov|edu|se|no|dk|fi|cz|pt|br|in|kr|tw|hk|sg|nz|ie|li|lu")   # Client: strenger als der Server
_HOST = re.compile(r"(?<![\w.\-/])(?:(?:[a-z0-9][a-z0-9\-]*\.)+(?:" + _TLD + r")|(?:[a-z0-9][a-z0-9\-]*\.)*[a-z0-9]*-[a-z0-9\-]*"
                   r"\.(?!(?:m?js|css|html?|json|kt|swift|java|py)\b)[a-z]{2,})(?![\w\-]|\.\w)", re.I)
_FILE = re.compile(r"[^\s\"'`<>()\[\]{}:;,\u201e\u201c\u201d\u00ab\u00bb]*\.(?:pdf|docx?|xlsx?|xlsm|pptx?|od[tspg]|rtf|txt|csv|md|zip|rar|7z|tar|gz|png|"
                   r"jpe?g|gif|webp|hei[cf]|bmp|svg|tiff?|mp[34]|m4[av]|wav|ogg|opus|flac|mov|webm|mkv|avi|epub|eml|vcf|ics|kdbx)(?![\w\-])", re.I)
_UUID = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", re.I)
_HEX = re.compile(r"\b(?:0x)?[0-9a-f]{8,}\b", re.I)
_TOKEN = re.compile(r"(?<![\w~])(?=[A-Za-z_\-+=]*\d)[A-Za-z0-9_\-+=]{20,}")
_HANDLE = re.compile(r"[~@][\w.\-]{2,}")
_PATH = re.compile(r"(?<![\w.:<])/[^\s\"'<>()]+")
_NUM = re.compile(r"(?<![\w$<])\d+(?:[.,]\d+)*|(?<=[A-Za-z_$])\d{3,}")
_QUOTE = re.compile("'([^'\\n]{0,200})'|\"([^\"\\n]{0,200})\"|`([^`\\n]{0,200})`|\u201e([^\u201c\u201d\\n]{0,200})[\u201c\u201d]"
                    "|\u201c([^\u201d\\n]{0,200})\u201d|\u00ab([^\u00bb\\n]{0,200})\u00bb|\u2018([^\u2019\\n]{0,200})\u2019")
_IDENT = re.compile(r"[A-Za-z_$][\w$.]{0,39}")
_PROP = re.compile(r"[a-z_$][\w$.]{0,39}")
_ALLOW = re.compile(r"[^\w\s.,:;()\[\]{}<>#'\"=+\-*/!?&|%$~^«»„“”–—…]")
_SPACES = re.compile(r"\s{2,}")
_WORD = re.compile(r"[^\W\d_][\w$]*(?:\.[\w$]+)*")
_DELIM = re.compile(r"(<[a-z]+>|\{\w+(?::[^{}]*)?\}|[:;,!?()\[\]{}\"'`\u00ab\u00bb\u201e\u201c\u201d\u2013\u2014\u2026|=]|\.(?=\s|$))")
_PROGRAM = re.compile(r"[.$_\d]|[a-z][A-Z]|(?:Error|Exception)$")
# Woerter englischer Fehlermeldungen (wie _ERR_WORDS in saga/clientlog.py)
ERR_WORDS = frozenset("""
a an the of to in on at by for from with without into is are was were be been not no null undefined nan true false
cannot can could read write set get call called properties property reading setting object function functions method
methods value values type types error errors exception failed fail failure load loading loaded fetch fetching network
request response resource attempting when while operation aborted abort timeout timed out quota exceeded has been
storage permission denied access allowed blocked origin cross script syntax unexpected token end input json parse
parsing invalid argument arguments range maximum stack size reference defined assignment constant variable
iterable iterator promise rejected resolved unhandled rejection uncaught internal unknown missing required expected
element elements node nodes child children parent document window insert remove removed append contains selector
execute executing constructor instance instanceof new length index offset count number string boolean array
map key keys message name code status http https ok closed close open opened connection websocket eventsource socket
media device devices stream streams track tracks play paused user gesture interaction not supported support disabled
enabled decode encode encoding decoding image audio video codec context lost webgl canvas worker service navigator
clipboard observer loop completed undelivered notifications limit is has have had does do did this that it its which
only already still yet must should please try again later offline online state crypto decrypt encrypt signature verify
verification module import export dynamically imported chunk database transaction version upgrade int double float
bool optional nil none self class struct enum protocol thread main queue signal fatal crash exit killed memory oom anr
warning critical
""".split())


def _quote(m):
    inner = next((g for g in m.groups() if g is not None), "")
    q = m.group(0)[0]
    if inner and _IDENT.fullmatch(inner):
        return m.group(0) if q in "'\"`" else "'" + inner + "'"
    return q + "<str>" + q if q in "'\"`" else '"<str>"'


def _word(m):
    w = m.group(0)
    if len(w) == 1 or _PROGRAM.search(w) or w.lower() in ERR_WORDS:
        return w
    return "<w>"


def _words(s):
    out, parts = [], _DELIM.split(s)
    for i, part in enumerate(parts):
        quoted = 0 < i < len(parts) - 1 and parts[i - 1] in ("'", '"', "`") and parts[i + 1] == parts[i - 1]
        if i % 2 or not part.strip() or (quoted and _PROP.fullmatch(part)):
            out.append(part)
        else:
            out.append(_WORD.sub(_word, part))
    return "".join(out)


def scrub(s, limit=MSG_MAX):
    """Fehlertext ohne Inhalte (hoechstens limit Zeichen). Der Server bereinigt noch einmal."""
    if not isinstance(s, str):
        return ""
    s = _CTRL.sub(" ", s[:4000])
    s = _URL.sub("<url>", s)
    s = _EMAIL.sub("<email>", s)
    s = _SECRET.sub("<secret>", s)
    s = _QUOTE.sub(_quote, s)
    s = _HOST.sub("<host>", s)
    s = _FILE.sub("<file>", s)
    s = _PATH.sub("<path>", s)
    s = _HANDLE.sub("<id>", s)
    s = _UUID.sub("<uuid>", s)
    s = _HEX.sub("<hex>", s)
    s = _TOKEN.sub("<tok>", s)
    s = _NUM.sub("<n>", s)
    s = _words(s)
    s = _ALLOW.sub("?", s)
    s = _SPACES.sub(" ", s).strip()
    return s[:limit]


def scrub_note(s):
    """Freitext der Person (nur Diagnose): Steuerzeichen weg (Zeilenumbrueche bleiben), Schluessel/Adressen/E-Mail
    maskiert, gekuerzt. Sonst unveraendert - die Person schreibt ihn bewusst fuer den Admin."""
    if not isinstance(s, str):
        return ""
    s = s.replace("\r\n", "\n")
    s = re.sub("[\\x00-\\x09\\x0b-\\x1f\\x7f-\\x9f\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]", " ", s)
    s = _SECRET.sub("<secret>", s)
    s = _URL.sub("<url>", s)
    s = _EMAIL.sub("<email>", s)
    s = re.sub(r"(?<![\w-])[A-Za-z0-9_\-+/=]{32,}", "<tok>", s)
    return s.strip()[:NOTE_MAX]


def screen(s):
    """grober Ort nach Vertrag ([a-z][a-z0-9-]{0,23}), sonst "other\""""
    return s if isinstance(s, str) and _SCREEN.fullmatch(s) else "other"


# --- Endpunkt-Muster -------------------------------------------------------------------------------------------------
# Feste Pfadwoerter der Saga-Endpunkte, die die App benutzt - alles andere wird :x (Zahlen :id)
ROUTE_WORDS = frozenset("""
account active ai albums api api-tokens app apps archive ask assets avatar backlinks backup blocks bundle calendar
calendars calls cancel caps cert chart chat chats client comments config confirm connect contacts dbs decline delete
device devices diag digest discard done duplicate empty events exchange export fed federation files flow forget gallery
generate graph handles hide history identity immich import info join key keys leave link list log me members messages
move nextcloud notes notes.cursor notifications page page-image pages pair people persona pins presence privacy
public-name publish push reactions read receipts recipients remote report request resolve restore resume rows run search
security seen services settings share shared shares signal sources src status subscriptions target targets tasks
tasksource templates test thumb today tokens trash typing unfurl unread uploads use v2 version versions widgets
""".split())


def endpoint(raw):
    """"api/notes/123/comments?x=1" bzw. "https://host/saga/api/notes/123" -> "/api/notes/:id/comments";
    None, wenn kein Saga-API-Pfad (api/, app/, fed/) darin steht"""
    p = str(raw or "")
    if "://" in p:
        p = p.split("://", 1)[1]
        p = p[p.index("/"):] if "/" in p else "/"
    p = p.split("?", 1)[0].split("#", 1)[0]
    segs = [s for s in p.split("/") if s]
    i = next((n for n, s in enumerate(segs) if s in ("api", "app", "fed")), None)
    if i is None:
        return None
    out = []
    for s in segs[i:i + 11]:
        out.append(":id" if re.fullmatch(r"-?\d+", s) else s if s in ROUTE_WORDS else ":x")
    return ("/" + "/".join(out))[:100]


def dotted(pattern):
    """Muster fuer Fehlertexte: "/api/notes/:id" -> "api.notes.ID" (bleibt bei der Server-Bereinigung erhalten)"""
    return ".".join("ID" if s == ":id" else "X" if s == ":x" else s for s in pattern.split("/") if s).replace("-", "_")


def _meth(m):
    return m if m in METHODS else "GET"


def http_message(code, method, pattern):
    """Fehlertext fuer eine 5xx-Antwort - ohne Server-Text (kann Inhalte enthalten)"""
    return f"HTTPError.status{int(code) // 100}xx: http.{_meth(method)} {dotted(pattern)}"


def json_message(method, pattern):
    """Antwort war kein JSON bzw. unlesbar"""
    return f"JSONDecodeError: http.{_meth(method)} {dotted(pattern)}"


def save_message(code):
    """Speichern abgelehnt (nicht 409/401/5xx): Statusklasse"""
    return f"SaveError.status{int(code) // 100}xx"


# --- Stack -----------------------------------------------------------------------------------------------------------
def _base(path):
    b = os.path.basename(str(path or ""))
    return b if re.fullmatch(r"[\w.\-]{1,60}", b) else "?"


def _func(name):
    n = str(name or "").replace(".<locals>.", ".")
    n = re.sub(r"<(\w+)>", r"\1", n)
    return re.sub(r"[^\w.]", "", n)[:80]


def frame_line(filename, lineno, func):
    """eine Stackzeile: "editor.py:612 editor.PageDoc.save" (Datei ohne Pfad, Modul.Funktion)"""
    b = _base(filename)
    mod = b[:-3] if b.endswith(".py") else re.sub(r"\W", "_", b)
    fn = _func(func)
    return f"{b}:{int(lineno or 0)} {mod}.{fn}" if fn else f"{b}:{int(lineno or 0)}"


def tb_frames(tb):
    """Traceback -> [(datei, zeile, funktion)] (aelteste zuerst)"""
    out = []
    while tb is not None:
        co = tb.tb_frame.f_code
        out.append((co.co_filename, tb.tb_lineno, getattr(co, "co_qualname", co.co_name)))
        tb = tb.tb_next
    return out


def stack_text(frames):
    """hoechstens STACK_LINES Zeilen (die innersten), hoechstens STACK_MAX Zeichen"""
    lines = [frame_line(*f) for f in frames][-STACK_LINES:]
    while lines and len("\n".join(lines)) > STACK_MAX:
        lines.pop(0)
    return "\n".join(lines) or None


def exc_message(exc):
    name = type(exc).__name__ if exc is not None else "Error"
    text = str(exc) if exc is not None else ""
    return scrub(f"{name}: {text}" if text else name) or "Error"


# --- Eintraege und Bericht -------------------------------------------------------------------------------------------
class Item:
    __slots__ = ("k", "msg", "stack", "src", "line", "screen", "n", "t")

    def __init__(self, k, msg, stack=None, src=None, line=None, screen=None, n=1, t=0):
        self.k = k if k in KINDS else "error"
        self.msg = scrub(msg) or "Error"
        self.stack = stack[:8000] if isinstance(stack, str) and stack else None
        self.src = _base(src) if src else None
        if self.src == "?":
            self.src = None
        self.line = int(line) if isinstance(line, int) and line >= 0 else None
        self.screen = screen
        self.n = max(1, int(n))
        self.t = t

    @property
    def key(self):
        return (self.k, self.msg, self.src or "", self.line or 0)

    def json(self):
        d = {"k": self.k, "msg": self.msg, "t": int(max(0, min(self.t, T_MAX)))}
        if self.stack:
            d["stack"] = self.stack
        if self.src:
            d["src"] = self.src
        if self.line is not None:
            d["line"] = self.line
        if self.screen:
            d["screen"] = screen(self.screen)
        if self.n > 1:
            d["n"] = self.n
        return d


class Reporter:
    """Sammeln, zaehlen, drosseln (Zeiten in Sekunden, monoton)."""

    def __init__(self):
        self.pending = []
        self.last_sent = float("-inf")
        self.blocked_until = float("-inf")
        self.sent = 0
        self.off = False                 # 404 (Schalter aus) bzw. zu viele Berichte: bis zum naechsten Start nichts

    def add(self, it):
        if self.off:
            return False
        for p in self.pending:
            if p.key == it.key:
                p.n += it.n
                return True
        if len(self.pending) >= ITEMS_MAX:
            return False
        self.pending.append(it)
        return True

    def wait(self, now):
        return max(0.0, self.last_sent + RATE_S - now, self.blocked_until - now)

    def due(self, now):
        return not self.off and bool(self.pending) and self.wait(now) == 0

    def take(self, now):
        if not self.due(now):
            return None
        if self.sent >= REPORTS_MAX:
            self.disable()
            return None
        items, self.pending = self.pending, []
        self.last_sent, self.sent = now, self.sent + 1
        return items

    def response(self, code, retry_s, items, now):
        """404 aus, 429 fruehestens nach retry_s (verwerfen), 401/keine Antwort: Eintraege zurueck (spaeter senden)"""
        if code == 404:
            self.disable()
        elif code == 429:
            try:
                r = float(retry_s)
            except (TypeError, ValueError):
                r = 60.0
            self.blocked_until = now + max(1.0, min(r, 86400.0))
        elif code in (401, 0):
            keys = {p.key for p in self.pending}
            back = [it for it in items if it.key not in keys]
            self.pending = (back + self.pending)[:ITEMS_MAX]
            if code == 401:
                self.last_sent = float("-inf")

    def disable(self):
        self.off, self.pending = True, []


def head(ver, os_label=None, sid=None, dev="desktop"):
    h = {"v": 1, "app": "linux", "ver": re.sub(r"[^A-Za-z0-9._+\-]", "", str(ver or ""))[:40] or "0", "sid": sid or new_sid()}
    o = re.sub(r"[^A-Za-z0-9._ ()+;/\-]", "", str(os_label or ""))[:60].strip()
    if o:
        h["os"] = o
    if dev in ("phone", "tablet", "desktop"):
        h["dev"] = dev
    return h


def new_sid():
    """Zufallskennung (16 Zeichen), neu je App-Start - nie aus Hardware-IDs"""
    a = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(a) for _ in range(16))


def os_label(paths=("/run/host/os-release", "/etc/os-release")):
    """System + Hauptversion ("Fedora 44", "Ubuntu 24"), sonst "Linux" (Flatpak: das System des Rechners)"""
    for p in paths:
        try:
            with open(p, encoding="utf-8") as f:
                d = dict(ln.rstrip("\n").split("=", 1) for ln in f if "=" in ln)
        except OSError:
            continue
        name = (d.get("NAME") or "").strip().strip('"')
        ver = (d.get("VERSION_ID") or "").strip().strip('"').split(".")[0]
        name = re.sub(r"[^A-Za-z0-9 ]", "", name).strip()[:30]
        ver = re.sub(r"[^A-Za-z0-9]", "", ver)[:10]
        if name:
            return (name + " " + ver).strip()
    return "Linux"


def _dumps(d):
    return json.dumps(d, separators=(",", ":"), ensure_ascii=False).encode()


def log_body(hd, items):
    """Bericht -> Bytes (hoechstens LOG_BYTES); zu gross: hintere Eintraege bzw. der Stack fallen weg"""
    lst = [it.json() for it in items[:ITEMS_MAX]]
    while lst:
        b = _dumps({**hd, "items": lst})
        if len(b) <= LOG_BYTES:
            return b
        if len(lst) == 1:
            one = dict(lst[0])
            one.pop("stack", None)
            return _dumps({**hd, "items": [one]})
        lst.pop()
    return None


class Ring:
    def __init__(self, cap=RING_MAX):
        self.events = collections.deque(maxlen=cap)

    def push(self, k, t, **f):
        e = {"t": int(max(0, min(t, T_MAX))), "k": k}
        e.update({a: v for a, v in f.items() if v is not None and v != ""})
        self.events.append(e)

    def net(self, method, pattern, status, ms, t):
        self.push("net", t, m=_meth(method), p=pattern, s=max(0, min(int(status), 999)), ms=None if ms is None else int(max(0, ms)))

    def err(self, it):
        self.push("err", it.t, kind=it.k, msg=it.msg, src=it.src, line=it.line,
                  screen=screen(it.screen) if it.screen else None)

    def clear(self):
        self.events.clear()

    def __len__(self):
        return len(self.events)


def diag_body(hd, events, note=""):
    """Diagnose -> Bytes (hoechstens DIAG_BYTES); zu gross: aelteste Ereignisse zuerst weg"""
    ev = list(events)[-RING_MAX:]
    n = scrub_note(note)
    while True:
        d = {**hd, "ev": ev}
        if n:
            d["note"] = n
        b = _dumps(d)
        if len(b) <= DIAG_BYTES or not ev:
            return b
        ev = ev[max(1, len(ev) // 10):]


def diag_id(j):
    """Antwort von /api/client/diag -> Kennung D-XXXXX (nur gueltige Zeichen) oder None"""
    i = j.get("id") if isinstance(j, dict) else None
    return i if isinstance(i, str) and _DIAG_ID.fullmatch(i) else None


# --- Absturz-Notiz ---------------------------------------------------------------------------------------------------
# Zwei Quellen schreiben in dieselbe Datei: faulthandler (harte Abbrueche: SIGSEGV/SIGABRT/... - eigenes Format, volle
# Pfade, nur lokal) und exc_note (Ausnahme, die die App beendet). Beim naechsten Start -> crash_item.
SIGNALS = {"segmentation fault": "SIGSEGV", "aborted": "SIGABRT", "bus error": "SIGBUS", "illegal instruction": "SIGILL",
           "floating-point exception": "SIGFPE", "floating point exception": "SIGFPE"}
_FH_FILE = re.compile(r'^\s*File "([^"]*)", line (\d+) in (\S+)')


def exc_note(exc, tb):
    """Text der Notiz fuer eine Ausnahme: "EXC <bereinigt>" + Stackzeilen (schon gekuerzt)"""
    st = stack_text(tb_frames(tb)) or ""
    return "EXC " + exc_message(exc).replace("\n", " ") + "\n" + st + "\n"


def crash_item(text, t=0):
    """Notiz -> Item(k="crash") oder None (leer/unlesbar)"""
    if not isinstance(text, str) or not text.strip():
        return None
    lines = text.replace("\r", "").split("\n")
    first = lines[0].strip()
    if first.startswith("EXC "):
        # Stackzeilen kamen aus frame_line - zur Sicherheit noch einmal auf genau dieses Format pruefen
        ok = [ln.strip() for ln in lines[1:] if re.fullmatch(r"[\w.\-?]{1,60}:\d{1,7}( [\w.]{1,80})?", ln.strip())]
        return Item("crash", first[4:], stack="\n".join(ok[-STACK_LINES:])[:STACK_MAX] or None, t=t)
    m = re.search(r"Fatal Python error:\s*([^\n]*)", text)
    if not m:
        return None
    what = m.group(1).strip().lower()
    sig = next((v for k, v in SIGNALS.items() if what.startswith(k)), None)
    frames = []
    for ln in lines:
        fm = _FH_FILE.match(ln)
        if fm:
            frames.append((fm.group(1), int(fm.group(2)), fm.group(3)))
    frames.reverse()                                    # faulthandler: innerste zuerst -> wie Tracebacks aelteste zuerst
    return Item("crash", "Signal." + sig if sig else "FatalError.other", stack=stack_text(frames), t=t)


# --- Laufzeit --------------------------------------------------------------------------------------------------------
def _route_screen(route):
    k = route[0] if route else None
    return {"page": "pages", "tasks": "tasks", "events": "calendar", "services": "services", "graph": "graph",
            "chats": "chat", "chat": "chat"}.get(k, "dashboard")


class ClientLog:
    """Sammeln und Senden (Haupt-Thread; net/report/exception auch aus Hintergrund-Threads)."""

    def __init__(self):
        self.t0 = time.monotonic()
        self.sid = new_sid()
        self.reporter = Reporter()
        self.ring = Ring()
        self.lock = threading.RLock()
        self.screen = "dashboard"
        self.base, self.overlays = "dashboard", []
        self._timer = 0
        self._sending = False
        self._os = None
        self.crash_note = None          # Notiz des letzten Laufs (beim Start gelesen)
        self.ring.push("life", 0, st="start")

    def now_ms(self):
        return (time.monotonic() - self.t0) * 1000

    @staticmethod
    def on():
        from .store import store
        return store.has(FEATURE)

    # Ringpuffer
    def ev(self, k, **f):
        with self.lock:
            self.ring.push(k, self.now_ms(), **f)

    def view(self, route):
        self.base = _route_screen(route)
        self._place()

    def overlay(self, name, shown):
        name = screen(name)
        self.overlays = [o for o in self.overlays if o != name] + ([name] if shown else [])
        self._place()

    def _place(self):
        s = self.overlays[-1] if self.overlays else self.base
        if s != self.screen:
            self.screen = s
            self.ev("view", screen=s)

    def life(self, st):
        if st in LIFE_ST:
            self.ev("life", st=st)

    def conn(self, ch, st):
        if ch in CONN_CH and st in CONN_ST:
            self.ev("conn", ch=ch, st=st)

    def save(self, st, status=None):
        if st in SAVE_ST:
            self.ev("save", st=st, s=status if isinstance(status, int) and 0 <= status <= 999 else None)

    def net(self, method, path, status, ms=None):
        """Antwort einer API-Anfrage (beliebiger Thread): Status je Endpunkt-Muster; 5xx als Fehlerbericht"""
        p = endpoint(path)
        if p is None or p.startswith("/api/client/"):
            return
        with self.lock:
            self.ring.net(method, p, status, ms, self.now_ms())
        if status >= 500:
            self.report("error", http_message(status, method, p))

    def json_failed(self, method, path):
        p = endpoint(path)
        if p is not None and not p.startswith("/api/client/"):
            self.report("error", json_message(method, p))

    def report(self, k, msg, stack=None, src=None, line=None):
        """Fehler aufnehmen (bereinigt; beliebiger Thread). Ohne Feature nur in den Ringpuffer."""
        with self.lock:
            it = Item(k, msg, stack=stack, src=src, line=line, screen=self.screen, t=self.now_ms())
            self.ring.err(it)
        try:
            on = self.on()
        except Exception:  # noqa: BLE001 - beim Beenden/frueh beim Start
            on = False
        if not on:
            return
        with self.lock:
            added = self.reporter.add(it)
        if added:
            self._later()

    def exception(self, exc, tb, k="error"):
        frames = tb_frames(tb)
        src, line = (frames[-1][0], frames[-1][1]) if frames else (None, None)
        self.report(k, exc_message(exc), stack=stack_text(frames), src=src, line=line)

    def reset(self):
        """Server/Konto gewechselt: nichts aus der alten Anmeldung mitnehmen"""
        with self.lock:
            self.reporter.pending = []
            self.ring.clear()

    def ready(self):
        """/api/version ist bekannt: Absturz-Notiz senden (mit Feature) bzw. verwerfen (ohne)"""
        note, self.crash_note = self.crash_note, None
        if not self.on():
            return
        it = crash_item(note, self.now_ms()) if note else None
        if it is not None:
            with self.lock:
                self.reporter.add(it)
        self._later()

    def _later(self):
        from gi.repository import GLib
        GLib.idle_add(self._schedule)

    def _schedule(self):
        if self._timer:
            return False
        with self.lock:
            if not self.reporter.pending or self.reporter.off:
                return False
            wait = max(1.5, self.reporter.wait(time.monotonic()))    # kurz sammeln (Folgefehler in einem Bericht)
        from gi.repository import GLib
        self._timer = GLib.timeout_add(int(wait * 1000), self._fire)
        return False

    def _fire(self):
        self._timer = 0
        self._flush()
        return False

    def head(self):
        from . import version_label
        if self._os is None:
            self._os = os_label()
        return head(version_label(), self._os, self.sid)

    def _flush(self):
        from .api import APIError, client
        from .store import store
        from .util import bg
        if self._sending or not self.on():
            return
        if store.needs_login:
            from gi.repository import GLib
            self._timer = GLib.timeout_add_seconds(int(RATE_S), self._fire)
            return
        now = time.monotonic()
        with self.lock:
            items = self.reporter.take(now)
        if not items:
            self._schedule()
            return
        body = log_body(self.head(), items)
        if body is None:
            return
        self._sending = True

        def fin(code, retry=None):
            self._sending = False
            with self.lock:
                self.reporter.response(code, retry, items, time.monotonic())
            self._schedule()

        def err(e):
            if isinstance(e, APIError):
                fin(e.code, (e.data or {}).get("retry_s") if isinstance(e.data, dict) else None)
            else:
                fin(0)
        bg(client.request, "POST", LOG_PATH, None, body, "application/json", 15,
           done=lambda _r: fin(200), error=err)

    # "Diagnose senden" (nur auf Knopfdruck)
    def send_diag(self, note, done):
        """done(art, wert): ("sent", "D-XXXXX") | ("off", None) | ("too_many", None) | ("failed", text)"""
        from .api import APIError, client
        from .util import bg
        if not self.on():
            done("off", None)
            return
        with self.lock:
            body = diag_body(self.head(), list(self.ring.events), note)

        def fin(out):
            try:
                j = json.loads(out) if out else None
            except ValueError:
                j = None
            i = diag_id(j)
            done(*(("sent", i) if i else ("failed", "")))

        def err(e):
            code = e.code if isinstance(e, APIError) else 0
            if code == 404:
                done("off", None)
            elif code == 429:
                done("too_many", None)
            else:
                done("failed", str(e))
        bg(client.request, "POST", DIAG_PATH, None, body, "application/json", 20, done=fin, error=err)


log = ClientLog()


# --- Einrichten (main.py) --------------------------------------------------------------------------------------------
_note_file = None
_keep = False                       # Notiz geschrieben: beim Beenden nicht loeschen


def _note_path():
    from gi.repository import GLib
    get = getattr(GLib, "get_user_state_dir", None) or GLib.get_user_cache_dir
    d = os.path.join(get(), "hlidskjalf")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "crash-note.txt")


def install():
    """frueh beim Start: Notiz des letzten Laufs einlesen, Datei leer neu oeffnen, faulthandler + excepthooks setzen"""
    global _note_file
    if _note_file is not None:
        return
    try:
        path = _note_path()
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                text = f.read(32000)
            if text.strip():
                log.crash_note = text
        except OSError:
            pass
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        _note_file = os.fdopen(fd, "w", encoding="utf-8")
        import faulthandler
        faulthandler.enable(file=_note_file, all_threads=False)
        import atexit
        atexit.register(_clean_exit, path)
    except Exception as e:  # noqa: BLE001 - ohne Notiz-Datei: nur laufende Fehler
        print("hk: Absturz-Notiz nicht verfuegbar:", type(e).__name__, file=sys.stderr)
    prev_hook, prev_thread = sys.excepthook, threading.excepthook

    def hook(t, v, tb):
        try:
            if not issubclass(t, (KeyboardInterrupt, SystemExit)):
                log.exception(v, tb)
        except Exception:  # noqa: BLE001 - der Haken darf nie selbst scheitern
            pass
        prev_hook(t, v, tb)

    def thread_hook(args):
        try:
            if args.exc_type is not None and not issubclass(args.exc_type, SystemExit):
                log.exception(args.exc_value, args.exc_traceback)
        except Exception:  # noqa: BLE001
            pass
        prev_thread(args)
    sys.excepthook = hook
    threading.excepthook = thread_hook


def note_exception(exc, tb):
    """Ausnahme beendet die App (ausserhalb der Hauptschleife): Notiz fuer den naechsten Start"""
    global _keep
    try:
        if _note_file is not None:
            _note_file.write(exc_note(exc, tb))
            _note_file.flush()
            _keep = True
    except Exception:  # noqa: BLE001
        pass


def _clean_exit(path):
    """normales Ende: leere Notiz entfernen (faulthandler schreibt nur bei harten Abbruechen hinein)"""
    try:
        import faulthandler
        faulthandler.disable()
        if _note_file is not None and not _keep:
            _note_file.close()
            if os.path.getsize(path) == 0:
                os.unlink(path)
    except (OSError, ValueError, AttributeError):
        pass
