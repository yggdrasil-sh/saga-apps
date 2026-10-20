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
import re
import struct
import time
import wave

from gi.repository import Adw, Gio, GLib, GObject, Gtk, Pango

from . import avatars, config
from .api import APIError, client
from .i18n import _
from .util import bg

RING_FALLBACK_S = 45
_ID_RE = re.compile(r"[0-9a-f]{32}")


def valid_id(cid):
    """Anruf-Kennung: genau 32 Hex-Zeichen (klein) - landet in API-Pfaden und Adressen"""
    return isinstance(cid, str) and _ID_RE.fullmatch(cid) is not None


def call_url(cid, media):
    return f"{config.settings.server.rstrip('/')}/chats#call-{cid}-{'video' if media == 'video' else 'audio'}"


def join_url(cid, media):
    """(2.4) Gruppenanruf beitreten - die Web-Oberflaeche tritt mit role "member" bei (static/calls.js route)"""
    return f"{config.settings.server.rstrip('/')}/chats#join-{cid}-member{'-video' if media == 'video' else ''}"


def is_group(c):
    return isinstance(c, dict) and (c.get("kind") == "group" or bool(c.get("group")))


def group_rings(c):
    """(2.4) GET /api/calls/<id> eines Gruppenanrufs: klingelt es fuer mich? Einladung offen (invite "ringing" bzw. ohne
    Feld), Anruf laeuft noch (ringing/active - "active" heisst nur: zwei sind dabei) und keins meiner Geraete ist dabei"""
    return is_group(c) and c.get("state") in ("ringing", "active") and c.get("invite", "ringing") == "ringing" \
        and not c.get("mine")


def chat_url(chat_id):
    return f"{config.settings.server.rstrip('/')}/chats#c{int(chat_id)}"


def caller_name(call):
    p = (call or {}).get("peer") or (call or {}).get("from") or {}
    return p.get("name") or p.get("username") or ""


def group_name(call):
    """Name der Gruppe (from.chat des Ereignisses bzw. des Anrufs) oder ein leerer Text"""
    f = (call or {}).get("from") if isinstance((call or {}).get("from"), dict) else {}
    ch = f.get("chat")
    return (ch.get("title") or ch.get("name") or "") if isinstance(ch, dict) else (ch if isinstance(ch, str) else "")


def ring_title(call):
    name = caller_name(call)
    video = (call or {}).get("media") == "video"
    if is_group(call):
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
    return {"call": str(d.get("call") or ""), "media": "video" if d.get("media") == "video" else "audio", "status": st, "dur": dur,
            "from": str(d.get("from") or ""), "to": str(d.get("to") or "")}


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


class Ringer:
    """Klingelton direkt mit GStreamer (playbin, nur Ton, Videoausgang fakesink, Schleife ueber EOS -> an den Anfang).
    Bewusst nicht Gtk.MediaFile: dessen GStreamer-Backend baut einen GL-Videopfad auf und bricht ohne GLES ab.
    Fehlt GStreamer bzw. wavparse (gstreamer1-plugins-good) oder ein Audio-Ausgang: still - Leiste und Mitteilung bleiben."""

    def __init__(self):
        self.pipe = None

    def start(self):
        if self.pipe is not None or os.environ.get("HK_NO_RING") or _quiet():
            return
        try:
            import gi
            gi.require_version("Gst", "1.0")
            from gi.repository import Gst
            Gst.init(None)
            pipe = Gst.ElementFactory.make("playbin", "hk-ring")
            if pipe is None:
                return
            pipe.set_property("uri", GLib.filename_to_uri(_tone_file(), None))
            pipe.set_property("volume", 0.8)
            fake = Gst.ElementFactory.make("fakesink")                 # nie ein Videopfad (WAV hat keinen, sicher ist sicher)
            if fake is not None:
                pipe.set_property("video-sink", fake)
            bus = pipe.get_bus()
            bus.add_signal_watch()
            bus.connect("message::eos", lambda _b, _m: pipe.seek_simple(Gst.Format.TIME, Gst.SeekFlags.FLUSH, 0))
            bus.connect("message::error", lambda _b, _m: self.stop())
            if pipe.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
                pipe.set_state(Gst.State.NULL)
                return
            self.pipe = pipe
        except (ImportError, ValueError, GLib.Error, OSError, TypeError, AttributeError):
            self.pipe = None

    def stop(self):
        p, self.pipe = self.pipe, None
        if p is not None:
            try:
                from gi.repository import Gst
                p.get_bus().remove_signal_watch()
                p.set_state(Gst.State.NULL)
            except (ImportError, GLib.Error, TypeError, AttributeError):
                pass


