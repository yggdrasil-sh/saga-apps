"""Anrufe (Server ab 2.1) - Linux v1 laut Spezifikation: die App KLINGELT (Live-Kanal call.ring bzw. UnifiedPush-Art
"call", Desktop-Mitteilung mit "Ablehnen"/"Annehmen", Leiste im Fenster, Klingelton), das Gespraech selbst laeuft im
Standardbrowser (Web-Oberflaeche /chats#call-<id>-audio|video; dort WebRTC mit Ende-zu-Ende-Pruefung). Anrufen aus der
App oeffnet den Direkt-Chat im Browser (/chats#c<id>), dort "Anruf"/"Videoanruf".

Kein Doppelklingeln: je Anruf hoechstens EIN Klingeln (gleiche Mitteilungs-ID "call-<id>" wie die Push-Mitteilung des
Servers, Live und Push teilen sich den Zustand), erledigte Anrufe (angenommen, abgelehnt, beendet, anderswo
angenommen) klingeln nie wieder - auch nicht nach nachgeholten Ereignissen (Last-Event-ID). Vor dem Klingeln immer
GET /api/calls/<id>: nur bei state == "ringing" und Rolle "callee"."""
import math
import os
import queue
import re
import socket
import stat
import struct
import threading
import time
import wave

from gi.repository import Adw, Gio, GLib, GObject, Gtk, Pango

from . import avatars, config
from .api import APIError, client
from .i18n import _, ngettext, pgettext
from .util import bg

RING_FALLBACK_S = 45
_ID_RE = re.compile(r"[0-9a-f]{32}")


def valid_id(cid):
    """Anruf-Kennung: genau 32 Hex-Zeichen (klein) - landet in API-Pfaden und Adressen"""
    return isinstance(cid, str) and _ID_RE.fullmatch(cid) is not None


def call_url(cid, media):
    return f"{config.settings.server.rstrip('/')}/chats#call-{cid}-{'video' if media == 'video' else 'audio'}"


JOIN_ROLES = ("member", "companion", "handover")


def join_url(cid, media, role="member"):
    """(2.4) Laufendem Anruf im Browser beitreten (static/calls.js route: #join-<id>-member|companion|handover[-video]).
    member: Gruppenanruf beitreten; (2.5, L6) companion: Begleitgeraet ohne Mikrofon und Ton (nie mit Video);
    handover: "Hierher uebernehmen" - der Browser ersetzt das bisherige eigene Hauptgeraet (Web waehlt es aus der Liste)"""
    role = role if role in JOIN_ROLES else "member"
    video = "-video" if media == "video" and role != "companion" else ""
    return f"{config.settings.server.rstrip('/')}/chats#join-{cid}-{role}{video}"


def block_text(why, limit=None, mine=False, msg=None):
    """(2.5) Warum Beitreten gerade nicht geht (join_block des Anruf-Objekts) - Wortlaut wie im Web (blockText).
    Voll (Bugs-DB 2154): Text des Servers (msg), sonst wie im Web - jedes Geraet zaehlt, auch ein zweites zum Teilen;
    mine: ein weiteres eigenes Geraet (Leiste "Anruf laeuft") - "Hierher uebernehmen" geht trotzdem."""
    if why == "old":
        return _("Someone in the call needs Saga 2.4 for that.")
    if why == "full":
        if isinstance(msg, str) and msg.strip():
            return msg.strip()[:300]
        if mine:
            return _("All {n} places are taken – another device of yours (e.g. for sharing) can only join once someone "
                     "hangs up. “Move here” still works.").format(n=limit or "?")
        return _("The call is full – all {n} places are taken (every device counts, including a second one for sharing). "
                 "Someone can only join once someone else hangs up.").format(n=limit or "?")
    if why == "ended":
        return _("The call has already ended.")
    if why == "none":
        return _("The call has not been answered yet.")
    if why == "handover_busy":
        return _("Another of your devices is taking over the call right now – please wait a moment.")
    return _("Joining is not possible right now.")


def _mine(c):
    m = (c or {}).get("mine")
    return [d for d in m if isinstance(d, str)] if isinstance(m, list) else []


def run_shown(c, here=None, ringing_id=None, local=()):
    """(2.5, L6) Leiste "Anruf laeuft" fuer einen Eintrag aus GET /api/calls/active zeigen? Wie runBar im Web: ein anderes
    eigenes Geraet ist im Anruf (1:1: nur wenn angenommen) bzw. Gruppenanruf in einem eigenen Chat. Nicht, solange es hier
    klingelt (eigene Leiste), und nicht, wenn nur der Browser dieses Rechners dabei ist (local: von hier geoeffnet)."""
    if not isinstance(c, dict) or not valid_id(c.get("id")) or c.get("state") not in ("ringing", "active"):
        return False
    if ringing_id and c["id"] == ringing_id:
        return False
    mine = _mine(c)
    if here and here in mine:
        return False
    if mine and set(mine) <= set(local or ()):
        return False
    if c.get("kind") == "group" and not c.get("dm"):      # 2.5: 1:1 ueber Knoten (dm) wie 1:1
        return True
    return c.get("state") == "active" and bool(mine)


def run_view(c):
    """(2.5, L6) Inhalt der Leiste: {"video", "title", "sub", "note", "actions": [(role, video, kurz, voll, haupt)]}.
    Mit eigenem Geraet im Anruf: "Hinzufuegen" (Begleitgeraet) und "Hierher uebernehmen"; voller Anruf (join_block
    "full") mit takeover: nur "Hierher uebernehmen". Sonst (Gruppe): "Beitreten" (bei Video zusaetzlich "Nur Ton")."""
    if not isinstance(c, dict):                            # pt2 L3: kaputte Serverantwort -> leere Leiste statt Ausnahme
        c = {}
    mine = _mine(c)
    legs = [x for x in (c.get("legs") if isinstance(c.get("legs"), list) else []) if isinstance(x, dict)]
    own = [x for x in legs if x.get("device") in mine]
    main = next((x for x in own if x.get("role") != "companion"), own[0] if own else None)
    video = c.get("media") == "video"
    group = c.get("kind") == "group" and not c.get("dm")
    if mine:
        dev = (main or {}).get("dev_name") or pgettext("call in progress on", "another device")
        title = _("Call in progress on {device}").format(device=dev)
        sub = group_name(c) if group else caller_name(c)
    else:
        title = _("Group call running")
        n = len(legs)
        sub = " · ".join(x for x in (group_name(c), ngettext("{n} device", "{n} devices", n).format(n=n)) if x)
    take = ("handover", video, _("Move here"),
            _("Move the call to this computer without interruption – it continues in your browser"), True)
    acts = []
    if not c.get("joinable", True):
        msg = next((c[k] for k in ("full_msg", "msg") if isinstance(c.get(k), str) and c[k].strip()), None)   # Text des Servers
        note = block_text(c.get("join_block"), c.get("limit"), mine=bool(mine), msg=msg)
        if c.get("takeover") and mine:                     # L4/E6: das ersetzte Geraet zaehlt nicht mit
            acts = [take]
    else:
        note = _("Continues in your browser.")
        if mine:
            acts = [("companion", False, _("Add"), _("Add your browser as a companion device (without microphone and sound)"), False),
                    take]
        elif video:
            acts = [("member", False, _("Audio only"), _("Join with audio only (in browser)"), False),
                    ("member", True, _("Join"), _("Join with video (in browser)"), True)]
        else:
            acts = [("member", False, _("Join (in browser)"), _("Join the group call (in browser)"), True)]
    return {"video": video, "title": title, "sub": sub, "note": note, "actions": acts}


def is_group(c):
    return isinstance(c, dict) and (c.get("kind") == "group" or bool(c.get("group")))


def group_rings(c):
    """(2.4) GET /api/calls/<id> eines Gruppenanrufs: klingelt es fuer mich? Einladung offen (invite "ringing" bzw. ohne
    Feld), Anruf laeuft noch (ringing/active - "active" heisst nur: zwei sind dabei) und keins meiner Geraete ist dabei"""
    return is_group(c) and c.get("state") in ("ringing", "active") and c.get("invite", "ringing") == "ringing" \
        and not c.get("mine")


def _num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def ring_left(c):
    """Restdauer des Klingelns (Sekunden). (2.5, L7) Gruppe mit invite_since und now (Server-Uhr): ab dem letzten
    Klingeln der eigenen Einladung (erneutes Klingeln spaeter im Anruf), mindestens 5 s wie im Web. Sonst wie bisher:
    expires_in (Server: ab created) bzw. ring_s."""
    rs = c.get("ring_s")
    rs = float(rs) if _num(rs) and rs > 0 else float(RING_FALLBACK_S)
    since, now = c.get("invite_since"), c.get("now")
    if is_group(c) and _num(since) and _num(now):
        return max(5.0, since + rs - now)
    left = c.get("expires_in")
    return float(left) if _num(left) and left > 0 else rs


