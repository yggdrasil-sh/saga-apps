"""Chat-Grundlagen (Saga 2.5) - Oberflaeche: Reaktionen (Schnellleiste, Chips, "Reaktionen von ..."), Sprachnachrichten
(Wellenform-Spieler in der Blase, Aufnahme-Leiste mit Vorschau). Zustand und Krypto: chat.py, chatcrypto.py, voice.py.

Wie im Web (static/chat.js, static/chat-voice.js): Schnellleiste 👍 ❤️ 😂 😮 😢 🙏 und "+" (hier die GTK-Emoji-Auswahl,
ohne Netz), Chips unter der Blase (Emoji + Zahl, eigene hervorgehoben; Klick setzt bzw. nimmt die eigene, Rechtsklick
zeigt die Namen). Sprachnachricht: Play, 64 Balken (der gehoerte Teil eingefaerbt, Klick springt dorthin), Dauer aus
"dur", Tempo 1x / 1,5x / 2x; danach startet die naechste Sprachnachricht."""
from gi.repository import Adw, Gdk, GLib, Graphene, Gtk

from . import chatcrypto as C
from . import config, voice
from .chat import QUICK, rx_groups
from .i18n import _, ngettext, pgettext, ui_language


def _lbl(text="", css=(), **kw):
    lb = Gtk.Label(label=text, xalign=0, **kw)
    for c in css:
        lb.add_css_class(c)
    return lb


def rx_name(c, h):
    """Name zu einem Handle im Chat: ich = "du", Mitglied = Anzeigename, sonst "Ehemaliges Mitglied" (nie der Handle)"""
    if h and h == (c or {}).get("my_handle"):
        return pgettext("subject", "you")
    m = next((x for x in (c or {}).get("members") or [] if isinstance(x, dict) and x.get("handle") == h), None)
    return (m or {}).get("name") or _("Former member")


# --- Schnellleiste ------------------------------------------------------------------------------------------------------
def quick_bar(current, pick, more):
    """6 Emojis + "+": pick(emoji) bzw. more(); das aktuelle eigene Emoji ist hervorgehoben (noch einmal = zuruecknehmen)"""
    row = Gtk.Box(spacing=2, margin_start=4, margin_end=4, margin_top=4, margin_bottom=2)
    row.add_css_class("hk-rq")
    row.update_property([Gtk.AccessibleProperty.LABEL], [_("React")])
    for e in QUICK:
        b = Gtk.Button(label=e)                              # ohne Tooltip: Tooltips in Popovers liessen GTK 4.14 abstuerzen
        b.add_css_class("flat")
        b.add_css_class("hk-rq-b")
        if e == current:
            b.add_css_class("hk-rq-on")
        b.update_property([Gtk.AccessibleProperty.LABEL], [e])
        b.connect("clicked", lambda *_, e=e: pick(e))
        row.append(b)
    p = Gtk.Button(label="+")
    p.add_css_class("flat")
    p.add_css_class("hk-rq-b")
    p.add_css_class("hk-rq-more")
    p.update_property([Gtk.AccessibleProperty.LABEL], [_("More emojis")])
    p.connect("clicked", lambda *_: more())
    row.append(p)
    return row


def _point(pop, anchor, host):
    """Popover zeigt auf anchor; haengt an host (dauerhaft) - die Blase kann waehrenddessen neu gebaut werden"""
    if pop.get_parent() is None:
        pop.set_parent(host or anchor)
    if host is not None:
        ok, r = anchor.compute_bounds(host)
        if ok:
            rect = Gdk.Rectangle()
            rect.x, rect.y, rect.width, rect.height = int(r.get_x()), int(r.get_y()), max(1, int(r.get_width())), max(1, int(r.get_height()))
            pop.set_pointing_to(rect)


def point_at(pop, anchor, host):
    _point(pop, anchor, host)


