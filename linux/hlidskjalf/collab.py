"""Live mitschreiben (2.5, P3 Stufe 2): WS /api/collab (docs/APP-API-COLLAB.md) mit pycrdt - Verbindung, Abo je Seite,
Bindung Block-JSON der App <-> Y.Doc. Ohne GTK (nur GLib fuer den Haupt-Thread), testbar.

- Nur wenn der Server "live1" meldet, pycrdt da ist und die Seite passt (eigener Server, Block-Seite, keine Datenbank,
  keine Altformen) - sonst bleibt alles bei Stufe 1 (PATCH merge: 1) wie bisher.
- Dokumentmodell: collabmodel.py = unveraenderte Kopie von saga/collab_model.py (Schema saga-pm v2): dieselbe Abbildung
  wie Server und Web (Fragment "prosemirror", Wurzeln title/meta). Lokale Eingaben: je Aenderung sofort eine
  Y-Transaktion (kleinste Aenderung; im bearbeiteten Textfeld genau an der Schreibmarke) -> update an den Server.
  Fremde Aenderungen: Y -> Block-JSON (unveraenderte Bloecke bleiben dieselben Objekte) -> die Ansicht zeichnet nur
  geaenderte Bloecke neu, die Schreibmarke bleibt.
- R1: abweichendes Schema bzw. ein Stand, den die App beim Laden umschreiben wuerde -> nur lesen, NICHTS senden.
- reject -> Neuaufbau aus dem Vollzustand (eigene unabhaengige Aenderungen bleiben); 3x in 60 s -> Stufe 1.
- Verbindung weg: weiter in das lokale Doc schreiben; kommt sie binnen OFFLINE_GRACE wieder (Neustart 1012), wird mit
  gen + Zustandsvektor neu abonniert und das Fehlende als sync2 nachgereicht; sonst Rueckfall Stufe 1 (PATCH merge).
- Undo/Redo: pycrdt UndoManager nur fuer eigene Aenderungen (fremde kommen mit Herkunft REMOTE).
- IME: die Eingabe (Preedit) steht in GTK4 nicht im Puffer - gesendet wird erst der fertige Text (commit)."""
import json
import random
import threading
import time
from difflib import SequenceMatcher

from gi.repository import GLib

from . import collabws as W
from .collabws import Closed

REMOTE = 0x5A6A0001              # Herkunft eingehender Updates (nicht senden, nicht ins Undo)
LOCAL = 0x5A6A0002               # Herkunft eigener Eingaben (Undo)
PING_IDLE = 25.0                 # Sekunden Ruhe -> ping
PONG_WAIT = 10.0                 # ohne pong: Verbindung tot
OFFLINE_GRACE = 15.0             # so lange darf die Verbindung weg sein, bevor auf Stufe 1 gewechselt wird
REJECT_WINDOW = 60.0
REJECT_MAX = 3
CAPS = "inline2,cols6,merge1"
PERMANENT = (4403, 4400, 4426)   # Live aus / Origin / Protokoll: bis zum naechsten /api/version nicht mehr versuchen

_M = None                        # collabmodel (lazy: braucht pycrdt)
_M_ERR = None


def model():
    """collabmodel oder None (pycrdt fehlt bzw. passt nicht) - dann bleibt die App bei Stufe 1"""
    global _M, _M_ERR
    if _M is None and _M_ERR is None:
        try:
            from . import collabmodel as m
            m.Doc  # noqa: B018 - pycrdt wirklich geladen
            _M = m
        except Exception as e:  # noqa: BLE001 - ImportError, fehlende anyio usw.
            _M_ERR = e
    return _M


def available():
    return model() is not None


def schema_info():
    m = model()
    return {"model": m.MODEL, "ver": m.SCHEMA_VER, "hash": m.SCHEMA_HASH}


# --- Eignung (wie static/pm-live.js eligible/legacy) ------------------------------------------------------------------
def legacy(blocks, depth=0):
    """Seiten mit Altformen bleiben bei Stufe 1: Toggle {text} ohne blocks, Kopie eines synchronisierten Blocks (data.src) -
    live saehen sie anders aus bzw. ihr Inhalt fehlt im Zustand (synced_ref)"""
    if not isinstance(blocks, list) or depth > 12:
        return False
    for b in blocks:
        d = b.get("data") if isinstance(b, dict) else None
        if not isinstance(d, dict):
            continue
        if b.get("type") == "toggle" and isinstance(d.get("text"), str) and not isinstance(d.get("blocks"), list):
            return True
        if b.get("type") == "synced" and (d.get("src") or not isinstance(d.get("blocks"), list)):
            return True
        if legacy(d.get("blocks"), depth + 1):
            return True
        for c in d.get("cols") or []:
            if isinstance(c, dict) and legacy(c.get("blocks"), depth + 1):
                return True
    return False