def rings_again(c, done_at, t=None):
    """(2.5, L7) Gruppenanruf, der hier schon erledigt war (done_at: time.time() beim Erledigen - abgelehnt, vorbei,
    angenommen): klingelt er erneut? Nur wenn die Einladung jetzt klingelt (group_rings) und das Klingeln laut
    invite_since nach dem Erledigen begann (Server-Uhr ueber now abgebildet, 1 s Spiel). Aeltere Server ohne
    invite_since: nie (wie bisher) - nachgeholte call.invite-Ereignisse klingeln dort sonst erneut."""
    since, now = c.get("invite_since"), c.get("now")
    if not group_rings(c) or not (_num(since) and _num(now) and _num(done_at)):
        return False
    t = time.time() if t is None else t
    return since > now - (t - done_at) - 1.0


def device_caps(cfg):
    """(2.4, N5) Faehigkeiten dieses Geraets fuer PUT /api/calls/caps (Vertrag: docs/APP-API.md "Mehrgeraete und Gruppen").
    Die Linux-App fuehrt kein WebRTC-Gespraech selbst - sie klingelt und das Gespraech laeuft im Browser, der dort als
    eigener Endpunkt mit seinen eigenen caps beitritt. Daher NUR "multi" (an Gruppenanrufen teilnehmen: Einladung,
    Klingeln, Beitreten ueber den Browser) und (2.5, Grundsatz Y) "horn_ring" (kann einen Anruf ueber Knoten - auch 1:1 -
    annehmen: klingelt, der Browser tritt bei; der Server zaehlt die Linux-App damit als faehig, ohne Erreichbarkeits-
    pruefung - sonst liefe jeder 1:1 zu Linux-Nutzern direkt mit dem falschen Hinweis "Gegenstelle veraltet"). Beides
    nur, wenn der eigene Server reneg und multi kann (aeltere Server ignorieren horn_ring). reneg/want/bye/screen2/horn
    betreffen die Signalisierung bzw. Medien einer eigenen PeerConnection und werden nicht behauptet."""
    f = (cfg or {}).get("features") if isinstance(cfg, dict) else None
    f = set(x for x in f if isinstance(x, str)) if isinstance(f, list) else set()
    on = bool((cfg or {}).get("enabled")) if isinstance(cfg, dict) else False
    out = ["multi", "horn_ring"] if {"reneg", "multi"} <= f and on else []
    # (2.6, Bugs-DB 2029-8) waiting1: dieses Geraet zeigt einen zweiten Anruf waehrend eines Gespraechs als Anklopf-Leiste
    # (ohne Klingeln, mit Ablehnen) - nur, wenn der Server Anklopfen kann (sonst klopft ohnehin nichts an)
    if "waiting1" in f and on:
        out.append("waiting1")
    return out


def ring_mode(c):
    """(2.6) Anruf-Objekt (GET /api/calls/<id>, /active; ggf. mit dem Ereignis zusammengefuehrt) -> "ring" (klingeln) |
    "wait" (Anklopfen: waiting, die Person telefoniert) | "quiet" (zurueckgehalten: eigenes Nicht stoeren/Ruhezeit) | None.
    Gruppenanrufe (nicht 1:1 ueber Knoten) klingeln wie bisher - Nicht stoeren kommt dort als stille Einladung (silent)."""
    if not isinstance(c, dict):
        return None
    if is_group(c) and not c.get("dm"):
        return "ring" if group_rings(c) else None
    if not ((c.get("state") == "ringing" and c.get("role") == "callee") or group_rings(c)):
        return None
    if c.get("quiet"):
        return "quiet"
    return "wait" if c.get("waiting") else "ring"


def own_running(lst, cid=None):
    """(2.6) eigene laufende Gespraeche aus GET /api/calls/active (ohne cid) - wie busyElsewhere im Web: 1:1 als Anrufer
    bzw. Angerufene(r), Gruppe mit einem eigenen Geraet"""
    out = []
    for x in lst if isinstance(lst, list) else []:
        if not isinstance(x, dict) or x.get("id") == cid or x.get("state") != "active" or not valid_id(x.get("id")):
            continue
        if (x.get("kind") == "group" and not x.get("dm")) and not _mine(x):
            continue
        if not (x.get("kind") == "group" and not x.get("dm")) and not (x.get("role") in ("caller", "callee") or _mine(x)):
            continue
        out.append(x)
    return out


def hold_view(h):
    """(2.6) Inhalt der Anklopf- bzw. stillen Leiste: {"title", "sub", "note", "label", "decline", "take", "accept", "hide"}.
    Linux fuehrt das Gespraech nie selbst (Browser) - wie im Web ohne eigenes Gespraech: Anklopfen nur "Ablehnen" (besetzt),
    dazu "Beenden und annehmen", wenn genau ein eigenes laufendes Gespraech bekannt ist; stumm: "Annehmen" nur, wenn kein
    eigenes Gespraech laeuft (sonst zwei Gespraeche)."""
    name = caller_name(h) or "?"
    video = h.get("media") == "video"
    what = _("Video call") if video else _("Call")
    elsewhere = _("You are on a call in your browser or on another device.")
    if h.get("mode") == "wait":
        lab = _("{name} is calling (call waiting)").format(name=name)
        return {"title": lab, "sub": what, "note": elsewhere, "label": lab, "decline": True, "take": bool(h.get("run")),
                "accept": False, "hide": False}
    quiet = h.get("why") == "quiet"
    lab = (_("Call from {name} – silent (quiet hours)") if quiet else _("Call from {name} – silent (Do not disturb)")).format(name=name)
    sub = what + " · " + (_("silent (quiet hours)") if quiet else _("silent (Do not disturb)"))
    return {"title": name, "sub": sub, "note": elsewhere if h.get("elsewhere") else "", "label": lab, "decline": False, "take": False,
            "accept": h.get("elsewhere") is False, "hide": True}


def chat_url(chat_id):
    return f"{config.settings.server.rstrip('/')}/chats#c{int(chat_id)}"


def caller_name(call):
    if not isinstance(call, dict):
        return ""
    p = call.get("peer") if isinstance(call.get("peer"), dict) and call.get("peer") else call.get("from")
    if not isinstance(p, dict):
        return ""
    n = p.get("name") or p.get("username") or ""
    return n if isinstance(n, str) else ""


def group_name(call):
    """Name der Gruppe (from.chat des Ereignisses bzw. des Anrufs) oder ein leerer Text"""
    f = call.get("from") if isinstance(call, dict) and isinstance(call.get("from"), dict) else {}
    ch = f.get("chat")
    n = (ch.get("title") or ch.get("name") or "") if isinstance(ch, dict) else ch
    return n if isinstance(n, str) else ""


def ring_title(call):
    name = caller_name(call)
    video = (call or {}).get("media") == "video"
    if is_group(call) and not (call or {}).get("dm"):     # (2.5) 1:1 ueber Knoten: Titel wie 1:1
        grp = group_name(call)
        if grp:
            return (_("Group video call in {group}") if video else _("Group call in {group}")).format(group=grp)
        if name:
            return (_("Group video call from {name}") if video else _("Group call from {name}")).format(name=name)
        return _("Group video call") if video else _("Group call")
    if name:
        return (_("Video call from {name}") if video else _("Call from {name}")).format(name=name)
    return _("Incoming video call") if video else _("Incoming call")


# --- Eintrag im Chat (GET /api/chats/<id>/messages?calls=1: kind "call", body_ct = Klartext-JSON) -------------------------
def parse_entry(m):
    """Chat-Nachricht der Art "call" -> {call, media, status, dur, from, to} oder None"""
    if not isinstance(m, dict) or m.get("kind") != "call":
        return None
    import json
    try:
        d = json.loads(m.get("body_ct") or "")
    except (TypeError, ValueError):
        return None
    if not isinstance(d, dict) or d.get("type") != "call":
        return None
    st = d.get("status") if d.get("status") in ("ended", "missed", "declined", "busy", "failed") else "missed"
    dur = d.get("dur") if isinstance(d.get("dur"), int) and d["dur"] >= 0 else None
    # (2.6, Bugs-DB 2375) hold: Grund der Anruf-Weiche (dnd | knock | again) - nur die angerufene Person bekommt ihn
    hold = d.get("hold") if d.get("hold") in ("dnd", "knock", "again") else None
    return {"call": str(d.get("call") or ""), "media": "video" if d.get("media") == "video" else "audio", "status": st, "dur": dur,
            "from": str(d.get("from") or ""), "to": str(d.get("to") or ""), "hold": hold}