def emoji_chooser(anchor, pick, host):
    """ "+" der Schnellleiste: GTK-Emoji-Auswahl (lokal, ohne Netz), zeigt auf anchor; pick(emoji). EINE Auswahl je host
    (Unterhaltung), dauerhaft daran und wiederverwendet - die Blase kann waehrenddessen neu gebaut werden."""
    ch = getattr(host, "_hk_emoji", None)
    if ch is None:
        ch = Gtk.EmojiChooser()
        host._hk_emoji = ch
        ch.hk_pick = None

        def picked(_c, text):                             # erst nach dem Schliessen
            fn, ch.hk_pick = ch.hk_pick, None
            if fn is not None:
                GLib.timeout_add(80, lambda: (fn(text), False)[1])
        ch.connect("emoji-picked", picked)
    ch.hk_pick = pick
    _point(ch, anchor, host)
    ch.popup()
    return ch


# --- Chips unter der Blase ----------------------------------------------------------------------------------------------
def reaction_chips(conv, c, m):
    """FlowBox mit einem Chip je Emoji (Emoji + Zahl); None ohne Reaktionen"""
    groups = rx_groups(m.get("rx"), (c or {}).get("my_handle"))
    if not groups:
        return None
    fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=8, min_children_per_line=1, row_spacing=3,
                     column_spacing=3, homogeneous=False, halign=Gtk.Align.START)
    fb.add_css_class("hk-rx")
    can = chat_can_react(c, m)
    for e, who, mine in groups:
        names = ", ".join(rx_name(c, h) for h in who)
        b = Gtk.Button()                                     # Namen: Rechtsklick bzw. Menue (kein Tooltip, siehe quick_bar)
        bx = Gtk.Box(spacing=4)
        bx.append(_lbl(e, ("hk-rx-e",)))
        bx.append(_lbl(str(len(who)), ("hk-rx-n", "caption")))
        b.set_child(bx)
        b.add_css_class("hk-rx-chip")
        if mine:
            b.add_css_class("hk-rx-mine")
        b.update_property([Gtk.AccessibleProperty.LABEL], [_("{emoji} from {names}").format(emoji=e, names=names)])
        b.set_sensitive(True)
        if can:
            b.connect("clicked", lambda *_, e=e, mine=mine: conv.react(m, "" if mine else e))
        else:
            b.connect("clicked", lambda *_, b=b, e=e, who=who: who_popover(b, c, e, who, conv))
        gc = Gtk.GestureClick(button=Gdk.BUTTON_SECONDARY)
        gc.connect("pressed", lambda g, *_a, b=b, e=e, who=who: (g.set_state(Gtk.EventSequenceState.CLAIMED), who_popover(b, c, e, who, conv)))
        b.add_controller(gc)
        lp = Gtk.GestureLongPress(touch_only=True)
        lp.connect("pressed", lambda g, *_a, b=b, e=e, who=who: who_popover(b, c, e, who, conv))
        b.add_controller(lp)
        fb.append(b)
    return fb


def chat_can_react(c, m):
    from .chat import can_react
    return can_react(c, m)


def who_popover(anchor, c, e, who, host=None):
    """ "Reaktionen von ...": Emoji und die Namen (ein Popover je host, wiederverwendet - siehe emoji_chooser)"""
    pop = getattr(host, "_hk_who", None) if host is not None else None
    if pop is None:
        pop = Gtk.Popover()
        if host is not None:
            host._hk_who = pop
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_top=8, margin_bottom=8, margin_start=10, margin_end=10)
    box.append(_lbl(e, ("title-3",)))
    for h in who:
        box.append(_lbl(rx_name(c, h)))
    pop.set_child(box)
    _point(pop, anchor, host)
    pop.popup()
    return pop