# --- Texte fuer Verweise/Erwaehnungen (der Zustand traegt keine, APP-API-COLLAB 5.2.6) ----------------------------------
class Resolver:
    """Wie der Server (saga/collab.py _Resolve): bisheriger Text an dieser Stelle (k-ter Verweis im Block), sonst der Titel
    aus der Seitenliste bzw. der bekannte Anzeigename, sonst "Seite ohne Zugriff" bzw. leer. titles(pid) -> str|None,
    names(ref) -> str|None"""
    import re as _re
    _A = _re.compile(r'<a href="/notes#([1-9][0-9]{0,17})">(.*?)</a>', _re.S)
    _MN = _re.compile(r'<span class="hk-mention" data-u="([^"]{1,300})">(.*?)</span>', _re.S)

    def __init__(self, titles=None, names=None, no_access="Seite ohne Zugriff"):
        self.titles, self.names, self.no_access = titles, names, no_access
        self.texts = {}

    def learn(self, blocks):
        """bekannte Texte aus einem Block-Stand (GET bzw. eigene Anzeige)"""
        self.texts = {}

        def walk(v, bid):
            if isinstance(v, dict):
                if isinstance(v.get("id"), str) and isinstance(v.get("type"), str):
                    bid = v["id"]
                for x in v.values():
                    walk(x, bid)
            elif isinstance(v, list):
                for x in v:
                    walk(x, bid)
            elif isinstance(v, str) and ("/notes#" in v or "hk-mention" in v):
                for m in self._A.finditer(v):
                    self.texts.setdefault(("p", bid, int(m.group(1))), []).append(m.group(2))
                    self.texts.setdefault(("p", None, int(m.group(1))), []).append(m.group(2))
                for m in self._MN.finditer(v):
                    self.texts.setdefault(("m", bid, m.group(1)), []).append(m.group(2))
                    self.texts.setdefault(("m", None, m.group(1)), []).append(m.group(2))
        walk(blocks, None)

    def page(self, bid, pid, i):
        from html import escape
        try:
            pid = int(pid)
        except (TypeError, ValueError):
            pid = 0
        t = self.titles(pid) if self.titles else None
        if t is not None:
            return escape(t, quote=False)
        lst = self.texts.get(("p", bid, pid)) or self.texts.get(("p", None, pid))
        if lst:
            return lst[min(i, len(lst) - 1)]
        return escape(self.no_access, quote=False)

    def mention(self, bid, u, i):
        from html import escape
        lst = self.texts.get(("m", bid, u)) or self.texts.get(("m", None, u))
        if lst:
            return lst[min(i, len(lst) - 1)]
        n = self.names(u) if self.names else None
        return escape(n, quote=False) if n else ""


# --- Bindung (ohne Netz, testbar) -------------------------------------------------------------------------------------
def new_doc():
    m = model()
    while True:
        d = m.Doc(client_id=random.randint(2, 2 ** 31 - 1))     # 1 = deterministischer Anfangszustand des Servers
        d.get("meta", type=m.Map)
        d.get(m.FRAGMENT, type=m.XmlFragment)
        d.get("title", type=m.Text)
        return d


def read(doc, resolver, base_blocks):
    """Y -> (Bloecke, Titel); unveraenderte Bloecke (gleiche Normalform) sind die Objekte aus base_blocks"""
    out, title = model().read(doc, resolver, {"blocks": base_blocks} if base_blocks is not None else None)
    return out["blocks"], title


def _find_el(parent, bid, depth=0):
    """(Eltern, Index, Element) des Blockknotens mit id=bid (auch verschachtelt) oder None"""
    m = model()
    if depth > m.MAX_DEPTH + 8:
        return None
    for i, ch in enumerate(list(parent.children)):
        if not isinstance(ch, m.XmlElement):
            continue
        try:
            if ch.attributes.get("id") == bid and ch.tag in m.BLOCKS:
                return parent, i, ch
        except Exception:  # noqa: BLE001
            pass
        if ch.tag in m.INLINE_CONTENT:
            continue
        r = _find_el(ch, bid, depth + 1)
        if r is not None:
            return r
    return None


def _depth_of(frag, el):
    d, p = 0, el
    while p is not None and p is not frag:
        try:
            p = p.parent
        except Exception:  # noqa: BLE001
            break
        d += 1
    return d


def _text_kids(el):
    m = model()
    kids = list(el.children)
    return kids if len(kids) == 1 and isinstance(kids[0], m.XmlText) else None