# --- Zustand -------------------------------------------------------------------------------------------------------------
class Calls(GObject.Object):
    """Klingeln (Haupt-Thread). Signal "ring" (dict|None): Leiste im Fenster zeigen/verbergen."""
    __gsignals__ = {
        "ring": (GObject.SignalFlags.RUN_FIRST, None, (object,)),
        "info": (GObject.SignalFlags.RUN_FIRST, None, (str,)),       # kurze Meldung (Toast)
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
        self.ringer = Ringer()
        self._timer = 0
        self._held = False

    def reset(self):
        self._stop(withdraw=True)
        self.done.clear()
        self.checking.clear()
        self._push_media.clear()
        self._invites.clear()
        self.config = None

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
            if done:
                done(self.enabled)

        def err(e):
            self.config = {"enabled": False} if isinstance(e, APIError) and e.code in (403, 404) else self.config
            if done:
                done(self.enabled)
        bg(client.get, "api/calls/config", done=fin, error=err)

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
        if typ == "call.ring":
            self.check(cid)
        elif typ == "call.invite":                          # (2.4) Gruppenanruf klingelt (grosse Gruppen: still)
            self._invites[cid] = d
            self.check(cid)
        elif typ == "call.roster" and cid:                  # (2.4) eigenes Geraet ist beigetreten: hier aufhoeren
            me = self._me()
            r = self.ringing
            if r and r["id"] == cid and r.get("group") and me and \
                    any(isinstance(x, dict) and x.get("who") == me for x in d.get("legs") or []):
                self._done(cid)
        elif typ == "call.state" and cid:
            st = d.get("state")
            group = d.get("kind") == "group" or bool(self.ringing and self.ringing["id"] == cid and self.ringing.get("group"))
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
        if (d or {}).get("kind") == "call.group" or (d or {}).get("group"):     # (2.4) Gruppenanruf (Push-Art call.group)
            self._push_group.add(cid)
            self._invites.setdefault(cid, {"call_id": cid, "kind": "group", "media": d.get("media"), "chat_id": d.get("chat"),
                                           "from": {"name": d.get("caller")} if isinstance(d.get("caller"), str) else {}})
        self.check(cid)
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
            for c in (j or {}).get("calls") or []:
                if isinstance(c, dict) and ((c.get("state") == "ringing" and c.get("role") == "callee") or group_rings(c)):
                    self._maybe_ring(c)
        bg(client.get, "api/calls/active", done=fin, error=lambda e: None)

    def check(self, cid):
        if not valid_id(cid) or cid in self.done or cid in self.checking or (self.ringing and self.ringing["id"] == cid):
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
                self._maybe_ring(c)
            elif isinstance(c, dict):
                self.done[cid] = time.time()
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
        if not cid or cid in self.done or (self.ringing and self.ringing["id"] == cid):
            return
        if self.ringing:                                     # schon ein anderer Anruf klingelt: der erste bleibt
            return
        left = c.get("expires_in")
        left = float(left) if isinstance(left, (int, float)) and left > 0 else float(c.get("ring_s") or RING_FALLBACK_S)
        group = is_group(c)
        self.ringing = {"id": cid, "media": c.get("media") or "audio", "chat_id": c.get("chat_id"),
                        "peer": c.get("peer") or (c.get("from") if isinstance(c.get("from"), dict) else {}),
                        "until": time.monotonic() + left, "group": group, "silent": bool(group and c.get("silent")),
                        "from": c.get("from") if isinstance(c.get("from"), dict) else {}}
        self._hold(True)
        self._notify()
        if not self.ringing["silent"]:                      # grosse Gruppe (silent): nur Leiste und Mitteilung, kein Ton
            self.ringer.start()
        self.emit("ring", self.ringing)
        if self._timer:
            GLib.source_remove(self._timer)
        self._timer = GLib.timeout_add(int(left * 1000) + 500, self._timeout, cid)

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
        group = bool((r or {}).get("group")) or cid in self._push_group
        self._push_group.discard(cid)
        self._done(cid)
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

    # --- intern ----------------------------------------------------------------------------------------------------------
    def _done(self, cid):
        self.done[cid] = time.time()
        for k in [k for k, t in self.done.items() if time.time() - t > 3600]:
            self.done.pop(k, None)
        if self.ringing and self.ringing["id"] == cid:
            self._stop(withdraw=True)

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