def reactions_dialog(win, c, m):
    """Menue "Reaktionen …": alle Reaktionen einer Nachricht mit Namen"""
    d = Adw.AlertDialog(heading=_("Reactions"))
    lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
    lb.add_css_class("boxed-list")
    groups = rx_groups(m.get("rx"), (c or {}).get("my_handle"))
    if not groups:
        lb.append(Adw.ActionRow(title=_("No reactions yet.")))
    for e, who, _mine in groups:
        r = Adw.ActionRow(title=GLib.markup_escape_text(", ".join(rx_name(c, h) for h in who)))
        r.add_prefix(_lbl(e, ("title-3",)))
        r.add_suffix(_lbl(str(len(who)), ("dim-label",)))
        lb.append(r)
    d.set_extra_child(lb)
    d.add_response("close", _("Close"))
    d.present(win)
    return d


# --- Wellenform ---------------------------------------------------------------------------------------------------------
def _accent(widget):
    try:
        return Adw.StyleManager.get_default().get_accent_color_rgba()
    except (AttributeError, TypeError):
        pass
    ok, col = widget.get_style_context().lookup_color("accent_color")
    if ok:
        return col
    col = Gdk.RGBA()
    col.parse("#3584e4")
    return col


class Waveform(Gtk.Widget):
    """64 Balken; frac = gehoerter Anteil (eingefaerbt). Klick bzw. Pfeiltasten springen (on_seek(frac))."""
    __gtype_name__ = "HKVoiceWave"

    def __init__(self, wf, on_seek=None, dur=0.0):
        super().__init__(focusable=True, hexpand=True, valign=Gtk.Align.CENTER)
        self.vals = voice.wf_decode(wf)
        self.frac = 0.0
        self.dur = dur
        self.on_seek = on_seek
        self.set_size_request(64 * 3, 30)
        self.add_css_class("hk-wave")
        self.set_accessible_role(Gtk.AccessibleRole.SLIDER)
        self.update_property([Gtk.AccessibleProperty.LABEL, Gtk.AccessibleProperty.VALUE_MIN, Gtk.AccessibleProperty.VALUE_MAX,
                              Gtk.AccessibleProperty.VALUE_NOW], [_("Position"), 0.0, float(max(1, round(dur))), 0.0])
        gc = Gtk.GestureClick()
        gc.connect("pressed", lambda _g, _n, x, _y: self._seek(x / max(1, self.get_width())))
        self.add_controller(gc)
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.add_controller(kc)

    def _key(self, _c, kv, _code, _state):
        if kv in (Gdk.KEY_Right, Gdk.KEY_Left):
            step = 5 / max(1.0, self.dur)
            self._seek(self.frac + (step if kv == Gdk.KEY_Right else -step))
            return True
        return False

    def _seek(self, f):
        f = max(0.0, min(1.0, f))
        if self.on_seek:
            self.on_seek(f)

    def set_frac(self, f):
        f = max(0.0, min(1.0, f))
        if round(f * 64) != round(self.frac * 64):
            self.frac = f
            self.queue_draw()
        else:
            self.frac = f
        self.update_property([Gtk.AccessibleProperty.VALUE_NOW], [float(round(f * self.dur))])

    def do_snapshot(self, snap):
        W, H = self.get_width(), self.get_height()
        if W <= 0 or H <= 0:
            return
        fg = self.get_color() if hasattr(self, "get_color") else Gdk.RGBA(red=0.5, green=0.5, blue=0.5, alpha=1)
        dim = Gdk.RGBA()
        dim.red, dim.green, dim.blue, dim.alpha = fg.red, fg.green, fg.blue, 0.35
        acc = _accent(self)
        n = len(self.vals)
        step = W / n
        bw = max(1.0, step * 0.6)
        on = round(self.frac * n)
        for i, v in enumerate(self.vals):
            h = max(3.0, H * v / 255)
            r = Graphene.Rect().init(i * step + (step - bw) / 2, (H - h) / 2, bw, h)
            snap.append_color(acc if i < on else dim, r)