def _hinted(el, cur, tgt, caret):
    """Genau an der Schreibmarke einfuegen/loeschen (statt Differenz raten, wichtig bei gleichen Buchstaben und
    gleichzeitigem Schreiben). Nur reiner Text ohne Inline-Knoten; sonst False (dann gleicht write() ab)."""
    m = model()
    if caret is None or not all(x.get("type") == "text" for x in cur + tgt):
        return False
    kids = list(el.children)
    if len(kids) > 1 or (kids and not isinstance(kids[0], m.XmlText)):
        return False
    a, b = m._chars(cur), m._chars(tgt)
    sa, sb = "".join(c for c, _ in a), "".join(c for c, _ in b)
    dlen = len(sb) - len(sa)
    if dlen > 0:
        s = caret - dlen
        if s < 0 or a[:s] != b[:s] or a[s:] != b[caret:]:
            return False
        t = kids[0] if kids else el.children.append(m.XmlText())
        pos = len(sa[:s].encode("utf-8"))
        k = s
        names = set(m.MARK_RANK) | {mk for _c, mj in a for mk in json.loads(mj)}
        while k < caret:
            mj = b[k][1]
            e = k
            while e < caret and b[e][1] == mj:
                e += 1
            want = json.loads(mj)
            attrs = {n: want.get(n) for n in names}         # None: geerbte Marke des Nachbarn nicht uebernehmen
            t.insert(pos, sb[k:e], attrs)
            pos += len(sb[k:e].encode("utf-8"))
            k = e
        return True
    if dlen < 0 and kids:
        s = caret
        if s < 0 or a[:s] != b[:s] or a[s - dlen:] != b[s:]:
            return False
        p1 = len(sa[:s].encode("utf-8"))
        ln = len(sa[s:s - dlen].encode("utf-8"))
        del kids[0][p1:p1 + ln]
        return True
    return False


def write_local(doc, blocks, title, hint=None, caret=None):
    """Eigenen Stand in EINER Transaktion (Herkunft LOCAL) ins Doc. hint: der Block (dict), in dem getippt wurde - dann nur
    dieser Knoten (schnell, bei grossen Seiten), caret: Zeichenposition nach der Eingabe im Feld (Textfelder mit einem
    Feld). Danach passt das Doc garantiert zum Stand (Abgleich wie write())."""
    m = model()
    frag = doc.get(m.FRAGMENT, type=m.XmlFragment)
    with doc.transaction(origin=LOCAL):
        done = hint == "title"                          # nur der Titel wurde getippt
        if not done and isinstance(hint, dict) and isinstance(hint.get("id"), str):
            r = _find_el(frag, hint["id"])
            if r is not None:
                parent, idx, el = r
                tgt = m.block_to_pm(hint)
                cur = m.y_to_pm(el)
                if cur.get("type") == tgt.get("type"):
                    if tgt["type"] in m.INLINE_CONTENT and caret is not None and \
                            (cur.get("attrs") or {}) == (tgt.get("attrs") or {}) and \
                            _hinted(el, cur.get("content") or [], tgt.get("content") or [], caret):
                        cur = m.y_to_pm(el)
                    m._sync_el(parent, idx, el, cur, tgt, _depth_of(frag, el))
                    done = True
        if not done:
            m._sync_list(frag, m.doc_to_pm({"blocks": blocks})["content"], 0)
        if title is not None:
            tt = doc.get("title", type=m.Text)
            cur = str(tt)
            if cur != title:
                m._sync_plain(tt, cur, title)


def pending_changes(doc, blocks, resolver, title=None, only=None):
    """Wuerde write_local etwas aendern? (R1: die App darf beim Laden/Anzeigen nichts umschreiben) -> Liste der Block-IDs
    (bzw. "title"). only: nur diese IDs pruefen (schnell)."""
    m = model()
    frag = doc.get(m.FRAGMENT, type=m.XmlFragment)
    y = [ch for ch in frag.children]
    bad = []
    if only is None and len(y) != len(blocks):
        bad.append("count")
    by = {}
    for ch in y:
        try:
            by[ch.attributes.get("id")] = ch
        except Exception:  # noqa: BLE001
            pass
    for i, b in enumerate(blocks):
        bid = b.get("id") if isinstance(b, dict) else None
        if only is not None and bid not in only:
            continue
        el = by.get(bid)
        if el is None:
            bad.append(bid)
            continue
        try:
            have = m.pm_to_block(m.y_to_pm(el), resolver, 0, None)
            want = m.canon_block(b, resolver)
        except m.ModelError:
            bad.append(bid)
            continue
        if not m._same(have, want):
            bad.append(bid)
    if title is not None and str(doc.get("title", type=m.Text)) != title:
        bad.append("title")
    return bad


def map_offset(old, new, off):
    """Schreibmarke (Zeichen) von old nach new verschieben (fremder Text davor/dahinter)"""
    if old == new:
        return off
    for tag, i1, i2, j1, j2 in SequenceMatcher(None, old, new, autojunk=False).get_opcodes():
        if tag == "equal" and i1 <= off <= i2:
            return j1 + off - i1
        if tag != "equal" and i1 <= off < i2:
            return j1 if tag == "delete" else min(j2, j1 + off - i1)
    return min(off, len(new))