def duration(sec):
    sec = max(0, int(sec or 0))
    h, m, s = sec // 3600, sec % 3600 // 60, sec % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def entry_text(k, me):
    """Text wie in der Web-Oberflaeche (static/chat.js callText)"""
    out = k.get("from") == me
    video = k.get("media") == "video"
    what = _("Video call") if video else _("Call")
    st = k.get("status")
    if st == "ended":
        return f"{what} · {duration(k.get('dur'))}"
    if st == "declined":
        return _("{what} declined").format(what=what)
    if st == "busy":
        return _("Busy") if out else _("Missed call (you were on another call)")
    if st == "failed":
        return _("{what} – no connection").format(what=what)
    if out:
        return _("{what} – not answered").format(what=what)
    if k.get("hold") == "dnd":                             # (2.6) zurueckgehalten: Nicht stoeren bzw. Ruhezeit
        return _("Missed video call (Do not disturb)") if video else _("Missed call (Do not disturb)")
    if k.get("hold") in ("knock", "again"):                # (2.6) angeklopft, waehrend du telefoniert hast
        return _("Missed call (you were on another call)")
    return _("Missed video call") if video else _("Missed call")


def entry_missed(k, me):
    return k.get("from") != me and k.get("status") in ("missed", "busy")


# --- Klingelton (eigene WAV im Zwischenspeicher, GTK-Medien; ohne Medien-Backend still) ----------------------------------
def _tone_file():
    p = os.path.join(config.CACHE_DIR, "ring-v1.wav")
    if os.path.exists(p):
        return p
    rate, frames = 22050, bytearray()
    for burst in range(2):                                   # zwei kurze Doppeltoene, dann Pause (Schleife ~3 s)
        for i in range(int(rate * 0.4)):
            t = i / rate
            env = min(1.0, t / 0.02, (0.4 - t) / 0.05)
            v = 0.32 * env * (math.sin(2 * math.pi * 880 * t) + 0.6 * math.sin(2 * math.pi * 1320 * t)) / 1.6
            frames += struct.pack("<h", int(v * 32767))
        frames += b"\x00\x00" * int(rate * 0.2)
    frames += b"\x00\x00" * int(rate * 1.8)
    tmp = p + ".tmp"
    with wave.open(tmp, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(frames))
    os.replace(tmp, p)
    return p


def _quiet():
    """GNOME "Nicht stoeren" (keine Banner): kein Klingelton"""
    try:
        src = Gio.SettingsSchemaSource.get_default()
        if src is not None and src.lookup("org.gnome.desktop.notifications", True) is not None:
            return not Gio.Settings.new("org.gnome.desktop.notifications").get_boolean("show-banners")
    except (GLib.Error, TypeError):
        pass
    return False


def _sock_ok(path):
    """Unix-Socket da und jemand nimmt an (auch Socket-Aktivierung von systemd)? Verwaiste Sockets zaehlen nicht."""
    try:
        if not stat.S_ISSOCK(os.stat(path).st_mode):
            return False
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            s.settimeout(0.3)
            s.connect(path)
            return True
        finally:
            s.close()
    except OSError:
        return False


def _audio_service():
    """Welcher Tondienst laeuft: "pulse" (PulseAudio oder pipewire-pulse), "pipewire" oder None. Ohne Dienst nimmt
    GStreamers autoaudiosink die ALSA-Ausgabe, und die fuehrt auf Fedora ueber pipewire-alsa - dessen Oeffnen kann ohne
    laufendes PipeWire im Streaming-Faden haengen bleiben (Bugs-DB 2175). Dann also kein Klingelton."""
    ps = os.environ.get("PULSE_SERVER") or ""
    if ps:                                              # ausdruecklich gesetzt: nur pruefbar, wenn es ein Socket-Pfad ist
        path = ps[5:] if ps.startswith("unix:") else ps
        return "pulse" if not path.startswith("/") or _sock_ok(path) else None
    run = os.environ.get("XDG_RUNTIME_DIR") or ""
    if not run:
        return None
    if _sock_ok(os.path.join(run, "pulse", "native")):
        return "pulse"
    rem = os.environ.get("PIPEWIRE_REMOTE") or "pipewire-0"
    if _sock_ok(rem if rem.startswith("/") else os.path.join(run, rem)):
        return "pipewire"
    return None


def _gst():
    import gi
    gi.require_version("Gst", "1.0")
    from gi.repository import Gst
    return Gst


def _set_states(q):
    """Arbeitsfaden des Klingeltons: Zustandswechsel der Reihe nach, nie im UI-Faden (set_state kann auf eine Sperre
    von GStreamer warten, solange der Streaming-Faden die Tonausgabe oeffnet - Bugs-DB 2175)."""
    while True:
        pipe, state, failed = q.get()
        try:
            if pipe.set_state(state) == failed and failed is not None:
                pipe.set_state(type(state).NULL)
        except Exception:  # noqa: BLE001 - Klingelton ist Beiwerk, der Faden darf nicht sterben
            pass
        finally:
            q.task_done()


class Ringer:
    """Klingelton direkt mit GStreamer (playbin, nur Ton, Videoausgang fakesink, Schleife ueber EOS -> an den Anfang).
    Bewusst nicht Gtk.MediaFile: dessen GStreamer-Backend baut einen GL-Videopfad auf und bricht ohne GLES ab.
    Fehlt GStreamer bzw. wavparse (gstreamer1-plugins-good), ein Tondienst (PulseAudio/PipeWire) oder ein Audio-Ausgang:
    still - Leiste und Mitteilung bleiben. Zustandswechsel (Starten/Stoppen) laufen in einem eigenen Faden: Stoppen
    blockiert den UI-Faden nie (Bugs-DB 2175)."""

    STUCK = 2                                           # so viele offene Zustandswechsel: Ausgabe haengt, nicht klingeln

    def __init__(self):
        self.pipe = None
        self._q = None

    def _queue(self):
        if self._q is None:
            self._q = queue.Queue()
            threading.Thread(target=_set_states, args=(self._q,), name="hk-ring", daemon=True).start()
        return self._q

    def idle(self, timeout=5.0):
        """Fuer Tests: warten, bis alle Zustandswechsel durch sind -> True"""
        if self._q is None:
            return True
        end = time.monotonic() + timeout
        while self._q.unfinished_tasks and time.monotonic() < end:
            time.sleep(0.02)
        return not self._q.unfinished_tasks

    def start(self):
        if self.pipe is not None or os.environ.get("HK_NO_RING") or _quiet():
            return
        if self._q is not None and self._q.unfinished_tasks >= self.STUCK:
            return
        svc = _audio_service()
        if svc is None:
            return
        try:
            Gst = _gst()
            Gst.init(None)
            pipe = Gst.ElementFactory.make("playbin", "hk-ring")
            if pipe is None:
                return
            pipe.set_property("uri", GLib.filename_to_uri(_tone_file(), None))
            pipe.set_property("volume", 0.8)
            fake = Gst.ElementFactory.make("fakesink")                 # nie ein Videopfad (WAV hat keinen, sicher ist sicher)
            if fake is not None:
                pipe.set_property("video-sink", fake)
            # Ausgabe zum gefundenen Dienst; kein Rueckfall auf ALSA (autoaudiosink), der ohne Dienst haengen kann
            sink = Gst.ElementFactory.make("pulsesink" if svc == "pulse" else "pipewiresink")
            if sink is not None:
                pipe.set_property("audio-sink", sink)
            bus = pipe.get_bus()
            bus.add_signal_watch()
            bus.connect("message::eos", lambda _b, _m: pipe.seek_simple(Gst.Format.TIME, Gst.SeekFlags.FLUSH, 0))
            bus.connect("message::error", lambda _b, _m: self.stop() if self.pipe is pipe else None)
            self.pipe = pipe
            self._queue().put((pipe, Gst.State.PLAYING, Gst.StateChangeReturn.FAILURE))
        except (ImportError, ValueError, GLib.Error, OSError, TypeError, AttributeError):
            self.pipe = None

    def stop(self):
        p, self.pipe = self.pipe, None
        if p is None:
            return
        try:
            p.get_bus().remove_signal_watch()
            self._queue().put((p, _gst().State.NULL, None))
        except (ImportError, ValueError, GLib.Error, TypeError, AttributeError):
            pass


