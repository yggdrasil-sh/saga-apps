"""KI-Assistent (Server ab 1.6): "Frag deine Seiten" und "Diese Seite" (Zusammenfassen, Aufgaben vorschlagen, Markierten
Text verbessern, Uebersetzen). Der Server spricht mit dem Modell, die App nie direkt; Antworten kommen als Strom
(text/event-stream). Antworten sind Klartext (kein Markup, keine Links aus dem Modelltext) - nur [n] wird zu einem
Verweis auf die Quelle n. Der Assistent aendert nichts: Uebernehmen macht die App ueber die normalen Endpunkte, nach
einem Klick. Die Fusszeile zeigt immer den Hinweis des Servers (wohin Inhalte gehen) und das Kontingent."""
import re
import threading

from gi.repository import Adw, Gdk, GLib, GObject, Gtk, Pango

from .api import APIError, client, sse_events
from .i18n import _, ngettext
from .util import bg, esc, new_block_id, rel_time

CITE_RX = re.compile(r"\[(\d{1,3})\]")


class AIState(GObject.Object):
    """Stand des Assistenten fuer dieses Konto (GET /api/ai/status) und - Server ab 2.1 - die Persona der KI (Name,
    Profilbild). Die Persona steckt in status.persona, solange der Assistent fuer mich an ist; sonst (z. B. nicht in der
    Gruppe) kommt sie aus GET /api/ai/persona - KI-Antworten anderer im Chat zeigen trotzdem Name und Bild.
    Aeltere Server ohne Persona: persona() ist None, alles bleibt wie bisher ("Assistent")."""
    __gsignals__ = {"changed": (GObject.SignalFlags.RUN_FIRST, None, ())}

    def __init__(self):
        super().__init__()
        self.status = {}
        self.known = False
        self.persona_only = None        # /api/ai/persona, wenn status keine Persona enthaelt

    @property
    def enabled(self):
        return bool(self.status.get("enabled"))

    @property
    def chat_allowed(self):
        """Server erlaubt KI in Chats ("KI in Chats erlauben") und der Assistent ist fuer mich an"""
        return self.enabled and bool(self.status.get("chat"))

    def persona(self):
        p = self.status.get("persona")
        if not isinstance(p, dict):
            p = self.persona_only
        return p if isinstance(p, dict) and isinstance(p.get("name"), str) else None

    def refresh(self, done=None):
        def work():
            try:
                st = client.get("api/ai/status")
            except APIError as e:
                if e.code not in (404, 401, 403):
                    raise
                st = {}
            st = st if isinstance(st, dict) else {}
            po = None
            if not isinstance(st.get("persona"), dict):
                try:
                    po = client.get("api/ai/persona")             # Server ab 2.1; aeltere: 404
                except (APIError, ValueError):
                    po = None
            return st, po if isinstance(po, dict) else None

        def fin(r):
            self.status, self.persona_only = r
            self.known = True
            self.emit("changed")
            if done:
                done()

        def err(e):
            if done:
                done()
        bg(work, done=fin, error=err)

    def reset(self):
        self.status, self.known, self.persona_only = {}, False, None
        self.emit("changed")

    def on_live(self, typ, d):
        """ai.persona.changed (Name/Bild/Schalter geaendert) bzw. reset: neu abfragen ("enabled" ist je Person verschieden)"""
        if typ == "ai.persona.changed" and isinstance(d, dict):
            p = self.persona()
            if p is not None and d.get("avatar") != p.get("avatar"):
                from . import avatars
                avatars.forget(p.get("avatar"))
        if typ in ("ai.persona.changed", "reset"):
            self.refresh()


ai = AIState()


def assistant_icon():
    from .publish import _icon
    return _icon("saga-assistant-symbolic", "starred-symbolic")


# --- Persona (Server ab 2.1): Name und Profilbild der KI ueberall, wo die App KI anbietet ------------------------------
def ai_name():
    """Anzeigename der KI: vom Server (eigener Name bzw. Standard in der Sprache der Anfrage), sonst "Saga KI\""""
    p = ai.persona()
    n = (p or {}).get("name")
    n = n.strip() if isinstance(n, str) else ""
    return n[:80] if n else _("Saga AI")


def ask_label():
    """Einstieg: "Frage Marin" - aeltere Server ohne Persona: wie bisher "Assistent\""""
    return _("Ask {name}").format(name=ai_name()) if ai.persona() else _("Assistant")


def assistant_title():
    return ai_name() if ai.persona() else _("AI assistant")


def thinking_text():
    return _("{name} is thinking …").format(name=ai_name()) if ai.persona() else _("Thinking …")


def ai_avatar(size=24, **kw):
    """Profilbild der KI (rund): Bild vom Server (/api/ai/avatar, Zwischenspeicher nach avatar_etag), sonst - und bis es
    geladen ist - das neutrale Symbol auf Akzentfarbe"""
    from . import avatars
    p = ai.persona() or {}
    av = Adw.Avatar(size=size, text=ai_name(), show_initials=False, icon_name=assistant_icon(), **kw)
    av.add_css_class("hk-ai-avatar")
    av.set_tooltip_text(ai_name())
    avatars.attach(av, p.get("avatar"), size, etag=p.get("avatar_etag"))
    return av