def update_state_ok(doc_sv, upd):
    """Haengt upd nur an Eintraegen, die das Doc schon kennt? (sonst waere es im Doc 'pending')"""
    from pycrdt import get_state
    try:
        want = W.state_vector(get_state(upd))
        have = W.state_vector(doc_sv)
    except (ValueError, IndexError):
        return False
    return all(have.get(c, 0) >= k for c, k in want.items())


# =====================================================================================================================
# Verbindung (eine je App bzw. Konto; mehrere Seiten)
# =====================================================================================================================
class Connection:
    """WS /api/collab. Lesen im Hintergrund-Thread, alles weitere im Haupt-Thread (GLib.idle_add). url(): ws(s)-Adresse,
    headers(): Anmeldung (Bearer hkd_... bzw. Sitzungs-Cookie) - ohne Origin (App)."""

    def __init__(self, url, headers, on_auth=None):
        self.url, self.headers, self.on_auth = url, headers, on_auth
        self.sessions = {}               # note_id -> Session
        self.ws = None
        self.open = False                # hello (note 0) erhalten
        self.hello = None
        self.off = False                 # 4403/4400/4426: bis reset() nicht mehr versuchen
        self.fails = 0
        self.delay = 1.0
        self.gen = 0
        self._q = []
        self._qc = threading.Condition()
        self._timer = 0
        self.last_rx = 0.0
        self.ping_at = 0.0
        self.closed_code = None
        self.hold_until = 0.0
        self.stats = {"sent": 0, "recv": 0, "acked": 0, "rejects": 0, "connects": 0}

    # --- Steuerung (Haupt-Thread) ---
    def want(self):
        return any(s.wants_conn() for s in self.sessions.values())

    def ensure(self):
        if self.off or self.ws is not None or self._timer or not self.want():
            return
        if time.monotonic() < self.hold_until:
            self._later(self.hold_until - time.monotonic())
            return
        self.gen += 1
        gen = self.gen
        self.open = False
        self.hello = None
        self.ws = "connecting"
        try:
            url, hdrs = self.url(), self.headers()
        except Exception:  # noqa: BLE001 - kein Server eingetragen o. ae.
            self.ws = None
            return
        threading.Thread(target=self._run, args=(gen, url, hdrs), daemon=True, name="hk-collab").start()

    def reset(self):
        """Server/Konto gewechselt bzw. /api/version neu: Schalter wieder pruefen"""
        self.off = False
        self.fails = 0
        self.delay = 1.0

    def close(self):
        self.gen += 1
        ws, self.ws = self.ws, None
        self.open = False
        if isinstance(ws, W.WebSocket):
            threading.Thread(target=ws.close, daemon=True).start()
        if self._timer:
            GLib.source_remove(self._timer)
            self._timer = 0
        with self._qc:
            self._q = []
            self._qc.notify_all()

    def send(self, kind, nid, *parts):
        if not self.open or not isinstance(self.ws, W.WebSocket):
            return False
        data = W.encode(kind, nid, *parts)
        with self._qc:
            self._q.append(data)
            self._qc.notify_all()
        return True

    def add(self, s):
        self.sessions[s.nid] = s

    def remove(self, s):
        if self.sessions.get(s.nid) is s:
            self.sessions.pop(s.nid, None)
        if not self.sessions:
            GLib.timeout_add_seconds(30, self._idle_close, self.gen)

    def _idle_close(self, gen):
        if gen == self.gen and not self.sessions:
            self.close()
        return False

    def _later(self, s):
        if self._timer:
            GLib.source_remove(self._timer)
        self._timer = GLib.timeout_add(int(max(0.05, s) * 1000), self._due)

    def _due(self):
        self._timer = 0
        self.ensure()
        return False

    # --- Hintergrund ---
    def _run(self, gen, url, hdrs):
        try:
            ws = W.WebSocket(url, hdrs, timeout=15).connect()
        except (Closed, OSError, ValueError) as e:
            code = getattr(e, "code", 1006)
            st = None
            if isinstance(e, Closed) and str(e.reason).startswith("http "):
                try:
                    st = int(str(e.reason)[5:])
                except ValueError:
                    st = None
            GLib.idle_add(self._closed, gen, 4401 if st == 401 else code, False)
            return
        if gen != self.gen:
            ws.close()
            return
        self.ws = ws
        self.last_rx = time.monotonic()
        self.ping_at = 0.0
        threading.Thread(target=self._writer, args=(gen, ws), daemon=True, name="hk-collab-w").start()
        code = 1006
        try:
            while gen == self.gen:
                m = ws.recv()
                self.last_rx = time.monotonic()
                self.ping_at = 0.0
                try:
                    k, nid, parts = W.decode(m)
                except ValueError:
                    continue
                GLib.idle_add(self._frame, gen, k, nid, parts)
        except Closed as e:
            code = e.code
        finally:
            ws.shutdown()
            with self._qc:
                self._qc.notify_all()
        GLib.idle_add(self._closed, gen, code, True)

    def _writer(self, gen, ws):
        while gen == self.gen and not ws.closed:
            with self._qc:
                if not self._q:
                    self._qc.wait(1.0)
                q, self._q = self._q, []
            try:
                for data in q:
                    ws.send_binary(data)
                now = time.monotonic()
                if self.open and not self.ping_at and now - self.last_rx > PING_IDLE:
                    self.ping_at = now
                    ws.send_binary(W.encode("ping", 0))
                elif self.ping_at and now - self.ping_at > PONG_WAIT:
                    ws.shutdown()                                    # kein pong: tot
                    return
            except Closed:
                ws.shutdown()
                return

    # --- Haupt-Thread ---
    def _frame(self, gen, k, nid, parts):
        if gen != self.gen:
            return False
        if k == "pong":
            return False
        if k == "hello" and nid == 0:
            self.hello = W.js(parts[0]) if parts else {}
            self.open = True
            self.fails = 0
            self.delay = 1.0
            self.stats["connects"] += 1
            for s in list(self.sessions.values()):
                s.conn_open()
            return False
        s = self.sessions.get(nid)
        if s is not None:
            self.stats["recv"] += 1
            try:
                s.frame(k, parts)
            except Exception as e:  # noqa: BLE001 - nie die Verbindung reissen; Seite auf Stufe 1
                import traceback
                traceback.print_exc()
                s.fallback("error-" + type(e).__name__, final=True)
        return False

    def _closed(self, gen, code, was_open):
        if gen != self.gen:
            return False
        self.ws = None
        self.open = False
        self.closed_code = code
        if code in PERMANENT:
            self.off = True
        elif code == 4401 and self.on_auth:
            self.on_auth()
        for s in list(self.sessions.values()):
            s.conn_closed(code)
        if self.off:
            return False
        self.fails += 1
        if code == 4429:
            d = 5 + random.random() * 25
        elif code == 1012:
            d = 1 + random.random() * 4
        else:
            d = self.delay
            self.delay = min(30.0, self.delay * 2)
        if self.want():
            self._later(d)
        return False


