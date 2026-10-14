"""Kommentare (Server 1.3): Logik (Zustand -> Anzeige, erlaubte Aktionen, Texte), Seitenleiste (Schreiben, Antworten,
Bearbeiten, Loeschen, Erledigt/Wieder oeffnen, Erledigte zeigen, gesehen), Zahl am Block, Rolle Lesen, Fehler (429)."""
import json

from gi.repository import Gtk

from hk_helpers import idle, pump


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


def _labels(w):
    return [x.get_label() for x in _walk(w) if isinstance(x, Gtk.Label)]


def _buttons(w, label):
    return [b for b in _walk(w) if isinstance(b, Gtk.Button) and (b.get_label() == label or any(
        isinstance(x, Gtk.Label) and x.get_label() == label for x in _walk(b)))]


def _open(win, nid):
    win.show_root(("page", nid))
    v = win.current
    pump(lambda: v.doc.fetched, 6)
    pump(None, 0.4)
    idle()
    return v


def _panel(win, v, block=None):
    v.show_comments(block)
    p = v.cpanel
    pump(lambda: p.s.loaded, 5)
    pump(None, 0.3)
    idle()
    return p


def _thread(t_id=1, block="p1", comments=None, unread=False, resolved=None, by=None):
    return {"id": t_id, "block_id": block, "block_exists": True, "resolved_at": resolved, "resolved_by": by, "unread": unread,
            "comments": comments or []}


def _cm(cid, name="Anna", me=False, body="Hallo", username=None, deleted=False):
    return {"id": cid, "author": {"name": name, "username": username or ("test" if me else name.lower()), "me": me},
            "body": "" if deleted else body, "created": 1.0, "edited": None, "mine": me, "deleted": deleted}


# --- Logik ohne GTK ------------------------------------------------------------------------------------------
def test_state_texts_and_counts():
    from hlidskjalf.comments import CommentState, block_counts, count_tooltip, resolved_text
    s = CommentState()
    s.apply({"role": "owner", "threads": [], "resolved_count": 0})
    assert s.empty_text().startswith("No comments yet. Comment on a paragraph with 💬")
    assert s.placeholder() == "Comment on the whole page" and not s.show_switch()
    s.block = "p1"
    assert s.empty_text() == "No comments on this block yet." and s.placeholder() == "Comment on this block"
    s.apply({"role": "view", "threads": [], "resolved_count": 2})
    assert not s.can and s.empty_text() == "No comments on this block."
    s.block = None
    assert s.empty_text() == "No open comments · 2 resolved" and s.show_switch()
    s.resolved = True
    assert s.empty_text() == "No comments yet."
    th = [_thread(1, "p1", unread=True), _thread(2, "p1"), _thread(3, "h1", resolved=5.0), _thread(4, "")]
    per, unread = block_counts(th)
    assert per == {"p1": 2} and unread == {"p1"}
    assert count_tooltip(3, 1) == "3 open threads · 1 unread" and count_tooltip(1, 0) == "1 open thread"
    assert resolved_text(_thread(by={"name": "Bert", "me": False})) == "✓ Resolved by Bert"
    assert resolved_text(_thread(by={"name": "x", "me": True})) == "✓ Resolved by you"
    assert resolved_text(_thread()) == "✓ Resolved"


def test_actions_and_delete_texts():
    from hlidskjalf.comments import comment_actions, delete_texts, unread_index
    mine, other = _cm(1, "Test", me=True), _cm(2, "Anna")
    t = _thread(comments=[mine, other], unread=True)
    assert comment_actions(t, mine, True, "comment") == ["edit", "delete"]
    assert comment_actions(t, other, False, "comment") == []
    assert comment_actions(t, other, False, "owner") == ["delete"]                     # Eigentuemer moderiert
    assert comment_actions(t, mine, True, "owner") == ["edit", "delete", "delete_all"]
    assert comment_actions(t, mine, True, "view") == []                                 # Lesen: nichts aendern
    assert delete_texts(t, mine, True) == ("Delete comment?", "The comment will be deleted; the replies remain.")
    alone = _thread(comments=[mine])
    assert delete_texts(alone, mine, True) == ("Delete thread?", "The first comment and all replies will be deleted.")
    assert delete_texts(t, other, False) == ("Delete comment?", "The comment will be deleted.")
    assert delete_texts(t, mine, True, True)[1].endswith("including those from others.")
    assert unread_index(t) == 1 and unread_index(_thread(comments=[mine])) == -1