def ai_head(size=20, extra=None):
    """Kopf einer KI-Antwort: Profilbild + Name (+ z. B. "gefragt von Ben")"""
    box = Gtk.Box(spacing=6)
    box.add_css_class("hk-ai-who")
    box.append(ai_avatar(size, valign=Gtk.Align.CENTER))
    nm = Gtk.Label(label=ai_name(), xalign=0, ellipsize=Pango.EllipsizeMode.END)
    nm.add_css_class("caption-heading")
    box.append(nm)
    if extra:
        ex = Gtk.Label(label=extra, xalign=0, ellipsize=Pango.EllipsizeMode.END)
        ex.add_css_class("caption")
        ex.add_css_class("dim-label")
        box.append(ex)
    return box


def cite_markup(text, sources):
    """Klartext -> Markup; [n] wird zum Verweis, wenn es die Quelle n gibt"""
    n_ok = {int(s.get("n")) for s in sources or [] if isinstance(s, dict) and str(s.get("n", "")).isdigit() and s.get("id") is not None}
    out, pos = [], 0
    for m in CITE_RX.finditer(text):
        out.append(esc(text[pos:m.start()]))
        n = int(m.group(1))
        out.append(f'<a href="src:{n}">[{n}]</a>' if n in n_ok else esc(m.group(0)))
        pos = m.end()
    out.append(esc(text[pos:]))
    return "".join(out)


class Stream:
    """Eine laufende Anfrage (Hintergrund-Thread) -> Rueckrufe im Haupt-Thread: on_sources, on_delta, on_done, on_error"""

    def __init__(self, path, body, on_sources, on_delta, on_done, on_error, on_reset=None):
        self.path, self.body = path, body
        self.cb = (on_sources, on_delta, on_done, on_error, on_reset or (lambda: None))
        self.cancelled = False
        self.resp = None
        threading.Thread(target=self._run, daemon=True, name="hk-ai").start()

    def _post(self, i, *a):
        if not self.cancelled or i == 3:
            GLib.idle_add(lambda: (self.cb[i](*a), False)[1])

    def _run(self):
        try:
            self.resp = client.stream("POST", self.path, self.body, timeout=60)
        except APIError as e:
            self._post(3, str(e), False)
            return
        got = False
        try:
            for ev, data, _eid in sse_events(self.resp, stop=lambda: self.cancelled):
                data = data if isinstance(data, dict) else {}
                if ev == "sources":
                    self._post(0, [s for s in data.get("sources") or [] if isinstance(s, dict)])
                elif ev == "delta":
                    got = True
                    self._post(1, str(data.get("t") or ""))
                elif ev == "reset":                          # Server beginnt die Antwort neu (bisherigen Text verwerfen)
                    self._post(4)
                elif ev == "done":
                    self._post(2, data)
                    return
                elif ev == "error":
                    self._post(3, str(data.get("error") or _("Error")), bool(data.get("partial")))
                    return
            if not self.cancelled:
                self._post(3, _("Server not reachable"), got)
        except (OSError, ValueError, TimeoutError, AttributeError) as e:
            if not self.cancelled:
                self._post(3, str(e), got)
        finally:
            self.close()

    def cancel(self):
        """Abbrechen = Verbindung schliessen (der Server bricht die Modell-Anfrage ab)"""
        self.cancelled = True
        self.close()

    def close(self):
        r, self.resp = self.resp, None
        if r is None:
            return
        try:
            import socket
            r.fp.raw._sock.shutdown(socket.SHUT_RDWR)          # noqa: SLF001
        except (OSError, AttributeError, ValueError):
            pass
        try:
            r.close()
        except (OSError, AttributeError, ValueError):
            pass


def _bar():
    """Knopfleiste, die in schmalen Fenstern umbricht"""
    return Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, column_spacing=8, row_spacing=6, max_children_per_line=3,
                       homogeneous=False, halign=Gtk.Align.START)


def _lbl(text="", css=(), **kw):
    lb = Gtk.Label(label=text, xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, **kw)
    for c in css:
        lb.add_css_class(c)
    return lb