# --- Zustand -------------------------------------------------------------------------------------------------------------
class Calls(GObject.Object):
    """Klingeln (Haupt-Thread). Signal "ring" (dict|None): Leiste im Fenster zeigen/verbergen."""
    __gsignals__ = {
        "ring": (GObject.SignalFlags.RUN_FIRST, None, (object,)),
        "info": (GObject.SignalFlags.RUN_FIRST, None, (str,)),       # kurze Meldung (Toast)
        "running": (GObject.SignalFlags.RUN_FIRST, None, (object,)),  # (2.5, L6) Liste fuer die Leiste "Anruf laeuft"
        "hold": (GObject.SignalFlags.RUN_FIRST, None, (object,)),     # (2.6) Anklopf-/stille Leisten (Liste, neueste zuerst)
    }

    def __init__(self):
        super().__init__()
        self.app = None
        self.ringing = None             # {"id", "media", "chat_id", "peer", "until"}
        self.done = {}                  # call_id -> Zeitpunkt: erledigt, klingelt nicht wieder
        self.checking = set()           # gerade per GET geprueft (Live + Push gleichzeitig)
        self._push = {}                 # call_id -> Push-Mitteilung (Rueckfall, wenn das Pruefen scheitert)
        self._push_media = {}           # call_id -> "audio"/"video" der Rueckfall-Mitteilung (Annehmen-Knopf)
        self._invites = {}              # (2.4) call_id -> call.invite (from, silent ...) bis zum Pruefen
        self._push_group = set()        # (2.4) Rueckfall-Mitteilung eines Gruppenanrufs (Push-Art call.group)
        self.config = None              # GET /api/calls/config (enabled ...), None = unbekannt
        self._caps_sent = None          # (2.4) (Server, Geraet, caps) zuletzt per PUT /api/calls/caps gemeldet
        self.ringer = Ringer()
        self._timer = 0
        self._held = False
        self.running = []               # (2.5, L6) Anrufe der Leiste "Anruf laeuft" (aus GET /api/calls/active)
        self.local = {}                 # call_id -> {"t", "before", "devs"}: hier im Browser geoeffnet (Geraet des Browsers)
        self._run_t = 0
        self.holds = {}                 # (2.6) call_id -> Anklopfen ("wait") bzw. zurueckgehalten ("quiet"), ohne Klingeln
        self._why = {}                  # (2.6) call_id -> why aus call.quiet (dnd | quiet)

    def reset(self):
        self._stop(withdraw=True)
        self.done.clear()
        self.checking.clear()
        self._push_media.clear()
        self._invites.clear()
        self.config = None
        self._caps_sent = None
        self.local.clear()
        for cid in list(self.holds):
            self._hold_drop(cid, emit=False)
        self._why.clear()
        self.emit("hold", [])
        if self._run_t:
            GLib.source_remove(self._run_t)
            self._run_t = 0
        if self.running:
            self.running = []
            self.emit("running", [])

    @staticmethod
    def _me():
        try:
            from .chat import chat
            return chat.uid
        except (ImportError, AttributeError):
            return None

    # --- Server-Einstellung (Anrufe an?) ---------------------------------------------------------------------------
    def load_config(self, done=None):
        def fin(j):
            self.config = j if isinstance(j, dict) else {}
            self.report_caps()
            if done:
                done(self.enabled)

        def err(e):
            self.config = {"enabled": False} if isinstance(e, APIError) and e.code in (403, 404) else self.config
            if done:
                done(self.enabled)
        bg(client.get, "api/calls/config", done=fin, error=err)

    def report_caps(self):
        """(2.4, N5) Faehigkeiten melden, sobald Server-Einstellung und Chat-Geraet bekannt sind (beim Start bzw. wenn die
        Chat-Schluessel bereit sind) - sonst laedt der Server reine Linux-Nutzer nie zu Gruppenanrufen ein ("braucht
        Saga 2.4"), es klingelt nicht. Einmal je Server, Geraet und caps."""
        try:
            from .chat import chat
        except ImportError:
            return
        caps = device_caps(self.config)
        f = (chat.me or {}).get("features")
        if isinstance(f, list) and "chat3" in f:          # (2.5) Chat-Stufe 3 (der Server merkt sie sich auch ueber X-Saga-Chat)
            caps = caps + ["chat3"]
        if not caps or not chat.ready or not chat.device_id:
            return
        key = (config.settings.server, chat.device_id, tuple(caps))
        if key == self._caps_sent:
            return
        self._caps_sent = key

        def err(e):
            if self._caps_sent == key:
                self._caps_sent = None                     # beim naechsten Anlass erneut versuchen
        bg(client.json, "PUT", "api/calls/caps", {"device": chat.device_id, "caps": caps}, error=err)

    @property
    def enabled(self):
        return bool((self.config or {}).get("enabled"))

    @property
    def browser_hint(self):
        """(2.4) Hinweis, was das Gespraech im Browser kann: Server mit saga-call v3 (features pip_hint/screen2) -> Bild-im-Bild
        und Bildschirm teilen (die Linux-App fuehrt das Gespraech nicht selbst, Konzept 2.4 §0)"""
        f = (self.config or {}).get("features")
        f = set(x for x in f if isinstance(x, str)) if isinstance(f, list) else set()
        if "screen2" in f:                                   # E3: dort auch "… teilt den Bildschirm" (Leiste im Browser)
            return _("Answering opens the call in your browser – picture-in-picture and screen sharing work there.")
        if "pip_hint" in f:
            return _("Answering opens the call in your browser – picture-in-picture works there.")
        return _("Answering opens the call in your browser.")

    # --- Eingang: Live, Push, Nachholen ----------------------------------------------------------------------------
    def on_live(self, typ, d):
        if not isinstance(d, dict):
            return
        cid = str(d.get("call_id") or "")
        if typ in ("call.invite", "call.roster", "call.state", "call.mod", "call.ended"):
            self.run_check()                                 # (2.5, L6) Leiste "Anruf laeuft" nachziehen
        if typ == "call.ring":
            self.check(cid)
        elif typ == "call.invite":                          # (2.4) Gruppenanruf klingelt (grosse Gruppen: still)
            self._invites[cid] = d
            self.check(cid, again=True)                     # (2.5, L7) auch erneut geklingelt (schon erledigt)
        elif typ == "call.roster" and cid:                  # (2.4) eigenes Geraet ist beigetreten: hier aufhoeren
            me = self._me()
            r = self.ringing
            if r and r["id"] == cid and (r.get("group") or r.get("dm")) and me and \
                    any(isinstance(x, dict) and x.get("who") == me for x in d.get("legs") or []):
                self._done(cid)
        elif typ == "call.quiet" and cid:                   # (2.6) zurueckgehalten (eigenes Nicht stoeren): nur still zeigen
            if not (d.get("kind") == "group" and not d.get("dm")) and cid not in self.holds:
                self._why[cid] = d.get("why") if d.get("why") in ("dnd", "quiet") else "dnd"
                self.check(cid)                             # nachgeholte Ereignisse koennen alt sein: Zustand pruefen
        elif typ == "call.state" and cid:
            if cid in self.holds and d.get("state") in ("active", "ended", "declined"):
                self._hold_drop(cid)                        # (2.6) angenommen (anderswo), vorbei, abgelehnt
            st = d.get("state")
            # (2.5) 1:1 ueber Knoten (kind group, dm): "active" heisst angenommen - wie 1:1 aufhoeren
            group = (d.get("kind") == "group" and not d.get("dm")) or \
                bool(self.ringing and self.ringing["id"] == cid and self.ringing.get("group"))
            if st == "active" and group:
                return                                      # Gruppe: "zwei sind dabei" - weiter klingeln
            if st in ("active", "ended", "declined"):
                was = self.ringing and self.ringing["id"] == cid
                self._done(cid)
                if was and st == "active":
                    self.emit("info", _("The call was answered on another device."))
        elif typ in ("hello", "reset"):
            self.check_active()

    def on_push(self, d):
        """UnifiedPush-Mitteilung der Art "call" ({call, chat, media, caller, ttl}) -> True, wenn hier behandelt"""
        cid = str((d or {}).get("call") or "")
        if not cid:
            return False
        self._push[cid] = d
        group = (d or {}).get("kind") == "call.group" or bool((d or {}).get("group"))
        if group:                                           # (2.4) Gruppenanruf (Push-Art call.group)
            self._push_group.add(cid)
            self._invites.setdefault(cid, {"call_id": cid, "kind": "group", "media": d.get("media"), "chat_id": d.get("chat"),
                                           "from": {"name": d.get("caller")} if isinstance(d.get("caller"), str) else {}})
        self.check(cid, again=group)                        # (2.5, L7) Gruppe: auch erneut geklingelt
        return True

    def _push_fallback(self, cid):
        """Pruefen ging nicht (offline, Sitzung): die Push-Mitteilung des Servers wenigstens zeigen"""
        d = self._push.pop(cid, None)
        app = self.app or Gio.Application.get_default()
        if not d or app is None:
            return
        n = Gio.Notification.new(str(d.get("title") or "Saga")[:200])
        n.set_body(str(d.get("body") or ring_title({"media": d.get("media")}))[:300])
        n.set_priority(Gio.NotificationPriority.URGENT)
        url = d.get("url") if isinstance(d.get("url"), str) and d["url"].startswith("/chats#call-") else None
        if hasattr(n, "set_category"):
            n.set_category("call.incoming")
        # wie die normale Mitteilung: Klick zeigt das Fenster, Annehmen/Ablehnen als Knoepfe (nie sofort annehmen)
        n.set_default_action_and_target("app.call-show", GLib.Variant("s", cid))
        n.add_button_with_target(_("Decline"), "app.call-decline", GLib.Variant("s", cid))
        if url:
            if d.get("media") in ("audio", "video"):
                self._push_media[cid] = d["media"]
            n.add_button_with_target(_("Answer"), "app.call-accept", GLib.Variant("s", cid))
        try:
            app.send_notification("call-" + cid, n)
        except GLib.Error:
            pass

    def check_active(self):
        """laufende/klingelnde Anrufe (Start, Aufwachen, neuer Live-Kanal) - verpasstes Klingeln nachholen"""
        def fin(j):
            lst = [c for c in ((j or {}).get("calls") or []) if isinstance(c, dict)] if isinstance(j, dict) else []
            for c in lst:
                if (c.get("state") == "ringing" and c.get("role") == "callee") or group_rings(c):
                    if ring_mode(c) == "wait" and not own_running(lst, c.get("id")):
                        c = dict(c, waiting=False)            # (2.6) das eigene Gespraech ist vorbei: jetzt normal klingeln
                    self._maybe_ring(c)
            self._holds_free(lst)
            self._set_running(lst)
        bg(client.get, "api/calls/active", done=fin, error=lambda e: None)

    # --- (2.5, L6) Leiste "Anruf laeuft" ---------------------------------------------------------------------------------
    def run_check(self, ms=400):
        """GET /api/calls/active bald (gebuendelt) - nach Live-Ereignissen der Anrufe und wenn das Klingeln hier endet"""
        if self._run_t:
            GLib.source_remove(self._run_t)

        def go():
            self._run_t = 0
            self.check_active()
            return False
        self._run_t = GLib.timeout_add(ms, go)

    @staticmethod
    def _here():
        try:
            from .chat import chat
            return chat.device_id
        except (ImportError, AttributeError):
            return None

    def _learn_local(self, c):
        """Der Browser dieses Rechners, den die App eben geoeffnet hat, ist das erste eigene Geraet, das danach (binnen
        90 s) neu in "mine" auftaucht - fuer ihn zeigt die Leiste nichts (sonst "Anruf laeuft auf <Browser>" hier)"""
        L = self.local.get(c.get("id")) if isinstance(c.get("id"), str) else None
        if not L:
            return
        new = [d for d in _mine(c) if d not in L["before"] and d not in L["devs"]]
        if new and not L["devs"] and time.time() - L["t"] < 90:
            L["devs"].add(new[0])

    def _set_running(self, lst):
        for k in [k for k, L in self.local.items() if time.time() - L["t"] > 6 * 3600]:
            self.local.pop(k, None)
        here, rid = self._here(), (self.ringing or {}).get("id")
        out = []
        for c in lst if isinstance(lst, list) else []:
            if not isinstance(c, dict):                    # pt2 L3: Nicht-dict ueberspringen
                continue
            self._learn_local(c)
            if run_shown(c, here, rid, ((self.local.get(c["id"]) if isinstance(c.get("id"), str) else None) or {}).get("devs", ())):
                out.append(c)
        if out != self.running:
            self.running = out
            self.emit("running", out)

    def _opened(self, cid):
        """Anruf wurde von hier im Browser geoeffnet: eigene Geraete bis jetzt merken (siehe _learn_local)"""
        prev = next((c for c in self.running if c.get("id") == cid), None)
        L = self.local.setdefault(cid, {"t": 0, "before": set(), "devs": set()})
        L["t"] = time.time()
        L["before"] = (set(_mine(prev)) if prev else set()) - L["devs"]
        self.run_check(3000)

    def join_here(self, cid, role, video=False, parent=None):
        """(2.5, L6) "Hinzufuegen" / "Hierher uebernehmen" / "Beitreten": im Browser (#join-<id>-<role>[-video]) - die
        Web-Oberflaeche tritt mit diesem Rechner bei (Uebergabe, Rueckfall und gen regelt sie, APP-API handover2)"""
        if not valid_id(cid):
            return
        self._opened(cid)
        open_in_browser(join_url(cid, "video" if video else "audio", role), parent)
        self.emit("info", _("The call opens in your browser …"))

    def check(self, cid, again=False):
        """GET /api/calls/<id> und ggf. klingeln. again (2.5, L7: call.invite, Push call.group): auch fuer einen hier
        schon erledigten bzw. gerade klingelnden Anruf pruefen - erneutes Klingeln entscheidet _maybe_ring"""
        if not valid_id(cid) or cid in self.checking:
            return
        if not again and (cid in self.done or (self.ringing and self.ringing["id"] == cid)):
            return
        self.checking.add(cid)
        self._hold(True)

        def fin(j):
            self.checking.discard(cid)
            self._push.pop(cid, None)
            c = (j or {}).get("call") if isinstance(j, dict) else None
            inv = self._invites.pop(cid, None)
            if isinstance(c, dict) and inv and is_group(inv):     # Ereignis bringt Gruppe, Name und "still" mit
                c = dict(inv, **{k: v for k, v in c.items() if v is not None})
                c.setdefault("kind", "group")
            if isinstance(c, dict) and ((c.get("state") == "ringing" and c.get("role") == "callee") or group_rings(c)):
                if cid in self._why:
                    c = dict(c, why=self._why.pop(cid))
                self._maybe_ring(c)
            elif isinstance(c, dict) and self.ringing and self.ringing["id"] == cid:
                self._done(cid)                               # klingelte hier noch (again): aufhoeren
            elif isinstance(c, dict):
                self.done.setdefault(cid, time.time())        # (2.5, L7) erstes Erledigen zaehlt (rings_again)
            if not self.ringing:
                self._hold(False)

        def err(e):
            self.checking.discard(cid)
            self._invites.pop(cid, None)
            if not (isinstance(e, APIError) and e.code in (403, 404)):
                self._push_fallback(cid)
            self._push.pop(cid, None)
            if not self.ringing:
                self._hold(False)
        bg(client.get, f"api/calls/{cid}", done=fin, error=err)

    def _maybe_ring(self, c):
        cid = c.get("id")
        if not cid:
            return
        mode = ring_mode(c)
        if mode in ("wait", "quiet"):                        # (2.6) Anklopfen bzw. zurueckgehalten: Leiste, kein Klingeln
            if not (self.ringing and self.ringing["id"] == cid) and cid not in self.done:
                self._hold_show(c, mode)
            return
        if cid in self.holds:                                # (2.6) war Anklopfen: jetzt klingelt es normal
            self._hold_drop(cid, done=False)
        if self.ringing and self.ringing["id"] == cid:
            self._extend(c)
            return
        if cid in self.done:
            if not rings_again(c, self.done[cid]):         # (2.5, L7) erneut geklingelt? sonst bleibt es erledigt
                return
            self.done.pop(cid, None)
        if self.ringing:                                     # schon ein anderer Anruf klingelt: der erste bleibt
            return
        left = ring_left(c)
        dm = is_group(c) and bool(c.get("dm"))             # (2.5) 1:1 ueber Knoten: Leiste/Mitteilung wie 1:1
        group = is_group(c) and not dm
        self.ringing = {"id": cid, "media": c.get("media") or "audio", "chat_id": c.get("chat_id"), "dm": dm,
                        "peer": c.get("peer") or (c.get("from") if isinstance(c.get("from"), dict) else {}),
                        "until": time.monotonic() + left, "group": group,
                        "silent": bool(group and (c.get("silent") or c.get("quiet"))),      # (2.6) quiet: Nicht stoeren
                        "from": c.get("from") if isinstance(c.get("from"), dict) else {},
                        "since": c.get("invite_since") if group and _num(c.get("invite_since")) else None}
        self._hold(True)
        self._notify()
        if not self.ringing["silent"]:                      # grosse Gruppe (silent): nur Leiste und Mitteilung, kein Ton
            self.ringer.start()
        self.emit("ring", self.ringing)
        if any(x.get("id") == cid for x in self.running):  # (2.5, L7) erneut geklingelt: eigene Leiste statt "Anruf laeuft"
            self._set_running(self.running)
        if self._timer:
            GLib.source_remove(self._timer)
        self._timer = GLib.timeout_add(int(left * 1000) + 500, self._timeout, cid)

    def _extend(self, c):
        """(2.5, L7) klingelt hier schon und wurde erneut geklingelt (neueres invite_since): Klingeldauer ab dem neuen
        Beginn, gezieltes Klingeln ist laut (auch wenn der Start in einer grossen Gruppe still war)"""
        r, since = self.ringing, c.get("invite_since")
        if not r or not r.get("group") or not _num(since) or not _num(r.get("since")) or since <= r["since"] + 0.5:
            return
        left = ring_left(c)
        r["since"], r["until"] = since, time.monotonic() + left
        if r.get("silent") and not c.get("silent") and not c.get("quiet"):
            r["silent"] = False
            self._notify()
            self.ringer.start()
            self.emit("ring", r)
        if self._timer:
            GLib.source_remove(self._timer)
        self._timer = GLib.timeout_add(int(left * 1000) + 500, self._timeout, r["id"])

    def _timeout(self, cid):
        self._timer = 0
        if self.ringing and self.ringing["id"] == cid:
            self._done(cid)                                 # der Server meldet gleich "ended" (verpasst)
        return False

    # --- Aktionen ------------------------------------------------------------------------------------------------------
    def accept(self, cid, media=None, parent=None):
        """Annehmen: im Standardbrowser (Web-Oberflaeche nimmt an). Hier nicht mehr klingeln."""
        if not valid_id(cid):
            return
        r = self.ringing if self.ringing and self.ringing["id"] == cid else None
        media = media or (r or {}).get("media") or self._push_media.pop(cid, None) or "audio"
        # Gruppe: #join-<id>-member; 1:1 (auch ueber Knoten, dm): #call-<id>-<media> - die Web-Oberflaeche tritt einem
        # Raum-Anruf (kind group) dort ebenso bei (static/calls.js route)
        group = bool((r or {}).get("group")) or (cid in self._push_group and not (r or {}).get("dm"))
        self._push_group.discard(cid)
        self._done(cid)
        self._opened(cid)
        open_in_browser(join_url(cid, media) if group else call_url(cid, media), parent)
        self.emit("info", _("The call opens in your browser …"))

    def decline(self, cid):
        if not valid_id(cid):
            return
        self._done(cid)

        def err(e):
            if not (isinstance(e, APIError) and e.code in (404, 409)):
                self.emit("info", str(e))
        bg(client.json, "POST", f"api/calls/{cid}/decline", {"reason": "declined"}, error=err)

    def show(self, cid):
        """Klick auf die Mitteilung: Fenster nach vorn, Leiste zeigt den Anruf"""
        if self.ringing and self.ringing["id"] == cid:
            self.emit("ring", self.ringing)

    # --- (2.6) Anklopfen und zurueckgehaltene Anrufe ---------------------------------------------------------------------
    def hold_list(self):
        return sorted(self.holds.values(), key=lambda h: -h["at"])

    def _hold_show(self, c, mode):
        cid = c["id"]
        if cid in self.holds:
            return
        h = {"id": cid, "mode": mode, "media": c.get("media") or "audio", "chat_id": c.get("chat_id"),
             "peer": c.get("peer") or (c.get("from") if isinstance(c.get("from"), dict) else {}), "why": c.get("why") or "dnd",
             "group": is_group(c), "dm": bool(c.get("dm")), "at": time.monotonic(), "left": ring_left(c),
             "elsewhere": True if mode == "wait" else None, "run": None, "c": c}
        self.holds[cid] = h
        h["timer"] = GLib.timeout_add(int(h["left"] * 1000) + 3000, self._hold_timeout, cid)
        h["poll"] = GLib.timeout_add_seconds(5, self._hold_poll, cid)

        def fin(j):
            lst = (j or {}).get("calls") if isinstance(j, dict) else None
            runs = own_running(lst if isinstance(lst, list) else [], cid)
            if cid not in self.holds:
                return
            if mode == "quiet":
                h["elsewhere"] = bool(runs)                  # eigenes Gespraech laeuft: kein "Annehmen" (sonst zwei)
            h["run"] = runs[0]["id"] if len(runs) == 1 else None
            self.emit("hold", self.hold_list())

        def err(_e):
            if cid in self.holds and mode == "quiet":
                h["elsewhere"] = False
                self.emit("hold", self.hold_list())
        bg(client.get, "api/calls/active", done=fin, error=err)
        if mode == "wait":
            self.emit("hold", self.hold_list())

    def _hold_drop(self, cid, emit=True, done=True):
        h = self.holds.pop(cid, None)
        if h is None:
            return None
        for k in ("timer", "poll"):
            if h.get(k):
                GLib.source_remove(h[k])
                h[k] = 0
        if done:
            self.done[cid] = time.time()
        if emit:
            self.emit("hold", self.hold_list())
        return h

    def _hold_timeout(self, cid):
        h = self.holds.get(cid)
        if h is not None:
            h["timer"] = 0
            self._hold_drop(cid)
        return False

    def _hold_poll(self, cid):
        """wie beim Klingeln im Web: alle 5 s nachsehen, falls das Ende-Ereignis fehlt"""
        if cid not in self.holds:
            return False

        def fin(j):
            k = (j or {}).get("call") if isinstance(j, dict) else None
            if isinstance(k, dict) and k.get("state") in ("ended", "declined", "active") and cid in self.holds:
                self._hold_drop(cid)

        def err(e):
            if isinstance(e, APIError) and e.code == 404:
                self._hold_drop(cid)
        bg(client.get, f"api/calls/{cid}", done=fin, error=err)
        return True

    def _holds_free(self, lst):
        """Liste von GET /api/calls/active: laeuft kein eigenes Gespraech mehr, klingelt ein wartender Anruf jetzt normal"""
        for cid, h in list(self.holds.items()):
            if h["mode"] == "wait" and not own_running(lst, cid):
                left = h["left"] - (time.monotonic() - h["at"])
                self._hold_drop(cid, done=False)
                if left > 2:
                    self._maybe_ring(dict(h["c"], waiting=False, expires_in=left))
            elif h["mode"] == "wait":
                runs = own_running(lst, cid)
                run = runs[0]["id"] if len(runs) == 1 else None
                if run != h["run"]:
                    h["run"] = run
                    self.emit("hold", self.hold_list())

    def hold_dismiss(self, cid):
        """stille Leiste nur hier ausblenden (kein Ablehnen)"""
        self._hold_drop(cid)

    def hold_decline(self, cid):
        """Anklopfen ablehnen: besetzt (decline busy)"""
        if self._hold_drop(cid) is None:
            return

        def err(e):
            if not (isinstance(e, APIError) and e.code in (404, 409)):
                self.emit("info", str(e))
        bg(client.json, "POST", f"api/calls/{cid}/decline", {"reason": "busy"}, error=err)

    def hold_accept(self, cid, parent=None):
        """stumm: annehmen (im Browser) - nur ohne eigenes laufendes Gespraech"""
        h = self.holds.get(cid)
        if h is None or h.get("elsewhere") is not False:
            return
        self._hold_drop(cid)
        self._push_group.discard(cid)
        self._opened(cid)
        open_in_browser(join_url(cid, h["media"]) if h["group"] and not h["dm"] else call_url(cid, h["media"]), parent)
        self.emit("info", _("The call opens in your browser …"))

    def hold_take(self, cid, parent=None):
        """"Beenden und annehmen": eigenes Gespraech beenden (POST /api/calls/<run>/end) und ERST nach bestaetigtem Ende
        annehmen - sonst liefen zwei Gespraeche; ging das Beenden schief, klingelt der wartende Anruf normal weiter"""
        h = self.holds.get(cid)
        run = (h or {}).get("run")
        if h is None or not valid_id(run):
            return
        self._hold_drop(cid, done=False)

        def fin(j):
            if isinstance(j, dict) and j.get("ok"):
                # (Pruefer) erledigt: sonst klingelt er hier beim naechsten GET /api/calls/active (eigenes Gespraech vorbei,
                # Anruf noch ringing, bis der Browser angenommen hat) - Leiste ggf. wieder weg (zwischendurch neu gezeigt)
                self._hold_drop(cid)
                self.done[cid] = time.time()
                self._opened(cid)
                open_in_browser(call_url(cid, h["media"]), parent)
                self.emit("info", _("The call opens in your browser …"))
                return
            err(None)

        def err(_e):
            self.emit("info", _("The current call could not be ended reliably – the incoming call was not answered. Please try again."))
            left = h["left"] - (time.monotonic() - h["at"])
            if left > 2:
                self._maybe_ring(dict(h["c"], waiting=False, expires_in=left))
        bg(client.json, "POST", f"api/calls/{run}/end", {"reason": "hangup", "connected": True}, done=fin, error=err)

    # --- intern ----------------------------------------------------------------------------------------------------------
    def _done(self, cid):
        self.done[cid] = time.time()
        for k in [k for k, t in self.done.items() if time.time() - t > 3600]:
            self.done.pop(k, None)
        if self.ringing and self.ringing["id"] == cid:
            self._stop(withdraw=True)
            self.run_check()                                 # (2.5, L6) laeuft der Anruf weiter -> Leiste

    def _stop(self, withdraw=False):
        r, self.ringing = self.ringing, None
        self.ringer.stop()
        if self._timer:
            GLib.source_remove(self._timer)
            self._timer = 0
        if r is not None:
            app = self.app or Gio.Application.get_default()
            if withdraw and app is not None:
                app.withdraw_notification("call-" + r["id"])
            self.emit("ring", None)
        self._hold(False)

    def _hold(self, on):
        """im Dienst-Modus (UnifiedPush, ohne Fenster) die App waehrend des Klingelns am Leben halten"""
        app = self.app or Gio.Application.get_default()
        if app is None or on == self._held:
            return
        self._held = on
        (app.hold if on else app.release)()

    def _notify(self):
        r = self.ringing
        app = self.app or Gio.Application.get_default()
        if app is None or r is None:
            return
        n = Gio.Notification.new(ring_title({"peer": r["peer"], "media": r["media"], "group": r.get("group"), "from": r.get("from")}))
        n.set_body(_("Joining opens the call in your browser.") if r.get("group") else self.browser_hint)
        # grosse Gruppe (silent): kein dringendes Klingeln, nur der Hinweis "Anruf laeuft - beitreten"
        n.set_priority(Gio.NotificationPriority.NORMAL if r.get("silent") else Gio.NotificationPriority.URGENT)
        if hasattr(n, "set_category"):
            n.set_category("call.incoming")
        n.set_icon(Gio.ThemedIcon.new("call-start-symbolic"))
        n.set_default_action_and_target("app.call-show", GLib.Variant("s", r["id"]))
        n.add_button_with_target(_("Decline"), "app.call-decline", GLib.Variant("s", r["id"]))
        n.add_button_with_target(_("Join (in browser)") if r.get("group") else _("Answer"), "app.call-accept", GLib.Variant("s", r["id"]))
        try:
            app.send_notification("call-" + r["id"], n)       # dieselbe ID wie die Push-Mitteilung: ersetzt sie
        except GLib.Error:
            pass