def test_linkify_markup():
    from hlidskjalf.comments import linkify_markup
    assert linkify_markup("Siehe https://example.org/holz. <b>") == \
        'Siehe <a href="https://example.org/holz">https://example.org/holz</a>. &lt;b&gt;'
    assert linkify_markup("javascript:alert(1) & mehr") == "javascript:alert(1) &amp; mehr"


# --- Seitenleiste ---------------------------------------------------------------------------------------------
def test_write_reply_resolve_and_show_resolved(win, srv):
    v = _open(win, 1)
    assert not win.cm_btn.get_visible()                                       # keine offenen Unterhaltungen
    p = _panel(win, v)
    assert v.comments_open and win.cm_btn.get_visible()
    assert "No comments yet. Comment on a paragraph with 💬 in the right margin, or write a comment on the whole page below." in _labels(p)
    p.composer.buf.set_text("Reicht das Budget? https://example.org/holz")
    p.composer.send()
    assert pump(lambda: len(srv.comments) == 1, 5)
    idle()
    pump(lambda: p.s.threads, 3)
    assert srv.comments[0]["block_id"] == "" and srv.comments[0]["body"].startswith("Reicht das Budget?")
    assert "Full page" in _labels(p) and "You" in _labels(p)
    assert win.cm_content.get_label() == "1" and win.cm_btn.get_tooltip_text() == "1 open thread"
    # Antworten
    _buttons(p, "Reply")[0].emit("clicked")
    reply = [w for w in _walk(p) if isinstance(w, Gtk.TextView)]
    assert len(reply) == 2                                                     # Antwortfeld + Fussfeld
    p_reply = next(c for c in _walk(p.list) if c.__class__.__name__ == "Composer")
    p_reply.buf.set_text("Ja, mit Lärche")
    p_reply.send()
    assert pump(lambda: len(srv.comments) == 2, 5)
    idle()
    assert srv.comments[1]["thread_id"] == srv.comments[0]["id"]
    # Erledigt: Unterhaltung verschwindet, Schalter "Erledigte zeigen" erscheint
    _buttons(p, "✓ Resolve")[0].emit("clicked")
    assert pump(lambda: srv.comments[0]["resolved_at"], 5)
    pump(lambda: not p.s.open(), 3)
    idle()
    assert "No open comments · 1 resolved" in _labels(p) and "Show resolved" in _labels(p)
    assert not win.cm_btn.get_visible() or win.cm_content.get_label() == ""
    sw = next(w for w in _walk(p.list) if isinstance(w, Gtk.Switch))
    sw.set_active(True)
    pump(lambda: p.s.resolved and p.s.threads, 5)
    idle()
    labels = _labels(p)
    assert "✓ Resolved by you" in labels and "2 comments" in labels             # zugeklappt
    v.hide_comments()
    assert not v.comments_open


def test_block_bubble_and_block_view(win, srv):
    srv.add_comment(1, "Zum Absatz", block_id="p1")
    v = _open(win, 1)
    assert pump(lambda: v.cm_counts.get("p1") == 1, 5)
    row = next(r for r in v.kit.rows() if r.bid == "p1")
    bub = next(w for w in _walk(row.cslot) if isinstance(w, Gtk.Button))
    assert bub.has_css_class("hk-cm-bubble") and bub.has_css_class("hk-unread") and bub.get_tooltip_text() == "1 thread on this block"
    assert win.cm_btn.get_visible() and win.cm_btn.has_css_class("hk-unread")
    other = next(r for r in v.kit.rows() if r.bid == "p2")
    add = next(w for w in _walk(other.cslot) if isinstance(w, Gtk.Button))
    assert add.has_css_class("hk-cm-add") and add.get_tooltip_text() == "Comment"
    bub.emit("clicked")
    p = v.cpanel
    pump(lambda: p.s.loaded and p.s.block == "p1", 5)
    pump(None, 0.3)
    idle()
    assert p.ctx.get_visible() and row.has_css_class("hk-cm-focus")
    assert any("Zum Absatz" in (x or "") for x in _labels(p))
    assert p.composer is not None and "Comment on this block" in _labels(p.composer)
    # gesehen gemeldet: Punkt weg
    assert pump(lambda: not v.cm_unread, 5)
    assert not bub.get_parent() or not any(w.has_css_class("hk-unread") for w in _walk(row.cslot) if isinstance(w, Gtk.Button))
    _buttons(p, "← All threads")[0].emit("clicked")
    assert p.s.block is None and not row.has_css_class("hk-cm-focus")