class AssistantDialog(Adw.Dialog):
    """Zwei Reiter: "Frag deine Seiten" und "Diese Seite" (nur mit offener Seite)"""

    def __init__(self, win, view=None, tab="ask", action=None):
        super().__init__(title=assistant_title(), content_width=560, content_height=700, width_request=320, height_request=360)
        self.win, self.view = win, view
        self.stream = None
        self.pairs = []                 # Verlauf dieser Sitzung (Frage/Antwort)
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.stack = Adw.ViewStack()
        sw = Adw.ViewSwitcher(stack=self.stack, policy=Adw.ViewSwitcherPolicy.WIDE)
        hb.set_title_widget(sw)
        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 520sp"))   # schmal: Reiter kompakt
        bp.add_setter(sw, "policy", Adw.ViewSwitcherPolicy.NARROW)
        self.add_breakpoint(bp)
        self.hist_btn = Gtk.Button(icon_name="document-open-recent-symbolic", tooltip_text=_("History"), visible=False)
        self.hist_btn.connect("clicked", lambda *_: HistoryDialog(self).present(self))
        hb.pack_start(self.hist_btn)
        tv.add_top_bar(hb)
        self.toasts = Adw.ToastOverlay()
        self.stack.add_titled_with_icon(self._ask_page(), "ask", _("Ask your pages"), "system-search-symbolic")
        if view is not None:
            self.stack.add_titled_with_icon(self._page_tab(), "page", _("This page"), assistant_icon())
        self.toasts.set_child(self.stack)
        tv.set_content(self.toasts)
        foot = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_start=14, margin_end=14, margin_top=6, margin_bottom=8)
        self.notice = _lbl("", ("caption", "dim-label"))
        self.quota = _lbl("", ("caption", "dim-label"))
        foot.append(self.notice)
        foot.append(self.quota)
        tv.add_bottom_bar(foot)
        self.set_child(tv)
        self.connect("closed", lambda *_: self.stream and self.stream.cancel())
        self.stack.set_visible_child_name(tab if (tab != "page" or view is not None) else "ask")
        self._status()
        ai.refresh(self._status)                     # Admin kann umschalten: beim Oeffnen neu holen
        if action and view is not None:
            GLib.idle_add(lambda: (self.run_action(action), False)[1])

    def toast(self, text):
        self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(text)))

    def _status(self):
        st = ai.status
        self.notice.set_label(st.get("notice") or "")
        self._quota(st.get("quota_left"))
        self.hist_btn.set_visible(bool(st.get("history_allowed")))
        if hasattr(self, "target"):
            self._targets()

    def _quota(self, left):
        q = ai.status.get("quota")
        if left is not None and q:
            self.quota.set_label(_("{n} of {m} requests left today").format(n=left, m=q))
            self.quota.set_visible(True)
        else:
            self.quota.set_visible(False)

    # --- Frag deine Seiten -------------------------------------------------------------------------------------------
    def _ask_page(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.ask_scroll = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.ask_list = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16, margin_start=14, margin_end=14, margin_top=12,
                                margin_bottom=12)
        self.empty = Adw.StatusPage(icon_name=assistant_icon(), title=_("Ask your pages"),
                                    description=_("Ask something – the assistant searches the pages you're allowed to see and names its "
                                                  "sources.") + "\n\n" +
                                    _("It can't change anything: you apply suggestions yourself. It never reads chats; tasks and calendar "
                                      "only when you turn them on."))
        self.empty.add_css_class("compact")
        self.ask_list.append(self.empty)
        self.ask_scroll.set_child(self.ask_list)
        box.append(self.ask_scroll)
        comp = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_start=12, margin_end=12, margin_top=6, margin_bottom=6)
        sw = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True, max_content_height=120)
        sw.add_css_class("hk-composer")
        self.q = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, top_margin=8, bottom_margin=8, left_margin=10, right_margin=10,
                              accepts_tab=False)
        self.q.update_property([Gtk.AccessibleProperty.LABEL], [_("Question to your pages")])
        ph = _lbl(_("Ask {name} something about your pages …").format(name=ai_name()) if ai.persona() else
                  _("Ask something about your pages …"), ("dim-label",), can_target=False, margin_start=11, valign=Gtk.Align.CENTER)
        ov = Gtk.Overlay(child=self.q)
        ov.add_overlay(ph)
        self.q.get_buffer().connect("changed", lambda b: ph.set_visible(b.get_char_count() == 0))
        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", lambda c, kv, code, s: kv in (Gdk.KEY_Return, Gdk.KEY_KP_Enter) and not s & Gdk.ModifierType.SHIFT_MASK
                   and (self.ask(), True)[1])
        self.q.add_controller(kc)
        sw.set_child(ov)
        comp.append(sw)
        row = Gtk.Box(spacing=12)
        self.inc_tasks = Gtk.CheckButton(label=_("Include tasks"))
        self.inc_cal = Gtk.CheckButton(label=_("Include calendar"))
        checks = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=2, column_spacing=12, row_spacing=2,
                             hexpand=True, homogeneous=False)       # schmal: untereinander
        checks.append(self.inc_tasks)
        checks.append(self.inc_cal)
        row.append(checks)
        self.ask_btn = Gtk.Button(label=_("Ask"), valign=Gtk.Align.CENTER)
        self.ask_btn.add_css_class("suggested-action")
        self.ask_btn.connect("clicked", lambda *_: self.stop() if self.stream else self.ask())
        row.append(self.ask_btn)
        comp.append(row)
        box.append(comp)
        GLib.idle_add(lambda: (self.q.grab_focus(), False)[1])
        return box

    def ask(self):
        if self.stream is not None:
            return
        buf = self.q.get_buffer()
        text = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False).strip()
        if not text:
            return
        text = text[:int(ai.status.get("q_max") or 1000)]
        buf.set_text("")
        if self.empty.get_parent() is not None:
            self.ask_list.remove(self.empty)
        pair = AnswerBox(self, text)
        self.ask_list.append(pair)
        body = {"q": text}
        if self.inc_tasks.get_active():
            body["tasks"] = True
        if self.inc_cal.get_active():
            body["calendar"] = True
        self._start("api/ai/ask", body, pair)

    def _start(self, path, body, box):
        self.ask_btn.set_label(_("Stop"))
        self.ask_btn.remove_css_class("suggested-action")
        self.ask_btn.add_css_class("destructive-action")

        def done(d):
            self._end()
            box.done(d)
            if d.get("quota_left") is not None:
                ai.status["quota_left"] = d.get("quota_left")
                self._quota(d.get("quota_left"))

        def error(msg, partial):
            self._end()
            box.error(msg, partial)
        self.stream = Stream(path, body, box.sources, box.delta, done, error, box.reset)
        box.started()

    def _end(self):
        self.stream = None
        self.ask_btn.set_label(_("Ask"))
        self.ask_btn.remove_css_class("destructive-action")
        self.ask_btn.add_css_class("suggested-action")
        for b in getattr(self, "page_btns", []):
            b.set_sensitive(True)

    def stop(self):
        s = self.stream
        if s is not None:
            s.cancel()
            self._end()

    def scroll_down(self, sw=None):
        sw = sw or self.ask_scroll

        def go():
            a = sw.get_vadjustment()
            a.set_value(a.get_upper() - a.get_page_size())
            return False
        GLib.idle_add(go)

    def open_source(self, sid):
        if sid is None:
            return
        self.close()
        self.win.show_page(int(sid))

    # --- Diese Seite ---------------------------------------------------------------------------------------------------
    def _page_tab(self):
        v = self.view
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_start=14, margin_end=14, margin_top=12, margin_bottom=12)
        self.page_scroll = sw
        if v.id < 0:                                           # Seite einer anderen Saga-Instanz
            inner.append(_lbl(_("The assistant does not work on pages from other Saga instances."), ("dim-label",)))
            self.page_btns = []
            sw.set_child(inner)
            box.append(sw)
            return box
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lb.add_css_class("boxed-list")
        self.page_btns = []
        for action, title in (("summarize", _("Summarize")), ("tasks", _("Suggest tasks")), ("improve", _("Improve selected text"))):
            r = Adw.ActionRow(title=title, activatable=True)
            r.connect("activated", lambda *_, a=action: self.run_action(a))
            r.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
            lb.append(r)
            self.page_btns.append(r)
        self.target = Adw.ComboRow(title=_("Translate to"))
        lb.append(self.target)
        tr = Adw.ActionRow(title=_("Translate"), activatable=True)
        tr.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
        tr.connect("activated", lambda *_: self.run_action("translate"))
        lb.append(tr)
        self.page_btns.append(tr)
        asg = Adw.ActionRow(title=_("Assign in database"), activatable=True,
                            subtitle=_("Suggests a value per entry for a property – you decide what is applied."))
        asg.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
        asg.connect("activated", lambda *_: self.assign_form())
        lb.append(asg)
        self.page_btns.append(asg)
        self._targets()
        inner.append(lb)
        self.sel_label = _lbl("", ("caption", "dim-label"))
        inner.append(self.sel_label)
        self.update_selection()
        self.result = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        inner.append(self.result)
        sw.set_child(inner)
        box.append(sw)
        return box

    def _targets(self):
        langs = [t for t in ai.status.get("targets") or [] if isinstance(t, str)] or ["en", "de", "es"]
        self.target_codes = langs
        names = []
        for t in langs:
            names.append(_lang_name(t))
        self.target.set_model(Gtk.StringList.new(names))
        from .i18n import ui_language
        want = "en" if ui_language() != "en" else "de"
        if want in langs:
            self.target.set_selected(langs.index(want))

    def selected_blocks(self):
        v = self.view
        ids = [b for b in v.editor_order() if b in v.ai_marked] if v.ai_marked else []
        if not ids and v.editor.focused_block:
            ids = [v.top_block(v.editor.focused_block)]
        return [i for i in ids if i][:50]

    def update_selection(self):
        if not hasattr(self, "sel_label"):
            return
        n = len(self.selected_blocks())
        self.sel_label.set_label(ngettext("Selected: {n} block", "Selected: {n} blocks", n).format(n=n) if n else
                                 _("For “Improve” and “Translate”, mark blocks in the block menu (⋮⋮ → “Mark for the assistant”) or "
                                   "place the cursor in a block first."))

    # 2.5 Datenbank zuordnen: Datenbank + Eigenschaft waehlen, Vorschlaege holen, abhaken und uebernehmen (PATCH)
    TYPE_NAMES = {"select": "Select", "multi_select": "Multi-select", "checkbox": "Checkbox", "text": "Text", "number": "Number",
                  "date": "Date", "url": "URL"}

    def assign_form(self):
        v = self.view
        if v is None or v.id < 0 or self.stream is not None:
            return
        while (c := self.result.get_first_child()):
            self.result.remove(c)
        sp = Gtk.Spinner(spinning=True, halign=Gtk.Align.START)
        self.result.append(sp)

        def fill(j):
            self.result.remove(sp)
            dbs = [d for d in (j or {}).get("dbs") or [] if d.get("props")]
            if not dbs:
                self.result.append(_lbl(_("There is no database with a suitable property (select, checkbox, text, number, date, URL) on this page."),
                                        ("dim-label",)))
                return
            lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
            lb.add_css_class("boxed-list")
            dbr = Adw.ComboRow(title=_("Database"), model=Gtk.StringList.new([f"{d.get('title')} ({d.get('rows')})" for d in dbs]))
            pr = Adw.ComboRow(title=_("Property"))
            lb.append(dbr)
            lb.append(pr)
            only = Adw.SwitchRow(title=_("Only entries without a value"), active=True)
            lb.append(only)
            hint = Adw.EntryRow(title=_("Hint (optional, e.g. “by urgency”)"))
            lb.append(hint)
            state = {"props": []}

            def props_of(*_a):
                d = dbs[dbr.get_selected()]
                state["props"] = d.get("props") or []
                pr.set_model(Gtk.StringList.new([f"{p.get('name')} · {_(self.TYPE_NAMES.get(p.get('type'), p.get('type') or ''))}" for p in state["props"]]))
                i = next((k for k, p in enumerate(state["props"]) if p.get("type") == "select"), 0)
                pr.set_selected(i)
                go.set_sensitive(bool(d.get("editable")))
                ro.set_visible(not d.get("editable"))
            go = Gtk.Button(label=_("Get suggestions"), halign=Gtk.Align.START)
            go.add_css_class("suggested-action")
            ro = _lbl(_("You can only read this database."), ("caption", "dim-label"))
            dbr.connect("notify::selected", props_of)
            self.result.append(_lbl(_("{name} suggests a value for each entry – you decide what is applied.").format(name=ai_name() if ai.persona() else _("The assistant")),
                                    ("dim-label",)))
            self.result.append(lb)
            self.result.append(ro)
            self.result.append(go)
            props_of()

            def run(*_a):
                p = state["props"][pr.get_selected()] if state["props"] else None
                if not p:
                    return
                body = {"action": "assign", "db": dbs[dbr.get_selected()]["id"], "prop": p["id"], "only_empty": only.get_active()}
                if hint.get_text().strip():
                    body["hint"] = hint.get_text().strip()[:300]
                while (c := self.result.get_first_child()):
                    self.result.remove(c)
                for b in self.page_btns:
                    b.set_sensitive(False)
                box = AnswerBox(self, _("Assign in database"), page_action="assign")
                self.result.append(box)
                self._start(f"api/ai/page/{v.id}", body, box)
            go.connect("clicked", run)

        def err(e):
            self.result.remove(sp)
            self.result.append(_lbl(str(e), ("error",)))
        v.editor.flush(lambda: bg(client.get, f"api/ai/page/{v.id}/dbs", done=fill, error=err))

    def run_action(self, action):
        v = self.view
        if v is None or v.id < 0 or self.stream is not None:
            return
        self.stack.set_visible_child_name("page")
        self.update_selection()                              # Hinweis "zuerst Bloecke markieren" nicht stehen lassen (BUG-117)
        body = {"action": action}
        if action in ("improve", "translate"):
            blocks = self.selected_blocks()
            if not blocks:
                self.toast(_("For “Improve” and “Translate”, select text on the page first."))
                return
            body["blocks"] = blocks
            if action == "translate":
                body["target"] = self.target_codes[self.target.get_selected()]
        for b in self.page_btns:
            b.set_sensitive(False)
        while (c := self.result.get_first_child()):
            self.result.remove(c)
        title = {"summarize": _("Summarize"), "tasks": _("Suggest tasks"), "improve": _("Improve selected text"),
                 "translate": _("Translate to")}.get(action, "")
        if action == "translate":                            # mit Zielsprache (BUG-117)
            title += " " + _lang_name(body["target"])
        box = AnswerBox(self, title, page_action=action)
        self.result.append(box)

        def go():
            self._start(f"api/ai/page/{v.id}", body, box)
        v.editor.flush(go)                                     # der Server liest den gespeicherten Stand