# =====================================================================================================================
# Abo einer Seite
# =====================================================================================================================
class Session:
    """Live-Abo einer Seite. host = Ansicht (PageView) mit:
        live_blocks() -> Liste der Bloecke (die App-Liste, wird an Ort und Stelle ersetzt)
        live_title() -> str
        live_busy() -> True, solange noch per PATCH gespeichert wird (Bindung wartet)
        live_bind(blocks, title) / live_apply(blocks, title)   Stand aus Y uebernehmen (erst Bindung / fremd, Undo)
        live_mode(mode)  'live' | 'ro' | 'lock' | 'offline' | None (Stufe 1)
        live_fallback(pending)  zurueck auf Stufe 1; pending: eigene, noch nicht bestaetigte Aenderungen -> per PATCH
        live_notice(code)  Hinweis (reject ...)
        live_gone(reason)  Zugriff weg
        live_aw(states)  {sid: {name, color, block}} der anderen
        live_focus() -> fokussierter Block (ID) bzw. None
        live_hold() -> True: fremde Aenderungen kurz zurueckhalten (IME-Eingabe laeuft im fokussierten Feld)"""

    def __init__(self, conn, nid, host, resolver):
        self.conn, self.nid, self.host, self.resolver = conn, nid, host, resolver
        self.state = "idle"              # idle | sub | live | offline | lock | off
        self.doc = None
        self.info = {}
        self.gen = None
        self.ro = True
        self.sv_server = None
        self.unacked = []                # [(ref, update)]
        self.offline_dirty = False
        self.seq = 0
        self.rejects = []
        self.um = None
        self.aw = {}
        self.aw_sent = None
        self.aw_src = 0
        self.bind_src = 0
        self.grace_src = 0
        self.retry_src = 0
        self.hold_src = 0
        self.leaving = False
        self.lock_why = None
        self.why = None
        self._resume = False
        self._aw_want = None
        self.loaddrops = 0
        self.sent = 0
        self.dropped = 0
        self._sub_obs = None

    # --- Lebenszeit ---
    def wants_conn(self):
        return self.state in ("sub", "live", "offline")

    @property
    def bound(self):
        return self.state in ("live", "offline", "lock")

    @property
    def editable(self):
        return self.state in ("live", "offline") and not self.ro

    def start(self):
        if self.state != "idle":
            return
        self.state = "sub"
        self.conn.add(self)
        if self.conn.open:
            self._subscribe()
        else:
            self.conn.ensure()

    def stop(self):
        """Seite verlassen: noch nicht Bestaetigtes auf Stufe 1 retten (PATCH), dann abmelden"""
        if self.state == "idle":
            return
        pend = bool(self.unacked) or self.offline_dirty
        if self.conn.open and self.state in ("sub", "live", "lock"):
            if self.aw_sent is not None:
                self.conn.send("aw", self.nid, None)
            self.conn.send("unsub", self.nid)
        self._clear_timers()
        was = self.state
        self.state = "idle"
        self.conn.remove(self)
        self._drop_doc()
        if was in ("live", "offline") and pend:
            self.host.live_fallback(True)

    def _clear_timers(self):
        for k in ("aw_src", "bind_src", "grace_src", "retry_src", "hold_src"):
            v = getattr(self, k)
            if v:
                GLib.source_remove(v)
                setattr(self, k, 0)

    def _drop_doc(self):
        self.doc, self.um = None, None
        self._sub_obs = None
        self.unacked = []
        self.offline_dirty = False

    # --- Verbindung ---
    def conn_open(self):
        if self.state in ("sub", "offline"):
            self._subscribe()

    def conn_closed(self, code):
        if self.state == "sub":
            if code in PERMANENT:
                self.fallback("closed-%d" % code, final=True)
            elif self.conn.fails >= 2:
                self.fallback("closed-%d" % code, final=False)
            return
        if self.state == "live":
            if code in PERMANENT:
                self.fallback("closed-%d" % code, final=True)
                return
            self.state = "offline"
            self.host.live_mode("offline")
            self.grace_src = GLib.timeout_add(int(OFFLINE_GRACE * 1000), self._grace_over)

    def _grace_over(self):
        self.grace_src = 0
        if self.state == "offline":
            self.fallback("offline", final=False)
        return False

    def _subscribe(self):
        opts = {"schema": schema_info(), "caps": CAPS}
        srv = (self.conn.hello or {}).get("schema") or {}
        if srv and (srv.get("hash") != opts["schema"]["hash"] or srv.get("ver") != opts["schema"]["ver"]):
            self._lock("schema")
            return
        if self.state == "offline" and self.doc is not None and self.gen:
            opts["gen"] = self.gen
            self.conn.send("sub", self.nid, opts, self.doc.get_state())
        else:
            self.conn.send("sub", self.nid, opts)

    # --- Rahmen der Seite ---
    def frame(self, k, parts):
        if self.state in ("idle", "off"):
            return
        p0 = parts[0] if parts else b""
        if k == "hello":
            self._hello(W.js(p0) or {})
        elif k == "sync1":
            self.sv_server = p0
        elif k == "sync2":
            self._sync2(p0)
        elif k == "update":
            self._remote(p0)
        elif k == "ack":
            self._ack(p0.decode("ascii", "replace"))
        elif k == "reject":
            self._reject(W.js(p0) or {}, parts[1] if len(parts) > 1 else None)
        elif k == "bye":
            self._bye((W.js(p0) or {}).get("reason") or "")
        elif k == "error":
            code = (W.js(p0) or {}).get("code")
            if code != "nosub":
                self.fallback("error-" + str(code), final=True)
        elif k == "aw":
            self._aw_in(W.js(p0) or {})

    def _hello(self, h):
        ro = bool(h.get("ro")) or h.get("role") not in ("owner", "edit")
        if h.get("update"):
            self.info.update(role=h.get("role"), ro=h.get("ro"), why=h.get("why"))
            if ro != self.ro:
                self.ro = ro
                if self.bound and self.state != "lock":
                    self.host.live_mode("ro" if ro else "live")
            return
        if h.get("why") == "schema":
            self._lock("schema")
            return
        resume = self.state == "offline" and self.doc is not None and self.gen and h.get("gen") == self.gen
        if self.state == "offline" and not resume:
            # Zustand wurde neu aufgebaut: eigenes Doc ungueltig - eigene Aenderungen per Stufe 1 retten, dann neu binden
            pend = bool(self.unacked) or self.offline_dirty
            if pend:
                self.fallback("gen", final=False)
                return
            self._drop_doc()
            self.state = "sub"
        self.info = h
        self.gen = h.get("gen")
        self.ro = ro
        self._resume = bool(resume)
        if not resume:
            self._new_doc()

    def _new_doc(self, full=None):
        self.doc = new_doc()
        if full:
            with self.doc.transaction(origin=REMOTE):
                self.doc.apply_update(full)
        self._sub_obs = self.doc.observe(self._on_txn)
        m = model()
        self.um = m.UndoManager(scopes=[self.doc.get(m.FRAGMENT, type=m.XmlFragment), self.doc.get("title", type=m.Text)],
                                capture_timeout_millis=500)
        self.um.include_origin(LOCAL)

    def _on_txn(self, e):
        try:
            origin = e.transaction.origin()
        except Exception:  # noqa: BLE001
            origin = None
        if origin == REMOTE:
            return
        upd = e.update
        if not upd or len(upd) <= 2:
            return
        if self.state == "sub" or self.leaving:
            self.loaddrops += 1                          # beim Laden geschrieben (R1): nie hinaus
            return
        if self.state == "lock" or self.ro or self.state not in ("live", "offline"):
            self.dropped += 1
            return
        if self.state == "offline" or not self.conn.open:
            self.offline_dirty = True
            return
        self._send_update(upd)

    def _send_update(self, upd):
        self.seq += 1
        ref = "u%d" % self.seq
        self.unacked.append((ref, upd))
        self.sent += 1
        self.conn.stats["sent"] += 1
        self.conn.send("update", self.nid, upd, ref.encode())

    def _apply_remote(self, upd):
        with self.doc.transaction(origin=REMOTE):
            self.doc.apply_update(upd)

    def _sync2(self, upd):
        if self.doc is None:
            return
        if upd:
            self._apply_remote(upd)
        if self.state == "offline":
            if getattr(self, "_resume", False):
                # gleiche Generation: was der Server nicht hat, nachreichen (Updates waehrend der Unterbrechung)
                if self.sv_server:
                    miss = self.doc.get_update(self.sv_server)
                    if miss and len(miss) > 2:
                        self.conn.send("sync2", self.nid, miss)
                self.unacked = []
                self.offline_dirty = False
                if self.grace_src:
                    GLib.source_remove(self.grace_src)
                    self.grace_src = 0
                self.state = "live"
                self.host.live_mode("ro" if self.ro else "live")
                self._refresh()
                self.aw_sent = None
                self.aw_out(self.host.live_focus())
            return
        if self.state == "sub":
            self._try_bind()

    def _try_bind(self):
        self.bind_src = 0
        if self.state != "sub" or self.doc is None:
            return False
        if self.host.live_busy():                             # eigene Eingaben werden noch per PATCH gespeichert
            self.bind_src = GLib.timeout_add(300, self._try_bind)
            return False
        m = model()
        try:
            blocks, title = read(self.doc, self.resolver, self.host.live_blocks())
        except m.ModelError:
            self._lock("model")
            return False
        if not m.roots_ok(self.doc):
            self._lock("roots")
            return False
        self.state = "live"
        self.rejects = []
        self.host.live_bind(blocks, title)
        # R1: gaebe es nach dem Anzeigen schon etwas zu schreiben, wuerde die App beim Laden umschreiben - nur lesen
        bad = pending_changes(self.doc, self.host.live_blocks(), self.resolver, self.host.live_title())
        if bad:
            self._lock("load:" + ",".join(str(x) for x in bad[:5]))
            return False
        self.host.live_mode("ro" if self.ro else "live")
        return False

    def _lock(self, why):
        """Nur lesen, nichts senden (R1). Die Seite bleibt mit dem geladenen Stand stehen; Hinweis in der Ansicht."""
        self.lock_why = why
        if self.conn.open and self.state in ("sub", "live"):
            self.conn.send("unsub", self.nid)
        self._clear_timers()
        self.state = "lock"
        self.conn.remove(self)
        self.unacked = []
        self.host.live_mode("lock")

    def _remote(self, upd):
        if self.doc is None or not upd:
            return
        self._apply_remote(upd)
        if self.bound:
            self._refresh()

    def _refresh(self):
        """Y -> Ansicht (nur geaenderte Bloecke)"""
        if self.doc is None or self.state not in ("live", "offline"):
            return
        if self.host.live_hold():                            # IME-Vorschau im Feld: erst nach dem Abschluss zeigen
            if not self.hold_src:
                self.hold_src = GLib.timeout_add(150, self._hold_due)
            return
        m = model()
        cur = self.host.live_blocks()
        try:
            blocks, title = read(self.doc, self.resolver, cur)
        except m.ModelError:
            self._lock("model")
            return
        same = len(blocks) == len(cur) and all(a is b for a, b in zip(blocks, cur)) and title == self.host.live_title()
        if same:
            return
        changed = {b.get("id") for b in blocks if not any(b is c for c in cur)}
        self.host.live_apply(blocks, title)
        bad = pending_changes(self.doc, self.host.live_blocks(), self.resolver, None, only=changed)
        if bad and self.state in ("live", "offline"):
            self._lock("show:" + ",".join(str(x) for x in bad[:5]))

    def _hold_due(self):
        self.hold_src = 0
        self._refresh()
        return False

    # --- eigene Aenderungen ---
    def local(self, hint=None, caret=None):
        """Ansicht hat geaendert (Text oder Struktur): sofort ins Doc (und damit hinaus)"""
        if not self.editable or self.doc is None:
            return
        blocks = self.host.live_blocks()
        if legacy(blocks):                                   # z. B. Kopie eines synchronisierten Blocks eingefuegt
            self.fallback("legacy", final=True, pending=True)
            return
        write_local(self.doc, blocks, self.host.live_title(), hint, caret)

    def undo(self):
        if self.editable and self.um is not None and self.um.can_undo():
            self.um.undo()
            self._refresh()
            return True
        return False

    def redo(self):
        if self.editable and self.um is not None and self.um.can_redo():
            self.um.redo()
            self._refresh()
            return True
        return False

    def _ack(self, ref):
        for i, (r, _u) in enumerate(self.unacked):
            if r == ref:
                del self.unacked[:i + 1]                    # in Reihenfolge bestaetigt
                break
        self.conn.stats["acked"] += 1

    def _reject(self, r, full):
        code = r.get("code") or ""
        self.conn.stats["rejects"] += 1
        if not full or self.doc is None:
            return
        if code == "pending":
            self._apply_remote(full)
            self._resend_all()
            self._refresh()
            return
        if code == "retry":
            self._apply_remote(full)
            self._refresh()
            if self.retry_src:
                GLib.source_remove(self.retry_src)
            self.retry_src = GLib.timeout_add(int((1 + random.random() * 4) * 1000), self._retry_due)
            return
        if code == "readonly":
            self.ro = True
            self._rebuild(full, keep=False)
            self.host.live_mode("ro")
            self.host.live_notice("readonly")
            return
        now = time.monotonic()
        self.rejects = [t for t in self.rejects if now - t < REJECT_WINDOW] + [now]
        self.unacked = [x for x in self.unacked if x[0] != r.get("ref")]
        if len(self.rejects) >= REJECT_MAX:
            self._rebuild(full, keep=False)
            self.fallback("rejects", final=True)
            self.host.live_notice("rejected")
            return
        kept = self._rebuild(full, keep=code != "client")
        self.host.live_notice("rejected" if kept else "rejected_lost")

    def _retry_due(self):
        self.retry_src = 0
        self._resend_all()
        return False

    def _resend_all(self):
        if not self.unacked:
            return
        from pycrdt import merge_updates
        u = merge_updates(*[x[1] for x in self.unacked])
        self.unacked = []
        self._send_update(u)

    def _rebuild(self, full, keep):
        """Vollzustand des Servers uebernehmen (neues Doc, neue clientID), eigene unbestaetigte Aenderungen, die nicht am
        Abgelehnten haengen, wieder anwenden -> True, wenn sie behalten wurden"""
        rest = [u for _r, u in self.unacked] if keep else []
        old = self.doc
        self._new_doc(full)
        lost = False
        if rest:
            probe = new_doc()
            probe.apply_update(self.doc.get_update())
            for u in rest:
                if not update_state_ok(probe.get_state(), u):
                    lost = True
                    break
                probe.apply_update(u)
            if not lost:
                for u in rest:
                    self._apply_remote(u)
        self.unacked = []
        del old
        self._refresh()
        if rest and not lost and self.editable and self.conn.open:
            from pycrdt import merge_updates
            self._send_update(merge_updates(*rest))
        return not lost

    def _bye(self, reason):
        if reason == "restart":
            if self.state == "live":
                self.state = "offline"
                self.host.live_mode("offline")
                self.grace_src = GLib.timeout_add(int(OFFLINE_GRACE * 1000), self._grace_over)
            return
        if reason == "off":
            self.conn.off = True
        if reason == "auth" and self.conn.on_auth:
            self.conn.on_auth()
        self.fallback("bye-" + reason, final=reason != "auth")
        if reason in ("access", "gone"):
            self.host.live_gone(reason)

    def fallback(self, why, final=False, pending=None):
        """Zurueck auf Stufe 1. final: fuer diese Seite nicht neu versuchen (bis sie wieder geoeffnet wird)."""
        if self.state in ("idle", "off"):
            return
        self.why = why
        was = self.state
        pend = (bool(self.unacked) or self.offline_dirty) if pending is None else pending
        if self.conn.open and was in ("sub", "live"):
            self.conn.send("unsub", self.nid)
        self._clear_timers()
        self.state = "off" if final else "idle"
        self.conn.remove(self)
        self._drop_doc()
        self.aw = {}
        if was in ("live", "offline", "sub"):
            self.host.live_fallback(pend and was != "sub")

    # --- Awareness ---
    def aw_out(self, block):
        """eigener Block (fokussiert) - gebuendelt, hoechstens alle 100 ms; nur wenn sich etwas aendert"""
        if self.state not in ("live",) or self.aw_src:
            self._aw_want = block
            return
        self._aw_want = block
        self.aw_src = GLib.timeout_add(100, self._aw_due)

    def _aw_due(self):
        self.aw_src = 0
        if self.state != "live":
            return False
        msg = {"block": self._aw_want, "cursor": None}
        if msg != self.aw_sent:
            self.aw_sent = msg
            self.conn.send("aw", self.nid, msg)
        return False

    def _aw_in(self, m):
        sid = m.get("sid")
        if not isinstance(sid, int):
            return
        st = m.get("state")
        if isinstance(st, dict):
            self.aw[sid] = {"name": str(st.get("name") or "")[:80], "color": st.get("color"),
                            "block": st.get("block") if isinstance(st.get("block"), str) else None}
        else:
            self.aw.pop(sid, None)
        self.host.live_aw(dict(self.aw))