calls = Calls()


def open_in_browser(uri, parent=None):
    try:
        Gtk.UriLauncher.new(uri).launch(parent, None, None)
    except (GLib.Error, TypeError):
        Gio.AppInfo.launch_default_for_uri(uri, None)


def install_actions(app):
    """app.call-accept / app.call-decline / app.call-show (Knoepfe der Desktop-Mitteilung)"""
    calls.app = app

    def act(name, fn):
        a = Gio.SimpleAction.new(name, GLib.VariantType.new("s"))
        a.connect("activate", lambda _a, v: fn(v.get_string()))
        app.add_action(a)

    def show(cid):
        app.activate()
        calls.show(cid)
    act("call-accept", lambda cid: calls.accept(cid, parent=getattr(app, "win", None)))
    act("call-decline", calls.decline)
    act("call-show", show)


# --- Leiste im Fenster ---------------------------------------------------------------------------------------------------
class CallBar(Gtk.Revealer):
    """Eingehender Anruf oben im Inhalt: Bild/Initialen, wer, Ablehnen, Annehmen (Ton bzw. Video)"""
    __gtype_name__ = "HKCallBar"

    def __init__(self, win):
        super().__init__(transition_type=Gtk.RevealerTransitionType.SLIDE_DOWN, reveal_child=False, visible=False)
        self.win = win
        self.cid = None
        box = Gtk.Box(spacing=12, margin_start=12, margin_end=12, margin_top=8, margin_bottom=8)
        box.add_css_class("hk-callbar")
        self.av = Adw.Avatar(size=40, text="?", show_initials=True)
        box.append(self.av)
        # Wer anruft, ist das Wichtigste: der Name steht allein im Titel (mit Mindestbreite), die Art des Anrufs darunter;
        # Knopftexte sind kurz (voller Text als Tooltip/Screenreader), damit der Name nicht abgeschnitten wird
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True, valign=Gtk.Align.CENTER)
        self.title = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.title.add_css_class("heading")
        col.append(self.title)
        self.kind = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.kind.add_css_class("caption")
        col.append(self.kind)
        self.sub = Gtk.Label(label=_("Answering opens the call in your browser."), xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.sub.add_css_class("dim-label")
        self.sub.add_css_class("caption")
        col.append(self.sub)
        box.append(col)
        self.dec = self._btn("call-stop-symbolic", _("Decline"), "destructive-action", lambda: calls.decline(self.cid))
        box.append(self.dec)
        self.acc_a = self._btn("call-start-symbolic", _("Answer"), "suggested-action", lambda: calls.accept(self.cid, "audio", self.win))
        box.append(self.acc_a)
        self.acc_v = self._btn("camera-video-symbolic", _("Answer with video"), "suggested-action",
                               lambda: calls.accept(self.cid, "video", self.win), short=_("Answer"))
        box.append(self.acc_v)
        self.set_child(box)
        self._h = [calls.connect("ring", lambda o, r: self.show(r)), calls.connect("info", lambda o, t: self.win.toast(t))]
        self.connect("destroy", lambda *_: [calls.disconnect(h) for h in self._h])
        if calls.ringing:
            self.show(calls.ringing)

    def _btn(self, icon, label, css, cb, short=None):
        b = Gtk.Button(valign=Gtk.Align.CENTER, tooltip_text=label)
        b.short = short or label                     # sichtbarer (kurzer) Text; der volle steht im Tooltip
        bc = Adw.ButtonContent(icon_name=icon, label=b.short)
        if hasattr(bc, "set_can_shrink"):
            bc.set_can_shrink(True)
        b.set_child(bc)
        b.add_css_class(css)
        b.add_css_class("pill")
        b.connect("clicked", lambda *_: cb())
        b.update_property([Gtk.AccessibleProperty.LABEL], [label])
        return b

    def set_compact(self, on):
        """schmales Fenster: runde Knoepfe nur mit Symbol (Beschriftung bleibt als Tooltip und fuer Screenreader)"""
        self.compact = on
        self.get_child().set_spacing(8 if on else 12)
        for b in (self.dec, self.acc_a, self.acc_v):
            b.get_child().set_label("" if on else b.short)
            (b.add_css_class if on else b.remove_css_class)("circular")
            (b.remove_css_class if on else b.add_css_class)("pill")
        self.sub.set_visible(not on)

    def show(self, r):
        if not r:
            self.cid = None
            self.set_reveal_child(False)
            self.set_visible(False)                  # versteckt: keine Mindestbreite mehr (Revealer behielte sie)
            return
        self.set_visible(True)
        self.cid = r["id"]
        p = r.get("peer") or {}
        group = bool(r.get("group"))
        grp = group_name(r) if group else ""
        self.av.set_text(avatars.initials_text(grp or p.get("name") or p.get("username") or "?"))
        avatars.attach(self.av, None if grp else p.get("avatar"), 40)
        video = r.get("media") == "video"
        name = grp or caller_name({"peer": p})
        if group:                                          # (2.4) Gruppenanruf: beitreten im Browser
            who = caller_name({"peer": p})
            if r.get("silent"):
                kind = _("Group call running")
            elif who and grp:
                kind = (_("{name} started a group video call") if video else _("{name} started a group call")).format(name=who)
            else:
                kind = _("Group video call") if video else _("Group call")
        else:
            kind = _("Incoming video call") if video else _("Incoming call")
        self.title.set_label(name or kind)
        self.title.set_width_chars(min(len(name or kind), 12))     # Name bleibt lesbar, Knoepfe geben nach
        self.kind.set_label(kind if name else "")
        self.kind.set_visible(bool(name))
        hint = _("Joining opens the call in your browser.") if group else calls.browser_hint
        self.sub.set_label(hint)                               # (2.4) im Browser: Bild-im-Bild, Bildschirm teilen
        self.sub.set_tooltip_text(hint)
        self.update_property([Gtk.AccessibleProperty.LABEL], [ring_title({"peer": p, "media": r.get("media"), "group": group,
                                                                            "from": r.get("from")})])
        self.dec.short = _("Ignore") if group else _("Decline")      # Gruppe: nur die eigene Einladung
        self.dec.get_child().set_label("" if getattr(self, "compact", False) else self.dec.short)
        self.dec.set_tooltip_text(self.dec.short)
        self.dec.update_property([Gtk.AccessibleProperty.LABEL], [self.dec.short])
        v_full = _("Join with video (in browser)") if group else _("Answer with video")
        self.acc_v.short = _("Join") if group else _("Answer")
        self.acc_v.get_child().set_label("" if getattr(self, "compact", False) else self.acc_v.short)
        self.acc_v.set_tooltip_text(v_full)
        self.acc_v.update_property([Gtk.AccessibleProperty.LABEL], [v_full])
        # Videoanruf: "Annehmen" (mit Video) ist die Hauptaktion, daneben kurz "Nur Ton"
        if group:
            full = _("Join with audio only (in browser)") if video else _("Join (in browser)")
            short = _("Audio only") if video else _("Join (in browser)")
        else:
            full = _("Answer with audio only") if video else _("Answer")
            short = _("Audio only") if video else _("Answer")
        self.acc_a.short = short
        self.acc_a.get_child().set_label("" if getattr(self, "compact", False) else short)
        self.acc_a.set_tooltip_text(full)
        self.acc_a.update_property([Gtk.AccessibleProperty.LABEL], [full])
        self.acc_v.set_visible(video)
        self.set_reveal_child(True)


class RunBar(Gtk.Revealer):
    """(2.5, L6) Leiste "Anruf laeuft": ein anderes eigenes Geraet ist im Anruf ("Anruf laeuft auf <Geraet>",
    "Hinzufuegen", "Hierher uebernehmen") bzw. ein Gruppenanruf in einem eigenen Chat ("Beitreten"). Die Knoepfe oeffnen
    den Anruf im Browser (#join-<id>-companion|handover|member[-video]). Zeigt den neuesten Anruf der Liste."""
    __gtype_name__ = "HKCallRunBar"

    def __init__(self, win):
        super().__init__(transition_type=Gtk.RevealerTransitionType.SLIDE_DOWN, reveal_child=False, visible=False)
        self.win = win
        self.cid = None
        self.compact = False
        self.buttons = []
        box = Gtk.Box(spacing=12, margin_start=12, margin_end=12, margin_top=6, margin_bottom=6)
        box.add_css_class("hk-callbar")
        box.add_css_class("hk-runbar")
        self.icon = Gtk.Image(icon_name="call-start-symbolic", valign=Gtk.Align.CENTER)
        box.append(self.icon)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True, valign=Gtk.Align.CENTER)
        self.title = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.title.add_css_class("heading")
        col.append(self.title)
        self.sub = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.sub.add_css_class("caption")
        col.append(self.sub)
        self.note = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.note.add_css_class("dim-label")
        self.note.add_css_class("caption")
        col.append(self.note)
        box.append(col)
        self.btns = Gtk.Box(spacing=8, valign=Gtk.Align.CENTER)
        box.append(self.btns)
        self.set_child(box)
        self.update_property([Gtk.AccessibleProperty.LABEL], [_("Call running")])
        self._h = [calls.connect("running", lambda o, lst: self.show(lst))]
        self.connect("destroy", lambda *_: [calls.disconnect(h) for h in self._h])
        if calls.running:
            self.show(calls.running)

    def set_compact(self, on):
        self.compact = on
        self.get_child().set_spacing(8 if on else 12)
        for b in self.buttons:
            self._style(b)
        self.note.set_visible(not on and bool(self.note.get_label()))

    def _style(self, b):
        b.get_child().set_label("" if self.compact else b.short)
        (b.add_css_class if self.compact else b.remove_css_class)("circular")
        (b.remove_css_class if self.compact else b.add_css_class)("pill")

    def show(self, lst):
        c = (lst or [None])[0] if isinstance(lst, list) else None
        if not isinstance(c, dict) or not c.get("id"):     # pt2 L3
            self.cid = None
            self.set_reveal_child(False)
            self.set_visible(False)
            return
        v = run_view(c)
        self.cid = c["id"]
        self.icon.set_from_icon_name("camera-video-symbolic" if v["video"] else "call-start-symbolic")
        self.title.set_label(v["title"])
        self.title.set_tooltip_text(v["title"])
        self.sub.set_label(v["sub"] or "")
        self.sub.set_visible(bool(v["sub"]))
        self.note.set_label(v["note"] or "")
        self.note.set_tooltip_text(v["note"] or None)
        self.note.set_visible(bool(v["note"]) and not self.compact)
        while (ch := self.btns.get_first_child()) is not None:
            self.btns.remove(ch)
        self.buttons = []
        icons = {"companion": "list-add-symbolic", "handover": "call-start-symbolic", "member": "call-start-symbolic"}
        for role, video, short, full, main in v["actions"]:
            b = Gtk.Button(valign=Gtk.Align.CENTER, tooltip_text=full)
            b.short, b.role, b.video = short, role, video
            ic = "camera-video-symbolic" if video else icons.get(role, "call-start-symbolic")
            bc = Adw.ButtonContent(icon_name=ic, label=short)
            if hasattr(bc, "set_can_shrink"):
                bc.set_can_shrink(True)
            b.set_child(bc)
            if main:
                b.add_css_class("suggested-action")
            b.connect("clicked", lambda _b, cid=c["id"], r=role, vid=video: calls.join_here(cid, r, vid, self.win))
            b.update_property([Gtk.AccessibleProperty.LABEL], [full])
            self._style(b)
            self.btns.append(b)
            self.buttons.append(b)
        self.update_property([Gtk.AccessibleProperty.LABEL], [" · ".join(x for x in (v["title"], v["sub"]) if x)])
        self.set_visible(True)
        self.set_reveal_child(True)