def _lang_name(code):
    return {"de": _("German"), "en": _("English"), "es": _("Spanish"), "fr": _("French"), "it": _("Italian"), "nl": _("Dutch"),
            "pt": _("Portuguese"), "pl": _("Polish"), "sv": _("Swedish"), "da": _("Danish"), "no": _("Norwegian"), "fi": _("Finnish"),
            "cs": _("Czech"), "tr": _("Turkish"), "uk": _("Ukrainian"), "ru": _("Russian"), "ja": _("Japanese"), "zh": _("Chinese"),
            "ko": _("Korean")}.get(code, code)


class AnswerBox(Gtk.Box):
    """Frage + Antwort (live), Quellen-Chips, Angebote je Aktion"""
    __gtype_name__ = "HKAnswerBox"

    def __init__(self, dlg, question, page_action=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.dlg, self.action = dlg, page_action
        self.text = ""
        self.srcs = []
        q = _lbl(question, ("heading",) if page_action else ("hk-ai-q",))
        if not page_action:
            q.set_halign(Gtk.Align.END)
            q.set_xalign(1)
        self.append(q)
        if ai.persona():                                     # Server ab 2.1: Antwort mit Profilbild und Namen der KI
            self.append(ai_head(20))
        self.answer = Gtk.Label(xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, selectable=True)
        self.answer.add_css_class("hk-ai-a")
        self.answer.connect("activate-link", self._link)
        self.append(self.answer)
        self.extra = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.append(self.extra)

    def started(self):
        self.answer.set_label(thinking_text())
        self.answer.add_css_class("dim-label")

    def sources(self, lst):
        self.srcs = lst

    def reset(self):
        self.text = ""
        self.started()

    def delta(self, t):
        if self.action == "assign":                          # Rohantwort ("3: Offen") nicht zeigen
            self.text += t
            return
        if not self.text:
            self.answer.remove_css_class("dim-label")
        self.text += t
        self.answer.set_markup(cite_markup(self.text, self.srcs))
        self._scroll()

    def _scroll(self):
        self.dlg.scroll_down(self.dlg.page_scroll if self.action else None)

    def _link(self, label, uri):
        if uri.startswith("src:"):
            n = int(uri[4:])
            s = next((x for x in self.srcs if str(x.get("n")) == str(n)), None)
            if s:
                self.dlg.open_source(s.get("id"))
        return True

    def error(self, msg, partial):
        if not partial or not self.text:
            self.answer.set_label(msg)
            self.answer.remove_css_class("dim-label")
            self.answer.add_css_class("error")
        else:
            self.extra.append(_lbl(msg, ("error", "caption")))
            self._chips()

    def done(self, d):
        self.answer.remove_css_class("dim-label")
        if not self.text and not d.get("tasks"):
            self.answer.set_label("")
        if d.get("truncated"):
            self.extra.append(_lbl(_("The page is long – only the beginning was used."), ("caption", "dim-label")))
        if self.action is None:
            self._chips()
        elif self.action == "assign":
            self.answer.set_visible(False)
            self._assign(d)
        elif self.action == "tasks":
            self._tasks(d.get("tasks") or [])
        elif self.action in ("improve", "translate"):
            self._segments(d)
        elif self.text:
            self.extra.append(self._copy_btn(self.text))
        self._scroll()

    def _chips(self):
        if self.action is not None:
            return
        fb = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=3, row_spacing=4, column_spacing=4)
        if not self.srcs:
            self.extra.append(_lbl(_("No matching pages found."), ("caption", "dim-label")))
            return
        self.extra.append(_lbl(_("Sources:"), ("caption-heading",)))
        for s in self.srcs:
            t = f"{s.get('n')} · {s.get('title') or _('Untitled')}" + (f" · {s['owner']}" if s.get("owner") else "")
            b = Gtk.Button(child=Gtk.Label(label=t, ellipsize=Pango.EllipsizeMode.END, max_width_chars=34))
            b.add_css_class("hk-chip")
            b.set_sensitive(s.get("id") is not None)
            b.connect("clicked", lambda *_, s=s: self.dlg.open_source(s.get("id")))
            fb.append(b)
        self.extra.append(fb)

    def _assign(self, d):
        a = d.get("assign") or {}
        items, prop = a.get("items") or [], a.get("prop") or {}
        if not items:
            self.extra.append(_lbl(d.get("message") or _("No suggestions."), ("dim-label",)))
            return
        self.extra.append(_lbl(_("“{name}” for {n} entries:").format(name=prop.get("name") or "?", n=len(items)), ("heading",)))
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lb.add_css_class("boxed-list")
        checks = []
        for it in items:
            sub = ((it.get("old") or "") + " → " if it.get("old") else "") + (it.get("label") or "")
            r = Adw.ActionRow(title=GLib.markup_escape_text(it.get("title") or _("Untitled")), subtitle=GLib.markup_escape_text(sub))
            cb = Gtk.CheckButton(active=True, valign=Gtk.Align.CENTER)
            r.add_prefix(cb)
            r.set_activatable_widget(cb)
            lb.append(r)
            checks.append((cb, it))
        self.extra.append(lb)
        if a.get("unsure"):
            n = a["unsure"]
            self.extra.append(_lbl(ngettext("Unsure about {n} entry.", "Unsure about {n} entries.", n).format(n=n), ("caption", "dim-label")))
        if d.get("more"):
            self.extra.append(_lbl(_("{n} more entries – run again after applying.").format(n=d["more"]), ("caption", "dim-label")))
        bar = _bar()
        ok = Gtk.Button(label=_("Apply"))
        ok.add_css_class("suggested-action")

        def apply(*_a):
            chosen = [it for cb, it in checks if cb.get_active()]
            if not chosen:
                return
            ok.set_sensitive(False)
            for cb, _it in checks:
                cb.set_sensitive(False)

            def work():
                for it in chosen:
                    client.json("PATCH", f"api/notes/{it['id']}", {"props": {prop["id"]: it.get("value")}})
                return len(chosen)

            def fin(n):
                from .store import store
                store.changed_data()
                self.dlg.toast(ngettext("{n} entry changed.", "{n} entries changed.", n).format(n=n))

            def err(e):
                ok.set_sensitive(True)
                self.dlg.toast(_("Not imported: {err}").format(err=e))
            bg(work, done=fin, error=err)
        ok.connect("clicked", apply)
        bar.append(ok)
        again = Gtk.Button(label=_("Other property …"))
        again.connect("clicked", lambda *_: self.dlg.assign_form())
        bar.append(again)
        self.extra.append(bar)

    def _copy_btn(self, text):
        b = Gtk.Button(label=_("Copy"), halign=Gtk.Align.START)
        b.connect("clicked", lambda *_a: (self.dlg.win.copy_text(text), self.dlg.toast(_("Copied."))))
        return b

    def _tasks(self, tasks):
        tasks = [str(t) for t in tasks if str(t).strip()][:20]
        if not tasks:
            self.extra.append(_lbl(_("No tasks found."), ("dim-label",)))
            return
        if not self.text:
            self.answer.set_visible(False)
        lb = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        lb.add_css_class("boxed-list")
        checks = []
        for t in tasks:
            r = Adw.ActionRow(title=GLib.markup_escape_text(t))
            cb = Gtk.CheckButton(active=True, valign=Gtk.Align.CENTER)
            r.add_prefix(cb)
            r.set_activatable_widget(cb)
            lb.append(r)
            checks.append((cb, t))
        self.extra.append(lb)
        bar = _bar()
        add = Gtk.Button(label=_("Add to tasks"))
        add.add_css_class("suggested-action")
        add.connect("clicked", lambda *_: self._add_tasks([t for cb, t in checks if cb.get_active()], add))
        bar.append(add)
        v = self.dlg.view
        if v is not None and not v.editor.locked:
            ins = Gtk.Button(label=_("Insert as checklist"))
            ins.connect("clicked", lambda *_: self._checklist([t for cb, t in checks if cb.get_active()]))
            bar.append(ins)
        self.extra.append(bar)

    def _add_tasks(self, items, btn):
        if not items:
            return
        btn.set_sensitive(False)

        def work():
            n = 0
            for t in items:
                client.json("POST", "api/tasks", {"text": t[:500]})
                n += 1
            return n

        def fin(n):
            from .store import store
            store.load_today()
            self.dlg.toast(ngettext("{n} task added.", "{n} tasks added.", n).format(n=n))

        def err(e):
            btn.set_sensitive(True)
            self.dlg.toast(_("Not imported: {err}").format(err=e))
        bg(work, done=fin, error=err)

    def _checklist(self, items):
        v = self.dlg.view
        if not items or v is None or v.editor.locked:
            return
        blk = {"id": new_block_id(), "type": "list", "data": {"style": "checklist", "meta": {}, "items": [
            {"content": esc(t), "meta": {"checked": False}, "items": []} for t in items]}}
        last = v.doc.root[-1]["id"] if v.doc.root else None
        v.editor.insert(blk, after=last)
        self.dlg.toast(_("Inserted."))

    def _segments(self, d):
        v = self.dlg.view
        if self.text:
            self.extra.append(self._copy_btn(self.text))
        if v is None or v.editor.locked:
            return
        segs, blocks = d.get("segments"), d.get("blocks") or []
        bar = _bar()
        if segs and len(segs) == len(blocks) and all(b.get("replaceable") for b in blocks):
            n = len(blocks)
            rp = Gtk.Button(label=_("Replace") if n == 1 else ngettext("Replace {n} block", "Replace {n} blocks", n).format(n=n))
            rp.add_css_class("suggested-action")
            rp.connect("clicked", lambda *_: self._ask_replace(blocks, segs))
            bar.append(rp)
        ins = Gtk.Button(label=_("Insert below"))
        ins.connect("clicked", lambda *_: self._insert_below(blocks))
        bar.append(ins)
        self.extra.append(bar)

    def _ask_replace(self, blocks, segs):
        from .publish import confirm
        confirm(self.dlg, _("Replace selected text?"),
                _("The text of the selected blocks will be replaced by the suggestion (formatting is lost). You'll find earlier versions "
                  "in the version history."), _("Replace"), lambda: self._replace(blocks, segs), destructive=False)

    def _replace(self, blocks, segs):
        v = self.dlg.view
        n = 0
        for b, s in zip(blocks, segs):
            blk = v.editor.block(b.get("id"))
            if blk is None or blk.get("type") not in ("paragraph", "header", "quote"):
                continue
            blk.setdefault("data", {})["text"] = esc(s).replace("\n", "<br>")
            n += 1
        if n:
            v.ai_marked.clear()
            v.editor.changed()
            v.rebuild_blocks()
        self.dlg.toast(_("Replaced.") if n == len(blocks) else _("Only partly replaced – page changed in the meantime?") if n else
                       _("Not replaced."))

    def _insert_below(self, blocks):
        v = self.dlg.view
        last = blocks[-1].get("id") if blocks else (v.doc.root[-1]["id"] if v.doc.root else None)
        if v.editor.block(last) is None:
            last = v.doc.root[-1]["id"] if v.doc.root else None
        t = re.sub(r"\n-{3,}\n", "\n\n", self.text)
        v.editor.insert({"id": new_block_id(), "type": "paragraph", "data": {"text": esc(t).replace("\n", "<br>")}}, after=v.top_block(last))
        self.dlg.toast(_("Inserted."))