# --- Spieler in der Blase -----------------------------------------------------------------------------------------------
class VoiceView(Gtk.Box):
    """Sprachnachricht: ▶/❚❚, Wellenform, Zeit, Tempo. source_fn() -> Klartext-Quelle (erst beim ersten Abspielen).
    Hoechstens eine Wiedergabe gleichzeitig; nach dem Ende ruft on_ended(self) (naechste Sprachnachricht)."""
    __gtype_name__ = "HKVoiceView"
    CURRENT = None

    def __init__(self, f, source_fn, on_ended=None):
        super().__init__(spacing=8, valign=Gtk.Align.CENTER)
        self.add_css_class("hk-voice")
        self.f = f
        self.dur = max(0.0, float(f.get("dur") or 0))
        self.source_fn = source_fn
        self.on_ended = on_ended
        self.player = None
        self.loading = False
        self.play_btn = Gtk.Button(icon_name="media-playback-start-symbolic", valign=Gtk.Align.CENTER, tooltip_text=_("Play voice message"))
        self.play_btn.add_css_class("circular")
        self.play_btn.add_css_class("hk-voice-play")
        self.play_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Play voice message")])
        self.play_btn.connect("clicked", lambda *_: self.toggle())
        self.append(self.play_btn)
        try:
            wf = C.unb64(f.get("wf") or "")
        except C.CryptoError:
            wf = b""
        self.wave = Waveform(wf, on_seek=self.seek, dur=self.dur)
        self.append(self.wave)
        self.time = _lbl(voice.fmt(self.dur), ("caption", "hk-voice-time"), wrap=False)
        self.append(self.time)
        self.speed_btn = Gtk.Button(label=self._speed_label(), valign=Gtk.Align.CENTER, tooltip_text=_("Speed"))
        self.speed_btn.add_css_class("flat")
        self.speed_btn.add_css_class("hk-voice-speed")
        self.speed_btn.update_property([Gtk.AccessibleProperty.LABEL], [_("Speed")])
        self.speed_btn.connect("clicked", lambda *_: self.next_speed())
        self.append(self.speed_btn)

    @staticmethod
    def speed():
        try:
            v = float(config.settings.get("voice_speed") or 1)
        except (TypeError, ValueError):
            v = 1.0
        return v if v in voice.SPEEDS else 1.0

    def _speed_label(self):
        v = self.speed()
        t = "%g×" % v
        return t.replace(".", ",") if (ui_language() or "en")[:2] in ("de", "es", "ru") else t

    def next_speed(self):
        v = voice.SPEEDS[(voice.SPEEDS.index(self.speed()) + 1) % len(voice.SPEEDS)]
        config.settings.set("voice_speed", v)
        self.speed_btn.set_label(self._speed_label())
        if self.player:
            self.player.set_rate(v)

    def _ensure(self):
        if self.player is not None:
            return True
        try:
            src = self.source_fn()
            self.player = voice.Player(src, self.dur, rate=self.speed())
        except Exception as e:  # noqa: BLE001 - kein GStreamer, kaputter Anhang
            self._fail(str(e))
            return False
        self.player.connect("changed", lambda p: self._sync())
        self.player.connect("ended", lambda p: self._ended())
        return True

    def toggle(self):
        if self.player is not None and self.player.playing:
            self.player.pause()
            return
        self.play()

    def play(self):
        cur = VoiceView.CURRENT
        if cur is not None and cur is not self:
            cur.pause()
        VoiceView.CURRENT = self
        if self._ensure():
            self.player.play()

    def pause(self):
        if self.player is not None and self.player.playing:
            self.player.pause()

    def seek(self, frac):
        if self._ensure():
            self.player.seek(frac)
            self.wave.set_frac(frac)

    def _sync(self):
        p = self.player
        if p is None:
            return
        if p.error:
            self._fail(p.error)
            return
        playing = p.playing
        self.play_btn.set_icon_name("media-playback-pause-symbolic" if playing else "media-playback-start-symbolic")
        t = _("Pause") if playing else _("Play voice message")
        self.play_btn.set_tooltip_text(t)
        self.play_btn.update_property([Gtk.AccessibleProperty.LABEL], [t])
        pos = p.pos
        self.wave.set_frac(pos / self.dur if self.dur else 0)
        self.time.set_label(voice.fmt(pos if (playing or pos > 0) else self.dur))
        (self.add_css_class if playing else self.remove_css_class)("hk-voice-playing")

    def _ended(self):
        self.wave.set_frac(0)
        self.time.set_label(voice.fmt(self.dur))
        if VoiceView.CURRENT is self:
            VoiceView.CURRENT = None
        if self.on_ended:
            self.on_ended(self)

    def _fail(self, text):
        self.play_btn.set_icon_name("dialog-warning-symbolic")
        self.play_btn.set_tooltip_text(_("Voice message can't be played.") + (f" ({text})" if text else ""))

    def stop(self):
        if self.player is not None:
            self.player.close()
            self.player = None
        if VoiceView.CURRENT is self:
            VoiceView.CURRENT = None