def test_edit_and_delete_own_comment(win, srv, monkeypatch):
    from hlidskjalf import publish
    asked = []
    monkeypatch.setattr(publish, "confirm", lambda parent, h, b, label, ok, cancelled=None, destructive=True:
                        (asked.append((h, b, label)), ok()))
    tid = srv.add_comment(1, "Meins", uid="test", block_id="p1")
    srv.add_comment(1, "Antwort von Anna", uid="anna", thread_id=tid)
    v = _open(win, 1)
    p = _panel(win, v)
    p._edit(tid)
    ed = next(c for c in _walk(p.list) if c.__class__.__name__ == "Composer")
    assert ed.text() == "Meins"
    ed.buf.set_text("Meins, geändert")
    ed.send()
    assert pump(lambda: srv.comments[0]["body"] == "Meins, geändert" and srv.comments[0]["edited"], 5)
    idle()
    assert "edited" in _labels(p)
    t = p.s.threads[0]
    p._ask_delete(t, t["comments"][0], True, False)
    assert asked[-1] == ("Delete comment?", "The comment will be deleted; the replies remain.", "Delete")
    assert pump(lambda: srv.comments[0]["body"] == "", 5)
    pump(None, 0.3)
    idle()
    assert "Comment deleted" in _labels(p) and "Antwort von Anna" in " ".join(_labels(p))


def test_view_role_reads_but_cannot_write(win, srv):
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt", role="view")
    srv.add_comment(200, "Von Anna", block_id="f200")
    store.refresh()
    idle()
    v = _open(win, 200)
    p = _panel(win, v)
    assert p.composer is None and "You can read comments but not write them." in _labels(p)
    assert not _buttons(p, "Reply") and not _buttons(p, "✓ Resolve")
    row = next(r for r in v.kit.rows() if r.bid == "f200")
    assert any(isinstance(w, Gtk.Button) and w.has_css_class("hk-cm-bubble") for w in _walk(row.cslot))


def test_comment_role_writes_on_read_only_page(win, srv):
    from hlidskjalf.store import store
    srv.add_foreign(200, "Projekt", role="comment")
    store.refresh()
    idle()
    v = _open(win, 200)
    assert v.editor.locked
    row = next(r for r in v.kit.rows() if r.bid == "f200")
    add = next(w for w in _walk(row.cslot) if isinstance(w, Gtk.Button))
    assert add.has_css_class("hk-cm-add")
    add.emit("clicked")
    p = v.cpanel
    pump(lambda: p.s.loaded, 5)
    idle()
    p.composer.buf.set_text("Schöne Ziele!")
    p.composer.send()
    assert pump(lambda: srv.comments and srv.comments[0]["block_id"] == "f200", 5)
    assert json.loads(srv.foreign[200]["body"])["blocks"][0]["id"] == "f200"


def test_rate_limit_message(win, srv):
    shown = []
    win.toast = lambda m: shown.append(str(m))
    srv.comment_429 = True
    v = _open(win, 1)
    p = _panel(win, v)
    p.composer.buf.set_text("Noch einer")
    p.composer.send()
    assert pump(lambda: shown, 5)
    assert shown[-1] == "Not sent: Zu viele Kommentare in kurzer Zeit – bitte kurz warten."
    assert p.composer.text() == "Noch einer" and p.composer.ok.get_sensitive()          # Text bleibt stehen


def test_composer_limit_and_counter(app):
    from hlidskjalf.comments import Composer
    sent = []
    c = Composer("Comment on this block", "Comment", lambda t, fin: (sent.append(t), fin(True)), maxlen=2000)
    assert not c.ok.get_sensitive() and c.ph.get_visible()
    c.buf.set_text("x" * 1850)
    assert c.count.get_label() == "1850 of 2000"
    c.buf.set_text("y" * 2100)
    assert len(c.text()) == 2000
    c.send()
    assert sent == ["y" * 2000] and c.text() == ""