class HoldBar(Gtk.Revealer):
    """(2.6, Bugs-DB 2029-8) Anklopfen ("{name} klopft an": Ablehnen, ggf. Beenden und annehmen) bzw. zurueckgehaltener Anruf
    ("Anruf von {name} – stumm (Nicht stören)": Hinweis ausblenden, Annehmen nur ohne eigenes Gespraech). Kein Klingelton,
    keine dringende Mitteilung. Zeigt den neuesten Eintrag der Liste."""
    __gtype_name__ = "HKCallHoldBar"

    def __init__(self, win):
        super().__init__(transition_type=Gtk.RevealerTransitionType.SLIDE_DOWN, reveal_child=False, visible=False)
        self.win = win
        self.cid = None
        self.compact = False
        box = Gtk.Box(spacing=12, margin_start=12, margin_end=12, margin_top=6, margin_bottom=6)
        box.add_css_class("hk-callbar")
        box.add_css_class("hk-holdbar")
        self.box = box
        self.av = Adw.Avatar(size=36, text="?", show_initials=True, valign=Gtk.Align.CENTER)
        box.append(self.av)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True, valign=Gtk.Align.CENTER)
        self.title = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.title.add_css_class("heading")
        col.append(self.title)
        self.sub = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self.sub.add_css_class("caption")
        col.append(self.sub)
        # (Pruefer) Hinweis darf umbrechen (hoechstens 2 Zeilen) - neben den beiden Knoepfen sonst "… auf einem and…"
        self.note = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END, wrap=True, lines=2,
                              wrap_mode=Pango.WrapMode.WORD_CHAR, width_chars=20)
        self.note.add_css_class("dim-label")
        self.note.add_css_class("caption")
        col.append(self.note)
        box.append(col)
        self.hide_b = Gtk.Button(icon_name="window-close-symbolic", valign=Gtk.Align.CENTER, tooltip_text=_("Hide notice"))
        self.hide_b.add_css_class("flat")
        self.hide_b.add_css_class("circular")
        self.hide_b.update_property([Gtk.AccessibleProperty.LABEL], [_("Hide notice")])
        self.hide_b.connect("clicked", lambda *_: calls.hold_dismiss(self.cid))
        self.dec = self._btn("call-stop-symbolic", _("Decline"), "destructive-action", lambda: calls.hold_decline(self.cid))
        self.take = self._btn("call-start-symbolic", _("End and answer"), "suggested-action", lambda: calls.hold_take(self.cid, self.win))
        self.acc = self._btn("call-start-symbolic", _("Answer"), "suggested-action", lambda: calls.hold_accept(self.cid, self.win))
        for b in (self.dec, self.take, self.acc, self.hide_b):
            box.append(b)
        self.set_child(box)
        self._h = [calls.connect("hold", lambda o, lst: self.show(lst))]
        self.connect("destroy", lambda *_: [calls.disconnect(h) for h in self._h])
        if calls.holds:
            self.show(calls.hold_list())

    def _btn(self, icon, label, css, cb):
        b = Gtk.Button(valign=Gtk.Align.CENTER, tooltip_text=label)
        b.short = label
        bc = Adw.ButtonContent(icon_name=icon, label=label)
        if hasattr(bc, "set_can_shrink"):
            bc.set_can_shrink(True)
        b.set_child(bc)
        b.add_css_class(css)
        b.add_css_class("pill")
        b.connect("clicked", lambda *_: cb())
        b.update_property([Gtk.AccessibleProperty.LABEL], [label])
        return b

    def set_compact(self, on):
        self.compact = on
        self.box.set_spacing(8 if on else 12)
        for b in (self.dec, self.take, self.acc):
            b.get_child().set_label("" if on else b.short)
            (b.add_css_class if on else b.remove_css_class)("circular")
            (b.remove_css_class if on else b.add_css_class)("pill")
        self.note.set_visible(not on and bool(self.note.get_label()))

    def show(self, lst):
        h = (lst or [None])[0] if isinstance(lst, list) else None
        if not isinstance(h, dict) or not h.get("id"):
            self.cid = None
            self.set_reveal_child(False)
            self.set_visible(False)
            return
        v = hold_view(h)
        self.cid = h["id"]
        p = h.get("peer") or {}
        self.av.set_text(avatars.initials_text(p.get("name") or p.get("username") or "?"))
        avatars.attach(self.av, p.get("avatar"), 36)
        (self.box.add_css_class if h["mode"] == "quiet" else self.box.remove_css_class)("hk-hold-quiet")
        self.title.set_label(v["title"])
        self.title.set_tooltip_text(v["label"])
        self.sub.set_label(v["sub"])
        self.note.set_label(v["note"])
        self.note.set_tooltip_text(v["note"] or None)
        self.note.set_visible(bool(v["note"]) and not self.compact)
        self.dec.set_visible(v["decline"])
        self.take.set_visible(v["take"])
        name = caller_name(h) or "?"
        self.take.set_tooltip_text(_("End the current call and answer the call from {name}").format(name=name))
        self.take.update_property([Gtk.AccessibleProperty.LABEL], [_("End the current call and answer the call from {name}").format(name=name)])
        self.acc.set_visible(v["accept"])
        self.acc.set_tooltip_text(_("Answer call from {name}").format(name=name))
        self.acc.update_property([Gtk.AccessibleProperty.LABEL], [_("Answer call from {name}").format(name=name)])
        self.hide_b.set_visible(v["hide"])
        self.update_property([Gtk.AccessibleProperty.LABEL], [v["label"]])
        self.set_visible(True)
        self.set_reveal_child(True)