# --- Aufnahme-Leiste ----------------------------------------------------------------------------------------------------
class RecordBar(Gtk.Box):
    """Statt der Eingabezeile, solange aufgenommen wird: ● Zeit / 15:00, Pegel, Abbrechen, Stopp; danach Vorschau mit
    Abspielen, Verwerfen, Senden. Klick startet, Klick stoppt. Grenzen: 14:00 Hinweis, 15:00 Stopp, unter 1 s verworfen."""
    __gtype_name__ = "HKRecordBar"

    def __init__(self, conv, max_s=voice.MAX_S, kbps=24, src=None):
        super().__init__(spacing=8, margin_start=10, margin_end=10, margin_top=6, margin_bottom=10)
        self.add_css_class("hk-recbar")
        self.conv, self.max_s, self.kbps, self.src = conv, max_s, kbps, src
        self.rec = None
        self.res = None
        self.state = "idle"             # idle | wait | rec | stop | prev | send
        self.warned = False
        self.preview = None
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", lambda _c, kv, *_a: kv == Gdk.KEY_Escape and self.state in ("rec", "prev", "wait")
                   and (self.cancel(), True)[1])
        self.add_controller(kc)

    def _clear(self):
        while (w := self.get_first_child()):
            self.remove(w)

    def start(self):
        self.state = "wait"
        self.warned = False
        self._draw()
        self.rec = voice.Recorder(on_tick=self._tick, on_limit=self._limit, src=self.src, max_s=self.max_s, kbps=self.kbps)
        try:
            self.rec.start()
        except voice.VoiceError:
            self.rec = None
            self.state = "idle"
            self.conv.record_ended(_("Recording isn't possible – no microphone found or GStreamer (Opus) is missing."))
            return False
        self.state = "rec"
        self._draw()
        return True

    def _tick(self, sec, lv):
        if self.state != "rec":
            return
        if not self.warned and sec >= self.max_s - voice.WARN_BEFORE_S:
            self.warned = True
            self.conv.toast(_("One more minute – then the recording ends."))
            self.time.add_css_class("hk-rec-warn")
        self.time.set_label(voice.fmt(sec) + " / " + voice.fmt(self.max_s))
        self.level.set_value(max(0.04, lv))

    def _limit(self):
        if self.state == "rec":
            self.conv.toast(ngettext("At most {n} minute – recording ended.", "At most {n} minutes – recording ended.",
                                     self.max_s // 60).format(n=self.max_s // 60))
            self.stop()

    def stop(self):
        if self.state != "rec" or self.rec is None:
            return
        self.state = "stop"
        self._draw()
        self.rec.stop(self._stopped, self._failed)

    def _stopped(self, res):
        self.rec = None
        if self.state != "stop":
            return
        if res["dur"] < voice.MIN_S:
            self.state = "idle"
            self.conv.record_ended(_("Too short – not sent."))
            return
        self.res = res
        self.state = "prev"
        self._draw()

    def _failed(self, e):
        self.rec = None
        self.state = "idle"
        self.conv.record_ended(_("Recording failed: {err}").format(err=e))

    def cancel(self):
        if self.rec is not None:
            self.rec.cancel()
            self.rec = None
        if self.preview is not None:
            self.preview.stop()
            self.preview = None
        self.state = "idle"
        self.res = None
        self.conv.record_ended(None)

    def send(self):
        if self.state != "prev" or not self.res:
            return
        self.state = "send"
        if self.preview is not None:
            self.preview.pause()
        self._draw()
        self.conv.send_voice(self.res)

    def sent(self, ok, text=None):
        """Ergebnis des Sendens (Conversation): ok -> Leiste weg, sonst zurueck zur Vorschau"""
        if ok:
            if self.preview is not None:
                self.preview.stop()
                self.preview = None
            self.state = "idle"
            self.res = None
            self.conv.record_ended(None)
        else:
            self.state = "prev"
            self._draw()
            if text:
                self.conv.toast(text)

    def progress(self, frac):
        if self.state == "send" and getattr(self, "prog", None) is not None:
            self.prog.set_label(f"{int(frac * 100)} %")

    def _draw(self):
        self._clear()
        if self.state in ("prev", "send"):
            res = self.res
            if self.preview is None:
                data = res["data"]
                f = {"id": "preview", "dur": res["dur"], "wf": C.b64(res["wf"])}
                self.preview = VoiceView(f, lambda: voice.BytesSource(data))
                self.preview.set_hexpand(True)
            self.append(self.preview)
            if self.state == "send":
                self.prog = _lbl("0 %", ("caption", "dim-label"), wrap=False)
                self.append(self.prog)
            d = Gtk.Button(icon_name="user-trash-symbolic", tooltip_text=_("Discard"), valign=Gtk.Align.CENTER)
            d.add_css_class("flat")
            d.update_property([Gtk.AccessibleProperty.LABEL], [_("Discard")])
            d.connect("clicked", lambda *_: self.cancel())
            d.set_sensitive(self.state == "prev")
            self.append(d)
            s = Gtk.Button(label=_("Send"), valign=Gtk.Align.CENTER)
            s.add_css_class("suggested-action")
            s.add_css_class("pill")
            s.add_css_class("hk-rec-send")
            s.connect("clicked", lambda *_: self.send())
            s.set_sensitive(self.state == "prev")
            self.append(s)
            if self.state == "prev":
                GLib.idle_add(lambda: (s.grab_focus(), False)[1])
            return
        dot = _lbl("●", ("hk-rec-dot",), wrap=False, valign=Gtk.Align.CENTER)
        dot.set_accessible_role(Gtk.AccessibleRole.PRESENTATION)
        self.append(dot)
        self.time = _lbl(_("Microphone …") if self.state == "wait" else voice.fmt(self.rec.secs if self.rec else 0) + " / " +
                         voice.fmt(self.max_s), ("hk-rec-time", "numeric"), wrap=False, valign=Gtk.Align.CENTER)
        self.time.set_accessible_role(Gtk.AccessibleRole.TIMER)
        if self.warned:
            self.time.add_css_class("hk-rec-warn")
        self.append(self.time)
        self.level = Gtk.LevelBar(min_value=0, max_value=1, value=0.04, hexpand=True, valign=Gtk.Align.CENTER)
        self.level.add_css_class("hk-rec-level")
        self.level.update_property([Gtk.AccessibleProperty.LABEL], [_("Level")])
        self.append(self.level)
        x = Gtk.Button(label=_("Cancel"), valign=Gtk.Align.CENTER)
        x.add_css_class("flat")
        x.connect("clicked", lambda *_: self.cancel())
        self.append(x)
        st = Gtk.Button(icon_name="media-playback-stop-symbolic", tooltip_text=_("Stop"), valign=Gtk.Align.CENTER)
        st.add_css_class("circular")
        st.add_css_class("destructive-action")
        st.add_css_class("hk-rec-stop")
        st.update_property([Gtk.AccessibleProperty.LABEL], [_("Stop recording")])
        st.connect("clicked", lambda *_: self.stop())
        st.set_sensitive(self.state == "rec")
        self.append(st)
        if self.state == "rec":
            GLib.idle_add(lambda: (st.grab_focus(), False)[1])