class HistoryDialog(Adw.Dialog):
    """Verlauf des Assistenten (nur wenn der Admin ihn erlaubt): Schalter "Verlauf speichern", Eintraege, loeschen"""

    def __init__(self, parent):
        super().__init__(title=_("Assistant history"), content_width=520, content_height=620)
        self.parent = parent
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        self.page = Adw.PreferencesPage()
        tv.set_content(self.page)
        self.toasts = Adw.ToastOverlay(child=tv)
        self.set_child(self.toasts)
        self.groups = []
        self.load()

    def load(self):
        bg(client.get, "api/ai/history", done=self.fill, error=lambda e: self.fill({"_err": str(e)}))

    def fill(self, j):
        for g in self.groups:
            self.page.remove(g)
        self.groups = []
        j = j or {}
        g = Adw.PreferencesGroup()
        on = bool(j.get("history"))
        sw = Adw.SwitchRow(title=_("Save history"), active=on,
                           subtitle=_("Questions, answers and source pages are saved. Sources you no longer have access to don't show a "
                                      "title.") if on else _("Off: questions and answers aren't saved."))
        sw.connect("notify::active", lambda r, _p: self._toggle(r))
        g.add(sw)
        self.page.add(g)
        self.groups.append(g)
        if j.get("_err"):
            g.set_description(GLib.markup_escape_text(_("History not loaded: {err}").format(err=j["_err"])))
        items = [x for x in j.get("items") or [] if isinstance(x, dict)]
        g2 = Adw.PreferencesGroup(title=_("History"))
        if items:
            da = Gtk.Button(label=_("Delete all"), css_classes=["flat", "destructive-action"])
            da.connect("clicked", lambda *_: self._delete_all())
            g2.set_header_suffix(da)
        elif on:
            g2.set_description(_("Nothing saved yet."))
        for it in items:
            ex = Adw.ExpanderRow(title=GLib.markup_escape_text(it.get("q") or ""), subtitle=rel_time(it.get("created")))
            a = _lbl(it.get("answer") or "", margin_start=12, margin_end=12, margin_top=8, margin_bottom=8, selectable=True)
            ex.add_row(Gtk.ListBoxRow(child=a, activatable=False))
            for s in it.get("sources") or []:
                r = Adw.ActionRow(title=GLib.markup_escape_text(f"{s.get('n')} · " + (s.get("title") or "")), activatable=s.get("id") is not None)
                if s.get("id") is not None:
                    r.connect("activated", lambda *_, s=s: (self.close(), self.parent.open_source(s["id"])))
                ex.add_row(r)
            d = Gtk.Button(icon_name="user-trash-symbolic", tooltip_text=_("Delete entry"), valign=Gtk.Align.CENTER)
            d.add_css_class("flat")
            d.connect("clicked", lambda *_, it=it: bg(client.request, "DELETE", f"api/ai/history/{it['id']}", done=lambda _r: self.load(),
                                                      error=lambda e: self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e))))))
            ex.add_suffix(d)
            g2.add(ex)
        if items or on:
            self.page.add(g2)
            self.groups.append(g2)

    def _toggle(self, row):
        on = row.get_active()
        if on:
            bg(client.json, "PUT", "api/ai/settings", {"history": True}, done=lambda _r: self.load(),
               error=lambda e: (self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(str(e)))), self.load()))
            return
        from .publish import confirm
        confirm(self, _("Turn off history?"), _("The saved history will be deleted."), _("Turn off"),
                lambda: bg(client.json, "PUT", "api/ai/settings", {"history": False}, done=lambda _r: self.load(),
                           error=lambda e: self.load()),
                cancelled=lambda: row.set_active(True))

    def _delete_all(self):
        from .publish import confirm
        confirm(self, _("Delete history?"), _("All saved questions and answers will be deleted."), _("Delete"),
                lambda: bg(client.request, "DELETE", "api/ai/history", done=lambda _r: self.load(), error=lambda e: self.load()))
